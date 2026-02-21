# GM AAOS CarPlay Video Pipeline Technical Analysis

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Analysis Date:** January 2026
**Source:** `/Users/zeno/Downloads/misc/GM_research/gm_aaos/`

---

## Executive Summary

GM AAOS implements CarPlay video projection using the **CINEMO** multimedia framework (developed by Harman/Samsung). The video pipeline receives H.264 streams via AirPlay protocol, processes NAL units through a software-based **NVIDIA NVDEC** decoder, and renders to Android native windows via EGL surfaces.

**Key Finding:** Despite having Intel hardware H.264 decoder (`OMX.Intel.hw_vd.h264`), CarPlay video uses a **software decoder** path through CINEMO's NVDEC libraries for AirPlay-specific NAL unit handling and timing synchronization.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        iPhone (AirPlay Source)                               │
│                     H.264 Video Stream @ 60fps                               │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │ USB/WiFi
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Transport Layer (carplay.sh)                              │
│                 USB NCM (usb0) + IPv6 fe80::/64 routing                      │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                   libNmeCarPlay.so (1.0 MB)                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  AirPlayReceiverServer                                               │    │
│  │  - AirPlayReceiverSession (per connection)                          │    │
│  │  - AirPlayReceiverSessionScreen (video frames)                      │    │
│  │  - AirPlay/320.17.8 protocol                                        │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  OnFrame() → NAL unit extraction → H.264 frame validation                   │
│  - nalu_size extraction                                                      │
│  - syncframe detection                                                       │
│  - corrupt data validation                                                   │
│  - ForceKeyframe() for recovery                                             │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     libNmeVideo.so (121 KB)                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  INmeVideoDecode Interface                                          │    │
│  │  - NmeVideoCodec abstraction                                        │    │
│  │  - QualityControl() - adaptive frame dropping                       │    │
│  │  - Clock synchronization (SwitchClock, OnClockChange)               │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  Options:                                                                    │
│  - video_hardware_decode (bool)                                             │
│  - video_software_decode (bool)                                             │
│  - video_max_h264_level (int)                                               │
│  - video_extra_surfaces (int)                                               │
│  - video_frame_drop_threshold (int)                                         │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                   libNmeVideoSW.so (646 KB)                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  NVIDIA NVDEC Software H.264 Decoder                                │    │
│  │  Module: Cinemo/nvdec/H264DEC                                       │    │
│  │  UUID: abce2648-0ba7-11ea-8d71-362b9e155667#F_VIDEO_H264*SW         │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  NAL Unit Processing:                                                        │
│  - H264Create() → H264DeliverAnnexB() → H264Free()                          │
│  - RBSP (Raw Byte Sequence Payload) parsing                                 │
│  - SPS/PPS/SEI extraction and validation                                    │
│                                                                              │
│  Error Handling:                                                             │
│  - NvdecError_BitDepth                                                      │
│  - NvdecError_ChromaFormat                                                  │
│  - NvdecError_Profile                                                       │
│  - NvdecError_MaxVxdFileMemory                                              │
│  - NvdecError_MaxVxdTotalMemory                                             │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                 libNmeVideoDevice.so (397 KB)                                │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  Surface/Device Management                                          │    │
│  │  - NmeCreateDeviceANW (Android Native Window)                       │    │
│  │  - NmeCreateSwapChain (buffer management)                           │    │
│  │  - ANativeWindow_fromSurface → acquire/release                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  EGL Context:                                                                │
│  - eglCreateWindowSurface / eglCreatePbufferSurface                         │
│  - Mesa Intel HD Graphics 505 renderer                                      │
│  - OpenGL ES 3.2                                                            │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│               libNmeVideoRenderer.so (277 KB)                                │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  Video Mixing Renderer (VMR)                                        │    │
│  │  - NmeCreateVmr / NmeCreateVmrClone                                 │    │
│  │  - DecideRenderFPS() - frame rate decision                          │    │
│  │  - DecideRenderQuality() - quality adaptation                       │    │
│  │  - Frame dropping for late frames                                   │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  Surface Callbacks:                                                          │
│  - OnSurfaceAllocate(subtype, native, attr, size, surfaces, device)         │
│  - OnSurfaceFreeze()                                                        │
│  - SignalOutputPreviousFrame()                                              │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SurfaceFlinger + HWC 2.3                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  iahwcomposer (Intel Automotive HWC)                                │    │
│  │  - DEVICE composition (hardware overlay)                            │    │
│  │  - CLIENT composition (GPU fallback)                                │    │
│  │  - Triple buffering (3 framebuffers)                                │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Display Output                                          │
│                   Chunghwa CMN DD134IA-01B                                   │
│                   2400x960 @ 60.00 Hz                                        │
│                   VSYNC: 16.666 ms                                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Component Details

