# Capture Playback Feature

## Overview

The Capture Playback feature allows replaying previously captured USB sessions without requiring a physical adapter connection. This is useful for:

- Testing and debugging video/audio pipelines on emulators
- Reproducing issues from captured sessions
- Development without access to physical hardware

## Architecture

### Design Principle: Injection-Based Playback

The playback system **does not** create separate video/audio pipelines. Instead, it injects captured data into the existing `CarlinkManager` pipeline, reusing the same:

- `H264Renderer` instance
- `MediaCodec` decoder
- `Surface` for video output
- `DualStreamAudioManager` for audio

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
│   CarlinkManager    │  injectVideoData() / injectAudioData()
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│  H264Renderer /     │  Same pipeline as live USB
│  DualStreamAudio    │
└─────────────────────┘
```

## File Structure

### Capture Files

Captures consist of two files:
- `capture.json` - Packet metadata (sequence, type, timestamp, offset, length)
- `capture.bin` - Raw binary packet data

### Capture File Location

Internal storage: `/data/user/<user_id>/<package>/files/captures/`

On AAOS (Android Automotive), the user ID is typically 10 for the driver profile.

## Code Components

### CaptureReplaySource.kt

Handles loading and replaying capture files:
- Parses JSON metadata
- Reads binary data at specified offsets
- Maintains original packet timing via coroutine delays
- Filters to IN-direction packets only (adapter → app)

### CapturePlaybackManager.kt

Orchestrates playback:
- Manages playback state (IDLE, LOADING, READY, PLAYING, COMPLETED, ERROR)
- Routes packets by type to appropriate processors
- Strips headers and extracts payload data
- Injects data into CarlinkManager

### CarlinkManager.kt (additions)

New methods for playback support:
```kotlin
fun injectVideoData(data: ByteArray, flags: Int)
fun injectAudioData(data: ByteArray, audioType: Int, decodeType: Int)
fun prepareForPlayback()  // Stops USB, keeps renderers
fun resumeAdapterMode()   // Resumes normal operation
fun isRendererReady(): Boolean
```

### UI Components

- `CapturePlaybackScreen.kt` - Settings UI for playback control (SAF file picker only)
- `SettingsTab.kt` - Added PLAYBACK tab to settings navigation
- `MainScreen.kt` - Playback overlay with progress timer and Stop button

### Playback Mode Toggle Behavior

The playback toggle in settings:
- **On enable:** Stops adapter search, prepares for playback
- **On disable:** Resumes adapter mode
- **Default state:** Always OFF on app start (not persisted across sessions)

## Captured Packet Structure

### Video Packets (Type 6)

The capture stores full USB packets with multiple headers:

```
Offset  Size  Description
──────  ────  ───────────────────────────────────
0       12    Capture prefix (length, flags, type)
12      16    Protocol header (magic, length, type, reserved)
28      20    Video header (width, height, flags, length, unknown)
48      N     H.264 Annex B data (with start codes)
```

**Total header size: 48 bytes**

### Audio Packets (Type 7)

```
Offset  Size  Description
──────  ────  ───────────────────────────────────
0       12    Capture prefix
12      16    Protocol header
28      12    Audio header (decode_type, volume, audio_type)
40      N     PCM audio data
```

**Total header size: 40 bytes**
**Audio header offset: 28 bytes** (for parsing decode_type/audio_type)

## Implementation Issues and Fixes

### Issue 1: Low-Latency Mode Incompatibility

**Symptom:** `IllegalArgumentException` when configuring MediaCodec

**Cause:** The emulator's goldfish video decoder doesn't support `KEY_LOW_LATENCY`

**Fix:** Check codec capability before setting:
```java
// H264Renderer.java
if (caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)) {
    mediaformat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
}
```

### Issue 2: Surface Contention

**Symptom:** MediaCodec configure failed with error 0xffffffea (-22 EINVAL)

**Cause:** Initial design created separate H264Renderer for playback, but a Surface can only be connected to one MediaCodec at a time

**Fix:** Redesigned to injection-based architecture - playback uses the existing CarlinkManager renderer instead of creating its own

### Issue 3: Incorrect Header Stripping (Critical)

**Symptom:** Video received but decoder produced no output (R:161/D:0)

**Cause:** Code stripped only 36 bytes (protocol + video header), but capture files include a 12-byte prefix before the protocol header

**Investigation:**
```
Raw hex at video packet offset:
00000000: 390f 0000 7c1f 0000 0006 0000 aa55 aa55  <- Prefix + Magic
00000010: 1d0f 0000 0600 0000 f9ff ffff 6009 0000  <- Protocol header
00000020: c003 0000 67ff 7f01 3e05 9300 0000 0000  <- Video header
00000030: 0000 0001 2764 0032 ac13 1450 0960 79a6  <- H.264 START CODE here!
```

The H.264 start code (`00 00 00 01`) appears at offset 48, not 36.

**Before fix:**
```kotlin
val headerSize = 16 + 20  // 36 bytes - WRONG
// Result: first bytes = "67 FF 7F 01..." (video header data, not H.264)
```

**After fix:**
```kotlin
val headerSize = 12 + 16 + 20  // 48 bytes - CORRECT
// Result: first bytes = "00 00 00 01 27 64..." (proper H.264 with start code)
```

### Issue 4: Playback Toggle UX

**Symptom:** User had to manually stop adapter connection before enabling playback

**Fix:** Playback toggle automatically calls `carlinkManager.stop()` when enabled and `carlinkManager.resumeAdapterMode()` when disabled

### Issue 5: Audio Not Initialized for Playback

**Symptom:** No audio during playback (video worked fine)

**Cause:** When playback toggle is enabled, `carlinkManager.stop()` releases the audio subsystem (`audioInitialized = false`). The `prepareForPlayback()` method didn't re-initialize audio, so `injectAudioData()` discarded all audio packets.

**Investigation:**
```
// Log showed audio being discarded:
[PLAYBACK_INJECT] Audio not initialized, discarding audio
```

**Fix:** Updated `prepareForPlayback()` to initialize audio if not already initialized:
```kotlin
fun prepareForPlayback() {
    // ... stop USB communication ...

    // Ensure audio is initialized for playback
    if (!audioInitialized && audioManager != null) {
        audioInitialized = audioManager?.initialize() ?: false
        if (audioInitialized) {
            logInfo("[PLAYBACK_INJECT] Audio initialized for playback", tag = Logger.Tags.AUDIO)
        }
    }
}
```

**Result:** Audio packets now flow through the same `DualStreamAudioManager` pipeline as live adapter audio.

### Issue 6: Audio Static from Tiny Packets

**Symptom:** Audio played as static/noise during playback of some captures

**Cause:** Some captures contain tiny audio packets (1 byte payload) at the start of audio streaming. These are likely audio initialization/control packets, not actual PCM data. Writing 1 byte to the AudioTrack corrupts PCM stream alignment (stereo 16-bit requires 4-byte frame alignment).

**Investigation:**
```
[PLAYBACK] Audio packet #1: 1 bytes, type=1, decode=4   <- Too small!
[PLAYBACK] Audio packet #2: 1 bytes, type=1, decode=4   <- Too small!
[PLAYBACK] Audio packet #3: 11520 bytes, type=1, decode=4  <- Normal
```

**Fix:** Added minimum payload size filter in `CapturePlaybackManager.processAudioPacket()`:
```kotlin
private const val MIN_AUDIO_PAYLOAD_SIZE = 64

