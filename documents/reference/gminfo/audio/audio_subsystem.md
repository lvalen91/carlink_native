# GM Infotainment Audio Subsystem Specifications

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** December 2025 - February 2026

> **Note:** All audio/video/codec configuration files are IDENTICAL between Y177 and Y181 builds. Behavioral differences between these builds are only in SELinux enforcement (Y177 permissive, Y181 enforcing).

---

## Audio System Overview

The GM infotainment system uses Android Automotive's multi-zone audio architecture with dedicated audio buses for different audio contexts. Audio is routed through an external DSP/amplifier system via AUDIO_DEVICE_OUT_BUS interfaces.

---

## Audio HAL Configuration

### HAL Version
- **HIDL Interface:** `vendor.hardware.audio@5.0` (Harman custom service — `vendor.hardware.audio@5.0-harman-custom-service`)
- **Audio Policy halVersion:** 3.0 (declared in `audio_policy_configuration.xml`). Note: The HIDL service interface version (5.0) and the audio policy module halVersion (3.0) are distinct — the former is the IPC transport, the latter is the policy engine version.
- **HarmanAudioControl:** Harman "Titan" HarmanAudioControl
- **Configuration:** `/vendor/etc/audio_policy_configuration.xml`

### System Properties

```properties
audio.safemedia.bypass=true
ro.audio.flinger_standbytime_ms=0
init.svc.audioserver=running
init.svc.vehicleaudiocontrol=running
speaker_drc_enabled=false
```

> **AudioFlinger standby:** `ro.audio.flinger_standbytime_ms=0` means mixer threads NEVER enter standby — they remain active at all times.

> **DRC disabled:** `speaker_drc_enabled=false` — Dynamic Range Compression is disabled at the Android level. The audio system relies on the external Harman DSP for dynamics processing.

---

## Audio Service Init Chain

Key audio services in boot order:

| Order | Service | Binary / Script | Details |
|-------|---------|-----------------|---------|
| 1 | earlyaudioalsa | `early_audio_alsa.sh` | ALSA device setup |
| 2 | gPTP + avb_streamhandler | avb_streamhandler | SCHED_FIFO 50, params: `-t GM3 -p CSM`, ringbuffer=9216 |
| 3 | earlyavbaudio + eavbmgr | eavbmgr | Runs as `vendor_eavbmgr` user; waits for AVB ready |
| 4 | vendor.audio-hal-2-0 | `vendor.hardware.audio@5.0-harman-custom-service` | User: `audioserver`, ioprio: `rt 4`; waits for PulseAudio ready |
| 5 | rtpolicy_pulseaudio | — | RT scheduling adjustments for PulseAudio threads |
| 6 | tuning_service | `vendor.harman.audio.tuning@1.0-service` | Harman audio tuning HIDL service |
| 7 | vehicleaudiocontrol | gmvt | Config: `vendor.etc/gmvt.cfg` |

---

## AudioFlinger Configuration

### Primary Output Thread (AudioOut_D)

| Property | Value |
|----------|-------|
| I/O Handle | 13 |
| Sample Rate | 48000 Hz |
| Format | PCM_16_BIT (0x1) |
| Channel Count | 2 (Stereo) |
| Channel Mask | front-left, front-right |
| HAL Frame Count | 384 frames |
| HAL Buffer Size | 1536 bytes |
| Processing Format | PCM_FLOAT |
| Output Device | AUDIO_DEVICE_OUT_BUS |

### Timing Characteristics

| Metric | Value |
|--------|-------|
| Mix Period | 8.00 ms |
| Latency | 24.00 ms |
| Normal Frame Count | 768 frames |
| Fast Track Count | 8 maximum |
| Standby Delay | 0 ns (never enters standby) |

### FastMixer Thread

| Property | Value |
|----------|-------|
| Sample Rate | 48000 Hz |
| Frame Count | 384 |
| Warmup Time | ~40.5 ms |
| Warmup Cycles | 6 |

---

## Output Audio Buses

The system uses 14 output buses (12 externally-routed) for audio routing:

| Bus Address | Purpose | Usage |
|-------------|---------|-------|
| `bus0_media_out` | Media playback | MEDIA, GAME, UNKNOWN |
| `bus1_navigation_out` | Navigation guidance | ASSISTANCE_NAVIGATION_GUIDANCE |
| `bus2_voice_command_out` | Voice assistant | ASSISTANT, ACCESSIBILITY |
| `bus3_call_ring_out` | Call ringtone | NOTIFICATION_TELEPHONY_RINGTONE |
| `bus4_call_out` | Voice calls | VOICE_COMMUNICATION |
| `bus5_alarm_out` | Alarms | ALARM |
| `bus6_notification_out` | Notifications | NOTIFICATION variants |
| `bus7_system_sound_out` | System sounds | ASSISTANCE_SONIFICATION |
| `bus8_ecall_ring_out` | Emergency call ring | eCall |
| `bus11_mix_unduck_out` | Mix unduck | Priority audio |
| `bus12_audio_cue_out` | Audio cues | System cues |
| `bus13_high_priority_mutex_out` | High priority mutex | Critical audio |

### Bus Configuration Details

All output buses share common configuration:

| Property | Value |
|----------|-------|
| Format | AUDIO_FORMAT_PCM_16_BIT |
| Sample Rate | 48000 Hz |
| Channel Mask | AUDIO_CHANNEL_OUT_STEREO |
| **Bus Gain Range** | **0 to +63 dB** |
| **Bus Default Gain** | **+30 dB** |
| Step Size | 1 dB (100 mB) |

> **Speaker device gain model (distinct from bus gain):** The Speaker output device uses an attenuation model: -128 dB to 0 dB, default -30 dB, 1 dB steps. Bus outputs use amplification (0 to +63 dB), while the Speaker device uses attenuation (-128 to 0 dB).

### bus0_media_out Multi-Rate Support

`bus0_media_out` is unique among output buses — it supports multiple sample rates and channel configurations:

| Property | bus0_media_out | All Other Output Buses |
|----------|----------------|------------------------|
| Sample Rates | 8000, 16000, 24000, 48000 Hz | 48000 Hz only |
| Channel Masks | MONO + STEREO | STEREO only |

This allows bus0 to accept media from diverse sources (including telephony-rate 8kHz streams) without intermediate resampling at the HAL level.

### Bus-Specific Buffer Sizes

Most buses use the default 384-frame HAL buffer (8ms @ 48kHz). Two buses use reduced buffers for lower latency:

| Bus | HAL Buffer | Latency |
|-----|-----------|---------|
| bus2_voice_command_out | 192 frames | 4ms (low-latency for Siri/Assistant) |
| bus4_call_out | 192 frames | 4ms (low-latency for voice calls) |
| All others | 384 frames | 8ms (standard) |

### Harman SSE Configuration

Sound Configuration Data (SCD) files per mode in `/vendor/etc/scd/`. Full set of 21 tuning files:

**CarPlay Wired (6 files):**
| File | Bandwidth | Notes |
|------|-----------|-------|
| NB | Narrowband | Base wired |
| NB_48kHz_DL | Narrowband | 48kHz downlink variant |
| WB | Wideband | Base wired |
| WB_48kHz_DL | Wideband | 48kHz downlink variant |
| FT_SWB | Super-wideband | FaceTime |
| FT_SWB_48kHz_DL | Super-wideband | FaceTime, 48kHz downlink |

**CarPlay Wireless (6 files):**
| File | Bandwidth | Notes |
|------|-----------|-------|
| WiFi_NB | Narrowband | WiFi prefix |
| WiFi_NB_48kHz | Narrowband | 48kHz variant |
| WiFi_WB | Wideband | WiFi prefix |
| WiFi_WB_48kHz | Wideband | 48kHz variant |
| WiFi_SWB | Super-wideband | WiFi prefix |
| WiFi_FT_SWB_48kHz | Super-wideband | FaceTime, 48kHz |

**Bluetooth (2 files):**
| File | Bandwidth |
|------|-----------|
| BTTelNBTuningSet | Narrowband |
| BTTelWB | Wideband |

