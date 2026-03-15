# CPC200-CCPA Microphone Processing

> **[Firmware Pipeline]** This document covers the internal microphone processing pipeline within the CPC200-CCPA adapter firmware (WebRTC, I2S, format conversion). For the USB wire format and audio command protocol, see `../02_Protocol_Reference/audio_protocol.md`. For binary-level audio format analysis, see `audio_formats.md`. Microphone capture specs apply to both CarPlay and Android Auto.

**Status:** VERIFIED via binary analysis
**Consolidated from:** carlink_native firmware research
**Last Updated:** 2026-02-19

---

## Overview

CPC200-CCPA firmware processes microphone audio from external sources (host apps) and forwards to CarPlay/Android Auto protocols. The A15W model lacks an onboard microphone, implementing bidirectional audio bridging via USB.

---

## Architecture

Intelligent audio protocol bridge handling bidirectional stream multiplexing - processes microphone data from USB hosts, routes to CarPlay/Android Auto via format conversion and RTP streaming.

### Hardware Context

| Property | Value |
|----------|-------|
| A15W Variant | No onboard microphone |
| Data Source | Host apps via USB NCM interface |
| Protocols | CarPlay, AndroidAuto, AndroidMirror, iOSMirror, HiCar |
| Config | `MDLINK='CarPlay,AndroidAuto,AndroidMirror,iOSMirror,HiCar'` |

---

## Data Flow Pipeline

```
Host App → USB NCM → boxNetworkService → MicAudioProcessor →
AudioConvertor → DMSDP RTP → CarPlay/Android Auto
```

### Components

| Component | Purpose |
|-----------|---------|
| **boxNetworkService** | USB NCM interface handler |
| **MicAudioProcessor** | PushAudio(), PopAudio(), Reset() |
| **AudioConvertor** | Format conversion, stereo→mono, sample rate conversion |
| **DMSDP RTP** | Packet assembly, RTP streaming |

---

## Core Components (Binary Analysis)

### MicAudioProcessor

```cpp
class MicAudioProcessor {
    void PushAudio(unsigned char* data, unsigned int size, unsigned int type);
    void PopAudio(unsigned char* data, unsigned int size, unsigned int type);
    void Reset();
};
// Mangled: _ZN17MicAudioProcessor[9PushAudio|8PopAudio|5Reset]E*
```

### AudioService

```cpp
class AudioService {
    void PushMicData(unsigned char* data, unsigned int size, unsigned int type);
    bool IsUsePhoneMic();
    bool IsSupportBGRecord();
    void OpenAudioRecord(const char* profile, int p1, int p2, const DMSDPProfiles*);
    void CloseAudioRecord(const char* profile, int p3);
};
// Mangled: _ZN12AudioService[11PushMicData|15OpenAudioRecord|16CloseAudioRecord]E*
// Global: _Z[13IsUsePhoneMic|17IsSupportBGRecord]v
```

### AudioConvertor

```cpp
class AudioConvertor {
    void SetFormat(AudioPCMFormat src, AudioPCMFormat dst);
    void PushSrcAudio(unsigned char* data, unsigned int size);
    void PopDstAudio(unsigned char* data, unsigned int size);
    float GetConvertRatio();
    void SteroToMono(short* left, short* right, int samples);
    int GetConvertSrcSamples(int src_rate, int dst_rate, int samples);
};
// Mangled: _ZN14AudioConvertor[9SetFormat|12PushSrcAudio|11PopDstAudio|15GetConvertRatio|11SteroToMono|20GetConvertSrcSamples]E*
```

---

## Processing Pipeline

| Step | Operation | Function |
|------|-----------|----------|
| 1 | USB Reception | Host app → USB NCM → boxNetworkService |
| 2 | Protocol Parsing | Extract audio payload, validate packets |
| 3 | Processing | `MicAudioProcessor::PushAudio()` → `AudioService::PushMicData()` |
| 4 | Conversion | `AudioConvertor::SetFormat()` → format/rate conversion |
| 5 | RTP Assembly | `DMSDPRtpSendPCMPackFillPayload()` → `DMSDPPCMPostData()` |
| 6 | Transmission | CarPlay (iAP2), Android Auto (AOA), HiCar (custom) |

