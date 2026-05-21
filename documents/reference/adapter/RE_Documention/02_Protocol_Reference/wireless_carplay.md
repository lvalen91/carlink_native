# CPC200-CCPA Wireless CarPlay Protocol

**Status:** VERIFIED via capture analysis
**Consolidated from:** GM_research, carlink_native
**Last Updated:** 2026-05-21 — re-verified against live capture `carplay-20260521-101016` (full fresh wireless CarPlay pairing: BT HCI + WiFi pcap + firmware ttyLog).

> For the consolidated end-to-end ordered handshake (BT iAP2 → MFi auth → WiFi handover →
> AirPlay), see `carplay_handshake.md`.

---

## Protocol Overview

Wireless CarPlay uses a multi-stage connection process:

```
1. Bluetooth Pairing (BR/EDR)
   ↓
1b. iAP2 link bring-up (ff55 probe / ff5a link-sync) + identification (0x1D00/0x1D01/0x1D02)
   ↓
1c. MFi authentication over iAP2
    (0xAA00 ReqAuthCert → 0xAA01 cert → 0xAA02 challenge → 0xAA03 RSA sig → 0xAA05 AuthSuccess)
   ↓
2. WiFi Credentials Exchange (iAP2 0x5702/0x5703 over BT Classic RFCOMM)
   ↓
3. WiFi association — iPhone joins the adapter's WPA2 SoftAP; DHCP lease assigned
   ↓
4. RTSP Session (Port 5000)
   ↓
5. HomeKit Pairing v2 (SRP-6a)
   ↓
6. Encrypted Media Streams
```

---

## Network Configuration

### WiFi Hotspot

| Parameter | Value |
|-----------|-------|
| **Subnet** | 192.168.43.0/24 |
| **Adapter IP** | 192.168.43.1 |
| **Phone IP** | 192.168.43.x (DHCP) |
| **Band** | 5GHz 802.11ac (configurable) |
| **Channel** | 36-165 (configurable) |

### Key Ports

| Port | Protocol | Purpose |
|------|----------|---------|
| 5000 | TCP | RTSP / AirPlay control (adapter advertises `_airplay._tcp` on this port) |

**Note:** Video and audio streams use ephemeral high TCP ports negotiated per-session in the
AirPlay SETUP response (eventPort 59615, timingPort 43140; stream sockets observed `:49376` /
`:49346`). There is no fixed 7000/7001.

---

## Bluetooth SDP Record

```
Service: Wireless iAP
UUID: 00000000-deca-fade-deca-deafdecacafe   (unverified by capture)
RFCOMM Channel: 1                            (unverified by capture)
```

**Verified (capture `carplay-20260521-101016`):** the accepted RFCOMM service is the iAP2
accessory profile, bound to `/dev/rfcomm0`.

---

## HomeKit Pairing v2

HomeKit pair-setup uses SRP — **CONFIRMED** by capture (`Control pair-setup HK` ×3,
`X-Apple-HKP: 0` on the AirPlay/RTSP channel).

### Cryptographic Parameters

| Algorithm | Parameters |
|-----------|------------|
| **Pairing** | SRP-6a — **3072-bit prime, SHA-512, 16-byte salt unverified by this capture** (HomeKit-spec defaults; not extracted from the wire) |
| **Transport** | ChaCha20-Poly1305 |
| **Key Exchange** | X25519 (Curve25519) — confirmed: pair-verify uses Curve25519 |
| **Symmetric** | AES-256-GCM |

### SRP-6a Flow

> **Generic HomeKit-spec description.** Only the round-trip count (3× `pair-setup`) is
> capture-confirmed; the individual M-message contents below are NOT extracted from this capture.

```
1. Host sends M1 (client public key + username)
2. Adapter sends M2 (server public key + salt)
3. Host sends M3 (proof)
4. Adapter sends M4 (verification)
5. Shared session key derived
```

### Key Derivation

> **Generic HomeKit-spec description** — not capture-confirmed.

```
session_key = HKDF-SHA512(
    shared_secret,
    salt: "Pair-Setup-Encrypt-Salt",
    info: "Pair-Setup-Encrypt-Info",
    length: 32
)
```

---

## Pairing Protocol: pair-setup (First-Time)

