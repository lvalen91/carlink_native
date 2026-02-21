# CPC200-CCPA Command Details Reference

**Status:** Binary-verified command documentation
**Source:** ARMadb-driver.unpacked disassembly analysis
**Firmware Version:** 2025.10.15.1127 (binary analysis reference version)
**Last Updated:** 2026-02-19 (Added: firmware trigger points for all 1000-1013 commands, historical 1010 bug analysis, session termination signals clarification. Prior: 14 new commands 400-403, 410-412, 502-503, 600-601, 700-702)
**Verification Method:** Direct radare2 disassembly tracing of command handlers

---

## Verification Legend

| Status | Meaning |
|--------|---------|
| **VERIFIED** | Traced through binary to action/D-Bus signal/filesystem operation |
| **PARTIAL** | Name confirmed in binary, but handler action not fully traced |
| **INFERRED** | Purpose derived from name/context, not traced to specific action |
| **UNKNOWN** | Cannot determine purpose from binary analysis |

---

## Basic Commands (1-31)

### Command 1: StartRecordMic (0x01)
**Direction:** Host → Adapter → Phone (H→A→P)
**Status:** VERIFIED

**Purpose:** Initiate microphone recording and send audio to phone.

**Binary Evidence:**
- String at `0x6b91b`: `"StartRecordMic"`
- Forwarded to phone via `Forward CarPlay control cmd!` path at `0x2047e`

**When to Use:** When phone requests microphone input (e.g., voice memo app). Host receives AUDIO_INPUT_CONFIG via AudioData first.

**Host Action:** Start capturing microphone audio and send via AudioData (0x07).

---

### Command 2: StopRecordMic (0x02)
**Direction:** Host → Adapter → Phone (H→A→P)
**Status:** VERIFIED

**Purpose:** Stop microphone recording.

**Binary Evidence:**
- String at `0x6b92a`: `"StopRecordMic"`
- Forwarded to phone via control command path

**When to Use:** When phone no longer needs microphone input.

**Host Action:** Stop microphone capture.

---

### Command 3: RequestHostUI (0x03)
**Direction:** Phone → Adapter → Host (P→A→H)
**Status:** VERIFIED

**Purpose:** Phone requests host application show its native UI (exit CarPlay/Android Auto projection).

**Binary Evidence:**
- String at `0x6b93d`: `"RequestHostUI"`
- Handler at `0x1de52` checks payload length (4 or 0x44 bytes)
- Calls `fcn.00018628` for extended handling

**When Received:** User taps "car" or "phone" icon in CarPlay to return to native head unit interface.

**Host Action:** Hide CarPlay projection view and show native host UI. May need to release video focus.

---

### Command 4: DisableBluetooth / PhoneBtMacNotify (0x04)
**Direction:** Bidirectional (H→A / A→H)
**Status:** VERIFIED

**Purpose:**
- **H→A (4-byte payload):** Disable Bluetooth on adapter
- **A→H (21-byte payload):** Adapter notifying host of connected phone's Bluetooth MAC address

**Binary Evidence:**
- String at `0x6b8f3`: `"DisableBluetooth"`
- Extended format (21 bytes) contains BT MAC at offset 4

**Extended Payload Format:**
```
Offset  Size  Content
0       4     Command ID (0x04)
4       17    Bluetooth MAC "XX:XX:XX:XX:XX:XX" (ASCII)
21      3     Additional data (possibly year)
```

**When Received (A→H):** After phone connects, adapter sends phone's BT MAC for pairing/identification.

---

### Command 5: SiriButtonDown (0x05)
**Direction:** Host → Adapter → Phone (H→A→P)
**Status:** VERIFIED

**Purpose:** Simulate Siri/voice assistant button press (down event).

**Binary Evidence:**
- String at `0x6b94b`: `"SiriButtonDown"`
- Forwarded to phone via CarPlay control path

**When to Use:** User presses steering wheel voice button or equivalent.

**Host Action:** Send this command; phone will respond with AUDIO_SIRI_START via AudioData when Siri activates.

**Note:** This INITIATES Siri from host side. Phone-initiated Siri comes via AudioData cmd=8, NOT this command.

---

