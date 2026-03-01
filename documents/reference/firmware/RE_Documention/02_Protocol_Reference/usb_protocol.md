# CPC200-CCPA USB Protocol Reference

**Status:** VERIFIED against 25+ capture sessions + firmware binary analysis
**Consolidated from:** All research projects (GM_research, carlink_native, pi-carplay)
**Firmware Version:** 2025.10.15.1127 (binary analysis reference version)
**Last Updated:** 2026-02-19 (Added: Inbound Message Handling Reference with firmware-verified classifications, Phase 0 session teardown proof, cmd 1000-1013 all confirmed pure status notifications, AudioCmd 14 PHONECALL_Incoming, FactorySetting 0x77 corrected to BOTH direction, app handling gap audit, Messages-That-MUST-NEVER-Trigger-Disconnect table. Prior: dual magic, CMD_ENABLE_CRYPT lifecycle, all undocumented types, corrected RemoteDisplay→BroadCastRemoteCxCy)

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
| **magic** | 0 | 4 | `0x55AA55AA` (cleartext) or `0x55BB55BB` (AES-encrypted payload) — see Dual Magic below |
| **length** | 4 | 4 | Payload size in bytes (LE) |
| **type** | 8 | 4 | Message type ID (LE) |
| **type_check** | 12 | 4 | `type XOR 0xFFFFFFFF` (validation) |
| **payload** | 16 | N | Message-specific data |

**Validation Rules:**
- Magic must equal `0x55AA55AA` or `0x55BB55BB`
- Type check must equal `type ^ 0xFFFFFFFF`
- Length must be ≤ 1048576 bytes
- Total message size = 16 + payload_length

**Dual Magic System (Binary Verified Feb 2026):**

| Magic | Meaning | When Used |
|-------|---------|-----------|
| `0x55AA55AA` | Cleartext payload | Default; always for types 0x06, 0x07, 0x2A, 0x2C (performance-exempt) |
| `0x55BB55BB` | AES-CBC encrypted payload | All other types when encryption is enabled via CMD_ENABLE_CRYPT |

Assembly proof at `fcn.000645ec` (`0x645f4`): `add.w r2, r2, 0x110011` transforms 0x55AA55AA → 0x55BB55BB.
Write path at `fcn.00064630` normalizes BB magic back to AA after decryption.

**Encryption-exempt types** (always use AA magic for performance):
- `0x06` VideoFrame — H.264 video stream
- `0x07` AudioFrame — PCM audio data
- `0x2A` DashBoard_DATA — Media/nav metadata
- `0x2C` AltVideoFrame — Navigation video stream

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

**AudioData (0x07) Commands:** When payload is 13 bytes, it contains an audio command (not PCM data), structured as `[decodeType:4][volume:4][audioType:4][command:1]`. **IMPORTANT:** Siri and phone call events are received via AudioData (0x07), NOT Command (0x08). See the **Inbound Message Handling Reference** section below for the complete audio command table (cmds 1-14) with firmware triggers and host actions, or `audio_protocol.md` for the authoritative reference with decode_type and audio_type per command.

**Audio Formats (decodeType):** Values 2 (44.1kHz stereo / commands), 4 (48kHz stereo), 5 (16kHz mono). **audio_type** values: 1=Media, 2=Nav, 3=Mic. For the complete decodeType format mapping, dual-purpose decode_type=2 behavior, and audio stream routing details, see `audio_protocol.md`.

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
| `NaviStatus` | int | 0=inactive/flush, 1=active |
| `NaviTimeToDestination` | int | ETA in seconds |
| `NaviDestinationName` | string | Destination name |
| `NaviDistanceToDestination` | int | Total remaining distance (meters) |
| `NaviAPPName` | string | Navigation app (e.g., "Apple Maps") |
| `NaviRemainDistance` | int | Distance to next maneuver (meters) |
| `NaviRoadName` | string | Extracted from iAP2 ManeuverDescription (⚠ duplicated key bug) |
| `NaviOrderType` | int | Turn order type (enum; observed 6, 16 — range wider than initially assumed) |
| `NaviManeuverType` | int | CPManeuverType 0–53 |
| `NaviTurnAngle` | int | Enum/type value, NOT degrees (only sent for non-roundabout turns) |
| `NaviTurnSide` | int | Driving side (0=RHD, 1=LHD, 2=observed undocumented value) |
| `NaviRoundaboutExit` | int | Exit number 1–19 (only sent for roundabout maneuvers) |

**Fields NOT forwarded (live-verified Feb 2026):**
- `NaviJunctionType` — never appears in any observed NaviJSON message
- `JunctionElementAngle` / `JunctionElementExitAngle` — dropped by adapter parser (ASSERT on dict types)
- `AfterManeuverRoadName` — stripped
- Lane guidance — stripped

**Example NaviJSON Payloads (Live Capture Feb 2026):**

Roundabout exit 1 (ManeuverIdx advance):
```json
{"NaviRoadName":"W Main St","NaviRoadName":"W Main St","NaviOrderType":16,"NaviRoundaboutExit":1,"NaviManeuverType":28}
```

Roundabout exit 2 (ManeuverIdx advance):
```json
{"NaviRoadName":"W Main St","NaviRoadName":"W Main St","NaviOrderType":16,"NaviRoundaboutExit":2,"NaviManeuverType":29}
```

Right turn maneuver (ManeuverIdx advance):
```json
{"NaviRoadName":"De Armoun Rd","NaviRoadName":"De Armoun Rd","NaviOrderType":6,"NaviTurnAngle":2,"NaviTurnSide":2,"NaviManeuverType":2}
```

Distance countdown (~1/sec):
```json
{"NaviRemainDistance":239}
```

Route status (periodic):
```json
{"NaviStatus":1,"NaviRemainDistance":245}
```

**Multi-Roundabout Capture (W Main St Route, Feb 2026):**
12 consecutive roundabouts captured. ALL had: `NaviOrderType=16`, `turnAngle=0`, `turnSide=0`, `junction=0`.
Only CPTypes 28 (exit 1) and 29 (exit 2) observed. iPhone sent `paramCount=21` per 0x5201;
adapter forwards only ~5 fields. AAOS cluster showed wrong icons for every roundabout due to
missing exit angle data — generic glyph per exit number cannot represent actual roundabout geometry.

**Message delivery pattern:** On ManeuverIdx change, adapter emits TWO _SendNaviJSON calls
back-to-back: first with `NaviRemainDistance`, second with maneuver-specific fields. Distance-only
updates are single messages at ~1/sec.

**iAP2 Source Messages:**
- `0x5200` StartRouteGuidanceUpdate - Adapter requests TBT data
- `0x5201` RouteGuidanceUpdate - Route status from iPhone (~1/sec, triggers ManeuverIdx advances)
- `0x5202` RouteGuidanceManeuverUpdate - Full maneuver list burst on route start (~20 messages in 200ms)

**Firmware Evidence (ARMiPhoneIAP2):**
- JSON fields: `MediaSongName`, `MediaAlbumName`, `MediaArtistName`, `MediaAPPName`, `MediaSongDuration`, `MediaSongPlayTime`
- Artwork: `mediaItemArtworkFileTransferIdentifier`, `CiAP2MediaPlayerEngine_Send_NowPlayingMeidaArtwork`
- Navigation: `iAP2RouteGuidanceEngine`, `_SendNaviJSON`, `NaviStatus`, `NaviDestinationName`
- Parser limitation: `iAP2UpdateEntity.cpp:314` ASSERTs on unknown dict/group field types, silently drops them

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

