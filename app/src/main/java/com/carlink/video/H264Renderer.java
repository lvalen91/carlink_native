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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import java.util.concurrent.locks.LockSupport;

import com.carlink.util.AppExecutors;
import com.carlink.util.LogCallback;


public class H264Renderer {

    /** Callback to request keyframe (IDR) from adapter after codec reset. */
    public interface KeyframeRequestCallback {
        void onKeyframeNeeded();
    }

    /** Pre-allocated frame buffer for staging between USB and feeder threads.
     *  Ownership transfer via SPSC ring buffer with volatile indices provides happens-before guarantee. */
    private static final class StagedFrame {
        final byte[] data;
        int length;
        long timestamp;
        StagedFrame(int capacity) {
            this.data = new byte[capacity];
        }
    }

    private volatile MediaCodec mCodec;
    private final MediaCodec.Callback codecCallback;
    private MediaCodecInfo codecInfo;
    private final ConcurrentLinkedQueue<Integer> codecAvailableBufferIndexes = new ConcurrentLinkedQueue<>();
    private int width;
    private int height;
    private Surface surface;
    private volatile boolean running = false;
    private volatile java.util.Timer retryTimer;  // Stored for cancellation in stop()
    private final LogCallback logCallback;
    private KeyframeRequestCallback keyframeCallback;

    private final AppExecutors executors;
    private final String preferredDecoderName;

    // Essential monitoring only
    private final AtomicLong totalFramesDecoded = new AtomicLong(0);
    private final AtomicLong codecResetCount = new AtomicLong(0);
    private static final long PERF_LOG_INTERVAL_MS = 30000;
    private long lastPerfLogTime = 0;

    // Pipeline diagnostic counters (debug builds only)
    // These help identify WHERE the pipeline breaks when video freezes
    private final AtomicLong framesReceived = new AtomicLong(0);
    private final AtomicLong feedSuccesses = new AtomicLong(0);
    private volatile long lastInputCallbackTime = 0;
    private volatile long lastOutputCallbackTime = 0;

    // Silent failure tracking - helps diagnose zombie codec state
    private final AtomicLong nullBufferCount = new AtomicLong(0);
    private final AtomicLong feedExceptionCount = new AtomicLong(0);
    private volatile String lastFeedException = null;

    // Drop tracking with NAL type awareness
    // IDR drops are catastrophic (all subsequent P-frames corrupt until next IDR)
    // P-frame drops are tolerable (single frame glitch)
    private final AtomicLong totalDropCount = new AtomicLong(0);
    private final AtomicLong idrDropCount = new AtomicLong(0);
    private final AtomicLong pFrameDropCount = new AtomicLong(0);
    // Accumulative IDR drop counter (not reset by logStats — for total session tracking)
    private final AtomicLong sessionIdrDrops = new AtomicLong(0);

