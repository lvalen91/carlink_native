package com.carlink.logging

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Centralized Logging System for Carlink Native
 *
 * Provides tag-based filtering, multiple output destinations, and
 * thread-safe logging operations.
 *
 * Ported from: lib/log.dart
 */
object Logger {
    private const val TAG = "CARLINK"

    /**
     * Log levels matching Android Log levels.
     */
    enum class Level(
        val priority: Int,
    ) {
        VERBOSE(Log.VERBOSE),
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
    }

    /**
     * Log level enum matching Flutter for LogPreset compatibility
     */
    enum class LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR,
    }

    /**
     * Known log tags for filtering.
     */
    object Tags {
        const val USB = "USB"
        const val VIDEO = "VIDEO"
        const val AUDIO = "AUDIO"
        const val MIC = "MIC"
        const val H264_RENDERER = "H264_RENDERER"
        const val PLATFORM = "PLATFORM"
        const val SERIALIZE = "SERIALIZE"
        const val COMMAND = "COMMAND"
        const val TOUCH = "TOUCH"
        const val CONFIG = "CONFIG"
        const val ADAPTR = "ADAPTR"
        const val PHONE = "PHONE"
        const val MEDIA = "MEDIA"
        const val MEDIA_SESSION = "MEDIA_SESSION"
        const val FILE_LOG = "FILE_LOG"
        const val USB_RAW = "USB_RAW"
        const val AUDIO_DEBUG = "AUDIO_DEBUG"
        const val ERROR_RECOVERY = "ERROR_RECOVERY"

        // Video Pipeline Debug Tags (comprehensive video troubleshooting)
        const val VIDEO_USB = "VIDEO_USB" // USB receive layer
        const val VIDEO_RING_BUFFER = "VIDEO_RING" // Ring buffer operations
        const val VIDEO_CODEC = "VIDEO_CODEC" // MediaCodec operations
        const val VIDEO_SURFACE = "VIDEO_SURFACE" // Surface/rendering
        const val VIDEO_PERF = "VIDEO_PERF" // Performance metrics
    }

    /**
     * Debug-only logging control.
     * When false, verbose debug logs are stripped from release builds.
     * This is set based on BuildConfig.DEBUG at runtime.
     */
    @Volatile
    private var debugLoggingEnabled = true

    /**
     * Enable or disable debug-only logging.
     * Call this with BuildConfig.DEBUG on app startup.
     */
    fun setDebugLoggingEnabled(enabled: Boolean) {
        debugLoggingEnabled = enabled
        if (!enabled) {
            // In release mode, disable verbose video pipeline tags
            disableTag(Tags.VIDEO_USB)
            disableTag(Tags.VIDEO_RING_BUFFER)
            disableTag(Tags.VIDEO_CODEC)
            disableTag(Tags.VIDEO_SURFACE)
            disableTag(Tags.USB_RAW)
            disableTag(Tags.AUDIO_DEBUG)
        }
    }

    /**
     * Check if debug logging is enabled (for performance-sensitive code paths).
     */
    fun isDebugLoggingEnabled(): Boolean = debugLoggingEnabled

    /**
     * Listener interface for log events.
     */
    interface LogListener {
        fun onLog(
            level: Level,
            tag: String?,
            message: String,
            timestamp: Long,
        )
    }

    private val enabledTags = ConcurrentHashMap<String, Boolean>()
    private val listeners = CopyOnWriteArrayList<LogListener>()
    private var minLevel = Level.DEBUG

    // Default enabled tags
    init {
        enableTag(Tags.USB)
        enableTag(Tags.VIDEO)
        enableTag(Tags.AUDIO)
        enableTag(Tags.ADAPTR)
        enableTag(Tags.PHONE)
        enableTag(Tags.MEDIA_SESSION)
        enableTag(Tags.ERROR_RECOVERY)
    }

    /**
     * Enable logging for a specific tag.
     */
    fun enableTag(tag: String) {
        enabledTags[tag] = true
    }

    /**
     * Disable logging for a specific tag.
     */
    fun disableTag(tag: String) {
        enabledTags[tag] = false
    }

    /**
     * Check if a tag is enabled.
     */
    fun isTagEnabled(tag: String): Boolean {
        return enabledTags[tag] ?: true // Default to enabled
    }

    /**
     * Set the minimum log level.
     */
    fun setMinLevel(level: Level) {
        minLevel = level
    }

    // ==================== LogPreset Support Methods ====================

    private var logEnabled = true
    private val logLevelEnabled =
        ConcurrentHashMap<LogLevel, Boolean>().apply {
            LogLevel.entries.forEach { put(it, true) }
        }

    /**
     * Enable or disable all logging globally.
     */
    fun setLogEnabled(enabled: Boolean) {
        logEnabled = enabled
    }

    /**
     * Check if logging is globally enabled.
     */
    fun isLogEnabled(): Boolean = logEnabled

    /**
     * Set whether a specific log level is enabled.
     */
    fun setLogLevel(
        level: LogLevel,
        enabled: Boolean,
    ) {
        logLevelEnabled[level] = enabled
        // Also update minLevel for Android logging
        if (!enabled) {
            val newMinLevel =
                when {
                    logLevelEnabled[LogLevel.DEBUG] == true -> Level.DEBUG
                    logLevelEnabled[LogLevel.INFO] == true -> Level.INFO
                    logLevelEnabled[LogLevel.WARN] == true -> Level.WARN
                    logLevelEnabled[LogLevel.ERROR] == true -> Level.ERROR
                    else -> Level.ERROR
                }
            minLevel = newMinLevel
        }
    }

    /**
     * Check if a specific log level is enabled.
     */
    fun isLogLevelEnabled(level: LogLevel): Boolean = logLevelEnabled[level] ?: true

    /**
     * Enable multiple tags at once.
     */
    fun setTagsEnabled(
        tags: List<String>,
        enabled: Boolean,
    ) {
        tags.forEach { tag ->
            enabledTags[tag] = enabled
        }
    }

    /**
     * Disable all tags.
     */
    fun disableAllTags() {
        enabledTags.keys.forEach { tag ->
            enabledTags[tag] = false
        }
    }

    /**
     * Enable all tags.
     */
    fun enableAllTags() {
        enabledTags.keys.forEach { tag ->
            enabledTags[tag] = true
        }
        // Also enable known tags
        listOf(
            Tags.USB,
            Tags.VIDEO,
            Tags.AUDIO,
            Tags.MIC,
            Tags.H264_RENDERER,
            Tags.PLATFORM,
            Tags.SERIALIZE,
            Tags.COMMAND,
            Tags.TOUCH,
            Tags.CONFIG,
            Tags.ADAPTR,
            Tags.PHONE,
            Tags.MEDIA,
            Tags.MEDIA_SESSION,
            Tags.FILE_LOG,
            Tags.USB_RAW,
            Tags.AUDIO_DEBUG,
            Tags.ERROR_RECOVERY,
        ).forEach { tag ->
            enabledTags[tag] = true
        }
    }

    /**
     * Get current logging status for debugging.
     */
    fun getLoggingStatus(): Map<String, Any> =
        mapOf(
            "enabled" to logEnabled,
            "levels" to logLevelEnabled.toMap(),
            "tags" to enabledTags.toMap(),
        )

    /**
     * Add a log listener.
     */
    fun addListener(listener: LogListener) {
        listeners.add(listener)
    }

    /**
     * Remove a log listener.
     */
    fun removeListener(listener: LogListener) {
        listeners.remove(listener)
    }

    /**
     * Log a message at VERBOSE level.
     */
    fun v(
        message: String,
        tag: String? = null,
    ) {
        log(Level.VERBOSE, tag, message)
    }

    /**
     * Log a message at DEBUG level.
     */
    fun d(
        message: String,
        tag: String? = null,
    ) {
        log(Level.DEBUG, tag, message)
    }

    /**
     * Log a message at INFO level.
     */
    fun i(
        message: String,
        tag: String? = null,
    ) {
        log(Level.INFO, tag, message)
    }

    /**
     * Log a message at WARN level.
     */
    fun w(
        message: String,
        tag: String? = null,
    ) {
        log(Level.WARN, tag, message)
    }

    /**
     * Log a message at ERROR level.
     */
    fun e(
        message: String,
        tag: String? = null,
        throwable: Throwable? = null,
    ) {
        log(Level.ERROR, tag, message, throwable)
    }

    /**
     * Core logging function.
     */
    fun log(
        level: Level,
        tag: String?,
        message: String,
        throwable: Throwable? = null,
    ) {
        // Check level
        if (level.priority < minLevel.priority) {
            return
        }

        // Check tag filter
        if (tag != null && !isTagEnabled(tag)) {
            return
        }

        val timestamp = System.currentTimeMillis()
        val formattedMessage = if (tag != null) "[$tag] $message" else message

        // Log to Android Logcat
        when (level) {
            Level.VERBOSE -> Log.v(TAG, formattedMessage, throwable)
            Level.DEBUG -> Log.d(TAG, formattedMessage, throwable)
            Level.INFO -> Log.i(TAG, formattedMessage, throwable)
            Level.WARN -> Log.w(TAG, formattedMessage, throwable)
            Level.ERROR -> Log.e(TAG, formattedMessage, throwable)
        }

        // Notify listeners
        for (listener in listeners) {
            try {
                listener.onLog(level, tag, message, timestamp)
            } catch (e: Exception) {
                Log.e(TAG, "Error in log listener: ${e.message}")
            }
        }
    }
}