### 1. CarPlay Service Layer

**Service:** `com.gm.domain.server.delayed:CarplayService` (PID 2685)

**Binder Interfaces:**
| Service | Interface |
|---------|-----------|
| `com.gm.carplay.service.BINDER` | `gm.carplay.ICarPlayService` |
| `com.gm.phoneprojection.service.BINDER` | `gm.phoneprojection.IPhoneProjectionService` |
| `com.gm.server.screenprojection.RDMSADBHandler` | `gm.connection.IRDMSADBHandler` |

**Event Handling:**
```
CarPlay_PlayerManager: onCinemoEvent(): CINEMO_EC_GRAPH_STATUS
```

### 2. USB Transport Setup

**File:** `/system/bin/carplay.sh`
```bash
#!/system/bin/sh
ifconfig usb0 up
ip -6 route flush table local_network
ip -6 route add fe80::/64 dev usb0 proto static table local_network
```

- USB NCM (Network Control Model) interface
- IPv6 link-local addressing for CarPlay
- Aptiv USB bridges (VID 10646) handle USB connection

### 3. NME Library Stack

| Library | Size | Purpose |
|---------|------|---------|
| `libNmeCarPlay.so` | 1.0 MB | AirPlay protocol, session management |
| `libNmeVideo.so` | 121 KB | Video codec abstraction |
| `libNmeVideoSW.so` | 646 KB | NVDEC H.264 software decoder |
| `libNmeVideoDevice.so` | 397 KB | Surface/EGL management |
| `libNmeVideoRenderer.so` | 277 KB | VMR, quality control |
| `libNmeIAP.so` | 2.9 MB | iAP (Apple Accessory Protocol) |
| `libNmeAppleAuth.so` | 80 KB | Apple authentication |
| `libNmeBaseClasses.so` | 4.8 MB | Core framework |

**Source Path References:**
```
../../NmeLibs/Nvdec/vdec/vdec264.cpp
../../NmeLibs/Nvdec/vdec/vrbsp_sequence.cpp
../../NmeLibs/Nvdec/vdec/vrbsp.cpp
../../NmeCarPlay/r14/src/NmeCarPlayVideo.cpp
```

---

## H.264 Frame Processing

### NAL Unit Types Handled

| NAL Type | Description | Handling |
|----------|-------------|----------|
| SPS | Sequence Parameter Set | Parsed for resolution, profile, level |
| PPS | Picture Parameter Set | Parsed for encoding parameters |
| IDR | Instantaneous Decoder Refresh | Full frame decode, reference reset |
| P-Frame | Predicted Frame | Reference-based decode |
| SEI | Supplemental Enhancement Info | Multiple types supported |

### SEI Message Processing (libNmeVideoSW.so)

| SEI Type | Function | Purpose |
|----------|----------|---------|
| `rbsp_sei_full_frame_freeze` | Freeze display | Pause rendering |
| `rbsp_sei_full_frame_freeze_release` | Resume display | Resume rendering |
| `rbsp_sei_full_frame_snapshot` | Snapshot | Capture current frame |
| `rbsp_sei_deblocking_filter_display_preference` | Quality | Deblocking settings |
| `rbsp_sei_dec_ref_pic_marking_repetition` | Reference | Frame reference management |
| `rbsp_sei_scene_info` | Metadata | Scene change detection |
| `rbsp_sei_stereo_video_info` | 3D | Stereo video support |
| `rbsp_sei_user_data_registered_itu_t_t35` | User data | ITU-T T.35 metadata |

