# GM Infotainment Display Subsystem Specifications

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** December 7, 2025

---

## Display Panel Specifications

### Primary Display

| Property | Value |
|----------|-------|
| Panel Model | DD134IA-01B |
| Manufacturer | Chimei Innolux (CMN) |
| Product ID | 41268 |
| Manufacture Date | Week 36, Year 2020 |
| Display Type | Internal LCD |
| Connection Type | Direct (Port 35) |

### Resolution and Timing

| Property | Value |
|----------|-------|
| Physical Resolution | 2400 x 960 pixels |
| Aspect Ratio | 2.5:1 (25:10) |
| Refresh Rate | 60.00 Hz (60.001434 Hz actual) |
| VSYNC Period | 16.666268 ms |
| Presentation Deadline | 14.666268 ms |
| App VSYNC Offset | 2.5 ms |

### Pixel Density

| Property | Value |
|----------|-------|
| Physical DPI (X) | 192.911 |
| Physical DPI (Y) | 193.523 |
| Logical Density | 200 dpi |
| Density Bucket | xhdpi (1.25x scale) |

### Display Metrics

| Metric | Value |
|--------|-------|
| Physical Size | ~13.4 inches diagonal (estimated) |
| Usable App Area | 1416 x 960 pixels (with system UI) |
| Full Screen | 2400 x 960 pixels |

---

## Display Controller

### Hardware Composer (HWC)

| Property | Value |
|----------|-------|
| HAL Version | 2.1 / 2.2 / 2.3 (`composer.intel@2.3-service`) |
| Implementation | `iahwcomposer` (Intel Automotive HWC) |
| Display ID | 4615537251352926243 |
| HWC Display ID | 0 |

### Display Modes

```
Mode ID: 0 (Active)
  Resolution: 2400 x 960
  Refresh Rate: 60.00 fps
  DPI: 192.91 x 193.52
```

**Note:** Single display mode only - no variable refresh rate support.

---

## SurfaceFlinger Configuration

### Build Configuration

| Setting | Value |
|---------|-------|
| PRESENT_TIME_OFFSET | 0 |
| FORCE_HWC_FOR_RGB_TO_YUV | 0 |
| MAX_VIRT_DISPLAY_DIM | 0 |
| RUNNING_WITHOUT_SYNC_FRAMEWORK | 0 |
| NUM_FRAMEBUFFER_SURFACE_BUFFERS | 3 |

### Sync Configuration

- **Sync Type:** `EGL_ANDROID_native_fence_sync`
- **Wait Sync:** `EGL_KHR_wait_sync`

### Scheduler Settings

| Property | Value |
|----------|-------|
| Touch Timer | Off |
| Content Detection | Off |
| Layer History Size | 125 |
| Active Layers | Variable |
| Idle Timer | Off (platform) |
| Frame Rate Override | Not Supported |

### Phase Timing

| Phase | Duration |
|-------|----------|
| App Phase | 2.5 ms |
| SF Phase | 3.0 ms |
| App Duration | 17.17 ms |
| SF Duration | 13.67 ms |
| HWC Min Duration | 0 ns |

---

## Color Management

### Color Configuration

| Property | Value |
|----------|-------|
| Color Mode | Native (Mode 0) |
| Render Intent | Colorimetric |
| Dataspace | Unknown (0) |
| Color Management | Enabled |
| Wide Color Gamut | Not Supported |

### Supported Color Modes

| Mode ID | Mode Name |
|---------|-----------|
| 0 | ColorMode::NATIVE |

### HDR Capabilities

| HDR Type | Supported |
|----------|-----------|
| HDR10 | No |
| HDR10+ | No |
| HLG | No |
| Dolby Vision | No |

**HDR Metadata:**
- Max Luminance: 500.0 nits
- Max Average Luminance: 500.0 nits
- Min Luminance: 0.0 nits

---

## Composition Pipeline

### Layer Composition

| Composition Type | Description | Usage |
|------------------|-------------|-------|
| DEVICE (2) | Hardware overlay | Preferred |
| CLIENT (1) | GPU composition | Fallback |

### Typical Layer Stack

```
Layer Stack (129 visible layers typical):

1. Wallpaper BBQ wrapper
   - Type: DEVICE
   - Size: 2400x960
   - Blend: NONE
   - Opaque: true

2. zeno.carlink/MainActivity
   - Type: CLIENT/DEVICE
   - Size: 2400x960
   - Blend: NONE
   - Opaque: true

3. LeftBar (System UI)
   - Type: CLIENT
   - Size: 189x960
   - Blend: PREMULTIPLIED
   - Opaque: false

4. ScreenDecorOverlay (Top)
   - Type: CLIENT
   - Size: 2400x95
   - Blend: PREMULTIPLIED

5. ScreenDecorOverlayBottom
   - Type: DEVICE
   - Size: 2400x95
   - Blend: PREMULTIPLIED
```

### Composition Statistics

| Metric | Value |
|--------|-------|
| Uses Client Composition | Yes |
| Uses Device Composition | Yes |
| Flip Client Target | No |
| Reused Client Composition | Yes |

---

## Framebuffer Management

### Buffer Queue Configuration

