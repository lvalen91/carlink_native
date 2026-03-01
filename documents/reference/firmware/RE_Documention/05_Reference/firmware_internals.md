# CPC200-CCPA Firmware Internals

**Purpose:** Technical reference for firmware architecture and internal processing
**Consolidated from:** carlink_native firmware analysis, binary reverse engineering
**Last Updated:** 2026-02-19 (Deduplicated: replaced verbatim copies with cross-references to canonical sources; added context labels)

---

## Architecture Overview

**[Hardware]** The CPC200-CCPA operates as an intelligent protocol bridge with severe hardware constraints. See `01_Firmware_Architecture/hardware_platform.md` for complete hardware specifications and resource constraints.

**Design Philosophy:** "Smart Interface, Dumb Processing" - the adapter handles protocol translation and format conversion, delegating sophisticated processing (WebRTC, noise cancellation) to the host application.

---

## Dual Data Path Architecture (r2 Analysis Feb 2026)

**[Firmware] [CarPlay]** CarPlay sessions use **two parallel paths** running simultaneously:

```
iPhone ──── AirPlay Session ────→ AppleCarPlay binary ──→ ARMadb-driver ──→ USB ──→ Host App
  │                                   ("dumb pipe")
  │                                   Video (H.264), Audio (PCM/AAC), HID
  │
  └──── iAP2 Session ──────────→ ARMiPhoneIAP2 binary ──→ ARMadb-driver ──→ USB ──→ Host App
                                     (9 engines)
                                     NaviJSON, GNSS, CallState, MediaPlayer,
                                     Communication, Power, VehicelStat
```

**AirPlay layer** (`AppleCarPlay`): Pure A/V relay. Receives H.264 video via AirPlay RTSP, forwards raw NAL units. Receives audio (PCM/AAC/ALAC). Handles HID touch/button relay. Does NOT parse any metadata — all rich content (now playing, caller ID, navigation UI) is rendered as pixels in the video stream.

**iAP2 layer** (`ARMiPhoneIAP2`): Runs in parallel. Parses ALL structured metadata via 9 engines (see `key_binaries.md` for complete field catalogs). Broadcasts parsed data via `BroadCastCFValueIfNeedSend_ToAccessoryDaemon` through the MiddleMan interface.

**Key insight**: NaviJSON and GNSS are confirmed flowing to the host app via USB. DashboardInfo bitmask (default=1) controls which engines are initialized — see `01_Firmware_Architecture/configuration.md` for full bitmask documentation, live test results, and assembly evidence.

**ARMadb-driver relay specifics (r2 Feb 2026)**:
- ARMadb-driver has **two reception paths**: a BroadCast handler (`aav.0x0001a7f1`) that only extracts playback status + audio signals, and a DashBoard_DATA handler (`fcn.00017b74`) that forwards engine data as opaque USB type 0x2A with subtypes
- Exhaustive r2 string search: **zero** title/artist/album/CallState/SignalStrength/Battery field name strings in ARMadb-driver — only `OniAPUpdateMediaPlayerPlaybackStatus` callback exists for media
- NaviJSON confirmed via 0x2A subtype 200; NowPlaying may arrive as 0x2A with a different subtype as opaque binary plist — **USB capture needed to verify which subtypes actually arrive**
- Battery: CiAP2PowerEngine does NOT call BroadCast at all (r2 verified) — local only
- Communication/Cellular: broadcast via MiddleMan but no corresponding handler in ARMadb-driver confirmed

---

## CarPlay Mode State Machine (r2 Analysis Feb 2026)

**[CarPlay] [Firmware]** From `AirPlayReceiverSessionMakeModeStateFromDictionary`:

**Mode channels** (from `changeModes` / `modesChanged`):

| Channel | Purpose |
|---------|---------|
| `screen` | Main screen active/inactive |
| `mainAudio` | Primary audio stream |
| `speech` | Speech recognition (Siri) active |
| `phoneCall` | Phone call active |
| `turnByTurn` | Turn-by-turn navigation audio active |

Log: `Modes changed: screen %s, mainAudio %s, speech %s (%s), phone %s, turns %s`

**Sub-properties**: `speechMode`, `speechRecognition`, `usingScreen`, `altScreen`

### Session Control Commands

| Command | Purpose |
|---------|---------|
| `startSession` / `sessionDied` | Session lifecycle |
| `changeModes` / `modesChanged` | Mode state changes |
| `requestUI` / `requestSiri` | UI/Siri activation |
| `setNightMode` / `setLimitedUI` | Display mode |
| `setAudioVolume` (with `durationMs`) | Volume control |
| `duckAudio` / `unduckAudio` | Audio ducking |
| `forceKeyFrame` / `forceKeyFrameNeeded` | H.264 IDR request |
| `setUpStreams` / `tearDownStreams` | AV stream lifecycle |
| `updateTimestamps` / `timestampsUpdated` | RTP timestamp sync |
| `disableBluetooth` | Disable BT on device |

### Instrument Cluster / Alt Screen

**Configuration Gate:** `AdvancedFeatures` riddleBoxCfg key (boolean 0-1, NOT a bitmask — max=1 enforced):
- Value 1 → sets exported global `g_bSupportNaviScreen` (enables alt screen video)
- **`g_bSupportViewarea`** is set from `HU_VIEWAREA_INFO` file content, NOT from AdvancedFeatures (r2 + live verified Feb 2026)
- Can also be bypassed by sending `naviScreenInfo` in BoxSettings (navi only). See `configuration.md` for full details.

