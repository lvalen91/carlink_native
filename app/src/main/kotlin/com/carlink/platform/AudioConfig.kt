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
    /** Target sample rate (48000Hz avoids resampling on GM AAOS). */
    val sampleRate: Int,
    /** Buffer multiplier on minBufferSize (4x typical, higher = more jitter tolerance). */
    val bufferMultiplier: Int,
    /** AudioTrack performance mode (LOW_LATENCY or NONE for GM AAOS). */
    val performanceMode: Int,
    /** Min buffer level before playback starts (prevents initial underruns). */
    val prefillThresholdMs: Int,
    /** Media ring buffer capacity (larger on GM AAOS for stall absorption). */
    val mediaBufferCapacityMs: Int,
    /** Nav ring buffer capacity (lower latency requirements than media). */
    val navBufferCapacityMs: Int,
) {
    companion object {
        /** ARM platforms. FAST track available, 4x buffer, 80ms prefill (P99 jitter ~7ms). */
        val DEFAULT =
            AudioConfig(
                sampleRate = 44100,
                bufferMultiplier = 4,
                performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
                prefillThresholdMs = 80,
                mediaBufferCapacityMs = 500,
                navBufferCapacityMs = 200,
            )

        /** Intel GM AAOS. 48kHz native, FAST denied, larger buffers for stall absorption. */
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
         * Select config based on platform. GM AAOS audio fixes require BOTH:
         * (1) Intel x86/x86_64 architecture, (2) GM AAOS device.
         */
        fun forPlatform(
            platformInfo: PlatformDetector.PlatformInfo,
            userSampleRate: Int? = null,
        ): AudioConfig {
            val effectiveSampleRate = userSampleRate ?: platformInfo.nativeSampleRate

            return when {
                platformInfo.requiresGmAaosAudioFixes() -> {
                    GM_AAOS.copy(sampleRate = effectiveSampleRate)
                }

                platformInfo.requiresIntelMediaCodecFixes() -> {
                    DEFAULT.copy(
                        sampleRate = effectiveSampleRate,
                        bufferMultiplier = 4,
                    )
                }

                // ARM: larger ring buffers for platform variance
                else -> {
                    DEFAULT.copy(
                        sampleRate = effectiveSampleRate,
                        bufferMultiplier = 4,
                        prefillThresholdMs = 80,
                        mediaBufferCapacityMs = 1000,
                        navBufferCapacityMs = 400,
                    )
                }
            }
        }
    }
}
