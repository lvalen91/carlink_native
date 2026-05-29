# CPC200-CCPA Firmware Research Documentation

**Model:** Carlinkit CPC200-CCPA (A15W) Wireless CarPlay/Android Auto Adapter
**Consolidated:** 2026-01-16
**Sources:** Firmware extraction, session captures, and live RE

---

## Overview

This documentation consolidates research from multiple projects focused on reverse engineering the Carlinkit CPC200-CCPA wireless CarPlay/Android Auto adapter. The research covers:

- USB protocol specification and message types
- Audio and video streaming protocols
- Wireless CarPlay authentication
- Firmware architecture and configuration
- Security analysis

**Evidence Base:** 25+ controlled capture sessions, deep r2 binary analysis of 7 main executables + 10 shared libraries, 40+ analysis documents

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
- Forward PCM audio verbatim (playback direction)
- Process microphone audio (WebRTC AGC, AECM echo cancellation, NS noise suppression)
- Prepend USB headers to data streams
- Respond to keyframe requests

**What the adapter does NOT do:**
- Buffer video for quality
- Pace or smooth frames
- Correct timing or sync
- Reset decoder on errors
- Compensate for jitter
- Make video policy decisions

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
│   ├── heartbeat_analysis.md           # Heartbeat timing deep analysis
│   ├── firmware_encryption.md          # .img format and AES keys
│   ├── flash_layout.md                 # Flash partition layout and U-Boot
│   ├── configuration.md                # riddleBoxCfg reference + Box Code Taxonomy
│   ├── version_comparison.md           # Firmware version differences (2022-2025)
│   ├── web_interface.md                # Web UI API and settings (2025.10)
│   └── web_settings_reference.md       # Web settings quick reference
├── 02_Protocol_Reference/
│   ├── usb_protocol.md                 # USB message types and payloads
│   ├── device_identification.md        # phoneType, BoxSettings, SessionToken analysis
│   ├── audio_protocol.md               # Audio streaming and commands
│   ├── video_protocol.md               # H.264 video and keyframes
│   ├── wireless_carplay.md             # WiFi, RTSP, HomeKit pairing
│   ├── carplay_handshake.md            # CarPlay session/HomeKit pairing handshake
│   ├── iap2_message_catalog.md         # iAP2 0x5201/0x5202 RouteGuidance field map (Apple R14G17 SDK + RE)
│   ├── iap2_sdk_delta_r14g17_to_ios26.md # Hybrid changelog R14G17 (2017) → iOS 26.5 — covers post-2017 fields + capability negotiation
│   ├── carkit_ios26_master.md          # iOS 26.5 CarPlay/CarKit master capability surface — 60+ feature flags, gating model, patching recipes, 3-agent verified
│   ├── command_ids.md                  # Command ID reference (0x08 payloads)
│   ├── command_details.md              # Command binary verification details
│   └── inbound_session_sequence.md     # Observed session message ordering
├── 03_Audio_Processing/
│   ├── audio_formats.md                # Firmware audio format analysis (decodeType 1-7)
│   └── microphone_processing.md        # Mic pipeline, WebRTC, AA phone call fix
├── 03_Security_Analysis/
│   ├── crypto_stack.md                 # Cryptographic implementations
│   └── vulnerabilities.md              # Security findings
├── 04_Implementation/
│   ├── host_app_guide.md               # Host application development
│   ├── session_examples.md             # Captured packet sequences
│   ├── capture_playback.md             # Capture/playback design reference
│   └── firmware_update.md              # OTA update mechanism
└── 06_Reference/
    ├── firmware_internals.md           # DMSDP framework, iAP2 engines, internals
    ├── android_mediacodec/             # Android MediaCodec API reference
    ├── vehicle_platforms/gminfo/       # GM Info 3.7 platform specs
    └── binary_analysis/
        ├── key_binaries.md             # Firmware binary analysis
        └── config_key_analysis.md      # 106 config keys deep analysis (85 ALIVE / 12 DEAD / 9 PASS-THROUGH)
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
| 1 | 0x01 | Open | Session init — 28-byte 7×uint32-LE payload `[width,height,fps,format,packetMax,boxVersion,phoneMode]`; handler `ProceessCmdOpen` |
| 6 | 0x06 | VideoData | H.264 video frames |
| 7 | 0x07 | AudioData | PCM audio or commands |
| 8 | 0x08 | Command | Control commands |
| 25 | 0x19 | BoxSettings | JSON config — parsed by ARMadb-driver `FUN_00016c20`, 29-entry map at `0x93f90` |
| 41 | 0x29 | GnssData | GPS/GNSS to phone (NMEA) |
| 153 | 0x99 | SendFile | Flat-file + `HU_*` struct upload (staging `/tmp/uploadFileTmp`, runs as root) |
| 170 | 0xAA | HeartBeat | Keep-alive (2s interval) |

