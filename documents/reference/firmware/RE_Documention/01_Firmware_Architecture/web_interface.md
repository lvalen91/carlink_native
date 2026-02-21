# CPC200-CCPA Web Interface Documentation

**Purpose:** Document the adapter's self-hosted web configuration interface
**Firmware Version:** 2025.10.15.1127
**Analysis Date:** 2026-01-20
**CGI Build Date:** Sep 5 2025 11:05:47

---

## Overview

The CPC200-CCPA adapter hosts a web-based configuration interface on port 80. The interface uses a Vue.js single-page application (SPA) frontend with CGI backend scripts.

### Server Details

| Property | Value |
|----------|-------|
| **Web Server** | Boa v0.94 (lightweight HTTP server) |
| **IP Address (WiFi)** | `http://192.168.43.1` |
| **IP Address (Alt)** | `http://192.168.50.2` |
| **Port** | 80 |
| **Document Root** | `/tmp/boa/www` (copied from `/etc/boa/www`) |
| **CGI Directory** | `/tmp/boa/cgi-bin` |

---

## Directory Structure

```
/etc/boa/
├── boa.conf                    # Boa server configuration
├── cgi-bin/
│   ├── server.cgi              # Main API backend (48KB, UPX-packed ARM ELF)
│   └── upload.cgi              # File upload handler (36KB, UPX-packed ARM ELF)
├── images/                     # Static images (car logos)
└── www/
    ├── index.html              # Main Vue.js SPA entry point
    ├── favicon.png             # Site favicon
    ├── js/
    │   ├── PublicV2.*.js.gz    # Main Vue.js application (gzipped)
    │   ├── chunk-vendors.*.js.gz  # Third-party libraries
    │   └── chunk-*.js.gz       # Lazy-loaded route chunks
    ├── lang/
    │   ├── en-*.js.gz          # English translations
    │   ├── zh-*.js.gz          # Chinese (Simplified)
    │   ├── tw-*.js.gz          # Chinese (Traditional)
    │   ├── ko-*.js.gz          # Korean
    │   ├── ru-*.js.gz          # Russian
    │   └── hu-*.js.gz          # Hungarian
    └── static/
        ├── css/                # Compiled CSS stylesheets
        └── img/                # Static images
```

---

## CGI API Reference

### Authentication

All API requests require MD5 signature verification.

**Signature Algorithm:**
```javascript
SECRET = 'HweL*@M@JEYUnvPw9G36MVB9X6u@2qxK'

// Sort parameters alphabetically, concatenate with &, append secret
function sign(params) {
    var keys = Object.keys(params).sort();
    var str = keys.map(k => k + '=' + params[k]).join('&');
    return md5(str + SECRET);
}

// Example for cmd=infos:
// Sign string: "cmd=infos&ts=1705728000000" + SECRET
// Result: md5("cmd=infos&ts=1705728000000HweL*@M@JEYUnvPw9G36MVB9X6u@2qxK")
```

**Request Format:**
```
POST /cgi-bin/server.cgi
Content-Type: application/x-www-form-urlencoded

cmd=<command>&ts=<timestamp_ms>&sign=<md5_signature>[&additional_params]
```

### API Endpoints

#### server.cgi Commands

| Command | Parameters | Response | Description |
|---------|------------|----------|-------------|
| `infos` | - | Full JSON config | Get all settings, device list, box info |
| `set` | `item`, `val` | `{"err":0}` | Set single configuration parameter |
| `reset` | - | `{"err":0}` | Restore previous configuration |
| `restart` | - | `{"err":0}` | Reboot the adapter |
| `BoxMonitor` | - | Live stats JSON | Real-time CPU, memory, temp, WiFi stats |
| `carInfo` | - | 403 Forbidden | Get car info (may need additional auth) |

**BoxMonitor Response:**
```json
{
  "BoxMonitor": {
    "CpuRate": 45.2,
    "CpuTemp": 52,
    "CpuFreq": 1200,
    "MemRate": 68.5,
    "WifiRX": 125.3,
    "WifiTX": 45.8,
    "MDLinkType": "CarPlay"
  }
}
```

**Error Codes:**
- `{"err":0}` - Success
- `{"err":404}` - Unknown command or missing parameters
- `Status: 403 Forbidden` - Invalid signature or unauthorized

---

## API Response: `infos` Command

The `infos` command returns comprehensive adapter information:

```json
{
  "CarInfo": {
    "brand": "",
    "model": "",
    "userid": "",
    "year": 0,
    "screen": "0x0",
    "gnss": "",
    "screenFps": "0",
    "screenSize": "0x0",
    "huid": ""
  },
  "BoxInfo": {
    "bt": "test_CCPA",
    "wifi": "test_CCPA",
    "boxName": "test_CCPA",
    "MDLinkType": "",
    "HULinkType": "",
    "MDModel": "",
    "MDOSVersion": "",
    "MDLinkVersion": "",
    "hardwareVer": "YMA0-WR2C-0003",
    "ver": "2025.10.15.1127",
    "cgiVer": "Sep  5 2025 11:05:47",
    "mfd": "20240119",
    "uuid": "651ede982f0a99d7f9138131ec5819fe",
    "productType": "A15W",
    "mac": "00:e0:4c:98:0a:6c",
    "wifiChipType": 6,
    "upTime": 4097946,
    "customVer": 2,
    "customCode": "",
    "fileName": "A15W_Update.img",
    "boxType": "YA",
    "logFileSuffix": "YA15W",
    "tabId": "",
    "code": "<activation_code>",
    "needActive": 1
  },
  "Settings": {
    "startDelay": 0,
    "mediaDelay": 300,
    "autoConn": 1,
    "wifi5GSwitch": 1,
    "wifiChannel": 36,
    "mediaSound": 1,
    "CallQuality": 2,
    "bitRate": 0,
    "autoPlay": 0,
    "backRecording": 0,
    "naviVolume": 0,
    "displaySize": 0,
    "ScreenDPI": 0,
    "Udisk": 0
  },
  // NOTE: Settings object only contains 13 keys!
  // Many settings (MicType, BtAudio, BackgroundMode, HudGPSSwitch,
  // UDiskPassThrough, FastConnect, ImprovedFluency, KnobMode, MouseMode,
  // AdvancedFeatures, CustomCarLogo) are NOT returned.
  // Use `riddleBoxCfg -g <key>` to query these directly.
  "DevList": [
    {
      "id": "64:31:35:8C:29:69",
      "type": "CarPlay",
      "name": "Luis",
      "index": "2",
      "time": "2026-01-20 03:43:05",
      "rfcomm": "1"
    }
  ],
  "LangList": [],
  "WifiChannelList": [
    {"id": 1, "wifiFreq": 2412},
    {"id": 2, "wifiFreq": 2417},
    {"id": 3, "wifiFreq": 2422},
    {"id": 4, "wifiFreq": 2427},
    {"id": 5, "wifiFreq": 2432},
    {"id": 6, "wifiFreq": 2437},
    {"id": 7, "wifiFreq": 2442},
    {"id": 36, "wifiFreq": 5180},
    {"id": 40, "wifiFreq": 5200},
    {"id": 44, "wifiFreq": 5220},
    {"id": 149, "wifiFreq": 5745},
    {"id": 157, "wifiFreq": 5785},
    {"id": 161, "wifiFreq": 5805}
  ]
}
```

### BoxInfo Fields

| Field | Description |
|-------|-------------|
| `bt` | Bluetooth device name |
| `wifi` | WiFi SSID |
| `boxName` | Box display name |
| `MDLinkType` | Active phone link type (CarPlay/AndroidAuto/HiCar) |
| `HULinkType` | Host unit link type (AutoPlay, etc.) |
| `MDModel` | Connected phone model |
| `MDOSVersion` | Phone OS version |
| `MDLinkVersion` | CarPlay/AA protocol version |
| `hardwareVer` | Hardware revision |
| `ver` | Firmware version |
| `cgiVer` | CGI binary build date |
| `mfd` | Manufacturing date (YYYYMMDD) |
| `uuid` | Adapter unique ID (32 hex chars) |
| `productType` | Product model (A15W, etc.) |
| `mac` | WiFi MAC address |
| `wifiChipType` | WiFi chip identifier |
| `upTime` | Seconds since boot |
| `needActive` | Activation required flag |

**Active Android Auto Session Example (Jan 2026 Capture):**

When a device is connected, the `infos` response includes real-time session data:

```json
{
  "BoxInfo": {
    "bt": "pi-carplay",
    "wifi": "pi-carplay",
    "boxName": "pi-carplay",
    "MDLinkType": "AndroidAuto",
    "HULinkType": "AutoPlay",
    "MDModel": "Google Pixel 10",
    "MDOSVersion": "",
    "MDLinkVersion": "1.7",
    "hardwareVer": "YMA0-WR2C-0003",
    "ver": "2025.10.15.1127",
    "cgiVer": "Sep  5 2025 11:05:47",
    "mfd": "20240119",
    "uuid": "651ede982f0a99d7f9138131ec5819fe",
    "productType": "A15W",
    "mac": "00:e0:4c:98:0a:6c",
    "wifiChipType": 6,
    "upTime": 855199
  },
  "Settings": {
    "bitRate": 5,
    "ScreenDPI": 160
  },
  "DevList": [
    {
      "id": "64:31:35:8C:29:69",
      "type": "CarPlay",
      "name": "Luis",
      "index": "2",
      "time": "2026-01-22 01:04:58",
      "rfcomm": "1"
    },
    {
      "id": "B0:D5:FB:A3:7E:AA",
      "type": "AndroidAuto",
      "name": "Pixel 10",
      "index": "1"
    }
  ]
}
```