**Android Auto / OnStar / VoIP (7 files):**
| File | Bandwidth | Notes |
|------|-----------|-------|
| GoogleAutoTelWB | Wideband | Android Auto telephony |
| DigOnstarAdvisorWB | Wideband | OnStar Advisor |
| DigOnstarECallWB | Wideband | OnStar eCall |
| WB_VoIP | Wideband | VoIP base |
| WB_VoIP_48kHz | Wideband | VoIP 48kHz variant |
| SWB_VoIP | Super-wideband | VoIP base |
| SWB_VoIP_48kHz | Super-wideband | VoIP 48kHz variant |

**Naming pattern:** base + `_48kHz_DL` variant for downlink rate. WiFi adds `WiFi_` prefix. Three bandwidth tiers: NB (narrowband) / WB (wideband) / SWB (super-wideband).

- Harman preprocessing (`harman_Preprocessing`) active during Siri sessions

---

## Input Audio Devices

### Built-In Microphone

| Property | Value |
|----------|-------|
| Device Type | AUDIO_DEVICE_IN_BUILTIN_MIC |
| Address | bottom |
| Format | PCM_16_BIT |
| Sample Rates | 16000, 24000, 48000 Hz |
| Channel Masks | Mono, Stereo, Front-Back |
| Processed Channel Masks | IN_LEFT_PROCESSED, IN_RIGHT_PROCESSED, IN_FRONT_PROCESSED |

> **Mic sample rate correction:** The Built-In Mic device port supports 16000/24000/48000 Hz (NOT 8000 Hz). The mixPort performs resampling from 16kHz minimum to service 8kHz capture requests from applications.

> **Processed channel masks:** IN_LEFT_PROCESSED, IN_RIGHT_PROCESSED, and IN_FRONT_PROCESSED indicate Harman preprocessing is available at the mic device level (before the stream reaches the application).

### Input Buses

| Bus Address | Purpose | Sample Rate | Device Port Channels | MixPort Channels |
|-------------|---------|-------------|----------------------|------------------|
| `bus3_sxm_in` | SiriusXM | 48000 Hz | Stereo | Stereo |
| `bus4_lvm_in` | LVM | 48000 Hz | Stereo | Stereo |
| `bus5_tcp_phone_dnlink_in` | Phone downlink | 48000 Hz | Stereo | Stereo |
| `bus6_tcp_prompt_in` | TCP prompt | 48000 Hz | **Stereo (device port)** | **Mono (mixPort capture)** |
| `bus7_bt_dnlink_in` | Bluetooth downlink | 48000 Hz | Stereo | Stereo |
| `bus8_tuner_in` | FM Tuner | 48000 Hz | Stereo | Stereo |
| `bus9_tcp_phone_emerg_dnlink_in` | Emergency phone | 48000 Hz | Stereo | Stereo |
| `bus10_tuner_am_in` | AM Tuner | 48000 Hz | Stereo | Stereo |
| `bus11_lvm_prompt_in` | LVM prompt | 48000 Hz | Mono | Mono |
| `bus12_tcp_mixprompt_in` | Mix prompt | 48000 Hz | **Stereo (device port)** | **Mono (mixPort capture)** |
| `bus13_dab_in` | DAB radio | 48000 Hz | Stereo | Stereo |
| `bus14_rsi_in` | RSI | 48000 Hz | Stereo | Stereo |

> **Channel mismatch note:** `bus6_tcp_prompt_in` and `bus12_tcp_mixprompt_in` have STEREO device ports but their corresponding mixPorts capture in MONO. The HAL performs the stereo-to-mono downmix.

### Special Input Ports

| Port | Type | Purpose | Sample Rates |
|------|------|---------|--------------|
| Echo-Reference Mic | ECHO_REFERENCE | AEC reference | 48000 Hz |
| mic_for_vc | BUS (Voice_Uplink) | Voice call uplink | 8000-24000 Hz |
| mic_for_raw | DEFAULT | Raw microphone | 48000 Hz |
| TelephonyTx | TELEPHONY_TX | VoIP TX | 8000, 16000, 24000 Hz (Mono) |

> **TelephonyTx:** Routes from "direct output" mixPort (VOIP_RX flag). Used for VoIP transmit path.

---

## Audio Codecs

### Audio Decoders

