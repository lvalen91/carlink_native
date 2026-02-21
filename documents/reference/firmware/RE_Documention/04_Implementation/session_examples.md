# Captured Session Examples

**Status:** VERIFIED via USB capture analysis
**Source:** Pi-Carplay session captures (Jan 2026)
**Last Updated:** 2026-01-22

---

## Overview

This document contains real captured session examples showing the exact packet sequences exchanged between a host application and the CPC200-CCPA adapter during CarPlay and Android Auto sessions.

---

## CarPlay Session Example

### Session Info

| Property | Value |
|----------|-------|
| Capture ID | `26JAN19_02-47-46` |
| Duration | 237.8 seconds |
| Device | iPhone 18,4 (iOS 23D5103d) |
| Resolution | 1280×720 (main) + 1200×500 (navigation) |
| Transport | Wired USB → WiFi handoff |

### Initialization Phase (0-1 seconds)

| Seq | Time | Dir | Type | Name | Details |
|-----|------|-----|------|------|---------|
| 1 | 153ms | OUT | 153 | SendFile | `/tmp/screen_dpi` |
| 2 | 278ms | OUT | 1 | Open | 1280×720 @ 30fps, H264 |
| 3 | 403ms | OUT | 153 | SendFile | `/tmp/night_mode` |
| 4 | 405ms | IN | 8 | Command | cmd=1000 (SupportWifi) |
| 5 | 406ms | IN | 13 | BluetoothDeviceName | "test_CCPA" |
| 6 | 406ms | IN | 14 | WifiDeviceName | "test_CCPA" |
| 7 | 406ms | IN | 38 | UiBringToForeground | - |
| 8 | 406ms | IN | 18 | BluetoothPairedList | JSON list |
| 9 | 407ms | IN | 8 | Command | cmd=1001 (btListReady) |
| 10 | 407ms | IN | 8 | Command | cmd=7 (UseCarMic) |

### Configuration Exchange (0.5-2 seconds)

| Seq | Time | Dir | Type | Name | Details |
|-----|------|-----|------|------|---------|
| 11 | 517ms | OUT | 153 | SendFile | `/tmp/hand_drive_mode` |
| 12 | 519ms | IN | 204 | SoftwareVersion | "2025.10.15.1127CAY" |
| 13 | 520ms | IN | 25 | BoxSettings | Adapter config JSON |
| 14 | 521ms | IN | 1 | Open | Adapter capabilities |
| 15 | 547ms | OUT | 8 | Command | cmd=12 (startVideoEncoderV2) |
| 16-18 | 643-892ms | OUT | 153 | SendFile | Additional config files |
| 19 | 1015ms | OUT | 8 | Command | cmd=25 (wifi5g) |
| 20 | 1141ms | OUT | 25 | BoxSettings | Host config JSON |

### WiFi Connection Sequence (1-8 seconds)

| Seq | Time | Dir | Type | Name | Details |
|-----|------|-----|------|------|---------|
| 21 | 1268ms | OUT | 25 | BoxSettings | naviScreenInfo config |
| 22 | 1395ms | OUT | 8 | Command | cmd=1000 (wifiListGetCmd) |
| 23 | 1522ms | OUT | 8 | Command | cmd=7 (mic car) |
| 24 | 1648ms | OUT | 8 | Command | cmd=23 (audioTransfer on) |
| 25 | 2380ms | OUT | 8 | Command | cmd=1002 (wifiConnect) |
| 26 | 2411ms | IN | 8 | Command | cmd=1003 (wifiConnectSuccess) |
| 27 | 2500ms | IN | 35 | PeerBluetoothAddress | Phone BT connecting |
| 28 | 3782ms | OUT | 170 | HeartBeat | Keep-alive |
| 29 | 4270ms | IN | 37 | UiHidePeerInfo | - |
| 30 | 4332ms | IN | 8 | Command | cmd=1005 (wifiReady) |

### Phone Connection (7-8 seconds)

| Seq | Time | Dir | Type | Name | Details |
|-----|------|-----|------|------|---------|
| 43 | 7524ms | IN | 44 | NaviVideoData | First nav frame (235 bytes) |
| 44 | 7788ms | OUT | 170 | HeartBeat | Keep-alive |
| 45 | 8064ms | IN | 25 | BoxSettings | Phone info JSON (below) |
| 46 | 8065ms | IN | 163 | SessionToken | Encrypted blob (508 bytes) |
| 47 | 8066ms | IN | 3 | Phase | phase=8 (streaming ready) |

