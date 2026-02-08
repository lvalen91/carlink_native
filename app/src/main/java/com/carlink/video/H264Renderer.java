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
import com.carlink.util.VideoDebugLogger;


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

    // Monotonic frame counter for PTS
    private final AtomicLong frameCounter = new AtomicLong(0);

    // Lock for codec lifecycle operations (stop/reset/resume).
    // Prevents races between onError callback, reset(), and stop() running
    // on different threads (codec internal thread, executor, main thread).
    private final Object codecLock = new Object();

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
        firstFrameLogged = false;

        log("start - " + width + "x" + height);

        try {
            initCodec(width, height, surface);
            mCodec.start();
            VideoDebugLogger.logCodecStarted();
            log("[VIDEO] codec started");
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

        synchronized (codecLock) {
            if (mCodec != null) {
                VideoDebugLogger.logCodecStopped();
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
            VideoDebugLogger.logCodecReset(resetCount);
            log("[VIDEO] reset - count: " + resetCount);

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

        VideoDebugLogger.logCodecInit(codecName, width, height);

        mCodec.setCallback(codecCallback);
        codecAvailableBufferIndexes.clear();
        mCodec.configure(mediaformat, surface, null, 0);
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
     * Feed H.264 data directly to codec. No buffer, no queue.
     * Called from USB-ReadLoop thread. [DIRECT_HANDOFF] implementation.
     *
     * USB → MediaCodec directly, drop if codec busy (GM AAOS approach).
     * Dropping is the goal, not a side effect. Codec busy = frame is already late.
     *
     * @return true if frame was accepted, false if dropped (codec busy = correct)
     */
    public boolean feedDirect(byte[] data, int offset, int length) {
        if (!running || mCodec == null) return false;
        framesReceived.incrementAndGet();
        logStats();

        Integer index = codecAvailableBufferIndexes.poll();
        if (index == null) {
            // Codec busy → drop. Track what we're dropping.
            totalDropCount.incrementAndGet();
            int nalType = getNalType(data, offset, length);
            if (nalType == NAL_IDR) {
                long idrDrops = idrDropCount.incrementAndGet();
                long sessionTotal = sessionIdrDrops.incrementAndGet();
                // Always log IDR drops — losing a keyframe corrupts all frames until next IDR
                VideoDebugLogger.logIdrDrop(length, sessionTotal);
                debugLog("DROP IDR (keyframe) size=" + length + " idrDrops=" + sessionTotal);
                log("[VIDEO] WARNING: Dropped IDR keyframe (" + length + "B) — expect pixelation until next keyframe. Session IDR drops: " + sessionTotal);
            } else {
                pFrameDropCount.incrementAndGet();
            }
            return false;
        }

        try {
            ByteBuffer inputBuffer = mCodec.getInputBuffer(index);
            if (inputBuffer == null) {
                long count = nullBufferCount.incrementAndGet();
                if (count == 1 || count % 100 == 0) {
                    debugLog("feedDirect: getInputBuffer null (count=" + count + ")");
                }
                codecAvailableBufferIndexes.offer(index);
                return false;
            }
            inputBuffer.clear();
            inputBuffer.put(data, offset, length);
            long pts = frameCounter.getAndIncrement();
            mCodec.queueInputBuffer(index, 0, length, pts, 0);
            feedSuccesses.incrementAndGet();
            return true;
        } catch (Exception e) {
            long count = feedExceptionCount.incrementAndGet();
            lastFeedException = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (count == 1 || count % 100 == 0) {
                debugLog("feedDirect exception (count=" + count + "): " + lastFeedException);
            }
            codecAvailableBufferIndexes.offer(index);
            return false;
        }
    }

    private void logStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPerfLogTime >= PERF_LOG_INTERVAL_MS) {
            // Standard log (respects app log level)
            log("[STATS] Decoded: " + totalFramesDecoded.get() + ", Resets: " + codecResetCount.get() +
                    ", IDR drops(session): " + sessionIdrDrops.get());

            // Pipeline diagnostic log (debug builds only, always outputs)
            // Format: Rx=received Dec=decoded InAvail=input_buffers Fed=successes
            //         LastIn/LastOut=ms since last callback, run/codec/surface=component states
            long rx = framesReceived.getAndSet(0);
            long dec = totalFramesDecoded.getAndSet(0);
            long successes = feedSuccesses.getAndSet(0);
            int inAvail = codecAvailableBufferIndexes.size();
            long lastInAge = lastInputCallbackTime > 0 ? currentTime - lastInputCallbackTime : -1;
            long lastOutAge = lastOutputCallbackTime > 0 ? currentTime - lastOutputCallbackTime : -1;
            boolean surfaceValid = surface != null && surface.isValid();

            // Silent failure counters (reset each interval)
            long nullBufs = nullBufferCount.getAndSet(0);
            long exceptions = feedExceptionCount.getAndSet(0);

            // Drop counters (reset each interval — session totals tracked separately)
            long drops = totalDropCount.getAndSet(0);
            long idrDrops = idrDropCount.getAndSet(0);
            long pDrops = pFrameDropCount.getAndSet(0);

            StringBuilder sb = new StringBuilder();
            sb.append("Rx:").append(rx).append(" Dec:").append(dec)
              .append(" InAvail:").append(inAvail)
              .append(" Fed:").append(successes)
              .append(" Drop:").append(drops);

            // Always show IDR drops prominently (even if 0 in this window)
            if (drops > 0) {
                sb.append("[IDR:").append(idrDrops).append(" P:").append(pDrops).append("]");
            }

            sb.append(" LastIn:").append(lastInAge).append("ms LastOut:").append(lastOutAge).append("ms")
              .append(" run=").append(running).append(" codec=").append(mCodec != null)
              .append(" surface=").append(surfaceValid);

            // Only append failure info if there were failures (keeps log clean when healthy)
            if (nullBufs > 0 || exceptions > 0) {
                sb.append(" FAIL[null:").append(nullBufs)
                  .append(" exc:").append(exceptions).append("]");
                if (lastFeedException != null && exceptions > 0) {
                    sb.append(" lastExc=").append(lastFeedException);
                }
            }

            debugLog(sb.toString());
            VideoDebugLogger.logDropStats(drops, idrDrops, pDrops, successes);

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
                VideoDebugLogger.logCodecError(e.getDiagnosticInfo(), e.isRecoverable(), e.isTransient());
                log("[Media Codec] onError: " + e.getDiagnosticInfo());
                executors.mediaCodec1().execute(() -> reset());
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                if (codec != mCodec) return;
                int w = format.getInteger("width");
                int h = format.getInteger("height");
                int color = format.containsKey("color-format") ? format.getInteger("color-format") : -1;
                VideoDebugLogger.logCodecFormatChanged(w, h, color);
                log("[Media Codec] format changed: " + w + "x" + h);
            }
        };
    }
}
