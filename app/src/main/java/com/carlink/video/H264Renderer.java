package com.carlink.video;

/**
  * H264Renderer - Hardware-Accelerated H.264 Video Decoder & Renderer
  *
  * PURPOSE:
  * Decodes and renders H.264 video streams from CarPlay/Android Auto projection sessions
  * using Android MediaCodec API with hardware acceleration (Intel Quick Sync preferred).
  *
  * KEY RESPONSIBILITIES:
  * - Hardware-accelerated H.264 video decoding via MediaCodec
  * - Real-time video stream buffering using ring buffer architecture
  * - Resolution-adaptive memory management (800x480 to 4K+)
  * - Direct Surface rendering via HWC overlay for optimal performance
  * - Performance monitoring (FPS, throughput, buffer health)
  *
  * OPTIMIZATION TARGETS:
  * - Primary: GM GMinfo3.7 (2400x960@60fps, Intel HD Graphics 505, 6GB RAM)
  * - Adaptive: Standard automotive displays (800x480 to 1080p)
  * - Extended: High-resolution displays (up to 4K)
  *
  * ARCHITECTURE:
  * - Asynchronous MediaCodec callback pipeline for low-latency decoding
  * - Multi-threaded codec feeding using dedicated high-priority executors
  * - Graduated memory pools (small/medium/large buffers) for efficient reuse
  * - Direct ByteBuffer allocation for zero-copy DMA operations
  * - SurfaceView direct rendering (HWC overlay path, no GPU composition)
  *
  * HARDWARE INTEGRATION:
  * - Input: Video packets from CPC200-CCPA adapter via USB
  * - Decoder: Intel Quick Sync (OMX.Intel.VideoDecoder.AVC) or fallback to generic
  * - Output: Direct to SurfaceView via HWC overlay
  *
  * LIFECYCLE:
  * start() -> Initialize codec -> Feed packets -> Decode -> Render -> stop() -> Cleanup
  * Supports reset() for recovery from codec errors or configuration changes
  *
  * @see PacketRingByteBuffer for video packet buffering implementation
  * @see AppExecutors for thread pool management
  */
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.carlink.util.AppExecutors;
import com.carlink.util.LogCallback;
import com.carlink.util.VideoDebugLogger;


public class H264Renderer {
    private static final String LOG_TAG = "CARLINK";

    /**
     * Callback interface for requesting keyframes (IDR frames) from the video source.
     *
     * After codec reset (flush+start), the H.264 decoder loses its SPS/PPS context
     * and cannot decode P-frames without a new keyframe. This callback allows the
     * renderer to request an immediate keyframe from the adapter.
     */
    public interface KeyframeRequestCallback {
        /**
         * Called when the renderer needs a new keyframe (IDR frame).
         * Implementation should send CommandMapping.FRAME to the adapter.
         */
        void onKeyframeNeeded();
    }

    // Thread safety lock for codec lifecycle operations
    // Prevents race conditions between callbacks and reset/stop operations
    // See: https://android.googlesource.com/platform/cts/+/0e8a8a0%5E!/
    private final Object codecLock = new Object();

    private volatile MediaCodec mCodec;  // volatile for visibility across threads
    private MediaCodec.Callback codecCallback;
    private ArrayList<Integer> codecAvailableBufferIndexes = new ArrayList<>(10);
    private int width;
    private int height;
    private Surface surface;
    private volatile boolean running = false;  // volatile for callback thread visibility
    private boolean bufferLoopRunning = false;
    private LogCallback logCallback;
    private KeyframeRequestCallback keyframeCallback;

    private final AppExecutors executors;

    private PacketRingByteBuffer ringBuffer;

    // Preferred hardware decoder name (detected by PlatformDetector)
    // e.g., "OMX.Intel.hw_vd.h264" for GM gminfo37
    private final String preferredDecoderName;

    // Safe optimization parameters - no aggressive frame skipping during startup
    // ADB analysis: gminfo37 display is 2400x960@60.001434fps, VSYNC period = 16.666268ms
    private static final int TARGET_FPS = 60; // 2400x960@60fps target
    private static final long DYNAMIC_TIMEOUT_US = 16666; // 16.666ms per frame (matches gminfo37 VSYNC)
    private boolean decoderInitialized = false; // Track if decoder has started outputting frames
    private int consecutiveOutputFrames = 0; // Count successful decoder outputs

    // Dynamic resolution-aware memory pool sizing for automotive displays
    private int bufferPoolSize; // Calculated based on resolution and device capabilities
    private static final int BUFFER_POOL_MIN_FREE = 2; // Maintain 2 free buffers for headroom

    // Resolution-based buffer pool scaling
    // Increased from 6/20 to 8/24 based on POOL_CRITICAL warnings in logcat
    private static final int MIN_POOL_SIZE = 8;  // Small displays (800x480)
    private static final int MAX_POOL_SIZE = 24; // Ultra-high res (4K+)
    // Improved buffer pool with size buckets for better reuse
    private final ConcurrentLinkedQueue<ByteBuffer> smallBuffers = new ConcurrentLinkedQueue<>();  // <= 64KB
    private final ConcurrentLinkedQueue<ByteBuffer> mediumBuffers = new ConcurrentLinkedQueue<>(); // <= 256KB
    private final ConcurrentLinkedQueue<ByteBuffer> largeBuffers = new ConcurrentLinkedQueue<>();  // > 256KB
    private boolean poolInitialized = false;

