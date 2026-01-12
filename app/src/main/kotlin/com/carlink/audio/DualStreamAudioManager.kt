package com.carlink.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.carlink.BuildConfig
import com.carlink.platform.AudioConfig
import com.carlink.util.AudioDebugLogger
import com.carlink.util.LogCallback
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CARLINK_AUDIO"

/**
 * Audio stream type identifiers from CPC200-CCPA protocol.
 */
object AudioStreamType {
    const val MEDIA = 1 // Music, podcasts, etc.
    const val NAVIGATION = 2 // Turn-by-turn directions
    const val PHONE_CALL = 3 // Phone calls (exclusive)
    const val SIRI = 4 // Voice assistant (exclusive)
}

/**
 * DualStreamAudioManager - Handles multiple audio streams with AAOS CarAudioContext integration.
 *
 * PURPOSE:
 * Provides stable, uninterrupted audio playback for CarPlay/Android Auto projection
 * by using separate AudioTracks for each stream type, with ring buffers to absorb
 * USB packet jitter. Each stream uses the appropriate USAGE constant for proper
 * AAOS routing to CarAudioContext.
 *
 * ARCHITECTURE:
 * ```
 * USB Thread (non-blocking)
 *     │
 *     ├──► Media Ring Buffer (250ms) ──► Media AudioTrack
 *     │                                    (USAGE_MEDIA → CarAudioContext.MUSIC)
 *     │
 *     ├──► Nav Ring Buffer (120ms) ──► Nav AudioTrack
 *     │                                  (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE → CarAudioContext.NAVIGATION)
 *     │
 *     ├──► Voice Ring Buffer (150ms) ──► Voice AudioTrack
 *     │                                    (USAGE_ASSISTANT → CarAudioContext.VOICE_COMMAND)
 *     │
 *     └──► Call Ring Buffer (150ms) ──► Call AudioTrack
 *                                         (USAGE_VOICE_COMMUNICATION → CarAudioContext.CALL)
 *     │
 *     └──► Playback Thread (THREAD_PRIORITY_URGENT_AUDIO)
 *             reads from all buffers, writes to AudioTracks
 * ```
 *
 * AAOS CarAudioContext Mapping:
 * - USAGE_MEDIA (1) → MUSIC context
 * - USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (12) → NAVIGATION context
 * - USAGE_ASSISTANT (16) → VOICE_COMMAND context
 * - USAGE_VOICE_COMMUNICATION (2) → CALL context
 *
 * KEY FEATURES:
 * - Lock-free ring buffers absorb 500-1200ms packet gaps from adapter
 * - Non-blocking writes from USB thread
 * - Dedicated high-priority playback thread
 * - Independent volume control per stream (ducking support)
 * - Automatic format switching per stream
 * - Proper AAOS audio routing via CarAudioContext
 *
 * THREAD SAFETY:
 * - writeAudio() called from USB thread (non-blocking)
 * - Playback thread handles AudioTrack writes
 * - Volume/ducking can be called from any thread
 */