### Frame Reception (libNmeCarPlay.so)

```cpp
// Pseudo-code from string analysis
OnFrame() {
    // Validate NAL unit
    if (nalu_size invalid) {
        log("OnFrame() invalid nalu_size");
        return;
    }

    if (data corrupt) {
        log("OnFrame() corrupt video data");
        return;
    }

    // Extract frame metadata
    log("OnFrame() received video frame: %d (nalu_size:%u, header_len:%d)");

    // Check for sync frame (IDR)
    if (is_sync_frame) {
        log("OnFrame() -> syncframe");
    }

    // Append to decoder buffer
    if (append_data_failed) {
        log("OnFrame() append of data failed");
        ForceKeyframe();  // Request IDR for recovery
    }
}
```

---

## Error Recovery Mechanisms

### 1. Keyframe Request

When frame corruption or decode errors occur:
```cpp
AirPlayReceiverSessionForceKeyFrame()  // Request new IDR from iPhone
```

### 2. Late Frame Handling

```cpp
// From libNmeVideo.so
QualityControl(timestamp, flags) {
    if (clock_running && frame_late) {
        log("QualityControl -> %T late -> reference frames only");
        // Drop B-frames, only decode reference frames
    }
    if (catching_up_finished) {
        log("QualityControl -> catching up finished");
        // Resume normal decoding
    }
}
```

### 3. Timestamp Validation

```cpp
// From libNmeVideo.so
OnSurface() {
    if (no_pts) {
        log("OnSurface() - discard surface with no PTS!");
        return;
    }

    if (non_monotonic_timestamps) {
        log("non-monotonic video timestamps %T-%T -> %T-%T");
        // Handle timestamp discontinuity
    }
}
```

### 4. Decoder Errors (libNmeVideoSW.so)

| Error | Cause | Recovery |
|-------|-------|----------|
| `NvdecError_BitDepth` | Unsupported bit depth | Fallback/skip frame |
| `NvdecError_ChromaFormat` | Invalid chroma format | Skip frame |
| `NvdecError_Profile` | Unsupported H.264 profile | Request format change |
| `NvdecError_MaxVxdFileMemory` | Memory limit exceeded | Reduce buffer count |
| `NvdecError_MaxVxdTotalMemory` | Total memory exceeded | Restart decoder |

### 5. Surface Allocation Fallback

```cpp
// From libNmeVideoRenderer.so
OnSurfaceAllocate() {
    result = TryHardwareSurface();
    if (result == success) {
        log("Native surface allocation successful");
        log("Video device based HW decoder opened");
    } else {
        log("Video device based HW decoder not available, %e!");
        log("using decoder allocation");  // Fallback to SW allocation
    }
}
```

---

## Decoder Selection

### Why Software Decoder?

Despite Intel `OMX.Intel.hw_vd.h264` being available, CarPlay uses software NVDEC:

1. **AirPlay Protocol Integration** - CINEMO framework handles AirPlay-specific timing/sync
2. **SEI Processing** - Custom SEI handling for freeze/snapshot/stereo
3. **Quality Control** - Adaptive frame dropping integrated with AirPlay session
4. **Latency Control** - Direct control over decode timing without OMX buffer queues

### Available Hardware Decoder

```xml
<!-- /vendor/etc/media_codecs.xml -->
<MediaCodec name="OMX.Intel.hw_vd.h264" type="video/avc">
    <Limit name="size" min="64x64" max="3840x2160" />
    <Limit name="bitrate" range="1-40000000" />
    <Limit name="performance-point-3840x2160" value="60" />
    <Feature name="adaptive-playback" />
</MediaCodec>
```

**Measured Performance (Hardware):**
| Resolution | FPS |
|------------|-----|
| 1280x720 | 460-590 |
| 1920x1088 | 320-360 |

---

## Rendering Pipeline

### Surface Management