| Codec | MIME Type | Max Channels | Sample Rates | Bitrate |
|-------|-----------|--------------|--------------|---------|
| AAC | audio/mp4a-latm | 8 | 7.35-48 kHz | 8-960 Kbps |
| MP3 | audio/mpeg | 2 | 8-48 kHz | 8-320 Kbps |
| Opus | audio/opus | 8 | 48 kHz | 6-510 Kbps |
| Vorbis | audio/vorbis | 8 | 8-96 kHz | 32-500 Kbps |
| FLAC | audio/flac | 8 | 1-655 kHz | 1-21 Mbps |
| AMR-NB | audio/3gpp | 1 | 8 kHz | 4.75-12.2 Kbps |
| AMR-WB | audio/amr-wb | 1 | 16 kHz | 6.6-23.85 Kbps |
| G.711 A-law | audio/g711-alaw | 6 | 8-48 kHz | 64 Kbps |
| G.711 u-law | audio/g711-mlaw | 6 | 8-48 kHz | 64 Kbps |
| PCM Raw | audio/raw | 8 | 8-192 kHz | - |

### AAC Profiles Supported

| Profile ID | Profile Name |
|------------|--------------|
| 2 | AAC-LC (Low Complexity) |
| 5 | HE-AAC (High Efficiency) |
| 29 | HE-AAC v2 (with Parametric Stereo) |
| 23 | AAC-LD (Low Delay) |
| 39 | AAC-ELD (Enhanced Low Delay) |
| 42 | xHE-AAC (Extended HE) |

### Audio Encoders

| Codec | MIME Type | Max Channels | Sample Rates | Bitrate |
|-------|-----------|--------------|--------------|---------|
| AAC | audio/mp4a-latm | 6 | 8-48 kHz | 8-512 Kbps |
| AMR-NB | audio/3gpp | 1 | 8 kHz | 4.75-12.2 Kbps |
| AMR-WB | audio/amr-wb | 1 | 16 kHz | 6.6-23.85 Kbps |
| FLAC | audio/flac | 2 | 1-655 kHz | Lossless |
| Opus | audio/opus | 2 | 8-48 kHz | 6-510 Kbps |

---

## Audio Streams and Volume

### Stream Types

| Stream | Index Range | Default | Mute Affected |
|--------|-------------|---------|---------------|
| VOICE_CALL | 1-40 | 40 | No |
| SYSTEM | 0-40 | 40 | Yes |
| RING | 0-40 | 40 | Yes |
| MUSIC | 0-40 | 40 | Yes |
| ALARM | 0-40 | 40 | Yes |
| NOTIFICATION | 0-40 | 40 | Yes |
| BLUETOOTH_SCO | 0-40 | 40 | Yes |
| SYSTEM_ENFORCED | 0-40 | 40 | Yes |
| DTMF | 0-40 | 40 | Yes |
| TTS | 0-15 | 15 | No |
| ACCESSIBILITY | 1-40 | 40 | No |
| ASSISTANT | 0-40 | 40 | No |

> **TTS index range:** TTS has `indexMax=15` (all other streams use `indexMax=40`).

### Volume Configuration

| Property | Value |
|----------|-------|
| Fixed Volume Mode | Enabled |
| Safe Media Volume | Disabled (bypassed) |
| Ringer Mode | NORMAL |
| Use Fixed Volume | true |

### Volume Architecture

Volume curves for standard strategies (media, speech, system, phone, ring) are **flat at 0 dB across all index points** — confirming that volume control is entirely DSP-controlled (not managed by AudioFlinger).

**Attenuation curves exist for OEM strategies:**

| Strategy | Curve Range | Notes |
|----------|-------------|-------|
| oem_traffic_announcement | -42 dB to 0 dB | Ducking curve |
| oem_adas_2 | -42 dB to 0 dB | ADAS alert level 2 |
| oem_adas_3 | -24 dB to 0 dB | ADAS alert level 3 (less attenuation) |
| media_car_audio_type_3 | -42 dB to 0 dB | Radio source |
| media_car_audio_type_7 | -42 dB to 0 dB | External audio source |

---

## Audio Effects

### Loaded Effect Libraries

