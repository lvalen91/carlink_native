# CPC200-CCPA Key Binaries Reference

**Purpose:** Reference for firmware binary analysis
**Consolidated from:** GM_research, pi-carplay firmware extraction
**Last Updated:** 2026-02-19 (Added: process architecture, MiddleMan IPC protocol, outbound send pipeline, 7 MiddleMan interfaces)

---

## Executable Binaries

| Binary | Packed Size | Unpacked Size | Purpose |
|--------|-------------|---------------|---------|
| AppleCarPlay | 325KB | 573KB | Main CarPlay receiver |
| ARMiPhoneIAP2 | 182KB | 494KB | iPhone IAP2 protocol handler |
| ARMadb-driver | 217KB | 479KB | Main USB protocol handler |
| ARMAndroidAuto | 489KB | 1,489KB | Android Auto protocol handler |
| ARMHiCar | - | 132KB | Huawei HiCar support |
| ARMandroid_Mirror | - | 106KB | Android mirroring |
| bluetoothDaemon | 173KB | 409KB | Bluetooth management |
| mdnsd | - | 141KB | mDNS/Bonjour service (378KB runtime BSS) |
| boxNetworkService | 45KB | - | Network management (not UPX packed) |
| riddleBoxCfg | 30KB | 50KB | Configuration CLI |
| server.cgi | - | 74KB | Web UI backend |
| upload.cgi | - | 53KB | File upload handler |
| ARMimg_maker | 21KB | 38KB | Firmware image tool |
| hfpd | - | Static | Bluetooth HFP daemon |
| hwSecret | - | 23KB | Secret/key management |
| riddle_top | - | 10KB | Process monitor |
| colorLightDaemon | - | - | Red/Blue LED status controller |
| adbd | - | - | Android Debug Bridge daemon |
| boa | - | - | Web server (HTTP) |
| am | - | - | Activity manager |

### Binary Availability Status

| Binary | Packed | Unpacked | Ghidra Analyzed |
|--------|--------|----------|-----------------|
| ARMadb-driver | ✅ | ✅ | ✅ Fully analyzed |
| ARMiPhoneIAP2 | ✅ | ✅ | ✅ Fully analyzed (2026-02-28) |
| AppleCarPlay | ✅ | ✅ | ✅ Fully analyzed (2026-02-28) |
| bluetoothDaemon | ✅ | ✅ | ✅ Fully analyzed (2026-02-28) |
| riddleBoxCfg | ✅ | ✅ | ✅ Fully analyzed (2026-02-28) |
| ARMimg_maker | ✅ | ✅ | ✅ Key extracted |
| ARMAndroidAuto | ✅ Custom packer | ✅ (1,488,932 bytes) | ✅ Analyzed (2026-02-28) — no section headers, does NOT link config library |
| ARMHiCar | ✅ | ✅ | ✅ Analyzed (2026-02-28) |
| server.cgi | ✅ | ✅ | ✅ Analyzed (2026-02-28) |

**Note:** `ARMAndroidAuto` uses a **custom LZMA-based packer** (NOT standard UPX):
- Magic: `0x55225522` (`U"U"`) at file offset where compressed data begins
- Packed size: 489,800 bytes → decompressed size: 1,488,932 bytes (3:1 ratio)
- Entropy: 8.00 bits/byte (maximum — effectively encrypted/maximally compressed)
- Decompressor stub: 5,844 bytes at offset 0x76274
- Stub uses direct Linux syscalls: `readlink("/proc/self/exe")` → `mmap2()` → LZMA decompress (lc=2, lp=0, pb=3) → `open("/dev/hwas", O_RDWR)` → `ioctl(fd, 0xC00C6206, ...)` → `mprotect()` → jump to decompressed code
- **Successfully unpacked** (2026-02-28): UPX with header fix unpacked to 1,488,932 bytes. No section headers in output ELF.
- **Key finding:** ARMAndroidAuto does NOT statically link the riddleBoxCfg config library — zero config system strings (no "BoxConfig", "riddle", "riddleConfig" etc.). It cannot read riddleBoxCfg keys directly.
- The same packer is used for `ARMadb-driver`, `ARMiPhoneIAP2`, and `AppleCarPlay` (but those also have UPX layer)

#### ImprovedFluency Binary Trace (2026-02-28)

All 8 firmware binaries containing the `ImprovedFluency` config table entry were exhaustively
analyzed via Ghidra headless decompilation and r2 cross-reference tracing:

| Binary | GetBoxConfig Callers | Passes "ImprovedFluency"? | Any Code Ref? |
|--------|---------------------|--------------------------|---------------|
| ARMadb-driver (1,909 funcs) | 24 | No | No |
| AppleCarPlay | 12 | No | No |
| ARMiPhoneIAP2 | 17 (14 get + 3 set) | No | No |
| bluetoothDaemon (1,524 funcs) | 8 | No | No |
| ARMHiCar | 0 code refs | No | No (table only) |
| server.cgi | 2 (JSON pass-through) | Pass-through only | No branching |
| riddleBoxCfg | Generic table iterate | Pass-through only | No branching |
| ARMAndroidAuto | N/A — no config library | N/A | N/A |

**Conclusion:** `ImprovedFluency` is an **unimplemented/dead config key** in firmware 2025.10.15.1127.
The web UI (`advanced.html`) describes the intended behavior as "Increase USB bulk transfer
buffers and adjust pcm_get_buffer_size" but this was never implemented in any binary. The config
table entry propagates to all binaries via statically linked config library, and server.cgi
serializes it for the web API, but no runtime code reads the value.

### ARMAndroidAuto Runtime Analysis (TTY Logs - Jan 2026)

The binary was successfully unpacked (2026-02-28) — see note above. TTY logs also reveal its runtime behavior:

**Framework:** Based on OpenAuto (open-source Android Auto implementation)

**Log Prefixes:**
| Prefix | Component |
|--------|-----------|
| `[OpenAuto]` | OpenAuto SDK wrapper |
| `[AaSdk]` | Android Auto SDK core |
| `[AndroidAutoEntity]` | Connection/session manager |
| `[BoxVideoOutput]` | Video output handler |
| `[BoxAudioOutput]` | Audio output handler |
| `[MediaStatusService]` | Media playback info |
| `[BluetoothServiceChannel]` | BT pairing coordination |

**Services Exposed:**
```cpp
AudioInputService        // Mic input from host
AudioService            // MEDIA_AUDIO, SPEECH_AUDIO, SYSTEM_AUDIO
SensorService          // Night mode, GPS, driving status
VideoService           // H.264 video output
BluetoothService       // BT pairing with phone
MediaStatusService     // Playback state, metadata
InputService           // Touch input (1920x1080)
GenericNotificationService // System notifications
```

**SSL/TLS Authentication:**
- Cipher: `ECDHE-RSA-AES128-GCM-SHA256`
- CA Certificate: Google Automotive Link (Mountain View, CA)
- Subject: CarService
- Validity: Jul 2014 - Apr 2026

**Communication with ARMadb-driver:**
- IPC via Unix socket `/var/run/adb-driver`
- MiddleManInterface type 5 = AndroidAuto
- Commands forwarded via `_SendPhoneCommandToCar()`
- Bridge via `libboxtrans.so`: `sendTransferData()`, `MiddleManClient_SendData()`

**DMSDP Integration:**
- Uses Huawei DMSDP stack (libdmsdp.so) for RTP media transport
- H.264/AVC via `DMSDPRtpSendQueueAVC`, FU-A fragmentation
- PCM audio via `DMSDPRtpSendQueuePCM`
- AAC audio via `DMSDPRtpSendQueueAAC`
- I-frame requests via `DMSDPServiceOpsTriggerIFrame`
- Data session FSM: INIT → NEG → SETUP → PLAY → ESTABLISHED

