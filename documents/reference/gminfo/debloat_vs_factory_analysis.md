# GM AAOS Platform Analysis: Debloated vs Factory Reset

## Study Parameters

| Parameter | Value |
|-----------|-------|
| Device | gminfo37 (CJUD4R4f1b5fd0) |
| SoC | Intel Atom x7, HD Graphics 505 |
| RAM | 5.4 GB (reported by /proc/meminfo) |
| Display | CMN DD134IA-01B, 2400x960 @ 60.001434 Hz, 200 dpi |
| Audio HAL | Harman HAL + PulseAudio, 48 kHz stereo, 384-frame buffer |
| Video Codec | OMX.Intel.hw_vd.h264 (hardware H.264 decoder) |
| HWC | Intel iahwcomposer 2.3, explicit sync, High Quality scaling |
| HeapGrowthLimit | 288 MB |
| Capture Period | 2026-02-11 through 2026-02-14 |
| Capture Method | adb logcat -b all -v threadtime, 60s metrics snapshots, CPC200 SSH ttyLog |
| Timezone | America/Anchorage (AKST) |

### Phase Definitions

**Debloated**: All logs from 2026-02-11 16:14 through 2026-02-13 06:12. The GM AAOS head unit had 36 packages disabled/removed. CarLink (`zeno.carlink`) was installed and actively streaming CarPlay via CPC200-CCPA USB adapter during captured sessions.

**Factory Reset**: All logs from 2026-02-13 18:13 onward. The same head unit after a full factory reset with only native/1st-party GM apps present. CarLink was **not running** during any factory reset metrics capture, making the factory data a pure platform baseline.

### Log Corpus

| Phase | Files | Total Lines | Total Size | Capture Duration |
|-------|-------|-------------|------------|-----------------|
| Debloated | 8 logcat, 11 metrics, 5 cpc200 | 3,918,097 | ~560 MB | ~215 min |
| Factory Reset | 9 logcat, 4 metrics | 5,634,901 | ~810 MB | ~251 min |

---

## 1. Process & Service Inventory

### Packages Removed by Debloat (36 total)

| Category | Packages | Processes Eliminated |
|----------|----------|---------------------|
| Google Mobile Services | `com.google.android.gms` (.persistent, .ui, .unstable) | 4 |
| Google Play Store | `com.android.vending` (:background, :quick_launch) | 3 |
| Google Maps | `com.google.android.apps.maps` (:server_recovery_process, :vms) | 4 |
| Google Assistant | `com.google.android.carassistant` (:interactor, :search) | 2 |
| Google Setup | `com.google.android.car.setupwizard` (:gmscore_unstable) | 2 |
| Amazon Alexa | `com.gm.info3.alexa` (:tcps.service.process) | 2 |
| GM Voice | `com.gm.vac`, `com.gm.gmvoiceassistantproxy` | 2 |
| OnStar | `com.gm.hmi.onstar` (:gtbtService), `com.gm.hmi.onstarui` | 3 |
| SiriusXM | `com.gm.hmi.sxm`, `com.siriusxm.svcsxedl` | 2 |
| GM CarPlay HMI | `com.gm.hmi.applecarplay` | 1 |
| GM Navigation | `com.gm.tbt.common` | 1 |
| GM Analytics | `com.gm.hmianalytics` | 1 |
| GM Infotainment | `com.gm.mybrand`, `com.gm.updater`, `com.gm.setupwizard`, `com.gm.subscriptionmanager`, `com.gm.apimanager`, `com.gm.deviceinformation`, `com.gm.vmsplugin`, `com.gm.homescreen:clock`, `com.gm.hmi.trailer` | 9 |

### Process Start Comparison

| Metric | Debloated | Factory Reset | Delta |
|--------|-----------|---------------|-------|
| Total `Start proc` events | 238 | 294 | -56 (19% fewer) |
| Unique packages started | 67 | 102 | -35 (34% fewer) |
| Unique bound services | 21 | 33 | -12 (36% fewer) |
| Only unique to debloated | `zeno.carlink` | — | +1 |

### Factory-Only Services (11 persistent services absent from debloated)

