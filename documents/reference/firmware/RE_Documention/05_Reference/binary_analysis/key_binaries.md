# CPC200-CCPA Key Binaries Reference

**Purpose:** Reference for firmware binary analysis
**Consolidated from:** GM_research, pi-carplay firmware extraction
**Last Updated:** 2026-02-18 (deep r2 analysis: LocationEngine object layout, GNSS gating, VirtualBoxGPS NMEA parser, DashboardInfo correction, dvgps encryption)

---

## Executable Binaries

| Binary | Packed Size | Unpacked Size | Purpose |
|--------|-------------|---------------|---------|
| AppleCarPlay | 325KB | 573KB | Main CarPlay receiver |
| ARMiPhoneIAP2 | 182KB | 494KB | iPhone IAP2 protocol handler |
| ARMadb-driver | 217KB | 479KB | Main USB protocol handler |
| ARMAndroidAuto | - | 489KB | Android Auto protocol handler |
| ARMHiCar | - | 132KB | Huawei HiCar support |
| ARMandroid_Mirror | - | 106KB | Android mirroring |
| bluetoothDaemon | 173KB | 409KB | Bluetooth management |
| mdnsd | - | 378KB | mDNS/Bonjour service |
| boxNetworkService | 40KB | - | Network management (not UPX packed) |
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
| ARMiPhoneIAP2 | ✅ | ✅ | Partial |
| AppleCarPlay | ✅ | ✅ | Partial |
| bluetoothDaemon | ✅ | ✅ | Not yet |
| riddleBoxCfg | ✅ | ✅ | Partial |
| ARMimg_maker | ✅ | ✅ | ✅ Key extracted |
| ARMAndroidAuto | ❌ UPX error | - | Blocked |

**Note:** `ARMAndroidAuto` fails to unpack with "compressed data violation" error even with modified UPX.

### ARMAndroidAuto Runtime Analysis (TTY Logs - Jan 2026)

Although the binary cannot be unpacked, TTY logs reveal its runtime behavior:

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

**Video Output:**
- SPS/PPS parsing: `spsWidth: 1920, spsHeight: 1088` (8-line alignment)
- H.264 I-frame detection for keyframe requests
- Configurable bitrate: `maxVideoBitRate = 5000 Kbps`

---

## Key Libraries

### DMSDP Framework

| Library | Size | Purpose |
|---------|------|---------|
| libdmsdp.so | 184KB | Core DMSDP protocol |
| libdmsdpcrypto.so | 80KB | Crypto (X25519, AES-GCM) |
| libdmsdpaudiohandler.so | 48KB | Audio dispatch |
| libdmsdpdvaudio.so | 48KB | Digital audio streaming |
| libdmsdpdvdevice.so | - | Device protocol constants |
| libdmsdpplatform.so | 242KB | FILLP, crypto, socket mgmt |

### Third-Party Libraries

| Library | Purpose |
|---------|---------|
| libfdk-aac.so.1.0.0 | AAC decoder (336KB) |
| libtinyalsa.so | Hardware abstraction (17KB) |
| libcrypto.so.1.1 | OpenSSL crypto |
| libssl.so.1.1 | OpenSSL SSL/TLS |

### Huawei HiCar

| Library | Size | Purpose |
|---------|------|---------|
| libHwKeystoreSDK.so | 168KB | Key Store API |
| libHisightSink.so | 147KB | Video sink |
| libhicar.so | - | HiCar protocol |

### Other

| Library | Purpose |
|---------|---------|
| libauthagent.so | Authentication/trust (44KB) |
| libnearby.so | Google Nearby protocol (91KB) |

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

## Key Functions (ARMadb-driver)

| Function | Address | Purpose |
|----------|---------|---------|
| FUN_00018244 | 0x18244 | Message encryption/validation |
| FUN_00018e2c | 0x18e2c | Main message dispatcher / _SendDataToCar |
| FUN_00066190 | 0x66190 | riddle.conf config writer |
| FUN_00062e1c | 0x62e1c | Message buffer init |
| FUN_00062f34 | 0x62f34 | Message buffer populate |
| FUN_00017340 | 0x17340 | Message sender |
| FUN_00018088 | 0x18088 | Message pre-processor |
| FUN_00065178 | 0x65178 | JSON field extractor |

### Video-Related Strings (ARMadb-driver)

| String | Address | Purpose |
|--------|---------|---------|
| `recv CarPlay videoTimestamp:%llu` | 0x6d139 | Video timestamp logging |
| `recv AA videoTimestamp:%llu` | 0x6d043 | Android Auto video timestamp |
| `recv HiCar videoTimestamp:%llu` | 0x6cdbe | HiCar video timestamp |
| `_SendDataToCar iSize: %d, may need send ZLP` | 0x6b823 | USB transmission |
| `CarPlay recv data size error!` | 0x6d0fc | Video reception error |
| `box video frame rate: %d, %.2f KB/s` | 0x6c18b | Video statistics |
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
| `/tmp/screen_fps` | - | Runtime FPS config file |
| `/tmp/screen_size` | - | Runtime resolution config file |
| `--width %d --height %d --fps %d` | - | AppleCarPlay launch parameters |

---

## Key Functions (AppleCarPlay)

| Function/String | Address | Purpose |
|-----------------|---------|---------|
| `AirPlayReceiverSessionScreen_ProcessFrames` | 0x7ecbf | Receives H.264 stream |
| `_AirPlayReceiverSessionScreen_ProcessFrame` | 0x7ecea | Process single frame |
| `ScreenStreamProcessData` | 0x8ff62 | Raw stream handling |
| `### Send screen h264 frame data failed!` | 0x9016d | H.264 send error |
| `### Send h264 I frame data %d byte!` | 0x90196 | I-frame transmission |
| `### H264 data buffer overrun!` | 0x90119 | Buffer overflow |
| `### h264 frame data parse error!` | 0x900f9 | NAL parsing error |
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

**Exploitation:** Shell metacharacters (`"`, `;`, `$()`) in wifiName/btName break out of the sed command and execute arbitrary commands as root. Example:
```
wifiName = "a\"; /usr/sbin/riddleBoxCfg -s AdvancedFeatures 1; echo \""
```
This executes `riddleBoxCfg` immediately. See `03_Security_Analysis/vulnerabilities.md` for full details.

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

## iAP2 Engines (ARMiPhoneIAP2)

```cpp
iAP2CallStateEngine
iAP2CommunicationEngine
iAP2LocationEngine
iAP2MediaPlayerEngine
iAP2RouteGuidanceEngine
iAP2WiFiConfigEngine
```

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

## D-Bus Interfaces

### org.riddle

```cpp
HUDComand_A_HeartBeat
HUDComand_A_ResetUSB
HUDComand_A_UploadFile
HUDComand_B_BoxSoftwareVersion
HUDComand_D_BluetoothName
kRiddleHUDComand_A_Reboot
kRiddleHUDComand_CommissionSetting
```

### Audio Signals

```cpp
kRiddleAudioSignal_MEDIA_START
kRiddleAudioSignal_MEDIA_STOP
kRiddleAudioSignal_ALERT_START
kRiddleAudioSignal_ALERT_STOP
kRiddleAudioSignal_PHONECALL_Incoming
```

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