**Video Output:**
- SPS/PPS parsing: `spsWidth: 1920, spsHeight: 1088` (8-line alignment)
- H.264 I-frame detection for keyframe requests
- Configurable bitrate: `maxVideoBitRate = 5000 Kbps`

---

## Key Libraries — Deep r2 Analysis Feb 2026

The adapter firmware is built on **Huawei's DMSDP (Distributed Multimedia Service Discovery Protocol)** framework, part of HarmonyOS/OpenHarmony distributed capability stack.

### Core Stack (7 libraries, layered architecture)

| Library | Size | Role |
|---------|------|------|
| **libdmsdpplatform.so** | 242KB | Platform layer — FillP reliable UDP, crypto (AES-128/256-GCM), epoll sockets, threading |
| **libdmsdp.so** | 185KB | Core DMSDP — Service/session mgmt, RTP/RTCP, AVC/AAC/PCM packetization, FSM, ability negotiation |
| **libauthagent.so** | 42KB | Authentication — BT pairing, trust mgmt (HKS keystore), PIN auth, PBKDF2, device identity |
| **libhicar.so** | 37KB | HiCar SDK facade — Projection start/stop/pause, device connect/disconnect, QR code, BLE advertising |
| **libmanagement.so** | 33KB | Management channel — Encrypted data channel (AES-128-GCM), metadata callbacks, feature info |
| **libARMtool.so** | 27KB | Utility — Threading (CMutex, CAutoThread, CThreadsafe), ring buffers, Win32-compat API |
| **libboxtrans.so** | 5KB | Transport bridge — CarPlay MiddleManClient, data/phase/riddle transfer, CPoll event loop |

### Additional DMSDP Libraries

| Library | Size | Purpose |
|---------|------|---------|
| libdmsdpcrypto.so | 80KB | Crypto (X25519, AES-GCM) — 2025.02 only; removed in 2025.10 |
| libdmsdpaudiohandler.so | 42KB | Audio dispatch |
| libdmsdpcamerahandler.so | 9KB | Camera dispatch |
| libdmsdpdvaudio.so | 45KB | Digital audio streaming |
| libdmsdpdvcamera.so | 37KB | Camera streaming |
| libdmsdpdvdevice.so | - | Device protocol constants |
| libdmsdpdvinterface.so | 16KB | Interface protocol |
| libdmsdpsec.so | 25KB | Security/encryption |

### Third-Party / Support Libraries

| Library | Purpose |
|---------|---------|
| libfdk-aac.so.1.0.0 | AAC decoder (336KB) |
| libtinyalsa.so | Hardware abstraction (17KB) |
| libcrypto.so.1.1 / libssl.so.1.1 | OpenSSL crypto/TLS |
| libHwKeystoreSDK.so | Huawei Key Store API (168KB) |
| libHisightSink.so | Huawei HiSight video sink (147KB) |
| libnearby.so | Google Nearby protocol (91KB) |

### libboxtrans.so Key Exports (CarPlay Bridge)

| Export | Purpose |
|---|---|
| `initTransfer` / `closeTransfer` | Initialize/tear down data transfer bridge |
| `runTransferLoop` / `quitTransferLoop` | Main event loop for data forwarding |
| `sendTransferData` | Forward data from phone protocol to USB host |
| `sendTransferData_RiddleData` | Forward encoded data |
| `sendHiCarPhase` | Send HiCar connection phase info |
| `NeedLikeCarPlay` | Check if `/usr/sbin/fakeiOSDevice` exists |
| `NeedLikeHiCar` | Check if `/usr/sbin/fakeHiCarDevice` exists |
| `MiddleManClient_EnsureConnect` / `_SendData` / `_SendPhase` / `_SendRiddleData` | Middle-man proxy |

### libmanagement.so Key Exports (Metadata Channel)

| Export | Purpose |
|---|---|
| `InitDataChannel` / `ReleaseDataChannel` | Data channel to USB host |
| `SetFeatureInfo` | Set feature capabilities |
| `RegisterMetaDataCallback` / `UnRegisterMetaDataCallback` | Metadata callback registration |
| `ReleaseMetaDataSocket` | Release metadata socket |
| `AuthorizeUploadLog` | Authorize log upload |

---

## UPX Unpacking

All main binaries are UPX-packed with a custom variant. To unpack:

```bash
# Requires ludwig-v's modified UPX
/path/to/modified/upx -d binary -o binary_unpacked

# Or on the adapter itself
./upx -d /usr/sbin/ARMadb-driver -o /tmp/ARMadb-driver_unpacked
```

---

## ARMadb-driver Process Architecture (Live Verified Feb 2026)

**Supervisor model**: `main()` spawns 3 child processes in a supervisor loop. Max 30 restarts before full reset.

| PID (example) | Process/Thread | Stack Size | Role |
|---------------|----------------|------------|------|
| 199 | ARMadb-driver (parent) | 43KB | USB host protocol + MiddleMan IPC server |
| 199 child 1 | `_usb_monitor_main` | — | USB hotplug monitoring via libusb |
| 199 child 2 | `CMiddleManServer` | 16KB | IPC broker (Unix socket `/var/run/phonemirror`) |
| 199 child 3 | `_hu_link_main` | 8KB | Head Unit USB accessory link |

### MiddleMan IPC Protocol (Binary Verified Feb 2026)

**Uses the same 16-byte header format as the USB protocol!**

| Property | Value |
|----------|-------|
| Socket paths | `/var/run/phonemirror`, `/var/run/adb-driver` |
| Runtime sockets | `/tmp/unix_udp_pid_{PID}_tid_{TID}` (Unix datagram) |
| Header format | `[magic 0x55AA55AA][length][type][check=~type]` — identical to USB |
| Registration | Type `0xF1` for IPC registration/keepalive (proved at `0x64326`: `movs r1, 0xF1`) |
| Live count | ~649 unix_udp sockets accumulated during active session |

### MiddleMan Interfaces

| Interface Class | Binary | Link Type |
|----------------|--------|-----------|
| `CCarPlay_MiddleManInterface` | AppleCarPlay | CarPlay |
| `CiOS_MiddleManInterface` | AppleCarPlay / ARMiPhoneIAP2 | iOS |
| `CAndroidAuto_MiddleManInterface` | ARMAndroidAuto | Android Auto |
| `CHiCar_MiddleManInterface` | ARMHiCar | HiCar |
| `CAndroidMirror_MiddleManInterface` | ARMandroid_Mirror | Mirroring |
| `CDVR_MiddleManInterface` | (no hardware) | DVR |
| `CICCOA_MiddleManInterface` | iccoa | ICCOA |

### Outbound Message Send Pipeline (Binary Verified Feb 2026)

Full send pipeline traced from ARMadb-driver function addresses:

```
initHeader(0x64650) → prepareMessage(0x64670) → buildPayload(0x64768)
→ sendMessage(0x18598) → normalizeHeader(0x64630) → writeToFd(0x64C70)
```

| Function | Address | Role |
|----------|---------|------|
| `fcn.00064650` | 0x64650 | Init 36-byte msg struct with 0x55AA55AA magic |
| `fcn.00064670` | 0x64670 | Set type, malloc payload buffer (+0xC0 pad, 0x60 header offset) |
| `fcn.000646fa` | 0x646FA | Finalize wrapper (trampoline to 0x64670) |
| `fcn.00064768` | 0x64768 | Build payload: set type, data ptr, length |
| `fcn.00018598` | 0x18598 | Main send: log, encrypt check, write to USB fd |
| `fcn.00064630` | 0x64630 | Normalize header: BB→AA magic, recompute check=~type |
| `fcn.00064c70` | 0x64C70 | Write to fd: header + payload via write() loop |
| `fcn.00064808` | 0x64808 | Encryption layer (memcpy through AES encrypt) |

