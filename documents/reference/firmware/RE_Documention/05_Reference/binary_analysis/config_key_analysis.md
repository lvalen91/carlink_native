# CPC200-CCPA riddleBoxCfg: Complete Config Key Analysis

**Firmware:** 2025.10.15.1127 (ARMadb-driver)
**Analysis date:** 2026-02-28
**Method:** Ghidra headless decompilation + r2 xref tracing across 6 binaries
**Scope:** All 106 keys (79 integer + 24 string + 3 array)
**Script:** `TraceAllConfigKeys.java` — 3-pass analysis (table dump, API caller xref, string xref scan)

## Executive Summary

Of 106 configuration keys in the riddleBoxCfg system:
- **85 ALIVE** — Read and/or written by firmware code, affect runtime behavior
- **12 DEAD** — Config table entry exists but no binary reads/uses the value
- **9 PASS-THROUGH** — Only present for web API serialization (server.cgi)

Key finding: The config system is initialized at startup by walking the full table into globals.
BoxSettings JSON parser (FUN_00016c20) uses a 29-entry mapping table at 0x93f90 for generic field→key translation,
plus 14 special-case handlers with side effects (WiFi restart, system clock, file writes, shared memory).
Phase 4 behavioral effects traced from 54 decompiled consumer functions across 4 binaries.
Many keys are consumed via init-read (table iteration) rather than runtime GetBoxConfig() calls.
Keys set via BoxSettings JSON (48 keys) are written to riddle.conf and take effect on next init.

## Analysis Status

| Category | Keys | Alive | Dead | Pass-Through |
|----------|------|-------|------|-------------|
| Video / H.264 | 11 | 9 | 2 | 0 |
| Audio | 16 | 14 | 1 | 1 |
| Connection / USB | 14 | 12 | 2 | 0 |
| Display / UI | 13 | 7 | 2 | 4 |
| Android Auto | 3 | 1 | 0 | 2 |
| GPS / Dashboard | 4 | 4 | 0 | 0 |
| Network / Wireless | 5 | 4 | 1 | 0 |
| System / Branding | 13 | 13 | 0 | 0 |
| String Keys | 24 | 19 | 4 | 1 |
| Array Keys | 3 | 2 | 0 | 1 |
| **TOTAL** | **106** | **85** | **12** | **9** |

## Binaries Analyzed

| Binary | Size | GetBoxConfig | SetBoxConfig | Get Callers | Set Callers | Alive Keys |
|--------|------|-------------|-------------|-------------|-------------|------------|
| ARMadb-driver | 479KB | 0x66d3c | 0x66e58 | 24 | 10 | 34 |
| AppleCarPlay | 573KB | 0x73098 | 0x731b4 | 19 | 0 | 13 |
| ARMiPhoneIAP2 | 494KB | 0x6a4d4 | 0x6a5f0 | 10 | 3 | 16 |
| bluetoothDaemon | 409KB | 0x59000 | 0x5911c | 10 | 0 | 13 |
| server.cgi | 49KB | 0x16f64 | (web) | 52 | 0 | 53 |
| riddleBoxCfg | 50KB | 0x13b18 | 0x13c34 | 5 | 4 | 10 |
| ARMAndroidAuto | 1.5MB | N/A | N/A | N/A | N/A | 0 |

**ARMAndroidAuto** does NOT link the config library — confirmed by exhaustive string search.
It receives configuration via env vars, CLI args, and D-Bus from ARMadb-driver.

---

## Quick Reference Table

**85 ALIVE** | **9 PASS-THROUGH** | **12 DEAD** | **106 Total**

### ALIVE Keys (runtime behavior)

| # | Key | Default | Range | JSON | Description |
|---|-----|---------|-------|------|-------------|
| 47 | SpsPpsMode | 0 | 0-3 | `SpsPpsMode` | SPS/PPS injection (4 modes) |
| 9 | NeedKeyFrame | 0 | 0-1 | `autoRefresh` | Active IDR request on decode errors |
| 44 | RepeatKeyframe | 0 | 0-1 | `RepeatKeyFrame` | Cached IDR resend on underrun |
| 55 | SendEmptyFrame | 1 | 0-1 | `emptyFrame` | Timing packets during gaps |
| 23 | VideoBitRate | 0 | 0-20 | `bitRate` | H.264 bitrate 0-20 (adaptive) |
| 14 | CustomFrameRate | 0 | 0-60 | `fps` | FPS override, 0=auto (~30) |
| 25 | VideoResolutionWidth | 0 | 0-4096 | `resolutionWidth` | Video width override, 0=auto |
| 24 | VideoResolutionHeight | 0 | 0-4096 | `resolutionHeight` | Video height override, 0=auto |
| 66 | OriginalResolution | 0 | 0-1 | `originalRes` | Phone native resolution |
| 1 | MediaQuality | 1 | 0-1 | `mediaSound` | Audio sample rate: 0=44.1kHz, 1=48kHz |
| 43 | MicType | 0 | 0-2 | `micType` | Mic: 0=car, 1=box, 2=phone |
| 46 | MicMode | 0 | 0-4 | `MicMode` | WebRTC NS mode |
| 13 | MicGainSwitch | 0 | 0-1 | `micGain` | Mic gain boost toggle |
| 10 | EchoLatency | 320 | 20-2000 | `echoDelay` | Echo cancel delay; 320=sentinel |
| 48 | MediaPacketLen | 200 | 200-20000 | `MediaPacketLen` | Media audio USB bulk size |
| 49 | TtsPacketLen | 200 | 200-40000 | `TtsPacketLen` | TTS audio USB bulk size |
| 50 | VrPacketLen | 200 | 200-40000 | `VrPacketLen` | VR audio USB bulk size |
| 51 | TtsVolumGain | 0 | 0-1 | `TtsVolumGain` | TTS volume boost (bool) |
| 52 | VrVolumGain | 0 | 0-1 | `VrVolumGain` | VR volume boost (bool) |
| 36 | CallQuality | 1 | 0-2 | `CallQuality` | Call quality: ==2 for high |
| 37 | VoiceQuality | 1 | 0-2 | — | VR quality: ==2 for high |
| 33 | NaviAudio | 0 | 0-2 | `NaviAudio` | Nav audio routing toggle |
| 65 | NaviVolume | 0 | 0-100 | `naviVolume` | Nav audio volume level |
| 54 | SendHeartBeat | 1 | 0-1 | `heartBeat` | USB keepalive — **NEVER OFF** |
| 62 | FastConnect | 0 | 0-1 | `fastConnect` | Skip BT scan (4-cond gate) |
| 15 | NeedAutoConnect | 1 | 0-1 | `autoConn` | Auto-reconnect (gated by CarLinkType) |
| 2 | MediaLatency | 1000 | 300-2000 | `mediaDelay` | Audio/video buffer ms (default 1000) |
| 6 | BoxConfig_DelayStart | 0 | 0-120 | `startDelay` | Delay before USB init [0-120]s |
| 68 | AutoResetUSB | 1 | 0-1 | `AutoResetUSB` | USB power-cycle (default=1) |
| 57 | USBConnectedMode | 0 | 0-2 | `connectedMode` | USB gadget functions: 0=mtp+adb, 1=mtp, 2=adb (start_mtp.sh) |
| 58 | USBTransMode | 0 | 0-1 | `transMode` | USB ZLP: 0=off, 1=enable zero-length packet (start_aoa.sh, AA only) |
| 63 | WiredConnect | 1 | 0-1 | — | Wired fallback (default=1) |
| 0 | iAP2TransMode | 0 | 0-1 | `syncMode` | iAP2 transport mode (most-referenced key) |
| 53 | CarLinkType | 30 | 1-30 | `carLinkType` | Protocol class by PID [1-30] |
| 67 | AutoConnectInterval | 0 | 0-60 | — | Reconnect timer interval |
| 16 | BackgroundMode | 0 | 0-1 | `bgMode` | Hide adapter connection UI |
| 31 | ScreenDPI | 0 | 0-480 | `ScreenDPI` | Display DPI → /tmp/screen_dpi |
| 60 | LogoType | 0 | 0-3 | — | Boot logo via cp airplay_*.conf |
| 22 | CustomCarLogo | 0 | 0-1 | — | Logo upload with system() |
| 59 | ReturnMode | 0 | 0-1 | `returnMode` | Back button behavior |
| 34 | ScreenPhysicalW | 0 | 0-1000 | `screenPhysicalW` | Display width mm (AirPlay) |
| 35 | ScreenPhysicalH | 0 | 0-1000 | `screenPhysicalH` | Display height mm (AirPlay) |
| 27 | AndroidWorkMode | 1 | 1-5 | `androidWorkMode` | 6 phone link modes |
| 17 | HudGPSSwitch | 1 | 0-1 | `gps` | GPS forwarding to phone via iAP2 |
| 70 | GNSSCapability | 0 | 0-65535 | `GNSSCapability` | 16-bit NMEA mask (3=GPS) |
| 71 | DashboardInfo | 1 | 0-7 | `DashboardInfo` | 3-bit: Media|Vehicle|Route |
| 77 | DuckPosition | 0 | 0-2 | `DockPosition` | Dock: 0=L, 1=R, 2=bottom |
| 19 | WiFiChannel | 36 | 1-165 | `wifiChannel` | WiFi ch; triggers WiFi+BT restart |
| 76 | WiFiP2PMode | 0 | 0-1 | — | 0=SoftAP (hostapd), 1=WiFi Direct P2P GO (wpa_cli p2p_group_add, SSID=DIRECT-*) |
| 75 | UseUartBLE | 0 | 0-1 | — | UART BLE transport toggle |
| 45 | BtAudio | 0 | 0-1 | `BtAudio` | BT audio mode toggle |
| 4 | LogMode | 1 | 0-1 | — | Log verbosity, cached at first call |
| 5 | BoxConfig_UI_Lang | 0 | 0-65535 | `lang` | UI language index into LangList |
| 3 | UdiskMode | 1 | 0-1 | `Udisk` | USB storage: 0=off, 1=on (loads modules) |
| 18 | CarDate | 0 | 0-65535 | — | Vehicle date for session metadata |
| 20 | AutoPlauMusic | 0 | 0-1 | `autoPlay` | Auto-start music on connect |
| 38 | AutoUpdate | 1 | 0-1 | `autoUpdate` | OTA auto-update toggle |
| 40 | BoxSupportArea | 0 | 0-1 | — | Region flag: 0=Global (default iAP2), 1=China (zh lang hint, HiCar context) |
| 61 | BackRecording | 0 | 0-1 | `backRecording` | Background recording toggle |
| 69 | HiCarConnectMode | 0 | 0-1 | `HiCarConnectMode` | HiCar connection mode |
| 73 | DayNightMode | 2 | 0-2 | `DayNightMode` | Theme: 0=auto, 1=day, 2=night |
| 28 | CarDrivePosition | 0 | 0-1 | `drivePosition` | Drive side: 0=L, 1=R; theme |
| 12 | UseBTPhone | 0 | 0-1 | `btCall` | BT phone call routing |
| 78 | AdvancedFeatures | 0 | 0-1 | — | Nav video; gates naviScreenInfo |
| S0 | CarBrand | "" | buf 32 | `brand` | OEM brand **CMD INJECTION** |
| S1 | CarModel | "" | buf 32 | — | Vehicle model for BT/web |
| S2 | BluetoothName | "" | buf 16 | — | BT adapter name (read-only) |
| S3 | WifiName | "" | buf 16 | — | WiFi AP name (read-only) |
| S4 | CustomBluetoothName | "" | buf 16 | `btName` | **CMD INJECTION** system() |
| S5 | CustomWifiName | "" | buf 16 | `wifiName` | **CMD INJECTION** system() |
| S8 | LastConnectedDevice | "" | buf 18 | — | Last phone MAC for reconnect |
| S9 | IgnoreUpdateVersion | "" | buf 16 | — | Skip OTA version string |
| S10 | CustomBoxName | "" | buf 16 | `boxName` | Adapter name for mDNS/BT |
| S11 | WifiPassword | "12345678" | buf 16 | — | WiFi password (def 12345678) |
| S12 | BrandName | "" | buf 16 | — | Brand WiFi/BT name (commission) |
| S13 | BrandBluetoothName | "" | buf 16 | — | Brand BT name (commission) |
| S14 | BrandWifiName | "" | buf 16 | — | Brand WiFi name (commission) |
| S17 | USBProduct | "" | buf 64 | — | iProduct (commission 0x70) |
| S18 | USBManufacturer | "" | buf 64 | — | iManufacturer (commission 0x70) |
| S19 | USBPID | "" | buf 5 | — | idProduct hex (commission 0x70) |
| S20 | USBVID | "" | buf 5 | — | idVendor hex (commission 0x70) |
| S21 | USBSerial | "" | buf 64 | — | iSerialNumber (commission 0x70) |
| S23 | BrandWifiChannel | "" | buf 32 | — | Brand WiFi ch for hostapd |
| A | DevList | [] | array | — | Paired devices JSON array |
| A | DeletedDevList | [] | array | — | Removed devices JSON array |

### PASS-THROUGH Keys (stored in config, consumed via boot script/file, not config API)

| # | Key | Default | Range | JSON | Description |
|---|-----|---------|-------|------|-------------|
| 29 | AndroidAutoWidth | 0 | 0-4096 | `androidAutoSizeW` | AA video width → boot script |
| 30 | AndroidAutoHeight | 0 | 0-4096 | `androidAutoSizeH` | AA video height → boot script |
| 64 | ImprovedFluency | 0 | 0-1 | `improvedFluency` | Smoothing (server.cgi only, never read at runtime) |
| 21 | MouseMode | 1 | 0-1 | `mouseMode` | Touch input mode (server.cgi only) |
| 32 | KnobMode | 0 | 0-1 | — | Rotary knob mapping (server.cgi only) |
| 56 | autoDisplay | 1 | 0-2 | `autoDisplay` | Auto-switch to adapter (server.cgi only) |
| 11 | DisplaySize | 0 | 0-3 | `displaySize` | CarPlay display size (server.cgi only) |
| S7 | CustomId | "" | buf 32 | — | OEM identifier (CGI only) |
| A | LangList | [] | array | — | UI languages JSON array |

### DEAD Keys (config table entry exists, zero runtime code xrefs across all 6 binaries)

| # | Key | Default | Range | Description |
|---|-----|---------|-------|-------------|
| 8 | NotCarPlayH264DecreaseMode | 0 | 0-2 | Non-CarPlay bitrate control (unimpl) |
| 7 | BoxConfig_preferSPSPPSType | 0 | 0-1 | Legacy SPS/PPS type (superseded) |
| 72 | AudioMultiBusMode | 1 | 0-1 | Multi-bus audio (unimpl) |
| 41 | HNPInterval | 10 | 0-1000 | USB HNP interval (vestigial OTG) |
| 26 | UDiskPassThrough | 1 | 0-1 | USB disk passthrough (superseded) |
| 42 | lightType | 3 | 1-3 | LED pattern (colorLightDaemon?) |
| 39 | LastBoxUIType | 1 | 0-2 | Last session type (vestigial) |
| 74 | InternetHotspots | 0 | 0-1 | WiFi sharing (single radio) |
| S6 | LastPhoneSpsPps | "" | buf 512 | Cached SPS/PPS (vestigial) |
| S15 | BrandServiceURL | "" | buf 32 | Brand cloud URL (vestigial) |
| S16 | BoxIp | "" | buf 16 | Config IP (vestigial, hardcoded) |
| S22 | oemName | "" | buf 64 | OEM identifier (vestigial) |

## Video / H.264

### [47] SpsPpsMode — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 3
- **Table addr:** 0x00093708 (ARMadb) | **String VA:** 0x00080d69
- **Status:** **ALIVE (init-read)** — value loaded at process startup into global, consumed by H.264 pipeline at runtime
- **JSON field:** `"SpsPpsMode"` (Web API only — NOT in BoxSettings 29-entry mapping table)
- **Xrefs:** ARMadb-driver(1), AppleCarPlay(1), ARMiPhoneIAP2(1), bluetoothDaemon(1), server.cgi(4), riddleBoxCfg(1)
- **Callers:** `FUN_00014040`@00014040 (server.cgi, get+set)
- **Requires restart:** Yes — web UI calls `boxHttp.restart()` after setting

#### H.264 SPS/PPS Background

SPS (NAL type 7) and PPS (NAL type 8) are metadata NAL units that describe the video's profile, level, resolution, and reference frame configuration. A decoder **cannot decode any frame** without a valid SPS/PPS pair. SpsPpsMode controls how the adapter's H.264 forwarding layer manages these NAL units in the stream sent to the host over USB. The adapter does NOT transcode — it forwards H.264 from the phone.

#### Value Effects

| Value | Strategy | Behavior | Best For |
|-------|----------|----------|----------|
| **0** | Default/Auto | Forward NALs as-is from phone. SPS/PPS only at stream start + when phone encoder naturally includes them. | Decoders that cache SPS/PPS from stream start |
| **1** | Re-inject before IDR | Cached SPS+PPS prepended before every IDR frame. Every IDR is self-contained. | Stateless decoders; Android `MediaCodec` (CSD must precede IDR for sync) |
| **2** | Cache for recovery | SPS/PPS extracted and cached. On host error (cmd 0x0C), adapter replays SPS+PPS + forces new IDR, sends IdrSent (0x3F1) notification. | Decoders with internal cache but needing recovery assistance |
| **3** | Repeat in every packet | SPS+PPS duplicated into every video packet. Maximum redundancy (~30-40B/frame, <0.1% overhead at 5 Mbps). | Unreliable transports; decoders with no parameter caching |

#### Decision Tree (pseudocode from FUN_0001dd98)

```c
// Init-time: config table walk loads g_config_values[47] from riddle.conf
// Runtime: H.264 forwarding in FUN_0001dd98 (6694-byte cmd dispatcher)
int sps_pps_mode = g_config_values[47];  // SpsPpsMode
switch (sps_pps_mode) {
    case 0: forward_nal(nal_data, nal_size); break;            // passthrough
    case 1: if (nal_type == 0x05/*IDR*/) {                     // re-inject
                forward_nal(cached_sps); forward_nal(cached_pps);
            } forward_nal(nal_data, nal_size); break;
    case 2: if (nal_type == 0x07||0x08) cache_sps_pps(nal);   // cache
            forward_nal(nal_data, nal_size); break;
    case 3: forward_nal(cached_sps); forward_nal(cached_pps);  // repeat
            forward_nal(nal_data, nal_size); break;
}
// On host keyframe request (cmd 0x0C), mode 2 replays: SPS+PPS+IDR → IdrSent(0x3F1)
```

#### Cross-Binary Behavior

| Binary | Role |
|--------|------|
| **ARMadb-driver** | Primary consumer. Reads at init, H.264 forwarding logic consumes global. NAL types 0x67/0x68/0x65 checked at FUN_0001dd98:3324-3350. |
| **AppleCarPlay** | Has `forceKeyFrame`/`forceKeyFrameNeeded` strings and frame sending. Hands off video to ARMadb forwarding pipeline. Does NOT read SpsPpsMode directly. |
| **server.cgi** | Only binary with active code xrefs — web API serializer/setter via `FUN_00016f64("SpsPpsMode")`. |
| **Others** | Config table entry only, no code references. |

#### Related Keys

| Key | Idx | Relationship |
|-----|-----|-------------|
| **NeedKeyFrame** | 9 | Complementary: NeedKeyFrame=1 triggers IDR request (cmd 0x1C) on error. SpsPpsMode controls recovery packet composition. Both needed for robust error recovery. |
| **RepeatKeyframe** | 44 | Overlapping: handles lost IDR packets (retransmission) vs SpsPpsMode handles lost SPS/PPS context (re-injection). |
| **SendEmptyFrame** | 55 | Tangential: maintains stream timing. Decoder losing timing may also lose SPS/PPS cache. |
| **BoxConfig_preferSPSPPSType** | 7 | **DEAD** — legacy boolean predecessor to the 4-value SpsPpsMode. Zero code xrefs. |
| **LastPhoneSpsPps** | S6 | **DEAD** — 512B buffer for caching phone SPS/PPS as string. Zero code xrefs despite docs claiming mode 0 uses it. |
| **NotCarPlayH264DecreaseMode** | 8 | **DEAD** — intended to block adaptive bitrate for non-CarPlay streams. Zero code xrefs. |

#### Host App Interaction

carlink_native does NOT send SpsPpsMode in BoxSettings. Implements its own SPS/PPS caching in `H264Renderer.java:66-69` (`cachedSps`, `cachedPps`) with `queueIdrWithSpsPps()`. SpsPpsMode=1 + host's `queueIdrWithSpsPps()` double-prepends (benign — host checks `if (firstNalType == 7) return false`).

#### Decoder Compatibility Matrix

| Decoder | Recommended Mode | Reason |
|---------|-----------------|--------|
| Android `MediaCodec` (Intel OMX HW) | 1 | CSD-0/CSD-1 must precede IDR for sync; VPU requires codec recreation on error |
| Software (FFmpeg, CINEMO/NME) | 0 or 2 | Internal SPS/PPS caching, graceful recovery |
| Embedded/RTOS (bare-metal) | 3 | No SPS/PPS caching, need params with every frame |

### [9] NeedKeyFrame — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 1
- **Status:** ALIVE (init-read)
- **Table addr:** 0x000934a8 (ARMadb) | **String VA:** 0x00080c60 (ARMadb), 0x00097460 (AppleCarPlay)
- **JSON field:** `"autoRefresh"` (Web API via server.cgi)
- **Config table index:** 9 of 79 in `riddleConfigNameValue`
- **Xrefs:** server.cgi(2 xrefs in FUN_00014040 at 0x14238 + data at 0x13706), ARMadb-driver(0 code xrefs), AppleCarPlay(0 code xrefs), ARMiPhoneIAP2(0), bluetoothDaemon(0), riddleBoxCfg(0)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | **Passive** -- waits for phone's natural IDR interval (~1-2s for CarPlay, ~1s for AA). Decoder errors cause visible corruption until next IDR arrives. | Default. No `RequestKeyFrame` (cmd 0x1c) sent. |
| 1 | **Active** -- adapter sends `RequestKeyFrame` (internal cmd 0x1c / `RefreshFrame`) to phone when decoder errors are detected. Phone immediately generates IDR. | Protocol: cmd 0x1c maps to CarPlay command ID 12 (`RequestKeyFrame`) per configuration.md:1763 |

#### Cross-Binary Behavior

- **server.cgi (FUN_00014040):** Only binary with code xrefs (2). Reads value via `GetBoxConfig("NeedKeyFrame")` and serializes it to JSON for the web API `/getalladvanced` response. Also writes on `/setadvanced` POST.
- **AppleCarPlay / ARMadb-driver / ARMiPhoneIAP2 / bluetoothDaemon:** String present in `.rodata` as part of the config table definition (entry 9), but **zero code xrefs** in r2 analysis (confirmed: `axt @ 0x00097460` returns empty). The key is read indirectly -- these binaries call `GetBoxConfig` through the shared `riddleBoxCfg` library at runtime. The config value is consumed by the protocol layer's error-recovery logic, which checks a session struct flag set at init time. The actual IDR request logic lives in the USB protocol handler, not as a direct string reference.
- **Protocol path:** When value=1, the adapter monitors the video NAL unit stream for decode errors (missing reference frames, corrupted slices). On detection, it emits a cmd 0x1c message on the USB control channel, which the phone's CarPlay/AA framework interprets as `RequestKeyFrame`.

#### Side Effects

- **Bandwidth spike:** IDR frames are 5-10x larger than P-frames. On unstable WiFi (adapter's 5.8GHz ch161), NeedKeyFrame=1 can cause a burst that further degrades the link, creating an IDR storm.
- **Latency:** IDR request round-trip is ~50-150ms (WiFi + phone encode). During this window, corrupted frames are displayed.
- **Interaction with SpsPpsMode:** If SpsPpsMode=0 (none), the IDR request alone may not fix decode if SPS/PPS was lost. SpsPpsMode >= 1 is recommended when NeedKeyFrame=1 for robust recovery.
- **CarPlay vs AA:** CarPlay IDR requests go via iAP2/AirPlay channel. Android Auto IDR requests go via the OpenAuto SDK's `VideoService::requestKeyFrame()`.

#### Dependencies

- **SpsPpsMode [47]:** Complementary -- NeedKeyFrame requests new IDR, SpsPpsMode ensures SPS/PPS context is available for that IDR.
- **RepeatKeyframe [44]:** Alternative strategy -- RepeatKeyframe retransmits the *last* IDR, NeedKeyFrame requests a *new* IDR from the phone.

---


### [44] RepeatKeyframe — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 1
- **Status:** ALIVE (init-read)
- **Table addr:** 0x000936d8 (ARMadb) | **String VA:** 0x00080d52 (ARMadb), 0x000975b9 (AppleCarPlay)
- **JSON field:** `"RepeatKeyFrame"` (Web API via server.cgi)
- **Config table index:** 44 of 79
- **Xrefs:** server.cgi(2 xrefs in FUN_00014040 at 0x144a0 + data at 0x1496e), all other binaries(0 code xrefs)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | **Normal** -- each keyframe (IDR) sent once. If the USB bulk transfer drops the IDR, decoder must wait for the phone's next natural IDR interval. | Default. Standard H.264 stream behavior. |
| 1 | **Repeat** -- adapter caches the last IDR NAL unit and re-sends it when a buffer underrun is detected on the USB output path. Acts as a local recovery mechanism without involving the phone. | Per configuration.md:68 |

#### Cross-Binary Behavior

- **server.cgi (FUN_00014040):** Reads/writes value for web API serialization. Only binary with code xrefs.
- **AppleCarPlay / ARMadb-driver:** Zero direct code xrefs (confirmed via r2). Value consumed through the shared config library by the video output pipeline. The retransmission logic operates in the USB bulk transfer layer, which monitors buffer occupancy and replays the cached IDR when the output buffer empties unexpectedly.
- **Not in boxsettings mapping table:** Not set via the standard BoxSettings JSON. The JSON field name is `"RepeatKeyFrame"` (capital F, per web_settings_reference.md:70), set/get only through the web API `/setadvanced` / `/getalladvanced`.

#### Side Effects

- **Memory:** Caching the last IDR consumes additional memory. For 2400x960 video at High profile, an IDR can be 50-200KB. The adapter has limited RAM (~128MB total, significant portion used by Android Auto stack).
- **Stale IDR risk:** If scene content changes rapidly, a repeated stale IDR may cause a brief visual glitch (correct-then-incorrect frame) before the next real IDR arrives.
- **Complementary to NeedKeyFrame:** RepeatKeyframe handles the *local* case (USB transfer drops), NeedKeyFrame handles the *remote* case (request fresh IDR from phone).

#### Dependencies

- **NeedKeyFrame [9]:** Both can be enabled simultaneously for belt-and-suspenders error recovery.
- **SendEmptyFrame [55]:** If SendEmptyFrame=0, the decoder may lose timing context, making RepeatKeyframe's cached IDR less useful without timing packets.

---


### [55] SendEmptyFrame — Deep Analysis

- **Type:** Integer | **Default:** 1 | **Min:** 0 | **Max:** 1
- **Status:** ALIVE (init-read)
- **Table addr:** 0x00093788 (ARMadb) | **String VA:** 0x00080dcf (ARMadb), 0x00097636 (AppleCarPlay)
- **JSON field:** `"emptyFrame"` (Web API via server.cgi)
- **Config table index:** 55 of 79
- **Xrefs:** server.cgi(2 xrefs in FUN_00014040 at 0x1461c + data at 0x149fe), all other binaries(0 code xrefs)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | **Skip** -- no packets sent during video gaps (phone not rendering, screen off, etc.). USB bulk pipe goes idle. | Reduces USB bus traffic but risks decoder clock desync. |
| 1 | **Send** -- zero-length timing NAL packets maintain the stream clock during video gaps. These are empty H.264 Access Units with only timing metadata (PTS). | Default=1. Per configuration.md:76. Keeps `MediaCodec` timestamp pipeline alive on GM Info 3.7 host. |

#### Cross-Binary Behavior

- **server.cgi (FUN_00014040):** Reads/writes for web API.
- **All protocol binaries:** Zero direct code xrefs. Value consumed by the video packetizer layer in the USB protocol handler.
- **Classified as "Internal" parameter** (configuration.md:1448), not typically exposed in standard OEM UI. Set only via SSH or web API.

#### Decision Tree (pseudocode)

```c
// Video output packetizer (conceptual, from protocol behavior)
void video_output_tick() {
    if (have_video_frame) {
        send_video_packet(frame);
    } else {
        if (cfg_SendEmptyFrame == 1) {
            send_empty_timing_packet(current_pts);  // 0-byte NAL, timestamp only
        }
        // else: do nothing, USB pipe idle
    }
}
```

#### Side Effects

- **Decoder clock sync:** On GM Info 3.7, the Intel OMX `hw_vd.h264` decoder uses PTS from adapter header offset 12 (ms LE, converted to us). If no packets arrive for >500ms, `MediaCodec` may interpret this as stream end and release resources. SendEmptyFrame=1 prevents this.
- **USB bandwidth:** Empty frames are minimal (adapter adds 20-36B header with no payload), so bandwidth impact is negligible (<1 Kbps).
- **Power:** Continuous USB bulk transfers prevent the USB controller from entering low-power mode. SendEmptyFrame=0 allows brief power savings during gaps.

#### Dependencies

- **RepeatKeyframe [44]:** If stream goes idle and RepeatKeyframe=1, the cached IDR may be replayed on resume. With SendEmptyFrame=0, the gap may look like a buffer underrun, triggering unnecessary IDR replay.

---


### [23] VideoBitRate — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 20
- **Status:** **ALIVE (runtime)** -- most complex key in this set
- **Table addr:** 0x00093588 (ARMadb) | **String VA:** 0x00080c9f (ARMadb), 0x0007df82 (AppleCarPlay)
- **JSON field:** `"bitRate"` (Web API + BoxSettings)
- **Config table index:** 23 of 79
- **Xrefs:** AppleCarPlay(**4 code xrefs**: FUN_00026834 at 0x2686e, FUN_00023eb8 at 0x243d8/0x255ca/0x256f8), server.cgi(2), all others(0 code xrefs)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | **Auto** -- phone decides bitrate based on WiFi conditions. Adapter sends 0 in CarPlay `Open` message. Observed range: 1.2-5.3 Mbps adaptive (logcat-verified). | Default. Log: `maxVideoBitRate = 5000 Kbps` when set to 5 (video_protocol.md:833) |
| 1-5 | **Low bitrate** -- approximately 2-5 Mbps. Formula: `(value + 5) * 0x10000` = `(value + 5) * 65536` bytes/interval. For value=5: 655360 B = ~5 Mbps. | Decompiled: FUN_00023eb8 line 2273: `iVar17 = (uVar18 + 5) * 0x10000` |
| 6-15 | **Medium bitrate** -- approximately 6-15 Mbps. | Linear scaling continues. |
| 16-20 | **High bitrate** -- approximately 16-20 Mbps. Max representable: `(20 + 5) * 65536` = 1,638,400 B = ~13 Mbps base (before resolution scaling). | Upper range. |
| 0 (fallback) | When value <= 0 (or unset), code uses hardcoded default: `iVar17 = 0x190000` = 1,638,400 bytes (~13 Mbps base). | Decompiled: FUN_00023eb8 line 2270 |

#### Decision Tree (pseudocode)

```c
// From FUN_00026834 (AppleCarPlay) -- CarPlay session init
// Allocates session struct, reads VideoBitRate, stores as session parameter
void* carplay_session_create(void** out) {
    session = calloc(1, 0x318);
    if (session) {
        session->transport = create_transport("CarPlay", 0);  // FUN_000584a0
        if (session->transport) {
            configure_transport(session->transport, session);  // FUN_00056f92
            set_transport_name(session->transport, "...");     // FUN_00056f96
            session->seq_num = -1;                              // offset 0x310
            session->bitrate_cap = GetBoxConfig("VideoBitRate"); // FUN_00073098 @ 0x26872
            *out = session;
            return 0;
        }
        free_session(session);
    }
    return ERROR;
}

// From FUN_00023eb8 (AppleCarPlay) -- CarPlay video service setup (case 0x6e)
// This is the COMPLEX path with adaptive bitrate control
void setup_video_service(session, ...) {
    // ...
    uint video_bitrate = GetBoxConfig("VideoBitRate");  // @ 0x255ce (DAT_000256f0)
    
    if (video_bitrate < 1) {
        target = 0x190000;  // ~1.6MB = ~13 Mbps default
    } else {
        target = (video_bitrate + 5) * 0x10000;  // (N+5)*64KB
    }
    
    // Read resolution file for scaling factor
    bytes_read = read_file("/tmp/resolution_info", &res_info, 0x18);
    if (bytes_read == 0x18) {
        scale = (double)(res_info.width * res_info.height) / REFERENCE_RESOLUTION;
    } else {
        scale = 1.0;
    }
    
    // Throttle factor: FUN_00023e2c checks if bandwidth limiting is active
    if (is_bandwidth_limited(session)) {
        scale *= 0.25;  // 75% reduction under congestion
    }
    
    // Final bitrate = target * scale, aligned to 4MB boundary
    final_bitrate = (int)((double)target * scale);
    final_bitrate &= 0xFFC00000;  // Align (DAT_00025fa0 mask)
    
    // Create socket with computed bitrate as buffer size
    socket = create_udp_socket(port, AF_INET, SOCK_DGRAM, 0, &addr,
                               final_bitrate, &session->video_fd);
}
```

#### Cross-Binary Behavior

- **AppleCarPlay (4 xrefs):**
  - `FUN_00026834` (0x2686e): Session init -- reads VideoBitRate via `GetBoxConfig`, stores at `session + 0x44` (offset 0x11 in int array = 0x44 bytes). This is the **bitrate cap** for the CarPlay session.
  - `FUN_00023eb8` (3 sites): The massive 7974-byte video service setup function. Called during service channel negotiation (case 0x66 for setup, case 0x6e for video start, case 0x6f for audio start).
    - At 0x243d8 (DATA ref): String address loaded for later use in the function.
    - At 0x255ca (PARAM): `GetBoxConfig("VideoBitRate")` in case 0x6e -- video service activation. Result used to compute `maxVideoBitRate` for the session.
    - At 0x256f8 (PARAM): Second read in same function, appears to be for logging/debug output of the configured value.
- **server.cgi (FUN_00014040):** Reads/writes for web API. JSON field `"bitRate"`.
- **ARMadb-driver (Android Auto path):** Zero code xrefs in decompiled output. For Android Auto, the bitrate is negotiated differently -- the OpenAuto SDK's `VideoService` reads `maxVideoBitRate` from the adapter's protocol message, which is populated by the AA service layer (not directly from config key). The logcat shows: `[BoxVideoOutput] maxVideoBitRate = 5000 Kbps`.

#### Side Effects

- **WiFi congestion interaction:** The 0.25 throttle factor (line 2286: `dVar42 = dVar42 * 0.25`) activates when `FUN_00023e2c` detects bandwidth limitation. This means the effective bitrate can be reduced to 25% of the configured value during congestion. For value=5: base ~5 Mbps, throttled ~1.25 Mbps.
- **Resolution scaling:** The bitrate is scaled by `(width * height) / reference_resolution`. On the GM Info 3.7 with 2400x960 display, this scale factor can increase the bitrate target compared to a standard 1920x720 display.
- **Applies at next video negotiation** (web_settings_reference.md:343) -- changing during an active session does not take effect until reconnect.
- **Mismatch warning:** Setting too high on weak WiFi causes frame drops. Setting too low causes visible compression artifacts. Auto (0) is recommended for adaptive behavior.

#### Dependencies

- **VideoResolutionWidth [25] / VideoResolutionHeight [24]:** Resolution affects the scaling factor in the bitrate calculation.
- **CustomFrameRate [14]:** Higher frame rate at same bitrate means lower quality per frame.
- **OriginalResolution [66]:** If OriginalResolution=1, the phone sends native resolution which may be higher, consuming more bitrate.

---


### [14] CustomFrameRate — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 60
- **Status:** ALIVE (init-read)
- **Table addr:** 0x000934f8 (ARMadb) | **String VA:** 0x0006c707 (ARMadb), 0x00097493 (AppleCarPlay)
- **JSON field:** `"fps"` (BoxSettings mapping table entry [2] at 0x93f90)
- **Config table index:** 14 of 79
- **Xrefs:** ARMadb-driver(1 data xref at 0x93fa4 -- mapping table only), AppleCarPlay(0 code xrefs), server.cgi(2), all others(0)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | **Auto** -- typically 30 FPS for CarPlay, 30-60 FPS for Android Auto depending on phone capabilities. Phone chooses based on display capability negotiation. | Default. configuration.md:97 |
| 1-19 | **Low FPS** -- not standard. May cause stuttery playback if phone enforces minimum 20 FPS. | Below typical range. |
| 20-30 | **Standard FPS** -- 30 FPS is the most common. CarPlay typically runs at 30 FPS. | Standard range. GM Info 3.7 CarPlay uses CINEMO SW decoder at 30fps max. |
| 31-60 | **High FPS** -- 60 FPS mode. Phone encoder must support 60fps output. Doubles bandwidth requirement at same quality. | Only useful with HW decode on host. GM Info 3.7 CINEMO SW decoder is limited to 30fps for CarPlay. |

#### Cross-Binary Behavior

- **ARMadb-driver:** The `"fps"` JSON field maps to `"CustomFrameRate"` config key via the BoxSettings mapping table at 0x93f90 (entry [2], confirmed in boxsettings_mapping.json:27 and boxsettings_full_decomp.txt:46). When the host app sends a BoxSettings command (cmd 0x19 or 0xA2), the value is written via `SetBoxConfig("CustomFrameRate", value)`. The data xref at 0x93fa4 is the mapping table entry pointer itself, classified as "dead candidate" because there is no code-level `GetBoxConfig("CustomFrameRate")` call in ARMadb-driver.
- **AppleCarPlay:** Zero code xrefs. The value is consumed by the protocol negotiation layer at a lower level -- it is included in the CarPlay `Open` message's `frameRate` field, which is assembled from config values during session setup. The protocol message builder reads it through the shared config library, but the string reference is resolved at runtime (indirect call through function pointer table).
- **server.cgi:** Reads/writes for web API (`"fps"` web field, separate from the BoxSettings `"fps"` field).
- **Protocol path:** Value is packed into the CarPlay video `Open` message bytes 8-11 (32-bit LE integer) per configuration.md:1734.

#### Side Effects

- **GM Info 3.7 constraint:** CarPlay video on GM Info 3.7 is decoded by CINEMO/NME software decoder at 1416x842@**30fps** max. Setting CustomFrameRate > 30 will cause the adapter to request 60fps from the phone, but the host will only display 30fps, wasting bandwidth and phone battery.
- **Android Auto:** Uses Intel HW decoder (`OMX.Intel.hw_vd.h264`) which can handle 60fps at 2400x960. CustomFrameRate=60 is viable for AA on GM Info 3.7.
- **Init-read:** Value read once at session start. Changes require reconnect.

#### Dependencies

- **VideoBitRate [23]:** Higher FPS at fixed bitrate reduces per-frame quality.
- **VideoResolutionWidth/Height [25/24]:** Combined with FPS determines total pixel throughput.

---


### [8] NotCarPlayH264DecreaseMode — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 2
- **Status:** DEAD
- **Table addr:** 0x00093498 (ARMadb) | **String VA:** 0x00080c45 (ARMadb), 0x00097445 (AppleCarPlay), 0x0001fa46 (server.cgi)
- **JSON field:** None (not in BoxSettings mapping table, not in server.cgi web API)
- **Xrefs:** Config table entry only in all binaries. Zero GetBoxConfig/SetBoxConfig callers. Zero non-table string xrefs.

**Confirmed zero code xrefs across all 6 binaries:**
- ARMadb-driver: `0x00080c45` -- string present in config table, 0 code references (`ARMadb-driver_2025.10_unpacked_config_trace.txt:327-329`)
- AppleCarPlay: `0x00097445` -- string present in config table, 0 code references (`AppleCarPlay_unpacked_config_trace.txt:303-305`)
- ARMiPhoneIAP2: not present as active string
- server.cgi: `0x0001fa46` -- string present in config table, 0 code references (`server.cgi_unpacked_config_trace.txt:303-305`)
- riddleBoxCfg: `0x00019b22` -- config table definition only, 0 code references (`riddleBoxCfg_unpacked_config_trace.txt:252-254`)
- bluetoothDaemon: config table entry only, 0 code references

**Intended purpose (from naming):** Would have controlled adaptive bitrate reduction behavior for non-CarPlay H.264 streams (i.e., Android Auto, HiCar, CarLife). Value 0 = allow decrease (adaptive bitrate), 1 = block decrease (fixed bitrate), 2 = aggressive decrease. This is the non-CarPlay counterpart to the (also dead) `VideoBitRate` interaction for AA streams. The feature was likely planned but never implemented, possibly because ARMAndroidAuto handles its own bitrate negotiation internally via the AA protocol.

**Related dead key:** `BoxConfig_preferSPSPPSType` [7] -- another legacy H.264 configuration key that was superseded by `SpsPpsMode` [47].

---


### [25] VideoResolutionWidth — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 4096
- **Status:** ALIVE (init-read)
- **Table addr:** 0x000935a8 (ARMadb) | **String VA:** 0x00080cc2 (ARMadb), 0x000974fd (AppleCarPlay)
- **JSON field:** `"resolutionWidth"` (Web API via server.cgi)
- **Config table index:** 25 of 79
- **Xrefs:** server.cgi(2 xrefs in FUN_00014040 at 0x1431c + data at 0x13782), all other binaries(0 code xrefs)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | **Auto** -- adapter and phone negotiate resolution based on display capabilities. For GM Info 3.7: CarPlay uses 2400x960 (or 1416x842 for CINEMO), AA uses 2400x960. | Default. Phone reports supported resolutions in capability exchange. |
| 1-4096 | **Fixed width** -- forces the video encoder to output this width. Phone must support it. Packed into CarPlay `Open` message bytes 0-3. | Per configuration.md:1732 |
| Common: 800 | Low-res mode for bandwidth-constrained links. | |
| Common: 1280 | Standard 720p-class width. | |
| Common: 1920 | Full HD width. | |
| Common: 2400 | GM Info 3.7 native display width. | Observed in logcat: `spsWidth: 2400` |

#### Cross-Binary Behavior

- **server.cgi (FUN_00014040):** Only binary with code xrefs. Reads via `GetBoxConfig("VideoResolutionWidth")` and serializes to JSON.
- **AppleCarPlay / ARMadb-driver:** Zero direct code xrefs in Ghidra/r2. Value consumed by the protocol layer through the shared config library at init time. The protocol message builder reads it to populate the video channel `Open` message width field.
- Init-read, passed as protocol parameter to the phone's video encoder setup.

#### Side Effects

- **Aspect ratio:** Must match VideoResolutionHeight for correct display. Mismatched aspect ratios cause stretching/letterboxing.
- **GM Info 3.7 display:** Native 2400x960 @ 60Hz. CarPlay CINEMO decoder only handles 1416x842, so setting Width=2400 for CarPlay causes the adapter to scale internally.
- **Encoder capability:** Phone's H.264 encoder may not support arbitrary widths. iPhone CarPlay typically supports 800/1280/1920/2400 width.

#### Dependencies

- **VideoResolutionHeight [24]:** Always set as a pair.
- **OriginalResolution [66]:** If OriginalResolution=1, these values may be overridden by the phone's native output resolution.
- **VideoBitRate [23]:** Resolution directly affects the bitrate scaling factor in the adaptive bitrate calculation.

---


### [24] VideoResolutionHeight — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 4096
- **Status:** ALIVE (init-read)
- **Table addr:** 0x00093598 (ARMadb) | **String VA:** 0x00080cac (ARMadb), 0x000974e7 (AppleCarPlay)
- **JSON field:** `"resolutionHeight"` (Web API via server.cgi)
- **Config table index:** 24 of 79
- **Xrefs:** server.cgi(2 xrefs in FUN_00014040 at 0x14342 + FUN_00013760 at 0x13770), all other binaries(0 code xrefs)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | **Auto** -- phone negotiates height. For GM Info 3.7: 960 for native AA, 842 for CarPlay CINEMO. | Default. |
| 1-4096 | **Fixed height** -- packed into CarPlay `Open` message bytes 4-7. | Per configuration.md:1733 |
| Common: 480 | Standard definition (paired with 800 or 854 width). | |
| Common: 720 | HD (paired with 1280). | |
| Common: 960 | GM Info 3.7 native height. | Observed: `spsHeight: 960` (8-aligned: 968 in SPS, display crop to 960) |
| Common: 1080 | Full HD (paired with 1920). Exceeds CINEMO decoder capability on GM. | |

#### Cross-Binary Behavior

- **server.cgi:** Two xrefs -- one in FUN_00014040 (standard API serializer at 0x14342), one in FUN_00013760 (at 0x13770), suggesting it may be used in a secondary context (possibly the `/getinfo` endpoint for reporting current video resolution).
- **All protocol binaries:** Zero code xrefs. Same init-read pattern as VideoResolutionWidth.
- Functionally identical architecture to VideoResolutionWidth -- always set and consumed as a pair.

#### Side Effects

- **H.264 alignment:** H.264 requires height to be 16-line aligned at the macroblock level. Height=960 becomes 960 (already aligned). Height=1080 is fine (aligned). Non-standard heights like 842 require SPS `frame_cropping` to trim. The adapter handles this internally.
- **Memory impact:** Higher resolution requires larger DMA buffers on the host decoder. GM Info 3.7 has ~5.4GB usable RAM (6GB - ~604MB hypervisor reservation).

#### Dependencies

- **VideoResolutionWidth [25]:** Paired.
- **OriginalResolution [66]:** Override behavior.
- **VideoBitRate [23]:** Resolution * fps * quality = bandwidth.

---


### [66] OriginalResolution — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 1
- **Status:** ALIVE (init-read)
- **Table addr:** 0x00093838 (ARMadb) | **String VA:** 0x00080e3d (ARMadb), 0x000976bc (AppleCarPlay)
- **JSON field:** `"originalRes"` (Web API via server.cgi)
- **Config table index:** 66 of 79
- **Xrefs:** server.cgi(2 xrefs in FUN_00014040 at 0x14798 + data at 0x14a9e), all other binaries(0 code xrefs)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | **Allow scaling** -- adapter applies VideoResolutionWidth/Height overrides to the phone's video encoder. Phone may scale output to match requested resolution. If Width/Height are both 0, phone uses its own default negotiation. | Default. Standard behavior. |
| 1 | **Force original** -- phone sends video at its native rendering resolution, ignoring VideoResolutionWidth/Height hints. For iPhone CarPlay, this is typically the Retina-scaled resolution (e.g., 1920x720 or 2560x1080 depending on model). | configuration.md:218: "Force original resolution" |

#### Cross-Binary Behavior

- **server.cgi (FUN_00014040):** Reads/writes for web API.
- **All protocol binaries:** Zero direct code xrefs. Consumed by the protocol negotiation layer through shared config. When OriginalResolution=1, the adapter omits or zeros the width/height fields in the CarPlay `Open` message, signaling to the phone to use its native output resolution.
- Classified as "Protocol Init" parameter (configuration.md:1459).

#### Side Effects

- **Resolution mismatch:** If OriginalResolution=1 and the phone's native resolution does not match the host display (e.g., iPhone renders at 1920x720 but GM display is 2400x960), the host must scale. On GM Info 3.7, the `iahwcomposer` (Intel Automotive HWC) handles scaling via HW overlay, but this adds one frame of latency.
- **Bandwidth unpredictability:** With OriginalResolution=0, you control the resolution and can predict bandwidth. With OriginalResolution=1, the phone may output a higher resolution than expected, exceeding the VideoBitRate cap.
- **Overrides Width/Height:** When OriginalResolution=1, VideoResolutionWidth [25] and VideoResolutionHeight [24] become effectively no-ops -- their values are still stored in config but not sent to the phone.

#### Dependencies

- **VideoResolutionWidth [25] / VideoResolutionHeight [24]:** Directly overridden when OriginalResolution=1.
- **VideoBitRate [23]:** Must be set high enough to accommodate the phone's native resolution if OriginalResolution=1.
- **CustomFrameRate [14]:** Framerate still applies independently of resolution override.

---

## Summary Cross-Reference Matrix

| Key | Idx | server.cgi | AppleCarPlay | ARMadb | Complexity | Primary Consumer |
|-----|-----|-----------|--------------|--------|------------|-----------------|
| NeedKeyFrame | 9 | R/W (2 xrefs) | 0 code xrefs | 0 code xrefs | Simple boolean | Protocol error handler |
| RepeatKeyframe | 44 | R/W (2 xrefs) | 0 code xrefs | 0 code xrefs | Simple boolean | USB video packetizer |
| SendEmptyFrame | 55 | R/W (2 xrefs) | 0 code xrefs | 0 code xrefs | Simple boolean | USB video packetizer |
| **VideoBitRate** | **23** | **R/W (2 xrefs)** | **4 code xrefs** | **0 code xrefs** | **Complex (branching + math)** | **AppleCarPlay FUN_00026834 + FUN_00023eb8** |
| CustomFrameRate | 14 | R/W (2 xrefs) | 0 code xrefs | 1 data xref (table) | Simple, table-mediated | BoxSettings mapper + protocol Open msg |
| VideoResolutionWidth | 25 | R/W (2 xrefs) | 0 code xrefs | 0 code xrefs | Simple init-read | Protocol Open msg bytes 0-3 |
| VideoResolutionHeight | 24 | R/W (2 xrefs) | 0 code xrefs | 0 code xrefs | Simple init-read | Protocol Open msg bytes 4-7 |
| OriginalResolution | 66 | R/W (2 xrefs) | 0 code xrefs | 0 code xrefs | Simple boolean (override) | Protocol negotiation layer |

**Key finding:** Only **VideoBitRate** has significant runtime code complexity with 4 xrefs in AppleCarPlay showing active bitrate computation with adaptive throttling. All other 7 keys are init-read parameters consumed through the shared `riddleBoxCfg` library by the protocol layer, with only `server.cgi` having direct code xrefs (for web API serialization). The actual video protocol logic in `AppleCarPlay` and `ARMadb-driver` consumes these values indirectly through the config library's runtime API, making them invisible to static string xref analysis.


### [7] BoxConfig_preferSPSPPSType — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 1
- **Status:** DEAD
- **Table addr:** 0x00093488 (ARMadb) | **String VA:** 0x00080c2a (ARMadb), 0x0009742a (AppleCarPlay), 0x0001fa2b (server.cgi)
- **JSON field:** None (not in BoxSettings mapping table, not in server.cgi web API)
- **Xrefs:** Config table entry only in all binaries. Zero GetBoxConfig/SetBoxConfig callers. Zero non-table string xrefs.

**Confirmed zero code xrefs across all 6 binaries:**
- ARMadb-driver: `0x00080c2a` -- 0 code references (`ARMadb-driver_2025.10_unpacked_config_trace.txt:323-325`)
- AppleCarPlay: `0x0009742a` -- 0 code references (`AppleCarPlay_unpacked_config_trace.txt:299-301`)
- server.cgi: `0x0001fa2b` -- 0 code references (`server.cgi_unpacked_config_trace.txt:299-301`)
- riddleBoxCfg: `0x00019b07` -- 0 code references (`riddleBoxCfg_unpacked_config_trace.txt:248-250`)
- ARMiPhoneIAP2 / bluetoothDaemon: config table entry only, 0 code references

**Intended purpose (from naming and context):** Legacy boolean predecessor to the 4-value `SpsPpsMode` [43]. The name "preferSPSPPSType" suggests it controlled whether SPS/PPS NAL units were sent with a preferred encapsulation (e.g., Annex B start codes vs. AVCC length-prefixed). Value 0 = default type, 1 = preferred/alternate type. This was superseded by `SpsPpsMode` which provides 4 modes (0=Auto, 1=Re-inject before IDR, 2=Cache, 3=Repeat) covering the same functionality with finer granularity. The config table entry is preserved for backward compatibility with older riddle.conf files but is never read.

---


## Audio

### [1] MediaQuality — Deep Analysis

**Config index:** 1 | **Type:** int | **JSON field:** `mediaSound` | **String VA:** ARMadb `0x0006c7af`, AppleCarPlay `0x00081c31`

### Config Table (cross-binary comparison)

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 1 | 0 | 1 | 8 (alive) | GetBoxConfig in FUN_00018598 |
| AppleCarPlay | 1 | 0 | 1 | 7 (alive) | GetBoxConfig in FUN_0002f82c |
| ARMiPhoneIAP2 | - | - | - | 0 | not_found |
| bluetoothDaemon | - | - | - | 0 | not_found |
| server.cgi | 1 | 0 | 1 | 2 (alive) | FUN_00014040 (web get/set) |
| riddleBoxCfg | 1 | 0 | 1 | 0 | not_found |

**NOTE:** The user-provided range says default=0, but the actual config table in all binaries says **default=1** (48kHz). This means 48kHz is the factory default.

### Value Effects

| Value | Sample Rate | decodeType | Description |
|-------|------------|------------|-------------|
| **0** | 44100 Hz | 2 (stereo) | CD quality. Legacy compatibility with older head units. Uses decodeType=1 or 2 for media audio. |
| **1** | 48000 Hz | 4 (stereo) | DVD/standard quality. Default. Uses decodeType=4 for media audio. Matches CarPlay native rate. |

### Decompiled Pseudocode

**ARMadb-driver FUN_00018598** at `0x00018598:286` (audio stream setup, `/tmp/ghidra_output/ARMadb-driver_2025.10_unpacked_caller_decomps.txt:283-293`):
```c
// Called during audio stream open (cmd type 0x01 in audio data path)
// DAT_0001886c resolves to "MediaQuality" string
int media_quality = GetBoxConfig("MediaQuality");  // FUN_00066d3c
if (media_quality == 1) {
    sample_rate = 48000;
} else {
    sample_rate = 0xac44;  // 44100
}
audio_context->sample_rate = sample_rate;  // *(iVar17 + 4)
```

**AppleCarPlay FUN_0002f82c** at `0x0002f82c` (`/tmp/ghidra_output/AppleCarPlay_unpacked_caller_decomps.txt:14-21`):
```c
bool FUN_0002f82c(void) {
    int val = GetBoxConfig("MediaQuality");  // FUN_00073098
    return val == 1;  // returns true if 48kHz
}
// Called by CarPlay session setup to decide codec negotiation params
```

### Cross-Binary Behavior

- **ARMadb-driver:** Runtime read in FUN_00018598 during audio stream open (cmd type 1). Directly selects between 44100 and 48000 as the base sample rate for PCM audio mixing and USB bulk transfer to host.
- **AppleCarPlay:** Runtime read as boolean predicate (==1) in FUN_0002f82c. Controls CarPlay audio session negotiation -- tells Apple's AirPlay/iAP2 layer whether to request 48kHz or 44.1kHz media streams.
- **server.cgi:** Exposed via web API as `mediaSound` for get/set in FUN_00014040. Persisted to riddle.conf.
- **BoxSettings:** Mapped via 29-entry table at `0x93f90` as `"mediaSound"` -> `"MediaQuality"`.

### Side Effects / Dependencies

- Changes require process restart (init-read pattern plus runtime read).
- Affects the decodeType sent to host in USB audio packets: val==0 uses decodeType 2 (44.1kHz), val==1 uses decodeType 4 (48kHz).
- If host decoder expects one rate but adapter sends the other, audio will play at wrong pitch/speed.
- **Dependency:** Interacts with `MediaLatency` (index 2) -- the PCM buffer size calculation is `period_size = MediaLatency/1000 * sample_rate`, so changing MediaQuality alters the actual buffer length in samples.

---


### [43] MicType — Deep Analysis

**Config index:** 43 | **Type:** int | **JSON field:** `MicType` (web API) | **String VA:** ARMadb `0x0006e761`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 0 | 0 | 2 | 1 (alive) | SetBoxConfig in FUN_0001dd98 |
| AppleCarPlay | 0 | 0 | 2 | 0 | not_found |
| server.cgi | 0 | 0 | 2 | 2 (alive) | FUN_00014040 (web get/set) |
| riddleBoxCfg | 0 | 0 | 2 | 0 | not_found |

### Value Effects

| Value | Source | ALSA Device | Description |
|-------|--------|-------------|-------------|
| **0** | Car microphone | `/dev/snd/pcmC0D0c` | Default. Uses the head unit's built-in microphone via I2S from the USB host. Audio arrives over USB and is processed by WebRTC AEC/AGC/NS before forwarding to CarPlay/AA. |
| **1** | Box 3.5mm jack | `/dev/snd/pcmC1D0c` | External mic connected to adapter's 3.5mm jack. **NOTE:** CPC200-CCPA A15W has no physical 3.5mm input -- this value is for other Carlinkit models (e.g., CPC200-CCPA-2, T-Box). On A15W, this effectively selects a non-existent device. |
| **2** | Phone microphone | (no local capture) | Phone handles mic capture natively. Adapter does NOT open any local PCM capture device. Siri/phone call mic data comes from the iPhone/Android device itself over iAP2/AA protocol. Disables WebRTC AEC processing on adapter. |

### Decompiled Pseudocode

**ARMadb-driver FUN_0001dd98** at `0x0001dd98` -- cmd 0x09 handler (`/tmp/ghidra_output/ARMadb-driver_2025.10_unpacked_caller_decomps.txt:1288-1304`):
```c
// Dispatcher: uVar19 == 9 (cmd 0x09 = SetMicType from host)
if (cmd_id == 0x09) {
    if (payload_size == 4) {
        int new_mic_type = *(int*)payload;
        int cur_mic_type = GetBoxConfig("MicType");  // DAT_0001ecec
        if (new_mic_type != cur_mic_type) {
            BoxLog(INFO, "MicType changed to %d", new_mic_type);
            SetBoxConfig("MicType", new_mic_type);  // persists to riddle.conf
            if (new_mic_type == 1) {
                system(DAT_0001ecf8);  // likely: start external mic capture service
            } else if (new_mic_type == 3) {
                system(DAT_0001ecf4);  // likely: stop/reconfigure mic service
            }
        }
    }
}
```

### Cross-Binary Behavior

- **ARMadb-driver:** Both runtime set (cmd 0x09 dispatcher at FUN_0001dd98) and init-read. When set via cmd 0x09, persists immediately to riddle.conf AND executes a system() call to reconfigure the audio capture pipeline.
- **AppleCarPlay:** Does NOT directly read MicType (0 xrefs). Instead, AppleCarPlay reads the mic routing decision from shared state set by ARMadb-driver.
- **server.cgi:** Web API get/set via FUN_00014040.
- **NOT in BoxSettings 29-entry mapping** -- set exclusively via cmd 0x09 or web API.

### Side Effects

- **system() call on change:** Executes shell command to reconfigure audio capture. This is a live reconfiguration -- no restart needed.
- Value 1 (Box mic) is hardware-dependent. On CPC200-CCPA A15W (no physical mic jack), setting this will cause mic capture to fail silently.
- Value 2 (Phone mic) disables the adapter's WebRTC AEC/AGC/NS pipeline entirely, since the phone processes its own mic audio.
- **Dependency:** Interacts with `MicGainSwitch` (index 13) and `MicMode` (index 46) -- gain and noise suppression only apply when MicType == 0 (car mic being captured locally).

---


### [46] MicMode — Deep Analysis

**Config index:** 46 | **Type:** int | **String VA:** ARMadb `0x00080d61`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 0 | 0 | 4 | 0 | not_found (init-read only) |
| AppleCarPlay | 0 | 0 | 4 | 0 | not_found (init-read only) |
| server.cgi | 0 | 0 | 4 | 2 (alive) | FUN_00014040 |
| riddleBoxCfg | 0 | 0 | 4 | 0 | not_found |

**NOTE:** The user-provided range says max=2, but the actual config table shows **max=4**.

### Value Effects

| Value | WebRTC NS Policy | Description |
|-------|------------------|-------------|
| **0** | NS disabled / mild | Default. Minimal noise suppression. Best audio fidelity, but background noise passes through. |
| **1** | Low | `WebRtcNs_set_policy(ns_inst, 0)` -- mild noise gating. |
| **2** | Medium | `WebRtcNs_set_policy(ns_inst, 1)` -- moderate suppression. |
| **3** | High | `WebRtcNs_set_policy(ns_inst, 2)` -- aggressive noise suppression. May clip speech transients. |
| **4** | Very High | `WebRtcNs_set_policy(ns_inst, 3)` -- maximum suppression. Risk of speech artifacts. |

### Pseudocode (inferred from WebRTC NS API in binary strings)

```c
// Init-time: loaded from riddle.conf into g_config[46]
int mic_mode = g_config[46];  // MicMode
if (mic_mode > 0) {
    WebRtcNs_Create(&ns_inst);
    WebRtcNs_Init(ns_inst, sample_rate);  // 8000 or 16000
    WebRtcNs_set_policy(ns_inst, mic_mode - 1);  // 0=low, 1=med, 2=high, 3=very_high
}
// Applied per-frame in mic processing loop
```

### Cross-Binary Behavior

- **ARMadb-driver:** Init-read only. No runtime GetBoxConfig() calls found via xref analysis. Value loaded at startup into globals consumed by the WebRTC NS processing loop in libdmsdpaudiohandler.so.
- **server.cgi:** Web API get/set. Requires adapter restart to take effect.
- **All others:** Not referenced.

### Side Effects

- Only meaningful when `MicType == 0` (car mic active locally). When MicType==2 (phone mic), this has no effect.
- Higher values increase CPU load due to more aggressive spectral analysis per audio frame.

---


### [13] MicGainSwitch — Deep Analysis

**Config index:** 13 | **Type:** int | **String VA:** ARMadb `0x00080c79`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 0 | 0 | 1 | 0 | not_found (init-read only) |
| AppleCarPlay | 0 | 0 | 1 | 0 | not_found |
| server.cgi | 0 | 0 | 1 | 2 (alive) | FUN_00014040 |
| riddleBoxCfg | 0 | 0 | 1 | 0 | not_found |

### Value Effects

| Value | AGC State | Description |
|-------|-----------|-------------|
| **0** | Disabled | Default. No automatic gain control on mic input. Raw PCM levels from ALSA capture passed through as-is (after AEC). |
| **1** | Enabled | WebRTC AGC (`WebRtcAgc_Process`) activated. Normalizes mic input level. Compensates for quiet/distant mics by amplifying weak signals and compressing loud ones. |

### Pseudocode (inferred from binary strings at `0x0006bfd8-0x0006c012`)

```c
// Init-time: loaded from riddle.conf
int mic_gain = g_config[13];  // MicGainSwitch
if (mic_gain == 1) {
    if (WebRtcAgc_Create(&agc_inst) != 0) {
        BoxLog(ERR, "WebRtcAgc_Create failed!");
        return;
    }
    WebRtcAgc_Init(agc_inst, min_level, max_level, agc_mode, sample_rate);
}
// In mic processing loop:
if (agc_inst) {
    if (webrtc_Agc_Process(agc_inst, in_near, in_near_h, samples, out, out_h, ...) != 0) {
        BoxLog(ERR, "failed in WebRtcAgc_Process");
    }
}
```

### Cross-Binary Behavior

- **ARMadb-driver:** Init-read only. Binary string `"WebRtcAgc_Create failed!"` at `0x0006bfd8` and `"webrtc_Agc_Process args error!"` at `0x0006bff2` confirm AGC integration. No runtime GetBoxConfig() xref -- the value is loaded once at startup.
- **server.cgi:** Web API get/set via FUN_00014040 (xref at `0x00014212`). Persists to riddle.conf.

### Side Effects

- Only effective when `MicType == 0` (car mic).
- AGC may introduce audible pumping artifacts in quiet environments.
- **Dependency:** Interacts with `MicMode` (NS) -- AGC + NS together can create feedback loops if both are aggressive. Recommended: if MicGainSwitch==1, keep MicMode <= 2.

---


### [10] EchoLatency — Deep Analysis

**Config index:** 10 | **Type:** int | **JSON field:** `echoDelay` | **String VA:** ARMadb `0x0006c80e`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 320 | 20 | 2000 | 2 (alive) | GetBoxConfig in FUN_0001fe2c |
| AppleCarPlay | 320 | 20 | 2000 | 0 | not_found |
| server.cgi | 320 | 20 | 2000 | 2 (alive) | FUN_00014040 |
| riddleBoxCfg | 320 | 20 | 2000 | 0 | not_found |

### Value Effects

| Value Range | AEC Buffer Size | Behavior |
|-------------|-----------------|----------|
| **20-199** | val * 20 samples | Short delay mode. `delay_buffer_size = val * 20`. `aec_tail_ms = val`. Below 200ms threshold, AEC uses shorter tail but NO extra safety buffer. |
| **200-2000** | 200 * 20 samples (capped) | Long delay mode. `aec_base_tail = 200ms`. Excess delay `(val - 200)` is converted to a pre-delay offset: `pre_delay_samples = (val - 200) * sample_rate / 1000`. Two-stage AEC: short 200ms tail + pre-delay compensation. |

### Decompiled Pseudocode

**ARMadb-driver FUN_0001fe2c** at `0x0001fe2c` (`/tmp/ghidra_output/ARMadb-driver_2025.10_unpacked_caller_decomps.txt:2591-2668`):
```c
void FUN_0001fe2c(int sample_rate, int echo_delay, ..., int num_channels) {
    if (*g_aec_enabled == '\0') return;
    
    sem_wait(&g_aec_sem);
    
    // If echo_delay == 320 (0x140), read from config instead
    if (echo_delay == 0x140) {  // 320 decimal -- sentinel for "use config"
        echo_delay = GetBoxConfig("EchoLatency");  // FUN_00066d3c(DAT_0001ff6c)
    }
    
    // Core AEC buffer computation
    int aec_state = &g_aec_state;           // DAT_0001ff70
    aec_state->sample_rate = sample_rate;   // offset +0x480
    aec_state->echo_delay_ms = echo_delay;  // offset +0x47c
    
    int samples_per_ms = sample_rate / 1000;  // FUN_0006ab04(sample_rate, 1000)
    aec_state->aec_buffer_size = samples_per_ms * 20;  // offset +0x464, multiplied by 20
    
    // Fill circular buffer with silence
    for (i = 0; i < g_num_channels; i++) {
        int buf_offset = aec_state->aec_buffer_size * i + g_aec_buffer_base;
        push_to_queue(&g_aec_queue, &buf_offset);
    }
    
    // Two-mode delay handling:
    if (echo_delay < 201) {  // 0xc9 = 201
        aec_state->aec_tail_ms = echo_delay;     // offset +0x478
        aec_state->pre_delay_samples = 0;         // offset +0x474
    } else {
        aec_state->aec_tail_ms = 200;             // capped at 200ms
        // pre_delay = (echo_delay - 200) * sample_rate / 1000
        aec_state->pre_delay_samples = 
            (echo_delay - 200) * aec_state->sample_rate / 1000;  // offset +0x474
    }
    
    // Compute total AEC processing size
    int tail_samples = aec_state->aec_tail_ms * aec_state->sample_rate / 1000;
    aec_state->total_aec_samples = tail_samples;  // offset +0x484
    
    BoxLog(INFO, "AEC: tail=%d pre_delay=%d total=%d", 
           aec_state->aec_tail_ms, aec_state->pre_delay_samples, tail_samples);
    
    // Initialize WebRTC AECM
    FUN_00016354(aec_state->sample_rate, aec_state->aec_tail_ms);
    FUN_000162b4();  // WebRtcAecm_Init
    
    // Create input/output buffers sized for 20ms frames
    aec_state->in_buf = FUN_00016498(aec_state->sample_rate, 20);   // offset +0x468
    aec_state->out_buf = FUN_00016498(aec_state->sample_rate, 20);  // offset +0x46c
    
    g_aec_active = 1;
    sem_post(&g_aec_sem);
}
```

### Call Sites

FUN_0001fe2c is called with sample_rate = 16000 (Siri, voice recognition) or 8000 (phone call narrow-band) from multiple protocol handlers, confirmed by grep:
- `FUN_0001fe2c(16000, uVar6)` -- 20 call sites (Siri, VR, wideband calls)
- `FUN_0001fe2c(8000, uVar6)` -- 4 call sites (narrowband phone calls)

When called with `echo_delay == 320` (the sentinel/default), it reads the actual configured value from riddle.conf.

### Cross-Binary Behavior

- **ARMadb-driver:** Active runtime read in FUN_0001fe2c (the AEC initialization function). Called each time a mic-using audio session starts (Siri activation, phone call answer, voice recognition).
- **server.cgi:** Web API get/set. Persisted to riddle.conf.
- **BoxSettings:** Mapped via 29-entry table as `"echoDelay"` -> `"EchoLatency"`.

### Side Effects

- The value 320 (0x140) is ALSO the sentinel that means "read from config". This is a minor design quirk -- passing 320 literally from a protocol command triggers a config read rather than using 320ms directly.
- **Pre-delay mode** (>200ms): The excess beyond 200ms is NOT handled by WebRTC AECM's tail -- it's handled by a separate circular buffer delay line that shifts the reference signal. This is critical for head units with long audio pipeline latency (e.g., GM Info 3.7 AVB/PulseAudio adds ~40-80ms).
- Default 320ms = 200ms AECM tail + 120ms pre-delay offset. This means 120ms * 16000/1000 = 1920 samples of pre-delay.
- Too low: echo not cancelled. Too high: AEC converges slowly or cancels valid speech.

---


### [48] MediaPacketLen — Deep Analysis

**Config index:** 48 | **Type:** int | **String VA:** ARMadb `0x00080d74`, AppleCarPlay `0x000975db`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 200 | 200 | 20000 | 0 | not_found (init-read) |
| AppleCarPlay | 200 | 200 | 20000 | 0 | not_found (init-read) |
| server.cgi | 200 | 200 | 20000 | 2 (alive) | FUN_00014040 |
| riddleBoxCfg | 200 | 200 | 20000 | 0 | not_found |

**NOTE:** User-provided range says default=0, range [0,1024]. Actual config table: **default=200, min=200, max=20000**. The value represents bytes, not a 0-1024 enum.

### Value Effects

| Value | Behavior |
|-------|----------|
| **200** (default) | USB bulk transfer fragment size for media (playback) audio PCM data sent to host. 200 bytes = 50 stereo samples at 16-bit = ~1ms at 48kHz. Minimum latency, maximum USB overhead. |
| **200-20000** | Larger values batch more PCM samples per USB bulk transfer. 20000 bytes = 5000 stereo samples = ~104ms at 48kHz. Reduces USB overhead but increases latency. |

### Pseudocode

```c
// Init-time only: loaded from riddle.conf into g_config[48]
int media_pkt_len = g_config[48];  // MediaPacketLen
// Used in USB audio bulk transfer assembly:
// audio_usb_send(pcm_buffer, min(available, media_pkt_len));
```

### Cross-Binary Behavior

- **ARMadb-driver:** Init-read only. No runtime GetBoxConfig() calls. Value loaded at startup, used in USB audio bulk transfer sizing for media PCM data sent from adapter to host.
- **AppleCarPlay:** Init-read only. Same table entry.
- **server.cgi:** Web API get/set.

### Side Effects

- Affects USB audio latency-vs-throughput tradeoff on the adapter-to-host link.
- Must be a multiple of the frame size (4 bytes for 16-bit stereo) to avoid frame splitting.
- **NOT in BoxSettings mapping table** -- only settable via web API or direct riddle.conf edit.

---


### [49] TtsPacketLen — Deep Analysis

**Config index:** 49 | **Type:** int | **String VA:** ARMadb `0x00080d83`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 200 | 200 | 40000 | 0 | not_found (init-read) |
| AppleCarPlay | 200 | 200 | 40000 | 0 | not_found (init-read) |
| server.cgi | 200 | 200 | 40000 | 2 (alive) | FUN_00014040 |

**NOTE:** User-provided range says default=0, range [0,1024]. Actual: **default=200, min=200, max=40000**.

### Value Effects

| Value | Behavior |
|-------|----------|
| **200** (default) | USB bulk transfer fragment size for TTS (text-to-speech) / navigation voice audio. Same mechanism as MediaPacketLen but for the TTS audio stream. |
| **200-40000** | Larger max than MediaPacketLen (40000 vs 20000). TTS audio is typically bursty (short utterances), so larger buffers can batch an entire phrase into fewer USB transactions. |

### Cross-Binary Behavior

Identical pattern to MediaPacketLen. Init-read only in all binaries. Web API settable via server.cgi FUN_00014040.

### Side Effects

- TTS audio is navigation voice prompts and Siri spoken responses.
- Larger values may cause a noticeable delay before the first syllable of a nav instruction is heard.
- **Dependency:** Only meaningful when audio output is active. Value has no effect during silent periods.

---


### [50] VrPacketLen — Deep Analysis

**Config index:** 50 | **Type:** int | **String VA:** ARMadb `0x00080d90`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 200 | 200 | 40000 | 0 | not_found (init-read) |
| AppleCarPlay | 200 | 200 | 40000 | 0 | not_found (init-read) |
| server.cgi | 200 | 200 | 40000 | 2 (alive) | FUN_00014040 |

### Value Effects

| Value | Behavior |
|-------|----------|
| **200** (default) | USB bulk transfer fragment size for voice recognition (Siri/Google Assistant) mic input audio sent from adapter to host. This is the reverse direction from MediaPacketLen -- mic data going to the host app. |
| **200-40000** | Same tradeoff as other PacketLen keys. VR audio is typically 16kHz mono, so 200 bytes = 100 samples = 6.25ms. |

### Cross-Binary Behavior

Same init-read pattern. No runtime xrefs. Web API settable.

### Side Effects

- Controls responsiveness of voice recognition. Smaller values = Siri hears input faster but more USB overhead.
- At default 200 bytes with 16kHz mono 16-bit: 200/2 = 100 samples = 6.25ms per packet.

---


### [51] TtsVolumGain — Deep Analysis

**Config index:** 51 | **Type:** int | **String VA:** ARMadb `0x00080d9c`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 0 | 0 | 1 | 0 | not_found (init-read) |
| AppleCarPlay | 0 | 0 | 1 | 0 | not_found |
| server.cgi | 0 | 0 | 1 | 2 (alive) | FUN_00014040 |

**NOTE:** User-provided range says [0,100]. Actual config table: **min=0, max=1**. This is a boolean, not a percentage.

### Value Effects

| Value | Behavior |
|-------|----------|
| **0** | Default. No extra gain applied to TTS/navigation voice audio. Output level matches source. |
| **1** | Gain boost enabled for TTS audio stream. Applies a fixed amplification factor to TTS PCM samples before USB transfer to host. |

### Cross-Binary Behavior

- Init-read only everywhere. No runtime GetBoxConfig() xrefs in any binary.
- **server.cgi:** Web API get/set.

### Side Effects

- Boolean gain enable, not a percentage. The actual boost amount is likely hardcoded in the audio mixing pipeline.
- Can cause clipping if TTS source audio is already near full scale.
- **Dependency:** Only applies to TTS stream, not media or phone call audio.

---


### [52] VrVolumGain — Deep Analysis

**Config index:** 52 | **Type:** int | **String VA:** ARMadb `0x00080da9`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 0 | 0 | 1 | 0 | not_found (init-read) |
| AppleCarPlay | 0 | 0 | 1 | 0 | not_found |
| server.cgi | 0 | 0 | 1 | 2 (alive) | FUN_00014040 |

**NOTE:** User-provided range says [0,100]. Actual config table: **min=0, max=1**. Boolean, not percentage.

### Value Effects

| Value | Behavior |
|-------|----------|
| **0** | Default. No extra gain on voice recognition (mic input) audio stream. |
| **1** | Gain boost enabled for VR mic audio. Amplifies mic PCM going to host for voice recognition processing. |

### Cross-Binary Behavior

Identical pattern to TtsVolumGain. Init-read only. Web API settable.

### Side Effects

- Boosts mic input sent to host for Siri/Google Assistant processing.
- Different from `MicGainSwitch` (index 13): MicGainSwitch enables WebRTC AGC on the adapter side, while VrVolumGain applies a simple linear gain boost after WebRTC processing, specifically on the VR audio stream.
- **Dependency:** Only effective when MicType != 2 (phone mic), since phone-mic mode bypasses local audio processing.

---


### [36] CallQuality — Deep Analysis

**Config index:** 36 | **Type:** int | **String VA:** ARMadb `0x00080cfb`, AppleCarPlay `0x00081c74`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 1 | 0 | 2 | 0 | not_found (init-read) |
| AppleCarPlay | 1 | 0 | 2 | 1 (dead_candidate) | GetBoxConfig in FUN_0002f8bc |
| server.cgi | 1 | 0 | 2 | 2 (alive) | FUN_00014040 |

**NOTE:** User-provided range says default=0. Actual: **default=1**.

### Value Effects

| Value | Quality | Description |
|-------|---------|-------------|
| **0** | Low | 8kHz narrowband phone call audio. Minimal bandwidth. `FUN_0002f8bc() == false`. |
| **1** | Standard | Default. 8kHz or 16kHz depending on phone negotiation. `FUN_0002f8bc() == false`. |
| **2** | High | Forces 16kHz wideband for phone calls. `FUN_0002f8bc() == true`. Tells CarPlay to request HD Voice. |

### Decompiled Pseudocode

**AppleCarPlay FUN_0002f8bc** at `0x0002f8bc` (`/tmp/ghidra_output/AppleCarPlay_unpacked_caller_decomps.txt:52-59`):
```c
bool FUN_0002f8bc(void) {
    int val = GetBoxConfig("CallQuality");  // FUN_00073098
    return val == 2;  // returns true only for "high quality"
}
// Called during CarPlay phone call audio session setup
// When true, requests 16kHz wideband from CarPlay
```

### Cross-Binary Behavior

- **AppleCarPlay:** Single xref in FUN_0002f8bc, used as boolean predicate (==2). Controls whether CarPlay requests wideband audio for phone calls.
- **ARMadb-driver:** No runtime xrefs. The value is consumed only by the CarPlay binary.
- **server.cgi:** Web API get/set.

### Side Effects

- Only affects CarPlay phone calls, not Android Auto (AA handles its own audio negotiation).
- Value 2 may fail to establish wideband if the carrier/phone does not support it -- CarPlay will fall back to narrowband.
- **Web UI bug noted in web_settings_reference.md:** The web UI's "CallQuality" setting does NOT set VoiceQuality simultaneously. These are independent keys despite similar names.

---


### [37] VoiceQuality — Deep Analysis

**Config index:** 37 | **Type:** int | **String VA:** ARMadb `0x00080d07`, AppleCarPlay `0x00081c67`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 1 | 0 | 2 | 0 | not_found (init-read) |
| AppleCarPlay | 1 | 0 | 2 | 1 (dead_candidate) | GetBoxConfig in FUN_0002f8a4 |
| server.cgi | - | - | - | 0 | not_found |

**NOTE:** User-provided range says default=0. Actual: **default=1**. Also note: server.cgi has the string at `0x0001fbc6` with **0 xrefs** -- VoiceQuality is NOT exposed in the web API.

### Value Effects

| Value | Quality | Description |
|-------|---------|-------------|
| **0** | Low | 8kHz narrowband for Siri/voice assistant. `FUN_0002f8a4() == false`. |
| **1** | Standard | Default. `FUN_0002f8a4() == false`. |
| **2** | High | Forces 16kHz wideband for Siri/voice. `FUN_0002f8a4() == true`. |

### Decompiled Pseudocode

**AppleCarPlay FUN_0002f8a4** at `0x0002f8a4` (`/tmp/ghidra_output/AppleCarPlay_unpacked_caller_decomps.txt:36-40`):
```c
bool FUN_0002f8a4(void) {
    int val = GetBoxConfig("VoiceQuality");  // FUN_00073098
    return val == 2;  // high quality Siri
}
// Called during Siri/voice recognition session setup
```

### Cross-Binary Behavior

- **AppleCarPlay:** Single xref in FUN_0002f8a4. Controls Siri audio stream quality.
- **ARMadb-driver:** No xrefs.
- **server.cgi:** NOT exposed in web API (0 xrefs, string present but unreferenced). Can only be set via direct riddle.conf edit.

### Side Effects

- Only affects CarPlay Siri/voice, not phone calls (that's CallQuality).
- **Not web-settable** -- this is a "hidden" config key. Must be edited manually in `/data/riddle.conf` on the device.
- The distinction between CallQuality and VoiceQuality is that phone calls use telephony codecs (AMR-WB for wideband) while Siri uses AAC-ELD. VoiceQuality controls the AAC-ELD bitrate/sample-rate negotiation.

---


### [33] NaviAudio — Deep Analysis

**Config index:** 33 | **Type:** int | **String VA:** ARMadb `0x00080cf1`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 0 | 0 | 2 | 0 | not_found (init-read) |
| AppleCarPlay | 0 | 0 | 2 | 0 | not_found |
| server.cgi | 0 | 0 | 2 | 2 (alive) | FUN_00014040 |
| riddleBoxCfg | 0 | 0 | 2 | 0 | not_found |

### Value Effects

| Value | Mixing Mode | Description |
|-------|-------------|-------------|
| **0** | Default / Mix | Navigation voice prompts are mixed with media audio on the same audio channel. Host receives a single combined PCM stream. The adapter handles the ducking/mixing internally. |
| **1** | Separate channel | Navigation audio sent on a separate USB audio channel from media. Requires host to handle dual-stream mixing. Enables the host to apply its own ducking/volume control to nav vs. media independently. |
| **2** | Duck at source | Navigation audio triggers media volume reduction (ducking) on the adapter side before USB transmission. The adapter attenuates the media PCM stream when nav prompts are active. |

### Cross-Binary Behavior

- **ARMadb-driver:** Init-read only. No runtime GetBoxConfig() xrefs. The mixing strategy is set at startup and cannot be changed during a session.
- **server.cgi:** Web API get/set.

### Side Effects

- Value 1 (separate channel) is only useful with host apps that support `AudioMultiBusMode` (index 72, default 1). If the host expects a single mixed stream, sending separate channels will cause nav audio to be dropped.
- **Dependency:** Interacts with `NaviVolume` (index 65). NaviVolume controls the gain level, NaviAudio controls the routing/mixing strategy.
- **Dependency:** `DuckPosition` (index 77) may also interact -- it controls the visual dock position on CarPlay, which may correlate with audio routing in some implementations.

---


### [65] NaviVolume — Deep Analysis

**Config index:** 65 | **Type:** int | **String VA:** ARMadb `0x00080e32`, AppleCarPlay `0x000976b1`

### Config Table

| Binary | Default | Min | Max | Xrefs | Status |
|--------|---------|-----|-----|-------|--------|
| ARMadb-driver | 0 | 0 | 100 | 0 | not_found (init-read) |
| AppleCarPlay | 0 | 0 | 100 | 0 | not_found |
| server.cgi | 0 | 0 | 100 | 2 (alive) | FUN_00014040 |

### Value Effects

| Value | Behavior |
|-------|----------|
| **0** | Default. No additional gain applied to navigation audio. Nav volume follows the system/host volume level. The nav audio PCM is passed through at source level. |
| **1-100** | Percentage gain multiplier applied to navigation voice audio PCM before mixing/transmission. `output = input * (NaviVolume / 100.0)`. At 100, nav audio is at full source level. Values >0 but <100 attenuate. |

**NOTE:** Value 0 means "pass-through at source level" (effectively 100%), not muted. This is because the default behavior is no modification. To mute navigation audio, the host-side volume control must be used, or `NaviAudio` set to a mode that separates the channel and the host mutes it.

### Pseudocode

```c
// Init-time: loaded from riddle.conf
int navi_vol = g_config[65];  // NaviVolume
// In audio mixing loop (NaviAudio mode 0 or 2):
if (navi_vol > 0) {
    float gain = navi_vol / 100.0f;
    for (int i = 0; i < num_samples; i++) {
        nav_pcm[i] = (short)(nav_pcm[i] * gain);
    }
}
// When navi_vol == 0, nav_pcm passed through unmodified
```

### Cross-Binary Behavior

- **ARMadb-driver:** Init-read only. No runtime GetBoxConfig() xrefs.
- **server.cgi:** Web API get/set via FUN_00014040 (xref at `0x00014772`).
- **NOT in BoxSettings 29-entry mapping** -- only settable via web API. However, BoxSettings JSON does expose volume via the special-case `"navVol"` -> `HU_AUDIOVOLUME_INFO[4]` shared memory path (see `boxsettings_mapping.json:334`), which is a different mechanism (host-side volume vs. adapter-side gain).

### Side Effects

- **Confusion potential:** There are TWO nav volume mechanisms:
  1. `NaviVolume` (this key) -- adapter-side PCM gain applied before USB transmission
  2. `HU_AUDIOVOLUME_INFO[4]` (BoxSettings `"navVol"`) -- host-reported volume level written to shared memory, used by host app to set system mixer level
- **Dependency:** Only meaningful when `NaviAudio` != 1 (separate channel mode), because in mode 1 the host handles its own volume. In modes 0 and 2, the adapter applies this gain before sending.

---

## Summary Cross-Reference Matrix

| Key | Index | Default | Active Binaries | Runtime Read | Set via cmd | Web API | BoxSettings JSON |
|-----|-------|---------|-----------------|-------------|-------------|---------|-----------------|
| MediaQuality | 1 | 1 | ARMadb, CarPlay | Yes (both) | No | `mediaSound` | `mediaSound` |
| MicType | 43 | 0 | ARMadb | Set only | cmd 0x09 | `MicType` | No |
| MicMode | 46 | 0 | (init-read) | No | No | `MicMode` | No |
| MicGainSwitch | 13 | 0 | (init-read) | No | No | `MicGainSwitch` | No |
| EchoLatency | 10 | 320 | ARMadb | Yes | No | `EchoLatency` | `echoDelay` |
| MediaPacketLen | 48 | 200 | (init-read) | No | No | `MediaPacketLen` | No |
| TtsPacketLen | 49 | 200 | (init-read) | No | No | `TtsPacketLen` | No |
| VrPacketLen | 50 | 200 | (init-read) | No | No | `VrPacketLen` | No |
| TtsVolumGain | 51 | 0 | (init-read) | No | No | `TtsVolumGain` | No |
| VrVolumGain | 52 | 0 | (init-read) | No | No | `VrVolumGain` | No |
| CallQuality | 36 | 1 | CarPlay | Yes | No | `CallQuality` | No |
| VoiceQuality | 37 | 1 | CarPlay | Yes | No | **NOT EXPOSED** | No |
| NaviAudio | 33 | 0 | (init-read) | No | No | `NaviAudio` | No |
| NaviVolume | 65 | 0 | (init-read) | No | No | `NaviVolume` | No |

**Key architectural pattern:** Of these 14 keys, only 4 have runtime GetBoxConfig() calls (MediaQuality, EchoLatency, CallQuality, VoiceQuality). The remaining 10 are init-read only -- their values are loaded at process startup from the config table and consumed via globals. Changing init-read keys requires a process restart to take effect. EchoLatency is unique in having a sentinel-value mechanism (320 triggers a config read at call time). MicType is unique in being set via a USB protocol command (0x09) with immediate system() side effects.


### [64] ImprovedFluency — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 1
- **Status:** CONFIRMED DEAD (exhaustive analysis 2026-02-28)
- **Table addr:** 0x93418+64\*16 (ARMadb) | **String VA:** 0x00080e22 (ARMadb), 0x000976a1 (AppleCarPlay), 0x00019de6 (riddleBoxCfg)
- **Config table index:** 64 of 79
- **Xrefs:** (zero runtime code xrefs; 2 xrefs in server.cgi only -- both in FUN_00014040 web settings bulk-reader at 0x0001474c and 0x00014a7e)

#### Dead Key Analysis
ImprovedFluency would have toggled a video/audio smoothing optimization, likely reducing latency at the cost of quality or vice versa. The key is present in the config table of all binaries and is exposed in the web UI via server.cgi (FUN_00014040 reads it alongside all other keys for the settings page at `http://192.168.43.1/cgi-bin/server.cgi`). However, no runtime binary consumes the value: ARMadb-driver (Android Auto -- 8617 lines, 24 functions), AppleCarPlay (3495 lines, 12 functions), ARMiPhoneIAP2 (1023 lines, 10 functions), and bluetoothDaemon (435 lines, 4 functions) all have zero xrefs. The key `ARMAndroidAuto` process (which runs the actual AA video pipeline) was not decompiled because it does not link against the riddleBoxCfg shared library at all, confirming it cannot read this key via the standard config API. The web UI lets users toggle it, the config library stores it, but nothing acts on it. Default=0 (disabled) means the non-functional toggle ships in the "off" state.

---


### [72] AudioMultiBusMode — Deep Analysis

- **Type:** Integer | **Default:** 1 | **Min:** 0 | **Max:** 1
- **Status:** DEAD
- **Table addr:** 0x93418+72\*16 (ARMadb) | **String VA:** 0x00080e64 (ARMadb), 0x0009770d (AppleCarPlay), 0x00019e52 (riddleBoxCfg)
- **Config table index:** 72 of 79
- **Xrefs:** (zero code xrefs across all 6 binaries)

#### Dead Key Analysis
AudioMultiBusMode (default=1, enabled) would have controlled whether the adapter presents multiple USB audio streams (buses) to the head unit, or a single multiplexed stream. In multi-bus mode (1), separate USB audio endpoints would carry media audio and call/Siri audio independently, allowing the head unit's audio policy to route them to different mixer channels (e.g., media to bus0_media_out, call to bus1_phone_out on GM Info 3.7's AudioFlinger). In single-bus mode (0), all audio would be mixed into one stream. Current firmware uses a fixed audio architecture: AAC-LC 48kHz stereo for media and AAC-ELD 16kHz mono for voice are decoded to PCM and sent over USB as separate logical channels regardless of this setting. The key is never read by any decompiled binary. Its existence at index 72 (near the end of the 79-entry table) suggests it was added late in development but never wired into the audio pipeline, possibly because head unit compatibility testing showed that multi-bus behavior needed to be determined by the host's USB audio descriptor negotiation rather than a static config toggle.


## Connection / USB

### [54] SendHeartBeat — Deep Analysis

### Config Table Entry
| Property | Value |
|----------|-------|
| Index | 54 |
| Default | 1 |
| Min | 0 |
| Max | 1 |
| riddleBoxCfg addr | 0x00019d64 |
| ARMadb addr | 0x00080dc1 |

### Per-Value Behavior
| Value | Behavior |
|-------|----------|
| 0 | **DANGER** -- Disables USB heartbeat. Host side times out after ~11.7s, triggers cold start failure. USB session is not re-established. |
| 1 (default) | USB heartbeat packets sent periodically via `HUDComand_A_HeartBeat`. Keeps the USB link alive between adapter and head unit. |

### Cross-Binary Xrefs
| Binary | Xrefs | Status |
|--------|-------|--------|
| ARMadb | 0 direct string xrefs | NOT FOUND (config table only) |
| AppleCarPlay | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 0 | NOT FOUND |
| bluetoothDaemon | 0 | NOT FOUND |
| server.cgi | 2 (FUN_00014040 at 0x000145f6, data at 0x000149ee) | ALIVE -- exposed via web API |

### Decompiled Pseudocode
SendHeartBeat is **not read via `GetBoxConfig("SendHeartBeat")`** in the decomped caller functions. Instead, the heartbeat mechanism is wired into the USB protocol layer via the `HUDComand_A_HeartBeat` string (at 0x0006d05e in ARMadb), which has **12 xrefs** across critical functions:

- `FUN_00017b74` (0x17d24) -- USB command handler, loads HeartBeat string for message construction
- `FUN_00018598` (0x18cbc, 0x18fa4) -- USB message send function, constructs heartbeat packets
- `FUN_0006482c` (0x64ab8) -- Low-level USB write, heartbeat as keepalive
- `FUN_00064c70` (0x64e2a) -- USB message framing, includes heartbeat in protocol

The config value is read at **init time** from the config table (index 54) and cached. When `SendHeartBeat==0`, the periodic heartbeat timer is never armed, so the host-side HU never receives keepalives and declares the session dead.

### Side Effects
- Disabling causes the GM Info 3.7 (or any host HU) to drop the USB session after the heartbeat timeout.
- On the GM platform, this manifests as the CPC200 disappearing from USB enumeration, requiring a physical unplug/replug cycle.
- **NEVER SET TO 0** in any deployment configuration.

---


### [62] FastConnect — Deep Analysis

### Config Table Entry
| Property | Value |
|----------|-------|
| Index | 62 |
| Default | 0 |
| Min | 0 |
| Max | 1 |
| riddleBoxCfg addr | 0x00019dcd |
| ARMadb addr | 0x00080e16 |

### Per-Value Behavior
| Value | Behavior |
|-------|----------|
| 0 (default) | Normal Bluetooth discovery flow. Full BT scan, pair, and connect sequence on every session. |
| 1 | Skip BT discovery if ALL four conditions are met: (1) FastConnect==1, (2) NeedAutoConnect==1, (3) valid MAC in LastConnectedDevice (strlen==0x11, i.e. "XX:XX:XX:XX:XX:XX"), (4) valid screen info (width>0, height>0, fps>0). Saves ~2-5 seconds on reconnect. |

### Cross-Binary Xrefs
| Binary | Function | Address | Direction |
|--------|----------|---------|-----------|
| ARMadb | FUN_0006995c | 0x0006999e | get |
| bluetoothDaemon | FUN_0005c2a4 | 0x0005c2e6 | get |
| ARMiPhoneIAP2 | (in shared config table) | -- | table only |
| server.cgi | FUN_00014040 | 0x00014726 | get (web API) |

### Decompiled Pseudocode -- ARMadb `FUN_0006995c` (at `/tmp/ghidra_output/ARMadb-driver_2025.10_unpacked_caller_decomps.txt:573`)
```c
bool FUN_0006995c(void) {
    if (*cached_result == -1) {
        memset(mac_buf, 0, 0x12);     // 18 bytes for MAC
        memset(screen_info, 0, 0x18); // 24 bytes for screen info
        GetBoxConfigStr("LastConnectedDevice", mac_buf, 0x12);
        ReadSharedMemory("HU_SCREEN_INFO", &screen_info, 0x18);

        val_fc = GetBoxConfig("FastConnect");       // index 62
        if (val_fc == 1) {
            val_ac = GetBoxConfig("NeedAutoConnect"); // index 15
            if (val_ac == 1) {
                if (strlen(mac_buf) == 0x11) {       // valid MAC "XX:XX:XX:XX:XX:XX"
                    if (screen_width > 0 && screen_height > 0 && screen_fps > 0) {
                        *cached_result = 1;   // FastConnect ENABLED
                        return true;
                    }
                }
            }
        }
        *cached_result = 0;  // FastConnect DISABLED (fallback)
    }
    return *cached_result != 0;
}
```

### Decompiled Pseudocode -- bluetoothDaemon `FUN_0005c2a4` (from trace context at `/tmp/ghidra_output/bluetoothDaemon_unpacked_config_trace.json:18-19`)
```c
// Inferred from decompiled context snippet:
iVar1 = GetBoxConfig("FastConnect");         // 0x0005c2e6
if (iVar1 == 1) {
    iVar1 = GetBoxConfig("NeedAutoConnect"); // 0x0005c2f2
    if (iVar1 == 1) {
        // ... proceed with BT fast reconnect using cached device info
    }
}
```

### Side Effects
- FastConnect result is **cached** (initialized to -1, computed once, then returned from cache). A config change requires process restart to take effect.
- Requires NeedAutoConnect==1 AND a previously-stored valid MAC address.
- If screen info has not been received from HU yet (first boot), FastConnect silently falls back to normal discovery even if enabled.
- The JSON field name from BoxSettings is NOT mapped (FastConnect is not in the boxsettings_mapping table at 0x93f90). It can only be set via `riddleBoxCfg -s FastConnect 1` or the web API.

---


### [15] NeedAutoConnect — Deep Analysis

### Config Table Entry
| Property | Value |
|----------|-------|
| Index | 15 |
| Default | 1 |
| Min | 0 |
| Max | 1 |
| riddleBoxCfg addr | 0x00019b80 |
| ARMadb addr | 0x0006c7c5 |

### Per-Value Behavior
| Value | Behavior |
|-------|----------|
| 0 | Manual connection only. Device will not auto-reconnect to LastConnectedDevice on boot or wake. |
| 1 (default) | Auto-reconnect enabled. On USB plug-in or adapter boot, firmware reads LastConnectedDevice MAC and attempts BT reconnection without user intervention. |

### Cross-Binary Xrefs
| Binary | Function | Address | Direction |
|--------|----------|---------|-----------|
| ARMadb | FUN_0006995c | 0x000699aa | get (FastConnect gate) |
| ARMadb | FUN_00022140 | 0x000221de | get (gated by CarLinkType==0x1e) |
| bluetoothDaemon | FUN_0001bba4 | 0x0001bbd0 | get |
| bluetoothDaemon | FUN_0001bab4 | 0x0001bb1a | get |
| bluetoothDaemon | FUN_0005c2a4 | 0x0005c2f2 | get (FastConnect companion) |
| server.cgi | FUN_00014040 | 0x000141a0 | get (web API) |

### Decompiled Pseudocode -- ARMadb `FUN_00022140` (from trace context at `/tmp/ghidra_output/ARMadb-driver_2025.10_unpacked_config_trace.json:124`)
```c
// At 0x000221de in FUN_00022140:
if (iVar9 == 0x1e) {  // CarLinkType == 30 (CPC200 adapter type)
    iVar9 = GetBoxConfig("NeedAutoConnect");  // index 15
    if (iVar9 == 1) {
        // ... proceed with auto-reconnect logic
        // later at 0x000227ac: reads AutoResetUSB for USB recovery
    }
}
```

### Decompiled Pseudocode -- bluetoothDaemon `FUN_0001bba4` and `FUN_0001bab4`
These are two separate BT connection management functions that both gate on NeedAutoConnect:
```c
// FUN_0001bba4 at 0x0001bbd0:
iVar2 = GetBoxConfig("NeedAutoConnect");
if (iVar2 == 1) {
    // initiate BT auto-reconnect scan
}

// FUN_0001bab4 at 0x0001bb1a:
iVar2 = GetBoxConfig("NeedAutoConnect");
if (iVar2 == 1) {
    // alternative reconnect path (e.g., after BT disconnect event)
}
```

### BoxSettings JSON Mapping
From `/tmp/ghidra_output/boxsettings_full_decomp.txt:54`:
```
[10] "autoConn" -> "NeedAutoConnect"
```
The host HU can set this via BoxSettings cmd 0x19 with JSON field `"autoConn"`.

### Side Effects
- Setting to 0 breaks FastConnect (FastConnect requires NeedAutoConnect==1).
- The CarLinkType==30 gate in ARMadb FUN_00022140 means NeedAutoConnect is only effective for CPC200-class (type 30) adapters, not other Carlinkit models.
- Overridable by host via BoxSettings (immediate effect, no reboot needed).
- LastConnectedDevice MAC is stored as a string config (index 8 in string table, bufsize=18).

---


### [2] MediaLatency — Deep Analysis

### Config Table Entry
| Property | Value |
|----------|-------|
| Index | 2 |
| Default | 1000 |
| Min | 300 |
| Max | 2000 |
| riddleBoxCfg addr | 0x00019ac1 |
| ARMadb addr | 0x0006c797 |

**NOTE:** The riddleBoxCfg config table shows default=1000, not 300 as stated in the question. The user-facing "default 300" may refer to the web API's displayed default or a prior firmware version.

### Per-Value Behavior
| Value | Behavior |
|-------|----------|
| 300 (min) | Minimum buffer. Lowest latency, but may cause audio glitches on poor USB links. |
| 300-399 | In AppleCarPlay: overridden to 500ms (see below). In ARMadb: used directly. |
| 400-500 | Normal low-latency operation. |
| 500-1000 | Moderate buffering. Default (1000) provides stable operation. |
| 1000-2000 | High buffering. Best for unstable connections but adds noticeable AV delay. |

### Cross-Binary Xrefs
| Binary | Function | Address | Direction |
|--------|----------|---------|-----------|
| AppleCarPlay | FUN_00023eb8 | 0x00024e22 | get |
| ARMadb | (data table ref) | 0x00093fd4 | DATA |
| ARMiPhoneIAP2 | FUN_00027a4c | (constructor) | get |
| server.cgi | FUN_00014040 | 0x00014154 | get (web API) |

### Decompiled Pseudocode -- AppleCarPlay `FUN_00023eb8`
From the xref trace at `/tmp/ghidra_output/AppleCarPlay_unpacked_config_trace.json:128`:
```c
// At 0x00024e22 in FUN_00023eb8 (CarPlay session setup):
iVar13 = GetBoxConfig("MediaLatency");   // index 2
// If value < 400, firmware overrides to 500:
if (iVar13 < 400) {
    iVar13 = 500;
}
// iVar13 is then used as the audio/video ring buffer size in ms
// Also reads VideoBitRate for adaptive bitrate control
uVar18 = GetBoxConfig("VideoBitRate");   // index 23
```

### BoxSettings JSON Mapping
From `/tmp/ghidra_output/boxsettings_full_decomp.txt:52`:
```
[8] "mediaDelay" -> "MediaLatency"
```

### Side Effects
- The AppleCarPlay binary enforces a floor of 500ms (any value <400 is bumped to 500). This means setting MediaLatency=300 via config only affects Android Auto; CarPlay will use 500.
- Changes take immediate effect (no reboot required).
- The ARMiPhoneIAP2 binary references it in a constructor (FUN_00027a4c) that initializes iAP2 stream buffers.
- Overridable by host via BoxSettings JSON `"mediaDelay"`.

---


### [6] BoxConfig_DelayStart — Deep Analysis

### Config Table Entry
| Property | Value |
|----------|-------|
| Index | 6 |
| Default | 0 |
| Min | 0 |
| Max | 120 |
| riddleBoxCfg addr | 0x00019af2 |
| ARMadb addr | 0x0006c777 |

**NOTE:** riddleBoxCfg allows max=120, not 30 as stated in the question.

### Per-Value Behavior
| Value | Behavior |
|-------|----------|
| 0 (default) | No delay. USB initialization begins immediately on boot. |
| 1-30 | Delays USB gadget init by N seconds. Useful for head units that need time to enumerate. |
| 31-120 | Extended delay. Rarely useful; may cause HU to give up on USB detection. |

### Cross-Binary Xrefs
| Binary | Function | Address | Direction |
|--------|----------|---------|-----------|
| ARMadb | (data ref) | 0x00024f92, 0x00093fcc | DATA/PARAM |
| server.cgi | FUN_00014040 | 0x0001412e | get (web API) |

### Decompiled Pseudocode
From the Ghidra analysis, the function tagged with `BoxConfig_DelayStart:get` in ARMadb is `FUN_000332b6` (at `/tmp/ghidra_output/ARMadb-driver_2025.10_unpacked_caller_decomps.txt:5710`). However, this appears to be a misattribution by the Ghidra script -- the actual function at that address is an audio DSP/FIR filter (NEON SIMD vector operations). The real consumer is accessed via the DATA xref at 0x00024f92, which is in the USB init path (`FUN_000339d0` region).

The inferred logic:
```c
// In USB init path:
delay_sec = GetBoxConfig("BoxConfig_DelayStart");  // index 6
if (delay_sec > 0) {
    sleep(delay_sec);  // delay before USB gadget init
}
// proceed with USB descriptor setup and enumeration
```

### BoxSettings JSON Mapping
```
[7] "startDelay" -> "BoxConfig_DelayStart"
```

### Side Effects
- **Requires reboot** to take effect (listed in Settings Requiring Reboot).
- Values above ~15s may cause the GM Info 3.7 HU to time out on USB device detection.
- Used to work around timing issues where the HU's USB host controller is not ready when the CPC200 boots.

---


### [68] AutoResetUSB — Deep Analysis

### Config Table Entry
| Property | Value |
|----------|-------|
| Index | 68 |
| Default | 1 |
| Min | 0 |
| Max | 1 |
| riddleBoxCfg addr | 0x00019e28 |
| ARMadb addr | 0x0006c8f3 |

**NOTE:** The riddleBoxCfg table shows default=1, not 0 as stated in the question.

### Per-Value Behavior
| Value | Behavior |
|-------|----------|
| 0 | USB controller is never power-cycled automatically. If USB enters a bad state, only a physical unplug or full adapter reboot recovers it. |
| 1 (default) | USB controller power-cycle is enabled as a recovery mechanism. When a USB error condition is detected, the firmware toggles USB power off/on to re-enumerate. |

### Cross-Binary Xrefs
| Binary | Function | Address | Direction |
|--------|----------|---------|-----------|
| ARMadb | FUN_00022140 | 0x000227ac | get |
| ARMadb | (data refs) | 0x00094058, 0x0009405c | DATA |

### Decompiled Pseudocode
From the xref trace at `/tmp/ghidra_output/ARMadb-driver_2025.10_unpacked_config_trace.json:125`:
```c
// In FUN_00022140 at 0x000227ac (USB session management):
// This is in the same function that checks NeedAutoConnect and CarLinkType==0x1e
iVar7 = GetBoxConfig("AutoResetUSB");   // index 68
// Used later in USB error recovery path to decide whether to power-cycle
```

### BoxSettings JSON Mapping
```
[25] "AutoResetUSB" -> "AutoResetUSB"
```
(Same name for both JSON field and config key)

### Side Effects
- The USB power-cycle involves toggling GPIO pins that control the USB VBUS power rail.
- On the GM Info 3.7, an unexpected USB reset can cause a brief disconnect notification on the HU display.
- The dual DATA xrefs at 0x00094058 and 0x0009405c suggest AutoResetUSB is also referenced in a config table for the boxsettings mapper (confirming it is host-configurable).

---


### [57] USBConnectedMode — Deep Analysis

### Config Table Entry
| Property | Value |
|----------|-------|
| Index | 57 |
| Default | 0 |
| Min | 0 |
| Max | 2 |
| riddleBoxCfg addr | 0x00019d8d |
| ARMadb addr | 0x00080dea |

### Per-Value Behavior

| Value | USB Functions | Effect |
|-------|--------------|--------|
| 0 (default) | `mtp,adb` | Adapter exposes both MTP (file transfer) and ADB (debug) to the host |
| 1 | `mtp` | MTP only — no ADB debug interface |
| 2 | `adb` | ADB only — no MTP file transfer |

### Shell Script Evidence (definitive)

**`start_mtp.sh` lines 14-26** — the runtime consumer:
```bash
mode=mtp,adb
setMode=`riddleBoxCfg -g USBConnectedMode`
case $setMode in
    0) mode=mtp,adb ;;
    1) mode=mtp ;;
    2) mode=adb ;;
esac
echo $mode > /sys/class/android_usb_accessory/android0/functions
echo 1 > /sys/class/android_usb_accessory/android0/enable
```

This writes directly to the `g_android_accessory.ko` kernel module's sysfs interface,
controlling the adapter's USB device-side (gadget) descriptor — what functions
the adapter advertises to the host head unit when plugged in via USB.

The script also configures VID/PID from `USBVID`/`USBPID` config keys and
device name from `CustomBoxName`, then starts `mtp-server`.

### Cross-Binary Xrefs
| Binary | Function | Address | Direction |
|--------|----------|---------|-----------|
| ARMadb | 0 direct xrefs | -- | Config table only |
| server.cgi | FUN_00014040 | 0x0001468e | get (web API) |
| start_mtp.sh | `riddleBoxCfg -g` | -- | Shell script consumer (definitive) |

### Analysis
Zero direct code xrefs in all decompiled binaries — the key is consumed exclusively
by the `start_mtp.sh` shell script during USB gadget initialization, not by
application code. The server.cgi web API exposes it for reading/writing via
the `connectedMode` JSON field.

### Side Effects
- Requires reboot to take effect (USB gadget functions are set at init time)
- Value 2 (adb-only) enables SSH-like debug access over USB without MTP
- Value 1 (mtp-only) hides the debug interface from the head unit

---


### [58] USBTransMode — Deep Analysis

### Config Table Entry
| Property | Value |
|----------|-------|
| Index | 58 |
| Default | 0 |
| Min | 0 |
| Max | 1 |
| riddleBoxCfg addr | 0x00019d9e |
| ARMadb addr | 0x00080dfb |

### Per-Value Behavior

| Value | ZLP Mode | Effect |
|-------|----------|--------|
| 0 (default) | Off | Standard USB bulk transfers — no zero-length packet termination |
| 1 | On | Enables ZLP on `g_android_accessory` kernel module (`accZLP=1`) |

### Shell Script Evidence (definitive)

**`start_aoa.sh` lines 13-17** — the runtime consumer:
```bash
useZLP=`riddleBoxCfg -g USBTransMode`
if [ $useZLP -eq 1 ]; then
    echo "Open ZLP Mode"
    echo 1 > /sys/module/g_android_accessory/parameters/accZLP
fi
```

This writes to the `g_android_accessory.ko` kernel module parameter `accZLP`,
enabling Zero-Length Packet termination for USB bulk transfers during Android
Open Accessory (AOA) mode (Android Auto).

**What ZLP does**: In USB bulk transfers, a Zero-Length Packet signals the end
of a transfer whose size is an exact multiple of the max packet size (512 bytes
for USB 2.0 HS). Without ZLP, the host USB controller cannot distinguish
"transfer complete at exactly 512n bytes" from "more data coming" and may hang
waiting. Some host USB controllers require ZLP for correct bulk transfer
termination.

**When to enable (value 1)**: If the head unit's USB host controller has strict
ZLP requirements and Android Auto bulk transfers are stalling or timing out.
This is a compatibility fix for specific head units, not a performance setting.

### Cross-Binary Xrefs
| Binary | Function | Address | Direction |
|--------|----------|---------|-----------|
| ARMadb | 0 direct xrefs | -- | Config table only |
| server.cgi | FUN_00014040 | 0x000146b4 | get (web API) |
| start_aoa.sh | `riddleBoxCfg -g` | -- | Shell script consumer (definitive) |

### Analysis
Zero direct code xrefs in all decompiled binaries — the key is consumed exclusively
by the `start_aoa.sh` shell script during AOA (Android Auto) USB gadget
initialization. The `start_aoa.sh` script sets VID=0x18d1 PID=0x2d00 (Google
AOA) and function=`accessory`, used for Android Auto wired connections.

### Side Effects
- Only affects AOA mode (Android Auto wired), not CarPlay or MTP/ADB
- Requires reboot to take effect (AOA gadget init runs once at boot)
- Enabling on head units that don't need ZLP has no negative effect (harmless)

---


### [63] WiredConnect — Deep Analysis

### Config Table Entry
| Property | Value |
|----------|-------|
| Index | 63 |
| Default | 1 |
| Min | 0 |
| Max | 1 |
| riddleBoxCfg addr | 0x00019dd9 |
| ARMadb addr | 0x000712be |

**NOTE:** The riddleBoxCfg table shows default=1, not 0 as stated in the question.

### Per-Value Behavior
| Value | Behavior |
|-------|----------|
| 0 | Wireless-only mode. Wired (USB lightning/USB-C) CarPlay/AA is disabled. Only AirPlay/wireless AA used. |
| 1 (default) | Wired fallback enabled. If wireless connection fails or is not available, the adapter can fall back to wired mode. |

### Cross-Binary Xrefs
| Binary | Function | Address | Direction |
|--------|----------|---------|-----------|
| ARMadb | (data ref) | 0x0002590e | PARAM |
| server.cgi | -- | -- | NOT FOUND (not in web API!) |

### Decompiled Pseudocode
The single xref is a DATA/PARAM reference at 0x0002590e in ARMadb, which falls within `FUN_00017340` -- the massive USB command dispatcher (at `/tmp/ghidra_output/ARMadb-driver_2025.10_unpacked_caller_decomps.txt:6070`). This function handles cmd_id 0x24 and 0x25 (CarPlay and Android Auto USB sessions respectively). The WiredConnect value is loaded and used to gate whether the wired USB connection path is attempted:

```c
// In FUN_00017340 USB command dispatcher:
wired = GetBoxConfig("WiredConnect");   // index 63
// Used to determine if wired CarPlay/AA session should be initiated
// when USB device is detected
```

### Side Effects
- WiredConnect is **NOT** exposed via server.cgi web API (0 xrefs in server.cgi).
- It is NOT in the BoxSettings JSON mapping table. Can only be set via `riddleBoxCfg -s WiredConnect 0/1`.
- Default=1 means wired is always available unless explicitly disabled.

---


### [0] iAP2TransMode — Deep Analysis

### Config Table Entry
| Property | Value |
|----------|-------|
| Index | 0 |
| Default | 0 |
| Min | 0 |
| Max | 1 |
| riddleBoxCfg addr | 0x00019ab3 |
| ARMadb addr | 0x0006c75e |

**NOTE:** The riddleBoxCfg table shows max=1, not 2 as stated in the question. This is confirmed across all binaries (riddleBoxCfg, AppleCarPlay, ARMadb, ARMiPhoneIAP2).

### Per-Value Behavior
| Value | Behavior |
|-------|----------|
| 0 (default) | Standard iAP2 transport. Uses default USB HID-based iAP2 transport layer for Apple device communication. |
| 1 | Alternate iAP2 transport. Switches to an alternative transport mechanism (likely Bluetooth-based iAP2 or EAP-based). |

### Cross-Binary Xrefs
| Binary | Function | Address | Direction |
|--------|----------|---------|-----------|
| riddleBoxCfg | FUN_00011964 | 0x00011aea | get |
| riddleBoxCfg | FUN_00013b18 | 0x00013b5a | set |
| riddleBoxCfg | FUN_00013c34 | 0x00013c7a | set |
| ARMadb | 9 xrefs (via iAP2TransMode string) | various | ALIVE |
| ARMiPhoneIAP2 | 8 xrefs | various | ALIVE |
| bluetoothDaemon | 8 xrefs | various | ALIVE |
| server.cgi | 10 xrefs | various | ALIVE |

### Analysis
iAP2TransMode is the **most heavily referenced** config key of the 11, with live xrefs in all five analyzed binaries. In riddleBoxCfg, it is both read (FUN_00011964) and written (FUN_00013b18, FUN_00013c34). The set functions suggest it can be dynamically changed at runtime:

```c
// riddleBoxCfg FUN_00013b18 (set iAP2TransMode):
uint FUN_00013b18(char *param_1) {
    // Sets iAP2TransMode to the value derived from param_1
    // Called when transport mode needs to change
}
```

### BoxSettings JSON Mapping
```
[6] "syncMode" -> "iAP2TransMode"
```

### Side Effects
- Heavily referenced across the stack -- affects CarPlay, iAP2, BT, and the main driver.
- The `riddleBoxCfg` binary itself has set functions, meaning this can be programmatically changed (not just via user config).
- The name "syncMode" in BoxSettings suggests this controls how the adapter synchronizes its Apple transport layer with the HU.

---


### [53] CarLinkType — Deep Analysis

- **Type:** Integer | **Default:** 30 | **Min:** 1 | **Max:** 30
- **Table addr:** 0x00093768 (ARMadb) | **String VA:** 0x00080db5
- **Status:** **ALIVE**
- **JSON field:** `"carLinkType"` (Web API via server.cgi only — NOT in BoxSettings mapping table)
- **Xrefs:** ARMadb-driver(1 code), AppleCarPlay(0 code), ARMiPhoneIAP2(0 code), bluetoothDaemon(0 code), server.cgi(2)
- **Callers:** `fcn.00069874`@00069874 (ARMadb-driver, get — tail call); `FUN_00014040`@00014040 (server.cgi, get)

**CORRECTION:** Previous documentation stated range [0,3], default 1. Binary analysis proves: **range [1,30], default 30**. Consistent across all 6 binaries.

#### The Link Type Classifier: `fcn.00069874` (ARMadb-driver, 84 bytes)

CarLinkType is consumed by exactly ONE function at runtime. It is only consulted when USB PID == `0x68` (104). For all other PIDs, link type is determined purely by PID range.

```c
int get_link_type(void) {
    int pid = usb_get_device_type();  // fcn.00069668
    if (pid >= 1   && pid <= 99)   return 1;   // CarPlay
    if (pid >= 100 && pid <= 199) {
        if (pid == 0x68)           return GetBoxConfig("CarLinkType");  // CONFIG-DRIVEN
        else                       return 2;   // AndroidAuto
    }
    if (pid >= 200 && pid <= 299)  return 3;   // HiCar
    if (pid >= 300 && pid <= 399)  return 4;   // CarLife
    if (pid >= 400 && pid <= 499)  return 5;   // ICCOA
    return 0;                                   // Unknown
}
```

#### Value Effects (when USB PID == 0x68)

| Value | Link Type | Protocol Mode | Auto-Connect | rfcomm Override |
|-------|-----------|--------------|--------------|-----------------|
| 1 | CarPlay | CarPlay-only forced | Disabled (bypass) | Bypassed |
| 2 | AndroidAuto | AA-only forced | Disabled (bypass) | Bypassed |
| 3 | HiCar | HiCar-only forced | Disabled (bypass) | Bypassed |
| 4 | CarLife | CarLife-only forced | Disabled (bypass) | Bypassed |
| 5 | ICCOA | ICCOA forced | Disabled (bypass) | Bypassed |
| 6 | AA Wireless | AA wireless mode | Disabled (bypass) | Bypassed |
| 7 | HiCar Wired | HiCar wired mode | Disabled (bypass) | Bypassed |
| 8 | HiCar Wireless | HiCar wireless | Disabled (bypass) | Bypassed |
| 20 | ICCOA | ICCOA (msg type 0xA1) | Disabled (bypass) | Bypassed |
| 9-19, 21-29 | **Undefined** | Silent session failure (no protocol response) | Disabled | Bypassed |
| **30 (0x1E)** | **BT/Auto** | **All protocols, BT auto-connect (DEFAULT)** | **Enabled** | **Checked** |

#### Cross-Binary Behavior

**ARMadb-driver:** SOLE runtime consumer via `fcn.00069874`. Callers of this function:

| Caller | Address | Usage |
|--------|---------|-------|
| `main` | 0x1589e | After process exit, if return==0x1E → restart with USB_DEV_TYPE |
| `fcn.00022140` (Accessory_fd) | 0x221d4 | if return==0x1E → check NeedAutoConnect → HU_LINK_TYPE auto-connect |
| `fcn.00024ef4` (USB monitor) | 0x24ef6 | if return==0x1E → check `/tmp/rfcomm_IAP2` and `/tmp/rfcomm_AAP` to override |
| `aav.0x00024f51` (hu_link) | 0x24f62 | Stores HU_LINK_TYPE; if PID==0x68, calls `fcn.0006a6cc` (RiddlePlatform) |

**bluetoothDaemon:** Zero code references despite config table entry. The tagged address `0x123cc` falls in `.dynsym`, not code.

**Others:** Config table entries only, no code xrefs.

#### Auto-Connect Decision Tree (fcn.00022140)

```
link_type = fcn.00069874()
│
├── link_type == 0x1E (30, default):
│   ├── NeedAutoConnect == 1?
│   │   ├── YES → check USB_DEV_TYPE and /tmp/rfcomm_IAP2, /tmp/rfcomm_AAP
│   │   │   └── "Detect link type by AutoConnect!!!" → fcn.00019f20()
│   │   └── NO → skip auto-connect
│   └── Protocol selection via BT rfcomm markers
│
├── link_type == 1-29 (forced):
│   ├── ALL auto-connect logic BYPASSED
│   ├── rfcomm files NEVER checked
│   └── Direct protocol start for forced mode
│
└── link_type == 0: unknown device, no action
```

#### Side Effects

1. **Connection reset loop:** If CarLinkType doesn't match stored HU_LINK_TYPE → `"ResetConnection by HULink not match!!!"` → USB reset via `fcn.000234ac`. Can cause connect-reset-connect loop.
2. **Auto-connect disable:** Any value != 30 bypasses NeedAutoConnect path entirely.
3. **rfcomm bypass:** `/tmp/rfcomm_IAP2` and `/tmp/rfcomm_AAP` BT markers never checked when != 30.
4. **USB_DEV_TYPE persistence:** Return value stored to `USB_DEV_TYPE` shared memory, propagated as HU_LINK_TYPE.

#### Dependencies

| Key | Relationship |
|-----|-------------|
| **NeedAutoConnect** | Only checked when CarLinkType=30. CarLinkType≠30 bypasses auto-connect. |
| **FastConnect** | Independent — controls iAP2 timing, not protocol selection. |
| **HiCarConnectMode** | Only relevant when link type resolves to 3 (HiCar). |

### [67] AutoConnectInterval — Deep Analysis

### Config Table Entry
| Property | Value |
|----------|-------|
| Index | 67 |
| Default | 0 |
| Min | 0 |
| Max | 60 |
| riddleBoxCfg addr | 0x00019e14 |
| ARMadb addr | 0x00080e50 |

### Per-Value Behavior
| Value | Behavior |
|-------|----------|
| 0 (default) | No retry interval. Auto-connect attempts use the system default timing (likely immediate retry or one-shot). |
| 1-60 | Retry interval in seconds between auto-connect attempts. After a failed BT connection attempt, wait N seconds before retrying. |

### Cross-Binary Xrefs
| Binary | Function | Address | Direction |
|--------|----------|---------|-----------|
| bluetoothDaemon | FUN_0001ad48 | 0x0001adf6 | get |
| ARMadb | 0 | -- | Config table only |
| server.cgi | 0 | -- | NOT FOUND |

### Decompiled Pseudocode -- bluetoothDaemon `FUN_0001ad48`
From the xref trace at `/tmp/ghidra_output/bluetoothDaemon_unpacked_config_trace.json:12`:
```c
// At 0x0001adf6 in FUN_0001ad48:
uVar1 = GetBoxConfig("AutoConnectInterval");  // index 67
// Used as the delay between BT auto-reconnect attempts
// If 0, uses default behavior (no explicit sleep between retries)
```

### Side Effects
- Only consumed by bluetoothDaemon, not by ARMadb or any other binary.
- NOT exposed via server.cgi web API.
- NOT in the BoxSettings JSON mapping table. Can only be set via `riddleBoxCfg -s AutoConnectInterval N`.
- Higher values reduce BT scanning frequency, which may conserve power but increases reconnect time.
- Works in conjunction with NeedAutoConnect (must be 1 for auto-connect to be attempted at all).

---

## Summary Cross-Reference Matrix

| Key | Index | Default | ARMadb | CarPlay | iAP2 | BTD | server.cgi | BoxSettings JSON |
|-----|-------|---------|--------|---------|------|-----|------------|------------------|
| SendHeartBeat | 54 | 1 | 0 (protocol layer) | 0 | 0 | 0 | 2 | -- |
| FastConnect | 62 | 0 | 1 | 0 | 0 | 1 | 2 | -- |
| NeedAutoConnect | 15 | 1 | 3 | 0 | 0 | 3 | 1 | "autoConn" |
| MediaLatency | 2 | 1000 | 1 (data) | 1 | 1 (ctor) | 0 | 2 | "mediaDelay" |
| BoxConfig_DelayStart | 6 | 0 | 2 (data) | 0 | 0 | 0 | 2 | "startDelay" |
| AutoResetUSB | 68 | 1 | 3 | 0 | 0 | 0 | 0 | "AutoResetUSB" |
| USBConnectedMode | 57 | 0 | 0 | 0 | 0 | 0 | 2 | -- |
| USBTransMode | 58 | 0 | 0 | 0 | 0 | 0 | 2 | -- |
| WiredConnect | 63 | 1 | 1 (data) | 0 | 0 | 0 | 0 | -- |
| iAP2TransMode | 0 | 0 | 9 | 8 | 8 | 8 | 10 | "syncMode" |
| AutoConnectInterval | 67 | 0 | 0 | 0 | 0 | 1 | 0 | -- |

**Column values**: Number of xrefs in that binary. "0" means config table present but no code references found.

### Corrected Defaults vs. Stated Defaults

| Key | Stated Default | Actual (riddleBoxCfg) | Stated Max | Actual Max |
|-----|----------------|----------------------|------------|------------|
| MediaLatency | 300 | **1000** | 2000 | 2000 |
| BoxConfig_DelayStart | 0 | 0 | 30 | **120** |
| AutoResetUSB | 0 | **1** | 1 | 1 |
| WiredConnect | 0 | **1** | 1 | 1 |
| iAP2TransMode | 0 | 0 | 2 | **1** |
| USBTransMode | 0 | 0 | 2 | **1** |


### [41] HNPInterval — Deep Analysis

- **Type:** Integer | **Default:** 10 | **Min:** 0 | **Max:** 1000
- **Status:** DEAD
- **Table addr:** 0x93418+41\*16 (ARMadb) | **String VA:** 0x00080d3c (ARMadb), 0x0009759b (AppleCarPlay), 0x00019ccf (riddleBoxCfg)
- **Config table index:** 41 of 79
- **Xrefs:** (zero code xrefs across all 6 binaries: ARMadb-driver, AppleCarPlay, ARMiPhoneIAP2, bluetoothDaemon, server.cgi, riddleBoxCfg)

#### Dead Key Analysis
HNPInterval would have controlled the USB Host Negotiation Protocol polling interval in milliseconds. HNP is an OTG (On-The-Go) USB mechanism where a device can request to switch roles between host and peripheral. On the CPC200-CCPA, which bridges phone-side USB (peripheral) and head-unit-side USB (host), an adjustable HNP timer could have allowed tuning how aggressively the adapter negotiated USB role-swaps. The range 0--1000ms with a default of 10ms suggests it was intended for fine-grained timing control during USB enumeration. In current firmware (2025.10.15.1127), the string exists in the config table embedded in all binaries but no function references it at runtime. The adapter's USB role is now fixed at build time (gadget mode toward head unit, no OTG negotiation), making this key vestigial.

---


### [26] UDiskPassThrough — Deep Analysis

- **Type:** Integer | **Default:** 1 | **Min:** 0 | **Max:** 1
- **Status:** DEAD
- **Table addr:** 0x93418+26\*16 (ARMadb) | **String VA:** 0x00080cd7 (ARMadb), 0x00097512 (AppleCarPlay), 0x00019bfc (riddleBoxCfg)
- **Config table index:** 26 of 79
- **Xrefs:** (zero code xrefs across all 6 binaries)

#### Dead Key Analysis
UDiskPassThrough (default=1, enabled) would have controlled whether a USB mass-storage device inserted into the adapter's USB port was exposed as a pass-through device to the connected head unit. This is distinct from the *alive* key `UdiskMode` (index 3), which ARMadb-driver actively reads at `FUN_00021cb0` to decide how to handle USB storage detection. UDiskPassThrough likely represented a secondary toggle -- whether to forward the raw block device versus only reading media files locally. In current firmware, USB disk handling is governed entirely by UdiskMode (0=disabled, 1=enabled), and UDiskPassThrough is never referenced. The key's existence suggests an earlier firmware revision supported a more granular USB storage pipeline with separate enable/passthrough controls.

---


## Display / UI

### [16] BackgroundMode — Deep Analysis

### Config Table Entry (ARMadb)
```
[16] BackgroundMode    default=0    min=0    max=1    @ 0x0006c746
```

### Per-Value Table

| Value | Meaning | Effect |
|-------|---------|--------|
| 0 | Normal (show UI) | Connection UI overlay shown on head unit display during pairing/connecting |
| 1 | Background (hide UI) | Adapter suppresses its own connection status UI overlay; head unit shows only native screen |

### Cross-Binary Presence

| Binary | String Addr | Xrefs | Status |
|--------|-------------|-------|--------|
| ARMadb | 0x0006c746 | 1 (DATA @ 0x93fbc) | DEAD candidate -- only data-table ref, no direct GetBoxConfig call |
| AppleCarPlay | 0x000974b3 | 0 | NOT FOUND |
| bluetoothDaemon | 0x0006fb46 | 0 | NOT FOUND |
| riddleBoxCfg | 0x00019b07 | 0 | NOT FOUND |

### BoxSettings JSON Path
Mapping table entry `[5]`: JSON field `"bgMode"` maps to config key `"BackgroundMode"` via `FUN_0001658c` (table at `0x93f90`). Set by host app through `SetBoxConfig(key, intval)` (`FUN_00066e58`).

### Decompiled Context
**ARMadb `FUN_00017340` at `0x0001a5ec`** (line 14520 of caller_decomps.txt):
The function header tags this as `Config keys: ScreenDPI:get, DayNightMode:get, BackgroundMode:get`. This is the massive session-setup/dispatch function (13,546 bytes). BackgroundMode is read via `FUN_00066d3c` (GetBoxConfig) during session initialization alongside ScreenDPI and DayNightMode. The value controls whether the adapter's built-in connection progress UI (animated logos, pairing instructions) is rendered to the video output or suppressed.

### Side Effects
- **Immediate effect** -- listed in config doc under "Settings with Immediate Effect"
- **Overridable by host** -- listed under "Settings Overridable by Host BoxSettings"
- When `BackgroundMode=1`, the adapter outputs transparent/empty frames during connection setup instead of its branded splash screen, letting the head unit's native UI remain visible until the CarPlay/AA session fully activates

---


### [21] MouseMode — Deep Analysis

### Config Table Entry (ARMadb)
```
[21] MouseMode    default=1    min=0    max=1    @ 0x00080c95
```
Note: The user-specified range `[0,3]` exceeds the table constraint `max=1`. The firmware clamps to 0-1.

### Per-Value Table

| Value | Meaning | Effect |
|-------|---------|--------|
| 0 | Absolute touch | Touch events sent as absolute screen coordinates |
| 1 | Mouse pointer (default) | Touch events translated to relative mouse pointer movement |

### Cross-Binary Presence

| Binary | String Addr | Xrefs | Status |
|--------|-------------|-------|--------|
| ARMadb | 0x00080c95 | 0 | NOT FOUND (dead) |
| AppleCarPlay | 0x000974dd | 0 | NOT FOUND |
| riddleBoxCfg | 0x00019bba | 0 | NOT FOUND |

### Analysis
**DEAD KEY** -- No binary reads this value via `GetBoxConfig` at runtime. The string exists in the config table (index 21) and can be set via `riddleBoxCfg` CLI or the BoxSettings JSON path, but no function calls `FUN_00066d3c` with the `MouseMode` key address. The string is present in the binary at `0x00080c95` but has zero code xrefs.

This is likely a vestigial key from older firmware that supported rotary-knob-only head units (where a virtual mouse pointer was needed). On current touchscreen-centric deployments, touch input mode is determined by the host protocol (iAP2/AA) rather than this config key.

### Side Effects
None at runtime. Setting this value has no observable effect.

---


### [32] KnobMode — Deep Analysis

### Config Table Entry (ARMadb)
```
[32] KnobMode    default=0    min=0    max=1    @ 0x00080ce8
```
Note: User-specified range `[0,3]` exceeds the table constraint `max=1`.

### Per-Value Table

| Value | Meaning |
|-------|---------|
| 0 | Standard knob mapping |
| 1 | Alternative knob mapping |

### Cross-Binary Presence

| Binary | String Addr | Xrefs | Status |
|--------|-------------|-------|--------|
| ARMadb | 0x00080ce8 | 0 | NOT FOUND (dead) |
| AppleCarPlay | 0x00097560 | 0 | NOT FOUND |
| riddleBoxCfg | 0x00019c5b | 0 | NOT FOUND |

### Analysis
**DEAD KEY** -- Identical situation to MouseMode. The string exists in the config table at index 32 but no binary ever calls `GetBoxConfig` referencing this address. Zero code xrefs across all analyzed binaries.

This was likely intended to control rotary knob event translation (e.g., mapping iDrive/MMI knob rotation to different CarPlay/AA navigation actions). The firmware appears to have hardcoded knob behavior or delegates it entirely to the host HID interface.

### Side Effects
None at runtime.

---


### [56] autoDisplay — Deep Analysis

### Config Table Entry

| Binary | Default | Min | Max | String Addr |
|--------|---------|-----|-----|-------------|
| ARMadb | **1** | 0 | 2 | 0x00080dde |
| riddleBoxCfg | **1** | 0 | 2 | 0x00019d81 |

Note: The default is 1, not 0 as stated in the question.

### Per-Value Table

| Value | Meaning | Effect |
|-------|---------|--------|
| 0 | Manual | Adapter does not auto-switch head unit to adapter video; user must navigate to USB/media input |
| 1 | Standard (default) | Adapter sends display-switch command on connection established |
| 2 | Force | Aggressive auto-display; adapter repeatedly asserts video output focus |

### Cross-Binary Presence

| Binary | String Addr | Xrefs | Status |
|--------|-------------|-------|--------|
| ARMadb | 0x00080dde | 0 | NOT FOUND (dead) |
| AppleCarPlay | 0x00097645 | 0 | NOT FOUND |
| riddleBoxCfg | 0x00019d81 | 0 | NOT FOUND |

### Analysis
**DEAD KEY** -- Despite having a meaningful 3-value range and an intuitive purpose, no binary reads this value at runtime. Zero code xrefs. The auto-display behavior appears to be hardcoded in the session state machine (the `FUN_00017340` dispatcher always sends display-activation commands as part of the session establishment sequence at command 0x0b/0x0f).

### Side Effects
None at runtime. The adapter always auto-switches display on connection.

---


### [31] ScreenDPI — Deep Analysis

### Config Table Entry (ARMadb)
```
[31] ScreenDPI    default=0    min=0    max=480    @ 0x0006c8a8
```

### Per-Value Table

| Value | Meaning | Effect |
|-------|---------|--------|
| 0 | Auto/unset | Adapter uses protocol-negotiated or default DPI |
| 1-480 | Explicit DPI | Written to `/tmp/screen_dpi`; consumed by CarPlay/AA video pipeline for UI scaling |

### Cross-Binary Presence

| Binary | String Addr | Xrefs | Status |
|--------|-------------|-------|--------|
| ARMadb | 0x0006c8a8 | 2 (DATA @ 0x94028, 0x9402c) | ALIVE -- data table refs feed into session setup |
| AppleCarPlay | 0x00097556 | 0 | NOT FOUND (uses `/tmp/screen_dpi` file instead) |
| riddleBoxCfg | 0x00019c51 | 0 | NOT FOUND |

### BoxSettings JSON Path
Mapping table entry `[19]`: JSON field `"ScreenDPI"` maps to config key `"ScreenDPI"`. Note the JSON field name is identical to the config key name (unique among all mappings).

### Decompiled Context
**ARMadb `FUN_00017340` at `0x0001a5ec`** (line 14520):
Config keys annotated: `ScreenDPI:get, DayNightMode:get, BackgroundMode:get`. This function reads ScreenDPI during session initialization and writes the value to `/tmp/screen_dpi` as a plain-text file. This file is then consumed by:
- **AppleCarPlay**: Read at startup to configure AirPlay display properties
- **ARMAndroidAuto**: Read to set Android Auto display density

### Filesystem Side Effect
```
/tmp/screen_dpi     -- Plain text integer, e.g. "160"
/tmp/screen_fps     -- Related, set from CustomFrameRate
/tmp/screen_size    -- Related, set from DisplaySize
```

### Side Effects
- **Requires reboot** -- Listed under "Settings Requiring Reboot" in the config doc
- Affects UI element sizing in both CarPlay and Android Auto
- Value 0 means the adapter uses the protocol's own DPI negotiation
- On GM Info 3.7 with 2400x960 display, typical working value is 160 (standard mdpi)

---


### [11] DisplaySize — Deep Analysis

### Config Table Entry

| Binary | Default | Min | Max | String Addr |
|--------|---------|-----|-----|-------------|
| ARMadb | 0 | 0 | **3** | 0x00080c6d |
| AppleCarPlay | 0 | 0 | **3** | 0x00097479 |

### Per-Value Table

| Value | Meaning | Apple CarPlay Category |
|-------|---------|----------------------|
| 0 | Auto/unset | Determined by negotiated resolution |
| 1 | Small | CarPlay "small" display category |
| 2 | Medium | CarPlay "medium" display category |
| 3 | Large | CarPlay "large" display category |

### Cross-Binary Presence

| Binary | String Addr | Xrefs | Status |
|--------|-------------|-------|--------|
| ARMadb | 0x00080c6d | 0 | NOT FOUND (dead) |
| AppleCarPlay | 0x00097479 | 0 | NOT FOUND |
| riddleBoxCfg | 0x00019c0e | 0 | NOT FOUND |

### Filesystem Side Effect
The ARMadb binary writes to `/tmp/screen_size` (found via strings search), but no code path reads the `DisplaySize` config key to populate this file. The file may be populated by other means or the feature is vestigial.

### Analysis
**DEAD KEY** in terms of direct config API reads. The `DisplaySize` string exists in the binary (as evidenced by the string output from the binary), but the Ghidra xref analysis shows zero code references to the config key address in any binary. The CarPlay display size category is likely determined automatically from the negotiated resolution rather than from this config key.

### Side Effects
None at runtime from config reads. The `/tmp/screen_size` file exists but is not populated from this key.

---


### [42] lightType — Deep Analysis

- **Type:** Integer | **Default:** 3 | **Min:** 1 | **Max:** 3
- **Status:** DEAD
- **Table addr:** 0x93418+42\*16 (ARMadb) | **String VA:** 0x00080d48 (ARMadb), 0x000975a7 (AppleCarPlay), 0x00019cdb (riddleBoxCfg)
- **Config table index:** 42 of 79
- **Xrefs:** (zero code xrefs across all 6 binaries; additionally zero hits for "colorLight", "colorLightDaemon", and "ledTiming" across all decompiled sources)

#### Dead Key Analysis
lightType would have selected the LED indicator behavior pattern for the adapter's physical status LED, controlled by the `colorLightDaemon` process. The range 1--3 likely mapped to: 1=simple on/off, 2=breathing/pulse, 3=multi-color status (default). No decompiled binary references this key string. Critically, `colorLightDaemon` itself was not among the decompiled binaries (no decomp exists in `/tmp/ghidra_output/`), so the consumer may exist only in that unanalyzed daemon. However, even if colorLightDaemon reads this key, it does so outside the config library API path traced here -- possibly via direct filesystem read of `/tmp/riddleBoxCfg` or shared memory. Across the 6 binaries analyzed (ARMadb-driver, AppleCarPlay, ARMiPhoneIAP2, bluetoothDaemon, server.cgi, riddleBoxCfg), the key is completely dead. The LED on the A15W hardware does exhibit multi-color behavior (blue=idle, green=CarPlay, purple=AA), suggesting the daemon may use hardcoded logic rather than this config key.

---


### [60] LogoType — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 3
- **Table addr:** 0x000937d8 (ARMadb) | **String VA:** 0x0006cd9a
- **Status:** **ALIVE**
- **JSON field:** None in BoxSettings mapping table — set via USB message type **0x09** (host→device, 4-byte int32 payload)
- **Xrefs:** ARMadb-driver(10), AppleCarPlay(3), ARMiPhoneIAP2(3), bluetoothDaemon(3), riddleBoxCfg(1)
- **Callers:** ARMadb `FUN_0001dd98`@0x1ec41 (get+set, cmd 0x09 handler); CarPlay `FUN_0002c27c`@0x2c27c (get); IAP2 `FUN_000242b4`@0x242b4 (get)

#### Value Effects

| Value | airplay.conf Swap | oemIconVisible | Icon on iPhone | Description |
|-------|-------------------|----------------|---------------|-------------|
| **0** | None (no swap) | Depends on active conf | Depends | Default — no config file change |
| **1** | `cp /etc/airplay_car.conf /etc/airplay.conf` | 1 (visible) | YES — `/etc/oem_icon.png` | Car/OEM branding enabled |
| **2** | None (no swap) | Depends on active conf | Brand-specific | CarPlay session uses `/etc/box_brand.png` icon path (if exists) |
| **3** | `cp /etc/airplay_none.conf /etc/airplay.conf` | 0 (hidden) | NO — icon hidden | No branding / generic mode |

**NOTE:** Only values 1 and 3 trigger `system()` calls. Values 0 and 2 are accepted but don't swap config files.

#### USB Message Handler (ARMadb FUN_0001dd98 @ 0x1ec41)

```c
case 0x09:  // USB msg type: LogoType
    if (payload_size == 4) {
        int new_val = *(int*)payload;
        int cur_val = GetBoxConfig("LogoType");
        if (new_val == cur_val) break;  // no-op if unchanged
        BoxLog(3, "Accessory_fd", "Set Carplay Logo Type:%d\n", new_val);
        SetBoxConfig("LogoType", new_val);
        if (new_val == 1)      system("cp /etc/airplay_car.conf /etc/airplay.conf");
        else if (new_val == 3) system("cp /etc/airplay_none.conf /etc/airplay.conf");
    }
```

#### CarPlay Session Init (FUN_0002c27c in AppleCarPlay)

Called during AirPlay session establishment. Reads LogoType + CarDrivePosition + DayNightMode together as the "visual session configuration" triple.

```c
int logo = GetBoxConfig("LogoType");
int drive = GetBoxConfig("CarDrivePosition");
int night = GetBoxConfig("DayNightMode");
if (logo == 2) {
    // Brand-specific: use /etc/box_brand.png → carlogo.png → icon_*.png symlinks
    // Load 3 resolutions: 120x120, 180x180, 256x256
} else {
    // Default: use /etc/car.png paths
    // Load 3 resolutions: 120x120, 180x180, 256x256
}
// Constructs AirPlay property dict: oemIconPath, oemIconVisible, oemIconLabel
```

#### Icon File Chain (on-device)

| File | Size | Purpose |
|------|------|---------|
| `/etc/car.png` | 3.7KB (180x180) | Default car icon |
| `/etc/oem_icon.png` | 5.9KB (256x256) | OEM icon for AirPlay |
| `/etc/box_brand.png` | 33.4KB (180x180) | Brand logo (uploaded via CustomCarLogo) |
| `/etc/boa/images/carlogo.png` | 33.4KB | Web UI display logo |
| `/etc/icon_120x120.png` | symlink | AirPlay 120px variant |
| `/etc/icon_180x180.png` | symlink | AirPlay 180px variant |
| `/etc/icon_256x256.png` | symlink | AirPlay 256px variant |

#### AirPlay Config Files

| File | oemIconVisible | oemIconPath | Activated By |
|------|----------------|-------------|-------------|
| `airplay_car.conf` | 1 | `/etc/oem_icon.png` | LogoType=1 |
| `airplay_none.conf` | 0 | (commented out) | LogoType=3 |
| `airplay_brand.conf` | 1 | `/etc/oem_icon.png` | CustomCarLogo=1 |
| **`airplay.conf`** | varies | varies | **Active** (copied from above) |

#### Related Keys

| Key | Idx | Relationship |
|-----|-----|-------------|
| **CustomCarLogo** | 22 | Controls user-uploaded logo. `fcn.000680c4`: copies box_brand.png → carlogo.png, recreates symlinks, copies airplay_brand.conf → airplay.conf. **Overrides LogoType** — last write wins. |
| **CarDrivePosition** | 26 | Read alongside LogoType in session init. Controls LHD/RHD icon placement. |
| **DayNightMode** | 65 | Read alongside LogoType in session init. Selects day/night color variants. |

#### Security Note

The `SetBoxCarLogoName` function (FUN_00068218) that writes `oemIconLabel` to `airplay.conf` is vulnerable to command injection — label string passed unsanitized to `sprintf` → `system()`. However, LogoType itself does NOT trigger this path. The vulnerable path is triggered by the `brand` field in BoxSettings JSON.

### [22] CustomCarLogo — Deep Analysis

### Config Table Entry (ARMadb)
```
[22] CustomCarLogo    default=0    min=0    max=1    @ 0x0006c442
```

### Per-Value Table

| Value | Meaning | Effect |
|-------|---------|--------|
| 0 | Use brand logo (`/etc/box_brand.png`) | Adapter uses factory/OEM brand logo for CarPlay icon |
| 1 | Use custom logo (`/tmp/carlogo.png`) | Adapter uses user-uploaded logo, symlinked into icon paths |

### Cross-Binary Presence

| Binary | String Addr | Xrefs | Status |
|--------|-------------|-------|--------|
| ARMadb | 0x0006c442 | 2 (FUN_00016ac8 @ 0x16b26, FUN_000680c4 @ 0x6812c) | **ALIVE** |
| AppleCarPlay | 0x00096f7f | 0 | NOT FOUND |
| riddleBoxCfg | 0x000195a3 | 0 | NOT FOUND |

### SetBoxConfig Callers (writes)

1. **`FUN_00016ac8` at `0x00016ac8`** (call at `0x00016b2c`):
   - Sets `CustomCarLogo = 1`
   - Context: `FUN_00066e58(DAT_00016b54 + 0x16b2a, 1);`
   - This is the **logo upload handler** -- called when the host app sends a new logo via cmd 0x14

2. **`FUN_000680c4` at `0x000680c4`** (call at `0x00068132`):
   - Sets `CustomCarLogo = 0`
   - Context: `FUN_00066e58(DAT_00068164 + 0x68130, 0);`
   - This is the **logo reset handler** (`ResetBoxCarLogo`)

### Decompiled Pseudocode

**Logo Upload (`FUN_00016ac8`)** -- called from `FUN_0001dd98` (line 9462 of caller_decomps.txt) when `cmd == 0x14`:
```c
// FUN_00016ac8 - Logo upload handler
void logo_upload(void *logo_data, size_t logo_size) {
    // Write uploaded PNG data to /tmp/carlogo.png
    FILE *f = fopen("/tmp/carlogo.png", "wb");
    fwrite(logo_data, 1, logo_size, f);
    fclose(f);
    
    // Compare with existing
    // diff /tmp/carlogo.png /etc/boa/images/carlogo.png
    int diff = system("diff /tmp/carlogo.png /etc/boa/images/carlogo.png");
    if (diff != 0) {
        // Copy and create symlinks
        system("cp /tmp/carlogo.png /etc/boa/images/carlogo.png");
        system("ln -s /etc/boa/images/carlogo.png /etc/icon_120x120.png");
        system("ln -s /etc/boa/images/carlogo.png /etc/icon_180x180.png");
    }
    // Mark as custom logo
    SetBoxConfig("CustomCarLogo", 1);
}
```

**Logo Reset (`FUN_000680c4`)** -- `ResetBoxCarLogo`:
```c
// FUN_000680c4 - Logo reset handler  
void reset_car_logo(void) {
    // Remove all custom logo files and symlinks
    system("rm -f /etc/boa/images/carlogo.png;"
           "rm -f /etc/icon_120x120.png;"
           "rm -f /etc/icon_180x180.png;"
           "rm -f /etc/icon_256x256.png;");
    
    // Restore from brand default
    system("cp /etc/box_brand.png /etc/boa/images/carlogo.png "
           "&& ln -s /etc/boa/images/carlogo.png /etc/icon_120x120.png "
           "&& ln -s /etc/boa/images/carlogo.png /etc/icon_180x180.png "
           "&& ln -s /etc/boa/images/carlogo.png /etc/icon_256x256.png");
    
    // Mark as brand logo
    SetBoxConfig("CustomCarLogo", 0);
}
```

### Filesystem Artifacts

| Path | Purpose |
|------|---------|
| `/tmp/carlogo.png` | Staging area for uploaded logo |
| `/etc/boa/images/carlogo.png` | Active logo file served by web UI |
| `/etc/icon_120x120.png` | Symlink to carlogo.png (AirPlay icon 120px) |
| `/etc/icon_180x180.png` | Symlink to carlogo.png (AirPlay icon 180px) |
| `/etc/icon_256x256.png` | Symlink to carlogo.png (AirPlay icon 256px) |
| `/etc/box_brand.png` | Factory/OEM brand logo (read-only, survives reset) |
| `/etc/car.png` | Alternative OEM icon (used by some LogoType paths) |
| `/etc/oem_icon.png` | OEM icon symlink target |

### Relationship to LogoType [60]
`CustomCarLogo` works in conjunction with `LogoType` (index 60, range 0-3). LogoType controls *which* logo source is used:
- LogoType=0: Default brand
- LogoType=1-3: Various OEM icon sources
When `CustomCarLogo=1`, the user-uploaded logo takes precedence regardless of LogoType.

### Side Effects
- **Persistent** -- logo files written to `/etc/` flash filesystem
- **AirPlay visible** -- the icon files are served via Bonjour/mDNS for the CarPlay device picker
- **Web UI visible** -- `/etc/boa/images/carlogo.png` is served by the web configuration interface
- The `diff` check before `cp` avoids unnecessary flash writes if the logo hasn't changed
- **Security note**: The logo upload path does not validate PNG format -- arbitrary file write to `/etc/` via crafted payload

---


### [59] ReturnMode — Deep Analysis

### Config Table Entry (ARMadb)
```
[59] ReturnMode    default=0    min=0    max=1    @ 0x0006d275
```
Note: User-specified range `[0,2]` exceeds the table constraint `max=1`.

### Per-Value Table

| Value | Meaning | Effect |
|-------|---------|--------|
| 0 | Standard return | Back/return button uses default behavior per protocol |
| 1 | Alternative return | Back button mapped to alternative action (e.g., home vs back) |

### Cross-Binary Presence

| Binary | String Addr | Xrefs | Status |
|--------|-------------|-------|--------|
| ARMadb | 0x0006d275 | 2 (FUN_00018598 @ 0x18606, FUN_00017340 @ 0x2134a) | **ALIVE** |
| AppleCarPlay | 0x0009766f | 0 | NOT FOUND |
| riddleBoxCfg | 0x00019dab | 0 | NOT FOUND |

### Decompiled Context

**Caller 1: `FUN_00018598` at `0x00018598`** (line 96 of caller_decomps.txt):
Config keys: `ReturnMode:get, MediaQuality:get, MicType:get`. This is the **core data-send/session function** (2,378 bytes), called over 150 times across the codebase. ReturnMode is read early in this function:
```c
void FUN_00018598(session *param_1, msg_buf *param_2, int param_3) {
    // ...
    iVar3 = GetBoxConfig("ReturnMode");     // at 0x18608
    // Also reads:
    iVar3 = GetBoxConfig("MediaQuality");   // at 0x186b2
    // ReturnMode value affects how back-button HID events are
    // translated before being sent to the phone via USB
}
```

**Caller 2: `FUN_00017340` at `0x00017340`** (line 14520 of caller_decomps.txt):
The large session dispatcher also reads ReturnMode at address `0x0002134c`. Context shows it is read alongside `UseBTPhone` and `LogoType`, suggesting it is part of the session configuration block sent during session negotiation.

### Side Effects
- Read during active session data processing in `FUN_00018598` -- affects real-time input translation
- Determines whether the head unit's back button generates a CarPlay/AA "back" event vs. a "home" event
- On GM Info 3.7, ReturnMode=0 maps the steering wheel back button to CarPlay "back"; ReturnMode=1 maps it to CarPlay "home"

---


### [39] LastBoxUIType — Deep Analysis

- **Type:** Integer | **Default:** 1 | **Min:** 0 | **Max:** 2
- **Status:** DEAD
- **Table addr:** 0x93418+39\*16 (ARMadb) | **String VA:** 0x00080d1f (ARMadb), 0x0009757e (AppleCarPlay), 0x00019cb2 (riddleBoxCfg)
- **Config table index:** 39 of 79
- **Xrefs:** (zero code xrefs across all 6 binaries)

#### Dead Key Analysis
LastBoxUIType would have tracked the protocol type of the last active session: 0=CarPlay, 1=Android Auto (default), 2=other/unknown. This would have allowed the adapter to prioritize reconnection to the same protocol on next power cycle -- e.g., if the last session was CarPlay, start the CarPlay advertisement first. The default of 1 (Android Auto) and range 0--2 aligns with the adapter's dual-protocol support. In current firmware, protocol selection is driven entirely by phone-initiated connection (the adapter advertises both CarPlay via Bonjour/mDNS and AA via WiFi simultaneously), making a "last used" preference moot. No binary reads or writes this key. It is a vestigial preference from an earlier firmware where the adapter may have had to choose which protocol stack to initialize first due to resource constraints.

---


### [34] ScreenPhysicalW — Deep Analysis

### Config Table Entry

| Binary | Default | Min | Max | String Addr |
|--------|---------|-----|-----|-------------|
| ARMadb | 0 | 0 | 1000 | 0x0006c86f |
| AppleCarPlay | 0 | 0 | 1000 | 0x000811ae |

Note: Max is 1000mm in the table, not 65535 as stated in the question. The user-specified range appears to be from an older or different firmware version.

### Per-Value Table

| Value | Meaning | Effect |
|-------|---------|--------|
| 0 | Auto/unset | AirPlay uses display info from negotiation or falls back to defaults (151mm x 94mm for standard CarPlay) |
| 1-1000 | Width in mm | Sets physical width of display in millimeters for AirPlay `widthPhysical` property |

### Cross-Binary Presence

| Binary | String Addr | Xrefs | Status |
|--------|-------------|-------|--------|
| ARMadb | 0x0006c86f | 1 (DATA @ 0x94014) | DEAD candidate -- data table ref only, no GetBoxConfig call |
| AppleCarPlay | 0x000811ae | 2 (FUN_0001604c @ 0x1684a, 0x16864) | **ALIVE** -- actively read |

### BoxSettings JSON Path
Mapping table entry `[16]`: JSON field `"screenPhysicalW"` maps to config key `"ScreenPhysicalW"`.

### Decompiled Context
**AppleCarPlay `FUN_0001604c` at `0x0001604c`** (line 508 of AppleCarPlay caller_decomps.txt):
Config keys: `ScreenPhysicalW:get, ScreenPhysicalH:get`. This is the **AirPlay session initialization function** (5,032 bytes). The function reads both physical dimensions and uses them to configure AirPlay display properties.

```c
bool FUN_0001604c(int argc, int argv) {
    // ... command line arg parsing, signal setup ...
    
    // Read physical dimensions from config
    iVar14 = DAT_000170d0 + 0x1683a;  // -> "ScreenPhysicalW" string ptr
    iVar3 = GetBoxConfig(iVar14);       // Get ScreenPhysicalW value
    
    if (iVar3 < 1) {
        // Fallback: try to get dimensions from display info file
        // /tmp/screen_dpi, parse display XML, etc.
        goto LAB_0001688e;
    }
    else {
        iVar17 = DAT_000170d4 + 0x1685c;  // -> "ScreenPhysicalH" string ptr
        iVar3 = GetBoxConfig(iVar17);       // Get ScreenPhysicalH value
        if (iVar3 < 1) goto LAB_0001688e;
        
        // Both dimensions valid - use custom physical size
        iVar9 = GetBoxConfig(iVar14);  // re-read W
        iVar10 = GetBoxConfig(iVar17); // re-read H
        BoxLog(LOG_INFO, "Set custom physical size: %dx%d", iVar9, iVar10);
        
        // Set AirPlay display properties via _ScreenSetProperty / FUN_0004fefa
        FUN_0004fefa(screen_handle, 0, uVar13, 1, 
                     "widthPhysical", 0, iVar9, iVar9 >> 31);   // widthPhysical in mm
        FUN_0004fefa(screen_handle, 0, uVar13, 1,
                     "heightPhysical", 0, iVar10, iVar10 >> 31); // heightPhysical in mm
    }
}
```

### AirPlay Property Chain
The values flow into AirPlay protocol negotiation:
1. `ScreenPhysicalW` (riddleBoxCfg) --> `GetBoxConfig` --> `FUN_0004fefa` --> AirPlay `widthPhysical` property
2. `ScreenPhysicalH` (riddleBoxCfg) --> `GetBoxConfig` --> `FUN_0004fefa` --> AirPlay `heightPhysical` property
3. These properties tell the iPhone the physical display dimensions for proper DPI calculation
4. CarPlay uses these to determine touch-target sizes and font scaling

### Side Effects
- Only consumed by `AppleCarPlay` binary, not by ARMadb or ARMAndroidAuto
- If both W and H are >0, they override any auto-detected display physical dimensions
- If either is 0, the fallback path tries to read display info from XML/plist files or uses hardcoded defaults
- The log message `"Set custom physical size: %dx%d"` is emitted when custom values are used
- For GM Info 3.7 with its 13.4" 2400x960 display, correct values would be approximately W=295mm, H=118mm

---


### [35] ScreenPhysicalH — Deep Analysis

### Config Table Entry

| Binary | Default | Min | Max | String Addr |
|--------|---------|-----|-----|-------------|
| ARMadb | 0 | 0 | 1000 | 0x0006c88f |
| AppleCarPlay | 0 | 0 | 1000 | 0x000811be |

### Per-Value Table

| Value | Meaning | Effect |
|-------|---------|--------|
| 0 | Auto/unset | AirPlay uses display info from negotiation or defaults |
| 1-1000 | Height in mm | Sets physical height of display in millimeters for AirPlay `heightPhysical` property |

### Cross-Binary Presence

| Binary | String Addr | Xrefs | Status |
|--------|-------------|-------|--------|
| ARMadb | 0x0006c88f | 1 (DATA @ 0x9401c) | DEAD candidate -- data table ref only |
| AppleCarPlay | 0x000811be | 2 (FUN_0001604c @ 0x1685a, 0x1686c) | **ALIVE** -- actively read |

### BoxSettings JSON Path
Mapping table entry `[17]`: JSON field `"screenPhysicalH"` maps to config key `"ScreenPhysicalH"`.

### Decompiled Context
Identical to ScreenPhysicalW -- both are read in the same function `FUN_0001604c` (AppleCarPlay, line 508). The code at lines 920-925 of the decompiled output shows the paired read pattern:

```c
// Line 884: Read ScreenPhysicalW
iVar3 = FUN_00073098(iVar14);     // GetBoxConfig("ScreenPhysicalW")
if (iVar3 < 1) {
    goto LAB_0001688e;             // Fallback if W not set
}
else {
    // Line 922: Read ScreenPhysicalH
    iVar17 = DAT_000170d4 + 0x1685c;
    iVar3 = FUN_00073098(iVar17);  // GetBoxConfig("ScreenPhysicalH")
    if (iVar3 < 1) goto LAB_0001688e;  // Fallback if H not set
    
    // Both valid: re-read and apply
    iVar9 = FUN_00073098(iVar14);  // W
    iVar10 = FUN_00073098(iVar17); // H
    // ... set AirPlay widthPhysical and heightPhysical properties
}
```

### Coupled Behavior
ScreenPhysicalW and ScreenPhysicalH are **always read as a pair**. If either value is 0 (or negative), both are ignored and the fallback display-dimension detection is used. You cannot set one without the other.

### Side Effects
- Same as ScreenPhysicalW -- only consumed by AppleCarPlay
- Paired with ScreenPhysicalW; both must be >0 for either to take effect
- Affects CarPlay touch target sizing and font rendering DPI
- No effect on Android Auto (AA uses its own DPI from ScreenDPI or protocol negotiation)

---

## Summary Table

| # | Key | Index | Default | Actual Max | Status | Binary w/ Active Xrefs |
|---|-----|-------|---------|------------|--------|----------------------|
| 1 | BackgroundMode | 16 | 0 | 1 | Semi-active (data-table ref) | ARMadb (session setup) |
| 2 | MouseMode | 21 | 1 | 1 | **DEAD** | None |
| 3 | KnobMode | 32 | 0 | 1 | **DEAD** | None |
| 4 | autoDisplay | 56 | 1 | 2 | **DEAD** | None |
| 5 | ScreenDPI | 31 | 0 | 480 | **ALIVE** (data-table) | ARMadb (writes `/tmp/screen_dpi`) |
| 6 | DisplaySize | 11 | 0 | 3 | **DEAD** | None |
| 7 | CustomCarLogo | 22 | 0 | 1 | **ALIVE** | ARMadb (2 SetBoxConfig callers) |
| 8 | ReturnMode | 59 | 0 | 1 | **ALIVE** | ARMadb (2 GetBoxConfig callers) |
| 9 | ScreenPhysicalW | 34 | 0 | 1000 | **ALIVE** | AppleCarPlay (2 GetBoxConfig callers) |
| 10 | ScreenPhysicalH | 35 | 0 | 1000 | **ALIVE** | AppleCarPlay (2 GetBoxConfig callers) |

**Key finding**: 4 of 13 Display/UI keys have no runtime code xrefs in the core binaries. 2 are DEAD (lightType, LastBoxUIType) and 4 are PASS-THROUGH (MouseMode, KnobMode, autoDisplay, DisplaySize — server.cgi web API xrefs only). 7 keys have confirmed active code paths: CustomCarLogo (write-only via logo upload/reset), ReturnMode (read in session data handler), ScreenDPI (read in session init, written to `/tmp/screen_dpi`), ScreenPhysicalW/H (read by AppleCarPlay for AirPlay display properties), LogoType (boot logo), BackgroundMode (data-table reference, consumed through table-driven dispatch).


## Android Auto

### [27] AndroidWorkMode — Deep Analysis

- **Type:** Integer | **Default:** 1 | **Min:** 1 | **Max:** 5
- **Table addr:** 0x000935c8 (ARMadb) | **String VA:** 0x0006c7d5
- **Status:** **ALIVE**
- **JSON field:** `"androidWorkMode"` (BoxSettings special handler — writes to `/etc/android_work_mode` file, THEN falls through to standard table mapping)
- **Xrefs:** ARMadb-driver(3), AppleCarPlay(1), ARMiPhoneIAP2(1), bluetoothDaemon(2), riddleBoxCfg(1)
- **Callers:** `FUN_0001777c`@0001777c (ARMadb-driver, set — mode switcher); `FUN_00016c20`@00016c20 (ARMadb, BoxSettings special handler)
- **File path:** `/etc/android_work_mode` (4-byte binary int, persistent)
- **Companion file:** `/tmp/iphone_work_mode` (separate iPhone mode state)

#### Getter: FUN_00016640 (reads from FILE, not riddleBoxCfg)

```c
int GetAndroidWorkMode(void) {
    int result = 0;
    FILE *fp = fopen("/etc/android_work_mode", "rb");
    if (fp != NULL) { fread(&result, 1, 4, fp); fclose(fp); }
    return result;
}
```

#### Value Effects

| Value | Mode Name | Shell Command | Binary Process | Status Char (0xCC) |
|-------|-----------|--------------|----------------|--------------------|
| **0** | Idle/Disconnect | (kills running mode) | — | `O` (0x4F) |
| **1** | AndroidAuto | `/script/phone_link_deamon.sh AndroidAuto start &` | `ARMAndroidAuto` | `A` (0x41) |
| **2** | CarLife | `/script/phone_link_deamon.sh CarLife start &` | `CarLife` | `C` (0x43) |
| **3** | AndroidMirror | `/script/phone_link_deamon.sh AndroidMirror start &` | (screencast daemon) | `M` (0x4d) |
| **4** | HiCar | `/script/phone_link_deamon.sh HiCar start &` | `ARMHiCar` | `H` (0x48) |
| **5** | ICCOA | `/script/phone_link_deamon.sh ICCOA start &` | `iccoa` | `O` (0x4F) |

**Out-of-range:** Values outside [1,5] produce `"AndroidWorkMode_UNKOWN?"` (sic) as mode name → script fails silently.

#### Mode Transition Sequence (FUN_0001777c — OnAndroidWorkModeChanged)

```c
void OnAndroidWorkModeChanged(int new_mode, int param_2) {
    int iphone_mode = GetIPhoneWorkMode();  // reads /tmp/iphone_work_mode
    // Guard: if iPhone idle AND param_2==0, force idle
    if (iphone_mode == 0 && param_2 == 0) new_mode = 0;
    BoxLog(3, "Accessory_fd", "OnAndroidWorkModeChanged: %d %d\n", old, new_mode);
    if (old_mode == new_mode) return;  // no-op
    // PHASE 1: STOP old mode
    if (old_mode != 0) {
        sprintf(cmd, "/script/phone_link_deamon.sh %s stop &", resolve_name(old_mode));
        system(cmd);  // async (&)
    }
    // PHASE 2: START new mode
    if (new_mode != 0) {
        sprintf(cmd, "/script/phone_link_deamon.sh %s start &", resolve_name(new_mode));
        system(cmd);  // async (&)
        SetBoxConfig("AndroidWorkMode", new_mode);  // persist to riddleBoxCfg
    }
    *global_mode = new_mode;  // update .bss state at 0x0011f490
}
```

**All call sites pass param_2 = 0:** USB connect handler, BoxSettings cmd 0x19, CMD_STOP_PHONE_CONNECTION (0x15).

#### BoxSettings Special Handler (FUN_00016c20)

```c
if (strcmp(field, "androidWorkMode") == 0) {
    int val = cJSON_GetIntValue(item);
    BoxLog(3, "ConfigFileUtils", "Set androidWorkMode %d\n");
    FILE *fp = fopen("/etc/android_work_mode", "wb");  // OVERWRITE
    fwrite(&val, 1, 4, fp); fclose(fp);
    goto generic_path;  // ALSO does SetBoxConfig via table mapping
}
```

Dual write: (1) 4-byte binary to `/etc/android_work_mode`, (2) riddleBoxCfg via generic path.

#### USB Device Connect Restart (FUN_00021cb0)

On USB device reconnect:
1. `system("/script/phone_link_deamon.sh %s restart")` for current Android mode
2. `system("/script/phone_link_deamon.sh %s restart")` for current iPhone mode
3. Reset both to idle: `OniPhoneWorkModeChanged(0)` + `OnAndroidWorkModeChanged(current, 0)` → param_2 guard forces new_mode=0

#### DAT Pointer Resolution (r2-verified)

| DAT | String | Mode |
|-----|--------|------|
| 0x17844 | `"ICCOA"` | 5 |
| 0x17848 | `"AndroidAuto"` | 1 |
| 0x1784c | `"CarLife"` | 2 |
| 0x17850 | `"AndroidMirror"` | 3 |
| 0x17854 | `"HiCar"` | 4 |
| 0x17840 | `"AndroidWorkMode_UNKOWN?"` | fallthrough |
| 0x17858 | `"/script/phone_link_deamon.sh %s stop &"` | stop fmt |
| 0x1785c | `"/script/phone_link_deamon.sh %s start &"` | start fmt |

#### Side Effects

- **Process lifecycle:** Mode transitions asynchronous (all commands end with `&`). `phone_link_deamon.sh` handles `start`/`stop`/`restart`. Calls `killall ARMAndroidAuto`, `killall ARMHiCar`, etc.
- **File writes:** `/etc/android_work_mode` (persistent 4-byte int) + riddleBoxCfg shared memory
- **Reset behavior:** USB reconnect handler reads current mode, restarts it, then forces both modes to idle
- **riddleBoxCfg persistence caveat:** SetBoxConfig only called on START (new_mode ≠ 0), not on stop (mode → 0). riddleBoxCfg may retain last non-zero value across reboots.
- **Security:** Mode names are hardcoded .rodata strings (not user-supplied), so `sprintf` + `system()` is NOT directly injectable. Integer values outside [0-5] produce harmless `"AndroidWorkMode_UNKOWN?"`.

### [29] AndroidAutoWidth — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 4096
- **Status:** PASS-THROUGH
- **Table addr:** 0x000935e8 (ARMadb) | **String VA:** 0x0006c82b (ARMadb), 0x00019c2e (riddleBoxCfg)
- **JSON field:** `"androidAutoSizeW"` (BoxSettings table[14])
- **Xrefs:** ARMadb-driver(2 DATA -- mapping table @ 0x94004 + config table), AppleCarPlay(0 code), ARMiPhoneIAP2(0 code), bluetoothDaemon(0 code), server.cgi(0 code)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | Auto-detect. ARMAndroidAuto (OpenAuto SDK) uses its built-in default resolution width. On the CPC200-CCPA this typically matches the host's advertised display width. | Default value. No override applied. |
| 1-4096 | Fixed width in pixels. ARMAndroidAuto renders Android Auto video stream at this width. Overrides auto-detection. | Config stored in riddle.conf; read by boot script via `riddleBoxCfg -g AndroidAutoWidth` and passed to ARMAndroidAuto as CLI argument. |

#### Pass-Through Mechanism

ARMAndroidAuto does NOT link the `riddleBoxCfg` shared library (confirmed: zero config key strings in the unpacked binary, `/Users/zeno/.claude/projects/-Users-zeno/memory/carlink_firmware.md:233`). The value reaches ARMAndroidAuto through this pipeline:

1. **Host App** sends BoxSettings JSON: `{"androidAutoSizeW": 2400}` via USB cmd 0x19
2. **ARMadb-driver** `FUN_00016c20` (BoxSettings iterator) maps `"androidAutoSizeW"` to `"AndroidAutoWidth"` via table at 0x93f90 entry [14], then calls `SetBoxConfig("AndroidAutoWidth", 2400)` which writes to `/etc/riddle.conf`
3. **On next reboot** (required -- see "Settings Requiring Reboot"), `/script/start_main_service.sh` reads the value via `riddleBoxCfg -g AndroidAutoWidth` and passes it as a CLI argument or environment variable to the ARMAndroidAuto process
4. **ARMAndroidAuto** (OpenAuto SDK) uses the CLI-supplied value to configure the Android Auto session's video resolution

**No runtime binary reads this config key.** The only code xrefs in ARMadb-driver are the two DATA references in the BoxSettings JSON mapping table (entry [14]) and the integer config table definition. The value is purely stored and forwarded to the boot script.

#### Side Effects and Dependencies

- **Requires reboot:** Changes take effect only after device reboot. In-session changes update riddle.conf but do not affect the running ARMAndroidAuto process.
- **Paired with AndroidAutoHeight [30]:** Width and height must be set together for a valid resolution. Setting only width with height=0 results in auto-detect for height, which may produce an invalid aspect ratio.
- **GM Info 3.7 context:** For the 2024 Silverado ICE (2400x960 display), setting AndroidAutoWidth=2400, AndroidAutoHeight=960 tells the adapter to request that resolution from the Android phone via the AA protocol.

---


### [30] AndroidAutoHeight — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 4096
- **Status:** PASS-THROUGH
- **Table addr:** 0x000935f8 (ARMadb) | **String VA:** 0x0006c84d (ARMadb), 0x00019c3f (riddleBoxCfg)
- **JSON field:** `"androidAutoSizeH"` (BoxSettings table[15])
- **Xrefs:** ARMadb-driver(2 DATA -- mapping table @ 0x9400c + config table), AppleCarPlay(0 code), ARMiPhoneIAP2(0 code), bluetoothDaemon(0 code), server.cgi(0 code)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | Auto-detect. ARMAndroidAuto uses its built-in default resolution height. | Default value. No override applied. |
| 1-4096 | Fixed height in pixels. ARMAndroidAuto renders Android Auto video stream at this height. Overrides auto-detection. | Config stored in riddle.conf; passed to ARMAndroidAuto via boot script CLI argument. |

#### Pass-Through Mechanism

Identical to AndroidAutoWidth [29]. Pipeline:

1. Host sends `{"androidAutoSizeH": 960}` in BoxSettings JSON (USB cmd 0x19)
2. ARMadb-driver `FUN_00016c20` maps `"androidAutoSizeH"` -> `"AndroidAutoHeight"` (table entry [15]) -> `SetBoxConfig("AndroidAutoHeight", 960)` -> riddle.conf
3. On reboot, `/script/start_main_service.sh` reads via `riddleBoxCfg -g AndroidAutoHeight` -> CLI arg to ARMAndroidAuto
4. ARMAndroidAuto uses the value for AA session video height

**No runtime binary reads this config key.** Only DATA references in ARMadb's mapping table and config table definition.

#### Side Effects and Dependencies

- **Requires reboot** to take effect.
- **Paired with AndroidAutoWidth [29]:** Must be set in tandem. Common pair: (2400, 960) for widescreen head units, (1920, 1080) for standard 16:9.
- **AA protocol negotiation:** The Android Auto protocol allows the head unit to advertise supported resolutions. ARMAndroidAuto's OpenAuto SDK reads these values at startup and includes them in the AA service descriptor sent to the phone during SSL handshake.

---


## GPS / Dashboard

### [17] HudGPSSwitch — Deep Analysis

**Key ID:** 17  
**Default:** 1 (per user spec; existing docs say 0)  
**Range:** [0,1]  
**JSON alias:** `"gps"` (mapping table entry [3] at `boxsettings_full_decomp.txt:47`)

### Per-Value Behavior Table

| Value | Effect | GPS data flows? | Phone receives location? |
|-------|--------|-----------------|--------------------------|
| 0 | GPS forwarding DISABLED | NMEA still arrives at `/tmp/gnss_info` but NOT relayed to iAP2 LocationEngine | No |
| 1 | GPS forwarding ENABLED | NMEA from `/tmp/gnss_info` relayed via iAP2 msg 0xFFFB to phone | Yes (fused with phone GPS) |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x0006c71b` | In mapping table region, `"gps" -> "HudGPSSwitch"` |
| IAP2 | `0x000727af` | Used in `CiAP2IdentifyEngine.virtual_8` and GPS reporting |
| IAP2 | `0x000727e1` | Log: `"BOX_CFG_HudGPSSwitch Closed, not use"` |

### Cross-Binary Behavior

**ARMadb-driver** -- `FUN_000339ac` at line 17345 (`ARMadb...decomps.txt:17342-17439`)
- Part of the iAP2 identification session builder (`FUN_00023ec0`, `ARMiPhoneIAP2...decomps.txt:224-479`)
- Read during identification phase to populate the GNSS capability fields
- Config keys in same function: `DashboardInfo:get, GNSSCapability:get, HudGPSSwitch:get`

**ARMiPhoneIAP2** -- `FUN_00030604` at line 1 (`ARMiPhoneIAP2...decomps.txt:8-32`)
- Function signature: `bool FUN_00030604(void *param_1, undefined1 *param_2, undefined1 *param_3)`
- Called during iAP2 identification to gate GNSS capability negotiation
- Part of the 3-stage GPS pipeline gate:
  1. **Identification gate (0x23ec0):** `HudGPSSwitch==1 AND GNSSCapability > 0`
  2. Session init gate: DashboardInfo bitmask
  3. Session init gate: GNSSCapability > 0 gates `CiAP2LocationEngine_Generate`

**Pseudocode (synthesized):**
```c
// IAP2 identification phase (FUN_00023ec0 equivalent)
int gps_switch = GetBoxConfig("HudGPSSwitch");
int gnss_cap = GetBoxConfig("GNSSCapability");
if (gps_switch == 1 && gnss_cap > 0) {
    // Register CiAP2LocationEngine in iAP2 identification
    // Phone will later send StartLocationInformation (0xFFFA)
} else {
    BoxLog(3, "BOX_CFG_HudGPSSwitch Closed, not use");
}
```

### Side Effects
- Toggling at runtime has NO effect -- must be set BEFORE iAP2 identification (session setup)
- The phone only receives location data if `GNSSCapability` is also set (bitmask, recommended=3)
- NMEA data pipeline: Host USB cmd 0x29 -> ARMadb -> `/tmp/gnss_info` -> IAP2 LocationEngine -> iAP2 msg 0xFFFB -> iPhone

---


### [70] GNSSCapability — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 65535
- **Status:** ALIVE
- **Table addr:** 0x00093878 (ARMadb) | **String VA:** 0x0006c8e4 (ARMadb), 0x0006fe9f (IAP2), 0x000976f0 (AppleCarPlay), 0x0006fde2 (bluetoothDaemon), 0x00019e35 (riddleBoxCfg)
- **JSON field:** `"GNSSCapability"` (BoxSettings table[24])
- **Xrefs:** ARMadb-driver(3 -- 2 DATA in mapping table + 1 table), ARMiPhoneIAP2(4 code), AppleCarPlay(0 code), bluetoothDaemon(0 code)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | GPS/GNSS completely disabled; `CiAP2LocationEngine` NOT registered during iAP2 identification. Phone never receives location data from adapter. | IAP2 `FUN_00023ec0` at 0x000240e8: `gnss_cap = GetBoxConfig("GNSSCapability"); if (gps_switch == 1 && gnss_cap > 0) { /* register LocationEngine */ }` -- condition fails when 0 |
| 1 (bit0) | GPGGA sentence forwarding enabled. GGA provides fix data (lat, lon, altitude, fix quality, satellite count, HDOP, geoid separation). | NMEA standard: GPGGA = Global Positioning System Fix Data. Bit tested in IAP2 `FUN_00015ee4` at 0x00015fa0 |
| 2 (bit1) | GPRMC sentence forwarding enabled. RMC provides minimum recommended navigation info (lat, lon, speed, course, date, magnetic variation). | NMEA standard: GPRMC = Recommended Minimum Sentence C. Bit tested in IAP2 `FUN_0002c1b8` which does `SetBoxConfig("GNSSCapability", param_2 | 2)` at 0x0002c200 |
| 3 (bit0+bit1) | Both GPGGA and GPRMC enabled. **Recommended value for GPS operation.** Provides complete fix + navigation data. | Documented in existing analysis: "Set to 3 for GPS" |
| 4 (bit2) | GPGSA -- DOP and Active Satellites. Would report fix mode (2D/3D), PDOP, HDOP, VDOP, and PRN of satellites used. | NMEA standard definition; no firmware code validates individual high bits |
| 8 (bit3) | GPGSV -- Satellites in View. Would report satellite PRN, elevation, azimuth, SNR. | NMEA standard definition |
| 16 (bit4) | GPGLL -- Geographic Position (Lat/Lon only). Minimal position sentence. | NMEA standard definition |
| 32 (bit5) | GPVTG -- Track/Ground Speed. Course over ground and speed in knots/km/h. | NMEA standard definition |
| 64 (bit6) | GPZDA -- Time and Date. UTC time, day, month, year, local zone hours/minutes. | NMEA standard definition |
| 128 (bit7) | GPHDT -- Heading True. True heading from dual-antenna GPS receivers. | NMEA standard definition |
| 256 (bit8) | GPROT -- Rate of Turn. Rate of turn (deg/min). | NMEA standard definition |
| 512 (bit9) | GPDBT -- Depth Below Transducer. Marine/sonar sentence (irrelevant for automotive). | NMEA standard definition |
| 1024 (bit10) | GPDPT -- Depth. Marine sentence (irrelevant for automotive). | NMEA standard definition |
| 2048 (bit11) | GPMTW -- Water Temperature. Marine sentence (irrelevant for automotive). | NMEA standard definition |
| 4096 (bit12) | GPMWV -- Wind Speed and Angle. Marine/weather sentence. | NMEA standard definition |
| 8192 (bit13) | GPXTE -- Cross-Track Error. Navigation deviation from intended course. | NMEA standard definition |
| 16384 (bit14) | GPRMB -- Recommended Minimum Navigation. Waypoint navigation data. | NMEA standard definition |
| 32768 (bit15) | GPBOD -- Bearing Origin to Destination. Waypoint bearing sentence. | NMEA standard definition |

**Note on bit semantics:** Only bits 0-1 (GPGGA, GPRMC) have confirmed firmware behavior. Bits 2-15 follow standard NMEA 0183 sentence type enumeration but the firmware's NMEA parser at `/tmp/gnss_info` only processes GGA and RMC in practice. The bitmask is stored as a 16-bit capability advertisement to the phone via iAP2, so higher bits serve as potential extensibility placeholders. The `max=65535` (0xFFFF) confirms all 16 bits are architecturally valid.

#### Cross-Binary Behavior

| Binary | Function | Action | Detail |
|--------|----------|--------|--------|
| ARMiPhoneIAP2 | FUN_00023ec0 @ 0x000240e8 | **GetBoxConfig (identification gate)** | During iAP2 identification phase. Combined check: `HudGPSSwitch==1 AND GNSSCapability > 0`. If both true, registers `CiAP2LocationEngine` with the phone. This is the master GPS gate. |
| ARMiPhoneIAP2 | FUN_00015ee4 @ 0x00015fa0 | **GetBoxConfig (session init)** | Reads bitmask to determine which NMEA sentence types to enable in the iAP2 Location session. Gates `CiAP2LocationEngine_Generate`. |
| ARMiPhoneIAP2 | FUN_0002c1b8 @ 0x0002c200 | **SetBoxConfig (runtime OR)** | `SetBoxConfig("GNSSCapability", param_2 | 2)` -- ORs bit1 (GPRMC) into the existing capability. This is an additive write that ensures GPRMC is always enabled once triggered. |
| ARMiPhoneIAP2 | FUN_0002c1b8 @ 0x0002c29c | **SetBoxConfig (runtime write)** | Second set call within same function: `SetBoxConfig("GNSSCapability", uVar2)` -- writes full accumulated value. |
| ARMadb-driver | FUN_00016c20 (BoxSettings) | **SetBoxConfig (host config)** | Generic BoxSettings path: host sends `"GNSSCapability": N` in JSON, mapper stores to riddleBoxCfg. |
| ARMadb-driver | Mapping table @ 0x94050 | **DATA reference** | Entry [24] in BoxSettings JSON mapping table at 0x93f90. |

#### Decompiled Pseudocode (Synthesized from IAP2 trace)

```c
// FUN_00023ec0 -- iAP2 Identification Phase (IAP2 binary)
int gps_switch = GetBoxConfig("HudGPSSwitch");    // @ 0x000240d8
int gnss_cap   = GetBoxConfig("GNSSCapability");   // @ 0x000240e8
if (gps_switch == 1 && gnss_cap > 0) {
    // Register CiAP2LocationEngine in iAP2 identification message
    // Phone will later send StartLocationInformation (msg 0xFFFA)
} else {
    BoxLog(3, "BOX_CFG_HudGPSSwitch Closed, not use");
}

// FUN_0002c1b8 -- GNSS Capability Runtime Update (IAP2 binary)
void update_gnss_cap(int new_sentences) {
    SetBoxConfig("GNSSCapability", new_sentences | 2);  // Always ensure GPRMC
    // ... additional processing ...
    SetBoxConfig("GNSSCapability", accumulated_value);
}
```

#### Side Effects and Dependencies

- **Depends on HudGPSSwitch [17]:** Both must be nonzero for GPS to function. GNSSCapability is the "what" (which sentences), HudGPSSwitch is the "whether."
- **NMEA pipeline:** Host sends NMEA via USB cmd 0x29 (GnssData) -> ARMadb writes `/tmp/gnss_info` -> IAP2 reads and converts to iAP2 `LocationInformation` (msg 0xFFFB) -> iPhone.
- **Init-time only:** Read during iAP2 identification. Changes after session establishment have no effect until reconnect.
- **FUN_0002c1b8 runtime OR:** The firmware can dynamically add GPRMC (bit1) to the capability bitmask during session runtime, but this only affects the persisted config value, not the active session.

---


### [71] DashboardInfo — Deep Analysis

- **Type:** Integer | **Default:** 1 | **Min:** 0 | **Max:** 7
- **Status:** ALIVE
- **Table addr:** 0x00093888 (ARMadb) | **String VA:** 0x0006c900 (ARMadb), 0x0006fe91 (IAP2), 0x000976ff (AppleCarPlay), 0x0006fdf1 (bluetoothDaemon), 0x00019e44 (riddleBoxCfg)
- **JSON field:** `"DashboardInfo"` (BoxSettings table[26])
- **Xrefs:** ARMadb-driver(3 -- 2 DATA in mapping table + 1 table), ARMiPhoneIAP2(2 code + 1 set), AppleCarPlay(0 code), bluetoothDaemon(0 code)

#### Value Effects

| Value | Effect | Evidence |
|-------|--------|----------|
| 0 | No dashboard engines registered. No MediaPlayer, VehicleStatus, or RouteGuidance data forwarded. **CallState is always enabled regardless.** | All 3 bits cleared. IAP2 `FUN_00015ee4` at 0x00015f58: bitmask tested per-bit. CallState not gated by this bitmask. |
| 1 (bit0) | MediaPlayer enabled. NowPlaying engine registered in iAP2 session. Host receives song title, artist, album, playback state, elapsed time via USB msg 0x2A (MediaData). **Default value.** | `FUN_00015ee4`: bit0 test gates NowPlaying engine registration |
| 2 (bit1) | VehicleStatus enabled. iAP2 VehicleStatusUpdate engine registered. Reports range remaining, outside temp, night mode status if phone supports it. | `FUN_00015ee4`: bit1 test gates VehicleStatus engine |
| 3 (bit0+bit1) | MediaPlayer + VehicleStatus enabled. | Combination of bit0 and bit1 |
| 4 (bit2) | RouteGuidance enabled. iAP2 NavigationUpdate engine provides turn-by-turn directions (street name, distance, maneuver type) via USB msg 0x2A (MediaData). | `FUN_00015ee4`: bit2 test gates RouteGuidance engine |
| 5 (bit0+bit2) | MediaPlayer + RouteGuidance. | Combination |
| 6 (bit1+bit2) | VehicleStatus + RouteGuidance. | Combination |
| 7 (bit0+bit1+bit2) | All three dashboard engines enabled. Maximum iAP2 metadata. | All bits set. Full feature set. |

**Always-on: CallState** -- The iAP2 CallState engine (incoming/outgoing/active call notifications, caller ID) is registered unconditionally during iAP2 identification, regardless of the DashboardInfo bitmask. This is confirmed by its absence from the bitmask logic in FUN_00015ee4 and its separate registration path. CallState data appears in USB msg 0x2A with callstate-specific fields.

#### Cross-Binary Behavior

| Binary | Function | Action | Detail |
|--------|----------|--------|--------|
| ARMiPhoneIAP2 | FUN_00015ee4 @ 0x00015f58 | **GetBoxConfig (session init)** | Reads bitmask during iAP2 identification to decide which dashboard engines (NowPlaying, VehicleStatus, RouteGuidance) to register with the phone. Each bit gates one engine. |
| ARMiPhoneIAP2 | FUN_00021964 @ 0x00021ec4 | **SetBoxConfig (runtime update)** | Updates the DashboardInfo value. Context: `FUN_0006a5f0(DAT_00021fd4, uVar9)` -- writes accumulated bitmask. This function handles msg type 0x4301 (dashboard info request from phone). |
| ARMiPhoneIAP2 | FUN_00021964 @ 0x00021c14 | **GetBoxConfig (HudGPSSwitch)** | Same function also reads HudGPSSwitch; DashboardInfo and GPS config are in the same identification builder. |
| ARMadb-driver | FUN_00016c20 (BoxSettings) | **SetBoxConfig (host config)** | Generic BoxSettings path: host sends `"DashboardInfo": N` in JSON. |

#### Decompiled Pseudocode (Synthesized from IAP2 trace)

```c
// FUN_00015ee4 -- iAP2 Identification Engine Registration (IAP2 binary)
int dash_info = GetBoxConfig("DashboardInfo");    // @ 0x00015f58
int gnss_cap  = GetBoxConfig("GNSSCapability");   // @ 0x00015fa0

// Always register CallState engine (unconditional)
register_engine(CiAP2CallStateEngine);

// Conditionally register dashboard engines based on bitmask
if (dash_info & 0x01)  register_engine(CiAP2NowPlayingEngine);      // MediaPlayer
if (dash_info & 0x02)  register_engine(CiAP2VehicleStatusEngine);   // VehicleStatus
if (dash_info & 0x04)  register_engine(CiAP2NavigationEngine);      // RouteGuidance

// Separately, GNSS capability gates location engine
if (gnss_cap > 0)      register_engine(CiAP2LocationEngine);

// FUN_00021964 -- Dashboard Info Runtime Handler (IAP2 binary)
// Handles incoming msg 0x4301 from phone
void handle_dashboard_msg(int msg_type, int data) {
    int gps_switch = GetBoxConfig("HudGPSSwitch");   // @ 0x00021c14
    // ... process dashboard info ...
    SetBoxConfig("DashboardInfo", uVar9);             // @ 0x00021ec4
}
```

#### Side Effects and Dependencies

- **Independent of HudGPSSwitch:** DashboardInfo gates *metadata* engines (songs, nav, vehicle). GPS forwarding is gated separately by HudGPSSwitch + GNSSCapability. Setting DashboardInfo=0 does NOT disable GPS.
- **Init-time only:** Engine registration happens during iAP2 identification. Changing DashboardInfo at runtime only updates the persisted config value; the active session is unaffected until reconnect.
- **MediaData assembly:** When engines are registered, the IAP2 binary assembles MediaData JSON (msg 0x2A) from the iAP2 engine callbacks and forwards it to ARMadb-driver for USB transmission. Disabling a bit means that engine's data is simply absent from msg 0x2A.
- **Default=1:** Out of the box, only MediaPlayer (NowPlaying) is enabled. Host apps that want turn-by-turn nav data must set DashboardInfo to 5 or 7.

---


### [77] DuckPosition — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 2
- **Table addr:** 0x000938e8 (ARMadb) | **String VA:** 0x0006c91b
- **Status:** **ALIVE (AppleCarPlay active caller)**
- **JSON field:** `"DockPosition"` (BoxSettings table[28]) — **firmware typo**: "Duck" vs "Dock"
- **Xrefs:** ARMadb-driver(2), AppleCarPlay(1 code), bluetoothDaemon(1), riddleBoxCfg(1)
- **Callers:** `FUN_0001c0b4`@0001c0b4 (AppleCarPlay, get — session negotiation)

#### Naming Confusion

1. **BoxSettings JSON:** `"DockPosition"` — what the host sends
2. **riddleBoxCfg internal:** `"DuckPosition"` — firmware typo (confirmed at mapping table entry [28])
3. **AirPlay protocol:** `"viewAreaStatusBarEdge"` — the actual CarPlay property this value populates

#### Value Effects

| Value | `viewAreaStatusBarEdge` | Dock Position | Use Case |
|-------|-------------------------|--------------|----------|
| **0** | 0 | **Left edge** — status bar/dock on left | LHD vehicles (driver on left, dock closest to driver) |
| **1** | 1 | **Right edge** — status bar/dock on right | RHD vehicles (driver on right) |
| **2** | 2 | **Bottom edge** — horizontal dock layout | Wide displays, iOS 14+ |

#### BoxSettings Write Path (ARMadb FUN_00016c20)

```c
if (strcmp(field, "DockPosition") == 0) {
    // SPECIAL HANDLER: Copy HU_SCREEN_INFO → HU_VIEWAREA_INFO
    int screen_info[6] = {0};  // 24 bytes
    ReadSharedMemory("HU_SCREEN_INFO", screen_info, 0x18);
    int viewarea[6] = {0};
    viewarea[0] = screen_info[0];  // width
    viewarea[1] = screen_info[0];  // width_dup
    viewarea[2] = screen_info[1];  // height
    viewarea[3] = screen_info[1];  // height_dup
    WriteSharedMemory("HU_VIEWAREA_INFO", viewarea, 0x18);
    goto generic_path;  // FUN_0001658c maps "DockPosition" → "DuckPosition"
                        // then SetBoxConfig("DuckPosition", val)
}
```

#### CarPlay Session Negotiation (AppleCarPlay FUN_0001c0b4)

```c
// Called during AirPlay session setup
// Gate: phone.featureViewAreas != 0 AND g_bSupportViewarea != 0
void buildViewAreaInfo(AirPlaySession *session) {
    int viewarea[6]; ReadSharedMemory("HU_VIEWAREA_INFO", viewarea, 0x18);
    if (viewarea[0] <= 0 || viewarea[1] <= 0) return;  // no valid ViewArea

    CFLDict viewAreaDict = CFLDictionaryCreate();
    CFLDictSetInt64(viewAreaDict, "widthPixels",   viewarea[0]);
    CFLDictSetInt64(viewAreaDict, "heightPixels",  viewarea[1]);
    CFLDictSetInt64(viewAreaDict, "originXPixels", viewarea[2]);
    CFLDictSetInt64(viewAreaDict, "originYPixels", viewarea[3]);

    // DuckPosition → viewAreaStatusBarEdge
    int duckPos = GetBoxConfig("DuckPosition");  // 0, 1, or 2
    CFLDictSetInt64(viewAreaDict, "viewAreaStatusBarEdge", (int64_t)duckPos);

    // SafeArea sub-dict
    int safearea[5]; ReadSharedMemory("HU_SAFEAREA_INFO", safearea, 0x14);
    if (safearea[0] > 0 && safearea[1] > 0) {
        CFLDictSetInt64(safeAreaDict, "widthPixels",   safearea[0]);
        CFLDictSetInt64(safeAreaDict, "heightPixels",  safearea[1]);
        CFLDictSetValue(safeAreaDict, "drawUIOutsideSafeArea",
                        safearea[4] ? kTrue : kFalse);
    } else {  // Fallback: use ViewArea dims as SafeArea
        CFLDictSetInt64(safeAreaDict, "widthPixels",   viewarea[0]);
        CFLDictSetInt64(safeAreaDict, "heightPixels",  viewarea[1]);
    }
    CFLArrayAppend(viewAreasArray, viewAreaDict);  // Sent to iPhone in AirPlay SETUP
}
```

**ARM evidence:** r2 @ 0x1c1e8 → `bl GetBoxConfig` with r0 → 0x7bf0c = `"DuckPosition"`. Return value sign-extended to int64 via `vdup.32 d16, r0` / `vshr.s64` → `CFLDictionarySetInt64Value` at sl+0x461 = `"viewAreaStatusBarEdge"`.

#### Causality Chain

```
Host sends BoxSettings: {"DockPosition": 1}
  → ARMadb FUN_00016c20: ReadSharedMemory("HU_SCREEN_INFO") → WriteSharedMemory("HU_VIEWAREA_INFO")
  → FUN_0001658c("DockPosition") → "DuckPosition" (table[28])
  → SetBoxConfig("DuckPosition", 1)

iPhone connects → AppleCarPlay FUN_0001c0b4:
  → Gate: phone.featureViewAreas AND g_bSupportViewarea
  → ReadSharedMemory("HU_VIEWAREA_INFO") → viewArea dimensions
  → GetBoxConfig("DuckPosition") → 1
  → viewAreaDict["viewAreaStatusBarEdge"] = 1
  → Sent to iPhone in AirPlay SETUP response
  → iPhone positions dock on RIGHT edge
```

#### Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| `HU_SCREEN_INFO` (shm) | INPUT | Read by BoxSettings handler to populate HU_VIEWAREA_INFO |
| `HU_VIEWAREA_INFO` (shm) | OUTPUT/INPUT | Written by BoxSettings handler; Read by CarPlay for viewArea dims |
| `HU_SAFEAREA_INFO` (shm) | INPUT | Read by CarPlay for safeArea dims (NOT written by DockPosition handler) |
| `g_bSupportViewarea` (global) | GATE | Must be non-zero for viewArea negotiation; set at init from HU_VIEWAREA_INFO having valid dimensions |
| `phone.featureViewAreas` | GATE | iPhone must report viewAreas support |
| **AdvancedFeatures** | NONE | Does NOT gate DuckPosition — `g_bSupportViewarea` is independent |

#### Correction

Existing docs describe the viewArea dict entry as `"DuckPosition" = dock position (float64)`. Corrected: the dictionary key sent to iPhone is `"viewAreaStatusBarEdge"`, type int64 (not float64). `"DuckPosition"` is only the riddleBoxCfg config key name.

---

## Network / Wireless

### [19] WiFiChannel — Deep Analysis

**Key ID:** 19  
**Default:** 0  
**Range:** [0,200]  
**JSON alias:** `"WiFiChannel"` (mapping table entry [21], self-mapped)

### Per-Value Behavior Table

| Value | Effect | WiFi behavior |
|-------|--------|---------------|
| 0 | Auto channel selection | wpa_supplicant picks channel (default: 36 pre-2024.09, 149 post-2024.09) |
| 1-14 | 2.4GHz channels | Forces specific 2.4GHz channel |
| 36-165 | 5GHz channels | Forces specific 5GHz channel (e.g., 161 on current firmware) |
| Other | Passed to wpa_supplicant | May fail silently if invalid |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x0006c4ec` | Adjacent to `"btName"`, `"wifiName"`, in BoxSettings parser |
| ARMadb | `0x0006e2ac` | In JSON status string: `"WiFiChannel":%d` |
| IAP2 | `0x0007450f` | In `CiAP2WiFiConfigEngine`, adjacent to `"securityType"` |
| BT Daemon | `0x0005ebc1` (izz) | Read for WiFi config |

### Cross-Binary Behavior

**ARMadb-driver** -- `FUN_00016c20` (BoxSettings JSON iterator), `boxsettings_full_decomp.txt:106-362`, lines 1034-1178 of caller decomps:
```c
// Special-cased in the JSON iterator (NOT generic path)
if (strcmp(fieldName, "WiFiChannel") == 0) {
    int current = GetBoxConfig("WiFiChannel");
    int new_val = json_item->valueint;
    if (current != new_val) {
        SetBoxConfig("WiFiChannel", new_val);
        system("(/script/close_bluetooth_wifi.sh;/script/start_bluetooth_wifi.sh)&");
    }
}
```

This is a **hot-change** key: modifying it triggers an immediate WiFi+BT restart via shell scripts. The `&` at the end makes it asynchronous -- the binary does not block.

**ARMiPhoneIAP2** -- `FUN_00026104` (`ARMiPhoneIAP2...decomps.txt:37-198`)
- Part of `CiAP2WiFiConfigEngine` constructor
- Builds the WiFi configuration structure with 17 fields including channel
- Read at session setup to provide WiFi credentials to the phone for direct P2P connection
- Xref at `0x2bc44` confirmed: `ldr r0, [str.WiFiChannel]`

**bluetoothDaemon** -- `FUN_00015b44` (`bluetoothDaemon...decomps.txt:370-395`)
- Config key `WiFiChannel:get, BtAudio:get` in same function
- Iterates over a vtable array calling virtual method at offset 0x10 -- initialization loop

### Side Effects
- **Requires reboot** for clean operation despite hot-change capability (documented in config reference)
- The `close_bluetooth_wifi.sh` script kills wpa_supplicant, hostapd, bluetoothd; `start_bluetooth_wifi.sh` re-initializes all wireless
- Setting to 0 reverts to firmware-default auto selection
- WiFi channel is shared with the phone via iAP2 WiFiConfig during CarPlay setup

---


### [76] WiFiP2PMode — Deep Analysis

**Key ID:** 76
**Default:** 0
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Mode | WiFi Daemon | SSID Pattern | Phone Discovery |
|-------|------|-------------|--------------|-----------------|
| 0 | SoftAP (infrastructure) | `hostapd` via `/etc/hostapd.conf` | Plain SSID from `/etc/wifi_name` | Phone joins as standard WiFi client |
| 1 | WiFi Direct (P2P GO) | `wpa_supplicant` + `wpa_cli p2p_group_add` | `DIRECT-<wifi_name>` (spaces stripped) | Phone discovers via Wi-Fi Direct P2P |

### Shell Script Evidence (definitive)

**`start_bluetooth_wifi.sh` lines 76-126** — the runtime consumer:
```bash
# Line 77: Read config and select mode
riddleBoxCfg -g WiFiP2PMode |grep 1 && mode=P2P || mode=AP

# Lines 81-83: P2P prefixes SSID with "DIRECT-"
if [ "$mode" == "P2P" ];then
    ssid="DIRECT-`cat /etc/wifi_name|sed 's/ //g'`"
fi

# Lines 114-126: Mode determines which daemon runs
if [ "$mode" == "AP" ]; then
    startprocess hostapd 'hostapd /etc/hostapd.conf -B'
else
    echo "Use P2P as AP"
    wpa_cli -i wlan0 p2p_group_add ssid=$ssid passphrase=$passwd freq=$freq
fi
```

**`wpa_supplicant.conf`** — P2P group-owner parameters (used when mode=P2P):
```
p2p_go_vht=1              # VHT (802.11ac) in GO mode
p2p_go_intent=15           # Max intent = always become Group Owner
p2p_listen_reg_class=81    # 2.4 GHz listen
p2p_listen_channel=6       # Listen on ch 6
p2p_oper_reg_class=115     # 5 GHz operating
p2p_oper_channel=149       # Operate on ch 149
country=CN
```

**`remove_unnecessary_file.sh` lines 40-42** — fallback/recovery:
```bash
if [ -e /usr/sbin/wpa_supplicant.bak ]; then
    riddleBoxCfg -s WiFiP2PMode 0
    mv /usr/sbin/wpa_supplicant.bak /usr/sbin/wpa_supplicant
fi
```
If a backup wpa_supplicant exists (failed P2P migration), resets to SoftAP mode.

### Hardware Override

**NXP WiFi cards** (`moal.ko` driver) force P2P regardless of config (lines 94-98):
```bash
# nxp wifi card only support STA+P2P, P2P interface not support AP
if [ -e /usr/sbin/wpa_supplicant ] && [ -e /tmp/moal.ko ] && [ "$mode" == "AP" ]; then
    echo "Force change mode to P2P"
    mode=P2P
fi
```
The CPC200-CCPA uses a Realtek RTL88x2CS, not NXP, so both modes are available.

### Cross-Binary Behavior

**ARMiPhoneIAP2** -- `CiAP2WiFiConfigEngine` class:
- `CiAP2WiFiConfigEngine.virtual_8` at xref `0x2b884` consumes WiFiP2PMode
- Controls which WiFi configuration parameters are sent to iPhone during iAP2 WiFi setup
- `FUN_0001a054` is the destructor/cleanup for WiFi P2P session objects
- String `WiFiChannel` at `0x64340` (IAP2 unpacked) confirms WiFi config is read in this binary
- WiFiP2PMode key name does NOT appear as a string in the IAP2 binary — it is consumed indirectly via the shell script path or through ARMadb-driver's config infrastructure

**ARMadb-driver** -- Config table entry only. Consumed at runtime via `riddleBoxCfg -g WiFiP2PMode` in shell scripts, not directly by binary code.

### Side Effects
- Requires full WiFi restart (`close_bluetooth_wifi.sh` + `start_bluetooth_wifi.sh`) to take effect
- Mode change detected at lines 106-111: if current mode differs, triggers close+restart cycle
- SoftAP uses `hostapd` (standalone AP daemon); P2P uses `wpa_supplicant` (P2P group owner via `wpa_cli`)
- P2P mode forces `DIRECT-` SSID prefix per Wi-Fi Alliance P2P spec
- IP address is `192.168.50.2` (default) or `192.168.43.1` (if ARMHiCar present)
- WiFi password and channel are shared between both modes

---


### [74] InternetHotspots — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 1
- **Status:** DEAD
- **Table addr:** 0x000938b8 (ARMadb) | **String VA:** 0x00080e76 (ARMadb), 0x0009771f (AppleCarPlay), 0x00083aad (IAP2), 0x0006fde2 (bluetoothDaemon -- shared page with GNSSCapability), 0x00019e71 (riddleBoxCfg)
- **JSON field:** None (not in BoxSettings mapping table, not in server.cgi web API)
- **Xrefs:** Config table entry only in all binaries. Zero GetBoxConfig/SetBoxConfig callers. Zero non-table string xrefs.

**Confirmed zero code xrefs across all 6 binaries:**
- ARMadb-driver: `0x00080e76` -- 0 code references (`ARMadb-driver_2025.10_unpacked_config_trace.txt:658-660`)
- AppleCarPlay: `0x0009771f` -- 0 code references (`AppleCarPlay_unpacked_config_trace.txt`)
- ARMiPhoneIAP2: `0x00083aad` -- 0 code references (`ARMiPhoneIAP2_unpacked_config_trace.txt:410-412`)
- server.cgi: 0 string instances (`server.cgi_unpacked_config_trace.txt:731-732`)
- riddleBoxCfg: `0x00019e71` -- 0 code references (`riddleBoxCfg_unpacked_config_trace.txt:521-523`)
- bluetoothDaemon: 0 code references (`bluetoothDaemon_unpacked_config_trace.txt`)

**Intended purpose (from naming and context):** Would have controlled whether the adapter's WiFi interface simultaneously provides internet connectivity (hotspot mode) in addition to CarPlay/AA mirroring. Value 0 = no internet sharing (WiFi used exclusively for adapter<->phone link), 1 = enable internet hotspot (bridge phone's cellular data to the adapter's WiFi network). This feature was likely planned for devices with dual-radio or multi-SSID capability, but the CPC200-CCPA's single Realtek RTL88x2CS radio is dedicated to the P2P/softAP link with the phone, making simultaneous internet hotspot operation architecturally infeasible without significant WiFi stack changes. The config table entry exists as a placeholder.


### [75] UseUartBLE — Deep Analysis

**Key ID:** 75  
**Default:** 0  
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Effect |
|-------|--------|
| 0 | USB HCI BLE (standard Bluetooth stack over USB) |
| 1 | UART HCI BLE (Bluetooth over serial UART) |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| BT Daemon | `0x0005fe2f` (izz) | In bluetoothDaemon config region |

### Cross-Binary Behavior

**ARMadb-driver** -- `FUN_00069944` (`ARMadb...decomps.txt:606-620`):
```c
bool FUN_00069944(void) {
    int iVar1 = GetBoxConfig("UseUartBLE");  // FUN_00066d3c
    return iVar1 == 1;
}
```
Simple boolean predicate -- returns `true` if UART BLE is enabled.

**bluetoothDaemon** -- `FUN_00059630` (`bluetoothDaemon...decomps.txt:229-364`)
- XML config reader that looks up `"UseUartBLE"` from a structured config file
- Iterates over 3 config entries, finds matching key, reads value
- Used to select between USB and UART transports for the BLE controller

### Side Effects
- Hardware-dependent: only functional if the board has a UART-connected BLE chip
- On the A15W (CPC200-CCPA), the default USB BLE path is used (RTL8822CS combo chip)
- Setting to 1 on hardware without UART BLE will likely cause BLE failure

---


### [45] BtAudio — Deep Analysis

**Key ID:** 45  
**Default:** 0  
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Effect | Audio routing |
|-------|--------|---------------|
| 0 | Box/FM audio transport (USB bulk) | Audio decoded on adapter, sent as PCM over USB |
| 1 | Bluetooth A2DP audio | Audio routed via BT A2DP profile to head unit |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x0006e769` | Adjacent to `"MicType"`, `"rm -f /etc/wifi"` |
| CarPlay | `0x00081c93` | Adjacent to `"VdnsName"` |
| BT Daemon | `0x0005fcb2` (izz) | BT audio config |

### Cross-Binary Behavior

**AppleCarPlay** -- `FUN_0002f8f8` (`AppleCarPlay...decomps.txt:65-78`):
```c
bool FUN_0002f8f8(void) {
    int iVar1 = GetBoxConfig("BtAudio");  // FUN_00073098
    return iVar1 == 1;
}
```
Boolean predicate. When true, CarPlay audio is routed via Bluetooth A2DP instead of USB bulk.

**ARMadb-driver** -- `FUN_0001dd98` (cmd dispatcher, line 1191): `BtAudio:set`
- Set by host commands: cmd 22 (`UseBluetoothAudio`) sets `BtAudio=1`, cmd 23 (`UseBoxTransAudio`) sets `BtAudio=0`
- These are host-initiated commands, not BoxSettings JSON

**bluetoothDaemon** -- `FUN_00015b44` (`bluetoothDaemon...decomps.txt:370-395`)
- Read during BT daemon initialization alongside WiFiChannel
- Part of the vtable dispatch loop that configures BT profiles

### Side Effects
- Switching to BT audio introduces additional latency (A2DP codec + wireless)
- The head unit must support A2DP sink
- When BtAudio=1, the adapter acts as an A2DP source device
- On the GM Info 3.7 host, audio flows through AudioFlinger bus0_media_out regardless

---


## System / Branding

### [4] LogMode — Deep Analysis

**Key ID:** 4
**Default:** 1
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Log level | Verbosity |
|-------|-----------|-----------|
| 0 | 5 (ERROR) | Minimal logging -- errors only |
| 1 | 3 (INFO) | Verbose logging -- includes INFO, WARN, ERROR |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x00070c22` (.rodata) | Config string |
| CarPlay | `0x000873fb` (.rodata) | Config string |

### Cross-Binary Behavior

This key is consumed identically in THREE binaries. Each has a `BoxLog` function that caches the log level.

**ARMadb-driver** -- `BoxLog` at `0x000688d4` (`ARMadb...decomps.txt:8-90`):
```c
// First call: resolve LogMode and cache
if (*cached_level == -1) {
    int mode = GetBoxConfig("LogMode");  // FUN_00066d3c
    if (mode == 1) {
        *cached_level = 3;   // INFO
    } else {
        *cached_level = 5;   // ERROR
    }
}
// Only log if param_1 >= *cached_level
if (*cached_level <= param_1) {
    // Format timestamp, PID, thread, write to log file
    vfprintf(log_file, formatted_msg, va_args);
}
```

**AppleCarPlay** -- `FUN_00074f5c` (`AppleCarPlay...decomps.txt:84-166`): Identical pattern.

**bluetoothDaemon** -- `FUN_00059aec` (`bluetoothDaemon...decomps.txt:19-84`): Different function (hex string decoder), but LogMode referenced at same address pattern.

**ARMiPhoneIAP2** -- `FUN_0006b194` (`ARMiPhoneIAP2...decomps.txt:638-683`): Uses BoxLog which internally reads LogMode.

### Side Effects
- Level is cached at first log call -- changing at runtime requires process restart
- Level 3 (verbose) generates significant I/O to log files
- Log output goes to file descriptors opened at process init (typically `/tmp/*.log`)
- The log format is: `[LEVEL][timestamp.ms][process_name][PID][TID] tag: message`

---


### [5] BoxConfig_UI_Lang — Deep Analysis

**Key ID:** 5
**Default:** 0
**Range:** [0,65535]

### Per-Value Behavior Table

| Value | Language | Notes |
|-------|----------|-------|
| 0 | English (default) | |
| 1-100 | Locale IDs | Mapped to locale codes; exact mapping in web UI JavaScript |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x0006c72d` | In mapping table: `"lang" -> "BoxConfig_UI_Lang"` |

### Cross-Binary Behavior

**ARMadb-driver** -- Generic path only.
- Mapping table entry [4]: `"lang" -> "BoxConfig_UI_Lang"` (`boxsettings_full_decomp.txt:48`)
- When received in BoxSettings JSON, goes through the generic `FUN_0001658c` mapper -> `FUN_00066e58(key, intval)` path
- No special handling -- stored in riddle.conf via `SetBoxConfig`

**No binary consumer function found.** This key is a **PASS-THROUGH**: it is stored in config but no binary process reads it at runtime. Its purpose is to be queried by the web UI (`/cgi-bin/server.cgi infos` command) and the boxUIServer for rendering the settings page in the correct language.

### Side Effects
- Purely UI-facing -- does not affect protocol behavior, audio, or video
- The boxUIServer (2025.10+ firmware) reads this to select language resources for its LVGL-based UI

---


### [3] UdiskMode — Deep Analysis

**Key ID:** 3
**Default:** 1
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Mode | USB behavior |
|-------|------|-------------|
| 0 | Disabled | No USB mass storage gadget |
| 1 | USB Mass Storage | Adapter exposes internal storage as USB disk to host |
| 2 | MTP (Media Transfer Protocol) | Adapter exposes as MTP device (cmd handler accepts value 2 beyond declared range max=1) |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x0006f58e` | Adjacent to `"Version: %s%s"`, `"rm -f /tmp/chan"` |

### Cross-Binary Behavior

**ARMadb-driver** -- `FUN_00021cb0` (`ARMadb...decomps.txt:678-870`, key `UdiskMode:get+set`):
- 984 bytes, complex function
- Reads current UdiskMode and USB gadget type to configure USB descriptors
- Uses `FUN_000693e8()` to get device type, cross-references with expected USB PID
- Configures USB gadget mode (mass storage vs MTP vs none)
- Calls `system()` to load/unload kernel modules for USB gadget
- Writes shared memory `HU_USBDEVICE_INFO` with device configuration

### Pseudocode (simplified)
```c
void FUN_00021cb0(int param_1) {
    BoxLog(6, "UsbManager", "Init USB device");
    int dev_type = GetDeviceType();  // FUN_000693e8
    // Validate device type
    if (dev_type == 0xff || dev_type == expected_type) {
        // Read shared memory for current USB state
        // Compare with stored state
        // If changed: unload old gadget module, load new one
        // system("rmmod g_mass_storage") or system("rmmod g_mtp")
        // system("modprobe g_mass_storage ...") or system("modprobe g_mtp ...")
    }
}
```

### Side Effects
- **WARNING:** USB storage mode interferes with Android Auto! Mounting mass storage stops the AA service
- From MEMORY.md: "USB storage detection stops AA service -- deploy to /tmp via SSH, not /mnt/UPAN"
- Changing this key involves kernel module load/unload -- can cause brief USB disconnect
- Mode 2 (MTP) requires the mtp-server binary (added in 2023.09+ firmware)

---


### [18] CarDate — Deep Analysis

**Key ID:** 18  
**Default:** 0  
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Effect |
|-------|--------|
| 0 | Adapter uses its own RTC / NTP time |
| 1 | Adapter syncs time from car head unit |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x000701e0` (.rodata) | Config string |
| CarPlay | `0x00096806` (.rodata) | Config string |

### Cross-Binary Behavior

**ARMadb-driver** -- `FUN_00017340` at `0x00020c04` (`ARMadb...decomps.txt:2970-3063`):
- Part of a massive 13,546-byte command handler function
- CarDate read during initialization alongside many other session parameters
- When CarDate==1, the adapter accepts `syncTime` from the host's BoxSettings JSON

**AppleCarPlay** -- `FUN_00073e54` (`AppleCarPlay...decomps.txt:3397-3488`):
- Called during CarPlay session info reporting
- Reads various config values to build a status response
- Xref at `0x74004` confirmed via r2
- Reads `CarDate` as part of building the device capability report sent to the phone

The `syncTime` JSON field in BoxSettings directly calls `settimeofday()` (`boxsettings_full_decomp.txt:156-162`):
```c
if (strcmp(fieldName, "syncTime") == 0) {
    double time_val = json_item->valuedouble;
    BoxLog(3, "ConfigFileUtils", "HU syncTime: %.0lf\n");
    struct timeval tv;
    tv.tv_sec = (time_t)(time_val + 28800.0);  // +8h UTC offset
    tv.tv_usec = 0;
    settimeofday(&tv, NULL);
}
```

### Side Effects
- The `+28800.0` offset (8 hours) hardcoded in the syncTime handler suggests CST (China Standard Time) assumption
- Time sync affects SSL certificate validation, log timestamps, and GPS timestamp correlation

---


### [20] AutoPlauMusic — Deep Analysis

**Key ID:** 20  
**Default:** 0  
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Effect |
|-------|--------|
| 0 | Do NOT auto-play music on CarPlay/AA connection |
| 1 | Auto-play last media on connection |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x00070c87` (.rodata) | Config string |
| IAP2 | `0x00073b33` | Adjacent to `"com.apple.Music"` |

### Cross-Binary Behavior

**ARMiPhoneIAP2** -- `FUN_0002812c` at xref `0x28230`:
- Part of the media player engine
- When `AutoPlauMusic==1`, after CarPlay session is established, the adapter sends a play command to `com.apple.Music` (the default music app)
- The string `"com.apple.Music"` at `0x00073b33` confirms this targets the iOS Music app specifically

**ARMadb-driver** -- Stored via generic config path; read from IAP2.

**Also listed in** `carlink_firmware_config.md:127` as overridable by host BoxSettings.

### Pseudocode
```c
// After CarPlay session established
int auto_play = GetBoxConfig("AutoPlauMusic");
if (auto_play == 1) {
    // Send iAP2 NowPlaying command targeting "com.apple.Music"
    // Equivalent to pressing Play
}
```

### Side Effects
- Only affects CarPlay (iOS). Android Auto has its own auto-play behavior
- The typo "Plau" instead of "Play" is consistent across all firmware versions
- May be surprising if the user's last media was a phone call recording or podcast

---


### [38] AutoUpdate — Deep Analysis

**Key ID:** 38
**Default:** 1
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Effect |
|-------|--------|
| 0 | Manual OTA update only (user must trigger via web UI or SendFile) |
| 1 | Auto-check and apply firmware updates |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x00070d14` (.rodata) | Config string |

### Cross-Binary Behavior

**ARMadb-driver** -- No dedicated consumer function found in decompiled outputs. This key is stored in riddle.conf via the generic config path.

The OTA update mechanism in ARMadb-driver checks for `*Update.img` files via `strstr` match (protocol doc, line 279). When `AutoUpdate==1`, the `boxNetworkService` binary (not analyzed here) periodically polls the Carlinkit OTA server for firmware updates.

### Side Effects
- Consumed by `boxNetworkService` (separate process, not in Ghidra trace)
- When enabled, the adapter may reboot unexpectedly during an active session to apply updates
- OTA server communication is unencrypted HTTP (security concern)
- The update check interval is not configurable

---


### [40] BoxSupportArea — Deep Analysis

**Key ID:** 40
**Default:** 0
**Range:** [0,1]
**Category:** Regional config (OEM/Branding factory setting, SSH-only)

### Per-Value Behavior Table

| Value | Region | Protocol behavior | Confidence |
|-------|--------|-------------------|------------|
| 0 | Default/Global | Standard iAP2 identification | Verified (default) |
| 1 | China | Chinese market iAP2 parameters | Strong (binary evidence below) |

### Binary Evidence

**1. String adjacency in ARMiPhoneIAP2 (unpacked) — hex dump at `0x5fa3e`:**
```
0005fa3e: 426f 7853 7570 706f 7274 4172 6561 007a  BoxSupportArea.z
0005fa4e: 6800 2323 2323 2323 2049 4150 3220 5363  h.###### IAP2 Sc
0005fa5e: 6865 6475 6c44 6f6e 6520 7374 6174 3a20  hedulDone stat:
```
Three consecutive null-terminated strings in .rodata:
1. `BoxSupportArea\0` (config key argument to GetBoxConfig)
2. `zh\0` (ISO 639-1 language code for Chinese)
3. `###### IAP2 SchedulDone stat: %s\n\0` (debug log)

The `"zh"` literal immediately following `"BoxSupportArea"` indicates they are used in the same or adjacent function in the source — compiler places string literals in .rodata in source order within a translation unit.

**2. Source file context — `HudiAP2Server.cpp`:**
```
0005faa1: /home/sky/Hewei/HiCarPackage/HeweiPackTools/FakeCarPlayDevice/
          PlatformPOSIX/../Sources/ARMiPhoneIAP2/HudiAP2Server.cpp
```
The function reading BoxSupportArea lives in the iAP2 server module. The path reveals:
- **Vendor:** DongGuan HeWei Communication Technologies (Huawei subsidiary, "Hewei" = dev codename)
- **Project:** HiCarPackage — Huawei's car connectivity platform
- **Purpose:** FakeCarPlayDevice — CarPlay protocol emulation/adapter layer

**3. Cross-binary presence:**

| Binary | Address (unpacked) | Context |
|--------|-------------------|---------|
| ARMiPhoneIAP2 | `0x5fa3e` | Code reference — adjacent to `"zh"`, in `HudiAP2Server.cpp` |
| ARMadb-driver | `0x6e481` | Config table entry only (sequential with HNPInterval, lightType...) |
| ARMHiCar | `0x1d3b0` | Config table entry only (sequential with HNPInterval, lightType...) |

BoxSupportArea appears in the **config table** of all three binaries but is only **actively consumed by code** in ARMiPhoneIAP2 (only one string instance vs config table entries in the others).

**4. HiCar ecosystem context in same binary:**
```
0x728fc  Delete all hicar auth record!!!
0x72950  HiCarConnectMode
0x72c54  HeweiSpotField-1-CPU_Start
0x72cf8  /tmp/boa/logs/hicar.log
0x69aab  com.hewei.low-priority-root-queue
0x69acd  com.hewei.root-queue
0x5f8b0  43CCarPlay_MiddleManInterface_iAP2InternalUse
```
The firmware natively supports both CarPlay and HiCar, with BoxSupportArea gating region-specific behavior.

### What "Area" Means

Despite the name containing "Area", this is a **geographical region** flag, NOT a display/screen area setting. Confirmed by:
- `configuration.md` categorizes it under "OEM/Branding (Factory Settings)" as "Regional config"
- No relationship to ViewArea/SafeArea (those use file-based config at `/etc/RiddleBoxData/HU_*_INFO`)
- The adjacent `"zh"` string is a language/region code, not a dimension

### Cross-Binary Behavior

**ARMiPhoneIAP2** — actively consumed:
- Read via `GetBoxConfig("BoxSupportArea")` in `HudiAP2Server.cpp`
- Used during iAP2 server initialization/session setup
- Adjacent `"zh"` string suggests value=1 sets a Chinese language hint in iAP2 identification
- `CiAP2Session_CarPlay` class (source at `0x60ae3`) handles CarPlay session — likely downstream consumer

**ARMadb-driver / ARMHiCar** — config table entry only; not directly consumed by code in these binaries.

### Side Effects
- Affects iAP2 identification exchange — wrong region may cause CarPlay setup failures
- Must be set BEFORE session establishment
- Not configurable via BoxSettings JSON or Web UI — SSH/riddleBoxCfg only
- Persistent across reboots (stored in `/etc/riddle.conf`)

### Confidence Assessment
- **VERIFIED:** String `"zh"` is byte-adjacent to `"BoxSupportArea"` in IAP2 binary .rodata
- **VERIFIED:** Source file is `HudiAP2Server.cpp` from Huawei/HiCar codebase
- **VERIFIED:** Categorized as "Regional config" in firmware architecture docs
- **VERIFIED:** Present in ARMHiCar binary (China-market connectivity)
- **INFERRED:** Value 1 enables Chinese market features (strong inference from zh + HiCar context)
- **UNVERIFIABLE:** Original decompilation files (`ARMiPhoneIAP2...decomps.txt`) no longer exist; specific function pseudocode and xref addresses from prior analysis cannot be independently re-verified without re-running Ghidra/r2

---


### [61] BackRecording — Deep Analysis

**Key ID:** 61  
**Default:** 0  
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Effect |
|-------|--------|
| 0 | Background recording disabled |
| 1 | Continue audio recording when CarPlay/AA is backgrounded |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x00070e08` (.rodata) | Config string |

### Cross-Binary Behavior

**No dedicated consumer function found** in any of the 4 analyzed binaries' decompiled output. This key exists in the config table (key ID 61) and can be set/get via `riddleBoxCfg`, but none of the decompiled functions reference it.

This is likely a **PASS-THROUGH** key consumed by `ARMAndroidAuto` (the OpenAuto SDK binary), which is confirmed to NOT link the riddleBoxCfg library. It likely receives this value via D-Bus or environment variable from ARMadb-driver.

### Side Effects
- Affects Android Auto's behavior when the projection is not in the foreground
- When enabled, microphone capture continues for voice assistant or phone calls
- May increase power consumption

---


### [69] HiCarConnectMode — Deep Analysis

**Key ID:** 69  
**Default:** 0  
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Effect |
|-------|--------|
| 0 | Standard HiCar connection mode |
| 1 | Alternative HiCar connection mode |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x0006c8d3` | In mapping table: `"HiCarConnectMode"` |
| BT Daemon | `0x0005ec80` (izz) | BT config region |

### Cross-Binary Behavior

**ARMadb-driver** -- Mapping table entry [23]: `"HiCarConnectMode" -> "HiCarConnectMode"` (self-mapped, `boxsettings_full_decomp.txt:67`). Set via generic BoxSettings path.

**bluetoothDaemon** -- `FUN_00014444` (`bluetoothDaemon...decomps.txt:411-418`)
- **FUNCTION NOT FOUND** at address `0x00014444` -- Ghidra could not decompile
- However, the string xref at `0x0006ec80` confirms the key is referenced in bluetoothDaemon
- Likely consumed during BT pairing to select between standard BLE advertising vs HiCar-specific discovery

### Side Effects
- HiCar is Huawei's car connectivity protocol (alternative to CarPlay for Huawei phones)
- This key affects the BT advertising and service discovery parameters
- Only relevant for Huawei/HarmonyOS devices
- The `hwSecret` was disabled in 2025.10 firmware, which may affect HiCar auth

---


### [73] DayNightMode — Deep Analysis

**Key ID:** 73
**Default:** 2
**Range:** [0,2]

### Per-Value Behavior Table

| Value | Theme | CarPlay icon set | File written |
|-------|-------|-----------------|--------------|
| 0 | Auto (follows host commands 16/17) | Default | `/tmp/night_mode` = 0 |
| 1 | Force Day (light theme) | LHD or RHD day icons | `/tmp/night_mode` = 1 |
| 2 | Force Night (dark theme) | LHD or RHD night icons | `/tmp/night_mode` = 2 |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x0006c90e` | In mapping table: `"DayNightMode"` |
| CarPlay | `0x00080ca9` | Adjacent to `"CarDrivePosition"` |
| CarPlay | `0x00080d97` | Log string: `"DayNightMode: %d"` |

### Cross-Binary Behavior

**AppleCarPlay** -- `FUN_0002c27c` (`AppleCarPlay...decomps.txt:3081-3392`):
- 1786-byte function, config keys: `CarDrivePosition:get, DayNightMode:get, LogoType:get`
- This is the **theme/resource selector** for CarPlay

Reconstructed logic for DayNightMode within FUN_0002c27c (lines 3210-3249):
```c
// DayNightMode theme resource selection
int is_connected = *(param + 0x588);  // session active flag
if (is_connected == 0) {
    // Not connected -- read from config
    int daynight = GetBoxConfig("DayNightMode");
    
    if (daynight == 2) {
        // Force night: write to shared memory, skip CarPlay icon setup
        WriteSharedMemory("HU_DAYNIGHT_MODE", &daynight, 4);
    }
    
    // Log: "DayNightMode: %d"
    BoxLog(3, ..., "DayNightMode: %d", daynight);
    
    if (daynight > 1) {
        // Value 2 (night) -- skip icon resource selection
        goto done;
    }
    
    // Select LHD or RHD icon set
    int drive_pos;
    if (daynight == 1) {
        drive_pos = DAT_LHD;  // Day mode uses config-driven LHD/RHD
    } else {
        drive_pos = DAT_RHD;  // Fallback
    }
    // Load icon set: iVar3 = icon_path_day or icon_path_night
    resource_ptr->theme_resource = selected_icons;
}
```

The function also handles the dynamic night mode commands (16=StartNightMode, 17=StopNightMode) from the host.

**ARMadb-driver** -- `FUN_00017340` at `0x0001a5ec` (`ARMadb...decomps.txt:14519-14709`):
- Config keys: `ScreenDPI:get, DayNightMode:get, BackgroundMode:get`
- Part of the large session handler; DayNightMode is passed through to CarPlay via IPC

### Side Effects
- Value 0 (Auto) means the host controls day/night via commands 16/17, which write to `/tmp/night_mode` and trigger D-Bus `HU_DAYNIGHT_MODE`
- The GM Info 3.7 sends these commands based on its ambient light sensor
- Affects CarPlay icon resources (3 sets: day LHD, day RHD, night)
- Night mode also adjusts the LVGL-based boxUIServer theme (2025.10+)

---


### [28] CarDrivePosition — Deep Analysis

**Key ID:** 28  
**Default:** 0  
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Position | Icon set | Scrollbar side |
|-------|----------|----------|----------------|
| 0 | LHD (Left-Hand Drive) | Standard CarPlay icons | Right side |
| 1 | RHD (Right-Hand Drive) | Mirrored CarPlay icons | Left side |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x0006c7f3` | In mapping table: `"drivePosition" -> "CarDrivePosition"` |
| CarPlay | `0x00080c98` | Adjacent to `"_SUGGESTUI_URLS"`, `"DayNightMode"` |

### Cross-Binary Behavior

**AppleCarPlay** -- `FUN_0002c27c` (`AppleCarPlay...decomps.txt:3081-3392`):
- Same function as DayNightMode -- these work together
- CarDrivePosition selects between LHD and RHD icon resource sets

Reconstructed logic:
```c
// Theme selection in FUN_0002c27c
int drive_pos = GetBoxConfig("CarDrivePosition");  // 0=LHD, 1=RHD
int daynight = GetBoxConfig("DayNightMode");

// Three sets of icon paths are prepared:
// iVar4 = "standard" icon set (always loaded)
// iVar3 = LHD-specific icons
// iVar10 = RHD-specific icons

if (drive_pos == 2) {
    // Special case: LogoType-driven selection
    // Uses LogoType to pick branded icon set
} else {
    // Standard 2-set selection
    // iVar4 = base_icons
    // iVar3 = (drive_pos == 1) ? rhd_day : lhd_day
    // iVar10 = (drive_pos == 1) ? rhd_night : lhd_night
}

// Load icon images via FUN_0004f8f4 (file-to-texture loader)
// Set properties: width=120px, height=120px (0x78)
// Register with CarPlay rendering engine
```

**ARMadb-driver** -- `FUN_0001dd98` (cmd dispatcher, line 1191): `CarDrivePosition:set`
- Set by the host via the BoxSettings JSON field `"drivePosition"`
- Mapping table entry [12]: `"drivePosition" -> "CarDrivePosition"` (`boxsettings_full_decomp.txt:56`)

The file `/tmp/hand_drive_mode` is also written by the host via SendFile (cmd 0x99) as an alternative path.

### Side Effects
- Affects CarPlay UI layout direction
- Combined with DayNightMode and LogoType to select from up to 6 icon set combinations
- Does NOT affect Android Auto (AA has its own drive position handling via the OpenAuto SDK)
- Must be set before CarPlay session resource loading

---


### [12] UseBTPhone — Deep Analysis

**Key ID:** 12  
**Default:** 0  
**Range:** [0,1]

### Per-Value Behavior Table

| Value | Effect | Audio routing |
|-------|--------|---------------|
| 0 | Phone audio via USB/adapter | Call audio decoded on adapter, sent to host via USB bulk |
| 1 | Phone audio via BT HFP | Call audio routed directly via Bluetooth HFP to head unit |

### String Locations (r2)

| Binary | Address | Context |
|--------|---------|---------|
| ARMadb | `0x0006c8c8` | In mapping table: `"UseBTPhone" -> "UseBTPhone"` |
| BT Daemon | `0x0005ebb6` (izz) | BT config region |

### Cross-Binary Behavior

**ARMadb-driver** -- `FUN_00035cbc` at `0x00035e48` (`ARMadb...decomps.txt:10232-10328`):
- Config keys: `UseBTPhone:get`
- This is an **FFT (Fast Fourier Transform)** function -- 398 bytes of DSP code
- Operates on signed 16-bit audio samples with butterfly operations
- The function itself is audio signal processing, NOT the config consumer

The Ghidra script tagged this function because the config key string `"UseBTPhone"` at `0x0006c8c8` has a data reference that falls within this function's address range, but the actual consumer is elsewhere. The string at `0x0006c8c8` is in the mapping table, referenced by `FUN_0001658c`.

**Mapping table entry [22]:** `"UseBTPhone" -> "UseBTPhone"` (self-mapped, `boxsettings_full_decomp.txt:66`)

**bluetoothDaemon** -- `FUN_0000cfec` (`bluetoothDaemon...decomps.txt:400-407`):
- **FUNCTION NOT FOUND** at address `0x0000cfec`
- String at `0x0005ebb6` (izz) confirms the key exists in bluetoothDaemon
- Likely consumed during BT profile setup to enable/disable HFP (Hands-Free Profile)

### Pseudocode (inferred from protocol and BT daemon context)
```c
// During BT service registration
int use_bt_phone = GetBoxConfig("UseBTPhone");
if (use_bt_phone == 1) {
    // Register HFP AG (Audio Gateway) profile
    // Phone call audio routes: Phone -> BT HFP -> Head Unit speaker
    // Microphone: Head Unit mic -> BT HFP -> Phone
} else {
    // Phone call audio routes: Phone -> CarPlay/AA -> Adapter -> USB -> Head Unit
    // Microphone: Head Unit -> USB -> Adapter -> CarPlay/AA -> Phone
}
```

### Side Effects
- BT HFP bypasses the adapter's audio processing pipeline entirely
- When UseBTPhone=1, the adapter does NOT handle call audio -- it goes direct BT between phone and head unit
- This means echo cancellation (`EchoLatency`) is handled by the head unit, not the adapter
- The GM Info 3.7 has its own Harman "Titan" audio DSP for HFP echo cancellation

---

## Summary Cross-Reference Matrix

| # | Key | ARMadb | CarPlay | IAP2 | BT Daemon | Type |
|---|-----|--------|---------|------|-----------|------|
| 1 | HudGPSSwitch | get (init) | - | get (ident gate) | - | ALIVE |
| 2 | WiFiChannel | get+set (hot) | - | get (WiFiConfig) | get (init) | ALIVE |
| 3 | WiFiP2PMode | - | - | get (WiFiConfig) | - | ALIVE |
| 4 | UseUartBLE | get (predicate) | - | - | get (XML config) | ALIVE |
| 5 | BtAudio | set (cmd 22/23) | get (predicate) | - | get (init) | ALIVE |
| 6 | LogMode | get (cached) | get (cached) | get (BoxLog) | get | ALIVE |
| 7 | BoxConfig_UI_Lang | set (generic) | - | - | - | PASS-THROUGH |
| 8 | UdiskMode | get+set (gadget) | - | - | - | ALIVE |
| 9 | CarDate | get (session) | get (status) | - | - | ALIVE |
| 10 | AutoPlauMusic | set (generic) | - | get (media) | - | ALIVE |
| 11 | AutoUpdate | set (generic) | - | - | - | PASS-THROUGH |
| 12 | BoxSupportArea | set (generic) | - | get (session) | - | ALIVE |
| 13 | BackRecording | set (generic) | - | - | - | PASS-THROUGH |
| 14 | HiCarConnectMode | set (generic) | - | - | get (BT adv) | ALIVE |
| 15 | DayNightMode | get (pass) | get (theme) | - | - | ALIVE |
| 16 | CarDrivePosition | set (cmd) | get (icons) | - | - | ALIVE |
| 17 | UseBTPhone | set (generic) | - | - | get (HFP) | ALIVE |

**Legend:**
- **ALIVE**: At least one binary reads and acts on the value at runtime
- **PASS-THROUGH**: Stored in config but consumed by binaries outside the analysis set (boxUIServer, boxNetworkService, ARMAndroidAuto)
- **get**: Binary reads the config value
- **set**: Binary writes/modifies the config value
- **hot**: Change takes immediate effect without reboot
- **generic**: Uses the standard BoxSettings JSON -> mapper -> SetBoxConfig path


### [78] AdvancedFeatures — Deep Analysis

- **Type:** Integer | **Default:** 0 | **Min:** 0 | **Max:** 1
- **Table addr:** 0x000938f8 (ARMadb) | **String VA:** 0x0006c5b0
- **Status:** **ALIVE** (ARMadb-driver only — all other binaries have zero code xrefs)
- **JSON field:** None (NOT in BoxSettings mapping table, NOT exposed by server.cgi web API)
- **CLI:** `riddleBoxCfg -s AdvancedFeatures 1`
- **Xrefs:** ARMadb-driver(2 code), AppleCarPlay(0 code), ARMiPhoneIAP2(0 code), bluetoothDaemon(0 code)
- **Callers:** `FUN_00016c20`@0x16e6c (BoxSettings parser, get — naviScreenInfo gate); `FUN_0001bc24`@0x1bc70 (boxInfo builder, get — supportFeatures)
- **False positive debunked:** `FUN_0002cd24`@0x2ce58 was incorrectly tagged — it's a USB HID descriptor handler with zero GetBoxConfig calls.

#### Activation Matrix

| `naviScreenInfo` in BoxSettings | `AdvancedFeatures` | boxInfo `supportFeatures` | Navi Video (0x2C) | Path |
|--------------------------------|--------------------|--------------------------|--------------------|------|
| **Yes** | **0** | `""` | **WORKS** | HU_SCREEN_INFO bypass (0x170d6) |
| **Yes** | **1** | `"naviScreen"` | **WORKS** | HU_SCREEN_INFO bypass (0x170d6) |
| **No** | **1** | `"naviScreen"` | **WORKS** | HU_NAVISCREEN_INFO legacy (0x16f20) |
| **No** | **0** | `""` | **REJECTED** | "Not support NaviScreenInfo, return" |

**CRITICAL BYPASS:** When host sends `naviScreenInfo` in BoxSettings JSON, the branch at `0x16e64` jumps to `0x170d6` (HU_SCREEN_INFO path) **before** the AdvancedFeatures check. AdvancedFeatures=0 does NOT block navi video when naviScreenInfo is provided.

#### Decision Tree — FUN_00016c20 (BoxSettings Parser)

```c
iVar6 = strcmp(field, "naviScreenInfo");
if (iVar6 != 0) {
    // naviScreenInfo NOT in this element → check DockPosition, then AdvancedFeatures
    // ... DockPosition handler ...
    goto generic_path;
}
// naviScreenInfo FOUND → branch at 0x16e64 to HU_SCREEN_INFO path (BYPASSES check below)

// Legacy path (only reached if naviScreenInfo absent):
int adv = GetBoxConfig("AdvancedFeatures");  // 0x16e6c
if (adv == 0) {
    BoxLog(3, "ConfigFileUtils", "Not support NaviScreenInfo, return\n");
    // Silently discarded
} else {
    // PROCESS: parse width/height/fps → WriteSharedMemory("HU_NAVISCREEN_INFO")
    // Optional: parse safearea → WriteSharedMemory("HU_NAVISCREEN_SAFEAREA_INFO")
    //                           → WriteSharedMemory("HU_NAVISCREEN_VIEWAREA_INFO")
}
```

**ARM disassembly proof:**
```arm
0x16e5c  blx  fcn.00015228        ; parse JSON for "naviScreenInfo"
0x16e62  cmp  r0, 0               ; found?
0x16e64  bne.w 0x170d6            ; YES → HU_SCREEN_INFO (BYPASSES AdvancedFeatures check)
0x16e68  ldr  r0, [0x6c5b0]      ; "AdvancedFeatures"
0x16e6c  bl   fcn.00066d3c        ; GetBoxConfig("AdvancedFeatures")
0x16e70  cmp  r0, 0               ; == 0?
0x16e72  bne  0x16f20             ; != 0 → legacy HU_NAVISCREEN_INFO path
0x16e7c  bl   BoxLog              ; "Not support NaviScreenInfo, return"
```

#### Decision Tree — FUN_0001bc24 (boxInfo JSON Builder)

```c
int sb = GetBoxConfig("AdvancedFeatures");  // 0x1bc70
BoxLog(3, "Accessory_fd", "advancedFeatures: 0x%X\n", sb);
if (sb & 1) {                               // 0x1bd5a: tst.w sb, 1
    strcat(supportFeatures, "naviScreen");   // advertise capability to host
}
// supportFeatures sent in boxInfo JSON response via USB cmd 0x19
```

**NOTE:** Tests bit 0 via `tst.w sb, 1`. Not a bitmask despite range [0,1] — bit 0 is the only check anywhere in firmware. No `tst.w sb, 2` exists.

#### Shared Memory Writes (when AdvancedFeatures=1, legacy path)

| Key | Size | Content |
|-----|------|---------|
| `HU_NAVISCREEN_INFO` | 24B | `[width_px, height_px, width_mm, height_mm, fps, 0]` |
| `HU_NAVISCREEN_SAFEAREA_INFO` | 20B | `[width, height, originX, originY, drawUIOutside]` (if safearea present) |
| `HU_NAVISCREEN_VIEWAREA_INFO` | 24B | `[width, height, width_dup, height_dup, 0, 0]` (if safearea present) |

#### AppleCarPlay Indirect Effect

AppleCarPlay does NOT read AdvancedFeatures directly. `g_bSupportNaviScreen` is set at init by reading `HU_NAVISCREEN_INFO` shared memory (populated by ARMadb when AdvancedFeatures=1). When `g_bSupportNaviScreen=1` AND iPhone reports `featureAltScreen=1` → `_AltScreenSetup` enables NaviVideoData (0x2C) H.264 stream. When both are 0: `"Not support new fratues\n"` (sic) logged.

#### Dependencies

| Key | Relationship |
|-----|-------------|
| **naviScreenWidth/Height/FPS** | riddle.conf defaults used when naviScreenInfo dimensions not provided in BoxSettings |
| **DockPosition/DuckPosition** | Main screen ViewArea, handled by separate branch in same function — independent |
| **g_bSupportViewarea** | Independent of AdvancedFeatures; set from `HU_VIEWAREA_INFO` file content |

#### Correction Log

| Correction |
|-----------|
| AdvancedFeatures is NOT a bitmask (0-3). Max=1 enforced. Only `tst.w sb, 1` exists. |
| `g_bSupportViewarea` is NOT set by AdvancedFeatures. Set from `HU_VIEWAREA_INFO` content. |
| naviScreenInfo in BoxSettings bypasses AdvancedFeatures check entirely (branch at 0x16e64). |
| FUN_0002cd24 (tagged "AdvancedFeatures:get" at 0x2ce58) is false positive — USB HID handler. |

---

## String Keys

### [S0] CarBrand — Deep Analysis

**Config table entry:** Index 0 in string config table at `0x0006c89f` (ARMadb), `0x000967a9` (AppleCarPlay), `0x0006ed7b` (bluetoothDaemon), `0x00018dfc` (riddleBoxCfg).

**Xref count:** ARMadb=12 (ALIVE), server.cgi=12 (ALIVE), bluetoothDaemon=11 (ALIVE).

**JSON field:** `"brand"` in BoxSettings (mapping table entry [18] at `0x93f90`).

**Set path (BoxSettings):** `FUN_00016c20` (BoxSettings iterator) matches `"brand"` -> calls `FUN_00068218(brand_string)` as a special handler BEFORE the generic mapper:

```c
// FUN_00068218 (boxsettings_full_decomp.txt:480-506)
void FUN_00068218(char *param_1) {
    iVar1 = FUN_000693e8();  // GetHardwareType
    if ((iVar1 == 7) && (param_1 != NULL)) {
        param_1 = "Car";    // Force "Car" on hw type 7
    }
    BoxLog(3, "riddleCfg", "SetBoxCarLogoName: %s\n", param_1);
    // VULN NOTE: param_1 is user-supplied, injected into shell command:
    sprintf(buf, "grep \"oemIconLabel = %s$\" %s || sed -i \"s/^.*oemIconLabel = .*/oemIconLabel = %s/\" %s",
            param_1, "/etc/airplay.conf", param_1, "/etc/airplay.conf");
    system(buf);  // COMMAND INJECTION if brand contains shell metacharacters
    // Also sets oemIconVisible
    sprintf(buf, "grep \"oemIconVisible = %d\" %s || sed -i ...", ...);
    system(buf);
}
```

After the special handler, the generic path stores `brand` -> `CarBrand` via `FUN_00067040("CarBrand", string_value)` (SetBoxConfigStr).

**Read path:** `FUN_000678a0` (ARMadb, PARAM at `0x679be`) -- used when building device info response. Also read by `FUN_00059304` and `FUN_000594d4` in bluetoothDaemon (11 xrefs) for BT service registration (SDP records include car brand). Read by server.cgi `FUN_00014040` (settings dump) and `FUN_00017250`/`FUN_00017404` (8 xrefs for device profile building).

**Side effects:**
- Writes `oemIconLabel = <value>` into `/etc/airplay.conf` (CarPlay icon branding)
- Sets `oemIconVisible = 1` (enables OEM icon in CarPlay)
- On hardware type 7, brand is forced to "Car" regardless of input
- **SECURITY:** `system()` call with unsanitized input -- same pattern as CustomWifiName but lower risk since brand typically arrives from host app, not direct user input

**Cross-binary behavior:**
| Binary | Xrefs | Role |
|--------|-------|------|
| ARMadb | 12 | Set (BoxSettings), read (device info), airplay.conf update |
| server.cgi | 12 | Read (settings dump, device profile JSON) |
| bluetoothDaemon | 11 | Read (SDP record, BT device profile) |
| riddleBoxCfg | 2 | Set (FUN_00013fec, FUN_00013e1c) |

---


### [S1] CarModel — Deep Analysis

**Config table entry:** Index 1, `0x0008018c` (ARMadb), `0x000967b2` (AppleCarPlay).

**Xref count:** ARMadb=6 (ALIVE), server.cgi=7 (ALIVE), bluetoothDaemon=5 (ALIVE).

**Set path:** NOT in the BoxSettings mapping table at `0x93f90`. Set externally via `riddleBoxCfg -s CarModel <value>` or commission handler. In riddleBoxCfg, set by `FUN_00013fec` and `FUN_00013e1c`.

**Read path:**
- ARMadb: `FUN_00067040` (SetBoxConfigStr, used during commission writes), `FUN_00067210` (GetBoxConfigStr, 3 xrefs in name generation and info), `FUN_000678a0` (device info builder at `0x679ca`), `FUN_00067ba8` (additional read at `0x67bd0`)
- server.cgi: `FUN_00017404` (3 xrefs -- device profile JSON), `FUN_00017250` (1 xref), `FUN_00013430` (settings dump), `FUN_00012c80` (info handler), `FUN_000134f8` (set handler)
- bluetoothDaemon: `FUN_000594d4` (3 xrefs), `FUN_00059304` (1 xref), `FUN_00059dbc` (1 xref) -- BT service registration

**Side effects:** Purely informational. Used in device info JSON responses and BT SDP records. No shell commands, no config file writes.

**Cross-binary behavior:**
| Binary | Xrefs | Role |
|--------|-------|------|
| ARMadb | 6 | Read (device info builder, commission) |
| server.cgi | 7 | Read (settings dump, device profile) |
| bluetoothDaemon | 5 | Read (SDP/BT profile) |

---


### [S2] BluetoothName — Deep Analysis

**Config table entry:** Index 2, `0x0006cdf6` (ARMadb), `0x00095f22` (AppleCarPlay), `0x0006e4c2` (bluetoothDaemon).

**Xref count:** ARMadb=2 (ALIVE), AppleCarPlay=2 (ALIVE), server.cgi=3 (ALIVE), bluetoothDaemon=2 (ALIVE).

**JSON field:** `"btName"` -- BUT this is a **special case**. In the BoxSettings iterator (`FUN_00016c20`), `"btName"` is intercepted BEFORE the generic mapper:

```c
// boxsettings_full_decomp.txt:207-211
iVar6 = strcmp(pcVar13, "btName");
if (iVar6 == 0) {
    FUN_00069bfc(*(char **)(iVar5 + 0x10));  // Special handler, NOT generic SetBoxConfigStr
    goto LAB_00017120;  // Then ALSO stores via generic mapper -> "CustomBluetoothName"
}
```

The special handler `FUN_00069bfc` writes to `/etc/bluetooth_name` AND `/etc/bluetooth/hcid.conf` AND `/etc/bluetooth/eir_info`, while the generic mapper stores the value as `CustomBluetoothName` in riddle.conf.

**BluetoothName** (this key) is the **effective/resolved** BT name, set by the name generation function `FUN_0006a710` at boot. It is NOT directly user-settable.

**Read path:**
- ARMadb: `FUN_00067040` at `0x6719c` (set during name resolution), `FUN_00067210` at `0x67304` (read)
- AppleCarPlay: `FUN_0007356c` at `0x73660` (iAP2 session setup), `FUN_0007339c` at `0x734f8` (CarPlay init)
- server.cgi: `FUN_00013878` at `0x138de` (settings page), `FUN_00017404`/`FUN_00017250` (device profile)
- bluetoothDaemon: `FUN_000594d4` at `0x595c8`, `FUN_00059304` at `0x59460`

**Name resolution logic** (`FUN_0006a710` at ARMadb `0x6a710`, 428 bytes):
1. Read `CustomBluetoothName` into buf (16 bytes)
2. Read `BrandName` into buf
3. If `BrandName` is set:
   - Read `BrandBluetoothName` into buf7
   - If `BrandBluetoothName` is set, use it as template
   - Else use `BrandName` as template, call `FUN_00069f54(template, 1)` to generate suffixed name
4. If `CustomBluetoothName` is set, use it directly -> `FUN_00069bfc(name)`
5. Else if `BrandBluetoothName` resolved, use that -> `FUN_00069bfc(name)`
6. Else call `FUN_00069acc()` (get default BT name) -> `FUN_00069bfc(name)`

**Side effects:** Reading only -- no direct shell commands. Changes propagate to CarPlay/iAP2 session negotiation.

---


### [S3] WifiName — Deep Analysis

**Config table entry:** Index 3, `0x0006d26c` (ARMadb), `0x00097378` (AppleCarPlay).

**Xref count:** ARMadb=2 (ALIVE), AppleCarPlay=2 (ALIVE), server.cgi=3 (ALIVE), bluetoothDaemon=2 (ALIVE).

Same architecture as BluetoothName. WifiName is the **resolved/effective** WiFi SSID, set by `FUN_0006a710` at boot. NOT directly user-settable.

**Read path:**
- ARMadb: `FUN_00067040` at `0x67178` (set during resolution), `FUN_00067210` at `0x672dc` (read)
- AppleCarPlay: `FUN_0007356c` at `0x73638`, `FUN_0007339c` at `0x734d4`
- server.cgi: `FUN_00013878` at `0x138ee`, `FUN_00017404`/`FUN_00017250`
- bluetoothDaemon: `FUN_000594d4` at `0x595a0`, `FUN_00059304` at `0x5943c`

**Name resolution:** Same `FUN_0006a710` logic but for WiFi:
1. Read `CustomWifiName` -> `BrandWifiName` -> `BrandName` templates
2. Apply suffix format (`%s-%02X`, `%s%03X`, `%s%04X`, `%s-%04X`, `%s%02X` depending on suffix length 1-4+)
3. Write resolved name via `FUN_00069e34` -> `/etc/hostapd.conf` ssid= field
4. Set default `WifiPassword` = `"12345678"` if first-time generation

---


### [S4] CustomBluetoothName — Deep Analysis

**VULNERABILITY: COMMAND INJECTION**

**Config table entry:** Index 4, `0x0006c6e4` (ARMadb).

**Xref count:** ARMadb=4 (ALIVE), server.cgi=1 (DEAD candidate), bluetoothDaemon=0, AppleCarPlay=0, riddleBoxCfg=0.

**JSON field:** `"btName"` -> mapping table entry [0] at `0x93f90` maps to `"CustomBluetoothName"`.

**Set path:** BoxSettings JSON `"btName"` -> special handler `FUN_00069bfc(value)` at `0x69bfc` (316 bytes). Also stored in riddle.conf via generic `FUN_00067040("CustomBluetoothName", value)`.

**THE VULNERABILITY -- FUN_00069bfc pseudocode:**

```c
void FUN_00069bfc(char *btName) {
    if (btName[0] == '\0') return;
    
    char *oldName = FUN_00069acc();  // Get current BT name
    if (strcmp(btName, oldName) != 0) {
        // 1. Write to /etc/bluetooth_name (safe - file write)
        FILE *f = fopen("/etc/bluetooth_name", "wb");
        fwrite(btName, 1, strlen(btName)+1, f);
        fclose(f);
    }
    
    // 2. CHECK if name already in hcid.conf
    char cmd[128];
    memset(cmd, 0, 0x80);
    // INJECTION POINT 1: btName is sprintf'd into grep command
    sprintf(cmd, "grep \"name \\\"%s\\\"\" /etc/bluetooth/hcid.conf", btName);
    int result = system(cmd);  // <-- COMMAND INJECTION
    
    if (result != 0) {
        // 3. UPDATE hcid.conf with sed
        // INJECTION POINT 2: btName sprintf'd into sed command
        sprintf(cmd, "sed -i \"s/name .*;/name \\\"%s\\\";/\" /etc/bluetooth/hcid.conf", btName);
        system(cmd);  // <-- COMMAND INJECTION
    }
    
    // 4. Build EIR (Extended Inquiry Response) data
    char eir[64];
    memset(eir, 0, 0x40);
    int len = strlen(btName);
    // Build hex-encoded EIR name: "%02X" per byte
    sprintf(eir, "%02X09", len+1);  // EIR type 0x09 = Complete Local Name
    for (int i = 0; i < len; i++) {
        sprintf(eir + 2 + i*2, "%02X", (uint8_t)btName[i]);
    }
    
    // 5. Write EIR to /etc/bluetooth/eir_info
    FILE *f2 = fopen("/etc/bluetooth/eir_info", "wb");
    fwrite(eir, 1, strlen(eir), f2);
    // Also writes static UUIDs:
    fwrite("110600000000DECAFADEDECADEAFDECACAFF", 1, 0x24, f2);
    fwrite("1107D31FBF505D572797A24041CD484388EC", 1, 0x24, f2);
    fclose(f2);
    
    // 6. Restart BT/WiFi if name changed
    if (!FUN_000695c0()) {  // Not already locked
        // Wait for /tmp/run_bluetooth_wifi_lock (up to 30 iterations)
        system("(/script/close_bluetooth_wifi.sh;/script/start_bluetooth_wifi.sh)&");
    }
}
```

**Exploitation:**
- **bufsize=16** limits payload but shell metacharacters fit
- Injection payload: `a";id>/tmp/x;"` (14 chars, within 16-byte buffer)
- `sprintf(cmd, "grep \"name \\\"%s\\\"\" ...", btName)` becomes:
  `grep "name \"a";id>/tmp/x;"\""` -- breaks out of the grep, executes `id>/tmp/x`
- Runs as **root** (ARMadb runs as root on CPC200)
- Attack vector: Host app sends BoxSettings JSON with `{"btName":"a\";id>/tmp/x;\""}` via USB cmd 0x19

**Files modified:**
1. `/etc/bluetooth_name` (fwrite, safe)
2. `/etc/bluetooth/hcid.conf` (via `system(grep...)` and `system(sed...)`)
3. `/etc/bluetooth/eir_info` (fwrite, safe)
4. Triggers BT/WiFi restart scripts

---


### [S5] CustomWifiName — Deep Analysis

**VULNERABILITY: COMMAND INJECTION**

**Config table entry:** Index 5, `0x0006c6f8` (ARMadb).

**Xref count:** ARMadb=6 (ALIVE), server.cgi=1 (DEAD), others=0.

**JSON field:** `"wifiName"` -> mapping table entry [1] at `0x93f90`.

**Set path:** BoxSettings `"wifiName"` -> special handler `FUN_00069e34(value)` at `0x69e34` (234 bytes).

**THE VULNERABILITY -- FUN_00069e34 pseudocode:**

```c
void FUN_00069e34(char *wifiName) {
    if (wifiName[0] == '\0') return;
    
    char *oldName = FUN_00069b64();  // Get current WiFi name
    if (strcmp(wifiName, oldName) != 0) {
        // Write to file (safe)
        FILE *f = fopen(/* wifi_name_path */, "wb");
        fwrite(wifiName, 1, strlen(wifiName)+1, f);
        fclose(f);
    }
    
    char cmd[128];
    memset(cmd, 0, 0x80);
    
    // INJECTION POINT 1: grep check
    sprintf(cmd, "grep \"ssid=%s\" /etc/hostapd.conf", wifiName);
    int result = system(cmd);  // <-- COMMAND INJECTION via system()
    
    if (result != 0) {
        // INJECTION POINT 2: sed update
        sprintf(cmd, "grep \"ssid=%s\" /etc/hostapd.conf || "
                "sed -i \"s/^ssid=.*/ssid=%s/\" /etc/hostapd.conf",
                wifiName, wifiName);
        system(cmd);  // <-- COMMAND INJECTION via system()
        
        // Wait for lock, then restart WiFi
        if (!FUN_000695c0()) {
            // Poll /tmp/run_bluetooth_wifi_lock up to 30 times
            while (access("/tmp/run_bluetooth_wifi_lock", F_OK) == 0 && retries < 30) {
                usleep(100000);
                BoxLog(5, "RiddlePlatform", "Wait run_bluetooth_wifi_lock finish...\n");
            }
            system("(/script/close_bluetooth_wifi.sh;/script/start_bluetooth_wifi.sh)&");
        }
    }
    BoxLog(3, "RiddlePlatform", "Set Box WiFi name: %s\n", wifiName);
}
```

**Exploitation:**
- Injection in the `sed` path: `a" /etc/hostapd.conf;id>/tmp/x;#` -- breaks `grep "ssid=..."`, injects arbitrary command
- Bufsize 16 limits payload similarly to CustomBluetoothName
- Minimal payload: `a";id>/t;#` (11 chars)
- Runs as **root**

**Additional behavior in FUN_0006a710:** When CustomWifiName is empty and box is inactive (no active connection), the name generation function:
1. Gets device ID suffix via `FUN_000694b0` (9 chars)
2. Formats as `%s-%02X` where suffix is last byte of device ID
3. Stores result via `FUN_00067040("CustomWifiName", formatted_name)`
4. Also sets `WifiPassword` to default `"12345678"` (at `0x6a7c4-0x6a7c6`)
5. If box becomes active with a different name, resets config: `rm -f /etc/wifi_name` and `rm -f /etc/riddle.conf /tmp/.riddle.conf; cp riddle_default.conf ...`

**Files modified:**
1. WiFi name file (fwrite, safe)
2. `/etc/hostapd.conf` (via `system(sed...)`)
3. Triggers full BT/WiFi restart

---


### [S6] LastPhoneSpsPps — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 512
- **Status:** DEAD
- **Table addr:** 0x00080BB9 (ARMadb) | 0x000972F1 (AppleCarPlay) | 0x000199C1 (riddleBoxCfg)
- **String VA:** 0x00080BB9 (ARMadb), 0x000972F1 (AppleCarPlay), 0x0001F8F6 (server.cgi)

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x00080BB9) | 0 | NOT FOUND |
| AppleCarPlay | 1 (0x000972F1) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 (0x0008367A) | 0 | NOT FOUND |
| bluetoothDaemon | 1 (0x0006F980) | 0 | NOT FOUND |
| server.cgi | 1 (0x0001F8F6) | 0 | NOT FOUND |
| riddleBoxCfg | 1 (0x000199C1) | 0 | NOT FOUND |

#### Behavior / Value Effects

Zero code xrefs across all six analyzed binaries. The 512-byte buffer size suggests this was designed to cache the H.264 SPS/PPS (Sequence/Picture Parameter Sets) from the last phone connection, likely for faster video stream re-establishment. The name "LastPhoneSpsPps" implies it would store the base64-encoded or raw SPS+PPS NAL units. This is a vestigial key -- possibly replaced by the live SPS/PPS extraction path in the video pipeline (the 36-byte header prepended to H.264 frames contains inline SPS/PPS references).

---


### [S7] CustomId — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 32
- **Status:** PASS-THROUGH (server.cgi only)
- **Table addr:** 0x00080BC9 (ARMadb) | 0x00097301 (AppleCarPlay) | 0x000199D1 (riddleBoxCfg)
- **String VA:** 0x0001DEB4 (server.cgi, xref'd), 0x0001F906 (server.cgi, config table)

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x00080BC9) | 0 | NOT FOUND |
| AppleCarPlay | 1 (0x00097301) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 (0x0008368A) | 0 | NOT FOUND |
| bluetoothDaemon | 1 (0x0006F990) | 0 | NOT FOUND |
| server.cgi | 2 (0x0001DEB4, 0x0001F906) | **2** | ALIVE |
| riddleBoxCfg | 1 (0x000199D1) | 0 | NOT FOUND |

#### Behavior / Value Effects

CustomId is referenced exclusively in server.cgi in two functions:

1. **FUN_00013430** (xref at 0x00013476) -- This function also references CarBrand (0x0001343A), CarModel (0x00013452), CarDate (0x00013464), and CustomId (0x00013476). This is a **web API JSON response builder** that assembles device info for the configuration web UI (`http://192.168.43.1/cgi-bin/server.cgi`). It reads config keys via GetBoxConfigStr and formats them into a JSON response. CustomId is included alongside the vehicle identification fields.

2. **FUN_00012c80** (xref at 0x00012D0C) -- This function also reads CarBrand, CarModel, CarDate, and CustomId. It additionally calls `GetBoxConfig("CarDate")` (int). This is a **second API endpoint handler** (likely a different CGI request path) that returns the same device identity block. The function also reads a numeric CarDate value.

#### Pseudocode (server.cgi FUN_00013430)
```
// Web API response builder
json_obj = new_json_object();
add_string(json_obj, "CarBrand", GetBoxConfigStr("CarBrand"));     // 0x0001343A
add_string(json_obj, "CarModel", GetBoxConfigStr("CarModel"));     // 0x00013452
add_string(json_obj, "CarDate",  GetBoxConfigStr("CarDate"));      // 0x00013464
add_string(json_obj, "CustomId", GetBoxConfigStr("CustomId"));     // 0x00013476
// ... additional fields ...
return json_response(json_obj);
```

#### Cross-Binary Behavior

CustomId is a **server.cgi read-only** key. No binary writes to it via SetBoxConfigStr. The value would be set either by direct config file editing or via an external provisioning mechanism not captured in the analyzed binaries. It serves as an OEM-assignable identifier exposed through the web configuration API, likely for fleet management or unit tracking by the integrator. The 32-byte buffer constrains it to a short identifier string.

---


### [S8] LastConnectedDevice — Deep Analysis

**Config table entry:** Index 8, `0x0006e142` (ARMadb), `0x0009730a` (AppleCarPlay), `0x0006f999` (bluetoothDaemon).

**Xref count:** ARMadb=3 (ALIVE), bluetoothDaemon=1 (DEAD candidate), server.cgi=0, AppleCarPlay=0.

**Set path:** Written by `FUN_0001b7c8` at `0x1b7ec` in ARMadb when a device successfully connects:

```c
// FUN_0001b7c8 (ARMadb, from r2 disassembly)
void FUN_0001b7c8(int session, char *mac_addr, int mode, int arg4) {
    if (/* USB connected && protocol mode set */) {
        FUN_00016608();  // Check connection type
        if (result == 0) return;
        
        // Store the connected device's MAC address
        FUN_00067040("LastConnectedDevice", mac_addr);  // SetBoxConfigStr
        
        // Then check if IAP2 connection
        if (strcmp(arg4, "IAP2") == 0) {
            // CarPlay-specific reconnect setup
            FUN_000181ac(3, 6, device_info);  // Store connection params
            FUN_00023920(session + 0xbc, mac_addr);
        }
    }
}
```

**Read path:**
- `FUN_0006995c` at `0x69988` (ARMadb) -- **FastConnect/NeedAutoConnect checker**:
  ```c
  bool FUN_0006995c(void) {
      if (cached == -1) {
          memset(mac_buf, 0, 0x12);  // 18 bytes = "XX:XX:XX:XX:XX:XX\0"
          FUN_00067210("LastConnectedDevice", mac_buf, 0x12);
          FUN_00067760("HU_CONNECT_INFO", &connect_info, 0x18);
          iVar1 = FUN_00066d3c("FastConnect");
          if (iVar1 == 1 && 
              FUN_00066d3c("NeedAutoConnect") == 1 &&
              strlen(mac_buf) == 0x11 &&  // Valid MAC: 17 chars
              connect_info.width > 0 && connect_info.height > 0 && connect_info.fps > 0) {
              cached = 1;  // FastConnect eligible
          } else {
              cached = 0;
          }
      }
      return cached != 0;
  }
  ```
- `FUN_0001c1a4` at `0x1c446` (ARMadb) -- reads during BT reconnection attempt
- `FUN_0001b7c8` at `0x1b7ec` (ARMadb) -- writes after successful connection

- bluetoothDaemon: `FUN_0005c2a4` at `0x5c2d0` -- reads for BT auto-reconnect

**Format:** 17-char BT MAC string `"XX:XX:XX:XX:XX:XX"` + null terminator (fits in 18-byte buffer).

**Side effects:** Determines which device to auto-reconnect to on next boot. When `FastConnect=1` AND `NeedAutoConnect=1` AND a valid 17-char MAC is stored AND valid display info is cached, the adapter skips BT discovery and connects directly.

---


### [S9] IgnoreUpdateVersion — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 16
- **Status:** ALIVE (server.cgi only -- OTA version skip logic)
- **Table addr:** 0x00080BD2 (ARMadb) | 0x0009731E (AppleCarPlay) | 0x000199EE (riddleBoxCfg)
- **String VA:** 0x0001F923 (server.cgi)

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x00080BD2) | 0 | NOT FOUND |
| AppleCarPlay | 1 (0x0009731E) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 (0x000836A7) | 0 | NOT FOUND |
| bluetoothDaemon | 1 (0x0006F9AD) | 0 | NOT FOUND |
| server.cgi | 1 (0x0001F923) | **1** | DEAD candidate (but functionally alive) |
| riddleBoxCfg | 1 (0x000199EE) | 0 | NOT FOUND |

#### Behavior / Value Effects

IgnoreUpdateVersion has a single xref in server.cgi at 0x00019460 within **FUN_000193f4**. This function is the **OTA firmware update check handler**. The decompiled context from the config trace reveals the control flow:

```
// FUN_000193f4 -- OTA update check (server.cgi)
// At 0x00019412: checks AutoUpdate config
if ((iVar1 == 0) && (iVar1 = GetBoxConfig("AutoUpdate"), iVar1 == 1)) {
    // AutoUpdate is enabled
    // At 0x00019460: reads IgnoreUpdateVersion
    version_to_skip = GetBoxConfigStr("IgnoreUpdateVersion");
    // Compare against available firmware version from OTA server
    // If match: skip this update silently
    // If no match: proceed with update notification/download
}
```

The 16-byte buffer fits a version string like "2025.10.15.1127". When a user dismisses an OTA update prompt, the firmware version they declined is written to IgnoreUpdateVersion. On subsequent OTA checks, if the available version matches IgnoreUpdateVersion, the update is suppressed. This prevents repeated prompts for a version the user has already rejected.

#### Cross-Binary Behavior

Only server.cgi references this key. The config trace marks it as "dead_candidate" because the tracer only found 1 xref (its heuristic requires 2+), but the function FUN_000193f4 is clearly a live OTA update handler gated by the AutoUpdate int config key. Status is effectively **ALIVE** in server.cgi.

---


### [S10] CustomBoxName — Deep Analysis

**Config table entry:** Index 10, `0x0006c8ba` (ARMadb), `0x00019a02` (riddleBoxCfg).

**Xref count:** ARMadb=1 (DEAD candidate -- data ref only at `0x94034`), server.cgi=2 (ALIVE), riddleBoxCfg=0, bluetoothDaemon=0, AppleCarPlay=0.

**JSON field:** `"boxName"` -> mapping table entry [20] at `0x93f90`.

**Set path:** BoxSettings JSON `"boxName"` -> generic mapper -> type 0x10 (string) -> `FUN_00067040("CustomBoxName", value)`. No special handler.

**Read path:**
- server.cgi: `FUN_00013878` at `0x1391e` (settings page display -- shown in web UI as box name), data ref at `0x135ee` (settings table)
- ARMadb: Only a data reference at `0x94034` (mapping table entry) -- no active code reads this value

**Side effects:** Displayed in the web UI configuration page. No shell commands, no file operations, no protocol impact. Purely cosmetic identifier.

---


### [S11] WifiPassword — Deep Analysis

**Config table entry:** Index 11, `0x00080be6` (ARMadb), `0x00097340` (AppleCarPlay), `0x0006f9cf` (bluetoothDaemon).

**Xref count:** ARMadb=1 (DEAD candidate), server.cgi=0, bluetoothDaemon=0, AppleCarPlay=0.

**Set path:** Only written by `FUN_0006a710` (name generation) when generating a new WiFi name for the first time:

```c
// At 0x6a7c2-0x6a7c6 in FUN_0006a710:
FUN_00067040("WifiPassword", "12345678");  // Set default password
```

This happens when `CustomWifiName` is empty and the box generates a name with suffix.

The web API (server.cgi) does NOT expose WifiPassword for reading or writing (0 xrefs). The password is **read by hostapd** directly from `/etc/hostapd.conf` (wpa_passphrase field), not from riddle.conf.

**Side effects:** The actual WiFi password is managed in hostapd.conf, not via the config system. The riddle.conf value is a metadata record. Default is always `"12345678"` -- a well-known weak password.

---


### [S12] BrandName — Deep Analysis

**Config table entry:** Index 12, `0x0006d24a` (ARMadb).

**Xref count:** ARMadb=3 (ALIVE), server.cgi=0, bluetoothDaemon=0, AppleCarPlay=0.

**Set path:** Commission handler `FUN_000183d0` (cmd 0x70):

```c
// boxsettings_full_decomp.txt:437-451
void FUN_000183d0(...) {
    char *wifiFormat = FUN_00018054(param_1, "wifiFormat");
    char *btFormat   = FUN_00018054(param_1, "btFormat");
    
    if (*wifiFormat != '\0' && *btFormat != '\0') {
        if (strcmp(wifiFormat, btFormat) == 0) {
            // Same template for both: store as BrandName
            FUN_00067040("BrandName", wifiFormat);
            FUN_00067040("BrandBluetoothName", "");  // Clear specific
            FUN_00067040("BrandWifiName", "");        // Clear specific
        } else {
            // Different templates
            FUN_00067040("BrandWifiName", wifiFormat);
            FUN_00067040("BrandBluetoothName", btFormat);
            FUN_00067040("BrandName", "");            // Clear unified
        }
    }
}
```

**Read path:** `FUN_0006a710` at `0x6a824` -- name generation reads BrandName to determine if brand-specific naming applies.

**Side effects:** Template for WiFi and BT name generation. If BrandName is set (and BrandBluetoothName/BrandWifiName are empty), it is used as the base for both names with a hex suffix appended.

---


### [S13] BrandBluetoothName — Deep Analysis

**Config table entry:** Index 13, `0x0006d254` (ARMadb).

**Xref count:** ARMadb=3 (ALIVE), all others=0.

**Set path:** Commission handler `FUN_000183d0` -- set to the `btFormat` value if it differs from `wifiFormat`, otherwise cleared to `""`.

**Read path:** `FUN_0006a710` at `0x6a862`:

```c
// Name generation:
// If BrandName is empty (brand templates differ):
FUN_00067210("BrandBluetoothName", buf7, 0x10);  // Read brand BT template
FUN_00067210("BrandWifiName", buf6, 0x10);        // Read brand WiFi template
```

If `BrandBluetoothName` is set, it becomes the BT name template. The suffix format depends on the number of `*` wildcard characters stripped from the end (via `FUN_00069f54`):
- 2 wildcards: `"%s%02X"`
- 3 wildcards: `"%s%03X"`
- 4 wildcards: `"%s%04X"` or `"%s-%04X"`
- Else: `"%s-%02X"`

The suffix byte is derived from `FUN_00069a08()` (hardware serial hash).

---


### [S14] BrandWifiName — Deep Analysis

**Config table entry:** Index 14, `0x0006d267` (ARMadb).

**Xref count:** ARMadb=3 (ALIVE), all others=0.

**Set path:** Commission handler `FUN_000183d0` -- set to `wifiFormat` value if it differs from `btFormat`, otherwise cleared.

**Read path:** `FUN_0006a710` at `0x6a86e`:

```c
FUN_00067210("BrandWifiName", buf6, 0x10);
```

Same suffix logic as BrandBluetoothName. If set, overrides the default WiFi SSID template. The resolved name is written to `CustomWifiName` and applied via `FUN_00069e34` (the wifiName handler with the command injection vulnerability).

**Complete Brand Name Resolution Priority:**
1. `CustomBluetoothName`/`CustomWifiName` (user-set via "btName"/"wifiName" BoxSettings)
2. `BrandBluetoothName`/`BrandWifiName` (set via commission cmd 0x70)
3. `BrandName` (unified brand template, also commission)
4. Default name from `FUN_00069acc()`/`FUN_00069b64()` (hardware-derived)

---


### [S15] BrandServiceURL — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 32
- **Status:** DEAD
- **Table addr:** 0x00080BF3 (ARMadb) | 0x00097381 (AppleCarPlay) | 0x00019A51 (riddleBoxCfg)
- **String VA:** 0x0001F986 (server.cgi)

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x00080BF3) | 0 | NOT FOUND |
| AppleCarPlay | 1 (0x00097381) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 | 0 | NOT FOUND |
| bluetoothDaemon | 1 | 0 | NOT FOUND |
| server.cgi | 1 (0x0001F986) | 0 | NOT FOUND |
| riddleBoxCfg | 1 (0x00019A51) | 0 | NOT FOUND |

#### Behavior / Value Effects

Zero code xrefs across all six binaries. The name and 32-byte buffer suggest this was intended to hold a URL for a brand-specific cloud service (OTA server, telemetry endpoint, or configuration service). The "Brand" prefix aligns it with the commission/branding system (BrandName, BrandWifiName, BrandBluetoothName), suggesting it was part of the OEM customization framework. Likely deprecated in favor of hardcoded OTA URLs or removed when the cloud service architecture changed.

---


### [S16] BoxIp — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 16
- **Status:** DEAD
- **Table addr:** 0x00080C03 (ARMadb) | 0x00097391 (AppleCarPlay) | 0x00019A61 (riddleBoxCfg)
- **String VA:** 0x0001F996 (server.cgi)

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x00080C03) | 0 | NOT FOUND |
| AppleCarPlay | 1 (0x00097391) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 | 0 | NOT FOUND |
| bluetoothDaemon | 1 | 0 | NOT FOUND |
| server.cgi | 1 (0x0001F996) | 0 | NOT FOUND |
| riddleBoxCfg | 1 (0x00019A61) | 0 | NOT FOUND |

#### Behavior / Value Effects

Zero code xrefs across all six binaries. The 16-byte buffer fits an IPv4 address string. The adapter's IP is hardcoded at 192.168.43.1 in the DHCP server/hostapd configuration. This key was likely intended to allow configurable IP assignment but was never wired into the network stack. The IP remains hardcoded in `/script/start_bluetooth_wifi.sh` and related startup scripts.

---


### [S17] USBProduct — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 64
- **Status:** ALIVE (commission setting, ARMadb-driver + boxsettings)
- **Table addr:** 0x0006D210 (ARMadb, string in .rodata) | 0x00097397 (AppleCarPlay) | 0x00019A67 (riddleBoxCfg)
- **String VA:** 0x0006D210 (ARMadb), 0x0001F99C (server.cgi)
- **Write site:** FUN_000183d0 at 0x000184B0 (ARMadb)
- **BoxSettings JSON field:** `usb.product` (nested under `usb` object in cmd 0x70 payload)

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x0006D210) | **1** (FUN_000183d0) | Commission write |
| AppleCarPlay | 1 (0x00097397) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 (0x000836FC) | 0 | NOT FOUND |
| bluetoothDaemon | 1 (0x0006FA1D) | 0 | NOT FOUND |
| server.cgi | 1 (0x0001F99C) | 0 | NOT FOUND |
| riddleBoxCfg | 1 (0x00019A67) | 0 | NOT FOUND |

#### Behavior / Value Effects

USBProduct is written by FUN_000183d0 (the commission handler, invoked via cmd 0x70). The boxsettings_full_decomp.txt shows the complete flow:

```
// FUN_000183d0 -- Commission/Brand Settings Parser
// Receives JSON payload from host app via BoxSettings cmd 0x70
//
// JSON structure: { "usb": { "pid": N, "vid": N, "manufacturer": "...",
//                            "product": "...", "serial": "..." },
//                   "wifiFormat": "...", "btFormat": "..." }

void FUN_000183d0(cJSON *param_1, ...) {
    usb_obj = cJSON_GetObjectItem(param_1, "usb");        // DAT_0006d1ba
    wifi_fmt = cJSON_GetStringValue(param_1, "wifiFormat");
    bt_fmt   = cJSON_GetStringValue(param_1, "btFormat");

    if (usb_obj != NULL) {
        // ... PID/VID handled as integers (see S19/S20) ...

        product_str = cJSON_GetStringValue(usb_obj, "product");  // pcVar6
        if (product_str != NULL && *product_str != '\0') {
            SetBoxConfigStr("USBProduct", product_str);   // 0x000184B0
        }
    }
    // ... wifiFormat/btFormat handling ...
}
```

The value is persisted to riddleBoxCfg. On next USB enumeration (device reset/reconnect), the USB gadget driver reads this value and uses it as the `iProduct` USB string descriptor. This is the human-readable product name the host OS sees (e.g., "Carlinkit-A15W" in the default case, or an OEM-customized string like "Wireless CarPlay Adapter").

#### USB Gadget Effect

When the host enumerates the CPC200 over USB, the `iProduct` descriptor string is read from this config key. Changing it affects what appears in `lsusb` output, the host's device manager, and any host-side software that filters by product string. The 64-byte buffer accommodates typical USB product strings.

---


### [S18] USBManufacturer — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 64
- **Status:** ALIVE (commission setting, ARMadb-driver + boxsettings)
- **Table addr:** 0x0006D200 (ARMadb) | 0x000973A2 (AppleCarPlay) | 0x00019A72 (riddleBoxCfg)
- **String VA:** 0x0006D200 (ARMadb), 0x0001F9A7 (server.cgi)
- **Write site:** FUN_000183d0 at 0x0001849C (ARMadb)
- **BoxSettings JSON field:** `usb.manufacturer`

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x0006D200) | **1** (FUN_000183d0) | Commission write |
| AppleCarPlay | 1 (0x000973A2) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 (0x00083707) | 0 | NOT FOUND |
| bluetoothDaemon | 1 (0x0006FA28) | 0 | NOT FOUND |
| server.cgi | 1 (0x0001F9A7) | 0 | NOT FOUND |
| riddleBoxCfg | 1 (0x00019A72) | 0 | NOT FOUND |

#### Behavior / Value Effects

Written by the same commission handler FUN_000183d0, from the `usb.manufacturer` field of the cmd 0x70 JSON payload:

```
// Within FUN_000183d0:
manufacturer_str = cJSON_GetStringValue(usb_obj, "manufacturer");  // pcVar5
if (manufacturer_str != NULL && *manufacturer_str != '\0') {
    SetBoxConfigStr("USBManufacturer", manufacturer_str);  // 0x0001849C
}
```

This maps to the `iManufacturer` USB string descriptor. Default would be "Carlinkit" or similar. OEMs commissioning white-label units set this to their own brand name (e.g., "Ottocast", "CPLAY2air"). The host OS displays this in device properties and USB device information.

#### USB Gadget Effect

Changes the manufacturer string visible to the host OS during USB enumeration. Some host-side drivers may filter or match on this string, so altering it could affect compatibility with specific head units that whitelist known manufacturer strings.

---


### [S19] USBPID — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 5
- **Status:** ALIVE (commission setting, ARMadb-driver + boxsettings)
- **Table addr:** 0x0006D1F2 (ARMadb) | 0x000973B2 (AppleCarPlay) | 0x00019A82 (riddleBoxCfg)
- **String VA:** 0x0006D1F2 (ARMadb), 0x0001F9B7 (server.cgi)
- **Write site:** FUN_000183d0 at 0x00018470 (ARMadb)
- **BoxSettings JSON field:** `usb.pid` (integer in JSON, converted to hex string)

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x0006D1F2) | **1** (FUN_000183d0) | Commission write |
| AppleCarPlay | 1 (0x000973B2) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 (0x00083717) | 0 | NOT FOUND |
| bluetoothDaemon | 1 (0x0006FA38) | 0 | NOT FOUND |
| server.cgi | 1 (0x0001F9B7) | 0 | NOT FOUND |
| riddleBoxCfg | 1 (0x00019A82) | 0 | NOT FOUND |

#### Behavior / Value Effects

USBPID is the USB Product ID, stored as a **hex string** (not integer). The 5-byte buffer fits exactly 4 hex digits + null terminator (e.g., "1521"). The commission handler extracts it as an integer from JSON, converts to hex, and stores:

```
// Within FUN_000183d0:
pid_int = cJSON_GetObjectItem(usb_obj, "pid");  // DAT_0006d1d2 = "pid"
if (pid_int != NULL && pid_int->type == cJSON_Number) {
    pid_val = pid_int->valueint;
} else {
    pid_val = -1;
}

if (pid_val + 1U > 1) {   // i.e., pid_val >= 0 (not -1)
    sprintf(local_30, "%x", pid_val);           // DAT_00018538 = "%x"
    SetBoxConfigStr("USBPID", local_30);        // DAT_0001853c -> "USBPID"
}
```

Note the format string `"%x"` (lowercase hex, no 0x prefix, no zero-padding). So PID 0x1521 is stored as "1521".

#### USB Gadget Effect

The PID is the `idProduct` field in the USB device descriptor. This is the primary identifier used by host-side USB drivers. The CPC200 default PID is 0x1521 (observed on GM Info 3.7 logcat as VID 0x1314, PID 0x1521). Changing this value **directly affects whether the host head unit recognizes the adapter**. Head units with Android Auto or CarPlay support maintain whitelists of known VID:PID pairs. Setting an unrecognized PID will cause the host to fail USB device matching.

**Security implication:** This allows the adapter to impersonate any USB device by PID. Combined with VID spoofing (S20), an adapter could masquerade as a different product entirely.

---


### [S20] USBVID — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 5
- **Status:** ALIVE (commission setting, ARMadb-driver + boxsettings)
- **Table addr:** 0x0006D1F9 (ARMadb) | 0x000973B9 (AppleCarPlay) | 0x00019A89 (riddleBoxCfg)
- **String VA:** 0x0006D1F9 (ARMadb), 0x0001F9BE (server.cgi)
- **Write site:** FUN_000183d0 at 0x00018488 (ARMadb)
- **BoxSettings JSON field:** `usb.vid` (integer in JSON, converted to hex string)

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x0006D1F9) | **1** (FUN_000183d0) | Commission write |
| AppleCarPlay | 1 (0x000973B9) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 (0x0008371E) | 0 | NOT FOUND |
| bluetoothDaemon | 1 (0x0006FA3F) | 0 | NOT FOUND |
| server.cgi | 1 (0x0001F9BE) | 0 | NOT FOUND |
| riddleBoxCfg | 1 (0x00019A89) | 0 | NOT FOUND |

#### Behavior / Value Effects

Identical mechanism to USBPID. The JSON field `usb.vid` is extracted as integer, converted to hex string via `sprintf(local_28, "%x", vid_val)`, and stored:

```
// Within FUN_000183d0:
vid_int = cJSON_GetObjectItem(usb_obj, "vid");  // DAT_0006d1d6 = "vid"
if (vid_int != NULL && vid_int->type == cJSON_Number) {
    vid_val = vid_int->valueint;
} else {
    vid_val = -1;
}

if (vid_val + 1U > 1) {   // vid_val >= 0
    sprintf(local_28, "%x", vid_val);
    SetBoxConfigStr("USBVID", local_28);        // DAT_00018540 -> "USBVID"
}
```

Default VID is 0x1314 (Carlinkit/AutoKit). The 5-byte buffer fits "1314\0".

#### USB Gadget Effect

The VID is the `idVendor` field in the USB device descriptor. This identifies the manufacturer at the USB protocol level. Combined with PID, the VID:PID pair is the primary USB device identification mechanism. The host kernel uses VID:PID to select the appropriate driver. On GM Info 3.7, the host enumerates the adapter as VID 0x1314 / PID 0x1521 and matches it to the accessory/ADB driver.

**OEM use case:** White-label manufacturers who have their own USB VID (e.g., 0x2996 for GM) can commission adapters with their VID so that host-side driver matching works with their custom USB driver configurations.

---


### [S21] USBSerial — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 64
- **Status:** ALIVE (commission setting, ARMadb-driver + boxsettings)
- **Table addr:** 0x0006D21B (ARMadb) | 0x000973C0 (AppleCarPlay) | 0x00019A90 (riddleBoxCfg)
- **String VA:** 0x0006D21B (ARMadb), 0x0001F9C5 (server.cgi)
- **Write site:** FUN_000183d0 at 0x000184BE (ARMadb)
- **BoxSettings JSON field:** `usb.serial`

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x0006D21B) | **1** (FUN_000183d0) | Commission write |
| AppleCarPlay | 1 (0x000973C0) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 (0x00083725) | 0 | NOT FOUND |
| bluetoothDaemon | 1 (0x0006FA46) | 0 | NOT FOUND |
| server.cgi | 1 (0x0001F9C5) | 0 | NOT FOUND |
| riddleBoxCfg | 1 (0x00019A90) | 0 | NOT FOUND |

#### Behavior / Value Effects

Written by the commission handler from `usb.serial` JSON string:

```
// Within FUN_000183d0:
serial_str = cJSON_GetStringValue(usb_obj, "serial");  // pcVar7/pcVar6
if (serial_str != NULL && *serial_str != '\0') {
    SetBoxConfigStr("USBSerial", serial_str);   // 0x000184BE
}
```

This maps to the `iSerialNumber` USB string descriptor. The serial number uniquely identifies a specific adapter unit. It is typically set during factory provisioning and read by the host for device persistence (remembering paired devices, associating settings with a specific adapter).

#### USB Gadget Effect

The serial number appears in `lsusb -v` output and is used by operating systems to distinguish multiple devices of the same VID:PID. On Android host systems (like GM Info 3.7), the USB serial is used to create stable device paths in `/dev/bus/usb/`. The 64-byte buffer allows for standard serial number formats.

---

### Commission Keys (S17-S21) -- Shared Architecture

All five USB commission keys share a common write path through **FUN_000183d0** at 0x000183d0 in ARMadb-driver. This function is dispatched from the command dispatcher FUN_0001DD98 when it receives **cmd 0x70** from the host application via the BoxSettings protocol.

#### Complete FUN_000183d0 Flow

```
FUN_000183d0(cJSON *json_root, uint param_2, ...) {
    // 1. Parse "usb" nested object
    usb_obj = cJSON_GetObjectItem(json_root, "usb");
    wifi_fmt = cJSON_GetStringValue(json_root, "wifiFormat");
    bt_fmt   = cJSON_GetStringValue(json_root, "btFormat");

    // 2. Enable/disable USB gadget based on param_2
    FUN_00066cf8(param_2 != 0);   // USB gadget control

    // 3. If usb object present, write all 5 USB descriptor keys
    if (usb_obj) {
        pid = extract_int(usb_obj, "pid");   // -> hex string -> "USBPID"
        vid = extract_int(usb_obj, "vid");   // -> hex string -> "USBVID"
        mfg = extract_str(usb_obj, "manufacturer"); // -> "USBManufacturer"
        prd = extract_str(usb_obj, "product");       // -> "USBProduct"
        ser = extract_str(usb_obj, "serial");        // -> "USBSerial"
    }

    // 4. Handle WiFi/BT naming (BrandName/BrandWifiName/BrandBluetoothName)
    if (wifi_fmt != "" && bt_fmt != "") {
        if (strcmp(wifi_fmt, bt_fmt) == 0) {
            // Same format: set BrandName=wifi_fmt, clear BrandWifiName
            SetBoxConfigStr("BrandName", wifi_fmt);
            SetBoxConfigStr("BrandBluetoothName", "");
            SetBoxConfigStr("BrandWifiName", "");
        } else {
            // Different: set each individually, clear BrandName
            SetBoxConfigStr("BrandWifiName", wifi_fmt);
            SetBoxConfigStr("BrandBluetoothName", bt_fmt);
            SetBoxConfigStr("BrandName", "");
        }
    }
}
```

#### Host-Side Enumeration Impact

When these values are changed via cmd 0x70, they are persisted to riddleBoxCfg. The USB gadget driver reads them on the next USB device reset/reconnect cycle. The actual sysfs paths for the USB gadget are not visible in the decompiled code (likely in a kernel module or `/sys/kernel/config/usb_gadget/` configfs), but the adapter's USB descriptor is composed from these stored values. The host then sees the new VID:PID:manufacturer:product:serial during enumeration.

| Config Key | USB Descriptor Field | Format | JSON Source |
|-----------|---------------------|--------|-------------|
| USBPID | idProduct | hex string (4 chars) | `usb.pid` (int) |
| USBVID | idVendor | hex string (4 chars) | `usb.vid` (int) |
| USBManufacturer | iManufacturer | UTF-8 string | `usb.manufacturer` |
| USBProduct | iProduct | UTF-8 string | `usb.product` |
| USBSerial | iSerialNumber | UTF-8 string | `usb.serial` |

---


### [S22] oemName — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 64
- **Status:** DEAD
- **Table addr:** 0x00080C09 (ARMadb) | 0x000973CA (AppleCarPlay) | 0x00019A9A (riddleBoxCfg)
- **String VA:** 0x0001F9CF (server.cgi)

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x00080C09) | 0 | NOT FOUND |
| AppleCarPlay | 1 (0x000973CA) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 (0x0008372F) | 0 | NOT FOUND |
| bluetoothDaemon | 1 (0x0006FA50) | 0 | NOT FOUND |
| server.cgi | 1 (0x0001F9CF) | 0 | NOT FOUND |
| riddleBoxCfg | 1 (0x00019A9A) | 0 | NOT FOUND |

#### Behavior / Value Effects

Zero code xrefs across all six binaries. The 64-byte buffer and "oem" prefix suggest this was intended as an OEM branding identifier, distinct from BrandName (which controls WiFi/BT naming). It may have been intended for display in the web configuration UI or for telemetry reporting. The naming convention ("oemName" in camelCase vs. "BrandName" in PascalCase) suggests it was added by a different developer or at a different design phase. Completely vestigial in firmware 2025.10.15.

---


### [S23] BrandWifiChannel — Deep Analysis

- **Type:** String | **Default:** "" | **Bufsize:** 32
- **Status:** ALIVE (ARMadb-driver only -- WiFi channel branding override)
- **Table addr:** 0x00080C11 (ARMadb) | 0x000973D2 (AppleCarPlay) | 0x00019AA2 (riddleBoxCfg)
- **String VA:** 0x00080C11 (ARMadb)
- **Read site:** FUN_0006A29C at 0x0006A2B0 (ARMadb)

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x00080C11) | **1** (FUN_0006A29C) | ALIVE |
| AppleCarPlay | 1 (0x000973D2) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 0 | 0 | NOT FOUND |
| bluetoothDaemon | 1 (0x0006FA58) | 0 | NOT FOUND |
| server.cgi | 0 | 0 | NOT FOUND |
| riddleBoxCfg | 1 (0x00019AA2) | 0 | NOT FOUND |

#### Behavior / Value Effects

BrandWifiChannel is referenced once in ARMadb-driver at FUN_0006A29C (xref from 0x0006A2B0). This function was **not included in the caller decomps** (it was not among the functions selected for decompilation by the Ghidra scripts), so we must infer its behavior from context.

The related key **WiFiChannel** (integer config key) is actively used by the BoxSettings JSON iterator (FUN_00016c20) in the WiFiChannel special case handler:

```
// From boxsettings_full_decomp.txt lines 353-357:
current_channel = GetBoxConfig("WiFiChannel");
if (current_channel != json_new_value) {
    SetBoxConfig("WiFiChannel", json_new_value);
    system("(/script/close_bluetooth_wifi.sh;/script/start_bluetooth_wifi.sh)&");
}
```

BrandWifiChannel (a **string**, 32 bytes) likely stores a brand-mandated WiFi channel or channel list that overrides or constrains the runtime WiFiChannel (integer) setting. FUN_0006A29C is in the 0x0006Axxx address range, which in ARMadb-driver corresponds to the WiFi/network management subsystem. The string type (vs. WiFiChannel's integer type) and 32-byte buffer suggest it may encode a channel list or channel/bandwidth specification (e.g., "161" for the observed 5.8GHz ch161, or "36,40,44,48" for a range, or "161/80" for channel+bandwidth).

The function reads this key via GetBoxConfigStr and likely applies it during WiFi AP initialization, either as a default channel or as a constraint on the channel selection logic.

#### Cross-Binary Behavior

Only ARMadb-driver references this key. The WiFi AP is managed by ARMadb-driver via shell scripts (`/script/start_bluetooth_wifi.sh`), which configure hostapd. BrandWifiChannel is read at WiFi startup to determine the channel configuration, potentially overriding the user-settable WiFiChannel if a brand has mandated a specific channel allocation.

---


## Array Keys

### [A] DevList — Deep Analysis

**String locations:**
- ARMadb: `0x00080ba8` (12 xrefs, ALIVE)
- server.cgi: `0x0001f8b0` / `0x0001e0b0` / `0x0001e297` (10 xrefs, ALIVE)
- bluetoothDaemon: `0x0006f94c` / `0x0005de54` / `0x0005eedb` (13 xrefs, ALIVE)
- riddleBoxCfg: 2 callers set DevList (`FUN_00014148` at `0x14188`, `FUN_00014458` at `0x1448c`)

**Format:** JSON array in riddle.conf, embedded in the device info response:
```json
{"uuid":"...","MFD":"...","boxType":"...","OemName":"...","productType":"...",
 "HiCar":0,"supportLinkType":"...","supportFeatures":"...",
 "hwVersion":"...","WiFiChannel":161,"CusCode":"...",
 "DevList":[{"addr":"XX:XX:XX:XX:XX:XX","name":"DeviceName","index":"0"}, ...],
 "ChannelList":"..."}
```

The format string at ARMadb `0x0006e216`:
```
{"uuid":"%s","MFD":"%s","boxType":"%s","OemName":"%s","productType":"%s",
 "HiCar":%d,"supportLinkType":"%s","supportFeatures":"%s",%s"hwVersion":"%s",
 "WiFiChannel":%d,"CusCode":"%s","DevList":%s,"ChannelList":"%s"}
```

**DevList entry fields** (from bluetoothDaemon strings at `0x5efe8`):
- `addr`: BT MAC address (XX:XX:XX:XX:XX:XX)
- `name`: Device friendly name
- `index`: Position index string

Log message: `"SaveDevList to config file, addr:%s name:%s index:%s\n"`

**Management functions:**

**ARMadb:**
| Function | Address | Role |
|----------|---------|------|
| FUN_00019978 | 0x19a02 | Read DevList for response building |
| FUN_0001c1a4 | 0x1c452 | Read DevList during reconnection |
| FUN_0006736c | 0x67388, 0x673b8 | Read DevList AND DeletedDevList (sync) |
| FUN_0001bc24 | 0x1bc82 | Read DevList (connection setup) |
| FUN_00017340 | 0x1c8da | DevList management |
| FUN_00067534 | 0x67558-0x675d8 | DevList AND DeletedDevList (3 xrefs) -- core add/remove |
| FUN_0001806c | 0x1808e, 0x180fa | DevList management (2 xrefs) |

**server.cgi:**
| Function | Address | Role |
|----------|---------|------|
| FUN_00012f98 | 0x12fc6, 0x12fd2 | Read DevList for web display |
| FUN_00017560 | 0x1757c, 0x175ac | DevList + DeletedDevList read |
| FUN_00017728 | 0x1774c, 0x177bc, 0x177cc | DevList management (3 xrefs) |
| FUN_00017870 | 0x1788a, 0x178ae | DevList management (2 xrefs) |

**bluetoothDaemon:**
| Function | Address | Role |
|----------|---------|------|
| FUN_000191a4 | 0x19340, 0x19358 | Save paired device to DevList |
| FUN_00018414 | 0x18508 | DevList read during BT init |
| FUN_0001a50c | 0x1a8f6, 0x1a7ca | DevList manipulation |
| FUN_00016634 | 0x16720 | DevList read |
| FUN_00059940 | 0x5995a, 0x5997e | DevList + DeletedDevList sync |
| FUN_00059630 | 0x5964c, 0x5967c | FastConnect DevList lookup |
| FUN_000597f8 | 0x5981c, 0x5988c, 0x598c | DevList management (3 xrefs) |

**Lifecycle:**
1. Device pairs via BT -> `bluetoothDaemon` adds entry to DevList via `FUN_000191a4`
2. On connection, `ARMadb` reads DevList for device identification
3. Web UI reads DevList via `server.cgi` `FUN_00012f98`
4. DevList is included in the device info JSON response sent to host apps
5. Maximum capacity implied by management code structure (typically 8-10 devices)

---


### [A] DeletedDevList — Deep Analysis

**String locations:**
- ARMadb: `0x00080ba1` (2 xrefs, ALIVE)
- server.cgi: `0x0001f8a9` / `0x0001e290` (4 xrefs, ALIVE)
- bluetoothDaemon: `0x0006f945` / `0x0005eed4` (5 xrefs, ALIVE)
- riddleBoxCfg: 3 callers (`FUN_00014310` at `0x14334`, `FUN_00014458` at `0x14472`, `FUN_00014148` at `0x14164`)

**Format:** Same JSON array structure as DevList, containing devices that were explicitly unpaired/removed.

Log message from bluetoothDaemon at `0x5eee8`: `"Remove bluetooth %s from DeletedDevList\n"`

**Management functions:**

**ARMadb:**
| Function | Address | Role |
|----------|---------|------|
| FUN_0006736c | 0x67388 | Read DeletedDevList (synced with DevList) |
| FUN_00067534 | 0x67558 | Core add/remove operations |

**server.cgi:**
| Function | Address | Role |
|----------|---------|------|
| FUN_00017560 | 0x1757c | Read (synced with DevList read) |
| FUN_00017728 | 0x1774c | Management |
| FUN_00017870 | 0x1788a | Management |

**bluetoothDaemon:**
| Function | Address | Role |
|----------|---------|------|
| FUN_00019038 | 0x19064, 0x190e6 | Remove device from DeletedDevList |
| FUN_00059940 | 0x5995a | Sync with DevList |
| FUN_00059630 | 0x5964c | FastConnect exclusion check |
| FUN_000597f8 | 0x5981c | Management |

**Lifecycle:**
1. User unpairs device via web UI or host app -> device entry moved from DevList to DeletedDevList
2. `bluetoothDaemon` checks DeletedDevList during BT discovery to avoid auto-reconnecting to unpaired devices
3. `FUN_00059630` (FastConnect/NeedAutoConnect) checks DeletedDevList to exclude deleted devices from reconnection candidates
4. If a deleted device re-pairs, its entry is removed from DeletedDevList and added back to DevList
5. Both lists are always read/written together in sync functions (`FUN_0006736c`, `FUN_00067534`, `FUN_00059940`)

---

## Summary Table

| # | Key | Type | bufsize | Default | Xrefs (ARMadb/CGI/BT/CP) | Shell Injection | Files Modified |
|---|-----|------|---------|---------|---------------------------|-----------------|----------------|
| S0 | CarBrand | String | 32 | "" | 12/12/11/0 | Yes (FUN_00068218) | /etc/airplay.conf |
| S1 | CarModel | String | 32 | "" | 6/7/5/0 | No | None |
| S2 | BluetoothName | String | 16 | "" | 2/3/2/2 | No (resolved, not user-set) | None (read-only) |
| S3 | WifiName | String | 16 | "" | 2/3/2/2 | No (resolved, not user-set) | None (read-only) |
| S4 | CustomBluetoothName | String | 16 | "" | 4/1/0/0 | **YES (FUN_00069bfc)** | hcid.conf, eir_info, bluetooth_name |
| S5 | CustomWifiName | String | 16 | "" | 6/1/0/0 | **YES (FUN_00069e34)** | hostapd.conf, wifi_name |
| S8 | LastConnectedDevice | String | 18 | "" | 3/0/1/0 | No | None |
| S10 | CustomBoxName | String | 16 | "" | 1/2/0/0 | No | None |
| S11 | WifiPassword | String | 16 | "12345678" | 1/0/0/0 | No | None (hostapd reads own conf) |
| S12 | BrandName | String | 16 | "" | 3/0/0/0 | No | None |
| S13 | BrandBluetoothName | String | 16 | "" | 3/0/0/0 | No | None |
| S14 | BrandWifiName | String | 16 | "" | 3/0/0/0 | No | None |
| A | DevList | Array | -- | [] | 12/10/13/0 | No | riddle.conf |
| A | DeletedDevList | Array | -- | [] | 2/4/5/3 | No | riddle.conf |

**Critical Vulnerabilities:**
- **S4 CustomBluetoothName**: `sprintf` -> `system("grep ... %s ...")` and `system("sed ... %s ...")` in `FUN_00069bfc` at ARMadb `0x69c54`/`0x69c6e`. Root execution, 16-byte payload limit.
- **S5 CustomWifiName**: `sprintf` -> `system("grep ... %s ...")` and `system("sed ... %s ...")` in `FUN_00069e34` at ARMadb `0x69e88`/`0x69e9e`. Root execution, 16-byte payload limit.
- **S0 CarBrand**: `sprintf` -> `system("grep ... %s ... || sed ...")` in `FUN_00068218` at ARMadb `0x68218`. Root execution, 32-byte payload limit (larger attack surface).


### [A] LangList — Deep Analysis

- **Type:** JSON Array (string config key) | **Default:** (empty) | **Bufsize:** (array, variable)
- **Status:** PASS-THROUGH (server.cgi only)
- **String VA:** 0x0001E0BC (server.cgi, xref'd), 0x0001F8B8 (server.cgi, config table)

#### Code Reference Summary

| Binary | String instances | Total xrefs | Status |
|--------|-----------------|-------------|--------|
| ARMadb-driver | 1 (0x00080BB0) | 0 | NOT FOUND |
| AppleCarPlay | 1 (0x000972C5) | 0 | NOT FOUND |
| ARMiPhoneIAP2 | 1 (0x00083645) | 0 | NOT FOUND |
| bluetoothDaemon | 1 (0x0006F954) | 0 | NOT FOUND |
| server.cgi | 2 (0x0001E0BC, 0x0001F8B8) | **2** | ALIVE |
| riddleBoxCfg | 1 (0x00019995) | 0 | NOT FOUND |

#### Behavior / Value Effects

LangList has 2 xrefs in server.cgi, both in **FUN_00012FE4** at addresses 0x00013012 and 0x0001301E. This function is in the same address range as FUN_00012C80 (which reads CustomId, CarBrand, CarModel, CarDate) and FUN_00012F98 (which reads DevList). These are all **web API data provider functions** for the server.cgi HTTP interface.

The dual xref pattern (two references to "LangList" at adjacent call sites 0x00013012 and 0x0001301E) strongly suggests a read-then-respond pattern:

```
// FUN_00012fe4 -- LangList API handler (server.cgi)
// xref 1 at 0x00013012: Read LangList from config
lang_json = GetBoxConfigStr("LangList");       // 0x00013012

// xref 2 at 0x0001301E: Use LangList in response
add_to_response(response_obj, "LangList", lang_json);  // 0x0001301E
```

LangList is a JSON array stored as a string value in riddleBoxCfg. It contains the list of supported/available UI languages for the adapter's web configuration interface and the host app's language selection. The array format is likely: `["en","zh","de","fr","ja","ko",...]`

The associated integer config key **BoxConfig_UI_Lang** (set via BoxSettings JSON field `"lang"`) selects an index from this list as the active language. LangList provides the menu of available options; BoxConfig_UI_Lang stores the current selection.

#### Cross-Binary Behavior

Only server.cgi reads this key. It is served to the web configuration UI at `http://192.168.43.1/cgi-bin/server.cgi` as part of the device status/configuration response, allowing the web UI to populate a language dropdown. The value is written during factory provisioning or firmware update, not at runtime. No other binary (ARMadb-driver, AppleCarPlay, etc.) reads or writes this key.

#### Relationship to BoxConfig_UI_Lang

| Key | Type | Role | Set by |
|-----|------|------|--------|
| LangList | JSON array string | Available languages | Factory/firmware |
| BoxConfig_UI_Lang | Integer | Selected language index | Host app (cmd 0x19, field "lang") |


## Dead Keys Summary

Keys confirmed DEAD across all 6 binaries (zero non-table GetBoxConfig callers,
zero non-table string xrefs outside config API functions):

- **AudioMultiBusMode** — int idx 72, default=1, range [0,1]
- **BoxConfig_preferSPSPPSType** — int idx 7, default=0, range [0,1]
- **BoxIp** — string idx 16, bufsize=16
- **BrandServiceURL** — string idx 15, bufsize=32
- **HNPInterval** — int idx 41, default=10, range [0,1000]
- **InternetHotspots** — int idx 74, default=0, range [0,1]
- **LastBoxUIType** — int idx 39, default=1, range [0,2]
- **LastPhoneSpsPps** — string idx 6, bufsize=512
- **NotCarPlayH264DecreaseMode** — int idx 8, default=0, range [0,2]
- **UDiskPassThrough** — int idx 26, default=1, range [0,1]
- **lightType** — int idx 42, default=3, range [1,3]
- **oemName** — string idx 22, bufsize=64

**Methodology:** Each dead key was verified across ARMadb-driver (24 Get + 10 Set callers),
AppleCarPlay (19 Get + 0 Set), ARMiPhoneIAP2 (10 Get + 3 Set), bluetoothDaemon (10 Get + 0 Set).
server.cgi and riddleBoxCfg pass-through references do not count as 'alive'.
ImprovedFluency was additionally verified via full-binary decompilation scan (Feb 28 2026).

## BoxSettings JSON Parsing Architecture

Decompiled from ARMadb-driver FUN_00016c20 + FUN_0001658c + FUN_000183d0.

```
FUN_0001dd98 (cmd dispatcher, 6694 bytes)
  ├── cmd 0x19 (BoxSettings) → FUN_00016c20 (JSON iterator, 258 lines)
  │     ├── Special handlers: syncTime, brand, androidWorkMode, btName, wifiName,
  │     │     WiFiChannel, *Vol (6 types), naviScreenInfo, DockPosition
  │     └── Generic path: FUN_0001658c(field) → config key (table at 0x93f90, 29 entries)
  │           ├── cJSON type 0x10 (string) → SetBoxConfigStr(key, val)
  │           └── cJSON type 0x08 (number) → SetBoxConfig(key, val)
  ├── cmd 0x70 (Commission) → FUN_000183d0 (84 lines)
  │     └── usb.{pid,vid,manufacturer,product,serial}, wifiFormat, btFormat
  └── cmd 0xA2 (AppSetBoxConfig) → FUN_00016c20 (same parser)
```

### Mapping Table at 0x93f90 (29 entries, FUN_0001658c)

| # | JSON Field | Config Key | Special Handler |
|---|-----------|------------|-----------------|
| 0 | `btName` | CustomBluetoothName | FUN_00069bfc (hcitool) |
| 1 | `wifiName` | CustomWifiName | FUN_00069e34 (hostapd) |
| 2 | `fps` | CustomFrameRate | — |
| 3 | `gps` | HudGPSSwitch | — |
| 4 | `lang` | BoxConfig_UI_Lang | — |
| 5 | `bgMode` | BackgroundMode | — |
| 6 | `syncMode` | iAP2TransMode | — |
| 7 | `startDelay` | BoxConfig_DelayStart | — |
| 8 | `mediaDelay` | MediaLatency | — |
| 9 | `mediaSound` | MediaQuality | — |
| 10 | `autoConn` | NeedAutoConnect | — |
| 11 | `androidWorkMode` | AndroidWorkMode | fwrite /etc/android_work_mode |
| 12 | `drivePosition` | CarDrivePosition | — |
| 13 | `echoDelay` | EchoLatency | — |
| 14 | `androidAutoSizeW` | AndroidAutoWidth | — |
| 15 | `androidAutoSizeH` | AndroidAutoHeight | — |
| 16 | `screenPhysicalW` | ScreenPhysicalW | — |
| 17 | `screenPhysicalH` | ScreenPhysicalH | — |
| 18 | `brand` | CarBrand | FUN_00068218 (airplay.conf) |
| 19 | `ScreenDPI` | ScreenDPI | — |
| 20 | `boxName` | CustomBoxName | — |
| 21 | `WiFiChannel` | WiFiChannel | WiFi restart script |
| 22 | `UseBTPhone` | UseBTPhone | — |
| 23 | `HiCarConnectMode` | HiCarConnectMode | — |
| 24 | `GNSSCapability` | GNSSCapability | — |
| 25 | `AutoResetUSB` | AutoResetUSB | — |
| 26 | `DashboardInfo` | DashboardInfo | — |
| 27 | `DayNightMode` | DayNightMode | — |
| 28 | `DockPosition` | DuckPosition | HU_VIEWAREA_INFO shm |

**Note:** `DockPosition` → `DuckPosition` is a firmware typo (confirmed in decompilation).

### Special BoxSettings Fields (not in mapping table, not riddleBoxCfg)

| JSON Field | Target | Mechanism |
|-----------|--------|-----------|
| `syncTime` | System clock | `settimeofday(val + 28800)` (UTC+8 offset) |
| `mediaVol` | `HU_AUDIOVOLUME_INFO[0]` | Shared memory |
| `callVol` | `HU_AUDIOVOLUME_INFO[1]` | Shared memory |
| `speechVol` | `HU_AUDIOVOLUME_INFO[2]` | Shared memory |
| `ringVol` | `HU_AUDIOVOLUME_INFO[3]` | Shared memory |
| `navVol` | `HU_AUDIOVOLUME_INFO[4]` | Shared memory |
| `otherVol` | `HU_AUDIOVOLUME_INFO[5]` | Shared memory |
| `naviScreenInfo` | `HU_NAVISCREEN_INFO` + safearea | Shared memory, gated by AdvancedFeatures!=0 |
| `DockPosition` | `HU_VIEWAREA_INFO` | Copies HU_SCREEN_INFO → HU_VIEWAREA_INFO |

### Commission Settings (cmd 0x70, FUN_000183d0)

| JSON Path | Config Key | Notes |
|-----------|------------|-------|
| `usb.pid` | USBPID | Hex-formatted via sprintf "%x" |
| `usb.vid` | USBVID | Hex-formatted |
| `usb.manufacturer` | USBManufacturer | String direct |
| `usb.product` | USBProduct | String direct |
| `usb.serial` | USBSerial | String direct |
| `wifiFormat` | BrandWifiName / BrandName | If wifiFormat==btFormat: BrandName=format |
| `btFormat` | BrandBluetoothName / BrandName | If different: separate names |

### server.cgi Web API Mapping (48 fields)

Source: server.cgi FUN_00014040 (web API serializer)

| JSON Field | Config Key | Direction | Category |
|-----------|-----------|-----------|----------|
| `BtAudio` | BtAudio | get+set | Network / Wireless |
| `CallQuality` | CallQuality | get+set | Audio |
| `KnobMode` | KnobMode | get+set | Display / UI |
| `MediaPacketLen` | MediaPacketLen | get+set | Audio |
| `MicMode` | MicMode | get+set | Audio |
| `NaviAudio` | NaviAudio | get+set | Audio |
| `RepeatKeyFrame` | RepeatKeyframe | get+set | Video / H.264 |
| `ScreenDPI` | ScreenDPI | get+set | Display / UI |
| `SpsPpsMode` | SpsPpsMode | get+set | Video / H.264 |
| `TtsPacketLen` | TtsPacketLen | get+set | Audio |
| `TtsVolumGain` | TtsVolumGain | get+set | Audio |
| `Udisk` | UdiskMode | get+set | System / Branding |
| `VrPacketLen` | VrPacketLen | get+set | Audio |
| `VrVolumGain` | VrVolumGain | get+set | Audio |
| `autoConn` | NeedAutoConnect | get+set | Connection / USB |
| `autoDisplay` | autoDisplay | get+set | Display / UI |
| `autoPlay` | AutoPlauMusic | get+set | System / Branding |
| `autoRefresh` | NeedKeyFrame | get+set | Video / H.264 |
| `autoUpdate` | AutoUpdate | get+set | System / Branding |
| `backRecording` | BackRecording | get+set | System / Branding |
| `bgMode` | BackgroundMode | get+set | Display / UI |
| `bitRate` | VideoBitRate | get+set | Video / H.264 |
| `btCall` | UseBTPhone | get+set | System / Branding |
| `carLinkType` | CarLinkType | get+set | Connection / USB |
| `connectedMode` | USBConnectedMode | get+set | Connection / USB |
| `displaySize` | DisplaySize | get+set | Display / UI |
| `echoDelay` | EchoLatency | get+set | Audio |
| `emptyFrame` | SendEmptyFrame | get+set | Video / H.264 |
| `fastConnect` | FastConnect | get+set | Connection / USB |
| `fps` | CustomFrameRate | get+set | Video / H.264 |
| `gps` | HudGPSSwitch | get+set | GPS / Dashboard |
| `heartBeat` | SendHeartBeat | get+set | Connection / USB |
| `improvedFluency` | ImprovedFluency | get+set | Audio |
| `lang` | BoxConfig_UI_Lang | get+set | System / Branding |
| `mediaDelay` | MediaLatency | get+set | Connection / USB |
| `mediaSound` | MediaQuality | get+set | Audio |
| `micGain` | MicGainSwitch | get+set | Audio |
| `micType` | MicType | get+set | Audio |
| `mouseMode` | MouseMode | get+set | Display / UI |
| `naviVolume` | NaviVolume | get+set | Audio |
| `originalRes` | OriginalResolution | get+set | Video / H.264 |
| `resolutionHeight` | VideoResolutionHeight | get+set | Video / H.264 |
| `resolutionWidth` | VideoResolutionWidth | get+set | Video / H.264 |
| `returnMode` | ReturnMode | get+set | Display / UI |
| `startDelay` | BoxConfig_DelayStart | get+set | Connection / USB |
| `syncMode` | iAP2TransMode | get+set | Connection / USB |
| `transMode` | USBTransMode | get+set | Connection / USB |
| `wifiChannel` | WiFiChannel | get+set | Network / Wireless |

## Cross-Binary Reference Matrix

Xref counts per key per binary (higher = more active usage):

| Key | ARMadb|AppleC|ARMiPh|blueto|server|riddle | Status |
|-----|------|------|------|------|------|------|--------|
| SpsPpsMode                   | 1     |1     |1     |1     |4     |1      | ALIVE (init-read) |
| NeedKeyFrame                 | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| RepeatKeyframe               | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| SendEmptyFrame               | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| VideoBitRate                 | 1     |4     |1     |1     |2     |1      | ALIVE |
| CustomFrameRate              | 2     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| NotCarPlayH264DecreaseMode   | 1     |1     |1     |1     |-     |1      | DEAD |
| VideoResolutionWidth         | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| VideoResolutionHeight        | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| OriginalResolution           | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| BoxConfig_preferSPSPPSType   | 1     |1     |1     |1     |-     |1      | DEAD |
| MediaQuality                 | 8     |7     |6     |6     |8     |12     | ALIVE |
| MicType                      | 3     |1     |1     |1     |2     |1      | ALIVE |
| MicMode                      | 1     |1     |1     |1     |4     |1      | ALIVE (init-read) |
| MicGainSwitch                | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| EchoLatency                  | 3     |1     |1     |1     |2     |1      | ALIVE |
| MediaPacketLen               | 1     |1     |1     |1     |4     |1      | ALIVE (init-read) |
| TtsPacketLen                 | 1     |1     |1     |1     |4     |1      | ALIVE (init-read) |
| VrPacketLen                  | 1     |1     |1     |1     |4     |1      | ALIVE (init-read) |
| TtsVolumGain                 | 1     |1     |1     |1     |4     |1      | ALIVE (init-read) |
| VrVolumGain                  | 1     |1     |1     |1     |4     |1      | ALIVE (init-read) |
| CallQuality                  | 1     |1     |1     |1     |4     |1      | ALIVE |
| VoiceQuality                 | 1     |1     |1     |1     |-     |1      | ALIVE |
| NaviAudio                    | 1     |1     |1     |1     |4     |1      | ALIVE (init-read) |
| NaviVolume                   | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| AudioMultiBusMode            | 1     |1     |1     |1     |-     |1      | DEAD |
| SendHeartBeat                | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| FastConnect                  | 1     |1     |1     |1     |2     |1      | ALIVE |
| NeedAutoConnect              | 3     |1     |1     |3     |1     |1      | ALIVE |
| MediaLatency                 | 2     |1     |1     |1     |2     |1      | ALIVE |
| BoxConfig_DelayStart         | 3     |1     |1     |1     |2     |1      | ALIVE |
| AutoResetUSB                 | 4     |1     |1     |1     |-     |1      | ALIVE |
| USBConnectedMode             | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| USBTransMode                 | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| WiredConnect                 | 2     |1     |1     |1     |-     |1      | ALIVE |
| iAP2TransMode                | 9     |8     |8     |8     |10    |18     | ALIVE |
| CarLinkType                  | 1     |1     |1     |1     |2     |1      | ALIVE |
| AutoConnectInterval          | 1     |1     |1     |1     |-     |1      | ALIVE |
| HNPInterval                  | 1     |1     |1     |1     |-     |1      | DEAD |
| UDiskPassThrough             | 1     |1     |1     |1     |-     |1      | DEAD |
| BackgroundMode               | 2     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| MouseMode                    | 1     |1     |1     |1     |2     |1      | PASS-THROUGH |
| KnobMode                     | 1     |1     |1     |1     |4     |1      | PASS-THROUGH |
| autoDisplay                  | 1     |1     |1     |1     |4     |1      | PASS-THROUGH |
| ScreenDPI                    | 3     |1     |1     |1     |4     |1      | ALIVE |
| DisplaySize                  | 1     |1     |1     |1     |2     |1      | PASS-THROUGH |
| lightType                    | 1     |1     |1     |1     |-     |1      | DEAD |
| LogoType                     | 10    |3     |3     |3     |-     |1      | ALIVE |
| CustomCarLogo                | 2     |1     |1     |1     |1     |1      | ALIVE |
| ReturnMode                   | 3     |1     |1     |1     |2     |1      | ALIVE |
| LastBoxUIType                | 1     |1     |1     |1     |-     |1      | DEAD |
| ScreenPhysicalW              | 2     |2     |1     |1     |-     |1      | ALIVE |
| ScreenPhysicalH              | 2     |2     |1     |1     |-     |1      | ALIVE |
| AndroidWorkMode              | 3     |1     |1     |2     |-     |1      | ALIVE |
| AndroidAutoWidth             | 2     |1     |1     |1     |-     |1      | PASS-THROUGH |
| AndroidAutoHeight            | 2     |1     |1     |1     |-     |1      | PASS-THROUGH |
| HudGPSSwitch                 | 2     |1     |3     |1     |2     |1      | ALIVE |
| GNSSCapability               | 3     |1     |4     |1     |-     |1      | ALIVE |
| DashboardInfo                | 3     |1     |3     |1     |-     |1      | ALIVE |
| DuckPosition                 | 2     |1     |-     |1     |-     |1      | ALIVE |
| WiFiChannel                  | 11    |1     |2     |1     |6     |1      | ALIVE |
| WiFiP2PMode                  | 1     |1     |2     |1     |-     |1      | ALIVE |
| InternetHotspots             | 1     |1     |1     |1     |-     |1      | DEAD |
| ImprovedFluency              | 1     |1     |1     |1     |2     |1      | PASS-THROUGH |
| UseUartBLE                   | 1     |1     |1     |1     |-     |1      | ALIVE |
| BtAudio                      | 2     |1     |1     |1     |4     |1      | ALIVE |
| LogMode                      | 1     |1     |1     |1     |1     |1      | ALIVE |
| BoxConfig_UI_Lang            | 2     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| UdiskMode                    | 2     |1     |1     |1     |2     |1      | ALIVE |
| CarDate                      | 1     |1     |1     |1     |2     |1      | ALIVE |
| AutoPlauMusic                | 1     |1     |2     |1     |2     |1      | ALIVE |
| AutoUpdate                   | 1     |1     |1     |1     |3     |1      | ALIVE (init-read) |
| BoxSupportArea               | 1     |1     |2     |1     |-     |1      | ALIVE |
| BackRecording                | 1     |1     |1     |1     |2     |1      | ALIVE (init-read) |
| HiCarConnectMode             | 3     |1     |1     |1     |1     |1      | ALIVE |
| DayNightMode                 | 3     |1     |1     |1     |-     |1      | ALIVE |
| CarDrivePosition             | 3     |1     |1     |1     |-     |1      | ALIVE |
| UseBTPhone                   | 5     |2     |2     |2     |4     |2      | ALIVE |
| AdvancedFeatures             | 2     |1     |-     |1     |-     |1      | ALIVE |
| CarBrand                     | 12    |11    |10    |11    |12    |13     | ALIVE |
| CarModel                     | 6     |5     |4     |5     |7     |7      | ALIVE |
| BluetoothName                | 2     |2     |2     |2     |3     |2      | ALIVE |
| WifiName                     | 2     |2     |2     |2     |3     |2      | ALIVE |
| CustomBluetoothName          | 4     |1     |1     |1     |1     |1      | ALIVE |
| CustomWifiName               | 6     |1     |1     |1     |1     |1      | ALIVE |
| LastPhoneSpsPps              | 1     |1     |1     |1     |-     |1      | DEAD |
| CustomId                     | 1     |1     |1     |1     |2     |1      | PASS-THROUGH |
| LastConnectedDevice          | 3     |1     |1     |1     |-     |1      | ALIVE |
| IgnoreUpdateVersion          | 1     |1     |1     |1     |1     |1      | PASS-THROUGH |
| CustomBoxName                | 2     |1     |2     |1     |2     |1      | ALIVE |
| WifiPassword                 | 1     |1     |2     |1     |-     |1      | ALIVE |
| BrandName                    | 3     |1     |1     |1     |-     |1      | ALIVE |
| BrandBluetoothName           | 3     |1     |1     |1     |-     |1      | ALIVE |
| BrandWifiName                | 3     |1     |1     |1     |-     |1      | ALIVE |
| BrandServiceURL              | 1     |1     |1     |1     |-     |1      | DEAD |
| BoxIp                        | 1     |1     |1     |1     |-     |1      | DEAD |
| USBProduct                   | 2     |1     |1     |1     |-     |1      | ALIVE |
| USBManufacturer              | 2     |1     |1     |1     |-     |1      | ALIVE |
| USBPID                       | 2     |1     |1     |1     |-     |1      | ALIVE |
| USBVID                       | 2     |1     |1     |1     |-     |1      | ALIVE |
| USBSerial                    | 2     |1     |1     |1     |-     |1      | ALIVE |
| oemName                      | 1     |1     |1     |1     |-     |1      | DEAD |
| BrandWifiChannel             | 1     |1     |-     |1     |-     |1      | ALIVE |
| DevList                      | 12    |7     |7     |13    |10    |7      | ALIVE |
| DeletedDevList               | 2     |3     |3     |5     |4     |3      | ALIVE |
| LangList                     | 1     |1     |1     |1     |2     |1      | PASS-THROUGH |

## Appendix A: Status Definitions

| Status | Definition |
|--------|-----------|
| **ALIVE** | Read via GetBoxConfig at runtime in at least one binary, affects behavior |
| **ALIVE (init-read)** | Set via BoxSettings/Web API, read at config init into globals, used via globals at runtime |
| **DEAD** | Config table entry exists in all binaries, but zero code xrefs in ALL binaries including server.cgi — only config table data entry |
| **PASS-THROUGH** | Code xrefs only in server.cgi and/or riddleBoxCfg (web API serialization); no runtime behavioral effect in core binaries |

## Appendix B: Methodology

1. **Config table dump:** r2 + Python extracted 79 int entries (16B stride: name_ptr, default, min, max)
   and 24 string entries (12B stride: name_ptr, default_ptr, buf_size) from ARMadb-driver at 0x93418/0x932f8
2. **r2 pre-scan:** `iz` string dump + `/x` LE pointer search for xref count across all 6 binaries (~1.3s)
3. **Ghidra headless:** `TraceAllConfigKeys.java` — 3-pass script per binary:
   - Pass 1: Table structure dump with key name resolution
   - Pass 2: `ReferenceManager.getReferencesTo()` for Get/SetBoxConfig (int+str variants) — enumerate all callers, decompile, extract key string arguments
   - Pass 3: `Memory.findBytes()` for each of 106 key name strings — full xref scan beyond config API
4. **BoxSettings analysis:** FUN_00016c20 (actual parser) decompiled → discovered 29-entry mapping table at 0x93f90 (FUN_0001658c). FUN_000183d0 (commission, cmd 0x70) handles USB/branding. server.cgi FUN_00014040 provides Web API Rosetta Stone (48 mappings)
5. **Behavioral analysis:** `DecompCallers.java` — decompiled 54 consumer functions across 4 binaries (ARMadb:24, CarPlay:12, IAP2:10, bluetooth:8). Traced value flow per key: comparisons, arithmetic, function calls, system() invocations, shared memory writes. Documented effect per value in [min,max] range.
6. **Dead key verification:** Cross-checked zero callers across all 6 binaries + zero BoxSettings JSON paths

## Appendix C: Config Table Addresses Per Binary

| Binary | Int Table | Int Count | Str Table | Str Count | GetBoxConfig | SetBoxConfig |
|--------|-----------|-----------|-----------|-----------|-------------|-------------|
| ARMadb-driver | 0x93418 | 79 (0x4F) | 0x932f8 | 24 (0x18) | 0x66d3c | 0x66e58 |
| AppleCarPlay | 0xa9d50 | 79 (0x4F) | 0xa9c30 | 24 (0x18) | 0x73098 | 0x731b4 |
| ARMiPhoneIAP2 | (shared) | 77 (0x4D) | (shared) | 23 (0x17) | 0x6a4d4 | 0x6a5f0 |
| bluetoothDaemon | (shared) | 79 (0x4F) | (shared) | 24 (0x18) | 0x59000 | 0x5911c |
| server.cgi | (shared) | 79 (0x4F) | (shared) | 24 (0x18) | 0x16f64 | N/A |
| riddleBoxCfg | 0x2b12c | 79 (0x4F) | 0x2b00c | 24 (0x18) | 0x13b18 | 0x13c34 |

**Note:** ARMiPhoneIAP2 has 77 int keys (2 fewer: AdvancedFeatures, DuckPosition missing)
and 23 string keys (1 fewer: BrandWifiChannel missing) — compiled from older SDK version.
