# ARMAndroidAuto Binary Analysis

**Purpose:** Comprehensive reverse-engineering documentation of the CPC200-CCPA adapter's Android Auto protocol handler
**Binary:** `/usr/sbin/ARMAndroidAuto`
**Last Updated:** 2026-03-16
**Sources:** Ghidra 12.0 headless + radare2 static analysis, extracted strings (10,800+), TTY session logs (12,058 lines), runtime observation (Pixel 10 wireless AA), deep decompilation of ARMAndroidAuto_rx.bin (1.4MB runtime memory dump)

---

## 1. Binary Identity

| Property | Value |
|----------|-------|
| Location on adapter | `/usr/sbin/ARMAndroidAuto` |
| Packed size | 489,800 bytes |
| Unpacked size | 1,488,932 bytes (3:1 ratio) |
| Architecture | ELF 32-bit LSB ARM, EABI5, stripped |
| Packer | Custom LZMA (magic `0x55225522`), NOT standard UPX |
| Decompressor stub | 5,844 bytes at offset `0x76274` |
| Entropy | 8.00 bits/byte (maximum) |
| Build path | `/home/hcw/M6PackTools/HeweiPackTools/AndroidAuto_Wireless/openauto-v2/` |
| SDK path | `/home/hcw/M6PackTools/HeweiPackTools/AndroidAuto_Wireless/AndroidAutoSdk-v2/` |
| Build date (embedded) | `20210301` (March 2021) |
| Linker | `/lib/ld-linux.so.3` (dynamically linked) |

### Unpacking

The decompressor stub chain: `readlink("/proc/self/exe")` → `mmap2()` → LZMA decompress (lc=2, lp=0, pb=3) → `open("/dev/hwas", O_RDWR)` → `ioctl(fd, 0xC00C6206, ...)` → `mprotect()` → jump to decompressed code.

Successfully unpacked (2026-02-28) using UPX with header fix. No ELF section headers in output.

### Linked Libraries

`libc`, `libpthread`, `libstdc++`, `libm`, `librt`, `libdl`, `libcrypto`, `libssl`, `libusb-1.0.so.0`

Note: `libboxtrans.so` and `libdmsdp.so` are NOT referenced in ARMAndroidAuto strings. These libraries are used by ARMadb-driver and AppleCarPlay, not by this binary. ARMAndroidAuto communicates with ARMadb-driver via raw Unix domain sockets using its own `CRiddleUnixSocketClient` class.

---

## 2. Origin and Framework

ARMAndroidAuto is a **modified fork of OpenAuto** (open-source Android Auto head unit implementation) built by **HeWei (DongGuan HeWei Communication Technologies Co. Ltd.)**. It implements the AA protocol using:

- **aasdk** — Android Auto SDK (protocol, transport, messenger, channels)
- **protobuf 2.5.0** — AA message serialization
- **boost 1.66.0** — boost::asio for async I/O
- **OpenSSL** — TLS handshake with phone

Platform variants: `A15F`, `A15HW`, `A15U`, `A15W`, `A15X`

Product types supporting AA: A15W, A15X, A15U, Auto_Box, U2AW, U2AC, U2OW, UC2AW, UC2CA, O2W

---

## 3. C++ Namespace Architecture

```
aasdk::
├── transport::     USBTransport, TCPTransport, SSLWrapper
├── messenger::     Messenger, Cryptor, MessageInStream, MessageOutStream
├── channel::       Service channels (av, input, sensor, control, bluetooth,
│                   navigation, phonestatus, notification)
├── usb::           USBHub, USBEndpoint, USBWrapper, AOAPDevice,
│                   AccessoryModeQueryChain
└── tcp::           TCPEndpoint, TCPWrapper

openauto::
├── App             Main application class
├── projection::    BoxVideoOutput, BoxAudioInput, BoxAudioOutput,
│                   BoxInputDevice, BoxRFCOMMService, RemoteBluetoothDevice
├── service::       AndroidAutoEntity, AndroidAutoInterface,
│                   AndroidAutoEntityFactory, VideoService, AudioService,
│                   AudioInputService, InputService, SensorService,
│                   BluetoothService, NavigationStatusService,
│                   PhoneStatusService, MediaStatusService,
│                   GenericNotificationService, Pinger
└── configuration:: Configuration
```

---

## 4. Service Channels

ARMAndroidAuto exposes **13 AA protocol service channels**:

| Service | Audio Format | Purpose |
|---------|-------------|---------|
| VideoService | H.264 Baseline Profile | Phone screen projection (1920x1080) |
| AudioService (MEDIA_AUDIO) | 48kHz/16bit/stereo | Music, podcasts |
| AudioService (SPEECH_AUDIO) | 16kHz/16bit/mono | Google Assistant, nav prompts |
| AudioService (SYSTEM_AUDIO) | 16kHz/16bit/mono | System sounds, alerts |
| AudioInputService | 8kHz or 16kHz/16bit/mono | Microphone input from host |
| InputService | — | Touch input (1920x1080 coordinate space) |
| SensorService | — | Night mode, GPS, driving status |
| NavigationStatusService | — | Turn-by-turn navigation events |
| BluetoothService | — | BT pairing coordination |
| MediaStatusService | — | Playback state, metadata, album art |
| GenericNotificationService | — | System notifications |
| AVInputChannel | — | AV input |
| VendorExtensionChannel | — | Vendor extensions |

### Service Discovery Keys

Static members of `AndroidAutoEntity`:
- Vehicle info: `cCarYearKey`, `cCarModelKey`, `cCarSerialKey`, `cSwBuildKey`, `cSwVersionKey`
- HU identity: `cHeadUnitNameKey`, `cHeadUnitModelKey`, `cHeadUnitManufacturerKey`, `cDisPlayNameKey`
- Capabilities: `cHaveAvChannelKey`, `cHaveWifiChannelKey`, `cHaveInputChannelKey`, `cHaveRadioChannelKey`, `cHaveSensorChannelKey`, `cHaveAvInputChannelKey`, `cHaveBluetoothChannelKey`, `cHaveMediaInfoChannelKey`, `cHaveNavigationChannelKey`, `cHavePhoneStatusChannelKey`, `cHaveMediaBrowserChannelKey`, `cHaveWifiProjectionChannelKey`, `cHaveVendorExtensionChannelKey`, `cHaveGenericNotificationChannelKey`
- Config: `cDriverPositionKey` (LEFT/RIGHT/CENTER/UNKNOWN), `cSessionConfigKey`, `cHideClockKey`, `cCanPlayNativeMediaDuringVrKey`

---

## 5. Configuration System

### Config Library Access (CORRECTED 2026-03-15)

**Correction:** Earlier analysis (key_binaries.md, 2026-02-28) claimed ARMAndroidAuto had "zero config system strings." String verification (2026-03-15) found this is **wrong**. The binary contains: `GetBoxConfig`, `SetBoxConfig`, `ResetBoxConfig`, `SetBoxConfigStr`, `GetJsonTypeBoxConfig`, `riddleConfigNameValue`, `riddleConfigNameStringValue`, `BoxConfig_DelayStart`, `BoxConfig_preferSPSPPSType`, `BoxConfig_UI_Lang`, plus direct references to `AndroidAutoWidth`, `AndroidAutoHeight`, `AndroidWorkMode`.

ARMAndroidAuto **does** read and write the riddle configuration system, including AA-specific keys. It reads `riddle.conf` and `riddle_default.conf` directly. The `android_work_mode` file path is NOT in the AA binary strings — that file is managed exclusively by ARMadb-driver.

### Configuration Delivery Mechanisms

| Mechanism | Data | Source |
|-----------|------|--------|
| `riddle.conf` / `riddle_default.conf` | All adapter config keys | Read directly via `riddleConfigNameValue` / `riddleConfigNameStringValue` |
| `BOX_CFG_AndroidAuto Width/Height` | AA video dimensions | Read via `GetBoxConfig` at startup |
| `gLinkParam` IPC message | `iWidth`, `iHeight`, `iFps`, `bWireless`, `btAddr` | ARMadb-driver via Unix socket |
| `/tmp/screen_dpi` | DPI value (e.g., 160) | File on disk |
| `/etc/box_product_type` | Product type (e.g., A15W) | Read for feature gating |
| `/etc/android_work_mode` | 4-byte file, value `1` for AA | Managed by ARMadb-driver (NOT read by ARMAndroidAuto) |

### Configuration Flow (from TTY log)

```
[OpenAuto] [Configuration] /tmp setScreenDPI = 160
[OpenAuto] [Configuration] getCarName = Exit
[OpenAuto] [Configuration] setVideoFPS = 2
[OpenAuto] GetBoxConfig BOX_CFG_AndroidAuto Width: 1920, Height: 630
[OpenAuto] [Configuration] setVideoResolution = 3
[OpenAuto] [Configuration] set margin w = 0
[OpenAuto] [Configuration] set margin h = 450
AndroidAuto iWidth: 1920, iHeight: 1080
```

**Margin system:** Height 630 + margin 450 = 1080. The margin is the difference between configured AA height and actual output height.

### AA-Specific BoxSettings JSON Fields

```json
{
  "androidAutoSizeW": 1920,
  "androidAutoSizeH": 630,
  "androidWorkMode": 1
}
```

These are written to `AndroidAutoWidth`/`AndroidAutoHeight` in `riddle.conf` by ARMadb-driver when received from host.

---

## 6. IPC with ARMadb-driver

### MiddleMan Interface

| Property | Value |
|----------|-------|
| Socket | `/var/run/adb-driver` (Unix domain) |
| MiddleMan type | 5 = AndroidAuto (`CAndroidAuto_MiddleManInterface`) |
| Client class | `CRiddleUnixSocketClient` |

Note: `sendTransferData`, `MiddleManClient_SendData`, `libboxtrans.so`, and `_SendPhoneCommandToCar` are NOT in the ARMAndroidAuto binary strings. These are ARMadb-driver constructs. ARMAndroidAuto uses its own `CRiddleUnixSocketClient` for IPC. The command names below are logged by ARMadb-driver when it receives and forwards them from ARMAndroidAuto.

### Commands Forwarded via IPC

ARMAndroidAuto sends phone commands to the host (logged by ARMadb-driver as `_SendPhoneCommandToCar`):

