# CPC200-CCPA Configuration Reference

**Purpose:** Complete riddleBoxCfg configuration keys reference
**Consolidated from:** pi-carplay firmware analysis, carlink_native research
**Last Updated:** 2026-02-03 (added Host App vs Direct Access configuration analysis)

---

## Configuration System Overview

| Component | Path | Description |
|-----------|------|-------------|
| **Config file** | `/etc/riddle.conf` | **JSON format** (not key=value) |
| **CLI tool** | `riddleBoxCfg -s <Key> [Value]` | Read/write to riddle.conf |
| **Backup** | `/etc/riddle_default.conf` | Factory defaults (minimal JSON) |
| **Runtime** | Global variables | Values loaded at ARMadb-driver startup |
| **Apply changes** | `riddleBoxCfg --upConfig` or reboot | Process restart or reboot needed |

**riddle.conf Format (Verified Jan 2026):**
```json
{
	"USBVID":	"1314",
	"USBPID":	"1521",
	"AndroidWorkMode":	1,
	"MediaLatency":	300,
	"AndroidAutoWidth":	2400,
	"AndroidAutoHeight":	960,
	"BtAudio":	1,
	"DevList":	[{"id":"XX:XX:XX:XX:XX:XX","type":"CarPlay","name":"iPhone"}],
	"LastConnectedDevice":	"XX:XX:XX:XX:XX:XX"
}
```

**Note:** While the file format is JSON, the `riddleBoxCfg` CLI uses key-based access (e.g., `riddleBoxCfg -s AdvancedFeatures 1`).

---

## Video / H.264 Settings

### SpsPpsMode
**Type:** Select (0-3) | **Default:** 0

Controls H.264 SPS/PPS handling for video stream.

| Value | Behavior |
|-------|----------|
| 0 | Auto - firmware decides based on `LastPhoneSpsPps` history |
| 1 | Re-inject - prepends cached SPS/PPS before each IDR frame |
| 2 | Cache - stores SPS/PPS in memory, replays on decode errors |
| 3 | Repeat - duplicates SPS/PPS in every video packet |

### NeedKeyFrame
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Passive - waits for phone's natural IDR interval |
| 1 | Active - sends `RequestKeyFrame` to phone on decoder errors |

Protocol: Uses internal command ID 0x1c (`RefreshFrame`).

### RepeatKeyframe
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Normal - each keyframe sent once |
| 1 | Repeat - re-sends last IDR when buffer underrun detected |

### SendEmptyFrame
**Type:** Toggle (0/1) | **Default:** 1

| Value | Behavior |
|-------|----------|
| 0 | Skip - no packets sent during video gaps |
| 1 | Send - empty timing packets maintain stream clock |

### VideoBitRate
**Type:** Number (0-20) | **Default:** 0

Hint for phone's encoder target bitrate (passed in Open message).

| Value | Effect |
|-------|--------|
| 0 | Auto - phone decides based on WiFi conditions |
| 1-5 | Low bitrate (~2-5 Mbps) |
| 6-15 | Medium bitrate (~6-12 Mbps) |
| 16-20 | High bitrate (~13-20 Mbps) |

### CustomFrameRate
**Type:** Number (0, 20-60) | **Default:** 0

Sets `frameRate` field in Open message.

| Value | Effect |
|-------|--------|
| 0 | Auto - typically 30 FPS |
| 20-60 | Custom frame rate |

---

## Performance / Fluency Settings

### ImprovedFluency
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Standard buffering - lower latency, possible stutters |
| 1 | Enhanced buffering - slightly higher latency, smoother playback |

### FastConnect
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Full handshake - all verification steps |
| 1 | Quick reconnect - skips BT discovery if MAC matches `LastConnectedDevice` |

Reduces connection time by ~2-5 seconds on reconnect.

### SendHeartBeat (CRITICAL)
**Type:** Toggle (0/1) | **Default:** 1 | **Access:** SSH only

Controls whether the adapter firmware expects and responds to heartbeat messages (0xAA) from the host application. This is a **critical setting for connection stability**.

| Value | Behavior |
|-------|----------|
| 0 | Heartbeat disabled - host must poll for status (NOT RECOMMENDED) |
| 1 | Heartbeat enabled - firmware expects 0xAA messages every ~2 seconds |

**WARNING:** Disabling heartbeat (`SendHeartBeat=0`) can cause:
- Cold start failures after ~11.7 seconds with `projectionDisconnected`
- Unstable firmware initialization
- Session termination without warning

