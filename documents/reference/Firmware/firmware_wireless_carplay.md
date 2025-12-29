# Wireless CarPlay Protocol Specification

## Overview

This document describes the wireless CarPlay protocol used between iPhones and the CPC200-CCPA adapter. Unlike the USB protocol (0x55AA55AA framing), wireless CarPlay uses **RTSP over TCP** with **HomeKit Pairing v2** authentication.

**Source:** Packet captures from `/Users/zeno/Downloads/GM_research/cpc200_research/captures/`

**Last Updated:** 2025-12-29

---

## Protocol Stack

```
┌─────────────────────────────────────────────────────────┐
│                    CarPlay Session                       │
│           (H.264 Video, AAC Audio, Touch)               │
├─────────────────────────────────────────────────────────┤
│              ChaCha20-Poly1305 Encryption               │
├─────────────────────────────────────────────────────────┤
│                   RTSP over TCP                          │
│                    (Port 5000)                           │
├─────────────────────────────────────────────────────────┤
│               HomeKit Pairing v2 (SRP-6a)               │
├─────────────────────────────────────────────────────────┤
│                   WiFi (IPv4)                            │
│              192.168.43.0/24 subnet                      │
└─────────────────────────────────────────────────────────┘
```

---

## Transport Details

| Property | Value |
|----------|-------|
| Protocol | RTSP (Real Time Streaming Protocol) |
| Port | TCP 5000 |
| Network | IPv4 WiFi (adapter acts as AP) |
| Subnet | 192.168.43.0/24 |
| Adapter IP | 192.168.43.1 |
| iPhone IP | 192.168.43.100-200 (DHCP) |
| mDNS Service | `_airplay._tcp` |

---

## Authentication Methods

### Wireless CarPlay: HomeKit Pairing v2

| Step | Endpoint | Purpose |
|------|----------|---------|
| 1 | `/pair-setup` | Initial pairing (SRP-6a exchange) |
| 2 | `/pair-verify` | Reconnection with stored credentials |
| 3 | (encrypted) | Session data after verification |

### USB CarPlay: MFi Certificate

| Step | Endpoint | Purpose |
|------|----------|---------|
| 1 | `/auth-setup` | MFi certificate authentication |
| 2 | `SETUP` | Session initialization |
| 3 | `RECORD` | Start streaming |

---

## Pairing Protocol: pair-setup (First-Time)

### Overview

First-time pairing uses **SRP-6a** (Secure Remote Password) with a PIN code displayed on the adapter screen.

**Crypto Parameters:**
- Prime: 3072-bit
- Hash: SHA-512
- Key derivation: HKDF

### Step 1: Method Selection (CSeq: 0)

**Request (iPhone → Adapter):**
```http
POST /pair-setup RTSP/1.0
X-Apple-AbsoluteTime: 788587167
X-Apple-HKP: 0
X-Apple-Client-Name: Luis
Content-Length: 6
Content-Type: application/x-apple-binary-plist
CSeq: 0
User-Agent: AirPlay/935.3.1

[6 bytes - bplist method selector]
```

**Response (Adapter → iPhone):**
```http
RTSP/1.0 200 OK
Content-Length: 409
Content-Type: application/octet-stream
Server: AirTunes/320.17
CSeq: 0

[409 bytes - SRP salt (16 bytes) + server public key B (384 bytes)]
```

### Step 2: Client Proof (CSeq: 1)

**Request (iPhone → Adapter):**
```http
POST /pair-setup RTSP/1.0
X-Apple-HKP: 0
Content-Length: 457
Content-Type: application/x-apple-binary-plist
CSeq: 1

[457 bytes - bplist with client public key A + proof M1]
```

**Response (Adapter → iPhone):**
```http
RTSP/1.0 200 OK
Content-Length: 69
Content-Type: application/octet-stream
CSeq: 1

[69 bytes - Server proof M2]
```

### Step 3: Key Exchange (CSeq: 2)

**Request (iPhone → Adapter):**
```http
POST /pair-setup RTSP/1.0
X-Apple-HKP: 0
Content-Length: 159
Content-Type: application/x-apple-binary-plist
CSeq: 2

[159 bytes - Encrypted Ed25519 public key + signature]
```

**Response (Adapter → iPhone):**
```http
RTSP/1.0 200 OK
Content-Length: 159
Content-Type: application/octet-stream
CSeq: 2

[159 bytes - Encrypted accessory Ed25519 key + signature]
```

---

## Reconnection Protocol: pair-verify

### Overview

After initial pairing, subsequent connections use the stored Ed25519 keys for quick verification.

### Step 1: Verify Start (CSeq: 0)

