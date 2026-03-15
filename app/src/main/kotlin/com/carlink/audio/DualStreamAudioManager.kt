package com.carlink.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import com.carlink.platform.AudioConfig
import com.carlink.protocol.StreamPurpose
import com.carlink.util.LogCallback
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Audio stream type identifiers from CPC200-CCPA protocol.
 */
object AudioStreamType {
    const val MEDIA = 1 // Music, podcasts, Siri, phone, alerts (all non-nav)
    const val NAVIGATION = 2 // Turn-by-turn directions
    const val PHONE_CALL = 3 // Protocol value (adapter sends as type=1)
    const val SIRI = 4 // Protocol value (adapter sends as type=1)
}

/**
 * DualStreamAudioManager - Two-stream audio: Media + Navigation.
 *
 * PURPOSE:
 * Provides stable, uninterrupted audio playback for CarPlay/Android Auto projection
 * using separate AudioTracks for media and navigation, with ring buffers to absorb
 * USB packet jitter.
 *
 * ARCHITECTURE:
 * ```
 * USB Thread (non-blocking)
 *     │
 *     ├──► Media Ring Buffer (500ms) ──► Media AudioTrack
 *     │                                    (USAGE_MEDIA → CarAudioContext.MUSIC)
 *     │
 *     └──► Nav Ring Buffer (200ms) ──► Nav AudioTrack
 *                                        (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE → CarAudioContext.NAVIGATION)
 *     │
 *     └──► Playback Thread (THREAD_PRIORITY_URGENT_AUDIO)
 *             reads from both buffers, writes to AudioTracks
 * ```
 *
 * NOTE: CPC200-CCPA sends Siri, Phone Call, and Alert audio all as audio_type=1 (MEDIA).
 * Only Navigation uses audio_type=2. All non-nav audio routes to the media track.
 * Microphone lifecycle for Siri/Phone is managed by VoiceMode in CarlinkManager.
 *
 * KEY FEATURES:
 * - Lock-free ring buffers absorb 500-1200ms packet gaps from adapter
 * - Non-blocking writes from USB thread
 * - Dedicated high-priority playback thread
 * - Volume control per stream (ducking support for nav over media)
 * - Automatic format switching per stream
 *
 * THREAD SAFETY:
 * - writeAudio() called from USB thread (non-blocking)
 * - Playback thread handles AudioTrack writes
 * - Volume/ducking can be called from any thread
 */
