package com.carlink.video;

/**
 * Hardware-accelerated H.264 decoder using MediaCodec async mode.
 *
 * Decodes video from CPC200-CCPA adapter and renders to SurfaceView via HWC overlay.
 * Uses ring buffer for packet buffering and graduated memory pools for efficiency.
 * Optimized for GM GMinfo3.7 (Intel HD Graphics 505) with fallback for other platforms.
 *
 * @see PacketRingByteBuffer
 * @see AppExecutors
 */
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.carlink.util.AppExecutors;
import com.carlink.util.LogCallback;
import com.carlink.util.VideoDebugLogger;


public class H264Renderer {
    private static final String LOG_TAG = "CARLINK";

    /** Callback to request keyframe (IDR) from adapter after codec reset. */
    public interface KeyframeRequestCallback {
        void onKeyframeNeeded();
    }

    private final Object codecLock = new Object(); // Synchronizes codec lifecycle with callbacks

    private volatile MediaCodec mCodec;
    private final MediaCodec.Callback codecCallback;
    private MediaCodecInfo codecInfo;
    private final ConcurrentLinkedQueue<Integer> codecAvailableBufferIndexes = new ConcurrentLinkedQueue<>();
    private final int width;
    private final int height;
    private Surface surface;
    private volatile boolean running = false;
    private volatile boolean isPaused = false;
    private final boolean bufferLoopRunning = false;
    private final LogCallback logCallback;
    private KeyframeRequestCallback keyframeCallback;

    private final AppExecutors executors;

    private final PacketRingByteBuffer ringBuffer;

    private final String preferredDecoderName; // From PlatformDetector (e.g., "OMX.Intel.hw_vd.h264")

    private static final int TARGET_FPS = 60;
    private static final long DYNAMIC_TIMEOUT_US = 16666; // ~60fps frame duration
    private boolean decoderInitialized = false;
    private int consecutiveOutputFrames = 0;

    // Resolution-adaptive buffer pool
    private int bufferPoolSize;
    private static final int BUFFER_POOL_MIN_FREE = 2;
    private static final int MIN_POOL_SIZE = 8;
    private static final int MAX_POOL_SIZE = 24;
    private final ConcurrentLinkedQueue<ByteBuffer> smallBuffers = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ByteBuffer> mediumBuffers = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ByteBuffer> largeBuffers = new ConcurrentLinkedQueue<>();
    private boolean poolInitialized = false;

    private static final int SMALL_BUFFER_THRESHOLD = 64 * 1024;
    private static final int MEDIUM_BUFFER_THRESHOLD = 256 * 1024;

    // Performance monitoring
    private long totalFramesReceived = 0;
    private long totalFramesDecoded = 0;
    private long totalFramesDropped = 0;
    private long lastStatsTime = 0;
    private long codecResetCount = 0;
    private long totalBytesProcessed = 0;

    // Recovery metrics
    private long totalRecoveryTimeMs = 0;
    private int recoveryCount = 0;
    private long lastRecoveryTimeMs = 0;

    // Adaptive FPS detection
    private int detectedTargetFps = 60;
    private static final int[] COMMON_FPS_TARGETS = {25, 30, 50, 60};

    private static final long PERF_LOG_INTERVAL_MS = 30000;
    private long lastPerfLogTime = 0;

    // Codec reset rate limiting with exponential backoff
    private static final long MIN_RESET_INTERVAL_MS = 200;
    private static final int MAX_RAPID_RESETS = 5;
    private static final long RESET_WINDOW_MS = 10000;
    private static final long MAX_BACKOFF_MS = 5000;  // Cap backoff at 5 seconds
    private static final int MAX_BACKOFF_EXPONENT = 4; // 2^4 = 16x multiplier max
    private long lastResetTime = 0;
    private int resetsInWindow = 0;
    private long windowStartTime = 0;
    private int consecutiveResetAttempts = 0;  // For exponential backoff calculation

    // Startup stabilization
    private static final long MIN_STARTUP_TIME_MS = 500;
    private long codecStartTime = 0;

    // Post-reset keyframe detection
    private static final int KEYFRAME_REQUEST_THRESHOLD = 5;
    private static final long KEYFRAME_REQUEST_TIME_THRESHOLD_MS = 1000;
    private static final long KEYFRAME_REQUEST_COOLDOWN_MS = 500;
    private long framesReceivedSinceReset = 0;
    private long framesDecodedSinceReset = 0;
    private long lastKeyframeRequestTime = 0;
    private long resetTimestamp = 0;
    private boolean pendingKeyframeRequest = false;

    // Presentation timestamp for frame ordering
    private final AtomicLong presentationTimeUs = new AtomicLong(0);
    private static final long DEFAULT_FRAME_DURATION_US = 16667; // ~60fps
    private volatile long frameDurationUs = DEFAULT_FRAME_DURATION_US;

    // SPS/PPS caching for codec recovery after flush/reset AND continuous operation
    // H.264 decoders require SPS+PPS before any IDR frame can be decoded.
    // The CPC200-CCPA adapter only sends SPS+PPS at session start, not on FRAME command.
    // We cache these NAL units and:
    //   1. Resubmit with BUFFER_FLAG_CODEC_CONFIG after flush() (async callback-based)
    //   2. Prepend to every IDR frame to prevent decoder drift during continuous operation
    private final Object spsLock = new Object(); // Protects SPS/PPS access
    private byte[] cachedSps = null;
    private byte[] cachedPps = null;
    private volatile boolean codecConfigPending = false;

    // Temporary buffer for SPS+PPS+IDR combination (reused to avoid allocation)
    private byte[] combinedBuffer = null;

    // Dedicated callback thread for reliable MediaCodec event delivery
    private HandlerThread codecCallbackThread;
    private Handler codecCallbackHandler;

    private int calculateOptimalBufferSize(int width, int height) {
        int pixels = width * height;
        if (pixels <= 1920 * 1080) return 8 * 1024 * 1024;
        if (pixels <= 2400 * 960) return 16 * 1024 * 1024;
        if (pixels <= 3840 * 2160) return 32 * 1024 * 1024;
        return 64 * 1024 * 1024;
    }

    private int calculateOptimalPoolSize(int width, int height) {
        int pixels = width * height;
        if (pixels <= 800 * 480) return MIN_POOL_SIZE;
        if (pixels <= 1024 * 600) return 10;
        if (pixels <= 1920 * 1080) return 14;
        if (pixels <= 2400 * 960) return 16;
        if (pixels <= 3840 * 2160) return 20;
        return MAX_POOL_SIZE;
    }

    private int calculateOptimalFrameBufferSize(int width, int height) {
        int pixels = width * height;
        int baseSize = (pixels * 4) / 10; // ~10:1 H.264 compression ratio
        if (pixels <= 800 * 480) return Math.max(baseSize, 64 * 1024);
        if (pixels <= 1920 * 1080) return Math.max(baseSize, 128 * 1024);
        if (pixels <= 2400 * 960) return Math.max(baseSize, 256 * 1024);
        if (pixels <= 3840 * 2160) return Math.max(baseSize, 512 * 1024);
        return Math.max(baseSize, 1024 * 1024);
    }

