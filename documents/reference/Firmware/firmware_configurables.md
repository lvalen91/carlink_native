# CPC200-CCPA Firmware Configuration

**Device**: CPC200-CCPA Wireless CarPlay/Android Auto Adapter (Model A15W)  
**Total Parameters**: 87 across video, audio, network, USB, system, LED categories  
**Update Methods**: USB Protocol, Web API (/server.cgi), CLI (/usr/sbin/riddleBoxCfg)  
**Storage**: /etc/riddle.conf (primary), /etc/riddle_default.conf (backup)

## Update Methods

| Method | Endpoint/Binary | Format | Latency | Restart Required |
|--------|----------------|--------|---------|------------------|
| USB | CPC200-CCPA protocol | Binary packets | <100ms | Video/USB changes only |
| Web API | /server.cgi | POST FormData+MD5 | Real-time | Resolution/USB identity |
| CLI | /usr/sbin/riddleBoxCfg | -s param value | Immediate | System settings |

## Video Parameters (15)

| Parameter | Type | Range/Values | Default | A15W | Restart | Description |
|-----------|------|-------------|---------|------|---------|-------------|
| resolutionWidth | int | 0-4096 | 2400 | ✓ | ✓ | Display width |
| resolutionHeight | int | 0-4096 | 960 | ✓ | ✓ | Display height |
| resolutionPreset | enum | 0-7 | 7 | ✓ | ✓ | 0=Auto,1=800x480,2=1024x600,3=1280x720,4=1920x1080,5=1280x480,6=1920x720,7=Custom |
| fps | int | 0,15-60 | 0 | ✓ | ✗ | Video frame rate |
| bitRate | int | 0-20 | 0 | ✓ | ✗ | Video bit rate (0=auto) |
| displaySize | enum | 0-3 | 3 | ✓ | ✗ | Icon size: S/M/L/Auto |
| ScreenDPI | int | 0-480 | 0 | ✓ | ✗ | Screen dots per inch |
| RepeatKeyFrame | bool | 0-1 | 0 | ✗ | ✗ | H.264 key frame repeat |
| SpsPpsMode | bool | 0-1 | 0 | ✗ | ✗ | H.264 SPS/PPS mode |
| emptyFrame | bool | 0-1 | 1 | ✗ | ✗ | Empty frame handling |
| originalRes | bool | 0-1 | 0 | ✗ | ✗ | Bypass resolution scaling |
| MediaLatency | int | 0-1000 | 300 | ✓ | ✗ | Video buffering delay (ms) |
| HardwareAcceleration | bool | 0-1 | 1 | ✗ | ✗ | GPU acceleration enable |
| ContentProtection | bool | 0-1 | 1 | ✗ | ✗ | DRM/HDCP protection |
| BufferSize | int | 1-10 | 3 | ✗ | ✗ | Video buffer frames |

## Audio Parameters (19)

| Parameter | Type | Range/Values | Default | A15W | Unit | Description |
|-----------|------|-------------|---------|------|------|-------------|
| mediaDelay | int | 300-2000 | 300 | ✓ | ms | Media audio delay compensation |
| echoDelay | int | 80-600 | 320 | ✓ | ms | Echo cancellation delay |
| micGain | int | 0-60 | 0 | ✗ | - | External microphone gain |
| CallQuality | enum | 0-2 | 1 | ✓ | - | 0=Norm,1=Clear,2=HD |
| BtAudio | bool | 0-1 | 1 | ✓ | - | Bluetooth audio channel |
| NaviAudio | enum | 0-2 | 0 | ✓ | - | 0=Nav,1=Media,2=Voice |
| naviVolume | int | 0-100 | 0 | ✓ | - | Navigation call volume |
| MediaPacketLen | int | 100-1000 | 200 | ✓ | bytes | Media packet size |
| TtsPacketLen | int | 100-1000 | 200 | ✓ | bytes | TTS packet size |
| VrPacketLen | int | 100-1000 | 200 | ✓ | bytes | Voice recognition packet size |
| TtsVolumGain | int | -20 to +20 | 0 | ✓ | dB | TTS volume adjustment |
| VrVolumGain | int | -20 to +20 | 0 | ✓ | dB | Voice recognition volume |
| MicMode | enum | 0-3 | 0 | ✗ | - | 0=Standard,1=Enhanced,2=NoiseReduction,3=HighGain |
| micType | enum | 0-1 | 0 | ✗ | - | 0=External,1=Internal |
| backRecording | bool | 0-1 | 0 | ✓ | - | Background app recording |
| mediaCategory | enum | 0-1 | 0 | ✓ | - | 0=Default,1=Enhanced |
| mediaSound | bool | 0-1 | 0 | ✓ | - | Advanced media processing |
| btCall | bool | 0-1 | 0 | ✗ | - | Bluetooth call channel |
| autoPlay | bool | 0-1 | 0 | ✓ | - | Auto-play after HiCar connect |

