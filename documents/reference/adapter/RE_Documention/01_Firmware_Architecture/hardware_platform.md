# CPC200-CCPA Hardware Platform

**Model:** CPC200-CCPA / Carlinkit A15W Wireless CarPlay/Android Auto Adapter
**Consolidated from:** GM_research, carlink_native, pi-carplay firmware analysis
**Last Updated:** 2026-01-16

---

## System-on-Chip

| Parameter | Value |
|-----------|-------|
| **Processor** | NXP i.MX6UL (ARM Cortex-A7) |
| **Architecture** | ARMv7, 32-bit |
| **RAM** | 128MB |
| **Storage** | 16MB Flash |
| **Kernel** | Linux 3.14.52+g94d07bb SMP |

## Wireless Connectivity

| Component | Chip | Details |
|-----------|------|---------|
| **WiFi** | Realtek RTL88x2CS | 5GHz 802.11ac, hotspot mode |
| **Bluetooth** | Realtek RTK HCI UART | BR/EDR + BLE |

## Audio Hardware

### Supported Codecs

| Codec | I2C Address | Purpose |
|-------|-------------|---------|
| **WM8960** (Primary) | 0x1a | Full-duplex stereo, high-quality audio |
| **AC6966** (Alternative) | 0x15 | Bluetooth SCO optimized, voice calls |

### Codec Detection (from init scripts)
```bash
i2cdetect -y -a 0 0x1a 0x1a | grep "1a" && audioCodec=wm8960
i2cdetect -y -a 0 0x15 0x15 | grep "15" && audioCodec=ac6966
```

### Kernel Modules
```bash
insmod /tmp/snd-soc-wm8960.ko
insmod /tmp/snd-soc-imx-wm8960.ko
insmod /tmp/snd-soc-bt-sco.ko
insmod /tmp/snd-soc-imx-btsco.ko
```

## MFi Authentication Coprocessor

The adapter carries a **genuine Apple MFi authentication IC** — not a software MFi spoof. The firmware drives it over I2C during every wireless CarPlay pairing.