### Command 6: SiriButtonUp (0x06)
**Direction:** Host → Adapter → Phone (H→A→P)
**Status:** VERIFIED

**Purpose:** Simulate Siri/voice assistant button release (up event).

**Binary Evidence:**
- String at `0x6b95a`: `"SiriButtonUp"`
- Forwarded to phone via CarPlay control path

**When to Use:** User releases steering wheel voice button.

---

### Command 7: UseCarMic (0x07)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Configure audio routing to use car's built-in microphone (host-side mic).

**Binary Evidence:**
- String at `0x6b8e9`: `"UseCarMic"`
- Handler at `0x1dc3c` calls `fcn.00018a08` with param 1
- Sets `MicType` configuration via `fcn.00065f98`
- Log: `"Box Audio Process set Enable\n"` or `"Box Audio Process set Disable\n"`

**Behavior:** When set, adapter expects host to capture and send microphone audio via AudioData.

**Host Action:** Prepare to capture microphone audio when SIRI_START or PHONECALL_START received.

---

### Command 8: UseBoxMic (0x08)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Configure audio routing to use adapter's onboard microphone.

**Binary Evidence:**
- String at `0x6b904`: `"UseBoxMic"`
- Handler sets `MicType` to box mic mode

**Behavior:** Adapter uses its internal microphone for Siri/calls. Host does not need to capture mic audio.

---

### Command 12: RequestKeyFrame (0x0C)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Request video encoder insert an IDR (keyframe) for decoder sync.

**Binary Evidence:**
- String at `0x6b967`: `"RequestKeyFrame"`
- Triggers video encoder to emit IDR frame

**When to Use:**
- After video decoder errors/corruption
- After seeking or resuming playback
- On initial stream start

**Expected Response:** Next VideoData (0x06) will contain IDR frame.

---

### Command 14: Hide (0x0E)
**Direction:** Phone → Adapter → Host (P→A→H)
**Status:** VERIFIED

**Purpose:** Phone requests projection view be hidden/minimized.

**Binary Evidence:**
- String at `0x6b938`: `"Hide"`
- Forwarded from phone to host

**When Received:** User minimizes CarPlay on phone or switches away from CarPlay app.

**Host Action:** Hide or minimize the CarPlay projection view. May continue receiving video frames.

---

### Command 15: UseBoxI2SMic (0x0F)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Configure audio routing to use adapter's I2S microphone interface.

**Binary Evidence:**
- String at `0x6b90e`: `"UseBoxI2SMic"`
- Handler at `0x1ddd2` sets mic routing configuration

**When to Use:** When adapter has external I2S microphone connected.

---

### Command 16: StartNightMode (0x10)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Enable night/dark mode theme in CarPlay UI.

**Binary Evidence:**
- String at `0x6b977`: `"StartNightMode"`
- Handler at ~`0x1ddc0` triggers theme change
- Relates to `HU_DAYNIGHT_MODE` D-Bus signal

**When to Use:** When vehicle dashboard enters night mode, or based on user preference.

**Effect:** CarPlay UI switches to dark color scheme.

---

### Command 17: StopNightMode (0x11)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Disable night mode, return to day/light theme.

**Binary Evidence:**
- String at `0x6b986`: `"StopNightMode"`
- Handler triggers theme change

**When to Use:** When vehicle dashboard exits night mode.

---

### Command 18: StartGNSSReport (0x12)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Enable GPS data forwarding from host to phone.

**Binary Evidence:**
- String at `0x6b994`: `"StartGNSSReport"`
- Enables reading from `/tmp/gnss_info`
- Forward via iAP2LocationEngine to phone

**Prerequisites:**
- `HudGPSSwitch=1` in riddle.conf
- Host must write NMEA data to `/tmp/gnss_info`

**Data Format:** NMEA 0183 sentences ($GPGGA, $GPRMC, $GPGSV)

---

### Command 19: StopGNSSReport (0x13)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Stop GPS data forwarding.

**Binary Evidence:**
- String at `0x6b9a4`: `"StopGNSSReport"`

**Effect:** Phone reverts to using its internal GPS.

---

### Command 21: UsePhoneMic (0x15)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Configure audio routing to use phone's microphone.