| Service | Package |
|---------|---------|
| VpaService | `com.android.vending` |
| AIFService | `com.gm.hmi.onstarui` |
| OnStarEarlyBootUpService | `com.gm.hmi.onstarui` |
| SxmPowerModeService | `com.gm.hmi.sxm` |
| SxmMediaBrowserService | `com.gm.hmi.sxm` |
| AlexaService | `com.gm.info3.alexa` |
| SPNService | `com.gm.subscriptionmanager` |
| CarEmbeddedService | `com.google.android.apps.maps` |
| NavigationService | `com.google.android.apps.maps` |
| NotificationBufferService | `com.google.android.car.setupwizard` |
| SxeDlService | `com.siriusxm.svcsxedl` |

---

## 2. CPU, Thermal, and System Load

### System Load Average

| Metric | Debloated (streaming) | Factory Reset (idle) |
|--------|----------------------|---------------------|
| 1-min load avg | 5.0–7.4 | 6.7–18.1 |
| 5-min load avg | 5.3–6.4 | 4.1–9.7 |
| 15-min load avg | 4.7–5.7 | 2.2–3.7 |

The debloated system running full CarPlay streaming (H.264 decode + dual-stream audio + USB I/O) produced lower 1-minute load averages than the factory reset system at idle. The factory reset 1-min peaks of 18.1 occurred during system boot with all 36 reinstated services starting concurrently.

### CPU Temperature

| Sensor | Debloated (sustained streaming) | Factory Reset (idle/settling) |
|--------|--------------------------------|------------------------------|
| TCPU | 23°C | 30–49°C |
| TGPU | 22–23°C | 17–49°C |
| TSKN | 25°C | 25°C |
| TBATTERY | 25°C | 25°C |
| Throttle threshold | 108°C | 108°C |

Neither state approached thermal throttling. The factory reset reached 49°C under sustained idle load from background services — more than double the debloated temperature during active streaming.

### CPU Consumers (Top Processes)

**Debloated (with CarLink streaming):**
- `zeno.carlink`: 12–29% total (USB-ReadLoop 3–16%, H264-Feeder 6–9%, CodecLooper 3–9%, AudioPlayback 3%)
- `system_server`: typical background levels

**Factory Reset (idle):**
- `system_server`: 30%
- `pulseaudio`: 24%
- `CarplayService`: 17%
- `kswapd0`: 3.2–4.4% (memory pressure — not present in debloated top list)

### Slow Operations

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Slow operations/hour | ~550 | ~1,000 |
| Max slow dispatch | 2.5 s | 19.8 s |
| Slow dispatch source (factory worst) | — | `android.bg` AppProfiler$BgHandler |

Factory reset generated approximately 2x more slow operations per hour. The extreme 10–20 second slow dispatches in factory were from `AppProfiler$BgHandler` profiling Google Play Services and reinstated bloatware during boot.

---

## 3. Memory & Low Memory Killer

### System Memory

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| MemTotal | 5,799 MB | 5,799 MB |
| MemAvailable (boot) | ~3,131 MB | ~2,467 MB |
| MemFree (steady-state range) | 217–1,761 MB | 173–250 MB |
| kswapd0 CPU usage | Not in top | 3.2–4.4% |
| `dumpsys meminfo` timeout | Completes normally | TIMEOUT (10,000ms) EXPIRED |

### LMK Kill Comparison

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| LMK kills per session | 30–35 | 0–5 |
| Total RSS freed by LMK | ~1,940 MB | ~411 MB |
| Lowest oom_adj killed | **200** (visible process) | 955 (cached only) |
| Thrashing events | 4–7 episodes (97–156%) | 0 |
| "Device not responding" | 3–4 per session | 0 |
| CarLink killed by LMK | Never | N/A |

### LMK adj Score Distribution (Debloated, 30 kills in primary log)

| adj Range | Category | Kills |
|-----------|----------|-------|
| 999–925 | Empty/cached | 14 |
| 900–800 | Cached services | 10 |
| 700 | Heavy weight | 2 |
| 600 | Home | 1 (`com.gm.homescreen`) |
| 500 | Last activity | 4 |
| 200 | Visible process | 1 (`com.gm.car.media.gmcarmediaservice`) |

### LMK adj Score Distribution (Factory Reset, 5 kills in primary log)

| adj Range | Category | Kills |
|-----------|----------|-------|
| 985–955 | Cached only | All 5 |

### Processes Killed by LMK (Debloated)