---

## Key Functions (ARMadb-driver)

| Function | Address | Purpose |
|----------|---------|---------|
| FUN_00018244 | 0x18244 | Message encryption/validation |
| FUN_00018e2c | 0x18e2c | Main message dispatcher / _SendDataToCar |
| FUN_00066190 | 0x66190 | riddle.conf config writer |
| FUN_00062e1c | 0x62e1c | Message buffer init |
| FUN_00062f34 | 0x62f34 | Message buffer populate |
| FUN_00017340 | 0x17340 | Main command handler (13,546 bytes) |
| FUN_00018088 | 0x18088 | Message pre-processor |
| FUN_000628a4 | 0x628a4 | Message buffer/send wrapper |
| FUN_00065178 | 0x65178 | JSON field extractor |

### Video-Related Strings (ARMadb-driver)

| String | Address | Purpose |
|--------|---------|---------|
| `recv CarPlay videoTimestamp:%llu` | 0x6d139 | Video timestamp logging |
| `recv AA videoTimestamp:%llu` | 0x6d043 | Android Auto video timestamp |
| `recv HiCar videoTimestamp:%llu` | 0x6cdbe | HiCar video timestamp |
| `_SendDataToCar iSize: %d, may need send ZLP` | 0x6b823 | USB transmission |
| `CarPlay recv data size error!` | 0x6d0fc | Video reception error |
| `box video frame rate: %d, %.2f KB/s, audio frame rate: %d, %.2f KB/s` | 0x6f62a | Video/audio statistics |
| USB magic `0x55AA55AA` | 0x62e18 | Protocol header constant |
| `recv CarPlay size info:%dx%d` | - | Resolution logging (no validation) |
| `set frame format: %s %dx%d %dfps` | - | Format setting (no bounds check) |
| `Not Enough Bandwidth` | - | Bandwidth limit warning |
| `Bandwidth Limit Exceeded` | - | Bandwidth limit error |

### Video Limit-Related Strings (AppleCarPlay)

| String | Address | Purpose |
|--------|---------|---------|
| `### Failed to allocate memory for video frame with timestamp!` | - | Memory allocation failure |
| `### H264 data buffer overrun!` | - | Buffer overflow error |
| `kScreenProperty_MaxFPS :%d` | - | Max FPS property (dynamic, not hardcoded) |
| `format[%d]: %s size: %dx%d minFps: %d maxFps: %d` | - | FPS range tracking |
| `### tcpSock recv bufSize: %d, maxBitrate: %d Mbps` | - | Bitrate limit (configurable) |
| `/tmp/screen_fps` | - | Runtime FPS config file (not found in binary strings; may be runtime path) |
| `/tmp/screen_size` | - | Runtime resolution config file (not found in binary strings; may be runtime path) |
| `--width %d --height %d --fps %d` | - | AppleCarPlay launch parameters |

---

## Key Functions (AppleCarPlay)

| Function/String | Address | Purpose |
|-----------------|---------|---------|
| `AirPlayReceiverSessionScreen_ProcessFrames` | 0x7ecbf | Receives H.264 stream |
| `_AirPlayReceiverSessionScreen_ProcessFrame` | 0x7ecea | Process single frame |
| `ScreenStreamProcessData` | 0x8ff62 | Raw stream handling |
| `### Send screen h264 frame data failed!` | 0x9016d | H.264 send error |
| `### Send h264 I frame data %d byte!` | 0x900f8 | I-frame transmission |
| `### H264 data buffer overrun!` | 0x900d9 | Buffer overflow |
| `### h264 frame data parse error!` | 0x9011d | NAL parsing error |
| `_create_unix_socket %s SUC` | - | Unix socket IPC |

### Video Processing Note

**Video from CarPlay/Android Auto is NOT transcoded.** The AppleCarPlay binary receives H.264 via AirPlay, parses NAL units for keyframe detection, and forwards raw H.264 data to ARMadb-driver via Unix socket. ARMadb-driver prepends USB headers and transmits to the host. The host application must decode H.264.

---

## Shell Execution Strings (ARMadb-driver) - Security Relevant

The firmware uses `popen()` with `/bin/sh` to execute shell commands. Many accept user-controlled parameters, enabling **arbitrary command execution**.

### Commands with User Input (Command Injection - CRITICAL)

| Command Pattern | Input Source | Risk |
|-----------------|--------------|------|
| `sed -i "s/^ssid=.*/ssid=%s/" /etc/hostapd.conf` | BoxSettings wifiName | **CRITICAL** |
| `sed -i "s/name .*;/name \"%s\";/" /etc/bluetooth/hcid.conf` | BoxSettings btName | **CRITICAL** |
| `sed -i "s/^.*oemIconLabel = .*/oemIconLabel = %s/" %s` | oemIconLabel config | **CRITICAL** |
| `echo -n %s > /etc/box_product_type` | BoxSettings | MEDIUM |

For complete vulnerability analysis and exploitation details, see `03_Security_Analysis/vulnerabilities.md`.

### Hardcoded Shell Commands (50+ found)

| Command | Trigger |
|---------|---------|
| `killall AppleCarPlay; AppleCarPlay --width %d --height %d --fps %d` | Open message |
| `killall ARMiPhoneIAP2;ARMiPhoneIAP2 %d %d %d 2&` | Phone connection |
| `mv %s %s;tar -xvf %s -C /tmp;rm -f %s;sync` | hwfs.tar.gz upload |
| `/script/phone_link_deamon.sh %s start &` | Phone link start |
| `sync;sleep 1;reboot` | Reboot command |
| `cp /tmp/carlogo.png /etc/boa/images/carlogo.png` | Logo upload |
| `rm -f /etc/riddle.conf /tmp/.riddle.conf` | Factory reset |
| `df -h >> %s;du -sh /* >> %s;cat /proc/meminfo >> %s;ps -l >> %s` | Debug test |
| `echo y > /sys/module/printk/parameters/time;dmesg >> %s` | Debug test |

### Script Execution

| Script Path | Trigger |
|-------------|---------|
| `/script/phone_link_deamon.sh %s start` | Phone connection |
| `/script/start_bluetooth_wifi.sh` | BT/WiFi init |
| `/script/open_log.sh` | Debug mode |
| `/script/update_box_ota.sh %s` | OTA update |
| `/script/custom_init.sh` | **Boot (if exists)** |

### SendFile Related Strings

| String | Purpose |
|--------|---------|
| `SEND FILE: %s, %d byte` | File upload logging |
| `UPLOAD FILE: %s, %d byte` | File upload logging |
| `UPLOAD FILE Length Error!!!` | Size validation error |
| `/tmp/uploadFileTmp` | Staging location |

---

## DMSDP Protocol Functions (libdmsdp.so)

```cpp
// Protocol initialization
DMSDPInitial()
DMSDPServiceStart()
DMSDPServiceStop()

// Data transmission
DMSDPConnectSendData()
DMSDPConnectSendBinaryData()
DMSDPNetworkSessionSendCrypto()

// Session management
DMSDPDataSessionNewSession()
DMSDPDataSessionSendCtrlMsg()

// RTP streaming
DMSDPCreateRtpReceiver()
DMSDPCreateRtpSender()
DMSDPRtpSendPCMPackMaxUnpacket()

// Channel management
DMSDPChannelProtocolCreate()
DMSDPChannelGetDeviceType()
DMSDPChannelGetDeviceState()
DMSDPChannelGetBusinessID()             // Get business identifier
DMSDPChannelMakeNotifyMsg()             // Create notification message
DMSDPChannelHandleMsg()
DMSDPChannelDealGlbCommand()
DMSDPNearbyChannelSendData()
DMSDPNearbyChannelUnPackageRcvData()

// Service loading
DMSDPLoadAudioService()
DMSDPLoadCameraService()
DMSDPLoadGpsService()
```