    // Size thresholds for buffer buckets
    private static final int SMALL_BUFFER_THRESHOLD = 64 * 1024;   // 64KB
    private static final int MEDIUM_BUFFER_THRESHOLD = 256 * 1024; // 256KB

    // Performance monitoring
    private long totalFramesReceived = 0;
    private long totalFramesDecoded = 0;
    private long totalFramesDropped = 0;
    private long lastStatsTime = 0;
    private long codecResetCount = 0;
    private long totalBytesProcessed = 0;

    // Adaptive FPS detection - tracks actual stream frame rate
    // Common rates: 30fps (some adapters), 60fps (CarPlay default), 25fps (PAL regions)
    private int detectedTargetFps = 60; // Initial assumption, updated based on actual rate
    private static final int[] COMMON_FPS_TARGETS = {25, 30, 50, 60}; // Standard video frame rates

    // Performance logging interval (30 seconds)
    private static final long PERF_LOG_INTERVAL_MS = 30000;
    private long lastPerfLogTime = 0;

    // Codec reset rate limiting - prevents rapid reset loops during startup
    private static final long MIN_RESET_INTERVAL_MS = 500; // Minimum 500ms between resets
    private static final int MAX_RAPID_RESETS = 3; // Max resets within window before cooldown
    private static final long RESET_WINDOW_MS = 5000; // 5 second window for tracking rapid resets
    private static final long RESET_COOLDOWN_MS = 2000; // 2 second cooldown after rapid resets
    private long lastResetTime = 0;
    private int resetsInWindow = 0;
    private long windowStartTime = 0;
    private boolean inCooldown = false;

    // Startup stabilization - don't reset during initial codec warmup
    // Prevents rapid resets during Surface sizing that corrupt callback state
    private static final long MIN_STARTUP_TIME_MS = 2000; // 2 seconds for codec to stabilize
    private long codecStartTime = 0;

    // Post-reset keyframe detection - request keyframe if receiving frames but not decoding
    // This handles the case where keyframe request fails during adapter reconnection
    private static final int KEYFRAME_REQUEST_THRESHOLD = 15; // Request after 15 frames with no output (reduced from 30)
    private static final long KEYFRAME_REQUEST_TIME_THRESHOLD_MS = 5000; // OR after 5 seconds with no output
    private static final long KEYFRAME_REQUEST_COOLDOWN_MS = 2000; // Don't spam requests
    private long framesReceivedSinceReset = 0;
    private long framesDecodedSinceReset = 0;
    private long lastKeyframeRequestTime = 0;
    private long resetTimestamp = 0; // Timestamp when reset occurred, for time-based detection
    private boolean pendingKeyframeRequest = false;

    // Dedicated HandlerThread for MediaCodec callbacks
    // Per Android documentation, callbacks run on the codec's internal looper when no Handler is specified.
    // Using a dedicated HandlerThread ensures callbacks are delivered reliably regardless of main thread state.
    // See: https://developer.android.com/reference/android/media/MediaCodec#setCallback
    private HandlerThread codecCallbackThread;
    private Handler codecCallbackHandler;

    private int calculateOptimalBufferSize(int width, int height) {
        // Base calculation for different resolutions - this is for the ring buffer
        int pixels = width * height;

        if (pixels <= 1920 * 1080) {
            // 1080p and below: 8MB buffer (standard)
            return 8 * 1024 * 1024;
        } else if (pixels <= 2400 * 960) {
            // Native GMinfo3.7 resolution: 16MB buffer (2x standard)
            return 16 * 1024 * 1024;
        } else if (pixels <= 3840 * 2160) {
            // 4K: 32MB buffer for high bitrate content
            return 32 * 1024 * 1024;
        } else {
            // Ultra-high resolution: 64MB buffer
            return 64 * 1024 * 1024;
        }
    }

    private int calculateOptimalPoolSize(int width, int height) {
        // Resolution-based buffer pool count calculation
        // Increased pool sizes based on POOL_CRITICAL warnings in production logs
        int pixels = width * height;

        if (pixels <= 800 * 480) {
            // Small automotive displays (7-8 inch): minimal buffering
            return MIN_POOL_SIZE; // 8 buffers (increased from 6)
        } else if (pixels <= 1024 * 600) {
            // Standard automotive displays (8-10 inch): basic buffering
            return 10; // 10 buffers (increased from 8)
        } else if (pixels <= 1920 * 1080) {
            // HD automotive displays: standard buffering
            return 14; // 14 buffers (increased from 10)
        } else if (pixels <= 2400 * 960) {
            // Native GMinfo3.7 resolution: optimized for Intel HD Graphics 505
            // ADB analysis: Intel Broxton platform with 5.5GB RAM, 48kHz audio
            // Increased from 12 to 16 based on POOL_CRITICAL: 0/12 warnings
            // Device supports 4K@60 via OMX.Intel.hw_vd.h264 but has adaptive-playback latency issues
            return 16; // 16 buffers
        } else if (pixels <= 3840 * 2160) {
            // 4K displays: high buffering for stability
            return 20; // 20 buffers (increased from 16)
        } else {
            // Ultra-high resolution: maximum buffering
            return MAX_POOL_SIZE; // 24 buffers (increased from 20)
        }
    }