### Navigation Focus (iOS 13+) [CarPlay only]

These are standalone message types for CarPlay navigation video focus, distinct from the Android Auto focus command IDs 500-509 (which are sent as Command 0x08 payloads -- see `command_ids.md`).

| Type | Hex | Name | Dir | Payload | Description |
|------|-----|------|-----|---------|-------------|
| 506 | 0x1FA | NaviFocus | OUT | 0 | Request nav focus |
| 507 | 0x1FB | NaviRelease | OUT | 0 | Release nav focus |
| 508 | 0x1FC | RequestNaviScreenFocus | BOTH | 0 | Adapter may send (IN); host can echo back (OUT). See Navigation Video section — 508 handshake requirement is **inconclusive**. |
| 509 | 0x1FD | ReleaseNaviScreenFocus | OUT | 0 | Release nav screen |
| 110 | 0x6E | NaviFocusRequest | IN | 0 | Nav requesting focus |
| 111 | 0x6F | NaviFocusRelease | IN | 0 | Nav released focus |

### WiFi/Bluetooth Connection Status (Binary Verified Feb 2026)

Commands 1000-1013 are **pure status notifications** sent by the adapter to report WiFi/Bluetooth internal state. They never indicate session failure and must never trigger disconnect. CMD 1010 (DeviceWifiNotConnected) is the most commonly misinterpreted -- it is a WiFi hotspot status check, completely irrelevant for USB CarPlay sessions. Use `Unplugged` (0x04) or `Phase 0` for session termination detection.

For the complete 1000-1013 table with firmware trigger functions and binary evidence, see the **Inbound Message Handling Reference** section below (Messages That MUST NEVER Trigger Disconnect) and `command_details.md`.

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

**Command ID Range Summary:**

| Range | Category | Direction | Count | Protocol Context |
|-------|----------|-----------|-------|------------------|
| 1-31 | Basic Session Control | Various | 20 | Universal (CarPlay + Android Auto) |
| 100-106 | D-Pad Navigation | H→A→P | 7 | CarPlay-specific |
| 111-114 | Rotary Knob | H→A→P | 4 | CarPlay-specific |
| 200-205 | Media Playback | H→A→P | 6 | CarPlay-specific |
| 300-314 | Phone/DTMF | H→A→P | 15 | CarPlay-specific |
| 400-403 | App Launch | H→A→P | 4 | CarPlay-specific |
| 410-412 | UI Control | H→A→P | 3 | CarPlay-specific |
| 500-509 | Focus (Android Auto) | A→H | 8 | **Android Auto only** -- manages video/audio/nav focus |
| 600-601 | DVR (Dead Code) | H→A | 2 | Dead code in FW 2025.10 |
| 700-702 | Custom Commands | H→A→P | 3 | CarPlay-specific |
| 1000-1013 | Connection Status | Both | 14 | Universal -- pure status notifications, never trigger disconnect |

For the complete per-ID command listing, see `command_ids.md`. For per-command binary evidence and firmware handler details, see `command_details.md`.

### BoxSettings (0x19) - JSON Configuration

> **[Protocol]** BoxSettings (0x19) is a JSON message sent H→A during initialization. For the firmware's internal handling of these fields, see `configuration.md`. For implementation guidance with code examples, see `host_app_guide.md`.

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

**Navigation Video (iOS 13+ — activated by sending `naviScreenInfo` in BoxSettings) [CarPlay only]:**

| Field | Type | Description |
|-------|------|-------------|
| `naviScreenInfo` | object | Nested object for nav video config |
| `naviScreenInfo.width` | int | Nav screen width (default: 480) |
| `naviScreenInfo.height` | int | Nav screen height (default: 272) |
| `naviScreenInfo.fps` | int | Nav screen FPS (default: 30, range: 10-60, recommended: 24-60) |

**Android Auto Mode [Android Auto only]:**

| Field | Type | Description |
|-------|------|-------------|
| `androidWorkMode` | int | Phone link daemon mode: 0=Idle, 1=AA, 2=CarLife, 3=Mirror, 4=HiCar, 5=ICCOA — resets to 0 on disconnect |
| `androidAutoSizeW` | int | Android Auto video width (also in Display/Video above) |
| `androidAutoSizeH` | int | Android Auto video height (also in Display/Video above) |

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
| `/tmp/gnss_info` | NMEA text | GPS data for CarPlay navigation -- see GnssData (0x29) section below for full pipeline |
| `/tmp/carplay_mode` | Value | CarPlay mode setting |
| `/tmp/manual_disconnect` | Flag | Manual disconnect trigger |
| `/etc/android_work_mode` | 0-5 | **Critical**: Phone link daemon mode selector (0=Idle, 1=AA, 2=CarLife, 3=Mirror, 4=HiCar, 5=ICCOA) |
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

> **[Protocol/Firmware]** This section documents the GnssData (0x29) message format and firmware GPS pipeline. For host app implementation with code examples, see `host_app_guide.md`. For StartGNSSReport/StopGNSSReport command details, see `command_details.md`.

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

> **Protocol context:** CarPlay and Android Auto use different GPS delivery paths. **CarPlay** receives GPS via iAP2 LocationInformation (NMEA → `CiAP2LocationEngine` → iPhone). **Android Auto** receives GPS via protobuf `gps_location` messages (NMEA → `ARMAndroidAuto` → phone), but the adapter's timestamp derivation is broken (see Android Auto GPS Path below).

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

### Host-Visible Phases (sent to host via USB)

| Value | Meaning | Status | Evidence |
|-------|---------|--------|----------|
| 0 | Session Terminated / Idle | VERIFIED | Used for session end detection |
| 7 | Connecting / Negotiating | VERIFIED | `@ 4.498s: Phase payload 07 00 00 00` |
| 8 | Streaming Ready / Active | VERIFIED | `@ 7.504s: Phase payload 08 00 00 00` |
| 13 | Session Negotiation Failed | VERIFIED | AirPlay negotiation rejected — typically viewArea/safeArea constraint violation (`safeArea ⊆ viewArea ⊆ display`). Adapter internally sees Phase 0 (disconnect) between Phase 2 and 103. Triggers Phase 7→13 reconnect loop. Observed Feb 2026 when HU_VIEWAREA_INFO dimensions exceeded OPEN resolution. |

### Internal Firmware Phases (Binary Verified Feb 2026)

These phases are used internally by the adapter firmware for CarPlay session management. The host sees only phases 0, 7, and 8 over USB. The internal phases drive the adapter's state machine:

| Phase | Name | Internal Action |
|-------|------|-----------------|
| 0 | DISCONNECTED | Kill AppleCarPlay/ARMiPhoneIAP2, cleanup |
| 1 | CONNECTED | Set state=3, prepare session |
| 2 | SCREEN_INFO | Send Phase(type=3, value=7) to host |
| 3 | STREAMING | Send Phase(type=3, value=8) to host, start streaming |
| 100 | WIRELESS_PROBE | HNP check for wired, screen info relay |
| 101 | USB_HNP_OK | Log `"PHASE_USBHNPOK!"`, launch AppleCarPlay binary |
| 102 | KILL | Kill AppleCarPlay, reset session |
| 103 | UI_CALLBACK | UI callback handler |
| 104 | RESET_WIFI | Log `"PHASE_ResetWiFi!!!!"`, restart WiFi |
| 105 | PHONE_CALL_START | Phone call audio routing setup |
| 106 | PHONE_CALL_END | End phone call audio routing |
| 200 | AUTO_CONNECT_TRIGGER | Triggers auto-connect sequence |

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

