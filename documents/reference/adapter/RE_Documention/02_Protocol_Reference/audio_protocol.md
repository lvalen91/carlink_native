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
│ 0x10    │  4   │ decode_type│ Audio format (2, 3, 4, or 5)           │
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
| **2** | ALT_MEDIA | 44.1kHz | Stereo | **Dual-purpose:** Commands OR 44.1kHz audio data (non-default; only when `mediaSound=0`) |
| **4** | PLAYBACK | 48kHz | Stereo | Standard media audio (default for both CarPlay and Android Auto) |
| **5** | VOICE | 16kHz | Mono | Voice mode (Siri, phone calls) |

**Important:** The adapter is transparent — it passes through whatever sample rate the phone negotiates. CarPlay and Android Auto both default to 48kHz (decodeType=4) for media. decodeType=2 with 44.1kHz audio only appears when explicitly configured via `mediaSound=0` in BoxSettings. Values 1, 6, 7 are firmware-supported but not observed in USB captures. Value 3 (8kHz) is actively used for Android Auto phone calls (HFP/SCO narrowband, verified Mar 2026).

### decode_type=3 (8kHz Narrowband) - Active for Android Auto Phone Calls

**Update (Mar 2026):** decode_type=3 IS used for Android Auto phone calls. The Jan 2026 analysis was CarPlay-only. See `../03_Audio_Processing/microphone_processing.md` § AA Phone Call Microphone — FIXED.

**Binary analysis confirms** the firmware supports decodeType=3 → 8000 Hz mono at WebRTC AECM (0x2dfa2). During Android Auto phone calls (HFP/SCO), the adapter sends `INPUT_CONFIG` with decodeType=3, requiring the host to capture and send mic audio at 8kHz. CarPlay phone calls continue to use decodeType=5 (16kHz) exclusively.

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
| **11532 bytes** | 44.1kHz stereo PCM audio data | Media when `mediaSound=0` (non-default) |

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
| 2 | **Stop Commands / 44.1kHz Audio (non-default)** | MEDIA_STOP, PHONECALL_STOP, 44.1kHz media (only when `mediaSound=0`) |
| 4 | **Default Media Audio (48kHz)** | MEDIA_START, NAVI_START, OUTPUT_*, 48kHz media (default for CarPlay and AA) |
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
| 3 | 0x03 | INPUT_CONFIG | 5 | 1 | Mic config from phone | Configure mic sample rate from decode_type; start capture if voice mode active | Yes |
| 4 | 0x04 | PHONECALL_START | 5 | 1 | Active phone call connected | Start mic capture, route call audio | Yes |
| 5 | 0x05 | PHONECALL_STOP | 2, 4 | 1, 2 | Phone call ended / channel clear | Stop mic capture if active; clear channel | Yes |
| 6 | 0x06 | NAVI_START | 2, 4 | 2 | Navigation audio starting | Duck media volume or route to nav AudioTrack | Yes |
| 7 | 0x07 | NAVI_STOP | 4 | 2 | Navigation audio prompt ended | Restore media volume | Yes |
| 8 | 0x08 | SIRI_START | 5 | 1 | Siri activated / responding | Start mic capture if not already active | Yes |
| 9 | 0x09 | SIRI_STOP | 5 | 1 | Siri session ended | Stop mic capture (unless phone call active) | Yes |
| 10 | 0x0A | MEDIA_START | 4 | 1 | Media stream opening | Internal state tracking | Yes |
| 11 | 0x0B | MEDIA_STOP | 2, 4 | 1 | Media stream closing | Internal state tracking | Yes |
| 12 | 0x0C | ALERT_START | 4 | 1 | System alert sound (ringtone) | Duck media or route alert audio | Yes |
| 13 | 0x0D | ALERT_STOP | 4 | 1 | System alert ended | Restore audio routing | Yes |
| 14 | 0x0E | INCOMING_CALL_INIT | 5 | 1 | Incoming call ringing | Show call notification, prepare ring audio | Yes |
| 15 | 0x0F | TBT_START | 4 | 2 | Turn-by-turn pre-announcement | Duck media; precedes NAVI_START for voice prompt | Yes |
| 16 | 0x10 | NAVI_COMPLETE | 4 | 2 | Navigation prompt sequence finished | End nav audio, restore routing | Yes |