| Command | ID | Direction | Purpose |
|---------|-----|-----------|---------|
| RequestVideoFocus | 500 | Adapter→Host | AA video started, request display |
| ReleaseVideoFocus | 501 | Adapter→Host | AA video stopped |
| RequestAudioFocus | 502 | Adapter→Host | AA audio started |
| ReleaseAudioFocus | 505 | Adapter→Host | AA audio stopped |
| RequestNaviFocus | 506 | Adapter→Host | Navigation active |
| ReleaseNaviFocus | 507 | Adapter→Host | Navigation inactive |
| StartRecordMic | 1 | Adapter→Host | Begin mic capture |
| StopRecordMic | 2 | Adapter→Host | Stop mic capture |
| UseCarMic | 7 | Adapter→Host | Use car's microphone |
| SupportWifi | 1000 | Adapter→Host | WiFi capability |
| SupportAutoConnect | 1001 | Adapter→Host | AutoConnect capability |
| StartAutoConnect | 1002 | Adapter→Host | Begin auto-connect scan |
| ScaningDevices | 1003 | Adapter→Host | Scanning in progress |
| DeviceFound | 1004 | Adapter→Host | Device discovered |
| DeviceNotFound | 1005 | Adapter→Host | Scan failed |
| DeviceBluetoothConnected | 1007 | Adapter→Host | BT connected |
| DeviceBluetoothNotConnected | 1008 | Adapter→Host | BT disconnected |
| DeviceWifiConnected | 1009 | Adapter→Host | WiFi connected |
| SupportWifiNeedKo | 1012 | Adapter→Host | WiFi kernel module needed |

### HUD Commands (Adapter←→Host)

| Command | Purpose |
|---------|---------|
| `HUDComand_A_HeartBeat` | Keepalive |
| `HUDComand_A_Reboot` | Reboot adapter |
| `HUDComand_A_ResetUSB` | Reset USB stack |
| `HUDComand_A_UploadFile` | File upload (0x99) |
| `HUDComand_B_BoxSoftwareVersion` | Version query |
| `HUDComand_D_BluetoothName` | BT name |
| `HUDComand_D_Ready` | Ready signal |

### Control Command Format

Incoming commands from host arrive as `kControlCmdFormat` with keycode. Example: `Recv kControlCmdFormat: 12, keycode: 0` (keyframe request).

### BOX_TMP_DATA_AUDIO_TYPE States

Bitmask logged by ARMadb-driver (not an ARMAndroidAuto string) tracking active audio channels:

| Value | Meaning |
|-------|---------|
| `0x0000` | Silence (media stopped) |
| `0x0001` | Media playing |
| `0x0104` | Voice recognition starting |
| `0x0504` | Voice recognition + mic recording |
| `0x0501` | Phone call + mic recording |

---

## 7. Connection Lifecycle

### Daemon Management

Managed by `phone_link_deamon.sh`:

```bash
# Start sequence
phone_link_deamon.sh AndroidAuto start
  → copies ARMAndroidAuto, hfpd to /tmp/bin/
  → copies libssl.so* to /tmp/lib/
  → starts hfpd -y -E -f &  (HFP daemon for BT SCO bridge)
  → runs ARMAndroidAuto (blocking)
  → auto-restarts in loop while lockfile exists

# Stop sequence
phone_link_deamon.sh AndroidAuto stop
  → killall hfpd
  → killall ARMAndroidAuto
```

### Trigger Mechanism

1. Host sends `/etc/android_work_mode` (value `1`) via SendFile (0x99)
2. ARMadb-driver's `OnAndroidWorkModeChanged: 0 → 1` fires
3. ARMadb-driver starts `phone_link_deamon.sh AndroidAuto start &`
4. ARMAndroidAuto launches, creates MiddleMan client (type=5), connects to IPC socket
5. ARMAndroidAuto enters `waitForUSBDevice` / `Need Wait Start Link` state

### Wireless AA Connection Sequence (from TTY log, verified 2026-03-15)

```
T+0.0s   ARMAndroidAuto starts, MiddleMan client type=5 connects
T+0.0s   "Need Wait Start Link" — waiting for BT RFCOMM trigger
T+5.0s   BT AutoConnect finds Pixel 10, RFCOMM AAP socket accepted
T+5.0s   "Recv start linkParam: 2, B0:D5:FB:A3:7E:AA" (2=wireless)
T+5.1s   recv gLinkParam: iWidth=2400, iHeight=788, iFps=30, bWireless=1
T+5.1s   setAudioCacheMs: 1000, 300, 300 (media, navi, speech)
T+5.2s   BoxRFCOMMService start — sends WiFi credentials via RFCOMM:
           channelfreq=5180, ssid=carlink, passwd=12345678,
           bssid=00:E0:4C:98:0A:6C, securityMode=8, port=54321
T+5.3s   RFCOMM message exchange: WifiVersionRequest(4)→WifiVersionResponse(5)→
           WifiStartRequest(1)→WifiInfoRequest(2)→WifiInfoResponse(3)→
           WifiConnectStatus(6): 0 (success)
T+5.6s   "Wireless Device connected."
T+5.6s   BoxVideoOutput: maxVideoBitRate=0 Kbps (unlimited), bEnableTimestamp_=1
T+5.6s   AndroidAutoEntity start — all 13 services start
T+5.6s   "first package, send version request"
T+5.9s   "version response, version: 1.7, status: 0"
T+5.9s   "Begin handshake."
T+6.0s   SSL handshake: ECDHE-RSA-AES128-GCM-SHA256
T+6.0s   "Auth completed."
T+6.0s   "Discovery request, device name: Android, brand: Google Pixel 10"
T+6.0s   SaveIcon: aa_32x32.png (152B), aa_64x64.png (240B), aa_128x128.png (411B)
T+6.0s   Fill features for all services
T+6.3s   Audio focus request type 4 → ReleaseAudioFocus(505)
T+6.3s   Channel opens: AudioInput, Media/Speech/System Audio, Sensor, BT, Nav, Media Status, Input
T+6.3s   Video setup request config index 3
T+6.3s   "send video focus indication. isVideoHide: 0"
T+6.5s   onAVChannelStartIndication → video streaming begins
T+6.5s   RequestVideoFocus(500) sent to host
T+6.7s   First H264 SPS/PPS (30 bytes) + first I-frame received
T+6.7s   spsWidth=1920, spsHeight=1088
T+6.7s   "recv AndroidAuto size info: 1920 x 1080"
T+7.0s   Full connection established, video streaming
```

**Total connection time:** ~6-7 seconds from BT trigger to first video frame.

### Disconnect Sequence

```
1. "closeTransfer() called"
2. All services stop in reverse order
3. MiddleMan socket ClosedByPeer cascade
4. BT DisconnectRemoteDevice via D-Bus
5. HCI Disconnect reason 0x13 (remote user terminated)
6. ARMadb-driver: "Phone disconnected"
7. ARMAndroidAuto enters waitForUSBDevice/waitForLink again
   (or is killed by phone_link_deamon.sh if mode changes)
```

---

## 8. SSL/TLS Authentication

| Property | Value |
|----------|-------|
| Cipher | `ECDHE-RSA-AES128-GCM-SHA256` |
| CA Issuer | `C=US, ST=California, L=Mountain View, O=Google Automotive Link` |
| Subject | `C=US, ST=California, L=Mountain View, O=CarService` |
| Key | RSA 2048-bit |
| Validity | Jul 4, 2014 — Jun 24, 2026 |
| Protocol version | 1.7 (observed with Pixel 10) |
| Handshake flow | Version request → Version response → SSL_do_handshake (async, 2 rounds) → Auth completed → Service discovery |

The RSA private key and X.509 certificate are embedded statically in `aasdk::messenger::Cryptor::cPrivateKey` / `cCertificate`.

**WARNING:** Certificate expires Jun 24, 2026. After this date, AA connections may fail unless firmware is updated with a new certificate.

---

## 9. Video Pipeline

### Configuration

| Parameter | Value | Source |
|-----------|-------|--------|
| Output resolution | 1920x1080 | Computed: config height 630 + margin 450 |
| SPS/PPS resolution | 1920x1088 | 8-line alignment padding (standard H.264) |
| Codec | H.264 Baseline Profile (`MEDIA_CODEC_VIDEO_H264_BP`) |
| Max bitrate | 0 Kbps (unlimited) or 5000 Kbps | `maxVideoBitRate` from config |
| FPS enum | 2 (maps to 30fps) | `setVideoFPS` |
| Resolution enum | 3 (maps to 1080p) | `setVideoResolution` |
| Wireless cap | 1080p max | Firmware-enforced: "Wireless AndroidAuto max support 1080p" |
| Timestamp mode | Enabled (`bEnableTimestamp_=1`) | |

Supported resolution enums: `_480p`, `_720p`, `_720p_p`, `_1080p`, `_1080p_p`, `_1440p`, `_1440p_p`, `_2160p`, `_2160p_p`

### BoxVideoOutput State Machine

```
open() → "do nothing" (no-op, already initialized)
init() → "do nothing"
onVideoStateChanged(1) → video streaming started
    → RequestVideoFocus(500) sent to host (unless bIgnoreVideoFocus=1)
onVideoStateChanged(0) → video streaming stopped
    → ReleaseVideoFocus(501) sent (unless bIgnoreVideoFocus=1)
```

`bIgnoreVideoFocus` is set to 1 after the first video focus cycle. The adapter does NOT gate AA video on focus responses.

### Keyframe Behavior (CRITICAL — differs from CarPlay)

- `requestKeyFrame()` has a **two-tier throttle** (see Section 29 for full decompilation):
  - **Tier 1:** If <101ms since last *received* keyframe → hard ignore (`"ignore it!!!"`)
  - **Tier 2:** If <1000ms since last *request* → blocking `sleep(1)` then send
  - After sending: `usleep(100000)` (100ms post-send delay)
  - Sets `bIgnoreVideoFocus_ = 1` before sending
- `bCheckManualRequestKeyFrame` — global flag controlling manual keyframe requests
- Host should send **ONE** keyframe request at session start, then rely on natural IDRs
- Natural IDR interval: **60-68 seconds** (encoder-configured `sync-frame-interval=60s` + encoding delay)
- **NEVER send periodic keyframe requests to AA** — unlike CarPlay's 2s periodic requests

### Video Focus Modes