---

## iAP2 Engines (ARMiPhoneIAP2) — Deep r2 Analysis Feb 2026

**Developer**: Hewei (HiCarPackage), source paths: `Sources/ARMiPhoneIAP2/`, `Sources/iAP2*.cpp`
**Engine datastore**: `/etc/RiddleBoxData/AIEIPIEREngines.datastore`

### Complete Engine Class Hierarchy

All engines inherit from `CiAP2Engine` (mangled: `11CiAP2Engine`) with a `BaseEngine` base.

| # | Engine Class | Mangled Name | Purpose |
|---|---|---|---|
| 1 | `CiAP2IdentifyEngine` | `19CiAP2IdentifyEngine` | Device identification, component registration |
| 2 | `CiAP2MediaPlayerEngine` | `22CiAP2MediaPlayerEngine` | NowPlaying: title, artist, album, artwork, playback, queue, lyrics, Like/Ban |
| 3 | `CiAP2CommunicationEngine` | `24CiAP2CommunicationEngine` | Cellular: signal strength, carrier, mute, call count, voicemail |
| 4 | `CiAP2CallStateEngine` | `20CiAP2CallStateEngine` | Call: name, number, direction, status, UUID, availability flags |
| 5 | `CiAP2PowerEngine` | `16CiAP2PowerEngine` | Battery: charge level, charging state, external charger |
| 6 | `CiAP2WiFiConfigEngine` | `21CiAP2WiFiConfigEngine` | WiFi: SSID, password, channel, P2P mode |
| 7 | `CiAP2LocationEngine` | `19CiAP2LocationEngine` | GNSS: NMEA passthrough (used by CarLink) |
| 8 | `CiAP2RouteGuidanceEngine` | `24CiAP2RouteGuidanceEngine` | Navigation: NaviJSON (used by CarLink) |
| 9 | `CiAP2VehicelStatEngine` | `22CiAP2VehicelStatEngine` | Vehicle: outside temperature, range warning |

**Infrastructure classes:**
- `CiAP2Session` / `CiAP2Session_CarPlay` — session handlers
- `CCarPlay_MiddleManInterface` / `CNoAirPlay_MiddleManInterface` — HUD-to-adapter bridge
- `CMiddleManClient` / `CMiddleManClient_iAPBroadCast` — data relay to host app
- `CiAP2Session_FileTransfer` — artwork and file transfer sessions

### Complete iAP2 Message Dispatch Table (60+ messages)

From `HudiAP2Session_CarPlay.cpp`, logged as `"Message from iPhone: 0x%04X %s"`:

| Message | Direction | Category |
|---|---|---|
| `CarPlayAvailability` | iPhone→HU | Session |
| `CarPlayStartSession` | iPhone→HU | Session |
| `DeviceTimeUpdateMsgID` | iPhone→HU | Device |
| `StopUSBDeviceModeAudio` | iPhone→HU | Audio |
| `USBDeviceAudioInformation` | iPhone→HU | Audio |
| `StartUSBDeviceModeAudio` | HU→iPhone | Audio |
| `WirelessCarPlayUpdateMsg` | Both | Transport |
| `AccessoryHIDMsg` | HU→iPhone | HID |
| `DeviceHIDReport` | iPhone→HU | HID |
| `AcceptCall` | HU→iPhone | Call Control |
| `EndCall` | HU→iPhone | Call Control |
| `DeviceUUIDUpdateMsg` | iPhone→HU | Device |
| `TransportNotify` | Both | Transport |
| `ReqAuthCert` / `ReqChallenge` / `ChallengeRsp` / `AuthSuccess` / `AuthSerNum` | Both | Auth |
| `StartIdentify` / `IdentifyInfo` / `IdentifyAccept` / `RejectIdentify` | Both | Identify |
| `WifiConfigInfo` / `ReqWifiConfig` | Both | WiFi |
| `StartNowPlayingUpdate` / `NowPlaying` / `StopNowPlayingUpdate` | Both | Media |
| `SetNowPlayingInformation` | HU→iPhone | Media |
| `StartPowerUpdate` / `StopPowerUpdate` / `HUDPowersourceUpdate` | Both | Power |
| `StartExernalAccessoryProtocol` / `StopExernalAccessoryProtocol` / `StatusExernalAccessoryProtocol` | Both | EAP |
| `RequestAppLaunch` | HU→iPhone | App |
| `StartCallStateUpdate` / `StopCallStateUpdate` | HU→iPhone | Call |
| `MediaLibraryAccess` / `MediaLibraryUpdate` / `StartMediaLibraryUpdates` / `StopMediaLibraryUpdates` / `StopMediaLibraryInformation` | Both | Media Library |
| `PlayMediaLibraryItems` / `PlayMediaLibraryCollection` / `PlayMediaLibraryCurrentSelection` / `PlayMediaLibrarySpecial` | HU→iPhone | Media Library |
| `StartCommunicationUpdate` / `StopCommunicationUpdate` | HU→iPhone | Communication |
| `BluetoothComponentInformation` / `StartBluetoothConnectionUpdates` / `BluetoothConnectionUpdate` / `StopBluetoothConnectionUpdates` | Both | Bluetooth |
| `StartVehiceStateUpdate` / `StopVehiceStateUpdate` | HU→iPhone | Vehicle |
| `DeviceLanguageUpdate` / `DeviceInformationUpdate` | iPhone→HU | Device |
| `StartLocationInformation` / `StopLocationInformation` | HU→iPhone | Location |
| `StartHID` / `StopHID` | HU→iPhone | HID |
| `StartRouteGuidanceUpdate` / `RouteGuidanceManeuverUpdate` / `StopRouteGuidanceUpdate` | Both | Route Guidance |

### CiAP2CallStateEngine — Complete Fields

**Inner types**: `CallStateItems`, `CallStateItems_Usablility`
**Debug**: `CiAP2CallStateEngine_Send_CallStatus: %@`

| Field | Type | Description |
|---|---|---|
| `CallStatus` | enum | Call state (ringing, connected, disconnected, etc.) |
| `CallDirection` | enum | Incoming / Outgoing |
| `CallID` | int | Internal call identifier |
| `CallName` | string | Caller display name (from iPhone) |
| `CallNumber` | string | Phone number |
| `RemoteID` | string | Remote party identifier |
| `DisplayName` | string | Display name for UI |
| `CallUUID` | string/UUID | Unique call identifier |
| `AddressBookID` | string | Address book entry reference |
| `Service` | string | Service type (e.g., "Mobile") |
| `IsConferenced` | bool | Part of conference call |
| `ConferenceGroup` | string | Conference group identifier |
| `DisconnectReson` | enum | Reason for call disconnect (firmware typo) |

**Usability fields** (what the HU can do):

| Field | Type |
|---|---|
| `InitiateCallAvailable` | bool |
| `EndAndAcceptAvailable` | bool |
| `HoldAndAcceptAvailable` | bool |
| `SwapAvailable` | bool |
| `MergeAvailable` | bool |
| `HoldAvailable` | bool |

### CiAP2CommunicationEngine — Complete Fields

**Inner types**: `CommunicationItems`, `CommunicationItems_Usablility`

