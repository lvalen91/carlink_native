# Intel Audio Subsystem on GM AAOS

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Audio Framework:** Intel IAS SmartX + Intel SST
**Analysis Date:** January 2026

---

## Overview

GM AAOS uses a **mixed proprietary audio stack** combining:
- **Intel IAS (Intelligent Audio Subsystem) SmartX** - Audio routing and processing framework
- **Intel SST (Smart Sound Technology)** - Kernel-level audio drivers
- **Harman Audio Processing** - Effects and preprocessing
- **Standard Android AudioFlinger** - Application interface

Unlike Intel Media SDK (video), Intel's audio frameworks have **limited public documentation**.

**Third-Party Access:** NO - Intel IAS SmartX is below the HAL barrier and not accessible to third-party apps. Apps must use standard Android AudioTrack/AudioRecord APIs. See [../third_party_access.md](../third_party_access.md) for details.

---

## Library Inventory

### Intel Audio HAL

| Library | Path | Size | Purpose |
|---------|------|------|---------|
| `audio.primary.broxton.so` | `/vendor/lib64/hw/` | 335 KB | Intel Broxton primary audio HAL |
| `audio.primary.default.so` | `/vendor/lib64/hw/` | - | Fallback audio HAL |
| `audio.usb.default.so` | `/vendor/lib64/hw/` | - | USB audio support |
| `audio.r_submix.default.so` | `/vendor/lib64/hw/` | - | Remote submix |

### Intel IAS (Intelligent Audio Subsystem)

| Library | Path | Size | Purpose |
|---------|------|------|---------|
| `libias-audio-smartx.so` | `/vendor/lib64/` | 2.3 MB | **SmartX audio framework** |
| `libias-audio-common.so` | `/vendor/lib64/` | 656 KB | Common audio utilities |
| `libias-audio-modules.so` | `/vendor/lib64/audio/plugin/` | 499 KB | Audio processing modules |
| `libias-media_transport-avb_streamhandler.so` | `/vendor/lib64/` | 1.5 MB | AVB stream handler |
| `libias-core_libraries-foundation.so` | `/vendor/lib64/` | 137 KB | Core foundation |
| `libias-core_libraries-base.so` | `/vendor/lib64/` | 12 KB | Base utilities |
| `libias-core_libraries-test_support.so` | `/vendor/lib64/` | 427 KB | Test support |
| `libias-android-pthread.so` | `/vendor/lib64/` | 6 KB | Android pthread wrapper |
| `libias-monitoring_and_lifecycle-watchdog_common_api-stub.so` | `/vendor/lib64/` | 20 KB | Watchdog API |

### Intel Audio Route Manager

| Library | Path | Size | Purpose |
|---------|------|------|---------|
| `libaudioroutemanager.so` | `/vendor/lib64/` | - | Audio route management |
| `libaudiohal_parameters.so` | `/vendor/lib64/` | - | HAL parameters |
| `libaudiofallback.so` | `/vendor/lib64/` | - | Audio fallback |

---

## Kernel Modules (Intel SST)

### Smart Sound Technology Drivers

| Module | Path | Purpose |
|--------|------|---------|
| `snd-soc-sst_bxt_tdf8532.ko` | `/vendor/lib/modules/.../sound/soc/intel/boards/` | Broxton + TDF8532 board driver |
| `snd-soc-sst-dsp.ko` | `/vendor/lib/modules/.../sound/soc/intel/common/` | SST DSP driver |
| `snd-soc-sst-ipc.ko` | `/vendor/lib/modules/.../sound/soc/intel/common/` | SST IPC driver |
| `snd-soc-skl.ko` | `/vendor/lib/modules/.../sound/soc/intel/skylake/` | Skylake audio driver |
| `snd-soc-skl-ipc.ko` | `/vendor/lib/modules/.../sound/soc/intel/skylake/` | Skylake IPC driver |

### Module Load Order

