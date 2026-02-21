# CPC200-CCPA Audio Format Analysis

> **[Firmware]** This document covers firmware-level audio format support derived from binary analysis of the CPC200-CCPA adapter. For the capture-verified protocol-level reference (wire format, command sequences, stream routing), see `../02_Protocol_Reference/audio_protocol.md`.

**Status:** Documented from binary analysis
**Source:** ARMadb-driver_unpacked, ARMiPhoneIAP2_unpacked binary analysis
**Firmware Version:** 2025.10.15.1127 (binary analysis reference version)
**Last Updated:** 2026-02-19

---

## Overview

The CPC200-CCPA adapter performs **active audio processing** rather than simple passthrough. The firmware includes WebRTC audio processing modules (AEC, AGC, NS) and handles multiple audio streams with different sample rates.

---

## Audio Format Mapping (decodeType)

The adapter uses a `decodeType` value (4-byte LE integer) in AudioData messages to specify audio format:

> **[Firmware Binary Analysis]** The following values were extracted from firmware binary analysis. Only decodeType 2, 4, and 5 have been verified in USB captures. See `../02_Protocol_Reference/audio_protocol.md` for the capture-verified subset.

| decodeType | Sample Rate | Channels | Bits | Use Case | Capture Status |
|------------|-------------|----------|------|----------|----------------|
| 1 | 44100 Hz | 2 (stereo) | 16 | Media playback (44.1kHz CD quality) | **[Not observed on USB]** |
| 2 | 44100 Hz | 2 (stereo) | 16 | 44.1kHz media OR stop/cleanup commands (dual-purpose) | Capture-verified |
| 3 | 8000 Hz | 1 (mono) | 16 | Phone call narrow-band **(LEGACY - not observed in modern implementations, CarPlay uses 16kHz)** | **[Not observed on USB]** |
| 4 | 48000 Hz | 2 (stereo) | 16 | Media HD / Standard CarPlay | Capture-verified |
| 5 | 16000 Hz | 1 (mono) | 16 | Siri / Phone / Mic input | Capture-verified |
| 6 | 24000 Hz | 1 (mono) | 16 | Voice recognition | **[Not observed on USB]** |
| 7 | 16000 Hz | 2 (stereo) | 16 | Stereo voice | **[Not observed on USB]** |

### Semantic Context (from firmware analysis)

The decodeType also indicates semantic context for audio commands:

- **decodeType=2**: Stop/cleanup operations (seen with MEDIA_STOP, PHONECALL_STOP)
- **decodeType=4**: Standard CarPlay audio output (MEDIA_START, NAVI_*, ALERT_*, OUTPUT_*)
- **decodeType=5**: Mic/input operations (SIRI_*, PHONECALL_START, INPUT_*, INCOMING_CALL_INIT)

---

## Audio Processing Pipeline

### Direction: Phone → Adapter → Host (Playback)

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  CarPlay/AA  │────►│   Adapter    │────►│   Host App   │
│   (Phone)    │iAP2 │  ARMadb-drv  │USB  │  (Playback)  │
└──────────────┘     └──────────────┘     └──────────────┘

Audio Types:
- Media: 48kHz stereo (decodeType=4) or 44.1kHz stereo (decodeType=1,2)
- Navigation: 48kHz stereo (decodeType=4) or 44.1kHz stereo (decodeType=2)
- Alerts: 48kHz stereo (decodeType=4)
- Phone Call: 8kHz or 16kHz mono (decodeType=3 or 5)

Processing: Minimal - primarily pass-through with format signaling
```

**Key Difference from Microphone Input:** Unlike mic input where WebRTC AECM explicitly validates sample rates (only 8kHz/16kHz accepted), **playback audio has no strict firmware validation**. The adapter passes through whatever format CarPlay/Android Auto sends.

**Format Negotiation:** The phone/CarPlay determines the playback format during session setup. The adapter signals the format to the host via `decodeType` but does not reject or validate incoming audio.

**Resampling Capability (Binary Evidence):**
The firmware includes format conversion functions if needed:
- `AudioConvertor::ReSample` at `0x6410` in libdmsdpaudiohandler.so
- `AudioConvertor::SteroToMono` - stereo to mono conversion
- `AudioConvertor::GetConvertSrcSamples` / `GetConvertDstSamples` - sample count conversion

These allow the adapter to handle format mismatches, though in practice CarPlay/AA audio is passed through directly.

### Direction: Host → Adapter → Phone (Microphone)

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   Host App   │────►│   Adapter    │────►│  CarPlay/AA  │
│    (Mic)     │USB  │  AGC + AEC   │iAP2 │   (Phone)    │
└──────────────┘     └──────────────┘     └──────────────┘

Processing Applied:
1. AGC (Automatic Gain Control) - WebRtcAgc_Process
2. AEC (Acoustic Echo Cancellation) - WebRtcAecm_*
3. NS (Noise Suppression) - WebRtcNs_*

Expected Mic Format (WebRTC validated):
- Siri: 16kHz mono (decodeType=5)
- Phone Call: 8kHz or 16kHz mono (decodeType=3 or 5)
- NOTE: Only 8kHz and 16kHz are accepted by WebRTC AECM
```

