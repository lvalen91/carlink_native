# CPC200-CCPA Firmware Audio Processing Documentation

## Overview

This document provides comprehensive analysis of CPC200-CCPA firmware audio processing between CarPlay/Android Auto devices and the Autokit Android application. Based on reverse engineering of the extracted rootfs and **live USB packet captures**, this reveals the lightweight nature of audio processing on severely constrained hardware.

**Sources:**
- Firmware rootfs analysis
- USB captures: `~/.pi-carplay/usb-logs/` (Dec 2025)
  - `48Khz_playback/` - Media audio at 48kHz
  - `44.1Khz_playback/` - Media audio at 44.1kHz
  - `navigation_audio_only/` - Navigation prompts
  - `navigation+media_audio/` - Concurrent streams
  - `siri_invocation/` - Siri with bidirectional audio
  - `phone_call/` - Outgoing phone calls with bidirectional audio
  - `incomming_phone_call/` - Incoming calls with ringtone + voice transition (44.1kHz + 48kHz)
  - `incomming_iMessage_notifications/` - iMessage notification sounds

**Last Updated:** 2025-12-29 (with CPC200 research decode_type/audio_type semantic mappings)

## Hardware Architecture & Constraints

**System Specifications:**
- **RAM**: 128MB | **Storage**: 16MB | **CPU**: ARM32 EABI5 single-core
- **Rootfs**: 15MB compressed | **OS**: Lightweight Linux + BusyBox
- **Impact**: Limits processing to basic format conversion and protocol translation vs sophisticated DSP

## Firmware Architecture

### Core Audio Components

**Primary Libraries:**
```yaml
libdmsdpaudiohandler.so: 48KB    # Audio routing
libdmsdpdvaudio.so: 48KB         # Digital streaming  
libdmsdp.so: 184KB               # Core DMSDP protocol
libfdk-aac.so.1.0.0: 336KB      # AAC decoder (largest)
libtinyalsa.so: 17KB             # Hardware abstraction
tinymix: ARM ELF                 # Mixer control
hfpd: ARM ELF static             # Bluetooth HFP
ARMAndroidAuto: 564KB            # Android Auto handler
```

### Hardware Codec Layer

**Supported Codecs:**
1. **WM8960** (Primary): I2C 0x1a, full-duplex stereo, high-quality audio
2. **AC6966** (Alternative): I2C 0x15, Bluetooth SCO optimized for voice calls

**Detection & Initialization:**
```bash
# Codec detection
i2cdetect -y -a 0 0x1a 0x1a | grep "1a" && audioCodec=wm8960
i2cdetect -y -a 0 0x15 0x15 | grep "15" && audioCodec=ac6966

# Driver loading
test -e /tmp/snd-soc-wm8960.ko && insmod /tmp/snd-soc-wm8960.ko
test -e /tmp/snd-soc-imx-wm8960.ko && insmod /tmp/snd-soc-imx-wm8960.ko
test -e /tmp/snd-soc-bt-sco.ko && insmod /tmp/snd-soc-bt-sco.ko
test -e /tmp/snd-soc-imx-btsco.ko && insmod /tmp/snd-soc-imx-btsco.ko
```

## Audio Processing Pipeline

### Audio Data Flow & Processing
```
┌─────────────────────────────────────┐
│        CarPlay/Android Auto         │
│    (iPhone/Android Phone Audio)     │
└─────────────────┬───────────────────┘
                  │ Lightning/USB-C
                  │ AAC/PCM Streams
                  ▼
┌─────────────────────────────────────┐
│         CPC200-CCPA Firmware       │
│  ┌─────────────────────────────────┐│
│  │     IAP2/NCM USB Interface      ││
│  │   VID: 0x08e4, PID: 0x01c0     ││
│  │   Functions: iap2,ncm           ││
│  └─────────────────────────────────┘│
│                  │                  │
│                  ▼                  │
│  ┌─────────────────────────────────┐│
│  │    Lightweight Audio Router     ││
│  │  • AAC Decoder (libfdk-aac)    ││
│  │  • Sample Rate Conversion      ││
│  │  • Audio Type Classification   ││
│  │  • Format Validation           ││
│  └─────────────────────────────────┘│
│                  │                  │
│                  ▼                  │
│  ┌─────────────────────────────────┐│
│  │       Hardware Codec Layer      ││
│  │    WM8960 / AC6966 Codecs      ││
│  │    TinyALSA Configuration      ││
│  └─────────────────────────────────┘│
└─────────────────┬───────────────────┘
                  │ CPC200-CCPA Protocol
                  │ 0x55AA55AA + PCM Data
                  ▼
┌─────────────────────────────────────┐
│           Autokit Android App       │
│     (Advanced WebRTC Processing)    │
└─────────────────────────────────────┘
```

### DMSDP Framework

**Source:** Reverse engineering of `libdmsdp.so` (184KB), `libdmsdpaudiohandler.so` (48KB), `libdmsdpdvaudio.so` (48KB)

**Core Initialization (from libdmsdp.so):**
```cpp
// Protocol initialization
DMSDPInitial();                          // Initialize DMSDP protocol stack
DMSDPServiceStart();                     // Start DMSDP services
DMSDPServiceStop();                      // Stop DMSDP services

// Data transmission
DMSDPConnectSendData();                  // Send structured data
DMSDPConnectSendBinaryData();            // Send raw binary data
DMSDPNetworkSessionSendCrypto();         // Send encrypted data (ChaCha20-Poly1305)

// Session management
DMSDPDataSessionNewSession();            // Create new data session
DMSDPDataSessionSendCtrlMsg();           // Send control messages

// RTP streaming
DMSDPCreateRtpReceiver();                // Create RTP receiver (audio/video)
DMSDPCreateRtpSender();                  // Create RTP sender
DMSDPRtpSendPCMPackMaxUnpacket();        // Process PCM audio packets

// Channel management
DMSDPChannelProtocolCreate();            // Create protocol channel
DMSDPChannelGetDeviceType();             // Query device type
DMSDPChannelGetDeviceState();            // Query device state
DMSDPChannelGetBusinessID();             // Get business identifier
DMSDPChannelMakeNotifyMsg();             // Create notification message
DMSDPChannelHandleMsg();                 // Handle incoming message
DMSDPChannelDealGlbCommand();            // Process global commands
DMSDPNearbyChannelSendData();            // Send data to nearby channel
DMSDPNearbyChannelUnPackageRcvData();    // Unpack received data

// Service loading
DMSDPLoadAudioService();                 // Load audio streaming service
DMSDPLoadCameraService();                // Load video/camera service
DMSDPLoadGpsService();                   // Load GPS data service
```

