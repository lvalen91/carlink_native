# CarPlay vs Android Auto Projection Comparison

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** December 2025 - February 2026

---

## Executive Summary

GM AAOS implements **fundamentally different architectures** for CarPlay and Android Auto. Additionally, the CPC200-CCPA wireless adapter introduces a third path that bypasses the CINEMO framework entirely.

| Aspect | Apple CarPlay (Native) | CarPlay via CPC200 | Android Auto |
|--------|----------------------|---------------------|--------------|
| **Framework** | CINEMO/NME (Harman) | Standard MediaCodec | Native Android MediaCodec |
| **Video Decoder** | Software (NVDEC) | Hardware (Intel OMX) | Hardware (Intel OMX) |
| **Resolution** | 1416x842 @ 30fps | 2400x960 @ 30fps | Negotiated |
| **Protocol** | AirPlay 320.17.8 | USB bulk (CPC200 protocol) | Android Auto Protocol (AAP) |
| **Libraries** | libNme*.so (~17.5 MB) | Standard AOSP | Standard AOSP |
| **Authentication** | Apple MFi (iAP2) | CPC200 handles MFi | Google certificates |

---

## Video Pipeline Comparison

### CarPlay Video (Native)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CARPLAY VIDEO PIPELINE (NATIVE)                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  iPhone ──► USB NCM/WiFi ──► AirPlay Protocol ──► libNmeCarPlay.so │
│                                       │                             │
│                                       ▼                             │
│                              libNmeVideoSW.so                       │
│                              (NVDEC Software Decoder)               │
│                                       │                             │
│                                       ▼                             │
│                              libNmeVideoRenderer.so                 │
│                              (VMR - Video Mixing Renderer)          │
│                                       │                             │
│                                       ▼                             │
│                              ANativeWindow + EGL                    │
│                                       │                             │
│                                       ▼                             │
│                              SurfaceFlinger ──► Display             │
│                              (1416x842 @ 30fps, windowed)          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Decoder:** CINEMO NVDEC Software Decoder
- Library: `libNmeVideoSW.so` (646 KB)
- Type: CPU-based software decode
- Resolution: 1416x842 @ 30fps (HU reports fps=60 but CarPlay uses 30)
- Reason: AirPlay timing sync, custom SEI handling, ForceKeyframe() integration

**H.264 Processing:**
- NAL unit parsing via `H264DeliverAnnexB()`
- RBSP extraction in `vrbsp.cpp`
- SPS/PPS parsing in `vrbsp_sequence.cpp`
- SEI handling for freeze/snapshot/stereo

**Error Recovery:**
- `AirPlayReceiverSessionForceKeyFrame()` - Request IDR on corruption
- Quality control with adaptive frame dropping
- Reference-only mode when late

### CarPlay Video (via CPC200-CCPA Adapter)

