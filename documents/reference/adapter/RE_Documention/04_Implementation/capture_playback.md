# Capture Playback Implementation Guide

**Status:** Implementation reference from carlink_native
**Last Updated:** 2026-01-19

---

## Overview

The Capture Playback feature allows replaying previously captured USB sessions without requiring a physical adapter connection. This is useful for:

- Testing and debugging video/audio pipelines on emulators
- Reproducing issues from captured sessions
- Development without access to physical hardware

---

## Architecture

### Design Principle: Injection-Based Playback

The playback system **does not** create separate video/audio pipelines. Instead, it injects captured data into the existing pipeline, reusing the same:

- H264 decoder instance
- MediaCodec decoder
- Surface for video output
- Audio manager for audio

This ensures playback behavior matches live adapter behavior exactly.

### Data Flow

```
┌─────────────────────┐
│  Capture Files      │
│  (.json + .bin)     │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│ CaptureReplaySource │  Reads packets, maintains timing
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│CapturePlaybackManager│  Strips headers, routes by type
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│   Main Manager      │  injectVideoData() / injectAudioData()
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│  H264Renderer /     │  Same pipeline as live USB
│  AudioManager       │
└─────────────────────┘
```

---

## Capture File Format

### File Structure

Captures consist of two files:
- `capture.json` - Packet metadata (sequence, type, timestamp, offset, length)
- `capture.bin` - Raw binary packet data

### Format Differences: alternate-host vs carlink_native vs carlink_macOS

**CRITICAL:** The capture format differs between implementations. This affects header parsing.

#### Alternate-host format (with 12-byte record prefix)

```
Offset  Size  Description
──────  ────  ───────────────────────────────────
0       12    Capture record prefix (length, offset, type)
12      16    Protocol header (magic, length, type, checksum)
28      20    Video header (width, height, flags, pts, reserved)
48      N     H.264 Annex B data (with start codes)
```
**Total header size: 48 bytes**

#### carlink_native format (direct protocol, no record prefix)

```
Offset  Size  Description
──────  ────  ───────────────────────────────────
0       16    Protocol header (magic, length, type, checksum)
16      20    Video header (width, height, flags, pts, reserved)
36      N     H.264 Annex B data (with start codes)
```
**Total header size: 36 bytes**

#### carlink_macOS CLNK recorder format (Apr 2026)

The macOS CarLink client (`zeno.carlink.mac`, `SessionRecorder`) emits a third, container-oriented format distinct from both of the above. The 16-byte USB protocol header and `typeCheck` field are stripped by the recorder; direction and timing metadata are prepended per record.

```
File layout:
0       4     Magic  "CLNK" (ASCII)
4       4     Version (LE uint32, currently = 1)
8       8     Session start timestamp (LE uint64 ns since Unix epoch)
16      8     Session end timestamp   (LE uint64 ns)
24      N     Firmware version string, null-terminated (e.g. "2025.10.15.1127CAY\0")
...     -     Padding to 256 bytes total header
256     ...   Packet record stream (see below)

Per-packet record:
+0      1     Direction  (0x01 = TX host→adapter, 0x00 = RX adapter→host)
+1      4     Timestamp  (LE uint32 ms since session start)
+5      4     Type       (LE uint32 — protocol type; e.g. 0x06 video, 0x07 audio, 0xAA heartbeat)
+9      4     Length     (LE uint32 — payload byte count)
+13     N     Payload    (raw payload only; no 0x55AA55AA magic, no typeCheck)
```

**Total header size (protocol + video header, for injection back into carlink_native decode path):** 0 — the recorder has already stripped them. When replaying through a carlink_native-style pipeline, you must re-synthesize the 16-byte protocol header and (for video) the 20-byte video header before feeding the decoder.

**⚠ Video payload truncation (recorder-side lossy capture):** the macOS recorder **truncates every `type=0x06` VideoData payload to the first 512 bytes of H.264 plus the 20B video header**, yielding 532-byte records regardless of actual frame size. Confirmed: 65,374 / 65,377 video frames in a reference 2026-04-20 capture were exactly 532 B. **Full-frame replay is impossible from macOS captures** — only header + NAL-prefix inspection is reliable. For full-payload analysis, use carlink_native or alternate-host recorders.

Audio (`0x07`), heartbeat (`0xAA`), command (`0x08`), and other non-video payloads are captured in full.

### Format Detection

For the alternate-host vs carlink_native split (both start with protocol magic), check the first 4 bytes of the binary:
- If `aa 55 aa 55` (little-endian magic) → carlink_native format (36-byte video header)
- If NOT magic → alternate-host format (48-byte video header, skip 12-byte prefix)

