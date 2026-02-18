# CPC200-CCPA USB Protocol Reference

**Status:** VERIFIED against 25+ capture sessions + firmware binary analysis
**Consolidated from:** All research projects (GM_research, carlink_native, pi-carplay)
**Firmware Version:** 2025.10.15.1127 (binary analysis reference version)
**Last Updated:** 2026-02-18 (Corrected DashboardInfo gating, GNSSCapability bitmask, ARMadb-driver forwarding path, file write path, 7 location sub-types)

---

## Protocol Header Structure

All USB messages use a common 16-byte header:

```
+------------------+------------------+------------------+------------------+
|   Magic (4B)     |   Length (4B)    |   Type (4B)      | Type Check (4B)  |
+------------------+------------------+------------------+------------------+
|                              Payload (N bytes)                           |
+--------------------------------------------------------------------------+
```

| Field | Offset | Size | Description |
|-------|--------|------|-------------|
| **magic** | 0 | 4 | `0x55AA55AA` (little-endian) |
| **length** | 4 | 4 | Payload size in bytes (LE) |
| **type** | 8 | 4 | Message type ID (LE) |
| **type_check** | 12 | 4 | `type XOR 0xFFFFFFFF` (validation) |
| **payload** | 16 | N | Message-specific data |

**Validation Rules:**
- Magic must equal `0x55AA55AA`
- Type check must equal `type ^ 0xFFFFFFFF`
- Length must be ≤ 1048576 bytes
- Total message size = 16 + payload_length

---

## Complete Message Type Reference

### Session Management

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 1 | 0x01 | Open | OUT | 28 | Initialize session with display params |
| 2 | 0x02 | Plugged | IN | 8 | Phone connected notification |
| 3 | 0x03 | Phase | IN | 4 | Connection phase update |
| 4 | 0x04 | Unplugged | IN | 0 | Phone disconnected |
| 15 | 0x0F | DisconnectPhone | OUT | 0 | Force disconnect phone |
| 21 | 0x15 | CloseDongle | OUT | 0 | Shutdown adapter |
| 170 | 0xAA | HeartBeat | OUT | 0 | Keep-alive (every 2s) |

### Data Streams

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 5 | 0x05 | Touch | OUT | 16 | Single touch input |
| 6 | 0x06 | VideoData | IN | Variable | H.264 video frame (36-byte header) |
| 7 | 0x07 | AudioData | BOTH | Variable | Audio data or commands (see below) |
| 23 | 0x17 | MultiTouch | OUT | Variable | Multi-touch (1-10 points) |
| 44 | 0x2C | NaviVideoData | IN | Variable | Navigation video (36-byte header, iOS 13+) |

**Video Header Sizes:**
- VideoData (0x06): 36 bytes total (16 USB + 20 video-specific)
- NaviVideoData (0x2C): 36 bytes total (16 USB + 20 video-specific) - **same structure as main video**

