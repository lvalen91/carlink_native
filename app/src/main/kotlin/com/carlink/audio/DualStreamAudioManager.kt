package com.carlink.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
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
    // Audio tracks for each stream type (maps to AAOS CarAudioContext)
    private var mediaTrack: AudioTrack? = null // USAGE_MEDIA → MUSIC
    private var navTrack: AudioTrack? = null // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE → NAVIGATION
    private var voiceTrack: AudioTrack? = null // USAGE_ASSISTANT → VOICE_COMMAND
    private var callTrack: AudioTrack? = null // USAGE_VOICE_COMMUNICATION → CALL

    // Ring buffers for jitter compensation (one per stream)
    private var mediaBuffer: AudioRingBuffer? = null // 250ms for music
    private var navBuffer: AudioRingBuffer? = null // 120ms for nav prompts
    private var voiceBuffer: AudioRingBuffer? = null // 150ms for voice assistant
    private var callBuffer: AudioRingBuffer? = null // 150ms for phone calls

    // Current audio format per stream
    private var mediaFormat: AudioFormatConfig? = null
    private var navFormat: AudioFormatConfig? = null
    private var voiceFormat: AudioFormatConfig? = null
    private var callFormat: AudioFormatConfig? = null

    // Volume control
    private var mediaVolume: Float = 1.0f
    private var navVolume: Float = 1.0f
    private var voiceVolume: Float = 1.0f
    private var callVolume: Float = 1.0f
    private var isDucked: Boolean = false
    private var duckLevel: Float = 0.2f // 20% volume when ducked

    // Playback thread
    private var playbackThread: AudioPlaybackThread? = null
    private val isRunning = AtomicBoolean(false)

    // Statistics
    private var startTime: Long = 0
    private var mediaUnderruns: Int = 0
    private var navUnderruns: Int = 0
    private var voiceUnderruns: Int = 0
    private var callUnderruns: Int = 0
    private var writeCount: Long = 0 // DEBUG: Counter for periodic logging
    private var lastStatsLog: Long = 0 // DEBUG: Timestamp of last stats log
    private var zeroPacketsFiltered: Long = 0 // Count of zero-filled packets filtered

    // Buffer size multiplier - platform-specific, from AudioConfig
    // Default: 5x minimum for USB jitter tolerance
    // GM AAOS: 8x minimum due to FAST track denial
    private val bufferMultiplier = audioConfig.bufferMultiplier

    // Playback chunk size in bytes (5ms of audio at configured sample rate, stereo)
    private val playbackChunkSize = audioConfig.sampleRate * 2 * 2 * 5 / 1000 // ~960 bytes at 48kHz

    // Pre-fill threshold: minimum buffer level (ms) before starting playback
    // Prevents initial underruns by ensuring buffer has enough data
    // Platform-specific: 150ms default, 200ms for GM AAOS
    private val prefillThresholdMs = audioConfig.prefillThresholdMs

    // Underrun recovery threshold: if this many underruns occur in a short period,
    // reset pre-fill to allow buffer to refill before resuming playback
    private val underrunRecoveryThreshold = 10

    // Minimum buffer level (ms) to maintain during playback
    // This prevents draining the buffer to 0ms which causes underruns
    // Keep at least this much data in the ring buffer as headroom for USB jitter
    // Reduced from 100ms to 50ms based on captured USB data showing P99 jitter of only 7ms
    private val minBufferLevelMs = 50

    // Track whether each stream has started playing (for pre-fill logic)
    @Volatile private var mediaStarted = false

    @Volatile private var navStarted = false

    // Track start times for minimum playback duration enforcement
    // Prevents premature stop when adapter sends stop command too quickly
    @Volatile private var navStartTime: Long = 0

    @Volatile private var voiceStartTime: Long = 0

    // Minimum playback duration (ms) before allowing stream stop
    // This fixes premature nav/Siri audio cutoff observed in Sessions 1-2
    private val minNavPlayDurationMs = 300
    private val minVoicePlayDurationMs = 200

    // Warmup skip duration (ms) - skip initial frames after stream start to avoid codec warmup noise
    // Observed in logs: mixed 0xFFFF/0x0000/0xFEFF patterns at NavStart for ~200-400ms
    private val navWarmupSkipMs = 250

    // Count of nav end markers detected (for statistics)
    private var navEndMarkersDetected: Long = 0

    // Count of warmup frames skipped
    private var navWarmupFramesSkipped: Long = 0

    // Consecutive zero-filled packet tracking for navigation
    // Flush buffer after multiple consecutive zero packets to prevent noise from resampling
    private var consecutiveNavZeroPackets: Int = 0
    private val navZeroFlushThreshold = 3 // Flush after 3 consecutive zero packets

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
     * Check if audio data is entirely zero-filled (invalid/uninitialized data).
     *
     * Real audio, even during silent moments, contains dithering noise.
     * Packets that are exactly 0x00 for every byte indicate adapter issues.
     *
     * IMPORTANT: Audio packet format is 12-byte header + PCM samples.
     * Header: [audioType(4) + volume(4) + decodeType(4)]
     * We must skip the header and only check the PCM audio data portion,
     * otherwise the non-zero audioType byte causes false negatives.
     *
     * Samples multiple positions for efficiency (O(1) check).
     */
    private fun isZeroFilledAudio(data: ByteArray): Boolean {
        // Audio data format: 12-byte header + PCM samples
        val headerSize = 12
        val audioDataSize = data.size - headerSize

        // Need at least 16 bytes of audio data to check
        if (audioDataSize < 16) return false

        // Sample 5 positions across the AUDIO DATA (after header, aligned to 2-byte boundary)
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
            // Check 4 consecutive bytes - if any non-zero, it's real audio
            if (data[pos] != 0.toByte() ||
                data[pos + 1] != 0.toByte() ||
                data[pos + 2] != 0.toByte() ||
                data[pos + 3] != 0.toByte()
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Detect navigation end marker (solid 0xFFFF pattern).
     *
     * The CPC200-CCPA adapter sends a solid 0xFFFF frame as an end-of-stream marker
     * just before NaviStop. This is distinct from warmup noise which contains mixed
     * patterns (0xFFFF/0x0000/0xFEFF).
     *
     * When detected:
     * 1. Don't write to ring buffer (marker is not audio)
     * 2. Flush both ring buffer and AudioTrack to clear stale data
     * 3. Next NaviStart gets a clean buffer with no residual audio
     *
     * @param data Audio data to check
     * @return true if this is a solid 0xFFFF end marker
     */
    private fun isNavEndMarker(data: ByteArray): Boolean {
        // Audio data format: 12-byte header + PCM samples
        // Header: [audioType(4) + unknown(4) + format(4)]
        // Skip header when checking for 0xFFFF pattern
        val headerSize = 12
        val audioDataSize = data.size - headerSize

        if (audioDataSize < 32) return false

        // Sample 4 positions across the AUDIO DATA (after header)
        // Must ALL be 0xFFFF for end marker
        // Warmup noise has mixed patterns (0xFEFF, 0x0000) so won't match
        val positions =
            intArrayOf(
                headerSize, // Start of audio data
                headerSize + ((audioDataSize * 0.25).toInt() and 0x7FFFFFFE),
                headerSize + ((audioDataSize * 0.5).toInt() and 0x7FFFFFFE),
                headerSize + ((audioDataSize * 0.75).toInt() and 0x7FFFFFFE),
            )

        // Collect sample values for debug logging
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

            // Must be exactly 0xFFFF 0xFFFF (2 consecutive 16-bit samples = -1)
            if (data[pos] != 0xFF.toByte() ||
                data[pos + 1] != 0xFF.toByte() ||
                data[pos + 2] != 0xFF.toByte() ||
                data[pos + 3] != 0xFF.toByte()
            ) {
                allMatch = false
            }
        }

        // Log pattern detection result
        AudioDebugLogger.logNavPatternCheck("end_marker", allMatch, sampleValues.toString())

        return allMatch
    }

    /**
     * Detect warmup noise pattern (mixed near-silence with 0xFFFF/0x0000/0xFEFF).
     *
     * Codec warmup noise appears at NavStart for ~200-400ms before valid audio.
     * These frames contain alternating patterns of near-silence values that
     * cause audible distortion if played.
     *
     * Pattern characteristics:
     * - Mix of 0xFFFF (-1), 0x0000 (0), 0xFEFF (-257) values
     * - NOT solid 0xFFFF (that's end marker)
     * - Occurs in first ~250ms after nav stream starts
     *
     * @param data Audio data to check
     * @return true if this appears to be warmup noise
     */
    private fun isWarmupNoise(data: ByteArray): Boolean {
        // Audio data format: 12-byte header + PCM samples
        val headerSize = 12
        val audioDataSize = data.size - headerSize

        if (audioDataSize < 32) return false

        // Sample 8 positions across the AUDIO DATA (after header)
        val sampleCount = 8
        var nearSilenceCount = 0
        val sampleValues = StringBuilder()

        for (i in 0 until sampleCount) {
            val pos = headerSize + (((audioDataSize * i) / sampleCount) and 0x7FFFFFFE)
            if (pos + 1 >= data.size) continue

            // Read 16-bit sample (little-endian)
            val sample = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            val signedSample = if (sample >= 32768) sample - 65536 else sample

            if (i > 0) sampleValues.append(",")
            sampleValues.append(signedSample)

            // Check for near-silence values: 0, -1, -2, -256, -257, -258, 1, 2
            // Note: -1 and -257 are already covered by the range -258..2
            if (signedSample in -258..2) {
                nearSilenceCount++
            }
        }

        // If most samples are near-silence but not ALL 0xFFFF (checked by isNavEndMarker),
        // this is warmup noise
        val isWarmup = nearSilenceCount >= 6 // 6 out of 8 samples are near-silence

        // Log pattern detection result
        AudioDebugLogger.logNavPatternCheck("warmup_noise", isWarmup, "$nearSilenceCount/8 near-silence: [$sampleValues]")

        return isWarmup
    }

    /**
     * Flush navigation buffers immediately.
     *
     * Called when nav end marker (0xFFFF) is detected to clear stale data.
     * This ensures next nav prompt starts with clean buffers.
     */
    private fun flushNavBuffers() {
        synchronized(lock) {
            // Capture buffer level before clearing for logging
            val discardedMs = navBuffer?.fillLevelMs() ?: 0

            // Clear ring buffer first (fast, lock-free)
            navBuffer?.clear()

            // Flush AudioTrack internal buffer
            // Note: flush() is no-op unless track is paused/stopped
            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    // Don't resume - NaviStop will arrive shortly
                    // Track will be resumed by ensureNavTrack when next nav audio arrives
                }
            }

            navEndMarkersDetected++
            AudioDebugLogger.logNavBufferFlush("end_marker", discardedMs)
            log("[AUDIO] Nav end marker detected, buffers flushed (total: $navEndMarkersDetected)")
        }
    }

    /**
     * Write audio data to the appropriate stream buffer (non-blocking).
     *
     * Called from USB thread. Never blocks.
     *
     * @param data PCM audio data (16-bit)
     * @param audioType Stream type (1=media, 2=navigation)
     * @param decodeType CPC200-CCPA format type (1-7)
     * @return Number of bytes written to buffer
     */
    fun writeAudio(
        data: ByteArray,
        audioType: Int,
        decodeType: Int,
    ): Int {
        if (!isRunning.get()) return -1

        // Debug logging for USB audio reception
        AudioDebugLogger.logUsbReceive(data.size, audioType, decodeType)

        // Check for zero-filled packets (adapter firmware issue / uninitialized data)
        // Note: Navigation gets special handling below to track consecutive zeros and flush buffers
        val isZeroFilled = isZeroFilledAudio(data)
        if (isZeroFilled && audioType != AudioStreamType.NAVIGATION) {
            // For non-navigation streams, just filter and count
            zeroPacketsFiltered++
            AudioDebugLogger.logUsbFiltered(audioType, zeroPacketsFiltered)
            if (BuildConfig.DEBUG && (zeroPacketsFiltered == 1L || zeroPacketsFiltered % 100 == 0L)) {
                Log.w(TAG, "[AUDIO_FILTER] Filtered $zeroPacketsFiltered zero-filled packets")
            }
            return 0 // Return 0 to indicate no bytes written (not an error)
        }

        writeCount++

        // DEBUG: Log every 500th packet with first bytes and buffer stats
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

        // Route to appropriate AudioTrack based on stream type
        // Each stream maps to a specific AAOS CarAudioContext for proper vehicle integration
        return when (audioType) {
            AudioStreamType.MEDIA -> {
                // USAGE_MEDIA → CarAudioContext.MUSIC
                ensureMediaTrack(decodeType)
                mediaBuffer?.write(data) ?: -1
            }

            AudioStreamType.NAVIGATION -> {
                // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE → CarAudioContext.NAVIGATION
                val bufferLevelMs = navBuffer?.fillLevelMs() ?: 0

                // Check for end marker FIRST (before ensuring track)
                // Use safe time calculation (navStartTime may be 0 before track created)
                val preTrackTimeSinceStart = if (navStartTime > 0) System.currentTimeMillis() - navStartTime else 0L
                if (isNavEndMarker(data)) {
                    // End marker detected - flush buffers to prevent stale data playback
                    AudioDebugLogger.logNavEndMarker(preTrackTimeSinceStart, bufferLevelMs)
                    flushNavBuffers()
                    consecutiveNavZeroPackets = 0
                    return 0 // Don't write marker to buffer
                }

                // Handle zero-filled packets for navigation with consecutive tracking
                // Multiple consecutive zeros indicate adapter sending empty audio, which
                // causes resampling artifacts (noise) on GM AAOS when 44.1kHz→48kHz resampling occurs
                if (isZeroFilled) {
                    consecutiveNavZeroPackets++
                    zeroPacketsFiltered++
                    if (consecutiveNavZeroPackets >= navZeroFlushThreshold) {
                        // Multiple consecutive zero packets - flush buffer to prevent noise
                        AudioDebugLogger.logNavZeroFlush(consecutiveNavZeroPackets, bufferLevelMs)
                        flushNavBuffers()
                        log(
                            "[AUDIO_FILTER] Nav buffer flushed after $consecutiveNavZeroPackets consecutive " +
                                "zero packets (total filtered: $zeroPacketsFiltered)",
                        )
                        consecutiveNavZeroPackets = 0
                    }
                    return 0 // Don't write zero data to buffer
                }

                // Valid audio - reset consecutive zero counter
                consecutiveNavZeroPackets = 0

                // Ensure track exists (creates track and sets navStartTime on first call)
                ensureNavTrack(decodeType)

                // Calculate time since start AFTER track is ensured (navStartTime now valid)
                val timeSinceStart = System.currentTimeMillis() - navStartTime

                // Skip warmup noise in first ~250ms after stream start
                if (timeSinceStart < navWarmupSkipMs && isWarmupNoise(data)) {
                    navWarmupFramesSkipped++
                    AudioDebugLogger.logNavWarmupSkip(timeSinceStart, "near-silence")
                    if (navWarmupFramesSkipped == 1L || navWarmupFramesSkipped % 10 == 0L) {
                        log("[AUDIO] Skipped nav warmup frame (${timeSinceStart}ms since start, total: $navWarmupFramesSkipped)")
                    }
                    return 0 // Don't write warmup noise to buffer
                }

                // Write to nav buffer and log
                val bytesWritten = navBuffer?.write(data) ?: -1
                if (bytesWritten > 0) {
                    navPackets++
                    AudioDebugLogger.logNavBufferWrite(bytesWritten, navBuffer?.fillLevelMs() ?: 0, timeSinceStart)
                }
                bytesWritten
            }

            AudioStreamType.SIRI -> {
                // USAGE_ASSISTANT → CarAudioContext.VOICE_COMMAND
                ensureVoiceTrack(decodeType)
                voiceBuffer?.write(data) ?: -1
            }

            AudioStreamType.PHONE_CALL -> {
                // USAGE_VOICE_COMMUNICATION → CarAudioContext.CALL
                ensureCallTrack(decodeType)
                callBuffer?.write(data) ?: -1
            }

            else -> {
                // Default to media stream for unknown types
                ensureMediaTrack(decodeType)
                mediaBuffer?.write(data) ?: -1
            }
        }
    }

    /**
     * Set volume ducking state.
     *
     * Called when adapter sends Len=16 volume packets.
     *
     * @param targetVolume Target volume (0.0 to 1.0), typically 0.2 during nav
     */
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

    /**
     * Set media stream volume.
     *
     * @param volume Volume level (0.0 to 1.0)
     */
    fun setMediaVolume(volume: Float) {
        synchronized(lock) {
            mediaVolume = volume.coerceIn(0.0f, 1.0f)
            val effectiveVolume = if (isDucked) mediaVolume * duckLevel else mediaVolume
            mediaTrack?.setVolume(effectiveVolume)
        }
    }

    /**
     * Set navigation stream volume.
     *
     * @param volume Volume level (0.0 to 1.0)
     */
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

    /**
     * Pause navigation AudioTrack when nav audio stream ends.
     * Called when AudioNaviStop command is received from the adapter.
     *
     * Enforces minimum playback duration to prevent premature cutoff when adapter
     * sends stop command too quickly (observed in Sessions 1-2).
     */
    fun stopNavTrack() {
        log("[NAV_STOP] stopNavTrack() called")

        synchronized(lock) {
            val trackState = navTrack?.playState
            val trackStateStr =
                when (trackState) {
                    AudioTrack.PLAYSTATE_PLAYING -> "PLAYING"
                    AudioTrack.PLAYSTATE_PAUSED -> "PAUSED"
                    AudioTrack.PLAYSTATE_STOPPED -> "STOPPED"
                    else -> "null/unknown($trackState)"
                }
            log("[NAV_STOP] Track state: $trackStateStr, navStarted=$navStarted, navStartTime=$navStartTime")

            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    // Check minimum playback duration
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

                    // Pause and flush to clear any remaining stale data
                    // This is a secondary safeguard - primary flush happens on 0xFFFF end marker
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
            navPackets = 0 // Reset packet counter for next nav prompt
            navUnderruns = 0 // Reset underrun counter for next nav prompt
        }
    }

    // Nav packet counter for debug logging
    private var navPackets: Long = 0

    /**
     * Pause voice assistant AudioTrack when Siri/voice stream ends.
     * Called when AudioSiriStop command is received from the adapter.
     *
     * Enforces minimum playback duration to prevent premature Siri tone cutoff.
     */
    fun stopVoiceTrack() {
        synchronized(lock) {
            voiceTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    // Check minimum playback duration
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

    /**
     * Pause phone call AudioTrack when call audio stream ends.
     * Called when AudioPhonecallStop command is received from the adapter.
     */
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

    /**
     * Pause media AudioTrack when media stream ends.
     * Called when AudioMediaStop or AudioOutputStop command is received.
     */
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

    /**
     * Suspend playback without releasing resources.
     *
     * Use this instead of release() for temporary disconnections (USB hiccups).
     * Tracks are paused but retained, allowing quick resume without reinitialization.
     * This prevents the 72+ pipeline resets observed in Session 1.
     */
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

            // Reset pre-fill flags so tracks wait for buffer to fill before resuming
            mediaStarted = false
            navStarted = false
            voiceStarted = false
            callStarted = false

            log("[AUDIO] Playback suspended - tracks paused but retained")
        }
    }

    /**
     * Resume playback after suspension.
     *
     * Only resumes tracks that have data in their buffers.
     */
    fun resumePlayback() {
        synchronized(lock) {
            log("[AUDIO] Resuming playback")

            // Tracks will be resumed automatically by ensureXxxTrack() when data arrives
            // Just log the current state for debugging
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

    /**
     * Stop playback and release all resources.
     */
    fun release() {
        synchronized(lock) {
            log("[AUDIO] Releasing DualStreamAudioManager")

            isRunning.set(false)

            // Stop playback thread
            playbackThread?.interrupt()
            try {
                playbackThread?.join(1000)
            } catch (e: InterruptedException) {
                // Ignore
            }
            playbackThread = null

            // Release all audio tracks
            releaseMediaTrack()
            releaseNavTrack()
            releaseVoiceTrack()
            releaseCallTrack()

            // Clear all buffers
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
            // Resume paused track if same format (Fix for Siri tone not heard after first invocation)
            mediaTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED && mediaFormat == format) {
                    track.play()
                    mediaStarted = false // Reset pre-fill for smooth resume
                    log("[AUDIO] Resumed paused media track (same format ${format.sampleRate}Hz)")
                    return
                }
            }

            // Check if format changed
            if (mediaFormat != format) {
                log("[AUDIO] Media format change: ${mediaFormat?.sampleRate ?: 0}Hz -> ${format.sampleRate}Hz")
                releaseMediaTrack()
                mediaFormat = format

                // Create new ring buffer for this format
                // 500ms buffer absorbs USB packet jitter (gaps up to 1200ms observed)
                mediaBuffer =
                    AudioRingBuffer(
                        capacityMs = 500,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                // Create AudioTrack with USAGE_MEDIA → CarAudioContext.MUSIC
                mediaTrack = createAudioTrack(format, AudioStreamType.MEDIA)
                mediaTrack?.play()
                AudioDebugLogger.logStreamStart("MEDIA", format.sampleRate, format.channelCount, 500)
            }
        }
    }

    private fun ensureNavTrack(decodeType: Int) {
        val format = AudioFormats.fromDecodeType(decodeType)

        synchronized(lock) {
            // Resume paused track if same format
            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED && navFormat == format) {
                    // Flush any residual data before resuming (belt-and-suspenders with end marker flush)
                    val discardedMs = navBuffer?.fillLevelMs() ?: 0
                    track.flush()
                    navBuffer?.clear()
                    track.play()
                    navStarted = false // Reset pre-fill for smooth resume
                    navStartTime = System.currentTimeMillis() // Track start time for min duration
                    AudioDebugLogger.logNavBufferFlush("track_resume", discardedMs)
                    AudioDebugLogger.logNavPromptStart(format.sampleRate, format.channelCount, 200)
                    log("[AUDIO] Resumed paused nav track with flush (same format ${format.sampleRate}Hz)")
                    return
                }
            }

            // Check if format changed
            if (navFormat != format) {
                log("[AUDIO] Nav format change: ${navFormat?.sampleRate ?: 0}Hz -> ${format.sampleRate}Hz")
                releaseNavTrack()
                navFormat = format

                // Create new ring buffer for this format
                // 200ms buffer for navigation prompts (lower latency than media)
                navBuffer =
                    AudioRingBuffer(
                        capacityMs = 200,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                // Create AudioTrack with USAGE_ASSISTANCE_NAVIGATION_GUIDANCE → CarAudioContext.NAVIGATION
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
            // Resume paused track if same format (critical for Siri tone on subsequent invocations)
            voiceTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED && voiceFormat == format) {
                    track.play()
                    voiceStarted = false // Reset pre-fill for smooth resume
                    voiceStartTime = System.currentTimeMillis() // Track start time for min duration
                    log("[AUDIO] Resumed paused voice track (same format ${format.sampleRate}Hz)")
                    return
                }
            }

            // Check if format changed
            if (voiceFormat != format) {
                log("[AUDIO] Voice format change: ${voiceFormat?.sampleRate ?: 0}Hz -> ${format.sampleRate}Hz")
                releaseVoiceTrack()
                voiceFormat = format

                // Create new ring buffer for this format
                // 250ms buffer for voice assistant responses
                voiceBuffer =
                    AudioRingBuffer(
                        capacityMs = 250,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                // Create AudioTrack with USAGE_ASSISTANT → CarAudioContext.VOICE_COMMAND
                voiceTrack = createAudioTrack(format, AudioStreamType.SIRI)
                voiceTrack?.play()
                voiceStartTime = System.currentTimeMillis() // Track start time for min duration
                AudioDebugLogger.logStreamStart("VOICE", format.sampleRate, format.channelCount, 250)
            }
        }
    }

    private fun ensureCallTrack(decodeType: Int) {
        val format = AudioFormats.fromDecodeType(decodeType)

        synchronized(lock) {
            // Resume paused track if same format
            callTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED && callFormat == format) {
                    track.play()
                    callStarted = false // Reset pre-fill for smooth resume
                    log("[AUDIO] Resumed paused call track (same format ${format.sampleRate}Hz)")
                    return
                }
            }

            // Check if format changed
            if (callFormat != format) {
                log("[AUDIO] Call format change: ${callFormat?.sampleRate ?: 0}Hz -> ${format.sampleRate}Hz")
                releaseCallTrack()
                callFormat = format

                // Create new ring buffer for this format
                // 250ms buffer for phone call audio
                callBuffer =
                    AudioRingBuffer(
                        capacityMs = 250,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                // Create AudioTrack with USAGE_VOICE_COMMUNICATION → CarAudioContext.CALL
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

            // Use larger buffer for jitter tolerance
            val bufferSize = minBufferSize * bufferMultiplier

            // Select AudioAttributes based on stream type for proper AAOS CarAudioContext routing
            val (usage, contentType, streamName) =
                when (streamType) {
                    // USAGE_MEDIA → CarAudioContext.MUSIC
                    AudioStreamType.MEDIA -> {
                        Triple(
                            AudioAttributes.USAGE_MEDIA,
                            AudioAttributes.CONTENT_TYPE_MUSIC,
                            "MEDIA",
                        )
                    }

                    // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE → CarAudioContext.NAVIGATION
                    AudioStreamType.NAVIGATION -> {
                        Triple(
                            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                            AudioAttributes.CONTENT_TYPE_SPEECH,
                            "NAV",
                        )
                    }

                    // USAGE_ASSISTANT → CarAudioContext.VOICE_COMMAND
                    AudioStreamType.SIRI -> {
                        Triple(
                            AudioAttributes.USAGE_ASSISTANT,
                            AudioAttributes.CONTENT_TYPE_SPEECH,
                            "VOICE",
                        )
                    }

                    // USAGE_VOICE_COMMUNICATION → CarAudioContext.CALL
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

            // Performance mode is platform-specific:
            // - DEFAULT: PERFORMANCE_MODE_LOW_LATENCY (requests FAST track)
            // - GM AAOS: PERFORMANCE_MODE_NONE (FAST track denied anyway)
            val track =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(audioConfig.performanceMode)
                    .build()

            // Set initial volume based on stream type
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
        mediaStarted = false // Reset pre-fill flag for next track
    }

    private fun releaseNavTrack() {
        try {
            navTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to release nav track: ${e.message}")
        }
        navTrack = null
        navFormat = null
        navStarted = false // Reset pre-fill flag for next track
    }

    private fun releaseVoiceTrack() {
        try {
            voiceTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to release voice track: ${e.message}")
        }
        voiceTrack = null
        voiceFormat = null
        voiceStarted = false // Reset pre-fill flag for next track
    }

    private fun releaseCallTrack() {
        try {
            callTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        } catch (e: Exception) {
            log("[AUDIO] ERROR: Failed to release call track: ${e.message}")
        }
        callTrack = null
        callFormat = null
        callStarted = false // Reset pre-fill flag for next track
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
        logCallback.log(message)
    }

    /**
     * Dedicated audio playback thread.
     *
     * Runs at THREAD_PRIORITY_URGENT_AUDIO for consistent scheduling.
     * Reads from ring buffers and writes to AudioTracks.
     *
     * Each stream has its own tempBuffer to prevent any potential data corruption
     * when multiple streams are active simultaneously (e.g., nav during music).
     */
    private inner class AudioPlaybackThread : Thread("AudioPlayback") {
        // Separate buffers per stream to prevent data corruption during interleaved playback
        private val mediaTempBuffer = ByteArray(playbackChunkSize)
        private val navTempBuffer = ByteArray(playbackChunkSize)
        private val voiceTempBuffer = ByteArray(playbackChunkSize)
        private val callTempBuffer = ByteArray(playbackChunkSize)

        override fun run() {
            // Set high priority for audio thread
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            log("[AUDIO] Playback thread started with URGENT_AUDIO priority")

            while (isRunning.get() && !isInterrupted) {
                try {
                    var didWork = false

                    // Process media buffer
                    mediaBuffer?.let { buffer ->
                        mediaTrack?.let { track ->
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                // Get current buffer fill level
                                val currentFillMs = buffer.fillLevelMs()

                                // Pre-fill check: wait for minimum buffer level before first playback
                                if (!mediaStarted) {
                                    if (currentFillMs < prefillThresholdMs) {
                                        // Not enough data yet, skip this iteration
                                        return@let
                                    }
                                    mediaStarted = true
                                    log("[AUDIO] Media pre-fill complete: ${currentFillMs}ms buffered, starting playback")
                                }

                                // Enforce minimum buffer level during playback
                                // This prevents draining the buffer to 0ms which causes constant underruns
                                if (currentFillMs <= minBufferLevelMs) {
                                    // Buffer too low - let it build up before reading more
                                    // This maintains headroom for USB timing jitter
                                    return@let
                                }

                                val available = buffer.availableForRead()
                                if (available > 0) {
                                    // Calculate how much we can read while maintaining minimum buffer level
                                    val bytesPerMs =
                                        (mediaFormat?.sampleRate ?: audioConfig.sampleRate) * (mediaFormat?.channelCount ?: 2) * 2 / 1000
                                    val maxReadableMs = currentFillMs - minBufferLevelMs
                                    val maxReadableBytes = maxReadableMs * bytesPerMs
                                    val toRead = minOf(available, playbackChunkSize, maxReadableBytes.coerceAtLeast(0))

                                    if (toRead > 0) {
                                        val bytesRead = buffer.read(mediaTempBuffer, 0, toRead)
                                        if (bytesRead > 0) {
                                            // Use WRITE_NON_BLOCKING to avoid blocking the playback thread
                                            // This allows better pacing and prevents starvation
                                            val written =
                                                track.write(
                                                    mediaTempBuffer,
                                                    0,
                                                    bytesRead,
                                                    AudioTrack.WRITE_NON_BLOCKING,
                                                )
                                            if (written < 0) {
                                                handleTrackError("MEDIA", written)
                                            } else if (written < bytesRead) {
                                                // AudioTrack buffer is full, couldn't write all data
                                                // This is normal with non-blocking - data stays in ring buffer
                                                // We'll try again next iteration
                                            }
                                            didWork = true
                                        }
                                    }
                                }

                                // Check for underruns and trigger recovery if needed
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

                    // Process navigation buffer
                    navBuffer?.let { buffer ->
                        navTrack?.let { track ->
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                // Get current buffer fill level
                                val currentNavFillMs = buffer.fillLevelMs()

                                // Pre-fill check for navigation (shorter threshold for lower latency)
                                if (!navStarted) {
                                    if (currentNavFillMs < prefillThresholdMs / 2) {
                                        return@let
                                    }
                                    navStarted = true
                                    val waitTimeMs = System.currentTimeMillis() - navStartTime
                                    AudioDebugLogger.logNavPrefillComplete(currentNavFillMs, waitTimeMs)
                                    log("[AUDIO] Nav pre-fill complete: ${currentNavFillMs}ms buffered, starting playback")
                                }

                                // Enforce minimum buffer level during playback (use half for nav - lower latency)
                                val navMinBufferMs = minBufferLevelMs / 2
                                if (currentNavFillMs <= navMinBufferMs) {
                                    // Buffer too low - let it build up
                                    return@let
                                }

                                val available = buffer.availableForRead()
                                if (available > 0) {
                                    // Calculate how much we can read while maintaining minimum buffer level
                                    val bytesPerMs =
                                        (navFormat?.sampleRate ?: audioConfig.sampleRate) * (navFormat?.channelCount ?: 2) * 2 / 1000
                                    val maxReadableMs = currentNavFillMs - navMinBufferMs
                                    val maxReadableBytes = maxReadableMs * bytesPerMs
                                    val toRead = minOf(available, playbackChunkSize, maxReadableBytes.coerceAtLeast(0))

                                    if (toRead > 0) {
                                        val bytesRead = buffer.read(navTempBuffer, 0, toRead)
                                        if (bytesRead > 0) {
                                            // Use WRITE_NON_BLOCKING to prevent thread starvation
                                            // across multiple streams (media/nav/voice/call)
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
                                                // Log nav track writes (throttled - only when buffer is low)
                                                AudioDebugLogger.logNavTrackWrite(written, buffer.fillLevelMs())
                                            }
                                            didWork = true
                                        }
                                    }
                                }

                                // Check for underruns
                                val underruns = track.underrunCount
                                if (underruns > navUnderruns) {
                                    val newUnderruns = underruns - navUnderruns
                                    navUnderruns = underruns
                                    AudioDebugLogger.logTrackUnderrun("NAV", underruns)
                                    log(
                                        "[AUDIO_UNDERRUN] Nav underrun detected: " +
                                            "+$newUnderruns (total: $underruns)",
                                    )
                                }
                            }
                        }
                    }

                    // Process voice assistant buffer (USAGE_ASSISTANT → CarAudioContext.VOICE_COMMAND)
                    voiceBuffer?.let { buffer ->
                        voiceTrack?.let { track ->
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                // Pre-fill check for voice assistant
                                if (!voiceStarted) {
                                    val fillMs = buffer.fillLevelMs()
                                    if (fillMs < prefillThresholdMs / 2) {
                                        return@let
                                    }
                                    voiceStarted = true
                                    log("[AUDIO] Voice pre-fill complete: ${fillMs}ms buffered, starting playback")
                                }

                                val available = buffer.availableForRead()
                                if (available > 0) {
                                    val toRead = minOf(available, playbackChunkSize)
                                    val bytesRead = buffer.read(voiceTempBuffer, 0, toRead)
                                    if (bytesRead > 0) {
                                        // Use WRITE_NON_BLOCKING to prevent thread starvation
                                        // across multiple streams (media/nav/voice/call)
                                        val written =
                                            track.write(
                                                voiceTempBuffer,
                                                0,
                                                bytesRead,
                                                AudioTrack.WRITE_NON_BLOCKING,
                                            )
                                        if (written < 0) {
                                            handleTrackError("VOICE", written)
                                        }
                                        didWork = true
                                    }
                                }

                                // Check for underruns
                                val underruns = track.underrunCount
                                if (underruns > voiceUnderruns) {
                                    val newUnderruns = underruns - voiceUnderruns
                                    voiceUnderruns = underruns
                                    AudioDebugLogger.logTrackUnderrun("VOICE", underruns)
                                    log(
                                        "[AUDIO_UNDERRUN] Voice underrun detected: " +
                                            "+$newUnderruns (total: $underruns)",
                                    )
                                }
                            }
                        }
                    }

                    // Process phone call buffer (USAGE_VOICE_COMMUNICATION → CarAudioContext.CALL)
                    callBuffer?.let { buffer ->
                        callTrack?.let { track ->
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                // Pre-fill check for phone calls
                                if (!callStarted) {
                                    val fillMs = buffer.fillLevelMs()
                                    if (fillMs < prefillThresholdMs / 2) {
                                        return@let
                                    }
                                    callStarted = true
                                    log("[AUDIO] Call pre-fill complete: ${fillMs}ms buffered, starting playback")
                                }

                                val available = buffer.availableForRead()
                                if (available > 0) {
                                    val toRead = minOf(available, playbackChunkSize)
                                    val bytesRead = buffer.read(callTempBuffer, 0, toRead)
                                    if (bytesRead > 0) {
                                        // Use WRITE_NON_BLOCKING to prevent thread starvation
                                        // across multiple streams (media/nav/voice/call)
                                        val written =
                                            track.write(
                                                callTempBuffer,
                                                0,
                                                bytesRead,
                                                AudioTrack.WRITE_NON_BLOCKING,
                                            )
                                        if (written < 0) {
                                            handleTrackError("CALL", written)
                                        }
                                        didWork = true
                                    }
                                }

                                // Check for underruns
                                val underruns = track.underrunCount
                                if (underruns > callUnderruns) {
                                    val newUnderruns = underruns - callUnderruns
                                    callUnderruns = underruns
                                    AudioDebugLogger.logTrackUnderrun("CALL", underruns)
                                    log(
                                        "[AUDIO_UNDERRUN] Call underrun detected: " +
                                            "+$newUnderruns (total: $underruns)",
                                    )
                                }
                            }
                        }
                    }

                    // Small sleep if no work done to prevent busy-waiting
                    if (!didWork) {
                        sleep(5)
                    }

                    // Periodic performance logging
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