---

## Audio Focus & Stream Management

### Focus System

```cpp
AudioService::requestAudioFocus(AUDIO_TYPE_VOICE_COMMAND, FOCUS_FLAGS);
AudioService::handleAudioType(AUDIO_TYPE_HICAR_SDK&, DMSDPAudioStreamType, bool&);
AudioService::getAudioTypeByDataAndStream(const char*, DMSDPVirtualStreamData*);
// Mangled: _ZN12AudioService[17requestAudioFocus|17abandonAudioFocus|15handleAudioType|27getAudioTypeByDataAndStream]E*
```

### Audio Types

```cpp
enum MicrophoneAudioTypes {
    AUDIO_TYPE_VOICE_COMMAND = 1,  // Siri, Google Assistant
    AUDIO_TYPE_PHONE_CALL = 2,     // Hands-free calling
    AUDIO_TYPE_VOICE_MEMO = 3,     // Voice recording
    AUDIO_TYPE_NAVIGATION = 4,     // Navigation voice input
};
```

---

## Configuration

```json
{
    "micGain": 0,           // External mic gain (app-controlled)
    "micType": 0,           // External/USB microphone (see audio_formats.md for full MicType command IDs)
    "VrPacketLen": 200,     // Voice recognition packet length
    "VrVolumGain": 0,       // Voice volume gain
    "backRecording": 0,     // Background recording capability
    "CallQuality": 1        // Call quality enhancement
}
```

### Capabilities

```cpp
bool IsUsePhoneMic();     // External/app microphone check
bool IsSupportBGRecord(); // "Hey Siri"/"OK Google" support
AudioService::GetAudioCapability(DMSDPAudioCapabilities**, unsigned int*);
// Mangled: _Z[13IsUsePhoneMic|17IsSupportBGRecord]v, _ZN12AudioService18GetAudioCapabilityE*
```

---

## RTP Transport

### DMSDP RTP Functions

```cpp
void DMSDPRtpSendPCMPackFillPayload(rtp_packet_t*, unsigned char*, unsigned int);
void DMSDPPCMPostData(unsigned char*, unsigned int stream_id, unsigned int timestamp);
void DMSDPPCMProcessPacket(unsigned char*, unsigned int);
void DMSDPDataSessionRtpSender[Callback|EventsHandler](rtp_session_t*, rtp_event[_t*|s_t]);
void DMSDPDataSessionInitRtpRecevier(...);

// Stream callbacks
void DMSDPStreamSetCallback(stream_id_t, stream_callback_t);
void DMSDPServiceProviderStreamSetCallback(provider_t*, stream_callback_t);
void DMSDPServiceSessionSetStreamCallback(session_t*, stream_callback_t);
```

---

## Performance

| Component | Time | Memory | CPU |
|-----------|------|--------|-----|
| USB Reception | <0.5ms | 5KB | <1% |
| MicAudioProcessor | 1-2ms | 20KB | 3-5% |
| Format Conversion | 0.5-1ms | 15KB | 2-3% |
| RTP Assembly | <0.5ms | 10KB | 1-2% |
| Protocol Transmission | 1-2ms | 8KB | 2-3% |
| **Total** | **3-6ms** | **58KB** | **9-14%** |

**Optimizations:**
- 200-byte VR packets
- Direct sample rate conversion
- Hardware-accelerated RTP
- USB NCM bulk transfer

---

## Protocol Integration

| Protocol | Implementation |
|----------|----------------|
| **CarPlay** | iAP2 audio sessions ("nMic2" config from ARMiPhoneIAP2) |
| **Android Auto** | AOA protocol audio channels via DMSDP transport |
| **HiCar** | Custom audio processing (ARMHiCar executable) |

