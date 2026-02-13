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

## Known Issue: Coupled Audio+Video Degradation (v64+ Direct Handoff)

### Symptom
After several minutes of operation, both audio and video degrade simultaneously. Video freezes/jumps and audio cuts out at the same time. Previous versions (pre-v57) had video-only decoder poisoning while audio remained unaffected.

### Root Cause
Rev 64 introduced **direct handoff** — the USB read thread calls `feedDirect()` → `MediaCodec.queueInputBuffer()` directly. This eliminated the `PacketRingByteBuffer` that previously decoupled the USB read thread from codec timing.

The old ring buffer wasn't just a stability buffer — it was an **isolation layer**. Writing to a pre-allocated byte array completes in microseconds regardless of GC or codec state. Now `queueInputBuffer()` (a JNI call into the codec HAL) runs on the USB thread, making both pipelines share that thread's time budget:

```
OLD (v31-v63): USB Thread → memcpy to ring buffer (microseconds, GC-immune) → read next packet
               Codec Thread → queueInputBuffer() (variable, may be slow)
               Audio: unaffected by codec state

v64-v66:       USB Thread → feedDirect() → queueInputBuffer() (variable, GC-sensitive) → read next packet
               Audio: blocked while USB thread is in codec interaction

FIXED (v67+):  USB Thread → feedDirect() → System.arraycopy + AtomicReference.getAndSet() (~50μs) → read next packet
               H264-Feeder → pendingFrame.getAndSet(null) → queueInputBuffer() (GC only affects video)
               Audio: unaffected by codec state (isolation restored)
```

### Contributing Factors

**1. `getInputBuffer(index)` allocates on every call**
AOSP JNI source (`android_media_MediaCodec.cpp`) shows `getInputBuffer()` calls `env->NewDirectByteBuffer()` each invocation — no caching. At 30fps, ~210 DirectByteBuffer allocations accumulate per GC cycle. Each requires two GC cycles to clean (PhantomReference). This is the primary NativeAlloc GC trigger (~every 7 seconds, 100-600ms total time on the 4-core Atom).

**2. GC competes with USB thread for CPU**
During GC (consuming one core on a 4-core Atom), `queueInputBuffer()` JNI calls take longer due to CPU contention. Log evidence shows GC total times spike from ~130ms to 311-628ms during system memory pressure, directly correlating with video frame drop bursts and AVB scheduling violations.

**3. Large video frames block audio reads**
A 100KB keyframe requires ~7 sequential 16KB `bulkTransfer()` reads. During these reads, no audio can be read from USB. Combined with codec interaction time, the audio ring buffer's 500ms margin can be exhausted.

**4. USB write contention from four competing writers**
Heartbeat (2s), keyframe request (2s), microphone capture (20ms during Siri/calls), and one-off commands all call `bulkTransfer()` on the same `UsbDeviceConnection`, competing with reads.

### Log Evidence (Session 2026-02-12, 05:30-06:24)

| Metric | Observation |
|--------|-------------|
| Audio USB delivery | Rock-steady ~485 packets/min entire session |
| Video throughput | 57fps → 23fps progressive decline, burst drops of 35-69 frames |
| Audio underruns | 55 (startup only in first file), 228 (second capture, first 11 min) |
| GC frequency | Every ~7 seconds, 266 events across 50-minute session |
| GC during degradation (06:14) | 311ms, 417ms, 389ms, 310ms, **628ms**, 407ms, 534ms |
| AVB TX worker violation | 18.4ms sleep (9.2x over 2ms deadline) at 06:14:19 |
| System memory pressure | LMK killed 5+ processes at 06:13:00 |

---

## Implemented Fixes

### Fix 1: Single-Slot Video Handoff (DONE — v67)
Restored pipeline isolation without reintroducing buffering latency. `feedDirect()` on the USB thread now does only `System.arraycopy` + `AtomicReference.getAndSet()` — no JNI, no codec calls. A dedicated `H264-Feeder` thread handles all MediaCodec interaction.

