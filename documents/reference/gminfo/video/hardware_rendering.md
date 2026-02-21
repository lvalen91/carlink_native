# GM Infotainment Hardware Rendering Specifications

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** December 2025 - February 2026

---

## Hardware Overview

### CPU Specifications

| Property | Value |
|----------|-------|
| Vendor | GenuineIntel |
| Model | Intel Atom x7-A3960 (Goldmont/Apollo Lake), IoT CPU 1.0 (Model 92, Stepping 10) |
| Architecture | x86_64 |
| Cores | 4 |
| Frequency | Base 800 MHz, Boost 2.4 GHz (1881.6 MHz nominal) |
| Cache | 1024 KB per core |
| Address Space | 39-bit physical, 48-bit virtual |

**CPU Features:**
- SSE, SSE2, SSE3, SSSE3, SSE4.1, SSE4.2
- AES-NI encryption acceleration
- AVX (not listed, likely disabled for automotive)
- SHA-NI (hardware SHA acceleration)
- PCLMULQDQ (carry-less multiplication)
- RDRAND/RDSEED (hardware RNG)

### GPU Specifications

| Property | Value |
|----------|-------|
| GPU Model | Intel HD Graphics 505 (APL 3) |
| Platform Codename | Apollo Lake (Broxton) |
| Driver | Mesa Intel 21.1.5 |
| OpenGL ES Version | 3.2 (196610 = 0x30002) |
| Vulkan Version | 1.0.64 (4198400 = 0x400040) |
| Vulkan Compute | Supported (Level 1) |

---

## Graphics Driver Stack

### Hardware Abstraction Layer (HAL)

| Component | Implementation |
|-----------|----------------|
| Gralloc | minigbm CrosGralloc4 (`gralloc.broxton.so`, 96 KB) |
| HW Composer | iahwcomposer (Intel Automotive HWC @2.1/@2.2/@2.3, explicit sync, high quality scaling) |
| Vulkan ICD | `vulkan.broxton.so` (9.8 MB) |

### Driver Properties

```
ro.hardware.gralloc=broxton
ro.hardware.hwcomposer=broxton
ro.hardware.vulkan=broxton
ro.board.platform=broxton
```

---

## OpenGL ES Capabilities

### Version Information
- **EGL Version:** 1.4 Android META-EGL
- **GLES Version:** OpenGL ES 3.2 Mesa 21.1.5
- **GLES Vendor:** Intel
- **GLES Renderer:** Mesa Intel(R) HD Graphics 505 (APL 3)

### Supported EGL Extensions

| Extension | Purpose |
|-----------|---------|
| `EGL_ANDROID_native_fence_sync` | GPU fence synchronization |
| `EGL_KHR_fence_sync` | Fence sync objects |
| `EGL_KHR_wait_sync` | Wait for sync objects |
| `EGL_ANDROID_presentation_time` | Frame presentation timing |
| `EGL_ANDROID_get_frame_timestamps` | Frame timestamp queries |
| `EGL_ANDROID_recordable` | Screen recording support |
| `EGL_EXT_buffer_age` | Partial redraw optimization |
| `EGL_KHR_gl_colorspace` | Color space management |
| `EGL_KHR_image_base` | Image sharing |
| `EGL_KHR_surfaceless_context` | Headless rendering |

### Supported GLES Extensions (Key Extensions)

**Texture Compression:**
- `GL_EXT_texture_compression_s3tc` (DXT1/3/5)
- `GL_EXT_texture_compression_rgtc` (BC4/BC5)
- `GL_EXT_texture_compression_bptc` (BC6H/BC7)
- `GL_KHR_texture_compression_astc_ldr` (ASTC)
- `GL_OES_compressed_ETC1_RGB8_texture` (ETC1)

**Shader Capabilities:**
- `GL_EXT_geometry_shader`
- `GL_EXT_tessellation_shader`
- `GL_OES_shader_image_atomic`
- `GL_EXT_gpu_shader5`
- `GL_EXT_shader_framebuffer_fetch`
- `GL_NV_compute_shader_derivatives`

**Rendering Features:**
- `GL_EXT_draw_buffers_indexed` (MRT)
- `GL_EXT_blend_func_extended`
- `GL_KHR_blend_equation_advanced`
- `GL_EXT_clip_cull_distance`
- `GL_EXT_depth_clamp`
- `GL_INTEL_conservative_rasterization`

**External Textures:**
- `GL_OES_EGL_image_external`
- `GL_OES_EGL_image_external_essl3`
- `GL_EXT_EGL_image_storage`

---

## Vulkan Capabilities

### Version and Features