## Navigation Video Setup (iOS 13+)

### What Is Required (Testing Verified Feb 2026)

Navigation video (Type 0x2C AltVideoFrame) is activated by **sending `naviScreenInfo` in BoxSettings**. This is the only confirmed requirement. The firmware parses `naviScreenInfo` at `0x16e5c` and immediately branches to the `HU_SCREEN_INFO` path at `0x170d6`, **bypassing** the `AdvancedFeatures` config check entirely.

```json
{
  "naviScreenInfo": {
    "width": 480,
    "height": 240,
    "fps": 30
  }
}
```

**Binary proof (ARMadb-driver `0x16e5c`):**
```arm
0x16e5c  blx fcn.00015228          ; parse "naviScreenInfo" from JSON
0x16e62  cmp r0, 0                 ; key found?
0x16e64  bne.w 0x170d6             ; YES → HU_SCREEN_INFO path (BYPASSES AdvancedFeatures)
0x16e68  ldr r0, "AdvancedFeatures" ; only reached if naviScreenInfo NOT found
0x16e6c  bl fcn.00066d3c           ; read config value
0x16e70  cmp r0, 0                 ; AdvancedFeatures == 0?
0x16e72  bne 0x16f20               ; if ≠ 0 → legacy path
0x16e7c  "Not support NaviScreenInfo, return\n"
```

**Activation Matrix:**

| `naviScreenInfo` in BoxSettings | `AdvancedFeatures` | Result |
|--------------------------------|-------------------|--------|
| **Yes** | 0 | **Works** — HU_SCREEN_INFO path (bypasses check) |
| **Yes** | 1 | Works — same path |
| No | 1 | Works — legacy HU_NAVISCREEN_INFO path |
| No | 0 | Rejected — "Not support NaviScreenInfo, return" |

### What Was Disproven

**`AdvancedFeatures=1` is NOT required.** Earlier documentation stated `AdvancedFeatures=1` was needed for navigation video. Live testing confirmed this is false — sending `naviScreenInfo` in BoxSettings is sufficient regardless of the `AdvancedFeatures` config value.

### Command 508 Handshake (INCONCLUSIVE)

The firmware binary shows a 508 (RequestNaviScreenFocus) command path where the adapter sends 508 to the host, and the `pi-carplay` reference implementation echoes 508 back. However, **live testing with CarLink Native could not conclusively determine whether the 508 echo is required** for navigation video to start.

**What the binary shows:**
- Adapter sends cmd 508 to host during session setup
- If host echoes 508 back, adapter emits `HU_NEEDNAVI_STREAM` D-Bus signal
- `pi-carplay` implementation does echo 508 back

**What testing showed:**
- Navigation video worked with `naviScreenInfo` configured
- The 508 handshake's necessity could not be isolated as the sole factor

**Recommendation:** Echo 508 back if received (low cost, may be required in some firmware paths), but do not consider it the primary activation mechanism. The primary mechanism is `naviScreenInfo` in BoxSettings.

**Reference implementation** (`pi-carplay-main/src/main/carplay/services/CarplayService.ts:270-277`):
```typescript
if ((msg.value as number) === 508 && this.config.naviScreen?.enabled) {
  this.driver.send(new SendCommand('requestNaviScreenFocus'))
}
```

---

## Previously Undocumented Message Types — NOW IDENTIFIED (Binary Verified Feb 2026)

All types previously listed as "unknown" have been identified via ARMadb-driver binary disassembly.
Full operational details for each type follow the summary table.

| Type | Hex | Binary Name | Direction | Payload | Status in FW 2025.10 |
|------|-----|-------------|-----------|---------|---------------------|
| 11 | 0x0B | CMD_CARPLAY_MODE_CHANGE | A→H only | Variable (mode struct) | ACTIVE — forwarded from iPhone CarPlay stack |
| 16 | 0x10 | CMD_CARPLAY_AirPlayModeChanges | A→H only | Variable (mode data) | ACTIVE — forwarded from iPhone AirPlay |
| 17 | 0x11 | AutoConnect_By_BluetoothAddress | BOTH | BT MAC string | ACTIVE — auto-reconnect trigger |
| 19 | 0x13 | CMD_BLUETOOTH_ONLINE_LIST | BOTH | Variable (device list) | ACTIVE — BT device enumeration |
| 27 | 0x1B | BTAudioDevice_Signal | H→A | Unknown | **DEAD CODE** — logged and discarded, no handler |
| 30 | 0x1E | Bluetooth_Search (IN) / BroadCastRemoteCxCy (OUT) | **DUAL** | IN: unused / OUT: 28B (resolution) | ACTIVE outbound, NO-OP inbound |
| 119 | 0x77 | FactorySetting | BOTH | A→H: empty (idle notification) / H→A: 4B (factory reset) | ACTIVE — dual-purpose |
| 136 | 0x88 | CMD_DEBUG_TEST | H→A | 4B (subcommand) | ACTIVE — log capture/retrieval |
| 161 | 0xA1 | ICCOA Open/Info | A→H | 12-24B | ACTIVE — ICCOA protocol only (not CarPlay/AA) |
| 205 | 0xCD | HUDComand_A_Reboot | H→A | None | ACTIVE — full system reboot |
| 206 | 0xCE | HUDComand_A_ResetUSB | H→A | None | ACTIVE — USB gadget reset |
| 240 | 0xF0 | CMD_ENABLE_CRYPT | H→A (trigger) / A→H (ack) | H→A: 4B crypto_mode / A→H: empty | ACTIVE — see full lifecycle below |
| 253 | 0xFD | HUDComand_D_Ready | H→A | None (ignored) | ACTIVE — display-ready signal |
| 255 | 0xFF | CMD_ACK | A→H | None | ACTIVE — Open session acknowledgment |

**Note on 0xF0 (CMD_ENABLE_CRYPT):** This is a standalone message type, NOT a sub-command of type 0x08. The host sends 0xF0 with a 4-byte crypto_mode value; the adapter echoes an empty 0xF0 back as acknowledgment. See full lifecycle below.

**Note on 0xF1 (IPC_REGISTRATION):** Type 0xF1 is used for MiddleMan IPC registration/keepalive between internal processes. Proved at `0x64326`: `movs r1, 0xF1`. **Not sent over USB** — internal only.

**Updated total: 51 message types** (49 USB dispatch + 0xF0 CMD_ENABLE_CRYPT + 0xF1 IPC_REGISTRATION internal).

### FactorySetting (0x77) - Dual-Purpose (Binary Verified Feb 2026)

**Direction:** BOTH
**Binary Name:** `FactorySetting`

**A→H (Adapter → Host) — Idle Notification:**
**Payload:** None (header only, 16 bytes total)

```
+------------------+------------------+------------------+------------------+
|   0x55AA55AA     |   0x00000000     |   0x00000077     |   0xFFFFFF88     |
|   (magic)        |   (length=0)     |   (type=119)     |   (type check)   |
+------------------+------------------+------------------+------------------+
```

Sent by adapter to host during idle state (observed ~33s into session with no active streaming). Acts as adapter-side "still waiting" signal. Host may log receipt but should take NO action.

**Capture Evidence:**
```
[    33.693s] <<< IN  #13 | Unknown(0x77) | 16 bytes
  Header: aa 55 aa 55 00 00 00 00 77 00 00 00 88 ff ff ff
```

**H→A (Host → Adapter) — Factory Reset Command:**
**Payload:** 4 bytes (reset command data)