From `/vendor/bin/load_modules.sh`:
```
snd-soc-sst-dsp.ko
snd-soc-sst-ipc.ko
snd-soc-skl-ipc.ko
snd-soc-skl.ko
snd-soc-sst_bxt_tdf8532.ko
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    GM AAOS AUDIO ARCHITECTURE                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  APPLICATION LAYER                                                  │
│  ─────────────────                                                  │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐           │
│  │   CarPlay     │  │  Android Auto │  │  Android Apps │           │
│  │  (CINEMO/NME) │  │  (MediaCodec) │  │  (AudioTrack) │           │
│  └───────┬───────┘  └───────┬───────┘  └───────┬───────┘           │
│          │                  │                  │                    │
├──────────┴──────────────────┴──────────────────┴────────────────────┤
│                                                                     │
│  ANDROID FRAMEWORK (AOSP)                                           │
│  ────────────────────────                                           │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              AudioFlinger + AudioPolicyService               │   │
│  │              Standard Android Audio Framework                │   │
│  │                                                              │   │
│  │  12+ Output Buses:                                           │   │
│  │  bus0_media_out, bus1_navigation_out, bus2_voice_command_out │   │
│  │  bus4_call_out, bus6_notification_out, etc.                  │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                       │
├─────────────────────────────┴───────────────────────────────────────┤
│                                                                     │
│  INTEL AUDIO HAL                                                    │
│  ───────────────                                                    │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              audio.primary.broxton.so (335 KB)               │   │
│  │              Intel Broxton Primary Audio HAL                 │   │
│  │                                                              │   │
│  │  Namespace: intel_audio::                                    │   │
│  │  • Device management                                         │   │
│  │  • Stream I/O                                                │   │
│  │  • Parameter handling                                        │   │
│  │  • Microphone access                                         │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                       │
├─────────────────────────────┴───────────────────────────────────────┤
│                                                                     │
│  INTEL IAS SMARTX FRAMEWORK                                         │
│  ──────────────────────────                                         │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              libias-audio-smartx.so (2.3 MB)                 │   │
│  │              Intel Intelligent Audio Subsystem               │   │
│  │                                                              │   │
│  │  Namespace: IasAudio::                                       │   │
│  │  ┌─────────────────────────────────────────────────────┐    │   │
│  │  │  IasSmartX                                          │    │   │
│  │  │  • init() / setup() / start() / stop()             │    │   │
│  │  │  • processing() - Main processing loop              │    │   │
│  │  │  • routing() - Audio routing                        │    │   │
│  │  │  • getNextEvent() - Event handling                  │    │   │
│  │  └─────────────────────────────────────────────────────┘    │   │
│  │                                                              │   │
│  │  Components:                                                 │   │
│  │  • Routing Zones      • Audio Sink/Source Devices           │   │
│  │  • Pipelines          • Processing Modules                  │   │
│  │  • Audio Ports        • AVB Streams                         │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              libias-audio-modules.so (499 KB)                │   │
│  │              Audio Processing Modules Plugin                 │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                       │
├─────────────────────────────┴───────────────────────────────────────┤
│                                                                     │
│  PULSEAUDIO LAYER                                                   │
│  ───────────────                                                    │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              PulseAudio Server                               │   │
│  │              Audio mixing and routing                        │   │
│  │                                                              │   │
│  │  PCM Devices (from asound.conf):                             │   │
│  │  • pcmMedia_p          - Media playback                      │   │
│  │  • pcmNavi_p           - Navigation                          │   │
│  │  • pcmDnlnk_pro_p      - CarPlay downlink (processed)        │   │
│  │  • pcmBTcall_dl_pro_p  - BT call downlink (processed)        │   │
│  │  • pcmIntPhnDL_*       - Phone downlink (8k/16k/24k)         │   │
│  │  • pcmPhn_ul_pro_*     - Phone uplink (8k/16k/24k)           │   │
│  │  • pcmTTS_p            - Text-to-speech                      │   │
│  │  • pcmRingtone_p       - Ringtone                            │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                       │
├─────────────────────────────┴───────────────────────────────────────┤
│                                                                     │
│  AVB STREAM HANDLER                                                 │
│  ─────────────────                                                  │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              libias-media_transport-avb_streamhandler.so     │   │
│  │              (1.5 MB)                                        │   │
│  │                                                              │   │
│  │  Audio Video Bridging (IEEE 802.1 AVB):                      │   │
│  │  • Ethernet-based real-time audio transport                  │   │
│  │  • Deterministic latency                                     │   │
│  │  • Multi-channel support                                     │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                       │
├─────────────────────────────┴───────────────────────────────────────┤
│                                                                     │
│  HARMAN AUDIO PROCESSING                                            │
│  ───────────────────────                                            │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  libharmanaudiocontrol.so     libharmanaudiorouter.so        │   │
│  │  libaudioprocessingplugin-sse.so                             │   │
│  │                                                              │   │
│  │  • SSE (Serene Sound Engine)                                 │   │
│  │  • AEC (Acoustic Echo Cancellation)                          │   │
│  │  • NS (Noise Suppression)                                    │   │
│  │  • AGC (Automatic Gain Control)                              │   │
│  │  • CarPlay telephony tuning (SCD files)                      │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                       │
├─────────────────────────────┴───────────────────────────────────────┤
│                                                                     │
│  INTEL SST KERNEL DRIVERS                                           │
│  ────────────────────────                                           │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              Intel Smart Sound Technology                    │   │
│  │                                                              │   │
│  │  snd-soc-sst_bxt_tdf8532.ko - Board driver                  │   │
│  │  snd-soc-sst-dsp.ko         - DSP driver                    │   │
│  │  snd-soc-sst-ipc.ko         - IPC driver                    │   │
│  │  snd-soc-skl.ko             - Skylake driver                │   │
│  │  snd-soc-skl-ipc.ko         - Skylake IPC                   │   │
│  │                                                              │   │
│  │  ALSA Card: broxtontdf8532                                   │   │
│  │  Sample Rate: 48000 Hz (default)                             │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                       │
├─────────────────────────────┴───────────────────────────────────────┤
│                                                                     │
│  HARDWARE                                                           │
│  ────────                                                           │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              TDF8532 Audio Codec (NXP)                       │   │
│  │              Connected via Ethernet AVB                      │   │
│  │                                                              │   │
│  │              → External Amplifier                            │   │
│  │              → Vehicle Speakers                              │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Intel IAS SmartX API

### Core Class: IasSmartX

From binary analysis of `libias-audio-smartx.so`:

```cpp
namespace IasAudio {

class IasSmartX {
public:
    // Lifecycle
    static IasSmartX* create();
    static void destroy(IasSmartX*);

