# GM Info 3.7 Network Architecture

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** December 2025 - February 2026

---

## Network Interfaces

| Interface | Purpose | Address |
|-----------|---------|---------|
| eth0 | Intel I211 physical (gPTP master, 1Gbps) | 192.168.1.100/24 (Y181, same as vlan5); Y175 has no IPv4 on eth0 |
| vlan4 | VIP-SoC IPC | 172.16.4.x |
| vlan5 | Vehicle network (NFSA) | 192.168.1.100 (IHU) |
| br0 | WiFi hotspot bridge (wlan1+wlan2 bridged, hostapd_bcm+dnsmasq) | — |
| wlan0-2 | Broadcom WiFi (AP/STA/P2P) | — |
| wlan1 | WiFi hotspot for CPC200 | ssid=myChevrolet 32D4 |

**MAC address:** eth0 always `02:04:00:00:01:00` (locally administered, OUI bit set).
**Net hostname:** `myChevrolet`

---

## Ethernet VLAN Detail

Broadcom BCM8953x managed switch. Switch port assignments:

| Port | Connected Node |
|------|---------------|
| 0 | RSI |
| 1 | AMP |
| 2 | CGM |
| 3 | TCP |
| 5 | Host (A11/IHU) |

### VLAN 5 — Vehicle LAN (192.168.1.x/24)

| Address | Node |
|---------|------|
| 192.168.1.100 | CSM / IHU |
| 192.168.1.101 | ADB |
| 192.168.1.102 | TCP_ETH |
| 192.168.1.103 | AMP_ETH |
| 192.168.1.106 | IPC |
| 192.168.1.107 | CGM Host |
| 192.168.1.112 | CGM_ETH |

### VLAN 4 — EOCM Network (172.16.4.x/24)

| Address | Node |
|---------|------|
| 172.16.4.12 | EOCM_HCP_SECONDARY |
| 172.16.4.13 | EOCM_HCB |
| 172.16.4.15 | EOCM_HCP_HOST |

---

## Listening Ports

| Bind Address | Port | Protocol | Purpose |
|-------------|------|----------|---------|
| 127.0.0.1 / 192.168.5.1 | 53 | UDP/TCP | DNS (dnsmasq) |
| 0.0.0.0 | 7000 | TCP | ADB (IPv4+IPv6) |
| 0.0.0.0 | 6363 | TCP | Unknown (loopback connections observed) |
| 0.0.0.0 | 49156 | TCP | Unknown |
| 192.168.1.100 | 9002 | TCP | NFSA (via IPv6-mapped IPv4) |
| 192.168.1.100 | 9010 | TCP | NFSA (via IPv6-mapped IPv4) |
| 192.168.1.100 | 9016 | TCP | NFSA (via IPv6-mapped IPv4) |

---

## VIP-SoC IPC

- **Transport:** HDLC over UART `/dev/ttyS1`
- **Channels:** 20 IPC channels (numbered 0-20)
- **Protocol:** Version 16
- **Baud:** 4104
- **PLC:** Programmable Logic Controller timers in VIP
- **MEC:** Mode/Event/Condition behavior framework

### IPC Channel Assignments

| Channels | Assignment |
|----------|-----------|
| 1-2 | PROTOKEY / Security |
| 3-5 | J6_CDD Diagnostics |
| 6-8 | PLC |
| 9-12 | Calibration |
| 13-16 | System state |
| 17-20 | Reserved |

The VIP MCU communicates with the Intel SoC via a dedicated UART link using HDLC framing. The 20 IPC channels carry power management commands, CAN messages, calibration data, and vehicle state information between the two processors.

---

## UART Devices

| Device | Owner | Purpose |
|--------|-------|---------|
| /dev/ttyACM1 | crw-rw-rw- | MCP2200 (world-writable debug) |
| /dev/ttyS0 | bluetooth:bluetooth | Bluetooth HCI |
| /dev/ttyS1 | root:root | VIP IPC (HDLC) |
| /dev/ttyS2 | root:root | — |
| /dev/ttyS3 | root:root | — |

---

## USB Identifiers