    public H264Renderer(Context context, int width, int height, Surface surface, LogCallback logCallback, AppExecutors executors, String preferredDecoderName) {
        this.width = width;
        this.height = height;
        this.surface = surface;
        this.logCallback = logCallback;
        this.executors = executors;
        this.preferredDecoderName = preferredDecoderName;

        int bufferSize = calculateOptimalBufferSize(width, height);
        ringBuffer = new PacketRingByteBuffer(bufferSize);
        // Request keyframe when ring buffer emergency resets (all data lost)
        ringBuffer.setEmergencyResetCallback(() -> {
            log("[RING_BUFFER] Emergency reset - requesting keyframe");
            if (keyframeCallback != null) {
                try {
                    keyframeCallback.onKeyframeNeeded();
                    lastKeyframeRequestTime = System.currentTimeMillis();
                } catch (Exception e) {
                    log("[RING_BUFFER] Keyframe request failed: " + e.getMessage());
                }
            }
        });
        log("Ring buffer initialized: " + (bufferSize / (1024*1024)) + "MB for " + width + "x" + height);

        initializeBufferPool();

        codecCallback = createCallback();
    }

    public void setKeyframeRequestCallback(KeyframeRequestCallback callback) {
        this.keyframeCallback = callback;
    }

    /**
     * Set the target FPS for presentation timestamp calculation.
     * Called when adapter reports video parameters (e.g., from OpenedMessage.fps).
     * Default is 60fps if not set.
     *
     * @param fps Target frames per second (e.g., 25, 30, 50, 60)
     */
    public void setTargetFps(int fps) {
        if (fps > 0 && fps <= 120) {
            this.frameDurationUs = 1_000_000L / fps;
            log("Target FPS set to " + fps + " (frame duration: " + frameDurationUs + "us)");
        } else {
            log("Invalid FPS value: " + fps + ", using default 60fps");
            this.frameDurationUs = DEFAULT_FRAME_DURATION_US;
        }
    }

    private void log(String message) {
        String formattedMessage = "[H264_RENDERER] " + message;
        // Log.d(LOG_TAG, formattedMessage);  // Removed to eliminate duplicate logging
        logCallback.log(formattedMessage);
    }