    // TODO [SELF_HEALING]: Auto-reset for frozen video pipeline.
    //
    // PROBLEM: Codec enters zombie state — alive by all observable metrics, internally dead.
    //   mCodec != null, running == true, surface.isValid() == true, but:
    //   - onInputBufferAvailable stops firing, OR
    //   - getInputBuffer() returns null for recycled indices, OR
    //   - onError callback never triggers (silent failure)
    //   Evidence: H264_PIPELINE logged Rx:1330 Dec:0 for 65+ seconds without error callback.
    //
    // DETECTION — ScheduledExecutorService, 1-second tick, 2 consecutive failures = 2s window.
    //   Reset when ALL true:
    //   1. running && surface != null && surface.isValid()
    //   2. framesReceived delta > 0 in window (USB delivering — rules out phone sleep/Siri)
    //   3. totalFramesDecoded delta == 0 in window (codec producing nothing)
    //   4. Cooldown elapsed (prevent reset loops)
    //   5. Grace period (3s) elapsed since last start()/reset()/resume()
    //
    //   Use snapshot-based deltas: currentCounter - previousSnapshot per tick. O(1), no buffer.
    //   Two consecutive ticks with received>0 && decoded==0 required to trigger.
    //   Dispatch reset on executors.mediaCodec1() (not timer thread) — same as onError handler.
    //
    // ACTION: reset() — full codec recreation (stop+release+create+configure+start+requestKeyframe).
    //   NOT flush(). Intel VPU (OMX.Intel.hw_vd.h264) does not reliably clear poisoned
    //   reference surfaces on flush — documented in 15_REV57_DECODER_POISONING_ANALYSIS.md.
    //   flush() requires start() afterward anyway in async mode, with CSD re-submission complexity.
    //   reset() is proven (2 successful recoveries in field test, ~250ms total blackout).
    //
    // COOLDOWN — stepped, never give up:
    //   Reset #1: 3s, #2: 5s, #3: 10s, #4+: 15s cap.
    //   Reset watchdogConsecutiveResets to 0 after 30s of healthy output (decoded > 0).
    //   No exponential backoff — frozen screen in a car is unacceptable at 32s+ intervals.
    //   Log severe warning if >5 resets in 60s (persistent hardware fault indicator).
    //
    // FALSE POSITIVE MITIGATION:
    //   - Siri/sleep: receivedInWindow == 0 → gate prevents trigger
    //   - Surface recreation: running becomes false during restart → condition #1 prevents
    //   - Post-start/reset: 3s grace period covers codec startup latency
    //   - USB burst patterns: 2s window smooths jitter (documented P99: 7ms, max: 30ms)
    //   - SPS/PPS changes: always bundled with IDR → decoded > 0
    //
    // THREAD SAFETY: Existing codecLock serializes lifecycle ops. feedDirect() try/catch
    //   handles races with concurrent reset (stale buffer index → IllegalStateException → caught).
    //   codecAvailableBufferIndexes.clear() in stop() handles stale indices from old codec instance.
    //
    // PRIOR ART: No commercial CarPlay app (AutoKit, Zlink, scrcpy, ExoPlayer) implements
    //   this specific received>0/decoded==0 watchdog. All rely on onError callbacks (which
    //   don't fire in silent zombie states) or manual user restart. This is novel but sound.
    //
    // PHASE 2 (NOT THIS TODO): Corrupted-but-outputting codec (decoded>0 but visually frozen/
    //   pixelated). Requires frame content analysis or decode-time anomaly detection. Separate concern.
    //
    // GM AAOS comparison: GM avoids this entirely by using CINEMO software decoder (not MediaCodec).
    //   Direct function-call chain with full internal state control. We use MediaCodec as a black box —
    //   the watchdog is the correct compensating mechanism for opaque hardware decoder state.

    // First-frame flag — survives logStats() counter resets
    private volatile boolean firstFrameLogged = false;

    // Sync gate — discard P-frames until first SPS/PPS+IDR arrives.
    // Prevents feeding undecodable frames if initial keyframe bundle is lost.
    private volatile boolean syncAcquired = false;

    // Monotonic frame counter for PTS
    private final AtomicLong frameCounter = new AtomicLong(0);

    // Lock for codec lifecycle operations (stop/reset/resume).
    // Prevents races between onError callback, reset(), and stop() running
    // on different threads (codec internal thread, executor, main thread).
    private final Object codecLock = new Object();

    // FIFO staging queue (SPSC ring): USB thread writes, feeder thread reads.
    // 4-slot power-of-2 ring (3 usable) absorbs USB frame bursts without overwrites.
    // 6 buffers: 1 write + up to 3 in queue + 1 feeder + 1 pool margin.
    private static final int STAGED_FRAME_CAPACITY = 512 * 1024;  // 512KB, covers 1080p I-frames
    private static final int STAGED_FRAME_COUNT = 6;               // write + queue(3) + feed + margin
    private static final int STAGING_QUEUE_SLOTS = 4;              // power-of-2, 3 usable slots

    private final StagedFrame[] stagingQueue = new StagedFrame[STAGING_QUEUE_SLOTS];
    private volatile int sqHead = 0;  // written by USB thread only
    private volatile int sqTail = 0;  // written by feeder thread only
    private final ConcurrentLinkedQueue<StagedFrame> framePool = new ConcurrentLinkedQueue<>();
    private StagedFrame writeFrame;                                // USB thread only
    private volatile Thread feederThread;
    private final AtomicLong stagingDropCount = new AtomicLong(0);
    private final AtomicLong oversizedDropCount = new AtomicLong(0);

