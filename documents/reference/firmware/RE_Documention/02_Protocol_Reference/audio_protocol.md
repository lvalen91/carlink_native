# CPC200-CCPA Audio Protocol Reference

> **[Protocol]** This document is the canonical reference for AudioData (0x07) message format, audio command signals, and stream routing between the CPC200-CCPA adapter firmware and host app.

**Status:** VERIFIED against 21 controlled capture sessions across 7 scenarios
**Consolidated from:** GM_research, carlink_native
**Last Updated:** 2026-02-19

---

## Overview

The CarPlay audio protocol uses message type `0x07` (AudioData) with two key identifiers:
- **decode_type**: Determines audio format/quality (playback vs voice)
- **audio_type**: Determines audio channel/routing

---

## Audio Packet Structure

```
┌─────────────────────────────────────────────────────────────────────┐
│                    COMMON USB HEADER (16 bytes)                     │
├─────────┬──────┬────────────┬───────────────────────────────────────┤
│ Offset  │ Size │ Field      │ Description                           │
├─────────┼──────┼────────────┼───────────────────────────────────────┤
│ 0x00    │  4   │ Magic      │ 0x55AA55AA (little-endian)            │
│ 0x04    │  4   │ PayloadLen │ Bytes after this 16-byte header       │
│ 0x08    │  4   │ MsgType    │ 7 = AudioData                         │
│ 0x0C    │  4   │ Checksum   │ MsgType ^ 0xFFFFFFFF = -8             │
├─────────┴──────┴────────────┴───────────────────────────────────────┤
│                    AUDIO HEADER (12 bytes)                          │
├─────────┬──────┬────────────┬───────────────────────────────────────┤
│ 0x10    │  4   │ decode_type│ Audio format (2, 4, or 5)             │
│ 0x14    │  4   │ volume     │ Float volume multiplier (0.0-1.0)     │
│ 0x18    │  4   │ audio_type │ Stream type (1=main, 2=nav, 3=mic)    │
├─────────┴──────┴────────────┴───────────────────────────────────────┤
│                    PAYLOAD (variable)                               │
├─────────────────────────────────────────────────────────────────────┤
│ 0x1C+   │  N   │ Data       │ PCM samples OR 1-byte command         │
└─────────────────────────────────────────────────────────────────────┘

Total Header Size: 28 bytes (0x1C)
PayloadLen = 12 (audio header) + audio_payload_size
```

---

## decode_type Values (VERIFIED)

| Value | Name | Sample Rate | Channels | Purpose |
|-------|------|-------------|----------|---------|
| **2** | ALT_MEDIA | 44.1kHz | Stereo | **Dual-purpose:** Commands OR 44.1kHz audio data |
| **4** | PLAYBACK | 48kHz | Stereo | Standard CarPlay HD audio |
| **5** | VOICE | 16kHz | Mono | Voice mode (Siri, phone calls) |

**Important:** Values 1, 3, 6, 7 were speculative in older docs and **NEVER observed** in USB captures.

### decode_type=3 (8kHz Narrowband) - VERIFIED UNOBSERVABLE (Jan 2026)

**Binary analysis confirms** the firmware supports decodeType=3 → 8000 Hz mono at WebRTC AECM (0x2dfa2). However, extensive testing (CallQuality 0/1/2, multiple phone calls) **never triggered 8kHz audio**.

**Why 8kHz is not observed:**

1. **CarPlay controls the sample rate** - The iPhone sends `audioFormat` during stream setup:
   ```
   [AirPlay] Setting up session with {
       audioType : "telephony"
       audioFormat : 16        ← iPhone requests format 16 (16kHz)
   }
   [AirPlay] Main audio setting up PCM/16000/16/1 for telephony
   ```

2. **CallQuality setting has a firmware bug** - The web UI's CallQuality (0-2) fails to translate to internal VoiceQuality:
   ```
   [E] apk callQuality value transf box value error , please check!
   ```

3. **Modern iPhones default to wideband (16kHz)** - The 8kHz path may only activate for:
   - Bluetooth HFP with CVSD codec (legacy narrowband)
   - Carriers/calls that force narrowband codec
   - Legacy devices that don't support wideband