| Mode | Value | Meaning |
|------|-------|---------|
| VIDEO_FOCUS_NATIVE | 1 | Native HU content |
| VIDEO_FOCUS_NATIVE_TRANSIENT | 3 | Temporary native overlay |
| VIDEO_FOCUS_PROJECTED | — | AA projection active |

### Frame Cache and Timing

- `VIDEO_FRAME_CACHE_NUM` — frame cache size
- `VIDEO_TIMESTAMP_MODE` — timestamp handling mode
- `frame, delay ack` — frame acknowledgment delay (flow control)
- `SendEmptyFrame` — empty frame for keepalive
- `AltVideoFrame` — alternative video frame handling

### Observed Frame Rates (from TTY log)

During active content: 19-30 fps, 24-357 KB/s
During idle screen: 5-13 fps, ~0.5 KB/s

---

## 10. Audio Pipeline

### Audio Channel Configuration

| Channel | Sample Rate | Sample Size | Channels | Cache (ms) |
|---------|-------------|-------------|----------|------------|
| MEDIA_AUDIO | 48,000 Hz | 16-bit | 2 (stereo) | 1000 |
| SPEECH_AUDIO | 16,000 Hz | 16-bit | 1 (mono) | 300 |
| SYSTEM_AUDIO | 16,000 Hz | 16-bit | 1 (mono) | 300 |

### Audio Codecs

`MEDIA_CODEC_AUDIO_AAC_LC`, `MEDIA_CODEC_AUDIO_AAC_LC_ADTS`, `MEDIA_CODEC_AUDIO_PCM`

### BoxAudioOutput Members

- `mediaStatus_`, `naviStatus_`, `callStatus_`, `vrStatus_` — per-stream state
- `mediaCacheMs_`, `naviCacheMs_`, `speechCacheMs_` — configurable jitter buffer
- `setAudioCacheMs: 1000, 300, 300` — set at connection time from link params

### Audio Focus Flow

```
AudioFocusRequest type 1 → RequestAudioFocus(502) → audio playing
AudioFocusRequest type 4 → ReleaseAudioFocus(505) → audio stopped
NaviFocusChanged 2       → RequestNaviFocus(506)   → nav audio active
NaviFocusChanged 1       → ReleaseNaviFocus(507)   → nav audio inactive
VRStatusChanged 3        → voice recognition start
VRStatusChanged 4        → voice recognition end
```

### Audio Ducking Rule

- `close media channel when call start` — phone call preempts media
- `close speech channel when call start` — phone call preempts speech
- `vr channel handle in onVRStatusChanned()` — VR routes through BoxAudioOutput

### Audio Resampling

`BoxAudioConvertInit ctx:%p, src format:%d-%d-%d, dst format:%d-%d-%d` — format conversion between phone codec output and adapter's internal audio pipeline.

### Jitter Buffer

The `BoxAudioUtils` module implements a jitter buffer for SPEECH_AUDIO:
- `Jitter Buffer first packet data.` — first packet received
- `Jitter Buffer is ready now.` — buffer filled, playback begins
- `JB buffer not enough: 1536` — underrun
- `JB buffer all-consumed.` — buffer empty

### Microphone (CRITICAL — AA Phone Call Fix, 2026-03-15)

| Mode | decodeType | Sample Rate | Chunk Size | Routing |
|------|-----------|-------------|------------|---------|
| Google Assistant | 5 | 16 kHz | 640B/20ms | AudioInputService |
| AA phone call (HFP/SCO) | 3 | 8 kHz | 320B/20ms | hfpd → BT SCO bridge |
| CarPlay phone call | 5 | 16 kHz | 640B/20ms | iAP2/WiFi (no SCO) |

**Root cause of original bug:** carlink_native hardcoded decodeType=5 (16kHz) for all mic modes. Adapter expected decodeType=3 for SCO routing. Fix: dynamic decodeType from `AUDIO_INPUT_CONFIG` command.

Mic lifecycle (from TTY log):
```
Voice session request, type: START
  → BoxAudioOutput onVRStatusChanned: 3
  → Set BOX_TMP_DATA_AUDIO_TYPE: 0x0104
  → AudioInputService input open request, open: 1
  → StartRecordMic(1)
  → Set BOX_TMP_DATA_AUDIO_TYPE: 0x0504
Voice session request, type: END
  → AudioInputService input open request, open: 0
  → StopRecordMic(2)
  → BoxAudioOutput onVRStatusChanned: 4
```

---

## 11. Wireless AA Setup

### RFCOMM WiFi Handoff

The phone connects to the adapter via BT RFCOMM (AAP service), then ARMAndroidAuto sends WiFi credentials for the phone to join the adapter's hotspot:

```
BoxRFCOMMService start: <phone BT addr>
  → sendRFCOMMData type 4 (WifiVersionRequest)
  ← recv type 5 (WifiVersionResponse): 0
  → sendRFCOMMData type 1 (WifiStartRequest)
  ← recv type 2 (WifiInfoRequest)
  → sendRFCOMMData type 3 (WifiInfoResponse)
  ← recv type 7 (WifiStartResponse): success
  ← recv type 6 (WifiConnectStatus): 0 (connected)
  → "Wireless Device connected."
```

### WiFi Configuration Sent via RFCOMM

| Parameter | Value |
|-----------|-------|
| channelfreq | 5180 (channel 36, 5GHz) |
| channeltype | 0 |
| ip | 192.168.43.1 |
| port | 54321 |
| ssid | carlink |
| passwd | 12345678 |
| bssid | 00:E0:4C:98:0A:6C (adapter WiFi MAC) |
| securityMode | 8 (WPA_PERSONAL) |

### WiFi Channel Support

`CHANNELS_24GHZ_ONLY`, `CHANNELS_5GHZ_ONLY`, `CHANNELS_DUAL_BAND`

WiFi chip detection: `BCM4335`, `BCM4354`, `BCM4358`, RTL8822CS — reads from `/sys/bus/sdio/devices/mmc0:0001:1/device`

### Network Info

- `/sys/class/net/wlan0/address` — WiFi MAC
- `/sys/class/net/wlan0/operstate` — WiFi state
- `/sys/class/bluetooth/hci0/address` — BT MAC

---

## 12. USB / AOA (Wired AA)

### Android Open Accessory Protocol

`AccessoryModeQueryChain` sequence: manufacturer → model → description → serial → URI → version → protocolVersion → start

```
AccessoryModeQueryChain startQuery, queryType = %d
Already in AOA Mode, Ignore          — skip setup if already in accessory mode
_isFakeiPhoneUsbBus, ignore this dev! — filter out iPhone USB devices
_isUSB_LinuxHUB, ignore this dev!     — filter out USB hubs
Configure AOA OK
```

### USB Transfer Config

- `USBTransMode` — Zero-Length Packet mode for AOA bulk transfers
- `USBConnectedMode` — connection mode indicator
- `AsyncWrite use time: %lld ms, iSize: %d, ret: %d` — transfer timing

---

## 13. Navigation

### Protobuf Messages

- `NavigationStatus` — ACTIVE / INACTIVE
- `NavigationTurnEvent` — turn type, street name, turn_side, turn_angle, turn_number, maneuver image
- `NavigationDistanceEvent` — distance in meters, time to turn in seconds
- `NavigationImageOptions` — maneuver icon dimensions
- `NavFocusType` — navigation focus enum

### NextTurn Enum → NaviOrderType Mapping (CORRECTED 2026-03-16)

**SUPERSEDED:** The table below was the original "+2 offset" prediction model. It has been replaced by the **verified 19-entry lookup table** decompiled from the binary in **Section 25**. The lookup table reveals lossy collapsing and 3 firmware bugs not visible from runtime captures alone. **Use Section 25 as the authoritative reference.**

| aasdk Enum | Proto Value | Old Prediction | **Verified (§25)** | Live Capture | Notes |
|------------|-------------|---------------|---------------------|-------------|-------|
| UNKNOWN | 0 | 0 | **0** | — | |
| DEPART | 1 | 1 | **1** | YES (NaviOrderType=1) | Identity |
| NAME_CHANGE | 2 | 4 | **4** | — | +2 holds |
| SLIGHT_TURN | 3 | 5 | **6** | — | **Collapses to TURN** |
| TURN | 4 | 6 | **6** | YES (NaviOrderType=6) | Confirmed |
| SHARP_TURN | 5 | 7 | **6** | — | **Collapses to TURN** |
| U_TURN | 6 | 8 | **6** | — | **Collapses to TURN** |
| ON_RAMP | 7 | 9 | **6** | YES (adapter: ON_RAMP) | **Collapses to TURN** — prior "live verified=9" was misread; emulator logcat not captured for this event |
| OFF_RAMP | 8 | 10 | **10** | YES (NaviOrderType=10) | +2 holds |
| FORK | 9 | 11 | **11** | — | +2 holds |
| MERGE | 10 | 0 | **0** | YES (NaviOrderType=0) | Maps to none |
| ROUNDABOUT_ENTER | 11 | 13 | **0** | — | **BUG: unmapped** |
| ROUNDABOUT_EXIT | 12 | 14 | **14** | — | +2 holds |
| ROUNDABOUT_ENTER_AND_EXIT | 13 | 15 | **15** | — | +2 holds |
| STRAIGHT | 14 | 16 | **16** | — | +2 holds |
| FERRY_BOAT | 16 | 18 | **18** | — | +2 holds |
| FERRY_TRAIN | 17 | 19 | **0** | — | **BUG: unmapped** |
| DESTINATION | 19 | 21 | **21** | — | +2 holds |

**NaviTurnSide values (AA-specific):** 0=unspecified, 1=left, 2=right, 3=unspecified (observed in DEPART). Note: CarPlay uses NaviTurnSide with different semantics (0=RHD, 1=LHD driving convention). These are **protocol-specific** — do not conflate.

**NaviTurnAngle values:** 0=none, 2=observed with TURN (meaning TBD — possibly moderate angle indicator)

### NaviJSON Conversion

ARMAndroidAuto converts AA navigation protobuf to NaviJSON and sends it via MediaData 0x2A subtype 200 to the host. The conversion happens inside the packed code (no `_SendNaviJSON` log in ttyLog for AA — only CarPlay logs this call). NaviJSON fields:

| Field | Present in AA | Present in CarPlay | Notes |
|-------|--------------|-------------------|-------|
| `NaviOrderType` | **YES** | no | AA maneuver type — see enum table above |
| `NaviManeuverType` | **no** | YES | CarPlay maneuver type (CPManeuverType 0-53) |
| `NaviRoadName` | YES | YES | Street name (identical field) |
| `NaviRemainDistance` | YES | YES | Distance to next maneuver in meters |
| `NaviNextTurnTimeSeconds` | **YES** | **no** | Time to next turn in seconds (AA-only) |
| `NaviTurnSide` | YES | YES | 0=unspecified, 1=left, 2=right |
| `NaviTurnAngle` | YES | YES | Turn angle indicator |
| `NaviRoundaboutExit` | YES | YES | Roundabout exit number |
| `NaviStatus` | YES | YES | 1=active, 2=inactive |
| `NaviDistanceToDestination` | no | YES | CarPlay-only |
| `NaviTimeToDestination` | no | YES | CarPlay-only |
| `NaviDestinationName` | no | YES | CarPlay-only |
| `NaviAPPName` | no | YES | CarPlay-only |

**Critical for host apps:** Check `NaviManeuverType` first (CarPlay). If absent, fall back to `NaviOrderType` (AA) and apply the **Section 25 lookup table** (not the old +2 offset model).

### Maneuver Icons (CORRECTED 2026-03-16)

AA sends 1739-2542 byte PNG maneuver icons per turn event. These ARE forwarded as `MEDIA_DATA` sub-type 201 (see Section 25). The previous claim that the adapter drops them was **incorrect** — the adapter sends them, but `carlink_native` drops them because `MediaType.fromId(201)` returns `UNKNOWN`. **This is AA-only** — CarPlay sends `NaviManeuverType` enum values via iAP2 Route Guidance, never icon images.

### Live Capture Evidence (2026-03-15)

**Adapter ttyLog** (AA protobuf side):
```
Turn Event, Street: Buzby Rd, turn_side: 2, event: NextTurn_Enum_TURN, image size: 2275
Turn Event, Street: North Pole / Fairbanks, turn_side: 1, event: NextTurn_Enum_ON_RAMP, image size: 2542
Turn Event, Street: Badger Rd / Santa Claus Ln, turn_side: 2, event: NextTurn_Enum_OFF_RAMP, image size: 2297
Turn Event, Street: AK-2 W, turn_side: 3, event: NextTurn_Enum_MERGE, image size: 3716
Distance Event, Distance (meters): 456, Time To Turn (seconds): 31
```

**Emulator logcat** (NaviJSON USB side, confirming data arrives):
```
[RECV] MediaData(type=NAVI_JSON)
NaviJSON: keys=[NaviRoadName, NaviTurnSide, NaviOrderType, NaviTurnAngle, NaviRoundaboutExit]
  values=NaviRoadName=Buzby Rd, NaviTurnSide=2, NaviOrderType=6, NaviTurnAngle=2, NaviRoundaboutExit=0
NaviJSON: keys=[NaviRemainDistance, NaviNextTurnTimeSeconds]
  values=NaviRemainDistance=440, NaviNextTurnTimeSeconds=30
```

**Cluster observation:** Road name and distance update correctly. Maneuver icon shows constant straight arrow because `NavigationStateManager` reads `NaviManeuverType` (absent in AA) → falls to 0 → `ManeuverMapper` returns `TYPE_STRAIGHT`.

> **Captures saved:** `analysis/aa_full_session_emulator_20260315.txt` (44,689 lines), `analysis/aa_full_session_adapter_20260315.txt` (15,297 lines)

---

## 14. Media Status

### MediaStatusService Output

```
Playback update, state: PLAYING, source: YouTube Music, progress: 175
Metadata update, track_name: Black Creek, artist_name: Brent Cobb,
  album_name: No Place Left to Leave (2006), album_art size: 163337,
  playlist: , duration_seconds: 212, rating: 0
```

Playback states: `STOPPED`, `PAUSED`, `PLAYING`

State changes are forwarded to ARMadb-driver as:
- `kRiddleAudioSignal_MEDIA_START` — when PLAYING
- `kRiddleAudioSignal_MEDIA_STOP` — when STOPPED/PAUSED

---

## 15. Sensor Data

### Supported Sensor Types

Vehicle sensors (protobuf messages):
- `Accel`, `Gyro`, `Compass`, `Speed`, `RPM`, `Odometer`, `FuelLevel`
- `Gear` (with gear enum), `NightMode`, `DrivingStatus`, `ParkingBrake`
- `SteeringWheel`, `TirePressure`, `Light`, `Door`, `HVAC`, `Passenger`, `Range`
- `GPSLocation`, `GpsSatellite`, `GpsSatelliteInner`

### GPS Location Fields

`latitude_e7`, `longitude_e7`, `elevation_e3`, `bearing_e6`, `altitude_e2`, `accuracy_e3`, `azimuth_e3`

Sensor fusion types: `ACCELEROMETER_FUSION`, `CAR_SPEED`, `CAR_SPEED_FUSION`, `RAW_GPS_ONLY`

### Night Mode

```
recv start night mode   → SensorService forwards night mode ON
recv stop night mode    → SensorService forwards night mode OFF
```

### GPS Bug

`std::stoi()`/`strtol()` truncates NMEA coordinate fractional minutes (e.g., "6447.9901" → integer 6447). GPS quantized to ~1.85km whole-minute grid.

**Runtime patcher** (`gps_patcher.c`): ptrace-based patch injects corrected NMEA→decimal_degrees conversion at GOT addresses. GOT: `strtol` at `0x1896f4`, `strtod` at `0x189920`, `strtof` at `0x189a4c`.

---

## 16. Bluetooth

### BT Service Registration Order

```
IAP2 service registered
NearBy service registered
HiChain service registered
AAP service registered        ← Android Auto Protocol
Serial Port service registered
```

### HFP (Hands-Free Profile) for Phone Calls

`hfpd` (HFP daemon) manages BT SCO bridge for phone calls:

```
hfpd -y -E -f
  → D-Bus: Exported /net/sf/nohands/hfpd
  → D-Bus: Exported /net/sf/nohands/hfpd/soundio
  → D-Bus: Exported /net/sf/nohands/hfpd/<BT_ADDR>
  → SCO MTU: 240:32 Voice: 0x0060
```

HFP AT command sequence (from TTY log):
```
<< AT+BRSF=63
>> +BRSF: 879
<< AT+CIND=?
>> +CIND: ("call",(0,1)),("callsetup",(0-3)),("service",(0-1)),...
<< AT+CMER=3,0,0,1
<< AT+CLIP=1
<< AT+CCWA=1
<< AT+CHLD=?
>> +CHLD: (0,1,2,3)
<< AT+CIND?
>> +CIND: 0,0,0,0,0,5,0
→ AG B0:D5:FB:A3:7E:AA: Connected
```

### BT Pairing

BluetoothService handles pairing coordination. On connection: `pairing request, address: <BT_ADDR>` → `pairing response`. If passkey unavailable: `"No passkey, try again!"` (retries every ~1-2s).

---

## 17. Media Transport (CORRECTED 2026-03-15)

**Correction:** Earlier analysis attributed DMSDP RTP stack usage to ARMAndroidAuto. String verification (2026-03-15) found that `DMSDPRtpSendQueueAVC`, `DMSDPRtpSendQueuePCM`, `DMSDPRtpSendQueueAAC`, `DMSDPServiceOpsTriggerIFrame`, and `libdmsdp` are **NOT present** in the ARMAndroidAuto binary strings. The DMSDP stack is used by AppleCarPlay and/or ARMadb-driver, not by ARMAndroidAuto.

ARMAndroidAuto uses its own aasdk/OpenAuto media pipeline: `aasdk::messenger::MessageInStream` / `MessageOutStream` with `aasdk::transport::USBTransport` or `TCPTransport` for media data transfer. Audio/video data flows through the `CRiddleUnixSocketClient` IPC to ARMadb-driver, which forwards it to the USB host.

---

## 18. Threading Model

| Thread | Purpose |
|--------|---------|
| Main thread | Lifecycle, configuration, initialization |
| IO Service workers | `startIOServiceWorkers` — boost::asio event loop runners |
| Transfer worker | `startTransferWorkers` — USB/TCP data transfer |
| USB worker | libusb event handling (`libusb_handle_events_timeout_completed`) |

Synchronization: pthread primitives (mutex, cond, thread create/join/detach)

Global exit flag: `gTransferExit`

Thread exit logging:
```
thread checkTransferWorker exit
thread transferWorker exit/start
thread usbWorker libusb_handle_events_timeout_completed exit
thread ioService run exit
```

---

## 19. Error Handling

### Protocol Errors

| Error | Meaning |
|-------|---------|
| `STATUS_OK` / `STATUS_SUCCESS` | Success |
| `STATUS_AUTHENTICATION_FAILURE` | SSL auth failed |
| `STATUS_CERTIFICATE_ERROR` | Certificate issue |
| `STATUS_NO_COMPATIBLE_VERSION` | Version mismatch |
| `STATUS_FRAMING_ERROR` | Protocol framing error |
| `STATUS_INVALID_CHANNEL` | Bad channel ID |
| `STATUS_MEDIA_CONFIG_MISMATCH` | Audio/video config mismatch |
| `STATUS_BLUETOOTH_UNAVAILABLE` | No BT adapter |
| `STATUS_BLUETOOTH_HFP_CONNECTION_FAILURE` | HFP connect failed |
| `STATUS_WIFI_DISABLED` | WiFi off |
| `STATUS_WIFI_INCORRECT_CREDENTIALS` | Bad WiFi password |
| `STATUS_PROJECTION_ALREADY_STARTED` | Already projecting |
| `ERROR_INCOMPATIBLE_PHONE_PROTOCOL_VERSION` | Phone protocol too old/new |
| `ERROR_BT_CLOSED_BEFORE_START` / `AFTER_START` | BT dropped |
| `ERROR_PHONE_UNABLE_TO_CONNECT_WIFI` | Phone WiFi connect failed |
| `ERROR_REQUEST_TIMEOUT` | Timeout |

### Signal Handling

```
Catch signal kill, process will exit!!!
```

ARMAndroidAuto catches SIGTERM (15) for graceful shutdown.

### Observed Crash

```
Segmentation fault
```

Observed once during startup (line 525) when the binary was killed and restarted rapidly. The daemon script auto-restarts it.

---

## 20. File Paths Referenced

### Configuration Files

