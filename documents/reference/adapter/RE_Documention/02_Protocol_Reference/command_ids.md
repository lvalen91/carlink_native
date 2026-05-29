# CPC200-CCPA Command (0x08) Reference

**Status:** Documented from binary analysis
**Source:** ARMadb-driver_unpacked binary analysis
**Firmware Version:** 2025.10.15.1127 (binary analysis reference version)
**Last Updated:** 2026-02-19 (Added 14 new commands: 400-403, 410-412, 600-601, 700-702; renamed 502/503; updated gap ranges)

> **Context:** Command IDs are 4-byte little-endian payloads carried in Command (0x08) messages over the USB protocol between the Android host app and the CPC200-CCPA adapter firmware. Commands 1-31 are universal (both CarPlay and Android Auto sessions). Commands 100-314 and 400-702 are CarPlay-specific. Commands 500-509 are Android Auto focus commands.

---

## USB Message Types — Pointer Table

Command (0x08) is one of several USB message types in the `0x55AA55AA` bulk protocol. The table below routes each message type to its canonical documentation. **This file (`command_ids.md`) documents only the 0x08 Command payload** — the 4-byte command IDs.

| Type | Name | Documented in |
|------|------|---------------|
| `0x01` | Open | `usb_protocol.md` / `CAPTURE_SESSION.md` §9.1.1 |
| `0x06` | VideoData | `video_protocol.md` |
| `0x07` | AudioData | `audio_protocol.md` |
| `0x08` | Command | **this file** |
| `0x19` | BoxSettings | `usb_protocol.md` / `CAPTURE_SESSION.md` §9.1.2 |
| `0x2C` | NaviVideo (AltVideoFrame) | `video_protocol.md` |
| `0x99` | SendFile | `usb_protocol.md` / `CAPTURE_SESSION.md` §9.1.3 |

---

## Overview

The Command message type (0x08) is **bidirectional** - commands flow in both directions between host and adapter. The adapter acts as a bridge, forwarding many commands between the host application and the connected phone (CarPlay/Android Auto).

### Message Format

```
USB Header (16 bytes):
+------------------+------------------+------------------+------------------+
|   0x55AA55AA     |   0x00000004     |   0x00000008     |   0xFFFFFFF7     |
|   (magic)        |   (length=4)     |   (type=8)       |   (type check)   |
+------------------+------------------+------------------+------------------+

Payload (4 bytes):
+------------------+
|   Command ID     |
|   (4B LE)        |
+------------------+
```

---

## Message Flow Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           MESSAGE FLOW                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   ┌──────────┐         ┌──────────┐         ┌──────────┐                │
│   │   HOST   │ ◄─────► │  ADAPTER │ ◄─────► │  PHONE   │                │
│   │   APP    │   USB   │ CPC200   │  iAP2/  │ CarPlay/ │                │
│   │          │         │          │   AA    │ Android  │                │
│   └──────────┘         └──────────┘         └──────────┘                │
│                                                                          │
│   Direction Codes:                                                       │
│   H→A    = Host to Adapter (handled by adapter)                         │
│   H→A→P  = Host to Adapter, forwarded to Phone                          │
│   P→A→H  = Phone to Adapter, forwarded to Host                          │
│   A→H    = Adapter originates, sends to Host                            │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Firmware Evidence

From binary analysis at `0x2047e`:
```c
BoxLog("Forward CarPlay control cmd!");  // Logged when forwarding to phone
call fcn.000197a8(r6, r5);               // Command name lookup and dispatch
```

---

## Command Direction Reference