See [SendHeartBeat Deep Dive](#sendheartbeat-deep-dive) below for complete analysis.

### BackgroundMode
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Show connection UI (logo, progress) |
| 1 | Hide UI - fixes blur/overlay issues on some head units |

### BoxConfig_DelayStart
**Type:** Number (0-30) | **Default:** 0

Firmware calls `usleep(value * 1000000)` before USB init.

| Value | Behavior |
|-------|----------|
| 0 | Immediate start |
| 1-30 | Wait N seconds before USB init |

### MediaLatency
**Type:** Number (300-2000) | **Default:** 300

Audio/video buffer size in milliseconds.

| Value | Behavior |
|-------|----------|
| 300-500 | Low latency - audio/video more in sync, may skip |
| 500-1000 | Balanced |
| 1000-2000 | High latency - very stable, noticeable A/V desync |

---

## USB / Connection Settings

### AutoResetUSB
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Normal disconnect - USB interface stays initialized |
| 1 | Full reset - USB controller power-cycled via sysfs |

D-Bus: `HUDComand_A_ResetUSB` signal, logged as `"$$$ ResetUSB from HU"`.

**NOT a factory reset** - only resets USB peripheral controller.

### USBConnectedMode
**Type:** Select (0-2) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Standard USB 2.0 enumeration timing |
| 1 | Extended descriptor delays for slow head units |
| 2 | Compatibility mode - slower handshake, retries on failure |

### USBTransMode
**Type:** Select (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Normal - standard bulk transfer sizes |
| 1 | Compact - smaller packets for limited buffers |

### iAP2TransMode
**Type:** Select (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Normal iAP2 framing |
| 1 | Compatible mode - longer ACK timeouts, smaller messages |

### WiredConnect
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Wireless only |
| 1 | Allow wired mode fallback |

### NeedAutoConnect
**Type:** Toggle (0/1) | **Default:** 1

| Value | Behavior |
|-------|----------|
| 0 | Manual - wait for phone to initiate |
| 1 | Auto - reconnect to `LastConnectedDevice` on boot |

---

## Audio Settings

### MediaQuality
**Type:** Select (0/1) | **Default:** 1

| Value | Rate | Description |
|-------|------|-------------|
| 0 | 44.1kHz | CD quality - compatible with all cars |
| 1 | 48kHz | DVD quality - better fidelity |

### MicType
**Type:** Select (0-2) | **Default:** 0

| Value | Source | Implementation |
|-------|--------|----------------|
| 0 | Car mic | Routes `/dev/snd/pcmC0D0c` capture to phone |
| 1 | Box mic | Uses adapter's 3.5mm mic input |
| 2 | Phone mic | Phone's built-in mic |

**Hardware Note:** The CPC200-CCPA (A15W) does **not** have a built-in microphone. Option 1 (Box mic) is not applicable for this model. Use 0 (Car mic) or 2 (Phone mic) only.

### MicMode
**Type:** Select (0-4) | **Default:** 0

| Value | Algorithm |
|-------|-----------|
| 0 | Auto - firmware selects based on detected noise |
| 1-4 | Different WebRTC NS configurations |

### EchoLatency
**Type:** Number (20-2000) | **Default:** 320

Echo cancellation delay parameter in milliseconds.

| Value | Effect |
|-------|--------|
| 20-100 | Low delay - minimal audio path latency |
| 100-500 | Typical car systems |
| 500-2000 | High delay - significant audio buffering |

### MediaPacketLen / TtsPacketLen / VrPacketLen
**Type:** Number (200-40000) | **Default:** 200

USB bulk transfer sizes for different audio streams:
- **MediaPacketLen:** Music/media audio
- **TtsPacketLen:** Navigation voice (TTS)
- **VrPacketLen:** Voice recognition/Siri microphone

---

## Display Settings

### ScreenDPI
**Type:** Number (0-480) | **Default:** 0

| Value | Effect |
|-------|--------|
| 0 | Auto - phone uses default |
| 160 | Low density (MDPI) |
| 240 | Medium density (HDPI) |
| 320 | High density (XHDPI) |
| 480 | Extra high density (XXHDPI) |

### MouseMode
**Type:** Toggle (0/1) | **Default:** 0

| Value | Behavior |
|-------|----------|
| 0 | Direct touch - absolute coordinates passed to phone |
| 1 | Cursor mode - relative movements, tap to click |

**Binary Evidence:** `MouseMode` string found in ARMadb-driver config key table.

---

## GPS/GNSS Settings (Binary Verified Jan 2026)

### HudGPSSwitch
**Type:** Toggle (0/1) | **Default:** 0

Controls whether GPS data from the head unit is forwarded to the phone.

| Value | Behavior |
|-------|----------|
| 0 | GPS from HU disabled - phone uses own GPS |
| 1 | GPS from HU enabled - adapter forwards NMEA to phone |

**Binary Evidence:** `BOX_CFG_HudGPSSwitch Closed, not use GPS from HUD`

### GNSSCapability (Live-Tested Feb 2026)
**Type:** Bitmask (0-65535) | **Default:** 0

Detailed bitmask specifying which GNSS features are advertised to the phone during iAP2 identification.

| Value | Behavior |
|-------|----------|
| 0 | No GNSS capability advertised; **LocationEngine disabled** |
| 1+ | GNSS capability bitmask; **enables LocationEngine for GPS forwarding to phone** |

**CRITICAL:** This setting is **REQUIRED** for `iAP2LocationEngine` to be enabled and for the adapter to advertise `locationInformationComponent` during iAP2 identification. Without `GNSSCapability ≥ 1`, the phone never learns the adapter can provide location data and never sends `StartLocationInformation`.

**DashboardInfo bit 1 is NOT required** for GPS forwarding to the phone. `GNSSCapability` alone gates the adapter→phone GPS path. DashboardInfo bit 1 controls the phone→HUD location data direction, which is a separate engine. Live-tested Feb 2026: `DashboardInfo=5` (bits 0+2, no bit 1) + `GNSSCapability=3` → GPS forwarding to iPhone fully operational.

**Binary Evidence:** `GNSSCapability=%d` format string in ARMiPhoneIAP2

**GNSSCapability Bitmask:**

| Bit | Value | NMEA Sentence | Purpose |
|-----|-------|---------------|---------|
| 0 | 1 | `$GPGGA` | Global Positioning System Fix Data |
| 1 | 2 | `$GPRMC` | Recommended Minimum Specific GPS Transit Data |
| 3 | 8 | `$PASCD` | Proprietary (dead-reckoning/compass) |

Recommended: `GNSSCapability=3` (GPGGA + GPRMC).

### DashboardInfo (Live-Tested & Verified Feb 2026)
**Type:** Bitmask (0-7) | **Default:** 1

Controls which iAP2 data engines are enabled during identification. This is a **3-bit bitmask** where each bit enables a different data stream to the head unit:

| Bit | Value | iAP2 Engine | Data Type | Requirements |
|-----|-------|-------------|-----------|--------------|
| 0 | 1 | **iAP2MediaPlayerEngine** | NowPlaying info (track, artist, album) | None |
| 1 | 2 | **iAP2LocationEngine** | Location FROM phone TO HUD | GNSSCapability ≥ 1 (for phone→HUD direction; GPS forwarding adapter→phone uses GNSSCapability alone) |
| 2 | 4 | **iAP2RouteGuidanceEngine** | Navigation TBT (turn-by-turn directions) | None |

**IMPORTANT:** `iAP2CallStateEngine` is always enabled regardless of DashboardInfo value.

**Common Values:**
| Value | Bits Set | Engines Enabled |
|-------|----------|-----------------|
| 0 | None | CallState only |
| 1 | Bit 0 | MediaPlayer + CallState (default) |
| 2 | Bit 1 | LocationEngine + CallState (requires GNSSCapability ≥ 1) |
| 3 | Bits 0+1 | MediaPlayer + Location + CallState |
| 4 | Bit 2 | RouteGuidance + CallState |
| 5 | Bits 0+2 | MediaPlayer + RouteGuidance + CallState |
| 7 | All | All engines |

**Live Test Results (Feb 2026 via SSH):**
```
DashboardInfo=1: iAP2MediaPlayerEngine ✓
DashboardInfo=2: (nothing extra - GNSSCapability was 0)
DashboardInfo=2 + GNSSCapability=1: iAP2LocationEngine ✓
DashboardInfo=3 + GNSSCapability=1: MediaPlayer + Location ✓
DashboardInfo=4: iAP2RouteGuidanceEngine ✓
DashboardInfo=7 + GNSSCapability=1: All three engines ✓
```

**Engine Datastore:** `/etc/RiddleBoxData/AIEIPIEREngines.datastore`
- Binary plist caching enabled engines
- Created on first iAP2 connection after boot
- Must be deleted (`rm -f`) for DashboardInfo changes to take effect

**Binary Evidence (ARMiPhoneIAP2_unpacked):**
```asm
; At 0x15f50: Load DashboardInfo config value
ldr r0, str.DashboardInfo      ; Load "DashboardInfo" key
blx fcn.0006a43c               ; Read config value
mov r7, r0                     ; r7 = DashboardInfo value

; At 0x15f78-0x15f98: Test each bit and call corresponding engine init
tst r7, #1                     ; Test bit 0 (MediaPlayer)
bne -> bl 0x282b8              ; If set, initialize MediaPlayerEngine
tst r7, #2                     ; Test bit 1 (Location)
bne -> bl 0x2aa6c              ; If set, initialize LocationEngine
tst r7, #4                     ; Test bit 2 (RouteGuidance)
bne -> bl 0x2ebc4              ; If set, initialize RouteGuidanceEngine
```

**Protocol Integration:**
- Used during iAP2 identification (`CiAP2IdentifyEngine`) to configure which data streams the adapter supports
- Controls data flow TO the HUD (cluster display), not from it
- Related D-Bus signal: `HU_GPS_DATA` for GPS data transmission
- Related message type: `0x2A (DashBoard_DATA)` for dashboard data packets

**Related Settings:**
- `GNSSCapability`: **REQUIRED** for LocationEngine (bit 1). Must be ≥ 1.
- `HudGPSSwitch`: Controls GPS data flow to HUD after engine is enabled
- These settings work together during iAP2 capability negotiation

**Recommended Configurations:**
```bash
# NowPlaying metadata only (most common use case)
riddleBoxCfg -s DashboardInfo 1

# NowPlaying + GPS location
riddleBoxCfg -s DashboardInfo 3
riddleBoxCfg -s GNSSCapability 1

# All features (NowPlaying + GPS + Navigation)
riddleBoxCfg -s DashboardInfo 7
riddleBoxCfg -s GNSSCapability 1

# After changing, delete cached engines and reboot:
rm -f /etc/RiddleBoxData/AIEIPIEREngines.datastore
busybox reboot
```

### DashBoard_DATA Message Format (0x2A) - Capture Verified Feb 2026

When DashboardInfo bit 2 is set, the adapter sends navigation data to the host via message type 0x2A (DashBoard_DATA) containing JSON payloads.

**Message Structure:**
```
Offset  Size  Field
0x00    4     Magic (0x55AA55AA)
0x04    4     Payload length (little-endian)
0x08    4     Message type (0x0000002A)
0x0C    4     Type inverse (0xFFFFFFD5)
0x10    4     Subtype (0x000000C8 = 200 for NaviJSON)
0x14    N     JSON payload (null-terminated)
```

**NaviJSON Payload Fields:**

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `NaviStatus` | int | Navigation state: 0=inactive, 1=active, 2=calculating | `1` |
| `NaviTimeToDestination` | int | ETA in seconds | `480` (8 minutes) |
| `NaviDestinationName` | string | Destination name | `"Speedway"` |
| `NaviDistanceToDestination` | int | Total distance in meters | `6124` |
| `NaviAPPName` | string | Navigation app name | `"Apple Maps"` |
| `NaviRemainDistance` | int | Distance to next maneuver (meters) | `26` |
| `NaviRoadName` | string | Current/next road name | `"Farrior Dr"` |
| `NaviOrderType` | int | Turn order in route | `1` |
| `NaviManeuverType` | int | Maneuver type code | `11` (start) |

**Example Payloads (from capture):**

Route Status Update:
```json
{"NaviStatus":1,"NaviTimeToDestination":480,"NaviDestinationName":"Speedway","NaviDistanceToDestination":6124,"NaviAPPName":"Apple Maps"}
```

Turn-by-Turn Maneuver:
```json
{"NaviRoadName":"Farrior Dr","NaviRoadName":"Start on Farrior Dr","NaviOrderType":1,"NaviManeuverType":11}
```

Distance Update:
```json
{"NaviRemainDistance":26}
```

**Data Flow (Capture Verified):**
```
iPhone (Apple Maps)
    │
    ├─► iAP2 StartRouteGuidanceUpdate (0x5200) ◄── Adapter requests TBT data
    │
    ├─► iAP2 RouteGuidanceUpdate (0x5201) ──────► Route status updates
    │
    └─► iAP2 RouteGuidanceManeuverUpdate (0x5202) ► Turn-by-turn instructions
            │
            ▼
    Adapter (iAP2RouteGuidanceEngine)
            │
            ├─► _SendNaviJSON() ─── Converts iAP2 to JSON
            │
            ▼
    USB Message Type 0x2A (DashBoard_DATA)
            │
            ▼
    Host Application
```

**Adapter Log Evidence:**
```
[iAP2Engine] Enable iAP2 iAP2RouteGuidanceEngine Capability
[iAP2Engine] Send_changes:StartRouteGuidanceUpdate(0x5200), msgLen: 18
[CiAP2Session_CarPlay] Message from iPhone: 0x5201 RouteGuidanceUpdate
[CiAP2Session_CarPlay] Message from iPhone: 0x5202 RouteGuidanceManeuverUpdate
[iAP2RouteGuidanceEngine] use ManeuverDescription as roadName: Start on Farrior Dr
[iAP2RouteGuidanceEngine] _SendNaviJSON:
```

**Requirements for Navigation Data:**
1. Set `DashboardInfo` with bit 2 enabled (value 4, 5, 6, or 7)
2. iPhone must have active navigation in Apple Maps (or compatible app)
3. Adapter must successfully complete iAP2 identification with RouteGuidance capability
4. Host receives 0x2A messages with JSON navigation payloads

### GPS Data Format

The adapter accepts standard **NMEA 0183** sentences via `/tmp/gnss_info` file:

| Sentence | iAP2 Component | Description |
|----------|----------------|-------------|
| `$GPGGA` | `globalPositionSystemFixData` | Position fix data |
| `$GPRMC` | `recommendedMinimumSpecificGPSTransitData` | Minimum GPS data |
| `$GPGSV` | `gpsSatellitesInView` | Satellite information |
| `$PASCD` | (proprietary) | Vehicle data |

**Additional Vehicle Data:**
- `VehicleSpeedData` - Speed from vehicle CAN
- `VehicleHeadingData` - Compass heading
- `VehicleGyroData` - Gyroscope readings
- `VehicleAccelerometerData` - Accelerometer readings

**Commands (Type 0x08):**
- Command 18 (0x12): `StartGNSSReport` - Begin GPS forwarding
- Command 19 (0x13): `StopGNSSReport` - Stop GPS forwarding

---

## Charge Mode (Binary Verified Jan 2026)

USB charging speed is controlled via GPIO pins, not a riddle.conf key.

### Charge Mode File
**Path:** `/tmp/charge_mode` or `/etc/charge_mode`

| Value | GPIO6 | GPIO7 | Mode | Log Message |
|-------|-------|-------|------|-------------|
| 0 | 1 | 1 | SLOW | `CHARGE_MODE_SLOW!!!!!!!!!!!!!!!!!` |
| 1 | 1 | 0 | FAST | `CHARGE_MODE_FAST!!!!!!!!!!!!!!!!!` |

**GPIO Initialization (from init_gpio.sh):**
```bash
echo 6 > /sys/class/gpio/export
echo out > /sys/class/gpio/gpio6/direction
echo 7 > /sys/class/gpio/export
echo out > /sys/class/gpio/gpio7/direction
echo 1 >/sys/class/gpio/gpio6/value   # Enable charging
echo 1 >/sys/class/gpio/gpio7/value   # Slow mode (default)
```

### OnlyCharge Work Mode

"OnlyCharge" is an iPhone work mode (not a config key) indicating the phone is connected for charging only without projection:

| Work Mode | Description |
|-----------|-------------|
| AirPlay | Audio/video mirroring |
| CarPlay | CarPlay projection |
| iOSMirror | Screen mirroring |
| OnlyCharge | Charging only, no projection |

---

## Navigation Video Parameters (iOS 13+)

### AdvancedFeatures
**Type:** Toggle (0/1) | **Default:** 0

**Purpose:** Enables navigation video (CarPlay Dashboard) feature with one-time activation behavior.

| Value | Effect |
|-------|--------|
| 0 (default) | Navigation video disabled (unless previously activated) |
| 1 | Navigation video enabled, feature unlocked permanently |

**How to Set:**
```bash
/usr/sbin/riddleBoxCfg -s AdvancedFeatures 1
/usr/sbin/riddleBoxCfg --upConfig
```

**When Set to 1:**
1. Adapter advertises `"supportFeatures":"naviScreen"` in boxInfo JSON
2. Adapter processes `naviScreenInfo` from incoming BoxSettings
3. Navigation video (Type 0x2C) becomes available

---

### Navigation Video Activation (Binary Verified Feb 2026)

The firmware has **two independent paths** to activate navigation video. Either path can work independently:

**Path A: naviScreenInfo in BoxSettings (BYPASSES AdvancedFeatures check)**

If the host sends `naviScreenInfo` in BoxSettings JSON:
```json
{
  "naviScreenInfo": {
    "width": 480,
    "height": 240,
    "fps": 30
  }
}
```

The firmware:
1. Parses naviScreenInfo at 0x16e5c
2. **Immediately branches** to HU_SCREEN_INFO path (0x170d6)
3. Sends D-Bus signal `HU_SCREEN_INFO` with resolution data
4. Navigation video becomes available via Type 0x2C (AltVideoFrame)

**Binary Evidence (ARMadb-driver_2025.10_unpacked):**
```asm
0x16e5c  blx fcn.00015228              ; Parse JSON for "naviScreenInfo"
0x16e62  cmp r0, 0                     ; Check if key found
0x16e64  bne.w 0x170d6                 ; If FOUND → HU_SCREEN_INFO path (BYPASS)
0x16e68  ldr r0, "AdvancedFeatures"    ; Only reached if naviScreenInfo NOT found
0x16e6c  bl fcn.00066d3c               ; Read config value
0x16e70  cmp r0, 0                     ; Check AdvancedFeatures
0x16e72  bne 0x16f20                   ; If ≠ 0 → HU_NAVISCREEN_INFO path
0x16e7c  add r2, pc, "Not support NaviScreenInfo, return\n"
```

**Path B: AdvancedFeatures=1 (Fallback for legacy config)**

If `naviScreenInfo` is NOT provided in BoxSettings:
1. Firmware checks `AdvancedFeatures` config at 0x16e70
2. If AdvancedFeatures=1 → sends HU_NAVISCREEN_INFO D-Bus signal
3. Uses legacy naviScreenWidth/naviScreenHeight/naviScreenFPS from riddle.conf
4. Navigation video becomes available

**Activation Matrix:**

| naviScreenInfo in BoxSettings | AdvancedFeatures | Result |
|------------------------------|------------------|--------|
| Yes (any resolution) | 0 | ✅ **WORKS** (HU_SCREEN_INFO path) |
| Yes (any resolution) | 1 | ✅ Works (same HU_SCREEN_INFO path) |
| No | 1 | ✅ Works (HU_NAVISCREEN_INFO path) |
| No | 0 | ❌ Rejected ("Not support NaviScreenInfo") |

**Key Insight:** The `bne.w 0x170d6` at 0x16e64 is the critical branch that **bypasses** the AdvancedFeatures check entirely when naviScreenInfo is present.

---

### AdvancedFeatures One-Time Activation (Legacy Behavior)

When using AdvancedFeatures (Path B), there is a one-time activation quirk:

| Scenario | Navigation Video | Notes |
|----------|------------------|-------|
| Fresh adapter, AdvancedFeatures=0 (never set to 1) | **NOT working** | Feature locked (if no naviScreenInfo sent) |
| Set AdvancedFeatures=1, connect phone | **Working** | Feature activated |
| Set back to AdvancedFeatures=0 | **STILL working** | Feature remains unlocked |

This suggests a persistent unlock flag is set on first activation.

**Note:** This quirk does NOT apply when using Path A (naviScreenInfo in BoxSettings) - that path works regardless of AdvancedFeatures history.

### naviScreenWidth
**Type:** Number (0-4096) | **Default:** 480

Navigation screen width in pixels.

### naviScreenHeight
**Type:** Number (0-4096) | **Default:** 272

Navigation screen height in pixels.

### naviScreenFPS
**Type:** Number (10-60) | **Default:** 30

Navigation video frame rate.

**Tested Range:**
- **Maximum:** 60 FPS (hardware limit)
- **Minimum usable:** 10 FPS (below this, UI refresh is noticeably degraded)
- **Recommended:** 24-60 FPS for acceptable user experience

**Note:** While the adapter accepts values down to 10 FPS, testing showed UI responsiveness becomes unacceptable below this threshold. Target 24-60 FPS for production use.

### naviScreenInfo BoxSettings Configuration

Host applications configure navigation video via BoxSettings JSON:
```json
{
  "naviScreenInfo": {
    "width": 480,
    "height": 272,
    "fps": 30  // Range: 10-60, Recommended: 24-60
  }
}
```

**Requirements (when using naviScreenInfo Path A):**
1. Host must send `naviScreenInfo` in BoxSettings (0x19) JSON
2. Host must implement Command 508 handshake
3. Host must handle NaviVideoData (Type 0x2C) messages

**Note:** When `naviScreenInfo` is present in BoxSettings, it **bypasses** the AdvancedFeatures check entirely. The firmware branches directly to the HU_SCREEN_INFO path. See "Navigation Video Activation" section above for the binary-verified control flow.

---

## BoxSettings JSON Mapping (Binary Verified Jan 2026)

**⚠️ SECURITY WARNING:** The `wifiName`, `btName`, and `oemIconLabel` fields are vulnerable to **command injection**. See `03_Security_Analysis/vulnerabilities.md`.

### Host to Adapter Fields - Complete List

**Core Configuration:**

| JSON Field | riddle.conf | Type | Description |
|------------|-------------|------|-------------|
| `mediaDelay` | `MediaLatency` | int | Audio buffer (ms) |
| `syncTime` | - | int | Unix timestamp |
| `autoConn` | `NeedAutoConnect` | bool | Auto-reconnect flag |
| `autoPlay` | `AutoPlay` | bool | Auto-start playback |
| `autoDisplay` | - | bool | Auto display mode |
| `bgMode` | `BackgroundMode` | int | Background mode |
| `startDelay` | `BoxConfig_DelayStart` | int | Startup delay (sec) |
| `syncMode` | - | int | Sync mode |
| `lang` | - | string | Language code |

**Display / Video:**

| JSON Field | riddle.conf | Type | Description |
|------------|-------------|------|-------------|
| `androidAutoSizeW` | `AndroidAutoWidth` | int | Android Auto width |
| `androidAutoSizeH` | `AndroidAutoHeight` | int | Android Auto height |
| `screenPhysicalW` | - | int | Physical screen width (mm) |
| `screenPhysicalH` | - | int | Physical screen height (mm) |
| `drivePosition` | `CarDrivePosition` | int | 0=LHD, 1=RHD |

**Audio:**

| JSON Field | riddle.conf | Type | Description |
|------------|-------------|------|-------------|
| `mediaSound` | `MediaQuality` | int | 0=44.1kHz, 1=48kHz |
| `mediaVol` | - | float | Media volume (0.0-1.0) |
| `navVol` | - | float | Navigation volume |
| `callVol` | - | float | Call volume |
| `ringVol` | - | float | Ring volume |
| `speechVol` | - | float | Speech/Siri volume |
| `otherVol` | - | float | Other audio volume |
| `echoDelay` | `EchoLatency` | int | Echo cancellation (ms) |
| `callQuality` | `CallQuality` | int | Voice call quality |

**Network / Connectivity:**

| JSON Field | riddle.conf | Type | Description |
|------------|-------------|------|-------------|
| `wifiName` | `CustomWifiName` | string | WiFi SSID ⚠️ **CMD INJECTION** |
| `wifiFormat` | - | int | WiFi format |
| `WiFiChannel` | `WiFiChannel` | int | WiFi channel (1-11, 36-165) |
| `btName` | `CustomBluetoothName` | string | Bluetooth name ⚠️ **CMD INJECTION** |
| `btFormat` | - | int | Bluetooth format |
| `boxName` | `CustomBoxName` | string | Device display name |
| `iAP2TransMode` | `iAP2TransMode` | int | iAP2 transport mode |

**Branding / OEM:**

| JSON Field | riddle.conf | Type | Description |
|------------|-------------|------|-------------|
| `oemName` | - | string | OEM name |
| `productType` | - | string | Product type (e.g., "A15W") |
| `lightType` | - | int | LED indicator type |

**Navigation Video (requires AdvancedFeatures=1):**

| JSON Field | Type | Description |
|------------|------|-------------|
| `naviScreenInfo` | object | Nested object for nav video |
| `naviScreenInfo.width` | int | Nav screen width (default: 480) |
| `naviScreenInfo.height` | int | Nav screen height (default: 272) |
| `naviScreenInfo.fps` | int | Nav screen FPS (default: 30) |

**Android Auto Mode:**

| JSON Field | Type | Description |
|------------|------|-------------|
| `androidWorkMode` | int | Enable Android Auto daemon (0/1) |

### Adapter to Host Fields

| JSON Field | Type | Description |
|------------|------|-------------|
| `uuid` | string | Device UUID |
| `MFD` | string | Manufacturing date |
| `boxType` | string | Model code (e.g., "YA") |
| `productType` | string | Product ID (e.g., "A15W") |
| `OemName` | string | OEM name |
| `hwVersion` | string | Hardware version |
| `HiCar` | int | HiCar support flag (0/1) |
| `WiFiChannel` | int | Current WiFi channel |
| `CusCode` | string | Customer code |
| `DevList` | array | Paired device list |
| `ChannelList` | string | Available WiFi channels |

### Phone Info Fields (Adapter → Host)

| JSON Field | Type | Description |
|------------|------|-------------|
| `MDLinkType` | string | "CarPlay", "AndroidAuto", "HiCar" |
| `MDModel` | string | Phone model |
| `MDOSVersion` | string | OS version (empty for Android Auto) |
| `MDLinkVersion` | string | Protocol version |
| `btMacAddr` | string | Bluetooth MAC address |
| `btName` | string | Phone Bluetooth name |
| `cpuTemp` | int | Adapter CPU temperature |

---

## AndroidWorkMode Deep Dive

### Critical Discovery (Dec 2025)

`AndroidWorkMode` controls whether the adapter starts the Android Auto daemon. **This is a dynamic toggle, not a persistent setting.**

### Behavior

| Event | AndroidWorkMode Value | Effect |
|-------|----------------------|--------|
| Host sends `android_work_mode=1` | `0 → 1` | `Start Link Deamon: AndroidAuto` |
| Phone disconnects | `1 → 0` (firmware auto-reset) | Android Auto daemon stops |
| Host reconnects | Must re-send `android_work_mode=1` | Daemon restarts |

### How to Set via Host App

**File Path:** `/etc/android_work_mode`
**Protocol:** `SendFile` (type 0x99) with 4-byte payload

```typescript
// Example
new SendBoolean(true, '/etc/android_work_mode')
```

**Firmware Log Evidence:**
```
UPLOAD FILE: /etc/android_work_mode, 4 byte
OnAndroidWorkModeChanged: 0 → 1
Start Link Deamon: AndroidAuto
```

### Impact on Android Auto Pairing

| AndroidWorkMode | Android Auto Daemon | Fresh Pairing |
|-----------------|---------------------|---------------|
| 0 (default) | NOT started | Fails |
| 1 | Started | Works |

**Key Finding:** Open-source projects that don't send `android_work_mode=1` during initialization cannot perform Android Auto pairing, even if Bluetooth pairing succeeds.

### riddle.conf vs Runtime State

- `AndroidWorkMode` in riddle.conf: May show `1` if previously set
- **Runtime state:** Always resets to `0` on phone disconnect
- Host app must send on **every connection**, not just first time

---

## SendHeartBeat Deep Dive

### Binary Analysis (January 2026)

The `SendHeartBeat` configuration key controls the USB heartbeat mechanism critical for firmware stability.

### Binary Locations

| Binary | Offset | String/Symbol | Purpose |
|--------|--------|---------------|---------|
| **riddleBoxCfg_unpacked** | `0x00018f54` | `SendHeartBeat` | Config key in key table |
| **ARMadb-driver_unpacked** | `0x0006e515` | `SendHeartBeat` | Config reader |
| **ARMadb-driver_unpacked** | `0x0005b583` | `HUDComand_A_HeartBeat` | D-Bus signal name |
| **ARMadb-driver_unpacked** | `0x0001053c` | `0x000000AA` | Message dispatch table entry |

### Protocol Details

**USB Message Format (Type 0xAA / 170):**
```
+------------------+------------------+------------------+------------------+
|   Magic (4B)     |   Length (4B)    |   Type (4B)      | Type Check (4B)  |
|   0x55AA55AA     |   0x00000000     |   0x000000AA     |   0xFFFFFF55     |
+------------------+------------------+------------------+------------------+
```

- **Magic**: `0x55AA55AA` (little-endian)
- **Length**: 0 bytes (no payload)
- **Type**: `0xAA` (170 decimal) - HeartBeat
- **Type Check**: `0xAA XOR 0xFFFFFFFF = 0xFFFFFF55`
- **Total message size**: 16 bytes (header only, no payload)

### D-Bus Signal Flow

```
Host sends HeartBeat (0xAA) via USB
         │
         ▼
ARMadb-driver receives at FUN_00018e2c (message dispatcher)
         │
         ▼
Emits D-Bus signal: HUDComand_A_HeartBeat
         │
         ▼
Internal processes receive keepalive notification
```

**D-Bus dispatch assembly (ARMadb-driver at 0x6327a):**
```asm
0x0006327a  ldr r4, [str.HUDComand_A_HeartBeat]  ; Load signal name
0x0006327c  b 0x63362                            ; Jump to signal handler
```

### Firmware Behavior by Value

| SendHeartBeat | Firmware Expects | Missing Heartbeat Effect | Recommended |
|---------------|------------------|--------------------------|-------------|
| **1 (default)** | 0xAA every ~2s | Triggers internal timeout → disconnect | ✅ Yes |
| **0** | Nothing | No timeout, but boot instability | ❌ No |

### Critical Timing Requirement

The heartbeat serves dual purposes:
1. **USB Keepalive**: Prevents USB interface timeout
2. **Boot Stabilization**: Signals firmware that host is ready

**CRITICAL**: Heartbeat must start **BEFORE** initialization messages:

```
CORRECT (stable):                    INCORRECT (11.7s failure):
──────────────────                   ────────────────────────
USB Connect                          USB Connect
    │                                    │
    ▼                                    ▼
Start Heartbeat ◄── First!           Send Init Messages
    │                                    │
    ▼                                    ▼
Send Init Messages                   Start Heartbeat ◄── Too late!
    │                                    │
    ▼                                    ▼
Stable session                       projectionDisconnected @ 11.7s
```

### Key Functions (ARMadb-driver)

| Address | Function | Purpose |
|---------|----------|---------|
| `0x00018088` | Message pre-processor | Validates header, magic bytes |
| `0x00018244` | Decrypt/validate handler | Processes incoming messages |
| `0x00018e2c` | Main message dispatcher | Routes by type (0xAA → heartbeat handler) |
| `0x0006327a` | D-Bus signal dispatch | Emits HUDComand_A_HeartBeat |

### Diagnostic Commands

**Check current setting:**
```bash
riddleBoxCfg SendHeartBeat
# Returns: 0 or 1
```

**Enable heartbeat (recommended):**
```bash
riddleBoxCfg SendHeartBeat 1
```

**Note:** Changes require reboot to take effect. The running ARMadb-driver process caches the value at startup.

---

## Configuration Precedence

| Priority | Source | Persistence | Notes |
|----------|--------|-------------|-------|
| 1 (Highest) | Host App BoxSettings | Until next init | Sent via USB protocol at connection |
| 2 | riddle.conf | Persistent | Written by riddleBoxCfg or Web API |
| 3 | Web API (/server.cgi) | Persistent | Manual configuration changes |
| 4 (Lowest) | riddle_default.conf | Factory | Restored on factory reset |

**Note:** When the host app sends BoxSettings during initialization, it can override values in riddle.conf. This is why the same adapter may behave differently with different host applications.

---

## D-Bus Interface

The firmware uses D-Bus (`org.riddle`) for inter-process communication.

### Key Signals
| Signal | Purpose |
|--------|---------|
| AudioSignal_* | Audio routing control |
| HUDComand_* | Head unit commands |
| **HUDComand_A_HeartBeat** | USB keepalive received (from host 0xAA message) |
| HUDComand_A_ResetUSB | USB controller reset |
| HUDComand_A_UploadFile | File upload complete |
| HUDComand_B_BoxSoftwareVersion | Version query response |
| kRiddleHUDComand_A_Reboot | System reboot |
| Bluetooth_ConnectStart | BT connection initiated |
| StartAutoConnect | Auto-connect triggered |

---

## Scripts Called by Firmware

| Script | Trigger | Purpose |
|--------|---------|---------|
| `/script/start_bluetooth_wifi.sh` | Boot, reconnect | Initialize BT/WiFi |
| `/script/close_bluetooth_wifi.sh` | Disconnect | Stop BT/WiFi services |
| `/script/phone_link_deamon.sh` | Connection events | Manage phone link |
| `/script/start_accessory.sh` | USB connect | Start USB accessory mode |
| `/script/update_box_ota.sh` | OTA update | Apply firmware update |
| `/script/open_log.sh` | Debug mode | Enable logging |

---

## LED Configuration Parameters (8)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| rgbStartUp | hex (24-bit) | 0x800000 | Startup LED color (red) |
| rgbWifiConnected | hex (24-bit) | 0x008000 | WiFi connected color (green) |
| rgbBtConnecting | hex (24-bit) | 0x800000 | Bluetooth connecting color (red) |
| rgbLinkSuccess | hex (24-bit) | 0x008000 | Link success color (green) |
| rgbUpgrading | hex (24-bit) | 0x800000/0x008000 | Firmware upgrade (alternating) |
| rgbUpdateSuccess | hex (24-bit) | 0x800080 | Update success color (purple) |
| rgbFault | hex (24-bit) | 0x000080 | Fault/error color (blue) |
| ledTimingMode | enum (0-2) | 1 | 0=Static, 1=Blink, 2=Gradient |

---

## Configuration Flow

```
1. Boot: ARMadb-driver starts
         |
         v
2. Read /etc/riddle.conf -> global variables (cached)
         |
         v
3. Initialize hardware (USB, WiFi, BT) using cached values
         |
         v
4. Runtime: Most settings already applied
   |
   +-> Changing config via riddleBoxCfg writes to file
       but does NOT update running process
         |
         v
5. Reboot/restart required to apply new values
```

---

---

## riddleBoxCfg CLI Reference

### Access Notes

**Important**: `riddleBoxCfg` is located at `/usr/sbin/riddleBoxCfg` which may not be in PATH for all shells.

| Access Method | PATH includes /usr/sbin | Usage |
|---------------|------------------------|-------|
| Telnet (port 23) | Yes | `riddleBoxCfg --info` |
| SSH (dropbear) | Often No | `/usr/sbin/riddleBoxCfg --info` |

**Fix for SSH**: Add to profile: `echo 'export PATH=$PATH:/usr/sbin' >> /etc/profile`

### Command Reference

```
riddleBoxCfg --help                                    : print usage
riddleBoxCfg --info                                    : get all config parameters with defaults/ranges
riddleBoxCfg --uuid                                    : print box uuid
riddleBoxCfg --readOld                                 : update riddle.conf include oldCfgFileData
riddleBoxCfg --removeOld                               : remove old cfgFileData
riddleBoxCfg --restoreOld                              : restore old cfgFileData (recovery)
riddleBoxCfg --upConfig                                : update riddle.conf on the box
riddleBoxCfg --specialConfig                           : sync riddle_special.conf to riddle_default.conf
riddleBoxCfg -s key value  [--default]                 : set a key's value
riddleBoxCfg -s list listKeyID listKey listvalue       : set a list key's value
riddleBoxCfg -g key  [--default]                       : get a value from key
riddleBoxCfg -g list listKeyID listKey                 : get a list value from key
riddleBoxCfg -d list listkey listvalue                 : delete a list key's value
```

---

## Configuration Setting Mechanisms

Each riddle.conf parameter can be set through different mechanisms. Understanding these helps determine how to modify settings:

### Setting Mechanisms

| Mechanism | Description | When Used |
|-----------|-------------|-----------|
| **Host App (0x19)** | BoxSettings JSON sent via USB message type 0x19 | Host app (pi-carplay, AutoKit) sends config during session |
| **riddleBoxCfg CLI** | Command-line tool on adapter: `riddleBoxCfg -s Key Value` | SSH access, scripts, OEM provisioning |
| **Auto (Connect)** | Firmware sets automatically when device connects | DevList, LastConnectedDevice, LastPhoneSpsPps |
| **Auto (Pair)** | Firmware sets automatically during Bluetooth pairing | DevList entries, PHONE_INFO |
| **Auto (Runtime)** | Firmware manages internally during operation | LastBoxUIType, CarDate |
| **OEM Default** | Set in riddle_default.conf during manufacturing | Brand*, USB*, oemName |

### BoxSettings JSON → riddle.conf Mapping

When host app sends BoxSettings (0x19), these JSON fields map to config keys:

| BoxSettings JSON | riddle.conf Key | Notes |
|------------------|-----------------|-------|
| `mediaDelay` | MediaLatency | Audio buffer (ms) |
| `autoConn` | NeedAutoConnect | Auto-reconnect toggle |
| `autoPlay` | AutoPlauMusic | Auto-start playback |
| `autoDisplay` | autoDisplay | Auto display mode |
| `bgMode` | BackgroundMode | Background mode |
| `startDelay` | BoxConfig_DelayStart | Startup delay |
| `lang` | BoxConfig_UI_Lang | UI language |
| `androidAutoSizeW` | AndroidAutoWidth | AA resolution |
| `androidAutoSizeH` | AndroidAutoHeight | AA resolution |
| `screenPhysicalW` | ScreenPhysicalW | Physical size (mm) |
| `screenPhysicalH` | ScreenPhysicalH | Physical size (mm) |
| `drivePosition` | CarDrivePosition | 0=LHD, 1=RHD |
| `mediaSound` | MediaQuality | 0=44.1kHz, 1=48kHz |
| `echoDelay` | EchoLatency | Echo cancellation |
| `callQuality` | CallQuality | **BUGGY** - translation fails |
| `wifiName` | CustomWifiName | WiFi SSID |
| `WiFiChannel` | WiFiChannel | WiFi channel |
| `btName` | CustomBluetoothName | Bluetooth name |
| `boxName` | CustomBoxName | Display name |
| `iAP2TransMode` | iAP2TransMode | Transport mode |
| `androidWorkMode` | AndroidWorkMode | AA daemon mode |

### Auto-Set Parameters (Firmware Managed)

These are set automatically by firmware - do not set manually:

| Key | Set When | Description |
|-----|----------|-------------|
| DevList | Device pairs via Bluetooth | Adds device entry |
| DeletedDevList | User removes device | Prevents auto-reconnect |
| LastConnectedDevice | Session starts | MAC of current device |
| LastPhoneSpsPps | Video stream starts | Cached H.264 SPS/PPS |
| LastBoxUIType | UI mode changes | CarPlay/AA/HiCar state |
| CarDate | Unknown | Date code |

### Internal Parameters (Not User-Settable via Host App)

These require `riddleBoxCfg` CLI or are firmware-only:

| Key | How to Set | Notes |
|-----|------------|-------|
| DashboardInfo | `riddleBoxCfg -s DashboardInfo 7` | iAP2 engine bitmask |
| GNSSCapability | `riddleBoxCfg -s GNSSCapability 1` | Required for LocationEngine |
| AdvancedFeatures | `riddleBoxCfg -s AdvancedFeatures 1` | Hidden features toggle |
| SpsPpsMode | `riddleBoxCfg -s SpsPpsMode 1` | H.264 handling mode |
| SendHeartBeat | `riddleBoxCfg -s SendHeartBeat 1` | Heartbeat toggle |
| LogMode | `riddleBoxCfg -s LogMode 1` | Logging toggle |

---

## Authoritative Parameter List (from --info)

Output from `/usr/sbin/riddleBoxCfg --info` on CPC200-CCPA firmware. This is the **definitive source** for all configuration parameters.

**Source Column Legend:**
- **Web UI** = Set via Host App BoxSettings (0x19) JSON
- **Protocol Init** = Set via Host App during session initialization
- **Internal** = Firmware-managed or riddleBoxCfg CLI only

### Integer Parameters

| Key | Default | Min | Max | Source |
|-----|---------|-----|-----|--------|
| iAP2TransMode | 0 | 0 | 1 | Web UI |
| MediaQuality | 1 | 0 | 1 | Web UI |
| MediaLatency | 1000 | 300 | 2000 | Web UI |
| UdiskMode | 1 | 0 | 1 | Web UI |
| LogMode | 1 | 0 | 1 | Internal |
| BoxConfig_UI_Lang | 0 | 0 | 65535 | Web UI |
| BoxConfig_DelayStart | 0 | 0 | 120 | Web UI |
| BoxConfig_preferSPSPPSType | 0 | 0 | 1 | Protocol Init |
| NotCarPlayH264DecreaseMode | 0 | 0 | 2 | Internal |
| NeedKeyFrame | 0 | 0 | 1 | Protocol Init |
| EchoLatency | 320 | 20 | 2000 | Web UI |
| DisplaySize | 0 | 0 | 3 | Web UI |
| UseBTPhone | 0 | 0 | 1 | Internal |
| MicGainSwitch | 0 | 0 | 1 | Web UI |
| CustomFrameRate | 0 | 0 | 60 | Protocol Init |
| NeedAutoConnect | 1 | 0 | 1 | Web UI |
| BackgroundMode | 0 | 0 | 1 | Web UI |
| HudGPSSwitch | 1 | 0 | 1 | Web UI |
| CarDate | 0 | 0 | 65535 | Internal |
| WiFiChannel | 36 | 1 | 165 | Web UI |
| AutoPlauMusic | 0 | 0 | 1 | Web UI |
| MouseMode | 1 | 0 | 1 | Web UI |
| CustomCarLogo | 0 | 0 | 1 | Web UI |
| VideoBitRate | 0 | 0 | 20 | Protocol Init |
| VideoResolutionHeight | 0 | 0 | 4096 | Protocol Init |
| VideoResolutionWidth | 0 | 0 | 4096 | Protocol Init |
| UDiskPassThrough | 1 | 0 | 1 | Web UI |
| AndroidWorkMode | 1 | 1 | 5 | Protocol Init |
| CarDrivePosition | 0 | 0 | 1 | Web UI |
| AndroidAutoWidth | 0 | 0 | 4096 | Protocol Init |
| AndroidAutoHeight | 0 | 0 | 4096 | Protocol Init |
| ScreenDPI | 0 | 0 | 480 | Protocol Init |
| KnobMode | 0 | 0 | 1 | Web UI |
| NaviAudio | 0 | 0 | 2 | Web UI |
| ScreenPhysicalW | 0 | 0 | 1000 | Protocol Init |
| ScreenPhysicalH | 0 | 0 | 1000 | Protocol Init |
| CallQuality | 1 | 0 | 2 | Web UI | **BUGGY** - see below |
| VoiceQuality | 1 | 0 | 2 | Internal | **BUGGY** - see below |
| AutoUpdate | 1 | 0 | 1 | Web UI |
| LastBoxUIType | 1 | 0 | 2 | Internal |
| BoxSupportArea | 0 | 0 | 1 | Internal |
| HNPInterval | 10 | 0 | 1000 | Internal |
| lightType | 3 | 1 | 3 | Web UI |
| MicType | 0 | 0 | 2 | Web UI |
| RepeatKeyframe | 0 | 0 | 1 | Protocol Init |
| BtAudio | 0 | 0 | 1 | Web UI |
| MicMode | 0 | 0 | 4 | Internal |
| SpsPpsMode | 0 | 0 | 3 | Protocol Init |
| MediaPacketLen | 200 | 200 | 20000 | Internal |
| TtsPacketLen | 200 | 200 | 40000 | Internal |
| VrPacketLen | 200 | 200 | 40000 | Internal |
| TtsVolumGain | 0 | 0 | 1 | Internal |
| VrVolumGain | 0 | 0 | 1 | Internal |
| CarLinkType | 30 | 1 | 30 | Internal |
| SendHeartBeat | 1 | 0 | 1 | Internal |
| SendEmptyFrame | 1 | 0 | 1 | Internal |
| autoDisplay | 1 | 0 | 2 | Web UI |
| USBConnectedMode | 0 | 0 | 2 | Web UI |
| USBTransMode | 0 | 0 | 1 | Web UI |
| ReturnMode | 0 | 0 | 1 | Web UI |
| LogoType | 0 | 0 | 3 | Web UI |
| BackRecording | 0 | 0 | 1 | Internal |
| FastConnect | 0 | 0 | 1 | Protocol Init |
| WiredConnect | 1 | 0 | 1 | Internal |
| ImprovedFluency | 0 | 0 | 1 | Web UI |
| NaviVolume | 0 | 0 | 100 | Web UI |
| OriginalResolution | 0 | 0 | 1 | Protocol Init |
| AutoConnectInterval | 0 | 0 | 60 | Internal |
| AutoResetUSB | 1 | 0 | 1 | Internal |
| HiCarConnectMode | 0 | 0 | 1 | Internal |
| GNSSCapability | 0 | 0 | 65535 | Internal |
| DashboardInfo | 1 | 0 | 7 | Internal |
| AudioMultiBusMode | 1 | 0 | 1 | Internal |

### String Parameters

| Key | Default | Min Len | Max Len | Source |
|-----|---------|---------|---------|--------|
| CarBrand | "" | 0 | 31 | Internal |
| CarModel | "" | 0 | 31 | Internal |
| BluetoothName | "" | 0 | 15 | Web UI |
| WifiName | "" | 0 | 15 | Web UI |
| CustomBluetoothName | "" | 0 | 15 | Web UI |
| CustomWifiName | "" | 0 | 15 | Web UI |
| HU_BT_PIN_CODE | "" | 0 | 6 | Internal - BT pairing PIN (see note) |
| LastPhoneSpsPps | "" | 0 | 511 | Internal |
| CustomId | "" | 0 | 31 | Internal |
| LastConnectedDevice | "" | 0 | 17 | Internal |
| IgnoreUpdateVersion | "" | 0 | 15 | Internal |
| CustomBoxName | "" | 0 | 15 | Web UI |
| WifiPassword | "12345678" | 0 | 15 | Web UI |
| BrandName | "" | 0 | 15 | Internal |
| BrandBluetoothName | "" | 0 | 15 | Internal |
| BrandWifiName | "" | 0 | 15 | Internal |
| BrandServiceURL | "" | 0 | 31 | Internal |
| BoxIp | "" | 0 | 15 | Web UI |
| USBProduct | "" | 0 | 63 | Web UI |
| USBManufacturer | "" | 0 | 63 | Web UI |
| USBPID | "" | 0 | 4 | Web UI |
| USBVID | "" | 0 | 4 | Web UI |
| USBSerial | "" | 0 | 63 | Internal |
| oemName | "" | 0 | 63 | Internal |

### Array/Object Parameters

| Key | Type | Description |
|-----|------|-------------|
| DevList | Array | Paired devices list |
| DeletedDevList | Array | Removed/unpaired devices list |

#### DevList Structure

Stores all paired Bluetooth devices. Each entry is an object with the following fields:

```json
{
  "DevList": [
    {
      "id": "64:31:35:8C:29:69",
      "type": "CarPlay",
      "name": "Luis",
      "index": "1",
      "time": "2026-02-03 17:51:25",
      "rfcomm": "1"
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Bluetooth MAC address (XX:XX:XX:XX:XX:XX format) |
| `type` | string | Connection type: `"CarPlay"`, `"AndroidAuto"`, `"HiCar"` |
| `name` | string | Device friendly name (from phone) |
| `index` | string | Device index in list (starts at "1") |
| `time` | string | Last connection timestamp (YYYY-MM-DD HH:MM:SS) |
| `rfcomm` | string | RFCOMM channel number |

**Behavior:**
- Populated automatically when devices pair via Bluetooth
- Used for auto-reconnect feature (`NeedAutoConnect=1`)
- `LastConnectedDevice` references an `id` from this list
- Maximum entries: ~10 devices (older entries may be pruned)
- Survives factory reset if Bluetooth pairing data persists

#### DeletedDevList Structure

Stores devices that were explicitly unpaired/removed by user:

```json
{
  "DeletedDevList": [
    {
      "id": "AA:BB:CC:DD:EE:FF",
      "type": "CarPlay"
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Bluetooth MAC address of removed device |
| `type` | string | Connection type that was removed |

**Behavior:**
- Prevents auto-reconnect to intentionally removed devices
- Device must be re-paired manually to reconnect
- Cleared on factory reset

---

## Known Configuration Bugs

### CallQuality → VoiceQuality Translation Bug (Firmware 2025.10.XX)

**Status:** CONFIRMED (Jan 2026)
**Severity:** Medium - Setting has no effect

The `CallQuality` Web UI setting (0=Normal, 1=Clear, 2=HD) fails to translate to the internal `VoiceQuality` parameter.

**Error observed in TTY logs:**
```
[D] CMD_BOX_INFO: {...,"callQuality":1,...}
[E] apk callQuality value transf box value error , please check!
```

**Technical Details:**
- Host app sends `callQuality` in BoxSettings JSON
- Firmware's `ConfigFileUtils` attempts to map it to `VoiceQuality`
- Translation fails with error, VoiceQuality remains unchanged
- Error occurs regardless of CallQuality value (0, 1, or 2)

**Impact:**
- CallQuality setting via Web UI has no effect
- Voice/telephony audio sample rate is NOT controllable via this setting
- CarPlay independently negotiates sample rate (always 16kHz on modern iPhones)

**Testing Performed:**
- Cycled CallQuality 0→1→2 via Web UI
- Captured USB audio packets during phone calls
- All captures showed decode_type=5 (16kHz), never decode_type=3 (8kHz)
- TTY logs confirmed error on every CallQuality change

**Workaround:** None. Sample rate is determined by CarPlay's `audioFormat` during stream setup.

---

## Parameter Source Classification

Parameters come from different sources and have different behaviors:

### Web UI Parameters
Set via web interface at `http://192.168.43.1` (or `http://192.168.50.2`). User-facing settings that persist in riddle.conf.

**Examples**: WiFiChannel, MediaLatency, MediaQuality, MicType, MouseMode, BackgroundMode, CustomWifiName

### Protocol Init Parameters
Set by host applications (carlink_native, pi-carplay, AutoKit) during USB protocol initialization. These are sent as BoxSettings and may override riddle.conf values.

**Examples**: SpsPpsMode, RepeatKeyframe, VideoBitRate, VideoResolutionWidth/Height, FastConnect, NeedKeyFrame, AndroidWorkMode, CustomFrameRate, ScreenDPI

**Note**: These parameters are dynamically set each time a host app connects. The adapter may behave differently with different host applications.

### Internal Parameters
System-level settings not exposed in standard UI. Used for protocol internals, debugging, or OEM configuration.

**Examples**: SendHeartBeat, SendEmptyFrame, MediaPacketLen, TtsPacketLen, HNPInterval, LogMode, CarLinkType, LastPhoneSpsPps, AutoResetUSB

---

## Web API Configuration

### Endpoint
```
POST /server.cgi
FormData: cmd=set&item=parameter&val=value&ts=timestamp&sign=md5_hash
Salt: HweL*@M@JEYUnvPw9G36MVB9X6u@2qxK
```

### Method Comparison

| Method | Endpoint/Binary | Format | Latency | Restart Required |
|--------|----------------|--------|---------|------------------|
| USB | CPC200-CCPA protocol | Binary packets | <100ms | Video/USB changes only |
| Web API | /server.cgi | POST FormData+MD5 | Real-time | Resolution/USB identity |
| CLI | /usr/sbin/riddleBoxCfg | -s param value | Immediate | System settings |

---

## Validation Rules

| Type | Rule | Error Message |
|------|------|---------------|
| Range 0-60 | micGain | "Please Enter A Number From 0-60" |
| Range 300-2000 | mediaDelay | "Please Enter A Number From 300-2000" |
| Range 0-4096 | resolution | "Please Enter A Number From 0-4096" |
| Range 0-20 | bitRate | "Please Enter A Number From 0-20" |
| Range 0-480 | ScreenDPI | "Please Enter A Number From 0-480" |
| Text | alphanumeric | "No Special Symbols Are Allowed" |
| Text | no emoji | "Emoticons Cannot Be Used" |

---

## Model Detection & Parameter Filtering

### Product Identification

| File | Purpose |
|------|---------|
| `/etc/box_product_type` | Model identifier (A15W, A15H, etc.) |
| `/etc/box_version` | Custom version affecting module loading |
| `/script/getFuncModule.sh` | Module loading logic based on product type |
| `/script/init_audio_codec.sh` | Audio codec detection (WM8960/AC6966) |

### Module Loading by Model

```
A15W: CarPlay,AndroidAuto,AndroidMirror,iOSMirror,HiCar
A15H: CarPlay,HiCar (limited subset)
```

### Model-Specific Parameter Visibility

**IMPORTANT**: Parameters are NOT "hidden" by firmware - they are **contextually filtered** based on hardware capabilities.

- **Backend Reality**: All 91 parameters are accessible via API/CLI regardless of model
- **Frontend Filtering**: Web interface conditionally displays parameters based on hardware capabilities

**A15W Contextual Limitations**:

| Parameter | Status | Reason |
|-----------|--------|--------|
| micGain, MicMode, micType | Limited | Hardware-dependent microphone |
| KnobMode | Disabled | No physical knob hardware |
| btCall | Limited | Bluetooth calling hardware constraints |
| audioCodec | Hardware-detected | WM8960(0) or AC6966(1) via I2C |

---

## Configuration Recovery

If configuration changes cause issues (e.g., CarPlay stops working):

```bash
# Restore previous configuration
/usr/sbin/riddleBoxCfg --restoreOld

# Or restore factory defaults
cp /etc/riddle_default.conf /etc/riddle.conf
/usr/sbin/riddleBoxCfg --upConfig
```

The `--restoreOld` command restores the previous configuration state, useful when experimental changes break functionality.

---

## Host App vs Direct Access Configuration (Binary Verified Feb 2026)

This section documents which configuration keys can be set via USB protocol messages from a host application versus those requiring direct adapter access (terminal/SSH, web interface, or riddleCfg CLI).

**Analysis Source:** Binary analysis of `riddleBoxCfg_unpacked` and `ARMadb-driver_2025.10_unpacked`

### Summary

| Access Method | Key Count | Percentage |
|---------------|-----------|------------|
| Host App (USB Protocol) | ~19 keys | 18% |
| Direct Access Only | ~68 keys | 64% |
| Read-Only | ~19 keys | 18% |

---

### Host App Configurable Keys (USB Protocol)

#### Via Open Message (0x01) - Session Parameters

| Key | Protocol Field | Effect |
|-----|----------------|--------|
| VideoResolutionWidth | width (bytes 0-3) | Video encoder width |
| VideoResolutionHeight | height (bytes 4-7) | Video encoder height |
| CustomFrameRate | fps (bytes 8-11) | Video framerate |
| ScreenDPI | Sent via SendFile (0x99) | UI scaling |
| DisplaySize | Derived from resolution | UI density |

#### Via BoxSettings Message (0x19) - JSON Configuration

| Key | JSON Field | Effect |
|-----|------------|--------|
| mediaDelay | "mediaDelay" | Audio delay compensation |
| MediaQuality | "mediaSound" | 0=44.1kHz, 1=48kHz |
| CallQuality | "callQuality" | 0=normal, 1=clear, 2=HD |
| WiFiChannel | "WiFiChannel" / "wifiChannel" | WiFi AP channel |
| CustomWifiName | "wifiName" | WiFi SSID |
| CustomBluetoothName | "btName" | Bluetooth name |
| CustomBoxName | "boxName" / "OemName" | Device name |
| NeedAutoConnect | "autoConn" | Auto-connect enable |
| AutoPlauMusic | "autoPlay" | Auto-play on connect |
| AndroidAutoWidth | "androidAutoSizeW" | AA video width |
| AndroidAutoHeight | "androidAutoSizeH" | AA video height |

#### Via Command Message (0x08) - Runtime Control

| Key Affected | Command ID | Effect |
|--------------|------------|--------|
| MicType | 7 (UseCarMic), 8 (UseBoxMic), 15 (I2S), 21 (Phone) | Mic source |
| DayNightMode | 16 (StartNightMode), 17 (StopNightMode) | Theme |
| BtAudio | 22 (UseBluetoothAudio), 23 (UseBoxTransAudio) | Audio routing |
| WiFiChannel | 24 (Use24GWiFi), 25 (Use5GWiFi) | WiFi band (not specific channel) |
| GNSSCapability | 18 (StartGNSSReport), 19 (StopGNSSReport) | GPS forwarding |
| NeedKeyFrame | 12 (RequestKeyFrame) | Video IDR request |
| NeedAutoConnect | 1001 (SupportAutoConnect), 1002 (StartAutoConnect) | Auto-connect |

#### Via SendFile Message (0x99) - File Writes

| Key | File Path | Effect |
|-----|-----------|--------|
| ScreenDPI | /tmp/screen_dpi | DPI value |
| DayNightMode | /tmp/night_mode | Theme state (0/1) |
| CarDrivePosition | /tmp/hand_drive_mode | LHD/RHD |
| ChargeMode | /tmp/charge_mode | Charging behavior |
| CustomBoxName | /etc/box_name | Device name |
| AirPlay config | /etc/airplay.conf | AirPlay settings |
| AndroidWorkMode | /etc/android_work_mode | AA daemon enable |
| CustomCarLogo | /etc/icon_*.png | Logo images |

---

### Direct Access Only Keys (Terminal/Web/riddleCfg)

#### USB Hardware Configuration

| Key | Why Direct Access Required |
|-----|---------------------------|
| USBVID | Changes USB device identity - requires gadget driver reconfigure |
| USBPID | Changes USB device identity - requires gadget driver reconfigure |
| USBProduct | USB descriptor - set at driver init |
| USBManufacturer | USB descriptor - set at driver init |
| USBSerial | USB descriptor - set at driver init |
| USBConnectedMode | Low-level USB mode |
| USBTransMode | Transfer mode (bulk/isoch) |
| AutoResetUSB | USB error recovery behavior |

#### Video Processing (Internal Encoder Settings)

| Key | Why Direct Access Required |
|-----|---------------------------|
| VideoBitRate | Encoder parameter - not in protocol |
| BoxConfig_preferSPSPPSType | Codec config |
| SpsPpsMode | Codec config |
| LastPhoneSpsPps | Cached value |
| RepeatKeyframe | Encoder behavior |
| SendEmptyFrame | Encoder behavior |
| NotCarPlayH264DecreaseMode | Quality reduction policy |
| ImprovedFluency | Buffering behavior |

#### Audio Processing (Internal Mixer Settings)

| Key | Why Direct Access Required |
|-----|---------------------------|
| MediaLatency | Internal buffer size |
| MediaPacketLen | Packet configuration |
| VoiceQuality | Audio processing |
| MicMode | Processing mode |
| MicGainSwitch | Gain control |
| NaviAudio | Channel routing |
| NaviVolume | Volume level |
| TtsPacketLen | TTS config |
| TtsVolumGain | TTS gain |
| VrPacketLen | Voice recognition config |
| VrVolumGain | Voice recognition gain |
| EchoLatency | Echo cancellation |
| DuckPosition | Ducking level |
| AudioMultiBusMode | Multi-channel mode |

#### WiFi/Bluetooth Hardware

| Key | Why Direct Access Required |
|-----|---------------------------|
| WifiPassword | Security - not sent over USB |
| WiFiP2PMode | Network mode change |
| InternetHotspots | Network routing |
| BrandWifiName | OEM config |
| BrandWifiChannel | OEM config |
| BrandBluetoothName | OEM config |
| UseBTPhone | Audio routing config |
| UseUartBLE | Hardware interface |
| HU_BT_PIN_CODE | BT pairing PIN config |

**HU_BT_PIN_CODE Note:**
This key stores the Bluetooth pairing PIN code used by the adapter. Related to two USB message types:
- **Type 0x0C (CMD_SET_BLUETOOTH_PIN_CODE):** Sets this configuration value
- **Type 0x2B (Connection_PINCODE):** Real-time PIN notification during active pairing
See `usb_protocol.md` → "Bluetooth PIN Message Types" for detailed flow.

#### System/Behavior (Feature Gating)

| Key | Why Direct Access Required |
|-----|---------------------------|
| **AdvancedFeatures** | **Critical: Enables nav screen (0x2C) - requires adapter restart** |
| AutoUpdate | Firmware update policy |
| IgnoreUpdateVersion | Update skip |
| BackgroundMode | Background operation |
| BackRecording | DVR recording |
| BoxConfig_DelayStart | Boot timing |
| BoxConfig_UI_Lang | UI language |
| FastConnect | Handshake behavior |
| SendHeartBeat | Heartbeat enable/disable |
| KnobMode | Input mapping |
| MouseMode | Input mode |
| ReturnMode | Button behavior |
| LogMode | Debug level |

#### OEM/Branding (Factory Settings)

| Key | Why Direct Access Required |
|-----|---------------------------|
| BrandName | OEM identity |
| BrandServiceURL | OEM service |
| CustomId | OEM tracking |
| LogoType | Logo selection |
| BoxSupportArea | Regional config |
| ResetBoxCarLogo | Factory reset trigger |
| ResetBoxConfig | Factory reset trigger |

---

### Read-Only Keys (Set by Adapter or Phone)

#### Set by Adapter (Hardware/Firmware Info)

| Key | Source |
|-----|--------|
| RiddlePlatform | Hardware detection |
| uuid | Generated/stored in /etc/uuid |
| hwVersion | /etc/box_version |
| software_version | /etc/software_version |

#### Set by Connected Phone (Session Info)

| Key | Source | When Set |
|-----|--------|----------|
| PHONE_INFO | Phone during iAP2/AA handshake | Session start |
| PHONE_DEVICE_TYPE | Phone identification | Session start |
| PHONE_OS_VERSION | Phone OS version | Session start |
| PHONE_LINK_TYPE | Active link type | Session start |
| BT_CONNECTING_ADDR | Bluetooth MAC | During BT connect |
| LastPhoneSpsPps | Phone's H.264 params | Video negotiation |
| conNum | Connection statistics | Session end |
| conRate | Connection success rate | Session end |
| conSpd | Connection speed | Session end |
| linkT | Link type string | Session end |
| MD_LINK_TIME | Link timestamp | Session end |

#### Set by Host App (Reported to Adapter)

| Key | Source | Protocol |
|-----|--------|----------|
| APP_INFO | Host app info blob | BoxSettings (0x19) |
| HU_TYPE_ID | Host identifier | Open (0x01) |
| HU_TYPE_OS | Host OS type | Open (0x01) |
| HU_OS_VERSION | Host OS version | BoxSettings (0x19) |
| HU_APP_VERSION | Host app version | BoxSettings (0x19) |
| HU_SCREEN_INFO | Host screen info | Open (0x01) |
| HU_LINK_TYPE | Host link type | Session info |

---

### Critical Note: Navigation Video Configuration

There are **two independent paths** to enable navigation video (see "Navigation Video Activation" section for binary-verified details):

**Path A: naviScreenInfo in BoxSettings (Host App Controlled)**
- Host sends `naviScreenInfo: {width, height, fps}` in BoxSettings JSON
- Firmware at 0x16e64 detects naviScreenInfo and **bypasses** the AdvancedFeatures check
- Uses HU_SCREEN_INFO D-Bus path
- ✅ **Works without AdvancedFeatures=1**

**Path B: AdvancedFeatures=1 (Legacy, Direct Access Only)**
- Set via `riddleBoxCfg -s AdvancedFeatures 1` (SSH/terminal only)
- Uses HU_NAVISCREEN_INFO D-Bus path
- Uses naviScreenWidth/naviScreenHeight/naviScreenFPS from riddle.conf
- Required only if host does NOT send naviScreenInfo in BoxSettings

**AdvancedFeatures Effects (when set to 1):**
- Adapter advertises `"supportFeatures": "naviScreen"` in boxInfo
- Enables HU_NAVISCREEN_INFO D-Bus signal (fallback path)
- Enables HU_NEEDNAVI_STREAM requests
- Enables RequestNaviScreenFoucs/ReleaseNaviScreenFoucs commands
- Enables NaviVideoData (0x2C) message handling

**Recommended Flow (Host App - Path A):**
1. Host sends BoxSettings with `naviScreenInfo: {width, height, fps}`
2. Firmware branches to HU_SCREEN_INFO path (bypasses AdvancedFeatures)
3. iPhone and adapter negotiate navigation support
4. NaviVideoData (0x2C) flows from iPhone → Adapter → Host

**Legacy Flow (Path B - requires SSH access):**
1. Set `AdvancedFeatures=1` via SSH/terminal (one-time, direct access)
2. Reboot or restart ARMadb-driver
3. Host does NOT send naviScreenInfo
4. Firmware uses legacy naviScreen* settings from riddle.conf

---

### Host App Configuration Workflow

```
Session Initialization (Host → Adapter):

1. Send Open (0x01)
   - width, height, fps, format, iBoxVersion, phoneWorkMode

2. Send BoxSettings (0x19) JSON
   - mediaDelay, mediaSound, callQuality, WiFiChannel
   - wifiName, btName, boxName, autoConn, autoPlay
   - androidAutoSizeW, androidAutoSizeH, syncTime

3. Send Commands (0x08) as needed
   - MicType: 7/8/15/21
   - NightMode: 16/17
   - WiFiBand: 24/25
   - GNSS: 18/19

4. Send Files (0x99) as needed
   - /tmp/screen_dpi
   - /etc/airplay.conf
   - /etc/android_work_mode
   - /etc/icon_*.png

Runtime Configuration Changes:
- Theme change: Send Command 16 or 17
- Mic change: Send Command 7, 8, 15, or 21
- WiFi band: Send Command 24 or 25
- Sample rate/Call quality: Resend full BoxSettings (0x19)
```

---

## References

- Source: `pi-carplay-4.1.3/firmware_binaries/CONFIG_KEYS_REFERENCE.md`
- Source: `carlink_native/documents/reference/Firmware/firmware_configurables.md`
- Source: Binary analysis of `riddleBoxCfg_unpacked` (49KB, 2025.10 firmware)
- Source: Binary analysis of `ARMadb-driver_2025.10_unpacked` (478KB)
- Firmware strings analyzed using: `strings -t x`, `objdump -d`, `radare2`