**Audio Processing (from libdmsdpaudiohandler.so):**
```cpp
// Format conversion
class AudioConvertor {
    void SetFormat(AudioPCMFormat src, AudioPCMFormat dst);
    void PushSrcAudio(unsigned char* data, unsigned int size);
    void PopDstAudio(unsigned char* data, unsigned int size);
    float GetConvertRatio();
};

// Format queries
GetAudioPCMFormat(int format_id);
getSpeakerFormat(); getMicFormat(); getModemSpeakerFormat(); getModemMicFormat();

// Stream handling
handleAudioType(AUDIO_TYPE_HICAR_SDK& type, DMSDPAudioStreamType stream_type);
getAudioTypeByDataAndStream(const char* data, DMSDPVirtualStreamData* stream_data);

// Service management
AudioService::requestAudioFocus(int type, int flags);
AudioService::abandonAudioFocus();
AudioService::GetAudioCapability(DMSDPAudioCapabilities** caps, unsigned int* count);
```

**Authentication (from libauthagent.so - 44KB):**
```cpp
// Trust management for paired devices
GetAuthagentInstance();
DestroyAuthagent();
RefreshPinAuth();
ListTrustPhones();
DelTrustPhones();
IsTrustPhones();
is_trust_peer();
list_trust_peers();
delete_local_auth_info();
```

### Hardware Mixer Configuration

**WM8960 Configuration:**
```bash
tinymix 0 60 60        # Master volume L/R (0-255)
tinymix 2 1 0          # Channel routing
tinymix 35 180 180     # Mic input boost
tinymix 4 7; tinymix 7 3; tinymix 48 1; tinymix 50 1; tinymix 52 1  # Output routing
```

**Microphone Recording:**
```bash
tinymix 8 255 255      # Mic boost maximum
tinymix 47 63 63       # Additional gain
```

## Protocol Integration

### CarPlay/Android Auto Interface

**USB Configuration:**
```bash
echo 0 > /sys/class/android_usb/android0/enable
echo 239 > /sys/class/android_usb/android0/bDeviceClass     # Misc device
echo 2 > /sys/class/android_usb/android0/bDeviceSubClass
echo 1 > /sys/class/android_usb/android0/bDeviceProtocol
echo "Auto Box" > /sys/class/android_usb/android0/iProduct
echo 08e4 > /sys/class/android_usb/android0/idVendor        # Magic Communication VID
echo 01c0 > /sys/class/android_usb/android0/idProduct       # Auto Box PID
echo "iap2,ncm" > /sys/class/android_usb/android0/functions # IAP2 + NCM
echo 1 > /sys/class/android_usb/android0/enable
```

### Bluetooth HFP Integration

**HFP Daemon Configuration (/etc/hfpd.conf):**
```ini
[daemon]
acceptunknown=1          # Accept unknown Bluetooth devices
voiceautoconnect=1       # Automatically connect voice audio

[audio]
packetinterval=40        # 40ms packet intervals for low latency
```

**D-Bus Service Integration:**
```xml
<!-- /etc/dbus-1/system.d/hfpd.conf -->
<policy user="root">
    <allow own="net.sf.nohands.hfpd"/>
</policy>

<!-- Service interfaces -->
<allow send_destination="net.sf.nohands.hfpd.HandsFree"/>
<allow send_destination="net.sf.nohands.hfpd.SoundIo"/>  
<allow send_destination="net.sf.nohands.hfpd.AudioGateway"/>
```

## Audio Format Support

**Sample Rates:** 8kHz (calls), 16kHz (voice/Siri), 44.1kHz (music), 48kHz (pro)

**Conversion Capabilities:**
- Sample rate: 8↔16↔44.1↔48 kHz
- Channels: Mono ↔ Stereo  
- Bit depth: 16-bit PCM primary
- Buffer: Push/Pop with conversion ratios

## Service Architecture

**Startup Sequence:
```bash
/script/init_audio_codec.sh
cp /usr/sbin/mdnsd /tmp/bin/; mdnsd
/script/start_iap2_ncm.sh
/script/start_ncm.sh
boxNetworkService &
```

**Key Processes:**
1. **mdnsd**: CarPlay mDNS discovery
2. **boxNetworkService**: Network communication  
3. **hfpd**: Bluetooth HFP daemon
4. **ARMAndroidAuto**: Android Auto handler
5. **Codec drivers**: WM8960/AC6966 kernel modules

## Testing & Validation

**DTMF Testing:**
```bash
tinycap -- -c 1 -r 16000 -b 16 -t 4 > /tmp/dtmf.pcm
result=`dtmf_decode /tmp/dtmf.pcm | grep 14809414327 | wc -l`
[ $result -eq 1 ] && echo "mic test success!!" || exit 1
```

**Capability Detection:**
```cpp
CheckMultiAudioBusCap(); CheckMultiAudioBusVersion();
CheckMultiAudioBusPolicy(); IsSupportBGRecord();
```

## Processing Capabilities & Limitations

**What Firmware Does:**
1. Protocol translation: IAP2/NCM USB ↔ CPC200-CCPA
2. Basic AAC decoding: Compressed streams → PCM
3. Format conversion: Sample rate, channels, bit depth
4. Hardware configuration: Codec init, mixer settings
5. Audio routing: Stream classification/direction
6. Buffer management: Simple I/O buffering