**Naming Note:** Command 14 is called `PHONECALL_Incoming` in the firmware D-Bus signals (`kRiddleAudioSignal_PHONECALL_Incoming`); `INCOMING_CALL_INIT` is the capture-derived name used here.

**CORRECTIONS (2026-04-11, verified via live wired + wireless CarPlay captures, iPhone 18,4 iOS 23F5043k):**
- ~~INPUT_STOP (byte 4) does NOT exist on the USB wire~~ — firmware internal enum only. Both carlink_native and carlink_macOS assign PHONECALL_START=4 and work correctly. The entire table is shifted by -1 from byte 4 onward vs the previous version.
- **SIRI_STOP (byte 9) EXISTS on wire** — previously documented as nonexistent. Captured on both wired (22:56:33.219) and wireless (01:38:25.285) CarPlay sessions. Siri end sequence: CMD 2 (StopRecordMic) → SIRI_STOP → OUTPUT_STOP.
- **TBT_START (byte 15) EXISTS on wire** — previously undocumented. Captured on wireless CarPlay (01:40:35.441). Precedes NAVI_START in turn-by-turn navigation sequences.
- **NAVI_START first prompt uses dt=2** (not dt=4) — subsequent prompts in the same sequence use dt=4. Confirmed on both wired and wireless.
- **PHONECALL_STOP can fire 2-3 times** on incoming call hangup (168-178ms apart). Wireless sessions show more aggressive post-hangup cycling than wired. Apps must handle idempotent stops.
- **Volume restore packets (vol=1.00 dur=0.5)** sent after nav prompts on wireless CarPlay — not observed on wired. Explicit unduck signal.

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
*Verified: wired + wireless CarPlay captures (2026-04-10/11, iPhone 18,4)*
```
CMD 506 (RequestNaviFocus)       ← Precedes audio
NAVI_START (dt=2, at=2)          ← First prompt uses dt=2 (not dt=4!)
OUTPUT_START (dt=4, at=2)
  ↓ [nav audio ~1.5-3 seconds]
VOL packet (vol=1.00, dur=0.5)   ← Explicit unduck (wireless only)
NAVI_STOP (dt=4, at=2)
  ↓ [optional: additional prompts as NAVI_START dt=4 at=2]
NAVI_COMPLETE (dt=4, at=2)
OUTPUT_STOP (dt=4, at=2)
CMD 19 (StopGNSSReport)          ← Brackets nav audio
CMD 507 (ReleaseNaviFocus)
CMD 18 (StartGNSSReport)         ← Restarts GPS
```

### Turn-by-Turn + Navigation
*Verified: wireless CarPlay capture (2026-04-11)*
```
TBT_START (dt=4, at=2)          ← Turn-by-turn pre-announcement (byte 15)
OUTPUT_START (dt=4, at=2)
NAVI_START (dt=4, at=2)          ← Voice prompt follows TBT
  ↓ [nav audio]
VOL packet (vol=1.00, dur=0.5)   ← Unduck
NAVI_STOP (dt=4, at=2)
NAVI_COMPLETE (dt=4, at=2)
OUTPUT_STOP (dt=4, at=2)
```

### Media + Navigation (Ducking)
```
MEDIA_START (dt=4, at=1)         ← Media begins
OUTPUT_START (dt=4, at=1)
  ↓ [media plays on audio_type=1]

CMD 506 (RequestNaviFocus)
NAVI_START (dt=2, at=2)          ← First nav prompt (dt=2!)
OUTPUT_START (dt=4, at=2)
  ↓ [nav on at=2, media ducks on at=1]
VOL packet (vol=1.00, dur=0.5)   ← Unduck (wireless)
NAVI_STOP (dt=4, at=2)
NAVI_COMPLETE (dt=4, at=2)
OUTPUT_STOP (dt=4, at=2)
CMD 507 (ReleaseNaviFocus)
  ↓ [media resumes full volume]
```