| Process | Kills | Notes |
|---------|-------|-------|
| com.gm.hmi.settings | 4 | Repeatedly respawned and killed |
| com.gm.rhmi | 3 | Remote HMI |
| com.gm.android.gmkeychainservice | 3 | Keychain |
| com.gm.spn | 2 | SPN service |
| com.gm.hmi.apa | 2 | Parking assist |
| com.gm.favoritesprovider | 2 | |
| com.gm.car.media.localmediaplayer | 2 | |
| com.gm.usbmountreceiver | 2 | |
| com.gm.homescreen | 1 | adj 600 — home launcher |
| com.gm.car.media.gmcarmediaservice | 1 | adj 200 — visible process |

### Processes Killed by LMK (Factory Reset)

| Process | Kills | RSS Freed |
|---------|-------|-----------|
| com.google.android.gms | 1 | 271 MB |
| com.android.vending:background | 1 | 172 MB |
| com.gm.hmi.settings | 1 | 184 MB |
| com.gm.mybrand | 1 | — |
| android.process.media | 1 | — |

### ActivityManager Process Kills

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Total AM kills | ~33 | ~54 |
| Reason: empty process | 17 | 38 |
| Reason: freeze failure | 16 | 16 |

Factory had 63% more AM kills — more processes launching, running, and becoming empty. These are normal lifecycle kills, not pressure kills.

### onTrimMemory Events

| Level | Debloated | Factory Reset |
|-------|-----------|---------------|
| 5 (RUNNING_MODERATE) | — | 3 episodes |
| 10 (RUNNING_LOW) | 3 episodes | — |
| 15 (RUNNING_CRITICAL) | 4 episodes | — |
| 40/60/80 (BACKGROUND+) | — | Play Store background processes |

### Process Death Count

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Process died events | 103–114 | 76–86 |

Debloated had 33% more process deaths despite fewer total processes — the LMK cascade kills processes that respawn and may die again.

### Google Services Memory Footprint (Factory Reset Only)

| Service | Log Lines | RSS at Kill |
|---------|-----------|-------------|
| Play Store (Finsky/vending) | 4,419 | 172 MB |
| Google Play Services (GMS) | 4,424 | 271 MB |
| Google Maps | 773 | — |

These three services alone consumed ~443 MB RSS on the factory reset system, but served as killable LMK cushion.

### CarLink Memory Progression (Debloated)

| Time | PSS | USS | RSS | State |
|------|----:|----:|----:|-------|
| 05:16:49 | 76.6 MB | 73.9 MB | 160.3 MB | Idle startup |
| 05:17:17 | 95.4 MB | 88.9 MB | 209.5 MB | Video codec active |
| 05:26:54 | 127.2 MB | 119.9 MB | 244.1 MB | Peak streaming |
| 05:28:28 | 97.2 MB | 89.6 MB | 214.3 MB | Post-disconnect |
| 05:47:09 | 38.6 MB | 34.7 MB | 87.1 MB | Post-trim (LMK episode) |
| 06:09:23 | 86.4 MB | 79.3 MB | 158.7 MB | Recovery/growing |

Native heap showed sawtooth pattern: 8–50 MB, peaking during active video decode, dropping at session boundaries.

---

## 4. Garbage Collection

### GC Event Rates

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| GC events/hour (system-wide) | ~789 | ~270 |
| CarLink GC events/session | 224–329 | N/A |
| CarLink dominant GC type | NativeAlloc (86% of events) | N/A |
| system_server GC events | ~200–211 | 25–37 |

### GC Timing (All Processes)

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Median total time | 145–146 ms | 119–133 ms |
| P95 total time | 345–376 ms | 386–455 ms |
| Max total time | 847–888 ms | 643–788 ms |
| Average total time | 187–198 ms | 165–184 ms |

### GC Stop-The-World Pauses

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Pauses > 1 ms | 96–105 | 32–71 |
| Median STW | 2.4–3.4 ms | 3.3–5.1 ms |
| P95 STW | 12.8–16.9 ms | 13.4–14.1 ms |
| Max STW | **125.7 ms** | 17.6 ms |
| Average STW | 4.1–6.5 ms | 4.6–4.7 ms |

The 125.7 ms STW pause in debloated was a single outlier event.

### GC Events > 1 Second (system_server)

