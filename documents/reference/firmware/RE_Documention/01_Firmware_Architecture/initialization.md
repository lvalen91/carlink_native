# CPC200-CCPA Firmware Initialization & Boot Sequence

**Source:** Extracted firmware analysis
**Firmware Version:** 2025.10.15.1127 (binary analysis reference version)
**Last Updated:** 2026-02-02

---

## Overview

This document covers the adapter's boot process and host initialization message sequence. For heartbeat timing requirements, see `heartbeat_analysis.md`.

---

## Host Initialization Sequence

The host application sends the following messages after USB connection.

**CRITICAL:** The adapter has a ~10-second watchdog timer. Both init AND first heartbeat must complete within this window.

```
1. USB Reset (clear partially configured state)
2. 3-second mandatory wait
3. Open USB connection
4. START HEARTBEAT TIMER (do NOT send heartbeat immediately!)
   - Timer starts, first heartbeat fires after 2000ms interval
   - See heartbeat_analysis.md for details
5. Send initialization messages (below) - takes ~1.5s
6. Start reading loop
7. First heartbeat fires at t=2000ms (after init completes)
```

**Note:** Do NOT send heartbeat at t=0. The timer should start before init, but the first heartbeat should fire AFTER the init messages are sent. This matches the working pattern in pi-carplay and carlink_native.

### Initialization Messages

| Order | Type | Name | Content |
|-------|------|------|---------|
| 1 | 160 | AppInfo | JSON: app version, device model, platform, uuid, screen size (host identification) |
| 2 | 240 | EnableCrypt | CMD_ENABLE_CRYPT — 4-byte random nonce for AES session key derivation |
| 3 | 153 | SendFile | `/tmp/screen_dpi` - Display DPI |
| 4 | 153 | SendFile | `/etc/android_work_mode` - Android work mode (1=AA, 2=CarLife, 3=Mirror, 4=HiCar, 5=ICCOA) |
| 5 | 1 | Open | Session parameters (28 bytes: width, height, fps, format, bitrate, boxVersion, phoneWorkMode) |
| — | — | *Wait* | *Wait for Open response from adapter before proceeding* |
| 6 | 25 | BoxSettings | JSON configuration (syncTime, mediaDelay, drivePosition, androidAutoSizeW/H, GNSSCapability, DashboardInfo, UseBTPhone) |
| 7 | 160 | AppInfo | JSON: full app/device info (second send, post-Open) |
| 8 | 153 | SendFile | CarPlay icons (`/etc/icon_120x120.png`, `/etc/icon_180x180.png`, `/etc/icon_256x256.png`) |
| 9 | 153 | SendFile | `/tmp/night_mode` - Night mode flag (0=day, 1=night, 2=auto) |
| 10 | 153 | SendFile | `/tmp/charge_mode` - Charge mode (0/1/2) |
| 11 | 13/14 | BT/WiFi Name | Set adapter Bluetooth name (type 0x0D) and WiFi name (type 0x0E) |
| 12 | 153 | SendFile | `/etc/box_name` - Adapter display name |
| 13 | 8 | Command | Set mic type (cmd 7/15/21), WiFi type (cmd 24/25), audio mode (cmd 22/23) |

**Source:** Carlinkit AutoKit app decompilation (v2025.03.19.1126, Mar 2026). The manufacturer's app sends CMD_ENABLE_CRYPT **before** the Open message, and sends AppInfo (type 0xA0) as the very first message. This differs from the observed capture sequences where encryption was not active.

### Open Message Structure

```
Offset  Size  Field        Description
------  ----  -----        -----------
0x00    4     width        Display width (e.g., 2400)
0x04    4     height       Display height (e.g., 960)
0x08    4     fps          Frame rate (e.g., 60)
0x0C    4     format       Video format ID
0x10    4     packetMax    Max packet size (e.g., 49152)
0x14    4     boxVersion   Protocol version (e.g., 2)
0x18    4     phoneMode    Operation mode (e.g., 2)
```

**Format Field Values:**

| Value | Name | Behavior |
|-------|------|----------|
| 1 | Basic Mode | Minimal IDR insertion |
| 5 | Full H.264 Mode | Responsive to Frame sync, aggressive IDR |

---

## Firmware Boot Process

### Manufacturer

- **Copyright:** DongGuan HeWei Communication Technologies Co. Ltd. (2014-2015)
- **Original Author:** Shi Kai
- **Original Date:** July 2015

### Boot Sequence (`/etc/init.d/rcS`)

```bash
#!/bin/sh
. /etc/profile
umask 022

# Mount filesystems and start mdev
mount -a
echo /sbin/mdev > /proc/sys/kernel/hotplug
mdev -s

# Network interface
ifconfig lo up

# Start main service in background
/script/start_main_service.sh &

# TCP/IP tuning (critical for CarPlay performance)
echo 1 > /proc/sys/net/ipv4/tcp_tw_reuse
echo 1 > /proc/sys/net/ipv4/tcp_tw_recycle       # TIME_WAIT optimization
echo 1 > /proc/sys/net/ipv4/tcp_orphan_retries   # FIN_WAIT1 optimization
echo 16777216 > /proc/sys/net/core/rmem_max      # 16MB receive buffer max
echo 2097152 > /proc/sys/net/core/rmem_default   # 2MB receive buffer default
echo 16777216 > /proc/sys/net/core/wmem_max      # 16MB send buffer max
echo 1 > /proc/sys/vm/overcommit_memory          # Allow memory overcommit
```