    private int calculateOptimalFrameBufferSize(int width, int height) {
        // Per-frame buffer size calculation based on resolution
        int pixels = width * height;

        // Base calculation: assume 4 bytes per pixel for worst case + compression overhead
        // Research shows MediaCodec needs headroom for different frame types (I, P, B)
        int baseSize = (pixels * 4) / 10; // Compressed H.264 is typically ~10:1 ratio

        // Minimum sizes based on research findings
        if (pixels <= 800 * 480) {
            return Math.max(baseSize, 64 * 1024);   // 64KB minimum for small displays
        } else if (pixels <= 1920 * 1080) {
            return Math.max(baseSize, 128 * 1024);  // 128KB minimum for HD
        } else if (pixels <= 2400 * 960) {
            return Math.max(baseSize, 256 * 1024);  // 256KB minimum for gminfo3.7
        } else if (pixels <= 3840 * 2160) {
            return Math.max(baseSize, 512 * 1024);  // 512KB minimum for 4K
        } else {
            return Math.max(baseSize, 1024 * 1024); // 1MB minimum for ultra-high res
        }
    }

    /**
     * Creates a new H264Renderer for hardware-accelerated video decoding.
     *
     * @param context Android context for accessing system services
     * @param width Video width in pixels
     * @param height Video height in pixels
     * @param surface Surface from SurfaceView for direct HWC rendering
     * @param logCallback Callback for logging messages
     * @param executors Thread pool for codec operations
     * @param preferredDecoderName Optional preferred decoder name from PlatformDetector
     *                             (e.g., "OMX.Intel.hw_vd.h264" for GM gminfo37)
     */
    public H264Renderer(Context context, int width, int height, Surface surface, LogCallback logCallback, AppExecutors executors, String preferredDecoderName) {
        this.width = width;
        this.height = height;
        this.surface = surface;
        this.logCallback = logCallback;
        this.executors = executors;
        this.preferredDecoderName = preferredDecoderName;

        // Optimize buffer size for 6GB RAM system and 2400x960@60fps target
        // Calculate optimal buffer: ~2-3 seconds of 4K video = 32MB for safety margin
        int bufferSize = calculateOptimalBufferSize(width, height);
        ringBuffer = new PacketRingByteBuffer(bufferSize);
        log("Ring buffer initialized: " + (bufferSize / (1024*1024)) + "MB for " + width + "x" + height);

        // Initialize memory pool after successful codec startup - research shows 30x performance improvement
        initializeBufferPool();

        codecCallback = createCallback();
    }

    /**
     * Sets the callback for requesting keyframes after codec reset.
     * @param callback The callback to invoke when a keyframe is needed
     */
    public void setKeyframeRequestCallback(KeyframeRequestCallback callback) {
        this.keyframeCallback = callback;
    }

    private void log(String message) {
        String formattedMessage = "[H264_RENDERER] " + message;
        // Log.d(LOG_TAG, formattedMessage);  // Removed to eliminate duplicate logging
        logCallback.log(formattedMessage);
    }

    public void start() {
        if (running) return;

        running = true;
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
            log("start error " + e.toString());
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

        synchronized (codecAvailableBufferIndexes) {
            // Check both conditions inside the synchronized block to prevent race condition
            if (codecAvailableBufferIndexes.isEmpty() || ringBuffer.isEmpty()) {
                return false;
            }

            int index = codecAvailableBufferIndexes.remove(0);

            ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
            byteBuffer.put(ringBuffer.readPacket());

            mCodec.queueInputBuffer(index, 0, byteBuffer.position(), 0, 0);
        }

        return true;
    }

    private void fillAllAvailableCodecBuffers(MediaCodec codec) {
        boolean filled = true;

        while (filled) {
            filled = fillFirstAvailableCodecBuffer(codec);
        }
    }

    private void feedCodec() {
        // Optimize for Intel Atom x7-A3960 quad-core
        // Use dedicated high-priority thread for codec feeding to reduce latency
        executors.mediaCodec1().execute(() -> {
            // Thread priority already set by OptimizedMediaCodecExecutor
            // No need to set it again here

            try {
                fillAllAvailableCodecBuffers(mCodec);

                // Buffer health monitoring for automotive stability
                if (ringBuffer != null) {
                    int packetCount = ringBuffer.availablePacketsToRead();
                    if (packetCount > 20) { // Warn if buffer is getting full
                        log("[BUFFER_WARNING] High buffer usage: " + packetCount + " packets");
                    }
                }
            } catch (Exception e) {
                log("[Media Codec] fill input buffer error:" + e.toString());
                // Let MediaCodec.Callback.onError() handle recovery properly
            }
        });
    }