| Property | Value |
|----------|-------|
| Vulkan API Version | 1.0.64 |
| Feature Level | android.hardware.vulkan.level=1 |
| Compute Support | android.hardware.vulkan.compute |
| DEQP Level | 132383489 |

### Driver Information

- **ICD:** `vulkan.broxton.so`
- **Driver Type:** Mesa/Intel ANV
- **Usage:** **Available but NOT used at runtime** — SurfaceFlinger uses GLES backend exclusively. Vulkan driver exists (vulkan.broxton.so) but no components use it.

---

## Display Subsystem

### Primary Display

| Property | Value |
|----------|-------|
| Panel Model | DD134IA-01B |
| Manufacturer PNP ID | CMN (Chimei Innolux) |
| Resolution | 2400 x 960 pixels |
| Refresh Rate | 60.00 Hz |
| DPI | Physical: xDpi=192.911 yDpi=193.523; system lcd_density=200 (xhdpi) |
| Display Density | 200 dpi |
| VSYNC Period | 16.666 ms |
| Color Mode | Native (Mode 0) |
| Display Type | Internal LCD |
| Manufacture Date | Week 36, 2020 |

### SurfaceFlinger Configuration

```
Build configuration:
  PRESENT_TIME_OFFSET=0
  FORCE_HWC_FOR_RBG_TO_YUV=0
  MAX_VIRT_DISPLAY_DIM=0
  NUM_FRAMEBUFFER_SURFACE_BUFFERS=3
```

### Composition Pipeline

| Feature | Status |
|---------|--------|
| Client Composition | Enabled (GPU fallback) |
| Device Composition | Enabled (HW overlay) |
| Color Management | Enabled |
| Wide Color Gamut | Not Supported |
| HDR10 | Not Supported |
| HDR10+ | Not Supported |
| HLG | Not Supported |
| Dolby Vision | Not Supported |

### Rendering Statistics

| Metric | Value |
|--------|-------|
| Framebuffer Surface Buffers | 3 (triple buffering) |
| Max Graphics Buffer Producers | 4096 |
| RenderEngine Protected Context | Not Supported |
| Visible Layers (typical) | ~129 |

---

## Hardware Composer (HWC)

### HWC Version
- **Interface:** `android.hardware.graphics.composer@2.1`, `@2.2`, `@2.3`
- **Service Binary:** `composer.intel@2.3-service`
- **Implementation:** `iahwcomposer` (Intel Automotive Hardware Composer)

### Layer Composition Types

| Type | Description |
|------|-------------|
| `DEVICE` | Hardware overlay (zero-copy) |
| `CLIENT` | GPU composition (SurfaceFlinger RenderEngine) |

### HWC Capabilities

- Supports multiple hardware overlays
- YUV to RGB conversion in display controller
- Scaling and rotation in hardware
- Alpha blending in hardware
- No HDR tone mapping (not supported)

### HWC Configuration
- Only 1 DRM display active (3 defined in hwc_display.ini)
- VSYNC period: 16.666 ms, presentation deadline: 14.666 ms
- No DCI-P3, no wide color gamut, no HDR
- Max luminance: 500 nits

---

## Memory Allocation (Gralloc)

### Implementation
- **HAL:** `gralloc.broxton.so`
- **Fallback:** `gralloc.default.so`

### Buffer Formats Supported

| Format Code | Format Name |
|-------------|-------------|
| 0x1 | RGBA_8888 |
| 0x2 | RGBX_8888 |
| 0x3 | RGB_888 |
| 0x4 | RGB_565 |
| 0x5 | BGRA_8888 |
| 0x7f420888 | YUV420Flexible |
| 0x13 | YUV420Planar |
| 0x15 | YUV420SemiPlanar (NV12) |
| 0x14 | YUV420PackedPlanar |
| 0x27 | YUV420PackedSemiPlanar |
| 0x100 | Implementation-defined |

---

## Video Decode/Encode Pipeline

### Hardware Video Engine

The Intel HD Graphics 505 includes dedicated video processing hardware:

| Engine | Function |
|--------|----------|
| VPU (Video Processing Unit) | Hardware decode/encode |
| VPG (Video Post-Processor) | Scaling, color conversion |
| Display Engine | Overlay composition |

### OMX Service
- **Interface:** HIDL 1.0 (`android.hardware.media.omx@1.0`)
- **User:** `mediacodec`
- **Groups:** `camera`, `drmrpc`, `mediadrm`
- **I/O Priority:** `rt 4` (real-time, priority 4)