**Binary Evidence:**
- String at `0x6b9b3`: `"UsePhoneMic"`
- Handler at `0x1de04` sets MicType to phone mode (value 2)

**Behavior:** Phone uses its own microphone for Siri/calls. Neither host nor adapter capture mic audio.

---

### Command 22: UseBluetoothAudio (0x16)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Route audio output via Bluetooth instead of USB.

**Binary Evidence:**
- String at `0x6b9bf`: `"UseBluetoothAudio"`
- Handler at `0x1de68` sets `BtAudio` configuration to 1

**When to Use:** When audio should play through Bluetooth connection rather than wired USB audio.

---

### Command 23: UseBoxTransAudio (0x17)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Route audio via adapter's FM/audio transmitter.

**Binary Evidence:**
- String at `0x6b9d1`: `"UseBoxTransAudio"`
- Handler at `0x1db94` sets `BtAudio` configuration to 0

**When to Use:** When using adapter's built-in audio transmitter output.

---

### Command 24: Use24GWiFi (0x18)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Switch adapter's WiFi to 2.4 GHz band.

**Binary Evidence:**
- String at `0x6b9e2`: `"Use24GWiFi"`
- Handler at `0x1de74` executes: `touch /etc/wifi_use_24G`
- Creates flag file that persists across reboots

**Effect:** Adapter's WiFi hotspot switches to 2.4 GHz. Requires reboot to take effect.

---

### Command 25: Use5GWiFi (0x19)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Switch adapter's WiFi to 5 GHz band.

**Binary Evidence:**
- String at `0x6b9ed`: `"Use5GWiFi"`
- Handler executes: `rm -f /etc/wifi_use_24G`
- Removes 2.4 GHz flag file

**Effect:** Adapter's WiFi hotspot uses 5 GHz (default). Requires reboot to take effect.

---

### Command 26: RefreshFrame (0x1A)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Force video frame refresh/re-encode.

**Binary Evidence:**
- String at `0x6b9f7`: `"RefreshFrame"`

**When to Use:** Similar to RequestKeyFrame but may trigger different encoder behavior. Use for display refresh.

---

### Command 28: StartStandbyMode (0x1C)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Put adapter into low-power standby mode.

**Binary Evidence:**
- String at `0x6ba04`: `"StartStandbyMode"`

**When to Use:** When vehicle is parked or ignition off but adapter should remain powered for quick resume.

---

### Command 29: StopStandbyMode (0x1D)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Exit standby mode and resume normal operation.

**Binary Evidence:**
- String at `0x6ba15`: `"StopStandbyMode"`

---

### Command 30: StartBleAdv (0x1E)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Start BLE advertising for wireless CarPlay pairing.

**Binary Evidence:**
- String at `0x6ba25`: `"StartBleAdv"`
- Handler at `0x1de7e` sets `BLE_ADV_ENABLE` to 1 via `fcn.00066640`

**When to Use:** To allow new phones to discover and pair with adapter wirelessly.

---

### Command 31: StopBleAdv (0x1F)
**Direction:** Host → Adapter (H→A)
**Status:** VERIFIED

**Purpose:** Stop BLE advertising.

**Binary Evidence:**
- String at `0x6ba31`: `"StopBleAdv"`
- Handler sets `BLE_ADV_ENABLE` to 0

**When to Use:** After pairing complete or to hide adapter from discovery.

---

## D-Pad Control Commands (100-106)

**Direction:** All H→A→P (forwarded to phone)
**Status:** VERIFIED (string names and forwarding path)

These commands simulate hardware navigation controls and are forwarded directly to the connected phone.

| ID | Name | Purpose |
|----|------|---------|
| 100 | CtrlButtonLeft | Navigate left in UI |
| 101 | CtrlButtonRight | Navigate right in UI |
| 102 | CtrlButtonUp | Navigate up in UI |
| 103 | CtrlButtonDown | Navigate down in UI |
| 104 | CtrlButtonEnter | Confirm/select current item |
| 105 | CtrlButtonRelease | Button released (sent after press) |
| 106 | CtrlButtonBack | Go back/cancel |