| Phase | Count | Worst |
|-------|-------|-------|
| Debloated | 3–4/session | 2.698 s (Background GC, 23 ms + 1.3 ms pause) |
| Factory Reset | 0–2/session | 1.348 s (Background young GC) |

### CarLink GC Profile

| Metric | Value |
|--------|-------|
| Events per session | 224–329 |
| NativeAlloc triggered | 218–284 (86%) |
| Explicit GC triggered | 0–45 |
| Heap steady-state | 5–6.5 MB used / 10–12 MB total |
| Heap utilization | ~49% free |
| Explicit GC outliers (wall-clock) | 3.606 s, 7.779 s, 9.565 s |
| Explicit GC reclamation | 879 KB–3,675 KB per event |
| Explicit GC STW pause | 99–307 us (sub-millisecond) |

The multi-second explicit GC wall-clock times had sub-millisecond STW pauses — the concurrent GC thread was CPU-starved by the video decode pipeline, not stalling the application. The NativeAlloc GCs fired approximately every 7 seconds, consistent with MediaCodec buffer lifecycle.

---

## 5. Jank & Frame Performance

### Choreographer Frame Drops

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Skipped-frame events | 6–9 | 0–5 |
| Total frames skipped | 587–720 | 0–198 |
| Worst single skip | 217–222 frames (SystemUI boot) | 54 frames |

**Debloated skip sources:**
- `com.android.systemui`: 72–222 frames (boot-time init)
- `zeno.carlink`: 32–152 frames (MediaCodec init / surface transitions)
- `com.gm.hmi.radio`: 51 frames

**Factory skip sources:**
- `com.gm.camera`: 54 frames
- `com.android.car.developeroptions`: 37–40 frames
- `com.gm.hmi.radio`: 34 frames

### Davey! Jank Events (>700 ms frame time)

| Phase | Count | Worst |
|-------|-------|-------|
| Debloated | 0 | — |
| Factory Reset | 4 | 224,960 ms (225 seconds, PID 1487 during boot) |

Factory had extreme jank events during boot/CarPlay session setup (721 ms, 1,065 ms, 1,416 ms, 224,960 ms). Debloated had zero Davey! events.

### ANRs

| Phase | Count |
|-------|-------|
| Debloated | 0 |
| Factory Reset | 0 |

No ANRs detected in either state.

---

## 6. Audio Subsystem

### HAL Configuration (Identical Both States)

| Parameter | Value |
|-----------|-------|
| HAL | Harman AudioHAL + PulseAudio layer |
| Sample rate | 48 kHz |
| Channels | Stereo |
| HAL buffer | 384 frames |
| Normal sink buffer | 768 frames |
| Mix period | 8.00 ms |
| Latency | 24.00 ms |
| Standby time | 0 ms |

### Audio Health

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| ALSA-level underruns (PulseAudio) | 0 | 8 |
| ALSA overruns | 0 | 0 |
| Dead IAudioTrack events | 0 | 1 (CarPlay native stack) |
| AudioTrack flag mismatch warnings | 0 | Multiple (flags 0x104 vs output 0x004) |
| AudioFlinger writeErrors | 0 | 0 |
| AudioFlinger numTracks (typical) | 1 (CarLink) | 2 (system) |

Factory reset had 8 PulseAudio ALSA-level underruns on `snd_pcm_mmap_commit` across `pcmMedia_p` and `pcmVR_ul_pro_24k2ch_p` streams. The native CarPlay stack also triggered a dead AudioTrack requiring `restoreTrack_l()` recreation. Neither occurred in debloated state.

GM's native CarPlay stack repeatedly requested audio flags the output did not support (`createTrack_l(): mismatch between requested flags (00000104) and output flags (00000004/00000006)`).

### CarLink Audio Performance (Debloated Only)

| Metric | Session 1 | Session 2 |
|--------|-----------|-----------|
| Media packets processed | 43,384 | 27,703 |
| Total underruns | 98 | 47 |
| Underrun rate | 0.23% | 0.17% |
| Underruns/minute | ~1.8 | ~1.5 |
| Ring buffer level (typical) | 50 ms | 50 ms |
| Ring buffer level (peak) | 110 ms | 110 ms |
| Nav stream packets | 0 | 0 |
| Write errors | 0 | 0 |
| Overruns | 0 | 0 |
| Stream config | 48 kHz, 2ch, buffer 24,640 B, USAGE_MEDIA |