---

## Advanced Features

### Background Recording

- Voice activation: "Hey Siri"/"OK Google" via `IsSupportBGRecord()`
- Continuous monitoring, low-power detection, seamless focus switching

### Call Quality Enhancement

- State management: `AudioService::OnCallStateChangeE(CALL_STATE)`
- Enhanced processing: `"CallQuality": 1`

### Voice Commands

| Protocol | Assistant |
|----------|-----------|
| CarPlay | Siri commands/dictation |
| Android Auto | Google Assistant |
| HiCar | Huawei voice assistant |
| Universal | Hands-free calling |

---

## Testing & Debug

### Testing Parameters

```json
{
    "VrPacketLen": 200,
    "VrVolumGain": 0
}
```

### Debug Functions

```cpp
AudioService::getCurAudioType(int*, int*);
AudioService::OnAudioFocusChange(int);
AudioService::OnMediaStatusChange(MEDIA_STATE);
```

---

## WebRTC AECM Requirements

**CRITICAL:** Microphone audio must be 16kHz in practice — the firmware's WebRTC AECM module at `0x2dfa2` accepts only 8kHz or 16kHz and rejects other rates. However, 8kHz is vestigial dead code: it exists in the binary (4 AEC call sites) but was never observed in 22+ capture sessions. The `CallQuality→VoiceQuality` firmware bug prevents configuration, and modern iPhones always negotiate 16kHz. Host apps should use 16kHz mono for all microphone audio.

See `audio_formats.md` (WebRTC Audio Processing > Supported Sample Rates) for the complete binary analysis with ARM assembly evidence.

---

## Android Auto Phone Call Microphone — FIXED (2026-03-15)

**Status:** FIXED — confirmed working via Google Meet VoIP call
**Previous status:** Was believed to be a firmware limitation (2026-03-14), actually a host-side decodeType mismatch
**Environment:** carlink_native on AAOS emulator (sdk_gcar_arm64) + CPC200-CCPA (FW 2025.10.15.1127CAY) + Pixel 10 wireless AA

### Root Cause

carlink_native hardcoded mic capture at 16kHz (decodeType=5) for all modes. During AA phone calls, the adapter switches to HFP/SCO at 8kHz narrowband (decodeType=3). The adapter expected mic data tagged as decodeType=3 for SCO routing, but received decodeType=5, so it routed the data to the voice assistant path (AudioInputService) instead of the SCO bridge — resulting in zero bytes on the phone call mic path (`SndPushInput total 0`).

### Why It Appeared to Be a Firmware Limitation

The initial analysis (2026-03-14) observed `SndPushInput total 0` during calls and concluded the USB mic and HFP/SCO paths were unbridged in firmware. In reality, the adapter's `SndPushInput` path works correctly — it just requires mic data at the correct sample rate and decodeType. Autokit (which works) handles this via its `INPUT_CONFIG` handler that dynamically switches AudioRecord between 16kHz and 8kHz.

### Fix: Dynamic decodeType from INPUT_CONFIG

**File:** `CarlinkManager.kt`

1. Track incoming audio decodeType (`lastIncomingDecodeType`) from AudioData packets
2. Handle `AUDIO_INPUT_CONFIG` command — restart mic capture at the adapter's requested format
3. Use `lastIncomingDecodeType` for PHONECALL_START instead of hardcoded 5
4. Pass decodeType to `MicrophoneCaptureManager.start(decodeType)` for correct sample rate
5. Adjust `readChunk` size: 320 bytes (20ms) at 8kHz, 640 bytes at 16kHz

### Audio Command Sequence — Google Assistant (decodeType=5, 16kHz)