**Binary Evidence:** All strings at `0x6ba3c`-`0x6ba99`, forwarded via `Forward CarPlay control cmd!` path.

**Usage Pattern:**
1. Send button down command (e.g., 100 for left)
2. Wait for UI response
3. Send CtrlButtonRelease (105) when button released

---

## Rotary Knob Commands (111-114)

**Direction:** All H→A→P (forwarded to phone)
**Status:** VERIFIED (string names and forwarding path)

| ID | Name | Purpose |
|----|------|---------|
| 111 | CtrlKnobLeft | Knob rotated counter-clockwise |
| 112 | CtrlKnobRight | Knob rotated clockwise |
| 113 | CtrlKnobUp | Knob tilted/pushed up |
| 114 | CtrlKnobDown | Knob tilted/pushed down |

**Binary Evidence:** Strings at `0x6baa8`-`0x6bace`.

**When to Use:** For vehicles with rotary knob/iDrive style controllers.

---

## Media Control Commands (200-205)

**Direction:** All H→A→P (forwarded to phone)
**Status:** VERIFIED (string names and forwarding path)

| ID | Name | Purpose |
|----|------|---------|
| 200 | MusicACHome | Return to media app home |
| 201 | MusicPlay | Start/resume playback |
| 202 | MusicPause | Pause playback |
| 203 | MusicPlayOrPause | Toggle play/pause |
| 204 | MusicNext | Skip to next track |
| 205 | MusicPrev | Skip to previous track |

**Binary Evidence:** Strings at `0x6badb`-`0x6bb17`.

**When to Use:** Map to steering wheel media controls or touchscreen buttons.

---

## Phone Call Commands (300-314)

**Direction:** All H→A→P (forwarded to phone)
**Status:** VERIFIED (string names and forwarding path)

| ID | Name | Purpose |
|----|------|---------|
| 300 | PhoneAnswer | Answer incoming call |
| 301 | PhoneHungUp | End/reject call |
| 302-311 | PhoneKey0-9 | DTMF tones 0-9 |
| 312 | PhoneKeyStar | DTMF tone * |
| 313 | PhoneKeyPound | DTMF tone # |
| 314 | CarPlay_PhoneHookSwitch | Hook switch toggle |

**Binary Evidence:** Strings at `0x6bb21`-`0x6bbb8`.

**When to Use:** Map to steering wheel phone controls or touchscreen call UI.

---

## Android Auto Focus Commands (500-509)

**Direction:** Mixed (mostly A→H)
**Status:** PARTIAL (names verified, behavior inferred from OpenAuto logs)

| ID | Name | Direction | Purpose |
|----|------|-----------|---------|
| 500 | RequestVideoFocus | A→H | Adapter requests host display video |
| 501 | ReleaseVideoFocus | A→H | Adapter releases video focus |
| 502 | RequestAudioFocus | A→H | Request audio focus (binary-verified Feb 2026) |
| 503 | RequestAudioFocusTransient | A→H | Request transient audio focus (binary-verified Feb 2026) |
| 504 | RequestAudioFocusDuck | A→H | Request host duck other audio |
| 505 | ReleaseAudioFocus | A→H | Release audio focus |
| 506 | RequestNaviFocus | A→H | Request navigation audio focus |
| 507 | ReleaseNaviFocus | A→H | Release navigation focus |
| 508 | RequestNaviScreenFocus | BOTH | Navigation screen focus handshake |
| 509 | ReleaseNaviScreenFocus | H→A | Release navigation screen focus |

**Host Actions:**
- 500: Show Android Auto video view
- 501: May hide video view
- 504: Lower volume of other audio sources
- 505: Restore normal audio levels
- 508: Echo back 508 to adapter (recommended precaution — testing was inconclusive on whether strictly required; nav video activation is primarily driven by `naviScreenInfo` in BoxSettings)

---

## Connection Status Commands (1000-1013)

**Direction:** Mixed (H→A for control, A→H for status)
**Status:** VERIFIED

### Host → Adapter Commands