```
┌─────────────────────────────────────────────────────────────────────┐
│              CARPLAY VIDEO PIPELINE (VIA CPC200 ADAPTER)            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  iPhone ──► WiFi/AirPlay ──► CPC200-CCPA Adapter                   │
│                                       │                             │
│                              (AirPlay terminated on adapter)        │
│                              (AAC→PCM decode on adapter)            │
│                              (H.264 passthrough + 36B header)       │
│                                       │                             │
│                                       ▼                             │
│                              USB Bulk Transfer                      │
│                              (VID 0x1314, PID 0x1521)              │
│                                       │                             │
│                                       ▼                             │
│                              MediaCodec API                         │
│                              (Standard Android)                     │
│                                       │                             │
│                                       ▼                             │
│                              OMX.Intel.hw_vd.h264                   │
│                              (Hardware Decoder)                     │
│                              color=256 (ARGB)                       │
│                                       │                             │
│                                       ▼                             │
│                              SurfaceTexture                         │
│                                       │                             │
│                                       ▼                             │
│                              SurfaceFlinger ──► Display             │
│                              (2400x960 @ 30fps, fullscreen)        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Decoder:** Intel Hardware Decoder
- Codec: `OMX.Intel.hw_vd.h264`, color format 256 (ARGB)
- Type: Hardware-accelerated (Intel VPU)
- Resolution: 2400x960 @ 30fps (fullscreen, native display resolution)

**H.264 Stream Characteristics:**
- Profile: High (100), Level 5.0
- Bitrate: 1.2-5.3 Mbps adaptive
- CSD caching per device+resolution: SPS = 25 bytes, PPS = 8 bytes
- Source PTS from adapter header offset 12 (ms, little-endian), converted to microseconds for MediaCodec

**Performance (Measured):**
- First packet to first decoded frame: ~248ms
- Steady state: 30fps, zero frame drops
- Decode latency: ~30ms per frame
- CPC200 adds 36-byte header to H.264 NAL units (20B video header + protocol framing)

**Key Difference from Native CarPlay:**
The CPC200 adapter terminates the AirPlay protocol entirely on the adapter itself. The head unit never sees AirPlay -- it receives raw H.264 via USB bulk transfers with a proprietary header. This means:
1. No CINEMO/NME framework involvement
2. Hardware decode instead of software decode
3. Full 2400x960 resolution instead of 1416x842 windowed
4. Standard MediaCodec path instead of proprietary NVDEC

### Android Auto Video

```
┌─────────────────────────────────────────────────────────────────────┐
│                   ANDROID AUTO VIDEO PIPELINE                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Android Phone ──► USB AOA/WiFi ──► AAP Protocol ──► CarService    │
│                                            │                        │
│                                            ▼                        │
│                                     MediaCodec API                  │
│                                            │                        │
│                                            ▼                        │
│                                   OMX.Intel.hw_vd.h264              │
│                                   (Hardware Decoder)                │
│                                            │                        │
│                                            ▼                        │
│                                     SurfaceTexture                  │
│                                            │                        │
│                                            ▼                        │
│                                   SurfaceFlinger ──► Display        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Decoder:** Intel Hardware Decoder
- Codec: `OMX.Intel.hw_vd.h264`
- Type: Hardware-accelerated (Intel VPU)
- Max: 3840x2160 @ 60fps

**H.264 Processing:**
- Standard MediaCodec queueInputBuffer()
- Hardware NAL parsing
- Standard Android error callbacks

**Error Recovery:**
- MediaCodec.Callback.onError()
- Standard codec flush/restart

---

## Video Codec Specifications

### Hardware Decoders (Used by Android Auto and CPC200 CarPlay)

| Codec | OMX Component | Max Resolution | Max FPS | Profiles |
|-------|---------------|----------------|---------|----------|
| H.264 | OMX.Intel.hw_vd.h264 | 3840x2160 | 60 | Baseline, Main, High (5.1) |
| H.265 | OMX.Intel.hw_vd.h265 | 3840x2160 | 60 | Main, Main10 (5.1) |
| VP8 | OMX.Intel.hw_vd.vp8 | 3840x2160 | 60 | - |
| VP9 | OMX.Intel.hw_vd.vp9 | 3840x2160 | 60 | Profile 0-2, HDR (5.2) |

### Software Decoder (Used by Native CarPlay)

| Codec | Library | Type | Notes |
|-------|---------|------|-------|
| H.264 | libNmeVideoSW.so | NVDEC Software | AirPlay-specific timing |

### Why Native CarPlay Uses Software Decode

Despite Intel hardware decoder availability, native CarPlay uses software decode for:

1. **AirPlay Protocol Integration** - Custom timing synchronization
2. **SEI Message Handling** - Freeze/snapshot/stereo support
3. **ForceKeyframe()** - Immediate IDR request on errors
4. **Quality Control** - Adaptive frame dropping
5. **Clock Sync** - AirPlay-specific timestamp handling