class DualStreamAudioManager(
    private val logCallback: LogCallback,
    private val audioConfig: AudioConfig = AudioConfig.DEFAULT,
) {
    // AudioTracks per stream (AAOS CarAudioContext mapping in class KDoc)
    private var mediaTrack: AudioTrack? = null
    private var navTrack: AudioTrack? = null
    private var voiceTrack: AudioTrack? = null
    private var callTrack: AudioTrack? = null

    // Ring buffers for USB jitter compensation
    private var mediaBuffer: AudioRingBuffer? = null
    private var navBuffer: AudioRingBuffer? = null
    private var voiceBuffer: AudioRingBuffer? = null
    private var callBuffer: AudioRingBuffer? = null

    private var mediaFormat: AudioFormatConfig? = null
    private var navFormat: AudioFormatConfig? = null
    private var voiceFormat: AudioFormatConfig? = null
    private var callFormat: AudioFormatConfig? = null

    private var mediaVolume: Float = 1.0f
    private var navVolume: Float = 1.0f
    private var voiceVolume: Float = 1.0f
    private var callVolume: Float = 1.0f
    private var isDucked: Boolean = false
    private var duckLevel: Float = 0.2f

    private var playbackThread: AudioPlaybackThread? = null
    private val isRunning = AtomicBoolean(false)

    private var startTime: Long = 0
    private var mediaUnderruns: Int = 0
    private var navUnderruns: Int = 0
    private var voiceUnderruns: Int = 0
    private var callUnderruns: Int = 0
    private var writeCount: Long = 0
    private var lastStatsLog: Long = 0
    private var zeroPacketsFiltered: Long = 0

    private val bufferMultiplier = audioConfig.bufferMultiplier
    private val playbackChunkSize = audioConfig.sampleRate * 2 * 2 * 5 / 1000
    private val prefillThresholdMs = audioConfig.prefillThresholdMs
    private val underrunRecoveryThreshold = 10
    private val minBufferLevelMs = 50 // Reduced from 100ms - USB P99 jitter only 7ms

    @Volatile private var mediaStarted = false

    @Volatile private var navStarted = false

    @Volatile private var navStartTime: Long = 0

    @Volatile private var voiceStartTime: Long = 0

    // Minimum playback duration before allowing stop (fixes premature cutoff - Sessions 1-2)
    private val minNavPlayDurationMs = 300
    private val minVoicePlayDurationMs = 200

    // Skip warmup noise: mixed 0xFFFF/0x0000/0xFEFF patterns for ~200-400ms after NavStart
    private val navWarmupSkipMs = 250

    private var navEndMarkersDetected: Long = 0
    private var navWarmupFramesSkipped: Long = 0

    // Flush after consecutive zero packets to prevent resampling noise on GM AAOS
    private var consecutiveNavZeroPackets: Int = 0
    private val navZeroFlushThreshold = 3

    // Drop nav packets after NAVI_STOP (~2s silence before NAVI_COMPLETE per USB captures)
    @Volatile private var navStopped = false

    @Volatile private var voiceStarted = false

    @Volatile private var callStarted = false

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
                startTime = System.currentTimeMillis()
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
     * Check if audio data is zero-filled (adapter issue). Skips 12-byte header.
     * Real audio has dithering noise even during silence.
     */
    private fun isZeroFilledAudio(data: ByteArray): Boolean {
        val headerSize = 12
        val audioDataSize = data.size - headerSize
        if (audioDataSize < 16) return false

        val positions =
            intArrayOf(
                headerSize,
                headerSize + ((audioDataSize * 0.25).toInt() and 0x7FFFFFFE),
                headerSize + ((audioDataSize * 0.5).toInt() and 0x7FFFFFFE),
                headerSize + ((audioDataSize * 0.75).toInt() and 0x7FFFFFFE),
                headerSize + ((audioDataSize - 8).coerceAtLeast(0) and 0x7FFFFFFE),
            )

        for (pos in positions) {
            if (pos + 4 > data.size) continue
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
    private fun isNavEndMarker(data: ByteArray): Boolean {
        val headerSize = 12
        val audioDataSize = data.size - headerSize
        if (audioDataSize < 32) return false

        // Sample 4 positions - all must be 0xFFFF for end marker
        val positions =
            intArrayOf(
                headerSize,
                headerSize + ((audioDataSize * 0.25).toInt() and 0x7FFFFFFE),
                headerSize + ((audioDataSize * 0.5).toInt() and 0x7FFFFFFE),
                headerSize + ((audioDataSize * 0.75).toInt() and 0x7FFFFFFE),
            )

        val sampleValues = StringBuilder()
        var allMatch = true

        for ((index, pos) in positions.withIndex()) {
            if (pos + 3 >= data.size) continue
            val b0 = data[pos].toInt() and 0xFF
            val b1 = data[pos + 1].toInt() and 0xFF
            val b2 = data[pos + 2].toInt() and 0xFF
            val b3 = data[pos + 3].toInt() and 0xFF

            if (index > 0) sampleValues.append(" ")
            sampleValues.append(String.format("%02X%02X%02X%02X", b0, b1, b2, b3))

            if (data[pos] != 0xFF.toByte() || data[pos + 1] != 0xFF.toByte() ||
                data[pos + 2] != 0xFF.toByte() || data[pos + 3] != 0xFF.toByte()
            ) {
                allMatch = false
            }
        }

        AudioDebugLogger.logNavPatternCheck("end_marker", allMatch, sampleValues.toString())
        return allMatch
    }

    /**
     * Detect warmup noise (near-silence mix of 0xFFFF/0x0000/0xFEFF).
     * Appears ~200-400ms after NavStart. Causes distortion if played.
     */
    private fun isWarmupNoise(data: ByteArray): Boolean {
        val headerSize = 12
        val audioDataSize = data.size - headerSize
        if (audioDataSize < 32) return false

        val sampleCount = 8
        var nearSilenceCount = 0
        val sampleValues = StringBuilder()

        for (i in 0 until sampleCount) {
            val pos = headerSize + (((audioDataSize * i) / sampleCount) and 0x7FFFFFFE)
            if (pos + 1 >= data.size) continue

            val sample = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            val signedSample = if (sample >= 32768) sample - 65536 else sample

            if (i > 0) sampleValues.append(",")
            sampleValues.append(signedSample)

            if (signedSample in -258..2) nearSilenceCount++
        }

        val isWarmup = nearSilenceCount >= 6
        AudioDebugLogger.logNavPatternCheck("warmup_noise", isWarmup, "$nearSilenceCount/8 near-silence: [$sampleValues]")
        return isWarmup
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
            AudioDebugLogger.logNavBufferFlush("end_marker", discardedMs)
            log("[AUDIO] Nav end marker detected, buffers flushed (total: $navEndMarkersDetected)")
        }
    }

    /** Write audio to stream buffer (non-blocking, USB thread). Returns bytes written. */
    fun writeAudio(
        data: ByteArray,
        audioType: Int,
        decodeType: Int,
    ): Int {
        if (!isRunning.get()) return -1

        AudioDebugLogger.logUsbReceive(data.size, audioType, decodeType)

        // Navigation handles zeros separately for consecutive tracking and buffer flush
        val isZeroFilled = isZeroFilledAudio(data)
        if (isZeroFilled && audioType != AudioStreamType.NAVIGATION) {
            zeroPacketsFiltered++
            AudioDebugLogger.logUsbFiltered(audioType, zeroPacketsFiltered)
            if (BuildConfig.DEBUG && (zeroPacketsFiltered == 1L || zeroPacketsFiltered % 100 == 0L)) {
                Log.w(TAG, "[AUDIO_FILTER] Filtered $zeroPacketsFiltered zero-filled packets")
            }
            return 0
        }

        writeCount++

        // Log every 500th packet for debugging
        if (BuildConfig.DEBUG && writeCount % 500 == 1L) {
            val firstBytes =
                data.take(16).joinToString(" ") { String.format(java.util.Locale.US, "%02X", it) }
            val bufferStats =
                mediaBuffer?.let {
                    "fill=${it.fillLevelMs()}ms overflow=${it.overflowCount} underflow=${it.underflowCount}"
                } ?: "no-buffer"
            Log.i(
                TAG,
                "[AUDIO_DEBUG] write#$writeCount size=${data.size} type=$audioType " +
                    "decode=$decodeType first16=[$firstBytes] $bufferStats",
            )
        }

        // DEBUG: Log buffer stats every 10 seconds
        if (BuildConfig.DEBUG) {
            val now = System.currentTimeMillis()
            if (now - lastStatsLog > 10000) {
                lastStatsLog = now
                mediaBuffer?.let {
                    Log.i(
                        TAG,
                        "[AUDIO_STATS] mediaBuffer: fill=${it.fillLevelMs()}ms/${it.fillLevel() * 100}% " +
                            "written=${it.totalBytesWritten} read=${it.totalBytesRead} " +
                            "overflow=${it.overflowCount} underflow=${it.underflowCount}",
                    )
                }
            }
        }

        // Route to AAOS CarAudioContext (USAGE mappings in class KDoc)
        return when (audioType) {
            AudioStreamType.MEDIA -> {
                ensureMediaTrack(decodeType)
                mediaBuffer?.write(data) ?: -1
            }

            AudioStreamType.NAVIGATION -> {
                // Drop packets after NAVI_STOP (~2s silence before NAVI_COMPLETE per USB captures)
                if (navStopped) return 0

                val bufferLevelMs = navBuffer?.fillLevelMs() ?: 0

                // Check end marker before track creation (navStartTime may be 0)
                val preTrackTimeSinceStart = if (navStartTime > 0) System.currentTimeMillis() - navStartTime else 0L
                if (isNavEndMarker(data)) {
                    AudioDebugLogger.logNavEndMarker(preTrackTimeSinceStart, bufferLevelMs)
                    flushNavBuffers()
                    consecutiveNavZeroPackets = 0
                    return 0
                }

                // Consecutive zeros cause resampling noise on GM AAOS (44.1kHz→48kHz)
                if (isZeroFilled) {
                    consecutiveNavZeroPackets++
                    zeroPacketsFiltered++
                    if (consecutiveNavZeroPackets >= navZeroFlushThreshold) {
                        AudioDebugLogger.logNavZeroFlush(consecutiveNavZeroPackets, bufferLevelMs)
                        flushNavBuffers()
                        log(
                            "[AUDIO_FILTER] Nav buffer flushed after $consecutiveNavZeroPackets consecutive " +
                                "zero packets (total filtered: $zeroPacketsFiltered)",
                        )
                        consecutiveNavZeroPackets = 0
                    }
                    return 0
                }

                consecutiveNavZeroPackets = 0
                ensureNavTrack(decodeType)
                val timeSinceStart = System.currentTimeMillis() - navStartTime

                // Skip warmup noise in first ~250ms
                if (timeSinceStart < navWarmupSkipMs && isWarmupNoise(data)) {
                    navWarmupFramesSkipped++
                    AudioDebugLogger.logNavWarmupSkip(timeSinceStart, "near-silence")
                    if (navWarmupFramesSkipped == 1L || navWarmupFramesSkipped % 10 == 0L) {
                        log("[AUDIO] Skipped nav warmup frame (${timeSinceStart}ms since start, total: $navWarmupFramesSkipped)")
                    }
                    return 0
                }

                val bytesWritten = navBuffer?.write(data) ?: -1
                if (bytesWritten > 0) {
                    navPackets++
                    AudioDebugLogger.logNavBufferWrite(bytesWritten, navBuffer?.fillLevelMs() ?: 0, timeSinceStart)
                }
                bytesWritten
            }

            AudioStreamType.SIRI -> {
                ensureVoiceTrack(decodeType)
                voiceBuffer?.write(data) ?: -1
            }

            AudioStreamType.PHONE_CALL -> {
                ensureCallTrack(decodeType)
                callBuffer?.write(data) ?: -1
            }

            else -> {
                ensureMediaTrack(decodeType)
                mediaBuffer?.write(data) ?: -1
            }
        }
    }

    /** Set media ducking (Len=16 volume packets from adapter). */
    fun setDucking(targetVolume: Float) {
        synchronized(lock) {
            isDucked = targetVolume < 1.0f
            duckLevel = targetVolume.coerceIn(0.0f, 1.0f)

            val effectiveVolume = if (isDucked) mediaVolume * duckLevel else mediaVolume
            mediaTrack?.setVolume(effectiveVolume)

            if (isDucked) {
                log("[AUDIO] Media ducked to ${(duckLevel * 100).toInt()}%")
            } else {
                log("[AUDIO] Media volume restored to ${(mediaVolume * 100).toInt()}%")
            }
        }
    }

    fun setMediaVolume(volume: Float) {
        synchronized(lock) {
            mediaVolume = volume.coerceIn(0.0f, 1.0f)
            val effectiveVolume = if (isDucked) mediaVolume * duckLevel else mediaVolume
            mediaTrack?.setVolume(effectiveVolume)
        }
    }

    fun setNavVolume(volume: Float) {
        synchronized(lock) {
            navVolume = volume.coerceIn(0.0f, 1.0f)
            navTrack?.setVolume(navVolume)
        }
    }

    /**
     * Check if audio is currently playing.
     */
    fun isPlaying(): Boolean =
        isRunning.get() && (
            mediaTrack?.playState == AudioTrack.PLAYSTATE_PLAYING ||
                navTrack?.playState == AudioTrack.PLAYSTATE_PLAYING ||
                voiceTrack?.playState == AudioTrack.PLAYSTATE_PLAYING ||
                callTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
        )

    /**
     * Get statistics about audio playback.
     */
    fun getStats(): Map<String, Any> {
        synchronized(lock) {
            val durationMs = if (startTime > 0) System.currentTimeMillis() - startTime else 0

            return mapOf(
                "isRunning" to isRunning.get(),
                "durationSeconds" to durationMs / 1000.0,
                "mediaVolume" to mediaVolume,
                "navVolume" to navVolume,
                "voiceVolume" to voiceVolume,
                "callVolume" to callVolume,
                "isDucked" to isDucked,
                "duckLevel" to duckLevel,
                "mediaFormat" to (mediaFormat?.let { "${it.sampleRate}Hz ${it.channelCount}ch" } ?: "none"),
                "navFormat" to (navFormat?.let { "${it.sampleRate}Hz ${it.channelCount}ch" } ?: "none"),
                "voiceFormat" to (voiceFormat?.let { "${it.sampleRate}Hz ${it.channelCount}ch" } ?: "none"),
                "callFormat" to (callFormat?.let { "${it.sampleRate}Hz ${it.channelCount}ch" } ?: "none"),
                "mediaBuffer" to (mediaBuffer?.getStats() ?: emptyMap()),
                "navBuffer" to (navBuffer?.getStats() ?: emptyMap()),
                "voiceBuffer" to (voiceBuffer?.getStats() ?: emptyMap()),
                "callBuffer" to (callBuffer?.getStats() ?: emptyMap()),
                "mediaUnderruns" to mediaUnderruns,
                "navUnderruns" to navUnderruns,
                "voiceUnderruns" to voiceUnderruns,
                "callUnderruns" to callUnderruns,
                "navEndMarkersDetected" to navEndMarkersDetected,
                "navWarmupFramesSkipped" to navWarmupFramesSkipped,
                "zeroPacketsFiltered" to zeroPacketsFiltered,
            )
        }
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
                    AudioDebugLogger.logNavBufferFlush("stop_command", discardedMs)
                    AudioDebugLogger.logNavPromptEnd(playDuration, bytesRead, navUnderruns)
                    AudioDebugLogger.logStreamStop("NAV", playDuration, navPackets)
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
            navStopped = false // Reset stopped state for next nav session
            navPackets = 0 // Reset packet counter for next nav prompt
            navUnderruns = 0 // Reset underrun counter for next nav prompt
        }
    }

    private var navPackets: Long = 0

    /** Pause voice track (AudioSiriStop). Enforces min duration for Siri tones. */
    fun stopVoiceTrack() {
        synchronized(lock) {
            voiceTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val playDuration = System.currentTimeMillis() - voiceStartTime
                    val bufferLevel = voiceBuffer?.fillLevelMs() ?: 0

                    if (playDuration < minVoicePlayDurationMs && bufferLevel > 50) {
                        log(
                            "[AUDIO] Ignoring premature voice stop after ${playDuration}ms, " +
                                "buffer has ${bufferLevel}ms data",
                        )
                        return
                    }

                    track.pause()
                    AudioDebugLogger.logStreamStop("VOICE", playDuration, 0)
                    log(
                        "[AUDIO] Voice track paused after ${playDuration}ms - " +
                            "stream ended, AAOS will deprioritize VOICE_COMMAND context",
                    )
                }
            }
            voiceStarted = false
        }
    }

    /** Pause call track (AudioPhonecallStop). */
    fun stopCallTrack() {
        synchronized(lock) {
            callTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    log("[AUDIO] Call track paused - stream ended, AAOS will deprioritize CALL context")
                }
            }
            callStarted = false
        }
    }

    /** Pause media track (AudioMediaStop/AudioOutputStop). */
    fun stopMediaTrack() {
        synchronized(lock) {
            mediaTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    log("[AUDIO] Media track paused - stream ended")
                }
            }
            mediaStarted = false
        }
    }

    /** Pause tracks without release (USB hiccups). Prevents Session 1 pipeline resets. */
    fun suspendPlayback() {
        synchronized(lock) {
            log("[AUDIO] Suspending playback (retaining tracks)")

            mediaTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
            }
            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
            }
            voiceTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
            }
            callTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
            }

            // Reset pre-fill for smooth resume
            mediaStarted = false
            navStarted = false
            voiceStarted = false
            callStarted = false

            log("[AUDIO] Playback suspended - tracks paused but retained")
        }
    }

    /** Resume playback after suspension. Tracks auto-resume via ensureXxxTrack on data. */
    fun resumePlayback() {
        synchronized(lock) {
            log("[AUDIO] Resuming playback")
            val states =
                listOf(
                    "media=${mediaTrack?.playState ?: "null"}",
                    "nav=${navTrack?.playState ?: "null"}",
                    "voice=${voiceTrack?.playState ?: "null"}",
                    "call=${callTrack?.playState ?: "null"}",
                )
            log("[AUDIO] Track states: ${states.joinToString(", ")}")
        }
    }

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

            releaseMediaTrack()
            releaseNavTrack()
            releaseVoiceTrack()
            releaseCallTrack()

            mediaBuffer?.clear()
            navBuffer?.clear()
            voiceBuffer?.clear()
            callBuffer?.clear()
            mediaBuffer = null
            navBuffer = null
            voiceBuffer = null
            callBuffer = null

            log("[AUDIO] DualStreamAudioManager released")
        }
    }

    // ========== Private Methods ==========

    private fun ensureMediaTrack(decodeType: Int) {
        val format = AudioFormats.fromDecodeType(decodeType)

        synchronized(lock) {
            // Resume paused track (Siri tone fix)
            mediaTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED && mediaFormat == format) {
                    track.play()
                    mediaStarted = false
                    log("[AUDIO] Resumed paused media track (same format ${format.sampleRate}Hz)")
                    return
                }
            }

            if (mediaFormat != format) {
                log("[AUDIO] Media format change: ${mediaFormat?.sampleRate ?: 0}Hz -> ${format.sampleRate}Hz")
                releaseMediaTrack()
                mediaFormat = format

                // 500ms buffer absorbs USB jitter (gaps up to 1200ms)
                mediaBuffer =
                    AudioRingBuffer(
                        capacityMs = 500,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                mediaTrack = createAudioTrack(format, AudioStreamType.MEDIA)
                mediaTrack?.play()
                AudioDebugLogger.logStreamStart("MEDIA", format.sampleRate, format.channelCount, 500)
            }
        }
    }

    private fun ensureNavTrack(decodeType: Int) {
        val format = AudioFormats.fromDecodeType(decodeType)

        synchronized(lock) {
            navStopped = false

            // Resume paused track with flush (secondary to end marker flush)
            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED && navFormat == format) {
                    val discardedMs = navBuffer?.fillLevelMs() ?: 0
                    track.flush()
                    navBuffer?.clear()
                    track.play()
                    navStarted = false
                    navStartTime = System.currentTimeMillis()
                    AudioDebugLogger.logNavBufferFlush("track_resume", discardedMs)
                    AudioDebugLogger.logNavPromptStart(format.sampleRate, format.channelCount, 200)
                    log("[AUDIO] Resumed paused nav track with flush (same format ${format.sampleRate}Hz)")
                    return
                }
            }

            if (navFormat != format) {
                log("[AUDIO] Nav format change: ${navFormat?.sampleRate ?: 0}Hz -> ${format.sampleRate}Hz")
                releaseNavTrack()
                navFormat = format

                // 200ms buffer for nav (lower latency than media)
                navBuffer =
                    AudioRingBuffer(
                        capacityMs = 200,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                navTrack = createAudioTrack(format, AudioStreamType.NAVIGATION)
                navTrack?.play()
                navStartTime = System.currentTimeMillis() // Track start time for min duration
                AudioDebugLogger.logStreamStart("NAV", format.sampleRate, format.channelCount, 200)
                AudioDebugLogger.logNavPromptStart(format.sampleRate, format.channelCount, 200)
            }
        }
    }

    private fun ensureVoiceTrack(decodeType: Int) {
        val format = AudioFormats.fromDecodeType(decodeType)

        synchronized(lock) {
            // Resume paused track (Siri tone on subsequent invocations)
            voiceTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED && voiceFormat == format) {
                    track.play()
                    voiceStarted = false
                    voiceStartTime = System.currentTimeMillis()
                    log("[AUDIO] Resumed paused voice track (same format ${format.sampleRate}Hz)")
                    return
                }
            }

            if (voiceFormat != format) {
                log("[AUDIO] Voice format change: ${voiceFormat?.sampleRate ?: 0}Hz -> ${format.sampleRate}Hz")
                releaseVoiceTrack()
                voiceFormat = format

                voiceBuffer =
                    AudioRingBuffer(
                        capacityMs = 250,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                voiceTrack = createAudioTrack(format, AudioStreamType.SIRI)
                voiceTrack?.play()
                voiceStartTime = System.currentTimeMillis()
                AudioDebugLogger.logStreamStart("VOICE", format.sampleRate, format.channelCount, 250)
            }
        }
    }

    private fun ensureCallTrack(decodeType: Int) {
        val format = AudioFormats.fromDecodeType(decodeType)

        synchronized(lock) {
            callTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED && callFormat == format) {
                    track.play()
                    callStarted = false
                    log("[AUDIO] Resumed paused call track (same format ${format.sampleRate}Hz)")
                    return
                }
            }

            if (callFormat != format) {
                log("[AUDIO] Call format change: ${callFormat?.sampleRate ?: 0}Hz -> ${format.sampleRate}Hz")
                releaseCallTrack()
                callFormat = format

                callBuffer =
                    AudioRingBuffer(
                        capacityMs = 250,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                callTrack = createAudioTrack(format, AudioStreamType.PHONE_CALL)
                callTrack?.play()
                AudioDebugLogger.logStreamStart("CALL", format.sampleRate, format.channelCount, 250)
            }
        }
    }

    /**
     * Create an AudioTrack with the appropriate USAGE constant for AAOS CarAudioContext mapping.
     *
     * AAOS CarAudioContext Mapping:
     * - USAGE_MEDIA (1) → MUSIC context
     * - USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (12) → NAVIGATION context
     * - USAGE_ASSISTANT (16) → VOICE_COMMAND context
     * - USAGE_VOICE_COMMUNICATION (2) → CALL context
     */
    private fun createAudioTrack(
        format: AudioFormatConfig,
        streamType: Int,
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

            // USAGE→CarAudioContext mapping (see KDoc for full table)
            val (usage, contentType, streamName) =
                when (streamType) {
                    AudioStreamType.MEDIA -> {
                        Triple(
                            AudioAttributes.USAGE_MEDIA,
                            AudioAttributes.CONTENT_TYPE_MUSIC,
                            "MEDIA",
                        )
                    }

                    AudioStreamType.NAVIGATION -> {
                        Triple(
                            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                            AudioAttributes.CONTENT_TYPE_SPEECH,
                            "NAV",
                        )
                    }

                    AudioStreamType.SIRI -> {
                        Triple(
                            AudioAttributes.USAGE_ASSISTANT,
                            AudioAttributes.CONTENT_TYPE_SPEECH,
                            "VOICE",
                        )
                    }

                    AudioStreamType.PHONE_CALL -> {
                        Triple(
                            AudioAttributes.USAGE_VOICE_COMMUNICATION,
                            AudioAttributes.CONTENT_TYPE_SPEECH,
                            "CALL",
                        )
                    }

                    else -> {
                        Triple(
                            AudioAttributes.USAGE_MEDIA,
                            AudioAttributes.CONTENT_TYPE_MUSIC,
                            "UNKNOWN",
                        )
                    }
                }

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
                when (streamType) {
                    AudioStreamType.MEDIA -> if (isDucked) mediaVolume * duckLevel else mediaVolume
                    AudioStreamType.NAVIGATION -> navVolume
                    AudioStreamType.SIRI -> voiceVolume
                    AudioStreamType.PHONE_CALL -> callVolume
                    else -> 1.0f
                }
            track.setVolume(volume)

            log(
                "[AUDIO] Created $streamName AudioTrack: ${format.sampleRate}Hz " +
                    "${format.channelCount}ch buffer=${bufferSize}B usage=$usage",
            )

            return track
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to create AudioTrack: ${e.message}")
            return null
        }
    }

    private fun releaseMediaTrack() {
        try {
            mediaTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to release media track: ${e.message}")
        }
        mediaTrack = null
        mediaFormat = null
        mediaStarted = false
    }

    private fun releaseNavTrack() {
        try {
            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.release()
            }
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to release nav track: ${e.message}")
        }
        navTrack = null
        navFormat = null
        navStarted = false
    }

    private fun releaseVoiceTrack() {
        try {
            voiceTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.release()
            }
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to release voice track: ${e.message}")
        }
        voiceTrack = null
        voiceFormat = null
        voiceStarted = false
    }

    private fun releaseCallTrack() {
        try {
            callTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.release()
            }
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to release call track: ${e.message}")
        }
        callTrack = null
        callFormat = null
        callStarted = false
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
        logCallback.log(message)
    }

    /** Playback thread (URGENT_AUDIO priority). Separate buffers per stream for safety. */
    private inner class AudioPlaybackThread : Thread("AudioPlayback") {
        private val mediaTempBuffer = ByteArray(playbackChunkSize)
        private val navTempBuffer = ByteArray(playbackChunkSize)
        private val voiceTempBuffer = ByteArray(playbackChunkSize)
        private val callTempBuffer = ByteArray(playbackChunkSize)

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            log("[AUDIO] Playback thread started with URGENT_AUDIO priority")

            while (isRunning.get() && !isInterrupted) {
                try {
                    var didWork = false

                    mediaBuffer?.let { buffer ->
                        mediaTrack?.let { track ->
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                val currentFillMs = buffer.fillLevelMs()

                                // Pre-fill before first playback
                                if (!mediaStarted) {
                                    if (currentFillMs < prefillThresholdMs) return@let
                                    mediaStarted = true
                                    log("[AUDIO] Media pre-fill complete: ${currentFillMs}ms buffered, starting playback")
                                }

                                // Maintain minimum buffer to absorb jitter
                                if (currentFillMs <= minBufferLevelMs) return@let

                                val available = buffer.availableForRead()
                                if (available > 0) {
                                    val bytesPerMs =
                                        (mediaFormat?.sampleRate ?: audioConfig.sampleRate) * (mediaFormat?.channelCount ?: 2) * 2 / 1000
                                    val maxReadableMs = currentFillMs - minBufferLevelMs
                                    val maxReadableBytes = maxReadableMs * bytesPerMs
                                    val toRead = minOf(available, playbackChunkSize, maxReadableBytes.coerceAtLeast(0))

                                    if (toRead > 0) {
                                        val bytesRead = buffer.read(mediaTempBuffer, 0, toRead)
                                        if (bytesRead > 0) {
                                            val written =
                                                track.write(
                                                    mediaTempBuffer,
                                                    0,
                                                    bytesRead,
                                                    AudioTrack.WRITE_NON_BLOCKING,
                                                )
                                            if (written < 0) handleTrackError("MEDIA", written)
                                            didWork = true
                                        }
                                    }
                                }

                                val underruns = track.underrunCount
                                if (underruns > mediaUnderruns) {
                                    val newUnderruns = underruns - mediaUnderruns
                                    mediaUnderruns = underruns
                                    AudioDebugLogger.logTrackUnderrun("MEDIA", underruns)
                                    log(
                                        "[AUDIO_UNDERRUN] Media underrun detected: " +
                                            "+$newUnderruns (total: $underruns)",
                                    )

                                    // Recovery: If many underruns and buffer critically low, reset pre-fill
                                    if (newUnderruns >= underrunRecoveryThreshold && buffer.fillLevelMs() < 50) {
                                        // Force pre-fill again
                                        mediaStarted = false
                                        log(
                                            "[AUDIO_RECOVERY] Resetting media pre-fill due to " +
                                                "$newUnderruns underruns, buffer=${buffer.fillLevelMs()}ms",
                                        )
                                    }
                                }
                            }
                        }
                    }

                    navBuffer?.let { buffer ->
                        navTrack?.let { track ->
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                val currentNavFillMs = buffer.fillLevelMs()

                                // Shorter pre-fill for nav (lower latency)
                                if (!navStarted) {
                                    if (currentNavFillMs < prefillThresholdMs / 2) return@let
                                    navStarted = true
                                    val waitTimeMs = System.currentTimeMillis() - navStartTime
                                    AudioDebugLogger.logNavPrefillComplete(currentNavFillMs, waitTimeMs)
                                    log("[AUDIO] Nav pre-fill complete: ${currentNavFillMs}ms buffered, starting playback")
                                }

                                val navMinBufferMs = minBufferLevelMs / 2
                                if (currentNavFillMs <= navMinBufferMs) return@let

                                val available = buffer.availableForRead()
                                if (available > 0) {
                                    val bytesPerMs =
                                        (navFormat?.sampleRate ?: audioConfig.sampleRate) * (navFormat?.channelCount ?: 2) * 2 / 1000
                                    val maxReadableMs = currentNavFillMs - navMinBufferMs
                                    val maxReadableBytes = maxReadableMs * bytesPerMs
                                    val toRead = minOf(available, playbackChunkSize, maxReadableBytes.coerceAtLeast(0))

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
                                            } else {
                                                AudioDebugLogger.logNavTrackWrite(written, buffer.fillLevelMs())
                                            }
                                            didWork = true
                                        }
                                    }
                                }

                                val underruns = track.underrunCount
                                if (underruns > navUnderruns) {
                                    val newUnderruns = underruns - navUnderruns
                                    navUnderruns = underruns
                                    AudioDebugLogger.logTrackUnderrun("NAV", underruns)
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

                    voiceBuffer?.let { buffer ->
                        voiceTrack?.let { track ->
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                if (!voiceStarted) {
                                    val fillMs = buffer.fillLevelMs()
                                    if (fillMs < prefillThresholdMs / 2) return@let
                                    voiceStarted = true
                                    log("[AUDIO] Voice pre-fill complete: ${fillMs}ms buffered, starting playback")
                                }

                                val available = buffer.availableForRead()
                                if (available > 0) {
                                    val toRead = minOf(available, playbackChunkSize)
                                    val bytesRead = buffer.read(voiceTempBuffer, 0, toRead)
                                    if (bytesRead > 0) {
                                        val written =
                                            track.write(
                                                voiceTempBuffer,
                                                0,
                                                bytesRead,
                                                AudioTrack.WRITE_NON_BLOCKING,
                                            )
                                        if (written < 0) handleTrackError("VOICE", written)
                                        didWork = true
                                    }
                                }

                                val underruns = track.underrunCount
                                if (underruns > voiceUnderruns) {
                                    val newUnderruns = underruns - voiceUnderruns
                                    voiceUnderruns = underruns
                                    AudioDebugLogger.logTrackUnderrun("VOICE", underruns)
                                    log(
                                        "[AUDIO_UNDERRUN] Voice underrun detected: " +
                                            "+$newUnderruns (total: $underruns)",
                                    )

                                    // Recovery: If many underruns and buffer critically low, reset pre-fill
                                    if (newUnderruns >= underrunRecoveryThreshold && buffer.fillLevelMs() < 30) {
                                        voiceStarted = false
                                        log(
                                            "[AUDIO_RECOVERY] Resetting voice pre-fill due to " +
                                                "$newUnderruns underruns, buffer=${buffer.fillLevelMs()}ms",
                                        )
                                    }
                                }
                            }
                        }
                    }

                    callBuffer?.let { buffer ->
                        callTrack?.let { track ->
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                if (!callStarted) {
                                    val fillMs = buffer.fillLevelMs()
                                    if (fillMs < prefillThresholdMs / 2) return@let
                                    callStarted = true
                                    log("[AUDIO] Call pre-fill complete: ${fillMs}ms buffered, starting playback")
                                }

                                val available = buffer.availableForRead()
                                if (available > 0) {
                                    val toRead = minOf(available, playbackChunkSize)
                                    val bytesRead = buffer.read(callTempBuffer, 0, toRead)
                                    if (bytesRead > 0) {
                                        val written =
                                            track.write(
                                                callTempBuffer,
                                                0,
                                                bytesRead,
                                                AudioTrack.WRITE_NON_BLOCKING,
                                            )
                                        if (written < 0) handleTrackError("CALL", written)
                                        didWork = true
                                    }
                                }

                                val underruns = track.underrunCount
                                if (underruns > callUnderruns) {
                                    val newUnderruns = underruns - callUnderruns
                                    callUnderruns = underruns
                                    AudioDebugLogger.logTrackUnderrun("CALL", underruns)
                                    log(
                                        "[AUDIO_UNDERRUN] Call underrun detected: " +
                                            "+$newUnderruns (total: $underruns)",
                                    )

                                    // Recovery: If many underruns and buffer critically low, reset pre-fill
                                    if (newUnderruns >= underrunRecoveryThreshold && buffer.fillLevelMs() < 30) {
                                        callStarted = false
                                        log(
                                            "[AUDIO_RECOVERY] Resetting call pre-fill due to " +
                                                "$newUnderruns underruns, buffer=${buffer.fillLevelMs()}ms",
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!didWork) sleep(5)

                    AudioDebugLogger.logPerfSummary(
                        mediaBuffer?.fillLevelMs() ?: 0,
                        navBuffer?.fillLevelMs() ?: 0,
                        voiceBuffer?.fillLevelMs() ?: 0,
                        callBuffer?.fillLevelMs() ?: 0,
                        mediaUnderruns,
                        navUnderruns,
                        voiceUnderruns,
                        callUnderruns,
                    )
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "[AUDIO] Playback thread error: ${e.message}")
                }
            }

            log("[AUDIO] Playback thread stopped")
        }

        private fun handleTrackError(
            streamType: String,
            errorCode: Int,
        ) {
            when (errorCode) {
                AudioTrack.ERROR_DEAD_OBJECT -> {
                    Log.e(TAG, "[AUDIO] $streamType AudioTrack dead, needs reinitialization")
                }

                AudioTrack.ERROR_INVALID_OPERATION -> {
                    Log.e(TAG, "[AUDIO] $streamType AudioTrack invalid operation")
                }

                else -> {
                    Log.e(TAG, "[AUDIO] $streamType AudioTrack write error: $errorCode")
                }
            }
        }
    }
}