```
Adapter → Host:  AUDIO_INPUT_CONFIG (decodeType=5)      ← 16kHz wideband
Adapter → Host:  AUDIO_SIRI_START (id=8)                 ← start mic
Host:            Capture started: 16000Hz 1ch            ← AudioRecord at 16kHz
Adapter → Host:  AUDIO_OUTPUT_START (id=1)               ← GA speech output begins
Host → Adapter:  AudioData(0x07) decodeType=5 audioType=3 ← mic packets at 16kHz
Adapter:         AudioInputService open succeed           ← OpenAuto accepts mic data
Adapter → Host:  AUDIO_SIRI_STOP (id=9)                  ← session end
Host:            Capture stopped: 392320B / 12.3s         ← ~31KB/s = correct for 16kHz mono
```

### Audio Command Sequence — Phone/VoIP Call (decodeType=3, 8kHz)

```
Adapter → Host:  AUDIO_INCOMING_CALL_INIT (id=14)        ← incoming call ring
Adapter → Host:  AUDIO_ALERT_START (id=12)               ← ringtone
Adapter:         SndPushOutput 320 samples                ← ringtone via BT SCO
Adapter → Host:  AUDIO_ALERT_STOP (id=13)                ← ring ends
Adapter → Host:  AUDIO_PHONECALL_START (id=4)             ← call answered
Host:            lastIncomingDecodeType=3                  ← tracked from 8kHz call audio
Host:            Capture started: 8000Hz 1ch              ← AudioRecord at 8kHz
Adapter → Host:  AUDIO_INPUT_CONFIG (decodeType=3)        ← confirms 8kHz narrowband
Host → Adapter:  AudioData(0x07) decodeType=3 audioType=3 ← mic packets at 8kHz
Adapter:         SndPushInput 320 samples, total N        ← NON-ZERO: mic data received!
Adapter:         Sco send 240, total N                    ← mic forwarded to phone via BT SCO
```

### BOX_TMP_DATA_AUDIO_TYPE Bitmask (adapter internal state)

| Value | Meaning |
|-------|---------|
| `0x0000` | Idle |
| `0x0001` | Phone call mode |
| `0x0004` | Siri/GA audio output |
| `0x0008` | Alert/ringtone |
| `0x0100` | Mic recording flag |
| `0x0400` | INPUT_CONFIG received |
| `0x0504` | Recording + Siri output (GA active) |
| `0x0501` | Recording + phone call (VOIP active) |

### Adapter VOIP Call Flow (ttyLog, confirmed working 2026-03-15)

```
Set BOX_TMP_DATA_AUDIO_TYPE: 0x0008             ← alert/ring
Set BOX_TMP_DATA_AUDIO_TYPE: 0x0108             ← ring + output active
Set BOX_TMP_DATA_AUDIO_TYPE: 0x0001             ← PHONECALL mode
_SendPhoneCommandToCar: StartRecordMic(1)       ← command to host: start mic
Set BOX_TMP_DATA_AUDIO_TYPE: 0x0401             ← INPUT_CONFIG + call
Set BOX_TMP_DATA_AUDIO_TYPE: 0x0501             ← recording + call
SndPushInput first sync, cache 60 ms            ← mic data arriving from host
SndPushInput 320 samples, total 16000           ← 16000 samples/sec = 16kHz on ALSA
Sco send 240, total 256080                      ← forwarded to phone via BT SCO
Sco read 120, total 480000                      ← caller voice received (bidirectional)
```

### DecodeType → Sample Rate → Purpose

| decodeType | Rate | Ch | Purpose | When Used |
|-----------|------|-----|---------|-----------|
| 3 | 8000 Hz | 1 | Phone call (narrowband) | AA VOIP/cellular calls |
| 5 | 16000 Hz | 1 | Voice assistant (wideband) | GA, Siri, CarPlay calls |

CarPlay phone calls also use decodeType=5 (16kHz) because iPhone negotiates wideband telephony over iAP2/WiFi (not HFP/SCO).

### Emulator HAL Audio State During VOIP Call