    public void stop() {
        if (!running) return;

        running = false;
        VideoDebugLogger.logCodecStopped();

        // Clean up MediaCodec resources following official Android guidelines
        // Use codecLock to prevent race with callbacks
        synchronized (codecLock) {
            try {
                if (mCodec != null) {
                    mCodec.stop();
                    mCodec.release();
                    mCodec = null;
                }
            } catch (Exception e) {
                log("STOP: MediaCodec cleanup failed - " + e.toString());
                mCodec = null; // Force null to prevent further issues
            } finally {
                // Always clean up additional resources regardless of MediaCodec cleanup success
                cleanupResources();
            }
        }
    }

    private void cleanupResources() {
        // NOTE: Surface is NOT released here - it is owned by SurfaceView
        // The SurfaceView manages the Surface lifecycle through SurfaceHolder.Callback
        // We just null our reference to prevent use after the view is destroyed
        surface = null;
        log("Surface reference cleared (lifecycle managed by SurfaceView)");

        // Clean up the dedicated callback HandlerThread
        // Use quitSafely() to allow pending callbacks to complete before stopping
        if (codecCallbackThread != null) {
            codecCallbackThread.quitSafely();
            try {
                // Wait briefly for thread to terminate
                codecCallbackThread.join(500);
            } catch (InterruptedException e) {
                log("Interrupted while waiting for callback thread to terminate");
            }
            codecCallbackThread = null;
            codecCallbackHandler = null;
            log("Callback HandlerThread cleaned up");
        }

        // Clear all buffer pools to prevent memory accumulation
        int totalCleared = smallBuffers.size() + mediumBuffers.size() + largeBuffers.size();
        smallBuffers.clear();
        mediumBuffers.clear();
        largeBuffers.clear();
        log("Buffer pools cleared - " + totalCleared + " buffers released");

        // Clear codec buffer indexes
        synchronized (codecAvailableBufferIndexes) {
            codecAvailableBufferIndexes.clear();
        }

        // Reset pool initialization flag to allow re-initialization
        poolInitialized = false;
    }


    public void reset() {
        long currentTime = System.currentTimeMillis();

        // CRITICAL: Don't reset during startup stabilization period
        // Rapid resets during Surface sizing corrupt the callback thread state
        // causing onInputBufferAvailable to stop firing (D:0 frames decoded)
        if (codecStartTime > 0 && (currentTime - codecStartTime) < MIN_STARTUP_TIME_MS) {
            log("[RESET_THROTTLE] Skipping reset during startup stabilization (" +
                (currentTime - codecStartTime) + "ms since start, need " + MIN_STARTUP_TIME_MS + "ms)");
            return;
        }

        // Rate limiting: Check if we're in cooldown period after rapid resets
        if (inCooldown) {
            if (currentTime - lastResetTime < RESET_COOLDOWN_MS) {
                log("[RESET_THROTTLE] In cooldown period, skipping reset request");
                return;
            }
            inCooldown = false;
            resetsInWindow = 0;
            log("[RESET_THROTTLE] Cooldown period ended");
        }

        // Rate limiting: Enforce minimum interval between resets
        if (currentTime - lastResetTime < MIN_RESET_INTERVAL_MS) {
            log("[RESET_THROTTLE] Reset requested too soon (" + (currentTime - lastResetTime) + "ms), skipping");
            return;
        }

        // Track resets within window for rapid reset detection
        if (currentTime - windowStartTime > RESET_WINDOW_MS) {
            // Start new window
            windowStartTime = currentTime;
            resetsInWindow = 1;
        } else {
            resetsInWindow++;
            // Check for rapid reset pattern
            if (resetsInWindow >= MAX_RAPID_RESETS) {
                log("[RESET_THROTTLE] Rapid reset detected (" + resetsInWindow + " resets in " +
                    (currentTime - windowStartTime) + "ms), entering cooldown");
                inCooldown = true;
                lastResetTime = currentTime;
                return;
            }
        }

        lastResetTime = currentTime;
        codecResetCount++;
        log("reset codec - Reset count: " + codecResetCount + ", Frames decoded: " + totalFramesDecoded +
            ", Resets in window: " + resetsInWindow);
        VideoDebugLogger.logCodecReset(codecResetCount);

        // Use codecLock to synchronize with callbacks
        synchronized (codecLock) {
            // Clear codec buffer indexes FIRST - they become invalid after flush
            synchronized (codecAvailableBufferIndexes) {
                codecAvailableBufferIndexes.clear();
            }

            // Clear ring buffer - stale data may confuse decoder after reset
            // New SPS/PPS will arrive from the adapter
            ringBuffer.reset();
            log("[RESET] Ring buffer cleared");

            // Reset keyframe detection counters - will request keyframe if we receive
            // frames but can't decode them (handles case where immediate request fails)
            framesReceivedSinceReset = 0;
            framesDecodedSinceReset = 0;
            resetTimestamp = System.currentTimeMillis(); // Record reset time for time-based detection
            pendingKeyframeRequest = true;  // Enable detection mode

            // CRITICAL FIX: Use flush() + start() instead of stop/release/recreate
            // Per Android docs: In async mode, you MUST call start() after flush()
            // to resume receiving input buffer callbacks.
            // See: https://developer.android.com/reference/android/media/MediaCodec#flush()
            if (mCodec != null) {
                try {
                    mCodec.flush();
                    mCodec.start();  // REQUIRED in async mode after flush!
                    codecStartTime = System.currentTimeMillis();  // Reset stabilization timer
                    log("[RESET] Codec flush+start completed - callbacks should resume");
                    VideoDebugLogger.logCodecStarted();
                } catch (Exception e) {
                    log("[RESET] flush+start failed: " + e.toString() + " - falling back to full recreate");
                    // Fallback: full codec recreation if flush fails
                    try {
                        mCodec.stop();
                        mCodec.release();
                        mCodec = null;
                    } catch (Exception e2) {
                        log("[RESET] Fallback cleanup failed: " + e2.toString());
                        mCodec = null;
                    }
                    // Recreate codec
                    try {
                        initCodec(width, height, surface);
                        mCodec.start();
                        codecStartTime = System.currentTimeMillis();
                        log("[RESET] Fallback codec recreation completed");
                        VideoDebugLogger.logCodecStarted();
                    } catch (Exception e3) {
                        log("[RESET] Fallback codec creation failed: " + e3.toString());
                    }
                }
            } else {
                // Codec was null - need full start
                log("[RESET] Codec was null, performing full start");
                running = false;  // Allow start() to proceed
                start();
            }
        }

        // Request keyframe AFTER reset completes (outside codecLock to avoid deadlock)
        // The H.264 decoder loses SPS/PPS context after flush() and cannot decode
        // P-frames without a new keyframe (IDR frame).
        // NOTE: This immediate request may fail if adapter is reconnecting - the
        // detection logic in onInputBufferAvailable will retry if needed.
        if (keyframeCallback != null) {
            log("[RESET] Requesting keyframe from adapter (immediate attempt)");
            try {
                keyframeCallback.onKeyframeNeeded();
                lastKeyframeRequestTime = System.currentTimeMillis();
            } catch (Exception e) {
                log("[RESET] Immediate keyframe request failed: " + e.toString() + " - will retry via detection");
            }
        }
    }