---

## WebRTC Audio Processing (Firmware Evidence)

### Supported Sample Rates (Binary Analysis)

> This is the definitive binary evidence for the 8kHz/16kHz microphone requirement. Other documents reference this section.

**CRITICAL FINDING:** The WebRTC AECM initialization function at `0x2dfa2` explicitly validates the sample rate parameter:

```arm
0x2dfaa: cmp.w r1, 0x1f40    ; Compare with 8000 Hz (0x1F40)
0x2dfae: beq 0x2dfbc         ; If equal, ACCEPT

0x2dfb0: cmp.w r1, 0x3e80    ; Compare with 16000 Hz (0x3E80)
0x2dfb4: beq 0x2dfbc         ; If equal, ACCEPT

0x2dfb6: movw r3, 0x2ee4     ; Otherwise, set error code
0x2dfba: b 0x2dfce           ; Return FAILURE
```

**The firmware WebRTC processing accepts ONLY two sample rates:**
- **8000 Hz** (0x1F40) - narrowband voice
- **16000 Hz** (0x3E80) - wideband voice

**FAILURE MODE:** Using any sample rate other than 8kHz or 16kHz for microphone input will cause WebRTC AECM initialization to fail. This is a **HARD REQUIREMENT** - the firmware will reject the audio and may cause session termination or silent mic failure.

### Dynamic Sample Rate Configuration

The sample rate is **not hardcoded** - it's configured dynamically at runtime:

1. Audio command from phone/CarPlay specifies `decodeType`
2. Adapter stores sample rate in `obj.SAMPLE_RATE` global (address `0x94930`)
3. `fcn.000165fc` sets the SAMPLE_RATE and WEBRTC_DELAYS values
4. `fcn.0001655c` initializes WebRTC modules with the configured rate
5. Firmware logs: `rate:%d delays:%d` (at `0x6a603`)

### Functions (from binary strings)

| Component | Functions | Purpose |
|-----------|-----------|---------|
| **AGC** | `WebRtcAgc_Create`, `WebRtcAgc_Process`, `WebRtcAgc_CalculateGainTable` | Automatic gain control |
| **AEC** | `WebRtcAecm_ProcessBlock`, `WebRtcAecm_AlignedFarend`, `WebRtcAecm_StoreAdaptiveChannelNeon` | Echo cancellation (mobile) |
| **NS** | WebRTC noise suppression module | Noise reduction |

### Processing Evidence

From binary analysis at `0x5a642`:
```
Not all PhoneCall data Do AGC: %d!!!
Not all Mic data Do AGC: %d!!!
Not all mic data will be processed: %d!!!
```

**Interpretation:**
- AGC is applied to phone call and microphone audio
- Not all frames are processed (buffer underflow or optimization)
- Frame count indicates how many frames were skipped

### Echo Cancellation Configuration

From binary at `0x5b7cb`:
```
msSpeakerToMic_PassToAEC_=%d frameCnt_needBufferedNative_=%d, needIdleDealFrameCnt_=%d
```

**Parameters:**
- `msSpeakerToMic_PassToAEC_`: Speaker-to-mic delay for AEC alignment
- `frameCnt_needBufferedNative_`: Frame buffer count for native processing
- `needIdleDealFrameCnt_`: Idle frame count for processing

---

## Audio Mixer

The adapter has an audio mixer that handles different sample rates:

**Error message at `0x5b7f1`:**
```
Audio mixer error, Channel:%d, SampleRate:%d !!!
```

This indicates:
1. The mixer handles multiple channels
2. Sample rate is a mixer parameter
3. Mixing can fail with mismatched parameters

---

## Configuration Parameters (riddle.conf)