Triggers factory reset on the adapter. The handler at `kRiddleHUDComand_CommissionSetting` deletes `/etc/riddle.conf`, restores from `/etc/riddle_default.conf`, clears BT pairings, resets WiFi settings, and generates a new device serial. **Use with extreme caution.**

**What the host should do:**
- **On receive (A→H):** Log as informational. Do NOT trigger disconnect or state change.
- **On send (H→A):** Only send for deliberate factory reset. Expect adapter reboot afterwards.

### CMD_ENABLE_CRYPT (0xF0) — Full Encryption Lifecycle (Binary Verified Feb 2026)

**Direction:** Bidirectional — Host sends trigger (4-byte payload), Adapter echoes empty ack
**Dispatch:** `method.CAccessory_fd.virtual_24` at `0x1deea` → handler at `0x1f798`
**NOT in `fcn.00017b74` dispatch table** — falls to "Not support decrypt cmd" log, then encryption processing path

**⚠️ CORRECTION (Feb 2026):** Previous documentation listed a "RemoteDisplay" type at 0xF0. This was incorrect — the string "RemoteDisplay" does NOT exist in the binary. Type 0xF0 is used exclusively for CMD_ENABLE_CRYPT. The `0x1af48` address previously cited as a function is actually a **data table** (string pointer array for command name logging).

#### Enable Protocol

**Step 1 — Host sends 0xF0 with crypto_mode:**
```
Offset  Size  Field        Description
------  ----  -----        -----------
0x00    4     crypto_mode  uint32, must be > 0 to enable encryption
```
Host sends: `[magic:55AA55AA][len:04000000][type:F0000000][check:0FFFFFFF][mode:01000000]`

**Step 2 — Adapter validates (at `0x1f798`):**
```arm
0x1f798  ldr r3, [r6, 4]       ; payload length
0x1f79a  cmp r3, 4             ; must be exactly 4 bytes
0x1f79c  bne 0x1f7d6           ; REJECT if wrong size
0x1f79e  ldr r3, [r6, 0x10]   ; payload pointer
0x1f7a0  ldr r3, [r3]         ; crypto_mode value
0x1f7a2  cmp r3, 0
0x1f7a4  ble 0x1f7d6           ; REJECT if value <= 0
```

**Step 3 — Adapter sends empty 0xF0 ack (at `0x1f7a6`):**
```arm
0x1f7a8  bl fcn.00064650       ; init message (magic=0x55AA55AA)
0x1f7ae  movs r1, 0xf0        ; type = 0xF0
0x1f7b0  movs r2, 0           ; payload = NULL (empty)
0x1f7b2  bl fcn.00064670       ; set message header
0x1f7bc  bl fcn.00018598       ; SEND empty 0xF0 back to host
```
Host receives: `[magic:55AA55AA][len:00000000][type:F0000000][check:0FFFFFFF]`

**Step 4 — Adapter stores crypto_mode globally (at `0x1f7ca`):**
```arm
0x1f7c8  ldr r3, [r3]         ; reload crypto_mode from payload
0x1f7ca  str r3, [r4]         ; WRITE to global at 0x11f408 (.bss)
```
Log: `"setUSB from HUCMD_ENABLE_CRYPT: %d\n"`

**Step 5 — All subsequent messages are encrypted/decrypted:**
The global at `0x11f408` is read by `fcn.00017b74` (4 read sites: `0x1DDBC`, `0x17B96`, `0x17D4A`, `0x18618`). When `> 0`, all messages with payload pass through AES-128-CBC, EXCEPT exempt types.

#### State Machine

```
┌─────────────────────────────────────────────────────┐
│  ENCRYPTION OFF (default, global=0)                 │
│  All messages use magic 0x55AA55AA (cleartext)      │
│                                                     │
│  Host sends 0xF0 [crypto_mode > 0]                  │
│       ↓                                             │
│  Adapter validates (4 bytes, value > 0)             │
│       ↓                                             │
│  Adapter echoes empty 0xF0 ack                      │
│       ↓                                             │
│  Adapter stores crypto_mode to global 0x11f408      │
│       ↓                                             │
│  ENCRYPTION ON (global > 0)                         │
│  Non-exempt messages: magic=0x55BB55BB + AES-CBC    │
│  Exempt types (0x06,0x07,0x2A,0x2C): still 55AA    │
│                                                     │
│  ⚠ CANNOT be disabled mid-session                   │
│  Only reset via adapter reboot (0x11f408 → 0)       │
└─────────────────────────────────────────────────────┘
```

#### AES Key Derivation (at `0x17d9e`-`0x17dd0`)

**Base key:** `"SkBRDy3gmrw1ieH0"` (16 bytes at `0x6d0d4`)

**Derived key:** Rotated by `crypto_mode % 16`:
```
derived_key[i] = base_key[(i + crypto_mode) % 16]
```
Only 16 possible key rotations for a known hardcoded key — **obfuscation, not security**.

**IV construction:** 16 zero bytes, then scatter `crypto_mode` bytes:
```
iv[1]  = (crypto_mode >>  0) & 0xFF
iv[4]  = (crypto_mode >>  8) & 0xFF
iv[9]  = (crypto_mode >> 16) & 0xFF
iv[12] = (crypto_mode >> 24) & 0xFF
```

**AES call:** `AES_cbc_encrypt(payload, payload, len, schedule, iv, direction)`
- Direction 1 = encrypt (outbound, host→adapter: r8≠0)
- Direction 0 = decrypt (inbound, adapter→host: r8=0)

#### Encryption Bypass (at `0x17d60`-`0x17d72`)

```arm
0x17d60  ldr r3, [r4, 8]       ; message type
0x17d62  subs r2, r3, 6
0x17d64  cmp r2, 1             ; types 6-7 → SKIP
0x17d68  cmp r3, 0x2c          ; type 0x2C → SKIP
0x17d6c  cmp r3, 0x2a          ; type 0x2A → SKIP
0x17d72  bl fcn.00064614       ; all others → SET 0x55BB55BB magic
```

| Type | Encrypted? | Reason |
|------|-----------|--------|
| 0x06 VideoFrame | NO | Bandwidth — would add unacceptable latency |
| 0x07 AudioFrame | NO | Bandwidth — real-time audio |
| 0x2A DashBoard_DATA | NO | Performance — frequent metadata updates |
| 0x2C AltVideoFrame | NO | Bandwidth — navigation video |
| All others | YES | Payload encrypted with AES-128-CBC |

#### Security Assessment

| Property | Value | Risk |
|----------|-------|------|
| Key space | 16 rotations of known key | CRITICAL — trivially brute-forceable |
| IV | Deterministic from crypto_mode | HIGH — same mode = same IV |
| Key visibility | Hidden in UPX-packed binary | LOW — visible once unpacked |
| HW acceleration | `/dev/hwaes` available | Performance mitigated |
| Disable mechanism | None (one-way enable) | Session must restart |
| Shared across all adapters | Yes — same hardcoded key | CRITICAL — one key for all units |

### CMD_CARPLAY_MODE_CHANGE (0x0B) — Operational Details (Binary Verified Feb 2026)

**Direction:** Adapter → Host ONLY (inbound from host is logged as unsupported and dropped)
**Dispatch:** `method.CAccessory_fd.virtual_24` — forwarded from iPhone CarPlay stack
**Payload:** Variable — raw CarPlay mode data from iPhone

**When sent:** iPhone transitions between work modes. The adapter's `OniPhoneWorkModeChanged` handler (`fcn.000176bc`) fires on:

