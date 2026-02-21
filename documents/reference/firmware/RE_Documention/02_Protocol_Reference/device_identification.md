# Device Identification Messages

**Purpose:** Protocol messages that identify connected devices and adapter information
**Last Updated:** 2026-01-20
**Verification:** Binary analysis + USB capture verification

---

## Overview

Multiple protocol messages provide device identification and connection information. This document consolidates the analysis of all identification-related messages.

---

## Message Summary

| Type | Hex | Name | Direction | Purpose |
|------|-----|------|-----------|---------|
| 2 | 0x02 | Plugged | IN | Phone connected notification |
| 10 | 0x0A | BluetoothAddress | IN | Adapter BT MAC address |
| 13 | 0x0D | BluetoothDeviceName | IN | Adapter BT device name |
| 14 | 0x0E | WifiDeviceName | IN | Adapter WiFi SSID |
| 15 | 0x0F | BluetoothPairedList | IN | List of paired devices |
| 25 | 0x19 | BoxSettings | BOTH | JSON configuration (richest data) |
| 35 | 0x23 | PeerBluetoothAddress | IN | Connected phone BT address |
| 36 | 0x24 | PeerBluetoothAddressAlt | IN | Alternative phone BT address |
| 163 | 0xA3 | SessionToken | IN | **Encrypted** JSON telemetry |

---

## Plugged Message (0x02)

### Payload Structure

```
Offset  Size  Field        Description
------  ----  -----        -----------
0x00    4     phoneType    Device/connection type (see below)
0x04    4     wifi         WiFi/wireless flag: 0=USB, 1=Wireless
```

**IMPORTANT:** The `wifi` field determines USB vs Wireless connection, NOT phoneType.

### phoneType Values (Binary Analysis + Capture Verification)

| Value | Type | Verification |
|-------|------|--------------|
| 1 | AndroidMirror | pi-carplay enum |
| 2 | Carlife | Firmware strings |
| 3 | CarPlay | ✓ **VERIFIED** (USB + Wireless, Jan 2026) |
| 4 | iPhoneMirror | pi-carplay enum |
| 5 | AndroidAuto | ✓ **VERIFIED** (session_examples.md) |
| 6 | HiCar | pi-carplay enum |
| 7 | ICCOA | Firmware ActionSession class |

### wifi Field (Transport Indicator)

| Value | Transport | Description |
|-------|-----------|-------------|
| 0 | USB | Wired USB connection |
| 1 | Wireless | WiFi CarPlay/AndroidAuto |

**Verification (Jan 2026):**
- CarPlay USB: `phoneType=3, wifi=0`
- CarPlay Wireless: `phoneType=3, wifi=1` ✓ **VERIFIED**

**Note:** Earlier GM_research captures (Dec 2025) showed `phoneType=8` for wireless CarPlay. This may have been a different firmware version or capture tool interpretation. Current firmware (2025.10.XX) uses the `wifi` field to distinguish transport.

### Firmware Evidence

ActionSession classes in `ARMadb-driver`:
```
Accessory_ActionSession_Link_iPhone_CarPlay_Wire
Accessory_ActionSession_Link_iPhone_CarPlay_WireLess
Accessory_ActionSession_Link_AndroidAuto_Wire
Accessory_ActionSession_Link_AndroidAuto_WireLess
Accessory_ActionSession_Link_Hicar_Wire
Accessory_ActionSession_Link_Hicar_WireLess
Accessory_ActionSession_Link_AndroidCarLife_Wire
Accessory_ActionSession_Link_ICCOA_Wire
Accessory_ActionSession_Link_ICCOA_WireLess
Accessory_ActionSession_Link_iPhone_Mirror_Wire
Accessory_ActionSession_Link_AnroidAdbMirror_Wire
```

### Plug Event Strings (Firmware)

```
iPhone Plug In!!! Unknown iPhone Mode!!
Android Auto Device Plug In!!!
Android Carlife Device Plug In!!!
Android ICCOA Device Plug In!!!
Huawei Device Plug In!!!
Device Plug In!!! Unknown Android Mode!!
```

---

## BoxSettings Message (0x19)

BoxSettings is bidirectional and contains the richest device information.

### Direction: IN (Adapter → Host)

**Initial Config (sent early in session):**
```json
{
  "uuid": "651ede982f0a99d7f9138131ec5819fe",
  "MFD": "20240119",
  "boxType": "YA",
  "OemName": "test_CCPA",
  "productType": "A15W",
  "HiCar": 1,
  "supportLinkType": "HiCar,CarPlay,AndroidAuto",
  "supportFeatures": "naviScreen",
  "hwVersion": "YMA0-WR2C-0003",
  "WiFiChannel": 36,
  "CusCode": "",
  "DevList": [
    {
      "id": "64:31:35:8C:29:69",
      "type": "CarPlay",
      "name": "Luis",
      "index": "2",
      "time": "2026-01-19 11:54:58",
      "rfcomm": "1"
    },
    {
      "id": "B0:D5:FB:A3:7E:AA",
      "type": "AndroidAuto",
      "name": "Pixel 10",
      "index": "1"
    }
  ],
  "ChannelList": "1,2,3,4,5,6,7,36,40,44,149,157,161"
}
```

**Phone Info (sent after connection established):**
```json
{
  "MDLinkType": "CarPlay",
  "MDModel": "iPhone18,4",
  "MDOSVersion": "23D5103d",
  "MDLinkVersion": "935.4.1",
  "btName": "",
  "cpuTemp": 55
}
```

For complete BoxSettings field documentation, see `01_Firmware_Architecture/configuration.md` > BoxSettings JSON Mapping.

### cpuTemp Source (Binary Verified)