| ID | Name | Purpose |
|----|------|---------|
| 1000 | SupportWifi | Enable WiFi mode on adapter |
| 1001 | SupportAutoConnect | Enable auto-reconnect feature |
| 1002 | StartAutoConnect | Initiate auto-connect scan for known devices |
| 1012 | WiFiPair | Enter WiFi pairing mode |
| 1013 | GetBluetoothOnlineList | Request list of paired Bluetooth devices |

### Adapter → Host Status Notifications

| ID | Name | When Sent |
|----|------|-----------|
| 1003 | ScaningDevices | Adapter is scanning for devices |
| 1004 | DeviceFound | Device found during scan |
| 1005 | DeviceNotFound | Scan complete, no device found |
| 1006 | DeviceConnectFailed | Connection attempt failed |
| 1007 | DeviceBluetoothConnected | Bluetooth connection established |
| 1008 | DeviceBluetoothNotConnected | Bluetooth disconnected |
| 1009 | DeviceWifiConnected | Phone connected to adapter's WiFi hotspot |
| 1010 | DeviceWifiNotConnected | No phone connected to WiFi hotspot |
| 1011 | DeviceBluetoothPairStart | Bluetooth pairing started |

**Binary Evidence:** All strings at `0x6bbd0`-`0x6bcb7`. All status notifications (1003-1012) are sent via `_SendPhoneCommandToCar` (`fcn.00019244`) which constructs a type 0x08 message and sends it to the host. These are **pure informational** — the firmware takes no session management action when generating them.

### Firmware Trigger Points (Binary Verified Feb 2026)

| Cmd ID | Trigger Function | Specific Trigger | Notes |
|--------|-----------------|------------------|-------|
| 1000 | `fcn.00022284` (ProcessCmdOpen) | Sent immediately after processing Open (0x01) | Init-time capability flag |
| 1001 | `fcn.00022284` | Sent immediately after 1000 | Init-time capability flag |
| 1003 | `BluetoothDaemonControlerEx.virtual_44` | Auto-connect scan started | D-Bus callback |
| 1004 | `BluetoothDaemonControlerEx.virtual_44` | Device found during scan | D-Bus callback |
| 1005 | `BluetoothDaemonControlerEx.virtual_44` | Scan complete, no device | D-Bus callback |
| 1006 | `BluetoothDaemonControlerEx.virtual_44` | BT connection attempt failed | D-Bus callback |
| 1007 | `fcn.0001b7c8` | BT connected via D-Bus signal from `org.riddle.BluetoothControl` | Fires on BT HFP connect |
| 1008 | `fcn.0001b9d8` | BT disconnected via D-Bus signal | **CAN fire during active USB CarPlay** — BT disconnect does NOT affect USB |
| 1009 | `fcn.00069d7c` returns 2 | WiFi client connected | Internal WiFi state check |
| 1010 | `fcn.00069d7c` returns 3 or 4 | WiFi client not connected or error | Internal WiFi state check |
| 1011 | `BluetoothDaemonControlerEx` callback | BT pairing initiated | D-Bus callback |
| 1012 | `fcn.00022284` | WiFi kernel module needed | Init-time status |

### ⚠️ CRITICAL: Commands 1000-1013 Are ALL Pure Status Notifications

**Status:** VERIFIED via firmware binary trace of every trigger path

**ALL commands in the 1000-1013 range are informational status notifications.** The adapter generates them via `_SendPhoneCommandToCar` which simply constructs a type 0x08 message and writes it to USB. **No session management logic is associated with any of these commands.**

**Correct Host Behavior for ALL 1000-1013:**
- Log the status for debugging
- Optionally update UI status indicators
- **DO NOT terminate, pause, or reset active sessions**
- **DO NOT trigger reconnect sequences**

### ⚠️ Command 1010 (DeviceWifiNotConnected) — Historical Bug

**This is the most misinterpreted message in the protocol.**

The old app incorrectly treated 1010 as a session termination signal and would reset the USB connection. Firmware analysis proves:

1. **Trigger:** `fcn.00069d7c` is a WiFi state polling function. Returns 3 (not connected) or 4 (error). Has NO connection to USB session state.
2. **Delivery:** Packaged by `_SendPhoneCommandToCar` — same path as all status notifications.
3. **Adapter behavior:** After sending 1010, the adapter **continues operating normally**. USB read loop, video forwarding, audio streaming — all unaffected.
4. **Fix:** Ignoring 1010 (and all 1000-1013) dramatically improved session stability. CarPlay sessions that previously dropped on transient WiFi fluctuations now run uninterrupted.