First-time pairing uses **SRP-6a** (Secure Remote Password). Wireless CarPlay pair-setup is
performed transparently over the AirPlay/RTSP channel — **no PIN is displayed** (the adapter has
no screen); user trust is handled iPhone-side via the "Use This Car" prompt.

> **Verified by capture (`carplay-20260521-101016`):** pair-setup carries `X-Apple-HKP: 0`;
> pair-verify carries `X-Apple-HKP: 2` + `X-Apple-PD: 1`; the sequence is exactly **3× pair-setup
> then 2× pair-verify**; pair-verify uses Curve25519. The specific `Content-Length` byte counts
> shown below (409, 457, 69, 159, 37, 125, 3) are **illustrative / unverified** by this capture.
> The iPhone `User-Agent` is `AirPlay/950.7.1`; the adapter `Server` is `AirTunes/320.17`.

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
User-Agent: AirPlay/950.7.1

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

After initial pairing, subsequent connections use stored Ed25519 keys for quick verification.

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
User-Agent: AirPlay/950.7.1

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

### Step 2: Verify Finish (CSeq: 1)

**Request (iPhone → Adapter):**
```http
POST /pair-verify RTSP/1.0
X-Apple-HKP: 2
X-Apple-PD: 1
Content-Length: 125
Content-Type: application/octet-stream
CSeq: 1

[125 bytes binary - verification data]
```

**Response (Adapter → iPhone):**
```http
RTSP/1.0 200 OK
Content-Length: 3

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

---

## Encrypted Session

After pair-verify completes (3-byte success response), all subsequent AirPlay data is encrypted:

### AirPlay Media Framing (over WiFi)

```
┌────────────────────────────────────────────────────┐
│              Encrypted Wrapper                      │
│         (ChaCha20-Poly1305 AEAD)                    │
├────────────────────────────────────────────────────┤
│              AirPlay media payload                  │
│  Video: H.264 elementary stream                     │
│  Audio: AAC-LC / AAC-ELD / PCM (per WifiAudioFormat)│
│  HID / control: AirPlay screen + input events       │
└────────────────────────────────────────────────────┘
```

**Note:** After encryption, packets are typically MTU-optimized (~1042 bytes). Stream sockets
were observed on ephemeral ports `:49376` (video) and `:49346` (audio).

> **Correction:** the `0x55AA55AA` 16-byte header is **host↔adapter USB framing**, not
> AirPlay-over-WiFi framing. It does **not** appear in the WiFi AirPlay stream. For the
> `0x55AA55AA` USB protocol header and the VIDEO/AUDIO/TOUCH USB payload formats, see
> `usb_protocol.md`.

---

## Wireless vs USB Comparison

| Aspect | Wireless CarPlay | USB CarPlay |
|--------|------------------|-------------|
| **Transport** | IPv4 WiFi | IPv6 USB-NCM |
| **Port** | TCP 5000 | TCP 5000 |
| **Auth Method** | HomeKit Pairing v2 + iAP2 MFi auth | MFi Certificate |
| **Auth Endpoint** | `/pair-setup`, `/pair-verify` (HomeKit) + iAP2 `0xAA0x` MFi auth over BT | MFi cert exchange |
| **Pairing Storage** | Ed25519 keys in keychain | Certificate trust |
| **Session Setup** | Implicit after pairing | Explicit `SETUP` + `RECORD` |
| **Encryption** | ChaCha20-Poly1305 | Standard AirPlay |
| **First Connect** | iPhone "Use This Car" prompt | Trust dialog on iPhone |

---

## RTSP Endpoints Summary

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/pair-setup` | POST | Initial pairing (SRP-6a) — **capture-confirmed** |
| `/pair-verify` | POST | Verify existing pairing — **capture-confirmed** |
| `/info` | GET | Device information |
| `/stream` | POST | Start media stream |
| `/feedback` | POST | Playback feedback |
| `/command` | POST | Control commands |
| `/audio` | POST | Audio stream setup |
| `/video` | POST | Video stream setup |

**Note:** No FairPlay (`/fp-setup`) or `/auth-setup` endpoint exists on this firmware —
confirmed by capture and by binary RE of `AppleCarPlay`. The only capture-confirmed RTSP
endpoints are `/pair-setup` and `/pair-verify`; the others are generic AirPlay and unverified
here.