## Network Parameters (8)

| Parameter | Type | Range/Values | Default | Description |
|-----------|------|-------------|---------|-------------|
| wifi5GSwitch | bool | 0-1 | 1 | 0=2.4GHz, 1=5GHz |
| wifiChannel | enum | 2.4G:[1-7], 5G:[36,40,44,149,157,161] | 36 | Wi-Fi channel selection |
| CustomWifiName | string | max 31 chars | AutoBox-76d4 | Wi-Fi SSID |
| WifiPassword | string | max 63 chars | - | Wi-Fi password |
| BoxIp | ipv4 | 192.168.x.x | 192.168.43.1 | Device IP address |
| autoConn | bool | 0-1 | 1 | Auto-connect to phone |
| autoDisplay | enum | 0-2 | 1 | 0=Auto,1=Standard,2=Force |
| fastConnect | bool | 0-1 | 0 | High speed connection mode |

## USB Identity Parameters (6)

| Parameter | Type | Range/Values | Default | Restart | Description |
|-----------|------|-------------|---------|---------|-------------|
| USBManufacturer | string | max 31 chars | Magic Communication Tec. | ✓ | USB manufacturer string |
| USBProduct | string | max 31 chars | Auto Box | ✓ | USB product name |
| USBVID | hex | 4-digit hex | 08e4 | ✓ | USB Vendor ID |
| USBPID | hex | 4-digit hex | 01c0 | ✓ | USB Product ID |
| USBSerial | string | UUID/custom | UUID | ✓ | USB serial number |
| USBTransMode | bool | 0-1 | 1 | ✓ | Zero Length Packet mode |

### Protocol-Specific USB Identity

| Protocol | VID | PID | Manufacturer | Product | Functions |
|----------|-----|-----|-------------|---------|-----------|
| CarPlay | 08e4 | 01c0 | Magic Communication Tec. | Auto Box | iap2,ncm |
| Android Auto | 18d1 | 2d00 | Google Inc. | Android Auto Device | accessory |
| Generic | 1314 | 1521 | configurable | configurable | accessory |

## System Parameters (12)

| Parameter | Type | Range/Values | Default | A15W | Description |
|-----------|------|-------------|---------|------|-------------|
| mouseMode | bool | 0-1 | 0 | ✓ | Show cursor for touchpad |
| KnobMode | bool | 0-1 | 0 | ✗ | Knob input support |
| autoRefresh | bool | 0-1 | 0 | ✓ | Auto-refresh interface |
| syncMode | bool | 0-1 | 0 | ✓ | Old car sync compatibility |
| bgMode | bool | 0-1 | 0 | ✓ | Background mode |
| lang | enum | 0-6 | 0 | ✓ | 0=Auto,1=EN,2=ZH,3=TW,4=HU,5=KO,6=RU |
| gps | bool | 0-1 | 1 | ✓ | GPS sync to phone |
| autoUpdate | bool | 0-1 | 1 | ✗ | Auto firmware updates |
| UdiskMode | bool | 0-1 | 0 | ✓ | U-disk mode |
| returnMode | enum | 0-1 | 0 | ✓ | 0=Original,1=Box |
| transferMode | enum | 0-1 | 0 | ✓ | 0=Compatible,1=Beta |
| CustomBoxName | string | max 31 chars | AutoBox | ✓ | Device display name |

