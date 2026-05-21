# CPC200-CCPA Firmware Version Comparison

**Purpose:** Document changes across A15W firmware versions from 2022 to 2025
**Analysis Date:** 2026-01-29
**Versions Analyzed:** 9 firmware images (2022.04.25 through 2025.10.15)

---

## Version Overview

| Version | Date | Size | WiFi Drivers | Binaries | Scripts | Kernel Modules |
|---------|------|------|--------------|----------|---------|----------------|
| 2022.04.25.1317 | Apr 2022 | 11.9MB | BCM4354, RTL8822, SD8987 | 18 | 21 | 3 |
| 2022.11.19.1218 | Nov 2022 | 10.7MB | BCM4358, SD8987 | 20 | 21 | 3 |
| 2023.05.29.1924 | May 2023 | 10.8MB | BCM4358, SD8987 | 23 | 24 | 3 |
| 2023.09.27.1710 | Sep 2023 | 13.2MB | BCM4358, RTL8822CS, SD8987 | 33 | 27 | 3 |
| 2024.01.19.1541 | Jan 2024 | 13.2MB | BCM4358, RTL8822CS, SD8987 | 33 | 27 | 3 |
| 2024.08.07.2014 | Aug 2024 | 11.5MB | RTL8822CS only | 34 | 29 | 8 |
| 2024.09.03.1028 | Sep 2024 | 10.6MB | **NONE** | 37 | 30 | 12 |
| 2025.02.25.1521 | Feb 2025 | 12.6MB | RTL8822CS, IW416 | 37 | 31 | 11 |
| 2025.10.15.1127 | Oct 2025 | 11.5MB | **NONE** | 39 | 31 | 11 |

---

## WiFi Driver Evolution

### Supported Chipsets by Version

| Chipset ID | Chip | 2022.04 | 2022.11 | 2023.05 | 2023.09 | 2024.01 | 2024.08 | 2024.09 | 2025.02 | 2025.10 |
|------------|------|---------|---------|---------|---------|---------|---------|---------|---------|---------|
| 0x4354 | BCM4354 | Y | - | - | - | - | - | - | - | - |
| 0x4358 | BCM4358 | - | Y | Y | Y | Y | - | - | - | - |
| 0xb822 | RTL8822BS | Y | - | - | - | - | - | - | - | - |
| 0xc822 | RTL8822CS | - | - | - | Y | Y | Y | - | Y | - |
| 0x9149 | SD8987 | Y | Y | Y | Y | Y | - | - | - | - |
| 0x9159 | IW416 | - | - | - | - | - | - | - | Y | - |

### WiFi Rootfs Files

```
2022.04.25: bcm4354_rootfs.tar.gz, rtl8822_rootfs.tar.gz, sd8987_rootfs.tar.gz
2022.11.19: bcm4358_rootfs.tar.gz, sd8987_rootfs.tar.gz
2023.05.29: bcm4358_rootfs.tar.gz, sd8987_rootfs.tar.gz
2023.09.27: bcm4358_rootfs.tar.gz, rtl8822cs_rootfs.tar.gz, sd8987_rootfs.tar.gz
2024.01.19: bcm4358_rootfs.tar.gz, rtl8822cs_rootfs.tar.gz, sd8987_rootfs.tar.gz
2024.08.07: rtl8822cs_rootfs.tar.gz
2024.09.03: (none - scripts reference but files missing)
2025.02.25: rtl8822cs_rootfs.tar.gz, iw416_rootfs.tar.gz
2025.10.15: (none - scripts reference but files missing)
```

### Critical Issue

Versions **2024.09.03** and **2025.10.15** have init scripts that reference WiFi driver packages that are NOT included in the firmware. This causes devices with non-RTL8822CS chipsets to fail to initialize WiFi, resulting in:
- No WiFi AP broadcast
- Device appears "bricked"
- Recovery requires flashing 2023.09.27 or earlier

---

## Binary Evolution

### New Binaries by Version

| Version | Added |
|---------|-------|
| 2022.11.19 | ARMandroid_Mirror, hostapd |
| 2023.05.29 | boxImgTools, colorLightDaemon, hwfsTools |
| 2023.09.27 | adbd, am, echoDelayTest, hciconfig, hcitool, iw, mtp-server, wl, wpa_cli, wpa_supplicant |
| 2024.01.19 | boxImgTools.zip, udhcpd |
| 2024.08.07 | boxNetworkService |
| 2024.09.03 | AutomaticTest, i2cdetect, i2cset |
| 2025.10.15 | boxHUDServer, boxUIServer, dropbear |

### Removed Binaries by Version

| Version | Removed |
|---------|---------|
| 2024.01.19 | boxImgTools (replaced with .zip), wl |
| 2025.10.15 | ~~ARMimg_maker~~ (earlier analysis claimed removal; binary confirmed present in 2025.02 and 2025.10 extracted rootfs — needs re-verification) |

### ARMadb-driver Size Evolution

