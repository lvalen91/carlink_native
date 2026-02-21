# GM Infotainment System Media Codec Specifications

**Device Platform:** Intel-based Android Automotive
**Research Date:** December 7, 2025
**Source:** `/system/etc/media_codecs.xml`, `/system/etc/media_codecs_google_video.xml`, `/system/etc/media_codecs_google_audio.xml`

---

## Overview

This document details the media rendering capabilities of the GM infotainment unit. The device features Intel hardware-accelerated video codecs with full 4K60 support, complemented by Google software codecs for broader format compatibility.

---

## Video Codecs

### Hardware Decoders (Intel OMX)

These are hardware-accelerated decoders providing optimal performance and power efficiency.

| Codec Name | MIME Type | Min Resolution | Max Resolution | Max Bitrate | Features |
|------------|-----------|----------------|----------------|-------------|----------|
| `OMX.Intel.hw_vd.h264` | `video/avc` | 64x64 | 3840x2160 | 40 Mbps | Adaptive playback, 60fps @ 4K |
| `OMX.Intel.hw_vd.h264.secure` | `video/avc` | 64x64 | 3840x2160 | 40 Mbps | Adaptive playback, Secure playback (DRM) |
| `OMX.Intel.hw_vd.h265` | `video/hevc` | 64x64 | 3840x2160 | 40 Mbps | Adaptive playback, 60fps @ 4K |
| `OMX.Intel.hw_vd.h265.secure` | `video/hevc` | 64x64 | 3840x2160 | 40 Mbps | Adaptive playback, Secure playback (DRM) |
| `OMX.Intel.hw_vd.vp8` | `video/x-vnd.on2.vp8` | 64x64 | 3840x2160 | 40 Mbps | Adaptive playback, 60fps @ 4K |
| `OMX.Intel.hw_vd.vp9` | `video/x-vnd.on2.vp9` | 64x64 | 3840x2160 | 40 Mbps | Adaptive playback, 60fps @ 4K |
| `OMX.Intel.hw_vd.vc1` | `video/x-ms-wmv` | 64x64 | 3840x2160 | 40 Mbps | Adaptive playback, 60fps @ 4K |

**Common Hardware Decoder Specifications:**
- Block size: 16x16 macroblocks
- Alignment: 2x2 pixels
- Blocks per second: 1 - 972,000
- Performance point: 3840x2160 @ 60fps

### Hardware Encoders (Intel OMX)

| Codec Name | MIME Type | Min Resolution | Max Resolution | Max Bitrate |
|------------|-----------|----------------|----------------|-------------|
| `OMX.Intel.hw_ve.h264` | `video/avc` | 176x144 | 3840x2160 | 40 Mbps |
| `OMX.Intel.hw_ve.h265` | `video/hevc` | 64x64 | 3840x2160 | 40 Mbps |

### Software Decoders (Google OMX)

Fallback software decoders for compatibility:

| Codec Name | MIME Type | Max Resolution | Max Bitrate | Profile/Level |
|------------|-----------|----------------|-------------|---------------|
| `OMX.google.h264.decoder` | `video/avc` | 4080x4080 | 48 Mbps | ProfileHigh : Level52 |
| `OMX.google.hevc.decoder` | `video/hevc` | 4096x4096 | 10 Mbps | ProfileMain : MainTierLevel51 |
| `OMX.google.vp8.decoder` | `video/x-vnd.on2.vp8` | 2048x2048 | 40 Mbps | - |
| `OMX.google.vp9.decoder` | `video/x-vnd.on2.vp9` | 2048x2048 | 40 Mbps | - |
| `OMX.google.mpeg4.decoder` | `video/mp4v-es` | 2048x2048 | 384 Kbps | ProfileSimple : Level3 |
| `OMX.google.h263.decoder` | `video/3gpp` | 352x288 | 384 Kbps | ProfileBaseline : Level30/45 |
| `c2.android.av1.decoder` | `video/av01` | 2048x2048 | 120 Mbps | Software only (!slow-cpu variant) |

### Software Encoders (Google OMX)