```
adev_create_audio_patch: source[0] type=1 address=Built-In Mic    ← emulator mic active
requestAudioFocus() USAGE_VOICE_COMMUNICATION/CONTENT_TYPE_SPEECH  ← PHONE_CALL focus
CarAudioFocus: Evaluating GAIN_TRANSIENT for USAGE_VOICE_COMMUNICATION
AUDIO_PERF: active:8000Hz 8000Hz:50ms(ACTIVE) Focus:[RINGTONE,PHONE_CALL]
```

### Pixel State During VOIP Call

```
AOC: VoIP session 2: Tx:9600, Rx:9600          ← bidirectional audio on Pixel
harmony: MeetLib: Meeting token is set           ← Google Meet active
CAR.BT.LITE: STATE_HFP_MONITORING               ← HFP profile connected
```

### Adapter System State During Active VOIP

| Component | State | Details |
|-----------|-------|---------|
| `ARMAndroidAuto` | Active | PID 1183, 70MB VSZ — OpenAuto AA protocol handler |
| `hfpd` (×2) | Active | HFP daemon instances for BT SCO bridge |
| `ARMadb-driver` (×4) | Active | USB protocol + worker threads |
| `boxNetworkService` | Active | USB NCM interface for mic/audio data |
| BT SCO | Connected | `Sco send/read` continuous — bidirectional voice |
| ALSA | No hardware | `/proc/asound/cards` empty (A15W no onboard codec) |
| SCO MTU | 240:32 | Voice setting 0x0060 |

### Autokit vs carlink_native Comparison (both confirmed working)

| Aspect | Autokit | carlink_native |
|--------|---------|----------------|
| GA mic rate | 16kHz (`htlog: rate:16000`) | 16kHz (`Capture started: 16000Hz`) |
| Call mic rate | 8kHz (`htlog: rate:8000`) | 8kHz (`Capture started: 8000Hz`) |
| INPUT_CONFIG handling | `a.i = w.a; h.h(c(w.a, true))` — restart AudioRecord | `stopMicrophoneCapture(); startMicrophoneCapture(decodeType)` |
| Mic packet decodeType | `a.i` (dynamic, from INPUT_CONFIG) | `currentMicDecodeType` (dynamic) |
| Mic packet volume | 0.0f (`CropImageView.DEFAULT_ASPECT_RATIO`) | 0.0f |
| Mic packet audioType | 3 | 3 |
| Init commands sent | `UseCarMic(7)` + `UseBoxTransAudio(23)` | `UseCarMic(7)` + `UseBoxTransAudio(23)` |
| Adapter SndPushInput | Non-zero totals during call | Non-zero totals during call |
| Adapter Sco send | Active during call | Active during call |

### Mic Data Pipeline (working end-to-end)

```
Mac mic → Emulator AudioRecord (8kHz/16kHz) → carlink_native ring buffer
  → 20ms chunks (320B@8kHz / 640B@16kHz)
  → USB AudioData(0x07) [decodeType=3/5, volume=0.0, audioType=3]
  → Adapter MicAudioProcessor → AudioConvertor
  → SndPushInput (ALSA PCM bridge) → hfpd → BT SCO → Pixel → Google Meet
```

---

## CarPlay + iPhone Microphone Session Capture (2026-03-15)

**Status:** VERIFIED via live instrumented capture
**Environment:** carlink_native on AAOS emulator + CPC200-CCPA (FW 2025.10.15.1127CAY) + iPhone via CarPlay (WiFi)
**Test:** Siri activation → Google Meet VOIP call → caller confirmed hearing mic audio

### Key Difference: CarPlay vs Android Auto Mic Routing