    /**
     * Pause video decoding when app goes to background.
     *
     * On AAOS, when the app is covered by another app, the Surface may remain valid
     * but SurfaceFlinger stops consuming frames. This causes the BufferQueue to fill up,
     * stalling the decoder. When the user returns, video appears blank.
     *
     * This method flushes the codec to clear pending buffers and prevents new frames
     * from being queued until resume() is called.
     *
     * Call this from Activity.onStop() to prevent BufferQueue stalls.
     */
    public void pause() {
        log("[LIFECYCLE] pause() called - flushing codec for background");

        synchronized (codecLock) {
            if (mCodec == null || !running) {
                log("[LIFECYCLE] Codec not running, nothing to pause");
                return;
            }

            try {
                // Flush codec to clear all pending input/output buffers
                // This prevents BufferQueue from filling up while in background
                mCodec.flush();

                // Clear our buffer tracking
                synchronized (codecAvailableBufferIndexes) {
                    codecAvailableBufferIndexes.clear();
                }

                // Clear ring buffer - stale data should not be decoded on resume
                ringBuffer.reset();

                log("[LIFECYCLE] Codec paused - buffers flushed, ready for background");
            } catch (Exception e) {
                log("[LIFECYCLE] pause() failed: " + e.toString());
            }
        }
    }

    /**
     * Resume video decoding when app returns to foreground.
     *
     * After pause(), the codec is in a flushed state. This method restarts the codec
     * and requests a keyframe so video can resume immediately.
     *
     * Call this from Activity.onStart() to resume video playback.
     */
    public void resume() {
        log("[LIFECYCLE] resume() called - restarting codec for foreground");

        synchronized (codecLock) {
            if (mCodec == null) {
                log("[LIFECYCLE] Codec is null, cannot resume");
                return;
            }

            if (!running) {
                log("[LIFECYCLE] Codec not running, triggering full start");
                // Will be handled by normal start() flow
                return;
            }

            try {
                // Restart codec after flush (required in async mode)
                mCodec.start();
                codecStartTime = System.currentTimeMillis();

                // Reset keyframe detection - we need a new IDR frame
                framesReceivedSinceReset = 0;
                framesDecodedSinceReset = 0;
                resetTimestamp = System.currentTimeMillis();
                pendingKeyframeRequest = true;

                log("[LIFECYCLE] Codec resumed - awaiting keyframe");
                VideoDebugLogger.logCodecStarted();
            } catch (Exception e) {
                log("[LIFECYCLE] resume() failed: " + e.toString() + " - will need full reset");
            }
        }

        // Request keyframe outside lock to avoid deadlock
        if (keyframeCallback != null) {
            log("[LIFECYCLE] Requesting keyframe after resume");
            try {
                keyframeCallback.onKeyframeNeeded();
                lastKeyframeRequestTime = System.currentTimeMillis();
            } catch (Exception e) {
                log("[LIFECYCLE] Keyframe request after resume failed: " + e.toString());
            }
        }
    }