**Packet size verification:**
- Observed: 972-byte packets = 960 PCM bytes = 480 samples @ 16kHz = 30ms ✓
- Expected for 8kHz: ~492-byte packets = 480 PCM bytes = 240 samples @ 8kHz = 30ms (never seen)

**WebRTC Requirement (Binary Verified at 0x2dfa2):** The firmware WebRTC AECM module **only accepts 8kHz or 16kHz** for microphone input. Other sample rates will fail WebRTC initialization (see `../03_Audio_Processing/audio_formats.md` for binary verification with ARM assembly evidence).

### decode_type=2 Dual-Purpose Behavior (CRITICAL)

**decode_type=2 serves TWO distinct purposes based on payload size:**

| PayloadLen | Behavior | Example |
|------------|----------|---------|
| **13 bytes** | Command packet (STOP/cleanup signal) | MEDIA_STOP, PHONECALL_STOP |
| **11532 bytes** | 44.1kHz stereo PCM audio data | Media from 44.1kHz source |

**Capture Evidence (44.1Khz_playback session 2025-12-29):**
```
AirPlay log: "Main audio setting up AAC-LC/44100/2 for media"
USB packet:  decode_type=0x02, payload=11532 bytes → 44.1kHz audio
```

**Capture Evidence (48Khz_playback session 2025-12-29):**
```
AirPlay log: "Main audio setting up AAC-LC/48000/2 for media"
USB packet:  decode_type=0x04, payload=11532 bytes → 48kHz audio
```

### decode_type Semantic Mapping

| decode_type | Semantic Purpose | Commands Using This Type |
|-------------|------------------|--------------------------|
| 2 | **44.1kHz Audio / Stop Commands** | MEDIA_STOP, PHONECALL_STOP, 44.1kHz media |
| 4 | **48kHz CarPlay Audio** | MEDIA_START, NAVI_START, OUTPUT_*, 48kHz media |
| 5 | **Voice/Mic Related** | SIRI_START, PHONECALL_START, INPUT_* |

---

## audio_type Values (VERIFIED)

| Value | Name | Direction | Purpose |
|-------|------|-----------|---------|
| **1** | MAIN | IN | Primary playback (media, alerts, Siri speech, call audio) |
| **2** | NAVIGATION | IN | Navigation voice prompts (ducking channel) |
| **3** | MICROPHONE | OUT | Microphone input (Siri listening, phone calls) |

**Direction Convention:**
- **IN**: Phone/Adapter → Head Unit (playback audio)
- **OUT**: Head Unit → Phone/Adapter (microphone capture)

---

## Audio Commands (payload_len = 13)

When payload_len is 13, the final byte is a command:

| Byte | Hex | Name | decode_type | audio_type | Firmware Trigger | Host Action | Verified |
|------|-----|------|-------------|------------|------------------|-------------|----------|
| 1 | 0x01 | OUTPUT_START | 4, 5 | 1, 2 | Media/voice playback beginning | Prepare AudioTrack for the stream | Yes |
| 2 | 0x02 | OUTPUT_STOP | 4, 5 | 1, 2 | Media/voice playback ending | May stop AudioTrack | Yes |
| 3 | 0x03 | INPUT_START | 5 | 1 | Mic config from phone | Start microphone capture; configure sample rate/channels | Yes |
| 4 | 0x04 | INPUT_STOP | 5 | 1 | Mic session ending | Stop microphone capture | Yes |
| 5 | 0x05 | PHONECALL_START | 5 | 1 | Active phone call connected | Start mic capture, route call audio | Yes |
| 6 | 0x06 | PHONECALL_STOP | 2, 4 | 2 | Phone call ended / channel clear | Stop mic capture if active; clear channel | Yes |
| 7 | 0x07 | NAVI_START | 4 | 2 | Navigation audio starting | Duck media volume or route to nav AudioTrack | Yes |
| 8 | 0x08 | NAVI_STOP | 5 | 1 | Navigation audio stopped / Siri mode | Restore media volume; prepare voice mode | Yes* |
| 9 | 0x09 | SIRI_START | 5 | 1 | Siri activated / responding | Start mic capture if not already active | Yes |
| 10 | 0x0A | MEDIA_START | 4 | 1 | Media stream opening | Internal state tracking | Yes |
| 11 | 0x0B | MEDIA_STOP | 2, 4 | 1 | Media stream closing | Internal state tracking | Yes |
| 12 | 0x0C | ALERT_START | 4 | 1 | System alert sound | Duck media or route alert audio | Yes |
| 13 | 0x0D | ALERT_STOP | 4 | 1 | System alert ended | Restore audio routing | Yes |
| 14 | 0x0E | INCOMING_CALL_INIT | 5 | 1 | Incoming call ringing | Start ring audio routing (distinct from active call) | Yes |
| 16 | 0x10 | NAVI_COMPLETE | 4 | 2 | Navigation prompt finished | End nav audio, restore routing | Yes |