| Path | Purpose |
|------|---------|
| `/etc/riddle.conf` | Main adapter configuration |
| `/etc/riddle_default.conf` | Default configuration |
| `/etc/android_work_mode` | Android work mode (1=AA) |
| `/etc/box_product_type` | Product type identifier |
| `/etc/box_version` | Firmware version |
| `/etc/software_version` | Software version |
| `/etc/serial_number` | Serial number |
| `/etc/uuid` | Device UUID |
| `/etc/deviceinfo` | Device information |
| `/etc/bluetooth_name` | BT adapter name |
| `/etc/airplay_brand.conf` | Brand config |
| `/etc/default_wifi_channel` | WiFi channel |
| `/etc/hostapd.conf` | WiFi AP config |

### Runtime Files

| Path | Purpose |
|------|---------|
| `/tmp/screen_dpi` | Screen DPI setting |
| `/tmp/rfcomm_AAP` | RFCOMM socket for AA wireless |
| `/tmp/aa_32x32.png` | AA app icon (32px) |
| `/tmp/aa_64x64.png` | AA app icon (64px) |
| `/tmp/aa_128x128.png` | AA app icon (128px) |
| `/tmp/app.log` | Application log |
| `/tmp/box.log` | Box log |
| `/tmp/bluetooth_status` | BT state |
| `/tmp/wifi_status` | WiFi state |
| `/tmp/wifi_connection_list` | Connected devices |
| `/var/run/adb-driver` | IPC Unix socket |

### System/Hardware

| Path | Purpose |
|------|---------|
| `/dev/i2c-1` | I2C bus |
| `/dev/mem` | Memory device |
| `/proc/meminfo` | Memory info |
| `/sys/class/gpio/gpio2/value` | GPIO control |
| `/sys/class/gpio/gpio9/value` | GPIO control |
| `/sys/class/thermal/thermal_zone0/temp` | CPU temperature |

---

## 21. Car Brand Support

Embedded brand strings: `AUDI`, `Bentley`, `BUICK`, `CADILLAC`, `CHEVROLET`, `DODGE`, `FIAT`, `FORD`, `HONDA`, `HYUNDAI`, `JEEP`, `LEXUS`, `LINCOLN`, `MASERATI`, `MERCEDES_BENZ`, `NISSAN`, `PORSCHE`, `SUBARU`, `TOYOTA`, `Alfa_Romeo`

---

## 22. Daily Active Info

ARMadb-driver collects connection telemetry:

```json
{
  "phone": {
    "model": "Google Pixel 10",
    "linkT": "AndroidAuto",
    "conSpd": 6,
    "conRate": 0.9,
    "conNum": 194,
    "success": 174
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

90% success rate (174/194 attempts), 6-second average connection speed.

---

## 23. Known Issues

1. **Certificate expiry:** Google Automotive Link CA cert expires Jun 24, 2026
2. **GPS precision:** `strtol()` truncation loses NMEA fractional minutes (~1.85km error)
3. **GPS timestamp:** `timestamp: 0` — adapter clock stuck at 2020-01-02, cannot derive epoch from NMEA
4. **Keyframe throttle:** Two-tier: 101ms ignore + 1-second blocking sleep between IDR requests
5. **Boot-screen poisoning:** First IDR is often a tiny boot-screen frame; host must skip initial decoded frames
6. **WiFi default password:** `12345678` sent in cleartext via RFCOMM
7. **No NaviManeuverType:** AA uses `NaviOrderType` in NaviJSON, causing host ManeuverMapper fallback to type 0
8. **Maneuver icons ARE forwarded** (CORRECTED 2026-03-16): Adapter sends AA maneuver icons as MediaData sub-type 201 (AA-only — CarPlay uses enum values, no images); carlink_native `MediaType.fromId(201)` returns UNKNOWN and silently drops them. **Fix: add NAVI_IMAGE(201) to MediaType enum.**
9. **NaviOrderType mapping is lossy:** SLIGHT_TURN, SHARP_TURN, U_TURN, ON_RAMP collapse to NaviOrderType=6; MERGE/ROUNDABOUT_ENTER/FERRY_TRAIN map to 0 (unmapped)
10. **BoxAudioConvert is dead code:** Resampling code exists but has no xrefs — format is negotiated directly, no runtime conversion

---

## 24. Resolution Tier Enforcement (Deep Analysis — 2026-03-16)

**Source:** Ghidra + r2 decompilation of function at `0x3a8a0` in `ARMAndroidAuto_rx.bin`

### Resolution Protobuf Enum (descriptor at rx offset `0x12e9d0`)

| Enum | Name | Width | Height |
|------|------|-------|--------|
| 0 | NONE | — | — |
| 1 | _480p | 800 | 480 |
| 2 | _720p | 1280 | 720 |
| 3 | _1080p | 1920 | 1080 |
| 4 | _1440p | 2560 | 1440 |
| 5 | _2160p | 3840 | 2160 |
| 6 | _720p_p | 720 | 1280 |
| 7 | _1080p_p | 1080 | 1920 |
| 8 | _1440p_p | 1440 | 2560 |
| 9 | _2160p_p | 2160 | 3840 |

### Selection Algorithm (function at `0x3a8a0`)

**Phase 1 — Input:**
1. `gLinkParam` received via IPC: `iWidth`, `iHeight`, `iFps`, `bWireless`, `btAddr`
2. FPS: `iFps > 30` → enum 1 (60fps), else → enum 2 (30fps). Set via `setVideoFPS` (vtable `0x24`)
3. Config: `GetBoxConfig("AndroidAutoWidth")` → width, `GetBoxConfig("AndroidAutoHeight")` → height

**Phase 2 — Landscape tier selection** (when width > height):

```
if (width <= 800 && height <= 480):   setVideoResolution(1)  // _480p
elif (width <= 1280 && height <= 720):  setVideoResolution(2)  // _720p
elif (width <= 1920 && height <= 1080): setVideoResolution(3)  // _1080p
elif (width <= 2560 && height <= 1440): setVideoResolution(4)  // _1440p
else:                                   setVideoResolution(5)  // _2160p
```

Tier = **smallest standard resolution that fully contains** the configured dimensions. Margins fill the difference: `margin_w = tier_width - config_width`, `margin_h = tier_height - config_height`.

**Phase 3 — Wireless cap** (at `0x3b216`):

```c
if (bWireless != 0) {
    if (width * height > 2073600) {  // 2073600 = 1920×1080, strictly greater than
        // Log: "Wireless AndroidAuto max support 1080p, change to 1080p"
        setVideoResolution(portrait ? 7 : 3);
        width = 1920; height = 1080;
        // Recalculate margins
    }
}
```

**Threshold:** 2,073,600 pixels (1920×1080 exactly). Comparison is BGT (strictly >), so 1080p itself is allowed. Anything above is hard-capped to 1080p for wireless connections. Wired AA has no cap.

**Fallback path** (at `0x3b12c`): When `GetBoxConfig` returns ≤ 0, uses `gLinkParam.iWidth/iHeight` directly with midpoint thresholds (959, 575, 1535, 863, 2099, 1219, 2799, 1759).

### Live Verification (2026-03-15)

```
gLinkParam: iWidth=2400, iHeight=960, iFps=30, bWireless=1
GetBoxConfig: AndroidAutoWidth=1920, AndroidAutoHeight=768
→ 1920 <= 1920 && 768 <= 1080 → Tier 3 (_1080p)
→ margin_w=0, margin_h=312
→ Wireless cap: 1920*1080 = 2073600, NOT > 2073600 → no cap
→ Final: iWidth=1920, iHeight=1080, config index 3
```

---

## 25. Maneuver Icon Forwarding — CORRECTED (Deep Analysis — 2026-03-16)

**Source:** r2 trace through `NavigationStatusServiceChannel::messageHandler` → `onTurnEvent` → NaviJSON builder → `sendData`

### AA-Only Feature

Sub-type 201 (NAVI_IMAGE) is **strictly Android Auto**. CarPlay does NOT send maneuver icons through this mechanism.

- **AA path:** Phone renders a PNG maneuver icon and embeds it in the `NavigationTurnEvent` protobuf → ARMAndroidAuto forwards it as MediaData sub-type 201
- **CarPlay path:** iPhone sends `CPManeuverType` enum (0-53) via iAP2 `RouteGuidanceEngine` → ARMiPhoneIAP2 `_SendNaviJSON()` converts it to `NaviManeuverType` in sub-type 200 JSON — **no icon image is ever sent**

The `ARMiPhoneIAP2` and `AppleCarPlay` binaries contain zero strings related to maneuver image/icon forwarding. CarPlay's iAP2 Route Guidance protocol transmits maneuver type integers only; the head unit is expected to render its own icons from the enum.

### Icon Pipeline (7 stages, AA only)

1. **Phone** sends `NavigationTurnEvent` protobuf with `image_options` containing PNG data (1739-2542 bytes)
2. **`NavigationStatusServiceChannel::messageHandler`** (`0x7b32c`) dispatches msg type `0x8003` to `onTurnEvent`
3. **`NavigationStatusService::onTurnEvent`** (`0x69bcc`) logs all fields including `"image size: NNNN"`, then calls NaviJSON builder
4. **NaviJSON builder** (`0x5520c`) sends **TWO** separate messages via `sendData` (`0x493e8`):
   - **Image**: 4-byte header `0xC9` (201) + raw PNG data — sent as media sub-type 201
   - **JSON**: 4-byte header `0xC8` (200) + NaviJSON string — sent as media sub-type 200
5. **`sendData`** forwards both through the IPC callback at global `0x18B7A0`
6. **ARMadb-driver** wraps both in `MEDIA_DATA` (0x2A) USB protocol and sends to host
7. **Host** receives both — but `MediaType.fromId(201)` returns `UNKNOWN` → silently dropped

### Root Cause — RUNTIME CONFIRMED

The adapter firmware **DOES forward** maneuver icons as `MEDIA_DATA` sub-type 201. The drop happens on the **host side** in `carlink_native`.

**Runtime proof** (emulator logcat `aa_full_session_emulator_20260315.txt`):
```
[RECV] MediaData(type=UNKNOWN)      ← sub-type 201 (icon PNG) — arrives at host, no MediaType mapping
[RECV] MediaData(type=NAVI_JSON)    ← sub-type 200 — parsed correctly
```
Every NaviJSON update is **preceded** by a `MediaData(type=UNKNOWN)`. This pattern repeats consistently across the entire session (dozens of instances).

- `MessageTypes.kt:410-424` — `MediaType` enum has no entry for `201`
- `MediaType.fromId(201)` returns `UNKNOWN`
- `MessageParser.kt:261-263` discards `UNKNOWN` media types

**Fix:** Add `NAVI_IMAGE(201)` to `MediaType` enum and handle in `parseMediaData` like `ALBUM_COVER`.

### NaviOrderType Lookup Table (at rx offset `0x120ebd`)

The adapter uses a **19-entry lookup table** (NOT a simple +2 offset as previously documented):

| Proto Value | NextTurn_Enum | NaviOrderType | NaviTurnAngle | Notes |
|-------------|---------------|---------------|---------------|-------|
| 0 | UNKNOWN | 0 | 0 | Unmapped |
| 1 | DEPART | 1 | 0 | Identity (not +2) |
| 2 | NAME_CHANGE | 4 | 0 | +2 |
| 3 | SLIGHT_TURN | **6** | 1 | Collapses to TURN |
| 4 | TURN | **6** | 0 | Standard turn |
| 5 | SHARP_TURN | **6** | 2 | Collapses to TURN |
| 6 | U_TURN | **6** | 3 | Collapses to TURN |
| 7 | ON_RAMP | **6** | 4 | Collapses to TURN |
| 8 | OFF_RAMP | 10 | 0 | +2 |
| 9 | FORK | 11 | 0 | +2 |
| 10 | MERGE | **0** | 0 | Maps to none/straight |
| 11 | ROUNDABOUT_ENTER | **0** | 0 | **BUG: unmapped** |
| 12 | ROUNDABOUT_EXIT | 14 | 0 | +2 |
| 13 | ROUNDABOUT_ENTER_AND_EXIT | 15 | 0 | +2 |
| 14 | STRAIGHT | 16 | 0 | +2 |
| 15 | FERRY_BOAT | 18 | 0 | +2, gap at 17 |
| 16 | FERRY_TRAIN | **0** | 0 | **BUG: unmapped** |
| 17 | NAME_CHANGE (alias) | **11** | 0 | **BUG: maps to DESTINATION** |
| 18 | — | — | — | Not defined |
| 19 | DESTINATION | 21 | 0 | +2 |

**NaviTurnAngle sub-table** (at rx `0x1211a5`): Only differentiates severity for entries 3-7 (SLIGHT=1, TURN=0, SHARP=2, U_TURN=3, ON_RAMP=4). All others = 0.

**Correction:** The previous "+2 offset with exceptions" model was an oversimplification. The actual mapping is a hardcoded lookup table with **3 bugs** (MERGE/ROUNDABOUT_ENTER/FERRY_TRAIN → 0) and **lossy collapsing** (5 distinct maneuver types → NaviOrderType 6).

---

## 26. IPC Wire Protocol (Deep Analysis — 2026-03-16)

**Source:** r2/Ghidra decompilation of `CRiddleUnixSocketClient` at `0x119502`, message init at `0x11965c`

### Unix Socket

- Path: `/var/run/adb-driver`
- Type: `AF_UNIX, SOCK_STREAM`
- Connection: `stat()` → verify `S_IFSOCK` → `socket()` → `connect()` → store fd at `[obj+0x1C]`

### Wire Protocol Header (16 bytes)

```
+0x00: uint32_le magic         = 0x55AA55AA
+0x04: uint32_le payload_size  = byte count after header
+0x08: uint32_le cmd_type      = command type ID
+0x0C: uint32_le reserved      = 0 (or fd for SCM_RIGHTS)
```

Total: 16-byte header + variable payload.

### In-Memory Message Struct (≥0x24 bytes)

| Offset | Field | Description |
|--------|-------|-------------|
| 0x00 | magic | 0x55AA55AA |
| 0x04 | payload_size | |
| 0x08 | cmd_type | |
| 0x0C | reserved | |
| 0x10 | buf_ptr | Pointer to data portion |
| 0x14 | buf_data | Buffer start |
| 0x18 | buf_end | Buffer end |
| 0x1C | buf_offset | Current write position |
| 0x20 | fd | File descriptor (-1 = none) |

Buffer allocation: payload + 0xC0 (192) bytes overhead. Data area starts at offset 0x60 within buffer.

### CRiddleUnixSocketClient Vtable (at `0x14b980`)

| Index | Address | Function |
|-------|---------|----------|
| 0 | 0x11a1a9 | destructor_1 |
| 1 | 0x11a1cd | destructor_2 |
| 2 | 0x11a3e9 | queue_write (async queue) |
| 3 | 0x119585 | on_peer_close |
| 4 | 0x119503 | connect_socket |
| 5 | 0x1194fd | get_connection_state |

### Registration Command (0xF1)

```
Header: magic=0x55AA55AA, size=4, type=0xF1, reserved=0
Payload: uint32_le middleman_type_id
```

For AA mode: type_id = 5.

### MiddleMan Factory (at `0x11921e`)

Allocates 96-byte (0x60) objects. Switch on type parameter:

| Type | Class | MiddleMan ID |
|------|-------|-------------|
| 1 | CiOS_MiddleManInterface | 2 |
| 2 | CCarPlay_MiddleManInterface | 3 |
| 3 | CNoAirPlay_MiddleManInterface | 4 |
| 4 | CAndroidAuto_MiddleManInterface | 5 |
| 5 | CHiCar_MiddleManInterface | 6 |
| 6 | CICCOA_MiddleManInterface | 7 |
| 7 | CBaiduCarLife_MiddleManInterface | 8 |
| 19 | CDVR_MiddleManInterface | 0x14 |

### IPC Control Commands (Header type 8, first 4 bytes = sub-command)

**CORRECTED 2026-03-16:** Cross-referenced against Section 6, command_ids.md, and adb-driver binary dispatch table at `0x19744`.

| Sub-cmd | Decimal | Name | Cross-ref |
|---------|---------|------|-----------|
| 0x1F4 | 500 | RequestVideoFocus | §6 ✓ |
| 0x1F5 | 501 | ReleaseVideoFocus | §6 ✓ |
| 0x1F6 | 502 | RequestAudioFocus | §6 ✓ |
| 0x1F7 | 503 | RequestAudioFocusTransient | command_ids.md ✓ |
| 0x1F8 | 504 | RequestAudioFocusDuck | command_ids.md ✓ |
| 0x1F9 | 505 | **ReleaseAudioFocus** | §6 ✓ (was incorrectly RequestNaviFocus) |
| 0x1FA | 506 | **RequestNaviFocus** | §6 ✓, TTY log ✓ (was incorrectly ReleaseNaviFocus) |
| 0x1FB | 507 | **ReleaseNaviFocus** | §6 ✓, TTY log ✓ (was incorrectly reserved) |
| 0x01 | 1 | StartRecordMic | §6 ✓ |
| 0x02 | 2 | StopRecordMic | §6 ✓ |
| 0x07 | 7 | UseCarMic | §6 ✓ |
| 0x3E8 | 1000 | SupportWifi | §6 ✓ |
| 0x3E9 | 1001 | SupportAutoConnect | §6 ✓ |
| 0x3EA | 1002 | StartAutoConnect | §6 ✓ |
| 0x3EB | 1003 | ScaningDevices | §6 ✓ |
| 0x3EC | 1004 | DeviceFound | command_ids.md ✓ |
| 0x3ED | 1005 | DeviceNotFound | command_ids.md ✓ |
| 0x3EF | 1007 | DeviceBluetoothConnected | §6 ✓ |
| 0x3F0 | 1008 | DeviceBluetoothNotConnected | §6 ✓ |
| 0x3F1 | 1009 | DeviceWifiConnected | §6 ✓ |
| 0x3F4 | 1012 | SupportWifiNeedKo | §6 ✓ |

Note: Mic commands (1, 2, 7) use small IDs in IPC namespace. Connection status commands use 1000+ range. The original Section 26 conflated the two namespaces.

### IPC Direction (CRITICAL FINDING)

**ARMAndroidAuto only SENDS commands to adb-driver.** The `CAndroidAuto_MiddleManInterface` vtable dispatch handler (`0x118ed3`) is a **no-op (`bx lr`)**. AA does NOT process incoming IPC control commands — ARMadb-driver is the sole command dispatcher.

### IPC Data Transfer Command Types (0x119b78)

Full command type table for `writev()` IPC messages:

| Type | Name |
|------|------|
| 0x01 | Open |
| 0x02 | PlugIn |
| 0x03 | Phase |
| 0x04 | PlugOut |
| 0x05 | Command |
| 0x06 | VideoFrame |
| 0x07 | AudioFrame |
| 0x08 | CarPlayControl |
| 0x09 | LogoType |
| 0x0A | SetBluetoothAddress |
| 0x0B | CMD_CARPLAY_MODE_CHANGE |
| 0x0C | CMD_SET_BLUETOOTH_PIN_CODE |
| 0x0D | HUDComand_D_BluetoothName |
| 0x0E | CMD_BOX_WIFI_NAME |
| 0x0F | CMD_MANUAL_DISCONNECT_PHONE |
| 0x10 | CMD_CARPLAY_AirPlayModeChanges |
| 0x11 | AutoConnect_By_BluetoothAddress |
| 0x12 | Bluetooth_BondList |
| 0x13 | CMD_BLUETOOTH_ONLINE_LIST |
| 0x14 | CMD_CAR_MANUFACTURER_INFO |
| 0x15 | CMD_STOP_PHONE_CONNECTION |
| 0x16 | CMD_CAMERA_FRAME |
| 0x17 | CMD_MULTI_TOUCH |
| 0x18 | CMD_CONNECTION_URL |
| 0x19 | CMD_BOX_INFO |
| 0x1A | CMD_PAY_RESULT |
| 0x1B | BTAudioDevice_Signal |
| 0x1E | Bluetooth_Search |
| 0x1F | Bluetooth_Found |
| 0x20 | Bluetooth_SearchStart |
| 0x21 | Bluetooth_SearchEnd |
| 0x22 | ForgetBluetoothAddr |
| 0x23 | Bluetooth_ConnectStart |
| 0x24 | Bluetooth_Connected |
| 0x25 | Bluetooth_DisConnect |
| 0x26 | Bluetooth_Listen |
| 0x28 | iAP2Type_PlistBinary |
| 0x29 | GNSS_DATA |
| 0x2A | DashBoard_DATA |
| 0x2B | Connection_PINCODE |
| 0x2C | AltVideoFrame |
| 0x2D | FactorySetting |
| 0x2E | CMD_DEBUG_TEST |
| 0x2F | HUDComand_A_UploadFile |
| 0x30 | HUDComand_A_HeartBeat |
| 0x31 | CMD_UPDATE |
| 0x32 | HUDComand_B_BoxSoftwareVersion |
| 0x33 | HUDComand_A_Reboot |
| 0x34 | HUDComand_A_ResetUSB |

**IPC↔USB type remapping note:** Types 0x01-0x2C are identical between IPC and USB protocols. Types 0x2D-0x34 use contiguous IPC numbering that ARMadb-driver translates to sparse USB numbering: 0x2D→0x77, 0x2E→0x88, 0x2F→0x99, 0x30→0xAA, 0x31→0xBB, 0x32→0xCC, 0x33→0xCD, 0x34→0xCE. The 4th header field also differs: IPC uses reserved=0 (or fd for SCM_RIGHTS), USB uses `type XOR 0xFFFFFFFF` (integrity check).

---

## 27. USB Transfer Engine (Deep Analysis — 2026-03-16)

**Source:** r2/Ghidra decompilation of AsyncWrite (`0x11a1e0`), core write (`0x119b78`), libusb fill (`0x80e60`)

### AsyncWrite Function (`0x11a1e0`)

1. Checks `flags & 4` and `[obj+4]` (enabled byte)
2. Dequeues write request from linked list at `[obj+0xC]/[obj+0x10]`
3. Timestamps before/after calling core write at `0x119b78`
4. **Timing threshold:** If write ≥ 101ms, logs WARN: `"AsyncWrite use time: %lld ms, iSize: %d, ret: %d."`
5. Updates remaining byte count, calls completion callback via vtable

### Core Write Function (`0x119b78`)

- Uses `writev()` (PLT `0x38bc0`) with scatter-gather buffers for Unix socket IPC
- Uses `sendmsg()`/`recvmsg()` for SCM_RIGHTS fd passing
- Buffer minimum: 0xC0 (192) bytes. Payload starts at `[msg+0x60]` (96-byte header/metadata)

### libusb Transfer Setup (`0x80e60`)

```c
struct libusb_transfer {
    +0x00: dev_handle
    +0x05: endpoint      // byte
    +0x06: type          // 2=BULK, 3=INTERRUPT
    +0x08: length
    +0x10: callback
    +0x18: buffer
    +0x1C: flags         // LIBUSB_TRANSFER_ADD_ZERO_PACKET = 0x08
    +0x20: user_data
};
```

Transfer alloc: 0x2C bytes (44 = sizeof libusb_transfer for 0 iso packets). Single callsite for `libusb_submit_transfer` at `0x80ffc`.

### ZLP Handling

**ZLP is handled in ARMadb-driver, NOT in ARMAndroidAuto.** The string `"_SendDataToCar iSize: %d, may need send ZLP"` is in the adb-driver binary. ARMAndroidAuto sends data via Unix socket → adb-driver handles actual USB bulk transfer including ZLP injection based on `USBTransMode` config and packet alignment.

---

## 28. Audio Jitter Buffer (Deep Analysis — 2026-03-16)

**Source:** Ghidra decompilation of `BoxAudioJitterBufferInit` (`0x11bc58`), push (`0x11bb64`), pop (`0x11bd84`)

### Jitter Buffer Context (100 bytes, malloc'd)

| Offset | Field | Description |
|--------|-------|-------------|
| +0x00 | mutex | pthread_mutex (4 words) |
| +0x1C | sampleRate | e.g., 48000 |
| +0x20 | bitDepth | e.g., 16 |
| +0x24 | channelCount | e.g., 2 |
| +0x28 | cacheDurationMs | Total cache ms |
| +0x2C | playbackIntervalMs | Pop interval ms |
| +0x30 | ringBufSize | per-pop chunk: `(bits/8) × ch × rate × interval / 1000` |
| +0x34 | totalBufSize | `2 × cacheDuration × (bits/8) × ch × rate / 1000` |
| +0x38 | bufferPtr | malloc'd audio data |
| +0x3C | readyFlag | 0=filling, 1=playing |
| +0x40 | exitFlag | 1=thread exit |
| +0x44 | callback | Playback callback fn ptr |
| +0x48 | callbackCtx | Callback context |
| +0x4C | ringBuffer | Embedded circular buffer (24 bytes) |

### Ring Buffer Struct (24 bytes, at JB+0x4C)

| Offset | Field |
|--------|-------|
| +0x00 | bufPtr |
| +0x04 | capacity |
| +0x08 | readPos |
| +0x0C | writePos |
| +0x10 | reserved |
| +0x14 | emptyFlag |

### Fill Threshold

**50% fill** before playback begins: `readyFlag` transitions 0→1 when `currentFill ≥ totalBufSize / 2`.

### Push Behavior (Blocking Producer)

1. If `currentFill + newData > capacity`: logs `"JB buffer is full"`, **usleep** for pop interval
2. Re-check: if still full, logs `"JB buffer push failed!!!"`, returns -1
3. Otherwise: circular memcpy (handles wrap), sets `emptyFlag = 0`, returns 0

### Pop Behavior (Adaptive Consumer)

1. Empty: zeros output, logs `"JB buffer is empty"`, returns -1
2. Near-overflow (`fill + requested > capacity`): logs `"JB buffer will full"`, **discards half** (advances read by capacity/2)
3. Underrun (`fill < requested`): logs `"JB buffer not enough"`, pops only available data
4. Exact (`fill == requested`): logs `"JB buffer will not enough"`, pops **half** only
5. All-consumed: logs `"JB buffer all-consumed"`, sets `emptyFlag = 1`, returns -2 → resets `readyFlag = 0` (re-enters fill wait)

### Pop Intervals

| Stream | Pop Interval | Cache Duration |
|--------|-------------|---------------|
| Media (type 0) | **80ms** (0x50) | `mediaCacheMs_` (default 1000ms) |
| Speech (type 1) | **60ms** (0x3C) | `speechCacheMs_` (default 300ms) |
| Navi (type 2+) | **60ms** (0x3C) | `naviCacheMs_` (default 300ms) |

### Audio Ducking Priority (Highest → Lowest)

1. **HFP Phone Call** (callStatus==7) — preempts media AND speech
2. **VR/Speech** (vrStatus==3) — preempts media only
3. **Navigation** — independent focus channel
4. **Media Audio** — lowest, preempted by all above

When call starts: closes media via `FUN_0004dc6c(0, 2)`, or if VR active, closes speech via `FUN_0004dc6c(1, 5)`.

### Audio Focus IPC Commands

| Focus Type | IPC Byte | Meaning |
|-----------|---------|---------|
| 1 | 0xF6 | Gain |
| 2 | 0xF7 | Gain Transient |
| 3 | 0xF8 | Loss |
| 4 | 0xF9 | Loss Transient |

### BoxAudioConvert — Dead Code

`BoxAudioConvertInit` and `BoxAudioConvert err` strings exist at `0x14d4f6` but have **zero code cross-references**. The AA protocol negotiates audio format directly — no runtime resampling occurs.

---

## 29. Video Keyframe Throttle (Deep Analysis — 2026-03-16)

**Source:** Ghidra decompilation of `BoxVideoOutput::requestKeyFrame` (`0x4d910`)

```c
void BoxVideoOutput_requestKeyFrame() {
    log("requestKeyFrame called()");
    if (bVideoHide_ == 0) {
        gettimeofday(&now);
        elapsed_recv = now - lastRecvKeyFrameTime_;

        // Tier 1: If <101ms since last RECEIVED keyframe → hard ignore
        if (elapsed_recv < 101) {
            log("requestKeyFrame too quickly, ignore it!!!");
            return;
        }

        // Tier 2: If <1000ms since last REQUEST → blocking 1s sleep
        elapsed_req = now - lastVideoRequestTime_;
        if (elapsed_req < 1000) {
            log("requestKeyFrame too quickly, wait 1 second");
            sleep(1);  // BLOCKING
        }

        bIgnoreVideoFocus_ = 1;
        sendVideoCommand(3);   // cmd 3 = request keyframe
        usleep(100000);        // 100ms post-send delay
    }
    sendVideoCommand(1);      // cmd 1 = sent regardless of hide state
}
```

### Video Config Keys

**riddleBoxCfg keys** (in config_key_analysis.md, read by multiple binaries):

| Config Key | riddleBoxCfg # | Status | Purpose |
|-----------|---------------|--------|---------|
| `NeedKeyFrame` | #9 | ALIVE | Enable/disable keyframe requests (JSON: `autoRefresh`) |
| `RepeatKeyframe` | #44 | ALIVE | Keyframe repeat behavior |
| `SendEmptyFrame` | #55 | ALIVE | Empty frame keepalive (default=1) |
| `VideoBitRate` | #23 | ALIVE | Max bitrate cap (JSON: `bitRate`) |
| `SpsPpsMode` | #47 | ALIVE | SPS/PPS injection mode (4 modes) |

**ARMAndroidAuto-internal strings** (NOT in riddleBoxCfg 110-entry table):

| Config Key | Purpose |
|-----------|---------|
| `VIDEO_FRAME_CACHE_NUM` | Frame cache depth — zero code xrefs, likely dead/disabled |
| `VIDEO_TIMESTAMP_MODE` | Timestamp attachment — code default=0 (disabled), but TTY log shows `bEnableTimestamp_=1`. Likely overridden by `BoxVideoOutput::init` hardcode or adapter's riddle.conf instance has it set to 1. |
| `AltVideoFrame` | Alternate video frame IPC format |
| `VideoBitRate` | Max bitrate in Kbps (0=unlimited) |
| `BoxConfig_preferSPSPPSType` | SPS/PPS delivery mode (in-band vs out-of-band) |

These are runtime config keys read via the generic config reader — no direct code xrefs (string comparison at runtime).

### Video Focus States

| Mode | Value | When |
|------|-------|------|
| VIDEO_FOCUS_NATIVE | 1 | Native HU content |
| VIDEO_FOCUS_PROJECTED | 2 | AA projection |
| VIDEO_FOCUS_NATIVE_TRANSIENT | 3 | Temporary overlay |

`onVideoFocusChanged(2)` (focus lost) triggers `sendVideoCommand(3)` — same as keyframe request.

---

## 30. Undocumented Functionality (Deep Analysis — 2026-03-16)

**Source:** Comprehensive string analysis (10,800+ strings) + r2/Ghidra cross-referencing

### 30.1 Multi-Protocol IPC Stubs

The binary is NOT AA-only. It contains complete MiddleMan interface classes for 8 protocols:

| Class | MiddleMan ID | Status |
|-------|-------------|--------|
| CiOS_MiddleManInterface | 2 | Active (iAP2) |
| CCarPlay_MiddleManInterface | 3 | Active |
| CNoAirPlay_MiddleManInterface | 4 | Active (suppression) |
| CAndroidAuto_MiddleManInterface | 5 | **Active** |
| CHiCar_MiddleManInterface | 6 | Stub |
| CICCOA_MiddleManInterface | 7 | Stub |
| CBaiduCarLife_MiddleManInterface | 8 | Stub |
| CDVR_MiddleManInterface | 0x14 | Active (dashcam) |

### 30.2 Device Authentication / Anti-Cloning

- `hwSecret %s > %s;rm -f %s` — hardware secret gen + cleanup
- `/etc/.custom_code` — custom device code
- `/etc/.manufacture_date_sign` — manufacturing date signature
- `CheckBoxManuDateSign: %s %s` — signature verification
- `CreateSerialNumber!!!` — serial generation
- Hardcoded hex constants: `DECAFADEDECADEAFDECACAFF` (magic/key material)

### 30.3 Payment/Licensing

- `CMD_PAY_RESULT` (IPC type 0x1A) — payment result command
- Suggests activation or feature unlock mechanism

### 30.4 DVR/Camera Integration

- `CMD_CAMERA_FRAME` (IPC type 0x16) — camera frame relay
- `BackRecording` config — dashcam background recording
- `CDVR_MiddleManInterface` (MiddleMan ID 0x14)

### 30.5 Config Keys Referenced in ARMAndroidAuto

Cross-reference (2026-03-16): Of 20 config key strings found in this binary, **19 map directly to known riddleBoxCfg keys** already documented in `config_key_analysis.md`. This confirms the 2026-03-15 correction that ARMAndroidAuto DOES access the riddle config system, contradicting the original "zero config strings" claim.

**Known riddleBoxCfg keys also in ARMAndroidAuto** (see `config_key_analysis.md` for full analysis):

| Key | riddleBoxCfg # | Status | JSON Field |
|-----|---------------|--------|------------|
| BoxConfig_DelayStart | #6 | ALIVE | `startDelay` |
| BoxConfig_preferSPSPPSType | #7 | DEAD | — |
| BoxConfig_UI_Lang | #5 | ALIVE | `lang` |
| CustomFrameRate | #14 | ALIVE | `fps` |
| DuckPosition | #77 | ALIVE | `DockPosition` |
| EchoLatency | #10 | ALIVE | `echoDelay` |
| FastConnect | #62 | ALIVE | `fastConnect` |
| HNPInterval | #41 | DEAD | — |
| ImprovedFluency | #64 | PASS-THROUGH | `improvedFluency` |
| InternetHotspots | #74 | DEAD | — |
| AudioMultiBusMode | #72 | DEAD | — |
| GNSSCapability | #70 | ALIVE | `GNSSCapability` |
| AdvancedFeatures | #78 | ALIVE | — |
| HiCarConnectMode | #69 | ALIVE | `HiCarConnectMode` |
| BoxSupportArea | #40 | ALIVE | — |
| iAP2TransMode | #0 | ALIVE | `syncMode` |
| autoDisplay | #56 | DEAD | — |
| CarLinkType | #53 | ALIVE | `carLinkType` |

**VERIFIED 2026-03-16 (r2 exhaustive xref scan):** All 4 DEAD keys are confirmed **DEAD in ARMAndroidAuto too**. They exist in the 110-entry config table (as vestigial struct entries) but none of the 26 `GetBoxConfig`/`GetBoxConfigBool` call sites in the binary pass these key names. Only 20 of 110 config keys are actually read at runtime. The DEAD classification in `config_key_analysis.md` remains correct across all 7 binaries.

| Key | Config Table Entry | GetBoxConfig Calls | Status |
|-----|-------------------|-------------------|--------|
| BoxConfig_preferSPSPPSType | Entry 52 (default=0) | 0 | DEAD |
| HNPInterval | Entry 78 (default=10) | 0 | DEAD |
| AudioMultiBusMode | Entry 105 (default=1) | 0 | DEAD |
| autoDisplay | Entry 92 (default=1) | 0 | DEAD |

**Not in riddleBoxCfg (truly AA-internal):**

| Key | Notes |
|-----|-------|
| `audio_loopback` | **Protobuf schema artifact** (VERIFIED 2026-03-16) — field #12 (bool) in `aasdk.proto.data.RadioProperties` message, alongside `radio_id`, `af_switch`, `mute_capability`. No config table entry, zero code xrefs. The linker stripped `set_audio_loopback` via `--gc-sections`. The AA SDK defines it but the CPC200-CCPA firmware never accesses it. |
| `FactorySetting` | **Not a config key** — this is USB message type 0x77 (IPC type 0x2D), a dual-purpose command: A→H idle notification / H→A factory reset trigger. See `usb_protocol.md` Section FactorySetting (0x77). |

### 30.6 Phone Call Metadata

Full call metadata forwarded: `CallDirection`, `CallID`, `CallName`, `CallNumber`, `CallQuality`, `CallStatus`, `caller_id`, `caller_number`, `caller_number_type`, `caller_thumbnail`, `call_duration_seconds`, `call_available`.

### 30.7 WiFi Chip Detection

Runtime chip detection via `/sys/bus/sdio/devices/mmc0:0001:1/device`:
- Broadcom: BCM4335 (0x4335), BCM4354 (0x4354), BCM4358 (0x4358)
- Realtek: RTL8822CS (loaded via `insmod /tmp/88x2bs.ko`)
- Additional IDs: 0x9149, 0x9159, 0xaa31, 0xb822, 0xc822

### 30.8 OEM Branding System

- `oemIconLabel` / `oemIconVisible` in riddle.conf
- `/etc/box_brand.png` → multi-resolution symlink chain (120×120, 180×180, 256×256)
- `SaveIcon`: aa_32x32.png, aa_64x64.png, aa_128x128.png saved to /tmp/

### 30.9 DailyActiveInfo Telemetry

`DailyActiveInfo: %s` — collects/reports usage telemetry including connection count, success rate, speed, device model. Likely sent via `BrandServiceURL`.

### 30.10 GPIO LED Control

- `echo 0/1 > /sys/class/gpio/gpio2/value` — BT status LED
- `echo 0/1 > /sys/class/gpio/gpio9/value` — WiFi status LED

### 30.11 SelfConnectedLoopbackSocket

`SelfConnectedLoopbackSocketE` — internal thread wakeup mechanism (epoll wakeup pattern, alternative to eventfd).

---

## 31. Global Variable Map (Deep Analysis — 2026-03-16)

**Source:** Ghidra RW segment analysis of `ARMAndroidAuto_rx.bin`

### BoxAudioOutput

| Address | Member | Type |
|---------|--------|------|
| 0x189DC4 | mutex_ | pthread_mutex* |
| 0x18A4F0 | callStatus_ | int* |
| 0x18AE70 | mediaStatus_ | int* |
| 0x189CD8 | vrStatus_ | int* |
| 0x18AE80 | naviStatus_ | int* |
| 0x18AFE4 | mediaCacheMs_ | int* |
| 0x18A24C | speechCacheMs_ | int* |
| 0x18A3DC | naviCacheMs_ | int* |

### BoxVideoOutput

| Address | Member | Type |
|---------|--------|------|
| 0x189D08 | bVideoHide_ | char* |
| 0x18AF38 | bIgnoreVideoFocus_ | char* |
| 0x18A8EC | maxVideoBitRate_ | int* |
| 0x18A4F8 | lastRecvKeyFrameTime_ | timeval* |
| 0x189DA4 | lastVideoRequestTime_ | timeval* |

### Config

| Address | Key | Type |
|---------|-----|------|
| 0x181970 | USBConnectedMode | int (default 0) |
| 0x181980 | USBTransMode | int (default 0) |
| 0x181960 | autoDisplay | int (default 1) |
| 0x181990 | ReturnMode | int (default 0) |

### IPC

| Address | Purpose |
|---------|---------|
| 0x18B7A0 | IPC callback function pointer |
| 0x18BDC8 | gLinkParam (36 bytes) |
| 0x18BCD8 | maxVideoBitRate global |

### Key Function Addresses

| rx vaddr | Function |
|----------|----------|
| 0x3A8A0 | Resolution selection + wireless cap |
| 0x4D910 | BoxVideoOutput::requestKeyFrame |
| 0x4D7E8 | BoxVideoOutput::onVideoStateChanged |
| 0x4DA98 | BoxVideoOutput config init |
| 0x4DFC0 | BoxAudioOutput::start |
| 0x4DF40 | setAudioCacheMs |
| 0x4E310 | onVRStatusChanged |
| 0x4E398 | onHFPCallStatusChanged |
| 0x4DE88 | onAudioFocusChanged |
| 0x5520C | NaviJSON builder |
| 0x493E8 | sendData (IPC) |
| 0x63E98 | VideoService::sendVideoFocusIndication |
| 0x69BCC | NavigationStatusService::onTurnEvent |
| 0x7B32C | NavigationStatusServiceChannel::messageHandler |
| 0x80E60 | libusb_fill_bulk_transfer |
| 0x80FFC | libusb_submit_transfer thunk |
| 0x11921E | New_MiddleManClient factory |
| 0x119502 | CRiddleUnixSocketClient::connect |
| 0x11965C | Message header init |
| 0x119B78 | Core IPC write (writev) |
| 0x11A1E0 | AsyncWrite |
| 0x11B73C | GetBoxConfig |
| 0x11BA20 | Config bool reader |
| 0x11BB30 | BoxAudioRingBufferInit |
| 0x11BB64 | JB Push |
| 0x11BC58 | BoxAudioJitterBufferInit |
| 0x11BD84 | JB Pop |
| 0x11BEB8 | JB playback thread |