| Property | Value |
|----------|-------|
| Max Acquired Buffers | 2 |
| Max Dequeued Buffers | 1 |
| Dequeue Cannot Block | No |
| Async Mode | No |
| Queue Buffer Can Drop | No |
| Legacy Buffer Drop | Yes |
| Buffer Format | 1 (RGBA_8888) |
| Transform Hint | 0x00 |
| Frame Counter | Continuous |

### Buffer Slots

| Slot | State | Dimensions |
|------|-------|------------|
| 0 | FREE/ACQUIRED | 2400x960 (stride 2432) |
| 1 | ACQUIRED | 2400x960 (stride 2432) |
| 2 | FREE | 2400x960 (stride 2432) |

---

## Display Power Management

### Power States

| State | Status |
|-------|--------|
| Current State | ON |
| Screen Acquired | Yes |
| HW VSYNC Enabled | No (when idle) |
| HW VSYNC Available | Yes |

### Brightness Control

| Property | Value |
|----------|-------|
| Current Brightness | 0.398 (39.8%) |
| Brightness Range | 0.0 - 1.0 |
| Default Brightness | 0.398 |
| Backlight Minimum | 0.035 |
| Backlight Maximum | 1.0 |
| VR Brightness Range | 0.307 - 1.0 |
| VR Default Brightness | 0.335 |

### Auto-Brightness

| Property | Value |
|----------|-------|
| Auto-Brightness Enabled | No |
| Light Sensor | Not Available |
| Software Auto-Brightness | Supported |
| Allow While Dozing | No |

### Brightness Curve

```
Ambient Lux → Brightness Mapping:
0 lux    → 0.232 (23.2%)
50 lux   → 0.390 (39.0%)
300 lux  → 0.547 (54.7%)
720 lux  → 0.783 (78.3%)
2000 lux → 0.980 (98.0%)
```

---

## Display Features

### Supported Features

| Feature | Status |
|---------|--------|
| Secure Display | Yes |
| Protected Buffers | Yes |
| Trusted Display | Yes |
| Rotation Support | Yes (with content) |
| Minimal Post-Processing | No |

### Display Flags

```
FLAG_DEFAULT_DISPLAY
FLAG_ROTATES_WITH_CONTENT
FLAG_SECURE
FLAG_SUPPORTS_PROTECTED_BUFFERS
FLAG_TRUSTED
```

---

## Virtual Display Support

### Display Adapters

| Adapter | Status |
|---------|--------|
| LocalDisplayAdapter | Active |
| VirtualDisplayAdapter | Available |
| OverlayDisplayAdapter | Available |

### Virtual Display Configuration

- Overlay displays not configured
- Remote display service: Stopped

---

## Performance Metrics

### Frame Statistics

| Metric | Value |
|--------|-------|
| Target Frame Rate | 60.00 fps |
| Total Missed Frames | 566 (since boot) |
| HWC Missed Frames | 566 |
| GPU Missed Frames | 536 |

### Static Screen Statistics

| Duration | Percentage | Description |
|----------|------------|-------------|
| < 1 frame | 2.9% | Very active |
| < 2 frames | 3.8% | Active |
| < 3 frames | 1.5% | Moderate |
| < 4 frames | 1.3% | Light |
| < 5 frames | 0.9% | Light |
| < 6 frames | 0.7% | Idle |
| < 7 frames | 0.6% | Idle |
| 7+ frames | 88.5% | Static |

---

## RenderEngine (GPU Fallback)

### Configuration

| Property | Value |
|----------|-------|
| EGL Version | 1.4 Android META-EGL |
| GLES Vendor | Intel |
| GLES Renderer | Mesa Intel HD Graphics 505 |
| GLES Version | OpenGL ES 3.2 Mesa 21.1.5 |
| Protected Context | Not Supported |
| Program Cache | 3 programs |
| Image Cache | 27 images |
| Framebuffer Cache | 3 buffers |

### Last Dataspace Conversion

```
Source: Default (0)
Target: Default (0)
```

---

## System UI Layout

### Screen Regions

| Region | Position | Size |
|--------|----------|------|
| Top Decor | (0, 0) | 2400 x 95 |
| Left Bar | (0, 0) | 189 x 960 |
| Bottom Decor | (0, 865) | 2400 x 95 |
| App Content | (189, 95) | 1416 x 770 |
| Full Screen | (0, 0) | 2400 x 960 |

### Insets

| Inset Type | Value |
|------------|-------|
| Top | 0 (configurable) |
| Status Bar | 95 pixels |
| Navigation Bar | 95 pixels |
| Left Bar | 189 pixels |

---

## Recommendations

### Optimal Video Surface Configuration

1. **Surface Type:** SurfaceView for video playback
2. **Layer Type:** DEVICE composition when possible
3. **Z-Order:** Below system overlays
4. **Secure Content:** Supported via FLAG_SECURE

### Performance Tips

1. Minimize layer count to reduce composition overhead
2. Use opaque layers where possible (blend=NONE)
3. Avoid transparent overlays during video playback
4. Target 60 fps for smooth automotive UI

### CarPlay/Android Auto Display

| Property | Recommended |
|----------|-------------|
| Resolution | 2400 x 960 or 1920 x 768 |
| Frame Rate | 60 fps |
| Color Format | RGBA_8888 or NV12 |
| Composition | DEVICE (overlay) |