| Function/String | Purpose |
|---|---|
| `_AltScreenSetup` / `_AltScreenStart` / `_AltScreenTearDown` | Alt screen lifecycle |
| `_AltScreenThread` | Alt screen render thread |
| `AirPlayAltScreenReceiver` | Separate video receiver (own port) |
| `AltVideoFrame` | Message type 0x2C carrier |
| `altScreenSuggestUIURLs` | URLs HU suggests for alt screen |
| `maps:/car/instrumentcluster` / `maps:/car/instrumentcluster/map` | Instrument cluster URLs |
| `g_bSupportNaviScreen` | HU feature flag for NaviScreen (set by AdvancedFeatures bit 0) |
| `g_bSupportViewarea` | HU feature flag for main screen ViewArea/SafeArea (set from `HU_VIEWAREA_INFO` file having valid dimensions — NOT from AdvancedFeatures) |
| `g_bNeedNaviStream` | Runtime flag: navi stream is requested |
| `featureAltScreen` / `featureViewAreas` | Phone feature flags (reported by iPhone) |
| `HU_VIEWAREA_INFO` / `HU_SAFEAREA_INFO` | **Main screen** view/safe area (gated by `g_bSupportViewarea`) |
| `HU_NAVISCREEN_INFO` | Alt screen resolution config |
| `HU_NAVISCREEN_VIEWAREA_INFO` / `HU_NAVISCREEN_SAFEAREA_INFO` | Alt screen layout areas (gated by `g_bSupportNaviScreen`) |
| `viewAreaTransitionControl` / `viewAreaStatusBarEdge` | View area dict properties (transition hardcoded false) |
| `drawUIOutsideSafeArea` | 0=black outside SafeArea, 1=home wallpaper extends to ViewArea (Maps/apps/controls stay inside SafeArea regardless — live verified iOS 18) |
| `DockPosition` | Dock/sidebar position in view area (float64) |

**Runtime Global Flags (AppleCarPlay binary, exported symbols):**

| Global | Purpose |
|--------|---------|
| `g_bSupportNaviScreen` | HU supports navi/cluster screen |
| `g_bSupportViewarea` | HU supports view area reporting |
| `g_bNeedNaviStream` | Navi stream is needed/requested |
| `g_bStartSiri` | Siri session is active |
| `g_bStartPhoneCall` | Phone call is active |
| `g_bInNaviMode` | Currently in navigation mode |
| `g_bVideoHide` | Video is hidden (privacy/screen off) |

---

## AirPlay-Layer Features (Binary Strings Feb 2026)

### Focus Domains

**[CarPlay] [Firmware]** The adapter manages 4 independent focus domains for CarPlay resource control:

| Domain | Purpose |
|--------|---------|
| `Audio` | Audio output focus (media, nav prompts, calls) |
| `Video` | Screen/display focus |
| `Navi` | Navigation audio focus |
| `NaviScreen` | Navigation video (instrument cluster) focus |

Strings: `AudioFocusGainType`, `VideoFocusGainType`, `NaviFocusGainType`, `NaviScreenFocusGainType`

### 6 Audio Channel States (ARMadb-driver)

Beyond the 3 audio_type values in the USB protocol (1=media, 2=nav, 3=mic), the adapter internally tracks 6 audio channel states:

| Channel | USB audio_type | Purpose |
|---------|---------------|---------|
| Media | 1 | Music playback |
| Navi | 2 | Navigation prompts |
| PhoneCall | — | Phone call audio |
| Siri | — | Voice assistant |
| Alert | — | System alerts |
| InputConfig | — | Microphone routing |

D-Bus signals: `kRiddleAudioSignal_MEDIA_START/STOP`, `kRiddleAudioSignal_ALERT_START/STOP`, `kRiddleAudioSignal_PHONECALL_Incoming`

**Note:** PhoneCall, Siri, and Alert audio are all sent to the host using the same USB audio_type values — the internal distinction is for the adapter's own focus management, not the USB protocol.

### App Launch Commands

The adapter can programmatically launch specific CarPlay apps on the iPhone:

| Command | Target App |
|---------|-----------|
| `LaunchAppMaps` | Apple Maps |
| `LaunchAppMusic` | Apple Music / Now Playing |
| `LaunchAppNowPlaying` | Now Playing screen |
| `LaunchAppPhone` | Phone app |

These are triggered via `RequestAppLaunch` in the iAP2 layer or AirPlay session commands.

### Haptic Feedback

String: `kAirPlayProperty_HIDHaptic` — supports haptic feedback relay to iPhone for button presses.

### Fake iPhone Mode

`InitFakeiPhoneMode` — adapter can impersonate a specific iPhone model to the head unit. Used for compatibility with head units that restrict connections to known iOS devices.

### LimitedUI Mode

`setLimitedUI` — adapter signals the phone that the vehicle is in a restricted UI state (e.g., driving). CarPlay responds by simplifying its interface: shorter lists, larger tap targets, and restricted keyboard input.

---

## CarPlay Audio Codec Support (r2 Analysis Feb 2026)

**[CarPlay]** Complete codec list from `AppleCarPlay_unpacked`:

| Format | Variants |
|--------|----------|
| PCM | 8000/16/1, 8000/16/2, 16000/16/1, 16000/16/2, 24000/16/1, 24000/16/2, 32000/16/1, 32000/16/2 |
| PCM | 44100/16/1, 44100/16/2, 44100/24/1, 44100/24/2, 48000/16/1, 48000/16/2, 48000/24/1, 48000/24/2 |
| ALAC | 44100/16/2, 44100/24/2, 48000/16/2, 48000/24/2 |
| AAC-LC | 44100/2, 48000/2 |
| AAC-ELD | 44100/2, 48000/2 |

Format notation: `codec/sampleRate/bitDepth/channels`

---

## CarPlay Encryption Key Names (r2 Analysis Feb 2026)

> **Full pairing protocol documented in:** `02_Protocol_Reference/wireless_carplay.md`

**[CarPlay]** Binary strings from `AppleCarPlay_unpacked` reveal the internal key derivation labels:

| Key Label | Purpose |
|-----------|---------|
| `Control-Read-Encryption-Key` | Decrypt control channel from phone |
| `Control-Write-Encryption-Key` | Encrypt control channel to phone |
| `DataStream-Output-Encryption-Key` | Encrypt media/data to phone |
| `DataStream-Input-Encryption-Key` | Decrypt media/data from phone |
| `Events-Read-Encryption-Key` | Decrypt event channel from phone |
| `Events-Write-Encryption-Key` | Encrypt event channel to phone |
| `Pair-Verify-ECDH-Salt` / `Pair-Verify-ECDH-Info` | HKDF params for pair-verify M1 |
| `Pair-Verify-Encrypt-Salt` / `Pair-Verify-Encrypt-Info` | HKDF params for pair-verify M3 |

**Additional RTSP endpoint** not in capture data: `/log` (device log retrieval)

**Additional Bonjour service**: `_mfi-config._tcp.` (MFi configuration, alongside `_carplay._tcp` and `_airplay._tcp`)

---

## DMSDP Framework

**[Firmware]** The Digital Media Streaming DisplayPort (DMSDP) framework is Huawei's distributed multimedia protocol stack (HarmonyOS/OpenHarmony). It provides the core protocol implementation for all projection protocols, handling RTP streaming, session management, crypto, and channel multiplexing.

See `binary_analysis/key_binaries.md` for complete DMSDP protocol function listing with addresses, core library inventory, and layered architecture details.

---

## Audio Processing Internals

**[Firmware]** This section documents the adapter's internal audio processing pipeline — the C++ classes and data flow within the CPC200-CCPA firmware itself (not the host application).

### MicAudioProcessor Class

```cpp
class MicAudioProcessor {
    void PushAudio(unsigned char* data, unsigned int size, unsigned int type);
    void PopAudio(unsigned char* data, unsigned int size, unsigned int type);
    void Reset();
};
// Mangled: _ZN17MicAudioProcessor[9PushAudio|8PopAudio|5Reset]E*
```

### AudioService Class

```cpp
class AudioService {
    void PushMicData(unsigned char* data, unsigned int size, unsigned int type);
    bool IsUsePhoneMic();
    bool IsSupportBGRecord();
    void OpenAudioRecord(const char* profile, int p1, int p2, const DMSDPProfiles*);
    void CloseAudioRecord(const char* profile, int p3);
    void requestAudioFocus(int type, int flags);
    void abandonAudioFocus();
    void GetAudioCapability(DMSDPAudioCapabilities** caps, unsigned int* count);
    void OnCallStateChangeE(CALL_STATE state);
    void getCurAudioType(int*, int*);
    void OnAudioFocusChange(int);
    void OnMediaStatusChange(MEDIA_STATE);
};
// Mangled: _ZN12AudioService[11PushMicData|15OpenAudioRecord|16CloseAudioRecord]E*
```

### AudioConvertor Class

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

### Audio Type Enumeration

```cpp
enum MicrophoneAudioTypes {
    AUDIO_TYPE_VOICE_COMMAND = 1,  // Siri, Google Assistant
    AUDIO_TYPE_PHONE_CALL = 2,     // Hands-free calling
    AUDIO_TYPE_VOICE_MEMO = 3,     // Voice recording
    AUDIO_TYPE_NAVIGATION = 4,     // Navigation voice input
};
```

### Audio Processing Pipeline

```
┌─────────────────────────────────────┐
│        CarPlay/Android Auto         │
│    (iPhone/Android Phone Audio)     │
└─────────────────┬───────────────────┘
                  │ Lightning/USB-C
                  │ AAC/PCM Streams
                  ▼
┌─────────────────────────────────────┐
│         CPC200-CCPA Firmware        │
│  ┌─────────────────────────────────┐│
│  │     IAP2/NCM USB Interface      ││
│  │   VID: 0x08e4, PID: 0x01c0      ││
│  │   Functions: iap2,ncm           ││
│  └─────────────────────────────────┘│
│                  │                  │
│                  ▼                  │
│  ┌─────────────────────────────────┐│
│  │    Lightweight Audio Router     ││
│  │  • AAC Decoder (libfdk-aac)     ││
│  │  • Sample Rate Conversion       ││
│  │  • Audio Type Classification    ││
│  │  • Format Validation            ││
│  └─────────────────────────────────┘│
│                  │                  │
│                  ▼                  │
│  ┌─────────────────────────────────┐│
│  │       Hardware Codec Layer      ││
│  │    WM8960 / AC6966 Codecs       ││
│  │    TinyALSA Configuration       ││
│  └─────────────────────────────────┘│
└─────────────────┬───────────────────┘
                  │ CPC200-CCPA Protocol
                  │ 0x55AA55AA + PCM Data
                  ▼
┌─────────────────────────────────────┐
│           Host Application          │
│     (Advanced WebRTC Processing)    │
└─────────────────────────────────────┘
```

### Microphone Data Flow

```
Host App → USB NCM → boxNetworkService → MicAudioProcessor →
AudioConvertor → DMSDP RTP → CarPlay/Android Auto
```

---

## Video Processing Internals (Binary Verified Jan 2026)

**[Firmware] [Protocol]** **CRITICAL:** Video from CarPlay/Android Auto is **forwarded passthrough** - the adapter does NOT decode, transcode, or re-encode the H.264 stream.

