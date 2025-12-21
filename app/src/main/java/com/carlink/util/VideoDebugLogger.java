package com.carlink.util;

import android.util.Log;
import java.util.Locale;

/**
 * Video Pipeline Debug Logger
 *
 * Provides comprehensive logging for video pipeline troubleshooting.
 * Designed to be easily enabled/disabled for debug APK vs release APK.
 *
 * USAGE:
 * - Call setDebugEnabled(BuildConfig.DEBUG) on app startup
 * - Use logXxx() methods throughout video pipeline
 * - In release builds, all logging is no-op (zero overhead)
 *
 * PIPELINE STAGES:
 * 1. USB     - USB bulk transfer reception
 * 2. RING    - Ring buffer write/read operations
 * 3. CODEC   - MediaCodec input/output/callbacks
 * 4. SURFACE - Surface rendering events
 * 5. PERF    - Performance metrics and statistics
 */
public class VideoDebugLogger {
    private static final String TAG = "CARLINK";

    // Master debug switch - set to false in release builds
    private static volatile boolean debugEnabled = true;

    // Individual stage switches for fine-grained control
    private static volatile boolean usbEnabled = true;
    private static volatile boolean ringBufferEnabled = true;
    private static volatile boolean codecEnabled = true;
    private static volatile boolean surfaceEnabled = true;
    private static volatile boolean perfEnabled = true;

    // Throttling for high-frequency logs
    private static long lastUsbLogTime = 0;
    private static long lastRingLogTime = 0;
    private static long lastCodecLogTime = 0;
    private static final long THROTTLE_INTERVAL_MS = 100; // Max 10 logs/sec per category

    // Frame counters for periodic logging
    private static long usbFrameCount = 0;
    private static long ringWriteCount = 0;
    private static long ringReadCount = 0;
    private static long codecInputCount = 0;
    private static long codecOutputCount = 0;

    /**
     * Enable or disable all debug logging.
     * Call this with BuildConfig.DEBUG on app startup.
     *
     * @param enabled true for debug builds, false for release
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        if (!enabled) {
            // Reset all counters when disabled
            usbFrameCount = 0;
            ringWriteCount = 0;
            ringReadCount = 0;
            codecInputCount = 0;
            codecOutputCount = 0;
        }
    }

    /**
     * Check if debug logging is enabled.
     */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * Enable/disable individual logging stages.
     */
    public static void setStageEnabled(String stage, boolean enabled) {
        switch (stage.toUpperCase(Locale.ROOT)) {
            case "USB":
                usbEnabled = enabled;
                break;
            case "RING":
                ringBufferEnabled = enabled;
                break;
            case "CODEC":
                codecEnabled = enabled;
                break;
            case "SURFACE":
                surfaceEnabled = enabled;
                break;
            case "PERF":
                perfEnabled = enabled;
                break;
        }
    }

    // ==================== USB Layer Logging ====================

    /**
     * Log USB video frame reception.
     * High frequency - throttled to prevent log spam.
     */
    public static void logUsbFrameReceived(int payloadLength, int headerType) {
        if (!debugEnabled || !usbEnabled) return;
        usbFrameCount++;

        long now = System.currentTimeMillis();
        if (now - lastUsbLogTime >= THROTTLE_INTERVAL_MS) {
            lastUsbLogTime = now;
            Log.d(TAG, "[VIDEO_USB] Frame #" + usbFrameCount +
                    " received: payload=" + payloadLength + "B, type=0x" +
                    Integer.toHexString(headerType));
        }
    }

    /**
     * Log USB video frame with resolution info.
     */
    public static void logUsbFrameWithResolution(int payloadLength, int width, int height) {
        if (!debugEnabled || !usbEnabled) return;
        usbFrameCount++;

        long now = System.currentTimeMillis();
        if (now - lastUsbLogTime >= THROTTLE_INTERVAL_MS) {
            lastUsbLogTime = now;
            Log.d(TAG, "[VIDEO_USB] Frame #" + usbFrameCount +
                    ": " + width + "x" + height + ", payload=" + payloadLength + "B");
        }
    }

    /**
     * Log USB read error (always logged, not throttled).
     */
    public static void logUsbError(String error) {
        if (!debugEnabled) return;
        Log.e(TAG, "[VIDEO_USB] ERROR: " + error);
    }

    /**
     * Log USB direct processing start.
     */
    public static void logUsbDirectProcessStart(int payloadLength) {
        if (!debugEnabled || !usbEnabled) return;
        Log.v(TAG, "[VIDEO_USB] Direct process start: " + payloadLength + "B");
    }

    // ==================== Ring Buffer Logging ====================