| Parameter | Value |
|-----------|-------|
| **Bus** | `/dev/i2c-1` (I2C bus 1) |
| **Address** | 7-bit `0x11` (8-bit `0x22`) |
| **Type** | Genuine Apple MFi authentication IC — firmware log: `mfi ic is from APPLE` |
| **Certificate serial** | `IPA_3333AA071227AA02AA0011AA003045` (subject serial `33 33 AA 07 12 27 AA 02 AA 00 11 AA 00 30 45`) |
| **Output** | 128-byte RSA signature (RSA-1024, PKCS#1 v1.5) |

The firmware reads `MFI_AUTH_COP_REG_ADDR_SIGNATURE_LEN` then `MFI_AUTH_COP_REG_ADDR_SIGNATURE_DATA` to retrieve the signature after writing the challenge.

**This is real hardware, NOT a software MFi spoof.** (Contrast the CPC200-U2W, which spoofs MFi in software.) The chip is exercised **twice per wireless pairing**:
1. **iAP2 / Bluetooth layer** — challenge-response carried in iAP2 messages `0xAA00`–`0xAA05` over BT RFCOMM (945-byte cert, 20-byte challenge, 128-byte signature).
2. **AirPlay layer** — a second `MFi auth create signature` after the HomeKit `pair-verify` exchange, on the WiFi side.

The cert is identical between the two; the signatures differ because the input challenges differ. See `02_Protocol_Reference/carplay_handshake.md` and the "Wireless CarPlay Handshake" section of `initialization.md` for the full flow.

> **RE caveat:** A live register-level I2C trace of this chip was *not* captured — the firmware's kernel rejects `ptrace` (`EINVAL`, kernel-wide) and has no ftrace, so `/dev/i2c-1` cannot be snooped in-place. The cryptographic material was instead captured on the wire (BT iAP2 `0xAA0x` + firmware `ttyLog`).

## USB Interfaces

### iPhone-Facing (Gadget Mode)

The adapter presents itself to the iPhone as an Apple-compatible accessory:

| Parameter | Value | Description |
|-----------|-------|-------------|
| idVendor | 0x08e4 (2276) | Magic Communication Technology |
| idProduct | 0x01c0 (448) | Auto Box product ID |
| iManufacturer | "Magic Communication Tec." | Manufacturer string |
| iProduct | "Auto Box" | Product string |
| functions | iap2,ncm | IAP2 protocol + USB NCM networking |

Configuration script:
```bash
echo 0 > /sys/class/android_usb/android0/enable
echo 0x08e4 > /sys/class/android_usb/android0/idVendor
echo 0x01c0 > /sys/class/android_usb/android0/idProduct
echo "Magic Communication Tec." > /sys/class/android_usb/android0/iManufacturer
echo "Auto Box" > /sys/class/android_usb/android0/iProduct
echo "iap2,ncm" > /sys/class/android_usb/android0/functions
echo 1 > /sys/class/android_usb/android0/enable
```

### Head Unit-Facing (Host Mode)

| Parameter | Value | Description |
|-----------|-------|-------------|
| VID | 0x1314 (4884) | Configurable in riddle.conf |
| PID | 0x1521 (5409) | Configurable in riddle.conf |

### iPhone Detection
```bash
# From start_hnp.sh
iphoneRoleSwitch_test 0x05ac 0x12a8
# 0x05ac = Apple Inc. vendor ID
# 0x12a8 = iPhone product ID
```

## USB Gadget Functions

| Module | Purpose |
|--------|---------|
| `g_iphone.ko` | IAP2 USB gadget driver |
| `f_ptp_appledev.ko` | PTP Apple device function |
| `f_ptp_appledev2.ko` | Alternative PTP function |
| `g_android_accessory.ko` | Android AOA gadget |
| `cdc_ncm.ko` | USB NCM networking |
| `storage_common.ko` | USB mass storage |

### Android Open Accessory (AOA) Mode

When an Android phone connects for Android Auto, the adapter configures it into AOA mode:

| Property | Value | Description |
|----------|-------|-------------|
| idVendor | 0x18d1 | Google Inc. |
| idProduct | 0x2d00 or 0x4ee1 | AOA accessory (0x2d00) or AOA+ADB composite (0x4ee1, seen with Pixel 10) |
| Protocol | AOA 2.0 | USB Accessory Protocol |

**Observed Devices (TTY log Jan 2026):**
```
usb 1-1: New USB device found, idVendor=18d1, idProduct=4ee1
usb 1-1: Product: Pixel 10
usb 1-1: Manufacturer: Google
usb 1-1: SerialNumber: 57281FDCR00673
```

**AOA Configuration Process:**
1. Adapter detects USB device arrival via libusb hotplug
2. `ConfigAoa` class configures phone into AOA mode
3. Phone re-enumerates with AOA USB identifiers
4. OpenAuto SDK establishes Android Auto session

## Key Hardware Interfaces

| Path | Purpose |
|------|---------|
| `/dev/android_iap2` | USB IAP2 device |
| `/dev/hwaes` | Hardware AES engine |
| `/dev/i2c-1` | MFi authentication coprocessor (genuine Apple IC, 7-bit addr 0x11) |
| `/sys/class/android_usb/android0/` | USB gadget control |
| `/sys/bus/platform/devices/ci_hdrc.1/` | USB OTG controller |

## GPIO Assignments

| GPIO | Suspected Purpose |
|------|-------------------|
| GPIO 2 | Unknown hardware control |
| GPIO 6 | WiFi/BT module power |
| GPIO 7 | WiFi/BT module reset |
| GPIO 9 | Unknown hardware control |

## Resource Constraints

The CPC200-CCPA operates under severe constraints:

| Resource | Limit | Impact |
|----------|-------|--------|
| RAM | 128MB | Limits processing to basic format conversion |
| Storage | 16MB | Compressed rootfs (~15MB) |
| CPU | Single-core ARM32 | No complex DSP operations |

This architecture results in a **"Smart Interface, Dumb Processing"** design where the adapter handles protocol translation and format conversion, delegating sophisticated processing (WebRTC, noise cancellation) to the host application.

---

## References

- Source: `GM_research/cpc200_research/docs/hardware/REVERSE_ENGINEERING_NOTES.md`
- Source: `carlink_native/documents/reference/Firmware/firmware_initialization.md`
- Source: `carlink_native/documents/reference/Firmware/firmware_audio.md`