Underruns clustered in bursts (e.g., 62 → 98 in one interval) suggesting momentary USB transfer stalls rather than sustained audio path issues. First underrun at packet 7,348 (~7 minutes into session).

### AVB Transport (Platform Level)

| Metric | Value |
|--------|-------|
| AVB Streamhandler version | 3.2.7.2 |
| ALSA ring buffer | 9,216 bytes |
| Scheduling | FIFO priority 50 for RX/TX threads |
| Ethernet | gPTP Master, 1000 Mbps |
| Streams | 3 RX + 3 TX configured |
| TX worker violation (worst) | 18.4 ms sleep vs 2 ms limit (9.2x over deadline) |
| Stream resets from violations | 2 episodes (all 3 TX streams reset each time) |

---

## 7. Video & Display Subsystem

### Display Configuration (Identical Both States)

| Parameter | Value |
|-----------|-------|
| Panel | CMN DD134IA-01B |
| Resolution | 2400x960 |
| Refresh rate | 60.001434 Hz |
| Density | 200 dpi |
| HWC | Intel iahwcomposer 2.3, explicit sync |
| GPU driver | i915 (Intel HD Graphics 505) |
| Gralloc | minigbm CrosGralloc4 |
| Content type support | Unsupported (getSupportedContentTypes fails) |

### Display Health

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| HWComposer errors | 16 | 10 |
| Gralloc format conversion errors | 130 | 29 |
| GPU errors/throttling | 0 | 0 |
| Wide gamut init failures | 0 | 8+ apps |

Gralloc `Failed to convert descriptor by convertToDrmFormat` errors were higher in debloated state (130 vs 29) due to CarLink's SurfaceView creating additional graphic buffers. These are cosmetic — the buffers are allocated successfully via fallback path.

Factory reset had 8+ apps fail `Device claims wide gamut support, cannot find matching config` — a known Intel HD Graphics 505 limitation (no 101010-2 format). Not present in debloated because those apps were removed.

HWComposer `getSupportedContentTypes failed: Unsupported (8)` appeared in both states — hardware limitation of the DD134IA-01B panel.

### MediaCodec / Intel VPU

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Codec used | OMX.Intel.hw_vd.h264 | OMX.Intel.hw_vd.h264 |
| Codec errors | 0 | 0 |
| Codec resets | 2 (startup surface swap) | 0 |
| Codec crashes | 0 | 0 |
| UnsupportedIndex queries (boot) | Present (benign) | Present (benign) |
| Middleware | CarLink direct | Cinemo player |

Both states used the same hardware H.264 decoder without errors. The debloated state had 2 codec resets during CarLink startup (surface swap during MediaCodec configuration), after which the pipeline was stable.

### CarLink Video Pipeline (Debloated Only)

| Metric | Value |
|--------|-------|
| Resolution | 2400x960 (native) |
| Profile | H.264 High Profile Level 5.0 |
| Sustained frame rate | 22–39 fps |
| Initial burst | 54 fps (first I-frame flood) |
| IDR frame size | ~47 KB |
| IDR request rate | ~1.2/sec |
| IDR drops | 0 |
| P-frame drops | 0–40 per 30-second interval |
| Oversized frame events | 0 |
| Pipeline state | run=true codec=true surface=true (no stalls) |
| First frame decode latency | 28 seconds after codec start |

P-frame drops coincided with high staging buffer overwrite counts — the decoder momentarily could not keep up, but no data was lost (staging pool absorbed the burst).

---

## 8. USB Subsystem

### USB Events

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Total USB log messages | 51,892–111,947 | 6,918–16,472 |
| USB detach events | 13–148 | Low |
| Dominant source | CARLINK (78,438 messages) | BinderAdapter (5,685) |

The debloated session with 148 USB detach events reflects CPC200-CCPA adapter hot-plug cycling during IAP2/CarPlay negotiation. The adapter reconnected successfully each time. A separate session showed 13 detach events with a `Reconnection failed: StandaloneCoroutine was cancelled` error.

### USB Errors (Both States)

| Error | Debloated | Factory Reset |
|-------|-----------|---------------|
| Failed to write f_rndis/ethaddr | Present | Present |
| Failed to open dual_role_usb | Present | Present |
| Port status enquiry failed | Present | Present |

All USB errors are platform-level — RNDIS and dual-role USB are not supported on this hardware.

