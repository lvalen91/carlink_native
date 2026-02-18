# CPC200-CCPA Firmware Research Documentation

**Model:** Carlinkit CPC200-CCPA (A15W) Wireless CarPlay/Android Auto Adapter
**Consolidated:** 2026-01-16
**Sources:** GM_research, carlink_native, pi-carplay session capture firmware analysis

---

## Overview

This documentation consolidates research from multiple projects focused on reverse engineering the Carlinkit CPC200-CCPA wireless CarPlay/Android Auto adapter. The research covers:

- USB protocol specification and message types
- Audio and video streaming protocols
- Wireless CarPlay authentication
- Firmware architecture and configuration
- Security analysis

**Evidence Base:** 25+ controlled capture sessions, binary analysis of 7 main executables, 40+ analysis documents

---

## Adapter Role: Protocol Tunnel

**Critical Understanding:** The CPC200-CCPA is a **protocol bridge**, not a media endpoint.

```
iPhone/Android                    Adapter                         Host App
┌──────────────┐    AirPlay/    ┌─────────────┐      USB       ┌──────────────┐
│  CarPlay UI  │───iAP2/AA────▶│   TUNNEL    │───────────────▶│   DECODER    │
│  (H.264 enc) │                │  (forward)  │                │   (policy)   │
└──────────────┘                └─────────────┘                └──────────────┘
```

**What the adapter DOES:**
- Protocol handshake and authentication
- Session establishment with phone
- Forward H.264 video verbatim (no decode, no transcode)
- Forward PCM audio verbatim
- Prepend USB headers to data streams
- Respond to keyframe requests

**What the adapter does NOT do:**
- Buffer video for quality
- Pace or smooth frames
- Correct timing or sync
- Reset decoder on errors
- Compensate for jitter
- Make any policy decisions

**All correctness decisions are the host application's responsibility.**

### Implications for Host App Development

| Adapter Provides | Host Must Handle |
|------------------|------------------|
| Raw H.264 NAL units | H.264 decoding |
| USB frame headers with PTS | Timing policy (or ignore PTS) |
| Passthrough without buffering | Jitter absorption |
| Keyframe on request (100-200ms) | Error detection and recovery |
| No quality assessment | Corruption detection and reset |

> **The adapter is neutral. If video corrupts, the host app's policy is wrong.**

---

## Hardware Summary

| Component | Specification |
|-----------|---------------|
| **Processor** | NXP i.MX6UL (ARM Cortex-A7, 32-bit) |
| **RAM** | 128MB |
| **Storage** | 16MB Flash |
| **WiFi** | Realtek RTL88x2CS (5GHz 802.11ac); hardware revisions are known to use others |
| **Bluetooth** | Realtek HCI UART (BR/EDR + BLE) |
| **Kernel** | Linux 3.14.52 |

---

## Documentation Structure

```
RE_Documention/
├── README.md                           # This file
├── 01_Firmware_Architecture/
│   ├── hardware_platform.md            # Hardware specs and constraints
│   ├── initialization.md               # Boot sequence and heartbeat
│   ├── firmware_encryption.md          # .img format and AES keys
│   ├── configuration.md                # riddleBoxCfg reference
│   └── web_interface.md                # Web UI API and settings (2025.10)
├── 02_Protocol_Reference/
│   ├── usb_protocol.md                 # USB message types and payloads
│   ├── device_identification.md        # phoneType, BoxSettings, SessionToken analysis
│   ├── audio_protocol.md               # Audio streaming and commands
│   ├── video_protocol.md               # H.264 video and keyframes
│   └── wireless_carplay.md             # WiFi, RTSP, HomeKit pairing
├── 03_Security_Analysis/
│   ├── crypto_stack.md                 # Cryptographic implementations
│   └── vulnerabilities.md              # Security findings
├── 04_Implementation/
│   └── host_app_guide.md               # Host application development
└── 05_Reference/
    ├── firmware_internals.md           # DMSDP framework, audio/video processing internals
    ├── gm_infotainment/                # GM Info 3.7 reference (if needed)
    ├── android_mediacodec/             # Android API reference (if needed)
    ├── binary_analysis/
    │   ├── key_binaries.md             # Firmware binary analysis
    │   ├── server.cgi                  # Web API binary (packed)
    │   └── upload.cgi                  # Upload handler binary (packed)
    └── web_interface_2025.10/          # Extracted web files from firmware
```