### Siri Invocation
*Verified: wired + wireless CarPlay captures (2026-04-10/11)*
```
SIRI_START (dt=5, at=1)          ← Siri activated
MEDIA_STOP (dt=4, at=1)          ← Music stops
OUTPUT_STOP (dt=4, at=1)
INPUT_CONFIG (dt=5, at=1)        ← Mic config (16kHz)
CMD 1 (StartRecordMic)           ← Mic on
OUTPUT_START (dt=5, at=1)        ← Siri audio output begins
  ↓ [Siri speech IN: dt=5, at=1, 972-byte packets]
  ↓ [silence (all zeros): Siri listening for user input]
MEDIA_STOP (dt=4, at=1)          ← Redundant (already stopped)
CMD 2 (StopRecordMic)            ← Mic off
SIRI_STOP (dt=5, at=1)           ← Siri session ended
OUTPUT_STOP (dt=5, at=1)
  ↓ [optional: brief SIRI_START→SIRI_STOP re-trigger within ~62ms]
MEDIA_START (dt=4, at=1)         ← Music resumes
OUTPUT_START (dt=4, at=1)
```
**CRITICAL:** PHONECALL_START arrives ~130ms BEFORE SIRI_STOP when making calls via Siri. Do NOT kill mic on SIRI_STOP if a phone call is active.

### Incoming Phone Call
*Verified: wired + wireless CarPlay captures (2026-04-10/11, iPhone 18,4)*

The same commands are used across captures, but the **ordering varies** between sessions. The adapter does not guarantee a fixed command sequence — the app must be state-driven, not sequence-driven.

**Canonical Sequence (Live Capture, 2026-04-10/11, verified wired + wireless):**
```
INCOMING_CALL_INIT (dt=5, at=1)  ← Call notification
MEDIA_STOP (dt=4, at=1)          ← Music stops
OUTPUT_STOP (dt=4, at=1)
ALERT_START (dt=4, at=1)         ← Ringtone begins
OUTPUT_START (dt=4, at=1)
  ↓ [ringtone audio: dt=4, at=1, rings ~6s]
ALERT_STOP (dt=4, at=1)          ← Ringtone ends (user answered)
OUTPUT_STOP (dt=4, at=1)
INPUT_CONFIG (dt=5, at=1)        ← Mic config (16kHz)
CMD 1 (StartRecordMic)           ← Mic on
PHONECALL_START (dt=5, at=1)     ← Call connected
OUTPUT_START (dt=5, at=1)        ← Voice audio begins
  ↓ [call audio IN: dt=5, at=1, 972-byte packets]
  ↓ [mic data OUT: dt=5, at=3]
CMD 2 (StopRecordMic)            ← Mic off
PHONECALL_STOP (dt=5, at=1)      ← Call ends
OUTPUT_STOP (dt=5, at=1)
PHONECALL_STOP (dt=5, at=1)      ← Duplicate (168-178ms later, normal)
MEDIA_START (dt=4, at=1)         ← Music resumes
OUTPUT_START (dt=4, at=1)
```

**Wireless post-hangup rapid cycle (2026-04-11):** After incoming call hangup on wireless, the adapter may briefly re-establish then immediately drop: PHONECALL_STOP → INPUT_CONFIG → CMD 1 → PHONECALL_START → OUTPUT_START → CMD 2 → PHONECALL_STOP → PHONECALL_STOP (all within ~200ms). Apps must handle this idempotently.

**OUTPUT_START/STOP Cycling:** During active phone calls, the adapter may send OUTPUT_START/OUTPUT_STOP pairs every 3-10 seconds. This is internal buffer management and does not indicate call audio interruption. The app should not tear down AudioTracks on these signals during an active call.