**Request (iPhone → Adapter):**
```http
POST /pair-verify RTSP/1.0
X-Apple-AbsoluteTime: 788538962
X-Apple-HKP: 2
X-Apple-Client-Name: Luis
X-Apple-PD: 1
Content-Length: 37
Content-Type: application/octet-stream
CSeq: 0
User-Agent: AirPlay/935.3.1

[37 bytes binary - curve25519 public key]
```

**Binary Format:**
```
Offset  Data
0x00    [1 byte]   Message type (0x01)
0x01    [4 bytes]  Payload length
0x05    [32 bytes] Curve25519 public key
```

**Response (Adapter → iPhone):**
```http
RTSP/1.0 200 OK
Content-Length: 159
Content-Type: application/octet-stream
Server: AirTunes/320.17
CSeq: 0

[159 bytes binary - server response]
```

**Response Binary Format:**
```
Offset  Data
0x00    [1 byte]   Message type
0x01    [32 bytes] Server public key
0x21    [64 bytes] Encrypted proof
0x61    [32 bytes] Auth tag
0x81+   Additional encrypted data
```

### Step 2: Verify Finish (CSeq: 1)

**Request (iPhone → Adapter):**
```http
POST /pair-verify RTSP/1.0
X-Apple-AbsoluteTime: 788538962
X-Apple-HKP: 2
X-Apple-Client-Name: Luis
X-Apple-PD: 1
Content-Length: 125
Content-Type: application/octet-stream
CSeq: 1
User-Agent: AirPlay/935.3.1

[125 bytes binary - verification data]
```

**Response (Adapter → iPhone):**
```http
RTSP/1.0 200 OK
Content-Length: 3
Content-Type: application/octet-stream
Server: AirTunes/320.17
CSeq: 1

[3 bytes - 0x06 0x01 0x04 = verification success]
```

---

## Header Reference

### X-Apple-HKP (HomeKit Pairing)

| Value | Meaning |
|-------|---------|
| 0 | Initial setup (pair-setup) |
| 2 | HomeKit Pairing v2 (pair-verify) |

### X-Apple-PD (Pairing Data)

| Value | Meaning |
|-------|---------|
| 0 | Not paired |
| 1 | Already paired, using stored credentials |

### X-Apple-AbsoluteTime

Apple epoch timestamp (seconds since 2001-01-01 00:00:00 UTC).

### Server/User-Agent

| Header | Typical Value |
|--------|---------------|
| Server | AirTunes/320.17 |
| User-Agent | AirPlay/935.3.1 |

---

## Encrypted Session

After pair-verify completes (3-byte success response), all subsequent data is encrypted:

### Encryption Scheme

| Property | Value |
|----------|-------|
| Algorithm | ChaCha20-Poly1305 |
| Key Derivation | HKDF from shared secret |
| Nonce | Incrementing counter |

### Encrypted Packet Format

```
┌────────────────────────────────────────────────────┐
│              Encrypted Wrapper                      │
│         (ChaCha20-Poly1305 AEAD)                   │
├────────────────────────────────────────────────────┤
│              Protocol Header (16 bytes)            │
│  Magic: 0x55aa55aa                                 │
│  Length: payload size                              │
│  Type: message type                                │
│  Check: ~type                                      │
├────────────────────────────────────────────────────┤
│              Protocol Payload                      │
│  VIDEO_DATA: 20-byte header + H.264                │
│  AUDIO_DATA: 12-byte header + PCM/AAC              │
│  TOUCH: 16 bytes (action, x, y, flags)             │
└────────────────────────────────────────────────────┘
```

**Note:** After encryption, packets are typically 1042 bytes (MTU-optimized).

---

## mDNS Service Advertisement

The adapter advertises itself via mDNS/Bonjour:

```
Service:  _airplay._tcp
Port:     5000
Name:     Magic-Car-Link-1.00

TXT Records:
  features = 0x44440B80,0x61
  model = Magic-Car-Link-1.00
  pk = <Ed25519 public key>
  srcvers = 320.17
  vv = 2
```

---

## Crypto Stack Requirements

To implement wireless CarPlay reception:

### Required Algorithms

| Algorithm | Purpose |
|-----------|---------|
| SRP-6a (3072-bit, SHA-512) | Initial pairing |
| Ed25519 | Signing |
| X25519 (Curve25519) | Key exchange |
| ChaCha20-Poly1305 | Session encryption |
| HKDF | Key derivation |
| SHA-512 | Hashing |

### Key Storage

**Location on Adapter:**
```
/Library/Keychains/default.keychain   # Apple binary plist
```