    // Fix A: Reactive keyframe request after staging drops
    private volatile boolean stagingOverwriteDetected = false;
    private volatile long lastReactiveKeyframeTimeNs = 0;
    private static final long REACTIVE_KEYFRAME_COOLDOWN_NS = 500_000_000L;  // 500ms

    public H264Renderer(int width, int height, Surface surface, LogCallback logCallback,
                        AppExecutors executors, String preferredDecoderName) {
        this.width = width;
        this.height = height;
        this.surface = surface;
        this.logCallback = logCallback;
        this.executors = executors;
        this.preferredDecoderName = preferredDecoderName;

        codecCallback = createCallback();
    }

    public void setKeyframeRequestCallback(KeyframeRequestCallback callback) {
        this.keyframeCallback = callback;
    }

    private void log(String message) {
        logCallback.log("H264_RENDERER", message);
    }

    private void logPerf(String message) {
        logCallback.logPerf("VIDEO_PERF", message);
    }

    public void start() {
        if (running) return;

        running = true;
        totalFramesDecoded.set(0);
        frameCounter.set(0);
        firstFrameLogged = false;
        syncAcquired = false;

        log("start - " + width + "x" + height);

        try {
            initCodec(width, height, surface);
            mCodec.start();
            initStaging();
            log("Codec started");
        } catch (Exception e) {
            log("start error: " + e);
            stopStaging();

            // Release the codec created by initCodec() to prevent native MediaCodec leak.
            // Without this, each failed retry overwrites mCodec in initCodec() without
            // releasing the previous instance, exhausting the hardware codec pool (1-3
            // instances on Intel Atom). release() is safe from any codec state per Android docs.
            if (mCodec != null) {
                try { mCodec.release(); } catch (Exception re) { /* ignore */ }
                mCodec = null;
            }

            running = false;

            log("restarting in 5s");
            retryTimer = new java.util.Timer();
            retryTimer.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    start();
                }
            }, 5000);
        }
    }

    public void stop() {
        // Cancel any pending start() retry to prevent resurrection after stop().
        // CarlinkManager handles reconnection at a higher level — the renderer
        // should not autonomously restart after being explicitly stopped.
        if (retryTimer != null) {
            retryTimer.cancel();
            retryTimer = null;
        }

        if (!running) return;

        running = false;
        stopStaging();  // Join feeder BEFORE stopping codec

        synchronized (codecLock) {
            if (mCodec != null) {
                log("Codec stopped");
                try {
                    mCodec.stop();
                } catch (Exception e) {
                    log("stop error: " + e);
                } finally {
                    // Always release the codec even if stop() threw — prevents
                    // native MediaCodec leak which exhausts the system codec pool.
                    try {
                        mCodec.release();
                    } catch (Exception e) {
                        log("release error: " + e);
                    }
                }
                mCodec = null;
            }
        }

        codecAvailableBufferIndexes.clear();
        surface = null;
    }

    public void reset() {
        synchronized (codecLock) {
            long resetCount = codecResetCount.incrementAndGet();
            log("Reset #" + resetCount);

            Surface savedSurface = this.surface;
            stop();
            this.surface = savedSurface;
            start();
        }

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

        synchronized (codecLock) {
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
        }

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

        // Intel-specific: set max input size to disable Adaptive Playback (reduces latency)
        if (codecName != null && codecName.contains("Intel")) {
            try {
                mediaformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height);
                log("Intel optimization applied: KEY_MAX_INPUT_SIZE");
            } catch (Exception e) {
                // Ignore
            }
        }

        log("Init: " + codecName + " @ " + width + "x" + height);

        mCodec.setCallback(codecCallback);
        codecAvailableBufferIndexes.clear();
        mCodec.configure(mediaformat, surface, null, 0);
        log("Surface bound: valid=" + (surface != null && surface.isValid()));
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

    /** Initialize staging buffers and start the feeder thread. Call after mCodec.start(). */
    private void initStaging() {
        // Clear FIFO queue
        for (int i = 0; i < STAGING_QUEUE_SLOTS; i++) {
            stagingQueue[i] = null;
        }
        sqHead = 0;
        sqTail = 0;
        framePool.clear();
        stagingDropCount.set(0);
        oversizedDropCount.set(0);
        stagingOverwriteDetected = false;
        lastReactiveKeyframeTimeNs = 0;

        // Allocate 6 StagedFrames: 1 → writeFrame, 5 → framePool
        writeFrame = new StagedFrame(STAGED_FRAME_CAPACITY);
        for (int i = 1; i < STAGED_FRAME_COUNT; i++) {
            framePool.offer(new StagedFrame(STAGED_FRAME_CAPACITY));
        }

        Thread t = new Thread(this::feederLoop, "H264-Feeder");
        t.setDaemon(true);
        feederThread = t;
        t.start();
        logPerf("Feeder started");
    }

    /** Stop the feeder thread and release staging buffers. Call before mCodec.stop(). */
    private void stopStaging() {
        Thread t = feederThread;
        feederThread = null;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                logPerf("Feeder thread did not exit within 1s");
            }
        }
        // Clear FIFO queue
        for (int i = 0; i < STAGING_QUEUE_SLOTS; i++) {
            stagingQueue[i] = null;
        }
        sqHead = 0;
        sqTail = 0;
        framePool.clear();
        writeFrame = null;
    }

    /** Offer a frame to the SPSC staging queue. Called from USB thread only.
     *  @return true if enqueued, false if queue full (caller must handle drop). */
    private boolean stagingOffer(StagedFrame frame) {
        int next = (sqHead + 1) & (STAGING_QUEUE_SLOTS - 1);
        if (next == sqTail) return false;  // full
        stagingQueue[sqHead] = frame;
        sqHead = next;  // volatile write publishes
        return true;
    }

    /** Poll a frame from the SPSC staging queue. Called from feeder thread only.
     *  @return next frame, or null if queue empty. */
    private StagedFrame stagingPoll() {
        int t = sqTail;
        if (t == sqHead) return null;  // empty
        StagedFrame f = stagingQueue[t];
        stagingQueue[t] = null;
        sqTail = (t + 1) & (STAGING_QUEUE_SLOTS - 1);  // volatile write
        return f;
    }

    /** Feeder thread main loop — drains FIFO staging queue and feeds to codec. */
    private void feederLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        try {
            while (running) {
                StagedFrame frame = stagingPoll();
                if (frame != null) {
                    feedFrameToCodec(frame);
                    framePool.offer(frame);

                    // Fix A: After feeding, check if a staging drop occurred → request reactive keyframe
                    if (stagingOverwriteDetected) {
                        stagingOverwriteDetected = false;
                        long now = System.nanoTime();
                        if (now - lastReactiveKeyframeTimeNs > REACTIVE_KEYFRAME_COOLDOWN_NS) {
                            lastReactiveKeyframeTimeNs = now;
                            KeyframeRequestCallback cb = keyframeCallback;
                            if (cb != null) {
                                executors.mediaCodec1().execute(() -> {
                                    try {
                                        cb.onKeyframeNeeded();
                                        logPerf("Reactive keyframe request after staging drop");
                                    } catch (Exception e) {
                                        logPerf("Reactive request failed: " + e);
                                    }
                                });
                            }
                        }
                    }
                } else {
                    LockSupport.parkNanos(1_000_000L);  // 1ms — 0.5ms avg added latency
                }
            }
        } catch (Exception e) {
            logPerf("Feeder exception: " + e);
        }
        logPerf("Feeder stopped");
    }

    /** Feed a staged frame to the codec. Called only from feeder thread. */
    private void feedFrameToCodec(StagedFrame frame) {
        if (mCodec == null) return;

        // Gate: discard frames until first SPS/PPS+IDR sync point.
        // Adapter bundles SPS+PPS+IDR as one payload — getNalType() returns SPS (first NAL).
        if (!syncAcquired) {
            int nalType = getNalType(frame.data, 0, frame.length);
            if (nalType == NAL_SPS || nalType == NAL_IDR) {
                syncAcquired = true;
                log("[VIDEO] Sync acquired (" + nalTypeToString(nalType) + "), feeding to codec");
            } else {
                logPerf("Pre-sync drop: " + nalTypeToString(nalType) + " " + frame.length + "B");
                return;
            }
        }

        Integer index = codecAvailableBufferIndexes.poll();
        if (index == null) {
            // Codec busy → drop. Track what we're dropping.
            totalDropCount.incrementAndGet();
            int nalType = getNalType(frame.data, 0, frame.length);
            if (nalType == NAL_IDR) {
                long sessionTotal = sessionIdrDrops.incrementAndGet();
                idrDropCount.incrementAndGet();
                log("IDR dropped (" + frame.length + "B) session=" + sessionTotal);
            } else {
                pFrameDropCount.incrementAndGet();
            }
            return;
        }

        try {
            ByteBuffer inputBuffer = mCodec.getInputBuffer(index);
            if (inputBuffer == null) {
                long count = nullBufferCount.incrementAndGet();
                if (count == 1 || count % 100 == 0) {
                    logPerf("getInputBuffer null (count=" + count + ")");
                }
                codecAvailableBufferIndexes.offer(index);
                return;
            }
            inputBuffer.clear();
            inputBuffer.put(frame.data, 0, frame.length);
            mCodec.queueInputBuffer(index, 0, frame.length, frame.timestamp, 0);
            feedSuccesses.incrementAndGet();
        } catch (Exception e) {
            long count = feedExceptionCount.incrementAndGet();
            lastFeedException = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (count == 1 || count % 100 == 0) {
                logPerf("Feed exception (count=" + count + "): " + lastFeedException);
            }
            codecAvailableBufferIndexes.offer(index);
        }
    }

    // H.264 NAL unit types (ITU-T H.264 Table 7-1)
    private static final int NAL_SLICE = 1;       // Non-IDR slice (P/B frame)
    private static final int NAL_IDR = 5;         // IDR slice (keyframe)
    private static final int NAL_SEI = 6;         // Supplemental enhancement info
    private static final int NAL_SPS = 7;         // Sequence parameter set
    private static final int NAL_PPS = 8;         // Picture parameter set

    /**
     * Extract NAL unit type from H.264 Annex B bitstream.
     * Scans for start code (0x00000001 or 0x000001) and returns lower 5 bits of next byte.
     * Only scans first 32 bytes (start code is always at/near beginning).
     *
     * @return NAL unit type (1-31), or -1 if no valid start code found
     */
    private static int getNalType(byte[] data, int offset, int length) {
        int scanEnd = offset + Math.min(length, 32);
        for (int i = offset; i < scanEnd - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                // 4-byte start code: 0x00000001
                if (i < scanEnd - 4 && data[i + 2] == 0 && data[i + 3] == 1) {
                    return data[i + 4] & 0x1F;
                }
                // 3-byte start code: 0x000001
                if (data[i + 2] == 1) {
                    return data[i + 3] & 0x1F;
                }
            }
        }
        return -1;
    }

    private static String nalTypeToString(int nalType) {
        switch (nalType) {
            case NAL_SLICE: return "P/B";
            case NAL_IDR:   return "IDR";
            case NAL_SEI:   return "SEI";
            case NAL_SPS:   return "SPS";
            case NAL_PPS:   return "PPS";
            default:        return "NAL:" + nalType;
        }
    }

    /**
     * Stage H.264 data for codec feeding. GC-immune USB thread fast path.
     * Called from USB-ReadLoop thread. [FIFO_STAGING] implementation.
     *
     * USB → System.arraycopy → SPSC ring offer. No JNI, no codec calls.
     * Feeder thread handles all codec interaction on its own timeline.
     *
     * @return true if frame was staged, false if dropped (oversized, no buffer, or queue full)
     */
    public boolean feedDirect(byte[] data, int offset, int length) {
        if (!running) return false;
        framesReceived.incrementAndGet();
        logStats();

        // Guard: reject frames exceeding staging capacity (corrupted USB data)
        if (length > STAGED_FRAME_CAPACITY) {
            oversizedDropCount.incrementAndGet();
            logPerf("Oversized drop: " + length + "B > " + STAGED_FRAME_CAPACITY + "B");
            return false;
        }

        StagedFrame wf = writeFrame;
        if (wf == null) return false;  // Staging not initialized

        // The only real work: memcpy into pre-allocated buffer
        System.arraycopy(data, offset, wf.data, 0, length);
        wf.length = length;
        wf.timestamp = frameCounter.getAndIncrement();

        // FIFO enqueue — feeder thread drains in order
        if (stagingOffer(wf)) {
            // Frame enqueued — get a fresh buffer from pool for next write
            writeFrame = framePool.poll();
            // writeFrame may be null briefly if feeder hasn't returned buffers yet.
            // Next feedDirect() call will return false (null guard above). This is fine —
            // the feeder will return buffers to the pool within ~1ms.
        } else {
            // Queue full — drop incoming frame (preserves FIFO order of already-queued frames).
            // wf stays as writeFrame for reuse (data will be overwritten next call).
            stagingDropCount.incrementAndGet();
            stagingOverwriteDetected = true;
            int nalType = getNalType(wf.data, 0, wf.length);
            if (nalType == NAL_IDR) {
                long sessionTotal = sessionIdrDrops.incrementAndGet();
                idrDropCount.incrementAndGet();
                log("Staging full, IDR dropped (" + wf.length + "B) session=" + sessionTotal);
            }
            return false;
        }

        return true;
    }

    private void logStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPerfLogTime >= PERF_LOG_INTERVAL_MS) {
            // Session stats (always logged via H264_RENDERER tag)
            log("Decoded:" + totalFramesDecoded.get() + " Resets:" + codecResetCount.get() +
                    " IDR drops:" + sessionIdrDrops.get());

            // Pipeline diagnostic stats (gated by VIDEO_PERF tag)
            long rx = framesReceived.getAndSet(0);
            long dec = totalFramesDecoded.getAndSet(0);
            long successes = feedSuccesses.getAndSet(0);
            int inAvail = codecAvailableBufferIndexes.size();
            long lastInAge = lastInputCallbackTime > 0 ? currentTime - lastInputCallbackTime : -1;
            long lastOutAge = lastOutputCallbackTime > 0 ? currentTime - lastOutputCallbackTime : -1;
            boolean surfaceValid = surface != null && surface.isValid();

            long nullBufs = nullBufferCount.getAndSet(0);
            long exceptions = feedExceptionCount.getAndSet(0);

            long drops = totalDropCount.getAndSet(0);
            long idrDrops = idrDropCount.getAndSet(0);
            long pDrops = pFrameDropCount.getAndSet(0);

            StringBuilder sb = new StringBuilder();
            sb.append("Rx:").append(rx).append(" Dec:").append(dec)
              .append(" InAvail:").append(inAvail)
              .append(" Fed:").append(successes)
              .append(" Drop:").append(drops);

            if (drops > 0) {
                sb.append("[IDR:").append(idrDrops).append(" P:").append(pDrops).append("]");
            }

            sb.append(" LastIn:").append(lastInAge).append("ms LastOut:").append(lastOutAge).append("ms")
              .append(" run=").append(running).append(" codec=").append(mCodec != null)
              .append(" surface=").append(surfaceValid);

            long stageDrops = stagingDropCount.getAndSet(0);
            long oversized = oversizedDropCount.getAndSet(0);
            if (stageDrops > 0 || oversized > 0) {
                sb.append(" STAGE[drop:").append(stageDrops)
                  .append(" oversized:").append(oversized).append("]");
            }

            if (nullBufs > 0 || exceptions > 0) {
                sb.append(" FAIL[null:").append(nullBufs)
                  .append(" exc:").append(exceptions).append("]");
                if (lastFeedException != null && exceptions > 0) {
                    sb.append(" lastExc=").append(lastFeedException);
                }
            }

            logPerf(sb.toString());

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
                // No feedCodec() — USB thread feeds directly via feedDirect()
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                if (codec != mCodec || !running) return;
                lastOutputCallbackTime = System.currentTimeMillis();

                if (info.size > 0) {
                    totalFramesDecoded.incrementAndGet();
                    if (!firstFrameLogged) {
                        firstFrameLogged = true;
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
                logCallback.log("VIDEO_CODEC", "ERROR: " + e.getDiagnosticInfo() +
                        " recoverable=" + e.isRecoverable() + " transient=" + e.isTransient());
                executors.mediaCodec1().execute(() -> reset());
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                if (codec != mCodec) return;
                int w = format.getInteger("width");
                int h = format.getInteger("height");
                int color = format.containsKey("color-format") ? format.getInteger("color-format") : -1;
                log("Format: " + w + "x" + h + " color=" + color);
            }
        };
    }
}
