package com.carlink.util;

import android.util.Log;
import java.util.Locale;

/**
 * Audio pipeline debug logging. Stages: USB → BUFFER → TRACK → STREAM → PERF.
 * Call setDebugEnabled(BuildConfig.DEBUG) on startup. No-op in release builds.
 */
public class AudioDebugLogger {
    private static final String TAG = "CARLINK_AUDIO_DEBUG";

    private static volatile boolean debugEnabled = true;
    private static volatile boolean usbEnabled = true;
    private static volatile boolean bufferEnabled = true;
    private static volatile boolean trackEnabled = true;
    private static volatile boolean streamEnabled = true;
    private static volatile boolean perfEnabled = true;

    private static long lastUsbLogTime = 0;
    private static long lastBufferLogTime = 0;
    private static long lastTrackLogTime = 0;
    private static final long THROTTLE_INTERVAL_MS = 100;

    private static long usbPacketCount = 0;
    private static long bufferWriteCount = 0;
    private static long bufferReadCount = 0;
    private static long trackWriteCount = 0;

    private static long mediaPackets = 0;
    private static long navPackets = 0;
    private static long voicePackets = 0;
    private static long callPackets = 0;

    private static long totalUnderruns = 0;
    private static long lastPerfLogTime = 0;
    private static final long PERF_LOG_INTERVAL_MS = 30000;

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        if (!enabled) {
            // Reset all counters when disabled
            usbPacketCount = 0;
            bufferWriteCount = 0;
            bufferReadCount = 0;
            trackWriteCount = 0;
            mediaPackets = 0;
            navPackets = 0;
            voicePackets = 0;
            callPackets = 0;
            totalUnderruns = 0;
        }
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setStageEnabled(String stage, boolean enabled) {
        switch (stage.toUpperCase(Locale.ROOT)) {
            case "USB":
                usbEnabled = enabled;
                break;
            case "BUFFER":
                bufferEnabled = enabled;
                break;
            case "TRACK":
                trackEnabled = enabled;
                break;
            case "STREAM":
                streamEnabled = enabled;
                break;
            case "PERF":
                perfEnabled = enabled;
                break;
            case "NAV":
                navEnabled = enabled;
                break;
            case "MIC":
                micEnabled = enabled;
                break;
        }
    }

    // USB Layer
    public static void logUsbReceive(int size, int audioType, int decodeType) {
        if (!debugEnabled || !usbEnabled) return;

        usbPacketCount++;

        // Track per-stream counts
        switch (audioType) {
            case 1: mediaPackets++; break;
            case 2: navPackets++; break;
            case 3: callPackets++; break;
            case 4: voicePackets++; break;
        }

        long now = System.currentTimeMillis();
        if (now - lastUsbLogTime >= THROTTLE_INTERVAL_MS) {
            lastUsbLogTime = now;
            Log.d(TAG, String.format("[AUDIO_USB] Packet #%d: size=%d type=%d decode=%d",
                    usbPacketCount, size, audioType, decodeType));
        }
    }

    public static void logUsbFiltered(int audioType, long totalFiltered) {
        if (!debugEnabled || !usbEnabled) return;

        // Log every 100th filtered packet
        if (totalFiltered == 1 || totalFiltered % 100 == 0) {
            Log.w(TAG, String.format("[AUDIO_USB] Filtered zero packet type=%d (total: %d)",
                    audioType, totalFiltered));
        }
    }

    // Buffer Layer
    public static void logBufferWrite(String streamName, int bytesWritten, int fillLevelMs, long overflowCount) {
        if (!debugEnabled || !bufferEnabled) return;

        bufferWriteCount++;

        long now = System.currentTimeMillis();
        if (now - lastBufferLogTime >= THROTTLE_INTERVAL_MS) {
            lastBufferLogTime = now;
            Log.d(TAG, String.format("[AUDIO_BUFFER] %s write: %dB, fill=%dms, overflow=%d",
                    streamName, bytesWritten, fillLevelMs, overflowCount));
        }
    }

    public static void logBufferRead(String streamName, int bytesRead, int fillLevelMs, long underflowCount) {
        if (!debugEnabled || !bufferEnabled) return;

        bufferReadCount++;

        if (fillLevelMs < 50 || underflowCount > 0) {
            Log.d(TAG, String.format("[AUDIO_BUFFER] %s read: %dB, fill=%dms, underflow=%d",
                    streamName, bytesRead, fillLevelMs, underflowCount));
        }
    }