### Main Service Startup (`/script/start_main_service.sh`)

```bash
# 1. Set system date (prevents certificate validation issues)
date -s "2020-01-02 $(date +%T)"

# 2. Replace /dev/random with urandom (avoids OpenSSL blocking)
rm -f /dev/random && ln -s /dev/urandom /dev/random

# 3. Enable kernel timestamps
echo y > /sys/module/printk/parameters/time

# 4. Run custom init hook (if exists)
test -e /script/custom_init.sh && /script/custom_init.sh

# 5. Initialize GPIO
/script/init_gpio.sh &

# 6. Copy binaries to /tmp (RAM disk for faster access)
/script/copy_to_tmp.sh

# 7. Create riddle.conf if missing
test -e /etc/riddle.conf || cp /etc/riddle_default.conf /etc/riddle.conf

# 8. Initialize Bluetooth and WiFi
/script/init_bluetooth_wifi.sh

# 9. Initialize audio codec (WM8960 or AC6966)
/script/init_audio_codec.sh

# 10. Start main USB protocol handler
cp /usr/sbin/ARMadb-driver /tmp/bin/
ARMadb-driver >> /tmp/userspace.log 2>&1 &

sleep 3

# 11. Start LED daemon
test -e /usr/sbin/colorLightDaemon && colorLightDaemon &

# 12. Start mDNS (CarPlay discovery)
cp /usr/sbin/mdnsd /tmp/bin/
mdnsd

# 13. Start iAP2 and NCM drivers
/script/start_iap2_ncm.sh
/script/start_ncm.sh

sleep 4

# 14. Start network service
boxNetworkService &

# 15. Unpack image tools
unzip /usr/sbin/boxImgTools.zip -d /tmp/bin/ &

sleep 6

# 16. Start web server (Boa)
cp /usr/sbin/boa /tmp/bin/
mkdir -p /tmp/boa
cp -r /etc/boa/www /tmp/boa/
cp -r /etc/boa/cgi-bin /tmp/boa/
boa

# 17. Start HUD server (if present)
test -e /usr/sbin/boxHUDServer && boxHUDServer &

# 18. Free memory
echo 3 > /proc/sys/vm/drop_caches

# 19. Start monitoring scripts
/script/cpu_UsageRate.sh &
/script/check_log_size.sh &
```

---

## Custom Init Hook

The firmware supports a user-defined initialization script at `/script/custom_init.sh`. This runs early in the boot process before main services start.

**Use cases:**
- Enable SSH/dropbear
- Set custom configuration
- Start additional daemons

---

## Key Processes

| Process | Purpose | Started By |
|---------|---------|------------|
| **ARMadb-driver** | Main USB protocol handler | start_main_service.sh |
| **mdnsd** | CarPlay mDNS/Bonjour discovery | start_main_service.sh |
| **boxNetworkService** | Network management | start_main_service.sh |
| **colorLightDaemon** | Red/Blue LED status control | start_main_service.sh |
| **boa** | Web server for configuration UI (v0.94.101wk) | start_main_service.sh |
| **hfpd** | Bluetooth HFP daemon | init_bluetooth_wifi.sh |
| **bluetoothDaemon** | Bluetooth management | init_bluetooth_wifi.sh |

---

## Service Registration Order

*Verified via TTY log capture (Jan 2026)*

During initialization, ARMadb-driver registers the following Bluetooth services in order:

| Order | Service | Purpose |
|-------|---------|---------|
| 1 | IAP2 | Apple iAP2 protocol for CarPlay |
| 2 | NearBy | Proximity-based device discovery |
| 3 | HiChain | Huawei HiCar authentication/trust chain |
| 4 | AAP | Android Auto Protocol |
| 5 | Serial Port | Generic Bluetooth serial (RFCOMM) |

**Log pattern:**
```
IAP2 service registered
NearBy service registered
HiChain service registered
AAP service registered
Serial Port service registered
```

These services are started by the CarPlay/AndroidAuto link daemons:
- `Start Link Deamon: CarPlay` → Starts IAP2/NearBy/HiChain
- `Start Link Deamon: AndroidAuto` → Starts AAP

---

## TCP/IP Tuning Parameters

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `tcp_tw_reuse` | 1 | Reuse TIME_WAIT sockets |
| `tcp_tw_recycle` | 1 | Fast TIME_WAIT recycling |
| `tcp_orphan_retries` | 1 | Reduce FIN_WAIT1 timeout |
| `rmem_max` | 16MB | Max receive buffer |
| `rmem_default` | 2MB | Default receive buffer |
| `wmem_max` | 16MB | Max send buffer |
| `overcommit_memory` | 1 | Allow memory overcommit |

These settings optimize TCP performance for CarPlay's real-time streaming requirements.

---

## References

- Source: `carlink_native/documents/reference/Firmware/firmware_initialization.md`
- Firmware: `cpc200_ccpa_firmware_binaries/`
- Heartbeat details: `heartbeat_analysis.md`