---

## Quick Reference

### USB Protocol

| Magic | Header Size | Max Payload |
|-------|-------------|-------------|
| 0x55AA55AA | 16 bytes | 1MB |

### Key Message Types

| Type | Hex | Name | Purpose |
|------|-----|------|---------|
| 1 | 0x01 | Open | Session initialization |
| 6 | 0x06 | VideoData | H.264 video frames |
| 7 | 0x07 | AudioData | PCM audio or commands |
| 8 | 0x08 | Command | Control commands |
| 25 | 0x19 | BoxSettings | JSON configuration |
| 41 | 0x29 | GnssData | GPS/GNSS to phone (NMEA) |
| 170 | 0xAA | HeartBeat | Keep-alive (2s interval) |

### Audio Types

| decode_type | Sample Rate | Purpose | Status |
|-------------|-------------|---------|--------|
| 2 | 44.1kHz Stereo | **Dual-purpose:** Commands (13 bytes) OR 44.1kHz audio | VERIFIED |
| 3 | 8kHz Mono | Phone call (narrowband) | LEGACY - firmware code exists but never observed; may be removed functionality |
| 4 | 48kHz Stereo | Standard CarPlay HD audio | VERIFIED |
| 5 | 16kHz Mono | Voice (Siri, phone calls) | VERIFIED |

**Notes:**
- decode_type=2 behavior depends on payload size - see `02_Protocol_Reference/audio_protocol.md`
- decode_type=3 (8kHz) exists in firmware code but **never observed** in captures - likely legacy code with functionality removed; no manual configuration produces 8kHz audio; modern iPhones negotiate 16kHz (wideband)
- CallQuality Web UI setting has a **firmware bug** and does not affect sample rate - see `01_Firmware_Architecture/configuration.md`

| audio_type | Channel | Direction |
|------------|---------|-----------|
| 1 | Main | Playback |
| 2 | Navigation | Playback (ducking) |
| 3 | Microphone | Capture |

---

## Critical Implementation Notes

### Heartbeat Timing (CRITICAL)

Heartbeat must start **BEFORE** sending initialization messages:

```
1. USB Reset
2. 3-second wait
3. Open USB connection
4. START HEARTBEAT IMMEDIATELY  ← Critical
5. Send initialization messages
6. Start reading loop
```

Failure to do this causes cold start failures (~11.7 seconds to disconnect).

### Audio Commands

15 verified audio commands (0x01-0x10):
- **No SIRI_STOP exists** - sessions end via OUTPUT_STOP
- **NAVI_STOP (0x08)** activates Siri mode (misleading name)
- **NAVI_COMPLETE (0x10)** signals navigation prompt finished

### Keyframe Recovery

Send Frame command (0x0C) to request IDR. Adapter responds within 100-200ms with SPS + PPS + IDR frame.

### Video Stream Characteristics (from 215K frame analysis)

| Metric | Value | Source |
|--------|-------|--------|
| Session start | 100% begin with SPS+PPS+IDR | 10/10 sessions |
| SPS/PPS bundling | Always with IDR, never standalone | 538+ IDRs |
| IDR interval | Median 2000ms (range 83-2117ms) | 538 intervals |
| Jitter std dev | 25.6ms (85% within ±40ms) | 215K frames |
| Frame sizes | IDR: 49KB avg, P: 23KB avg | 215K frames |

See `02_Protocol_Reference/video_protocol.md` for complete quantitative analysis.

---

## Source Documentation

This consolidation drew from:

| Source | Content |
|--------|---------|
| `GM_research/cpc200_research/` | Protocol captures, binary analysis, CLAUDE.md reference |
| `carlink_native/documents/` | Subsystem docs, audio/video analysis, configuration |
| `pi-carplay/firmware_binaries/` | Firmware encryption, navigation protocol, config keys |
| `cpc200_ccpa_firmware_binaries/` | Extracted firmware, unpacked binaries, boot scripts |