### Audio Types

| decode_type | Sample Rate | Purpose | Status |
|-------------|-------------|---------|--------|
| 2 | 44.1kHz Stereo | **Dual-purpose:** Commands (13 bytes) OR 44.1kHz audio (non-default, only when `mediaSound=0`) | VERIFIED |
| 3 | 8kHz Mono | Phone call (narrowband) | Active for Android Auto phone calls (8kHz narrowband via HFP/SCO). Not used by CarPlay (uses 16kHz). See `03_Audio_Processing/microphone_processing.md` § AA Phone Call Microphone. |
| 4 | 48kHz Stereo | Standard media audio (default for both CarPlay and Android Auto) | VERIFIED |
| 5 | 16kHz Mono | Voice (Siri, phone calls) | VERIFIED |

**Notes:**
- The adapter is transparent — it passes through whatever sample rate the phone negotiates. CarPlay and AA both default to 48kHz (decodeType=4). 44.1kHz (decodeType=2) only appears when explicitly configured via `mediaSound=0`.
- decode_type=2 behavior depends on payload size - see `02_Protocol_Reference/audio_protocol.md`
- decode_type=3 (8kHz) is active for Android Auto phone calls (HFP/SCO narrowband). Not used by CarPlay — iPhones negotiate 16kHz wideband. See `03_Audio_Processing/microphone_processing.md` § AA Phone Call Microphone — FIXED.
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
| Protocol captures + binary analysis | USB protocol, command IDs |
| Subsystem documentation | Audio/video analysis, configuration |
| Firmware extraction | Encryption, navigation protocol, config keys |
| Extracted firmware images | Unpacked binaries, boot scripts |

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
2. Binary analysis details in `06_Reference/binary_analysis/`
3. Original captures available in source directories

---

## External References

- [ludwig-v/wireless-carplay-dongle-reverse-engineering](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering)

---

## Change Log