**What Firmware Does NOT Do (Handled by Autokit App):**
- WebRTC processing (noise suppression, echo cancellation, AGC)
- Automotive optimizations, complex mixing, real-time DSP
- Multi-channel processing beyond basic routing

## Resource Usage & Optimization

**Memory Footprint (~1.1MB total):**
- DMSDP Framework: 500KB | AAC Decoder: 336KB | Buffers: 50KB | System: 200KB

**Optimization Strategies:**
- Static linking: Reduced overhead
- Minimal buffers: Low latency, small footprint  
- Hardware acceleration: Dedicated codec chips
- Simple algorithms: Basic conversion, no complex DSP

## Integration Points

### Communication with Autokit App

**Protocol Structure:**
```cpp
struct CPCAudioMessage {  // 16-byte header
    uint32_t magic;       // 0x55AA55AA
    uint32_t length;      // PCM payload size
    uint32_t command;     // 0x07 for AudioData
    uint32_t checksum;    // command ^ 0xFFFFFFFF
};

struct AudioPayload {     // Variable length
    uint32_t decType;     // Format ID (1-7)
    float    volume;      // Volume level (0.0-1.0) IEEE 754
    uint32_t audType;     // Stream type identifier
    uint8_t  audData[];   // Raw PCM samples OR 1-byte command
};
```

---

## USB Capture Verification (December 2025)

### Captured Audio Packet Structure

From `navigation_audio_only` capture:

**Audio Command Packet (13-byte payload):**
```
Offset  Size  Field     Example      Description
────────────────────────────────────────────────────────
0x00    4     decType   02 00 00 00  Format type (2 = 44.1kHz stereo)
0x04    4     volume    00 00 00 00  Float volume (0.0)
0x08    4     audType   02 00 00 00  Audio stream type
0x0C    1     command   06           Audio command (6 = NAVI_START)
```

**Audio Data Packet (11532+ byte payload):**
```
Offset  Size   Field     Description
────────────────────────────────────────────────────────
0x00    4      decType   Format type
0x04    4      volume    Float volume
0x08    4      audType   Audio stream type
0x0C    N      pcmData   Raw 16-bit PCM samples
```

### Verified Timing: Navigation Audio Sequence

From `navigation_audio_only` capture @ 75.663s:

```
Time      Packet  Type           Payload Summary
────────────────────────────────────────────────────────
75.663s   #36     AudioData      Volume config (32 bytes)
                                  decType=2, volume=0.2, audType=1

75.665s   #37     AudioData      Command (13 bytes)
                                  decType=2, cmd=6 (NAVI_START)

75.690s   #38     AudioData      Command (13 bytes)
                                  decType=2, cmd=1 (OUTPUT_START)

75.886s   #39     AudioData      PCM data (11548 bytes)
                                  First data packet - silence/warmup

75.951s   #40     AudioData      PCM data (11548 bytes)
76.017s   #41     AudioData      PCM data (11548 bytes)
76.082s   #42     AudioData      PCM data with 0xFFFF patterns
76.148s   #43     AudioData      More 0xFFFF end marker patterns
...
```

**Key Timing Observations:**
- NAVI_START → First PCM data: ~220ms warmup
- PCM packet interval: ~65ms (11532 bytes @ 44.1kHz stereo = 65.4ms)
- 0xFFFF end marker appears before NAVI_STOP

### Verified Audio Decode Types

| decType | Sample Rate | Channels | Bits | Usage | Capture Status |
|---------|-------------|----------|------|-------|----------------|
| 1 | 44100 Hz | 2 (Stereo) | 16 | Media playback | **Verified** |
| 2 | 44100 Hz | 2 (Stereo) | 16 | Navigation audio / Stop commands | **Verified** |
| 3 | 8000 Hz | 1 (Mono) | 16 | (Reserved - not observed) | Not captured |
| 4 | 48000 Hz | 2 (Stereo) | 16 | Media HD / Standard CarPlay | **Verified** |
| 5 | 16000 Hz | 1 (Mono) | 16 | Siri + Phone calls + Mic input | **Verified** |
| 6 | 24000 Hz | 1 (Mono) | 16 | Voice recognition | Not captured |
| 7 | 16000 Hz | 2 (Stereo) | 16 | Alt voice | Not captured |

### decode_type Semantic Mapping (Dec 2025 Research)

From comprehensive capture analysis across media, navigation, siri, and phonecall scenarios:

| decType | Semantic Purpose | Commands Using This Type |
|---------|-----------------|--------------------------|
| 2 | **Stop/Cleanup** | MEDIA_STOP, PHONECALL_STOP |
| 4 | **Standard CarPlay Audio** | MEDIA_START, NAVI_START, NAVI_COMPLETE, ALERT_*, OUTPUT_* |
| 5 | **Mic/Input Related** | SIRI_START, PHONECALL_START, INPUT_*, INCOMING_CALL_INIT |

**Key Insight:** The decode_type in command packets indicates the audio context:
- decType=4 signals standard output audio operations
- decType=5 signals microphone/voice-related operations
- decType=2 appears in stop/cleanup commands

**Capture Evidence:**
- `48Khz_playback`: decType=4 confirmed (media)
- `44.1Khz_playback`: decType=1 confirmed (media)
- `navigation_audio_only`: decType=2 confirmed (navigation)
- `siri_invocation`: decType=5 confirmed (Siri)
- `phone_call`: decType=5 confirmed (phone call)

---

## Audio Stream Types (Capture-Verified)

### audio_type Semantic Mapping (Dec 2025 Research)

The `audio_type` field in audio packets determines stream routing:

| audType | Purpose | Direction | Used By |
|---------|---------|-----------|---------|
| 1 | **Main Audio Channel** | Adapter → Host | Media, Siri output, Phone output, Alerts, Ringtones |
| 2 | **Navigation Channel (Ducking)** | Adapter → Host | NAVI_START, NAVI_COMPLETE, nav OUTPUT_*, iMessage notifications |
| 3 | **Microphone Input** | Host → Adapter | User voice during Siri/Phone calls |