### Original Source Paths

```
~/Downloads/GM_research/cpc200_research/
~/Downloads/carlink_native/documents/
~/Downloads/misc/pi-carplay-4.1.3/firmware_binaries/
~/Downloads/misc/cpc200_ccpa_firmware_binaries/
```

---

## Verification Status

| Area | Status | Evidence |
|------|--------|----------|
| USB Protocol | VERIFIED | 25+ capture sessions |
| Audio Commands | VERIFIED | 21 controlled sessions |
| Video Protocol | VERIFIED | Frame timing analysis |
| Initialization | VERIFIED | Cold start testing |
| GPS/GNSS Pipeline | VERIFIED | Binary analysis + live config testing |
| Crypto Stack | DOCUMENTED | Binary analysis |

---

## Usage Notes

### For Developers

1. Start with `04_Implementation/host_app_guide.md`
2. Reference protocol docs in `02_Protocol_Reference/`
3. Check `01_Firmware_Architecture/configuration.md` for settings

### For Researchers

1. Review security findings in `03_Security_Analysis/`
2. Binary analysis details in `05_Reference/binary_analysis/`
3. Original captures available in source directories

---

## External References

- [ludwig-v/wireless-carplay-dongle-reverse-engineering](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering)
- [Pi-carplay](https://github.com/f-io/pi-carplay)

---

## Change Log

| Date | Change |
|------|--------|
| 2026-02-17 | **iPhone GPS fusion live testing:** Added iPhone-side GPS behavior documentation to host_app_guide.md — `accessoryd` → `locationd` → `CL-fusion` pipeline, best-accuracy-wins model, `numHypothesis`/`isPassthrough` interpretation, practical scenarios for vehicle GPS priority, debugging via `idevicesyslog`; updated GPS data flow diagram to show full end-to-end path including iPhone processing; corrected DashboardInfo/GNSSCapability relationship in configuration.md; added iPhone verification section + Android Auto GPS path to usb_protocol.md |
| 2026-02-18 | **Deep r2 binary analysis:** CiAP2LocationEngine full object layout (0x1F4+ bytes, flags 0x1F0-0x1F3), 3-stage GPS gating mechanism, GNSSCapability bitmask (bit 0=GPGGA, 1=GPRMC, 3=PASCD), **DashboardInfo correction** (bit 1=vehicleStatus NOT location), ARMadb-driver type 0x29 handler (strstr+file write to HU_GPS_DATA, forward as 0x22), VirtualBoxGPS NMEA parser (GPGGA/GPRMC/PASCD parsing, $GPVAI/$RMTINFO generation, speed thread), libdmsdpdvgps.so ENCRYPTED, complete iAP2 identification component table (26 components), 7 AskStartItems sub-types, r2 PLT mislabel fix (0x14e3c=strstr not dbus_bus_add_match); updated usb_protocol.md, key_binaries.md |
| 2026-02-17 | **GPS/GNSS forwarding analysis:** Added GnssData (0x29) payload format with dual delivery paths (USB direct + /tmp/gnss_info), per-binary GPS pipeline roles (ARMadb-driver → ARMiPhoneIAP2 CiAP2LocationEngine → iPhone), iAP2 Location Registration Flow disassembly, GNSSCapability=0 blocking discovery; updated usb_protocol.md, key_binaries.md, host_app_guide.md |
| 2026-01-20 | Added device_identification.md with phoneType analysis (verified: 3=CarPlay both USB/Wireless, 5=AndroidAuto USB, wifi field determines transport); verified cpuTemp is adapter temperature; verified SessionToken (0xA3) decryption using USB key with AES-128-CBC; documented decode_type=3 (8kHz) as NEVER OBSERVED; documented CallQuality→VoiceQuality firmware bug; consolidated heartbeat documentation; **Added web_interface.md** documenting 2025.10 firmware Boa web server, server.cgi API (MD5 signed), Vue.js frontend, all configurable settings via HTTP |
| 2026-01-18 | Added cpc200_ccpa_firmware_binaries source: boot scripts, timeout constants, custom init hook |
| 2026-01-16 | Initial consolidation from 4 source directories |

---

*This documentation is for research and educational purposes.*
