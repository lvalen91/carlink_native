# carlink_native
Carlink Alternative without Flutter/Dart

# Work In progress

## Multiple updates expected. Assume they will each break something.

I'm focusing on my gminfo Intel AAOS radio. Both for Video and Audio performance. Many things have been carried over from the Flutter Carlink that have not been fixed. 

## Change to your package name, version, and version code for PlayStore Upload.
section: defaultConfig InFile: /app/build.gradle.kts

Remember kids: (mostly me)
```
Projection streams are live UI state, not video playback.
Do not buffer, pace, preserve, or “play” frames.
Late frames must be dropped. Corruption must trigger reset.

CarPlay / Android Auto h264 is not media.
It is a real-time projection of UI state.
Correctness is defined by latency, not completeness.
Buffers create corruption. Queues create lies.

Video is a best-effort, disposable representation of UI state.
Audio is a continuous time signal that must never stall.
Video may drop. Audio may buffer. Neither may block the other
```

Video:
- Represents live UI state
- Late == invalid
- Drop aggressively
- Reset on corruption
- Never wait

Audio:
- Represents continuous time
- Late == fill
- Buffer aggressively
- Never stall
- Never block video

---

Current build: v70. See documents/revisions.txt for full changelog.

Known issue: coupled audio+video degradation under memory pressure.
USB thread shares time with codec interaction. Large keyframes block audio reads.
GC from MediaCodec DirectByteBuffer allocations every ~7s on 4-core Atom.
USB write contention from 4 competing writers (heartbeat, keyframe req, mic, commands).
LMK is platform-level, not app-caused. ZRAM disabled on 6GB systems by check_lowmem.sh threshold bug.
Memory crisis every ~20-30 min, 28+ process kills, cycle repeats.

Completed fixes:
- Single-slot video handoff (rev 67). USB thread does System.arraycopy to pre-allocated StagedFrame, dedicated H264-Feeder thread handles codec interaction. 4-slot SPSC lock-free queue, 6x512KB pre-allocated frames. Overflow drops oldest frame and requests keyframe. Audio and video pipelines fully decoupled.
- Hot-path allocation gating. Debug logs behind BuildConfig.DEBUG flags. Pre-allocated buffers for headers, chunks, staging frames, audio ring buffers.
- Dual-stream audio. Separate AudioTrack and ring buffer for media (500ms) and navigation (200ms). Lock-free SPSC ring buffers, WRITE_NON_BLOCKING, THREAD_PRIORITY_URGENT_AUDIO playback thread.
- Cluster navigation via Car App Library Templates Host. CarlinkClusterService + CarlinkClusterSession for instrument cluster display. NavigationStateManager, ManeuverMapper, DistanceFormatter for turn-by-turn. Multi-step trip support — adapter sends two maneuvers per burst, both are captured and pushed to the cluster via Trip.addStep().
- Media browser service. CarlinkMediaBrowserService registers as AAOS media source. MediaSessionManager for playback state.
- CarAppActivity declared in manifest solely to trigger Templates Host binding. Not a launcher, not visible.
- PlatformDetector for Intel/GM AAOS hardware detection, codec selection, audio config.
- File logging system with LogPreset, FileLogManager, FileExportService.

Failed fix (reverted):
- getInputBuffers() for per-frame allocation elimination. Throws IllegalStateException in async callback mode. Lazy per-index caching exposed Surface timing race on adb install -r. Core premise about NewDirectByteBuffer on every call was unverified AI assertion.

Still pending:
- USB write serialization. Route all writes through single queue instead of 4 threads calling bulkTransfer directly.
- UsbRequest async I/O investigation. queue()+requestWait() for pipelined USB transfers.

Platform constraints (third-party vs system app):
GM CarPlay runs native C++ (CINEMO/NME), SCHED_FIFO, hypervisor-level decode, direct HAL audio.
We get CFS scheduling, MediaCodec Java/JNI, full Android audio stack, GC pauses.
Best we can do: THREAD_PRIORITY_URGENT_AUDIO, KEY_LOW_LATENCY, KEY_PRIORITY=0, pre-allocated buffers, lock-free rings, aggressive frame dropping, foreground service.

Platform details (GM Y181, gminfo 3.7, GHS INTEGRITY hypervisor):
SCHED_FIFO 50 AVB threads on cores 1-3 unconditionally preempt app threads.
Background demotion = core 0 only, 40ms timer slack, 1/5 I/O bandwidth. Fatal for streaming.
6GB RAM, ~3.5GB available to Android after GHS reserves. No ZRAM = LMK only relief.
10W TDP passive cooling, boost drops from 2.4GHz toward 800MHz under sustained load.
HW decode via OMX.Intel.hw_vd.h264 at ~8-9% capacity for 1080p@30. SW fallback unusable (11-13fps).
Audio path: AudioTrack > AudioFlinger > HAL > PulseAudio > SmartX IAS > AVB TX > Dirana3 DSP. ~43-48ms latency.
Native heap grows ~1.2MB per codec reset (Intel VPU driver leak, negligible vs 3.9GB page cache).

ZRAM: platform designed for 1GB compressed swap but check_lowmem.sh only enables it on <=2GB devices. Requires root to activate manually. Without it, anonymous pages are unpageable and LMK must kill processes to reclaim memory.