**BoxSettings Phone Info (seq 45):**
```json
{
  "MDLinkType": "CarPlay",
  "MDModel": "iPhone18,4",
  "MDOSVersion": "23D5103d",
  "MDLinkVersion": "935.4.1",
  "btMacAddr": "64:31:35:8C:29:69",
  "btName": "Luis",
  "cpuTemp": 49
}
```

### Active Streaming Phase

| Event | Time | Type | Details |
|-------|------|------|---------|
| Video frames | 8069ms+ | 6 | H.264 NAL units (main video) |
| Nav video | 7524ms+ | 44 | Navigation video (1200×500) |
| HeartBeat | Every ~2s | 170 | Keep-alive (34 total) |
| Touch events | User input | 5 | Touch coordinates |
| Audio data | As needed | 7 | PCM audio streams |

### Navigation Video Activation

Navigation video is activated by sending `naviScreenInfo` in BoxSettings. `AdvancedFeatures=1` is NOT required when `naviScreenInfo` is provided.

| Seq | Time | Dir | Type | Details |
|-----|------|-----|------|---------|
| - | ~7500ms | IN | 8 | cmd=508 (adapter requests nav focus) |
| - | ~7500ms | OUT | 8 | cmd=508 (host echoes nav focus — recommended but inconclusive if required) |
| 43 | 7524ms | IN | 44 | First navigation video frame |

**Note:** The 508 exchange was observed in captures, but testing could not conclusively isolate it as a requirement. The confirmed trigger is `naviScreenInfo` in BoxSettings.

### Session Statistics

| Metric | Value |
|--------|-------|
| Total packets | 3,333 |
| HeartBeat (OUT) | 34 |
| Touch events (OUT) | 847 |
| Video frames (IN) | ~2,400 |
| Commands (IN) | 15 |
| Commands (OUT) | 12 |
| Audio out packets | 4,760 |
| Audio in (mic) packets | 517 |
| Audio out data | 17.09 MB |
| Audio in data | 4.02 MB |

---

## Android Auto Session Example

### Session Info

| Property | Value |
|----------|-------|
| Capture ID | `26JAN19_03-06-43` |
| Duration | 71.2 seconds |
| Device | Google Pixel 10 |
| Resolution | 1280×720 @ 30fps |
| Transport | Wired USB (AOA protocol) |

### Initialization Phase (0-1 seconds)

| Seq | Time | Dir | Type | Name | Details |
|-----|------|-----|------|------|---------|
| 1 | 144ms | OUT | 153 | SendFile | `/tmp/screen_dpi` |
| 2 | 271ms | OUT | 1 | Open | 1280×720 @ 30fps, H264 |
| 3 | 396ms | OUT | 153 | SendFile | `/tmp/night_mode` |
| 4 | 398ms | IN | 8 | Command | cmd=1000 (wifiListGetCmd) |
| 5 | 399ms | IN | 13 | BluetoothDeviceName | "test_CCPA" |
| 6 | 399ms | IN | 14 | WifiDeviceName | "test_CCPA" |
| 7 | 399ms | IN | 38 | UiBringToForeground | - |
| 8 | 399ms | IN | 18 | BluetoothPairedList | JSON list |
| 9 | 400ms | IN | 8 | Command | cmd=1001 (btListReady) |
| 10 | 400ms | IN | 8 | Command | cmd=7 (UseCarMic) |

### Configuration Exchange (0.5-2.5 seconds)

| Seq | Time | Dir | Type | Name | Details |
|-----|------|-----|------|------|---------|
| 11 | 517ms | OUT | 153 | SendFile | `/tmp/hand_drive_mode` |
| 12 | 518ms | IN | 204 | SoftwareVersion | "2025.10.15.1127CAY" |
| 13 | 519ms | IN | 25 | BoxSettings | Adapter config (481 bytes) |
| 14 | 520ms | IN | 1 | Open | Adapter capabilities |
| 15 | 546ms | OUT | 8 | Command | cmd=12 (startVideoEncoderV2) |
| 16-18 | 642-891ms | OUT | 153 | SendFile | Additional config |
| 19 | 1014ms | OUT | 8 | Command | cmd=25 (wifi5g) |
| 20 | 1140ms | OUT | 25 | BoxSettings | Host config JSON |
| 21 | 1267ms | OUT | 25 | BoxSettings | naviScreenInfo |
| 22 | 1394ms | OUT | 8 | Command | cmd=1000 (wifiListGetCmd) |
| 23 | 1521ms | OUT | 8 | Command | cmd=7 (mic car) |
| 24 | 1647ms | OUT | 8 | Command | cmd=23 (audioTransfer on) |
| 25 | 2378ms | OUT | 8 | Command | cmd=1002 (wifiConnect) |
| 26 | 2410ms | IN | 8 | Command | cmd=1003 (wifiConnectSuccess) |

