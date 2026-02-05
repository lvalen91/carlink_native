package com.carlink.video;

/**
 * Hardware-accelerated H.264 decoder using MediaCodec async mode.
 *
 * Decodes video from CPC200-CCPA adapter and renders to SurfaceView.
 * CarPlay/Android Auto streams are live UI - prioritize immediacy over fidelity.
 */
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.view.Surface;

import androidx.annotation.NonNull;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.carlink.BuildConfig;
import com.carlink.util.AppExecutors;
import com.carlink.util.LogCallback;


public class H264Renderer {

    /** Callback to request keyframe (IDR) from adapter after codec reset. */
    public interface KeyframeRequestCallback {
        void onKeyframeNeeded();
    }

    private volatile MediaCodec mCodec;
    private final MediaCodec.Callback codecCallback;
    private MediaCodecInfo codecInfo;
    private final ConcurrentLinkedQueue<Integer> codecAvailableBufferIndexes = new ConcurrentLinkedQueue<>();
    private int width;
    private int height;
    private Surface surface;
    private volatile boolean running = false;
    private final LogCallback logCallback;
    private KeyframeRequestCallback keyframeCallback;

    private final AppExecutors executors;
    private final PacketRingByteBuffer ringBuffer;
    private final String preferredDecoderName;

    // Essential monitoring only
    private final AtomicLong totalFramesDecoded = new AtomicLong(0);
    private final AtomicLong codecResetCount = new AtomicLong(0);
    private static final long PERF_LOG_INTERVAL_MS = 30000;
    private long lastPerfLogTime = 0;

    // Pipeline diagnostic counters (debug builds only)
    // These help identify WHERE the pipeline breaks when video freezes
    private final AtomicLong framesReceived = new AtomicLong(0);
    private final AtomicLong feedAttempts = new AtomicLong(0);
    private final AtomicLong feedSuccesses = new AtomicLong(0);
    private volatile long lastInputCallbackTime = 0;
    private volatile long lastOutputCallbackTime = 0;

    // TODO [SELF_HEALING]: Auto-reset for frozen video pipeline.
    //
    // PROBLEM: Codec stuck. Frames arriving, nothing rendering. Frozen = corruption.
    // SOLUTION: Detect stuck state, reset immediately. Don't try to recover - reset.
    //
    // "Corruption must trigger reset." - Not flush, not retry, not recover. Reset.
    //
    // DETECTION - Reset when ALL true:
    //   1. running && surface valid (should be rendering)
    //   2. framesReceived > 0 in window (USB delivering - not phone sleep/Siri)
    //   3. framesDecoded == 0 in window (codec producing nothing)
    //   4. Cooldown elapsed (prevent reset loops)
    //
    // ACTION: reset() - Full codec recreation. It works. Keep it simple.
    //
    // Note: GM AAOS uses flush() but that's "trying to recover broken state".
    // Our philosophy: broken = reset. flush() is optional optimization later,
    // not a recovery strategy. Test flush() only AFTER reset-based watchdog is proven stable.

    // Monotonic frame counter for PTS
    private final AtomicLong frameCounter = new AtomicLong(0);

    // Buffer overflow threshold
    // TODO [LIVE_UI_OPTIMIZATION]: Reduce buffer. Buffers are lies.
    //
    // "Buffers create corruption. Queues create lies."
    // 10 frames = 166ms of stale UI state. User already interacted past it.
    //
    // GM AAOS: NO buffer. Direct handoff. Frame drops are CORRECT - late == invalid.
    // Target: 1-2 frames max (thread handoff only), or 0 (direct to codec).
    // Test: 10 → 5 → 3 → 1. Drops will increase. That's correct behavior.
    private static final int MAX_BUFFER_PACKETS = 10;

    public H264Renderer(int width, int height, Surface surface, LogCallback logCallback,
                        AppExecutors executors, String preferredDecoderName) {
        this.width = width;
        this.height = height;
        this.surface = surface;
        this.logCallback = logCallback;
        this.executors = executors;
        this.preferredDecoderName = preferredDecoderName;

        ringBuffer = new PacketRingByteBuffer(0);
        codecCallback = createCallback();
    }

    public void setKeyframeRequestCallback(KeyframeRequestCallback callback) {
        this.keyframeCallback = callback;
    }

    private void log(String message) {
        logCallback.log("[H264_RENDERER] " + message);
    }

    /** Debug log - always outputs in debug builds, bypasses app log level filter.
     *  Use for pipeline diagnostics that must appear regardless of user log settings. */
    private void debugLog(String message) {
        if (BuildConfig.DEBUG) {
            Log.d("H264_PIPELINE", message);
        }
    }