**Naming Note:** Command 14 is called `PHONECALL_Incoming` in the firmware D-Bus signals (`kRiddleAudioSignal_PHONECALL_Incoming`); `INCOMING_CALL_INIT` is the capture-derived name used here. Command 16 (`NAVI_COMPLETE`) is not listed in the simplified `usb_protocol.md` command table but is capture-verified.

**IMPORTANT:**
- No SIRI_STOP (0x0F) command exists - Siri sessions end via OUTPUT_STOP
- NAVI_STOP (0x08) is **misleading** - it actually activates Siri/voice mode

---

## Audio Packet Sizes

### CarPlay Audio Sizes

| PayloadLen | Purpose |
|------------|---------|
| 13 | Control command (12-byte header + 1 command byte) |
| 16 | Extended control (navigation volume adjustment) |
| 972 | Voice audio chunk: 960 bytes PCM = 30ms @ 16kHz mono |
| 11532 | Standard audio chunk: 11520 bytes PCM = 60ms @ 48kHz stereo |
| 1856-8204 | Variable microphone data |

**Sample Calculations:**
- **Media (dt=4):** 11520 / 4 bytes / 48000 Hz = 60ms per packet
- **Voice (dt=5):** 960 / 2 bytes / 16000 Hz = 30ms per packet

### Android Auto Audio Sizes (DIFFERENT)

*Verified via Android Auto capture (Jan 2026, Pixel 10)*

| PayloadLen | Purpose |
|------------|---------|
| 29 | Control command (audio_type=1) |
| 1948 | Navigation audio: 1920 bytes PCM = 60ms @ 16kHz mono (dt=5, at=2) |
| 15388 | Media audio: 15360 bytes PCM = 80ms @ 48kHz stereo (dt=4, at=1) |

**Key Differences from CarPlay:**
- Media packets are 80ms (15360 bytes) vs CarPlay's 60ms (11520 bytes)
- Navigation uses decode_type=5 (16kHz mono) on audio_type=2
- Commands are 29 bytes vs CarPlay's 13 bytes
- Android Auto does NOT send volume ducking packets - host must handle independently

---

## Scenario Command Sequences

### Media Playback
```
MEDIA_START (dt=4, at=1)
OUTPUT_START (dt=4, at=1)
  ↓ [media audio packets: 11532 bytes each]
MEDIA_STOP (dt=4, at=1)
OUTPUT_STOP (dt=4, at=1)
```

### Navigation Prompt
```
PHONECALL_STOP (dt=2, at=2)      ← Channel clear
OUTPUT_START (dt=4, at=2)
NAVI_START (dt=4, at=2)
  ↓ [nav audio ~1.5-2 seconds]
NAVI_COMPLETE (dt=4, at=2)
OUTPUT_STOP (dt=4, at=2)
```