| Field | Type | Description |
|---|---|---|
| `SignalStrength` | int | Cellular signal strength |
| `RegistrationStatus` | enum | Network registration state |
| `AirplaneModeStatus` | bool | Airplane mode on/off |
| `CariierName` | string | Carrier name (firmware typo: CarrierName) |
| `CellularSupported` | bool | Device has cellular |
| `TelephonyEnabled` | bool | Phone calls available |
| `FaceTimeAudioEnabled` | bool | FaceTime Audio available |
| `FaceTimeVideoEnabled` | bool | FaceTime Video available |
| `MuteStatus` | bool | Current mute state |
| `CurrentCallCount` | int | Number of active calls |
| `NewVoicemailCount` | int | Pending voicemails |

### CiAP2MediaPlayerEngine — Complete Fields

**Inner types**: `NowPlayingMediaItems`, `NowPlayingPlaybacks`, `NowPlayingInfo` (each with `_Usablility` variant)
**Source**: `iAP2MediaPlayerEngine.cpp`
**Send functions**: `_Send_NowPlayingMeidaArtwork`, `_Send_NowPlayingLyrics`, `_Send_NowPlayingMeidaItemTitle`, `_Send_NowPlayingElapsedTime`, `_Send_NowPlayingStatus`

**HUD broadcast aliases** (sent via MiddleMan):

| Alias | Maps to |
|---|---|
| `MediaSongName` | mediaItemTitle |
| `MediaAlbumName` | mediaItemAlbumTitle |
| `MediaArtistName` | mediaItemArtist |
| `MediaLyrics` | lyrics data |
| `MediaAPPName` | playbackAppname |
| `MediaSongDuration` | mediaItemPlaybackdurationInMilliseconds |
| `MediaSongPlayTime` | playbackElapsedTimeInMilliseconds |
| `MediaPlayStatus` | playbackStatus |

**mediaItem group** (27 fields):

| Field | Description |
|---|---|
| `mediaItemPersistendIdentifier` | Unique media ID (firmware typo: Persistent) |
| `mediaItemTitle` | Song/track title |
| `mediaItemMediaType` | Audio/video/podcast etc. |
| `mediaItemRating` | User rating |
| `mediaItemPlaybackdurationInMilliseconds` | Total duration |
| `mediaItemAlbumPersistentIdentifier` | Album ID |
| `mediaItemAlbumTitle` | Album name |
| `mediaItemAlbumTrackNumber` / `TrackCount` | Track # and total |
| `mediaItemAlbumDiskNumber` / `DiskCount` | Disk # and total |
| `mediaItemArtistPersistentIdentifier` | Artist ID |
| `mediaItemArtist` | Artist name |
| `mediaItemAlbumArtistPersistentIdentifier` | Album artist ID |
| `mediaItemAlbumArtist` | Album artist name |
| `mediaItemGenre` / `GenrePersistentIdentifier` | Genre string and ID |
| `mediaItemComposer` / `ComposerPersistentIdentifier` | Composer and ID |
| `mediaItemPartofcompilation` | Is compilation flag |
| `mediaItemIsLikeSupported` / `mediaItemIsLiked` | Like support and state |
| `mediaItemIsBanSupported` / `mediaItemIsBaned` | Ban support and state |
| `mediaItemIsRisidentOnDevice` | Downloaded locally (firmware typo: Resident) |
| `mediaItemArtworkFileTransferIdentifier` | File transfer ID for artwork |
| `mediaItemChaptercount` | Number of chapters |

**playback group** (17 fields):

| Field | Description |
|---|---|
| `playbackStatus` | Playing/Paused/Stopped/etc. |
| `playbackElapsedTimeInMilliseconds` | Current position |
| `playbackQueueIndex` / `QueueCount` | Queue position and total |
| `playbackQueueChapterIndex` | Current chapter |
| `playbackShuffleMode` | Shuffle on/off/albums |
| `playbackRepeatMode` | Repeat off/one/all |
| `playbackAppname` / `AppbundleID` | Source app name and bundle ID |
| `playbackMediaLibraryUniqueIdentifier` | Library ID |
| `playbackAppleMusicRadioAd` | Is radio ad |
| `playbackAppleMusicRadioStationName` | Radio station name |
| `playbackAppleMusicRadioStationMediaPlaylistPersistendIdentifier` | Radio playlist ID |
| `playbackSpeed` | Playback speed multiplier |
| `playbackSetElapsedTimeAvailable` | Can seek |
| `playbackQueueListAvail` / `QueueListTransferID` | Queue list availability and transfer ID |

### CiAP2RouteGuidanceEngine — Complete Fields

**Inner types**: `StartGuidanceItems`, `StopGuidanceItems`, `RouteGuidanceItems`, `RouteGuidanceManeuverItems`
**JSON output**: `_SendNaviJSON` (sends to HUD as JSON string)
**ManeuverIdx tracking**: Adapter maintains current maneuver index; advances on 0x5201 updates from iPhone

**RouteGuidanceState** (top-level, from 0x5201 RouteGuidanceUpdate):

| Field | Description |
|---|---|
| `RouteGuidanceState` | Active/Inactive |
| `ManeuverState` | Current maneuver state |
| `CurrentRoadName` | Name of current road |
| `EstimatedTimeOfArrival` | ETA timestamp |
| `TimeRemainingToDestination` | Time remaining (seconds) |
| `DistanceRemaining` / `DistanceRemainingDisplayStr` / `DistanceRemainingDisplayUnits` | Distance to destination |
| `DistanceToNextManeuver` / `DistanceToNextManeuverDisplayStr` / `DistanceToNextManeuverDisplayUnits` | Distance to next turn |
| `RouteGuidanceManeuverCurrentList` / `ManeuverCount` | Maneuver list |
| `RouteGuidanceVisibleInApp` | Is guidance visible |

**RouteGuidanceManeuverItems** (per-maneuver, from 0x5202 RouteGuidanceManeuverUpdate):

| Field | Forwarded? | Description |
|---|---|---|
| `ManeuverDescription` | **Partial** — extracted as `NaviRoadName` | Text description of maneuver |
| `AfterManeuverRoadName` | **NO** — stripped | Road name after completing turn |
| `DistanceBetweenManeuver` / `DisplayStr` / `DisplayUnits` | **NO** — stripped | Distance between maneuvers |
| `DrivingSide` | **YES** — as `NaviTurnSide` | 0=RHD, 1=LHD (observed value 2 — undocumented) |
| `JunctionType` | **NO** — stripped (never appears in NaviJSON despite field existing) | Junction type |
| `JunctionElementAngle` | **NO** — triggers `iAP2UpdateEntity.cpp:314` ASSERT, dropped | Per-spoke angular position |
| `JunctionElementExitAngle` | **NO** — triggers ASSERT, dropped | Exit spoke angle |

**iAP2 Parser Limitation (Live-Verified Feb 2026):**
The `iAP2UpdateEntity.cpp` parser at line 314 asserts on iAP2 dictionary/group field types
it doesn't recognize, logging `### [ASSERT] iAP2UpdateEntity.cpp:314 "", "dict"` and silently
dropping the data. This was observed on multiple 0x5202 messages during route initialization.
The `JunctionElementAngle`/`JunctionElementExitAngle` fields are structured as iAP2 group types
and are silently discarded by this parser limitation.

**NaviJSON broadcast fields** (sent to HUD/MiddleMan):
`NaviStatus`, `NaviRoadName`, `NaviOrderType`, `NaviTurnAngle`, `NaviTurnSide`, `NaviRoundaboutExit`, `NaviManeuverType`, `NaviTimeToDestination`, `NaviDestinationName`, `NaviDistanceToDestination`, `NaviAPPName`, `NaviRemainDistance`