## Advanced Parameters (18)

| Parameter | Type | Range/Values | Default | A15W | Description |
|-----------|------|-------------|---------|------|-------------|
| heartBeat | bool | 0-1 | 1 | ✓ | Connection heartbeat |
| improvedFluency | bool | 0-1 | 0 | ✓ | Streaming fluency optimization |
| startDelay | int | 0-5000 | 0 | ✓ | Power-on communication delay (ms) |
| USBConnectedMode | enum | 0-2 | 0 | ✓ | 0=MTP+ADB,1=MTP_only,2=ADB_only |
| CustomBluetoothName | string | max 31 chars | AutoBox | ✓ | Bluetooth device name |
| logos | string | file path | /etc/oem_icon.png | ✓ | Car logo selection |
| UDiskPassThrough | bool | 0-1 | 0 | ✓ | U-disk pass-through mode |
| connectedMode | enum | 0-2 | 0 | ✓ | Connection mode selection |
| audioCodec | enum | 0-1 | 0 | ✗ | 0=WM8960,1=AC6966 |
| touchMode | bool | 0-1 | 1 | ✓ | Touch input processing |
| keyboardMode | bool | 0-1 | 0 | ✓ | Virtual keyboard support |
| rotationMode | enum | 0-3 | 0 | ✓ | 0=0°,1=90°,2=180°,3=270° |
| brightnessControl | bool | 0-1 | 0 | ✓ | Brightness adjustment |
| aspectRatio | enum | 0-2 | 0 | ✓ | 0=Auto,1=4:3,2=16:9 |
| carLinkType | int | 0-50 | 30 | ✓ | Protocol identifier |
| CarDrivePosition | enum | 0-1 | 0 | ✓ | 0=Left,1=Right drive position |
| AndroidWorkMode | enum | 0-1 | 1 | ✓ | Android Auto work mode |
| transMode | enum | 0-1 | 0 | ✓ | Transmission mode selection |

## LED Configuration Parameters (8)

| Parameter | Type | Range/Values | Default | Description |
|-----------|------|-------------|---------|-------------|
| rgbStartUp | hex | 24-bit color | 0x800000 | Startup LED color |
| rgbWifiConnected | hex | 24-bit color | 0x008000 | WiFi connected color |
| rgbBtConnecting | hex | 24-bit color | 0x800000 | Bluetooth connecting color |
| rgbLinkSuccess | hex | 24-bit color | 0x008000 | Link success color |
| rgbUpgrading | hex | 24-bit color | 0x800000/0x008000 | Firmware upgrade color (alternating) |
| rgbUpdateSuccess | hex | 24-bit color | 0x800080 | Update success color |
| rgbFault | hex | 24-bit color | 0x000080 | Fault/error color |
| ledTimingMode | enum | 0-2 | 1 | 0=Static,1=Blink,2=Gradient |

## riddle.conf and Host App Initialization

### Configuration File Format

The primary configuration storage is `/etc/riddle.conf`, a JSON file that persists all firmware parameters. The backup file `/etc/riddle_default.conf` stores factory defaults.

**File Location**: `/etc/riddle.conf`
**Format**: JSON
**Backup**: `/etc/riddle_default.conf`

### Host App BoxSettings Influence

**IMPORTANT**: Many riddle.conf parameters are dynamically set by the host application during initialization. The CPC200-CCPA firmware accepts configuration commands from the connected head unit's host app (e.g., carlink_native), which then writes to riddle.conf.

**Initialization Flow**:
```
1. Host App starts → Sends BoxSettings configuration via USB protocol
2. Firmware receives configuration → Updates riddle.conf
3. Parameters persist across adapter reboots until next host configuration
```

### Host-Configurable Parameters

These parameters are typically set by the host app during connection initialization:

| Parameter | Type | Description | BoxSettings Mapping |
|-----------|------|-------------|---------------------|
| MediaLatency | int | Video buffering delay (ms) | `mediaDelay` |
| AndroidAutoWidth | int | Android Auto display width | `width` |
| AndroidAutoHeight | int | Android Auto display height | `height` |
| MediaQuality | int | Video quality level | `fps` / `bitRate` |
| WiFiChannel | int | 5GHz channel (36,40,44,149,157,161) | `wifiChannel` |
| BtAudio | bool | Bluetooth audio enable | `btMusicChannel` |
| MicType | int | Microphone type (0=External,1=Internal) | `micType` |
| CarDrivePosition | int | Drive position (0=Left,1=Right) | `drivePosition` |

### Example riddle.conf Content

```json
{
  "resolutionWidth": 2400,
  "resolutionHeight": 960,
  "MediaLatency": 300,
  "AndroidAutoWidth": 1000,
  "AndroidAutoHeight": 500,
  "MediaQuality": 0,
  "WiFiChannel": 36,
  "BtAudio": 0,
  "MicType": 0,
  "CarDrivePosition": 0,
  "CustomWifiName": "AutoBox-76d4",
  "BoxIp": "192.168.43.1",
  "USBVID": "1314",
  "USBPID": "1521"
}
```

### Configuration Precedence

| Priority | Source | Persistence | Notes |
|----------|--------|-------------|-------|
| 1 (Highest) | Host App BoxSettings | Until next init | Sent via USB protocol at connection |
| 2 | riddle.conf | Persistent | Written by riddleBoxCfg or Web API |
| 3 | Web API (/server.cgi) | Persistent | Manual configuration changes |
| 4 (Lowest) | riddle_default.conf | Factory | Restored on factory reset |

**Note**: When the host app sends BoxSettings during initialization, it can override values in riddle.conf. This is why the same adapter may behave differently with different host applications - each app sends its own configuration preferences.

---

## AndroidWorkMode Deep Dive

### Critical Discovery (Dec 2025)

`AndroidWorkMode` controls whether the adapter starts the Android Auto daemon. **This is a dynamic toggle, not a persistent setting.**

### Behavior

| Event | AndroidWorkMode Value | Effect |
|-------|----------------------|--------|
| Host sends `android_work_mode=1` | `0 → 1` | `Start Link Deamon: AndroidAuto` |
| Phone disconnects | `1 → 0` (firmware auto-reset) | Android Auto daemon stops |
| Host reconnects | Must re-send `android_work_mode=1` | Daemon restarts |

### How to Set via Host App

**File Path**: `/etc/android_work_mode`
**Protocol**: `SendFile` (type 0x99) with 4-byte payload

```typescript
// Pi-Carplay example (DongleDriver.ts)
new SendBoolean(true, FileAddress.ANDROID_WORK_MODE)
// FileAddress.ANDROID_WORK_MODE = '/etc/android_work_mode'
```

**Firmware Log Evidence**:
```
UPLOAD FILE: /etc/android_work_mode, 4 byte
OnAndroidWorkModeChanged: 0 → 1
Start Link Deamon: AndroidAuto
```

### Impact on Android Auto Pairing

| AndroidWorkMode | Android Auto Daemon | Fresh Pairing |
|-----------------|---------------------|---------------|
| 0 (default) | NOT started | ❌ Fails |
| 1 | Started | ✅ Works |

**Key Finding**: Open-source projects (Pi-Carplay, carlink_native) that don't send `android_work_mode=1` during initialization cannot perform Android Auto pairing, even if Bluetooth pairing succeeds.

### riddle.conf vs Runtime State

- `AndroidWorkMode` in riddle.conf: May show `1` if previously set by Autokit
- **Runtime state**: Always resets to `0` on phone disconnect
- Host app must send on **every connection**, not just first time

---

## Configuration Commands

