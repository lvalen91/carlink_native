package com.carlink.logging

import androidx.compose.ui.graphics.Color

/** Logging presets that configure which tags and levels are enabled. */
enum class LogPreset(
    val displayName: String,
    val description: String,
    val color: Color,
) {
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
    PIPELINE_DEBUG(
        displayName = "Pipeline Debug",
        description = "Full video + audio pipeline debug",
        color = Color(0xFFA5D6A7), // green[200]
    ),
    ;

    companion object {
        val DEFAULT = NORMAL

        fun fromIndex(index: Int): LogPreset = entries.getOrElse(index) { SILENT }
    }
}

/** Apply this preset to the Logger system. */
fun LogPreset.apply() {
    when (this) {
        LogPreset.SILENT -> {
            Logger.setLogLevel(Logger.LogLevel.DEBUG, false)
            Logger.setLogLevel(Logger.LogLevel.INFO, false)
            Logger.setLogLevel(Logger.LogLevel.WARN, false)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
            Logger.setDebugLoggingEnabled(com.carlink.BuildConfig.DEBUG)
        }

        LogPreset.MINIMAL -> {
            Logger.setLogLevel(Logger.LogLevel.DEBUG, false)
            Logger.setLogLevel(Logger.LogLevel.INFO, false)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.enableAllTags()
            Logger.setTagsEnabled(
                listOf(Logger.Tags.SERIALIZE, Logger.Tags.TOUCH, Logger.Tags.AUDIO, Logger.Tags.MEDIA),
                false,
            )
            Logger.setDebugLoggingEnabled(com.carlink.BuildConfig.DEBUG)
        }

        LogPreset.NORMAL -> {
            Logger.setLogLevel(Logger.LogLevel.DEBUG, false)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.enableAllTags()
            Logger.setTagsEnabled(listOf(Logger.Tags.SERIALIZE, Logger.Tags.TOUCH, Logger.Tags.MEDIA), false)
            Logger.setDebugLoggingEnabled(com.carlink.BuildConfig.DEBUG)
        }

        LogPreset.DEBUG -> {
            Logger.setLogLevel(Logger.LogLevel.DEBUG, true)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.enableAllTags()
            Logger.setTagsEnabled(listOf(Logger.Tags.VIDEO, Logger.Tags.AUDIO, Logger.Tags.USB_RAW), false)
            Logger.setDebugLoggingEnabled(true)
        }

        LogPreset.PERFORMANCE -> {
            Logger.setLogLevel(Logger.LogLevel.DEBUG, false)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
            Logger.setTagsEnabled(
                listOf(
                    Logger.Tags.USB, Logger.Tags.ADAPTR, Logger.Tags.PLATFORM,
                    Logger.Tags.VIDEO_PERF, Logger.Tags.AUDIO_PERF,
                ),
                true,
            )
            Logger.setDebugLoggingEnabled(true)
        }

        LogPreset.RX_MESSAGES -> {
            Logger.setLogLevel(Logger.LogLevel.DEBUG, true)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
            Logger.setTagsEnabled(listOf(Logger.Tags.USB_RAW, Logger.Tags.USB, Logger.Tags.ADAPTR), true)
        }

        LogPreset.VIDEO_ONLY -> {
            Logger.setLogLevel(Logger.LogLevel.DEBUG, true)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
            Logger.setTagsEnabled(
                listOf(
                    Logger.Tags.VIDEO,
                    Logger.Tags.H264_RENDERER,
                    Logger.Tags.VIDEO_PERF,
                    Logger.Tags.ADAPTR,
                    Logger.Tags.USB,
                    Logger.Tags.PLATFORM,
                    Logger.Tags.CONFIG,
                ),
                true,
            )
            Logger.setDebugLoggingEnabled(true)
        }

        LogPreset.AUDIO_ONLY -> {
            Logger.setLogLevel(Logger.LogLevel.DEBUG, true)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
            Logger.setTagsEnabled(
                listOf(
                    Logger.Tags.AUDIO,
                    Logger.Tags.MIC,
                    Logger.Tags.AUDIO_PERF,
                    Logger.Tags.ADAPTR,
                    Logger.Tags.USB,
                    Logger.Tags.PLATFORM,
                    Logger.Tags.CONFIG,
                ),
                true,
            )
            Logger.setDebugLoggingEnabled(true)
        }

        LogPreset.PIPELINE_DEBUG -> {
            Logger.setLogLevel(Logger.LogLevel.DEBUG, true)
            Logger.setLogLevel(Logger.LogLevel.INFO, true)
            Logger.setLogLevel(Logger.LogLevel.WARN, true)
            Logger.setLogLevel(Logger.LogLevel.ERROR, true)
            Logger.disableAllTags()
            Logger.setTagsEnabled(
                listOf(
                    Logger.Tags.VIDEO,
                    Logger.Tags.VIDEO_USB,
                    Logger.Tags.VIDEO_RING_BUFFER,
                    Logger.Tags.VIDEO_CODEC,
                    Logger.Tags.VIDEO_SURFACE,
                    Logger.Tags.VIDEO_PERF,
                    Logger.Tags.H264_RENDERER,
                    Logger.Tags.AUDIO,
                    Logger.Tags.AUDIO_PERF,
                    Logger.Tags.MIC,
                    Logger.Tags.USB,
                    Logger.Tags.ADAPTR,
                ),
                true,
            )
            // Override release-build restriction for field diagnostics
            Logger.setDebugLoggingEnabled(true)
        }
    }
}