### Video Data Flow (Phone → Host)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         VIDEO DATA FLOW                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   ┌──────────────┐                                                       │
│   │   iPhone/    │  AirPlay/iAP2                                        │
│   │ Android Auto │  H.264 stream                                        │
│   └──────┬───────┘                                                       │
│          │                                                               │
│          ▼                                                               │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │                    AppleCarPlay Binary                            │  │
│   │  ┌────────────────────────────────────────────────────────────┐  │  │
│   │  │  AirPlayReceiverSessionScreen_ProcessFrames                 │  │  │
│   │  │  _AirPlayReceiverSessionScreen_ProcessFrame                 │  │  │
│   │  │  ScreenStreamProcessData                                    │  │  │
│   │  └────────────────────────────────────────────────────────────┘  │  │
│   │                           │                                       │  │
│   │                           │ H.264 NAL units (unchanged)           │  │
│   │                           ▼                                       │  │
│   │  ┌────────────────────────────────────────────────────────────┐  │  │
│   │  │  CRiddleUnixSocketServer (IPC to ARMadb-driver)            │  │  │
│   │  │  "### Send screen h264 frame data failed!"                  │  │  │
│   │  │  "### Send h264 I frame data %d byte!"                      │  │  │
│   │  └────────────────────────────────────────────────────────────┘  │  │
│   └──────────────────────────────────────────────────────────────────┘  │
│          │                                                               │
│          │ Unix Socket                                                   │
│          ▼                                                               │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │                    ARMadb-driver Binary                           │  │
│   │  ┌────────────────────────────────────────────────────────────┐  │  │
│   │  │  CMiddleManClient (receives video data)                     │  │  │
│   │  │  "recv CarPlay videoTimestamp:%llu" (at 0x6d139)            │  │  │
│   │  └────────────────────────────────────────────────────────────┘  │  │
│   │                           │                                       │  │
│   │                           │ Add headers only                      │  │
│   │                           ▼                                       │  │
│   │  ┌────────────────────────────────────────────────────────────┐  │  │
│   │  │  _SendDataToCar (at 0x18e2c)                                │  │  │
│   │  │  - Prepend USB header (16 bytes, magic 0x55AA55AA)          │  │  │
│   │  │  - Prepend video header (20 bytes: W, H, PTS, flags)        │  │  │
│   │  │  - "may need send ZLP" (at 0x6b823)                         │  │  │
│   │  └────────────────────────────────────────────────────────────┘  │  │
│   └──────────────────────────────────────────────────────────────────┘  │
│          │                                                               │
│          │ USB Bulk Transfer                                             │
│          ▼                                                               │
│   ┌──────────────┐                                                       │
│   │   Host App   │  H.264 + 36-byte header                              │
│   │  (Decodes)   │  Host performs MediaCodec/FFmpeg decode              │
│   └──────────────┘                                                       │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Key Video Functions (Binary Analysis)

| Binary | Function/String | Address | Purpose |
|--------|-----------------|---------|---------|
| AppleCarPlay | `AirPlayReceiverSessionScreen_ProcessFrames` | 0x7ecbf | Receives AirPlay screen |
| AppleCarPlay | `_AirPlayReceiverSessionScreen_ProcessFrame` | 0x7ecea | Processes single frame |
| AppleCarPlay | `### Send screen h264 frame data failed!` | 0x9016d | H.264 send error |
| AppleCarPlay | `### Send h264 I frame data %d byte!` | 0x90196 | I-frame send log |
| ARMadb-driver | `recv CarPlay videoTimestamp:%llu` | 0x6d139 | Timestamp logging |
| ARMadb-driver | `_SendDataToCar iSize: %d` | 0x6b823 | USB transmission |
| ARMadb-driver | USB magic `0x55AA55AA` | 0x62e18 | Header constant |

### No Video Codec in Firmware

**Verified absence of video codec libraries:**
- ❌ No FFmpeg (`libavcodec`, `libavformat`)
- ❌ No x264/x265
- ❌ No libvpx (VP8/VP9)
- ❌ No OpenH264
- ❌ No hardware video decoder imports

**Only codec found:** AAC audio (`aacDecoder_*`, `aacEncoder_*` in AppleCarPlay)

**libdmsdpdvcamera.so video functions are for REVERSE CAMERA:**
- `OmxVideoEncoder*` - encodes backup camera feed TO phone
- Not used for CarPlay video FROM phone

---

## Hardware Codec Configuration

**[Hardware]** See `01_Firmware_Architecture/hardware_platform.md` for audio codec hardware details (WM8960/AC6966 detection, kernel modules, TinyALSA mixer configuration).

---

## D-Bus Service Integration

**[Firmware]**

### HFP Daemon Provenance

The `hfpd` binary is derived from the **nohands** open-source HFP implementation (`net.sf.nohands.hfpd` D-Bus service). It implements HFP 1.6 with:
- Three-way calling support (CHLD 0-4)
- Wideband speech: CVSD + mSBC codecs
- D-Bus interfaces: `HandsFree`, `SoundIo`, `AudioGateway`

The binary itself is custom-encrypted (high-entropy `.text` section), but the D-Bus policy files and service names confirm the nohands lineage.

### HFP Daemon Configuration (/etc/hfpd.conf)

```ini
[daemon]
acceptunknown=1          # Accept unknown Bluetooth devices
voiceautoconnect=1       # Automatically connect voice audio

[audio]
packetinterval=40        # 40ms packet intervals for low latency
```

### D-Bus Policy (/etc/dbus-1/system.d/hfpd.conf)

```xml
<policy user="root">
    <allow own="net.sf.nohands.hfpd"/>
</policy>

<allow send_destination="net.sf.nohands.hfpd.HandsFree"/>
<allow send_destination="net.sf.nohands.hfpd.SoundIo"/>
<allow send_destination="net.sf.nohands.hfpd.AudioGateway"/>
```

### HFP AT Command Exchange (NEW Jan 2026)

The HFP daemon exchanges AT commands with the phone for hands-free profile setup:

**Adapter → Phone:**
```
AT+BRSF=63           # Supported features bitmask (adapter)
AT+CIND=?            # Query indicator descriptions
AT+CMER=3,0,0,1      # Enable unsolicited result codes
AT+CLIP=1            # Enable caller ID
AT+CCWA=1            # Enable call waiting
AT+CHLD=?            # Query call hold modes
AT+CIND?             # Query current indicator values
```

**Phone → Adapter:**
```
+BRSF: 879           # Supported features bitmask (phone)
+CIND: ("call",(0,1)),("callsetup",(0-3)),("service",(0-1)),...
+CHLD: (0,1,2,3)     # Supported hold modes
+BSIR: 0/1           # In-band ring tone setting
+CIND: 0,0,0,0,0,4,0 # Current indicator values
OK                   # Command acknowledged
```

**Feature Bitmask (BRSF):**
| Bit | Feature |
|-----|---------|
| 0 | Three-way calling |
| 1 | EC/NR function |
| 2 | Voice recognition |
| 3 | In-band ring tone |
| 4 | Voice tag |
| 5 | Call reject |

### MiddleMan IPC Architecture (Binary Strings Feb 2026)

All projection protocols communicate through a MiddleMan interface server using Unix domain sockets:

| Interface Class | Protocol |
|-----------------|----------|
| `CCarPlay_MiddleManInterface` | CarPlay (primary) |
| `CCarPlay_MiddleManInterface_iAP2InternalUse` | CarPlay iAP2 metadata relay |
| `CAndroidAuto_MiddleManInterface` | Android Auto |
| `CAndroidMirror_MiddleManInterface` | Android screen mirroring |
| `CHiCar_MiddleManInterface` | Huawei HiCar |
| `CICCOA_MiddleManInterface` | China ICCOA standard |
| `CDVR_MiddleManInterface` | DVR/dashcam feed |
| `CiOS_MiddleManInterface` | Generic iOS interface |
| `CNoAirPlay_MiddleManInterface` | Fallback when AirPlay unavailable |

The `CMiddleManClient_iAPBroadCast` class handles iAP2 metadata broadcast relay from `ARMiPhoneIAP2` engines to `ARMadb-driver`.

### Adapter Filesystem Config Paths

| Path | Persistence | Purpose |
|------|-------------|---------|
| `/etc/riddle.conf` | Flash | Main JSON config (all riddleBoxCfg keys) |
| `/etc/riddle_default.conf` | Flash | Factory defaults (copied on reset) |
| `/etc/riddle_special.conf` | Flash | OEM overrides (synced via `--specialConfig`) |
| `/tmp/.riddle_default.conf` | RAM | Runtime shadow copy of defaults |
| `/etc/RiddleBoxData/` | Flash | Persistent data store |
| `/etc/RiddleBoxData/AIEIPIEREngines.datastore` | Flash | Cached iAP2 engine negotiation (must delete after DashboardInfo/GNSSCapability changes) |
| `/tmp/RiddleBoxData/` | RAM | Runtime data (HU_GPS_DATA, etc.) |
| `/etc/airplay.conf` | Flash | Active AirPlay config (copied from brand or car variant) |
| `/etc/airplay_brand.conf` | Flash | OEM AirPlay config |
| `/etc/airplay_car.conf` | Flash | Car-specific AirPlay config |
| `/etc/airplay_siri.conf` | Flash | Siri-specific AirPlay config |
| `/etc/hostapd.conf` | Flash | WiFi AP config (SSID, channel) |
| `/etc/bluetooth/hcid.conf` | Flash | Bluetooth name config |
| `/etc/bluetooth/eir_info` | Flash | Extended Inquiry Response data |

### org.riddle D-Bus Interface

```cpp
// HUD Commands
HUDComand_A_HeartBeat
HUDComand_A_ResetUSB
HUDComand_A_UploadFile
HUDComand_B_BoxSoftwareVersion
HUDComand_D_BluetoothName
kRiddleHUDComand_A_Reboot
kRiddleHUDComand_CommissionSetting

// Audio Signals
kRiddleAudioSignal_MEDIA_START
kRiddleAudioSignal_MEDIA_STOP
kRiddleAudioSignal_ALERT_START
kRiddleAudioSignal_ALERT_STOP
kRiddleAudioSignal_PHONECALL_Incoming
```

---

## USB Protocol Message Dispatch (ARMadb-driver)

**[Protocol]** See `binary_analysis/key_binaries.md` for the complete ARMadb-driver key function table with addresses (message sender, pre-processor, dispatcher, buffer init, etc.).

### Adapter-to-Host Message Senders

| Function | Address | Message Types | Trigger |
|----------|---------|---------------|---------|
| `fcn.00018628` | `0x18628` | 0x06, 0x09, 0x0B, 0x0D, 0xA1 | State-dependent routing |
| `fcn.000186ba` | `0x186ba` | 0x14, 0xA1 | ManufacturerInfo response |
| `fcn.00018850` | `0x18850` | 0x06, 0x0B | HiCar device list |
| `fcn.00018990` | `0x18990` | StartPhoneLink data | Phone connection |
| `fcn.0001af48` | `0x1af48` | 0x01, 0x1E, 0xF0 | Display resolution |

### Status Event Strings

| String | Address | Associated Type |
|--------|---------|-----------------|
| `OnCarPlayPhase %d` | `0x5c415` | Phase (0x03) |
| `OnAndroidPhase _val=%d` | `0x5bf52` | Phase (0x03) |
| `DeviceBluetoothConnected` | `0x5bc88` | Status event |
| `DeviceWifiConnected` | `0x5bcbd` | Status event |
| `CMD_BOX_INFO` | `0x5b44c` | BoxSettings (0x19) |
| `CMD_CAR_MANUFACTURER_INFO` | `0x5b3e4` | ManufacturerInfo (0x14) |
| `_SendDataToCar` | `0x5b823` | Debug logging |

---

## Service Architecture