### Outgoing Phone Call
*Verified: wired + wireless CarPlay captures (2026-04-10/11)*
```
PHONECALL_START (dt=5, at=1)     ← Call connected
MEDIA_STOP (dt=4, at=1)          ← Music stops
OUTPUT_STOP (dt=4, at=1)
INPUT_CONFIG (dt=5, at=1)        ← Mic config (16kHz)
CMD 1 (StartRecordMic)           ← Mic on
OUTPUT_START (dt=5, at=1)        ← Voice audio begins
  ↓ [call audio IN: dt=5, at=1]
  ↓ [mic data OUT: dt=5, at=3]
CMD 2 (StopRecordMic)            ← Mic off
PHONECALL_STOP (dt=5, at=1)      ← Call ends
OUTPUT_STOP (dt=5, at=1)
MEDIA_START (dt=4, at=1)         ← Music resumes
OUTPUT_START (dt=4, at=1)
```

**Key differences from incoming:** No INCOMING_CALL_INIT, no ALERT_START/STOP (ringback tone is in audio stream, not alerted)

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
| Media (default) | 4 | 1 | 0x0A | 0x0B | 48kHz |
| Media (alt, `mediaSound=0`) | 2 | 1 | 0x0A | 0x0B | 44.1kHz (non-default) |
| Navigation | 4 (default) / 2 | 2 | 0x07 | 0x10 | 48kHz (default) / 44.1kHz |
| Notification | 4 (default) / 2 | 2 | 0x07 | 0x10 | 48kHz (default) / 44.1kHz |
| Siri (speaker) | 5 | 1 | 0x08 | - | 16kHz |
| Siri (mic) | 5 | 3 | 0x03 | - | 16kHz |
| Phone (speaker) | 5 | 1 | 0x05 | - | 16kHz |
| Phone (mic) | 5 (CarPlay) / 3 (AA) | 3 | 0x03 | - | 16kHz for CarPlay, 8kHz for Android Auto phone calls (HFP/SCO) |
| Ringtone | 4 (default) / 2 | 1 | 0x0C | 0x0D | 48kHz (default) / 44.1kHz |

**Microphone Note:** Microphone audio (audio_type=3) must be 8kHz or 16kHz. The firmware WebRTC AECM at `0x2dfa2` accepts only these two rates. CarPlay uses 16kHz exclusively (iPhone wideband negotiation). Android Auto phone calls use 8kHz (HFP/SCO narrowband) — the adapter sends `INPUT_CONFIG` with decodeType=3 to signal 8kHz. Host apps must parse `decodeType` from the adapter's AudioData command and set mic capture rate accordingly. See `../03_Audio_Processing/microphone_processing.md` § AA Phone Call Microphone for the full fix.

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

## CarPlay WiFi Audio Format Codes

`WifiAudioFormats` is emitted by `AppleCarPlay` during the AirPlay session **SETUP** over **RTSP port 5000** (wireless CarPlay only), via the `_getWifiAudioLatencies` path. When `HUDinfo.plist` is empty (`filesize=0 err=kSizeErr`), the adapter falls back to firmware defaults — that default array is what is captured here.

Each `WifiAudioFormats` entry is a **4-field** dictionary: `{ audioType, type, audioInputFormats, audioOutputFormats }`. The `type` field is part of the AirPlay-side negotiation namespace:

- `type 100` = main audio
- `type 101` = alt audio
- `type 102` = media audio

> **Earlier documentation omitted the `type` field** and the paraphrased TTY excerpt dropped it. It is present in every entry — see the verified 8-entry array below.

### Format codes — single-bit selectors

The `audioInputFormats` / `audioOutputFormats` values are **single-bit selectors**, not enumerated codec IDs. A value can be a **bitwise-OR** of several selectors (e.g. `32784 = 0x8010 = 0x10 | 0x8000`).