    IasResult init();
    IasResult setup();
    IasResult start();
    IasResult stop();

    // Processing
    void processing();
    void routing();
    void debug();

    // Events
    IasResult getNextEvent(std::shared_ptr<IasEvent>* event);

    // Version info
    static const char* getVersion();
    static int getMajor();
    static int getMinor();
    static int getPatch();
    static bool isAtLeast(int major, int minor, int patch);
    static bool hasFeature(const std::string& feature);

private:
    static int mNumberInstances;
    IasSmartXPriv* mPriv;
};

} // namespace IasAudio
```

### Audio Logging

```cpp
namespace IasAudio {

class IasAudioLogging {
public:
    static void registerDltContext(
        const std::string& contextId,
        const char* description
    );

    static void addDltContextItem(
        const std::string& contextId,
        DltLogLevelType logLevel,
        DltTraceStatusType traceStatus
    );
};

} // namespace IasAudio
```

---

## Intel Audio HAL API

### Namespace: intel_audio

From binary analysis of `audio.primary.broxton.so`:

```cpp
namespace intel_audio {

// Parameter keys
extern const char* gKeyDevices;
extern const char* gKeyMicMute;
extern const char* gKeyUseCases;
extern const char* gKeyAndroidMode;
extern const char* gKeyVoipBandType;
extern const char* gkeyHmiBusVolume;
extern const char* gkeyNaviBusVolume;
extern const char* gkeyMediaBusVolume;
extern const char* gKeyDeviceAddresses;
extern const char* gKeyPreProcRequested;
extern const char* gKeyFlags;

class Device {
public:
    bool setMicMute(bool mute);
    void setParameters(const std::string& params);
    void getMicrophones(std::vector<audio_microphone_characteristic_t>* mics);

