package com.carlink.platform

import android.media.AudioTrack

/**
 * AudioConfig - Configuration for audio playback behavior.
 *
 * PURPOSE:
 * Provides platform-specific AudioTrack settings to optimize playback on GM AAOS.
 * GM AAOS denies AUDIO_OUTPUT_FLAG_FAST for third-party apps, requiring compensating
 * adjustments to buffer sizes and performance modes.
 *
 * GM AAOS ISSUES:
 * - FAST track denial: AudioFlinger denies low-latency path for non-system apps
 * - Native sample rate: 48kHz; non-matching rates trigger resampling
 * - Resampling: Further degrades FAST track eligibility and increases latency
 *
 * CONFIGURATION SELECTION:
 * - DEFAULT: Standard settings for ARM platforms
 * - GM_AAOS: Optimized for Intel GM AAOS (48kHz, larger buffers, no LOW_LATENCY)
 *
 * Reference:
 * - https://source.android.com/docs/core/audio/latency/design
 * - https://developer.android.com/reference/android/media/AudioTrack
 */
data class AudioConfig(
    /**
     * Target sample rate in Hz.
     * GM AAOS native rate is 48000Hz; using this avoids resampling.
     * Standard Android devices typically use 44100Hz.
     */
    val sampleRate: Int,
    /**
     * Buffer size multiplier applied to AudioTrack.getMinBufferSize().
     * Higher values provide more jitter tolerance at the cost of latency.
     * - 5x: Standard (good for FAST track path)
     * - 8x: GM AAOS (compensates for non-FAST path latency)
     */
    val bufferMultiplier: Int,
    /**
     * AudioTrack performance mode.
     * - PERFORMANCE_MODE_LOW_LATENCY: Requests FAST track (may be denied)
     * - PERFORMANCE_MODE_NONE: No preference (appropriate when FAST denied anyway)
     *
     * Reference: https://developer.android.com/reference/android/media/AudioTrack#PERFORMANCE_MODE_LOW_LATENCY
     */
    val performanceMode: Int,
    /**
     * Pre-fill threshold in milliseconds.
     * Minimum buffer level before starting playback to prevent initial underruns.
     * Higher values on GM AAOS due to longer latency path.
     */
    val prefillThresholdMs: Int,
    /**
     * Media stream ring buffer capacity in milliseconds.
     * Larger on GM AAOS to absorb USB packet jitter during video stalls.
     */
    val mediaBufferCapacityMs: Int,
    /**
     * Navigation stream ring buffer capacity in milliseconds.
     * Navigation prompts have lower latency requirements than media.
     */
    val navBufferCapacityMs: Int,
) {
    companion object {
        /**
         * Default configuration for ARM platforms (Raspberry Pi, standard Android devices).
         * Uses low-latency mode which works well when FAST track is available.
         *
         * Buffer multiplier reduced from 5x to 4x based on USB capture analysis showing
         * consistent packet delivery with P99 jitter of only 7ms. Ring buffer provides
         * primary jitter absorption; AudioTrack buffer is secondary safety net.
         *
         * Pre-fill threshold reduced from 150ms to 80ms (slightly > 1 packet at 60-65ms)
         * based on observed consistent packet timing.
         */
        val DEFAULT =
            AudioConfig(
                sampleRate = 44100,
                bufferMultiplier = 4,
                performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
                prefillThresholdMs = 80,
                mediaBufferCapacityMs = 500,
                navBufferCapacityMs = 200,
            )

        /**
         * Configuration for Intel GM AAOS devices.
         * Optimized for FAST track denial and 48kHz native rate.
         *
         * Key differences from DEFAULT:
         * - sampleRate = 48000: Matches GM native rate, avoids resampling
         * - bufferMultiplier = 4: Reduced from 8x based on USB capture analysis
         *   showing consistent packet delivery. Ring buffer handles jitter.
         * - performanceMode = NONE: FAST denied anyway, don't request it
         * - prefillThresholdMs = 80: Reduced from 200ms based on observed
         *   consistent 60ms packet timing
         * - mediaBufferCapacityMs = 750: Larger ring buffer for stall absorption
         * - navBufferCapacityMs = 300: Larger nav buffer for reliability
         */
        val GM_AAOS =
            AudioConfig(
                sampleRate = 48000,
                bufferMultiplier = 4,
                performanceMode = AudioTrack.PERFORMANCE_MODE_NONE,
                prefillThresholdMs = 80,
                mediaBufferCapacityMs = 750,
                navBufferCapacityMs = 300,
            )

        /**
         * Select appropriate configuration based on platform detection.
         *
         * CRITICAL: Only applies GM AAOS audio fixes when BOTH conditions are true:
         * 1. Intel x86/x86_64 architecture
         * 2. GM AAOS device (Harman_Samsung / gminfo)
         *
         * ARM-based GM AAOS devices will use DEFAULT config.
         *
         * @param platformInfo Platform detection results
         * @param userSampleRate Optional user-configured sample rate (overrides platform default)
         * @return Appropriate AudioConfig for the platform
         */
        fun forPlatform(
            platformInfo: PlatformDetector.PlatformInfo,
            userSampleRate: Int? = null,
        ): AudioConfig {
            // Determine effective sample rate: user preference > platform native
            val effectiveSampleRate = userSampleRate ?: platformInfo.nativeSampleRate

            return when {
                // Intel GM AAOS - apply all audio optimizations
                platformInfo.requiresGmAaosAudioFixes() -> {
                    GM_AAOS.copy(sampleRate = effectiveSampleRate)
                }

                // Intel non-GM - use native rate with optimized buffers
                // Reduced from 6x to 4x based on USB capture analysis
                platformInfo.requiresIntelMediaCodecFixes() -> {
                    DEFAULT.copy(
                        sampleRate = effectiveSampleRate,
                        bufferMultiplier = 4,
                    )
                }

                // ARM (including ARM-based GM AAOS) - use native sample rate to avoid resampling
                // Pi AAOS native rate is 48000Hz; using DEFAULT's 44100Hz causes resampling artifacts
                // Buffer multiplier reduced from 10x to 4x, prefill from 250ms to 80ms based on
                // USB capture analysis showing consistent packet delivery with P99 jitter of 7ms.
                // Ring buffer capacity kept larger for additional safety on varied ARM platforms.
                else -> {
                    DEFAULT.copy(
                        sampleRate = effectiveSampleRate,
                        bufferMultiplier = 4,
                        prefillThresholdMs = 80,
                        mediaBufferCapacityMs = 1000, // Keep larger ring buffer for ARM platform variance
                        navBufferCapacityMs = 400,
                    )
                }
            }
        }
    }
}
