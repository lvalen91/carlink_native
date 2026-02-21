# GM Infotainment Video System Documentation

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** December 2025 - February 2026

---

## Document Index

| Document | Description |
|----------|-------------|
| [video_codecs.md](video_codecs.md) | Complete video codec specifications (H.264, H.265, VP8, VP9, AV1, etc.) |
| [hardware_rendering.md](hardware_rendering.md) | GPU, OpenGL, Vulkan, and hardware rendering pipeline |
| [software_rendering.md](software_rendering.md) | CPU-based codec and rendering specifications |
| [display_subsystem.md](display_subsystem.md) | Display panel, SurfaceFlinger, and composition details |
| **[carplay_video_pipeline.md](carplay_video_pipeline.md)** | **CarPlay/AirPlay video processing pipeline (CINEMO framework)** |
| **[h264_nal_processing.md](h264_nal_processing.md)** | **H.264 NAL unit processing and frame handling** |
| **[cinemo_nme_framework.md](cinemo_nme_framework.md)** | **CINEMO/NME multimedia framework architecture** |
| **[pts_timing_strategies.md](pts_timing_strategies.md)** | **PTS timing: Source extraction vs Synthetic monotonic (pros/cons)** |
| [../projection/carplay_vs_android_auto.md](../projection/carplay_vs_android_auto.md) | CarPlay vs Android Auto video/audio comparison |

---

## Quick Reference

### Hardware Summary

| Component | Specification |
|-----------|---------------|
| CPU | Intel IoT CPU 1.0, 4 cores @ 1.88 GHz |
| GPU | Intel HD Graphics 505 (Apollo Lake) |
| Display | 2400x960 @ 60Hz (DD134IA-01B) |
| OpenGL ES | 3.2 (Mesa 21.1.5) |
| Vulkan | 1.0.64 (exists but NOT used at runtime — GLES backend only) |

### Video Capabilities at a Glance

| Capability | Hardware | Software |
|------------|----------|----------|
| H.264 Decode | 4K60 @ 40Mbps | 4K @ 48Mbps |
| H.265 Decode | 4K60 @ 40Mbps | 4K @ 10Mbps |
| VP8 Decode | 4K60 @ 40Mbps | 2K @ 40Mbps |
| VP9 Decode | 4K60 @ 40Mbps | 2K @ 40Mbps |
| AV1 Decode | Not Available | 2K @ 120Mbps |
| MPEG-2 Decode | Supported | Not Available |
| H.264 Encode | 4K60 @ 40Mbps | 2K @ 12Mbps |
| H.265 Encode | 4K60 @ 40Mbps | 512p @ 4Mbps |
| DRM/Secure | H.264, H.265 | N/A |

### CarPlay Video Pipeline (Native)

**Key Discovery:** GM Native CarPlay uses **CINEMO NVDEC software decoder**, not Intel hardware:

```
Framework: CINEMO (Harman/Samsung NME)
Protocol: AirPlay 320.17.8
Decoder: libNmeVideoSW.so (NVDEC H.264)
Library: libNmeCarPlay.so (1.0 MB)
Transport: USB NCM + IPv6 (carplay.sh)
```

See [carplay_video_pipeline.md](carplay_video_pipeline.md) for full analysis.

### Android Auto Video Pipeline

**Key Difference:** Android Auto uses **Intel hardware decoder**, not CINEMO:

```
Framework: Standard Android AOSP
Protocol: Android Auto Protocol (AAP)
Decoder: OMX.Intel.hw_vd.h264 (hardware)
Transport: USB AOA, WiFi
```

See [../projection_comparison.md](../projection_comparison.md) for detailed comparison.

### Recommended Settings for Third-Party Apps

```
Codec: H.264 (video/avc)
Decoder: OMX.Intel.hw_vd.h264
Resolution: 1920x1080 or 2400x960
Frame Rate: 30 fps (matches GM CarPlay behavior; 60 supported by HW)
Bitrate: 5-15 Mbps
Profile: Main or High (Level 4.0-4.1)
Color: NV12 (YUV420SemiPlanar)
```

---

## Key Findings

### Strengths
- Full 4K60 hardware decode/encode for H.264, H.265, VP8, VP9
- Dedicated Intel VPU for video processing
- DRM secure playback support
- Triple-buffered display for smooth rendering
- OpenGL ES 3.2 with geometry/tessellation shaders

### Limitations
- No AV1 hardware decode (software only)
- No HDR10/HLG/Dolby Vision support
- No wide color gamut display
- Single 60Hz display mode (no VRR)
- Software decode at 1080p achieves only 11-32 fps
- Native CarPlay uses software decoder (AirPlay timing requirements)

### Recommendations
1. Always use hardware codecs (`OMX.Intel.*`) for real-time playback
2. Prefer H.264 for maximum compatibility
3. Use SurfaceView for video to enable HWC overlay
4. Target 1080p or native 2400x960; use 30fps for projection (matches GM CarPlay)
5. Use NV12 color format for hardware decode path

---

## System Properties Reference

```properties
ro.board.platform=broxton
ro.hardware.gralloc=broxton
ro.hardware.hwcomposer=broxton
# Note: actual HWC is `iahwcomposer` (Intel Automotive HWC 2.1), not generic hwcomposer.broxton
ro.hardware.vulkan=broxton
ro.opengles.version=196610
ro.hardware.type=automotive
```

---

## Data Sources

All specifications obtained from GM AAOS research data:

**ADB Enumeration (`/analysis/adb_Y181/`):**
- `dumpsys SurfaceFlinger`
- `dumpsys display`
- `dumpsys media.player`
- `dumpsys gpu`
- Process list, service list, logcat

**Extracted Partitions (`/extracted_partitions/`):**
- `/vendor/etc/media_codecs.xml`
- `/system/lib64/libNme*.so` - NME libraries (binary analysis)
- `/system/app/GMCarPlaySrc/GMCarPlay.apk`
- `/system/bin/carplay.sh`

**Binary Analysis:**
- `strings`, `readelf`, `nm` on NME libraries
- APK structure analysis

**Source:** `/Users/zeno/Downloads/misc/GM_research/gm_aaos/`