// ==================== Extension Functions ====================

/**
 * Log a debug message.
 */
fun logDebug(
    message: String,
    tag: String? = null,
) {
    Logger.d(message, tag)
}

/**
 * Log an info message.
 */
fun logInfo(
    message: String,
    tag: String? = null,
) {
    Logger.i(message, tag)
}

/**
 * Log a warning message.
 */
fun logWarn(
    message: String,
    tag: String? = null,
) {
    Logger.w(message, tag)
}

/**
 * Log an error message.
 */
fun logError(
    message: String,
    tag: String? = null,
    throwable: Throwable? = null,
) {
    Logger.e(message, tag, throwable)
}

/**
 * Log a message (default to debug level).
 */
fun log(
    message: String,
    tag: String? = null,
) {
    Logger.d(message, tag)
}

/**
 * Check if a tag is enabled for logging.
 */
fun isTagEnabled(tag: String): Boolean = Logger.isTagEnabled(tag)

// ==================== Debug-Only Logging Functions ====================
// These functions are designed for verbose debug logging that should be
// disabled in release builds for performance. They check isDebugLoggingEnabled()
// before logging to avoid string concatenation overhead in release builds.

/**
 * Log a debug-only message. No-op in release builds.
 * Use for high-frequency verbose logging in hot paths.
 */
inline fun logDebugOnly(
    tag: String,
    message: () -> String,
) {
    if (Logger.isDebugLoggingEnabled() && Logger.isTagEnabled(tag)) {
        Logger.d(message(), tag)
    }
}

/**
 * Log video pipeline USB receive events (debug only).
 */
inline fun logVideoUsb(message: () -> String) {
    logDebugOnly(Logger.Tags.VIDEO_USB, message)
}

/**
 * Log video pipeline ring buffer events (debug only).
 */
inline fun logVideoRingBuffer(message: () -> String) {
    logDebugOnly(Logger.Tags.VIDEO_RING_BUFFER, message)
}

/**
 * Log video pipeline codec events (debug only).
 */
inline fun logVideoCodec(message: () -> String) {
    logDebugOnly(Logger.Tags.VIDEO_CODEC, message)
}

/**
 * Log video pipeline surface events (debug only).
 */
inline fun logVideoSurface(message: () -> String) {
    logDebugOnly(Logger.Tags.VIDEO_SURFACE, message)
}

/**
 * Log video pipeline performance metrics (debug only).
 */
inline fun logVideoPerf(message: () -> String) {
    logDebugOnly(Logger.Tags.VIDEO_PERF, message)
}