| Codec Name | MIME Type | Max Resolution | Max Bitrate | Features |
|------------|-----------|----------------|-------------|----------|
| `OMX.google.h264.encoder` | `video/avc` | 2048x2048 | 12 Mbps | Intra-refresh |
| `OMX.google.vp8.encoder` | `video/x-vnd.on2.vp8` | 2048x2048 | 40 Mbps | VBR, CBR |
| `OMX.google.vp9.encoder` | `video/x-vnd.on2.vp9` | 2048x2048 | 40 Mbps | VBR, CBR |
| `OMX.google.mpeg4.encoder` | `video/mp4v-es` | 176x144 | 64 Kbps | ProfileCore : Level2 |
| `OMX.google.h263.encoder` | `video/3gpp` | 176x144 | 128 Kbps | ProfileBaseline : Level45 |

---

## Audio Codecs

### Audio Decoders

| Codec Name | MIME Type | Max Channels | Sample Rates (Hz) | Bitrate Range |
|------------|-----------|--------------|-------------------|---------------|
| `OMX.google.aac.decoder` | `audio/mp4a-latm` | 8 | 7350, 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000 | 8-960 Kbps |
| `OMX.google.mp3.decoder` | `audio/mpeg` | 2 | 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000 | 8-320 Kbps |
| `OMX.google.opus.decoder` | `audio/opus` | 8 | 48000 | 6-510 Kbps |
| `OMX.google.vorbis.decoder` | `audio/vorbis` | 8 | 8000-96000 | 32-500 Kbps |
| `OMX.google.flac.decoder` | `audio/flac` | 8 | 1-655350 | 1-21000 Kbps |
| `OMX.google.amrnb.decoder` | `audio/3gpp` | 1 | 8000 | 4.75-12.2 Kbps |
| `OMX.google.amrwb.decoder` | `audio/amr-wb` | 1 | 16000 | 6.6-23.85 Kbps |
| `OMX.google.g711.alaw.decoder` | `audio/g711-alaw` | 1 | 8000-48000 | 64 Kbps |
| `OMX.google.g711.mlaw.decoder` | `audio/g711-mlaw` | 1 | 8000-48000 | 64 Kbps |
| `c2.android.g711.alaw.decoder` | `audio/g711-alaw` | 6 | 8000-48000 | 64 Kbps |
| `c2.android.g711.mlaw.decoder` | `audio/g711-mlaw` | 6 | 8000-48000 | 64 Kbps |
| `OMX.google.raw.decoder` | `audio/raw` | 8 | 8000-192000 | 1-10000 Kbps |

### Audio Encoders

| Codec Name | MIME Type | Max Channels | Sample Rates (Hz) | Bitrate Range | Modes |
|------------|-----------|--------------|-------------------|---------------|-------|
| `OMX.google.aac.encoder` | `audio/mp4a-latm` | 6 | 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000 | 8-960 Kbps | - |
| `c2.android.opus.encoder` | `audio/opus` | 2 | 8000, 12000, 16000, 24000, 48000 | 0.5-512 Kbps | CBR, VBR (complexity 0-10) |
| `OMX.google.amrnb.encoder` | `audio/3gpp` | 1 | 8000 | 4.75-12.2 Kbps | CBR |
| `OMX.google.amrwb.encoder` | `audio/amr-wb` | 1 | 16000 | 6.6-23.85 Kbps | CBR |
| `OMX.google.flac.encoder` | `audio/flac` | 2 | 1-655350 | 1-21000 Kbps | CQ (complexity 0-8, default 5) |

---

## Key Specifications Summary

### Video Rendering Capabilities

| Capability | Specification |
|------------|---------------|
| Maximum Resolution | 3840x2160 (4K UHD) |
| Maximum Frame Rate | 60 fps @ 4K |
| Maximum Bitrate | 40 Mbps |
| Hardware Decode Formats | H.264, H.265/HEVC, VP8, VP9, VC1/WMV |
| Hardware Encode Formats | H.264, H.265/HEVC |
| DRM Support | Secure playback for H.264 and H.265 |
| Adaptive Streaming | Supported on all hardware decoders |