### Media + Navigation (Ducking)
```
MEDIA_START (dt=4, at=1)         ← Media begins
OUTPUT_START (dt=4, at=1)
  ↓ [media plays on audio_type=1]

VOL packet (vol=0.20)            ← Adapter ducks media
PHONECALL_STOP (dt=2, at=2)      ← Nav prompt incoming
OUTPUT_START (dt=4, at=2)
NAVI_START (dt=4, at=2)
  ↓ [nav on at=2, media ducks on at=1]
NAVI_COMPLETE (dt=4, at=2)
OUTPUT_STOP (dt=4, at=2)
VOL packet (vol=1.00)            ← Adapter restores media
  ↓ [media resumes full volume]
```

### Siri Invocation
```
NAVI_STOP (dt=5, at=1)           ← Activates Siri mode
INPUT_START (dt=5, at=1)         ← Microphone on
OUTPUT_START (dt=5, at=1)        ← Audio output on
  ↓ [mic data OUT: dt=5, at=3, ~4 packets/sec]
  ↓ [Siri audio IN: dt=5, at=1, 972-byte packets]
SIRI_START (dt=5, at=1)          ← Siri responding
OUTPUT_STOP (dt=5, at=1)
```

### Incoming Phone Call

The same commands are used across captures, but the **ordering varies** between sessions. The adapter does not guarantee a fixed command sequence — the app must handle commands in any order.

**Sequence A (Pi-Carplay Capture, Jan 2026):**
```
INCOMING_CALL_INIT (dt=5, at=1)  ← Call notification
  ↓ ~300ms
ALERT_START (dt=4, at=1)         ← Ringtone begins
OUTPUT_START (dt=4, at=1)
  ↓ [ringtone audio: dt=4, at=1, rings 7-13s]
ALERT_STOP (dt=4, at=1)
OUTPUT_STOP (dt=4, at=1)
  ↓ ~150ms
INPUT_START (dt=5, at=1)         ← Mic activates
INPUT_STOP (dt=5, at=1)          ← State transition
OUTPUT_START (dt=5, at=1)        ← Call audio begins
PHONECALL_START (dt=5, at=1)     ← Call connected
  ↓ [call in progress]
OUTPUT_STOP (dt=5, at=1)         ← Call ends
```

**Sequence B (Live App Capture, Feb 2026):**
```
INCOMING_CALL_INIT (dt=5, at=1)  ← Call notification
OUTPUT_START (dt=4, at=1)        ← Ringtone begins (no ALERT_START)
  ↓ [ringtone audio: dt=4, at=1, rings ~6.7s]
INPUT_CONFIG (dt=5, at=1)        ← Mic config (INPUT_START)
PHONECALL_START (dt=5, at=1)     ← Call connected (no INPUT_START→INPUT_STOP transition)
  ↓ [call in progress ~37s]
  ↓ [OUTPUT_START/OUTPUT_STOP cycling every 3-10s]
PHONECALL_STOP (dt=2, at=2)     ← Call ends
PHONECALL_STOP (dt=2, at=2)     ← Duplicate, 199ms later
```

**Observed ordering differences:**

| Phase | Sequence A | Sequence B |
|-------|-----------|-----------|
| Ring | ALERT_START → ring → ALERT_STOP | OUTPUT_START only |
| Ring→call | INPUT_START → INPUT_STOP → OUTPUT_START | INPUT_CONFIG → PHONECALL_START |
| During call | Stable | OUTPUT_START/STOP cycling every 3-10s |
| Call end | Single OUTPUT_STOP | Duplicate PHONECALL_STOP (199ms apart) |

The ordering likely varies based on firmware version, whether media was already playing, and host ack timing. Both use the same commands — the app must be state-driven, not sequence-driven.

**OUTPUT_START/STOP Cycling:** During active phone calls, the adapter may send OUTPUT_START/OUTPUT_STOP pairs every 3-10 seconds. This is internal buffer management and does not indicate call audio interruption. The app should not tear down AudioTracks on these signals during an active call.

**Mic Performance (Live Capture):**
- Chunk sizes observed: 640 bytes (20ms, live) and 8204 bytes (Pi-Carplay) — both valid
- Duration: 36,909ms | Total: 1,179,520 bytes | Overruns: 0