### Waiting for Phone (2-37 seconds)

During this phase, the host sends HeartBeat every ~2 seconds while waiting for phone connection:

| Seq | Time | Dir | Type | Details |
|-----|------|-----|------|---------|
| 27 | 2498ms | IN | 35 | PeerBluetoothAddress |
| 28 | 3779ms | OUT | 170 | HeartBeat |
| 29 | 4269ms | IN | 37 | UiHidePeerInfo |
| 30 | 4331ms | IN | 8 | cmd=1005 (wifiReady/DeviceNotFound) |
| 31-47 | 5782-35839ms | OUT | 170 | HeartBeat (every ~2s) |
| 37 | 16775ms | OUT | 8 | cmd=1012 (wifiBtCommand) |

### Phone Connection (37-40 seconds)

| Seq | Time | Dir | Type | Name | Details |
|-----|------|-----|------|------|---------|
| 48 | 37300ms | IN | 25 | BoxSettings | MDLinkType=AndroidAuto |
| 49 | 37302ms | IN | 2 | Plugged | phoneType=5 (AndroidAuto) |
| 50 | 37840ms | OUT | 170 | HeartBeat | - |
| 51 | 38648ms | IN | 3 | Phase | phase=7 |
| 52 | 38904ms | IN | 8 | Command | cmd=505 (videoReleaseNotify) |
| 53 | 38988ms | IN | 8 | Command | cmd=500 (videoFocusRequest) |
| 54 | 39318ms | IN | 25 | BoxSettings | Phone info (below) |
| 55 | 39319ms | IN | 163 | SessionToken | Encrypted blob (508 bytes) |
| 56 | 39320ms | IN | 3 | Phase | phase=8 (streaming ready) |

**BoxSettings Phone Info (seq 54):**
```json
{
  "MDLinkType": "AndroidAuto",
  "MDModel": "Google Pixel 10",
  "MDOSVersion": "",
  "MDLinkVersion": "1.7",
  "btMacAddr": "B0:D5:FB:A3:7E:AA",
  "btName": "Pixel 10",
  "cpuTemp": 47
}
```

**Note:** `MDLinkVersion` for Android Auto reflects the AA protocol version (1.7). The `btMacAddr` field contains the phone's Bluetooth MAC address.

### Active Streaming (39-70 seconds)

| Seq | Time | Dir | Type | Details |
|-----|------|-----|------|---------|
| 70 | 39844ms | OUT | 170 | HeartBeat |
| 81 | 40409ms | IN | 42 | MediaData (59 bytes) |
| 126+ | 41842ms+ | IN | 42 | MediaData packets |
| 306+ | 47525ms+ | OUT | 5 | Touch events (user interaction) |

### Video Focus Commands (Android Auto Specific)

| Seq | Time | Dir | Command | Meaning |
|-----|------|-----|---------|---------|
| 52 | 38904ms | IN | 505 | ReleaseAudioFocus - adapter releasing |
| 53 | 38988ms | IN | 500 | RequestVideoFocus - adapter requesting |
| 763 | 69675ms | IN | 19 | micFocusRequest |

**Adapter TTY correlation:**
```
[D] _SendPhoneCommandToCar: ReleaseAudioFocus(505)
[D] _SendPhoneCommandToCar: RequestVideoFocus(500)
```

### Disconnection (70-71 seconds)

| Seq | Time | Dir | Type | Name | Details |
|-----|------|-----|------|------|---------|
| 765 | 70885ms | IN | 4 | Unplugged | Phone disconnected |
| 766 | 70886ms | OUT | 15 | DisconnectPhone | Host acknowledges |
| 767 | 70887ms | OUT | 21 | CloseDongle | Shutdown adapter |

### Session Statistics

| Metric | Value |
|--------|-------|
| Total packets | 105 |
| HeartBeat (OUT) | 34 |
| Touch events (OUT) | 23 |
| MediaData (IN) | 6 |
| Commands (IN) | 8 |
| Commands (OUT) | 7 |
| Audio out packets | 2 |
| Audio in (mic) packets | 0 |
| Audio out data | 58 bytes |
| Audio in data | 0 bytes |