    public static void logBufferOverflow(String streamName, int droppedBytes, int fillLevelMs) {
        if (!debugEnabled || !bufferEnabled) return;

        Log.w(TAG, String.format("[AUDIO_BUFFER] %s OVERFLOW: dropped %dB, fill=%dms",
                streamName, droppedBytes, fillLevelMs));
    }

    public static void logBufferUnderflow(String streamName, int requestedBytes, int availableBytes) {
        if (!debugEnabled || !bufferEnabled) return;

        Log.w(TAG, String.format("[AUDIO_BUFFER] %s UNDERFLOW: requested %dB, available %dB",
                streamName, requestedBytes, availableBytes));
    }

    // AudioTrack Layer
    public static void logTrackWrite(String streamName, int bytesWritten, String writeMode) {
        if (!debugEnabled || !trackEnabled) return;

        trackWriteCount++;

        long now = System.currentTimeMillis();
        if (now - lastTrackLogTime >= THROTTLE_INTERVAL_MS) {
            lastTrackLogTime = now;
            Log.d(TAG, String.format("[AUDIO_TRACK] %s write: %dB (%s), total=%d",
                    streamName, bytesWritten, writeMode, trackWriteCount));
        }
    }

    public static void logTrackStateChange(String streamName, String oldState, String newState) {
        if (!debugEnabled || !trackEnabled) return;

        Log.i(TAG, String.format("[AUDIO_TRACK] %s state: %s -> %s",
                streamName, oldState, newState));
    }

    public static void logTrackUnderrun(String streamName, int underrunCount) {
        if (!debugEnabled || !trackEnabled) return;

        totalUnderruns = underrunCount;
        Log.w(TAG, String.format("[AUDIO_TRACK] %s UNDERRUN detected (total: %d)",
                streamName, underrunCount));
    }

    // Stream Lifecycle
    public static void logStreamStart(String streamName, int sampleRate, int channels, int bufferSizeMs) {
        if (!debugEnabled || !streamEnabled) return;

        Log.i(TAG, String.format("[AUDIO_STREAM] %s STARTED: %dHz %dch buffer=%dms",
                streamName, sampleRate, channels, bufferSizeMs));
    }

    public static void logStreamStop(String streamName, long durationMs, long packetsProcessed) {
        if (!debugEnabled || !streamEnabled) return;

        Log.i(TAG, String.format("[AUDIO_STREAM] %s STOPPED: duration=%dms packets=%d",
                streamName, durationMs, packetsProcessed));
    }

    public static void logStreamFormatChange(String streamName, String oldFormat, String newFormat) {
        if (!debugEnabled || !streamEnabled) return;

        Log.i(TAG, String.format("[AUDIO_STREAM] %s FORMAT CHANGE: %s -> %s",
                streamName, oldFormat, newFormat));
    }

    public static void logStreamPause(String streamName, String reason) {
        if (!debugEnabled || !streamEnabled) return;

        Log.i(TAG, String.format("[AUDIO_STREAM] %s PAUSED: %s", streamName, reason));
    }

    public static void logStreamResume(String streamName, int bufferLevelMs) {
        if (!debugEnabled || !streamEnabled) return;

        Log.i(TAG, String.format("[AUDIO_STREAM] %s RESUMED: buffer=%dms", streamName, bufferLevelMs));
    }

    // Performance
    public static void logPerfSummary(
            int mediaFillMs, int navFillMs, int voiceFillMs, int callFillMs,
            int mediaUnderruns, int navUnderruns, int voiceUnderruns, int callUnderruns) {

        if (!debugEnabled || !perfEnabled) return;

        long now = System.currentTimeMillis();
        if (now - lastPerfLogTime < PERF_LOG_INTERVAL_MS) return;
        lastPerfLogTime = now;

        int totalUnderruns = mediaUnderruns + navUnderruns + voiceUnderruns + callUnderruns;

        Log.i(TAG, String.format(
                "[AUDIO_PERF] Buffers: media=%dms nav=%dms voice=%dms call=%dms | " +
                "Packets: media=%d nav=%d voice=%d call=%d | Underruns: %d",
                mediaFillMs, navFillMs, voiceFillMs, callFillMs,
                mediaPackets, navPackets, voicePackets, callPackets,
                totalUnderruns));
    }

    public static void logLatency(String streamName, long latencyMs) {
        if (!debugEnabled || !perfEnabled) return;

        if (latencyMs > 100) {
            Log.w(TAG, String.format("[AUDIO_PERF] %s latency: %dms (HIGH)",
                    streamName, latencyMs));
        }
    }