| Version | Size (bytes) | Notes |
|---------|--------------|-------|
| 2022.04.25 | 231,604 | Base |
| 2022.11.19 | 216,952 | -6% |
| 2023.05.29 | 222,244 | +2% |
| 2023.09.27 | 223,412 | +0.5% |
| 2024.01.19 | 224,092 | +0.3% |
| 2024.08.07 | 225,928 | +0.8% |
| 2024.09.03 | 226,752 | +0.4% |
| 2025.02.25 | 211,424 | -7% (major refactor) |
| 2025.10.15 | 216,520 | +2% |

---

## Kernel Module Evolution

### ko.tar.gz Contents

**2022.04.25 - 2024.01.19 (3 modules):**
```
storage_common.ko
g_android_accessory.ko
cdc_ncm.ko
```

**2024.08.07 (8 modules):**
```
+ f_ptp.ko              (iPhone PTP protocol)
+ f_ptp_appledev.ko     (iPhone PTP device)
+ f_ptp_appledev2.ko    (iPhone PTP device v2)
+ g_iphone.ko           (iPhone USB gadget)
+ g_android_autobox.ko  (Android Auto USB gadget)
```

**2024.09.03+ (12 modules):**
```
+ snd-soc-wm8960.ko     (WM8960 audio codec)
+ snd-soc-imx-wm8960.ko (i.MX WM8960 interface)
+ snd-soc-bt-sco.ko     (Bluetooth SCO audio)
+ snd-soc-imx-btsco.ko  (i.MX Bluetooth SCO)
```

> **Note:** `g_android_autobox.ko` was removed between 2024.09.03 and 2025.02.25 (not present in 2025.02 or 2025.10 extracted rootfs).

---

## Script Evolution

### New Scripts by Version

| Version | Added |
|---------|-------|
| 2023.05.29 | +3 scripts |
| 2023.09.27 | +3 scripts |
| 2024.08.07 | check_mfg_mode.sh, start_ncm.sh |
| 2024.09.03 | init_audio_codec.sh |
| 2025.02.25 | start_android_ncm.sh |

### Key Script Changes

**init_bluetooth_wifi.sh:**
- 2023.09.27: Added RTL8822CS support (0xc822)
- 2024.08.07: Added iPhone PTP module support
- 2024.09.03: Added IW416 support (0x9159), RTL8733 support (0xb733)
- 2025.02.25: Added WiFi P2P mode forcing for NXP chips

**start_main_service.sh:**
- 2024.08.07: Added boxNetworkService startup
- 2024.09.03: Added audio codec initialization
- 2025.10.15: Added boxHUDServer startup, disabled hwSecret

---

## Configuration Changes

### wpa_supplicant.conf

| Version | p2p_oper_channel |
|---------|------------------|
| 2022-2023.09 | 36 |
| 2024.01+ | 149 |

**Note — SoftAP channel vs WiFi-Direct channel:** `p2p_oper_channel` is the **WiFi-Direct (p2p)** operating channel, used **only when `WiFiP2PMode=1`**. It is distinct from the **SoftAP** `WiFiChannel` (hostapd), whose default is **36**. The 2026-05-21 capture confirmed SoftAP operation on **channel 36 / 5180 MHz**; the `0x5703 WifiConfigInfo` handed to the iPhone carried channel `0x24` (36). The change to `p2p_oper_channel=149` in 2024.01+ therefore does not affect the default SoftAP channel.

### udhcpd.conf

| Version | IP Range |
|---------|----------|
| 2022-2024.08 | 192.168.43.100-200 |
| 2024.09+ | 192.168.50.100-200 |

---

## Recovery Firmware Selection

For bricked devices, use firmware version based on WiFi chipset:

| WiFi Chip | Chipset ID | Recommended Version |
|-----------|------------|---------------------|
| BCM4354 | 0x4354 | 2022.04.25 |
| BCM4358 | 0x4358, 0xaa31 | 2023.09.27 or 2024.01.19 |
| RTL8822BS | 0xb822 | 2022.04.25 |
| RTL8822CS | 0xc822 | 2023.09.27 or later |
| SD8987 | 0x9149, 0x9141 | 2023.09.27 or 2024.01.19 |
| IW416 | 0x9159 | 2025.02.25 |

**Safest universal recovery:** 2023.09.27 (supports BCM4358, RTL8822CS, SD8987)

---

## iAP2 serialNumber Is Not the Firmware Version

The iAP2 `IdentificationInformation` (`0x1D01`) message advertises a `serialNumber` parameter that carries a **firmware build string** — on the 2026-05-21 capture unit this was `2025.02.25.1521626a`. This string is **not** the running firmware version and should not be used to identify the firmware release. The authoritative firmware version is `/etc/software_version` (or the `ver` field in the web `infos` response, e.g. `2025.10.15.1127`); the version table at the top of this document is keyed off that. The iAP2 `firmwareVersion` parameter is likewise a fixed `1.0.0` and not a release identifier.

---

## References

- Firmware binaries: `/Users/zeno/Downloads/misc/cpc200_ccpa_firmware_binaries/old_firmware/`
- Extraction tool: `FirmwareA15W.sh`
- Analysis date: 2026-01-29