### Audio Rendering Capabilities

| Capability | Specification |
|------------|---------------|
| Maximum Channels | 8 (7.1 surround) |
| Maximum Sample Rate | 192 kHz (PCM), 96 kHz (Vorbis) |
| Supported Formats | AAC, MP3, Opus, Vorbis, FLAC, AMR-NB/WB, G.711, PCM |
| Encoding Formats | AAC, Opus, AMR-NB/WB, FLAC |

---

## Codec Priority Order

The Android MediaCodec framework will prioritize hardware codecs over software codecs when available:

1. **Intel Hardware Codecs** (OMX.Intel.*) - Preferred for performance
2. **Codec2 Software Codecs** (c2.android.*) - Android 12 Codec2 framework implementations
3. **Google OMX Software Codecs** (OMX.google.*) - Legacy fallback for compatibility

---

## CarPlay/Android Auto Compatibility

For CarPlay and Android Auto video projection:

| Protocol | Recommended Codec | Configuration |
|----------|-------------------|---------------|
| CarPlay | H.264 (video/avc) | Hardware decode via `OMX.Intel.hw_vd.h264` |
| Android Auto | H.264 (video/avc) | Hardware decode via `OMX.Intel.hw_vd.h264` |

**Optimal Settings for Projection:**
- Codec: H.264/AVC
- Max Resolution: 1920x1080 (1080p) or 1280x720 (720p)
- Frame Rate: 30-60 fps
- Bitrate: 5-15 Mbps
- Profile: Baseline or Main

---

## System Settings

```xml
<Settings>
    <Setting name="max-video-encoder-input-buffers" value="9" />
</Settings>
```

---

## Codec2 Software Decoder Performance

Measured frame rates for Codec2 software decoders on the Atom x7-A3960. These are CPU-only (no HW acceleration) and are relevant when the HW decoder is unavailable or for formats without HW support (e.g., AV1).

**c2.android.avc.decoder (H.264 SW):**

| Resolution | FPS Range |
|------------|-----------|
| 320x240 | 150-180 |
| 720x480 | 60-80 |
| 1280x720 | 25-30 |
| 1920x1080 | 11-13 |

**c2.android.hevc.decoder (H.265 SW):**

| Resolution | FPS Range |
|------------|-----------|
| 352x288 | 170-200 |
| 640x360 | 95-115 |
| 1280x720 | 43-53 |
| 1920x1080 | 27-32 |

---

## Hardware Encoder Performance

Measured frame rates for Intel hardware encoders.

**OMX.Intel.hw_ve.h264:**

| Resolution | FPS Range |
|------------|-----------|
| 320x240 | 260-300 |
| 720x480 | 140-160 |
| 1280x720 | 75-110 |
| 1920x1080 | 50-55 |

**OMX.Intel.hw_ve.h265:**

| Resolution | FPS Range |
|------------|-----------|
| 320x240 | 100-180 |
| 720x480 | 120-160 |
| 1280x720 | 70-90 |
| 1920x1080 | 35-45 |
| 3840x2160 | 10-14 |

---

## Notes

1. All hardware decoders support **adaptive playback**, enabling seamless resolution switching during streaming
2. **Secure playback** variants (`.secure` suffix) are available for DRM-protected content (H.264 and H.265)
3. The Intel hardware platform provides excellent 4K performance with dedicated video encode/decode engines
4. Software codecs provide broader format support but with higher CPU usage and power consumption
5. G.711 codecs (A-law and u-law) are available for telephony audio applications — OMX implementations support 1 channel only; Codec2 (`c2.android`) variants support up to 6 channels
6. All Intel HW decoder entries in the vendor `media_codecs.xml` use the comment "ProfileBaseline : Level51" even for H.265/VC-1 decoders — this is a copy-paste error in the vendor config and does not reflect actual profile/level support
7. AV1 software decoding (`c2.android.av1.decoder`) is available but impractical above 720p on this CPU — expect less than 30fps at 1080p