**Call Audio Playback (Live Capture):**
- 6 underruns + 107 zero-fills during 37s call
- Format switch 48kHz→16kHz pool creation adds ~90ms latency at call start

### Outgoing Phone Call
```
INPUT_STOP (dt=5, at=1)          ← Clear mic state
INPUT_START (dt=5, at=1)         ← Mic activates
OUTPUT_START (dt=5, at=1)        ← Audio output begins
PHONECALL_START (dt=5, at=1)     ← Call connected
  ↓ [call audio IN: dt=5, at=1]
  ↓ [mic data OUT: dt=5, at=3]
OUTPUT_STOP (dt=5, at=1)         ← Call ends
```

**Key differences:** No INCOMING_CALL_INIT, no ALERT_START/STOP (ringback tone in audio stream)

### iMessage Notification
```
VOL packet (vol=0.1)             ← Duck media
NAVI_START (dt=2, at=2)          ← Uses nav pathway!
OUTPUT_START (dt=2, at=2)
  ↓ [notification sound]
NAVI_COMPLETE (dt=2, at=2)
OUTPUT_STOP (dt=2, at=2)
VOL packet (vol=1.0)             ← Restore media
```

**Note:** iMessage notifications use **NAVI_START**, NOT ALERT_START.

---

## Audio Stream Routing Summary

| Stream | decode_type | audio_type | Start Cmd | Stop Cmd | Sample Rate |
|--------|-------------|------------|-----------|----------|-------------|
| Media (HD) | 4 | 1 | 0x0A | 0x0B | 48kHz |
| Media (Alt) | 2 | 1 | 0x0A | 0x0B | 44.1kHz |
| Navigation | 2/4 | 2 | 0x07 | 0x10 | 44.1/48kHz |
| Notification | 2 | 2 | 0x07 | 0x10 | 44.1kHz |
| Siri (speaker) | 5 | 1 | 0x08 | - | 16kHz |
| Siri (mic) | 5 | 3 | 0x03 | - | 16kHz |
| Phone (speaker) | 5 | 1 | 0x05 | - | 16kHz |
| Phone (mic) | 5 | 3 | 0x03 | - | 16kHz (8kHz in firmware code but vestigial — never observed) |
| Ringtone | 2/4 | 1 | 0x0C | 0x0D | 44.1/48kHz |

**Microphone Note:** Microphone audio (audio_type=3) must be 16kHz in practice. The firmware WebRTC AECM at `0x2dfa2` accepts only 8kHz or 16kHz and rejects other rates, but 8kHz is vestigial dead code — never observed in 22+ capture sessions. The `CallQuality→VoiceQuality` firmware bug and modern iPhone wideband negotiation ensure only 16kHz is ever used. See `../03_Audio_Processing/audio_formats.md` for full binary analysis.

---

## AAOS Audio Bus Mapping

| Stream Type | Android AudioAttributes | Bus |
|-------------|------------------------|-----|
| Media (dt=2,4) | USAGE_MEDIA | Bus 0 |
| Navigation (at=2) | USAGE_ASSISTANCE_NAVIGATION_GUIDANCE | Bus 1 |
| Notification (at=2) | USAGE_NOTIFICATION | Bus 1 |
| Ringtone (at=1) | USAGE_NOTIFICATION_RINGTONE | Bus 1 |
| Voice/Siri (dt=5) | USAGE_ASSISTANT | Bus 2 |
| Phone Call (dt=5) | USAGE_VOICE_COMMUNICATION | Bus 3 |

---

## Volume Ducking

The adapter sends volume packets for media ducking:

| Volume | Usage |
|--------|-------|
| 0.0 | Normal (no ducking specified) |
| 0.1-0.2 | Ducking (reduce media for nav/notification) |
| 1.0 | Restore (return to full volume) |

**Note:** Android Auto does NOT send ducking packets - host must handle independently.

---

## End-of-Stream Marker

Navigation audio uses a **solid 0xFFFF pattern** as an end marker:

```
End Marker:   ff ff ff ff ff ff ff ff (all samples = -1)
```