### CLI Examples
```bash
# Network
riddleBoxCfg -s CustomWifiName "MyCarBox"
riddleBoxCfg -s WifiPassword "mypassword123"
riddleBoxCfg -s BoxIp "192.168.50.1"

# USB Identity  
riddleBoxCfg -s USBVID "1314"
riddleBoxCfg -s USBPID "1521"
riddleBoxCfg -s USBManufacturer "Custom Manufacturer"

# System
riddleBoxCfg -s CustomBoxName "MyCar"
riddleBoxCfg --upConfig
```

### Web API Format
```
POST /server.cgi
FormData: cmd=set&item=parameter&val=value&ts=timestamp&sign=md5_hash
Salt: HweL*@M@JEYUnvPw9G36MVB9X6u@2qxK
```

## Model Detection & Parameter Filtering

**Product Identification**:
- **Product Type**: Read from `/etc/box_product_type` (A15W, A15H, etc.)
- **Hardware Version**: `YMA0-WR2C-0003` (A15W variant)
- **Custom Version**: Read from `/etc/box_version` (affects UI modules)
- **WiFi Chip Type**: Dynamically detected (Type 6 for A15W)

**Module Loading System** (`getFuncModule.sh`):
```
A15W: CarPlay,AndroidAuto,AndroidMirror,iOSMirror,HiCar
A15H: CarPlay,HiCar (limited subset)
```

## Model-Specific Parameter Visibility

**IMPORTANT**: Parameters are NOT "hidden" by firmware - they are **contextually filtered** based on hardware capabilities.

**Backend Reality**: All 87 parameters are accessible via API/CLI regardless of model
**Frontend Filtering**: Web interface conditionally displays parameters based on:
- Hardware capabilities (detected modules)
- Product type configuration  
- Physical component availability

**A15W Contextual Limitations**:

| Parameter | Status | Reason |
|-----------|--------|--------|
| micGain, MicMode, micType | Limited | Hardware-dependent microphone |
| KnobMode | Disabled | No physical knob hardware |
| btCall | Limited | Bluetooth calling hardware constraints |
| autoUpdate | Configurable | May be disabled per custom version |
| RepeatKeyFrame, SpsPpsMode | Available | H.264 encoding parameters |
| emptyFrame, originalRes | Available | Video processing options |
| HardwareAcceleration | Available | GPU acceleration support |
| ContentProtection | Available | DRM/HDCP support |
| audioCodec | Hardware-detected | WM8960(0) or AC6966(1) via I2C |

**Access Methods**:
- **CLI**: All parameters configurable via `riddleBoxCfg -s parameter value`
- **Web API**: All parameters returned in server.cgi responses
- **Web UI**: Contextually filtered display based on hardware detection

## Validation Rules

| Type | Rule | Error Message |
|------|------|---------------|
| Range 0-60 | micGain | "Please Enter A Number From 0-60" |
| Range 300-2000 | mediaDelay | "Please Enter A Number From 300-2000" |
| Range 0-4096 | resolution | "Please Enter A Number From 0-4096" |
| Range 0-20 | bitRate | "Please Enter A Number From 0-20" |
| Range 0-480 | ScreenDPI | "Please Enter A Number From 0-480" |
| Text | alphanumeric | "No Special Symbols Are Allowed" |
| Text | no emoji | "Emoticons Cannot Be Used" |

## Configuration Summary

- **Total Parameters**: 87 (all accessible via API/CLI, subset visible in web UI based on hardware)
- **Parameter Access**: Backend exposes all parameters; frontend filters by hardware capabilities
- **Model Detection**: Dynamic hardware detection determines feature availability
- **Real-time Updates**: Audio levels, display preferences, network settings
- **Restart Required**: Resolution changes, USB identity, protocol switches
- **Security**: MD5-signed requests with input validation
- **Multi-language**: EN/ZH/TW/HU/KO/RU support
- **Storage**: /etc/riddle.conf with /etc/riddle_default.conf backup

## Hardware Detection Files

- `/etc/box_product_type`: Model identifier (A15W, A15H, etc.)
- `/etc/box_version`: Custom version affecting module loading
- `/script/getFuncModule.sh`: Module loading logic based on product type
- `/script/init_audio_codec.sh`: Audio codec detection (WM8960/AC6966)