### CPC200-CCPA Adapter (Debloated Only)

| Metric | Value |
|--------|-------|
| Video throughput | 103–1,950 KB/s (content dependent) |
| Audio throughput | 180–191 KB/s (stable) |
| Total sustained | ~400–800 KB/s typical, peaks to ~2 MB/s |
| ZLP warnings | 346 (5,120 byte payloads at USB bulk boundary) |
| AirPlay session drops | 2 in 55 min (kConnectionErr -6753) |
| Reconnection time | 10–12 seconds per drop |
| Session durations | 10m 5s, 32m 4s (before drops) |

---

## 9. Logcat Infrastructure

### Buffer Configuration

| Phase | Date | logd Buffer Size |
|-------|------|-----------------|
| Debloated (all) | 02-11 through 02-13 | 8 MB (8,388,608) |
| Factory Reset (02-13 evening) | 02-13 18:13–20:01 | 1 MB (1,048,576) — default |
| Factory Reset (02-14 morning) | 02-14 05:29+ | 8 MB (8,388,608) — manually restored |

Factory reset reverted `select_logd_size` to the Android default of 1 MB. The 1 MB buffer caused the early factory reset captures (`185210`, `185232`, `185251`) to have extremely low throughput (25–71 lines/sec vs normal 300–1,000) due to ring buffer overflow before the capture script could drain. No data was lost after the buffer was manually restored to 8 MB on 02-14.

### Chatty / Rate Limiting

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Chatty suppression messages | 0 | 0 |
| logcat rate-limiting active | No | No |

Neither state had logcat chatty suppression active. All log messages were emitted without throttling.

### Log Throughput

| Phase | Overall Lines/sec | Per-File Avg | Range |
|-------|-------------------|--------------|-------|
| Debloated | 303.3 | 312.9 | 202–392 |
| Factory Reset | 373.6 | 434.1 | 25–1,019 |

Factory reset throughput was 23% higher overall, with much higher variance. Debloated throughput was more consistent.

### Log Level Distribution

| Level | Debloated | Factory Reset |
|-------|-----------|---------------|
| V (Verbose) | 0% | 0% |
| D (Debug) | 91.4% | 65.9–79.7% |
| I (Info) | 5.8% | 14.5–14.8% |
| W (Warning) | 1.4% | 5.1–18.4% |
| E (Error) | 1.5% | 0.8–0.9% |
| F (Fatal) | 0% | 0.0002% (4 events) |

Debloated logs were overwhelmingly Debug-level (91.4%) because the removed GM apps contributed most Warning and Info level output. Factory had a broader distribution with 4–13x more warnings from reinstated services.

The 4 Fatal events in factory were from the Cinemo/CarPlay transport module during Bluetooth pairing.

### Top 10 Noisiest Tags

**Debloated:**

| Rank | Tag | Lines |
|------|-----|-------|
| 1 | MediaServiceProvider | 236,721 |
| 2 | CARLINK | 98,138 |
| 3 | BinderAdapter | 86,841 |
| 4 | MediaJson | 53,881 |
| 5 | SourceControllerSRep | 43,561 |
| 6 | SRReceiverManager | 32,726 |
| 7 | ActivityManager | 29,541 |
| 8 | CARLINK_AUDIO_DEBUG | 23,545 |
| 9 | NotificationService | 21,972 |
| 10 | SystemListener | 21,785 |

**Factory Reset:**

| Rank | Tag | Lines |
|------|-----|-------|
| 1 | pulseaudio | 307,734 |
| 2 | VHAL-STATS | 168,785 |
| 3 | CPECallbackController | 87,653 |
| 4 | TunerCommon | 86,192 |
| 5 | CarPlay_RouteGuidanceManager | 84,963 |
| 6 | GnssLocationProvider | 63,434 |
| 7 | MediaServiceProvider | 63,329 |
| 8 | GM_LOCATION | 61,981 |
| 9 | SafetyCamera_VehicleProxy | 45,847 |
| 10 | BinderAdapter | 45,463 |

Tags absent from debloated but dominant in factory (>40K lines each): `pulseaudio` (307K), `VHAL-STATS` (168K), `CPECallbackController` (87K), `TunerCommon` (86K), `CarPlay_RouteGuidanceManager` (84K), `GnssLocationProvider` (63K), `GM_LOCATION` (61K). These alone account for ~900K+ lines of factory-only log output.