// Skip tiny packets that would corrupt PCM stream alignment
if (audioData.size < MIN_AUDIO_PAYLOAD_SIZE) {
    logDebug("[$TAG] Skipping tiny audio packet: ${audioData.size} bytes (min: $MIN_AUDIO_PAYLOAD_SIZE)", tag = TAG)
    return
}
```

### Issue 7: Progress Timer Mismatch

**Symptom:** Playback timer showed "46s / 58s" at completion instead of matching values

**Cause:** Progress calculation used relative time (from first packet) but total duration used absolute session duration:
```kotlin
val currentTime = packet.timestampMs - firstPacketTime  // Relative
callback.onProgress(currentTime, session.durationMs)    // session.durationMs is absolute!
```

For a capture where first packet is at 11395ms and last at 58821ms:
- Progress shows: 58821 - 11395 = 47426ms
- Total shows: 58884ms (session duration)

**Fix:** Calculate and use effective duration (last packet - first packet):
```kotlin
val effectiveDuration = lastPacketTime - firstPacketTime
callback.onProgress(currentTime, effectiveDuration)
```

### Issue 8: Premature Completion Signal

**Symptom:** UI navigated to settings while video/audio still had buffered content

**Cause:** `onComplete()` fired when last packet was dispatched, not when it finished displaying

**Fix:** Added 500ms delay after last packet and final progress update:
```kotlin
// Final progress update to show 100%
callback.onProgress(effectiveDuration, effectiveDuration)

// Allow audio/video buffers to drain before signaling completion
delay(500)