**Note:** Android Auto audio is handled internally by the OpenAuto SDK on the adapter. Only control packets traverse USB.

---

## Android Auto Wireless WiFi Negotiation (RFCOMM)

*Verified via TTY log capture (Jan 2026)*

Before Android Auto can stream over WiFi, the phone and adapter negotiate WiFi credentials over Bluetooth RFCOMM. This happens via the `BoxRFCOMMService` in the OpenAuto SDK.

### RFCOMM Message Types

| Type | Direction | Name | Description |
|------|-----------|------|-------------|
| 1 | A→P | WifiVersionRequest | Request phone's WiFi version support |
| 2 | P→A | WifiInfoRequest | Phone requests adapter WiFi info |
| 3 | A→P | WifiInfo | Adapter sends WiFi credentials |
| 4 | A→P | InitInfo | Initial connection parameters |
| 5 | P→A | WifiVersionResponse | Phone's WiFi version capabilities |
| 6 | P→A | WifiConnectStatus | WiFi connection result |
| 7 | P→A | WifiStartResponse | WiFi streaming ready |

### WiFi Negotiation Flow (Wireless Android Auto)

```
Adapter                              Phone
   │                                    │
   ├──── Type 4 (InitInfo) ────────────>│
   │<─── Type 5 (WifiVersionResponse) ──┤
   ├──── Type 1 (WifiVersionRequest) ───>│
   │<─── Type 2 (WifiInfoRequest) ──────┤
   ├──── Type 3 (WifiInfo) ─────────────>│
   │    (SSID, password, IP, port)       │
   │<─── Type 7 (WifiStartResponse) ────┤
   │        ... WiFi connects ...        │
   │<─── Type 6 (WifiConnectStatus=0) ──┤
   │                                    │
   ▼                                    ▼
  WiFi streaming begins (TCP 54321)
```

### WifiInfo Message Content (Type 3)

The adapter sends these WiFi credentials to the phone:

| Field | Example Value | Description |
|-------|---------------|-------------|
| `channelfreq` | 5180 | WiFi channel frequency (MHz) |
| `channeltype` | 0 | Channel type |
| `ip` | 192.168.43.1 | Adapter's IP address |
| `port` | 54321 | TCP port for AA streaming |
| `ssid` | pi-carplay | WiFi network name |
| `passwd` | 12345678 | WiFi password |
| `bssid` | 00:E0:4C:98:0A:6C | Adapter's WiFi MAC |
| `securityMode` | 8 | WPA2-PSK |

**TTY Log Example:**
```
[BoxRFCOMMService] initWifiInfo, channelfreq: 5180, channeltype: 0,
  ip: 192.168.43.1, port: 54321, ssid: pi-carplay, passwd: 12345678,
  bssid: 00:E0:4C:98:0A:6C, securityMode: 8
[BoxRFCOMMService] sendRFCOMMData type: 4
[BoxRFCOMMService] recv msg size: 22, type: 5
[BoxRFCOMMService] onRecvWifiVersionResponse: 0
[BoxRFCOMMService] sendRFCOMMData type: 1
[BoxRFCOMMService] recv msg size: 0, type: 2
[BoxRFCOMMService] sendRFCOMMData type: 3
[BoxRFCOMMService] recv msg size: 2, type: 7
[BoxRFCOMMService] recv msg size: 2, type: 6
[BoxRFCOMMService] onRecvWifiConnectStatus: 0
[App] Wireless Device connected.
```

---

## Command Reference

For complete command ID reference, see `02_Protocol_Reference/command_ids.md`. For per-command binary evidence, see `02_Protocol_Reference/command_details.md`.

---

## Type 163 (SessionToken) Analysis

SessionToken (type 0xA3) is AES-128-CBC encrypted. Key: `W2EC1X1NbZ58TXtn`, IV = first 16 bytes of Base64-decoded payload. See `02_Protocol_Reference/usb_protocol.md` > SessionToken for full decryption analysis and field descriptions.

Both CarPlay and Android Auto sessions include a single Type 163 packet:

| Session | Seq | Timestamp | Size |
|---------|-----|-----------|------|
| CarPlay | 46 | 8065ms | 508 bytes |
| Android Auto | 55 | 39319ms | 444 bytes |