| Value | Hex | Bit | Codec / format |
|-------|-----|-----|----------------|
| 16 | `0x10` | 4 | PCM 16-bit, 16 kHz mono — mic / telephony input |
| 32768 | `0x8000` | 15 | PCM 16-bit, 48 kHz stereo |
| 32784 | `0x8010` | 4+15 | `0x10 \| 0x8000` — **combination** of 16 kHz-mono and 48 kHz-stereo PCM (NOT a separate "Various" codec) |
| 8388608 | `0x800000` | 23 | **AAC-LC** 48 kHz stereo — media (the `type:102` stream) |
| 33554432 | `0x2000000` | 25 | AAC-ELD 48 kHz stereo — alert / alt-audio |
| 67108864 | `0x4000000` | 26 | AAC-ELD 48 kHz stereo — default / telephony / speechRecognition (duplex) |

### Verified WifiAudioFormats array — 2026-05-21 wireless CarPlay capture

Captured `carplay-20260521-101016/ttyLog.txt:570-615` — 8 entries:

| audioType | type | audioInputFormats | audioOutputFormats |
|-----------|------|-------------------|--------------------|
| compatibility | 100 | 16 (`0x10`) | 32784 (`0x8010`) |
| compatibility | 101 | — | 32768 (`0x8000`) |
| alert | 100 | — | 33554432 (`0x2000000`) |
| default | 100 | 67108864 (`0x4000000`) | 67108864 (`0x4000000`) |
| telephony | 100 | 67108864 (`0x4000000`) | 67108864 (`0x4000000`) |
| speechRecognition | 100 | 67108864 (`0x4000000`) | 67108864 (`0x4000000`) |
| default | 101 | — | 33554432 (`0x2000000`) |
| media | 102 | — | 8388608 (`0x800000`) |

**TTY Log Evidence (2026-05-21 Wireless CarPlay, `ttyLog.txt:570-615`):**
```
[AirPlay] #### WifiAudioFormats = [
    { audioType : "compatibility"       type : 100  audioInputFormats : 16        audioOutputFormats : 32784 }
    { audioType : "compatibility"       type : 101                                audioOutputFormats : 32768 }
    { audioType : "alert"               type : 100                                audioOutputFormats : 33554432 }
    { audioType : "default"             type : 100  audioInputFormats : 67108864  audioOutputFormats : 67108864 }
    { audioType : "telephony"           type : 100  audioInputFormats : 67108864  audioOutputFormats : 67108864 }
    { audioType : "speechRecognition"   type : 100  audioInputFormats : 67108864  audioOutputFormats : 67108864 }
    { audioType : "default"             type : 101                                audioOutputFormats : 33554432 }
    { audioType : "media"               type : 102                                audioOutputFormats : 8388608 }
]
```

**TTY Log Evidence (Jan 2026 Wireless CarPlay — session-specific):**
```
> stream info: channel = 2  sample_rate = 48000  frame_size = 480  aot = 39  bitrate = 0
[AirPlay] Alt audio setting up AAC-ELD/48000/2
```
The `frame_size = 480 aot = 39` line above is **Jan-2026-session-specific** (an AAC-ELD alt-audio stream that was negotiated in that particular capture). The authoritative format-negotiation reference is the verified 8-entry 2026-05-21 array above.

**AAC Audio Object Types (aot):**
| aot | Name | Use |
|-----|------|-----|
| 2 | AAC-LC | Standard media (44.1/48kHz) |
| 39 | AAC-ELD | Enhanced Low Delay (wireless CarPlay) |

### AirPlay `type` vs USB-side audio IDs — two unrelated namespaces

The AirPlay-side `type` (100/101/102, carried inside `WifiAudioFormats`) is the **iPhone ↔ adapter** negotiation namespace. It is **not** the same ID space as the USB-side `audio_type` (1/2/3) and `decode_type` (2/4/5) documented earlier in this file.