---

## Implementation Notes

### To Force Fresh pair-setup

The iPhone "Forget This Car" only clears iPhone-side credentials. The adapter still has the pairing stored.

To trigger initial `pair-setup`:
```bash
# On adapter - backup and remove pairing data
mv /Library/Keychains/default.keychain /Library/Keychains/default.keychain.bak

# Then forget on iPhone AND reconnect
# This forces full pair-setup sequence
```

---

## RTSP Session

### Discovery

The adapter advertises `_airplay._tcp` (on port 5000). The iPhone advertises
`_carplay-ctrl._tcp`; the adapter browses for it and connects out to the iPhone.

```
adapter advertises :  _airplay._tcp.local.        (port 5000)
iPhone advertises  :  _carplay-ctrl._tcp.local.   (e.g. Luis._carplay-ctrl._tcp, :49372)
```

### RTSP Commands

| Method | Purpose |
|--------|---------|
| OPTIONS | Capabilities query |
| ANNOUNCE | Session setup |
| SETUP | Stream configuration |
| RECORD | Start streaming |
| TEARDOWN | End session |
| SET_PARAMETER | Runtime config |
| GET_PARAMETER | Query state |

### Typical Session

```
C→S: OPTIONS rtsp://192.168.43.1:5000 RTSP/1.0
S→C: RTSP/1.0 200 OK
     Public: ANNOUNCE, SETUP, RECORD, ...

C→S: ANNOUNCE rtsp://192.168.43.1:5000 RTSP/1.0
     Content-Type: application/sdp
     [SDP payload]

S→C: RTSP/1.0 200 OK

C→S: SETUP rtsp://192.168.43.1:5000/audio RTSP/1.0
     Transport: ...

...
```

---

## AirPlay Version

The adapter reports (AirPlay/AirTunes):
```
AirPlay/320.17
```

The iPhone side reports (capture `carplay-20260521-101016`):
```
AirPlay/950.7.1
```

---

## mDNS Service Configuration

### Adapter Advertisement — `_airplay._tcp`

The adapter advertises **`_airplay._tcp` on TCP port 5000** (NOT `_carplay._tcp`).

```
_airplay._tcp.local.
  Port: 5000
  TXT Records:
    deviceid=00:E0:4C:91:B0:DF
    features=0x44440B80,0x61
    srcvers=320.17
```

### iPhone Advertisement — `_carplay-ctrl._tcp`

The iPhone advertises `_carplay-ctrl._tcp`; the adapter browses for it and connects out.

```
_carplay-ctrl._tcp.local.
  TXT Records:
    id=<iPhone BT MAC>
    srcvers=950.7.1
```

### Adapter `_airplay._tcp` TXT Records (capture-verified)

| Key | Value | Description |
|-----|-------|-------------|
| deviceid | 00:E0:4C:91:B0:DF | Adapter device ID |
| features | 0x44440B80,0x61 | AirPlay capability bits |
| srcvers | 320.17 | AirPlay version |

---

## iAP2 + MFi Handshake (over Bluetooth RFCOMM)

Before WiFi exists, the entire CarPlay capability negotiation and MFi authentication run over
the iAP2 accessory profile on Bluetooth RFCOMM (`/dev/rfcomm0`). Live-captured order
(`carplay-20260521-101016`):

| Step | Direction | iAP2 message | Purpose |
|------|-----------|--------------|---------|
| 1 | — | `ff 55` probe → `ff 5a` link-sync | iAP2 link bring-up (linkVersion 1, maxOutstandingPackets 127) |
| 2 | iPhone → adapter | `0x1D00 StartIdentify` | begin identification |
| 3 | adapter → iPhone | `0x1D01 IdentificationInformation` | advertises components incl. `wirelessCarPlayTransportComponent` / `transportSupportsCarPlay` |
| 4 | iPhone → adapter | `0x1D02 IdentifyAccept` | identification accepted → firmware `OnCarPlayPhase 100` |
| 5 | iPhone → adapter | `0xAA00 ReqAuthCert` | request MFi accessory certificate |
| 6 | adapter → iPhone | `0xAA01` (945-byte cert) | MFi certificate from real chip cache |
| 7 | iPhone → adapter | `0xAA02 ReqChallenge` (20-byte challenge) | challenge for the MFi chip |
| 8 | adapter → iPhone | `0xAA03` (128-byte RSA signature) | adapter drives `/dev/i2c-1` MFi chip to sign |
| 9 | iPhone → adapter | `0xAA05 AuthSuccess` | MFi authentication accepted |
| 10 | iPhone → adapter | `0x5702 ReqWifiConfig` | request WiFi credentials |
| 11 | adapter → iPhone | `0x5703 WifiConfigInfo` | `ssid`, `passphrase`, security type, channel |
| 12 | iPhone → adapter | `0x4E0A/0x4E0B/0x4E0D/0x4E0E` | language / time / WirelessCarPlayUpdate / TransportNotify |