```
USB Thread:     read → System.arraycopy to StagedFrame → AtomicReference.getAndSet() → read next  (~50μs, GC-immune)
H264-Feeder:    pendingFrame.getAndSet(null) → getInputBuffer → put → queueInputBuffer  (GC stalls only affect video)
```

- 3 pre-allocated `StagedFrame` byte arrays (512KB each, ~1.5MB total) — write + pending + feed
- `AtomicReference` ownership transfer (happens-before guarantee per JMM)
- If slot still full when next frame arrives → overwrite (drop older frame), reuse buffer
- Added latency: 0-1ms (feeder parks 1ms when idle)
- Philosophy-compliant: drop semantics preserved, "neither may block the other" restored
- File: `H264Renderer.java` — single file change, `feedDirect()` signature unchanged

---

## Planned Fixes

### Fix 2: Use `getInputBuffers()` to Eliminate Per-Frame Allocation (Priority: HIGH)
Switch from `getInputBuffer(index)` to the deprecated `getInputBuffers()` array API. Pre-allocates all ByteBuffer wrappers once at codec creation, eliminating ~210 DirectByteBuffer allocations per GC cycle. The "disabled optimization" tradeoff is insignificant compared to eliminating the NativeAlloc GC trigger on a 4-core Atom.

### Fix 3: Reduce Hot-Path Allocations (Priority: MEDIUM)
Audit and eliminate allocations on the USB read thread:
- Audio debug logging (`[AUDIO_USB] Packet #N...`) creates string objects every 4th packet (~120 strings/sec) — gate behind debug flag
- Lambda captures in callbacks — replace with pre-allocated Runnable instances
- Any `ByteBuffer.wrap()` or `.slice()` calls in the read path

### Fix 4: USB Write Serialization (Priority: MEDIUM)
Route all USB writes through a single write queue instead of 4 threads calling `bulkTransfer()` directly. Eliminates write contention that can delay USB reads. Writes are fire-and-forget control messages — a few ms of queuing delay is irrelevant.

### Fix 5: Investigate `UsbRequest` for Async I/O (Priority: LOW)
Android's `UsbRequest.queue()` + `requestWait()` provides async USB I/O that can pipeline transfers, potentially allowing audio reads to interleave with in-flight video reads rather than waiting for sequential `bulkTransfer()` completion.

---

## Platform Constraints (Third-Party vs System App)

GM's own CarPlay implementation (CINEMO/NME framework) operates at a fundamentally different layer. The following capabilities are **not available** to third-party apps and cannot be replicated:

| Capability | GM System App | Third-Party App | Impact |
|------------|--------------|-----------------|--------|
| **SCHED_FIFO scheduling** | `SYS_NICE` capability via audioserver.rc | `THREAD_PRIORITY_URGENT_AUDIO` max | GM gets hard real-time; we get best-effort priority |
| **Native-only media path** | `libNmeVideo.so`, `libNmeAudio.so` (C++) | Must use MediaCodec Java/JNI API | GM has zero GC; we must manage around GC pauses |
| **Hypervisor decode** | Video decoded in GHS tasks below Android | MediaCodec in Android VM | GM has deterministic timing; we compete with Android scheduler |
| **Direct HAL access** | IAS/SmartX audio bus routing, `libaudiocontrol_gm.so` | AudioTrack → AudioFlinger → HAL | GM bypasses mixer; we go through full Android audio stack |
| **Audio policy override** | `vendor.gm.audio@1.1-impl.so`, custom CarAudioService | Standard `USAGE_MEDIA` / `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` | GM controls bus routing directly; we rely on AAOS mapping |
| **Real-time I/O priority** | `ioprio rt 4`, `ProcessCapacityHigh`, `HighPerformance` | Default I/O priority | GM's media threads get preferential disk/device I/O |
| **Kernel preemption** | `CONFIG_PREEMPT=y` benefits system tasks | Same kernel, but lower scheduling tier | GC and system services can preempt our threads |
| **Hypervisor buffer management** | GHS `ReadGuestMemory`/`WriteGuestMemory`, kernel-space staging | Java heap + native buffers subject to GC | GM buffers never touch the JVM |

