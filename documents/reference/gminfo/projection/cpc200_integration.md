# CPC200-CCPA Adapter Integration with GM Info 3.7

**Device:** GM Info 3.7 (gminfo37)
**Adapter:** CPC200-CCPA (Carlinkit A15W)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** February 2026
**Evidence:** 241MB logcat, 45MB CPC200 firmware logs, 25+ capture sessions

---

## Adapter Hardware

- Firmware: 2025.10.15.1127
- Kernel: linux-3.0.15 (iMX6UL)
- BT/WiFi chip: 0x9159
- WiFi chip firmware: SDIW416---16.92.21.p119.11-MXM5X16437.p20-GPL-(FP92)
- WiFi binary: nxp/sdioiw416_wlan_v0.bin (425,896 bytes)
- BT firmware: nxp/uartiw416_bt_v0.bin
- WiFi channel 161 (5.8GHz), MAC 00:E0:4C:91:B0:DF
- No onboard microphone ("Box No Mic!")
- USB VID 0x1314, PID 0x1521
- manufacturer="Magic Communication Tec.", product="Auto Box"
- AirPlay version: 320.17
- Web server: Boa/0.94.101wk
- Manufacture date: 2025-02-25 (MFD from box JSON)
- Box type: YA, Product type: A15W, HiCar support: yes
- hwVersion: YMA0-WN16-0003
- UUID: 692173ca1d16c1d7767bb8ecfefd6672
- **Security note:** WiFi password "12345678" stored and logged in plaintext

## Connection Sequence (logcat-verified)

1. USB enumeration: CPC200 appears as VID 0x1314 / PID 0x1521
2. CarLink app detects adapter, starts USB communication
3. CPC200 establishes WiFi hotspot, phone connects via BT+WiFi
4. AirPlay session established between phone and CPC200
5. CPC200 bridges video/audio to GM IHU via USB
6. GM IHU creates MediaCodec decoder for H.264 stream

## Phone Identity (from logcat)

- iPhone 18,4, iOS 26.4 beta (build 23E5207q)
- CarPlay source version: 940.19.1

## Video Pipeline

```
iPhone → AirPlay → CPC200 (H.264 passthrough + 20B header)
  → USB bulk transfer → CarLink app
  → MediaCodec (OMX.Intel.hw_vd.h264, color=256/ARGB)
  → SurfaceView → SurfaceFlinger → Display
```

Key specs:

- H.264 High Profile (100) Level 5.0
- Resolution: 2400x960 @ 30fps
- Bitrate: 1.2-5.3 Mbps adaptive
- CSD caching per device+resolution: SPS=25B, PPS=8B
- First packet to first decode: ~248ms
- Steady state: 30fps, zero drops, ~30ms decode latency
- No Codec2 fallback available. 12 c2.android.* codecs missing from system. ONLY OMX.Intel.hw_* codecs available.
- "Cannot find role for decoder of type video/x-ms-wmv" (VC-1 role mismatch at boot)

## CPC200 Video Header (20 bytes)

| Offset | Size | Field |
|--------|------|-------|
| 0 | 4B LE | Width |
| 4 | 4B LE | Height |
| 8 | 4B | encoderState |
| 12 | 4B LE | PTS (milliseconds) |
| 16 | 4B | Flags |

Source PTS recommended (convert ms to us for MediaCodec). Synthetic monotonic as fallback.
Frame drop detection: gap > 50ms. IDR request on gap > 500ms.

## Audio Pipeline

```
iPhone → AirPlay → CPC200 (AAC decode → PCM)
  → USB bulk transfer → CarLink app
  → AudioTrack (USAGE_MEDIA) → bus0_media_out
  → AudioFlinger → Harman HAL → PulseAudio → AVB → Speakers
```

- Media: AAC-LC 48kHz stereo (type 102, audioFormat 8388608)
- Siri: AAC-ELD (aot=39) 16kHz mono, 480 frames/packet
- No mic: CPC200 reports "Box No Mic!" — uses IHU mic for Siri via Harman AEC/NS/AGC

## CarLink Known Bugs

- **MediaBrowserService session token crash** (2 occurrences): `IllegalStateException: The session token has already been set` at `CarlinkMediaBrowserService.updateSessionToken(CarlinkMediaBrowserService.kt:84)` called from `CarlinkManager.initialize(CarlinkManager.kt:420)`. Root cause: double `setSessionToken()` during rapid reconnection. CarLink restarts; subsequent connections succeed.

## Connection Reliability

- 155 attempts, 147 successful (94.8%)
- ~4s connect time
- AirPlay video latency: 75ms
- CPU temp: 31-33°C during streaming

### Thread Architecture During Streaming

| Thread | Priority | Nice | CPU% |
|--------|----------|------|------|
| AudioPlayback | 1 (RT) | -19 | 3.8 |
| USB-ReadLoop | 10 (RT) | -10 | 5.7 |
| H264-Feeder | 16 | -4 | 3.8 |
| MediaCodec_loop | 10 (RT) | -10 | 1.9 |
| CodecLooper | 4 | -16 | 1.9 |

- Process memory: 58MB PSS, 178MB RSS. OOM score adj: 0 (foreground). Total threads: 33-39.
- AudioFlinger write latency (bus0_media_out): avg 69.6ms, std 3.0ms, min 57.4ms, max 118.5ms.
- System CPU during streaming: system_server 51%, pulseaudio 21%, surfaceflinger 16%, avb_streamhandler 14%.

## Session Data

- DHCP: 192.168.43.x (pre-2024.09 firmware behavior)
- WiFi: channel 161 (5.8GHz)
- MFi IC: clone/compatible, fallback address works
- Unrecovered AirPlay packets during Siri to media transitions (known issue)
- CarLink app memory: PSS 58MB, RSS 178MB

## Key Difference from Native CarPlay

| Aspect | Native CarPlay | CarPlay via CPC200 |
|--------|---------------|-------------------|
| Video Decoder | Software (CINEMO/NVDEC) | **Hardware** (OMX.Intel.hw_vd.h264) |
| Resolution | 1416x842 (windowed) | **2400x960** (fullscreen) |
| FPS | 30 | 30 |
| Video Latency | ~20-40ms decode | **~30ms** decode + 75ms AirPlay |
| Audio Path | CINEMO (libNmeAudioAAC.so) | Standard AudioTrack |
| Protocol | AirPlay direct to IHU | AirPlay to CPC200, USB to IHU |
| Wireless | WiFi to IHU | WiFi to CPC200, USB to IHU |

## Data Sources

- Logcat: `/Volumes/POTATO/logcat/20260219/logcat_20260219_102907.log` (241MB)
- CPC200 logs: `/Volumes/POTATO/cpc200/` (45MB, 20 sessions)
- Metrics: `/Volumes/POTATO/metrics/` (4.7MB, 30 captures)