### Why CPC200 CarPlay Uses Hardware Decode

The CPC200 adapter bypasses these constraints because:

1. **AirPlay Terminated on Adapter** - The head unit never sees AirPlay protocol
2. **No SEI Forwarding** - Adapter strips AirPlay-specific SEI messages
3. **Standard H.264 Stream** - Clean NAL units with simple header, compatible with MediaCodec
4. **No Clock Sync Needed** - PTS provided directly in adapter header (offset 12, ms LE)

---

## Android Auto Resolution Configuration

### Display Specifications

```
Physical Display: 2400 x 960 @ 60Hz
Aspect Ratio: 2.5:1 (non-standard)
Density: 200 dpi (1.25 scale factor)
xDpi: 192.911, yDpi: 193.523
Panel: DD134IA-01B (CMN, manufactured 2020)
```

### Required Configurations

Android Auto requires **two resolution configurations** for proper operation:

| Configuration | Purpose | Description |
|---------------|---------|-------------|
| **Video Resolution** | Phone rendering | Resolution at which phone renders and encodes H.264 video stream |
| **UI Resolution** | Touch mapping | Resolution used to map touch coordinates from display to phone |

### Configuration Gap (Documentation Finding)

**WARNING:** No explicit Android Auto video resolution configuration was found in the extracted GM AAOS partitions.

**Searched Locations:**
- `/vendor/etc/` - No Android Auto config XML
- `/system/etc/` - No projection resolution config
- `/product/etc/` - No embedded.projection config
- CalDef Database - No video resolution calibrations for Android Auto

**Services Found (but no config):**
```
com.gm.phoneprojection.service.BINDER    (IPhoneProjectionService)
com.gm.server.screenprojection.RDMSADBHandler
com.google.android.embedded.projection   (package referenced but not extracted)
```

### Potential Issues

The GM display has a **2.5:1 aspect ratio** which does not match standard Android Auto resolutions:

| Standard AA Resolution | Aspect Ratio | Match? |
|------------------------|--------------|--------|
| 800 x 480 | 5:3 (1.67:1) | No |
| 1280 x 720 | 16:9 (1.78:1) | No |
| 1920 x 1080 | 16:9 (1.78:1) | No |
| 2400 x 960 | 2.5:1 | Native display |

**Without proper configuration:**
1. Phone may default to 720p or 1080p instead of optimal resolution
2. Touch mapping could be misaligned if UI resolution doesn't match
3. Letterboxing/pillarboxing may occur due to aspect ratio mismatch
4. Margins may not be properly configured

### Expected Configuration Format

A proper Android Auto configuration should include:

```xml
<!-- Example - NOT found in GM AAOS -->
<projectionConfig>
    <video>
        <resolution width="1920" height="768" />  <!-- or 2400x960 -->
        <frameRate min="30" max="60" />
        <codec>H264</codec>
    </video>
    <ui>
        <resolution width="2400" height="960" />
        <density>200</density>
        <margins left="0" top="0" right="0" bottom="0" />
    </ui>
</projectionConfig>
```

### Where Configuration May Exist

The Android Auto resolution configuration may be:
1. Embedded in `com.google.android.embedded.projection` APK (not extracted)
2. Hardcoded in `com.gm.phoneprojection.service` native libraries
3. Negotiated at runtime via AAP protocol
4. Configured via Google Automotive Services (GAS) overlay

### Recommendation

Third-party implementations targeting GM AAOS should:
1. Support multiple video resolutions (720p, 1080p, native 2400x960)
2. Handle aspect ratio conversion (letterbox/pillarbox)
3. Properly map touch coordinates regardless of video resolution
4. Test with actual GM hardware to determine negotiated resolution

---

## Audio Pipeline Comparison