### What We CAN Maximize
- `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)` on codec feeder and audio playback threads
- `PERFORMANCE_MODE_NONE` with `WRITE_NON_BLOCKING` (LOW_LATENCY denied to third-party)
- Pre-allocated buffers to minimize GC pressure on hot paths
- `KEY_LOW_LATENCY` on MediaCodec (Android 11+)
- `KEY_PRIORITY = 0` (realtime) on MediaCodec
- Hardware decoder `OMX.Intel.hw_vd.h264` via PlatformDetector
- Lock-free ring buffers for audio (already implemented)
- Aggressive frame dropping for video (projection philosophy)
- Foreground service with notification to prevent background demotion

---

## OS-Level Runtime Analysis (Y181 Build, gminfo 3.7)

Analysis of the extracted GM AAOS partitions (system, vendor, product) and GHS INTEGRITY hypervisor to understand how the OS schedules, throttles, and constrains the app at runtime.

### Thread Scheduling Hierarchy

The app's threads compete against multiple layers of higher-priority scheduling that cannot be overridden by a third-party app.

**`rtpolicy.conf`** (vendor/etc/rtpolicy.conf) — the most impactful configuration:
```
AVB stream handler threads:
  AvbRxWrk     → SCHED_FIFO 50, affinity cores 1,2,3
  AvbTxWrklow  → SCHED_FIFO 50, affinity cores 1,2,3
  AvbAlsaWrk0  → SCHED_FIFO 50, affinity cores 1,2,3

PulseAudio    → affinity cores 1,2,3 (SYS_NICE, ioprio rt 7)
daemon_cl     → SCHED_RR 49, affinity cores 1,2,3
```

SCHED_FIFO threads **unconditionally preempt** all CFS (SCHED_OTHER) threads. The app's USB read thread and codec thread run at CFS. Every time an AVB worker wakes (which is continuous during audio playback), the app's threads on that core are preempted instantly. The app is feeding audio into a pipeline whose own processing threads preempt the app's ability to feed it.

**Full priority stack affecting the app:**

| Priority | Scheduler | What | Cores |
|----------|-----------|------|-------|
| Highest | GHS hypervisor | VMM, Camera, Chimes, Audio_InitialTask | All (invisible to Android) |
| SCHED_FIFO 50 | Linux RT | AVB stream handler (3 threads) | 1,2,3 |
| SCHED_RR 49 | Linux RT | daemon_cl | 1,2,3 |
| SCHED_FIFO (varies) | Linux RT | PulseAudio, SmartX (priority 20) | 1,2,3 |
| ioprio rt 4 | I/O scheduler | audioserver, mediaserver, codec service | 0-3 |
| CFS top-app | Linux CFS | **Your app (when foreground)** | 0-3 |
| CFS background | Linux CFS | Background processes | **0 only** |

### System Service Init Configurations

From extracted init scripts, every media-critical system service runs with elevated privileges the app cannot match:

| Service | User | I/O Priority | Capabilities | Task Profile | cpuset |
|---------|------|-------------|--------------|--------------|--------|
| audioserver | audioserver | **rt 4** | SYS_NICE, BLOCK_SUSPEND | ProcessCapacityHigh, HighPerformance | foreground (0-3) |
| mediaserver | media | **rt 4** | — | ProcessCapacityHigh, HighPerformance | foreground (0-3) |
| vendor.media.omx (codec) | mediacodec | **rt 4** | — | — | foreground (0-3) |
| vendor.audio-hal (Harman) | audioserver | **rt 4** | SYS_NICE, BLOCK_SUSPEND | — | foreground + stune/foreground |
| PulseAudio | audioserver | **rt 7** | SYS_NICE | — | foreground (0-3) |
| AVB streamhandler | audioserver | **rt 4** | NET_ADMIN, NET_RAW, SYS_NICE | — | — |
| SurfaceFlinger | system | — | SYS_NICE | HighPerformance | — |