| Mode | Name | Meaning |
|------|------|---------|
| 0 | None/Idle | No active projection |
| 1 | AirPlay | Wireless audio streaming |
| 2 | CarPlay | Display projection active |
| 3 | iOSMirror | Screen mirroring |
| 4 | OnlyCharge | Phone charging, no projection |

**Triggering code paths:**
1. CarPlay data sub-type `0x64` (100) at `0x219c4` — direct mode forwarding
2. WorkMode transition with state==6 at `0x1f646` — state-to-type mapping
3. `StartPhoneLink` at `0x1cc00` — sends with 0x2C-byte payload containing linkType/transportType

**What the host should do:** Parse the mode byte. On mode 0→2 (idle→CarPlay): prepare video/audio pipelines. On mode 2→0 (CarPlay→idle): tear down pipelines, prepare for disconnect. On mode change to 1 (AirPlay): switch to audio-only routing.

### CMD_CARPLAY_AirPlayModeChanges (0x10) — Operational Details (Binary Verified Feb 2026)

**Direction:** Adapter → Host ONLY
**Dispatch:** Forwarded from CarPlay data sub-type `0x65` (101) at `0x219be`
**Payload:** Variable — AirPlay mode state from iPhone

**When sent:** iPhone transitions to/from AirPlay audio streaming mode. This is distinct from 0x0B (CarPlay mode) — AirPlay is audio-only wireless, no display.

**What the host should do:** Track AirPlay state. When active, the audio pipeline receives AirPlay-encoded audio rather than standard CarPlay media audio. The host may need different decoding or routing paths.

**Firmware validation:** `"NoAirPlay recv data size error!"` at `0x6dd9a` — adapter validates data size before forwarding.

### AutoConnect_By_BluetoothAddress (0x11) — Operational Details (Binary Verified Feb 2026)

**Direction:** Bidirectional (primarily Host → Adapter)
**Payload:** Null-terminated Bluetooth MAC address string (e.g., `"AA:BB:CC:DD:EE:FF"`)

**When sent (A→H):** Adapter sends as `"Ignal PhoneCommand: AutoConnect_By_BluetoothAddress(0x11)"` at `0x18fba`. This requests the host to initiate BT pairing to the given address.

**When sent (H→A):** Host provides a BT MAC for the adapter to auto-connect to on subsequent sessions. Config `NeedAutoConnect` must be `1`.

**Auto-connect flow (at `fcn.00022140`):**
1. Adapter detects link type via `fcn.00069874` (returns `0x1E` for BT)
2. Reads `NeedAutoConnect` config flag
3. If enabled AND USB device type known AND `HU_LINK_TYPE` set:
4. Reads stored BT MAC and initiates connection
5. Log: `"Detect link type by AutoConnect!!!"` at `0x6f606`

**Related commands:** `SetBluetoothAddress` (type 0x0A) sets the address; `ForgetBluetoothAddr` clears it.

### CMD_BLUETOOTH_ONLINE_LIST (0x13) — Operational Details (Binary Verified Feb 2026)

**Direction:** Bidirectional
**Payload (A→H):** Variable — list of Bluetooth devices from adapter's BT daemon

**When sent (A→H):** After querying BT daemon via D-Bus (`org.riddle.BluetoothControl` at `0x739a0`, `/RiddleBluetoothService` at `0x73c04`). Triggered by:
1. Phone command `0x3F5` (`GetBluetoothOnlineList`) at `0x19514`
2. `OnBluetoothDaemonOn` callback at `0x6dd14` (BT subsystem init)
3. BT connection state changes (`DeviceBluetoothConnected`/`NotConnected`/`PairStart`)

**CarPlay data forwarding (at `0x21592`):** When receiving sub-type 0x13 from iPhone:
- If payload > 12 bytes: extracts 64-bit value, stores at `[0x11f498]` (BT list offset)
- If payload == 8 bytes: reads screen size values, logs `"recv CarPlay altScreen size info: %dx%d"`
- If CarPlay active (state==5): constructs a type 0x2C message with resolution data

**Firmware validation:** `"Invalid Bluetooth DeviceID! - %s(%d)"` at `0x6ee27`

**What the host should do:** Parse BT device list for display or auto-connect decisions.

### BTAudioDevice_Signal (0x1B) — Operational Details (Binary Verified Feb 2026)

**Direction:** Host → Adapter
**Status: DEAD CODE** in firmware 2025.10.15.1127

The adapter recognizes type 0x1B in the dispatch table at `0x17ba0` → `0x17d46`, but the handler only loads the string `"BTAudioDevice_Signal"` and falls through to `"Not support decrypt cmd: %s !!!"` at `0x17d3a`. No payload processing occurs.

**Purpose (historical):** Likely a placeholder for HFP/A2DP Bluetooth audio handoff coordination that was never implemented. May be supported in other adapter models or future firmware versions.

**What the host should do:** Do NOT send this type. It will be logged and discarded.

### CMD_DEBUG_TEST (0x88) — Operational Details (Binary Verified Feb 2026)

**Direction:** Host → Adapter
**Dispatch:** `method.CAccessory_fd.virtual_24` at `0x1deac` → handler at `0x1f6e0`
**Payload:** Exactly 4 bytes — `uint32 subcommand`

```
Offset  Size  Field         Description
------  ----  -----         -----------
0x00    4     subcommand    1=open log, 2=read log, 3=enable periodic
```

| Sub-cmd | Action | Detail |
|---------|--------|--------|
| **1** | Start log capture | Checks `/tmp/userspace.log` exists, sets log-active flag, runs `system("/script/open_log.sh &")` |
| **2** | Read & send log | Opens `/tmp/userspace.log` with `fopen("rb")`, reads up to 16MB, packages as **type 0x99** message, sends back to host |
| **3** | Enable periodic | Sets global flag at `[0x11f3d0]=1`, starts timer (interval 50 ticks) for continuous log streaming |

**Usage sequence:**
1. Send `0x88` with `[01 00 00 00]` — start log capture
2. Wait for log data to accumulate
3. Send `0x88` with `[02 00 00 00]` — retrieve log
4. Listen for **type 0x99** (SendFile) response containing firmware log contents
5. Optionally: Send `0x88` with `[03 00 00 00]` for continuous streaming

### HUDComand_A_Reboot (0xCD) — Operational Details (Binary Verified Feb 2026)

**Direction:** Host → Adapter
**Dispatch:** `method.CAccessory_fd.virtual_24` at `0x1dee0` → `fcn.00067b18`
**Payload:** None (empty — type alone triggers action)

**What happens:**
1. Logs `"Reboot box reason: kRiddleHUDComand_A_Reboot"`
2. Checks a suppression flag file — if flag == 1, returns without rebooting
3. If not suppressed, saves diagnostic logs:
   - `echo "Save last log when reboot" > /var/log/box_last_reboot.log`
   - `dmesg | tail -n 2000 >> /var/log/box_last_reboot.log`
   - `tail -n 2000 /tmp/userspace.log >> /var/log/box_last_reboot.log`
4. Executes: `sync; sleep 1; reboot`

**What the host should do:** After sending, treat the connection as dead. Expect USB device detach followed by re-enumeration after reboot (~10-15 seconds). Enter DISCONNECTED state and wait for USB re-attach.

### HUDComand_A_ResetUSB (0xCE) — Operational Details (Binary Verified Feb 2026)