| Date | Change |
|------|--------|
| 2026-05-21 | **Live wireless CarPlay pairing capture** (`MFi_research/capture/carplay-20260521-101016`): RTSP control port confirmed 5000 (not 7000); HomeKit `/pair-setup`×3 + `/pair-verify`×2; no FairPlay; real MFi coprocessor on I2C-1 `0x11` exercised twice/pairing; `HU_*` struct layouts verified on-device; iAP2 `0x1D01` component table captured; DuckPosition `HU_VIEWAREA_INFO` field order corrected. New doc: `02_Protocol_Reference/carplay_handshake.md`. |
| 2026-03-14 | **Comprehensive verification pass (Mar 2026):** 50+ agents cross-checked all docs against firmware binary, APK decompilation, and carlink_native source. ~40 errors fixed: decode_type=3 corrected from "vestigial" to "active for AA phone calls" (4 files); wireless_carplay.md encryption key/algorithm corrected (AES-128-CBC, `SkBRDy3gmrw1ieH0`); inbound_session_sequence.md type IDs rewritten (10+ wrong IDs); session_examples.md command labels corrected; video nav header fields renamed; firmware_update.md status codes updated to 2025.10; key_binaries.md binary sizes corrected; config_key_analysis.md classification fixes; host_app_guide.md touch format and VID/PID updated; Box Code Taxonomy and SoftwareVersion format added to configuration.md; MultiTouch/StModeChange/Camera structs and UseBTPhone added to usb_protocol.md; manufacturer frame-drop reference added to video_protocol.md; mic delay note added to host_app_guide.md; XX_Binary_Verification removed (content in canonical docs); README directory structure and adapter role updated. |
| 2026-02-18 | **ViewArea/SafeArea live verified:** `g_bSupportViewarea` is NOT set by AdvancedFeatures — it's set from `HU_VIEWAREA_INFO` file content (r2 init at ~0x16ca2). Writing `HU_VIEWAREA_INFO` (24B) + `HU_SAFEAREA_INFO` (20B) to `/etc/RiddleBoxData/` + reboot enables main screen SafeArea. Live test: 100px inset on 2400×960, CarPlay correctly inset interactive UI while wallpaper rendered full-screen (`drawUIOutsideSafeArea=true`). AdvancedFeatures corrected back to Boolean (0-1, max enforced by riddleBoxCfg), only bit 0 tested in firmware (`tst.w sb, 1`); corrected all docs. |
| 2026-02-18 | **riddleBoxCfg feature gate analysis:** AdvancedFeatures default/range documented; HudGPSSwitch factory default corrected to 0 (--info shows current value, not factory); added performance warning for AdvancedFeatures (second H.264 stream); documented 9 MiddleMan IPC interfaces (CarPlay, AA, HiCar, ICCOA, DVR, AndroidMirror, iOS, NoAirPlay); documented 15 adapter filesystem config paths; added 7 AppleCarPlay runtime global flags; updated configuration.md, firmware_internals.md, host_app_guide.md |
| 2026-02-18 | **Binary strings analysis (extended):** PBAP support confirmed (PCE+PSE roles in bluetoothDaemon SDP), hfpd provenance identified (nohands HFP 1.6, `net.sf.nohands.hfpd`), AirPlay-layer features documented (4 focus domains, app launch commands: Maps/Music/NowPlaying/Phone, haptic feedback, fake iPhone mode, limitedUI), 6 internal audio channel states discovered (Media/Navi/PhoneCall/Siri/Alert/InputConfig), media metadata format clarified (iAP2Type_PlistBinary, not parsed JSON); updated firmware_internals.md |
| 2026-02-17 | **iPhone GPS fusion live testing:** Added iPhone-side GPS behavior documentation to host_app_guide.md — `accessoryd` → `locationd` → `CL-fusion` pipeline, best-accuracy-wins model, `numHypothesis`/`isPassthrough` interpretation, practical scenarios for vehicle GPS priority, debugging via `idevicesyslog`; updated GPS data flow diagram to show full end-to-end path including iPhone processing; corrected DashboardInfo/GNSSCapability relationship in configuration.md; added iPhone verification section + Android Auto GPS path to usb_protocol.md |
| 2026-02-18 | **Deep r2 binary analysis (5 binaries):** Complete iAP2 engine field catalogs (9 engines with all fields: CallState 14+6, Communication 11, MediaPlayer 27+17, RouteGuidance/NaviJSON, Power 8, VehicelStat 3, WiFiConfig 4), 60+ iAP2 message dispatch table, HUD broadcast aliases (MediaSongName→mediaItemTitle etc.), dual data path architecture (AirPlay ‖ iAP2 parallel), CarPlay mode state machine (5 channels: screen/mainAudio/speech/phoneCall/turnByTurn), session control commands, complete audio codec table (PCM/ALAC/AAC-LC/AAC-ELD), encryption key derivation labels, 13 supported link types, core library stack (7 DMSDP libraries with roles), libboxtrans/libmanagement key exports, ARMAndroidAuto custom LZMA packer (magic 0x55225522), CiAP2LocationEngine GPS gating mechanism, GNSSCapability bitmask, DashboardInfo correction; updated key_binaries.md, firmware_internals.md |
| 2026-02-18 | **Deep r2 binary analysis (GPS):** CiAP2LocationEngine full object layout (0x1F4+ bytes, flags 0x1F0-0x1F3), 3-stage GPS gating mechanism, GNSSCapability bitmask (bit 0=GPGGA, 1=GPRMC, 3=PASCD), **DashboardInfo correction** (bit 1=vehicleStatus NOT location), ARMadb-driver type 0x29 handler (strstr+file write to HU_GPS_DATA, forward as 0x22), VirtualBoxGPS NMEA parser (GPGGA/GPRMC/PASCD parsing, $GPVAI/$RMTINFO generation, speed thread), libdmsdpdvgps.so ENCRYPTED, complete iAP2 identification component table (26 components), 7 AskStartItems sub-types, r2 PLT mislabel fix (0x14e3c=strstr not dbus_bus_add_match); updated usb_protocol.md, key_binaries.md |
| 2026-02-17 | **GPS/GNSS forwarding analysis:** Added GnssData (0x29) payload format with dual delivery paths (USB direct + /tmp/gnss_info), per-binary GPS pipeline roles (ARMadb-driver → ARMiPhoneIAP2 CiAP2LocationEngine → iPhone), iAP2 Location Registration Flow disassembly, GNSSCapability=0 blocking discovery; updated usb_protocol.md, key_binaries.md, host_app_guide.md |
| 2026-01-20 | Added device_identification.md with phoneType analysis (verified: 3=CarPlay both USB/Wireless, 5=AndroidAuto USB, wifi field determines transport); verified cpuTemp is adapter temperature; verified SessionToken (0xA3) decryption using USB key with AES-128-CBC; documented CallQuality→VoiceQuality firmware bug; consolidated heartbeat documentation; **Added web_interface.md** documenting 2025.10 firmware Boa web server, server.cgi API (MD5 signed), Vue.js frontend, all configurable settings via HTTP |
| 2026-01-18 | Added cpc200_ccpa_firmware_binaries source: boot scripts, timeout constants, custom init hook |
| 2026-01-16 | Initial consolidation from 4 source directories |

---

*This documentation is for research and educational purposes.*