**Source files:**
- `system_extracted/system/etc/init/audioserver.rc`
- `system_extracted/system/etc/init/mediaserver.rc`
- `vendor_extracted/etc/init/android.hardware.media.omx@1.0-service.rc`
- `vendor_extracted/etc/init/vendor.hardware.audio@5.0-harman-custom-service.rc`
- `vendor_extracted/etc/init/pulseaudio.rc`
- `vendor_extracted/etc/init/avb_streamhandler_app.rc`

### CGroup and Task Profile Configuration

**`task_profiles.json`** (system/etc/task_profiles.json) defines scheduling treatment per app state:

| App State | CPU cgroup | cpuset (cores) | blkio weight | Timer Slack | I/O Priority |
|-----------|-----------|----------------|-------------|-------------|-------------|
| **top-app** (foreground) | MaxPerformance | 0-3 (all) | 1000 (max) | 50us | MaxIoPriority |
| **foreground** | HighPerformance | 0-3 (all) | 1000 | 50us | HighIoPriority |
| **background** | HighEnergySaving | **0 only** | **200** (BFQ: **10**) | **40ms** | LowIoPriority |
| **restricted** | — | **0 only** | — | — | — |

**Background demotion is catastrophic.** If the app loses foreground status even briefly (notification popup, GM system dialog, screen-off policy), its threads are confined to core 0 with 40ms timer slack and 1/5th (or 1/100th BFQ) I/O bandwidth. Real-time streaming becomes impossible.

**Kernel CFS tunables** (from init.rc):
- `sched_latency_ns = 10,000,000` (10ms scheduling period)
- `sched_wakeup_granularity_ns = 2,000,000` (2ms preemption threshold)

A waking thread must have ≥2ms more vruntime debt than the running thread before the kernel preempts. If system services are also runnable at higher nice priority, the app's thread may wait a full 10ms scheduling period.

### Memory and GC Pressure

**Heap limits** (vendor/build.prop):
```
dalvik.vm.heapgrowthlimit=288m     (normal app limit)
dalvik.vm.heapsize=512m            (largeHeap limit)
dalvik.vm.heaptargetutilization=0.75
dalvik.vm.heapminfree=512k
dalvik.vm.heapmaxfree=8m
```

**Physical memory** (6GB total, ~3.5GB available to Android):
- GHS hypervisor reserves ~2.5GB for kernel, tasks, IPU firmware, TEE, frame buffers, IPC
- Android's memory manager has no visibility into this reservation

**Swap** (vendor/etc/fstab):
- zRAM: **1GB** compressed swap
- `swappiness=100` — maximum aggressiveness, kernel heavily compresses pages to zRAM
- `watermark_scale_factor=60` — aggressive kswapd wakeup
- zRAM compression adds CPU overhead on an already thermally constrained 4-core Atom

**LMK** (PSI-based, system/build.prop):
- `ro.lmk.psi_partial_stall_ms=100` — kills start when memory stall exceeds 100ms
- `ro.lmk.psi_complete_stall_ms=100` — escalated kills at 100ms complete stall
- `ro.lmk.lmk_kswapd_limit=30720` — 30MB free threshold

**DEX2OAT** restricted to cores 0-1, 2 threads. Thermal cutoff at `THERMAL_STATUS_MODERATE`.

### Thermal Throttling

- **TDP**: 10W, passive cooling only (no fan)
- **Thermal framework**: Intel DPTF via `esif_ufd`, controls Intel RAPL power capping
- **Baseline**: CPU 53C / GPU 53C at idle
- Under sustained load, DPTF caps CPU power via RAPL — boost clocks (2.4GHz) drop toward base (800MHz)
- GC cycles, codec calls, and USB transactions all take longer as clocks decrease
- No active cooling means thermal throttling is cumulative and progressive

### GHS Hypervisor Impact

The Android VM runs under GHS INTEGRITY, a hard real-time OS. GHS tasks execute at priorities above everything in Android.

**GHS tasks that steal CPU from Android:**