### CarPlay Audio

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CARPLAY AUDIO PIPELINE                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  DOWNLINK (iPhone → Vehicle):                                       │
│  ────────────────────────────                                       │
│  iPhone ──► AirPlay ──► libNmeCarPlay.so ──► libNmeAudioAAC.so     │
│                                │                                    │
│                                ▼                                    │
│                         libNmeAudioDevice.so                        │
│                                │                                    │
│                                ▼                                    │
│                         AudioFlinger (bus routing)                  │
│                                │                                    │
│                                ▼                                    │
│                         PulseAudio + AVB ──► Amplifier              │
│                                                                     │
│  UPLINK (Vehicle → iPhone):                                         │
│  ──────────────────────────                                         │
│  Microphone ──► Harman HAL (AEC/NS/AGC) ──► libNmeCarPlay.so       │
│                                │                                    │
│                                ▼                                    │
│                         AAC-ELD/Opus Encode                         │
│                                │                                    │
│                                ▼                                    │
│                         AirPlay ──► iPhone                          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Codecs Supported:**
- AAC-LC (44.1/48 kHz)
- AAC-ELD (16/24 kHz) - Low-latency voice
- Opus (8-48 kHz) - VoIP
- PCM (44.1/48 kHz)

**Audio Routing (Dedicated Buses):**
| Audio Type | Bus | Sample Rate |
|------------|-----|-------------|
| Media | bus0_media_out | 48 kHz |
| Navigation | bus1_navigation_out | 48 kHz |
| Siri | bus2_voice_command_out | 16-48 kHz |
| Phone Call | bus4_call_out | 8-16 kHz |
| Notification | bus6_notification_out | 48 kHz |

**Telephony Tuning (SCD Files):**
```
USB Wired:
  SSE_HF_GM_INFO3_CarPlayTelNB.scd      (8 kHz narrowband)
  SSE_HF_GM_INFO3_CarPlayTelWB.scd      (16 kHz wideband)
  SSE_HF_GM_INFO3_CarPlayFT_SWB.scd     (24 kHz super-wideband FaceTime)

WiFi Wireless:
  SSE_HF_GM_INFO3_WiFi_CarPlayTelNB.scd
  SSE_HF_GM_INFO3_WiFi_CarPlayTelWB.scd
  SSE_HF_GM_INFO3_WiFi_CarPlayTelSWB.scd
```

### CPC200 CarPlay Audio

```
┌─────────────────────────────────────────────────────────────────────┐
│               CARPLAY AUDIO PIPELINE (VIA CPC200 ADAPTER)           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  DOWNLINK (iPhone → Vehicle):                                       │
│  ────────────────────────────                                       │
│  iPhone ──► AirPlay ──► CPC200 Adapter                              │
│                              │                                      │
│                              ▼                                      │
│                    AAC-LC → PCM Decode (on adapter)                 │
│                    48kHz stereo (media)                              │
│                    16kHz mono (Siri, AAC-ELD)                       │
│                              │                                      │
│                              ▼                                      │
│                    USB Bulk Transfer → Host                         │
│                              │                                      │
│                              ▼                                      │
│                    AudioFlinger bus0_media_out (direct)              │
│                              │                                      │
│                              ▼                                      │
│                    PulseAudio + AVB ──► Amplifier                   │
│                                                                     │
│  UPLINK (Vehicle → iPhone):                                         │
│  ──────────────────────────                                         │
│  Microphone ──► Host AudioRecord ──► USB ──► CPC200 Adapter        │
│                              │                                      │
│                              ▼                                      │
│                    WebRTC (AEC/NS/AGC on adapter)                   │
│                              │                                      │
│                              ▼                                      │
│                    AirPlay ──► iPhone                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Key Difference:** The CPC200 adapter decodes AAC to PCM on-device, so the head unit receives raw PCM and routes it directly through AudioFlinger without needing CINEMO's audio decode libraries.

### Android Auto Audio

```
┌─────────────────────────────────────────────────────────────────────┐
│                   ANDROID AUTO AUDIO PIPELINE                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  DOWNLINK (Phone → Vehicle):                                        │
│  ───────────────────────────                                        │
│  Phone ──► AAP Protocol ──► CarAudioService ──► AudioFlinger       │
│                                       │                             │
│                                       ▼                             │
│                                 Standard Android                    │
│                                 Audio Decoders                      │
│                                       │                             │
│                                       ▼                             │
│                                 Bus Routing                         │
│                                       │                             │
│                                       ▼                             │
│                                 Harman DSP ──► Amplifier            │
│                                                                     │
│  UPLINK (Vehicle → Phone):                                          │
│  ─────────────────────────                                          │
│  Microphone ──► Harman HAL (AEC/NS/AGC) ──► CarAudioService        │
│                                       │                             │
│                                       ▼                             │
│                                 AAP Protocol ──► Phone              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Codecs Supported:**
- AAC (all profiles via MediaCodec)
- MP3
- Opus
- Vorbis
- FLAC
- AMR-NB/WB
- G.711 (telephony)