**Timing:** Appears ~60ms before NAVI_STOP command.

**Detection Logic:**
```
Sample 4 positions in audio data (after 12-byte header)
All must be 0xFFFF for end marker detection
```

---

## Verification Data

### Capture Sessions Analyzed

| Scenario | Sessions | Commands Verified |
|----------|----------|-------------------|
| Video Only | 3 | Baseline (no audio) |
| Media Playback | 3 | MEDIA_START/STOP, OUTPUT_* |
| Navigation | 3 | NAVI_START/COMPLETE, PHONECALL_STOP |
| Media+Navigation | 3 | Ducking behavior |
| Siri | 3 | NAVI_STOP, INPUT_START, SIRI_START |
| Incoming Call | 3 | INCOMING_CALL_INIT, ALERT_*, PHONECALL_START |
| Outgoing Call | 3 | INPUT_*, PHONECALL_START |
| Incoming Call (Live) | 1 | INCOMING_CALL_INIT, PHONECALL_START/STOP, OUTPUT cycling |
| **Total** | **22** | **All 15 commands verified + live behavioral differences** |

---

## Captured Audio Analysis (Jan 2026)

**Source:** Pi-Carplay USB captures
**Date:** 2026-01-19

### CarPlay Session Audio Summary

| Metric | Audio Out (Speaker) | Audio In (Mic) |
|--------|---------------------|----------------|
| Total packets | 4760 | 517 |
| Control packets | 58 | 0 |
| Data packets | 4702 | 517 |
| Total PCM data | 17.09 MB | 4.02 MB |
| Session duration | 237.8 seconds | 237.8 seconds |

**Audio Out Breakdown:**

| Packet Size | PCM Bytes | decode_type | Count | Purpose |
|-------------|-----------|-------------|-------|---------|
| 29 (13+16 hdr) | 0 | 4, 5 | 58 | Control commands |
| 972 | 959 | 5 (16kHz) | 3430 | Voice/Siri audio |
| 11532 | 11519 | 4 (48kHz) | 1270 | Media audio |

**Control Commands Observed:**

| decode_type | audio_type | Command | Count | Description |
|-------------|------------|---------|-------|-------------|
| 4 | 0 | 1 (OUTPUT_START) | 4 | Start media output |
| 4 | 0 | 2 (OUTPUT_STOP) | 4 | Stop media output |
| 4 | 0 | 10 (MEDIA_START) | 6 | Media playback start |
| 4 | 0 | 11 (MEDIA_STOP) | 6 | Media playback stop |
| 5 | 0 | 1-5, 8-9 | various | Voice/Siri commands |
| 2 | 0 | 6 (PHONECALL_STOP) | 1 | Channel clear |

**Audio In (Microphone):**
- All packets use decode_type=5 (16kHz mono)
- Consistent 8204-byte packets (8191 PCM bytes)
- First mic packet at 31153ms (Siri activation)

### Android Auto Session Audio Summary

| Metric | Value |
|--------|-------|
| Audio out packets | 2 |
| Audio out size | 58 bytes |
| Audio in packets | 0 |
| Audio in size | 0 bytes |

**Key Finding:** Android Auto audio is handled internally by the OpenAuto SDK on the adapter. Minimal USB audio data is captured because:
1. Audio decoding/playback occurs on the adapter itself
2. Only control packets traverse USB (MEDIA_STOP commands)
3. Speech audio (VR) goes through Jitter Buffer internal path

**Android Auto Audio Out Content:**
```
Packet 1: decode=2 audio=0 flags=0x00000001 cmd=11 (MEDIA_STOP)
Packet 2: decode=2 audio=0 flags=0x00000001 cmd=11 (MEDIA_STOP)
```

---

## TTY Log Correlation: Video & Audio Timing

### CarPlay Session Timeline