| Task | Purpose | When Active |
|------|---------|-------------|
| `Chimes_InitialTask` | Safety alert sounds | Safety events (highest priority) |
| `Camera_InitialTask` | Reverse camera (IPU4) | Shift to reverse + periodic heartbeat |
| `Audio_InitialTask` | HDA controller, audio DSP | Always (manages Dirana3 DSP) |
| `GPU_InitialTask` | GPU resource arbitration | Display updates |
| `VMM1_InitialTask` | Android VM management | Always (VM entry/exit overhead) |
| `Lifecycle_InitialTask` | System health monitoring | Periodic (can trigger reboot) |

GHS uses `VMM_PendVCpu` / `VMM_UnpendVCpu` to pause/resume Android vCPUs. Android's CFS scheduler cannot account for this stolen time — it makes scheduling decisions based on incomplete information.

**Shared memory**: GHS accesses Android memory directly via `ReadGuestMemory`/`WriteGuestMemory` and EPT (Extended Page Tables). Audio data flows through virtio/shared memory channels (`/dev/ghs/chime`, `/dev/ghs/ipc`).

### Audio Signal Path on GM AAOS

The complete path from `AudioTrack.write()` to speaker output:

```
AudioTrack (app, SCHED_OTHER, best-effort I/O)
  → AudioFlinger mixport (bus0_media_out: 384 frames × 3 periods = 24ms buffer)
  → Audio HAL (audio.primary.broxton.so, ioprio rt 4)
  → Harman Audio Router (libharmanaudiorouter.so)
  → ALSA write to pcmMedia_p
  → PulseAudio (SCHED_FIFO, SYS_NICE, ioprio rt 7, cores 1-3)
  → Harman Crossbar (libharmancrossbar.so)
  → SmartX IAS pipeline (SCHED_FIFO 20, 192 frames = 4ms/period)
    → ias_volume (vol_media, 100ms ramp) → ias_mixer → mix_out (4ch)
  → ALSA hw:broxtontdf8532,0 (TDM3 TX, 48kHz S32_LE, 4ch)
  → AVB Ethernet TX (SCHED_FIFO 50, cores 1-3)
  → External amplifier (Dirana3 DSP) → Speakers
```

Estimated software latency: **~43-48ms**. Navigation audio follows the same path via `bus1_navigation_out` → `pcmNavi_p` (identical 24ms buffer configuration).

**Bus routing** (from audio_policy_engine_product_strategies.xml + car_audio_configuration.xml):
- `USAGE_MEDIA` → strategy `music` → context `music` → **bus0_media_out**
- `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` → strategy `nav_guidance` → context `navigation` → **bus1_navigation_out**
- Media and navigation are in separate volume groups with independent user control
- Volume curves are flat 0dB — all volume control delegated to Harman DSP/amplifier

**No audio processing applied at HAL level** for media or navigation buses. Only phone call audio (bus4) passes through SSE voice processing. AEC/NS preprocessing applies only to voice_communication and voice_recognition input streams via `libharmanpreprocessing_gm.so`.

### Degradation Timeline (Predicted from OS Configuration)

```
0-2 min:   App runs well. Top-app cpuset (all 4 cores), MaxPerformance.
           GHS steals ~5-15% CPU invisibly.
           SCHED_FIFO audio threads preempt periodically on cores 1-3.

2-5 min:   CPU temperature rises from 53C baseline (10W TDP, passive cooling).
           DPTF/Intel RAPL caps power → boost clocks drop toward 800MHz.
           GC cycles that took 130ms now take 300ms+.

5-15 min:  swappiness=100 drives aggressive zRAM compression.
           CPU overhead from compression competes with app threads.
           NativeAlloc GC (from getInputBuffer DirectByteBuffer) every ~7s.
           Each GC cycle takes longer on throttled CPU.

15-30 min: PSI-based LMKD (100ms stall threshold) starts killing background processes.
           Memory churn from kills triggers more GC.
           GC balloons to 400-628ms during pressure spikes.
           AVB workers miss 2ms deadlines → audio artifacts at speaker.
           USB thread blocked in queueInputBuffer during GC → audio starved. (Mitigated by Fix 1: v67+)

If app loses foreground:
           cpuset: cores 0-3 → core 0 ONLY
           blkio: 1000 → 200 (BFQ: 10)
           Timer slack: 50us → 40ms
           Instant failure for real-time streaming.
```