    public void start() {
        if (running) return;

        running = true;
        isPaused = false;  // Reset paused state on fresh start
        lastStatsTime = System.currentTimeMillis();
        totalFramesReceived = 0;
        totalFramesDecoded = 0;
        totalFramesDropped = 0;
        totalBytesProcessed = 0;
        decoderInitialized = false;
        consecutiveOutputFrames = 0;

        log("start - Resolution: " + width + "x" + height + ", Surface: " + (surface != null));

        try {
            synchronized (codecLock) {
                initCodec(width, height, surface);
                mCodec.start();
                codecStartTime = System.currentTimeMillis();  // Record start time for stabilization
            }
            log("codec started successfully");
            VideoDebugLogger.logCodecStarted();
        } catch (Exception e) {
            log("start error " + e);
            e.printStackTrace();

            log("restarting in 5s ");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (running) {
                    start();
                }
            }, 5000);
        }
    }

    private boolean fillFirstAvailableCodecBuffer(MediaCodec codec) {

        if (codec != mCodec) return false;

        // Use lock-free poll from ConcurrentLinkedQueue
        if (ringBuffer.isEmpty()) {
            return false;
        }

        Integer index = codecAvailableBufferIndexes.poll();
        if (index == null) {
            return false;
        }

        ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
        if (byteBuffer == null) {
            log("[CODEC] getInputBuffer returned null for index " + index);
            return false;
        }

        // Direct copy from ring buffer to codec buffer (no intermediate allocation)
        int bytesWritten = ringBuffer.readPacketInto(byteBuffer);
        if (bytesWritten == 0) {
            // No packet available or error - return index for later use
            codecAvailableBufferIndexes.offer(index);
            return false;
        }

        // Detect NAL type and cache SPS/PPS if present
        int nalType = detectNalUnitType(byteBuffer, bytesWritten);
        if (nalType == 7 || nalType == 8 || nalType == 5) {
            cacheCodecConfigData(byteBuffer, bytesWritten);
        }

        // Use monotonic presentation timestamp for proper frame ordering
        long pts = presentationTimeUs.getAndAdd(frameDurationUs);

        // For IDR frames: prepend cached SPS/PPS to ensure decoder refresh
        boolean isIdrFrame = (nalType == 5) || containsIdrFrame(byteBuffer, bytesWritten);
        if (isIdrFrame && queueIdrWithSpsPps(codec, index, byteBuffer, bytesWritten, pts)) {
            // Successfully queued IDR with SPS/PPS prepended
            return true;
        }

        // Normal frame or IDR injection failed - queue as-is
        mCodec.queueInputBuffer(index, 0, bytesWritten, pts, 0);

        return true;
    }

    private void fillAllAvailableCodecBuffers(MediaCodec codec) {
        boolean filled = true;

        while (filled) {
            filled = fillFirstAvailableCodecBuffer(codec);
        }
    }

    private void feedCodec() {
        executors.mediaCodec1().execute(() -> {
            try {
                fillAllAvailableCodecBuffers(mCodec);

                if (ringBuffer != null) {
                    int packetCount = ringBuffer.availablePacketsToRead();
                    if (packetCount > 20) {
                        log("[BUFFER_WARNING] High buffer usage: " + packetCount + " packets");
                    }
                }
            } catch (Exception e) {
                log("[Media Codec] fill input buffer error:" + e);
            }
        });
    }

    public void stop() {
        if (!running) return;

        running = false;
        VideoDebugLogger.logCodecStopped();

        synchronized (codecLock) {
            try {
                if (mCodec != null) {
                    mCodec.stop();
                    mCodec.release();
                    mCodec = null;
                }
            } catch (Exception e) {
                log("STOP: MediaCodec cleanup failed - " + e);
                mCodec = null; // Force null to prevent further issues
            } finally {
                // Always clean up additional resources regardless of MediaCodec cleanup success
                cleanupResources();
            }
        }
    }

    private void cleanupResources() {
        // Surface lifecycle managed by SurfaceView
        surface = null;

        if (codecCallbackThread != null) {
            codecCallbackThread.quitSafely();
            try {
                codecCallbackThread.join(500);
            } catch (InterruptedException e) {
                log("Interrupted waiting for callback thread");
            }
            codecCallbackThread = null;
            codecCallbackHandler = null;
        }

        int totalCleared = smallBuffers.size() + mediumBuffers.size() + largeBuffers.size();
        secureBufferPoolClear(smallBuffers);
        secureBufferPoolClear(mediumBuffers);
        secureBufferPoolClear(largeBuffers);
        log("Buffer pools cleared - " + totalCleared + " buffers");

        codecAvailableBufferIndexes.clear();
        poolInitialized = false;
        presentationTimeUs.set(0);
    }


    public void reset() {
        long currentTime = System.currentTimeMillis();

        // Don't reset during startup stabilization
        if (codecStartTime > 0 && (currentTime - codecStartTime) < MIN_STARTUP_TIME_MS) {
            log("[RESET_THROTTLE] Skipping - startup stabilization");
            return;
        }

        // Calculate exponential backoff interval based on consecutive reset attempts
        // Backoff formula: MIN_RESET_INTERVAL_MS * 2^min(attempts, MAX_EXPONENT)
        int exponent = Math.min(consecutiveResetAttempts, MAX_BACKOFF_EXPONENT);
        long backoffIntervalMs = Math.min(MIN_RESET_INTERVAL_MS * (1L << exponent), MAX_BACKOFF_MS);

        if (currentTime - lastResetTime < backoffIntervalMs) {
            log("[RESET_THROTTLE] Backoff active - waiting " + backoffIntervalMs + "ms (attempt " + consecutiveResetAttempts + ")");
            return;
        }

        // Reset window tracking
        if (currentTime - windowStartTime > RESET_WINDOW_MS) {
            windowStartTime = currentTime;
            resetsInWindow = 1;
            consecutiveResetAttempts = 0;  // Reset backoff after quiet period
        } else {
            resetsInWindow++;
            consecutiveResetAttempts++;
            if (resetsInWindow >= MAX_RAPID_RESETS) {
                log("[RESET_THROTTLE] Max resets reached (" + MAX_RAPID_RESETS + "), backoff: " + backoffIntervalMs + "ms");
                lastResetTime = currentTime;
                return;
            }
        }

        lastResetTime = currentTime;
        codecResetCount++;
        log("reset codec - count: " + codecResetCount + ", decoded: " + totalFramesDecoded);
        VideoDebugLogger.logCodecReset(codecResetCount);

        synchronized (codecLock) {
            codecAvailableBufferIndexes.clear();
            ringBuffer.reset();

            framesReceivedSinceReset = 0;
            framesDecodedSinceReset = 0;
            resetTimestamp = System.currentTimeMillis();
            pendingKeyframeRequest = true;

            // flush() + start() required in async mode to resume callbacks
            if (mCodec != null) {
                try {
                    mCodec.flush();
                    mCodec.start();
                    codecStartTime = System.currentTimeMillis();
                    log("[RESET] Codec flush+start completed");
                    VideoDebugLogger.logCodecStarted();

                    // Request SPS+PPS injection on next available buffer (async-safe)
                    requestCodecConfigInjection();
                } catch (Exception e) {
                    log("[RESET] flush+start failed: " + e + " - recreating codec");
                    try {
                        mCodec.stop();
                        mCodec.release();
                        mCodec = null;
                    } catch (Exception e2) {
                        log("[RESET] Cleanup failed: " + e2);
                        mCodec = null;
                    }
                    try {
                        initCodec(width, height, surface);
                        mCodec.start();
                        codecStartTime = System.currentTimeMillis();
                        log("[RESET] Codec recreated");
                        VideoDebugLogger.logCodecStarted();

                        // Request SPS+PPS injection on next available buffer (async-safe)
                        requestCodecConfigInjection();
                    } catch (Exception e3) {
                        log("[RESET] Codec creation failed: " + e3);
                    }
                }
            } else {
                log("[RESET] Codec was null, full start");
                running = false;
                start();
            }
        }

        // Request keyframe after reset (decoder lost SPS/PPS context)
        if (keyframeCallback != null) {
            log("[RESET] Requesting keyframe");
            try {
                keyframeCallback.onKeyframeNeeded();
                lastKeyframeRequestTime = System.currentTimeMillis();
            } catch (Exception e) {
                log("[RESET] Keyframe request failed: " + e);
            }
        }
    }

    /** Pause decoding and flush codec. Call from Activity.onStop(). */
    public void pause() {
        log("[LIFECYCLE] pause() called - flushing codec for background");

        synchronized (codecLock) {
            if (mCodec == null || !running) {
                return;
            }

            try {
                mCodec.flush();
                isPaused = true;
                codecAvailableBufferIndexes.clear();
                ringBuffer.reset();
                log("[LIFECYCLE] Codec paused");
            } catch (Exception e) {
                log("[LIFECYCLE] pause() failed: " + e);
            }
        }
    }

    /** Resume decoding with surface. Call from Activity.onStart(). */
    public void resume(Surface newSurface) {
        log("[LIFECYCLE] resume() called");

        synchronized (codecLock) {
            if (mCodec == null) {
                log("[LIFECYCLE] Codec null, full start");
                this.surface = newSurface;
                running = false;
                start();
                return;
            }

            if (!running) {
                log("[LIFECYCLE] Not running, full start");
                this.surface = newSurface;
                start();
                return;
            }

            // Always update surface (native BufferQueue may have been recreated)
            if (newSurface != null && newSurface.isValid()) {
                boolean surfaceChanged = (newSurface != this.surface);

                try {
                    mCodec.setOutputSurface(newSurface);
                    this.surface = newSurface;
                    log("[LIFECYCLE] Surface updated");

                    // In async mode, flush/start required after pause OR surface change
                    // This ensures clean state and proper callback resumption
                    if (isPaused || surfaceChanged) {
                        // Flush to clear any stale frames from old surface or paused state
                        mCodec.flush();
                        ringBuffer.reset();
                        codecAvailableBufferIndexes.clear();
                        mCodec.start();  // Required after flush() in async mode
                        isPaused = false;
                        codecStartTime = System.currentTimeMillis();
                        log("[LIFECYCLE] Codec resumed with flush/start (paused=" + !surfaceChanged + ", surfaceChanged=" + surfaceChanged + ")");

                        // Request SPS+PPS injection on next available buffer (async-safe)
                        requestCodecConfigInjection();
                    }

                    framesReceivedSinceReset = 0;
                    framesDecodedSinceReset = 0;
                    resetTimestamp = System.currentTimeMillis();
                    pendingKeyframeRequest = true;

                } catch (IllegalArgumentException e) {
                    log("[LIFECYCLE] setOutputSurface failed: " + e.getMessage());
                    recreateCodecWithSurface(newSurface);
                    return;
                } catch (IllegalStateException e) {
                    log("[LIFECYCLE] setOutputSurface failed: " + e.getMessage());
                    recreateCodecWithSurface(newSurface);
                    return;
                }
            } else {
                log("[LIFECYCLE] Invalid surface");
                return;
            }
        }

        if (keyframeCallback != null) {
            log("[LIFECYCLE] Requesting keyframe");
            try {
                keyframeCallback.onKeyframeNeeded();
                lastKeyframeRequestTime = System.currentTimeMillis();
            } catch (Exception e) {
                log("[LIFECYCLE] Keyframe request failed: " + e);
            }
        }
    }

    /** Full codec recreation when setOutputSurface() fails. */
    private void recreateCodecWithSurface(Surface newSurface) {
        log("[LIFECYCLE] Recreating codec");

        try {
            if (mCodec != null) {
                mCodec.stop();
                mCodec.release();
            }
        } catch (Exception e) {
            log("[LIFECYCLE] Cleanup error: " + e.getMessage());
        }
        mCodec = null;

        codecAvailableBufferIndexes.clear();
        ringBuffer.reset();
        presentationTimeUs.set(0);

        this.surface = newSurface;
        try {
            initCodec(width, height, newSurface);
            mCodec.start();
            codecStartTime = System.currentTimeMillis();

            framesReceivedSinceReset = 0;
            framesDecodedSinceReset = 0;
            resetTimestamp = System.currentTimeMillis();
            pendingKeyframeRequest = true;

            log("[LIFECYCLE] Codec recreated");
            VideoDebugLogger.logCodecStarted();

            // Request SPS+PPS injection on next available buffer (async-safe)
            requestCodecConfigInjection();

            if (keyframeCallback != null) {
                keyframeCallback.onKeyframeNeeded();
                lastKeyframeRequestTime = System.currentTimeMillis();
            }
        } catch (Exception e) {
            log("[LIFECYCLE] Recreate failed: " + e);
            running = false;
        }
    }

    /** Resume with existing surface. */
    public void resume() {
        resume(this.surface);
    }


    private void initCodec(int width, int height, Surface surface) throws Exception {
        log("init codec: " + width + "x" + height);

        MediaCodec codec = null;
        String codecName = null;
        MediaCodecInfo selectedCodecInfo = null;

        // Try preferred decoder from PlatformDetector
        if (preferredDecoderName != null && !preferredDecoderName.isEmpty()) {
            try {
                codec = MediaCodec.createByCodecName(preferredDecoderName);
                codecName = preferredDecoderName;
                selectedCodecInfo = findCodecInfo(preferredDecoderName);
                log("Using decoder: " + codecName);
            } catch (Exception e) {
                log("Preferred decoder unavailable: " + e.getMessage());
            }
        }

        // Fallback to generic decoder
        if (codec == null) {
            try {
                codec = MediaCodec.createDecoderByType("video/avc");
                codecName = codec.getName();
                selectedCodecInfo = findCodecInfo(codecName);
                log("Using decoder: " + codecName);
            } catch (Exception e2) {
                throw new Exception("No H.264 decoder available", e2);
            }
        }

        mCodec = codec;
        codecInfo = selectedCodecInfo;
        VideoDebugLogger.logCodecInit(codecName, width, height);

        final MediaFormat mediaformat = MediaFormat.createVideoFormat("video/avc", width, height);

        // Low latency mode - only enable if codec supports it
        boolean lowLatencySupported = false;
        if (codecInfo != null) {
            try {
                CodecCapabilities caps = codecInfo.getCapabilitiesForType("video/avc");
                lowLatencySupported = caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency);
            } catch (Exception e) {
                // Ignore
            }
        }
        if (lowLatencySupported) {
            try {
                mediaformat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                log("Low latency: enabled");
            } catch (Exception e) {
                log("Low latency: failed to set key");
            }
        } else {
            log("Low latency: not supported by decoder, skipping");
        }

        try {
            mediaformat.setInteger(MediaFormat.KEY_PRIORITY, 0); // Realtime
        } catch (Exception e) {
            // Ignore
        }

        // Intel-specific: disable Adaptive Playback (causes high latency)
        if (codecName != null && codecName.contains("Intel")) {
            try {
                mediaformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height);
                mediaformat.setInteger("max-concurrent-instances", 1);
                log("Intel optimizations applied");
            } catch (Exception e) {
                // Ignore
            }
        }

        log("format: " + mediaformat);

        // Create callback thread
        if (codecCallbackThread == null || !codecCallbackThread.isAlive()) {
            codecCallbackThread = new HandlerThread("MediaCodecCallback", Process.THREAD_PRIORITY_URGENT_AUDIO);
            codecCallbackThread.start();
            codecCallbackHandler = new Handler(codecCallbackThread.getLooper());
        }

        // setCallback must be called before configure for async mode
        mCodec.setCallback(codecCallback, codecCallbackHandler);

        codecAvailableBufferIndexes.clear();

        mCodec.configure(mediaformat, surface, null, 0);
        VideoDebugLogger.logCodecConfigured(mediaformat.toString());
        VideoDebugLogger.logSurfaceBound(surface != null && surface.isValid());
    }

    private MediaCodecInfo findCodecInfo(String codecName) {
        if (codecName == null) return null;

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : codecList.getCodecInfos()) {
            if (!info.isEncoder() && info.getName().equals(codecName)) {
                return info;
            }
        }
        return null;
    }

    public void processData(byte[] data, int flags) {
        if (data == null || data.length == 0) return;

        processDataDirect(data.length, 0, (buffer, offset) -> {
            System.arraycopy(data, 0, buffer, offset, data.length);
        });
    }

    public void processDataDirect(int length, int skipBytes, PacketRingByteBuffer.DirectWriteCallback callback) {
        totalFramesReceived++;
        totalBytesProcessed += length;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPerfLogTime >= PERF_LOG_INTERVAL_MS) {
            logPerformanceStats();
            lastPerfLogTime = currentTime;
        }

        ringBuffer.directWriteToBuffer(length, skipBytes, callback);
        feedCodec();
    }


    private void initializeBufferPool() {
        if (poolInitialized) return;

        bufferPoolSize = calculateOptimalPoolSize(width, height);
        int bufferSize = calculateOptimalFrameBufferSize(width, height);

        // Distribution 50:30:20 based on typical H.264 frame size patterns:
        // - Small (50%): P-frames (~10-50KB) are most common
        // - Medium (30%): Some P-frames and smaller I-frames
        // - Large (20%): IDR+SPS+PPS frames (~100-300KB) are least frequent
        int smallCount = (bufferPoolSize * 50) / 100;
        int mediumCount = (bufferPoolSize * 30) / 100;
        int largeCount = bufferPoolSize - smallCount - mediumCount;

        for (int i = 0; i < smallCount; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(SMALL_BUFFER_THRESHOLD);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            smallBuffers.offer(buffer);
        }

        for (int i = 0; i < mediumCount; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(MEDIUM_BUFFER_THRESHOLD);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            mediumBuffers.offer(buffer);
        }

        for (int i = 0; i < largeCount; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            largeBuffers.offer(buffer);
        }

        poolInitialized = true;
        log("Pool: " + smallCount + "/" + mediumCount + "/" + largeCount + " buffers for " + width + "x" + height);
    }

    private ByteBuffer getPooledBuffer(int minimumSize) {
        ByteBuffer buffer = null;

        if (minimumSize <= SMALL_BUFFER_THRESHOLD) {
            buffer = smallBuffers.poll();
            if (buffer == null) buffer = mediumBuffers.poll();
        } else if (minimumSize <= MEDIUM_BUFFER_THRESHOLD) {
            buffer = mediumBuffers.poll();
            if (buffer == null) buffer = largeBuffers.poll();
        } else {
            buffer = largeBuffers.poll();
        }

        if (buffer == null || buffer.capacity() < minimumSize) {
            int newSize = Math.max(minimumSize, 128 * 1024);
            buffer = ByteBuffer.allocateDirect(newSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            log("[POOL_EXPAND] " + (newSize / 1024) + "KB");
        }

        buffer.clear();
        secureBufferClear(buffer);
        return buffer;
    }

    private void returnPooledBuffer(ByteBuffer buffer) {
        if (buffer == null) return;

        buffer.clear();
        int capacity = buffer.capacity();
        boolean returned = false;

        // Use same 50:30:20 distribution as initializeBufferPool
        int maxSmall = (bufferPoolSize * 50) / 100;
        int maxMedium = (bufferPoolSize * 30) / 100;
        int maxLarge = bufferPoolSize - maxSmall - maxMedium;

        if (capacity <= SMALL_BUFFER_THRESHOLD && smallBuffers.size() < maxSmall) {
            smallBuffers.offer(buffer);
            returned = true;
        } else if (capacity <= MEDIUM_BUFFER_THRESHOLD && mediumBuffers.size() < maxMedium) {
            mediumBuffers.offer(buffer);
            returned = true;
        } else if (capacity > MEDIUM_BUFFER_THRESHOLD && largeBuffers.size() < maxLarge) {
            largeBuffers.offer(buffer);
            returned = true;
        }

        // !returned: buffer discarded, will be GC'd
    }

    /** Zero buffer contents (called on session end). */
    private void secureBufferClear(ByteBuffer buffer) {
        if (buffer != null && buffer.isDirect()) {
            buffer.clear();
            byte[] zeros = new byte[Math.min(32768, buffer.capacity())];
            while (buffer.hasRemaining()) {
                int toWrite = Math.min(zeros.length, buffer.remaining());
                buffer.put(zeros, 0, toWrite);
            }
            buffer.clear();
        }
    }

    private void secureBufferPoolClear(ConcurrentLinkedQueue<ByteBuffer> pool) {
        ByteBuffer buffer;
        while ((buffer = pool.poll()) != null) {
            secureBufferClear(buffer);
        }
    }


    ////////////////////////////////////////

    private MediaCodec.Callback createCallback() {
        return new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                synchronized (codecLock) {
                    if (codec != mCodec || mCodec == null || !running) {
                        return;
                    }

                    VideoDebugLogger.logCodecInputAvailable(index, codecAvailableBufferIndexes.size());

                    // Priority 1: Inject SPS/PPS after codec reset (before any frame data)
                    if (codecConfigPending) {
                        if (injectCodecConfigToBuffer(codec, index)) {
                            // Successfully injected, this buffer is consumed
                            return;
                        }
                        // Injection failed, fall through to process normal data
                    }

                    if (!ringBuffer.isEmpty()) {
                        try {
                            ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
                            if (byteBuffer == null) return;

                            int dataSize = ringBuffer.readPacketInto(byteBuffer);
                            if (dataSize == 0) {
                                codecAvailableBufferIndexes.offer(index);
                                return;
                            }

                            int nalType = detectNalUnitType(byteBuffer, dataSize);
                            if (nalType >= 0) {
                                VideoDebugLogger.logNalUnitType(nalType, dataSize);
                            }

                            // Cache SPS/PPS from incoming data for later use
                            // Must be done before any buffer modifications
                            if (nalType == 7 || nalType == 8 || nalType == 5) {
                                cacheCodecConfigData(byteBuffer, dataSize);
                            }

                            long pts = presentationTimeUs.getAndAdd(frameDurationUs);

                            // For IDR frames: prepend cached SPS/PPS to ensure decoder refresh
                            // This prevents progressive degradation during continuous operation
                            boolean isIdrFrame = (nalType == 5) || containsIdrFrame(byteBuffer, dataSize);
                            if (isIdrFrame && queueIdrWithSpsPps(codec, index, byteBuffer, dataSize, pts)) {
                                // Successfully queued IDR with SPS/PPS prepended
                                VideoDebugLogger.logCodecInputQueued(index, dataSize);
                                framesReceivedSinceReset++;
                            } else {
                                // Normal frame or IDR injection failed - queue as-is
                                mCodec.queueInputBuffer(index, 0, dataSize, pts, 0);
                                VideoDebugLogger.logCodecInputQueued(index, dataSize);
                                framesReceivedSinceReset++;
                            }

                            // Keyframe detection: receiving frames but not decoding
                            if (pendingKeyframeRequest && framesDecodedSinceReset == 0) {
                                long now = System.currentTimeMillis();
                                long timeSinceReset = now - resetTimestamp;
                                boolean frameThresholdMet = framesReceivedSinceReset >= KEYFRAME_REQUEST_THRESHOLD;
                                boolean timeThresholdMet = timeSinceReset >= KEYFRAME_REQUEST_TIME_THRESHOLD_MS;

                                if ((frameThresholdMet || timeThresholdMet) &&
                                    (now - lastKeyframeRequestTime) > KEYFRAME_REQUEST_COOLDOWN_MS) {
                                    log("[KEYFRAME_DETECT] Requesting keyframe");
                                    if (keyframeCallback != null) {
                                        try {
                                            keyframeCallback.onKeyframeNeeded();
                                            lastKeyframeRequestTime = now;
                                        } catch (Exception ke) {
                                            log("[KEYFRAME_DETECT] Keyframe request failed: " + ke.getMessage());
                                        }
                                    }
                                    framesReceivedSinceReset = 0;
                                    resetTimestamp = now;
                                }
                            }
                        } catch (Exception e) {
                            log("[CALLBACK] Failed to queue buffer #" + index + ": " + e.getMessage());
                            codecAvailableBufferIndexes.offer(index);
                        }
                    } else {
                        // No data yet - save buffer index for when data arrives
                        codecAvailableBufferIndexes.offer(index);
                    }
                }
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                // Thread-safe check
                synchronized (codecLock) {
                    if (codec != mCodec || mCodec == null || !running) {
                        return;
                    }

                    if (info.size > 0) {
                        totalFramesDecoded++;
                        consecutiveOutputFrames++;
                        framesDecodedSinceReset++;

                        // Mark decoder as initialized after first few successful outputs
                        if (consecutiveOutputFrames >= 3) {
                            decoderInitialized = true;
                        }

                        // Disable keyframe detection once we're successfully decoding
                        // Also log recovery metrics when video recovers after reset
                        if (pendingKeyframeRequest && framesDecodedSinceReset >= 3) {
                            // Calculate and log recovery time
                            long recoveryTime = System.currentTimeMillis() - resetTimestamp;
                            totalRecoveryTimeMs += recoveryTime;
                            recoveryCount++;
                            lastRecoveryTimeMs = recoveryTime;
                            long avgRecoveryTime = totalRecoveryTimeMs / recoveryCount;
                            log("[RECOVERY] Video recovered in " + recoveryTime + "ms " +
                                "(avg: " + avgRecoveryTime + "ms, count: " + recoveryCount + ")");
                            pendingKeyframeRequest = false;
                            consecutiveResetAttempts = 0;  // Reset exponential backoff on successful recovery
                        }

                        // Log first decoded frame for debugging
                        if (totalFramesDecoded == 1) {
                            log("[CALLBACK] First frame decoded! size=" + info.size);
                        }
                    } else {
                        totalFramesDropped++;
                    }

                    final boolean doRender = (info.size != 0);
                    final int frameSize = info.size;
                    final MediaCodec currentCodec = mCodec;  // Capture for lambda

                    executors.mediaCodec2().execute(() -> {
                        try {
                            if (currentCodec != null && running) {
                                currentCodec.releaseOutputBuffer(index, doRender);
                                VideoDebugLogger.logCodecOutputReleased(index, frameSize, doRender);
                            }
                        } catch (Exception e) {
                            log("[CALLBACK] releaseOutputBuffer failed: " + e.getMessage());
                        }
                    });
                }
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                synchronized (codecLock) {
                    if (codec != mCodec) return;

                    // Differentiate error codes for appropriate handling
                    int errorCode = e.getErrorCode();
                    String errorType = getErrorCodeName(errorCode);

                    log("[Media Codec] onError " + errorType + " (" + errorCode + "): " + e.getDiagnosticInfo() +
                        ", Recoverable: " + e.isRecoverable() + ", Transient: " + e.isTransient());
                    VideoDebugLogger.logCodecError(e.toString(), e.isRecoverable(), e.isTransient());

                    // Handle specific error codes differently
                    if (errorCode == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE) {
                        // Resource contention - another app may be using the decoder
                        // Try releasing and recreating after a delay
                        log("[Media Codec] Insufficient resources - will attempt recovery");
                    } else if (errorCode == MediaCodec.CodecException.ERROR_RECLAIMED) {
                        // Codec was reclaimed by system - need full recreation
                        log("[Media Codec] Codec reclaimed by system - requires full restart");
                        mCodec = null;  // Mark as invalid
                    }

                    // Only auto-reset on recoverable errors
                    if (e.isRecoverable()) {
                        log("[Media Codec] Recoverable error - attempting reset");
                        // Schedule reset on executor to avoid blocking callback thread
                        executors.mediaCodec1().execute(() -> {
                            try {
                                reset();
                            } catch (Exception re) {
                                log("[Media Codec] Recovery reset failed: " + re.getMessage());
                            }
                        });
                    } else if (!e.isTransient()) {
                        log("[Media Codec] Fatal error - will reset on next start attempt");
                        // Don't automatically reset - let user restart manually to avoid crash loops
                    } else {
                        log("[Media Codec] Transient error - continuing operation");
                    }
                }
            }

            /**
             * Maps MediaCodec error codes to human-readable names for logging.
             */
            private String getErrorCodeName(int errorCode) {
                switch (errorCode) {
                    case MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE:
                        return "ERROR_INSUFFICIENT_RESOURCE";
                    case MediaCodec.CodecException.ERROR_RECLAIMED:
                        return "ERROR_RECLAIMED";
                    default:
                        return "UNKNOWN_ERROR";
                }
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                synchronized (codecLock) {
                    if (codec != mCodec) return;

                    int colorFormat = format.getInteger("color-format");
                    int formatWidth = format.getInteger("width");
                    int formatHeight = format.getInteger("height");

                    log("[Media Codec] onOutputFormatChanged - Format: " + format);
                    log("[Media Codec] Output format - Color: " + colorFormat + ", Size: " + formatWidth + "x" + formatHeight);
                    VideoDebugLogger.logCodecFormatChanged(formatWidth, formatHeight, colorFormat);
                }
            }
        };
    }

    private void logPerformanceStats() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastStatsTime;

        if (timeDiff > 0) {
            double fps = (double) totalFramesDecoded * 1000.0 / timeDiff;
            double dropRate = totalFramesDropped > 0 ? (double) totalFramesDropped / (totalFramesReceived + totalFramesDropped) * 100.0 : 0.0;
            double avgFrameSize = totalFramesReceived > 0 ? (double) totalBytesProcessed / totalFramesReceived / 1024.0 : 0.0;
            double throughputMbps = (double) totalBytesProcessed * 8.0 / (timeDiff * 1000.0); // Mbps

            // Adaptive FPS target detection - find closest common frame rate
            // This prevents false warnings when adapter is configured for 30fps instead of 60fps
            if (totalFramesReceived > 60) { // Need enough samples for reliable detection
                detectedTargetFps = findClosestFpsTarget(fps);
            }

            // Enhanced logging for Intel GPU performance analysis
            String perfMsg = String.format(Locale.US, "[PERF] FPS: %.1f/%d, Frames: R:%d/D:%d/Drop:%d, DropRate: %.1f%%, AvgSize: %.1fKB, Throughput: %.1fMbps, Resets: %d",
                fps, detectedTargetFps, totalFramesReceived, totalFramesDecoded, totalFramesDropped, dropRate, avgFrameSize, throughputMbps, codecResetCount);

            // Add Intel GPU specific metrics if available
            if (mCodec != null && mCodec.getName().contains("Intel")) {
                perfMsg += " [Intel Quick Sync Active]";
            }

            // Warning only if FPS is significantly below detected target (< 90% of target)
            // This avoids false alarms when adapter is configured for lower frame rates
            double fpsThreshold = detectedTargetFps * 0.90;
            if (fps < fpsThreshold && totalFramesReceived > 120) {
                perfMsg += String.format(Locale.US, " [WARNING: FPS below %.0f%% of %dfps target]", 90.0, detectedTargetFps);
            }

            // Monitor frame lag for performance analysis (no aggressive action)
            long frameLag = totalFramesReceived - totalFramesDecoded;
            if (frameLag > 10) { // Conservative threshold for monitoring only
                perfMsg += " [INFO: Frame lag " + frameLag + "]";
            }

            // Resolution-adaptive graduated memory pool monitoring
            int totalFreeBuffers = smallBuffers.size() + mediumBuffers.size() + largeBuffers.size();
            int poolUtilization = ((bufferPoolSize - totalFreeBuffers) * 100) / bufferPoolSize;
            int freeBuffers = totalFreeBuffers;

            if (freeBuffers < BUFFER_POOL_MIN_FREE) {
                perfMsg += " [POOL_CRITICAL: " + freeBuffers + "/" + bufferPoolSize + " free]";
            } else if (poolUtilization > 75) {
                perfMsg += " [POOL_HIGH: " + poolUtilization + "% used]";
            } else if (poolUtilization > 50) {
                perfMsg += " [POOL_NORMAL: " + poolUtilization + "% used]";
            }

            log(perfMsg);

            // Also log via VideoDebugLogger for structured debugging
            VideoDebugLogger.logPerformanceStats(fps, totalFramesReceived, totalFramesDecoded,
                    totalFramesDropped, dropRate, throughputMbps);
            VideoDebugLogger.logBufferPoolStatus(smallBuffers.size(), mediumBuffers.size(),
                    largeBuffers.size(), bufferPoolSize);

            // Reset counters for next measurement period
            totalFramesReceived = 0;
            totalFramesDecoded = 0;
            totalFramesDropped = 0;
            totalBytesProcessed = 0;
        }

        lastStatsTime = currentTime;
    }

    /**
     * Finds the closest standard video frame rate target for the measured FPS.
     * This enables adaptive performance monitoring that works correctly regardless
     * of whether the adapter is configured for 30fps, 60fps, or other common rates.
     *
     * @param measuredFps The actual measured frames per second
     * @return The closest common FPS target (25, 30, 50, or 60)
     */
    private int findClosestFpsTarget(double measuredFps) {
        int closest = COMMON_FPS_TARGETS[0];
        double minDiff = Math.abs(measuredFps - closest);

        for (int target : COMMON_FPS_TARGETS) {
            double diff = Math.abs(measuredFps - target);
            if (diff < minDiff) {
                minDiff = diff;
                closest = target;
            }
        }
        return closest;
    }

    /**
     * Detects the NAL unit type from H.264 data in a ByteBuffer.
     *
     * H.264 NAL units start with a start code (0x00 0x00 0x01 or 0x00 0x00 0x00 0x01)
     * followed by a NAL header byte. The lower 5 bits of the header contain the NAL type:
     *   1 = Non-IDR slice (P-frame or B-frame)
     *   5 = IDR slice (keyframe - Instantaneous Decoder Refresh)
     *   6 = SEI (Supplemental Enhancement Information)
     *   7 = SPS (Sequence Parameter Set)
     *   8 = PPS (Picture Parameter Set)
     *
     * @param buffer The ByteBuffer containing H.264 data (position at start of data)
     * @param dataSize The size of valid data in the buffer
     * @return The NAL unit type (1-31), or -1 if no valid NAL header found
     */
    private int detectNalUnitType(java.nio.ByteBuffer buffer, int dataSize) {
        if (dataSize < 5) return -1; // Need at least start code + NAL header

        // Save and restore buffer position
        int originalPosition = buffer.position();

        try {
            // Look for NAL start code: 0x00 0x00 0x01 or 0x00 0x00 0x00 0x01
            // Check first 10 bytes for start code (handle potential leading zeros)
            int searchLimit = Math.min(dataSize, 10);

            for (int i = 0; i < searchLimit - 3; i++) {
                int b0 = buffer.get(i) & 0xFF;
                int b1 = buffer.get(i + 1) & 0xFF;
                int b2 = buffer.get(i + 2) & 0xFF;

                if (b0 == 0x00 && b1 == 0x00) {
                    if (b2 == 0x01) {
                        // 3-byte start code: 00 00 01
                        if (i + 3 < dataSize) {
                            int nalHeader = buffer.get(i + 3) & 0xFF;
                            return nalHeader & 0x1F; // Lower 5 bits = NAL type
                        }
                    } else if (b2 == 0x00 && i + 3 < searchLimit) {
                        int b3 = buffer.get(i + 3) & 0xFF;
                        if (b3 == 0x01) {
                            // 4-byte start code: 00 00 00 01
                            if (i + 4 < dataSize) {
                                int nalHeader = buffer.get(i + 4) & 0xFF;
                                return nalHeader & 0x1F; // Lower 5 bits = NAL type
                            }
                        }
                    }
                }
            }

            return -1; // No valid start code found
        } finally {
            // Restore buffer position (important - codec will read from this position)
            buffer.position(originalPosition);
        }
    }

    /**
     * Scans H.264 data for SPS and PPS NAL units and caches them for later injection.
     *
     * The CPC200-CCPA adapter only sends SPS+PPS at session start (with the first IDR frame).
     * After codec flush(), the decoder loses this configuration and cannot decode frames
     * until it receives SPS+PPS again. Since FRAME command only triggers IDR (not SPS+PPS),
     * we must cache and re-inject the original SPS+PPS after each flush.
     *
     * Additionally, cached SPS+PPS is prepended to every IDR frame to prevent decoder
     * drift during continuous operation without resets.
     *
     * @param buffer ByteBuffer containing H.264 Annex B stream data
     * @param dataSize Size of valid data in buffer
     */
    private void cacheCodecConfigData(ByteBuffer buffer, int dataSize) {
        if (dataSize < 8) return; // Need at least start code + NAL header + some data

        synchronized (spsLock) {
            // Only cache if we don't have SPS/PPS yet
            if (cachedSps != null && cachedPps != null) return;

            try {
                // Use absolute get() to avoid modifying buffer position/limit
                // This is safer and avoids IllegalArgumentException on limit changes

                // Scan for NAL units and extract SPS (7) and PPS (8)
                int pos = 0;
                while (pos < dataSize - 4) {
                    // Find start code using absolute get
                    int startCodeLen = 0;
                    if (pos + 3 < dataSize &&
                        buffer.get(pos) == 0 && buffer.get(pos + 1) == 0 && buffer.get(pos + 2) == 1) {
                        startCodeLen = 3;
                    } else if (pos + 4 < dataSize &&
                        buffer.get(pos) == 0 && buffer.get(pos + 1) == 0 &&
                        buffer.get(pos + 2) == 0 && buffer.get(pos + 3) == 1) {
                        startCodeLen = 4;
                    }

                    if (startCodeLen > 0) {
                        int nalStart = pos;
                        int nalHeaderPos = pos + startCodeLen;
                        if (nalHeaderPos >= dataSize) break;

                        int nalType = buffer.get(nalHeaderPos) & 0x1F;

                        // Find the end of this NAL unit (next start code or end of data)
                        int nalEnd = dataSize;
                        int searchPos = nalHeaderPos + 1;
                        while (searchPos < dataSize - 3) {
                            if (buffer.get(searchPos) == 0 && buffer.get(searchPos + 1) == 0 &&
                                (buffer.get(searchPos + 2) == 1 ||
                                 (searchPos + 3 < dataSize && buffer.get(searchPos + 2) == 0 && buffer.get(searchPos + 3) == 1))) {
                                nalEnd = searchPos;
                                break;
                            }
                            searchPos++;
                        }

                        // Cache SPS (NAL type 7) or PPS (NAL type 8)
                        if (nalType == 7 && cachedSps == null) {
                            int nalSize = nalEnd - nalStart;
                            cachedSps = new byte[nalSize];
                            // Use absolute get to copy bytes without modifying buffer state
                            for (int i = 0; i < nalSize; i++) {
                                cachedSps[i] = buffer.get(nalStart + i);
                            }
                            log("[SPS_CACHE] Cached SPS: " + nalSize + " bytes");
                        } else if (nalType == 8 && cachedPps == null) {
                            int nalSize = nalEnd - nalStart;
                            cachedPps = new byte[nalSize];
                            for (int i = 0; i < nalSize; i++) {
                                cachedPps[i] = buffer.get(nalStart + i);
                            }
                            log("[PPS_CACHE] Cached PPS: " + nalSize + " bytes");
                        }

                        pos = nalEnd;
                    } else {
                        pos++;
                    }
                }

                if (cachedSps != null && cachedPps != null) {
                    log("[CODEC_CONFIG] SPS+PPS cached successfully - will prepend to IDR frames");
                }
            } catch (Exception e) {
                log("[CODEC_CONFIG] Error caching SPS/PPS: " + e.getMessage());
            }
        }
    }

    /**
     * Signals that codec config (SPS/PPS) should be injected on the next available input buffer.
     * This is called after flush/reset to ensure decoder has codec-specific data.
     *
     * In async mode, we cannot use dequeueInputBuffer(). Instead, we set a flag and
     * inject via the onInputBufferAvailable callback.
     *
     * If SPS/PPS is not cached, proactively requests a keyframe from the adapter since
     * the adapter only sends SPS/PPS at session start (not on FRAME command).
     */
    private void requestCodecConfigInjection() {
        synchronized (spsLock) {
            if (cachedSps == null || cachedPps == null) {
                log("[CODEC_CONFIG] No cached SPS/PPS - requesting keyframe from adapter");
                // Proactively request keyframe since adapter only sends SPS/PPS at session start
                if (keyframeCallback != null) {
                    try {
                        keyframeCallback.onKeyframeNeeded();
                        lastKeyframeRequestTime = System.currentTimeMillis();
                    } catch (Exception e) {
                        log("[CODEC_CONFIG] Keyframe request failed: " + e.getMessage());
                    }
                }
                return;
            }
            codecConfigPending = true;
            log("[CODEC_CONFIG] Codec config injection requested - will inject on next available buffer");
        }
    }

    /**
     * Injects cached SPS+PPS into the provided codec buffer.
     * Called from onInputBufferAvailable when codecConfigPending is true.
     *
     * @param codec The MediaCodec instance
     * @param bufferIndex The available input buffer index
     * @return true if injection was successful, false otherwise
     */
    private boolean injectCodecConfigToBuffer(MediaCodec codec, int bufferIndex) {
        synchronized (spsLock) {
            if (cachedSps == null || cachedPps == null) {
                codecConfigPending = false;
                return false;
            }

            try {
                ByteBuffer inputBuffer = codec.getInputBuffer(bufferIndex);
                if (inputBuffer == null) {
                    log("[CODEC_CONFIG] getInputBuffer returned null");
                    return false;
                }

                int totalSize = cachedSps.length + cachedPps.length;
                if (inputBuffer.capacity() < totalSize) {
                    log("[CODEC_CONFIG] Buffer too small: " + inputBuffer.capacity() + " < " + totalSize);
                    return false;
                }

                inputBuffer.clear();
                inputBuffer.put(cachedSps);
                inputBuffer.put(cachedPps);

                // Submit with BUFFER_FLAG_CODEC_CONFIG
                codec.queueInputBuffer(bufferIndex, 0, totalSize, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);

                log("[CODEC_CONFIG] Injected SPS+PPS (" + totalSize + " bytes) via callback");
                codecConfigPending = false;
                return true;
            } catch (Exception e) {
                log("[CODEC_CONFIG] Injection failed: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Prepends cached SPS+PPS to IDR frame data and queues to codec.
     * This ensures decoder always has fresh configuration with each keyframe,
     * preventing video degradation during continuous operation.
     *
     * @param codec The MediaCodec instance
     * @param bufferIndex The available input buffer index
     * @param frameData The original IDR frame data
     * @param frameSize Size of the IDR frame data
     * @param pts Presentation timestamp
     * @return true if successful, false otherwise
     */
    private boolean queueIdrWithSpsPps(MediaCodec codec, int bufferIndex,
                                        ByteBuffer frameData, int frameSize, long pts) {
        synchronized (spsLock) {
            if (cachedSps == null || cachedPps == null) {
                // No cached SPS/PPS, queue frame as-is
                return false;
            }

            try {
                ByteBuffer inputBuffer = codec.getInputBuffer(bufferIndex);
                if (inputBuffer == null) {
                    return false;
                }

                int totalSize = cachedSps.length + cachedPps.length + frameSize;
                if (inputBuffer.capacity() < totalSize) {
                    log("[IDR_INJECT] Buffer too small for SPS+PPS+IDR: " + inputBuffer.capacity() + " < " + totalSize);
                    return false;
                }

                // Build combined buffer: SPS + PPS + IDR
                inputBuffer.clear();
                inputBuffer.put(cachedSps);
                inputBuffer.put(cachedPps);

                // Copy IDR frame data
                int originalPosition = frameData.position();
                frameData.position(0);
                for (int i = 0; i < frameSize; i++) {
                    inputBuffer.put(frameData.get(i));
                }
                frameData.position(originalPosition);

                codec.queueInputBuffer(bufferIndex, 0, totalSize, pts, 0);

                // Log occasionally to avoid spam
                if (totalFramesDecoded % 100 == 0) {
                    log("[IDR_INJECT] Prepended SPS+PPS to IDR frame (total: " + totalSize + " bytes)");
                }
                return true;
            } catch (Exception e) {
                log("[IDR_INJECT] Failed: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Checks if NAL data contains an IDR frame (NAL type 5).
     * IDR frames are keyframes that can start a new decoding sequence.
     */
    private boolean containsIdrFrame(ByteBuffer buffer, int dataSize) {
        if (dataSize < 5) return false;

        int searchLimit = Math.min(dataSize, 64); // IDR NAL usually near start
        for (int i = 0; i < searchLimit - 4; i++) {
            if (buffer.get(i) == 0 && buffer.get(i + 1) == 0) {
                int nalOffset = -1;
                if (buffer.get(i + 2) == 1) {
                    nalOffset = i + 3;
                } else if (buffer.get(i + 2) == 0 && i + 3 < searchLimit && buffer.get(i + 3) == 1) {
                    nalOffset = i + 4;
                }
                if (nalOffset >= 0 && nalOffset < dataSize) {
                    int nalType = buffer.get(nalOffset) & 0x1F;
                    if (nalType == 5) return true; // IDR
                }
            }
        }
        return false;
    }
}