**⚠️ Video Processing Note:** VideoData contains **live UI projection**, not traditional video. The adapter forwards frames without buffering or policy decisions. Host apps must:
- Drop late frames (>30-40ms) to prevent decoder poisoning - a balance is needed; too long causes visible corruption, too short drops valid frames
- Keep buffers shallow (~150ms jitter absorption)
- Reset decoder on corruption (don't wait for self-healing)

See `video_protocol.md` for detailed header structures and host implementation guidance.

**AudioData (0x07) Commands:** When payload is 13 bytes, contains audio command (not PCM data):
```
Payload (13 bytes):
[decodeType:4][volume:4][audioType:4][command:1]
```

| AudioCmd | Name | Direction | Host Action |
|----------|------|-----------|-------------|
| 4 | PHONECALL_START | Phone→Host | Start microphone capture |
| 5 | PHONECALL_STOP | Phone→Host | Stop microphone capture |
| 8 | SIRI_START | Phone→Host | Start microphone capture |
| 9 | SIRI_STOP | Phone→Host | Stop microphone capture |
| 6/7 | NAVI_START/STOP | Phone→Host | Duck/restore media audio |

**IMPORTANT:** Siri and phone call events are received via AudioData (0x07), NOT Command (0x08).
See `command_ids.md` for complete flow documentation and `../03_Audio_Processing/audio_formats.md` for audio format details.

**Audio Formats (decodeType):**
| decodeType | Sample Rate | Channels | Use Case | Status |
|------------|-------------|----------|----------|--------|
| 2 | 44100 Hz | Stereo | **Dual-purpose:** Commands (13-byte payload) OR 44.1kHz media audio | VERIFIED |
| 3 | 8000 Hz | Mono | Phone call (narrow-band) | LEGACY - never observed in captures; firmware code exists but modern CarPlay negotiates 16kHz |
| 4 | 48000 Hz | Stereo | Media HD / CarPlay | VERIFIED |
| 5 | 16000 Hz | Mono | Siri / Mic input | VERIFIED |

**Note on decode_type=2:** Behavior depends on payload size. 13-byte payloads are audio commands; larger payloads are 44.1kHz PCM audio data.

**Note on decode_type=3:** Firmware still lists 8kHz as an option, but no manual configuration resulted in observed 8kHz audio during testing. May be legacy code with functionality effectively removed. Modern iPhones default to wideband (16kHz).

### Control Commands

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 8 | 0x08 | Command | BOTH | 4 | Control commands (see below) |
| 9 | 0x09 | LogoType | OUT | 4 | Set UI branding |
| 11 | 0x0B | CarPlayModeChange | IN | Variable | CarPlay mode state change |
| 12 | 0x0C | Frame/BluetoothPIN | **DUAL** | Variable | **See Dual-Purpose Types below** |
| 16 | 0x10 | AirPlayModeChange | IN | Variable | AirPlay mode state change |
| 22 | 0x16 | CameraFrame | IN | Variable | Camera/reverse video input (Binary: CMD_CAMERA_FRAME) |

**⚠️ CORRECTION (Feb 2026):** Type 0x16 was previously documented as "AudioTransfer". Binary analysis confirms it is `CMD_CAMERA_FRAME` (camera input). Audio transfer is Command ID 22 sent via type 0x08, not a separate message type.

### Dual-Purpose Message Types (Binary Verified Feb 2026)

Some message types have different meanings depending on direction. This was verified through firmware binary disassembly of `ARMadb-driver.unpacked`.

| Type | Hex | OUT (Host→Adapter) | IN (Adapter→Host) |
|------|-----|--------------------|--------------------|
| 12 | 0x0C | **Frame** - Request IDR keyframe (0-byte payload) | **BluetoothPIN** - Pairing PIN (variable payload) |

**Type 0x0C Details:**

**When sending (OUT):** Host sends Type 0x0C with no payload to request a video keyframe (IDR). This is used when the video decoder needs to resync.
```
Header: aa 55 aa 55 00 00 00 00 0c 00 00 00 f3 ff ff ff
        └─ magic    └─ len=0   └─ type=12 └─ check
```

**When receiving (IN):** Adapter sends Type 0x0C with the Bluetooth pairing PIN as payload. The host displays this PIN for user verification during pairing.
```
Header + Payload: aa 55 aa 55 04 00 00 00 0c 00 00 00 f3 ff ff ff [PIN bytes]
                  └─ magic    └─ len=4   └─ type=12              └─ e.g., "1234"
```

**App Implementation Note:** The Kotlin app correctly implements both:
- `MessageType.BLUETOOTH_PIN(0x0C)` for receiving pairing PIN
- `CommandMapping.FRAME(12)` sends as Command payload (type 0x08 with cmd_id=12)

**⚠️ IMPORTANT:** The app uses Command ID 12 via message type 0x08 for keyframe requests, NOT raw message type 0x0C. Both approaches work - the firmware handles Command 12 identically to Message Type 0x0C with no payload.

**Command (0x08) IDs:** Payload is a 4-byte command ID. Full reference: `command_ids.md`
**Detailed Usage:** See `command_details.md` for binary-verified purpose, when to use, and expected behavior for each command.
- Basic (1-31): Mic, Siri, Night Mode, GNSS, WiFi band, Standby, BLE
- Controls (100-114): D-Pad buttons, Rotary knob
- Media (200-205): Play, Pause, Next, Prev
- Phone (300-314): Answer, HangUp, DTMF tones
- Status (1000-1013): WiFi/BT connection status

### Device Information

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 10 | 0x0A | BluetoothAddress | IN | 17 | Box BT address |
| 12 | 0x0C | BluetoothPIN | IN | Variable | Pairing PIN (**dual-purpose type, see above**) |
| 13 | 0x0D | BluetoothDeviceName | IN | 8 | BT device name |
| 14 | 0x0E | WifiDeviceName | IN | 8 | WiFi device name |
| 18 | 0x12 | BluetoothPairedList | IN | 50 | Paired device list |
| 20 | 0x14 | ManufacturerInfo | IN | Variable | OEM info |
| 204 | 0xCC | SoftwareVersion | IN | 32 | Firmware version |
| 187 | 0xBB | StatusValue | IN | 4 | Status/config value |

### Configuration

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 25 | 0x19 | BoxSettings | BOTH | Variable | JSON configuration |
| 153 | 0x99 | SendFile | OUT | Variable | Write file to adapter |

### Peer Device Info

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 24 | 0x18 | HiCarLink | IN | 113 | HiCar connection URL (Binary: CMD_CONNECTION_URL) |
| 35 | 0x23 | BluetoothConnectStart | IN | 17 | BT connection started, contains peer address (Binary: Bluetooth_ConnectStart) |
| 36 | 0x24 | BluetoothConnected | IN | 17 | BT connected, contains peer address (Binary: Bluetooth_Connected) |
| 37 | 0x25 | BluetoothDisconnect | IN | 0 | BT disconnected notification (Binary: Bluetooth_DisConnect) |
| 38 | 0x26 | BluetoothListen | IN | 0 | BT listening/advertising (Binary: Bluetooth_Listen) |

**Note:** Types 0x23-0x26 are Bluetooth state notifications. The firmware uses `Bluetooth_*` naming internally.
Previously documented as peer info types - corrected based on binary analysis Feb 2026.

### Navigation & Vehicle Data (Binary Verified Feb 2026)

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 40 | 0x28 | iAP2PlistBinary | IN | Variable | iAP2 plist binary data |
| 41 | 0x29 | GnssData | OUT | Variable | GPS/GNSS location data forwarded to phone (Binary: GNSS_DATA) |
| 43 | 0x2B | ConnectionPinCode | IN | Variable | BT pairing PIN code (Binary: Connection_PINCODE) |
| 44 | 0x2C | NaviVideoData | IN | Variable | Navigation video stream (Binary: AltVideoFrame) |

**⚠️ IMPORTANT:** Type 0x2B is `Connection_PINCODE`, NOT AltVideoFrame. Navigation video is type 0x2C.
This was verified from binary string table at switch statement fcn.00017b74.

### Bluetooth PIN Message Types - Binary Analysis (Feb 2026)

The firmware has **two distinct** Bluetooth PIN message types with different purposes:

| Type | Hex | Binary Name | Purpose | Direction |
|------|-----|-------------|---------|-----------|
| 12 | 0x0C | CMD_SET_BLUETOOTH_PIN_CODE | Configuration PIN | BOTH |
| 43 | 0x2B | Connection_PINCODE | Pairing PIN | IN |

**Type 0x0C - Configuration PIN (CMD_SET_BLUETOOTH_PIN_CODE):**
- **Purpose:** Set/configure the Bluetooth PIN code stored in adapter
- **Config Key:** `HU_BT_PIN_CODE` (persistent storage)
- **When Used:** During initial adapter configuration or when changing PIN
- **Related String:** `"Set Bluetooth Pin Code: %s"` (at 0x6e86d)
- **Dual-purpose:** Also used as keyframe request (OUT with 0-byte payload)

**Type 0x2B - Pairing PIN (Connection_PINCODE):**
- **Purpose:** Real-time PIN notification during active Bluetooth pairing
- **When Used:** During phone-to-adapter Bluetooth pairing flow
- **Direction:** Adapter→Host only (IN)
- **Related String:** `"Send connetion pincode to HU: %s"` (at 0x6d363)
- **Code Reference:** Function at 0x1911c uses `movs r1, 0x2b` when sending

**Why Two Types?**
The firmware separates PIN handling into configuration vs. pairing:
1. **0x0C (Configuration):** Persistent setting - the PIN stored in adapter config that will be used for ALL pairings
2. **0x2B (Pairing):** Transient notification - the actual PIN to display during a specific pairing session

**Typical Flow:**
1. Host sends 0x0C (OUT) to configure adapter's Bluetooth PIN
2. Phone initiates pairing with adapter
3. Adapter sends 0x2B (IN) with PIN for host to display
4. User confirms PIN on phone and host

**Binary Evidence:**
```
fcn.00017b74 dispatch table:
  0x17cb0: Type 0x0C → loads "CMD_SET_BLUETOOTH_PIN_CODE"
  0x17d10: Type 0x2B → loads "Connection_PINCODE"

fcn.0001911c (PIN sender):
  0x1913a: movs r1, 0x2b  ; Sets message type to 0x2B
  0x1914a: ldr r2, "Send connetion pincode to HU: %s"
```

### Media Metadata

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 42 | 0x2A | MediaData | IN | Variable | Rich media metadata (Binary: DashBoard_DATA) |

**MediaData Subtypes (first 4 bytes of payload):**

| Subtype | Hex | Content | Typical Size |
|---------|-----|---------|--------------|
| 1 | 0x00000001 | JSON metadata (song info, playback time) | 30-202 bytes |
| 3 | 0x00000003 | Binary data (album artwork - JPEG) | 170-180 KB |
| 200 | 0x000000C8 | Navigation JSON (route, TBT directions) | 30-200 bytes |

**Packet Structure:**
```
Offset  Size  Field        Description
------  ----  -----        -----------
0x00    4     Subtype      Content type indicator (1=JSON, 3=JPEG, 200=NaviJSON)
0x04    N     Content      JSON string or JPEG image data
```

**MediaData JSON Fields (Subtype 1):**
```json
{
  "MediaAPPName": "YouTube Music",
  "MediaSongName": "Song Title",
  "MediaArtistName": "Artist",
  "MediaAlbumName": "Album",
  "MediaSongDuration": 171000,
  "MediaSongPlayTime": 5000,
  "MediaPlayStatus": 1
}
```

**Album Artwork (Subtype 3):**
- JPEG image data starting at offset 0x04
- Starts with JPEG magic: `FF D8 FF E0`
- Typical resolution: 300x300 to 600x600 pixels
- Transferred via iAP2 file transfer session (`mediaItemArtworkFileTransferIdentifier`)

**Navigation JSON (Subtype 200 / 0xC8) - Capture Verified Feb 2026:**

Sent when `DashboardInfo` bit 2 is set and iPhone has active navigation. Contains turn-by-turn route guidance data from Apple Maps.

| Field | Type | Description |
|-------|------|-------------|
| `NaviStatus` | int | 0=inactive, 1=active, 2=calculating |
| `NaviTimeToDestination` | int | ETA in seconds |
| `NaviDestinationName` | string | Destination name |
| `NaviDistanceToDestination` | int | Total remaining distance (meters) |
| `NaviAPPName` | string | Navigation app (e.g., "Apple Maps") |
| `NaviRemainDistance` | int | Distance to next maneuver (meters) |
| `NaviRoadName` | string | Current or next road name |
| `NaviOrderType` | int | Turn order in route sequence |
| `NaviManeuverType` | int | Maneuver type code |

**Example NaviJSON Payloads:**
```json
{"NaviStatus":1,"NaviTimeToDestination":480,"NaviDestinationName":"Speedway","NaviDistanceToDestination":6124,"NaviAPPName":"Apple Maps"}
```
```json
{"NaviRoadName":"Farrior Dr","NaviRoadName":"Start on Farrior Dr","NaviOrderType":1,"NaviManeuverType":11}
```
```json
{"NaviRemainDistance":26}
```

**iAP2 Source Messages:**
- `0x5200` StartRouteGuidanceUpdate - Adapter requests TBT data
- `0x5201` RouteGuidanceUpdate - Route status from iPhone
- `0x5202` RouteGuidanceManeuverUpdate - Turn instructions from iPhone

**Firmware Evidence (ARMiPhoneIAP2):**
- JSON fields: `MediaSongName`, `MediaAlbumName`, `MediaArtistName`, `MediaAPPName`, `MediaSongDuration`, `MediaSongPlayTime`
- Artwork: `mediaItemArtworkFileTransferIdentifier`, `CiAP2MediaPlayerEngine_Send_NowPlayingMeidaArtwork`
- Navigation: `iAP2RouteGuidanceEngine`, `_SendNaviJSON`, `NaviStatus`, `NaviDestinationName`

### Session Establishment (Encrypted Blob)

*Verified via CarPlay capture (Jan 2026, iPhone18,4)*

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 163 | 0xA3 | SessionToken | IN | 508 | Encrypted session data (see below) |

**Type 163 (SessionToken) Analysis:**

Sent once during session establishment, immediately after BoxSettings (Type 25).
Appears in both CarPlay and Android Auto sessions.

**Packet Structure:**
```
Offset  Size  Field           Description
------  ----  -----           -----------
0x00    16    Protocol Header (magic, length, type, check)
0x10    492   Base64 payload  ASCII Base64-encoded data

Base64 decoded: 368 bytes of high-entropy binary (7.45 bits/byte entropy)
```

**Timing Context:**
- Sent ~8 seconds into session during establishment phase
- Immediately follows BoxSettings (phone info JSON)
- Precedes Phase update and first video frames

**Payload Characteristics:**
- High entropy (encrypted or cryptographic data)
- First 4 bytes: `f4 08 08 74` (possible header/version)
- Not ASN.1 DER format (doesn't start with 0x30)
- Likely contains session credentials or encrypted device telemetry

**Firmware Analysis (Jan 2026):**

| Property | CarPlay | Android Auto |
|----------|---------|--------------|
| Base64 payload | 492 bytes | 428 bytes |
| Decoded size | 368 bytes | 320 bytes |
| Block alignment | 23 × 16-byte blocks | 20 × 16-byte blocks |
| Encryption | AES (block-aligned) | AES (block-aligned) |

**Structure (decoded):**
```
Offset  Size  Field           Description
------  ----  -----           -----------
0x00    16    IV/Nonce        Likely AES initialization vector
0x10    N     Ciphertext      AES-CBC or AES-CTR encrypted data
```

**DECRYPTION SUCCESSFUL (Jan 2026):**

| Property | Value |
|----------|-------|
| Algorithm | AES-128-CBC |
| Key | `W2EC1X1NbZ58TXtn` (USB Communication Key) |
| IV | First 16 bytes of Base64-decoded payload |
| Content | JSON telemetry data |

**Decrypted CarPlay Example:**
```json
{
  "phone": {
    "model": "iPhone18,4",
    "osVer": "23D5103d",
    "linkT": "CarPlay",
    "conSpd": 4,
    "conRate": 0.24,
    "conNum": 17,
    "success": 4
  },
  "box": {
    "uuid": "651ede982f0a99d7f9138131ec5819fe",
    "model": "A15W",
    "hw": "YMA0-WR2C-0003",
    "ver": "2025.10.15.1127",
    "mfd": "20240119"
  }
}
```

**Field Descriptions:**

| Field | Description |
|-------|-------------|
| `phone.model` | Device model (iPhone18,4, Google Pixel 10) |
| `phone.osVer` | OS build version |
| `phone.linkT` | Link type (CarPlay, AndroidAuto) |
| `phone.conSpd` | Connection speed indicator |
| `phone.conRate` | Historical connection success rate |
| `phone.conNum` | Total connection attempts to this adapter |
| `phone.success` | Successful connection count |
| `box.uuid` | Adapter unique identifier |
| `box.model` | Adapter model (A15W) |
| `box.hw` | Hardware revision |
| `box.ver` | Firmware version |
| `box.mfd` | Manufacturing date (YYYYMMDD)

**Purpose:** Session telemetry sent from adapter to host containing device statistics and adapter identification. Used for logging/analytics.

### Navigation Focus (iOS 13+)

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 506 | 0x1FA | NaviFocus | OUT | 0 | Request nav focus |
| 507 | 0x1FB | NaviRelease | OUT | 0 | Release nav focus |
| 508 | 0x1FC | RequestNaviScreenFocus | BOTH | 0 | **Asymmetric handshake**: Adapter sends first (IN), host echoes back (OUT) |
| 509 | 0x1FD | ReleaseNaviScreenFocus | OUT | 0 | Release nav screen |
| 110 | 0x6E | NaviFocusRequest | IN | 0 | Nav requesting focus |
| 111 | 0x6F | NaviFocusRelease | IN | 0 | Nav released focus |

### WiFi/Bluetooth Connection Status (Binary Verified Feb 2026)

**⚠️ CORRECTED:** Previous documentation incorrectly labeled these commands. Verified via `ARMadb-driver` binary disassembly.

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 1003 | 0x3EB | ScaningDevices | IN | 0 | Adapter scanning for devices |
| 1004 | 0x3EC | DeviceFound | IN | 0 | Device found during scan |
| 1005 | 0x3ED | DeviceNotFound | IN | 0 | No device found in scan |
| 1007 | 0x3EF | DeviceBluetoothConnected | IN | 0 | Bluetooth connected |
| 1008 | 0x3F0 | DeviceBluetoothNotConnected | IN | 0 | Bluetooth disconnected |
| 1009 | 0x3F1 | DeviceWifiConnected | IN | 0 | Phone connected to adapter WiFi hotspot |
| 1010 | 0x3F2 | DeviceWifiNotConnected | IN | 0 | No phone connected to adapter WiFi hotspot |

**Critical Note on 1010 (DeviceWifiNotConnected):**

This is a **WiFi hotspot status notification**, NOT a session termination signal. The adapter sends this when:
- During initialization (no phone connected yet)
- Periodically while idle/waiting for connection
- When WiFi link drops during wireless session

**Host apps must NOT terminate sessions upon receiving 1010.** For USB CarPlay, WiFi status is irrelevant.
Use `Unplugged` (0x04) or `Phase 0` for session termination detection.

---

## Message Payload Details

### Plugged (0x02) - Phone Connected

```
Offset  Size  Field        Description
------  ----  -----        -----------
0x00    4     phoneType    Device type (see below)
0x04    4     connected    Connection state (1=connected)
```

**phoneType Values (Verified):**
| Value | Device | Transport | Status |
|-------|--------|-----------|--------|
| 1 | AndroidMirror | USB | Unverified |
| 2 | Carlife | USB | Unverified |
| 3 | CarPlay | USB | ✓ VERIFIED |
| 4 | iPhoneMirror | USB | Unverified |
| 5 | AndroidAuto | USB | ✓ VERIFIED |
| 6 | HiCar | USB | Unverified |
| 7 | ICCOA | USB | Unverified |
| 8 | CarPlay | Wireless | ✓ VERIFIED |

See `device_identification.md` for full analysis and firmware evidence.

### Open (0x01) - Session Initialization

```
Offset  Size  Field        Description
------  ----  -----        -----------
0x00    4     width        Display width (e.g., 2400)
0x04    4     height       Display height (e.g., 960)
0x08    4     fps          Frame rate (e.g., 60)
0x0C    4     format       Video format ID (see below)
0x10    4     packetMax    Max packet size (e.g., 49152)
0x14    4     boxVersion   Protocol version (e.g., 2)
0x18    4     phoneMode    Operation mode (e.g., 2)
```

**Format Field Values:**

| Value | Name | Behavior |
|-------|------|----------|
| 1 | Basic Mode | Minimal IDR insertion |
| 5 | Full H.264 Mode | Responsive to Frame sync, aggressive IDR |

**Capture Verification (Jan 2026):**
- format=5: 107 IDR frames received, 118 SPS repetitions
- format=1: 27 IDR frames received, 33 SPS repetitions

### Command (0x08) - Control Commands

**Payload:** 4-byte command ID

| ID | Hex | Name | Description |
|----|-----|------|-------------|
| 1 | 0x01 | startRecordAudio | Begin audio recording |
| 2 | 0x02 | stopRecordAudio | Stop audio recording |
| 3 | 0x03 | requestHostUI | Request UI focus |
| 5 | 0x05 | siri | Trigger Siri |
| 7 | 0x07 | mic | Phone microphone control |
| 15 | 0x0F | boxMic | Adapter microphone control |
| 16 | 0x10 | nightModeOn | Enable night mode |
| 17 | 0x11 | nightModeOff | Disable night mode |
| 22 | 0x16 | audioTransferOn | Enable audio transfer |
| 23 | 0x17 | audioTransferOff | Disable audio transfer |
| 24 | 0x18 | wifi24g | Switch to 2.4GHz WiFi |
| 25 | 0x19 | wifi5g | Switch to 5GHz WiFi |
| 100-114 | | Navigation | D-pad controls |
| 200 | 0xC8 | home | Home button |
| 201 | 0xC9 | play | Play button |
| 202 | 0xCA | pause | Pause button |
| 204 | 0xCC | next | Next track |
| 205 | 0xCD | prev | Previous track |
| 500 | 0x1F4 | RequestVideoFocus | Request video focus (Android Auto) |
| 501 | 0x1F5 | ReleaseVideoFocus | Release video focus (Android Auto) |
| 502 | 0x1F6 | unknown502 | Android Auto related |
| 503 | 0x1F7 | unknown503 | Android Auto related |
| 504 | 0x1F8 | RequestAudioFocusDuck | Request audio focus with ducking (AA) |
| 505 | 0x1F9 | ReleaseAudioFocus | Release audio focus (AA) |
| 1000 | 0x3E8 | SupportWifi | WiFi mode supported |
| 1001 | 0x3E9 | SupportAutoConnect | Auto-connect supported |
| 1002 | 0x3EA | StartAutoConnect | Start auto-connect scan |
| 1003 | 0x3EB | ScaningDevices | Device scanning |
| 1004 | 0x3EC | DeviceFound | Device found |
| 1005 | 0x3ED | DeviceNotFound | No device found |
| 1007 | 0x3EF | DeviceBluetoothConnected | Bluetooth connected |
| 1008 | 0x3F0 | DeviceBluetoothNotConnected | Bluetooth disconnected |
| 1009 | 0x3F1 | DeviceWifiConnected | WiFi hotspot: phone connected |
| 1010 | 0x3F2 | DeviceWifiNotConnected | WiFi hotspot: no phone connected (**NOT session end**) |

### BoxSettings (0x19) - JSON Configuration

**⚠️ SECURITY WARNING:** The `wifiName`, `btName`, and `oemIconLabel` fields are vulnerable to **command injection**. These values are passed to `popen()` shell commands without sanitization. See `03_Security_Analysis/vulnerabilities.md` for details.

#### Host → Adapter Fields (Binary Verified Jan 2026)

**Core Configuration:**

| Field | Type | Description | Maps to riddle.conf |
|-------|------|-------------|---------------------|
| `mediaDelay` | int | Audio buffer (ms) | `MediaLatency` |
| `syncTime` | int | Unix timestamp | - |
| `autoConn` | bool | Auto-reconnect | `NeedAutoConnect` |
| `autoPlay` | bool | Auto-start playback | `AutoPlay` |
| `autoDisplay` | bool | Auto display mode | - |
| `bgMode` | int | Background mode | `BackgroundMode` |
| `startDelay` | int | Startup delay (sec) | `BoxConfig_DelayStart` |
| `syncMode` | int | Sync mode | - |
| `lang` | string | Language code | - |

**Display / Video:**

| Field | Type | Description | Maps to riddle.conf |
|-------|------|-------------|---------------------|
| `androidAutoSizeW` | int | Android Auto width | `AndroidAutoWidth` |
| `androidAutoSizeH` | int | Android Auto height | `AndroidAutoHeight` |
| `screenPhysicalW` | int | Physical screen width (mm) | - |
| `screenPhysicalH` | int | Physical screen height (mm) | - |
| `drivePosition` | int | 0=LHD, 1=RHD | `CarDrivePosition` |

**Audio:**

| Field | Type | Description | Maps to riddle.conf |
|-------|------|-------------|---------------------|
| `mediaSound` | int | 0=44.1kHz, 1=48kHz | `MediaQuality` |
| `mediaVol` | float | Media volume (0.0-1.0) | - |
| `navVol` | float | Navigation volume | - |
| `callVol` | float | Call volume | - |
| `ringVol` | float | Ring volume | - |
| `speechVol` | float | Speech/Siri volume | - |
| `otherVol` | float | Other audio volume | - |
| `echoDelay` | int | Echo cancellation (ms) | `EchoLatency` |
| `callQuality` | int | Voice call quality | `CallQuality` | ⚠️ **REMOVED/BROKEN** - Removed from web UI in 2025.10.X firmware; no observed differences in manual testing. See configuration.md for details. |

**Network / Connectivity:**

| Field | Type | Description | Maps to riddle.conf |
|-------|------|-------------|---------------------|
| `wifiName` | string | WiFi SSID | ⚠️ **CMD INJECTION** |
| `wifiFormat` | int | WiFi format | - |
| `WiFiChannel` | int | WiFi channel (1-11, 36-165) | `WiFiChannel` |
| `btName` | string | Bluetooth name | ⚠️ **CMD INJECTION** |
| `btFormat` | int | Bluetooth format | - |
| `boxName` | string | Device display name | `CustomBoxName` |
| `iAP2TransMode` | int | iAP2 transport mode | `iAP2TransMode` |

**Branding / OEM:**

| Field | Type | Description | Maps to riddle.conf |
|-------|------|-------------|---------------------|
| `oemName` | string | OEM name | - |
| `productType` | string | Product type (e.g., "A15W") | - |
| `lightType` | int | LED indicator type | - |

**Navigation Video (iOS 13+ with AdvancedFeatures=1):**

| Field | Type | Description |
|-------|------|-------------|
| `naviScreenInfo` | object | Nested object for nav video config |
| `naviScreenInfo.width` | int | Nav screen width (default: 480) |
| `naviScreenInfo.height` | int | Nav screen height (default: 272) |
| `naviScreenInfo.fps` | int | Nav screen FPS (default: 30, range: 10-60, recommended: 24-60) |

**Android Auto Mode:**

| Field | Type | Description |
|-------|------|-------------|
| `androidWorkMode` | int | Enable Android Auto daemon (0/1) - resets on disconnect |

**Complete Example:**
```json
{
  "mediaDelay": 300,
  "syncTime": 1737331200,
  "autoConn": true,
  "autoPlay": false,
  "bgMode": 0,
  "startDelay": 0,
  "androidAutoSizeW": 1920,
  "androidAutoSizeH": 720,
  "screenPhysicalW": 250,
  "screenPhysicalH": 100,
  "drivePosition": 0,
  "mediaSound": 1,
  "mediaVol": 1.0,
  "navVol": 1.0,
  "callVol": 1.0,
  "echoDelay": 320,
  "callQuality": 1,
  "wifiName": "CarAdapter",
  "WiFiChannel": 36,
  "btName": "CarAdapter",
  "boxName": "CarAdapter",
  "naviScreenInfo": {
    "width": 480,
    "height": 272,
    "fps": 30
  }
}
```

**Command Injection via wifiName/btName (Binary Verified):**
```json
{
  "wifiName": "a\"; /usr/sbin/riddleBoxCfg -s AdvancedFeatures 1; echo \"",
  "btName": "carlink"
}
```
This executes `riddleBoxCfg` immediately as root. Any shell command can be injected.

**Adapter → Host:**
```json
{
  "uuid": "651ede982f0a99d7f9138131ec5819fe",
  "MFD": "20240119",
  "boxType": "YA",
  "productType": "A15W",
  "OemName": "carlink_test",
  "hwVersion": "YMA0-WR2C-0003",
  "HiCar": 1,
  "WiFiChannel": 36,
  "DevList": [{"id": "64:31:35:8C:29:69", "type": "CarPlay"}]
}
```

**Phone Info Update (CarPlay):**
```json
{
  "MDLinkType": "CarPlay",
  "MDModel": "iPhone18,4",
  "MDOSVersion": "23D5089e",
  "MDLinkVersion": "935.3.1",
  "btMacAddr": "64:31:35:8C:29:69",
  "cpuTemp": 53
}
```

**Phone Info Update (Android Auto):**
```json
{
  "MDLinkType": "AndroidAuto",
  "MDModel": "Google Pixel 10",
  "MDOSVersion": "",
  "MDLinkVersion": "1.7",
  "btMacAddr": "B0:D5:FB:A3:7E:AA",
  "btName": "Pixel 10",
  "cpuTemp": 54
}
```

**Note:** Android Auto does not populate MDOSVersion. MDLinkVersion contains the Android Auto protocol version.

### SendFile (0x99) - File Upload (Binary Verified Jan 2026)

```
Offset  Size  Field        Description
------  ----  -----        -----------
0x00    4     pathLen      Path string length
0x04    N+1   filePath     Null-terminated path
+N+1    4     contentLen   Content length
+N+5    M     content      File content bytes
```

**Binary Evidence:**
```
SEND FILE: %s, %d byte        - Logs path and size (at upload)
UPLOAD FILE: %s, %d byte      - Alternative logging
UPLOAD FILE Length Error!!!   - Size validation exists
/tmp/uploadFileTmp            - Temporary staging location
```

**Known Target Files and Effects:**

| Path | Content | Side Effect |
|------|---------|-------------|
| `/tmp/screen_dpi` | Integer (e.g., 240) | Sets display DPI |
| `/tmp/screen_fps` | Integer | Sets target framerate |
| `/tmp/screen_size` | Dimensions | Sets display size |
| `/tmp/night_mode` | 0 or 1 | Triggers `StartNightMode`/`StopNightMode` |
| `/tmp/hand_drive_mode` | 0 or 1 | Left/right hand drive |
| `/tmp/charge_mode` | 0 or 1 | USB charging speed (see below) |
| `/tmp/gnss_info` | NMEA text | GPS data for CarPlay navigation (see below) |
| `/tmp/carplay_mode` | Value | CarPlay mode setting |
| `/tmp/manual_disconnect` | Flag | Manual disconnect trigger |
| `/etc/android_work_mode` | 0 or 1 | **Critical**: Enables Android Auto daemon |
| `/tmp/carlogo.png` | PNG data | Custom car logo (copied to `/etc/boa/images/`) |
| `/tmp/hwfs.tar.gz` | Archive | **Auto-extracted to /tmp** via `tar -xvf` |
| `/tmp/*Update.img` | Firmware | Triggers OTA update process |

**Path Restrictions (Binary Verified):**

| Finding | Evidence |
|---------|----------|
| **No path whitelist** | No `strncmp("/tmp/")` or similar validation found |
| **No path traversal filter** | No `../` or sanitization logic found |
| **/etc is WRITABLE** | Scripts cp/rm files to `/etc/` (not squashfs read-only) |
| **Writes to any path** | Filesystem permissions only restriction |

**Limitations:**

| Constraint | Value | Evidence |
|------------|-------|----------|
| Size validation | Unknown limit | `UPLOAD FILE Length Error!!!` |
| tmpfs space | ~50-80MB | `/tmp` is RAM-backed tmpfs |
| Flash space | ~16MB total | Compressed rootfs |

**Archive Auto-Extraction:**
```c
mv %s %s;tar -xvf %s -C /tmp;rm -f %s;sync
```
Files uploaded to `/tmp/hwfs.tar.gz` are automatically extracted to `/tmp`.

**Firmware Update:** Files matching `*Update.img` pattern auto-trigger OTA update.
See `04_Implementation/firmware_update.md` for complete update procedure.

#### Charge Mode (Binary Verified Jan 2026)

Controls USB charging speed via GPIO pins 6 and 7.

| `/tmp/charge_mode` Value | GPIO6 | GPIO7 | Effect |
|--------------------------|-------|-------|--------|
| 0 (or missing) | 1 | 1 | **SLOW** charge (default) |
| 1 | 1 | 0 | **FAST** charge |

**Firmware log messages:**
- `CHARGE_MODE_FAST!!!!!!!!!!!!!!!!!` - Fast charge enabled
- `CHARGE_MODE_SLOW!!!!!!!!!!!!!!!!!` - Slow charge enabled

**Note:** "OnlyCharge" is a separate iPhone work mode (alongside AirPlay, CarPlay, iOSMirror) indicating phone is connected for charging only, no projection.

#### GPS/GNSS Data (Binary Verified Jan 2026)

GPS data for CarPlay navigation is sent via `/tmp/gnss_info` file.

**Format:** Standard NMEA 0183 sentences

| Sentence | Name | Purpose |
|----------|------|---------|
| `$GPGGA` | Global Positioning System Fix | Position, altitude, satellites |
| `$GPRMC` | Recommended Minimum | Position, speed, course, date |
| `$GPGSV` | Satellites in View | Satellite information |

**Example content for `/tmp/gnss_info`:**
```
$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,47.0,M,,*47
$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A
```

**Additional vehicle data supported:**
- `VehicleSpeedData` - Vehicle speed
- `VehicleHeadingData` - Compass heading
- `VehicleGyroData` - Gyroscope data
- `VehicleAccelerometerData` - Accelerometer data

**Flow:** Host writes NMEA → `iAP2LocationEngine` parses → forwarded to iPhone via CarPlay

**Config Requirements:**
- `HudGPSSwitch=1` in riddle.conf (enable GPS from HU)
- `GNSSCapability=1` (advertise GNSS to phone)

**Size limit:** Firmware logs `GNSSSentencesSize:%zu, GNSSSentences too long` if data exceeds buffer.

See `command_ids.md` for StartGNSSReport (18) and StopGNSSReport (19) commands.

### GnssData (0x29) - GPS/GNSS Location Data (Binary Verified Feb 2026)

**Direction:** Host → Adapter (OUT)
**Purpose:** Forward GPS location data from head unit to connected phone for CarPlay/Android Auto navigation.

**Finding:** The CPC200-CCPA has a **fully implemented GPS forwarding pipeline**. GPS data sent via this message type is converted to iAP2 LocationInformation by `CiAP2LocationEngine` in ARMiPhoneIAP2 and delivered to the iPhone for use in CarPlay Maps.

**Payload Structure:**

```
Offset  Size  Field         Description
------  ----  -----         -----------
0x00    4     nmeaLength    Length of NMEA data in bytes (LE uint32)
0x04    N     nmeaData      NMEA 0183 sentences (ASCII, \r\n terminated)
```

**Header Example:**
```
55 AA 55 AA    Magic
9E 00 00 00    Payload length (158 bytes, LE uint32)
29 00 00 00    Type = 0x29 (LE uint32)
D6 FF FF FF    Type check = ~0x29 (LE uint32)
```

**Supported NMEA Sentences (from CiAP2LocationEngine disassembly):**

| Sentence | iAP2 Mapping | Purpose | Required |
|----------|-------------|---------|----------|
| `$GPGGA` | `globalPositionSystemFixData` | Position, altitude, fix quality, satellites | Yes |
| `$GPRMC` | `recommendedMinimumSpecificGPSTransitData` | Position, speed, course, date | Yes |
| `$GPGSV` | `gpsSatellitesInView` | Satellite visibility info | Optional |
| `$PASCD` | (proprietary) | Vehicle-specific data | Optional |

**ARMadb-driver Processing (r2 disassembly verified Feb 2026):**

The type 0x29 handler at `0x1f5ce` in ARMadb-driver:
1. Reads NMEA payload from message offset `+0x10`, skips 4-byte length prefix
2. Calls `strstr(nmea_data, "$GPGGA")` — if found, writes to `/tmp/RiddleBoxData/HU_GPS_DATA` via `fopen("wb")`
3. **Regardless of strstr result**, forwards to phone as internal type `0x22` via `fcn.00017328` link dispatch
4. Size limit: `GNSSSentencesSize:%zu, GNSSSentences too long` if NMEA exceeds 0x400 (1KB) buffer

**Note:** The `$GPGGA` check only controls file writing. All NMEA data is forwarded to the phone unconditionally.

**Data Flow (Binary Verified Feb 2026):**

```
Host App                    ARMadb-driver                        ARMiPhoneIAP2          Phone
  │ USB Type 0x29            │                                     │                      │
  │ [4B len][NMEA ASCII]     │                                     │                      │
  ├─────────────────────────►│                                     │                      │
  │                          │ strstr($GPGGA)?                     │                      │
  │                          │   Y→ fwrite /tmp/RiddleBoxData/     │                      │
  │                          │       HU_GPS_DATA                   │                      │
  │                          │                                     │                      │
  │                          │ Forward as type 0x22 ──────────────►│                      │
  │                          │ (always, regardless of GPGGA)       │ CiAP2LocationEngine  │
  │                          │                                     │ stores in             │
  │                          │                                     │ NMEASentence entity   │
  │                          │                                     │                      │
  │                          │                                     │ iAP2 0xFFFB ─────────►│
  │                          │                                     │ LocationInformation   │
  │                          │                                     │              CarPlay  │
  │                          │                                     │              Maps     │
```

**Type 0x28 vs 0x29:**

| Type | Name | File Write | Forward States | Purpose |
|------|------|-----------|----------------|---------|
| 0x28 | iAP2 PlistBinary | No | CarPlay only (state 3) | iAP2 binary plist GPS inquiry |
| 0x29 | GNSS_DATA | Yes (if $GPGGA) | CarPlay (3), Android Auto (5-7) | NMEA GPS data |

**GPS File Paths:**

| Path | Written By | Content | Purpose |
|------|-----------|---------|---------|
| `/tmp/RiddleBoxData/HU_GPS_DATA` | ARMadb-driver (type 0x29 handler) | Raw NMEA binary (fopen "wb") | Debug/diagnostic dump of incoming GPS data |
| `/tmp/gnss_info` | CiAP2LocationEngine `fcn.0002c190` | NMEA type config string ("GPGGA,GPRMC,PASCD,") | Stores which NMEA sentence types are enabled |

**Configuration Requirements:**

| Config Key | Required Value | Default | Purpose |
|------------|---------------|---------|---------|
| `HudGPSSwitch` | 1 | 1 | Enable GPS from head unit (already correct on most units) |
| `GNSSCapability` | ≥ 1 | **0** | Register `locationInformationComponent` in iAP2 identification. **MUST be changed.** |

**GNSSCapability Bitmask (set by `fcn.0002c190` in ARMiPhoneIAP2):**

| Bit | Value | NMEA Sentence | Purpose |
|-----|-------|---------------|---------|
| 0 | 1 | `$GPGGA` | Global Positioning System Fix Data |
| 1 | 2 | `$GPRMC` | Recommended Minimum Specific GPS Transit Data |
| 3 | 8 | `$PASCD` | Proprietary (dead-reckoning/compass) |

Setting `GNSSCapability=1` enables GPGGA only. `GNSSCapability=3` enables GPGGA+GPRMC. `GNSSCapability=11` enables all three.

**DashboardInfo Clarification:** DashboardInfo does NOT gate location. Its bits control:
- Bit 0 (0x01): vehicleInformation init
- Bit 1 (0x02): vehicleStatus init
- Bit 2 (0x04): routeGuidanceDisplay init

Location/GPS is gated **only** by `GNSSCapability > 0`.

**⚠️ CRITICAL:** When `GNSSCapability=0` (factory default), the GPS pipeline is blocked at **two** points:
1. `CiAP2IdentifyEngine.virtual_8` at `0x240e4`: skips GPS session entity setup during identification
2. `fcn.00015ee4` at `0x15fa4`: skips `CiAP2LocationEngine_Generate` during session init

The iPhone never learns the adapter can provide location data and never sends `StartLocationInformation`. Fix:

```bash
ssh root@192.168.43.1
/usr/sbin/riddleBoxCfg -s GNSSCapability 3    # Enable GPGGA + GPRMC
rm -f /etc/RiddleBoxData/AIEIPIEREngines.datastore
busybox reboot
```

See `01_Firmware_Architecture/configuration.md` for full GNSSCapability documentation.

**iAP2 Location Data Types (7 sub-types in AskStartItems):**

| ID | Object Offset | Name | Source |
|----|--------------|------|--------|
| 1 | +0x38 | GloblePositionSystemFixData | NMEA `$GPGGA` |
| 2 | +0x58 | RecommendedMinimumSpecificGPSTransistData | NMEA `$GPRMC` |
| 3 | +0x78 | GPSSataellitesInView | NMEA `$GPGSV` |
| 4 | +0x98 | VehicleSpeedData | CAN/sensor |
| 5 | +0xB8 | VehicleGyroData | CAN/sensor |
| 6 | +0xD8 | VehicleAccelerometerData | CAN/sensor |
| 7 | +0xF8 | VehicleHeadingData | CAN/sensor |

Note: String names contain firmware typos ("Globle", "Transist", "Sataellites") — these are internal identifiers.

**Related Commands (Type 0x08):**

| Command ID | Name | Direction | Purpose |
|------------|------|-----------|---------|
| 18 | StartGNSSReport | H→A | Tell adapter to start GPS forwarding to phone |
| 19 | StopGNSSReport | H→A | Tell adapter to stop GPS forwarding |

See `command_ids.md` and `command_details.md` for full command documentation.

**Binaries Involved:**

| Binary | GPS Role |
|--------|----------|
| ARMadb-driver | Receives USB type 0x29, validates NMEA, forwards via IPC |
| ARMiPhoneIAP2 | `CiAP2LocationEngine` — NMEA→iAP2 conversion, iAP2 identification GPS registration |
| AppleCarPlay | Receives GNSS_DATA for status logging (location delivery is via iAP2, not CarPlay A/V) |
| bluetoothDaemon | GNSS_DATA/GNSSCapability references (Bluetooth-path GPS) |
| libdmsdpgpshandler.so | `VirtualBoxGPS::ProcessGNSSData()` — HiCar GPS path |
| libdmsdpdvgps.so | GPS device service: `GpsReceiveLocationData`, `GpsSendServiceData` |

See `05_Reference/binary_analysis/key_binaries.md` for GPS pipeline details.

**End-to-End Verification (Live-Tested Feb 2026):**

The complete GPS pipeline was verified with a CarLink Native host app on GM AAOS emulator, CPC200-CCPA adapter (firmware 2025.10.15.1127), and iPhone Air (iOS 18):

```
1. iAP2 Identification:
   [iAP2LocationEngine] CiAP2LocationEngine_Generate
   [iAP2Engine] Enable iAP2 iAP2LocationEngine Capability
   [iAP2IdentifyEngine] GNSSCapability=3
   identifyItemsArray: "FFFA: StartLocationInformation", "FFFC: StopLocationInformation",
                       "friendlyName": "locationInformationComponent"
   iPhone → IdentifyAccept ✓

2. iPhone requests GPS (pull-based):
   [CiAP2Session_CarPlay] Message from iPhone: 0xFFFA StartLocationInformation

3. Adapter sends at ~1Hz:
   [iAP2Engine] Send_changes:LocationInformation(0xFFFB), msgLen: 148

4. iPhone receives and parses:
   accessoryd: [#Location] sending nmea sentence to location client com.apple.locationd
   locationd(ExternalAccessory): [#Location] send EAAccessoryDidReceiveNMEASentenceNotification
   locationd: A,NMEA:<private>

5. iPhone fusion engine processes:
   #fusion inputLoc,...,GPS,...,Accuracy 4.7,...,in vehicle frozen
   CL-fusion,...,Accuracy,7.276,Type,1,GPS,...,isPassthrough,1,numHypothesis,1
   shouldBypassFusion,vehicleConnected,...
```

**iPhone GPS Fusion Behavior:**

The iPhone does NOT simply switch to vehicle GPS. It uses a **best-accuracy-wins fusion model**:
- `accessoryd` receives iAP2 NMEA and forwards to `locationd` via `EAAccessoryDidReceiveNMEASentenceNotification`
- `locationd` recognizes the accessory (`make="Magic Tec.", model="Magic-Car-Link-1.00"`) and processes NMEA via the "Realtime" subHarvester
- The fusion engine (`CL-fusion`) evaluates all location hypotheses by `horizontalAccuracy`
- When the iPhone's own GPS has acceptable accuracy (e.g., 4.7m indoors), it wins (`isPassthrough=1, numHypothesis=1`)
- Vehicle GPS is more likely to win when: phone is in pocket/bag (degraded GPS), wireless CarPlay (phone not mounted), or phone GPS is unavailable

**Android Auto GPS Path (Feb 2026):**

When connected via Android Auto (ARMAndroidAuto process), the adapter converts NMEA to protobuf:
```
gps_location {
  timestamp: 0              ← adapter clock wrong (stuck 2020-01-02), cannot derive epoch
  latitude_e7: 647166676
  longitude_e7: -1472666682
  accuracy_e3: 899
}
```
The `timestamp: 0` issue is a firmware limitation — the adapter derives time from NMEA time-of-day fields but has no epoch reference. Android Auto clients may reject zero-timestamp fixes.

### Touch (0x05) - Touch Input (Updated Jan 2026)

**Two encoding formats observed:**

**Format A (Legacy/Documented):**
```
Offset  Size  Field        Description
------  ----  -----        -----------
0x00    4     action       0=Down, 1=Move, 2=Up, 3=Menu, 4=Home, 5=Back
0x04    4     x_coord      Normalized X (float 0.0-1.0)
0x08    4     y_coord      Normalized Y (float 0.0-1.0)
0x0C    4     flags        Additional flags (reserved)
```

**Format B (Verified via Capture Jan 2026):**
```
Offset  Size  Field        Description
------  ----  -----        -----------
0x00    4     action       14=Down, 15=Move, 16=Up (0x0e, 0x0f, 0x10)
0x04    4     x_coord      Raw pixel X coordinate (little-endian int)
0x08    4     y_coord      Raw pixel Y coordinate (little-endian int)
0x0C    4     flags        Reserved (0x00000000)
```

**Capture Evidence (Format B):**
```
[36.497s] >>> OUT | SendTouch | 32 bytes
  0010: 0e 00 00 00 b3 0e 00 00 85 17 00 00 00 00 00 00
        └─ Down     └─ X=3763  └─ Y=6021

[36.499s] >>> OUT | SendTouch | 32 bytes
  0010: 0f 00 00 00 af 0e 00 00 7d 17 00 00 00 00 00 00
        └─ Move     └─ X=3759  └─ Y=6013

[36.580s] >>> OUT | SendTouch | 32 bytes
  0010: 10 00 00 00 af 0e 00 00 7d 17 00 00 00 00 00 00
        └─ Up       └─ X=3759  └─ Y=6013
```

**Action Values (Format B):**
| Value | Hex | Action |
|-------|-----|--------|
| 14 | 0x0e | Touch Down |
| 15 | 0x0f | Touch Move |
| 16 | 0x10 | Touch Up |

**Note:** The X/Y coordinates in Format B appear to be raw pixel values relative to a high-resolution coordinate space, not normalized floats. The format used may depend on firmware version or configuration.

### HeartBeat (0xAA) - USB Keepalive (CRITICAL)

**Message Format (header only, no payload):**
```
+------------------+------------------+------------------+------------------+
|   0x55AA55AA     |   0x00000000     |   0x000000AA     |   0xFFFFFF55     |
|   (magic)        |   (length=0)     |   (type=170)     |   (type check)   |
+------------------+------------------+------------------+------------------+
```

**Purpose:** USB keepalive + firmware boot stabilization signal

**Configuration:**
- Config key: `SendHeartBeat` in `/etc/riddle.conf`
- Default: `1` (enabled)
- D-Bus signal: `HUDComand_A_HeartBeat`

**Binary Analysis (ARMadb-driver):**
| Address | Function | Purpose |
|---------|----------|---------|
| `0x00018e2c` | Message dispatcher | Routes 0xAA to heartbeat handler |
| `0x0006327a` | D-Bus signal emit | Emits `HUDComand_A_HeartBeat` |

**Critical Timing:**
- Must start **BEFORE** initialization messages on cold start
- Send every ~2 seconds continuously
- **Timeout values:** Binary constant shows 15,000ms (0x3a98 at address 0x21112), but practical testing shows disconnect at ~10-11.7 seconds. **Design for ~10 seconds to be safe.** The discrepancy may be due to timer starting before USB handshake completes.

See `initialization.md` and `configuration.md` for detailed timing requirements.

---

## Phase Values (0x03)

| Value | Meaning | Status | Evidence |
|-------|---------|--------|----------|
| 0 | Session Terminated / Idle | VERIFIED | Used for session end detection |
| 1-6 | Reserved / Internal | UNKNOWN | Not observed in captures |
| 7 | Connecting / Negotiating | VERIFIED | `@ 4.498s: Phase payload 07 00 00 00` |
| 8 | Streaming Ready / Active | VERIFIED | `@ 7.504s: Phase payload 08 00 00 00` |

**Session Termination Detection:**
- Phase 0 indicates session has ended
- Use `Unplugged` (0x04) OR `Phase 0` for definitive session termination
- Do NOT rely on Command 1010 (DeviceWifiNotConnected) for session end

---

## Verified Initialization Sequence

From `video_2400x960@60` capture (Jan 2026):

```
Time      Dir  Type                   Payload Summary
────────────────────────────────────────────────────────────────
0.132s    OUT  SendFile (0x99)       /tmp/screen_dpi = 240
0.253s    OUT  Open (0x01)           2400x960 @ 60fps, format=5
0.374s    OUT  SendFile (0x99)       /tmp/night_mode = 1
0.376s    IN   Command (0x08)        0x3E8 (wifiEnable)
0.408s    IN   BluetoothDeviceName   "carlink_test"
0.408s    IN   WifiDeviceName        "carlink_test"
0.409s    IN   UiBringToForeground   (no payload)
0.409s    IN   BluetoothPairedList   "64:31:35:8C:29:69Luis"
0.409s    IN   Command (0x08)        0x3E9 (autoConnectEnable)
0.409s    IN   Command (0x08)        0x07 (micSource)
0.410s    IN   SoftwareVersion       "2025.02.25.1521CAY"
0.410s    IN   BoxSettings           JSON with device info
0.410s    IN   Open (0x01)           Echo of session params
...
2.362s    IN   PeerBluetoothAddress  "64:31:35:8C:29:69"
3.645s    OUT  HeartBeat             (first heartbeat)
3.662s    IN   PeerBluetoothAddressAlt
4.180s    IN   Plugged (0x02)        phoneType=3, connected=1
4.498s    IN   Phase (0x03)          phase=7 (connecting)
7.503s    IN   BoxSettings           MDModel="iPhone18,4"
7.504s    IN   Phase (0x03)          phase=8 (streaming ready)
```

---

## Navigation Video Handshake (iOS 13+)

**CRITICAL DISCOVERY (Jan 2026):** Command 508 is a bidirectional handshake.

**Handshake Sequence:**
1. Adapter sends 508 to host (RequestNaviScreenFocus)
2. Host MUST respond by sending 508 back to adapter
3. This triggers `HU_NEEDNAVI_STREAM` D-Bus signal
4. Navigation video (Type 0x2C) streaming begins

If host does not respond with 508, navigation video will not start.

**Verified implementation** (`pi-carplay-main/src/main/carplay/services/CarplayService.ts:270-277`):
```typescript
if ((msg.value as number) === 508 && this.config.naviScreen?.enabled) {
  this.driver.send(new SendCommand('requestNaviScreenFocus'))
}
```

---

## Undocumented Message Types

| Type | Hex | Notes |
|------|-----|-------|
| 11 | 0x0B | Encryption bypass list |
| 16-17 | 0x10-0x11 | Unknown |
| 19 | 0x13 | Unknown |
| 27 | 0x1B | Encryption control |
| 119 | 0x77 | **AdapterIdle** - Idle/waiting notification (see below) |
| 136 | 0x88 | Debug mode enable |
| 253 | 0xFD | Error/reset related |
| 255 | 0xFF | Error or special control |

### AdapterIdle (0x77) - Idle Notification (Verified Jan 2026)

**Direction:** Adapter → Host (IN)
**Payload:** None (header only, 16 bytes total)

```
+------------------+------------------+------------------+------------------+
|   0x55AA55AA     |   0x00000000     |   0x00000077     |   0xFFFFFF88     |
|   (magic)        |   (length=0)     |   (type=119)     |   (type check)   |
+------------------+------------------+------------------+------------------+
```

**Observed Behavior:**
- Sent by adapter to host during idle state
- Occurred at ~33 seconds into a session with no active streaming
- May indicate adapter is waiting for activity or phone connection
- Similar to HeartBeat but in reverse direction (adapter-originated)

**Capture Evidence:**
```
[    33.693s] <<< IN  #13 | Unknown(0x77) | 16 bytes
  Header: aa 55 aa 55 00 00 00 00 77 00 00 00 88 ff ff ff
```

**Purpose (Inferred):** Adapter-side keepalive or "still waiting" signal. Host may use this to detect adapter is responsive but idle.

---

## Newly Identified Types (Firmware Binary Analysis - Jan 2026)

From disassembly of `ARMadb-driver_unpacked`:

| Type | Hex | Name | Dir | Payload | Code Evidence |
|------|-----|------|-----|---------|---------------|
| 30 | 0x1E | RemoteCxCy | A→H | Display resolution data | fcn.0001af48 @ `0x1afb0` |
| 161 | 0xA1 | ExtendedMfgInfo | A→H | Extended OEM/manufacturer data | fcn.00018628, fcn.000186ba |
| 240 | 0xF0 | RemoteDisplay | A→H | Remote display parameters | fcn.0001af48 @ `0x1af96` |

### Message Sender Functions

| Function | Address | Message Types Sent |
|----------|---------|-------------------|
| fcn.00018628 | `0x18628` | 0x06, 0x09, 0x0B, 0x0D, 0xA1 (state-dependent) |
| fcn.000186ba | `0x186ba` | 0x14 (ManufacturerInfo), 0xA1 |
| fcn.00018850 | `0x18850` | 0x06, 0x0B (HiCar DevList JSON) |
| fcn.0001af48 | `0x1af48` | 0x01, 0x1E, 0xF0 |
| fcn.00017340 | `0x17340` | Generic message sender (21 call sites) |

### Payload Structure Details (Binary Analysis - Jan 2026)

**RemoteCxCy (0x1E)** - 8 bytes payload:
```
+--------+--------+
| Width  | Height |
| (4B)   | (4B)   |
+--------+--------+
```
- Log string: `Accessory_fd::BroadCastRemoteCxCy : %d  %d` @ `0x5c20d`
- Broadcast display resolution from adapter to host

**RemoteDisplay (0xF0)** - 28 bytes payload:
```
+--------------------------------------------------------------------------+
|                    Display Parameters (28 bytes @ 0x11d00c)              |
+--------------------------------------------------------------------------+
```
- Size evidence: `movs r3, 0x1c` @ `0x1af9a`
- Runtime data structure, fields require protocol capture to verify

**ExtendedMfgInfo (0xA1)** - 12 bytes payload:
```
+--------------------------------------------------------------------------+
|                    Extended OEM Data (12 bytes)                          |
+--------------------------------------------------------------------------+
```
- Size evidence: `movs r2, 0xc` @ `0x186ca`
- Sent alongside ManufacturerInfo (0x14) when state==0x14 (20)

**HiCar DevList (0x0B)** - 32 bytes payload:
```
+--------------------------------------------------------------------------+
|                    Device List Data (32 bytes)                           |
+--------------------------------------------------------------------------+
```
- Size evidence: `movs r2, 0x20` @ `0x18890`
- Contains "DevList" and "type" JSON fields

### JSON Payload Format Strings (Firmware Addresses)

**Box Info JSON** (String @ `0x5c2ce`):
```json
{"uuid":"%s","MFD":"%s","boxType":"%s","OemName":"%s","productType":"%s",
 "HiCar":%d,"hwVersion":"%s","WiFiChannel":%d,"CusCode":"%s",
 "DevList":%s,"ChannelList":"%s"}
```

**Phone Link Info JSON** (String @ `0x5be16`):
```json
{"MDLinkType":"%s","MDModel":"%s","MDOSVersion":"%s","MDLinkVersion":"%s",
 "btMacAddr":"%s","btName":"%s","cpuTemp":%d}
```

### Status Event D-Bus Strings

| String | Address | Trigger |
|--------|---------|---------|
| `OnCarPlayPhase %d` | `0x5c415` | CarPlay phase change (Type 0x03) |
| `OnAndroidPhase _val=%d` | `0x5bf52` | Android Auto phase change |
| `DeviceBluetoothConnected` | `0x5bc88` | BT connection established |
| `DeviceBluetoothNotConnected` | `0x5bca1` | BT disconnected |
| `DeviceWifiConnected` | `0x5bcbd` | WiFi connection established |
| `DeviceWifiNotConnected` | `0x5bcd1` | WiFi disconnected |
| `CMD_BOX_INFO` | `0x5b44c` | Box info request/response |
| `CMD_CAR_MANUFACTURER_INFO` | `0x5b3e4` | OEM info (Type 0x14) |

---

## Appendix: Binary-Verified Message Types (Feb 2026)

Complete message type table extracted via radare2 disassembly of `ARMadb-driver_2025.10_unpacked`.
Source: Switch statement at `fcn.00017b74` (0x17b74 - 0x17d48).

| Hex  | Dec | Binary String Name               | Compare Addr | Handler Addr |
|------|-----|----------------------------------|--------------|--------------|
| 0x01 |   1 | Open                             | 0x17bb2      | 0x17c84      |
| 0x02 |   2 | PlugIn                           | 0x17bb6      | 0x17c88      |
| 0x03 |   3 | Phase                            | 0x17bba      | 0x17c8c      |
| 0x04 |   4 | PlugOut                          | 0x17bbe      | 0x17c90      |
| 0x05 |   5 | Command                          | 0x17bc2      | 0x17c94      |
| 0x06 |   6 | VideoFrame                       | 0x17bc6      | 0x17c98      |
| 0x07 |   7 | AudioFrame                       | 0x17bca      | 0x17c9c      |
| 0x08 |   8 | CarPlayControl                   | 0x17bce      | 0x17ca0      |
| 0x09 |   9 | LogoType                         | 0x17bd2      | 0x17ca4      |
| 0x0A |  10 | SetBluetoothAddress              | 0x17bd6      | 0x17ca8      |
| 0x0B |  11 | CMD_CARPLAY_MODE_CHANGE          | 0x17bda      | 0x17cac      |
| 0x0C |  12 | CMD_SET_BLUETOOTH_PIN_CODE       | 0x17bde      | 0x17cb0      |
| 0x0D |  13 | HUDComand_D_BluetoothName        | 0x17be2      | 0x17cb4      |
| 0x0E |  14 | CMD_BOX_WIFI_NAME                | 0x17be6      | 0x17cb8      |
| 0x0F |  15 | CMD_MANUAL_DISCONNECT_PHONE      | 0x17bea      | 0x17cbc      |
| 0x10 |  16 | CMD_CARPLAY_AirPlayModeChanges   | 0x17bee      | 0x17cc0      |
| 0x11 |  17 | AutoConnect_By_BluetoothAddress  | 0x17bf2      | 0x17cc4      |
| 0x12 |  18 | kRiddleHUDComand_D_Bluetooth_BondList | 0x17bf6 | 0x17cc8      |
| 0x13 |  19 | CMD_BLUETOOTH_ONLINE_LIST        | 0x17bfa      | 0x17ccc      |
| 0x14 |  20 | CMD_CAR_MANUFACTURER_INFO        | 0x17bfe      | 0x17cd0      |
| 0x15 |  21 | CMD_STOP_PHONE_CONNECTION        | 0x17c02      | 0x17cd4      |
| 0x16 |  22 | CMD_CAMERA_FRAME                 | 0x17c06      | 0x17cd8      |
| 0x17 |  23 | CMD_MULTI_TOUCH                  | 0x17c0a      | 0x17cdc      |
| 0x18 |  24 | CMD_CONNECTION_URL               | 0x17c0e      | 0x17ce0      |
| 0x19 |  25 | CMD_BOX_INFO                     | 0x17c12      | 0x17ce4      |
| 0x1A |  26 | CMD_PAY_RESULT                   | 0x17c16      | 0x17ce8      |
| 0x1B |  27 | BTAudioDevice_Signal             | 0x17ba0      | 0x17d46      |
| 0x1E |  30 | Bluetooth_Search                 | 0x17c1a      | 0x17cec      |
| 0x1F |  31 | Bluetooth_Found                  | 0x17c1e      | 0x17cf0      |
| 0x20 |  32 | Bluetooth_SearchStart            | 0x17c22      | 0x17cf4      |
| 0x21 |  33 | Bluetooth_SearchEnd              | 0x17c26      | 0x17cf8      |
| 0x22 |  34 | ForgetBluetoothAddr              | 0x17c2a      | 0x17cfc      |
| 0x23 |  35 | Bluetooth_ConnectStart           | 0x17ba6      | 0x17c78      |
| 0x24 |  36 | Bluetooth_Connected              | 0x17c2e      | 0x17d00      |
| 0x25 |  37 | Bluetooth_DisConnect             | 0x17baa      | 0x17c7c      |
| 0x26 |  38 | Bluetooth_Listen                 | 0x17bae      | 0x17c80      |
| 0x28 |  40 | iAP2Type_PlistBinary             | 0x17c32      | 0x17d04      |
| 0x29 |  41 | GNSS_DATA                        | 0x17c36      | 0x17d08      |
| 0x2A |  42 | DashBoard_DATA                   | 0x17c3a      | 0x17d0c      |
| 0x2B |  43 | Connection_PINCODE               | 0x17c3e      | 0x17d10      |
| 0x2C |  44 | AltVideoFrame                    | 0x17c42      | 0x17d14      |
| 0x77 | 119 | FactorySetting                   | 0x17c46      | 0x17d18      |
| 0x88 | 136 | CMD_DEBUG_TEST                   | 0x17c4a      | 0x17d1c      |
| 0x99 | 153 | HUDComand_A_UploadFile           | 0x17c4e      | 0x17d20      |
| 0xAA | 170 | HUDComand_A_HeartBeat            | 0x17c52      | 0x17d24      |
| 0xBB | 187 | CMD_UPDATE                       | 0x17c56      | 0x17d28      |
| 0xCC | 204 | HUDComand_B_BoxSoftwareVersion   | 0x17c5a      | 0x17d2c      |
| 0xCD | 205 | HUDComand_A_Reboot               | 0x17c5e      | 0x17d30      |
| 0xCE | 206 | HUDComand_A_ResetUSB             | 0x17c62      | 0x17d34      |
| 0xFD | 253 | HUDComand_D_Ready                | 0x17c66      | 0x17d38      |
| 0xFF | 255 | CMD_ACK                          | 0x17c6e      | (inline)     |

**Gaps in sequence (not defined in binary):** 0x1C (28), 0x1D (29), 0x27 (39)

**Special handling:**
- 0xFF (CMD_ACK) - handled inline at 0x17c6e-0x17c74
- Unknown types - logged as "Unkown_RiddleHUDComand_" at 0x17c6a

---

## References

- Source: `carlink_native/documents/reference/Firmware/firmware_protocol_table.md`
- Source: `GM_research/cpc200_research/CLAUDE.md`
- Source: `pi-carplay-4.1.3/firmware_binaries/PROTOCOL_ANALYSIS.md`
- **Session examples: `../04_Implementation/session_examples.md` - Real captured packet sequences**
- Verification: 25+ controlled CarPlay capture sessions
- Android Auto verification: Jan 2026 capture (Pixel 10, YouTube Music)