| Key | Description | Values |
|-----|-------------|--------|
| `mediaSound` | Media sample rate | 0=44.1kHz, 1=48kHz |
| `MediaQuality` | Media audio quality setting | 0=CD (44.1kHz), 1=HD (48kHz) |
| `CallQuality` | Voice call quality (Web UI) | 0=Normal, 1=Clear, 2=HD |
| `VoiceQuality` | Voice call quality (Internal) | 0-2, maps from CallQuality |
| `MicType` | Microphone type selection | See microphone types |
| `MicMode` | Microphone mode | Unknown |
| `NaviAudio` | Navigation audio settings | Unknown |
| `SAMPLE_RATE` | Runtime sample rate (not configurable) | Set by CarPlay negotiation |
| `AudioMultiBusMode` | Multi-bus audio mode | Unknown |

### CallQuality / VoiceQuality Bug (Verified Jan 2026)

**CRITICAL:** The `CallQuality` setting from the Web UI **does not work** due to a firmware bug.

**Evidence from TTY logs:**
```
[D] CMD_BOX_INFO: {...,"callQuality":1,...}
[E] apk callQuality value transf box value error , please check!
```

The firmware's `ConfigFileUtils` fails to translate the host app's `callQuality` value to the internal `VoiceQuality` config. This error occurs in firmware 2025.10.XX regardless of the CallQuality value (0, 1, or 2).

**Impact:** Even if VoiceQuality controlled sample rate selection (8kHz vs 16kHz), the setting is never applied. All telephony audio uses 16kHz because:
1. The CallQuality→VoiceQuality translation fails
2. CarPlay independently negotiates 16kHz (wideband) with modern iPhones

**Workaround:** None known. The sample rate is determined by CarPlay's `audioFormat` during stream setup, not by adapter configuration.

---

## Microphone Types (Command IDs)

| ID | Command | Description |
|----|---------|-------------|
| 7 | UseCarMic | Route audio from car's microphone |
| 8 | UseBoxMic | Use adapter's built-in microphone |
| 15 | UseBoxI2SMic | Use adapter's I2S microphone |
| 21 | UsePhoneMic | Use phone's microphone |
| 22 | UseBluetoothAudio | Route via Bluetooth audio |
| 23 | UseBoxTransAudio | Use adapter transmitter |

---

## Critical Implementation Notes

### 1. Phone Call Sample Rate

**Finding:** The firmware WebRTC processing supports **both** 8kHz and 16kHz for phone calls.

**Binary evidence (0x2dfa2):**
- WebRtcAecm_Init explicitly accepts 8000 Hz OR 16000 Hz
- Both rates are equally valid from a firmware perspective
- The sample rate is determined dynamically based on audio context

**Observation from carlink_native:**
```kotlin
AudioCommand.AUDIO_PHONECALL_START -> {
    startMicrophoneCapture(decodeType = 5, audioType = 3)  // 16kHz
}
// decodeType=3 (8kHz) is also valid for phone calls
```

**Implications:**
- Using 16kHz for phone calls is **supported** by the firmware
- The choice between 8kHz and 16kHz may depend on phone/CarPlay negotiation
- Host apps should use the `decodeType` specified in the adapter's AudioData command

### 2. Audio Format from Adapter Must Be Used

The adapter sends format information in AudioData command messages:
```
AudioData (13 bytes when command present):
[decodeType:4][volume:4][audioType:4][command:1]
```

Host applications MUST:
1. Parse the `decodeType` from the adapter's AudioData message
2. Use that format for microphone capture (not hardcode it)
3. Send microphone audio in the requested format

### 3. WiFi vs Bluetooth Format Differences

From binary:
```
wifiFormat
btFormat
wifiFormat is same as BTFormat %s!!!!!!
```

The adapter tracks separate formats for:
- **WiFi (CarPlay wireless)**: Typically higher quality
- **Bluetooth (HFP)**: 8kHz or 16kHz narrowband

---

## Audio Type Signaling

The adapter uses D-Bus signals internally to coordinate audio:

| Signal | Purpose |
|--------|---------|
| `kRiddleAudioSignal_MEDIA_START` | Media playback starting |
| `kRiddleAudioSignal_MEDIA_STOP` | Media playback stopped |
| `kRiddleAudioSignal_ALERT_START` | Alert audio starting |
| `kRiddleAudioSignal_ALERT_STOP` | Alert audio stopped |
| `kRiddleAudioSignal_PHONECALL_Incoming` | Incoming call notification |
| `AudioSignal_OUTPUT_START` | Audio output starting |
| `AudioSignal_OUTPUT_STOP` | Audio output stopping |
| `AudioSignal_INPUT_CONFIG` | Input audio configuration |
| `AudioSignal_SIRI_START` | Siri activation |
| `AudioSignal_SIRI_STOP` | Siri deactivation |
| `AudioSignal_PHONECALL_START` | Phone call starting |
| `AudioSignal_PHONECALL_STOP` | Phone call ending |
| `AudioSignal_NAVI_START` | Navigation audio starting |
| `AudioSignal_NAVI_STOP` | Navigation audio stopping |