**Key Observations:**
- `MDLinkVersion: "1.7"` indicates Android Auto protocol version 1.7
- `bitRate: 5` corresponds to `maxVideoBitRate = 5000 Kbps`
- `DevList` contains both CarPlay and Android Auto paired devices
- Android Auto entries lack `time` and `rfcomm` fields (no BT profile data)

For complete settings field documentation with firmware hooks, see `web_settings_reference.md`.

---

## Setting a Configuration Value

**Example: Set MediaLatency to 500ms**

```bash
SECRET='HweL*@M@JEYUnvPw9G36MVB9X6u@2qxK'
TS=$(date +%s000)
SIGN=$(echo -n "cmd=set&item=MediaLatency&ts=${TS}&val=500${SECRET}" | md5 -q)

curl -X POST "http://192.168.43.1/cgi-bin/server.cgi" \
  -d "cmd=set&item=MediaLatency&val=500&ts=${TS}&sign=${SIGN}"
```

**Response:**
```json
{"err":0}
```

---

## Upload CGI

The `upload.cgi` endpoint handles file uploads for:
- Firmware updates (`.img` files)
- Car logo images
- Configuration files

**Endpoint:** `POST /cgi-bin/upload.cgi`

**Note:** The upload.cgi binary is UPX-packed and full analysis requires runtime debugging.

---

## Vue.js Frontend

The web interface is built with Vue.js 2.x using webpack code splitting.

### Supported Languages

| Code | Language |
|------|----------|
| en | English |
| zh | Chinese (Simplified) |
| tw | Chinese (Traditional) |
| ko | Korean |
| ru | Russian |
| hu | Hungarian |

### UI Sections

Based on language strings, the UI includes:

1. **Device Management** - View/delete paired devices
2. **Box Info** - Hardware/firmware information
3. **Car Info** - Vehicle brand/model configuration
4. **Settings**
   - Audio Settings (MediaDelay, CallQuality, MicType)
   - Video Settings (BitRate, Resolution, DisplaySize)
   - Network Settings (WiFi channel, Bluetooth name)
   - Other Settings (AutoConnect, StartDelay, etc.)
5. **Firmware Update** - Check for and install updates
6. **Feedback** - Submit problem reports

---

## Boa Configuration

Key settings from `/etc/boa/boa.conf`:

```
Port 80
User root
Group root
DocumentRoot /tmp/boa/www
ServerName www.autobox.com
DirectoryIndex index.html
KeepAliveMax 1000
KeepAliveTimeout 10
SinglePostLimit 25165824    # 24MB max upload
ScriptAlias /cgi-bin/ /tmp/boa/cgi-bin/
Alias /images /etc/boa/images
Alias /logs /tmp/boa/logs
```

**Notes:**
- Runs as root (security concern)
- 24MB max POST size for firmware uploads
- Logs to /dev/console (no persistent logging)

---

## Security Considerations

1. **MD5 Signature** - Weak authentication using hardcoded secret
2. **Root Execution** - CGI runs as root
3. **No HTTPS** - All traffic is unencrypted
4. **Hardcoded Secret** - Secret key embedded in frontend JavaScript
5. **Command Injection** - Prior firmware versions vulnerable via wifiName/btName (see `vulnerabilities.md`)

### Secret Key

The API signing secret is publicly visible in the JavaScript:
```
HweL*@M@JEYUnvPw9G36MVB9X6u@2qxK
```

---

## Binary Analysis Notes

Both `server.cgi` and `upload.cgi` are:
- ARM 32-bit ELF executables
- Statically linked
- Custom-packed (not standard UPX)
- No symbols/stripped

Standard UPX and radare2 string extraction fail due to packing. Runtime analysis or custom unpacking is required for full reverse engineering.

---

## Related Documentation

- `configuration.md` - Full riddle.conf parameter reference
- `../03_Security_Analysis/vulnerabilities.md` - Security issues
- `../04_Implementation/firmware_update.md` - Firmware update process