    public static String getStatsString() {
        return String.format(Locale.US,
                "USB: %d pkts | Buffer: W=%d R=%d | Track: %d writes | " +
                "Streams: M=%d N=%d V=%d C=%d | Underruns: %d",
                usbPacketCount, bufferWriteCount, bufferReadCount, trackWriteCount,
                mediaPackets, navPackets, voicePackets, callPackets, totalUnderruns);
    }

    public static void resetCounters() {
        usbPacketCount = 0;
        bufferWriteCount = 0;
        bufferReadCount = 0;
        trackWriteCount = 0;
        mediaPackets = 0;
        navPackets = 0;
        voicePackets = 0;
        callPackets = 0;
        totalUnderruns = 0;
        micCaptureCount = 0;
        micSendCount = 0;
        Log.i(TAG, "[AUDIO_DEBUG] Counters reset");
    }

    // Microphone
    private static long micCaptureCount = 0;
    private static long micSendCount = 0;
    private static long lastMicLogTime = 0;
    private static volatile boolean micEnabled = true;

    public static void logMicStart(int sampleRate, int channels, int bufferSizeMs) {
        if (!debugEnabled || !micEnabled) return;

        micCaptureCount = 0;
        micSendCount = 0;
        Log.i(TAG, String.format("[MIC_DEBUG] STARTED: %dHz %dch buffer=%dms",
                sampleRate, channels, bufferSizeMs));
    }

    public static void logMicStop(long durationMs, long totalBytesCaptured, int overruns) {
        if (!debugEnabled || !micEnabled) return;

        Log.i(TAG, String.format("[MIC_DEBUG] STOPPED: duration=%dms bytes=%d overruns=%d captured=%d sent=%d",
                durationMs, totalBytesCaptured, overruns, micCaptureCount, micSendCount));
    }

    public static void logMicCapture(int bytesRead, int bufferLevelMs) {
        if (!debugEnabled || !micEnabled) return;

        micCaptureCount++;

        long now = System.currentTimeMillis();
        if (now - lastMicLogTime >= THROTTLE_INTERVAL_MS) {
            lastMicLogTime = now;
            Log.d(TAG, String.format("[MIC_DEBUG] Capture #%d: %dB, buffer=%dms",
                    micCaptureCount, bytesRead, bufferLevelMs));
        }
    }

    public static void logMicSend(int bytesSent, int bufferLevelMs) {
        if (!debugEnabled || !micEnabled) return;

        micSendCount++;

        if (bufferLevelMs < 30) {
            Log.d(TAG, String.format("[MIC_DEBUG] Send #%d: %dB, buffer=%dms (LOW)",
                    micSendCount, bytesSent, bufferLevelMs));
        }
    }

    public static void logMicOverrun(int bytesLost, int bufferLevelMs) {
        if (!debugEnabled || !micEnabled) return;

        Log.w(TAG, String.format("[MIC_DEBUG] OVERRUN: lost %dB, buffer=%dms",
                bytesLost, bufferLevelMs));
    }

    public static void logMicUnderrun(int bytesRequested, int bytesAvailable) {
        if (!debugEnabled || !micEnabled) return;

        Log.w(TAG, String.format("[MIC_DEBUG] UNDERRUN: requested %dB, available %dB",
                bytesRequested, bytesAvailable));
    }

    public static void logMicError(String errorType, String details) {
        if (!debugEnabled || !micEnabled) return;

        Log.e(TAG, String.format("[MIC_DEBUG] ERROR: %s - %s", errorType, details));
    }

    public static void setMicEnabled(boolean enabled) {
        micEnabled = enabled;
    }

    // Navigation Audio
    private static long navPromptCount = 0;
    private static long navEndMarkersDetected = 0;
    private static long navWarmupFramesSkipped = 0;
    private static long navZeroPacketsFlushed = 0;
    private static long lastNavLogTime = 0;
    private static long currentNavPromptStartTime = 0;
    private static volatile boolean navEnabled = true;

    public static void setNavEnabled(boolean enabled) {
        navEnabled = enabled;
    }

    public static void logNavPromptStart(int sampleRate, int channels, int bufferSizeMs) {
        if (!debugEnabled || !navEnabled) return;

        navPromptCount++;
        currentNavPromptStartTime = System.currentTimeMillis();
        Log.i(TAG, String.format("[NAV_DEBUG] Prompt #%d STARTED: %dHz %dch buffer=%dms",
                navPromptCount, sampleRate, channels, bufferSizeMs));
    }