---

## Debug Files

The firmware can write debug PCM files:

| File | Content |
|------|---------|
| `/tmp/agc.pcm` | AGC processed audio |
| `/tmp/mic.pcm` | Raw microphone input |
| `/tmp/echo.pcm` | Echo reference signal |
| `/tmp/ref.pcm` | Reference audio for AEC |

---

## Summary: Does the Adapter Resample Audio?

### Phone → Host (Playback)
**Answer: Minimal processing / Pass-through**
- Audio is received from CarPlay/AndroidAuto via iAP2
- Format is signaled to host via decodeType
- Host must handle the format (44.1kHz, 48kHz stereo for media; 8kHz, 16kHz mono for voice)

### Host → Phone (Microphone)
**Answer: YES - Active processing**
- WebRTC AGC applied to phone call audio
- WebRTC AGC applied to microphone audio
- WebRTC AEC for echo cancellation (supports 8kHz and 16kHz ONLY)
- Noise suppression applied
- Audio format must be 8000 Hz or 16000 Hz (other rates will fail WebRTC init)

### Expected Formats

**Host app should send microphone audio:**
- **Siri/Voice Recognition:** 16000 Hz, 1 channel, 16-bit PCM (decodeType=5)
- **Phone Calls:** 8000 Hz OR 16000 Hz, 1 channel, 16-bit PCM (decodeType=3 or 5)
- **IMPORTANT:** Use the `decodeType` from the adapter's AudioData command message

**Firmware-supported mic sample rates (binary verified):**
- 8000 Hz (0x1F40) - narrowband
- 16000 Hz (0x3E80) - wideband
- Other sample rates will cause WebRTC AECM initialization failure

**Host app will receive playback audio:**
- **Media:** 48000 Hz, 2 channels, 16-bit PCM (decodeType=4)
- **Voice/Phone:** Format specified in AudioData command message

---

## Binary Analysis References

| Component | Address | String/Function |
|-----------|---------|-----------------|
| **AudioConvertor::ReSample** | `0x6410` | libdmsdpaudiohandler.so - sample rate conversion |
| **AECM Init** | `0x2dfa2` | WebRtcAecm_Init - validates 8kHz/16kHz |
| **Sample rate check** | `0x2dfaa` | `cmp.w r1, 0x1f40` (8000 Hz) |
| **Sample rate check** | `0x2dfb0` | `cmp.w r1, 0x3e80` (16000 Hz) |
| **SAMPLE_RATE global** | `0x94930` | `obj.SAMPLE_RATE` storage |
| **Set sample rate** | `0x165fc` | `fcn.000165fc` - sets SAMPLE_RATE |
| **WebRTC init** | `0x1655c` | `fcn.0001655c` - initializes WebRTC modules |
| Rate/delay log | `0x6a603` | `rate:%d delays:%d` |
| AGC error | `0x5a642` | `webrtc_Agc_Process args error!` |
| PhoneCall AGC | - | `Not all PhoneCall data Do AGC: %d!!!` |
| Mic AGC | - | `Not all Mic data Do AGC: %d!!!` |
| Mixer error | `0x5b7f1` | `Audio mixer error, Channel:%d, SampleRate:%d !!!` |
| Audio init | `0x5a4f1` | `init_audio_record %d %d %d` |
| Audio config | - | `change_audio_config %d %d %d` |
| AEC delay | - | `msSpeakerToMic_PassToAEC_=%d...` |

---

## Related Documentation

- `../02_Protocol_Reference/audio_protocol.md` - **Canonical** AudioData (0x07) protocol reference (capture-verified commands, stream routing, packet sizes)
- `../02_Protocol_Reference/command_ids.md` - Audio command IDs
- `../02_Protocol_Reference/usb_protocol.md` - AudioData message format
- `../01_Firmware_Architecture/initialization.md` - Audio initialization sequence
- `microphone_processing.md` - Firmware microphone pipeline (WebRTC, I2S, format conversion)