**NaviJSON field presence by maneuver type (Live-Verified Feb 2026, 2 routes, 13+ roundabouts):**

| Field | Roundabout exit 1 (28) | Roundabout exit 2 (29) | Right Turn (2) | Distance Update |
|---|---|---|---|---|
| `NaviRoadName` | YES (duplicated key bug) | YES (duplicated key bug) | YES (duplicated key bug) | — |
| `NaviManeuverType` | YES (=28) | YES (=29) | YES (=2) | — |
| `NaviOrderType` | YES (=16) | YES (=16) | YES (=6) | — |
| `NaviRoundaboutExit` | YES (=1) | YES (=2) | — | — |
| `NaviTurnAngle` | **=0** | **=0** | YES (=2, enum not degrees) | — |
| `NaviTurnSide` | **=0** | **=0** | YES (=2, undocumented value) | — |
| `NaviJunctionType` | **=0 / NOT SENT** | **=0 / NOT SENT** | **NOT SENT** | — |
| `NaviRemainDistance` | — | — | — | YES |

**Multi-roundabout capture (W Main St, Feb 2026):** 12 consecutive roundabouts, ALL had
turnAngle=0, turnSide=0, junction=0. Only exit 1 (CPType 28) and exit 2 (CPType 29) observed.
Both map to identical AAOS `Maneuver.TYPE=34` (ROUNDABOUT_ENTER_AND_EXIT_CCW).
iPhone sent paramCount=21 per 0x5201 message — adapter forwards ~5 fields.

**Adapter Processing Flow (Live-Verified Feb 2026):**
1. Route start → iPhone sends burst of ~20 `0x5202` messages (~200ms) with full maneuver list
2. Adapter stores `ManeuverDescription[]` array indexed by position
3. During navigation → iPhone sends `0x5201` every ~1s with distance/ETA updates
4. When iPhone advances to next maneuver → `0x5201` triggers `update ManeuverIdx: N`
5. On ManeuverIdx change → adapter emits TWO `_SendNaviJSON` calls:
   - First: `{"NaviRemainDistance": N}` (distance to new maneuver)
   - Second: `{"NaviRoadName":..., "NaviManeuverType":..., ...}` (maneuver-specific fields)

### CiAP2PowerEngine — Complete Fields

| Field | Description |
|---|---|
| `MaxCurrentDrawnFromAccessory` | Max current the accessory draws |
| `DeviceBatteryWillChargeIfPowerIsPresent` | Will charge when connected |
| `AccessoryPowerMode` | Power mode enum |
| `IsExternalChargerConnected` | External charger state |
| `BatteryChargingState` | Charging/NotCharging/Full |
| `BatteryCharegLevel` | Battery percentage (firmware typo: ChargeLevel) |
| `powerProvidingCapability` | What power the HU can provide |
| `maximumCurrentDrawnFromDevice` | Max current from iPhone |

### CiAP2VehicelStatEngine — Complete Fields

| Field | Description |
|---|---|
| `OutsideTempratrue` | Outside temperature (firmware typo: Temperature) |
| `RangeWarning` | Low fuel/battery range warning |
| `VehicelStateAsk` | State request |

### CiAP2WiFiConfigEngine — Complete Fields

| Field | Description |
|---|---|
| `WIFISSID` | WiFi network name |
| `WifiPassword` | WiFi password |
| `WiFiP2PMode` | P2P/Direct mode |
| `WiFiChannel` | WiFi channel |

### Data Relay Architecture

All iAP2 engine data is broadcast via `BroadCastCFValueIfNeedSend_ToAccessoryDaemon`, keyed by `friendlyName_`. The adapter can enable/disable capabilities dynamically: `"Enable iAP2 %s Capability"` / `"Disable iAP2 %s Capability"` with `Send_changes:%s(0x%04X)`.

**Data confirmed flowing to host app**: NaviJSON (route guidance), GNSS (location)
**Data relay status unknown**: Call state, communication, media/NowPlaying — these are parsed by the iAP2 layer but may or may not be forwarded as USB message types. Requires USB traffic sniffing to confirm.

### GPS Pipeline — Per-Binary Roles (Deep r2 Analysis Feb 2026)

The adapter's GPS forwarding involves multiple binaries in a pipeline:

| Binary | GPS Role | Key Functions/Strings |
|--------|----------|----------------------|
| **ARMadb-driver** | Receives USB type 0x29; `strstr($GPGGA)` for file write to `/tmp/RiddleBoxData/HU_GPS_DATA`; forwards ALL data as type 0x22 via link dispatch | `GNSS_DATA`, `HU_GPS_DATA`, `$GPGGA` |
| **ARMiPhoneIAP2** | `CiAP2LocationEngine`: NMEA→iAP2 conversion; registers `locationInformationComponent` (ID 0x16) in iAP2 identification | `CiAP2LocationEngine`, `GNSSCapability`, `NMEASentence` |
| **AppleCarPlay** | GNSS_DATA status logging only; location delivery is via iAP2 protocol | `GNSS_DATA` references |
| **libdmsdpgpshandler.so** | `VirtualBoxGPS::ProcessGNSSData()` — full NMEA parser, HiCar GPS path, speed thread | See VirtualBoxGPS section below |
| **libdmsdpdvgps.so** | GPS device service — **ENCRYPTED** (high-entropy code, not analyzable statically) | Exports visible but code obfuscated |
| **boxNetworkService** | No GPS references | — |
| **riddleBoxCfg** | No GPS references | — |
| **server.cgi** | No GPS references | — |

**CiAP2LocationEngine Object Layout (0x1F4+ bytes, r2 verified):**

| Offset | Type | Purpose |
|--------|------|---------|
| +0x00 | vtable* | Points to `vtable.CiAP2LocationEngine.0` at `0x744d0` |
| +0x04 | uint32 | Session/state |
| +0x08 | byte | Active flag |
| +0x0C | char* | Engine name = `"iAP2LocationEngine"` |
| +0x10 | AskStartItems | Start-request sub-object (7 data types) |
| +0x128 | AskStopItems | Stop-request sub-object (1 dummy item) |
| +0x180 | LocationInformationItems | NMEA/location data container |
| +0x188 | byte | Data-ready flag |
| +0x1B8 | string | Raw NMEA sentence buffer |
| +0x1F0 | byte | **GPGGA enabled** (0=off, nonzero=on) |
| +0x1F1 | byte | **GPRMC enabled** (0=off, nonzero=on) |
| +0x1F2 | byte | **PASCD enabled** (0=off, nonzero=on) |
| +0x1F3 | byte | **Master GNSS enable** (set to 1 on StartLocationInformation) |

**CiAP2LocationEngine Methods (ARMiPhoneIAP2 disassembly):**

| Method | Address | Size | Purpose |
|--------|---------|------|---------|
| `virtual_12` (main dispatcher) | `0x2bd14` | 840B | Dispatches 0xFFFA/0xFFFB/0xFFFC iAP2 messages |
| `virtual_8` (start/init) | `0x2bfa8` | 188B | Handles Start/Stop/LocationInfo responses; sets 0x1F3 flag |
| `virtual_16` (cleanup) | `0x2bf68` | — | Unregisters 0xFFFA/0xFFFB/0xFFFC from session |
| `fcn.0002c064` (GNSS receive) | `0x2c064` | — | Receives NMEA from HU, stores in NMEASentence, sends 0xFFFB |
| `fcn.0002c190` (GNSS config) | `0x2c190` | 244B | Sets 0x1F0-0x1F2 flags, writes GNSSCapability bitmask, writes `/tmp/gnss_info` |
| `fcn.0002c4f0` (AskStartItems) | `0x2c4f0` | 592B | Registers 7 location data sub-types |
| `fcn.0002c7c4` (AskStopItems) | `0x2c7c4` | 180B | Registers 1 dummy stop item |
| `fcn.0002c2e0` (LocationInfoItems) | `0x2c2e0` | — | Registers NMEASentence entity for iAP2 delivery |

