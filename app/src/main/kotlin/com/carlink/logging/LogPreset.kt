package com.carlink.logging

import androidx.compose.ui.graphics.Color

/**
 * Performance logging presets matching Flutter log.dart LogPreset enum.
 * Each preset configures which log tags and levels are enabled.
 *
 * Ported from: lib/log.dart
 */
enum class LogPreset(
    val displayName: String,
    val description: String,
    val color: Color,
) {
    // Order matches Flutter log.dart LogPreset enum exactly
    SILENT(
        displayName = "Silent",
        description = "Only errors",
        color = Color(0xFFE57373), // red[300]
    ),
    MINIMAL(
        displayName = "Minimal",
        description = "Errors + warnings",
        color = Color(0xFFFFB74D), // orange[300]
    ),
    NORMAL(
        displayName = "Normal",
        description = "Standard operational logging",
        color = Color(0xFF64B5F6), // blue[300]
    ),
    DEBUG(
        displayName = "Debug",
        description = "Full debug (no raw data dumps)",
        color = Color(0xFF4DD0E1), // cyan[300]
    ),
    PERFORMANCE(
        displayName = "Performance",
        description = "Performance metrics only",
        color = Color(0xFFBA68C8), // purple[300]
    ),
    RX_MESSAGES(
        displayName = "Adapter Messages",
        description = "Raw messages (no video/audio)",
        color = Color(0xFF7986CB), // indigo[300]
    ),
    VIDEO_ONLY(
        displayName = "Video Only",
        description = "Video events + raw video data",
        color = Color(0xFF81C784), // green[300]
    ),
    AUDIO_ONLY(
        displayName = "Audio Only",
        description = "Audio events + raw audio data",
        color = Color(0xFFFFD54F), // amber[300]
    ),
    VIDEO_PIPELINE(
        displayName = "Video Pipeline",
        description = "Full video pipeline debug (USB→Ring→Codec→Surface)",
        color = Color(0xFFA5D6A7), // green[200]
    ),
    ;

    companion object {
        /**
         * Default preset for first launch
         */
        val DEFAULT = NORMAL

        /**
         * Get preset by index safely
         */
        fun fromIndex(index: Int): LogPreset = entries.getOrElse(index) { SILENT }
    }
}

/**
 * Extension to apply a LogPreset to the Logger system.
 * Matches Flutter setLogPreset() function behavior.
 */
fun LogPreset.apply() {
    when (this) {
        LogPreset.SILENT -> {
            Logger.setLogEnabled(true)
            Logger.setLogLevel(Logger.LogLevel.DEBUG, false)
            Logger.setLogLevel(Logger.LogLevel.INFO, false)
            Logger.setLogLevel(Logger.LogLevel.WARN, false)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
        }

        LogPreset.MINIMAL -> {
            Logger.setLogEnabled(true)
            Logger.setLogLevel(Logger.LogLevel.DEBUG, false)
            Logger.setLogLevel(Logger.LogLevel.INFO, false)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.enableAllTags()
            Logger.setTagsEnabled(listOf(Logger.Tags.SERIALIZE, Logger.Tags.TOUCH, Logger.Tags.AUDIO, Logger.Tags.MEDIA), false)
        }

        LogPreset.NORMAL -> {
            Logger.setLogEnabled(true)
            Logger.setLogLevel(Logger.LogLevel.DEBUG, false)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.enableAllTags()
            Logger.setTagsEnabled(listOf(Logger.Tags.SERIALIZE, Logger.Tags.TOUCH, Logger.Tags.MEDIA), false)
        }

        LogPreset.DEBUG -> {
            Logger.setLogEnabled(true)
            Logger.setLogLevel(Logger.LogLevel.DEBUG, true)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.enableAllTags()
            // Disable raw data dumps to prevent file bloat
            Logger.setTagsEnabled(listOf(Logger.Tags.VIDEO, Logger.Tags.AUDIO, Logger.Tags.USB_RAW), false)
        }

        LogPreset.PERFORMANCE -> {
            Logger.setLogEnabled(true)
            Logger.setLogLevel(Logger.LogLevel.DEBUG, false)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
            // Enable only performance-related tags
            Logger.setTagsEnabled(listOf(Logger.Tags.USB, Logger.Tags.ADAPTR, Logger.Tags.PLATFORM), true)
        }

        LogPreset.RX_MESSAGES -> {
            Logger.setLogEnabled(true)
            Logger.setLogLevel(Logger.LogLevel.DEBUG, true)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
            // Enable only raw message logging with minimal noise
            Logger.setTagsEnabled(listOf(Logger.Tags.USB_RAW, Logger.Tags.USB, Logger.Tags.ADAPTR), true)
        }

        LogPreset.VIDEO_ONLY -> {
            Logger.setLogEnabled(true)
            Logger.setLogLevel(Logger.LogLevel.DEBUG, true)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
            // Enable only video-related logging
            Logger.setTagsEnabled(listOf(Logger.Tags.VIDEO, Logger.Tags.USB, Logger.Tags.PLATFORM, Logger.Tags.CONFIG), true)
        }

        LogPreset.AUDIO_ONLY -> {
            Logger.setLogEnabled(true)
            Logger.setLogLevel(Logger.LogLevel.DEBUG, true)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
            // Enable only audio-related logging
            Logger.setTagsEnabled(
                listOf(Logger.Tags.AUDIO, Logger.Tags.MIC, Logger.Tags.USB, Logger.Tags.PLATFORM, Logger.Tags.CONFIG),
                true,
            )
        }

        LogPreset.VIDEO_PIPELINE -> {
            Logger.setLogEnabled(true)
            Logger.setLogLevel(Logger.LogLevel.DEBUG, true)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
            // Enable all video pipeline debug tags for comprehensive troubleshooting
            // This enables throttled high-frequency logging at each pipeline stage
            Logger.setTagsEnabled(
                listOf(
                    Logger.Tags.VIDEO,
                    Logger.Tags.VIDEO_USB,
                    Logger.Tags.VIDEO_RING_BUFFER,
                    Logger.Tags.VIDEO_CODEC,
                    Logger.Tags.VIDEO_SURFACE,
                    Logger.Tags.VIDEO_PERF,
                    Logger.Tags.H264_RENDERER,
                    Logger.Tags.USB,
                    Logger.Tags.ADAPTR,
                ),
                true,
            )
            // Also enable debug logging flag for VideoDebugLogger (Java)
            com.carlink.util.VideoDebugLogger
                .setDebugEnabled(true)
        }
    }
}