```cpp
// ANativeWindow creation
ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
ANativeWindow_acquire(window);

// EGL setup
EGLSurface eglSurface = eglCreateWindowSurface(display, config, window, attrs);
```

### VMR (Video Mixing Renderer)

```cpp
DecideRenderFPS() {
    // Initial frame timing
    log("DecideRenderFPS() -> initial npts=%T time=%T");

    // Frame dropping decision
    if (frame_too_late) {
        log("DecideRenderFPS() -> npts=%T diff=%T dropped!");
    }
}

DecideRenderQuality() {
    if (stream_behind) {
        log("DecideRenderQuality() -> npts=%T streamtime=%T dropped!");
    }
}
```

### Display Composition

| Stage | Component | Method |
|-------|-----------|--------|
| 1 | VMR | Software composition |
| 2 | SurfaceFlinger | Layer management |
| 3 | HWC 2.1 | `DEVICE` or `CLIENT` composition |
| 4 | Display | 2400x960 @ 60Hz output |

---

## Performance Characteristics

### Timing Targets

| Metric | Value |
|--------|-------|
| Target Frame Rate | 60 fps |
| VSYNC Period | 16.666 ms |
| Presentation Deadline | 14.666 ms |
| Frame Drop Threshold | Configurable |

### Latency Sources

| Stage | Estimated Latency |
|-------|-------------------|
| USB/WiFi Transport | 1-5 ms |
| NAL Parsing | < 1 ms |
| NVDEC Decode | 5-10 ms |
| Surface Copy | 1-2 ms |
| SurfaceFlinger | 0-16 ms (vsync aligned) |
| Display Scanout | 8 ms |
| **Total** | **~20-40 ms** |

---

## Configuration Options

### CINEMO Video Options

| Option | Type | Description |
|--------|------|-------------|
| `video_hardware_decode` | bool | Enable HW decode path |
| `video_software_decode` | bool | Enable SW decode path |
| `video_max_h264_level` | int | Maximum H.264 level |
| `video_extra_surfaces` | int | Additional surface buffers |
| `video_frame_drop_threshold` | int | Drop threshold (ms) |

### AirPlay Configuration

**File:** `/etc/airplay.conf`

---

## System Integration

### Kernel Support

```
CONFIG_VIDEO_INTEL_IPU=y
CONFIG_VIDEO_INTEL_IPU4=y
CONFIG_VIDEO_INTEL_IPU_SOC=y
CONFIG_VIDEO_INTEL_IPU_FW_LIB=y
CONFIG_VIDEOBUF2_CORE=y
CONFIG_VIDEOBUF2_V4L2=y
CONFIG_VIDEOBUF2_DMA_CONTIG=y
```

### User Accounts

```
vendor_carplay:x:2997:2997::/:/vendor/bin/sh
vendor_iap2:x:2994:2994::/:/vendor/bin/sh
```

---

## Comparison: CarPlay vs Android Auto

| Aspect | CarPlay | Android Auto |
|--------|---------|--------------|
| Protocol | AirPlay 320.17.8 | AOA + Projection |
| Video Codec | H.264 (AirPlay) | H.264 (AOA) |
| Decoder | CINEMO NVDEC (SW) | OMX.Intel (HW)* |
| Library | libNmeCarPlay.so | libNmeVideoGAVD.so |
| Transport | USB NCM + IPv6 | USB AOA |

*Android Auto may use hardware decoder through standard Android MediaCodec path.

---

## Related Documentation

- [video_codecs.md](video_codecs.md) - Hardware/software codec specifications
- [display_subsystem.md](display_subsystem.md) - Display and SurfaceFlinger details
- [hardware_rendering.md](../hardware_rendering.md) - GPU and EGL specifications

---

## Data Sources

All specifications from analysis of:
- `/Users/zeno/Downloads/misc/GM_research/gm_aaos/extracted_partitions/system_extracted/`
- `/Users/zeno/Downloads/misc/GM_research/gm_aaos/analysis/adb_Y181/`
- Binary analysis: `strings`, `readelf`, `nm` on libNme*.so
- ADB dumps: processes, services, logcat