    public void start() {
        if (running) return;

        running = true;
        totalFramesDecoded.set(0);
        frameCounter.set(0);

        // Clear any stale data - new codec needs fresh IDR frame
        ringBuffer.reset();

        log("start - " + width + "x" + height);

        try {
            initCodec(width, height, surface);
            mCodec.start();
            log("[VIDEO] codec started");

            // Feed any buffered data immediately
            feedCodec();
        } catch (Exception e) {
            log("start error: " + e);
            running = false;

            log("restarting in 5s");
            new java.util.Timer().schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    start();
                }
            }, 5000);
        }
    }

    public void stop() {
        if (!running) return;

        running = false;

        if (mCodec != null) {
            try {
                mCodec.stop();
                mCodec.release();
            } catch (Exception e) {
                log("stop error: " + e);
            }
            mCodec = null;
        }

        codecAvailableBufferIndexes.clear();
        surface = null;
    }

    public void reset() {
        long resetCount = codecResetCount.incrementAndGet();
        log("[VIDEO] reset - count: " + resetCount);

        Surface savedSurface = this.surface;
        stop();
        this.surface = savedSurface;
        start();

        if (keyframeCallback != null) {
            try {
                keyframeCallback.onKeyframeNeeded();
            } catch (Exception e) {
                log("[VIDEO_ERROR] Keyframe request failed: " + e);
            }
        }
    }

    /** Resume with new surface after returning from background. */
    public void resume(Surface newSurface) {
        log("[LIFECYCLE] resume()");

        if (newSurface == null || !newSurface.isValid()) {
            log("[LIFECYCLE] Invalid surface");
            return;
        }

        // If codec is running, just swap the surface without restart
        if (running && mCodec != null) {
            try {
                mCodec.setOutputSurface(newSurface);
                this.surface = newSurface;
                log("[LIFECYCLE] Surface swapped without restart");
                return;
            } catch (Exception e) {
                log("[LIFECYCLE] setOutputSurface failed, doing full restart: " + e.getMessage());
            }
        }

        // Full restart needed (codec not running or setOutputSurface failed)
        stop();
        this.surface = newSurface;
        start();

        if (keyframeCallback != null) {
            try {
                keyframeCallback.onKeyframeNeeded();
            } catch (Exception e) {
                log("[VIDEO_ERROR] Keyframe request failed: " + e);
            }
        }
    }

    private void initCodec(int width, int height, Surface surface) throws IOException {
        log("init codec: " + width + "x" + height);

        MediaCodec codec = null;
        String codecName = null;
        MediaCodecInfo selectedCodecInfo = null;

        // Try preferred decoder (Intel platforms)
        if (preferredDecoderName != null && !preferredDecoderName.isEmpty()) {
            try {
                codec = MediaCodec.createByCodecName(preferredDecoderName);
                codecName = preferredDecoderName;
                selectedCodecInfo = findCodecInfo(preferredDecoderName);
                log("Using decoder: " + codecName);
            } catch (IOException e) {
                log("Preferred decoder unavailable: " + e.getMessage());
            }
        }

        // Fallback to generic decoder
        if (codec == null) {
            codec = MediaCodec.createDecoderByType("video/avc");
            codecName = codec.getName();
            selectedCodecInfo = findCodecInfo(codecName);
            log("Using decoder: " + codecName);
        }

        mCodec = codec;
        codecInfo = selectedCodecInfo;

        MediaFormat mediaformat = MediaFormat.createVideoFormat("video/avc", width, height);

        // Low latency mode if supported
        if (codecInfo != null) {
            try {
                CodecCapabilities caps = codecInfo.getCapabilitiesForType("video/avc");
                if (caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)) {
                    mediaformat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    log("Low latency: enabled");
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        // Realtime priority
        try {
            mediaformat.setInteger(MediaFormat.KEY_PRIORITY, 0);
        } catch (Exception e) {
            // Ignore
        }

        // Intel-specific optimizations
        if (codecName != null && codecName.contains("Intel")) {
            try {
                mediaformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height);
                mediaformat.setInteger("max-concurrent-instances", 1);
                log("Intel optimizations applied");
            } catch (Exception e) {
                // Ignore
            }
        }

        mCodec.setCallback(codecCallback);
        codecAvailableBufferIndexes.clear();
        mCodec.configure(mediaformat, surface, null, 0);
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

    private boolean fillFirstAvailableCodecBuffer() {
        feedAttempts.incrementAndGet();
        if (!running || mCodec == null) return false;
        if (ringBuffer.isEmpty()) return false;

        Integer index = codecAvailableBufferIndexes.poll();
        if (index == null) return false;

        try {
            ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
            if (byteBuffer == null) {
                codecAvailableBufferIndexes.offer(index);
                return false;
            }

            int bytesWritten = ringBuffer.readPacketInto(byteBuffer);
            if (bytesWritten == 0) {
                codecAvailableBufferIndexes.offer(index);
                return false;
            }

            long pts = frameCounter.getAndIncrement();
            mCodec.queueInputBuffer(index, 0, bytesWritten, pts, 0);
            feedSuccesses.incrementAndGet();
            return true;
        } catch (Exception e) {
            codecAvailableBufferIndexes.offer(index);
            return false;
        }
    }

    private void feedCodec() {
        if (!running) return;
        if (codecAvailableBufferIndexes.isEmpty()) return;

        executors.mediaCodec1().execute(() -> {
            if (!running) return;
            try {
                while (fillFirstAvailableCodecBuffer()) {
                    // Continue filling
                }
            } catch (Exception e) {
                log("[Media Codec] feed error: " + e);
            }
        });
    }

    /** Process video data with direct write callback (zero-copy path). */
    // TODO [DIRECT_HANDOFF]: Bypass buffer. Feed codec directly or drop.
    //
    // "Late frames must be dropped." - Don't queue them, don't preserve them.
    //
    // Current: USB → ringBuffer → feedCodec() → MediaCodec (queues stale frames)
    // Correct: USB → MediaCodec directly, drop if codec busy (GM AAOS approach)
    //
    // Dropping is the goal, not a side effect. Codec busy = frame is already late.
    // Priority: [SELF_HEALING] first (fix freezes), then this (reduce latency).
    public void processData(int length, int skipBytes, PacketRingByteBuffer.DirectWriteCallback callback) {
        // NOTE: Current behavior buffers even if codec not ready. This preserves IDR frames
        // but contradicts "late == invalid, drop aggressively" philosophy.
        // When [DIRECT_HANDOFF] is implemented, change to: feed codec or drop immediately.
        framesReceived.incrementAndGet();
        logStats();

        if (ringBuffer.availablePacketsToRead() > MAX_BUFFER_PACKETS) {
            return;
        }

        ringBuffer.directWriteToBuffer(length, skipBytes, callback);

        // Only feed codec if running
        if (running) {
            feedCodec();
        }
    }

    /** Process video data from byte array (fallback path). */
    public void processData(byte[] data) {
        if (data == null || data.length == 0) return;

        framesReceived.incrementAndGet();
        logStats();

        if (ringBuffer.availablePacketsToRead() > MAX_BUFFER_PACKETS) {
            return;
        }

        ringBuffer.directWriteToBuffer(data.length, 0, (buffer, offset) -> {
            System.arraycopy(data, 0, buffer, offset, data.length);
        });

        // Only feed codec if running
        if (running) {
            feedCodec();
        }
    }

    private void logStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPerfLogTime >= PERF_LOG_INTERVAL_MS) {
            // Standard log (respects app log level)
            log("[STATS] Decoded: " + totalFramesDecoded.get() + ", Resets: " + codecResetCount.get());

            // Pipeline diagnostic log (debug builds only, always outputs)
            // Format: Rx=received Dec=decoded Buf=buffered InAvail=input_buffers Feed=attempts/successes
            //         LastIn/LastOut=ms since last callback, run/codec/surface=component states
            long rx = framesReceived.getAndSet(0);
            long dec = totalFramesDecoded.getAndSet(0);
            long attempts = feedAttempts.getAndSet(0);
            long successes = feedSuccesses.getAndSet(0);
            int bufCount = ringBuffer.availablePacketsToRead();
            int inAvail = codecAvailableBufferIndexes.size();
            long lastInAge = lastInputCallbackTime > 0 ? currentTime - lastInputCallbackTime : -1;
            long lastOutAge = lastOutputCallbackTime > 0 ? currentTime - lastOutputCallbackTime : -1;
            boolean surfaceValid = surface != null && surface.isValid();

            debugLog("Rx:" + rx + " Dec:" + dec + " Buf:" + bufCount + " InAvail:" + inAvail +
                    " Feed:" + attempts + "/" + successes +
                    " LastIn:" + lastInAge + "ms LastOut:" + lastOutAge + "ms" +
                    " run=" + running + " codec=" + (mCodec != null) + " surface=" + surfaceValid);

            lastPerfLogTime = currentTime;
        }
    }

    private MediaCodec.Callback createCallback() {
        return new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                if (codec != mCodec) return;
                lastInputCallbackTime = System.currentTimeMillis();
                codecAvailableBufferIndexes.offer(index);

                // Try to feed buffered data when input buffer becomes available
                if (running && !ringBuffer.isEmpty()) {
                    feedCodec();
                }
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                if (codec != mCodec || !running) return;
                lastOutputCallbackTime = System.currentTimeMillis();

                if (info.size > 0) {
                    long count = totalFramesDecoded.incrementAndGet();
                    if (count == 1) {
                        log("[VIDEO] First frame decoded");
                    }
                }

                try {
                    mCodec.releaseOutputBuffer(index, info.size != 0);
                } catch (Exception e) {
                    // Ignore
                }
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                if (codec != mCodec) return;
                log("[Media Codec] onError: " + e.getDiagnosticInfo());
                executors.mediaCodec1().execute(() -> reset());
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                if (codec != mCodec) return;
                log("[Media Codec] format changed: " + format.getInteger("width") + "x" + format.getInteger("height"));
            }
        };
    }
}