**Direction:** Host → Adapter
**Dispatch:** `method.CAccessory_fd.virtual_24` at `0x1dece` → `0x1f786`
**Payload:** None (empty)
**Log:** `"$$$ ResetUSB from HU\n"` at `0x1f78a`

**What happens (at `fcn.0001c048`):**
1. Checks device-opened flag at `[0x11f4a0+0x14]`
2. If `/tmp/ram_fat32.img` exists, writes: `echo 0 > /sys/class/android_usb_accessory/android0/enable`
3. This **disables the USB gadget**, forcing a USB-level disconnect
4. Sets USB-resetting flag at `[obj+0x33c]=1`
5. Resets two global state objects at `0x11f4a0` and `0x11f43c`
6. If WiFi active (mode==2): runs `/script/close_bluetooth_wifi.sh; sleep 6` (also resets wireless)

**Softer than 0xCD (Reboot):** Only tears down the USB link, does not reboot the entire adapter. The adapter process continues running.

**What the host should do:** Expect USB detach event, then re-attach. Enter DISCONNECTED state. If in wireless mode, expect both USB and WiFi to drop.

**Related:** `"AutoResetUSB"` string at `0x6c8f3` — the adapter can also trigger USB reset internally.

### HUDComand_D_Ready (0xFD) — Operational Details (Binary Verified Feb 2026)

**Direction:** Host → Adapter
**Dispatch:** `method.CAccessory_fd.virtual_24` at `0x1def0` → handler at `0x1dfbe`
**Payload:** None (payload is ignored — the message type alone is the signal)

**What happens (at `0x1dfbe`):**
1. If `this->0x68` is non-NULL: calls `fcn.00023dce` which flushes/releases RAM disk at `/tmp/ram_fat32.img`
2. Sets global byte flag at `0x96ea8` to `1` — the **display-ready** indicator

**When to send:** After the host's video surface/decoder is initialized and ready to receive frames. The "D" in the name likely stands for "Display".

**What the host should do:** Send type 0xFD (empty payload) as a fire-and-forget notification. No response from adapter. The adapter may gate video frame transmission on this flag.

### CMD_ACK (0xFF) — Operational Details (Binary Verified Feb 2026)

**Direction:** Adapter → Host
**Dispatch:** Type 0xFF is NOT in the main dispatcher's binary search tree. It is only recognized in the name-resolver at `0x17c6e`:
```arm
0x17c6e  cmp r3, 0xff
0x17c72  mov r3, "CMD_ACK"     ; if 0xFF
0x17c74  mov r3, "Unkown..."   ; else (any other unknown type)
```

**Payload:** None (empty — header only)

**When sent:** The adapter sends 0xFF **after processing type 0x05 "Open"** for CarPlay session types (at `0x1e110`). It is the session-open acknowledgment.
```arm
0x1e110  movs r3, 0xff        ; type = CMD_ACK
0x1e112  str r3, [sp, 0x88]   ; set type in message header
0x1e114  bl fcn.00018598       ; send to host
```

**What the host should do:** Receiving 0xFF confirms the adapter has accepted the Open command and is ready for streaming. Proceed with video/audio configuration. If 0xFF is not received after sending Open, the session establishment may have failed.

### BroadCastRemoteCxCy (0x1E outbound) — Operational Details (Binary Verified Feb 2026)

**Direction:** Adapter → Host (outbound only — inbound 0x1E is "Bluetooth_Search", unsupported/no-op)
**Function:** `fcn.0001b574` (`BroadCastRemoteCxCy`)
**Payload:** 28 bytes (inner type 0xF0 + resolution struct from `0x11f3d4`)

**⚠️ CORRECTION (Feb 2026):** Previous documentation listed a separate "RemoteDisplay" type 0xF0. This was incorrect — the 0xF0 value inside this message is an **inner/sub-type**, NOT a separate USB message type. On the wire, this message has type **0x1E**. The string "RemoteDisplay" does NOT exist in the binary.

**When sent:**
1. **During Open handshake** (`ProcessCmdOpen` at `0x22098`): After processing the host's Open message, adapter broadcasts the phone's screen resolution
2. **During link session init** (at `0x1c4bc`): When a new BT/WiFi link starts, resolution is pushed to all active sessions

**Payload structure (at `fcn.0001b574`):**
```arm
0x1b5c4  movs r1, 0xf0        ; inner type marker = 0xF0
0x1b5c8  movs r3, 0x1c        ; size = 28 bytes
0x1b5ca  bl fcn.00064768       ; build message from struct at 0x11f3d4
```
Log: `"Accessory_fd::BroadCastRemoteCxCy : %d  %d"` — two uint32 values (width, height)

**Key fields from source struct `[0x11f3d4]`:**
```
Offset  Size  Field    Description
------  ----  -----    -----------
0x00    4     Cx       Remote display width (pixels)
0x04    4     Cy       Remote display height (pixels)
0x08+   20    ...      Extended display parameters (28 total)
```

**What the host should do:** Extract Cx/Cy from the payload. These are the connected phone's display resolution. Use to configure ViewArea/SafeArea settings or video rendering surface size.

**Inbound (0x1E "Bluetooth_Search"):** If the host sends type 0x1E, the adapter logs `"Not support decrypt cmd: Bluetooth_Search !!!"` and drops it. This is vestigial code — do NOT send 0x1E.

### ICCOA Open/Info (0xA1) — Operational Details (Binary Verified Feb 2026)

**Direction:** Adapter → Host
**Payload:** 12-24 bytes (device info + coordinate data)

**When sent:** Only for **ICCOA protocol** (link type `0x14` / decimal 20). The adapter's link-type-to-message-type mapper at `fcn.00017f4c` (`0x17f5c`) maps:

| Link Type | Protocol | Response Msg Type |
|-----------|----------|-------------------|
| 3 | CarPlay Wired | 0x06 |
| 5 | Android Auto Wired | 0x09 |
| 6 | Android Auto Wireless | 0x0B |
| 7 | HiCar Wired | 0x0D |
| 8 | HiCar Wireless | 0x0F |
| **20 (0x14)** | **ICCOA** | **0xA1** |

The handler at `0x1faaa` performs floating-point coordinate conversion (`vcvt.f64.s32`, `vdiv.f64`) — converting integer lat/lon to floating-point degrees.

**Payload structure (from `0x1faaa` handler):**
```
Offset  Size  Field        Description
------  ----  -----        -----------
0x00    4     sub_type     Message sub-type (0x0b) and length
0x04    4     flags        link_type | sub_cmd << 8
0x08    8     latitude     float64 (converted from int32)
0x10    8     longitude    float64 (converted from int32)
```

**What the host should do:** If implementing ICCOA support, treat this as the Open acknowledgment (equivalent to 0x06 for CarPlay). For CarPlay/AA-only implementations, this message will never be seen.

---

## Additional Findings (Feb 2026)

### Message Sender Functions (Corrected)

| Function | Address | Messages Sent | Notes |
|----------|---------|---------------|-------|
| fcn.00018628 | `0x18628` | 0x06, 0x09, 0x0B, 0x0D, 0xA1 | Link-type-dependent info response |
| fcn.000186ba | `0x186ba` | 0x14 (ManufacturerInfo), 0xA1 | OEM info sender |
| fcn.00018850 | `0x18850` | 0x06, 0x0B | HiCar DevList JSON (32B) |
| fcn.0001b574 | `0x1b574` | 0x1E (BroadCastRemoteCxCy) | Resolution broadcast (28B, inner type 0xF0) |
| fcn.00017328 | `0x17328` | Generic | 21+ call sites, generic message sender |
| fcn.00067b18 | `0x67b18` | N/A | Reboot handler (0xCD) |
| fcn.0001c048 | `0x1c048` | N/A | USB gadget reset (0xCE) |