**Contents:**
- `kSecAttrLabel`: "AirPlay Pairing Identity: <UUID>"
- `kSecValueData`: Ed25519 keypair + paired device credentials

### HKDF Info Strings

```
Pair-Verify-ECDH-Salt
Pair-Verify-ECDH-Info
```

---

## USB CarPlay Protocol (Alternative)

USB CarPlay uses a different authentication method:

### Transport

- IPv6 over USB-NCM (Network Control Model)
- TCP port 5000
- Link-local addresses (fe80::/10)

### Authentication: /auth-setup

```http
POST /auth-setup RTSP/1.0
Content-Length: 33
Content-Type: application/octet-stream
CSeq: 0
User-Agent: AirPlay/935.3.1

[33 bytes - authentication request]
```

**Response:**
```http
RTSP/1.0 200 OK
Content-Length: 1113
Content-Type: application/octet-stream
Server: AirTunes/320.17
CSeq: 0

[1113 bytes - MFi certificate chain]
```

**Certificate Chain:**
```
Apple Computer Certificate Authority
  └── Apple iPod Accessories Certificate Authority
        └── IPA_3333AA071227AA02AA0011AA003  (MFi chip ID)
```

### Session Flow

1. `POST /auth-setup` - MFi certificate authentication
2. `SETUP rtsp://<ip>/<session_id>` - Session initialization
3. `GET /info` - Device capabilities (18KB response)
4. `RECORD rtsp://<ip>/<session_id>` - Start streaming

---

## Comparison: Wireless vs USB

| Aspect | Wireless CarPlay | USB CarPlay |
|--------|------------------|-------------|
| **Transport** | IPv4 WiFi | IPv6 USB-NCM |
| **Port** | TCP 5000 | TCP 5000 |
| **Auth Method** | HomeKit Pairing v2 | MFi Certificate |
| **Auth Endpoint** | `/pair-setup`, `/pair-verify` | `/auth-setup` |
| **Pairing Storage** | Ed25519 keys in keychain | Certificate trust |
| **Session Setup** | Implicit after pairing | Explicit `SETUP` + `RECORD` |
| **Encryption** | ChaCha20-Poly1305 | Standard AirPlay |
| **Headers** | X-Apple-HKP, X-Apple-PD | X-Apple-ProtocolVersion |
| **First Connect** | PIN code on screen | Trust dialog on iPhone |

---

## RTSP Endpoints Summary

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/pair-setup` | POST | Initial pairing (SRP-6a) |
| `/pair-verify` | POST | Verify existing pairing |
| `/auth-setup` | POST | MFi certificate auth (USB only) |
| `/fp-setup` | POST | FairPlay setup |
| `/info` | GET | Device information |
| `/stream` | POST | Start media stream |
| `/feedback` | POST | Playback feedback |
| `/command` | POST | Control commands |
| `/audio` | POST | Audio stream setup |
| `/video` | POST | Video stream setup |

---

## Implementation Notes

### To Force Fresh pair-setup

The iPhone "Forget This Car" only clears iPhone-side credentials. The adapter still has the pairing stored, so it will always do `pair-verify`.

To capture/trigger initial `pair-setup`:
```bash
# On adapter - backup and remove pairing data
mv /Library/Keychains/default.keychain /Library/Keychains/default.keychain.bak

# Then forget on iPhone AND reconnect
# This forces full pair-setup sequence
```

### Capture Scripts

Located in `cpc200_research/capture_scripts/`:

| Script | Purpose |
|--------|---------|
| `capture_wireless_carplay.sh` | Passive WiFi capture (recommended) |
| `capture_carplay_handshake.sh` | Basic port 5000 capture |
| `download_tools.sh` | Download tcpdump for adapter |

### Capture File Locations

```
# Wireless CarPlay (pair-setup captured)
captures/wireless_20251228_115900/
├── port5000.pcap          # 228KB - Full pair-setup + session
├── port5000_ascii.txt     # 279KB - Readable protocol dump
└── mdns.pcap              # 18KB - Service discovery

# USB CarPlay (auth-setup captured)
captures/usb_passive_20251228_122809/
├── port5000.pcap          # 65KB - MFi auth + SETUP + RECORD
├── port5000_ascii.txt     # 93KB - Readable protocol dump
└── monitor.txt            # Connection timeline
```

---

## References

- `CARPLAY_PROTOCOL_CAPTURE.md` - Raw capture analysis
- `REVERSE_ENGINEERING_NOTES.md` - Adapter binary analysis
- `PI_STANDALONE_IMPLEMENTATION.md` - Protocol format reference
- RFC 5054 - SRP-6a specification
- HomeKit Accessory Protocol specification