**Timing:** Always sent immediately after BoxSettings (phone info) and before Phase 8.

---

## Key Differences: CarPlay vs Android Auto

| Aspect | CarPlay | Android Auto |
|--------|---------|--------------|
| Phone plugged type | 1 (iPhone) | 5 (AndroidAuto) |
| Auth protocol | MFi + iAP2 | AOA + SSL handshake (v1.7) |
| SSL encryption | N/A | Varies: `(NONE)` or `ECDHE-RSA-AES128-GCM-SHA256` |
| Video focus cmd | Not observed | 500/505 |
| MediaData (0x2A) | Not observed | Present (6 packets) |
| Navigation video | Type 44 / TCP 111 | **Not supported** |
| naviScreenInfo sent | Yes | No |
| Video margins | N/A | 0×0 or 80×240 |
| Phase values | 8 (streaming) | 7→8 transition |
| Connection time | ~8 seconds | ~37 seconds (waited) |

**Navigation Screen Support:**

- **CarPlay:** Host sends `naviScreenInfo` in BoxSettings → adapter negotiates second video stream
- **Android Auto:** Host does NOT send `naviScreenInfo` → no second video stream protocol exists

---

## Adapter TTY Log Correlation

### CarPlay Connection Log

```
[D] Android Auto Device Plug In!!!               # Despite name, handles CarPlay too
[D] 有线 CarPlay 进入                              # "Wired CarPlay entered"
[D] PhoneLinkInfo: {"MDLinkType":"CarPlay",...}
[D] _SendPhoneCommandToCar: SupportWifi(1000)
[D] _SendPhoneCommandToCar: SupportAutoConnect(1001)
[D] _SendPhoneCommandToCar: UseCarMic(7)
[D] recv CarPlay size info:1280x720
[D] 连接耗时 4 秒                                   # "Connection took 4 seconds"
```

### Android Auto Connection Log (Unauthenticated - Early Captures)

```
[D] Android Auto Device Plug In!!!
[D] 有线 AndroidAuto 进入                          # "Wired Android Auto entered"
[D] StartPhoneLink linkType: 5, transportType: 1
[D] PhoneLinkInfo: {"MDLinkType":"AndroidAuto",...}
[OpenAuto] version response, version: 1.7, status: 0
[OpenAuto] [Configuration] set margin w = 80       # Video margins
[OpenAuto] [Configuration] set margin h = 240
[D] [BoxVideoOutput] maxVideoBitRate = 5000 Kbps, bEnableTimestamp_ = 1
[OpenAuto] Begin handshake.
Connected2 with (NONE) encryption                  # No certificate auth
No certificates.
[AaSdk] [SSLWrapper] SSL_do_handshake res = -1, errorCode = 2  # Needs more data
[AaSdk] [SSLWrapper] SSL_do_handshake res = -1, errorCode = 2  # Retry
[AaSdk] [SSLWrapper] SSL_do_handshake res = 1, errorCode = 0   # Success
[OpenAuto] Handshake, size: 2348
[OpenAuto] Handshake, size: 51
[D] _SendPhoneCommandToCar: ReleaseAudioFocus(505)
[D] _SendPhoneCommandToCar: RequestVideoFocus(500)
[D] AndroidAuto iWidth: 1280, iHeight: 720
```

### Android Auto Connection Log (Authenticated - Jan 22, 2026 Pixel 10)