After step 12 the iPhone joins the adapter's WiFi SoftAP and the BT iAP2 link is torn down
(`kAirPlayCommand_DisableBluetooth`); the AirPlay session then runs entirely over WiFi.

See `device_identification.md` (iAP2 Identification, `0x1D00/0x1D01/0x1D02`) and
`carplay_handshake.md` (full ordered phases A–G) for the complete detail.

---

## Connection State Machine

```
IDLE
 ↓ (BT pairing initiated)
BT_PAIRING
 ↓ (iAP2 link + identification + MFi auth complete)
BT_PAIRED
 ↓ (WiFi credentials received)
WIFI_CONNECTING
 ↓ (WiFi associated)
WIFI_CONNECTED
 ↓ (RTSP session started)
RTSP_CONNECTING
 ↓ (HomeKit pairing complete)
STREAMING
 ↓ (disconnect event)
IDLE
```

---

## USB Encryption (CMD_ENABLE_CRYPT)

For USB transport encryption:

| Property | Value |
|----------|-------|
| **Algorithm** | Firmware binary: AES-128-CBC (`AES_cbc_encrypt`); AutoKit app: AES-128-CFB (`AES/CFB/NoPadding`). See `crypto_stack.md` § CBC vs CFB. |
| **Key** | `SkBRDy3gmrw1ieH0` (hardcoded at `0x6d0d4`; note: `W2EC1X1NbZ58TXtn` is the SessionToken 0xA3 key only) |
| **Payload** | 4-byte seed (must be > 0) |

**Security Note:** All adapters share the same hardcoded AES key. See `../03_Security_Analysis/crypto_stack.md` for the complete two-key system.

---

## Android Auto Specifics

### Connection

| Parameter | Value |
|-----------|-------|
| **Port** | 54321 (WiFi, SSL/TLS) |
| **Transport** | BT RFCOMM → WiFi credentials → TCP SSL |
| **Auth Handshake** | 2037 + 51 bytes |
| **Protocol Version** | 1.7 |

### Differences from CarPlay

| Aspect | CarPlay | Android Auto |
|--------|---------|--------------|
| Volume field | 0.0 - 1.0 | Always 0.0 |
| audio_type | 1, 2, or 3 | Always 1 |
| Nav ducking | Explicit audio_type=2 | Not observed |
| Mode setting | Default enabled | `androidWorkMode: true` required |

### androidWorkMode Issue

Fresh pairing fails unless `androidWorkMode: true` is enabled in BoxSettings.

**Behavior:** Firmware dynamically resets to 0 on disconnect. Host must re-send.

---

## Authentication Files

| Path | Purpose |
|------|---------|
| `/var/lib/lockdown/common.cert` | usbmuxd/lockdown USB-trust records (plist) — **distinct from MFi auth** |
| `/var/lib/lockdown/root_key.pem` | usbmuxd/lockdown RSA private key (2048-bit) — **distinct from MFi auth** |
| `/Library/Keychains/default.keychain` | HomeKit pairing data |
| `/tmp/rfcomm_IAP2` | Bluetooth RFCOMM socket |
| `/tmp/.mfi_auth_lock` | Auth mutex |
| **MFi coprocessor** — `/dev/i2c-1` addr 0x11 (8-bit 0x22) | Real Apple MFi auth IC — cert serial `IPA_3333AA071227AA02AA0011AA003045` |

---

## References

- Source: `GM_research/cpc200_research/CLAUDE.md`
- Source: `carlink_native/documents/reference/Firmware/firmware_wireless_carplay.md`
- RTSP captures from pairing sessions