class DualStreamAudioManager(
    private val context: Context,
    private val logCallback: LogCallback,
    private val audioConfig: AudioConfig = AudioConfig.DEFAULT,
) {
    private val systemAudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Media pool: pre-created AudioTrack+buffer per (format, purpose) key
    private data class SlotKey(val format: AudioFormatConfig, val purpose: StreamPurpose)

    private val mediaSlots = mutableMapOf<SlotKey, MediaSlot>()

    @Volatile private var activeMediaSlot: MediaSlot? = null

    @Volatile private var activeMediaKey: SlotKey? = null

    // Navigation: single track (format doesn't change mid-session)
    @Volatile private var navTrack: AudioTrack? = null

    @Volatile private var navBuffer: AudioRingBuffer? = null

    @Volatile private var navFormat: AudioFormatConfig? = null

    private var mediaVolume: Float = 1.0f
    private var navVolume: Float = 1.0f
    private var isDucked: Boolean = false
    private var duckLevel: Float = 0.2f

    // AudioFocus state machine
    private val activeFocusRequests = mutableMapOf<StreamPurpose, AudioFocusRequest>()
    private var focusDuckLevel: Float = 1.0f // system-driven duck (1.0 = none)

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val (newLevel, changeStr) = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> 1.0f to "GAIN"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.2f to "LOSS_TRANSIENT_CAN_DUCK"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> 0.0f to "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS -> 0.0f to "LOSS"
            else -> focusDuckLevel to "UNKNOWN($focusChange)"
        }
        logDebug("[AUDIO_FOCUS] FocusChange: $changeStr → focusDuckLevel=${(newLevel * 100).toInt()}%")
        focusDuckLevel = newLevel
        applyEffectiveVolume()
    }

    private var playbackThread: AudioPlaybackThread? = null
    private val isRunning = AtomicBoolean(false)

    private var navUnderruns: Int = 0
    private var writeCount: Long = 0
    private var zeroPacketsFiltered: Long = 0

    // 30s aggregate stats counters (per-window deltas, reset each interval)
    private val statsInterval = 30_000L
    private var lastStatsTime = 0L
    private val windowMediaRx = AtomicLong(0)
    private val windowNavRx = AtomicLong(0)
    private val windowMediaPlayed = AtomicLong(0)
    private val windowNavPlayed = AtomicLong(0)
    private val windowMediaResiduals = AtomicLong(0)
    private val windowNavResiduals = AtomicLong(0)
    private val windowZeroFiltered = AtomicLong(0)
    private var prevNavOverflow = 0
    private var prevNavUnderruns = 0

    private val bufferMultiplier = audioConfig.bufferMultiplier
    private val prefillThresholdMs = audioConfig.prefillThresholdMs
    private val underrunRecoveryThreshold = 10
    private val minBufferLevelMs = 50 // Reduced from 100ms - USB P99 jitter only 7ms

    @Volatile private var navStarted = false

    @Volatile private var navStartTime: Long = 0

    @Volatile private var navPendingPlay = false

    /**
     * Per-format media slot: owns AudioTrack + ring buffer + playback state.
     * Pool keyed by AudioFormatConfig (data class equality: FORMAT_2==FORMAT_4 → same slot).
     */
    private class MediaSlot(
        val format: AudioFormatConfig,
        val purpose: StreamPurpose,
        var track: AudioTrack?,
        val buffer: AudioRingBuffer,
        var started: Boolean = false,
        var pendingPlay: Boolean = false,
        var residualOffset: Int = 0,
        var residualCount: Int = 0,
        var underruns: Int = 0,
        var draining: Boolean = false,
        val tempBuffer: ByteArray,
        // Stats delta tracking
        var prevOverflow: Int = 0,
        var prevUnderruns: Int = 0,
    )

    // Minimum playback duration before allowing stop (fixes premature cutoff - Sessions 1-2)
    private val minNavPlayDurationMs = 300

    // Skip warmup noise: mixed 0xFFFF/0x0000/0xFEFF patterns for ~200-400ms after NavStart
    private val navWarmupSkipMs = 250

    private var navEndMarkersDetected: Long = 0
    private var navWarmupFramesSkipped: Long = 0

    // Flush after consecutive zero packets to prevent resampling noise on GM AAOS
    private var consecutiveNavZeroPackets: Int = 0
    private val navZeroFlushThreshold = 3

    // Drop nav packets after NAVI_STOP (~2s silence before NAVI_COMPLETE per USB captures)
    @Volatile private var navStopped = false

    private val lock = Any()

    /**
     * Initialize the audio manager and start playback thread.
     */
    fun initialize(): Boolean {
        synchronized(lock) {
            if (isRunning.get()) {
                log("[AUDIO] Already initialized")
                return true
            }

            try {
                isRunning.set(true)

                // Start playback thread
                playbackThread = AudioPlaybackThread().also { it.start() }

                val perfModeStr =
                    if (audioConfig.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) {
                        "LOW_LATENCY"
                    } else {
                        "NONE"
                    }
                log(
                    "[AUDIO] DualStreamAudioManager initialized with config: " +
                        "sampleRate=${audioConfig.sampleRate}Hz, " +
                        "bufferMult=${audioConfig.bufferMultiplier}x, " +
                        "perfMode=$perfModeStr, " +
                        "prefill=${audioConfig.prefillThresholdMs}ms",
                )
                return true
            } catch (e: Exception) {
                log("[AUDIO] ERROR: Failed to initialize: ${e.message}")
                isRunning.set(false)
                return false
            }
        }
    }

    /**
     * Check if audio data is zero-filled (adapter issue).
     * Real audio has dithering noise even during silence.
     */
    private fun isZeroFilledAudio(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        if (length < 16) return false
        val end = offset + length

        val positions =
            intArrayOf(
                offset,
                offset + ((length * 0.25).toInt() and 0x7FFFFFFE),
                offset + ((length * 0.5).toInt() and 0x7FFFFFFE),
                offset + ((length * 0.75).toInt() and 0x7FFFFFFE),
                offset + ((length - 8).coerceAtLeast(0) and 0x7FFFFFFE),
            )

        for (pos in positions) {
            if (pos + 4 > end) continue
            if (data[pos] != 0.toByte() || data[pos + 1] != 0.toByte() ||
                data[pos + 2] != 0.toByte() || data[pos + 3] != 0.toByte()
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Detect nav end marker (solid 0xFFFF). Adapter sends before NaviStop.
     * Distinct from warmup noise (mixed 0xFFFF/0x0000/0xFEFF patterns).
     * When detected: flush buffers for clean next NaviStart.
     */
    private fun isNavEndMarker(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        if (length < 32) return false
        val end = offset + length

        // Sample 4 positions - all must be 0xFFFF for end marker
        val positions =
            intArrayOf(
                offset,
                offset + ((length * 0.25).toInt() and 0x7FFFFFFE),
                offset + ((length * 0.5).toInt() and 0x7FFFFFFE),
                offset + ((length * 0.75).toInt() and 0x7FFFFFFE),
            )

        for (pos in positions) {
            if (pos + 3 >= end) continue
            if (data[pos] != 0xFF.toByte() || data[pos + 1] != 0xFF.toByte() ||
                data[pos + 2] != 0xFF.toByte() || data[pos + 3] != 0xFF.toByte()
            ) {
                return false
            }
        }

        return true
    }

    /**
     * Detect warmup noise (near-silence mix of 0xFFFF/0x0000/0xFEFF).
     * Appears ~200-400ms after NavStart. Causes distortion if played.
     */
    private fun isWarmupNoise(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        if (length < 32) return false
        val end = offset + length

        val sampleCount = 8
        var nearSilenceCount = 0

        for (i in 0 until sampleCount) {
            val pos = offset + (((length * i) / sampleCount) and 0x7FFFFFFE)
            if (pos + 1 >= end) continue

            val sample = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            val signedSample = if (sample >= 32768) sample - 65536 else sample

            if (signedSample in -258..2) nearSilenceCount++
        }

        return nearSilenceCount >= 6
    }

    /** Flush nav buffers when end marker (0xFFFF) detected. Ensures clean next prompt. */
    private fun flushNavBuffers() {
        synchronized(lock) {
            val discardedMs = navBuffer?.fillLevelMs() ?: 0
            navBuffer?.clear()

            // flush() requires paused state; track resumes via ensureNavTrack on next audio
            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
            }

            navEndMarkersDetected++
            log("[AUDIO] Nav end marker detected, buffers flushed (total: $navEndMarkersDetected)")
        }
    }

    /** Write audio to stream buffer (non-blocking, USB thread). Returns bytes written. */
    fun writeAudio(
        data: ByteArray,
        dataOffset: Int,
        dataLength: Int,
        audioType: Int,
        decodeType: Int,
        purpose: StreamPurpose = StreamPurpose.MEDIA,
    ): Int {
        if (!isRunning.get()) return -1

        // Navigation handles zeros separately for consecutive tracking and buffer flush
        val isZeroFilled = isZeroFilledAudio(data, dataOffset, dataLength)
        if (isZeroFilled && audioType != AudioStreamType.NAVIGATION) {
            zeroPacketsFiltered++
            windowZeroFiltered.incrementAndGet()
            return 0
        }

        writeCount++

        // Route: Navigation → nav track, everything else → media track
        // CPC200-CCPA sends all non-nav audio (media, Siri, phone, alert) as audioType=1
        return when (audioType) {
            AudioStreamType.NAVIGATION -> {
                // Drop packets after NAVI_STOP (~2s silence before NAVI_COMPLETE per USB captures)
                if (navStopped) return 0

                // Check end marker before track creation (navStartTime may be 0)
                if (isNavEndMarker(data, dataOffset, dataLength)) {
                    flushNavBuffers()
                    consecutiveNavZeroPackets = 0
                    return 0
                }

                // Consecutive zeros cause resampling noise on GM AAOS (44.1kHz→48kHz)
                if (isZeroFilled) {
                    consecutiveNavZeroPackets++
                    zeroPacketsFiltered++
                    windowZeroFiltered.incrementAndGet()
                    if (consecutiveNavZeroPackets >= navZeroFlushThreshold) {
                        flushNavBuffers()
                        log(
                            "[AUDIO_FILTER] Nav buffer flushed after " +
                                "$consecutiveNavZeroPackets consecutive zero packets",
                        )
                        consecutiveNavZeroPackets = 0
                    }
                    return 0
                }

                consecutiveNavZeroPackets = 0
                ensureNavTrack(decodeType)
                val timeSinceStart = System.currentTimeMillis() - navStartTime

                // Skip warmup noise in first ~250ms
                if (timeSinceStart < navWarmupSkipMs && isWarmupNoise(data, dataOffset, dataLength)) {
                    navWarmupFramesSkipped++
                    if (navWarmupFramesSkipped == 1L || navWarmupFramesSkipped % 10 == 0L) {
                        log(
                            "[AUDIO] Skipped nav warmup frame " +
                                "(${timeSinceStart}ms since start, total: $navWarmupFramesSkipped)",
                        )
                    }
                    return 0
                }

                windowNavRx.incrementAndGet()
                val bytesWritten = navBuffer?.write(data, dataOffset, dataLength) ?: -1
                if (bytesWritten > 0) {
                    navPackets++
                }
                bytesWritten
            }

            else -> {
                // All non-nav audio → purpose-specific media track
                windowMediaRx.incrementAndGet()
                ensureMediaTrack(decodeType, purpose)
                activeMediaSlot?.buffer?.write(data, dataOffset, dataLength) ?: -1
            }
        }
    }

    /** Set media ducking (Len=16 volume packets from adapter). */
    fun setDucking(targetVolume: Float) {
        synchronized(lock) {
            isDucked = targetVolume < 1.0f
            duckLevel = targetVolume.coerceIn(0.0f, 1.0f)

            applyEffectiveVolume()

            if (isDucked) {
                log("[AUDIO] Media ducked to ${(duckLevel * 100).toInt()}%")
            } else {
                log("[AUDIO] Media volume restored to ${(mediaVolume * 100).toInt()}%")
            }
        }
    }

    /** Combine adapter ducking and system focus ducking, apply to MEDIA-purpose slots only. */
    private fun applyEffectiveVolume() {
        val effectiveVolume = mediaVolume * minOf(duckLevel, focusDuckLevel)
        for ((key, slot) in mediaSlots) {
            if (key.purpose == StreamPurpose.MEDIA) {
                slot.track?.setVolume(effectiveVolume)
            }
            // Non-media purpose slots (PHONE_CALL, SIRI, etc.) stay at full volume
        }
        logDebug(
            "[AUDIO_FOCUS] Volume: effective=${(effectiveVolume * 100).toInt()}% " +
                "(media=${(mediaVolume * 100).toInt()}% adapterDuck=${(duckLevel * 100).toInt()}% " +
                "focusDuck=${(focusDuckLevel * 100).toInt()}%)",
        )
    }

    /** Request AudioFocus for a stream purpose. */
    fun onPurposeChanged(purpose: StreamPurpose) {
        synchronized(lock) {
            val gainType = when (purpose) {
                StreamPurpose.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
                StreamPurpose.PHONE_CALL -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                StreamPurpose.SIRI -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                StreamPurpose.ALERT, StreamPurpose.RINGTONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                StreamPurpose.NAVIGATION -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            }

            val (usage, contentType) = purposeToAttributes(purpose)

            val focusRequest = AudioFocusRequest.Builder(gainType)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()

            val result = systemAudioManager.requestAudioFocus(focusRequest)
            activeFocusRequests[purpose] = focusRequest

            val resultStr = when (result) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "GRANTED"
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "DELAYED"
                AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "FAILED"
                else -> "UNKNOWN($result)"
            }
            val gainStr = when (gainType) {
                AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "GAIN_TRANSIENT"
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "GAIN_TRANSIENT_MAY_DUCK"
                else -> "UNKNOWN($gainType)"
            }
            logDebug("[AUDIO_FOCUS] Request $purpose: gain=$gainStr → $resultStr")
        }
    }

    /** Abandon AudioFocus for a stream purpose. */
    fun onPurposeEnded(purpose: StreamPurpose) {
        synchronized(lock) {
            activeFocusRequests.remove(purpose)?.let { request ->
                systemAudioManager.abandonAudioFocusRequest(request)
                logDebug("[AUDIO_FOCUS] Abandon $purpose")
            }
        }
    }

    private fun purposeToAttributes(purpose: StreamPurpose): Pair<Int, Int> = when (purpose) {
        StreamPurpose.MEDIA -> Pair(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC)
        StreamPurpose.PHONE_CALL -> Pair(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.CONTENT_TYPE_SPEECH)
        StreamPurpose.SIRI -> Pair(AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH)
        StreamPurpose.ALERT -> Pair(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AudioAttributes.CONTENT_TYPE_MUSIC)
        StreamPurpose.RINGTONE -> Pair(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AudioAttributes.CONTENT_TYPE_MUSIC)
        StreamPurpose.NAVIGATION -> Pair(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.CONTENT_TYPE_SPEECH)
    }

    // ========== Stream Stop Methods ==========
    //
    // These methods pause individual AudioTracks when their corresponding stream ends
    // (e.g., AudioNaviStop command received). This is critical for AAOS volume control:
    //
    // AAOS CarAudioService determines which volume group to adjust based on "active players"
    // (AudioTracks in PLAYSTATE_PLAYING). If a track remains in PLAYING state after its
    // audio stream ends, AAOS continues to prioritize that context for volume control.
    //
    // Example: Nav track left in PLAYING state after nav prompt ends causes volume keys
    // to control NAVIGATION volume instead of MEDIA volume, appearing "stuck".
    //
    // Using pause() instead of stop() preserves the buffer and allows quick resume
    // when the stream restarts, avoiding audio glitches.

    /** Reset nav stopped state (AUDIO_NAVI_START). Allows packets after a prior NAVI_STOP. */
    fun onNavStarted() {
        navStopped = false
        logDebug("[AUDIO_TRACK] onNavStarted() - accepting nav packets")
    }

    /** Stop accepting nav packets (AUDIO_NAVI_STOP). Track cleanup on NAVI_COMPLETE. */
    fun onNavStopped() {
        log("[NAV_STOP] onNavStopped() called - will reject incoming nav packets")
        navStopped = true
    }

    /** Pause nav track (AUDIO_NAVI_COMPLETE). Enforces min duration (Sessions 1-2 fix). */
    fun stopNavTrack() {
        log("[NAV_STOP] stopNavTrack() called")

        synchronized(lock) {
            val trackStateStr =
                when (val trackState = navTrack?.playState) {
                    AudioTrack.PLAYSTATE_PLAYING -> "PLAYING"
                    AudioTrack.PLAYSTATE_PAUSED -> "PAUSED"
                    AudioTrack.PLAYSTATE_STOPPED -> "STOPPED"
                    else -> "null/unknown($trackState)"
                }
            log("[NAV_STOP] Track state: $trackStateStr, navStarted=$navStarted, navStartTime=$navStartTime")

            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val playDuration = System.currentTimeMillis() - navStartTime
                    val bufferLevel = navBuffer?.fillLevelMs() ?: 0
                    val bytesRead = navBuffer?.totalBytesRead ?: 0

                    log(
                        "[NAV_STOP] playDuration=${playDuration}ms, bufferLevel=${bufferLevel}ms, " +
                            "bytesRead=$bytesRead, packets=$navPackets, underruns=$navUnderruns",
                    )

                    if (playDuration < minNavPlayDurationMs && bufferLevel > 50) {
                        log(
                            "[NAV_STOP] Ignoring premature stop after ${playDuration}ms " +
                                "(min=${minNavPlayDurationMs}ms), buffer has ${bufferLevel}ms data",
                        )
                        return
                    }

                    // Secondary flush (primary is on 0xFFFF end marker)
                    track.pause()
                    track.flush()
                    val discardedMs = navBuffer?.fillLevelMs() ?: 0
                    navBuffer?.clear()
                    log(
                        "[NAV_STOP] Nav track paused+flushed after ${playDuration}ms - " +
                            "discarded=${discardedMs}ms, packets=$navPackets, underruns=$navUnderruns",
                    )
                } else {
                    log("[NAV_STOP] Track not playing, skipping pause (state=$trackStateStr)")
                }
            } ?: run {
                log("[NAV_STOP] navTrack is null, nothing to stop")
            }

            navStarted = false
            navPendingPlay = false
            navStopped = false // Reset stopped state for next nav session
            navPackets = 0 // Reset packet counter for next nav prompt
            navUnderruns = 0 // Reset underrun counter for next nav prompt
            // Abandon nav focus
            onPurposeEnded(StreamPurpose.NAVIGATION)
            logDebug("[AUDIO_FOCUS] Nav focus abandoned (stopNavTrack)")
        }
    }

    private var navPackets: Long = 0

    /** Stop playback and release all resources. */
    fun release() {
        synchronized(lock) {
            log("[AUDIO] Releasing DualStreamAudioManager")

            isRunning.set(false)

            playbackThread?.interrupt()
            try {
                playbackThread?.join(1000)
            } catch (_: InterruptedException) {
            }
            playbackThread = null

            releaseAllMediaSlots()
            releaseNavTrack()

            // Abandon all AudioFocus requests
            for ((purpose, request) in activeFocusRequests) {
                systemAudioManager.abandonAudioFocusRequest(request)
                logDebug("[AUDIO_FOCUS] Abandon $purpose (release)")
            }
            activeFocusRequests.clear()

            navBuffer?.clear()
            navBuffer = null

            log("[AUDIO] DualStreamAudioManager released")
        }
    }

    // ========== Private Methods ==========

    private fun ensureMediaTrack(decodeType: Int, purpose: StreamPurpose = StreamPurpose.MEDIA) {
        val format = AudioFormats.fromDecodeType(decodeType)
        val key = SlotKey(format, purpose)

        synchronized(lock) {
            val currentSlot = activeMediaSlot

            // Same format+purpose fast-path: resume paused track if needed (Siri tone fix)
            if (currentSlot != null && currentSlot.format == format && currentSlot.purpose == purpose) {
                val track = currentSlot.track
                if (track != null && track.playState == AudioTrack.PLAYSTATE_PAUSED && !currentSlot.pendingPlay) {
                    currentSlot.pendingPlay = true
                    currentSlot.started = false
                    log("[AUDIO] Resumed paused media track (same format ${format.sampleRate}Hz purpose=$purpose)")
                }
                return
            }

            // Format or purpose switch: drain current slot if playing with buffered data
            if (currentSlot != null) {
                val oldKey = SlotKey(currentSlot.format, currentSlot.purpose)
                val track = currentSlot.track
                if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    currentSlot.buffer.availableForRead() > 0
                ) {
                    currentSlot.draining = true
                    logDebug(
                        "[AUDIO_SLOT] Switch: $oldKey → $key draining=true",
                    )
                } else {
                    // Nothing to drain, pause immediately
                    try {
                        if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.pause()
                        }
                    } catch (_: Exception) {
                    }
                    currentSlot.draining = false
                    currentSlot.started = false
                    currentSlot.pendingPlay = false
                }
            }

            // Get or create target slot (lazy creation per format+purpose)
            val targetSlot =
                mediaSlots.getOrPut(key) {
                    val buffer =
                        AudioRingBuffer(
                            capacityMs = audioConfig.mediaBufferCapacityMs,
                            sampleRate = format.sampleRate,
                            channels = format.channelCount,
                        )
                    val chunkSize = format.sampleRate * format.channelCount * 2 * 5 / 1000
                    val track = createAudioTrack(format, purpose)
                    val slot =
                        MediaSlot(
                            format = format,
                            purpose = purpose,
                            track = track,
                            buffer = buffer,
                            tempBuffer = ByteArray(chunkSize * 20),
                        )
                    logDebug(
                        "[AUDIO_TRACK] Created $purpose track: ${format.sampleRate}Hz " +
                            "${format.channelCount}ch id=${track?.audioSessionId}",
                    )
                    slot
                }

            // Clear stale data and reset state for activation
            targetSlot.buffer.clear()
            targetSlot.pendingPlay = true
            targetSlot.started = false
            targetSlot.residualCount = 0
            targetSlot.residualOffset = 0
            targetSlot.draining = false

            // Recreate dead track (ERROR_DEAD_OBJECT recovery)
            if (targetSlot.track == null) {
                targetSlot.track = createAudioTrack(format, purpose)
                log("[AUDIO] Pool: recreated dead ${format.sampleRate}Hz/${format.channelCount}ch/$purpose track")
            }

            activeMediaSlot = targetSlot
            activeMediaKey = key

            val prevRate = currentSlot?.format?.sampleRate ?: 0
            val prevPurpose = currentSlot?.purpose ?: StreamPurpose.MEDIA
            val poolHit = mediaSlots.size > 1
            log(
                "[AUDIO] Media slot switch: ${prevRate}Hz/$prevPurpose -> " +
                    "${format.sampleRate}Hz/${format.channelCount}ch/$purpose (${if (poolHit) "pool hit" else "new"})",
            )
        }
    }

    private fun ensureNavTrack(decodeType: Int) {
        val format = AudioFormats.fromDecodeType(decodeType)

        synchronized(lock) {
            navStopped = false

            // Resume paused track with flush (secondary to end marker flush)
            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED && navFormat == format && !navPendingPlay) {
                    val discardedMs = navBuffer?.fillLevelMs() ?: 0
                    track.flush()
                    navBuffer?.clear()
                    navPendingPlay = true
                    navStarted = false
                    navStartTime = System.currentTimeMillis()
                    // Request nav focus on resume
                    onPurposeChanged(StreamPurpose.NAVIGATION)
                    log("[AUDIO] Resumed paused nav track with flush (same format ${format.sampleRate}Hz)")
                    return
                }
            }

            if (navFormat != format) {
                log("[AUDIO] Nav format change: ${navFormat?.sampleRate ?: 0}Hz -> ${format.sampleRate}Hz")
                releaseNavTrack()
                navFormat = format

                navBuffer =
                    AudioRingBuffer(
                        capacityMs = audioConfig.navBufferCapacityMs,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                navTrack = createAudioTrack(format, StreamPurpose.NAVIGATION)
                navPendingPlay = true
                navStartTime = System.currentTimeMillis() // Track start time for min duration
                // Request nav focus on new track creation
                onPurposeChanged(StreamPurpose.NAVIGATION)
            }
        }
    }

    /**
     * Create an AudioTrack with per-purpose AudioAttributes for AAOS CarAudioContext mapping.
     *
     * AAOS CarAudioContext Mapping:
     * - USAGE_MEDIA → MUSIC context
     * - USAGE_VOICE_COMMUNICATION → CALL context
     * - USAGE_ASSISTANT → VOICE_COMMAND context
     * - USAGE_NOTIFICATION_RINGTONE → CALL_RING context
     * - USAGE_ASSISTANCE_NAVIGATION_GUIDANCE → NAVIGATION context
     */
    private fun createAudioTrack(
        format: AudioFormatConfig,
        purpose: StreamPurpose,
    ): AudioTrack? {
        try {
            val minBufferSize =
                AudioTrack.getMinBufferSize(
                    format.sampleRate,
                    format.channelConfig,
                    format.encoding,
                )

            if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                log("[AUDIO] ERROR: Invalid buffer size for ${format.sampleRate}Hz")
                return null
            }

            val bufferSize = minBufferSize * bufferMultiplier

            val (usage, contentType) = purposeToAttributes(purpose)

            val audioAttributes =
                AudioAttributes
                    .Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()

            val audioFormat =
                AudioFormat
                    .Builder()
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(format.channelConfig)
                    .setEncoding(format.encoding)
                    .build()

            // Performance mode: LOW_LATENCY (default) or NONE (GM AAOS)
            val track =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(audioConfig.performanceMode)
                    .build()

            val volume =
                when (purpose) {
                    StreamPurpose.NAVIGATION -> navVolume
                    StreamPurpose.MEDIA -> if (isDucked) mediaVolume * minOf(duckLevel, focusDuckLevel) else mediaVolume
                    else -> 1.0f // Non-media purpose slots stay at full volume
                }
            track.setVolume(volume)

            log(
                "[AUDIO] Created $purpose AudioTrack: ${format.sampleRate}Hz " +
                    "${format.channelCount}ch buffer=${bufferSize}B usage=$usage id=${track.audioSessionId}",
            )

            return track
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to create AudioTrack: ${e.message}")
            return null
        }
    }

    private fun releaseAllMediaSlots() {
        for (slot in mediaSlots.values) {
            try {
                slot.track?.let { track ->
                    try {
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.stop()
                        }
                    } catch (e: Exception) {
                        log("[AUDIO] WARN: Failed to stop media track (${slot.format.sampleRate}Hz): ${e.message}")
                    }
                    track.release()
                }
            } catch (e: Exception) {
                log("[AUDIO] ERROR: Failed to release media track (${slot.format.sampleRate}Hz): ${e.message}")
            }
            slot.buffer.clear()
        }
        mediaSlots.clear()
        activeMediaSlot = null
        activeMediaKey = null
    }

    private fun releaseNavTrack() {
        try {
            navTrack?.let { track ->
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                } catch (e: Exception) {
                    log("[AUDIO] WARN: Failed to stop nav track: ${e.message}")
                }
                track.release()
            }
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to release nav track: ${e.message}")
        }
        navTrack = null
        navFormat = null
        navStarted = false
        navPendingPlay = false
    }

    private fun log(message: String) {
        logCallback.log("AUDIO", message)
    }

    private fun logPerf(message: String) {
        logCallback.logPerf("AUDIO_PERF", message)
    }

    private fun logDebug(message: String) {
        logCallback.log("AUDIO_DEBUG", message)
    }

    /** Playback thread (URGENT_AUDIO priority). Separate buffers per stream for safety. */
    private inner class AudioPlaybackThread : Thread("AudioPlayback") {
        // Nav temp buffer (sized for max nav format: 48kHz stereo, 100ms bulk)
        private val navTempBuffer = ByteArray(audioConfig.sampleRate * 2 * 2 / 10)

        // Residual tracking for nav partial WRITE_NON_BLOCKING returns
        private var navResidualOffset = 0
        private var navResidualCount = 0

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            log("[AUDIO] Playback thread started with URGENT_AUDIO priority")

            while (isRunning.get() && !isInterrupted) {
                try {
                    var didWork = false

                    // === Media: process all pool slots ===
                    val slotsSnapshot: List<MediaSlot>
                    val currentActive: MediaSlot?
                    synchronized(lock) {
                        slotsSnapshot = mediaSlots.values.toList()
                        currentActive = activeMediaSlot
                    }

                    for (slot in slotsSnapshot) {
                        val track = slot.track ?: continue
                        val isActive = slot === currentActive
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING &&
                            !slot.pendingPlay && !slot.draining
                        ) {
                            continue
                        }

                        val buffer = slot.buffer
                        val currentFillMs = buffer.fillLevelMs()
                        val effectiveMinBufMs = if (slot.draining) 0 else minBufferLevelMs

                        // Pre-fill before first playback (active slot only)
                        if (!slot.started) {
                            if (isActive) {
                                if (currentFillMs < prefillThresholdMs) continue
                                slot.started = true
                                if (slot.pendingPlay) {
                                    var preloaded = 0
                                    while (buffer.fillLevelMs() > effectiveMinBufMs) {
                                        val avail = buffer.availableForRead()
                                        if (avail <= 0) break
                                        val bytesRead =
                                            buffer.read(
                                                slot.tempBuffer,
                                                0,
                                                minOf(avail, slot.tempBuffer.size),
                                            )
                                        if (bytesRead <= 0) break
                                        val written =
                                            track.write(
                                                slot.tempBuffer,
                                                0,
                                                bytesRead,
                                                AudioTrack.WRITE_NON_BLOCKING,
                                            )
                                        if (written <= 0) break
                                        windowMediaPlayed.addAndGet(written.toLong())
                                        preloaded += written
                                        if (written < bytesRead) {
                                            slot.residualOffset = written
                                            slot.residualCount = bytesRead - written
                                            break
                                        }
                                    }
                                    track.play()
                                    slot.pendingPlay = false
                                    slot.underruns = track.underrunCount
                                }
                                log(
                                    "[AUDIO] Media pre-fill complete " +
                                        "(${slot.format.sampleRate}Hz/${slot.format.channelCount}ch): " +
                                        "${currentFillMs}ms buffered, starting playback",
                                )
                            } else if (slot.draining) {
                                // Draining slot with no data: complete immediately
                                if (buffer.availableForRead() == 0 && slot.residualCount == 0) {
                                    slot.draining = false
                                    try {
                                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                                    } catch (_: Exception) {
                                    }
                                    log("[AUDIO] Drain complete (${slot.format.sampleRate}Hz/${slot.purpose}), track paused (empty)")
                                    continue
                                }
                            } else {
                                continue
                            }
                        }

                        // Retry residual from prior partial WRITE_NON_BLOCKING
                        if (slot.residualCount > 0) {
                            val written =
                                track.write(
                                    slot.tempBuffer,
                                    slot.residualOffset,
                                    slot.residualCount,
                                    AudioTrack.WRITE_NON_BLOCKING,
                                )
                            if (written < 0) {
                                slot.residualCount = 0
                                handleTrackError("MEDIA", written, slot)
                                continue
                            }
                            if (written > 0) {
                                windowMediaPlayed.addAndGet(written.toLong())
                                slot.residualOffset += written
                                slot.residualCount -= written
                                didWork = true
                            }
                        }

                        // Skip new reads while residual pending or below min buffer
                        if (slot.residualCount > 0 || currentFillMs <= effectiveMinBufMs) {
                            if (slot.draining && buffer.availableForRead() == 0 && slot.residualCount == 0) {
                                slot.draining = false
                                try {
                                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                                } catch (_: Exception) {
                                }
                                log("[AUDIO] Drain complete (${slot.format.sampleRate}Hz/${slot.purpose}), track paused")
                            }
                            continue
                        }

                        val available = buffer.availableForRead()
                        if (available > 0) {
                            val bytesPerMs =
                                slot.format.sampleRate * slot.format.channelCount * 2 / 1000
                            val maxReadableMs = currentFillMs - effectiveMinBufMs
                            val maxReadableBytes = maxReadableMs * bytesPerMs
                            val toRead =
                                minOf(
                                    available,
                                    slot.tempBuffer.size,
                                    maxReadableBytes.coerceAtLeast(0),
                                )

                            if (toRead > 0) {
                                val bytesRead = buffer.read(slot.tempBuffer, 0, toRead)
                                if (bytesRead > 0) {
                                    val written =
                                        track.write(
                                            slot.tempBuffer,
                                            0,
                                            bytesRead,
                                            AudioTrack.WRITE_NON_BLOCKING,
                                        )
                                    if (written < 0) {
                                        handleTrackError("MEDIA", written, slot)
                                        continue
                                    }
                                    if (written > 0) {
                                        windowMediaPlayed.addAndGet(written.toLong())
                                    }
                                    if (written < bytesRead) {
                                        slot.residualOffset = written
                                        slot.residualCount = bytesRead - written
                                        windowMediaResiduals.incrementAndGet()
                                    }
                                    if (written > 0) didWork = true
                                }
                            }
                        }

                        // Underrun detection
                        val underruns = track.underrunCount
                        if (underruns > slot.underruns) {
                            val newUnderruns = underruns - slot.underruns
                            slot.underruns = underruns
                            log(
                                "[AUDIO_UNDERRUN] Media underrun " +
                                    "(${slot.format.sampleRate}Hz): +$newUnderruns (total: $underruns)",
                            )
                            if (newUnderruns >= underrunRecoveryThreshold && buffer.fillLevelMs() < 50) {
                                slot.started = false
                                log(
                                    "[AUDIO_RECOVERY] Resetting media pre-fill " +
                                        "(${slot.format.sampleRate}Hz): $newUnderruns underruns, " +
                                        "buffer=${buffer.fillLevelMs()}ms",
                                )
                            }
                        }

                        // Check drain completion after processing
                        if (slot.draining && buffer.availableForRead() == 0 && slot.residualCount == 0) {
                            slot.draining = false
                            try {
                                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                            } catch (_: Exception) {
                            }
                            log("[AUDIO] Drain complete (${slot.format.sampleRate}Hz/${slot.purpose}), track paused")
                        }
                    }

                    // === Navigation: single track (unchanged) ===
                    navBuffer?.let { buffer ->
                        navTrack?.let { track ->
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING || navPendingPlay) {
                                val currentNavFillMs = buffer.fillLevelMs()

                                // Shorter pre-fill for nav (lower latency)
                                if (!navStarted) {
                                    if (currentNavFillMs < prefillThresholdMs / 2) return@let
                                    navStarted = true
                                    if (navPendingPlay) {
                                        // Pre-load AudioTrack buffer while STOPPED/PAUSED
                                        // before play() so AudioFlinger finds data on first pull
                                        val navMinBuf = minBufferLevelMs / 2
                                        var preloaded = 0
                                        while (buffer.fillLevelMs() > navMinBuf) {
                                            val avail = buffer.availableForRead()
                                            if (avail <= 0) break
                                            val bytesRead =
                                                buffer.read(
                                                    navTempBuffer,
                                                    0,
                                                    minOf(avail, navTempBuffer.size),
                                                )
                                            if (bytesRead <= 0) break
                                            val written =
                                                track.write(
                                                    navTempBuffer,
                                                    0,
                                                    bytesRead,
                                                    AudioTrack.WRITE_NON_BLOCKING,
                                                )
                                            if (written <= 0) break
                                            windowNavPlayed.addAndGet(written.toLong())
                                            preloaded += written
                                            if (written < bytesRead) {
                                                navResidualOffset = written
                                                navResidualCount = bytesRead - written
                                                break
                                            }
                                        }
                                        track.play()
                                        navPendingPlay = false
                                        navUnderruns = track.underrunCount
                                    }
                                    log(
                                        "[AUDIO] Nav pre-fill complete: " +
                                            "${currentNavFillMs}ms buffered, starting playback",
                                    )
                                }

                                // Retry residual from prior partial WRITE_NON_BLOCKING
                                if (navResidualCount > 0) {
                                    val written =
                                        track.write(
                                            navTempBuffer,
                                            navResidualOffset,
                                            navResidualCount,
                                            AudioTrack.WRITE_NON_BLOCKING,
                                        )
                                    if (written < 0) {
                                        navResidualCount = 0
                                        handleTrackError("NAV", written)
                                        return@let
                                    }
                                    if (written > 0) {
                                        navResidualOffset += written
                                        navResidualCount -= written
                                        windowNavPlayed.addAndGet(written.toLong())
                                        didWork = true
                                    }
                                }

                                val navMinBufferMs = minBufferLevelMs / 2
                                // Also skip new reads while residual is pending (AudioTrack full)
                                if (navResidualCount > 0 || currentNavFillMs <= navMinBufferMs) return@let

                                val available = buffer.availableForRead()
                                if (available > 0) {
                                    val bytesPerMs =
                                        (navFormat?.sampleRate ?: audioConfig.sampleRate) *
                                            (navFormat?.channelCount ?: 2) * 2 / 1000
                                    val maxReadableMs = currentNavFillMs - navMinBufferMs
                                    val maxReadableBytes = maxReadableMs * bytesPerMs
                                    val toRead = minOf(available, navTempBuffer.size, maxReadableBytes.coerceAtLeast(0))

                                    if (toRead > 0) {
                                        val bytesRead = buffer.read(navTempBuffer, 0, toRead)
                                        if (bytesRead > 0) {
                                            val written =
                                                track.write(
                                                    navTempBuffer,
                                                    0,
                                                    bytesRead,
                                                    AudioTrack.WRITE_NON_BLOCKING,
                                                )
                                            if (written < 0) {
                                                handleTrackError("NAV", written)
                                                return@let
                                            }
                                            if (written > 0) {
                                                windowNavPlayed.addAndGet(written.toLong())
                                            }
                                            if (written < bytesRead) {
                                                navResidualOffset = written
                                                navResidualCount = bytesRead - written
                                                windowNavResiduals.incrementAndGet()
                                            }
                                            if (written > 0) didWork = true
                                        }
                                    }
                                }

                                val underruns = track.underrunCount
                                if (underruns > navUnderruns) {
                                    val newUnderruns = underruns - navUnderruns
                                    navUnderruns = underruns
                                    log(
                                        "[AUDIO_UNDERRUN] Nav underrun detected: " +
                                            "+$newUnderruns (total: $underruns)",
                                    )

                                    // Recovery: If many underruns and buffer critically low, reset pre-fill
                                    if (newUnderruns >= underrunRecoveryThreshold && buffer.fillLevelMs() < 30) {
                                        navStarted = false
                                        log(
                                            "[AUDIO_RECOVERY] Resetting nav pre-fill due to " +
                                                "$newUnderruns underruns, buffer=${buffer.fillLevelMs()}ms",
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!didWork) sleep(5)

                    // 30s aggregate stats (gated by AUDIO_PERF tag)
                    val now = System.currentTimeMillis()
                    if (now - lastStatsTime >= statsInterval) {
                        val mRx = windowMediaRx.getAndSet(0)
                        val nRx = windowNavRx.getAndSet(0)
                        val mPlay = windowMediaPlayed.getAndSet(0)
                        val nPlay = windowNavPlayed.getAndSet(0)
                        val mRes = windowMediaResiduals.getAndSet(0)
                        val nRes = windowNavResiduals.getAndSet(0)
                        val zf = windowZeroFiltered.getAndSet(0)

                        // Media: per-slot stats
                        val statsActive = activeMediaSlot
                        var mOvf = 0
                        var mUrun = 0
                        val sb = StringBuilder()
                        sb
                            .append("Media[Rx:")
                            .append(mRx)
                            .append(" Play:")
                            .append(mPlay / 1024)
                            .append("KB")

                        if (slotsSnapshot.isNotEmpty()) {
                            sb
                                .append(" active:")
                                .append(statsActive?.format?.sampleRate ?: "none")
                                .append("Hz")
                            for (slot in slotsSnapshot) {
                                val fill = slot.buffer.fillLevelMs()
                                val state =
                                    when {
                                        slot === statsActive -> "ACTIVE"
                                        slot.draining -> "DRAIN"
                                        else -> "IDLE"
                                    }
                                sb
                                    .append(" ")
                                    .append(slot.format.sampleRate)
                                    .append("Hz:")
                                    .append(fill)
                                    .append("ms(")
                                    .append(state)
                                    .append(")")

                                val ovfDelta = slot.buffer.overflowCount - slot.prevOverflow
                                slot.prevOverflow = slot.buffer.overflowCount
                                mOvf += ovfDelta

                                val urunDelta = slot.underruns - slot.prevUnderruns
                                slot.prevUnderruns = slot.underruns
                                mUrun += urunDelta
                            }
                        } else {
                            sb.append(" Buf:0ms")
                        }

                        sb
                            .append(" Ovf:")
                            .append(mOvf)
                            .append(" Urun:")
                            .append(mUrun)
                        if (mRes > 0) sb.append(" Res:").append(mRes)

                        // Nav stats (unchanged)
                        val nFill = navBuffer?.fillLevelMs() ?: 0
                        val nOvf = (navBuffer?.overflowCount ?: 0) - prevNavOverflow
                        val nUrun = navUnderruns - prevNavUnderruns
                        prevNavOverflow = navBuffer?.overflowCount ?: 0
                        prevNavUnderruns = navUnderruns

                        sb
                            .append("] Nav[Rx:")
                            .append(nRx)
                            .append(" Play:")
                            .append(nPlay / 1024)
                            .append("KB")
                            .append(" Buf:")
                            .append(nFill)
                            .append("ms")
                            .append(" Ovf:")
                            .append(nOvf)
                            .append(" Urun:")
                            .append(nUrun)
                        if (nRes > 0) sb.append(" Res:").append(nRes)
                        sb.append("]")
                        if (zf > 0) sb.append(" Zero:").append(zf)
                        sb.append(" Duck:").append(if (isDucked) "Y" else "N")
                        sb.append(" FDuck:").append((focusDuckLevel * 100).toInt()).append("%")
                        val focusPurposes = activeFocusRequests.keys.joinToString(",")
                        if (focusPurposes.isNotEmpty()) sb.append(" Focus:[").append(focusPurposes).append("]")

                        logPerf(sb.toString())
                        lastStatsTime = now
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log("[AUDIO] Playback thread error: ${e.message}")
                }
            }

            log("[AUDIO] Playback thread stopped")
        }

        private fun handleTrackError(
            streamType: String,
            errorCode: Int,
            slot: MediaSlot? = null,
        ) {
            when (errorCode) {
                AudioTrack.ERROR_DEAD_OBJECT -> {
                    log("[AUDIO] $streamType AudioTrack dead, releasing for recreation")
                    synchronized(lock) {
                        if (slot != null) {
                            // Pool slot: null track, leave in pool for recreation on next activation
                            try {
                                slot.track?.release()
                            } catch (_: Exception) {
                            }
                            slot.track = null
                            slot.started = false
                            slot.pendingPlay = false
                            slot.draining = false
                            slot.residualCount = 0
                        } else {
                            // NAV path (unchanged)
                            when (streamType) {
                                "NAV" -> {
                                    try {
                                        navTrack?.release()
                                    } catch (_: Exception) {
                                    }
                                    navTrack = null
                                    navFormat = null
                                    navStarted = false
                                    navPendingPlay = false
                                }
                            }
                        }
                    }
                }

                AudioTrack.ERROR_INVALID_OPERATION -> {
                    log("[AUDIO] $streamType AudioTrack invalid operation")
                }

                else -> {
                    log("[AUDIO] $streamType AudioTrack write error: $errorCode")
                }
            }
        }
    }
}