| Device | VID | PID | Notes |
|--------|-----|-----|-------|
| GM IHU (iAP) | 0x2996 | 0x0120 | iAP2 USB role |
| GM IHU (proprietary) | 0x2996 | 0x0105 | GM proprietary |
| GM IHU (hub) | 0x2996 | 0x0132 | USB hub |
| CPC200 adapter | 0x1314 | 0x1521 | manufacturer="Magic Communication Tec.", product="Auto Box" |
| dabr_udc | — | — | USB device controller for gadget mode |

### USB Gadget

- **Controller:** `sys.usb.controller=dabr_udc.0`
- **Kernel:** `CONFIG_USB_GADGET=y`
- **FunctionFS endpoints:** iAPClient, adb, mtp, ptp

The `dabr_udc.0` device controller supports USB gadget mode via FunctionFS. The kernel has `CONFIG_USB_GADGET=y` and exposes functionfs endpoints for iAPClient (iAP2 accessory role), adb, mtp, and ptp.

---

## NFSA (Network Function Service Architecture)

NFSA provides the vehicle-internal network communication layer between the IHU and other vehicle computing nodes over VLAN 5. The IHU participates as both server and client across 6 ports:

### IHU as Server (listening)

| Bind Address | Port |
|-------------|------|
| 192.168.1.100 | 9002 |
| 192.168.1.100 | 9010 |
| 192.168.1.100 | 9016 |

### IHU as Client (connects to)

| Remote Address | Port |
|---------------|------|
| 192.168.1.102 | 9005 |
| 192.168.1.102 | 9012 |
| 192.168.1.102 | 9018 |

---

## GM Cloud Endpoints

| Endpoint | Port | Purpose |
|----------|------|---------|
| vtmpub.oboservices.mobi | 443 | OTA / OMA-DM updates |
| vehicle.api.gm.com | 443 | Vehicle registration |
| vehicle.api.gm.com | 20445 | Security logging |
| vehicle.api.gm.com | 20647 | Auth / personalization |

Telematics (CGM module) operates independently of the A11 radio stack — cloud connectivity does not depend on the IHU's WiFi or cellular state.

### Update Libraries

| Library | Purpose |
|---------|---------|
| libpal_swupdate_ecu.so | ECU software update PAL |
| libpal_swupdate_hostos.so | HostOS software update PAL |
| vendor.gm.swupdate@1.0.so | GM SWUpdate HIDL service |

---

## GM Service Architecture

| Service | Details |
|---------|---------|
| Power mode | State machine: Run, Accessory, Crank, Off — managed by VIP MCU |
| License/calibration | GHS cal service, DPS AES-CMAC with server key provisioning |
| IPC | 20 channels on /dev/ttyS1, HDLC protocol v16, baud 4104 |

---

## CAN Bus

- **ECU count:** 24
- **CSM address:** 0x80
- **Gateway:** VIP MCU (RH850/P1M-E) acts as CAN gateway between vehicle bus and SoC

The VIP MCU bridges CAN messages to the Intel SoC over the HDLC IPC link, translating vehicle bus traffic into structured IPC messages for Android services. The CSM (Chassis System Module) at address 0x80 is the primary audio amplifier endpoint connected via Ethernet AVB.

---

## WiFi Configuration

The Broadcom BCM WiFi module supports three concurrent interfaces:

| Interface | Mode | Usage |
|-----------|------|-------|
| wlan0 | STA (station) | Internet connectivity (tethered or hotspot client) |
| wlan1 | AP (access point) | CPC200 wireless adapter connection (ssid=myChevrolet 32D4) |
| wlan2 | P2P | WiFi Direct for wireless projection protocols |

---

## Ethernet AVB

- **NIC:** Intel I211, 1Gbps
- **gPTP role:** Master
- **AVB streamhandler:** v3.2.7.2 (GM3/CSM)
- **Audio endpoint:** NXP TDF8532 codec on CSM → external amplifier → 4 speakers

The IHU serves as the gPTP grandmaster on the AVB network, providing time synchronization for audio stream delivery to the CSM amplifier module.