    static std::mutex sGlobalLock;
};

class SampleSpec {
public:
    SampleSpec(uint32_t rate, uint32_t channels, uint32_t format,
               const std::vector<ChannelsPolicy>& policies);
};

class KeyValuePairs {
public:
    KeyValuePairs(const std::string& pairs);
    void addLiteral(const std::string& key, const std::string& value);
    std::string toString();
};

class CompressedStreamOut {
public:
    CompressedStreamOut(Device* device, int id, uint32_t flags,
                        audio_devices_t devices, const std::string& address);
};

class Patch {
public:
    Patch(int handle, PatchInterface* interface);
    void addPorts(size_t numSources, const audio_port_config* sources,
                  size_t numSinks, const audio_port_config* sinks);
    void release(bool sync);
    static int nextUniqueHandle();
};

class RouteManagerInstance {
public:
    static StreamInterface* getStreamInterface();
};

} // namespace intel_audio
```

---

## Parameter Framework Configuration

### SST Subsystem (SstSubsystem-tdf8532.xml)

```xml
<Subsystem Name="sst" Type="ALSA_CUSTOM" Mapping="Card:broxtontdf8532">
    <ComponentLibrary>
        <!-- BT HFP Band Type -->
        <ComponentType Name="BtHfpBandType">
            <EnumParameter Name="mode" Size="8">
                <ValuePair Literal="narrow-band" Numerical="0"/>
                <ValuePair Literal="wide-band" Numerical="1"/>
            </EnumParameter>
        </ComponentType>

        <!-- Main Mixer -->
        <ComponentType Name="MainMixerMatrix">
            <BooleanParameter Name="ihf_switch" Mapping="Control:'Speaker Switch'"/>
            <Component Name="bt_hfp_in" Type="BtHfpBandType"/>
            <Component Name="bt_hfp_out" Type="BtHfpBandType"/>
            <IntegerParameter Name="mic_volume" Min="0" Max="1440" ArrayLength="2"/>
            <IntegerParameter Name="voice_volume" Min="0" Max="1440"/>
        </ComponentType>
    </ComponentLibrary>
</Subsystem>
```

### SmartX Subsystem (SmartXSubsystem-eavb-master.xml)

```xml
<Subsystem Name="smartx" Type="SMARTX">
    <ComponentLibrary>
        <xi:include href="eAVB/SmartX/SmartXSubsystem-Definitions.xml"/>
        <xi:include href="eAVB/SmartX/AudioSourceDevicesLibrary-eavb-master.xml"/>
        <xi:include href="eAVB/SmartX/AudioSinkDevicesLibrary-eavb-master.xml"/>
        <xi:include href="eAVB/SmartX/ModulesLibrary.xml"/>
        <xi:include href="eAVB/SmartX/PipelineLibrary-amp.xml"/>
        <xi:include href="eAVB/SmartX/RoutingZonesLibrary-eavb-master.xml"/>
    </ComponentLibrary>

    <InstanceDefinition>
        <Component Name="audio_ports" Type="RoutingZoneAudioPorts"/>
        <Component Name="audio_sink_ports" Type="AudioSinkPorts"/>
        <Component Name="audio_source_ports" Type="AudioSourcePorts"/>
        <Component Name="audio_pins" Type="AudioPins"/>
        <Component Name="audio_source_devices" Type="AudioSourceDevices"/>
        <Component Name="audio_sink_devices" Type="AudioSinkDevices"/>
        <Component Name="avb_audio_source_devices" Type="LocalAudioStreamsFromNetwork"/>
        <Component Name="avb_audio_sink_devices" Type="LocalAudioStreamsToNetwork"/>
        <Component Name="pipelines" Type="Pipelines"/>
        <Component Name="audio_processing_modules" Type="AudioProcessingModules"/>
        <Component Name="routing_zones" Type="RoutingZones"/>
    </InstanceDefinition>
</Subsystem>
```

---

## ALSA Configuration (asound.conf)

### Default PCM Device

```
pcm.!default {
    type hw
    card broxtontdf8532
    device 0
    rate 48000
}
```

### CarPlay Audio Streams

```
# CarPlay processed downlink (from SSE)
pcm.pcmDnlnk_pro_p {
    type pulse
    device combined
}

# Phone downlink at various sample rates
pcm.pcmIntPhnDL_8k_p {
    type pulse
    device bridge_8k_1
}

pcm.pcmIntPhnDL_16k_p {
    type pulse
    device bridge_16k_1
}

pcm.pcmIntPhnDL_24k_p {
    type pulse
    device bridge_24k_1
}

# Phone uplink (processed)
pcm.pcmPhn_ul_pro_8k_p {
    type pulse
    device bridge_8k_2
}

pcm.pcmPhn_ul_pro_16k_p {
    type pulse
    device bridge_16k_2
}