The adapter terminates the AirPlay audio, **decodes AAC → PCM**, and **re-tags** the resulting streams with the USB-side `audio_type` / `decode_type` before forwarding to the host. There is no fixed numeric correspondence between the AirPlay `type` and the USB `audio_type` — the two ID spaces are unrelated.

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

## Host Audio Focus Architecture (AutoKit Decompilation, Mar 2026)

**Source:** Carlinkit AutoKit app v2025.03.19.1126 — manufacturer's reference implementation.

The AutoKit app creates **5 independent audio focus managers**, each with dedicated `AudioAttributes` (Android SDK 26+). This maps the adapter's audio commands to proper Android audio routing:

### Audio Focus Manager Mapping

| Manager | AudioAttributes Usage | ContentType | Focus Type | Adapter Trigger |
|---------|----------------------|-------------|------------|-----------------|
| **MediaManager** | USAGE_MEDIA (1) | CONTENT_TYPE_MUSIC (2) | AUDIOFOCUS_GAIN (1) | MEDIA_START (0x0A) |
| **NavManager** | USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (12) | CONTENT_TYPE_MUSIC (2) | AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK (3) | NAVI_START (0x07) |
| **CallManager** | USAGE_VOICE_COMMUNICATION (2) | CONTENT_TYPE_SPEECH (1) | AUDIOFOCUS_GAIN_TRANSIENT (2) | PHONECALL_START (0x05) |
| **RingManager** | USAGE_NOTIFICATION_RINGTONE (6) | CONTENT_TYPE_MUSIC (2) | AUDIOFOCUS_GAIN_TRANSIENT (2) | ALERT_START (0x0C) |
| **VoiceManager** | USAGE_VOICE_COMMUNICATION_SIGNALLING (16) | CONTENT_TYPE_SPEECH (1) | AUDIOFOCUS_GAIN_TRANSIENT (2) | SIRI_START (0x09) |

**Legacy API (SDK < 26):** MediaManager uses `STREAM_MUSIC` (3), CallManager uses `STREAM_VOICE_CALL` (0), RingManager uses `STREAM_RING` (2).

### Volume Ducking

On `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`:
- **Android Auto streaming:** Duck media volume to **0.8f** (80%)
- **All other modes:** Duck media volume to **0.2f** (20%)

### Audio Player Instances (8 Total)

The app pre-creates 8 AudioTrack instances covering all decode_type × stream_type combinations:

| # | decode_type | stream | Rate | Channels | Purpose |
|---|-------------|--------|------|----------|---------|
| 1 | 4 | Main | 48kHz | Stereo | CarPlay media (default) |
| 2 | 4 | Nav | 48kHz | Stereo | CarPlay navigation (default) |
| 3 | 3 | Main | 8kHz | Mono | Narrowband phone call |
| 4 | 4 | Main | 48kHz | Stereo | AA/HiCar media |
| 5 | 5/7 | Main | 16kHz | Mono/Stereo | Wideband voice (Siri, calls) |
| 6 | 6 | Main | 24kHz | Mono | Super-wideband voice |
| 7 | 5 | Nav | 16kHz | Mono | Navigation voice prompts |
| 8 | 4 | Nav | 48kHz | Stereo | AA navigation audio |

### Platform-Specific Audio Overrides

| Platform | Override |
|----------|----------|
| Renesas G6SH | CallManager uses USAGE_ASSISTANCE_SONIFICATION (13); VoiceManager delegates to NavManager |
| eCarX IHU3Q122 | Mic recording uses channel mask 3 |
| Intel HONG QI / alps changan | Mic recording uses channel mask 15 |
| Qualcomm msmnile / Leapmotor C10 | Mic source = VOICE_COMMUNICATION (7) |
| Qualcomm MSM8996 (JMEV) | Mic source = UNPROCESSED (10) |
| Default | Mic source = MIC (1), channel mask = CHANNEL_IN_MONO (16) |

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