**⚠️ CORRECTION:** `0x1af48` was previously listed as a function that sends types 0x01, 0x1E, 0xF0. It is actually a **data table** — a `.dword` array of string pointers used for command name logging (contains strings like "LaunchAppNowPlaying", "ShowUI", "StopUI", etc.).

### iPhone/Android Work Mode Enumerations (Binary Verified)

**iPhone modes** (from `fcn.000176bc` / `OniPhoneWorkModeChanged`):

| Mode | Name |
|------|------|
| 0 | None/Idle |
| 1 | AirPlay |
| 2 | CarPlay |
| 3 | iOSMirror |
| 4 | OnlyCharge |

**Android modes** (from `fcn.0001777c` / `OnAndroidWorkModeChanged`):

| Mode | Name |
|------|------|
| 0 | None/Idle |
| 1 | AndroidAuto |
| 2 | CarLife |
| 3 | AndroidMirror |
| 4 | HiCar |
| 5 | ICCOA |

### HiCar DevList (0x0B subtype) — 32 bytes payload

When sent as HiCar device list (via `fcn.00018850` at `0x18890`):
```
+--------------------------------------------------------------------------+
|                    Device List Data (32 bytes)                           |
+--------------------------------------------------------------------------+
```
Contains "DevList" and "type" JSON fields.

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

## Inbound Message Handling Reference (Binary Verified Feb 2026)

This section is the **definitive guide** for how the host app should respond to every message the adapter sends. All classifications were verified by tracing the firmware binary's outbound message generation paths in `ARMadb-driver_2025.10_unpacked` and cross-referencing with the `_SendPhoneCommandToCar` dispatcher at `fcn.00019244`.

### Session-Critical Messages (MUST Handle)

These messages indicate state changes that **require** host action. Ignoring them can leave the session in an inconsistent state.

| Type | Name | Payload | Host Action |
|------|------|---------|-------------|
| 0x02 | PlugIn | 8B (phoneType + connected) | Prepare video/audio pipelines. Transition to DEVICE_CONNECTED state. |
| 0x03 | Phase | 4B (phase value) | Phase 7: transition to CONNECTING. Phase 8: start video/audio, transition to STREAMING. **Phase 0: FULL SESSION TEARDOWN** — see critical note below. |
| 0x04 | PlugOut | 0B | **Immediate disconnect.** Phone was physically unplugged from adapter. Stop all pipelines, transition to DISCONNECTED. |
| 0x06 | VideoFrame | Variable | Feed H.264 NAL units to decoder. |
| 0x07 | AudioData | Variable | If 13B: audio command (see below). If larger: PCM audio data → AudioTrack. |
| 0x2A | DashBoard_DATA | Variable | Parse subtype: 1=media JSON, 3=album art, 200=NaviJSON. Update media session and navigation state. |
| 0x2C | AltVideoFrame | Variable | Navigation video stream → secondary decoder (iOS 13+, activated by `naviScreenInfo` in BoxSettings). |
| 0x08 | CarPlayControl | 4B (cmd ID) | Dispatch on command ID — see Command ID Classification below. |
| 0xFF | CMD_ACK | 0B | Open session acknowledged. Proceed with streaming configuration. |

**CRITICAL — Phase 0 Detection:**

Firmware binary proof at `0x1c6c4`: Phase 0 handler executes `killall AppleCarPlay` and `killall ARMiPhoneIAP2` — a **full teardown** of all phone-link processes. This is the adapter's definitive "session is over" signal.

```
0x1c6c4: cmp r5, 0     ; phase == 0?
0x1c6c6: bne ...        ; no → check other phases
         ; YES → teardown path:
         ; killall AppleCarPlay
         ; killall ARMiPhoneIAP2
         ; reset session state
```

The host app MUST:
1. Stop video decoder
2. Stop audio playback
3. Stop microphone capture
4. Stop GNSS forwarding
5. Transition to DISCONNECTED state
6. Await new PlugIn (0x02) for reconnection

### Audio Commands (Type 0x07, 13-byte payloads)

When AudioData has exactly 13 bytes, it's an audio **command**, not PCM data:

| AudioCmd | Name | Firmware Trigger | Host Action |
|----------|------|------------------|-------------|
| 1 | OUTPUT_START | Media playback beginning | Prepare media AudioTrack |
| 2 | OUTPUT_STOP | Media playback ending | May stop media AudioTrack |
| 3 | INPUT_CONFIG | Mic config from phone | Configure mic sample rate/channels |
| 4 | PHONECALL_START | Active phone call | Start microphone capture, route call audio |
| 5 | PHONECALL_STOP | Phone call ended | Stop microphone capture |
| 6 | NAVI_START | Navigation audio starting | Duck media volume or route to nav AudioTrack |
| 7 | NAVI_STOP | Navigation audio stopped | Restore media volume |
| 8 | SIRI_START | Siri activated | Start microphone capture |
| 9 | SIRI_STOP | Siri deactivated | Stop microphone capture |
| 10 | MEDIA_START | Media stream opening | Internal — can be used for state tracking |
| 11 | MEDIA_STOP | Media stream closing | Internal — can be used for state tracking |
| 12 | ALERT_START | System alert sound | Duck media or route alert audio |
| 13 | ALERT_STOP | System alert ended | Restore audio routing |
| 14 | PHONECALL_Incoming | Incoming call ringing | Start ring audio routing (distinct from active call) |

**Note:** AudioCmd 14 (PHONECALL_Incoming) was previously undocumented. It signals an **incoming call ring**, distinct from PHONECALL_START which signals the **active call** after answering.

### Informational Messages (Log Only — Do NOT Change State)

These messages are **pure status notifications**. The adapter sends them to inform the host of internal state. **No session action required.**

| Type | Name | Payload | What It Means | Host Action |
|------|------|---------|---------------|-------------|
| 0x0A | SetBluetoothAddress | 17B | Adapter's BT MAC address | Store for reference. |
| 0x0B | CMD_CARPLAY_MODE_CHANGE | Variable | iPhone work mode changed (0=idle, 1=AirPlay, 2=CarPlay, 3=iOSMirror, 4=OnlyCharge) | Log mode. Pipeline setup is driven by Phase, not mode change. |
| 0x0D | BluetoothDeviceName | Variable | Adapter's BT device name | Store for display. |
| 0x0E | WifiDeviceName | Variable | Adapter's WiFi SSID | Store for display. |
| 0x10 | AirPlayModeChange | Variable | iPhone AirPlay mode changed | Log. AirPlay is audio-only wireless mode. |
| 0x12 | BluetoothPairedList | Variable | List of BT-paired devices | Display or use for auto-connect UI. |
| 0x18 | CMD_CONNECTION_URL | Variable | HiCar/wireless connection URL | Store for wireless pairing flow. |
| 0x19 | BoxSettings (A→H) | Variable JSON | Adapter info (uuid, model, version, DevList) | Parse and store. No state change. |
| 0x1E | BroadCastRemoteCxCy | 28B | Phone's display resolution (Cx, Cy) | Store for ViewArea/SafeArea. |
| 0x23 | Bluetooth_ConnectStart | 17B | BT connection started | Log. |
| 0x24 | Bluetooth_Connected | 17B | BT connection established | Log. |
| 0x25 | Bluetooth_DisConnect | 0B | BT disconnected | Log. Do NOT disconnect USB session. |
| 0x26 | Bluetooth_Listen | 0B | BT advertising/listening | Log. |
| 0x28 | iAP2PlistBinary | Variable | iAP2 binary plist data | Pass through or log. |
| 0x2B | Connection_PINCODE | Variable | BT pairing PIN to display | Show to user for pairing confirmation. |
| 0x77 | FactorySetting (A→H) | 0B | Adapter idle notification | Log. Do NOT disconnect. |
| 0xA3 | SessionToken | ~500B | Encrypted session telemetry | Parse if interested, otherwise ignore. |
| 0xBB | CMD_UPDATE | 4B | Status/config value from adapter | Log. |
| 0xCC | SoftwareVersion | 32B | Firmware version string | Store for display/logging. |
| 0xFD | HUDComand_D_Ready (A→H) | 0B | N/A — this is HOST→ADAPTER only | Should never be received. Log if seen. |

