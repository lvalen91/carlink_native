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
         */
        val DEFAULT =
            AudioConfig(
                sampleRate = 44100,
                bufferMultiplier = 5,
                performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
                prefillThresholdMs = 150,
                mediaBufferCapacityMs = 500,
                navBufferCapacityMs = 200,
            )

        /**
         * Configuration for Intel GM AAOS devices.
         * Optimized for FAST track denial and 48kHz native rate.
         *
         * Key differences from DEFAULT:
         * - sampleRate = 48000: Matches GM native rate, avoids resampling
         * - bufferMultiplier = 8: Larger buffer for non-FAST path
         * - performanceMode = NONE: FAST denied anyway, don't request it
         * - prefillThresholdMs = 200: More pre-buffering before playback
         * - mediaBufferCapacityMs = 750: Larger ring buffer for stall absorption
         * - navBufferCapacityMs = 300: Larger nav buffer for reliability
         */
        val GM_AAOS =
            AudioConfig(
                sampleRate = 48000,
                bufferMultiplier = 8,
                performanceMode = AudioTrack.PERFORMANCE_MODE_NONE,
                prefillThresholdMs = 200,
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

                // Intel non-GM - use native rate with slightly increased buffers
                platformInfo.requiresIntelMediaCodecFixes() -> {
                    DEFAULT.copy(
                        sampleRate = effectiveSampleRate,
                        bufferMultiplier = 6,
                    )
                }

                // ARM (including ARM-based GM AAOS) - use native sample rate to avoid resampling
                // Pi AAOS native rate is 48000Hz; using DEFAULT's 44100Hz causes resampling artifacts
                // Use larger buffers similar to GM_AAOS to prevent underruns
                else -> {
                    DEFAULT.copy(
                        sampleRate = effectiveSampleRate,
                        bufferMultiplier = 10, // Increased from 6 to 10 for more headroom
                        prefillThresholdMs = 250, // Increased from 180 to 250ms
                        mediaBufferCapacityMs = 1000, // Increased from 600 to 1000ms (1 second)
                        navBufferCapacityMs = 400, // Increased from 250 to 400ms
                    )
                }
            }
        }
    }
}