**iAP2 Message Types (CiAP2LocationEngine):**

| Value | Name | Direction | Purpose |
|-------|------|-----------|---------|
| 0xFFFA | StartLocationInformation | Phone→Adapter | iPhone requests GPS data; sets master enable flag (0x1F3=1) |
| 0xFFFB | LocationInformation | Adapter→Phone | NMEA data wrapped in iAP2 LocationInformation |
| 0xFFFC | StopLocationInformation | Phone→Adapter | iPhone stops GPS data |

**Three-Stage GPS Gating (r2 verified Feb 2026):**

```
Stage 1: CiAP2IdentifyEngine.virtual_8 (0x23ec0, msg type 0x1D00)
  ┌─ 0x240c8: if [r4+0x11] != 0 (HUD device) → skip GPS
  ├─ 0x240d4: if HudGPSSwitch != 1 → skip GPS entity setup
  └─ 0x240e4: GNSSCapability check
       0x2458c: if GNSSCapability <= 0 → skip
       0x24598: if GNSSCapability > 0 → fcn.0001ff84 (GPS session setup)

Stage 2: fcn.00015ee4 (session init, post-identification)
  DashboardInfo bitmask checks (NOT for GPS):
  ┌─ 0x15f78: tst r7, #1 → bit 0 → vehicleInformation init (fcn.000282b8)
  ├─ 0x15f84: tst r7, #2 → bit 1 → vehicleStatus init (fcn.0002aa6c)
  └─ 0x15f90: tst r7, #4 → bit 2 → routeGuidanceDisplay init (fcn.0002ebc4)

Stage 3: GNSSCapability (separate from DashboardInfo)
  ┌─ 0x15f9c: r0 = get("GNSSCapability")
  ├─ 0x15fa4: cmp r0, 0
  ├─ 0x15fa8: if r0 <= 0 → SKIP GPS engine init entirely
  └─ 0x15fac: fcn.0002c928 → CiAP2LocationEngine_Generate
```

**⚠️ CORRECTION:** DashboardInfo does NOT gate locationInformationComponent. Previous docs incorrectly stated bit 1 = Location. Actual mapping:
- Bit 0: vehicleInformation
- Bit 1: vehicleStatus
- Bit 2: routeGuidanceDisplay

GPS/Location is gated **only** by `GNSSCapability > 0`.

**GNSSCapability Bitmask (set by fcn.0002c190):**

| Bit | Value | Sentence | Purpose |
|-----|-------|----------|---------|
| 0 | 1 | GPGGA | Global Positioning System Fix Data |
| 1 | 2 | GPRMC | Recommended Minimum GPS Transit Data |
| 3 | 8 | PASCD | Proprietary (dead-reckoning/compass) |

**AskStartItems — 7 Location Data Sub-Types:**

| ID | Offset | Name (firmware typos preserved) | Source |
|----|--------|--------------------------------|--------|
| 1 | +0x38 | GloblePositionSystemFixData | NMEA $GPGGA |
| 2 | +0x58 | RecommendedMinimumSpecificGPSTransistData | NMEA $GPRMC |
| 3 | +0x78 | GPSSataellitesInView | NMEA $GPGSV |
| 4 | +0x98 | VehicleSpeedData | CAN/sensor |
| 5 | +0xB8 | VehicleGyroData | CAN/sensor |
| 6 | +0xD8 | VehicleAccelerometerData | CAN/sensor |
| 7 | +0xF8 | VehicleHeadingData | CAN/sensor |

**GNSS Data Receive Flow (fcn.0002c064 pseudocode):**

```c
void process_gnss_data(this, data, len) {
    if (this->flag_0x1f3 == 0) return;     // master enable must be set
    if (data == NULL || len == 0) return;
    if (len >= 0x400) { log("GNSSSentences too long"); return; }

    store_string(&this->nmeaBuffer, data);  // at this+0x1B8
    this->dataReady = 1;                    // [this+0x188]
    if (this->callback) callback->method_8(sub);
    dispatch_response(this, sub, 0xFFFB);   // send LocationInformation to iPhone
}
```

**iAP2 Identification Component Table (fcn.00023590, all registered unconditionally):**

| ID | Component Name |
|----|---------------|
| 0x00-0x09 | name, modelIdentifier, manufacturer, serialNumber, firmwareVersion, hardwareVersion, messagesSent/Received, powerCapability, maxCurrentDraw |
| 0x0A | supportedExternalAccessoryProtocol |
| 0x0B-0x0D | appMatchTeamID, currentLanguage, supportedLanguage |
| 0x0E-0x11 | serialTransport, USBDeviceTransport, USBHostTransport, bluetoothTransport |
| 0x12 | iAP2HIDComponent |
| 0x14 | vehicleInformationComponent |
| **0x15** | **vehicleStatusComponent** |
| **0x16** | **locationInformationComponent** |
| 0x17 | USBHostHIDComponent |
| 0x18 | wirelessCarPlayTransportComponent |
| 0x1D | bluettoothHIDComponent (firmware typo) |
| **0x1E** | **routeGuidanceDisplayComponent** |

**VirtualBoxGPS (libdmsdpgpshandler.so, 10KB, r2 fully analyzed):**

C++ class implementing the HiCar/DMSDP GPS handler:

```cpp
class VirtualBoxGPS {
    void ProcessGNSSData(uint8_t* data, unsigned int len);  // Full NMEA parser
    static void SendSpeedFunc(void* arg);                    // Speed reporting thread
};

// Exported C functions:
VirtualGPSSetReportFreq(int freq1, int freq2, int freq3);   // 3 independent frequencies
VirtualGPSGetReportFreq(int* f1, int* f2, int* f3);
VirtualGPSRegisterCallback(DMSDPGPSCallback*);
VirtualGPSUnRegisterCallback();
VirtualGPSBusinessControl(uint, char*, uint, char*, uint);
```

**ProcessGNSSData parsing:**
- Validates `$GPGGA`, `$GPRMC`, `$PASCD` prefixes via `memcmp`
- GPGGA: extracts time, lat, lon, fix quality, satellites, HDOP, altitude via `sscanf`/`strtol`/`strtod`
- GPRMC: extracts time, status, lat, lon, speed (knots→km/h), course, date
- Generates custom `$GPVAI,%s,%d,,,,,,%06.1f,%s` (speed + time) sentence
- Generates `$RMTINFO,%s,%s,%s,` (car brand from `/etc/airplay.conf` + device ID from `/tmp/car_deviceID`)
- Appends `*XX` NMEA checksum
- Sends via `HiCarSendGNSSData()` and `sendTransferData()` (libboxtrans.so)
- Tracks driving mode via `HiCarSendDrivingMode()` with stop-time counter (`iStopTimes` static)

**libdmsdpdvgps.so (16KB, ENCRYPTED):**

This library exports GPS service functions (`GpsReceiveLocationData`, `GpsSendServiceData`, etc.) visible in the symbol table, but the `.text` section is **high-entropy encrypted/obfuscated** — no valid ARM/Thumb instructions in function bodies. The entry point and function prologues are invalid opcodes. Analysis requires runtime memory dump from the adapter.

**Critical Configuration:** `GNSSCapability` defaults to `0`, which **disables** the entire GPS pipeline at two points. Must be set to `≥ 1` via `riddleBoxCfg -s GNSSCapability 3` for GPS forwarding to work.