**Audio Routing:**
Same bus architecture as CarPlay (shared AudioFlinger)

**Telephony Tuning:**
Uses standard Bluetooth HFP SCD files (no Android Auto specific tuning)

---

## Protocol Comparison

### CarPlay Protocol (Native)

| Aspect | Specification |
|--------|---------------|
| **Protocol** | AirPlay 320.17.8 |
| **Transport (Wired)** | USB NCM + IPv6 |
| **Transport (Wireless)** | WiFi Direct + Bluetooth (pairing) |
| **Authentication** | Apple MFi (iAP2) |
| **Encryption** | FairPlay (AES-128) |
| **Service Discovery** | _airplay._tcp |

**Key Libraries:**
- `libNmeCarPlay.so` (1.0 MB) - AirPlay protocol
- `libNmeIAP.so` (2.9 MB) - iAP2 protocol
- `libNmeAppleAuth.so` (80 KB) - MFi authentication

### CarPlay via CPC200 Protocol

| Aspect | Specification |
|--------|---------------|
| **Protocol** | CPC200 proprietary USB bulk |
| **Transport** | USB (VID 0x1314, PID 0x1521) |
| **Authentication** | CPC200 handles Apple MFi on-device |
| **Video** | H.264 with 36B header (20B video header + framing) |
| **Audio** | PCM (adapter decodes AAC to PCM) |
| **Service Discovery** | USB device enumeration |

**Key Difference:** The CPC200 adapter is an intelligent protocol bridge -- it terminates AirPlay/iAP2 entirely and presents a simple USB device to the head unit. The head unit never speaks AirPlay.

### Android Auto Protocol

| Aspect | Specification |
|--------|---------------|
| **Protocol** | Android Auto Protocol (AAP) |
| **Transport (Wired)** | USB AOA (Android Open Accessory) |
| **Transport (Wireless)** | WiFi Direct + Bluetooth |
| **Authentication** | Google certificates |
| **Encryption** | SSL/TLS |
| **Service Discovery** | Android Binder IPC |

**Key Components:**
- `CarService` - Vehicle integration
- `media_projection` - Screen mirroring
- `companiondevice` - Device pairing
- `android.car.usb.handler` - USB handling

**Security Certificates:**
```
/system/etc/security/androidauto/
├── dc3d1471.0           (4387 bytes)
├── dc3d1471_nxp.0       (4387 bytes)
├── a1467e3a.0           (1285 bytes)
├── a1467e3a_nxp.0       (1285 bytes)
├── privatecert.txt      (1704 bytes)
└── privatecert_nxp.txt  (1704 bytes)
```

---

## Feature Comparison

### Driver Workload Lockouts (GIS-337)