**[Firmware]** See `01_Firmware_Architecture/initialization.md` for complete boot sequence, startup scripts, and service registration.

### ARMadb-driver Process Architecture (Live Verified Feb 2026)

The main `ARMadb-driver` process (PID 199) acts as a supervisor, spawning 3 child processes:

| Child | Function | Role |
|-------|----------|------|
| Child 1 | `_usb_monitor_main` | USB hotplug monitoring via libusb |
| Child 2 | `CMiddleManServer` | IPC broker (Unix datagram socket `/var/run/phonemirror`) |
| Child 3 | `_hu_link_main` | Head Unit USB accessory link |

Max 30 restarts before full reset. See `key_binaries.md` for MiddleMan IPC protocol details.

### Boot Timing Observations (Live Verified Feb 2026)

> Full boot script listing: `01_Firmware_Architecture/initialization.md`

**[Firmware]** Live-observed startup timing from `/etc/init.d/rcS` + `start_main_service.sh`:

1. **t=0s** — ARMadb-driver starts (PID 199, supervisor for 3 children)
2. **t=3s** — mdnsd + iAP2/NCM drivers start
3. **t=7s** — boxNetworkService starts
4. **t=13s** — Boa web server + WiFi AP start

7 chipset variants supported for BT/WiFi init (RTL8822CS, etc.).

### Web Server (Boa v0.94, Live Verified Feb 2026)

See `01_Firmware_Architecture/web_interface.md` for web server architecture and `03_Security_Analysis/vulnerabilities.md` for unauthenticated api.cgi endpoint analysis.

### USB Gadget Configuration

**[Hardware]** See `01_Firmware_Architecture/hardware_platform.md` for USB gadget configuration.

---

## Performance Metrics

**[Firmware]**

### Audio Processing Performance

| Component | Time | Memory | CPU |
|-----------|------|--------|-----|
| USB Reception | <0.5ms | 5KB | <1% |
| MicAudioProcessor | 1-2ms | 20KB | 3-5% |
| Format Conversion | 0.5-1ms | 15KB | 2-3% |
| RTP Assembly | <0.5ms | 10KB | 1-2% |
| Protocol Transmission | 1-2ms | 8KB | 2-3% |
| **Total Audio Pipeline** | **3-6ms** | **58KB** | **9-14%** |

### Video Processing Performance (CarPlay/AA → Host)

**IMPORTANT:** The adapter does NOT decode or transcode H.264 video from CarPlay/Android Auto. Video is **forwarded passthrough** with only header prepending.

| Component | Time | Memory | CPU |
|-----------|------|--------|-----|
| AirPlay Reception (AppleCarPlay) | 1-2ms | 100KB | 2-5% |
| NAL Unit Parsing (keyframe detect) | <0.5ms | 10KB | <1% |
| Unix Socket IPC | <0.5ms | 50KB | 1-2% |
| USB Header Construction | <0.5ms | 36 bytes | <1% |
| USB Bulk Transfer | 1-2ms | 50KB | 2-3% |
| **Total Video Pipeline** | **3-5ms** | **~200KB** | **6-12%** |

**Binary Evidence (Jan 2026):**
- No H.264 decoder/encoder libraries (FFmpeg, x264, etc.) found
- Only codec imports are AAC (audio): `aacDecoder_*`, `aacEncoder_*`
- Video encoder in `libdmsdpdvcamera.so` is for **reverse camera** (TO phone), not CarPlay
- Strings: `### Send screen h264 frame data failed!` - sends raw H.264
- Log: `recv CarPlay videoTimestamp:%llu` - timestamp forwarding only

### Video Resolution/FPS Limits (Binary Verified)

**No hardcoded limits found.** The adapter forwards whatever the phone sends.

| Limit Type | Binary Evidence | Finding |
|------------|-----------------|---------|
| Resolution | `recv CarPlay size info:%dx%d` | Logged only, no validation |
| FPS | `kScreenProperty_MaxFPS :%d` | Dynamic property, not hardcoded |
| Buffer | `### H264 data buffer overrun!` | Fixed size, will overflow on large frames |
| Memory | `### Failed to allocate memory for video frame` | Dynamic allocation can fail |
| Bandwidth | `Not Enough Bandwidth`, `Bandwidth Limit Exceeded` | Runtime configured |

**Practical Limits (not programmatic):**
- USB 2.0: ~280 Mbps practical throughput
- RAM: ~128MB total, limits frame buffer size
- 4K@60: Marginal (bandwidth limit)
- 8K: Will fail (memory allocation)
- 120fps: Doubles bandwidth requirement

### Memory Footprint (~1.1MB Audio)

| Component | Size |
|-----------|------|
| DMSDP Framework | 500KB |
| AAC Decoder | 336KB |
| Buffers | 50KB |
| System | 200KB |

---

## RTP Transport Functions

**[Firmware] [Protocol]**

### DMSDP RTP API

```cpp
void DMSDPRtpSendPCMPackFillPayload(rtp_packet_t*, unsigned char*, unsigned int);
void DMSDPPCMPostData(unsigned char*, unsigned int stream_id, unsigned int timestamp);
void DMSDPPCMProcessPacket(unsigned char*, unsigned int);
void DMSDPDataSessionRtpSenderCallback(rtp_session_t*, rtp_event_t*);
void DMSDPDataSessionRtpSenderEventsHandler(rtp_session_t*, rtp_events_t);
void DMSDPDataSessionInitRtpRecevier(...);

// Stream callbacks
void DMSDPStreamSetCallback(stream_id_t, stream_callback_t);
void DMSDPServiceProviderStreamSetCallback(provider_t*, stream_callback_t);
void DMSDPServiceSessionSetStreamCallback(session_t*, stream_callback_t);
```

---

## Authentication (libauthagent.so)