pcm.pcmPhn_ul_pro_24k_p {
    type pulse
    device bridge_24k_2
}
```

### Other Audio Streams

```
pcm.pcmMedia_p       { type pulse; device combined }
pcm.pcmNavi_p        { type pulse; device combined }
pcm.pcmCommNotify_p  { type pulse; device combined }
pcm.pcmTTS_p         { type pulse; device combined }
pcm.pcmRingtone_p    { type pulse; device combined }
pcm.pcmCue_p         { type pulse; device combined }
pcm.pcmEcall_p       { type pulse; device combined }
```

---

## Comparison with Intel Media SDK (Video)

| Aspect | Intel Media SDK (Video) | Intel IAS SmartX (Audio) |
|--------|------------------------|-------------------------|
| **Public API** | Yes (MFX functions) | No |
| **Documentation** | Extensive (1.4 MB PDF) | None publicly available |
| **Open Source** | Partial (VA-API) | No |
| **SDK Download** | Yes (GitHub) | No |
| **Standard Interface** | OMX IL / MediaCodec | Android Audio HAL |
| **Configuration** | media_codecs.xml | Parameter Framework XML |

---

## Comparison with Standard Android Audio

| Layer | GM AAOS (Intel) | Standard Android |
|-------|-----------------|------------------|
| **Application** | AudioTrack/AudioFlinger | AudioTrack/AudioFlinger |
| **Policy** | AudioPolicyService | AudioPolicyService |
| **HAL** | audio.primary.broxton.so (Intel) | audio.primary.*.so (SoC vendor) |
| **Middleware** | IAS SmartX + PulseAudio | None (direct HAL) |
| **Kernel** | Intel SST (ALSA) | ALSA or other |
| **Transport** | AVB (Ethernet) | I2S / TDM / USB |

---

## Audio Bus Routing

### Output Buses (to amplifier)

| Bus | Purpose | CarPlay | Android Auto |
|-----|---------|---------|--------------|
| bus0_media_out | Media playback | Yes | Yes |
| bus1_navigation_out | Navigation prompts | Yes | Yes |
| bus2_voice_command_out | Siri / Google Assistant | Yes | Yes |
| bus3_call_ring_out | Call ring | Yes | Yes |
| bus4_call_out | Phone call audio | Yes | Yes |
| bus5_alarm_out | Alarms | No | No |
| bus6_notification_out | Notifications | Yes | Yes |
| bus7_system_out | System sounds | No | No |

### Input Buses (from microphone)

| Bus | Purpose | Sample Rate |
|-----|---------|-------------|
| mic_for_vc | Voice commands | 16-48 kHz |
| mic_for_phone | Phone calls | 8-24 kHz |

---

## Usage by Projection Protocol

### CarPlay

```
iPhone → AirPlay → libNmeCarPlay.so → libNmeAudioAAC.so
    → AudioFlinger → Intel HAL → IAS SmartX → PulseAudio
    → AVB → TDF8532 → Amplifier → Speakers
```

Microphone path:
```
Microphone → Intel SST → Harman preprocessing (AEC/NS/AGC)
    → Intel HAL → AudioFlinger → libNmeCarPlay.so
    → AirPlay → iPhone
```

### Android Auto

```
Phone → AAP → CarAudioService → AudioFlinger
    → Intel HAL → IAS SmartX → PulseAudio
    → AVB → TDF8532 → Amplifier → Speakers
```

---

## No Public Documentation

Unlike Intel Media SDK which has:
- Official API reference PDF
- Developer's guide
- GitHub repository
- Community forum

Intel IAS SmartX has **no publicly available documentation**. The API information in this document was obtained through binary analysis (`strings`, `nm`, `readelf`).

For Intel audio development, the closest public resource is:
- Intel Audio Development Kit (ADK) - Contact Intel directly
- ALSA/ASoC kernel documentation for SST

---

## Data Sources

**Extracted from GM AAOS:**
- `/vendor/lib64/hw/audio.primary.broxton.so` - Binary analysis
- `/vendor/lib64/libias-audio-*.so` - Binary analysis
- `/vendor/lib/modules/.../sound/soc/intel/` - Kernel modules
- `/vendor/etc/parameter-framework/` - XML configurations
- `/vendor/etc/asound.conf` - ALSA configuration

**Source:** `/Users/zeno/Downloads/misc/GM_research/gm_aaos/`