| Feature | CarPlay | Android Auto |
|---------|---------|--------------|
| Soft Keyboard | Configurable | No text input allowed |
| Soft Keypad | Configurable | - |
| Voice Input | Always allowed | Configurable |
| Video Playback | Not applicable | Disabled |
| List Length Limits | Music/Non-music separate | Message length limited |
| Setup/Config | - | Disabled while driving |

### Wireless Support

| Feature | CarPlay | Android Auto |
|---------|---------|--------------|
| WiFi Projection | `Apple_CarPlay_enableWireless` | `AndroidAuto_enableWireless` |
| Default State | false (calibratable) | false (calibratable) |
| Implementation | AirPlay over WiFi | AAP over WiFi |

### Projection Features (GIS-513)

| Feature | Calibration | Default |
|---------|-------------|---------|
| Apple CarPlay | `Enable_Application_Apple_Carplay` | true |
| Android Auto | `Enable_application_google_Automotive_link` | true |
| MirrorLink | `Enable_Application_MirrorLink` | false |
| Baidu CarLife | `ENABLE_APPLICATIONBAIDU_CARLIFE` | false |

---

## Performance Comparison

### Video Decode Latency

| Protocol | Decoder | Latency | CPU Usage |
|----------|---------|---------|-----------|
| CarPlay (Native) | NVDEC SW | 10-15 ms | Medium |
| CarPlay (CPC200) | Intel HW | ~30 ms (incl. adapter overhead) | Low |
| Android Auto | Intel HW | ~5 ms | Low |

**CPC200 Startup Performance:**
- First packet to first decoded frame: ~248ms
- Steady state: 30fps, zero frame drops
- Includes USB transfer + MediaCodec configure + first decode

### Audio Latency

| Protocol | Path | Latency |
|----------|------|---------|
| CarPlay (Native) | AirPlay → NME → AudioFlinger | ~50-90 ms |
| CarPlay (CPC200) | Adapter PCM → AudioFlinger | ~50-90 ms |
| Android Auto | AAP → CarAudio → AudioFlinger | ~40-60 ms |

### Memory Usage

| Protocol | Video Buffers | Audio Buffers | Total |
|----------|---------------|---------------|-------|
| CarPlay (Native) | ~75-90 MB | ~10 MB | ~85-100 MB |
| CarPlay (CPC200) | ~40-50 MB | ~10 MB | ~50-60 MB |
| Android Auto | ~40-50 MB | ~10 MB | ~50-60 MB |

---

## Error Recovery Comparison

### CarPlay (Native)

```cpp
// AirPlay-specific recovery
OnFrame() {
    if (corrupt_data) {
        AirPlayReceiverSessionForceKeyFrame(session);
    }
}

// Decoder errors
NvdecDeliverHeaders() {
    switch (error) {
        case NvdecError_BitDepth:
        case NvdecError_ChromaFormat:
        case NvdecError_Profile:
            // Reconfigure or skip
    }
}
```

### CarPlay (via CPC200)

```java
// Standard MediaCodec recovery (same as Android Auto)
// CPC200 adapter handles AirPlay-level error recovery internally
// Host only sees clean H.264 stream via USB bulk

mediaCodec.setCallback(new MediaCodec.Callback() {
    @Override
    public void onError(MediaCodec codec, CodecException e) {
        if (e.isRecoverable()) {
            codec.stop();
            codec.configure(...);  // Re-use cached CSD (SPS 25B + PPS 8B)
            codec.start();
        }
    }
});
```

### Android Auto

```java
// Standard MediaCodec recovery
mediaCodec.setCallback(new MediaCodec.Callback() {
    @Override
    public void onError(MediaCodec codec, CodecException e) {
        if (e.isRecoverable()) {
            codec.stop();
            codec.configure(...);
            codec.start();
        }
    }
});
```

---

## Architecture Summary

### CarPlay Stack (Native)