| Library | Path | Purpose |
|---------|------|---------|
| pre_processing | libharmanpreprocessing_gm.so | Harman audio preprocessing |
| dynamics_processing | libdynproc.so | Dynamics processing |
| loudness_enhancer | libldnhncr.so | Loudness enhancement |
| downmix | libdownmix.so | Multichannel downmix |
| visualizer | libvisualizer.so | Audio visualization |
| reverb | libreverbwrapper.so | Reverb effects |
| bundle | libbundlewrapper.so | NXP effects bundle |

### Available Effects

| Effect Name | UUID | Type | Vendor |
|-------------|------|------|--------|
| Noise Suppression | 1d97bb0b-9e2f-4403-9ae3-58c2554306f8 | NS | Harman |
| Acoustic Echo Canceler | 0f8d0d2a-59e5-45fe-b6e4-248c8a799109 | AEC | Harman |
| Automatic Gain Control | 0dd49521-8c59-40b1-b403-e08d5f01875e | AGC | Harman |
| Bass Boost | 8631f300-72e2-11df-b57e-0002a5d5c51b | BB | NXP |
| Virtualizer | 1d4033c0-8557-11df-9f2d-0002a5d5c51b | VIRT | NXP |
| Equalizer | ce772f20-847d-11df-bb17-0002a5d5c51b | EQ | NXP |
| Volume | 119341a0-8469-11df-81f9-0002a5d5c51b | VOL | NXP |
| Environmental Reverb (Aux) | 4a387fc0-8ab3-11df-8bad-0002a5d5c51b | REVERB | NXP |
| Environmental Reverb (Insert) | c7a511a0-a3bb-11df-860e-0002a5d5c51b | REVERB | NXP |
| Preset Reverb (Aux) | f29a1400-a3bb-11df-8ddc-0002a5d5c51b | REVERB | NXP |
| Preset Reverb (Insert) | 172cdf00-a3bc-11df-a72f-0002a5d5c51b | REVERB | NXP |
| Visualizer | d069d9e0-8329-11df-9168-0002a5d5c51b | VIS | AOSP |
| Downmix | 93f04452-e4fe-41cc-91f9-e475b6d1d69f | DOWNMIX | AOSP |
| Loudness Enhancer | fa415329-2034-4bea-b5dc-5b381c8d1e2c | LE | AOSP |
| Dynamics Processing | e0e6539b-1781-7261-676f-6d7573696340 | DYN | AOSP |

### Pre-Processing Configuration

Applied to voice streams:

| Stream Type | Effects Applied |
|-------------|-----------------|
| voice_communication | AEC, NS |
| voice_recognition | AEC, NS |

> **No postprocess effects are auto-applied** to any output stream. Only preprocessing (AEC+NS) is auto-applied to `voice_communication` and `voice_recognition` input streams.

> **Known bug:** Bass Boost effect has a known crash bug (OAM-68864, Harman). Avoid programmatic use.

---

## Audio Policy Strategies

### Product Strategies

Strategies map to **volume groups** (which then route to bus outputs), not directly to bus outputs. The Output column shows the final bus destination via the volume group routing.

| Strategy | ID | Audio Usage | Volume Group | Output Bus |
|----------|----|--------------| -------------|------------|
| oem_traffic_anouncement | 14 | NAVIGATION_GUIDANCE | — (OEM) | bus1_navigation_out |
| oem_strategy_1 | 15 | NAVIGATION_GUIDANCE | — (OEM) | bus1_navigation_out |
| oem_strategy_2 | 16 | NAVIGATION_GUIDANCE | — (OEM) | bus1_navigation_out |
| radio | 17 | MEDIA (car_audio_type=3) | Group 5 (music) | bus0_media_out |
| ext_audio_source | 18 | MEDIA (car_audio_type=7) | Group 5 (music) | bus0_media_out |
| voice_command | 19 | ASSISTANT, ACCESSIBILITY | Group 2 (voice_command) | bus2_voice_command_out |
| safety_alert | 20 | NOTIFICATION (car_audio_type=2) | Group 0 (system) | bus6_notification_out |
| music | 21 | MEDIA, GAME | Group 5 (music) | bus0_media_out |
| nav_guidance | 22 | NAVIGATION_GUIDANCE | Group 1 (navigation) | bus1_navigation_out |
| voice_call | 23 | VOICE_COMMUNICATION | Group 4 (call) | bus4_call_out |
| alarm | 24 | ALARM | Group 0 (system) | bus5_alarm_out |
| ring | 25 | TELEPHONY_RINGTONE | Group 3 (call_ring) | bus3_call_ring_out |
| notification | 26 | NOTIFICATION | Group 0 (system) | bus6_notification_out |
| system | 27 | SONIFICATION | Group 0 (system) | bus7_system_sound_out |
| tts | 28 | TTS | — (TTS) | bus2_voice_command_out |