### Decode Path
1. **Input:** Compressed video bitstream
2. **MediaCodec API** → Intel OMX decoder
3. **VPU** decodes to YUV surface in graphics memory
4. **Gralloc buffer** holds decoded frame
5. **SurfaceFlinger** composites via HWC or RenderEngine
6. **Display Engine** outputs to panel

### Encode Path
1. **Input:** Camera/screen capture in graphics memory
2. **VPU** encodes from YUV/RGB surface
3. **Output:** Compressed bitstream via MediaCodec

---

## Performance Characteristics

### Measured Frame Rates (from codec benchmarks)

**H.264 Hardware Decode:**
| Resolution | FPS Range |
|------------|-----------|
| 320x240 | 740-980 |
| 720x480 | 830-1020 |
| 1280x720 | 460-590 |
| 1920x1088 | 320-360 |

**H.265 Hardware Decode:**
| Resolution | FPS Range |
|------------|-----------|
| 352x288 | 600-1000 |
| 640x360 | 450-800 |
| 720x480 | 400-700 |
| 1280x720 | 250-500 |
| 1920x1080 | 190-400 |
| 3840x2160 | 120-130 |

**VP8 Hardware Decode:**
| Resolution | FPS Range |
|------------|-----------|
| 320x180 | 600-1300 |
| 640x360 | 500-800 |
| 1280x720 | 200-400 |
| 1920x1080 | 150-330 |

**VP9 Hardware Decode:**
| Resolution | FPS Range |
|------------|-----------|
| 320x180 | 600-1300 |
| 640x360 | 500-800 |
| 1280x720 | 400-600 |
| 1920x1080 | 350-420 |
| 3840x2160 | 100-130 |

### GPU Loading Stats

```
GL driver loading time: ~45ms
Vulkan loading: Not commonly used
ANGLE: Not used
```

---

## Automotive-Specific Features

### Display Flags
- `FLAG_SECURE` - Protected content support
- `FLAG_SUPPORTS_PROTECTED_BUFFERS` - DRM buffer support
- `FLAG_TRUSTED` - System display
- `FLAG_ROTATES_WITH_CONTENT` - Auto-rotation capable

### System Services
- `automotive_display` service: Running
- `remote_display` service: Stopped (not in use)
  - Binary: `/vendor/bin/remote_display`
  - User: `system`
  - Type: `oneshot` (does not auto-restart)

### Hardware SKU
```
ro.boot.product.hardware.sku=gv221
ro.hardware.type=automotive
```

---

## Boot-Time GPU/Display Init (init.bxtp_gm.rc)

### GPU Configuration
- **GPU Min Frequency:** Forced to 300 MHz on `post-fs` phase (required for IPU camera integration)
- **Mesa Driver Environment:**
  - `INTEL_DEBUG=heur32` (heuristic tuning for Gen 3.2 / Apollo Lake)
  - `MESA_LOADER_DRIVER_OVERRIDE=iris` (forces Iris driver over i965)

### CPU Power States During Boot
- **C-states (C1-C3):** Disabled during boot for deterministic init timing
- **C-states:** Re-enabled on `boot_completed` trigger
- **Suspend:** Disabled permanently via `nosleeptillbrooklyn` wake_lock — the system never enters suspend

### GPIO Assignments (External Processors)
| GPIO | Function |
|------|----------|
| 466 | Dirana Reset |
| 460 | Dirana Bootstate |
| 458 | Saturn Reset |
| 464 | Saturn Bootmode |

---

## Ethernet Switch Hardware ID

The property `persist.vendor.harman.hardwareid` determines which Ethernet switch driver is loaded.

| Switch Vendor | Hardware IDs |
|---------------|-------------|
| Broadcom (`bcm`) | ED, DV1, DV2, DV4, DV6, PV1, MY22PV01-08 |
| Marvell (`mrvl`) | MY23DV0, MY23DV1, MY24DV1 |

MY23 and later hardware revisions use the Marvell switch.

---

## Recommendations for Video Rendering

### Optimal Video Playback Settings

1. **Preferred Codec:** H.264 or H.265 via hardware decoder
2. **Max Resolution:** 1920x1080 for smooth 60fps playback
3. **Color Format:** NV12 (YUV420SemiPlanar) for hardware path
4. **Surface Type:** SurfaceView or TextureView with hardware layers

### CarPlay/Android Auto Integration

For projection rendering:
- Use `OMX.Intel.hw_vd.h264` for video decode
- Render to SurfaceView for direct HWC overlay
- Avoid TextureView if possible (requires GPU composition)
- Use 1080p or 720p for optimal latency

### Memory Considerations

- Triple-buffered framebuffer (17ms latency overhead)
- Gralloc buffers in unified graphics memory
- No dedicated VRAM (shared system memory architecture)
