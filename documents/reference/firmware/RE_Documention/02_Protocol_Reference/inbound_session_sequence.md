# Inbound Session Message Sequence

> **Status**: Non-definitive — still testing. Based on 4 observed wireless CarPlay sessions
> with CPC200-CCPA firmware `2025.10.15.1127CAY` and iPhone 18,4.
>
> Captured 2026-02-19 via adb logcat `[RECV]` messages.

## Overview

After the host sends its initialization sequence, the adapter responds with an ordered
series of inbound messages that follow a consistent pattern across sessions. These messages
map to distinct adapter + phone states and can be used to track connection progress.

High-frequency data messages (VIDEO_DATA, AUDIO_DATA, HEARTBEAT_ECHO) are excluded —
this documents only control/state messages.

## Sequence

### Phase 1 — Init Echo (adapter acknowledges host config)

Arrives immediately after the host's init sequence completes (~0ms).

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 1 | `Command(WIFI_ENABLE)` | 0x08 cmd=1000 | Adapter WiFi radio is on |
| 2 | `BluetoothDeviceName(carlink)` | 0x0A | Adapter BT name echo |
| 3 | `WifiDeviceName(carlink)` | 0x0A | Adapter WiFi SSID echo |
| 4 | `UiBringToForeground` | 0x0A | Tells HU to show pairing UI |
| 5 | `BluetoothPairedList(...)` | 0x0A | Previously paired devices (MAC + name) |
| 6 | `Command(AUTO_CONNECT_ENABLE)` | 0x08 cmd=1001 | Auto-connect feature enabled |
| 7 | `Command(MIC)` | 0x08 cmd=7 | Mic config echo |
| 8 | `SoftwareVersion(...)` | 0x0A | Firmware version string |
| 9 | `BoxSettings(adapter: YA)` | 0x09 | Adapter info JSON (uuid, DevList, hwVersion, etc.) |
| 10 | `OpenEcho(WxH)` | 0x0A | Resolution acknowledged (e.g., 2400x788) |

### Phase 2 — Phone Search (~600ms after init echo)

Triggered by the host's `wifiConnect` command.

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 11 | `Command(SCANNING_DEVICE)` | 0x08 cmd=1003 | BT scanning started |
| 12 | `PeerBluetoothAddress(MAC)` | 0x26 | Target device BT MAC for connection |

### Phase 3 — Phone Found & BT Connected (~2-3s after scan)

BT paging and RFCOMM connection to the phone.

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 13 | `StatusValue(4 / 0x4)` | 0x12 | BT page in progress (not always present) |
| 14 | `BoxSettings(phone: )` | 0x09 | Phone connecting — model empty initially |
| 15 | `PeerBluetoothAddress(MAC)` | 0x26 | Confirmed BT MAC |
| 16 | `Command(BT_CONNECTED)` | 0x08 cmd=1007 | **BT link established** |
| 17 | `BluetoothPairedList(...)` | 0x0A | Updated paired device list |
| 18 | `Command(AUTO_CONNECT_ENABLE)` | 0x08 cmd=1001 | Re-echo |
| 19 | `Command(DEVICE_FOUND)` | 0x08 cmd=1004 | Phone found confirmation |

**Alternate path (phone not reachable):**
- `UiHidePeerInfo` + `Command(DEVICE_NOT_FOUND)` (cmd=1005) instead of #14-19

### Phase 4 — CarPlay Session Setup (~1s after BT connect)

WiFi Direct handshake and CarPlay protocol negotiation.

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 20 | `BoxSettings(phone: )` | 0x09 | WiFi handshake starting (model still empty) |
| 21 | `Plugged(phoneType=CARPLAY, wifi=1)` | 0x03 | **CarPlay session active, wireless mode** |
| 22 | `Phase(7=connecting)` | 0x0E | AirPlay protocol negotiation in progress |

### Phase 5 — Streaming Active (~2s after Plugged)

Video and audio pipelines established.

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 23 | `Command(DISABLE_BLUETOOTH)` | 0x08 cmd=4 | BT radio off — WiFi Direct handles stream |
| 24 | `Command(REQUEST_VIDEO_FOCUS)` | 0x08 cmd=500 | Adapter ready to send video frames |
| 25 | `BoxSettings(phone: iPhone18,4)` | 0x09 | Full phone model now identified |
| 26 | `SessionToken(492B encrypted)` | 0x19 | Encrypted session token established |
| 27 | `Phase(8=streaming)` | 0x0E | **Video/audio streaming active** |

### Phase 6 — Post-Stream Steady State

Follows immediately after streaming begins.

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 28 | `UiHidePeerInfo` | 0x0A | Hide pairing/connection overlay |
| 29 | `Command(BT_DISCONNECTED)` | 0x08 cmd=1008 | BT radio off (WiFi-only from here) |
| 30 | `MediaData(type=DATA)` x6 | 0x0B | Media metadata burst (song/artist/album/cover) |

### Ongoing Session Messages (async, not ordered)

| Message | Type ID | Trigger |
|---------|---------|---------|
| `Command(REQUEST_HOST_UI)` | 0x08 cmd=600 | User pressed Home/CarPlay button |
| `Command(WIFI_DISCONNECTED)` | 0x08 cmd=1006 | WiFi status ping (informational, not session end) |
| `Command(WIFI_CONNECTED)` | 0x08 cmd=? | WiFi client connected (rare, seen once) |
| `MediaData(type=DATA)` | 0x0B | Song change, position tick, album art |
| `MediaData(type=NAVI_JSON)` | 0x0B | Navigation turn-by-turn data |
| `Unplugged` | 0x04 | **Session terminated by phone/adapter** |
| `Phase(0)` | 0x0E | Session terminated by adapter firmware |
| `Phase(13)` | 0x0E | **AirPlay session negotiation failed** — iPhone rejected viewAreasArray (e.g., `viewArea > display`). Causes Phase 7→13 reconnect loop. |

## Observations

1. **`StatusValue(4)` intermittent** — Only appeared in sessions after adapter reboot.
   May indicate a BT pairing state flag when re-establishing connection. Not present
   when phone was recently connected.

2. **Progressive phone identification** — `BoxSettings(phone:)` arrives three times:
   first empty (BT connecting), second empty (WiFi starting), third with full model
   string (`iPhone18,4`) at streaming start.

3. **BT is transient** — Bluetooth is only used for initial device discovery and
   RFCOMM handshake. Once WiFi Direct is established, `DISABLE_BLUETOOTH` and
   `BT_DISCONNECTED` are sent. The actual CarPlay stream runs entirely over WiFi.

4. **`WIFI_DISCONNECTED` is informational** — Does NOT indicate session end.
   Real disconnects come via `Unplugged` (0x04) or `Phase(0)`.

5. **Timing (typical wireless CarPlay)**:
   - Init echo: immediate
   - Phone scan: +600ms
   - BT connected: +2-3s
   - Plugged: +3-4s
   - Streaming: +5-7s from init start

6. **Phase 13 = negotiation failed** — Not an internal firmware phase (internal phases
   are 0-3, 100-106, 200). Phase 13 is only sent to the host over USB when AirPlay
   session negotiation fails. The adapter internally transitions through Phase 2
   (SCREEN_INFO / AirPlay control connected) then back to Phase 0 (disconnect),
   but reports 13 to the host instead of 0. The iPhone rejects the session gracefully
   (Dur=0s, Reason=noErr) when `viewAreasArray` constraints are violated — specifically
   when `viewArea` dimensions exceed `display` dimensions in the AirPlay SETUP request.
   This produces a Phase 7→13 reconnect loop with no streaming.