**IMPORTANT:** `cpuTemp` is the **adapter's** CPU temperature, NOT the phone's.

Firmware evidence:
```c
// From ARMadb-driver strings
/sys/class/thermal/thermal_zone0/temp    // Linux thermal zone on adapter
GetBoxCpuTemp: %s -- %d                   // "Box" = adapter
```

The adapter reads `/sys/class/thermal/thermal_zone0/temp` and includes it in the BoxSettings JSON.

---

## SessionToken Message (0xA3)

Encrypted JSON telemetry sent once during session establishment.

### Encryption Details (Verified Jan 2026)

| Property | Value |
|----------|-------|
| Algorithm | AES-128-CBC |
| Key | `W2EC1X1NbZ58TXtn` (USB Communication Key) |
| IV | First 16 bytes of Base64-decoded payload |
| Format | Base64-encoded → AES-CBC encrypted JSON |

### Decryption Process

```bash
# 1. Extract Base64 payload (skip 16-byte USB header)
# 2. Base64 decode
# 3. Split: first 16 bytes = IV, rest = ciphertext
# 4. AES-128-CBC decrypt with USB key

KEY_HEX=$(printf "W2EC1X1NbZ58TXtn" | xxd -p)
IV_HEX=$(dd if=decoded_payload.bin bs=1 count=16 | xxd -p)
dd if=decoded_payload.bin bs=1 skip=16 | \
  openssl enc -d -aes-128-cbc -K "$KEY_HEX" -iv "$IV_HEX" -nopad
```

### Presence in Captures

| Capture Type | Date | SessionToken Present |
|--------------|------|---------------------|
| CarPlay USB | Jan 2026 | ✓ YES |
| CarPlay Wireless | Jan 2026 | ✓ YES |
| CarPlay Wireless | Dec 2025 | NO (GM_research) |

**Note:** GM_research wireless CarPlay captures (Dec 2025) did not contain SessionToken messages, but fresh Jan 2026 wireless captures DO include SessionToken. The Dec 2025 absence may have been due to capture tool limitations or different firmware.

### Decrypted Content

**CarPlay Wireless Example (Jan 2026):**
```json
{
  "phone": {
    "model": "iPhone18,4",
    "os": "",
    "osVer": "23D5103d",
    "linkT": "CarPlay",
    "conSpd": 4,
    "conRate": 0.53,
    "conNum": 30,
    "success": 16
  },
  "car": {},
  "box": {
    "uuid": "651ede982f0a99d7f9138131ec5819fe",
    "model": "A15W",
    "hw": "YMA0-WR2C-0003",
    "ver": "2025.10.15.1127",
    "mfd": "20240119",
    "sdk": ""
  }
}
```

### SessionToken Field Reference

| Field | Description | Observations |
|-------|-------------|--------------|
| `phone.model` | Phone model identifier | Same as MDModel |
| `phone.os` | OS name (often empty) | |
| `phone.osVer` | OS build version | Same as MDOSVersion |
| `phone.linkT` | Link type | CarPlay/AndroidAuto |
| `phone.conSpd` | Connection speed indicator | Values: 4, 6 observed |
| `phone.conRate` | Historical connection success rate | 0.0-1.0, may be absent |
| `phone.conNum` | Total connection attempts | May be absent on first connect |
| `phone.success` | Successful connection count | May be absent |
| `car` | Car/head unit info | Usually empty object |
| `box.uuid` | Adapter UUID | Same as BoxSettings |
| `box.model` | Adapter model | A15W, etc. |
| `box.hw` | Hardware revision | Same as hwVersion |
| `box.ver` | Firmware version | e.g., 2025.10.15.1127 |
| `box.mfd` | Manufacturing date | YYYYMMDD format |
| `box.sdk` | SDK version | Often empty |

### conSpd Analysis

The `conSpd` field appears to be a connection speed/quality indicator:

| Value | Interpretation (Hypothesized) |
|-------|------------------------------|
| 4 | Fast connection |
| 6 | Normal connection |

**Note:** Values are from limited captures. The exact meaning and full range of values is not documented in firmware strings. The field name suggests "connection speed" and lower values may indicate faster connections.

---

## Other Identification Messages

### BluetoothDeviceName (0x0D)

```
Payload: Null-terminated ASCII string
Example: "test_CCPA\0"
```

### WifiDeviceName (0x0E)

```
Payload: Null-terminated ASCII string
Example: "test_CCPA\0"
```

### BluetoothAddress (0x0A)

```
Payload: 17 bytes - MAC address as ASCII "XX:XX:XX:XX:XX:XX\0"
```

### PeerBluetoothAddress (0x23)

```
Payload: 17 bytes - Connected phone's BT MAC address
```

---

## Capture Verification Summary

| phoneType | Device | Transport | Capture Date | Status |
|-----------|--------|-----------|--------------|--------|
| 3 | CarPlay | USB | 2026-01-20 | ✓ VERIFIED |
| 5 | AndroidAuto | USB | 2026-01-19 | ✓ VERIFIED |
| 8 | CarPlay | Wireless | 2025-12-29 | ✓ VERIFIED |
| 1 | AndroidMirror | USB | - | Unverified |
| 2 | Carlife | USB | - | Unverified |
| 4 | iPhoneMirror | USB | - | Unverified |
| 6 | HiCar | USB | - | Unverified |
| 7 | ICCOA | USB | - | Unverified |
| 9+ | Wireless variants | WiFi | - | Unverified |

---

## Related Documentation

- `usb_protocol.md` - USB message format and payload details
- `../03_Security_Analysis/crypto_stack.md` - SessionToken encryption details
- `../04_Implementation/session_examples.md` - Full session capture examples