| Aspect | CarPlay | Android Auto |
|--------|---------|-------------|
| Phone call audio path | iAP2/WiFi (no BT HFP/SCO) | BT HFP/SCO via adapter |
| Call mic decodeType | **5 (16kHz wideband)** | **3 (8kHz narrowband)** |
| Call audio rate | 16000 Hz | 8000 Hz |
| Mic negotiation | iPhone negotiates wideband | Adapter forces narrowband for SCO |
| SCO bridge used | **No** — audio over iAP2/WiFi | **Yes** — `Sco send/read` active |
| SndPushInput rate | 16000 samples/sec | 16000 samples/sec (ALSA side) |

CarPlay routes telephony audio through the iAP2 WiFi session (same path as Siri), so the adapter never needs to bridge to BT SCO. This is why CarPlay calls always worked with decodeType=5 hardcoded — the iPhone negotiates 16kHz wideband for all voice, including phone calls.

### Siri Session (adapter ttyLog)

```
15:55:09.680  AudioSignal_SIRI_START
15:55:09.681  Set BOX_TMP_DATA_AUDIO_TYPE: 0x0004     ← Siri output
15:55:09.877  AudioSignal_INPUT_CONFIG                 ← mic format config
15:55:09.880  _SendPhoneCommandToCar: StartRecordMic(1)
15:55:09.880  Set BOX_TMP_DATA_AUDIO_TYPE: 0x0404     ← INPUT_CONFIG + Siri
15:55:09.881  AudioSignal_OUTPUT_START
15:55:09.881  Set BOX_TMP_DATA_AUDIO_TYPE: 0x0504     ← recording + Siri
15:55:18.567  _SendPhoneCommandToCar: StopRecordMic(2)
15:55:18.568  AudioSignal_SIRI_STOP
15:55:18.570  Set BOX_TMP_DATA_AUDIO_TYPE: 0x0000     ← idle
```

Duration: ~9s. Identical bitmask pattern to AA Google Assistant.

### CarPlay VOIP Call #1 (adapter ttyLog)

```
15:55:22.873  AudioSignal_PHONECALL_START
15:55:22.874  Set BOX_TMP_DATA_AUDIO_TYPE: 0x0001     ← phone call mode
15:55:23.299  AudioSignal_INPUT_CONFIG                 ← mic format (decodeType=5, 16kHz!)
15:55:23.300  _SendPhoneCommandToCar: StartRecordMic(1)
15:55:23.300  Set BOX_TMP_DATA_AUDIO_TYPE: 0x0401     ← INPUT_CONFIG + call
15:55:23.301  AudioSignal_OUTPUT_START
15:55:23.301  Set BOX_TMP_DATA_AUDIO_TYPE: 0x0501     ← recording + call
15:56:29.618  _SendPhoneCommandToCar: StopRecordMic(2)
15:56:29.619  AudioSignal_PHONECALL_STOP
15:56:29.629  AudioSignal_OUTPUT_STOP
15:56:29.629  Set BOX_TMP_DATA_AUDIO_TYPE: 0x0000     ← idle
```

Duration: ~66s. Note: **same `0x0501` bitmask as AA VOIP calls** — the adapter uses the same internal state for both protocols.

### CarPlay VOIP Call #2 (adapter ttyLog)

```
15:57:02.854  AudioSignal_PHONECALL_START
15:57:02.855  Set BOX_TMP_DATA_AUDIO_TYPE: 0x0001
15:57:03.284  AudioSignal_INPUT_CONFIG
15:57:03.285  _SendPhoneCommandToCar: StartRecordMic(1)
15:57:03.286  Set BOX_TMP_DATA_AUDIO_TYPE: 0x0401 → 0x0501
```

Same pattern, second call. Still active at capture time.

### Emulator State During CarPlay VOIP

```
AUDIO_PERF: active:16000Hz 16000Hz:50ms(ACTIVE) Focus:[PHONE_CALL]
```

Confirms **16kHz** playback track active during CarPlay phone call (not 8kHz like AA).

### Adapter SndPush During CarPlay VOIP

```
SndPushInput 320 samples, total 0
SndPushInput 320 samples, total 16000      ← +16000/sec = 16kHz rate
SndPushInput 320 samples, total 32000
...
SndPushInput total 3645440                 ← ~228 seconds of mic audio captured
```