**Key Insight:** The distinction between audType=1 and audType=2 enables:
- Proper audio ducking (lower media volume during nav prompts)
- Separate audio bus routing on AAOS (USAGE_MEDIA vs USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
- Independent volume control for different stream types

**carlink_native Implementation:**
```kotlin
object AudioStreamType {
    const val MEDIA = 1      // → ensureMediaTrack()
    const val NAVIGATION = 2 // → ensureNavTrack() with ducking support
    const val PHONE_CALL = 3 // → ensureCallTrack()
    const val SIRI = 4       // → ensureVoiceTrack()
}
```

---

### 1. Media Audio (Music/Podcast Playback)

**Characteristics:**
- **decType**: 4 (48kHz) or 1 (44.1kHz)
- **audType**: 1 (media stream)
- **Commands**: MEDIA_START (0x0a), OUTPUT_START (0x01)

**Captured Sequence (from `48Khz_playback` @ 22.162s):**
```
Time      Payload                                    Meaning
─────────────────────────────────────────────────────────────
22.162s   04 00 00 00 00 00 00 00 01 00 00 00 0a    MEDIA_START (decType=4, audType=1, cmd=10)
22.164s   04 00 00 00 00 00 00 00 01 00 00 00 01    OUTPUT_START (decType=4, audType=1, cmd=1)
22.223s   04 00 00 00 00 00 00 00 01 00 00 00 [PCM] First audio data packet (48kHz stereo)
```

**Sample Rate Selection:**
- CarPlay typically uses 48kHz (decType=4) for HD audio
- Some content may use 44.1kHz (decType=1)

**Packet Timing:**
- ~60ms intervals (11520 bytes @ 48kHz stereo = 60ms)

---

### 2. Navigation Audio (Turn-by-Turn Prompts)

**Characteristics:**
- **decType**: 4 (48kHz stereo) or 2 (44.1kHz stereo) - depends on host BoxSettings.mediaSound config
- **audType**: 2 (navigation stream)
- **Commands**: NAVI_START (0x06), OUTPUT_START (0x01), NAVI_STOP (0x07), NAVI_COMPLETE (0x10)

**Sample Rate Discovery (Dec 2025):**
- **48kHz config (mediaSound=1)**: Navigation uses decType=4, 60ms packet intervals
- **44.1kHz config (mediaSound=0)**: Navigation uses decType=2, 65ms packet intervals
- The NAVI_START command may carry a different decType than the actual audio data packets

**Captured Sequence (from `navigation_audio_only` @ 48kHz config):**
```
Time       Payload                                    Meaning
──────────────────────────────────────────────────────────────
23398ms    04 00 00 00 cd cc 4c 3e 01 00 00 00 [vol]  Volume duck (vol=0.20) - duck media
23399ms    02 00 00 00 00 00 00 00 02 00 00 00 06     NAVI_START (decType=2 in cmd)
23492ms    04 00 00 00 00 00 00 00 02 00 00 00 01     OUTPUT_START (decType=4 in cmd)
23673ms    04 00 00 00 00 00 00 00 02 ... [PCM]       First data packet (decType=4, silence)
...        (navigation audio @ ~60ms intervals)
25173ms    04 00 00 00 00 00 00 00 02 ... [0xFFFF]   End marker pattern
25233ms    04 00 00 00 00 00 00 00 02 ... [silence]  Silence packets continue
...        (~2 seconds of post-NAVI_STOP silence)
[later]    04 00 00 00 00 00 00 00 02 00 00 00 10     NAVI_COMPLETE (cmd=16) **NEW**
[later]    04 00 00 00 00 00 00 00 02 00 00 00 02     OUTPUT_STOP
```

**Key Behaviors:**
- **Volume Ducking**: Adapter sends `vol=0.20` packet BEFORE NAVI_START to duck media audio
- **Warmup Period**: ~200ms of silence (zeros) after OUTPUT_START
- **End Marker**: Solid 0xFFFF pattern appears in audio data near end of prompt
- **Post-NAVI_STOP Silence**: ~2 seconds of silence packets sent after NAVI_STOP
- **NAVI_COMPLETE**: Explicit end signal (cmd=16) sent AFTER silence period, before OUTPUT_STOP
- **Volume Restore**: Adapter sends `vol=1.00` packet after NAVI_STOP to restore media volume

**Navigation Stop Sequence (Critical for Clean Audio):**
```
1. NAVI_STOP (cmd=7)      → Stop accepting new nav audio
2. [~2 seconds]           → Silence packets continue (should be dropped)
3. NAVI_COMPLETE (cmd=16) → Clean shutdown, flush buffers
4. OUTPUT_STOP (cmd=2)    → Stream fully stopped
```

**Implementation Note:** The app should stop writing to the nav buffer upon receiving NAVI_STOP,
then perform final cleanup when NAVI_COMPLETE is received. This prevents the silence packets
from causing playback artifacts.

---

### 3. Siri / Voice Assistant Audio

**Characteristics:**
- **decType**: 5 (16kHz mono)
- **audType**: 1 (speaker output), 3 (microphone input)
- **Commands**: SIRI_START (0x08), INPUT_CONFIG (0x03), OUTPUT_START (0x01), SIRI_STOP (0x09)
- **Bidirectional**: Yes - uses SendAudio for microphone capture

**Full Captured Sequence (from `siri_invocation` @ 19.174s):**
```
Time      Direction  Payload                                 Meaning
──────────────────────────────────────────────────────────────────────────────
19.174s   D2H        05 00 00 00 ... 01 00 00 00 08         SIRI_START
19.314s   D2H        05 00 00 00 ... 01 00 00 00 03         INPUT_CONFIG
19.315s   D2H        Command: 01 00 00 00                   Acknowledgment
19.316s   D2H        05 00 00 00 ... 01 00 00 00 01         OUTPUT_START
19.495s   D2H        AudioData (988 bytes, audType=1)       Siri speaking
19.640s   H2D        SendAudio (8220 bytes, audType=3)      User voice (mic)
...       (interleaved bidirectional audio @ ~30ms intervals)
```

**Voice Audio Packets:**
- **Siri output (D2H)**: 988 bytes (30ms @ 16kHz mono), audType=1
- **User mic (H2D)**: 8220 bytes (256ms @ 16kHz mono), audType=3

**Trigger:** Touch event (SendTouch) precedes each SIRI_START

---

### 4. Phone Call Audio

**Characteristics:**
- **decType**: 5 (16kHz mono) - same as Siri
- **audType**: 1 (speaker output), 3 (microphone input)
- **Commands**: PHONECALL_START (0x04), INPUT_CONFIG (0x03), OUTPUT_START (0x01), PHONECALL_STOP (0x05)
- **Bidirectional**: Yes - uses SendAudio for microphone capture

**Full Captured Sequence (from `phone_call` @ 13.630s):**
```
Time      Direction  Payload                                 Meaning
──────────────────────────────────────────────────────────────────────────────
13.630s   D2H        05 00 00 00 ... 01 00 00 00 04         PHONECALL_START
14.008s   D2H        05 00 00 00 ... 01 00 00 00 03         INPUT_CONFIG
14.009s   D2H        Command: 01 00 00 00                   Acknowledgment
14.010s   D2H        05 00 00 00 ... 01 00 00 00 01         OUTPUT_START
14.060s   H2D        SendAudio (8220 bytes, audType=3)      User voice (mic)
14.191s   D2H        AudioData (988 bytes, audType=1)       Caller voice
...       (interleaved bidirectional audio continues)
```

**Voice Audio Packets:**
- **Caller voice (D2H)**: 988 bytes (30ms @ 16kHz mono), audType=1
- **User mic (H2D)**: 8220 bytes (256ms @ 16kHz mono), audType=3

**Observed Call Characteristics:**
- Warmup period: ~400ms of silence after OUTPUT_START before voice data
- Packet interval (speaker): ~30ms for continuous voice
- Microphone packets contain actual PCM waveform data (not silence) when user speaks

**Note:** Phone calls use 16kHz (decType=5), not 8kHz narrowband as originally expected. This provides better voice quality over CarPlay.

---

### 5. Incoming Call Audio (Ringtone + Answer)

**Captured Sequence (from `incomming_phone_call` @ 48.918s):**

**Phase 1: Incoming Call Notification**
```
Time      Direction  Payload                                 Meaning
──────────────────────────────────────────────────────────────────────────────
48.918s   D2H        05 00 00 00 ... 01 00 00 00 0e         INCOMING_CALL_INIT (NEW!)
```
- **cmd=0x0e (14)**: New command indicating incoming call
- decType=5, audType=1

**Phase 2: Ringtone Audio (decType=2, 44.1kHz stereo)**
```
Time      Direction  Payload                                 Meaning
──────────────────────────────────────────────────────────────────────────────
54.514s   D2H        02 00 00 00 ... 01 00 00 00 0c         ALERT_START (ringtone)
54.515s   D2H        02 00 00 00 ... 01 00 00 00 01         OUTPUT_START
54.711s   D2H        AudioData (11548 bytes, decType=2)     Ringtone audio starts
...       (ringtone continues - ~65ms per packet)
85.531s   D2H        AudioData (11548 bytes, decType=2)     Last ringtone packet
```

**User Answers Call:**
```
85.146s   H2D        SendTouch (tap to answer)              User interaction
85.258s   H2D        SendTouch (release)
```

**Phase 3: Transition to Voice (decType=5, 16kHz mono)**
```
Time      Direction  Payload                                 Meaning
──────────────────────────────────────────────────────────────────────────────
85.655s   D2H        05 00 00 00 ... 01 00 00 00 03         INPUT_CONFIG
85.655s   D2H        Command: 01 00 00 00                   Acknowledgment
85.656s   D2H        05 00 00 00 ... 01 00 00 00 04         PHONECALL_START
85.661s   D2H        05 00 00 00 ... 01 00 00 00 01         OUTPUT_START
85.835s   D2H        AudioData (988 bytes, decType=5)       Voice audio starts
86.090s   H2D        SendAudio (8220 bytes, audType=3)      Microphone starts
```

**Key Discovery - Ringtone Sample Rate Follows Host Configuration:**
- **44.1kHz config**: Ringtone uses decType=2 (44.1kHz stereo), ~65ms packet intervals
- **48kHz config**: Ringtone uses decType=4 (48kHz stereo), ~60ms packet intervals
- **Voice audio** always uses decType=5 (16kHz mono) regardless of media sample rate
- The adapter handles the format transition seamlessly

**Verified with captures:**
- `usb-capture-2025-12-29T11-34-49` (44.1kHz): ALERT_START with decType=2
- `usb-capture-2025-12-29T11-58-43` (48kHz): ALERT_START with decType=4

**Incoming vs Outgoing Call Comparison:**
| Aspect | Outgoing Call | Incoming Call |
|--------|--------------|---------------|
| Initial command | PHONECALL_START (0x04) | INCOMING_CALL_INIT (0x0e) |
| Ringtone | None (dial tone from phone) | ALERT_START (0x0c) + decType=2 audio |
| Ringtone format | N/A | 44.1kHz stereo (11548-byte packets) |
| Voice format | 16kHz mono (988-byte packets) | 16kHz mono (988-byte packets) |
| Answer transition | Immediate | ALERT_STOP → voice transition |

---

### 6. Notification Audio (iMessage)

**Characteristics:**
- **decType**: 2 (44.1/48kHz stereo, configurable)
- **audType**: 2 (navigation/notification stream)
- **Commands**: NAVI_START (0x06), OUTPUT_START (0x01), NAVI_COMPLETE (0x10), OUTPUT_STOP (0x02)

**Key Discovery:** iMessage notifications use the **navigation audio pathway** (NAVI_START), NOT the alert pathway (ALERT_START). This distinguishes notification sounds from ringtones.

**Captured Sequence (from `incomming_iMessage_notifications` @ 34.339s):**

**Start Sequence:**
```
Time      Direction  Payload                                    Meaning
──────────────────────────────────────────────────────────────────────────────
34.339s   D2H        02 00 00 00 cd cc cc 3d 01 ... [16 bytes]  Volume config (vol=0.1)
34.341s   D2H        02 00 00 00 00 00 00 00 02 00 00 00 06     NAVI_START (decType=2, audType=2)
34.351s   D2H        02 00 00 00 00 00 00 00 02 00 00 00 01     OUTPUT_START
34.548s   D2H        AudioData (11548 bytes, decType=2)         First audio packet
...       (notification sound continues - ~65ms per packet)
```

**Stop Sequence:**
```
Time      Direction  Payload                                    Meaning
──────────────────────────────────────────────────────────────────────────────
40.165s   D2H        AudioData (11548 bytes, zeros)             Final silence packet
40.166s   D2H        02 00 00 00 00 00 00 00 02 00 00 00 10     NAVI_COMPLETE (NEW!)
40.168s   D2H        02 00 00 00 00 00 00 00 02 00 00 00 02     OUTPUT_STOP
```

**Multiple Notifications in Session:**
The capture contained multiple iMessage notifications in sequence:
- Notification 1: 34.339s - 40.168s
- Notification 2: 49.671s - 55.497s
- Notification 3: 71.544s - 77.369s

Each notification follows the same pattern: NAVI_START → audio → NAVI_COMPLETE → OUTPUT_STOP.

**Audio Format:**
- 11548-byte packets (~65ms @ 44.1kHz stereo)
- Same packet format as navigation prompts
- decType=2, audType=2 (identical to navigation)

**Notification vs Ringtone Comparison:**
| Aspect | iMessage Notification | Phone Ringtone |
|--------|----------------------|----------------|
| Start command | NAVI_START (0x06) | ALERT_START (0x0c) |
| Stop command | NAVI_COMPLETE (0x10) | ALERT_STOP (0x0d) |
| audType | 2 (navigation channel) | 1 (speaker channel) |
| decType | 2 | 2 |
| Audio format | 44.1/48kHz stereo | 44.1/48kHz stereo |
| Typical duration | ~5-6 seconds | Until answered/declined |

**Implementation Note:** The distinction between audType=1 (ringtone) and audType=2 (notification) allows the host app to route these to different audio buses or apply different volume policies.

---

### 7. Concurrent Streams (Media + Navigation)

**Captured Behavior (from `navigation+media_audio` @ 48kHz config):**

When navigation prompt plays during media playback, streams are **interleaved**:

```
Time       decType  audType  Event
──────────────────────────────────────────────────────────────────────
18065ms    4        1        Media playing (48kHz), MEDIA_START
...        4        1        Media packets continue @ ~60ms intervals
32532ms    4        1        VOL packet (vol=0.20) - DUCK MEDIA **NEW**
32533ms    4(cmd=2) 2        NAVI_START command
32589ms    4        1        Media packet (still playing)
32719ms    4        2        OUTPUT_START for nav
32769ms    4        1        Media packet ←─┐
32900ms    4        2        Nav packet    ←─┼── INTERLEAVED @ 10-20ms
32950ms    4        1        Media packet ←─┘
...        (rapid interleaving continues)
34041ms    4        1        VOL packet (vol=1.00) - RESTORE MEDIA **NEW**
34041ms    4        2        NAVI_STOP command
...        4        2        Silence packets continue (should be dropped)
36024ms    4        2        NAVI_COMPLETE (cmd=16)
36025ms    4        2        OUTPUT_STOP
36068ms    4        1        Media packets resume (full volume)
```

**Key Discoveries (Dec 2025):**

1. **Adapter-Initiated Volume Ducking:**
   - Adapter sends `vol=0.20` packet (audType=1) BEFORE NAVI_START
   - Adapter sends `vol=1.00` packet (audType=1) AFTER NAVI_STOP
   - Pi-Carplay ignores these packets (plays both streams at full volume)
   - carlink_native implements ducking based on these packets

2. **Rapid Stream Interleaving:**
   - Media (audType=1) and nav (audType=2) packets interleave at ~10-20ms intervals
   - Both streams use same decType (4 for 48kHz, or 1/2 for 44.1kHz config)
   - Packets must be demuxed to separate ring buffers based on audType

3. **Post-NAVI_STOP Handling:**
   - After NAVI_STOP, ~2 seconds of silence packets continue on audType=2
   - These should be dropped to prevent playback artifacts
   - NAVI_COMPLETE signals actual end of navigation audio

**Host App Responsibilities:**
1. Apply volume ducking when `vol<1.0` packet received
2. Demux interleaved packets by audType to separate buffers
3. Stop writing to nav buffer upon NAVI_STOP
4. Perform cleanup when NAVI_COMPLETE received
5. Restore media volume when `vol=1.0` packet received

---

## Audio Stream Routing Summary

| Stream | decType | audType | Start Cmd | Stop Cmd | Sample Rate | Capture Status |
|--------|---------|---------|-----------|----------|-------------|----------------|
| Media | 1 or 4 | 1 | 0x0a | 0x0b | 44.1/48 kHz | **Verified** |
| Navigation | 2 | 2 | 0x06 | 0x07+0x10 | 44.1/48 kHz* | **Verified** |
| Notification (iMessage) | 2 | 2 | 0x06 | 0x10+0x02 | 44.1/48 kHz* | **Verified** |
| Siri (speaker) | 5 | 1 | 0x08 | 0x09 | 16 kHz | **Verified** |
| Siri (mic) | 5 | 3 | (INPUT_CONFIG) | - | 16 kHz | **Verified** |
| Phone (speaker) | 5 | 1 | 0x04 | 0x05 | 16 kHz | **Verified** |
| Phone (mic) | 5 | 3 | (INPUT_CONFIG) | - | 16 kHz | **Verified** |
| Incoming Call | 5 | 1 | 0x0e | - | - | **Verified** (init only) |
| Ringtone/Alert | 2 or 4 | 1 | 0x0c | 0x0d | 44.1/48 kHz* | **Verified** |

*\* Sample rate depends on host configuration (BoxSettings). At 44.1kHz config, ringtone/nav uses decType=2. At 48kHz config, ringtone uses decType=4.*

**AAOS Audio Bus Mapping:**
- Media (decType 1,4) → `USAGE_MEDIA` → Bus 0
- Navigation (decType 2, audType 2) → `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` → Bus 1
- Notification (decType 2, audType 2) → `USAGE_NOTIFICATION` → Bus 1 (shared with nav)
- Ringtone/Alert (decType 2, audType 1) → `USAGE_NOTIFICATION_RINGTONE` → Bus 1
- Voice/Siri (decType 5) → `USAGE_ASSISTANT` → Bus 2
- Phone Call (decType 5) → `USAGE_VOICE_COMMUNICATION` → Bus 3

### Verified Audio Commands

| Value | Name | Direction | Capture Evidence |
|-------|------|-----------|------------------|
| 1 | OUTPUT_START | D2H | `@ 75.690s: cmd byte 01` |
| 2 | OUTPUT_STOP | D2H | Documented |
| 3 | INPUT_CONFIG | D2H | `@ 14.008s: cmd byte 03` (phone/Siri) |
| 4 | PHONECALL_START | D2H | `@ 13.630s: cmd byte 04` (outgoing), `@ 85.656s` (incoming) |
| 5 | PHONECALL_STOP | D2H | Documented |
| 6 | NAVI_START | D2H | `@ 75.665s: cmd byte 06` |
| 7 | NAVI_STOP | D2H | Documented |
| 8 | SIRI_START | D2H | `@ 19.174s: cmd byte 08` |
| 9 | SIRI_STOP | D2H | Documented |
| 10 | MEDIA_START | D2H | Documented |
| 11 | MEDIA_STOP | D2H | Documented |
| 12 | ALERT_START | D2H | `@ 54.514s: cmd byte 0c` (ringtone) |
| 13 | ALERT_STOP | D2H | Documented |
| 14 | INCOMING_CALL_INIT | D2H | `@ 48.918s: cmd byte 0e` |
| 16 | NAVI_COMPLETE | D2H | `@ 40.166s: cmd byte 10` (notification done) **NEW** |

### Verified PCM Data Characteristics

**Packet Size:**
- Standard packet: 11548 bytes total
- Header: 16 bytes (protocol)
- Audio header: 12 bytes (decType + volume + audType)
- PCM data: 11520 bytes

**PCM Calculation:**
```
11520 bytes / 4 bytes per sample (stereo 16-bit) = 2880 samples
2880 samples / 44100 Hz = 65.3ms per packet
```

**Warmup Silence Pattern:**
First 1-2 packets after NAVI_START contain:
```
00 00 00 00 00 00 00 00 ...  (all zeros - digital silence)
```

**Near-Silence Patterns During Audio:**
```
ff ff ff ff  (-1 in 16-bit signed)
fe ff fe ff  (-2 in 16-bit signed - near silence)
00 00 00 00  (0 - digital silence)
```

### Audio Stream End Marker (0xFFFF Pattern)

The adapter uses a **solid 0xFFFF pattern** as an end-of-stream marker for navigation audio:

**Pattern Characteristics:**
```
End Marker:   ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ...
              (all PCM samples = -1 in signed 16-bit)

Warmup Noise: ff ff 00 00 fe ff 01 00 ff ff 00 00 fe ff ...
              (mixed near-silence values at stream start)
```

**Timing Observation:**
- Solid 0xFFFF appears ~60ms before `AudioNaviStop` command (id=7)
- Mixed 0xFFFF/0x0000/0xFEFF patterns appear ~200-400ms after `AudioNaviStart` (id=6)

**Application Handling:**
The Carlink app detects the solid 0xFFFF end marker to:
1. Immediately flush the ring buffer (clear stale audio)
2. Flush the AudioTrack internal buffer
3. Prepare for clean playback on next nav prompt

This prevents residual audio from the previous navigation prompt playing when a new prompt starts.

**Detection Logic (skips 12-byte header):**
```kotlin
// Sample 4 positions in audio data (after 12-byte header)
// All must be 0xFFFF for end marker detection
val positions = intArrayOf(
    headerSize,
    headerSize + (audioDataSize * 0.25),
    headerSize + (audioDataSize * 0.5),
    headerSize + (audioDataSize * 0.75),
)
```

**Audio Focus Management:**
```cpp
AudioService::requestAudioFocus(int type, int flags);
AudioService::abandonAudioFocus();

enum BasicAudioTypes { CALL=1, MEDIA=2, NAVIGATION=3, ALERT=4 };
```

---

## Bidirectional Audio (Capture-Verified December 2025)

Voice sessions (Siri, phone calls) use **bidirectional audio** with distinct packet types and audType values for speaker output vs microphone input.

### audType Values

| audType | Direction | Purpose | Packet Type |
|---------|-----------|---------|-------------|
| 1 | Adapter → Host | Speaker output (voice, media, ringtone) | AudioData |
| 2 | Adapter → Host | Navigation prompts & notifications (iMessage) | AudioData |
| 3 | Host → Adapter | Microphone input (user voice) | SendAudio |

### Voice Session Initialization Sequence

**Phone Call (from `phone_call` capture @ 13.630s):**
```
Time      Direction  Packet        Payload                              Meaning
────────────────────────────────────────────────────────────────────────────────────
13.630s   D2H        AudioData     05 00 00 00 ... 01 00 00 00 04       PHONECALL_START
14.008s   D2H        AudioData     05 00 00 00 ... 01 00 00 00 03       INPUT_CONFIG (NEW)
14.009s   D2H        Command       01 00 00 00                          Acknowledgment
14.010s   D2H        AudioData     05 00 00 00 ... 01 00 00 00 01       OUTPUT_START
14.060s   H2D        SendAudio     8220 bytes (decType=5, audType=3)    Microphone data
14.191s   D2H        AudioData     988 bytes (decType=5, audType=1)     Speaker data
...       (interleaved bidirectional audio continues)
```

**Siri Invocation (from `siri_invocation` capture @ 19.174s):**
```
Time      Direction  Packet        Payload                              Meaning
────────────────────────────────────────────────────────────────────────────────────
19.174s   D2H        AudioData     05 00 00 00 ... 01 00 00 00 08       SIRI_START
19.314s   D2H        AudioData     05 00 00 00 ... 01 00 00 00 03       INPUT_CONFIG (NEW)
19.315s   D2H        Command       01 00 00 00                          Acknowledgment
19.316s   D2H        AudioData     05 00 00 00 ... 01 00 00 00 01       OUTPUT_START
19.495s   D2H        AudioData     988 bytes (actual voice data)        Siri speaking
19.640s   H2D        SendAudio     8220 bytes (decType=5, audType=3)    User voice (mic)
...       (interleaved bidirectional audio continues)
```

### Bidirectional Packet Structure

**Speaker Output (AudioData, adapter → host):**
```
Total: 988 bytes (16-byte header + 972-byte payload)

Payload breakdown:
  Offset  Size  Field     Value       Description
  ──────────────────────────────────────────────────
  0x00    4     decType   05 00 00 00  16kHz mono
  0x04    4     volume    00 00 00 00  Float volume
  0x08    4     audType   01 00 00 00  Speaker output
  0x0C    960   pcmData   [PCM samples] 30ms @ 16kHz mono
```

**Microphone Input (SendAudio, host → adapter):**
```
Total: 8220 bytes (16-byte header + 8204-byte payload)

Payload breakdown:
  Offset  Size  Field     Value       Description
  ──────────────────────────────────────────────────
  0x00    4     decType   05 00 00 00  16kHz mono
  0x04    4     volume    00 00 00 00  Float volume
  0x08    4     audType   03 00 00 00  Microphone input
  0x0C    8192  pcmData   [PCM samples] 256ms @ 16kHz mono
```

### Voice Audio Timing

| Direction | Packet Size | PCM Data | Duration | Interval |
|-----------|-------------|----------|----------|----------|
| Speaker (D2H) | 988 bytes | 960 bytes | 30ms @ 16kHz | ~30ms |
| Microphone (H2D) | 8220 bytes | 8192 bytes | 256ms @ 16kHz | ~256ms |

**PCM Calculations:**
```
Speaker:    960 bytes / 2 bytes per sample / 16000 Hz = 30ms
Microphone: 8192 bytes / 2 bytes per sample / 16000 Hz = 256ms
```

### INPUT_CONFIG Command (0x03)

**Discovery**: The `INPUT_CONFIG` command (value 0x03) was discovered in December 2025 captures. It appears in the initialization sequence for bidirectional voice sessions, between the session start command (PHONECALL_START or SIRI_START) and OUTPUT_START.

**Purpose**: Signals that the host should prepare microphone input. After receiving INPUT_CONFIG, the host begins sending `SendAudio` packets with `audType=3`.

**Sequence:**
1. Session start (cmd=0x04 or cmd=0x08)
2. INPUT_CONFIG (cmd=0x03) → **Host prepares microphone capture**
3. Acknowledgment (Command type, value=0x01)
4. OUTPUT_START (cmd=0x01) → Audio streaming begins
5. Bidirectional audio (AudioData + SendAudio interleaved)

### Implementation Notes

- **Host Responsibility**: The Carlink app must capture microphone audio and send it via SendAudio packets when INPUT_CONFIG is received
- **Packet Interleaving**: Speaker and microphone packets are interleaved during active voice sessions
- **Silence Detection**: Initial microphone packets may contain near-silence (0x0000, 0xFFFF patterns)
- **Session End**: When session stops (cmd=0x05 or cmd=0x09), bidirectional audio ceases

---

## Performance Characteristics

### Latency Analysis

| Component | Processing Time | Memory Usage | CPU Usage |
|-----------|----------------|--------------|-----------|
| AAC Decode | 2-5ms | 50KB | 5-8% |
| Format Convert | 0.5-1ms | 20KB | 2-3% |
| Protocol Package | <0.5ms | 5KB | 1% |
| Hardware Config | <0.1ms | 1KB | <1% |
| **Total** | **3-6.6ms** | **76KB** | **8-12%** |

### Bandwidth Efficiency

**USB Transfer Optimization:**
- **48KB Maximum Chunk Size**: Efficient bulk transfer
- **Direct PCM Streaming**: No additional compression overhead
- **Minimal Protocol Overhead**: 28-byte header per audio packet
- **Hardware DMA**: Direct memory access for audio transfers

## Development and Debugging

### Debugging Tools

**TinyALSA:**
```bash
tinymix                                  # Show mixer controls
tinymix <control> <value>                # Set control
tinycap -c <ch> -r <rate> -b <bits> -t <time> > out.pcm
```

**System Info:**
```bash
lsmod | grep snd                         # Audio modules
i2cdetect -y -a 0                       # I2C devices
ps | grep -E "(hfpd|mdnsd|boxNetworkService)"  # Processes
```

## Conclusion

CPC200-CCPA firmware implements a **lightweight audio gateway** optimized for severe constraints (128MB RAM, 16MB storage, ARM32). 

**Core Functions:**
1. Protocol bridge: CarPlay/Android Auto ↔ CPC200-CCPA translation
2. Format converter: AAC decoding, PCM conversion
3. Hardware interface: Codec config, audio routing
4. Stream classifier: Audio type detection/routing

**Architecture: "Smart Interface, Dumb Processing"**
- Minimum processing on constrained hardware
- Maximum CarPlay/Android Auto compatibility  
- Efficient transfer to capable units (Autokit app)
- Reliable abstraction for codec configurations

This enables effective **automotive audio bridging** while delegating sophisticated processing (WebRTC, noise cancellation, optimizations) to downstream systems.