For the carlink_macOS format, check the first 4 bytes for the ASCII magic `"CLNK"` (`43 4c 4e 4b`).

```kotlin
val firstFour = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
val format = when {
    firstFour == 0x4B4E4C43 -> CaptureFormat.CARLINK_MACOS       // "CLNK" ASCII
    firstFour == 0x55AA55AA -> CaptureFormat.CARLINK_NATIVE       // protocol magic
    else                    -> CaptureFormat.ALTERNATE_HOST       // with 12-byte record prefix
}
```

### Header Size Summary

| Packet Type | alternate-host | carlink_native | carlink_macOS |
|-------------|------------|----------------|---------------|
| **File preamble** | 0 | 0 | 256 bytes (CLNK + ts + fwver) |
| **Per-record prefix** | 12 | 0 | 13 (dir + ts + type + len) |
| **Protocol header** | 16 | 16 | **0 (stripped)** |
| **Video header** | 20 | 20 | 20 (prepended to H.264 payload) |
| **Total video prefix** | 48 | 36 | 13 (+ 20 internal video header = 33) |
| **Audio header** | 40 | 28 | 13 (+ 12 internal audio header = 25) |
| **Video payload** | full | full | **capped at 512 B** |

---

## Implementation Issues and Fixes

### Issue 1: Low-Latency Mode Incompatibility

**Symptom:** `IllegalArgumentException` when configuring MediaCodec

**Cause:** Emulator's goldfish video decoder doesn't support `KEY_LOW_LATENCY`

**Fix:** Check codec capability before setting:
```java
if (caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)) {
    mediaformat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
}
```

### Issue 2: Surface Contention

**Symptom:** MediaCodec configure failed with error 0xffffffea (-22 EINVAL)

**Cause:** A Surface can only be connected to one MediaCodec at a time

**Fix:** Use injection-based architecture - playback uses existing renderer instead of creating its own

### Issue 3: Incorrect Header Stripping (Critical)

**Symptom:** Video received but decoder produced no output (R:161/D:0)

**Cause:** Code stripped only 36 bytes (protocol + video header), but alternate-host capture files include a 12-byte prefix

**Investigation:**
```
Raw hex at video packet offset:
00000000: 390f 0000 7c1f 0000 0006 0000 aa55 aa55  <- Prefix + Magic
00000010: 1d0f 0000 0600 0000 f9ff ffff 6009 0000  <- Protocol header
00000020: c003 0000 67ff 7f01 3e05 9300 0000 0000  <- Video header
00000030: 0000 0001 2764 0032 ac13 1450 0960 79a6  <- H.264 START CODE here!
```

The H.264 start code (`00 00 00 01`) appears at offset 48, not 36.

**Fix:** Detect format and use correct header size:
```kotlin
val headerSize = if (isAlternateHostFormat) 12 + 16 + 20 else 16 + 20
```

### Issue 4: Audio Not Initialized for Playback

**Symptom:** No audio during playback (video worked fine)

**Cause:** `stop()` releases audio subsystem. `prepareForPlayback()` didn't re-initialize it.

**Fix:** Initialize audio in `prepareForPlayback()`:
```kotlin
fun prepareForPlayback() {
    // ... stop USB communication ...

    // Ensure audio is initialized for playback
    if (!audioInitialized && audioManager != null) {
        audioInitialized = audioManager?.initialize() ?: false
    }
}
```

### Issue 5: Audio Static from Tiny Packets

**Symptom:** Audio played as static/noise

**Cause:** Tiny audio packets (1 byte payload) at stream start corrupt PCM alignment (stereo 16-bit requires 4-byte frame alignment)

**Fix:** Filter minimum payload size:
```kotlin
private const val MIN_AUDIO_PAYLOAD_SIZE = 64

if (audioData.size < MIN_AUDIO_PAYLOAD_SIZE) {
    return  // Skip tiny packets
}
```

### Issue 6: Progress Timer Mismatch

**Symptom:** Timer showed "46s / 58s" at completion

**Cause:** Progress used relative time but duration used absolute session time

**Fix:** Use effective duration (last packet - first packet):
```kotlin
val effectiveDuration = lastPacketTime - firstPacketTime
callback.onProgress(currentTime, effectiveDuration)
```

### Issue 7: Premature Completion Signal

**Symptom:** UI navigated away while video still had buffered content

**Cause:** `onComplete()` fired when last packet dispatched, not when displayed

**Fix:** Add delay after last packet:
```kotlin
callback.onProgress(effectiveDuration, effectiveDuration)
delay(500)  // Allow buffers to drain
callback.onComplete()
```

