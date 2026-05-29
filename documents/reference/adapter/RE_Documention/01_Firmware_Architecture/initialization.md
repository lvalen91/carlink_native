# CPC200-CCPA Firmware Initialization & Boot Sequence

**Source:** Extracted firmware analysis
**Firmware Version:** 2025.10.15.1127 (binary analysis reference version)
**Last Updated:** 2026-02-02

---

## Overview

This document covers the adapter's boot process and host initialization message sequence. For heartbeat timing requirements, see `heartbeat_analysis.md`.

---

## Host Initialization Sequence

The host application sends the following messages after USB connection. (This section documents only the **USB-side / head-unit-facing** init — i.e. the head unit's host app bringing up the adapter over USB. The separate **iPhone↔adapter wireless flow** that precedes a CarPlay session is documented in "Wireless CarPlay Handshake" below.)

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

**Note:** Do NOT send heartbeat at t=0. The timer should start before init, but the first heartbeat should fire AFTER the init messages are sent. This matches the working pattern observed in capture sessions.

### Initialization Messages

| Order | Type | Name | Content |
|-------|------|------|---------|
| 1 | 160 | AppInfo | JSON: app version, device model, platform, uuid, screen size (host identification) |
| 2 | 240 | EnableCrypt | CMD_ENABLE_CRYPT — 4-byte random nonce for AES session key derivation |
| 3 | 153 | SendFile | `/tmp/screen_dpi` - Display DPI |
| 4 | 153 | SendFile | `/etc/android_work_mode` - Android work mode (1=AA, 2=CarLife, 3=Mirror, 4=HiCar, 5=ICCOA) |
| 5 | 1 | Open | Session parameters (28 bytes: width, height, fps, format, packetMax, boxVersion, phoneMode) |
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
| **ARMadb-driver** | Main USB protocol handler ("MiddleManServer") | start_main_service.sh |
| **mdnsd** | CarPlay mDNS/Bonjour discovery | start_main_service.sh |
| **boxNetworkService** | Network management | start_main_service.sh |
| **colorLightDaemon** | Red/Blue LED status control | start_main_service.sh |
| **boa** | Web server for configuration UI (v0.94.101wk) | start_main_service.sh |
| **hfpd** | Bluetooth HFP daemon | init_bluetooth_wifi.sh |
| **bluetoothDaemon** | Bluetooth management | init_bluetooth_wifi.sh |
| **AppleCarPlay** | CarPlay daemon (the `CarPlayDemoApp`, AirPlay/AirTunes 320.17) — runs the AirPlay receiver over RTSP | started on demand when an iPhone connects (not at boot) |
| **ARMiPhoneIAP2** | iAP2 protocol engine over BT RFCOMM (identify, MFi auth, WiFi handover) | started on demand when an iPhone connects (not at boot) |

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

**Note (link selection):** Once a link is selected and `IAP2` becomes the active link, all non-active BT services are stopped — the firmware keeps only the active link's listener and tears down the rest (`保留当前连接: IAP2, 其余清除掉` → `Bluetooth StopListen NEARBY!!!`, `Bluetooth StopListen AAP!!!`). Verified in the 2026-05-21 CarPlay capture (`ttyLog` lines 91-93).

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

## Wireless CarPlay Handshake

This section documents the **iPhone↔adapter** side — the wireless pairing/handshake that brings up a CarPlay session, separate from the USB-side host init above. Verified against a fresh wireless pairing captured 2026-05-21 (`carplay-20260521-101016`: BT HCI + WiFi pcap + firmware `ttyLog`). For the consolidated message-level reference see `02_Protocol_Reference/carplay_handshake.md`.

### Phases A–G

**Phase A — Bluetooth + iAP2 link**
- BT classic connects; an RFCOMM channel for `IAP2` is accepted (→ `/dev/rfcomm0`).
- iAP2 link bring-up: `ff 55 …` probe (e.g. `ff 55 02 00 ee 10`) → `ff 5a` link-sync. Negotiated: linkVersion 1, maxOutstandingPackets 127, maxRecvPacketLen 2048 (box) / 65535 (phone), retransmit timeout 2000 ms.

**Phase B — iAP2 identification**
- iPhone → `0x1D00 StartIdentify`.
- Adapter → `0x1D01 IdentificationInformation` — name `CarLink`, model `Magic-Car-Link-1.00`, manufacturer `Magic Tec.`; advertises `wirelessCarPlayTransportComponent` / `transportSupportsCarPlay` and supported message IDs. Carries **no resolution**.
- iPhone → `0x1D02 IdentifyAccept` → firmware `OnCarPlayPhase 100`.

**Phase C — MFi authentication over iAP2 (Bluetooth RFCOMM)**
- iPhone → `0xAA00 ReqAuthCert` → adapter → `0xAA01` 945-byte accessory certificate (PKCS#7 SignedData; cert serial `IPA_3333AA071227AA02AA0011AA003045`).
- iPhone → `0xAA02 ReqChallenge` (20-byte challenge) → adapter drives the **genuine MFi chip** (I2C bus 1, 7-bit addr 0x11) → `0xAA03` 128-byte RSA signature.
- iPhone → `0xAA05 AuthSuccess`.

**Phase D — WiFi credential handover**
- iPhone → `0x5702 ReqWifiConfig` → adapter → `0x5703 WifiConfigInfo`: `ssid`, `passphrase`, security type, **channel** (capture: `0x24` = 36).
- iPhone → `0x4E0A DeviceLanguageUpdate`, `0x4E0B DeviceTimeUpdate`, `0x4E0D WirelessCarPlayUpdateMsg`, `0x4E0E TransportNotify` (carries iPhone BT MAC + transport id).

**Phase E — iPhone joins the AP, AirPlay discovery**
- iPhone associates to `wlan0` (HostMlme assoc + EAPOL/WPA), DHCP-leases an address. The BT iAP2 link is then torn down (`kAirPlayCommand_DisableBluetooth`; `ARMiPhoneIAP2` receives SIGTERM).
- `AppleCarPlay` (`CarPlayDemoApp`, AirPlay 320.17) starts; registers the screen + HID devices.
- Adapter advertises Bonjour **`_airplay._tcp` on TCP port 5000**, browses, finds the iPhone's `_carplay-ctrl._tcp`, and connects out to it.

**Phase F — AirPlay pairing (RTSP on port 5000)**
- **3× `POST /pair-setup`** — `X-Apple-HKP: 0` (HomeKit pair-setup; SRP, M1–M6 over 3 round-trips).
- **2× `POST /pair-verify`** — `X-Apple-HKP: 2`, `X-Apple-PD: 1` (Curve25519).
- Adapter performs a **second MFi chip signature** (`MFi auth create signature`) after pair-verify — the AirPlay-layer MFi step. No separate `/fp-setup` or `/auth-setup` RTSP endpoint exists.

**Phase G — Session active**
- `AirPlay session started`; negotiated event/timing ports and `enabledFeatures` (capture: `[viewAreas]`).
- Video + audio streaming connections open; firmware phases `OnCarPlayPhase 100 → 1 → 103`.

### Notes

- **RTSP control port = 5000** (not 7000).
- **No FairPlay** — wireless CarPlay on this firmware uses HomeKit `pair-setup`/`pair-verify` plus iAP2/MFi auth; no `/fp-setup`.
- The **MFi coprocessor is a real Apple chip, exercised twice** per pairing: once in iAP2 `0xAA00`–`0xAA05` (BT), once in the AirPlay-layer `MFi auth create signature` (WiFi). See `hardware_platform.md` → "MFi Authentication Coprocessor".
- `/var/lib/lockdown/*` records are **usbmuxd USB-trust records, separate from MFi credentials** — do not conflate them with the MFi auth path.

---

## References

- Source: `carlink_native/documents/reference/Firmware/firmware_initialization.md`
- Firmware: `cpc200_ccpa_firmware_binaries/`
- Heartbeat details: `heartbeat_analysis.md`
