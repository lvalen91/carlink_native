package com.carlink.util;

/**
 * Callback interface for logging messages from native Android components.
 *
 * This interface provides a decoupled logging mechanism that allows Java/Kotlin components
 * (such as H264Renderer) to send diagnostic messages to the
 * centralized logging system.
 *
 * Usage example:
 * <pre>
 * LogCallback callback = message -> Log.d(TAG, message);
 * H264Renderer renderer = new H264Renderer(context, width, height, texture, id, callback);
 * </pre>
 */
public interface LogCallback {

    void log(String message);

    /** Log with an explicit tag (routed to Logger with proper tag). */
    default void log(String tag, String message) { log("[" + tag + "] " + message); }

    /** Performance/diagnostic log — gated by debug logging + tag in release builds. */
    default void logPerf(String tag, String message) { log(tag, message); }
}