    private void initCodec(int width, int height, Surface surface) throws Exception {
        log("init media codec - Resolution: " + width + "x" + height);

        // Codec selection using PlatformDetector-provided decoder name
        // This ensures we use the correct codec for the platform (e.g., OMX.Intel.hw_vd.h264 for gminfo37)
        MediaCodec codec = null;
        String codecName = null;

        // Try preferred decoder first (from PlatformDetector)
        if (preferredDecoderName != null && !preferredDecoderName.isEmpty()) {
            try {
                codec = MediaCodec.createByCodecName(preferredDecoderName);
                codecName = preferredDecoderName;
                log("Using platform-detected decoder: " + codecName);
            } catch (Exception e) {
                log("Preferred decoder '" + preferredDecoderName + "' not available: " + e.getMessage());
            }
        }

        // Fallback to generic hardware decoder if preferred not available
        if (codec == null) {
            try {
                codec = MediaCodec.createDecoderByType("video/avc");
                codecName = codec.getName();
                log("Using generic decoder: " + codecName);
            } catch (Exception e2) {
                throw new Exception("No H.264 decoder available", e2);
            }
        }

        mCodec = codec;
        log("codec created: " + codecName);
        VideoDebugLogger.logCodecInit(codecName, width, height);

        final MediaFormat mediaformat = MediaFormat.createVideoFormat("video/avc", width, height);

        // Intel HD Graphics 505 optimization for 2400x960@60fps
        // Low latency decoding (minSdk 32 >= API 30, so always available)
        try {
            mediaformat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            log("Low latency mode enabled");
        } catch (Exception e) {
            log("Low latency mode not supported: " + e.getMessage());
        }

        // Set realtime priority (0 = realtime, 1 = best effort)
        try {
            mediaformat.setInteger(MediaFormat.KEY_PRIORITY, 0);
            log("Realtime priority set");
        } catch (Exception e) {
            log("Priority setting not supported on this API level");
        }

        // Intel Quick Sync specific optimizations
        // Research: Intel decoders have high latency when Adaptive Playback is enabled
        // See: https://community.intel.com/t5/Software-Archive/High-Video-Latency-using-MediaCodec-on-Moorefield-H264-Decoder/m-p/998290
        if (codecName != null && codecName.contains("Intel")) {
            try {
                // Optimize buffer count for Intel Quick Sync (typically 8-16 buffers)
                mediaformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height);
                mediaformat.setInteger("max-concurrent-instances", 1); // Single instance for automotive

                // CRITICAL: Do NOT set FEATURE_AdaptivePlayback for Intel decoders
                // Research shows this triggers the "high latency" code path in Intel's libmix
                // Instead, we handle resolution changes by codec reset (already implemented)
                log("Intel Quick Sync optimizations applied (Adaptive Playback disabled for low latency)");
            } catch (Exception e) {
                log("Intel-specific optimizations not supported: " + e.getMessage());
            }
        }

        // For non-Intel decoders, we could enable Adaptive Playback if supported
        // But for automotive use case with fixed resolution, it's not needed

        log("media format created: " + mediaformat);

        // Create dedicated HandlerThread for MediaCodec callbacks
        // This ensures callbacks are delivered reliably on a dedicated thread with high priority
        // Previously, callbacks were delivered on the codec's internal looper which could be blocked
        if (codecCallbackThread == null || !codecCallbackThread.isAlive()) {
            codecCallbackThread = new HandlerThread("MediaCodecCallbackThread", Process.THREAD_PRIORITY_URGENT_AUDIO);
            codecCallbackThread.start();
            codecCallbackHandler = new Handler(codecCallbackThread.getLooper());
            log("Created dedicated callback thread with URGENT_AUDIO priority");
        }

        // CRITICAL: setCallback() MUST be called BEFORE configure() for asynchronous mode
        // Per Android documentation: "if the client intends to use the component in asynchronous mode,
        // a valid callback should be provided before configure() is called"
        // See: https://developer.android.com/reference/android/media/MediaCodec#setCallback
        // Using dedicated Handler ensures callbacks are delivered regardless of main thread state
        log("media codec setting async callback (before configure) with dedicated Handler");
        mCodec.setCallback(codecCallback, codecCallbackHandler);

        codecAvailableBufferIndexes.clear();