### Issue 8: Stop Button Caused Restart Loop

**Symptom:** Pressing Stop caused playback to restart

**Cause:** `stopPlayback()` set state to `READY`, triggering auto-start

**Fix:** Stop button fully exits playback mode:
```kotlin
capturePlaybackManager.stopPlayback()
playbackPreference.setPlaybackEnabled(false)
manager.resetVideoDecoder()
manager.resumeAdapterMode()
```

---

## Issue 9: Video Freeze at Consistent Timestamps (Jan 2026)

### Symptom

Playing back carlink_native captures resulted in video freezing at the exact same timestamp every time. NAL types showed `-1` (parsing failure), indicating data corruption.

Alternate-host captures played back without issues.

### Root Cause: Capture Point Too Late in Pipeline

**Alternate-host recorders** capture data at USB boundary, BEFORE processing:
```
USB Read → [CAPTURE HERE] → Ring Buffer → Video Processing
```

**carlink_native** (before fix) captured AFTER ring buffer:
```
USB Read → Ring Buffer → Video Processing → [CAPTURE HERE]
```

This caused a **race condition**: Ring buffer could be partially overwritten before capture saved the data.

### Fix

1. **Move capture point earlier** - Capture immediately after USB read
2. **Allocate per-frame** - Prevents reuse-before-write bugs
3. **Fix InputStream.skip()** - Add retry loop for unreliable skip
4. **Fix PROTOCOL_MAGIC byte order** - Use little-endian storage

### Performance Considerations

| Scenario | Frame Budget | Allocations/sec | Impact |
|----------|--------------|-----------------|--------|
| 1080p @ 30fps | 33ms | ~3MB | Negligible |
| 2400x960 @ 60fps | 16.67ms | ~18MB+ | Potential GC pauses |

**Non-recording path is unchanged** - zero-copy directly into ring buffer.

---

## AAOS Multi-User Storage

**Critical:** Android Automotive uses multi-user profiles. Driver runs as **user 10**, not user 0.

| Path | Actual Location | Accessible By |
|------|-----------------|---------------|
| `/sdcard/` | `/data/media/0/` | User 0 only |
| `/storage/emulated/0/` | `/data/media/0/` | User 0 only |
| User 10's storage | `/data/media/10/` | User 10 apps (via SAF) |

**Common mistake:** `adb push file /sdcard/` puts files in user 0's storage, **invisible** to apps running as user 10.

### Correct Method (SAF File Picker)

```bash
# Requires root on emulator
adb root

# Copy to user 10's Download folder
adb shell "cp /data/local/tmp/capture.json /data/media/10/Download/"
adb shell "cp /data/local/tmp/capture.bin /data/media/10/Download/"

# Fix permissions
adb shell "chown media_rw:media_rw /data/media/10/Download/capture.*"
adb shell "chmod 664 /data/media/10/Download/capture.*"

# Trigger media scanner
adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d file:///data/media/10/Download/capture.json --user 10"
```

---

## H.264 NAL Unit Reference

| NAL Type | Description | Hex Header |
|----------|-------------|------------|
| 1 | Non-IDR slice (P-frame) | 0x21, 0x41, 0x61 |
| 5 | IDR slice (keyframe) | 0x25, 0x45, 0x65 |
| 7 | SPS (Sequence Parameter Set) | 0x27, 0x47, 0x67 |
| 8 | PPS (Picture Parameter Set) | 0x28, 0x48, 0x68 |

NAL type is extracted: `nalType = headerByte & 0x1F`

---

## Verification

### Check H.264 Data

```bash
adb logcat -s CARLINK:V | grep "Video packet"
```

**Correct:**
```
Video packet #1: 3849 bytes, first 16: 00 00 00 01 27 64 00 32...
```
(Starts with `00 00 00 01` start code)

**Incorrect:**
```
Video packet #1: 3861 bytes, first 16: 67 FF 7F 01 3E 05 93 00...
```
(Missing start code - header stripping is wrong)

### Check Audio

```bash
adb logcat -s CARLINK:V | grep -E "Audio packet|Injecting audio"
```

Audio types:
- `type=1` - Media audio
- `type=2` - Navigation audio
- `type=3` - Voice (Siri)
- `type=4` - Phone call

---

## Future Improvements

- Seek/scrub functionality
- Pause/resume playback
- Speed control (0.5x, 2x, etc.)
- Looping playback
- Skip initial handshake delay option
- Buffer pool for recording to reduce GC pressure

---

## References

- Source: `carlink_native/documents/playback_feature/playback.md`
- Implementation: carlink_native capture/playback system