### Messages That MUST NEVER Trigger Disconnect

The following messages are **frequently misinterpreted** as error/disconnect signals. Firmware binary analysis proves they are all **pure status notifications** generated by `_SendPhoneCommandToCar` (`fcn.00019244`). They report internal adapter state and **do not indicate session failure**.

| Cmd ID | Name | Firmware Trigger Function | Why NOT a Disconnect |
|--------|------|--------------------------|----------------------|
| 3 | RequestHostUI | `0x1de52` — phone requests native HU screen | **Notification only.** Phone wants HU to show its own UI. CarPlay session remains active in background. |
| 14 | Hide | `0x19536` — CarPlay going to background | **NOT a disconnect.** Phone UI is still running, just backgrounded. |
| 28 | StartStandbyMode | `0x19572` — low-power mode | **Power management.** Session paused, not terminated. |
| 29 | StopStandbyMode | `0x19576` — exit low-power mode | **Resume from standby.** |
| 1000 | SupportWifi | Init-time, from `fcn.00022284` after Open | **Capability advertisement.** Sent once during init. |
| 1001 | SupportAutoConnect | Init-time, from BT daemon `BluetoothDaemonControlerEx` | **Capability advertisement.** |
| 1002 | StartAutoConnect | Auto-connect sequence begin | **Status notification.** |
| 1003 | ScaningDevices | Auto-connect scanning via `virtual_44` callback | **Status notification.** |
| 1004 | DeviceFound | Device found during scan | **Status notification.** |
| 1005 | DeviceNotFound | Scan complete, no device | **Status notification.** |
| 1006 | DeviceConnectFailed | Connection attempt failed | **Status notification.** Does NOT mean active session failed. |
| 1007 | DeviceBluetoothConnected | BT connected via D-Bus callback at `fcn.0001b7c8` | **Status notification.** BT link is secondary to USB session. |
| 1008 | DeviceBluetoothNotConnected | BT disconnected via D-Bus callback at `fcn.0001b9d8` | **CAN fire during active USB CarPlay.** BT is used for audio routing — losing BT does NOT affect USB video/audio streaming. |
| 1009 | DeviceWifiConnected | WiFi client connected (`fcn.00069d7c` returns 2) | **Status notification.** WiFi is independent of USB session. |
| **1010** | **DeviceWifiNotConnected** | **WiFi check returns 3 or 4 (`fcn.00069d7c`)** | **THE MOST MISINTERPRETED MESSAGE.** This is a WiFi hotspot status check, NOT a session error. For USB CarPlay, WiFi is entirely irrelevant. Old apps that disconnected on 1010 caused unnecessary session interruptions. |
| 1011 | DeviceBluetoothPairStart | BT pairing initiated | **Status notification.** |
| 1012 | SupportWifiNeedKo | WiFi needs kernel module | **Status notification.** |

**Historical Bug — CMD 1010:**
The old app incorrectly treated `DeviceWifiNotConnected` (1010) as a session termination signal and would reset the connection. Firmware analysis proves this message is generated by `_SendPhoneCommandToCar` as a pure WiFi status notification with NO session management implications. The adapter continues operating normally — CarPlay video/audio streaming is unaffected. Ignoring 1010 (and all 1000-1013 commands) dramatically improved session stability.

### Command ID Classification (Type 0x08 Inbound)

Commands received as inbound CarPlayControl (type 0x08) messages from the adapter:

**Session-Affecting (require action):**

| Cmd ID | Name | Required Host Action |
|--------|------|---------------------|
| 3 | RequestHostUI | Show native HU UI (hide CarPlay overlay). Session stays active. |
| 508 | RequestNaviScreenFocus | Echo 508 back to adapter (recommended but **inconclusive** if required — see Navigation Video Setup). |

**Audio Routing (require action):**

| Cmd ID | Name | Required Host Action |
|--------|------|---------------------|
| 1 | StartRecordMic | Start microphone capture |
| 2 | StopRecordMic | Stop microphone capture |
| 7 | UseCarMic | Route to car microphone |
| 8 | UseBoxMic | Route to adapter microphone |
| 21 | UsePhoneMic | Route to phone microphone |
| 22 | UseBluetoothAudio | Route audio via Bluetooth |
| 23 | UseBoxTransAudio | Route audio via USB |
| 18 | StartGNSSReport | Start GPS forwarding to adapter |
| 19 | StopGNSSReport | Stop GPS forwarding |

**Pure Status (log only, no action):**

All commands 1000-1013 (SupportWifi through SupportWifiNeedKo) — see table above.

### Direction Corrections (Binary Verified Feb 2026)

Several message types had incorrect direction documentation. Corrected via firmware binary trace:

| Type | Name | Previously Documented | Corrected Direction | Evidence |
|------|------|-----------------------|---------------------|----------|
| 0x0F | CMD_MANUAL_DISCONNECT_PHONE | Ambiguous | **H→A ONLY** | `fcn.000178e8` (DisconnectPhoneConnection) — host sends to disconnect phone. Adapter never sends this type. |
| 0x15 | CMD_STOP_PHONE_CONNECTION | Ambiguous | **H→A ONLY** | `fcn.00017940` (full stop) — aggressive disconnect. Adapter never sends this type. |
| 0x77 | FactorySetting | A→H only | **BOTH** | A→H: idle notification (0B). H→A: factory reset command (4B payload). |

### App Handling Gaps (Audit Results Feb 2026)

Cross-referencing firmware binary analysis with the CarLink Native app code (`CarlinkManager.kt`) identified these gaps:

| Gap | Severity | Details |
|-----|----------|---------|
| **Phase 0 not detected** | HIGH | The app does not check for Phase value 0. Firmware kills all phone-link processes on Phase 0 — this is the definitive session termination signal. The app should transition to DISCONNECTED. |
| **NaviFocus 508 not echoed** | LOW | Adapter sends cmd 508 (RequestNaviScreenFocus). The `pi-carplay` reference implementation echoes 508 back, but **live testing could not conclusively confirm this is required**. Navigation video activation is primarily driven by `naviScreenInfo` in BoxSettings. Echoing 508 back is recommended as a low-cost precaution. |
| **AudioCmd 14 not handled** | LOW | `kRiddleAudioSignal_PHONECALL_Incoming` (audio command 14) signals an incoming call ring, distinct from PHONECALL_START. Missing handler means incoming ring audio may not be routed correctly. |
| **0x0F/0x15 defined but never sent** | INFO | `DISCONNECT_PHONE` (0x0F) and `CLOSE_DONGLE` (0x15) are defined in MessageTypes but are H→A only commands. The app should never receive them. They can be removed from the inbound parser. |

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