### SELinux USB Access

The app runs as `untrusted_app` domain. From extracted SELinux policies:
- **Granted**: `ioctl`, `read`, `write`, `getattr` on `usb_device` (via appdomain attribute)
- **NOT granted**: `open` — must use `UsbManager.openDevice()` (system_server opens FD, passes via Binder)
- **NOT granted**: `netlink_kobject_uevent_socket` — cannot listen for raw USB hotplug events

GM's CarPlay service runs under `bluetooth` domain with direct `open` on USB devices + proprietary `CARPLAY_USB` permission granting the `usb` group GID.

No USB bulk transfer throttling exists in the configuration — throughput bounded only by USB 2.0 hardware (480 Mbps) and kernel xHCI/DWC3 scheduling.

### MediaCodec Hardware Path

When the app creates a decoder via `MediaCodec.createDecoderByType("video/avc")`:

```
App (untrusted_app) → Binder IPC → vendor.media.omx service (mediacodec user, ioprio rt 4)
  → libmfx_omx_core.so → libmfx_omx_components_hw.so (OMX.Intel.hw_vd.h264)
  → libmfxhw64.so (Intel Media SDK)
  → libva.so + libva-android.so (VA-API)
  → i965_drv_video.so (Gen9 VA driver)
  → libdrm.so + libdrm_intel.so (DRM/KMS)
  → i915 kernel driver → Gen9 GPU fixed-function decode hardware
```

**HW decode capacity** (from media_codecs_performance.xml, GM Confidential):
- 1280x720: 460-590 fps
- 1920x1088: 320-360 fps
- At the app's ~1080p@30fps: uses ~8-9% of HW decode capacity (massively overprovisioned)
- SW fallback (c2.android.avc.decoder): only 11-13 fps at 1080p — unusable

**The IPC tax**: `queueInputBuffer()` crosses a hwbinder boundary to the codec service. The codec service has `ioprio rt 4`, but the app thread making the call does not. The binder transaction is subject to the app's CFS scheduling — if a SCHED_FIFO thread preempts mid-transaction, the codec call stalls.

### Key OS Configuration Files

| File | Location (relative to extracted_partitions/) | Content |
|------|----------------------------------------------|---------|
| rtpolicy.conf | vendor_extracted/etc/rtpolicy.conf | RT thread scheduling + core affinity |
| task_profiles.json | system_extracted/system/etc/task_profiles.json | CGroup profiles per app state |
| cgroups.json | system_extracted/system/etc/cgroups.json | CGroup controller mount points |
| audio_policy_configuration.xml | vendor_extracted/etc/audio_policy_configuration.xml | 13 audio output buses, gain, format |
| car_audio_configuration.xml | vendor_extracted/etc/car_audio_configuration.xml | AAOS zone/volume group mapping |
| audio_policy_engine_product_strategies.xml | vendor_extracted/etc/audio_policy_engine_product_strategies.xml | USAGE → strategy → bus routing |
| audio_routing_configuration.xml | vendor_extracted/etc/audio_routing_configuration.xml | HAL stream → ALSA device paths |
| media_codecs.xml | vendor_extracted/etc/media_codecs.xml | Codec registry (Intel HW first) |
| media_codecs_performance.xml | vendor_extracted/etc/media_codecs_performance.xml | Measured decode FPS per resolution |
| mfx_omxil_core.conf | vendor_extracted/etc/mfx_omxil_core.conf | OMX component → library mapping |
| asound.conf | vendor_extracted/etc/asound.conf | ALSA device definitions + AVB streams |
| smartx_config.txt | vendor_extracted/etc/smartx_config.txt | SmartX IAS scheduling (SCHED_FIFO 20) |
| init.bxtp_gm.rc | vendor_extracted/etc/init/hw/init.bxtp_gm.rc | Platform init (cpuset, GPU, USB, thermal) |
| plat_sepolicy.cil | system_extracted/system/etc/selinux/plat_sepolicy.cil | SELinux policy (USB access rules) |

---