**Session Termination Signals (the ONLY ones):**
- `Unplugged` (message type 0x04) — physical USB disconnect
- `Phase 0` (message type 0x03 with value 0) — firmware kills all phone-link processes
- Heartbeat timeout (~10-15 seconds without heartbeat response)

---

## App Launch Commands (400-403) — Binary Verified Feb 2026

**Direction:** All H→A→P (forwarded to phone via MiddleMan IPC)
**Status:** VERIFIED (strings in `_SendPhoneCommandToCar` at `0x19244`)

| ID | Name | Purpose |
|----|------|---------|
| 400 | LaunchAppMaps | Launch Apple Maps on phone |
| 401 | LaunchAppPhone | Launch Phone app on phone |
| 402 | LaunchAppMusic | Launch Apple Music on phone |
| 403 | LaunchAppNowPlaying | Launch Now Playing on phone |

**When to Use:** To programmatically launch specific apps on the connected phone. Commands are forwarded to AppleCarPlay via MiddleMan IPC.

---

## UI Control Commands (410-412) — Binary Verified Feb 2026

**Direction:** All H→A→P
**Status:** VERIFIED (strings in `_SendPhoneCommandToCar`)

| ID | Name | Purpose |
|----|------|---------|
| 410 | ShowUI | Show CarPlay UI (URL payload via `HU_SHOWUI_URL` config) |
| 411 | StopUI | Hide/stop CarPlay UI |
| 412 | SuggestUI | Siri suggestions (via `altScreenSuggestUIURLs`) |

---

## DVR Commands (600-601) — Binary Verified Feb 2026

**Direction:** All H→A
**Status:** VERIFIED (strings in binary) — **Dead code, no camera hardware**

| ID | Name | Purpose |
|----|------|---------|
| 600 | DVRCommand_RequestPreview | Request DVR camera preview |
| 601 | DVRCommand_ScanAndConnect | Scan and connect to DVR camera |

**Note:** DVRServer binary not shipped on live firmware. These commands exist in the dispatch table but have no functional implementation.

---

## Custom Commands (700-702) — Binary Verified Feb 2026

**Direction:** All H→A→P
**Status:** VERIFIED (strings in `_SendPhoneCommandToCar`)

| ID | Name | Purpose |
|----|------|---------|
| 700 | CusCommand_UpdateAudioVolume | Forward HU volume level to CarPlay session (via `HU_AUDIOVOLUME_INFO`) |
| 701 | CusCommand_HFPCallStart | Notify CarPlay of HFP call start |
| 702 | CusCommand_HFPCallStop | Notify CarPlay of HFP call end |

**When to Use:**
- **700:** When the head unit volume changes, forward the level to CarPlay so it can adjust audio rendering.
- **701/702:** When a Hands-Free Profile call starts/stops via the vehicle's Bluetooth, notify CarPlay to coordinate audio routing.

---

## Commands Not Found in Binary

The following command IDs were NOT found in the switch table at `fcn.00019744`:

| ID Range | Notes |
|----------|-------|
| 9-11 | Not implemented or internal use |
| 13 | Not implemented |
| 20 | Not found (0x14) |
| 27 | Not found (0x1B) |
| 107-110 | Gap between D-Pad and Knob |
| 115-199 | Not implemented |
| 206-299 | Not implemented |
| 315-399 | Not implemented |
| 404-409 | Not implemented |
| 413-499 | Not implemented |
| 510-599 | Not implemented |
| 602-699 | Not implemented |
| 703-999 | Not implemented |
| 1014+ | Not implemented |

---

## References

- **Binary:** `ARMadb-driver.unpacked`
- **Command Name Lookup Function:** `0x19744`
- **Command Dispatch Function:** `0x1da00`
- **Forwarding Log:** `"Forward CarPlay control cmd!"` at `0x6d18b`
- **USB Protocol:** `usb_protocol.md`
- **Audio Commands:** See AudioData (0x07) in `usb_protocol.md`