This section classifies commands by flow direction. For per-ID details, see the [Command ID Reference Tables](#command-id-reference-tables) below.

### Host → Adapter → Phone (H→A→P) — Forwarded to Phone

These commands are sent by the host and **forwarded to the connected phone** via CarPlay/Android Auto protocol.

**Firmware handler:** `0x2047e` - logs "Forward CarPlay control cmd!" then dispatches

| ID Range | Group | Summary |
|----------|-------|---------|
| 1-2 | Mic Recording | StartRecordMic, StopRecordMic |
| 5-6 | Siri/Assistant | SiriButtonDown/Up — initiates voice assistant from host side |
| 100-106 | CtrlButton_* | D-Pad navigation (Left, Right, Up, Down, Enter, Release, Back) |
| 111-114 | CtrlKnob_* | Rotary knob input (Left, Right, Up, Down) |
| 200-205 | Music* | Media control (Home, Play, Pause, Toggle, Next, Prev) |
| 300-314 | PhoneCall/PhoneKey_* | Call control (Answer, HangUp) + DTMF tones (0-9, *, #) + HookSwitch |
| 400-403 | LaunchApp* | App launch (Maps, Phone, Music, NowPlaying) — CarPlay only |
| 410-412 | UI Control | ShowUI, StopUI, SuggestUI — CarPlay only |
| 700-702 | CusCommand_* | Volume forwarding, HFP call start/stop — CarPlay only |

### Host → Adapter (H→A) — Handled by Adapter Only

These commands are processed by the adapter firmware and **NOT forwarded** to the phone.

| ID Range | Group | Summary |
|----------|-------|---------|
| 7-8, 15, 21 | Mic Routing | UseCarMic, UseBoxMic, UseBoxI2SMic, UsePhoneMic |
| 12, 26 | Video | RequestKeyFrame, RefreshFrame |
| 16-17 | Night Mode | StartNightMode, StopNightMode |
| 18-19 | GNSS | StartGNSSReport, StopGNSSReport |
| 22-23 | Audio Routing | UseBluetoothAudio, UseBoxTransAudio |
| 24-25 | WiFi Band | Use24GWiFi, Use5GWiFi |
| 28-29 | Standby | StartStandbyMode, StopStandbyMode |
| 30-31 | BLE | StartBleAdv, StopBleAdv |
| 600-601 | DVR | DVRCommand_RequestPreview, ScanAndConnect — dead code, no hardware |
| 1000-1002, 1012-1013 | Connection Control | SupportWifi, SupportAutoConnect, StartAutoConnect, WiFiPair, GetBluetoothOnlineList |

### Phone → Adapter → Host (P→A→H) — Forwarded from Phone

**Firmware handler:** `fcn.0001d2fe` at `0x1da50` - receives via D-Bus from iAP handler

| ID | Action | Host Should |
|----|--------|-------------|
| 3 | RequestHostUI | Show native host UI (user tapped car/phone icon in CarPlay) |
| 14 | Hide | Hide projection view (user minimized CarPlay) |

### Adapter → Host (A→H) — Adapter Originated

These commands are **generated by the adapter** to notify the host of state changes. All are pure informational — **do NOT trigger session management actions**.

| ID Range | Group | Summary |
|----------|-------|---------|
| 500-509 | Focus (AA only) | RequestVideoFocus, ReleaseVideoFocus, audio focus variants, navi focus — Android Auto only |
| 1003-1011 | Connection Status | WiFi/BT scan results, connect/disconnect notifications |

**⚠️ CRITICAL: Command 1010 Clarification (Binary Verified Feb 2026)**

Command 1010 (`DeviceWifiNotConnected`) is a **WiFi hotspot status notification**, NOT a session termination signal.

- **Correct name:** `DeviceWifiNotConnected` (verified via `ARMadb-driver` binary disassembly at `0x19a64`)
- **Meaning:** The adapter's WiFi hotspot currently has no phone connected
- **When sent:** During initialization, periodically while idle, or when WiFi link drops
- **Host should:** Log status, update UI indicator, but **do NOT terminate active sessions**

For **USB CarPlay**: This command is completely irrelevant - data flows over USB, not WiFi. Ignore it.

For **Wireless CarPlay**: WiFi dropping doesn't mean the session has ended. Wait for:
- `Unplugged` (0x04) message for definitive session end
- `OnCarPlayPhase 0` for session termination
- Heartbeat timeout for connection loss

---

## IMPORTANT: Voice/Call Events Use AudioData, NOT Command

**Siri, phone calls, and navigation audio events are signaled via AudioData (0x07), not Command (0x08).**

For the authoritative audio command table with `decode_type` and `audio_type` per command, see `audio_protocol.md`.

### AudioData (0x07) Audio Commands

These are embedded in AudioData messages with a 13-byte payload when no audio data is present:

| AudioCmd | Name | Direction | Host Action |
|----------|------|-----------|-------------|
| 1 | AUDIO_OUTPUT_START | P→A→H | Prepare audio playback |
| 2 | AUDIO_OUTPUT_STOP | P→A→H | Stop audio playback |
| 3 | AUDIO_INPUT_CONFIG | P→A→H | Configure mic input format |
| 4 | AUDIO_PHONECALL_START | P→A→H | **Start microphone capture** |
| 5 | AUDIO_PHONECALL_STOP | P→A→H | **Stop microphone capture** |
| 6 | AUDIO_NAVI_START | P→A→H | Duck media audio for navigation |
| 7 | AUDIO_NAVI_STOP | P→A→H | Restore media audio |
| 8 | AUDIO_SIRI_START | P→A→H | **Start microphone capture** |
| 9 | AUDIO_SIRI_STOP | P→A→H | **Stop microphone capture** |
| 10 | AUDIO_MEDIA_START | P→A→H | Media playback starting |
| 11 | AUDIO_MEDIA_STOP | P→A→H | Media playback stopped |
| 14 | AUDIO_INCOMING_CALL | P→A→H | Incoming call notification |

**Firmware handler:** `0x1a97e` - maps iAP audio signals to AudioSignal_* names

### Correct Siri Flow (Verified)

```
Phone (CarPlay)              Adapter                    Host App
      │                         │                          │
      │ [User activates Siri]   │                          │
      │                         │                          │
      │──AudioSignal_SIRI_START─┼──AudioData(cmd=8)───────►│
      │                         │                          │ [Start mic capture]
      │                         │                          │
      │                         │◄──────AudioData──────────│ [Mic audio samples]
      │◄──────iAP audio─────────│                          │
      │                         │                          │
      │ [Siri processes voice]  │                          │
      │                         │                          │
      │──AudioSignal_SIRI_STOP──┼──AudioData(cmd=9)───────►│
      │                         │                          │ [Stop mic capture]
```

### Incorrect Assumption (Clarified)

The Command IDs `SiriButtonDown(5)` and `SiriButtonUp(6)` are for the **host to INITIATE Siri** (e.g., steering wheel button press), NOT for receiving Siri activation notifications from the phone.

---

## Command ID Reference Tables

**Source:** Direct disassembly of `ARMadb-driver.unpacked` function at `0x19744` (binary verified Feb 2026)
**Method:** Traced switch table comparisons to string load targets

### Basic Commands (1-31) — Universal (CarPlay + Android Auto)

| ID | Hex | Action | Direction | Description |
|----|-----|--------|-----------|-------------|
| 1 | 0x01 | StartRecordMic | H→A→P | Begin microphone recording |
| 2 | 0x02 | StopRecordMic | H→A→P | Stop microphone recording |
| 3 | 0x03 | RequestHostUI | P→A→H | Phone requests host UI |
| 4 | 0x04 | DisableBluetooth / PhoneBtMacNotify | H→A / A→H | Disable Bluetooth (H→A) or Phone BT MAC notification (A→H, extended) |
| 5 | 0x05 | SiriButtonDown | H→A→P | Siri button pressed |
| 6 | 0x06 | SiriButtonUp | H→A→P | Siri button released |
| 7 | 0x07 | UseCarMic | H→A | Use car's microphone |
| 8 | 0x08 | UseBoxMic | H→A | Use adapter's microphone |
| 12 | 0x0C | RequestKeyFrame | H→A | Request video IDR frame (IDR aggressiveness is set by the Open `format` field — 1=basic, 5=aggressive; see `video_protocol.md`) |
| 14 | 0x0E | Hide | P→A→H | Hide/minimize projection |
| 15 | 0x0F | UseBoxI2SMic | H→A | Use adapter's I2S microphone |
| 16 | 0x10 | StartNightMode | H→A | Enable night/dark mode |
| 17 | 0x11 | StopNightMode | H→A | Disable night mode |
| 18 | 0x12 | StartGNSSReport | H→A | Start GPS data forwarding to phone |
| 19 | 0x13 | StopGNSSReport | H→A | Stop GPS data forwarding |
| 21 | 0x15 | UsePhoneMic | H→A | Use phone's microphone |
| 22 | 0x16 | UseBluetoothAudio | H→A | Route audio via Bluetooth |
| 23 | 0x17 | UseBoxTransAudio | H→A | Use adapter audio transmitter |
| 24 | 0x18 | Use24GWiFi | H→A | Use 2.4 GHz WiFi band |
| 25 | 0x19 | Use5GWiFi | H→A | Use 5 GHz WiFi band |
| 26 | 0x1A | RefreshFrame | H→A | Force video frame refresh |
| 28 | 0x1C | StartStandbyMode | H→A | Enter standby mode |
| 29 | 0x1D | StopStandbyMode | H→A | Exit standby mode |
| 30 | 0x1E | StartBleAdv | H→A | Start BLE advertising |
| 31 | 0x1F | StopBleAdv | H→A | Stop BLE advertising |

### Control Button Commands (100-106) - All H→A→P — CarPlay

| ID | Hex | Action | Description |
|----|-----|--------|-------------|
| 100 | 0x64 | CtrlButtonLeft | D-Pad left - forwarded to phone |
| 101 | 0x65 | CtrlButtonRight | D-Pad right - forwarded to phone |
| 102 | 0x66 | CtrlButtonUp | D-Pad up - forwarded to phone |
| 103 | 0x67 | CtrlButtonDown | D-Pad down - forwarded to phone |
| 104 | 0x68 | CtrlButtonEnter | Enter/Select - forwarded to phone |
| 105 | 0x69 | CtrlButtonRelease | Button release - forwarded to phone |
| 106 | 0x6A | CtrlButtonBack | Back button - forwarded to phone |

### Rotary Knob Commands (111-114) - All H→A→P — CarPlay

| ID | Hex | Action | Description |
|----|-----|--------|-------------|
| 111 | 0x6F | CtrlKnobLeft | Knob CCW - forwarded to phone |
| 112 | 0x70 | CtrlKnobRight | Knob CW - forwarded to phone |
| 113 | 0x71 | CtrlKnobUp | Knob tilt up - forwarded to phone |
| 114 | 0x72 | CtrlKnobDown | Knob tilt down - forwarded to phone |

### Media Control Commands (200-205) - All H→A→P — CarPlay

| ID | Hex | Action | Description |
|----|-----|--------|-------------|
| 200 | 0xC8 | MusicACHome | Home - forwarded to phone |
| 201 | 0xC9 | MusicPlay | Play - forwarded to phone |
| 202 | 0xCA | MusicPause | Pause - forwarded to phone |
| 203 | 0xCB | MusicPlayOrPause | Toggle - forwarded to phone |
| 204 | 0xCC | MusicNext | Next track - forwarded to phone |
| 205 | 0xCD | MusicPrev | Previous track - forwarded to phone |

### Phone Call Commands (300-314) - All H→A→P — CarPlay

| ID | Hex | Action | Description |
|----|-----|--------|-------------|
| 300 | 0x12C | PhoneAnswer | Answer call - forwarded to phone |
| 301 | 0x12D | PhoneHungUp | End call - forwarded to phone |
| 302-313 | 0x12E-0x139 | PhoneKey0-9,*,# | DTMF - forwarded to phone |
| 314 | 0x13A | CarPlay_PhoneHookSwitch | Hook toggle - forwarded to phone |

### Android Auto Focus Commands (500-509) - Android Auto Only — Verified Jan 2026

These commands manage audio/video/navigation focus for Android Auto sessions only (not used in CarPlay):

| ID | Hex | Action | Direction | Description |
|----|-----|--------|-----------|-------------|
| 500 | 0x1F4 | RequestVideoFocus | A→H | Adapter requests host show video |
| 501 | 0x1F5 | ReleaseVideoFocus | A→H | Adapter releases video focus |
| 502 | 0x1F6 | **RequestAudioFocus** | A→H | Request audio focus (binary-verified Feb 2026) |
| 503 | 0x1F7 | **RequestAudioFocusTransient** | A→H | Request transient audio focus (binary-verified Feb 2026) |
| 504 | 0x1F8 | RequestAudioFocusDuck | A→H | Request audio ducking (lower other audio) |
| 505 | 0x1F9 | ReleaseAudioFocus | A→H | Release audio focus |
| 506 | 0x1FA | RequestNaviFocus | A→H | Request navigation audio focus |
| 507 | 0x1FB | ReleaseNaviFocus | A→H | Release navigation focus |
| 508 | 0x1FC | RequestNaviScreenFocus | BOTH | Three-step handshake: host sends, adapter replies, host echoes. On wireless CarPlay the adapter never initiates, so host must send first. See video_protocol.md "Handshake Sequence". |
| 509 | 0x1FD | ReleaseNaviScreenFocus | H→A | Release navigation screen focus |

**Audio Focus Types (from OpenAuto TTY logs):**

| Type Value | Meaning | Command Triggered |
|------------|---------|-------------------|
| 3 | Duck | RequestAudioFocusDuck (504) |
| 4 | Release | ReleaseAudioFocus (505) |

**Firmware Log Evidence:**
```
[I] requested audio focus, type: 3
[OpenAuto] [BoxAudioOutput] onAudioFocusChanned: 3
[D] _SendPhoneCommandToCar: RequestAudioFocusDuck(504)
```

### App Launch Commands (400-403) - All H→A→P — CarPlay Only (Binary Verified Feb 2026)

| ID | Hex | Action | Direction | Description |
|----|-----|--------|-----------|-------------|
| 400 | 0x190 | LaunchAppMaps | H→A→P | Launch Apple Maps on phone |
| 401 | 0x191 | LaunchAppPhone | H→A→P | Launch Phone app on phone |
| 402 | 0x192 | LaunchAppMusic | H→A→P | Launch Apple Music on phone |
| 403 | 0x193 | LaunchAppNowPlaying | H→A→P | Launch Now Playing on phone |

**Binary Evidence:** Strings found in `_SendPhoneCommandToCar` at `0x19244`. Forwarded to phone via MiddleMan IPC to AppleCarPlay.

### UI Control Commands (410-412) - All H→A→P — CarPlay Only (Binary Verified Feb 2026)

| ID | Hex | Action | Direction | Description |
|----|-----|--------|-----------|-------------|
| 410 | 0x19A | ShowUI | H→A→P | Show CarPlay UI (URL payload via HU_SHOWUI_URL) |
| 411 | 0x19B | StopUI | H→A→P | Hide/stop CarPlay UI |
| 412 | 0x19C | SuggestUI | H→A→P | Siri suggestions (altScreenSuggestUIURLs) |

**Binary Evidence:** Strings found in `_SendPhoneCommandToCar`. Related config keys: `HU_SHOWUI_URL`, `HU_SUGGESTUI_URLS`.

### DVR Commands (600-601) - Dead Code (Binary Verified Feb 2026)

| ID | Hex | Action | Direction | Description |
|----|-----|--------|-----------|-------------|
| 600 | 0x258 | DVRCommand_RequestPreview | H→A | Request DVR camera preview |
| 601 | 0x259 | DVRCommand_ScanAndConnect | H→A | Scan and connect to DVR camera |

**Note:** Dead code — no camera hardware on CPC200-CCPA. DVRServer binary not shipped on live firmware.

### Custom Commands (700-702) - H→A→P — CarPlay Only (Binary Verified Feb 2026)

| ID | Hex | Action | Direction | Description |
|----|-----|--------|-----------|-------------|
| 700 | 0x2BC | CusCommand_UpdateAudioVolume | H→A→P | Forward HU volume to CarPlay (HU_AUDIOVOLUME_INFO) |
| 701 | 0x2BD | CusCommand_HFPCallStart | H→A→P | Notify CarPlay of HFP call start |
| 702 | 0x2BE | CusCommand_HFPCallStop | H→A→P | Notify CarPlay of HFP call end |

**Binary Evidence:** Strings found in `_SendPhoneCommandToCar`. CusCommand_UpdateAudioVolume forwards the host's volume level to the CarPlay session.

### Connection Status Commands (1000-1013) - Mixed Directions

| ID | Hex | Action | Direction | Description |
|----|-----|--------|-----------|-------------|
| 1000 | 0x3E8 | SupportWifi | H→A | Enable WiFi mode |
| 1001 | 0x3E9 | SupportAutoConnect | H→A | Enable auto-connect |
| 1002 | 0x3EA | StartAutoConnect | H→A | Start auto-connect |
| 1003 | 0x3EB | ScaningDevices | A→H | Scanning notification |
| 1004 | 0x3EC | DeviceFound | A→H | Device found notification |
| 1005 | 0x3ED | DeviceNotFound | A→H | No device found |
| 1006 | 0x3EE | DeviceConnectFailed | A→H | Connection failed |
| 1007 | 0x3EF | DeviceBluetoothConnected | A→H | BT connected |
| 1008 | 0x3F0 | DeviceBluetoothNotConnected | A→H | BT disconnected |
| 1009 | 0x3F1 | DeviceWifiConnected | A→H | WiFi hotspot: phone connected |
| 1010 | 0x3F2 | DeviceWifiNotConnected | A→H | WiFi hotspot: no phone connected (**NOT session end**) |
| 1011 | 0x3F3 | DeviceBluetoothPairStart | A→H | Pairing started |
| 1012 | 0x3F4 | WiFiPair | H→A | Enter pairing mode |
| 1013 | 0x3F5 | GetBluetoothOnlineList | H→A | Request BT device list |

---

## Binary Analysis Details

### Key Functions

| Address | Function | Purpose |
|---------|----------|---------|
| `0x197a8` | Command name lookup | Maps command ID to string name |
| `0x2047e` | CarPlay forward | Forwards commands to CarPlay |
| `0x1d2fe` | D-Bus handler | Receives commands from phone via D-Bus |
| `0x1a97e` | Audio signal handler | Maps iAP audio events to AudioSignal_* |

### Forwarding Log Messages (Firmware)

| Address | String | Meaning |
|---------|--------|---------|
| `0x6d18b` | "Forward CarPlay control cmd!" | Command forwarded to CarPlay |
| `0x6d15b` | "Forward AndroidAuto control cmd!" | Command forwarded to AA |
| `0x6bd4d` | "_SendPhoneCommandToCar: %s(%d)" | Logging forwarded command |

---

## GPS/GNSS Commands

Commands 18 (StartGNSSReport) and 19 (StopGNSSReport) control GPS data forwarding from the head unit to the connected phone. See the [Command ID Reference Tables](#command-id-reference-tables) for their basic entries.

See `command_details.md` for StartGNSSReport/StopGNSSReport implementation details (binary evidence, prerequisites, firmware behavior) and `usb_protocol.md` > GnssData (0x29) for the complete GPS pipeline analysis (NMEA format, iAP2 conversion, GNSSCapability configuration, end-to-end data flow).

---

## Extended Command Formats

*Verified via USB capture (Jan 2026)*

Some Command packets (type 8) use extended payloads beyond the standard 4-byte command ID. These are identified by packet length > 20 bytes.

### Command 4 Extended: Phone Bluetooth MAC Notification (A→H)

When a phone connects, the adapter sends an extended Command 4 with the phone's Bluetooth MAC address:

| Offset | Size | Content | Description |
|--------|------|---------|-------------|
| 0 | 4 | `04 00 00 00` | Command ID 4 (little-endian) |
| 4 | 17 | `XX:XX:XX:XX:XX:XX` | Bluetooth MAC address (ASCII, null-padded) |
| 21 | 3 | `e8 07 00` | Additional data (possibly year: 2024) |

**Captured Example (40-byte Command):**
```
Header: aa 55 aa 55 18 00 00 00 08 00 00 00 f7 ff ff ff
Payload: 04 00 00 00 36 34 3a 33 31 3a 33 35 3a 38 43 3a  |....64:31:35:8C:|
         32 39 3a 36 39 e8 07 00                          |29:69...|
```

**Context:** Sent by adapter after CarPlay phone connects to notify host of the phone's Bluetooth address for pairing/identification.

---

## Command 1010 Binary Analysis Evidence (Feb 2026)

**Status:** VERIFIED via direct binary disassembly and TTY log correlation

### Previous Incorrect Documentation

The command 1010 was previously documented with conflicting names:
- "ConnectionComplete" (connection established) - **WRONG**
- "projectionDisconnected" (session ended) - **WRONG**

### Correct Name: `DeviceWifiNotConnected`

**Binary Evidence (ARMadb-driver.unpacked):**

The function at `0x19744` is a command ID → string name mapper. Disassembly shows:

```asm
; Command ID comparison for 0x3f1 (1009)
0x00019922      movw r3, 0x3f1
0x00019926      cmp r1, r3
0x00019928      beq.w 0x19a60

; Command ID comparison for 0x3f2 (1010)
0x0001992c      movw r3, 0x3f2
0x00019930      cmp r1, r3
0x00019932      beq.w 0x19a64

; Handler for 0x3f1 (1009) - loads "DeviceWifiConnected" string
0x00019a60      ldr r3, str.DeviceWifiConnected    ; [0x6bc73] = "DeviceWifiConnected"
0x00019a62      b 0x19a6e

; Handler for 0x3f2 (1010) - loads "DeviceWifiNotConnected" string
0x00019a64      ldr r3, str.DeviceWifiNotConnected ; [0x6bc87] = "DeviceWifiNotConnected"
0x00019a66      b 0x19a6e

; Log output with format string
0x00019a72      ldr r2, str._SendPhoneCommandToCar:__s__d__n ; "_SendPhoneCommandToCar: %s(%d)\n"
0x00019a76      bl sym.BoxLog
```

**String locations in binary:**
```
0x0006bc73  "DeviceWifiConnected"      (19 bytes)
0x0006bc87  "DeviceWifiNotConnected"   (22 bytes)
```

### TTY Log Correlation

Captured firmware logs confirm the mapping:

```
[D]2020-01-02 00:08:01.770 ARMadb-driver[Accessory_fd]: _SendPhoneCommandToCar: DeviceWifiConnected(1009)
[D]2020-01-02 00:08:00.769 ARMadb-driver[Accessory_fd]: _SendPhoneCommandToCar: DeviceWifiNotConnected(1010)
```

See the [Connection Status Commands (1000-1013)](#connection-status-commands-1000-1013---mixed-directions) table for the complete per-ID listing.

### Behavioral Analysis

**When 1010 is sent:**
1. During adapter initialization (no phone connected yet)
2. Periodically while idle/waiting for connection (~12s interval)
3. When WiFi link drops during wireless CarPlay session

**When 1010 is NOT a session termination:**
- For USB CarPlay: WiFi status is completely irrelevant (data flows over USB)
- For Wireless CarPlay: WiFi dropping doesn't immediately end session; may reconnect

**Actual session termination signals:**
- `Unplugged` (message type 0x04) - definitive phone disconnect
- `OnCarPlayPhase 0` - session ended
- Heartbeat timeout - USB connection lost

### Multi-Device Tracking

The adapter maintains a `DevList` array of known devices:
```json
"DevList": [
  {"id":"14:1B:A0:1E:DE:28", "type":"CarPlay", "name":"lePhone"},
  {"id":"F0:04:E1:81:0E:06", "type":"CarPlay", "name":"Matt"}
]
```

Command 1010 is a **general WiFi hotspot status**, not tied to a specific device. It indicates "no device currently connected to WiFi hotspot" regardless of which devices are in the DevList.

---

## Related Documentation

- **`command_details.md`** - Detailed usage documentation for each command (binary-verified)
- **`audio_protocol.md`** - Audio command table with decode_type and audio_type per command
- `usb_protocol.md` - Main USB protocol reference (includes GnssData 0x29 pipeline)
- `../04_Implementation/session_examples.md` - Real captured session examples (CarPlay & Android Auto)
- `../03_Audio_Processing/audio_formats.md` - Audio format analysis and processing pipeline
- `../01_Firmware_Architecture/initialization.md` - Session setup