    public static void logNavPromptEnd(long durationMs, long bytesPlayed, int underruns) {
        if (!debugEnabled || !navEnabled) return;

        Log.i(TAG, String.format("[NAV_DEBUG] Prompt #%d ENDED: duration=%dms bytes=%d underruns=%d",
                navPromptCount, durationMs, bytesPlayed, underruns));
    }

    public static void logNavEndMarker(long timeSincePromptStart, int bufferLevelMs) {
        if (!debugEnabled || !navEnabled) return;

        navEndMarkersDetected++;
        Log.i(TAG, String.format("[NAV_DEBUG] End marker #%d detected: %dms into prompt, buffer=%dms",
                navEndMarkersDetected, timeSincePromptStart, bufferLevelMs));
    }

    public static void logNavWarmupSkip(long timeSinceStart, String patternDescription) {
        if (!debugEnabled || !navEnabled) return;

        navWarmupFramesSkipped++;
        if (navWarmupFramesSkipped == 1 || navWarmupFramesSkipped % 5 == 0) {
            Log.d(TAG, String.format("[NAV_DEBUG] Warmup skip #%d: %dms since start, pattern=%s",
                    navWarmupFramesSkipped, timeSinceStart, patternDescription));
        }
    }

    public static void logNavZeroFlush(int consecutiveZeros, int bufferLevelMs) {
        if (!debugEnabled || !navEnabled) return;

        navZeroPacketsFlushed++;
        Log.w(TAG, String.format("[NAV_DEBUG] Zero flush #%d: %d consecutive zeros, buffer=%dms",
                navZeroPacketsFlushed, consecutiveZeros, bufferLevelMs));
    }

    public static void logNavBufferWrite(int bytesWritten, int fillLevelMs, long timeSinceStart) {
        if (!debugEnabled || !navEnabled) return;

        long now = System.currentTimeMillis();
        if (now - lastNavLogTime >= THROTTLE_INTERVAL_MS) {
            lastNavLogTime = now;
            Log.d(TAG, String.format("[NAV_DEBUG] Buffer write: %dB, fill=%dms, t+%dms",
                    bytesWritten, fillLevelMs, timeSinceStart));
        }
    }

    public static void logNavTrackWrite(int bytesWritten, int bufferLevelMs) {
        if (!debugEnabled || !navEnabled) return;

        if (bufferLevelMs < 80) {
            Log.d(TAG, String.format("[NAV_DEBUG] Track write: %dB, buffer=%dms (LOW)",
                    bytesWritten, bufferLevelMs));
        }
    }

    public static void logNavPrefillComplete(int fillLevelMs, long waitTimeMs) {
        if (!debugEnabled || !navEnabled) return;

        Log.i(TAG, String.format("[NAV_DEBUG] Pre-fill complete: %dms buffered, waited %dms",
                fillLevelMs, waitTimeMs));
    }

    public static void logNavBufferFlush(String reason, int discardedMs) {
        if (!debugEnabled || !navEnabled) return;

        Log.i(TAG, String.format("[NAV_DEBUG] Buffer flush: reason=%s, discarded=%dms",
                reason, discardedMs));
    }

    public static void logNavPatternCheck(String patternType, boolean detected, String sampleValues) {
        if (!debugEnabled || !navEnabled) return;

        if (detected) {
            Log.d(TAG, String.format("[NAV_DEBUG] Pattern %s DETECTED: samples=[%s]",
                    patternType, sampleValues));
        }
    }

    public static String getNavStatsString() {
        return String.format(Locale.US,
                "Nav prompts=%d | End markers=%d | Warmup skipped=%d | Zero flushes=%d",
                navPromptCount, navEndMarkersDetected, navWarmupFramesSkipped, navZeroPacketsFlushed);
    }

    public static void resetNavCounters() {
        navPromptCount = 0;
        navEndMarkersDetected = 0;
        navWarmupFramesSkipped = 0;
        navZeroPacketsFlushed = 0;
        currentNavPromptStartTime = 0;
        Log.i(TAG, "[NAV_DEBUG] Navigation counters reset");
    }

    public static long getTimeSinceNavPromptStart() {
        if (currentNavPromptStartTime == 0) return 0;
        return System.currentTimeMillis() - currentNavPromptStartTime;
    }
}