> **OEM strategy attributes:** OEM strategies use Bundle key `"oem"` (values 1-3) and `car_audio_type` (values 1, 2, 3, 7) to differentiate routing within the same base Android usage type.

---

## Car Audio Zone Configuration

Single primary zone with 6 volume groups:

| Volume Group | ID | Audio Contexts | Controlled Buses |
|--------------|----|----------------|------------------|
| Group 0 | 0 | system, alarm, notification | bus7_system_sound_out, bus5_alarm_out, bus6_notification_out |
| Group 1 | 1 | navigation | bus1_navigation_out |
| Group 2 | 2 | voice_command | bus2_voice_command_out |
| Group 3 | 3 | call_ring | bus3_call_ring_out |
| Group 4 | 4 | call | bus4_call_out |
| Group 5 | 5 | music | bus0_media_out |

**Buses NOT in any volume group (DSP-controlled independently):**
- `bus8_ecall_ring_out` — Emergency call ring
- `bus11_mix_unduck_out` — Mix unduck
- `bus12_audio_cue_out` — Audio cues
- `bus13_high_priority_mutex_out` — High priority mutex

These buses are controlled directly by the external DSP/amplifier, outside of Android volume management.

---

## Audio Routing (Audio Patches)

Active audio patches route mixer outputs to bus devices:

| Patch | Source | Sink |
|-------|--------|------|
| 1 | Mix ID 1 (handle 13) | bus0_media_out |
| 2 | Mix ID 5 (handle 21) | bus1_navigation_out |
| 3 | Mix ID 8 (handle 29) | bus2_voice_command_out |
| 4 | Mix ID 11 (handle 37) | bus3_call_ring_out |
| 5 | Mix ID 14 (handle 45) | bus4_call_out |
| 6 | Mix ID 17 (handle 53) | bus5_alarm_out |
| 7 | Mix ID 20 (handle 61) | bus6_notification_out |
| 8 | Mix ID 23 (handle 69) | bus7_system_sound_out |
| 9 | Mix ID 26 (handle 77) | bus8_ecall_ring_out |
| 10 | Mix ID 29 (handle 85) | bus11_mix_unduck_out |
| 11 | Mix ID 32 (handle 93) | bus12_audio_cue_out |
| 12 | Mix ID 35 (handle 101) | bus13_high_priority_mutex_out |

### Audio Routing Cross-Paths

External input buses route directly to output buses via audio policy device-to-device routes, bypassing AudioFlinger mixing:

| Output Bus | Sources (mixPort + direct input buses) |
|------------|----------------------------------------|
| `bus0_media_out` | mixport + `bus3_sxm_in` + `bus4_lvm_in` + `bus8_tuner_in` + `bus10_tuner_am_in` + `bus13_dab_in` + `bus14_rsi_in` |
| `bus1_navigation_out` | mixport + `bus12_tcp_mixprompt_in` |
| `bus2_voice_command_out` | mixport + `bus6_tcp_prompt_in` + `bus7_bt_dnlink_in` |
| `bus4_call_out` | mixport + `bus7_bt_dnlink_in` + `bus5_tcp_phone_dnlink_in` + `bus9_tcp_phone_emerg_dnlink_in` |
| `bus6_notification_out` | mixport + `bus11_lvm_prompt_in` |

> **Note:** `bus7_bt_dnlink_in` routes to BOTH `bus2_voice_command_out` and `bus4_call_out` — Bluetooth downlink serves both voice assistant and telephony paths.

---

## PulseAudio Crossbar Architecture

Below the Android AudioFlinger, audio passes through a PulseAudio 34-channel combine sink that acts as a crossbar mixer:

```
14 Android buses -> AudioFlinger -> Harman HAL
  -> PulseAudio 34-channel combine sink (crossbar mixing)
    -> TDM output: 8ch s32le 48kHz -> broxtontdf8532 (I2S to codec)
    -> AVB output: 6ch s16le 48kHz -> csm_amp (amplifier over Ethernet)
    -> AVB output: 1ch s16le 48kHz -> csm_tcp (telemetry/control)
```

### AVB Streamhandler
- Version: v3.2.7.2, target=GM3, platform=CSM, profile=CSM
- Scheduling: SCHED_FIFO priority 50
- 3 Rx multicast streams: `91:e0:f0:00:fe:{04,05,07}`
- 3 Tx streams (to amplifier + TCP)
- gPTP: master role, 1Gbps Ethernet
- Known issues: TX worker oversleep, ALSA engine oversleep (scheduling jitter)

### EAVB Mode Configuration

AudioParameterFramework configuration selected based on EAVB mode:

| Mode | Config File |
|------|-------------|
| No EAVB | `AudioParameterFramework-tdf8532-no-eavb.xml` |
| EAVB Master (raw) | `AudioParameterFramework-tdf8532-eavb-master-raw.xml` |
| EAVB Master | `AudioParameterFramework-tdf8532-eavb-master.xml` |
| EAVB Slave | `AudioParameterFramework-tdf8532-eavb-slave.xml` |

### SmartX ALSA Plugin

Intel IAS SmartX integration at the ALSA level via custom PCM plugin:

```
pcm_type.smartx {
    lib "libasound_module_pcm_smartx.so"
}
```

Defined in `asound.rc`. Confirms Intel Intelligent Audio System (IAS) SmartX is the underlying ALSA routing layer between PulseAudio and hardware.

### I2C Audio Bus

- **Bus:** `i2c-3` owned by `audioserver` (chmod 0660 in `init.audio.rc`)
- **Purpose:** TDF8532 codec control (register configuration, power management)

---

## Audio Focus Management

### Focus Policy

| Property | Value |
|----------|-------|
| External Focus Policy | Enabled |
| Multi Audio Focus | Disabled |
| Focus Owner | com.gm.gmaudio.tuner |

### Audio Attributes to Bus Mapping

| Audio Usage | Destination Bus |
|-------------|-----------------|
| USAGE_MEDIA | bus0_media_out |
| USAGE_GAME | bus0_media_out |
| USAGE_ASSISTANCE_NAVIGATION_GUIDANCE | bus1_navigation_out |
| USAGE_ASSISTANT | bus2_voice_command_out |
| USAGE_ASSISTANCE_ACCESSIBILITY | bus2_voice_command_out |
| USAGE_NOTIFICATION_TELEPHONY_RINGTONE | bus3_call_ring_out |
| USAGE_VOICE_COMMUNICATION | bus4_call_out |
| USAGE_ALARM | bus5_alarm_out |
| USAGE_NOTIFICATION | bus6_notification_out |
| USAGE_ASSISTANCE_SONIFICATION | bus7_system_sound_out |

---

## Performance Metrics

### Output Statistics (Primary)

| Metric | Value |
|--------|-------|
| Total Writes | 17,405 |
| Delayed Writes | 0 |
| Underruns | 0 |
| Overruns | 0 |
| Frames Written | 13,367,040 |

### Jitter Statistics

| Metric | Value |
|--------|-------|
| Average | 0.028 ms |
| Std Dev | 0.181 ms |
| Min | -1.165 ms |
| Max | 1.186 ms |

---

## Recommendations

### CarPlay/Android Auto Audio

| Parameter | Recommended |
|-----------|-------------|
| Sample Rate | 48000 Hz |
| Format | PCM_16_BIT |
| Channels | Stereo |
| Audio Usage | USAGE_MEDIA |
| Destination | bus0_media_out |

### Voice Assistant Integration

| Parameter | Recommended |
|-----------|-------------|
| Sample Rate | 16000 or 48000 Hz |
| Format | PCM_16_BIT |
| Input Device | Built-In Mic |
| Audio Usage | USAGE_ASSISTANT |
| Effects | AEC + NS (Harman) |

### Low-Latency Audio

| Metric | Value |
|--------|-------|
| Min Latency | ~24 ms |
| Mix Period | 8 ms |
| Buffer Size | 384 frames (8 ms @ 48kHz) |
