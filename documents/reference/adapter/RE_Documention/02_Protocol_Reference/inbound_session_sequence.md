# Inbound Session Message Sequence

> **Status**: Non-definitive — still testing. Based on observed wireless CarPlay sessions
> with CPC200-CCPA firmware `2025.10.15.1127CAY` and iPhone 18,4.
>
> Captured 2026-02-19 via adb logcat `[RECV]` messages; re-verified 2026-04-20 via macOS
> CarLink client USB capture.
>
> **Note (Mar 2026):** Type IDs corrected to match protocol spec (usb_protocol.md dispatch table). Original capture used log-derived sequence numbers that did not correspond to USB message type IDs.
>
> **Note (Apr 2026):** Host-side init sequence block added below (was previously undocumented — only inbound/adapter-origin messages were captured in Feb 2026).

## Overview

After the host sends its initialization sequence, the adapter responds with an ordered
series of inbound messages that follow a consistent pattern across sessions. These messages
map to distinct adapter + phone states and can be used to track connection progress.

High-frequency data messages (VIDEO_DATA, AUDIO_DATA, HEARTBEAT_ECHO) are excluded —
this documents only control/state messages.

## Sequence

### Phase 0 — Host Init (host → adapter, before adapter responds)

Observed in macOS CarLink client session 2026-04-20 (FW `2025.10.15.1127CAY`, iPhone18,4). Timings are relative to USB attach.

| Δ | Message | Type ID | Content |
|---|---------|---------|---------|
| +0.124s | Heartbeat timer started (first fire at t=+2s) | 0xAA | — |
| +0.381s | `SENDFILE /tmp/screen_dpi` | 0x99 | 4B (DPI, e.g. `a0 00 00 00` = 160) |
| +0.510s | `OPEN 2400x960@60fps mode=2` | 0x01 | 28B = 7×uint32-LE: width, height, fps, format, packetMax, boxVersion, phoneMode (2=CarPlay) |
| +0.636s | `BOXSETTINGS (host JSON)` | 0x19 | ~280B JSON (CallQuality, DashboardInfo, GNSSCapability, box/bt/wifiName, androidAutoSize, etc.) |
| +0.764s | `SENDFILE /etc/RiddleBoxData/HU_VIEWAREA_INFO` | 0x99 | 24B (W,H,viewW,viewH,offX,offY) |
| +0.890s | `SENDFILE /etc/RiddleBoxData/HU_SAFEAREA_INFO` | 0x99 | 20B (w, h, originX, originY, drawUIOutsideSafeArea) |
| +2.364s | `SENDFILE /etc/android_work_mode` | 0x99 | 4B (LE; `01 00 00 00` = Android Auto enabled, `00 00 00 00` = disabled) |
| +2.523s | `CMD WIFI_ENABLE` | 0x08 | cmdId=1000 |
| +2.523s | `CMD WIFI_CONNECT` (if autoConn) | 0x08 | cmdId=1002 |
| +2.810s | `SENDFILE /tmp/hand_drive_mode` | 0x99 | 4B |
| +2.810s | `SENDFILE /etc/box_name` | 0x99 | variable (e.g. `CarLink`, 7B) |
| +2.937s | `SENDFILE /tmp/charge_mode` | 0x99 | 4B |
| +2.937s | `CMD USE_5G_WIFI` | 0x08 | cmdId=25 |
| +2.937s | `CMD USE_CAR_MIC` | 0x08 | cmdId=7 |
| +2.937s | `CMD USE_BOX_TRANS_AUDIO` | 0x08 | cmdId=23 |
| +3.108s | `SENDFILE /etc/airplay.conf` | 0x99 | 113B (OEM/car-specific AirPlay config) |

**Key observation:** the host pushes `/etc/android_work_mode` during init. Because the firmware resets this file's effective value on every USB disconnect (`fcn.00017940` in `ARMadb-driver`, see `06_Reference/firmware_internals.md` — "Both modes reset to 0 on disconnect"), the host MUST send it fresh on every connection for Android Auto to be selectable. Value is raw little-endian uint32 (0=disabled, 1=enabled).

### Phase 1 — Init Echo (adapter acknowledges host config)