```cpp
// Trust management for paired devices (44KB library)
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

---

## iAP2 Protocol Engines (ARMiPhoneIAP2)

**[CarPlay]** 9 iAP2 engines run in parallel with AirPlay, parsing structured metadata (Identify, MediaPlayer, CallState, Communication, RouteGuidance, Location, Power, VehicelStat, WiFiConfig).

See `binary_analysis/key_binaries.md` for complete engine class hierarchy, field catalogs, message dispatch table, and data relay architecture.

---

## Capability Detection

```cpp
CheckMultiAudioBusCap();      // Multi-bus audio support
CheckMultiAudioBusVersion();  // Audio bus version
CheckMultiAudioBusPolicy();   // Audio routing policy
IsSupportBGRecord();          // "Hey Siri"/"OK Google" support
IsUsePhoneMic();              // External/app microphone check
```

---

## Audio Format Support

| Sample Rate | Use Case |
|-------------|----------|
| 8kHz | Phone calls (narrow-band), voice |
| 16kHz | Voice/Siri (wide-band), phone calls |
| 44.1kHz | Music (CD quality) |
| 48kHz | Professional audio |

**WebRTC Processing (Binary Verified at 0x2dfa2):**
- WebRTC AECM only accepts **8000 Hz or 16000 Hz** for microphone input
- Other sample rates will cause initialization failure
- Sample rate is configured dynamically based on audio context

**Conversion Capabilities:**
- Sample rate: 8↔16↔44.1↔48 kHz
- Channels: Mono ↔ Stereo
- Bit depth: 16-bit PCM primary
- Buffer: Push/Pop with conversion ratios

---

## Processing Boundaries

### What Firmware Does:
1. Protocol translation: IAP2/NCM USB ↔ CPC200-CCPA protocol
2. Basic AAC decoding: Compressed streams → PCM
3. Format conversion: Sample rate, channels, bit depth
4. Hardware configuration: Codec init, mixer settings
5. Audio routing: Stream classification/direction
6. Buffer management: Simple I/O buffering

### What Firmware DOES Do (WebRTC Processing):
- **WebRTC AGC** (Automatic Gain Control) - applied to microphone audio
- **WebRTC AECM** (Acoustic Echo Cancellation, Mobile) - 8kHz/16kHz only
- **WebRTC NS** (Noise Suppression) - configurable via VoiceQuality setting

### What Firmware Does NOT Do (Host App Responsibility):
- Automotive-specific optimizations
- Complex multi-stream mixing
- Advanced real-time DSP beyond WebRTC
- Multi-channel processing beyond basic routing

---

## Testing & Debug

### DTMF Testing

```bash
tinycap -- -c 1 -r 16000 -b 16 -t 4 > /tmp/dtmf.pcm
result=`dtmf_decode /tmp/dtmf.pcm | grep 14809414327 | wc -l`
[ $result -eq 1 ] && echo "mic test success!!" || exit 1
```

### Debug Functions

```cpp
AudioService::getCurAudioType(int*, int*);
AudioService::OnAudioFocusChange(int);
AudioService::OnMediaStatusChange(MEDIA_STATE);
```

### Debug Mode (CMD_DEBUG_TEST 0x88)

| Value | Action |
|-------|--------|
| 1 | Open `/tmp/userspace.log`, run `/script/open_log.sh` |
| 2 | Read log file, send contents to host |
| 3 | Enable persistent debug mode flag |

---

## Bluetooth/WiFi Hardware (RTL8822CS)

**[Hardware]** *Verified via adapter TTY log capture (Jan 2026)*

### Realtek RTL8822CS Module

| Property | Value |
|----------|-------|
| Device ID | `0xc822` |
| HCI Revision | `0x000c` |
| HCI Version | `0x08` |
| LMP Subversion | `0x8822` |
| IC Type | RTL8822CS (combo WiFi+BT) |

### Firmware Loading

| File | Size | Purpose |
|------|------|---------|
| `/lib/firmware/rtlbt/rtl8822cs_fw` | 60980 bytes | Bluetooth firmware |
| `/lib/firmware/rtlbt/rtl8822cs_config` | 41 bytes | Bluetooth config |

**Firmware Version:** `0x05a8cbcd`
- Patch number: 3
- Patch length: `0x8a6c` (35436 bytes)
- Start offset: `0x00006380`
- SVN version: 1940234490
- Coexistence: `BTCOEX_20210106-2020`

### Bluetooth/WiFi Coexistence

The adapter uses Realtek's `rtk_btcoex` driver for Bluetooth/WiFi coexistence management.

**Profile Bitmap Values:**

| Bitmap | Profile |
|--------|---------|
| 0x01 | Unknown profile 0 |
| 0x02 | Unknown profile 1 |
| 0x04 | Unknown profile 2 |
| 0x08 | HFP (Hands-Free Profile) |
| 0x10 | Unknown profile 4 |
| 0x20 | Unknown profile 5 |
| 0x40 | Unknown profile 6 |
| 0x80 | Unknown profile 7 |

**Coex Events (from kernel log):**

| Event | Meaning |
|-------|---------|
| `hci accept conn req` | Incoming Bluetooth connection |
| `connected, handle XXXX` | Connection established |
| `Page success` | Outgoing page completed |
| `link key notify` | Link key exchanged |
| `io capability request` | Pairing negotiation |
| `l2cap conn req, PSM 0xXXXX` | L2CAP channel request |
| `pan idle->busy` / `pan busy->idle` | PAN profile state |

**Vendor Command:** `opcode 0xfc19` - Profile info notification to firmware

### L2CAP Protocol Service Multiplexers (PSM)

| PSM | Service |
|-----|---------|
| 0x0001 | SDP (Service Discovery Protocol) |
| 0x0003 | RFCOMM (Serial port emulation) |

### PBAP Support (Binary Strings Feb 2026)

`bluetoothDaemon` registers both PBAP roles via SDP:

| Role | Purpose |
|------|---------|
| **PCE** (Phone Book Client Equipment) | Pulls phonebook/call history FROM the phone |
| **PSE** (Phone Book Server Equipment) | Serves phonebook TO the phone |

SDP record strings: `Phone Book Access - PCE`, `Phone Book Access - PSE`

This enables full phonebook synchronization: contacts, call history (missed/received/dialed), and favorites. The adapter acts as both client (pulling from the iPhone) and server (potentially serving cached data).

**Note:** PBAP data flow to the host app via USB is unconfirmed — this may be used internally by the adapter for caller ID enrichment during HFP calls.

### HFP AT Command Exchange (Runtime Verified Jan 2026)

*Full handshake captured during Pixel 10 Android Auto connection*

| Direction | Command | Description |
|-----------|---------|-------------|
| Host→Phone | `AT+BRSF=63` | Supported features bitmask (adapter) |
| Phone→Host | `+BRSF: 879` | Supported features bitmask (phone) |
| Host→Phone | `AT+CIND=?` | Query indicator descriptions |
| Phone→Host | `+CIND: ("call",(0,1)),("callsetup",(0-3)),...` | Indicator support |
| Host→Phone | `AT+CMER=3,0,0,1` | Enable event reporting |
| Host→Phone | `AT+CLIP=1` | Enable caller ID |
| Host→Phone | `AT+CCWA=1` | Enable call waiting |
| Host→Phone | `AT+CHLD=?` | Query call hold modes |
| Phone→Host | `+CHLD: (0,1,2,3)` | Supported hold modes |
| Host→Phone | `AT+CIND?` | Query current indicators |
| Phone→Host | `+CIND: 0,0,0,0,0,4,0` | Current indicator values |
| Phone→Host | `+BSIR: 0` / `+BSIR: 1` | In-band ring setting |

**BRSF Bitmap (Adapter = 63):**
- Bit 0: EC/NR function
- Bit 1: Call waiting / three-way calling
- Bit 2: CLI presentation
- Bit 3: Voice recognition
- Bit 4: Remote volume control
- Bit 5: Enhanced call status

**BRSF Bitmap (Pixel 10 = 879):**
- Includes all adapter features plus extended codecs

---

## Bluetooth Link Key Storage

**[Firmware]** *Directory structure verified via adapter TTY log (Jan 2026)*

### Directory Structure

```
/tmp/bluetooth/
└── [LOCAL_ADDR]/              # e.g., 48:8F:4C:E0:AC:2B
    ├── linkkeys               # Paired device link keys
    ├── names                  # Cached device names
    ├── features               # Device feature flags
    ├── manufacturers          # Device manufacturer info
    ├── lastused               # Last used timestamps
    ├── classes                # Device class codes
    ├── config                 # Adapter configuration
    └── services               # Service definitions (at root level)