```
[D] Android Auto Device Plug In!!!
[D] 有线 AndroidAuto 进入
[D] StartPhoneLink linkType: 5, transportType: 1
[OpenAuto] Begin handshake.
Cryptor readHandshakeBuffer size = 1191
[AaSdk] [ControlServiceChannel] recv message SSL_HANDSHAKE
[I] Handshake, size: 51
Connected2 with ECDHE-RSA-AES128-GCM-SHA256 encryption
Server certificates:
Subject: /C=US/ST=California/L=Mountain View/O=CarService
Issuer: /C=US/ST=California/L=Mountain View/O=Google Automotive Link
[AaSdk] [SSLWrapper] SSL_do_handshake res = 1, errorCode = 0
[I] Auth completed.
[AaSdk] [ControlServiceChannel] recv message SERVICE_DISCOVERY_REQUEST
[I] Discovery request, device name: Android, brand: Google Pixel 10
[D] SaveIcon: /tmp/aa_32x32.png, length: 152
[D] SaveIcon: /tmp/aa_64x64.png, length: 240
[D] SaveIcon: /tmp/aa_128x128.png, length: 411
[OpenAuto] [AudioService] fill features, channel: MEDIA_AUDIO
[OpenAuto] [AudioService] fill features, channel: SPEECH_AUDIO
[OpenAuto] [AudioService] fill features, channel: SYSTEM_AUDIO
[OpenAuto] [SensorService] fill features.
[OpenAuto] [VideoService] fill features.
[OpenAuto] [BluetoothService] fill features
[OpenAuto] [BluetoothService] sending local adapter address: 48:8F:4C:E0:AC:2B
[D] [MediaStatusService] fill features
[OpenAuto] [InputService] fill features.
[OpenAuto] [InputService] inputChannel touch screen set w = 1920, h = 1080
[D] [GenericNotificationService] fill features
[D] _SendPhoneCommandToCar: ReleaseAudioFocus(505)
[D] _SendPhoneCommandToCar: RequestVideoFocus(500)
[D] recv AndroidAuto size info:1920 x 1080
[D] 有线 AndroidAuto 连接成功                       # "Wired Android Auto connected successfully"
[D] 连接耗时 3 秒                                   # "Connection took 3 seconds"
```

**Google Automotive Link CA Certificate (from Pixel 10):**

| Property | Value |
|----------|-------|
| Issuer | C=US, ST=California, L=Mountain View, O=Google Automotive Link |
| Subject | C=US, ST=California, L=Mountain View, O=CarService |
| Algorithm | RSA 2048-bit |
| Validity | Jul 4, 2014 - Apr 29, 2026 |
| Cipher Suite | ECDHE-RSA-AES128-GCM-SHA256 |

**SSL Handshake Error Codes:**

| Code | Constant | Meaning |
|------|----------|---------|
| 2 | SSL_ERROR_WANT_READ | Needs more data from peer |
| 3 | SSL_ERROR_WANT_WRITE | Output buffer full |
| 0 | SSL_ERROR_NONE | Success (when res=1) |

**Video Margin Settings:**

The `margin w` and `margin h` values configure letterboxing for aspect ratio adjustment. Values vary by session (0×0 or 80×240 observed).

### Android Auto Service Discovery (NEW Jan 2026)

After SSL handshake, the phone sends SERVICE_DISCOVERY_REQUEST. The adapter responds with available services:

| Service | Description |
|---------|-------------|
| **AudioInputService** | Microphone input from host |
| **AudioService (MEDIA_AUDIO)** | 48000Hz, 16-bit, 2ch stereo media |
| **AudioService (SPEECH_AUDIO)** | 16000Hz, 16-bit, 1ch mono voice |
| **AudioService (SYSTEM_AUDIO)** | 16000Hz, 16-bit, 1ch mono system |
| **SensorService** | Vehicle sensors (night mode, GPS) |
| **VideoService** | H.264 video output |
| **BluetoothService** | BT pairing coordination |
| **MediaStatusService** | Playback state, metadata updates |
| **InputService** | Touch input (1920x1080) |
| **GenericNotificationService** | System notifications |

### MediaStatusService Updates (NEW Jan 2026)

The adapter receives media state updates from Android Auto:

```
[D] [MediaStatusService] Playback update, state: PAUSED, source: YouTube Music, progress: 103
[D] [MediaStatusService] Metadata update, track_name: Tom's Diner,
    artist_name: AnnenMayKantereit & Giant Rooks, album_name: Tom's Diner,
    album_art size: 59244, playlist: , duration_seconds: 269, rating: 0
```

These are internal to the adapter - the host receives simplified PhoneLinkInfo via USB.

---

## References

- Captures: `/Users/zeno/.pi-carplay/usb-logs/`, `/Users/zeno/.pi-carplay/usb-capture/`
- Adapter logs: `/Users/zeno/.pi-carplay/adapter-logs/`
- Protocol: `02_Protocol_Reference/usb_protocol.md`
- Commands: `02_Protocol_Reference/command_ids.md`
- Audio: `02_Protocol_Reference/audio_protocol.md`
- Video: `02_Protocol_Reference/video_protocol.md`
- Host guide: `04_Implementation/host_app_guide.md`

**Capture Sessions:**
- Jan 2026 CarPlay (iPhone 18,4 - wireless): `picarplay-capture_26JAN22_02-45-40`
- Jan 2026 Android Auto (Pixel 10 - wired): Same adapter log session
- Adapter TTY log: `adapter-ttylog_26JAN22_02-45-44.log`