        log("configure media codec");
        mCodec.configure(mediaformat, surface, null, 0);
        VideoDebugLogger.logCodecConfigured(mediaformat.toString());
        VideoDebugLogger.logSurfaceBound(surface != null && surface.isValid());
    }

    /**
     * Process video data directly from a ByteArray.
     * This is a convenience method for native Android apps.
     *
     * @param data H.264 NAL unit data
     * @param flags Frame flags (unused, for API compatibility)
     */
    public void processData(byte[] data, int flags) {
        if (data == null || data.length == 0) return;

        processDataDirect(data.length, 0, (buffer, offset) -> {
            System.arraycopy(data, 0, buffer, offset, data.length);
        });
    }

    public void processDataDirect(int length, int skipBytes, PacketRingByteBuffer.DirectWriteCallback callback) {
        totalFramesReceived++;
        totalBytesProcessed += length;

        // CRITICAL FIX: Never drop frames during decoder initialization
        // Research shows SPS/PPS frames are essential for decoder startup
        // Frame skipping should only happen AFTER successful streaming has begun

        // Log performance stats every 30 seconds (time-based for accuracy)
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

        // Calculate optimal pool size based on resolution and research findings
        bufferPoolSize = calculateOptimalPoolSize(width, height);

        // Research-based buffer sizing per frame for automotive streaming
        // ByteBuffer.allocateDirect provides maximum efficiency per research
        int bufferSize = calculateOptimalFrameBufferSize(width, height);

        // Initialize buffers across different size buckets for optimal reuse
        int smallCount = bufferPoolSize / 3;
        int mediumCount = bufferPoolSize / 3;
        int largeCount = bufferPoolSize - smallCount - mediumCount;

        // Small buffers (64KB) - for headers and small frames
        for (int i = 0; i < smallCount; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(SMALL_BUFFER_THRESHOLD);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            smallBuffers.offer(buffer);
        }

        // Medium buffers (256KB) - for standard frames
        for (int i = 0; i < mediumCount; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(MEDIUM_BUFFER_THRESHOLD);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            mediumBuffers.offer(buffer);
        }

        // Large buffers (calculated size) - for high-quality frames
        for (int i = 0; i < largeCount; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            largeBuffers.offer(buffer);
        }

        poolInitialized = true;
        log("Resolution-adaptive memory pool initialized: " + bufferPoolSize + " buffers (" +
            smallCount + " small/" + mediumCount + " medium/" + largeCount + " large) for " + width + "x" + height);
    }

    private ByteBuffer getPooledBuffer(int minimumSize) {
        ByteBuffer buffer = null;

        // Select appropriate buffer bucket based on size requirement
        if (minimumSize <= SMALL_BUFFER_THRESHOLD) {
            buffer = smallBuffers.poll();
            if (buffer == null) {
                // Try next size up if small pool is empty
                buffer = mediumBuffers.poll();
            }
        } else if (minimumSize <= MEDIUM_BUFFER_THRESHOLD) {
            buffer = mediumBuffers.poll();
            if (buffer == null) {
                // Try large pool if medium is empty
                buffer = largeBuffers.poll();
            }
        } else {
            buffer = largeBuffers.poll();
        }

        // If no suitable buffer found in pools, allocate new one
        if (buffer == null || buffer.capacity() < minimumSize) {
            int newSize = Math.max(minimumSize, 128 * 1024);
            buffer = ByteBuffer.allocateDirect(newSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            log("[POOL_EXPAND] Allocated " + (newSize / 1024) + "KB direct buffer for size requirement: " + (minimumSize / 1024) + "KB");
        }

        buffer.clear();
        // Clear buffer contents for security before first use
        secureBufferClear(buffer);
        return buffer;
    }

    private void returnPooledBuffer(ByteBuffer buffer) {
        if (buffer == null) return;

        buffer.clear();
        // Securely clear buffer contents before returning to pool to prevent data leakage
        secureBufferClear(buffer);

        // Return buffer to appropriate size bucket
        int capacity = buffer.capacity();
        boolean returned = false;

        if (capacity <= SMALL_BUFFER_THRESHOLD && smallBuffers.size() < bufferPoolSize / 3) {
            smallBuffers.offer(buffer);
            returned = true;
        } else if (capacity <= MEDIUM_BUFFER_THRESHOLD && mediumBuffers.size() < bufferPoolSize / 3) {
            mediumBuffers.offer(buffer);
            returned = true;
        } else if (capacity > MEDIUM_BUFFER_THRESHOLD && largeBuffers.size() < bufferPoolSize / 3) {
            largeBuffers.offer(buffer);
            returned = true;
        }

        if (!returned) {
            // Pool bucket is full - log for monitoring
            String bucketType = capacity <= SMALL_BUFFER_THRESHOLD ? "small" :
                               capacity <= MEDIUM_BUFFER_THRESHOLD ? "medium" : "large";
            log("[POOL_FULL] " + bucketType + " buffer pool at capacity, discarding " + (capacity / 1024) + "KB buffer");
        }
    }

    /**
     * Securely clears ByteBuffer contents to prevent data leakage between sessions.
     * Uses efficient zero-filling for direct buffers according to Android security best practices.
     */
    private void secureBufferClear(ByteBuffer buffer) {
        if (buffer != null && buffer.isDirect()) {
            int position = buffer.position();
            int limit = buffer.limit();

            // Clear entire buffer capacity, not just current position/limit
            buffer.position(0);
            buffer.limit(buffer.capacity());

            // Zero-fill the buffer for security
            byte[] zeros = new byte[Math.min(8192, buffer.remaining())]; // 8KB chunks for efficiency
            while (buffer.hasRemaining()) {
                int toWrite = Math.min(zeros.length, buffer.remaining());
                buffer.put(zeros, 0, toWrite);
            }

            // Restore original position and limit
            buffer.position(position);
            buffer.limit(limit);
        }
    }


    ////////////////////////////////////////

    private MediaCodec.Callback createCallback() {
        return new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                // Thread-safe check using codecLock to prevent race with reset/stop
                synchronized (codecLock) {
                    // Double-check codec validity inside lock
                    if (codec != mCodec || mCodec == null || !running) {
                        log("[CALLBACK] onInputBufferAvailable ignored - codec=" +
                            (codec == mCodec ? "current" : "STALE") + ", running=" + running);
                        return;
                    }

                    // Directly feed buffer if data is available in ring buffer
                    // This eliminates race condition where feedCodec() runs before callbacks fire
                    synchronized (codecAvailableBufferIndexes) {
                        VideoDebugLogger.logCodecInputAvailable(index, codecAvailableBufferIndexes.size());
                        log("[CALLBACK] onInputBufferAvailable index=" + index +
                            ", ringBuffer=" + (ringBuffer.isEmpty() ? "empty" : "has data") +
                            ", savedIndices=" + codecAvailableBufferIndexes.size());

                        if (!ringBuffer.isEmpty()) {
                            // Data available - feed directly to codec
                            try {
                                ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
                                if (byteBuffer != null) {
                                    byteBuffer.put(ringBuffer.readPacket());
                                    int dataSize = byteBuffer.position();
                                    mCodec.queueInputBuffer(index, 0, dataSize, 0, 0);
                                    VideoDebugLogger.logCodecInputQueued(index, dataSize);
                                    log("[CALLBACK] Queued input buffer #" + index + " with " + dataSize + " bytes");

                                    // Track frames received since reset for keyframe detection
                                    framesReceivedSinceReset++;

                                    // Check if we need to request keyframe (receiving frames but not decoding)
                                    // Trigger on EITHER: frame count threshold OR time threshold (whichever comes first)
                                    if (pendingKeyframeRequest && framesDecodedSinceReset == 0) {
                                        long now = System.currentTimeMillis();
                                        long timeSinceReset = now - resetTimestamp;
                                        boolean frameThresholdMet = framesReceivedSinceReset >= KEYFRAME_REQUEST_THRESHOLD;
                                        boolean timeThresholdMet = timeSinceReset >= KEYFRAME_REQUEST_TIME_THRESHOLD_MS;

                                        if ((frameThresholdMet || timeThresholdMet) &&
                                            (now - lastKeyframeRequestTime) > KEYFRAME_REQUEST_COOLDOWN_MS) {
                                            // We've received frames but decoded none - need keyframe
                                            String trigger = frameThresholdMet ?
                                                "frame count (" + framesReceivedSinceReset + " frames)" :
                                                "time elapsed (" + timeSinceReset + "ms)";
                                            log("[KEYFRAME_DETECT] Triggered by " + trigger +
                                                " - decoded " + framesDecodedSinceReset + " - requesting keyframe");
                                            if (keyframeCallback != null) {
                                                try {
                                                    keyframeCallback.onKeyframeNeeded();
                                                    lastKeyframeRequestTime = now;
                                                } catch (Exception ke) {
                                                    log("[KEYFRAME_DETECT] Keyframe request failed: " + ke.getMessage());
                                                }
                                            }
                                            // Reset counters to avoid spamming, but keep detection active
                                            framesReceivedSinceReset = 0;
                                            resetTimestamp = now; // Reset time window for next attempt
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // Buffer might be invalid after codec reset, save index for later
                                log("[CALLBACK] Failed to queue buffer #" + index + ": " + e.getMessage());
                                codecAvailableBufferIndexes.add(index);
                            }
                        } else {
                            // No data yet - save buffer index for when data arrives
                            codecAvailableBufferIndexes.add(index);
                        }
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
                        if (pendingKeyframeRequest && framesDecodedSinceReset >= 3) {
                            log("[KEYFRAME_DETECT] Decoder producing output - disabling keyframe detection");
                            pendingKeyframeRequest = false;
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

                    log("[Media Codec] onError " + e.toString() + ", Recoverable: " + e.isRecoverable() + ", Transient: " + e.isTransient());
                    VideoDebugLogger.logCodecError(e.toString(), e.isRecoverable(), e.isTransient());

                    // Only reset on critical errors - let transient/recoverable errors pass
                    if (!e.isTransient() && !e.isRecoverable()) {
                        log("[Media Codec] Fatal error - will reset on next start attempt");
                        // Don't automatically reset - let user restart manually to avoid crash loops
                    } else {
                        log("[Media Codec] Transient/recoverable error - continuing operation");
                    }
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
}