    /**
     * Log ring buffer write operation.
     * High frequency - throttled.
     */
    public static void logRingWrite(int length, int skipBytes, int packetCount) {
        if (!debugEnabled || !ringBufferEnabled) return;
        ringWriteCount++;

        long now = System.currentTimeMillis();
        if (now - lastRingLogTime >= THROTTLE_INTERVAL_MS) {
            lastRingLogTime = now;
            Log.d(TAG, "[VIDEO_RING] Write #" + ringWriteCount +
                    ": len=" + length + ", skip=" + skipBytes +
                    ", queued=" + packetCount);
        }
    }

    /**
     * Log ring buffer read operation.
     * High frequency - throttled.
     */
    public static void logRingRead(int length, int actualLength, int remainingPackets) {
        if (!debugEnabled || !ringBufferEnabled) return;
        ringReadCount++;

        long now = System.currentTimeMillis();
        if (now - lastRingLogTime >= THROTTLE_INTERVAL_MS) {
            lastRingLogTime = now;
            Log.d(TAG, "[VIDEO_RING] Read #" + ringReadCount +
                    ": raw=" + length + ", actual=" + actualLength +
                    ", remaining=" + remainingPackets);
        }
    }

    /**
     * Log ring buffer resize event (always logged - important).
     */
    public static void logRingResize(int oldSize, int newSize, int readPos, int writePos) {
        if (!debugEnabled) return;
        Log.w(TAG, "[VIDEO_RING] RESIZE: " + (oldSize / 1024 / 1024) + "MB -> " +
                (newSize / 1024 / 1024) + "MB, read=" + readPos + ", write=" + writePos);
    }

    /**
     * Log ring buffer emergency reset (always logged - critical).
     */
    public static void logRingEmergencyReset(int oldSize) {
        Log.e(TAG, "[VIDEO_RING] EMERGENCY RESET: was " + (oldSize / 1024 / 1024) +
                "MB, resetting to 1MB");
    }

    /**
     * Log ring buffer bounds error (always logged - critical).
     */
    public static void logRingBoundsError(String operation, int offset, int length, int bufferSize) {
        Log.e(TAG, "[VIDEO_RING] BOUNDS ERROR in " + operation +
                ": offset=" + offset + ", length=" + length + ", bufferSize=" + bufferSize);
    }

    // ==================== MediaCodec Logging ====================

    /**
     * Log MediaCodec input buffer queued.
     * High frequency - throttled.
     */
    public static void logCodecInputQueued(int bufferIndex, int dataSize) {
        if (!debugEnabled || !codecEnabled) return;
        codecInputCount++;

        long now = System.currentTimeMillis();
        if (now - lastCodecLogTime >= THROTTLE_INTERVAL_MS) {
            lastCodecLogTime = now;
            Log.d(TAG, "[VIDEO_CODEC] Input #" + codecInputCount +
                    ": buffer=" + bufferIndex + ", size=" + dataSize + "B");
        }
    }

    /**
     * Log MediaCodec output buffer released.
     * High frequency - throttled.
     */
    public static void logCodecOutputReleased(int bufferIndex, int size, boolean rendered) {
        if (!debugEnabled || !codecEnabled) return;
        codecOutputCount++;

        long now = System.currentTimeMillis();
        if (now - lastCodecLogTime >= THROTTLE_INTERVAL_MS) {
            lastCodecLogTime = now;
            Log.d(TAG, "[VIDEO_CODEC] Output #" + codecOutputCount +
                    ": buffer=" + bufferIndex + ", size=" + size +
                    ", rendered=" + rendered);
        }
    }