---

## Audio Processing (libdmsdpaudiohandler.so)

```cpp
class AudioConvertor {
    void SetFormat(AudioPCMFormat src, AudioPCMFormat dst);
    void PushSrcAudio(unsigned char* data, unsigned int size);
    void PopDstAudio(unsigned char* data, unsigned int size);
    float GetConvertRatio();
};

// Format queries
GetAudioPCMFormat(int format_id);
getSpeakerFormat();
getMicFormat();
getModemSpeakerFormat();
getModemMicFormat();

// Stream handling
handleAudioType(AUDIO_TYPE_HICAR_SDK& type, DMSDPAudioStreamType stream_type);
getAudioTypeByDataAndStream(const char* data, DMSDPVirtualStreamData* stream_data);

// Service management
AudioService::requestAudioFocus(int type, int flags);
AudioService::abandonAudioFocus();
AudioService::GetAudioCapability(DMSDPAudioCapabilities** caps, unsigned int* count);
```

---

## Internal Command Strings

```
CMD_CARPLAY_MODE_CHANGE
CMD_SET_BLUETOOTH_PIN_CODE
CMD_BOX_WIFI_NAME
CMD_MANUAL_DISCONNECT_PHONE
CMD_CARPLAY_AirPlayModeChanges
CMD_BLUETOOTH_ONLINE_LIST
CMD_CAR_MANUFACTURER_INFO
CMD_STOP_PHONE_CONNECTION
CMD_CAMERA_FRAME
CMD_MULTI_TOUCH
CMD_CONNECTION_URL
CMD_BOX_INFO
CMD_PAY_RESULT
CMD_ACK
CMD_DEBUG_TEST
CMD_UPDATE
CMD_APP_SET_BOX_CONFIG
CMD_ENABLE_CRYPT
CMD_APP_INFO
```

---

## D-Bus Interfaces (Deep r2 Analysis Feb 2026)

### org.riddle Bus

| Component | Value |
|---|---|
| Bus name | `org.riddle` |
| BT Service path | `/RiddleBluetoothService` |
| BT Control interface | `org.riddle.BluetoothControl` |
| BT Daemon path | `/BluetoothDaemonControler` |
| Signal match | `type='signal',interface='%s',sender='org.riddle',path='%s'` |

### HUD Commands

```cpp
HUDComand_A_HeartBeat
HUDComand_A_ResetUSB
HUDComand_A_UploadFile
HUDComand_B_BoxSoftwareVersion
HUDComand_D_BluetoothName
HUDComand_D_Ready
kRiddleHUDComand_A_Reboot
kRiddleHUDComand_CommissionSetting
kRiddleHUDComand_D_Bluetooth_BondList
```

### D-Bus Signals (Complete)

```cpp
// Audio
kRiddleAudioSignal_MEDIA_START
kRiddleAudioSignal_MEDIA_STOP
kRiddleAudioSignal_ALERT_START
kRiddleAudioSignal_ALERT_STOP
kRiddleAudioSignal_PHONECALL_Incoming
AudioSignal_OUTPUT_START / AudioSignal_OUTPUT_STOP
AudioSignal_INPUT_CONFIG
AudioSignal_PHONECALL_START / AudioSignal_PHONECALL_STOP
AudioSignal_NAVI_START / AudioSignal_NAVI_STOP
AudioSignal_SIRI_START / AudioSignal_SIRI_STOP

// Bluetooth
Bluetooth_ConnectStart / Bluetooth_DisConnect / Bluetooth_Listen
Bluetooth_Search / Bluetooth_Found / Bluetooth_SearchStart / Bluetooth_SearchEnd
Bluetooth_Connected
BTAudioDevice_Signal
AudioDeviceSignal
SDPToolSearchEnd / InquerySearchEnd

// BLE / HiCar
BLERiddleFragrancesNotifyJsonType    // BLE fragrance diffuser notifications
BLE_RiddleFragrancesCommand_JsonType // BLE fragrance commands
EnableHiCarBLEAdvertising / DisableHiCarBLEAdvertising
CancelAutoConnect
```

### Unix Socket IPC

| Socket | Purpose |
|---|---|
| `/var/run/adb-driver` | Main IPC socket (CMiddleManServer) |
| `/var/run/phonemirror` | Phone mirror IPC |

---

## Supported Link Types (13 protocols)

| Session Class | Link Type | Transport |
|---|---|---|
| `Accessory_ActionSession_Link_iPhone_CarPlay_Wire` | CarPlay | Wired USB |
| `Accessory_ActionSession_Link_iPhone_CarPlay_WireLess` | CarPlay | Wireless |
| `Accessory_ActionSession_Link_AndroidAuto_Wire` | Android Auto | Wired USB |
| `Accessory_ActionSession_Link_AndroidAuto_WireLess` | Android Auto | Wireless |
| `Accessory_ActionSession_Link_Hicar_Wire` | HiCar | Wired USB |
| `Accessory_ActionSession_Link_Hicar_WireLess` | HiCar | Wireless |
| `Accessory_ActionSession_Link_AndroidCarLife_Wire` | CarLife | Wired USB |
| `Accessory_ActionSession_Link_AndroidCarLife_Wireless` | CarLife | Wireless |
| `Accessory_ActionSession_Link_ICCOA_Wire` | ICCOA | Wired USB |
| `Accessory_ActionSession_Link_ICCOA_WireLess` | ICCOA | Wireless |
| `Accessory_ActionSession_Link_iPhone_Mirror_Wire` | iOS Mirror | Wired USB |
| `Accessory_ActionSession_Link_AnroidAdbMirror_Wire` | Android ADB Mirror | Wired USB |
| `Accessory_ActionSession_WholeLife` | Whole lifecycle | N/A |

**iPhone Work Modes**: `AirPlay`, `OnlyCharge`, `iOSMirror`, `iPhoneWorkMode_UNKOWN?`
**Android Work Modes**: `AndroidMirror`, `ICCOA`, `AndroidWorkMode_UNKOWN?`

---

## Firmware Version Format

```
YYYY.MM.DD.HHMMVer
Example: 2025.02.25.1521CAY

YYYY = Year
MM = Month
DD = Day
HHMM = Build time
Ver = Version code character
```

---

## Analysis Tools Used

| Tool | Purpose |
|------|---------|
| Ghidra 12.0 | Decompilation |
| radare2 | Disassembly |
| strings | String extraction |
| objdump | Binary analysis |
| ludwig-v modified UPX | Unpacking |

---

## Firmware Scripts (from extracted firmware)

| Script | Purpose |
|--------|---------|
| `/script/start_main_service.sh` | Main startup sequence |
| `/script/init_bluetooth_wifi.sh` | BT/WiFi initialization |
| `/script/init_audio_codec.sh` | Audio codec setup |
| `/script/init_gpio.sh` | GPIO initialization |
| `/script/start_iap2_ncm.sh` | iAP2 and NCM driver startup |
| `/script/start_ncm.sh` | NCM network startup |
| `/script/phone_link_deamon.sh` | Phone link process management |
| `/script/update_box.sh` | Firmware update handler |
| `/script/check_mfg_mode.sh` | Manufacturing test mode |
| `/script/custom_init.sh` | User-defined init hook (optional) |

---

## References

- Source: `GM_research/cpc200_research/docs/analysis/`
- Source: `pi-carplay-4.1.3/firmware_binaries/`
- Source: `cpc200_ccpa_firmware_binaries/analysis/`
- Source: `cpc200_ccpa_firmware_binaries/A15W_extracted/`
- External: ludwig-v/wireless-carplay-dongle-reverse-engineering