Arrives immediately after the host's init sequence completes (~0ms).

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 1 | `Command(WIFI_ENABLE)` | 0x08 cmd=1000 | Adapter WiFi radio is on |
| 2 | `BluetoothDeviceName(carlink)` | 0x0D | Adapter BT name echo |
| 3 | `WifiDeviceName(carlink)` | 0x0E | Adapter WiFi SSID echo |
| 4 | `UiBringToForeground` | 0x26 | Tells HU to show pairing UI |
| 5 | `BluetoothPairedList(...)` | 0x12 | Previously paired devices (MAC + name) |
| 6 | `Command(AUTO_CONNECT_ENABLE)` | 0x08 cmd=1001 | Auto-connect feature enabled |
| 7 | `Command(MIC)` | 0x08 cmd=7 | Mic config echo |
| 8 | `SoftwareVersion(...)` | 0xCC | Firmware version string |
| 9 | `BoxSettings(adapter: YA)` | 0x19 | Adapter info JSON (uuid, DevList, hwVersion, etc.) |
| 10 | `OpenEcho(WxH)` | 0xFF | Resolution acknowledged (e.g., 2400x788) — CMD_ACK |

### Phase 2 — Phone Search (~600ms after init echo)

Triggered by the host's `wifiConnect` command.

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 11 | `Command(SCANNING_DEVICE)` | 0x08 cmd=1003 | BT scanning started |
| 12 | `PeerBluetoothAddress(MAC)` | 0x23 | Target device BT MAC for connection (Bluetooth_ConnectStart) |

### Phase 3 — Phone Found & BT Connected (~2-3s after scan)

BT paging and RFCOMM connection to the phone.

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 13 | `StatusValue(4 / 0x4)` | 0xBB | BT page in progress (not always present) |
| 14 | `BoxSettings(phone: )` | 0x19 | Phone connecting — model empty initially |
| 15 | `PeerBluetoothAddress(MAC)` | 0x24 | Confirmed BT MAC (Bluetooth_Connected) |
| 16 | `Command(BT_CONNECTED)` | 0x08 cmd=1007 | **BT link established** |
| 17 | `BluetoothPairedList(...)` | 0x12 | Updated paired device list |
| 18 | `Command(AUTO_CONNECT_ENABLE)` | 0x08 cmd=1001 | Re-echo |
| 19 | `Command(DEVICE_FOUND)` | 0x08 cmd=1004 | Phone found confirmation |

**Alternate path (phone not reachable):**
- `UiHidePeerInfo` (0x25) + `Command(DEVICE_NOT_FOUND)` (cmd=1005) instead of #14-19

### Phase 4 — CarPlay Session Setup (~1s after BT connect)

WiFi SoftAP association + AirPlay/RTSP negotiation.

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 20 | `BoxSettings(phone: )` | 0x19 | WiFi handshake starting (model still empty) |
| 21 | `Plugged(phoneType=CARPLAY, wifi=1)` | 0x02 | **CarPlay session active, wireless mode** |
| 22 | `Phase(7=connecting)` | 0x03 | AirPlay protocol negotiation in progress |

> **Note (iAP2 / MFi / AirPlay activity in the Phase 3→4 window):** between BT connect and
> `Plugged`, the adapter and iPhone run — over Bluetooth RFCOMM — the iAP2 identification
> (`0x1D00/0x1D01/0x1D02`), MFi authentication (`0xAA00–0xAA05`), WiFi credential handover
> (`0x5702/0x5703`) and device update messages (`0x4E0A/0B/0D/0E`); then, over WiFi, AirPlay
> `/pair-setup` ×3 + `/pair-verify` ×2. The long Phase-4 delay corresponds to iPhone-side trust
> (the "Use This Car" prompt) plus pair-setup. None of this appears on the USB control channel.
> See `device_identification.md` and `carplay_handshake.md` for the full ordered handshake.

### Phase 5 — Streaming Active (~2s after Plugged)