```

### File Formats

| File | Format | Description |
|------|--------|-------------|
| `linkkeys` | Text | Paired device keys (see below) |
| `names` | Text | Cached friendly names by MAC |
| `features` | Text | LMP feature bitmask per device |
| `manufacturers` | Text | Manufacturer ID per device |
| `lastused` | Text | Unix timestamp of last connection |
| `classes` | Text | CoD (Class of Device) per device |
| `config` | Text | Adapter configuration |

**Link Key Format:**
```
MAC_ADDRESS LINK_KEY_HEX KEY_TYPE PIN_TYPE
```
Example: `B0:D5:FB:A3:7E:AA 68851F93529776F17B9A155512568EA5 5 -1`

**Key Type Values:**
| Value | Type |
|-------|------|
| 0 | Combination key |
| 1 | Local unit key |
| 2 | Remote unit key |
| 3 | Debug combination key |
| 4 | Unauthenticated P-192 |
| 5 | Authenticated P-192 |
| 6 | Changed combination key |
| 7 | Unauthenticated P-256 |
| 8 | Authenticated P-256 |

**Config File Format:**
```
class 0x000408
onmode discoverable
mode off
```

---

## DeletedDevList JSON Format

*Verified via adapter TTY log (Jan 2026)*

The `DeletedDevList` is a JSON array tracking devices scheduled for removal from pairing:

```json
[{"id": "B0:D5:FB:A3:7E:AA", "name": ""}]
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Bluetooth MAC address |
| `name` | string | Device name (may be empty) |

**Usage:** Referenced by `RiddleBluetoothService_Interface_Control` during device removal operations. Persisted in configuration to handle removals across reboots.

---

## LED Status Daemon (colorLightDaemon)

**[Firmware]** *Runtime states verified via TTY log (Jan 2026)*

The CPC200-CCPA has two LEDs: **Red** and **Blue** (not RGB).

| Status String | LED | Meaning |
|---------------|-----|---------|
| `StartUp` | Red | Adapter initializing / waiting for host app |
| `LinkSuccess` | Blue | Phone connected and streaming |
| `WifiConnected` | Blue | WiFi connection established |
| `BtConnecting` | Blue (blink) | Bluetooth pairing in progress |
| (disconnected) | Red | No device connected / idle state |

**Observed Behavior:**
- **Blue LED**: Indicates active connection (WiFi connected, device streaming)
- **Red LED**: Indicates disconnected/idle state

Status changes logged as:
```
colorLightDaemon[colorLightDaemon]: Change status to "STATUS" or switch songs!!
```

---

## References

- Source: `carlink_native/documents/reference/Firmware/firmware_audio.md`
- Source: `carlink_native/documents/reference/Firmware/firmware_microphone.md`
- Binary analysis: Ghidra 12.0, radare2
- Firmware version: 2025.02.25.1521CAY
- TTY capture: adapter-ttylog_26JAN22_03-52-39.log