16000 samples/sec on the ALSA bridge = 16kHz. Total 3,645,440 samples = ~228 seconds of continuous mic audio.

### CarPlay Audio Format Negotiation (iAP2)

From adapter startup:
```
audioInputFormats : 16          ← 16kHz supported
audioInputFormats : 67108864    ← additional format codes
supportsRTPPacketRedundancy : true
```

`audioInputFormats: 16` indicates 16kHz mic input is advertised to iPhone during iAP2 session setup. The iPhone selects this rate for both Siri and telephony.

### No BT SCO During CarPlay Calls

Unlike AA, CarPlay VOIP calls show **no** `Sco send` or `Sco read` entries. The SCO link activity seen was from the AA session earlier:
```
07:52:28  __DisconnectSco 1 1 0 -1!!!     ← SCO disconnected (previous AA session)
07:52:39  SCO MTU: 240:32 Voice: 0x0060   ← SCO re-negotiated for new BT connection
07:54:03  AG 64:31:35:8C:29:69: Disconnected  ← iPhone BT HFP disconnected
```

CarPlay telephony is fully WiFi-based via iAP2 protocol — the adapter's `hfpd`/SCO bridge is not involved.

### CarPlay vs AA: Complete Mic Protocol Comparison

| Stage | CarPlay (iPhone) | Android Auto (Pixel) |
|-------|-----------------|---------------------|
| **Init** | `UseCarMic(7)` + `UseBoxTransAudio(23)` | Same |
| **Siri/GA start** | `AudioSignal_SIRI_START` → `0x0504` | Same |
| **Siri/GA mic** | 16kHz, decodeType=5 | 16kHz, decodeType=5 |
| **Call start** | `AudioSignal_PHONECALL_START` → `0x0501` | Same bitmask |
| **Call INPUT_CONFIG** | decodeType=5 (16kHz wideband) | **decodeType=3 (8kHz narrowband)** |
| **Call mic capture** | 16kHz mono, 640B/20ms | **8kHz mono, 320B/20ms** |
| **Call audio transport** | iAP2/WiFi (DMSDP RTP) | BT SCO (hfpd bridge) |
| **Adapter call bridge** | MicAudioProcessor → iAP2 RTP | MicAudioProcessor → ALSA → hfpd → SCO |
| **SndPushInput rate** | 16kHz (16000 samples/sec) | 16kHz on ALSA (internal) |
| **SCO active** | No | Yes (`Sco send 240`) |
| **Total mic bytes** | 3,645,440 (228s) | Varies by call duration |

---

## Summary

CPC200-CCPA implements sophisticated bidirectional audio bridge with:

**Capabilities:**
1. Multi-stage audio pipeline (format/rate/channel conversion)
2. RTP transport for low-latency streaming
3. Multi-protocol support (CarPlay, Android Auto, HiCar)
4. Audio focus arbitration & concurrent streams
5. Call quality enhancement

**Architecture:** Intelligent protocol multiplexer providing professional-grade processing, universal compatibility, low-latency streaming, and seamless audio focus management for voice commands, calling, and voice assistant integration.

---

## References

- Source: `carlink_native/documents/reference/Firmware/firmware_microphone.md`
- Binary analysis: `ARMadb-driver_unpacked`, `ARMiPhoneIAP2` (Jan 2026)
- Autokit decompiled: `BoxInterface/a.java` (audio dispatch, INPUT_CONFIG handler lines 696-702), `BoxInterface/f.java` (mic send thread, packet format lines 155-175)
- Live captures: 2026-03-15 Autokit + carlink_native VOIP sessions on AAOS emulator + CPC200-CCPA + Pixel 10 wireless AA
- Fix commit: `CarlinkManager.kt` — dynamic decodeType from INPUT_CONFIG (2026-03-15)