callback.onComplete()
```

### Issue 9: Stop Button Caused Restart Loop

**Symptom:** Pressing Stop during playback caused playback to restart, producing errors

**Cause:** `stopPlayback()` set state to `READY`, which triggered the `LaunchedEffect` that auto-starts playback when state is `READY` and renderer is ready.

**Fix:** Stop button now fully exits playback mode instead of just stopping:
```kotlin
// Stop button onClick
capturePlaybackManager.stopPlayback()
playbackPreference.setPlaybackEnabled(false)  // Disable playback mode
carlinkManager.resetVideoDecoder()             // Flush video buffers
carlinkManager.resumeAdapterMode()             // Resume adapter search
```

### Issue 10: Playback Completion Not Cleaning Up

**Symptom:** After playback ended naturally, returning to main screen showed frozen last frame

**Cause:** Completion handler only navigated to settings without cleaning up video decoder

**Fix:** Completion now performs same cleanup as Stop button:
```kotlin
LaunchedEffect(playbackState) {
    if (playbackState == State.COMPLETED || playbackState == State.ERROR) {
        capturePlaybackManager.stopPlayback()
        playbackPreference.setPlaybackEnabled(false)
        carlinkManager.resetVideoDecoder()
        carlinkManager.resumeAdapterMode()
        onNavigateToSettings()
    }
}
```

## Playback Timing Behavior

### Initial Delay Before Video

Captures preserve original timing. If the capture session had a CarPlay handshake period before video started, playback will show the same delay.

Example from a capture:
- First IN packet: 11,395ms (handshake commands)
- First VIDEO packet: 19,038ms
- Delay before video: 19038 - 11395 = **7.6 seconds**

This is correct behavior - it matches the original session timing.

### Effective vs Session Duration

The playback timer shows **effective duration** (actual content time), not session duration:
- Session duration: Total time the capture ran
- Effective duration: Time from first IN packet to last IN packet

This ensures the timer reaches 100% when the last packet is processed.

## H.264 NAL Unit Reference

The H264Renderer expects Annex B format with start codes:

| NAL Type | Description | Hex Header |
|----------|-------------|------------|
| 1 | Non-IDR slice (P-frame) | 0x21, 0x41, 0x61 |
| 5 | IDR slice (keyframe) | 0x25, 0x45, 0x65 |
| 7 | SPS (Sequence Parameter Set) | 0x27, 0x47, 0x67 |
| 8 | PPS (Picture Parameter Set) | 0x28, 0x48, 0x68 |

NAL type is extracted from header byte: `nalType = headerByte & 0x1F`

## Testing

### AAOS Multi-User Storage

**Critical:** Android Automotive uses multi-user profiles. The driver runs as **user 10**, not user 0.

| Path | Actual Location | Accessible By |
|------|-----------------|---------------|
| `/sdcard/` | `/data/media/0/` | User 0 only |
| `/storage/emulated/0/` | `/data/media/0/` | User 0 only |
| User 10's storage | `/data/media/10/` | User 10 apps (via SAF) |

**Common mistake:** Using `adb push file /sdcard/` puts files in user 0's storage, which is **invisible** to apps running as user 10.

### Method 1: SAF File Picker (User 10's Download folder)

Place files in user 10's storage for SAF access:
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
adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///data/media/10/Download/capture.json --user 10"
```

Then: Settings → Playback → Enable toggle → Select JSON file → Select BIN file → Start Playback

### Method 2: Production Use

For real devices without ADB root:
1. Transfer files via USB drive, Bluetooth, or cloud storage
2. Use a file manager app (running as user 10) to move files to Downloads
3. Use the SAF file picker in the app to select the capture files

### Verifying Correct Operation

Check logcat for proper H.264 data:
```bash
adb logcat -s CARLINK:V | grep "Video packet"
```

**Correct output:**
```
Video packet #1: 3849 bytes, first 16: 00 00 00 01 27 64 00 32...
```
(Starts with `00 00 00 01` start code)

**Incorrect output:**
```
Video packet #1: 3861 bytes, first 16: 67 FF 7F 01 3E 05 93 00...
```
(Missing start code - header stripping is wrong)

### Verifying Audio

Check logcat for audio initialization and injection:
```bash
adb logcat -s CARLINK:V | grep -E "Audio packet|Injecting audio|Audio initialized"
```

**Correct output:**
```
[PLAYBACK_INJECT] Audio initialized for playback
[PLAYBACK] Audio packet #1: 11520 bytes, type=1, decode=4
[PLAYBACK_INJECT] Injecting audio #1: 11520 bytes, type=1, decode=4
```

Audio types:
- `type=1` - Media audio (music)
- `type=2` - Navigation audio
- `type=3` - Voice (Siri)
- `type=4` - Phone call

If audio packets show "Audio not initialized, discarding audio", the `prepareForPlayback()` fix is not applied.

## Configuration

Capture config in JSON file:
```json
{
  "config": {
    "includeVideoData": true,
    "includeAudioData": false,
    "includeMicData": true,
    "includeSpeakerData": true
  }
}
```

Note: If `includeAudioData` is false, audio will not play during capture playback.

## Future Improvements

- Seek/scrub functionality
- Pause/resume playback
- Speed control (0.5x, 2x, etc.)
- Looping playback
- Skip initial handshake delay option (start from first video packet)