    /**
     * Log MediaCodec initialization (always logged).
     */
    public static void logCodecInit(String codecName, int width, int height) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_CODEC] Init: " + codecName + " @ " + width + "x" + height);
    }

    /**
     * Log MediaCodec format configured (always logged).
     */
    public static void logCodecConfigured(String format) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_CODEC] Configured: " + format);
    }

    /**
     * Log MediaCodec started (always logged).
     */
    public static void logCodecStarted() {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_CODEC] Started");
    }

    /**
     * Log MediaCodec stopped (always logged).
     */
    public static void logCodecStopped() {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_CODEC] Stopped");
    }

    /**
     * Log MediaCodec reset (always logged - important).
     */
    public static void logCodecReset(long resetCount) {
        if (!debugEnabled) return;
        Log.w(TAG, "[VIDEO_CODEC] Reset #" + resetCount);
    }

    /**
     * Log MediaCodec error (always logged).
     */
    public static void logCodecError(String error, boolean isRecoverable, boolean isTransient) {
        Log.e(TAG, "[VIDEO_CODEC] ERROR: " + error +
                " (recoverable=" + isRecoverable + ", transient=" + isTransient + ")");
    }

    /**
     * Log MediaCodec output format changed (always logged).
     */
    public static void logCodecFormatChanged(int width, int height, int colorFormat) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_CODEC] Format changed: " + width + "x" + height +
                ", color=" + colorFormat);
    }

    /**
     * Log available input buffer (verbose, throttled).
     */
    public static void logCodecInputAvailable(int bufferIndex, int queuedBuffers) {
        if (!debugEnabled || !codecEnabled) return;
        Log.v(TAG, "[VIDEO_CODEC] Input available: buffer=" + bufferIndex +
                ", queued=" + queuedBuffers);
    }

    // ==================== Surface Logging ====================

    /**
     * Log surface created (always logged).
     */
    public static void logSurfaceCreated(int width, int height) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_SURFACE] Created: " + width + "x" + height);
    }

    /**
     * Log surface changed (always logged).
     */
    public static void logSurfaceChanged(int width, int height, int format) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_SURFACE] Changed: " + width + "x" + height + ", format=" + format);
    }

    /**
     * Log surface destroyed (always logged).
     */
    public static void logSurfaceDestroyed() {
        if (!debugEnabled) return;
        Log.w(TAG, "[VIDEO_SURFACE] Destroyed");
    }

    /**
     * Log surface binding to codec (always logged).
     */
    public static void logSurfaceBound(boolean valid) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_SURFACE] Bound to codec: valid=" + valid);
    }

    // ==================== Performance Logging ====================

    /**
     * Log performance statistics (always logged when called).
     */
    public static void logPerformanceStats(double fps, long framesReceived, long framesDecoded,
                                           long framesDropped, double dropRate, double throughputMbps) {
        if (!debugEnabled || !perfEnabled) return;
        Log.i(TAG, String.format("[VIDEO_PERF] FPS: %.1f, Frames: R:%d/D:%d/Drop:%d, " +
                        "DropRate: %.1f%%, Throughput: %.1fMbps",
                fps, framesReceived, framesDecoded, framesDropped, dropRate, throughputMbps));
    }

    /**
     * Log buffer pool status (always logged when called).
     */
    public static void logBufferPoolStatus(int smallFree, int mediumFree, int largeFree, int total) {
        if (!debugEnabled || !perfEnabled) return;
        int totalFree = smallFree + mediumFree + largeFree;
        int utilization = ((total - totalFree) * 100) / total;
        Log.i(TAG, String.format("[VIDEO_PERF] Pool: %d/%d used (%d%%), small=%d, med=%d, large=%d",
                total - totalFree, total, utilization, smallFree, mediumFree, largeFree));
    }

    /**
     * Log ring buffer health (always logged when called).
     */
    public static void logRingBufferHealth(int packetCount, int bufferSize, int readPos, int writePos) {
        if (!debugEnabled || !perfEnabled) return;
        int usedBytes = (writePos >= readPos) ? (writePos - readPos) : (bufferSize - readPos + writePos);
        int utilization = (usedBytes * 100) / bufferSize;
        Log.i(TAG, String.format("[VIDEO_PERF] Ring: %d packets, %d/%dKB (%d%%), R:%d W:%d",
                packetCount, usedBytes / 1024, bufferSize / 1024, utilization, readPos, writePos));
    }

    /**
     * Log frame timing for latency analysis.
     */
    public static void logFrameTiming(long usbReceiveTime, long ringWriteTime,
                                      long codecQueueTime, long renderTime) {
        if (!debugEnabled || !perfEnabled) return;
        long totalLatency = renderTime - usbReceiveTime;
        Log.d(TAG, String.format("[VIDEO_PERF] Latency: total=%dms (USB->Ring=%dms, Ring->Codec=%dms, Codec->Render=%dms)",
                totalLatency,
                ringWriteTime - usbReceiveTime,
                codecQueueTime - ringWriteTime,
                renderTime - codecQueueTime));
    }

    /**
     * Get summary of frame counts for debugging.
     */
    public static String getFrameCountSummary() {
        return String.format(Locale.US, "USB:%d, RingW:%d, RingR:%d, CodecIn:%d, CodecOut:%d",
                usbFrameCount, ringWriteCount, ringReadCount, codecInputCount, codecOutputCount);
    }

    /**
     * Reset frame counters (call at session start).
     */
    public static void resetCounters() {
        usbFrameCount = 0;
        ringWriteCount = 0;
        ringReadCount = 0;
        codecInputCount = 0;
        codecOutputCount = 0;
    }
}