| Time (ms) | Event | Source |
|-----------|-------|--------|
| 0 | USB capture starts | USB |
| ~5200 | AirPlay screen stream setup (type 111) | TTY |
| ~5500 | Nav screen config (1200x500) | TTY: ScreenStream |
| ~5600 | Main screen config (1280x720) | TTY: ScreenStream |
| ~5700 | First I-frame sent (nav: 187 B) | TTY: ScreenStream |
| ~5800 | First I-frame sent (main: 9687 B) | TTY: ScreenStream |
| 7524 | First nav video frame | USB: Type 44 |
| 8070 | First main video frame | USB: Type 6 |
| ~11700 | Video: 26 fps, 562 KB/s | TTY: frame rate |
| ~15700 | Audio stream setup (type 102) | TTY: AirPlay |
| ~15800 | AAC-LC/48000/2 stream negotiated | TTY: Main audio |
| ~15900 | MEDIA_START signal | TTY: iAP2Engine |
| 18200 | First audio out packet | USB: Type 7 |
| ~21400 | Video: 22 fps, Audio: 17 fps | TTY: frame rate |
| 31153 | First mic audio in (Siri) | USB: Type 7 |

**TTY Log Audio Setup Sequence:**
```
[AirPlay] ### Setup stream type: 102
[AirPlay] ### kAirPlayKey_SamplesPerFrame: 1024, minLatency: 48000, maxLatency: 48000
> stream info: channel = 2  sample_rate = 48000  frame_size = 1024  aot = 2  bitrate = 0
[AirPlay] Main audio setting up AAC-LC/48000/2 for media, input no, loopback no, volume no
[D] Set BOX_TMP_DATA_AUDIO_TYPE: 0x0010
[D] Set BOX_TMP_DATA_AUDIO_TYPE: 0x0110
[D] OniAPUpdateMediaPlayerPlaybackStatus -> kRiddleAudioSignal_MEDIA_START
```

### Android Auto Session Timeline

| Time (ms) | Event | Source |
|-----------|-------|--------|
| 0 | USB capture starts | USB |
| ~35000 | OpenAuto USB workers start | TTY: OpenAuto |
| ~36000 | USB device connected | TTY: App |
| ~36200 | SSL handshake begin (2348 B) | TTY: AndroidAutoEntity |
| ~36300 | SSL handshake complete (51 B) | TTY: AndroidAutoEntity |
| ~36400 | Audio focus request (type 4) | TTY: AudioFocus |
| ~36500 | Services start (Video, Audio, Input) | TTY: OpenAuto |
| 39321 | First video frame (2800 B) | USB: Type 6 |
| 40414 | First audio ctrl packet | USB: Type 7 |
| ~250000 | VR audio start (300ms cache) | TTY: BoxAudioOutput |
| ~252700 | Jitter buffer ready | TTY: BoxAudioUtils |

**TTY Log Android Auto Audio Services:**
```
[OpenAuto] [AudioInputService] start.
[OpenAuto] [AudioService] start, channel: MEDIA_AUDIO
[OpenAuto] [AudioService] start, channel: SPEECH_AUDIO
[OpenAuto] [AudioService] start, channel: SYSTEM_AUDIO
[I] requested audio focus, type: 4
[OpenAuto] [BoxAudioOutput] onAudioFocusChanned: 4
[I] audio focus state: 3
```

**VR (Voice Recognition) Session:**
```
[OpenAuto] [BoxAudioOutput] onVRStatusChanned: 3
[D] Set BOX_TMP_DATA_AUDIO_TYPE: 0x0004
[D] Set BOX_TMP_DATA_AUDIO_TYPE: 0x0104
[OpenAuto] [AudioService] start indication, channel: SPEECH_AUDIO, session: 2
[OpenAuto] [BoxAudioOutput] Use audio cache: 300 ms
[D] Jitter Buffer first packet data.
[D] Jitter Buffer is ready now.
```

---

## CarPlay WiFi Audio Format Codes (NEW Jan 2026)

During wireless CarPlay setup, the adapter and iPhone negotiate audio formats via WiFiAudioFormats. These format codes are bitmasks:

| Format Code | Codec | Sample Rate | Channels | Use Case |
|-------------|-------|-------------|----------|----------|
| 16 | PCM | 16000 Hz | 1 | Telephony input |
| 32768 | PCM | 48000 Hz | 2 | Compatibility output |
| 32784 | PCM | Various | 2 | Compatibility I/O |
| 8388608 | AAC | 48000 Hz | 2 | Media output |
| 33554432 | AAC-ELD | 48000 Hz | 2 | Alt audio (low latency) |
| 67108864 | AAC-ELD | 48000 Hz | 2 | Default/telephony/speech |

**TTY Log Evidence (Jan 2026 Wireless CarPlay):**
```
[AirPlay] #### WifiAudioFormats = [
    audioType : "compatibility" - audioInputFormats : 16, audioOutputFormats : 32784
    audioType : "alert" - audioOutputFormats : 33554432
    audioType : "default" - audioInputFormats : 67108864, audioOutputFormats : 67108864
    audioType : "telephony" - audioInputFormats : 67108864, audioOutputFormats : 67108864
    audioType : "speechRecognition" - audioInputFormats : 67108864, audioOutputFormats : 67108864
    audioType : "media" - audioOutputFormats : 8388608
]
> stream info: channel = 2  sample_rate = 48000  frame_size = 480  aot = 39  bitrate = 0
[AirPlay] Alt audio setting up AAC-ELD/48000/2
```

**AAC Audio Object Types (aot):**
| aot | Name | Use |
|-----|------|-----|
| 2 | AAC-LC | Standard media (44.1/48kHz) |
| 39 | AAC-ELD | Enhanced Low Delay (wireless CarPlay) |

---

## Android Auto Audio Focus Types (NEW Jan 2026)

The OpenAuto SDK uses numeric focus types to signal audio focus changes:

| Type | Name | Effect | Command Triggered |
|------|------|--------|-------------------|
| 3 | AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK | Other audio should duck | RequestAudioFocusDuck (504) |
| 4 | AUDIOFOCUS_LOSS | Audio focus lost/released | ReleaseAudioFocus (505) |

**TTY Log Evidence:**
```
[I] requested audio focus, type: 3
[OpenAuto] [BoxAudioOutput] onAudioFocusChanned: 3
[I] audio focus state: 2
[D] _SendPhoneCommandToCar: RequestAudioFocusDuck(504)
```

**Navigation Focus (separate from audio):**
```
[I] navigation focus request, type: 2
[OpenAuto] [BoxAudioOutput] onNaviFocusChanned: 2
[D] _SendPhoneCommandToCar: RequestNaviFocus(506)
```

---

## CarPlay vs Android Auto Audio Comparison

| Aspect | CarPlay | Android Auto |
|--------|---------|--------------|
| Audio codec | AAC-LC (over AirPlay) → PCM | AAC (OpenAuto SDK) |
| USB audio data | 17+ MB per session | ~58 bytes (control only) |
| Audio processing | Host application | Adapter (OpenAuto SDK) |
| Sample rate (media) | 48kHz or 44.1kHz | 48kHz (internal) |
| Sample rate (voice) | 16kHz | 16kHz |
| Latency handling | AirPlay latency negotiation | Jitter Buffer (300ms cache) |
| Volume ducking | USB packets (vol=0.1-1.0) | Host-side (no USB packets) |
| First audio delay | ~18 seconds after video | ~1 second after video |
| Mic recording | USB Type 7 OUT | Internal (adapter) |

---

## References

- Source: `GM_research/cpc200_research/docs/protocol/AUDIO_PROTOCOL.md`
- Source: `carlink_native/documents/reference/Firmware/firmware_audio.md`
- CarPlay Verification: 21 controlled capture sessions
- 44.1kHz Evidence: `pi-carplay_raw_capture/audio/44.1Khz_playback/` (2025-12-29)
- 48kHz Evidence: `pi-carplay_raw_capture/audio/48Khz_playback/` (2025-12-29)
- Android Auto Verification: Jan 2026 capture (Pixel 10, YouTube Music)
- Captures: `/Users/zeno/.pi-carplay/usb-logs/`
- Live Incoming Call: Feb 2026 AAOS emulator + CPC200-CCPA capture (adb logcat)