```
┌─────────────────────────────────────────┐
│           GMCarPlay.apk (36 MB)         │
├─────────────────────────────────────────┤
│           Java/Kotlin Layer             │
├─────────────────────────────────────────┤
│    libNmeCarPlay.so │ libNmeIAP.so      │
├─────────────────────────────────────────┤
│  libNmeVideoSW.so │ libNmeAudioAAC.so   │
├─────────────────────────────────────────┤
│       libNmeBaseClasses.so (4.8 MB)     │
├─────────────────────────────────────────┤
│    ANativeWindow │ AudioFlinger │ EGL   │
└─────────────────────────────────────────┘
```

### CarPlay Stack (via CPC200)

```
┌─────────────────────────────────────────┐
│     CPC200-CCPA Adapter (ARM SoC)       │
│  AirPlay + iAP2 termination, AAC→PCM   │
│  H.264 passthrough + 36B header         │
├─────────────────────────────────────────┤
│           USB Bulk Transfer             │
│  VID 0x1314, PID 0x1521                │
├─────────────────────────────────────────┤
│     Host App (Standard MediaCodec)      │
├─────────────────────────────────────────┤
│  OMX.Intel.hw_vd.h264 │ AudioFlinger   │
├─────────────────────────────────────────┤
│      Standard Android Framework         │
└─────────────────────────────────────────┘
```

### Android Auto Stack

```
┌─────────────────────────────────────────┐
│     AndroidAutoIMEPrebuilt (72 MB)      │
├─────────────────────────────────────────┤
│           CarService (AAOS)             │
├─────────────────────────────────────────┤
│      MediaCodec │ CarAudioService       │
├─────────────────────────────────────────┤
│  OMX.Intel.hw_vd.* │ AudioFlinger       │
├─────────────────────────────────────────┤
│      Standard Android Framework         │
└─────────────────────────────────────────┘
```

---

## Key Takeaways

1. **Three Distinct Paths**: Native CarPlay (CINEMO/SW decode), CPC200 CarPlay (MediaCodec/HW decode), and Android Auto (MediaCodec/HW decode)

2. **Native CarPlay is the Outlier**: Only path that uses software decode and proprietary CINEMO/NME framework

3. **CPC200 Matches Android Auto Architecture**: Both use standard Android MediaCodec with Intel hardware acceleration

4. **CPC200 Gets Better Resolution**: Full 2400x960 native resolution vs native CarPlay's 1416x842 windowed

5. **Same Audio Routing**: All three paths share AudioFlinger and the bus architecture

6. **CarPlay Has More Libraries**: ~17.5 MB of NME libraries for native path vs standard AOSP for CPC200 and Android Auto

7. **CarPlay Has Dedicated Audio Tuning**: SCD files for USB and WiFi telephony variants

8. **CPC200 Trades Latency for Resolution**: ~248ms startup vs near-instant native, but gains fullscreen HW-decoded video

9. **Android Auto Resolution Gap**: No explicit video/UI resolution configuration found for Android Auto in extracted partitions - may cause aspect ratio or touch mapping issues with the non-standard 2400x960 (2.5:1) display

---

## Data Sources

**Extracted Partitions:**
- `/vendor/etc/media_codecs.xml` - Video codec definitions
- `/system/lib64/libNme*.so` - NME library binaries
- `/system/etc/security/androidauto/` - Android Auto certificates
- `/vendor/etc/scd/*.scd` - CarPlay audio tuning

**CalDef Database:**
- `GIS337_ConsumerDeviceProjection.caldef` - Driver workload lockouts
- `GIS513_DeviceProjection.caldef` - Feature enable/disable

**Binary Analysis:**
- `strings`, `readelf`, `nm` on NME libraries

**CPC200 Adapter Testing (February 2026):**
- USB protocol analysis (VID 0x1314, PID 0x1521)
- MediaCodec decoder profiling on GM Info 3.7
- Logcat-verified decode latency and frame rate measurements

**Source:** `/Users/zeno/Downloads/misc/GM_research/gm_aaos/`