### Unique Tags and PIDs

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Unique tags | 1,781–1,886 | 980–1,586 |
| Unique PIDs | 292 | 194–205 |
| Tags only in debloated | 869 | — |
| Tags only in factory | — | 569 |

Debloated had more unique tags (CarLink subcomponents + longer cumulative runtime exposing sporadic tags) and more unique PIDs despite fewer total packages.

---

## 10. Binder & IPC

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Binder failures | 14 | 0 |
| TransactionTooLargeException | 0 | 0 |
| service_manager_slow events | 28–37 | 60–75 |

Debloated binder failures were from two sources:
- `DCSM: Binder call failed` — `IDeviceConnectionManager.getDCMState()` NPE (1 event)
- `DeferredRebinder: Failed to bound to render service` — cluster rendering service rebind retries (12 events, then WTF log)

These are debloat artifacts — the cluster rendering service was disabled. Factory had no binder failures but 2x more `service_manager_slow` events (service lookup delays during boot with more services contending for binder).

---

## 11. Google Services Footprint (Factory Reset Only)

| Metric | Debloated | Factory Reset |
|--------|-----------|---------------|
| Play Store (Finsky) log lines | 15 | 4,419 |
| Google Maps log lines | 9 | 773 |
| Google Play Services (GMS) log lines | 37 | 4,424 |
| Factor increase | — | 86–295x |

Google services were residually referenced in debloated logs (framework stubs, intent resolution) but not running. In factory, they were fully active and among the noisiest processes.

---

## 12. CPC200-CCPA Firmware

### Adapter Identity

| Property | Value |
|----------|-------|
| Software Version | 2025.10.15.1127CAY |
| Kernel | Linux 3.14.52+g94d07bb (iMX6UL) |
| Platform | iMX6UL 14x14 EVK |
| ChipID | 7201, Version 0 |
| Product Type | A15W (YA box type) |
| HW Version | YMA0-WN16-0003 |
| WiFi | 5 GHz, Channel 161, SSID "carlink", password "12345678" |
| WiFi MAC | 00:E0:4C:91:B0:DF |
| Bluetooth | 38:BA:B0:7D:D3:8D, name "carlink" |
| AirPlay version | 320.17 |
| Supported modes | HiCar, CarPlay, AndroidAuto |
| Manufacture date | 20250225 |
| MFI Auth IC | Version 0x0102, cert 945 bytes cached |

### Connection Sequence

1. Kernel boots (Linux 3.14.52, iMX6UL)
2. USB gadget initializes (Android accessory mode)
3. Bluetooth HCI UART loads, firmware downloaded
4. WiFi driver loads (IW416, SDIO), AP starts on ch161 5 GHz
5. DHCP server starts on 192.168.43.1
6. Android USB accessory driver: `ADB_Driver main start`
7. 5 USB connect/disconnect cycles during CarPlay IAP2 negotiation (normal)
8. Bluetooth IAP2 connection to iPhone (64:31:35:8C:29:69)
9. CarPlay IAP2+NCM USB functions enabled
10. MFI authentication succeeds
11. AirPlay session starts: 2400x960 @ 60fps, H.264 High Profile L5.0

### Protocol Codec Parameters

| Parameter | Value |
|-----------|-------|
| H.264 AVCC header | 01 64 00 32 (Profile High 100, Level 5.0) |
| SPS | 27 64 00 32 ac 13 14 50 09 60 79 a6 e0 21 a0 c0 da 08 84 65 80 |
| PPS | 28 ee 3c b0 |
| I-frame size | 47,446–47,690 bytes (~46 KB) |
| IDR request cycle | HUD → RequestKeyFrame(12) → CPC200 → iPhone → I-frame → HUD |

### Firmware Stability

| Metric | Value |
|--------|-------|
| Kernel panics | 0 |
| Firmware crashes | 0 |
| Watchdog resets | 0 |
| Protocol errors | 0 |
| AirPlay session drops | 2 (kConnectionErr -6753) |
| Auto-reconnection time | 10–12 seconds |
| Session durations before drop | 10m 5s, 32m 4s |
| DisconnectPhoneConnection events | 52 (mostly USB enumeration cycling at boot) |
| ZLP warnings | 346 (5,120 byte payloads at bulk boundary, handled gracefully) |