Video and audio pipelines established.

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 23 | `Command(DISABLE_BLUETOOTH)` | 0x08 cmd=4 | BT radio off — WiFi Direct handles stream |
| 24 | `Command(REQUEST_VIDEO_FOCUS)` | 0x08 cmd=500 | Adapter ready to send video frames |
| 25 | `BoxSettings(phone: iPhone18,4)` | 0x19 | Full phone model now identified |
| 26 | `SessionToken(492B encrypted)` | 0xA3 | Encrypted session token established |
| 27 | `Phase(8=streaming)` | 0x03 | **Video/audio streaming active** |

### Phase 6 — Post-Stream Steady State

Follows immediately after streaming begins.

| # | Message | Type ID | Meaning |
|---|---------|---------|---------|
| 28 | `UiHidePeerInfo` | 0x25 | Hide pairing/connection overlay |
| 29 | `Command(BT_DISCONNECTED)` | 0x08 cmd=1008 | BT radio off (WiFi-only from here) |
| 30 | `MediaData(type=DATA)` x6 | 0x2A | Media metadata burst (song/artist/album/cover) |

### Ongoing Session Messages (async, not ordered)

| Message | Type ID | Trigger |
|---------|---------|---------|
| `Command(REQUEST_HOST_UI)` | 0x08 cmd=600 | User pressed Home/CarPlay button |
| `Command(WIFI_DISCONNECTED)` | 0x08 cmd=1006 | WiFi status ping (informational, not session end) |
| `Command(WIFI_CONNECTED)` | 0x08 cmd=? | WiFi client connected (rare, seen once) |
| `MediaData(type=DATA)` | 0x2A | Song change, position tick, album art |
| `MediaData(type=NAVI_JSON)` | 0x2A | Navigation turn-by-turn data |
| `Unplugged` | 0x04 | **Session terminated by phone/adapter** |
| `Phase(0)` | 0x03 | Session terminated by adapter firmware |
| `Phase(13)` | 0x03 | **AirPlay session negotiation failed** — iPhone rejected viewAreasArray (e.g., `viewArea > display`). Causes Phase 7→13 reconnect loop. |

## Observations

1. **`StatusValue(4)` intermittent** — Only appeared in sessions after adapter reboot.
   May indicate a BT pairing state flag when re-establishing connection. Not present
   when phone was recently connected.

2. **Progressive phone identification** — `BoxSettings(phone:)` arrives three times:
   first empty (BT connecting), second empty (WiFi starting), third with full model
   string (`iPhone18,4`) at streaming start.

3. **BT is transient** — Bluetooth is only used for initial device discovery and
   RFCOMM handshake. Once the iPhone has joined the adapter's WiFi SoftAP, `DISABLE_BLUETOOTH`
   and `BT_DISCONNECTED` are sent. The actual CarPlay stream runs entirely over WiFi.

4. **`WIFI_DISCONNECTED` is informational** — Does NOT indicate session end.
   Real disconnects come via `Unplugged` (0x04) or `Phase(0)`.

5. **Timing (typical wireless CarPlay)**:
   - Init echo: immediate
   - Phone scan: +600ms
   - BT connected: +2-3s
   - Plugged: +3-4s
   - Streaming: +5-7s from init start

   **First-pair / manual-accept flows take much longer.** Observed 2026-04-20 macOS session: ready (post-init) at +3.1s, `BoxSettings(phone:MDLinkType=CarPlay)` not received until +75.1s, `Phase(8=streaming)` at +78.3s. The delay is entirely on the iPhone side (user taps "Use This Car" + trust prompt). The +5-7s figure only applies to auto-connect-enabled re-pairs. Host state machines should not treat a 60–90s gap between Init Echo and Plugged as a timeout.

6. **Phase 13 = negotiation failed** — Not an internal firmware phase (internal phases
   are 0-3, 100-106, 200). Phase 13 is only sent to the host over USB when AirPlay
   session negotiation fails. The adapter internally transitions through Phase 2
   (SCREEN_INFO / AirPlay control connected) then back to Phase 0 (disconnect),
   but reports 13 to the host instead of 0. The iPhone rejects the session gracefully
   (Dur=0s, Reason=noErr) when `viewAreasArray` constraints are violated — specifically
   when `viewArea` dimensions exceed `display` dimensions in the AirPlay SETUP request.
   This produces a Phase 7→13 reconnect loop with no streaming.
