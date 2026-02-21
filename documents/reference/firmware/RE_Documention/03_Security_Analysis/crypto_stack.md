# CPC200-CCPA Cryptographic Stack

**Purpose:** Security analysis of cryptographic implementations
**Consolidated from:** GM_research binary analysis, pi-carplay firmware extraction
**Last Updated:** 2026-02-19 (CORRECTED: Protocol encryption uses AES-128-CBC with key SkBRDy3gmrw1ieH0. Added full CMD_ENABLE_CRYPT operational lifecycle: trigger/ack protocol, key/IV derivation formulas, state machine, bypass rules, security assessment)

---

## Overview

The CPC200-CCPA uses a multi-layer cryptographic stack for different purposes:

| Layer | Algorithm | Purpose |
|-------|-----------|---------|
| Pairing | SRP-6a | Initial device authentication |
| Key Exchange | X25519 | Session key derivation |
| Transport | ChaCha20-Poly1305 | Encrypted stream transport |
| Symmetric | AES-256-GCM | General encryption |
| USB Protocol Payload | AES-128-CBC | USB message payload encryption (key: `SkBRDy3gmrw1ieH0`) |
| SessionToken | AES-128-CBC | Session telemetry encryption (key: `W2EC1X1NbZ58TXtn`) |
| Firmware | AES-128-CBC | Firmware image encryption |

---

## Two Distinct Encryption Systems (IMPORTANT)

The CPC200-CCPA uses **two completely separate encryption systems** that should not be confused:

### 1. Firmware Image Encryption (.img files)
| Property | Value |
|----------|-------|
| **Purpose** | Protect firmware update packages |
| **When Used** | Firmware distribution and updates |
| **Algorithm** | AES-128-CBC |
| **Key (A15W)** | `AutoPlay9uPT4n17` |
| **Key Source** | Extracted from `ARMimg_maker` binary |
| **IV** | Same as key (16 bytes) |

### 2. USB Protocol Payload Encryption (CMD_ENABLE_CRYPT)
| Property | Value |
|----------|-------|
| **Purpose** | Encrypt USB message payloads at protocol level |
| **When Used** | After CMD_ENABLE_CRYPT (type 0xF0) exchange |
| **Algorithm** | AES-128-CBC |
| **Key** | `SkBRDy3gmrw1ieH0` (at `0x6d0d4` in unpacked binary) |
| **Key Source** | Hardcoded in `ARMadb-driver` (invisible in UPX-packed live binary) |
| **Key Derivation** | XOR-rotated with session seed: `(i + payloadSize) & 0x8000000F` |
| **IV** | Generated at runtime |
| **Exempt Types** | 0x06 (Video), 0x07 (Audio), 0x2A (Dashboard), 0x2C (AltVideo) — always cleartext for performance |
| **Magic Marker** | `0x55BB55BB` marks encrypted payloads (vs `0x55AA55AA` for cleartext) |
| **Toggle** | Type 0xF0 zero-payload ack from adapter |
| **HW Accel** | `/dev/hwaes` kernel module (misc device 10:0, confirmed on live firmware) |

**CORRECTION (Feb 2026):** Previous documentation listed AES-128-CTR with key `W2EC1X1NbZ58TXtn`. Binary analysis reveals the protocol-level encryption actually uses **AES-128-CBC** with a **different key** `SkBRDy3gmrw1ieH0`. The `W2EC1X1NbZ58TXtn` key is used only for SessionToken (type 0xA3) encryption.

### 2b. SessionToken Encryption (Type 163 / 0xA3)
| Property | Value |
|----------|-------|
| **Purpose** | Encrypt session telemetry blob |
| **When Used** | Sent once during session establishment |
| **Algorithm** | AES-128-CBC |
| **Key** | `W2EC1X1NbZ58TXtn` (at `0x6dc7b` in unpacked binary) |
| **IV** | First 16 bytes of Base64-decoded payload |
| **Content** | JSON telemetry (phone info, adapter info, connection stats) |

**Key Summary:**

| Key | Algorithm | Purpose |
|-----|-----------|---------|
| `AutoPlay9uPT4n17` | AES-128-CBC | Firmware .img file encryption |
| `SkBRDy3gmrw1ieH0` | AES-128-CBC | USB protocol payload encryption (runtime) |
| `W2EC1X1NbZ58TXtn` | AES-128-CBC | SessionToken (type 0xA3) encryption |

**These are three independent systems** — firmware encryption protects static files, protocol encryption protects USB payloads at runtime, and SessionToken encryption protects the session telemetry blob.

---

## HomeKit Pairing v2 (SRP-6a)

### Parameters

| Parameter | Value |
|-----------|-------|
| **Algorithm** | Secure Remote Password v6a |
| **Prime** | 3072-bit |
| **Hash** | SHA-512 |
| **Salt** | 16 bytes random |

### Process

```
1. Client (phone) sends:
   - Username (I)
   - Public value A = g^a mod N

2. Server (adapter) sends:
   - Salt (s)
   - Public value B = kv + g^b mod N

3. Both compute:
   - Shared secret S = (A * v^u)^b mod N
   - Session key K = HKDF(S)

4. Client proves knowledge:
   - M1 = H(H(N) XOR H(g), H(I), s, A, B, K)

5. Server verifies M1, responds:
   - M2 = H(A, M1, K)
```

### Key Derivation

```cpp
// From libdmsdpcrypto.so
DMSDPGetPBKDF2Key();  // PBKDF2 key derivation

// Key derivation info strings
"Pair-Setup-Encrypt-Salt"
"Pair-Setup-Encrypt-Info"
```

---

## Key Exchange (X25519)

### Parameters

| Parameter | Value |
|-----------|-------|
| **Curve** | Curve25519 |
| **Key Size** | 32 bytes |
| **Library** | libdmsdpplatform.so |

### Usage

Used after SRP-6a pairing to establish per-session keys:

```
1. Both parties generate ephemeral X25519 keypairs
2. Compute shared secret via ECDH
3. Derive session keys using HKDF
```

---

## Transport Encryption (ChaCha20-Poly1305)

### Parameters

| Parameter | Value |
|-----------|-------|
| **Cipher** | ChaCha20 |
| **MAC** | Poly1305 |
| **Nonce** | 12 bytes (incremented) |
| **Key** | 32 bytes (from key exchange) |

### Frame Format

```
+-------------------+--------------------+----------------+
| Encrypted Data    | Auth Tag (16 bytes)| Nonce Counter  |
+-------------------+--------------------+----------------+
```

---

## Symmetric Encryption (AES-256-GCM)

### Parameters

| Parameter | Value |
|-----------|-------|
| **Algorithm** | AES-256 |
| **Mode** | GCM (Galois/Counter Mode) |
| **IV** | 12 bytes |
| **Tag** | 16 bytes |

### Functions (from libdmsdpplatform.so)

```cpp
AES_256GCMEncry()
AES_256GCMDecrypt()
```

---

## USB Protocol Payload Encryption (AES-128-CBC) — CORRECTED Feb 2026

**CORRECTION:** Previous documentation described AES-128-CTR with key `W2EC1X1NbZ58TXtn`. Binary analysis reveals the protocol payload encryption uses **AES-128-CBC** with key **`SkBRDy3gmrw1ieH0`**. The `W2EC1X1NbZ58TXtn` key is for SessionToken only.

### Two Distinct Runtime Encryption Keys

| Key | Mode | Purpose | Location |
|-----|------|---------|----------|
| `SkBRDy3gmrw1ieH0` | AES-128-CBC | Protocol payload encryption (all non-exempt types) | `0x6d0d4` in unpacked binary |
| `W2EC1X1NbZ58TXtn` | AES-128-CBC | SessionToken (type 0xA3) encryption only | `0x6dc7b` in unpacked binary |

### Protocol Encryption Parameters

| Parameter | Value |
|-----------|-------|
| **Algorithm** | AES-128-CBC (via OpenSSL `AES_set_encrypt_key`, `AES_cbc_encrypt`) |
| **Key** | `SkBRDy3gmrw1ieH0` (16 bytes, hardcoded) |
| **Key Derivation** | XOR-rotated with session seed: `(i + payloadSize) & 0x8000000F` |
| **Exempt Types** | 0x06 (Video), 0x07 (Audio), 0x2A (Dashboard), 0x2C (AltVideo) |
| **Magic Marker** | `0x55BB55BB` = encrypted, `0x55AA55AA` = cleartext |
| **HW Acceleration** | `/dev/hwaes` kernel module (misc 10:0, confirmed live) |

### CMD_ENABLE_CRYPT Protocol — Full Operational Lifecycle (Binary Verified Feb 2026)

**Type:** `0xF0` (standalone message type, NOT a sub-command of 0x08)
**Direction:** Bidirectional — host sends trigger (4B payload), adapter echoes empty 0xF0 ack
**Global state variable:** `0x11f408` (`.bss`, uint32, default 0 = encryption disabled)
**Write site:** 1 (`0x1f7ca`)
**Read sites:** 4 (`0x1ddbc`, `0x17b96`, `0x17d4a`, `0x18618`)

#### How to Enable Encryption

**Host sends type 0xF0 with 4-byte payload:**
```
Offset  Size  Field        Description
------  ----  -----        -----------
0x00    4     crypto_mode  uint32 LE, must be > 0 (e.g., 0x01000000)
```

**Example on-wire (host → adapter):**
```
55 AA 55 AA   magic (cleartext)
04 00 00 00   payload length = 4
F0 00 00 00   type = 0xF0
0F FF FF FF   type check = ~0xF0
01 00 00 00   crypto_mode = 1
```

#### Adapter Validation (at `0x1f798`)

```arm
0x1f798  ldr r3, [r6, 4]       ; payload length
0x1f79a  cmp r3, 4             ; MUST be exactly 4 bytes
0x1f79c  bne 0x1f7d6           ; REJECT → exit (no response sent)
0x1f79e  ldr r3, [r6, 0x10]   ; payload pointer
0x1f7a0  ldr r3, [r3]         ; crypto_mode value
0x1f7a2  cmp r3, 0
0x1f7a4  ble 0x1f7d6           ; REJECT if value ≤ 0 (no response sent)
```

**Rejection is silent** — if payload is wrong size or value ≤ 0, the adapter exits without sending any response. The host has no way to know the request was rejected other than the absence of an ack.

#### Adapter Response (at `0x1f7a6`)

On valid request, adapter echoes an empty 0xF0:
```arm
0x1f7a8  bl fcn.00064650       ; init message (magic=0x55AA55AA)
0x1f7ae  movs r1, 0xf0        ; type = 0xF0
0x1f7b0  movs r2, 0           ; payload size = 0
0x1f7b2  bl fcn.00064670       ; set message header
0x1f7bc  bl fcn.00018598       ; SEND empty 0xF0 ack to host
```

**Example on-wire (adapter → host):**
```
55 AA 55 AA   magic
00 00 00 00   payload length = 0 (empty)
F0 00 00 00   type = 0xF0
0F FF FF FF   type check
```

#### State Transition (at `0x1f7ca`)

```arm
0x1f7c8  ldr r3, [r3]         ; reload crypto_mode from payload
0x1f7ca  str r3, [r4]         ; WRITE to global at 0x11f408
```
Log: `"setUSB from HUCMD_ENABLE_CRYPT: %d\n"` (at `0x6ed2c`)

From this point, ALL subsequent messages pass through the encryption path in `fcn.00017b74`.

#### Key Derivation (at `0x17d9e`-`0x17dd0`)

**Derived key** — rotated permutation of base key:
```
derived_key[i] = "SkBRDy3gmrw1ieH0"[(i + crypto_mode) % 16]
```

Example for `crypto_mode=1`:
```
Base:    S k B R D y 3 g m r w 1 i e H 0
Derived: k B R D y 3 g m r w 1 i e H 0 S
```

**IV construction** — sparse scatter of crypto_mode bytes:
```
iv = [00, mode[0], 00, 00, mode[1], 00, 00, 00, 00, mode[2], 00, 00, mode[3], 00, 00, 00]
```
where `mode[n]` = `(crypto_mode >> (n*8)) & 0xFF`

**AES call chain:**
```
AES_set_encrypt_key(derived_key, 128, schedule)  ; at 0x17dd8 via PLT 0x15370
AES_cbc_encrypt(payload, payload, len, schedule, iv, direction)  ; at 0x17ee4 via PLT 0x14a60
```
Direction: `1` = encrypt (outbound), `0` = decrypt (inbound). In-place operation.

#### Encryption Bypass (at `0x17d60-0x17d72`)

Before applying AES, the adapter checks the message type:
```arm
0x17d60  ldr r3, [r4, 8]       ; message type
0x17d62  subs r2, r3, 6
0x17d64  cmp r2, 1             ; types 6-7 (Video/Audio) → SKIP
0x17d68  cmp r3, 0x2c          ; type 0x2C (AltVideo) → SKIP
0x17d6c  cmp r3, 0x2a          ; type 0x2A (Dashboard) → SKIP
0x17d72  bl fcn.00064614       ; all others → SET 0x55BB55BB magic
```

| Type | Name | Encrypted? | Reason |
|------|------|-----------|--------|
| 0x06 | VideoFrame | **NO** | Bandwidth — unacceptable latency for 60fps video |
| 0x07 | AudioFrame | **NO** | Real-time audio — cannot tolerate AES overhead |
| 0x2A | DashBoard_DATA | **NO** | Frequent metadata updates |
| 0x2C | AltVideoFrame | **NO** | Navigation video — same latency concern |
| All others | - | **YES** | Payload encrypted via AES-128-CBC |

#### How to Disable Encryption

**You cannot.** There is no CMD_DISABLE_CRYPT, and the validation at `0x1f7a4` rejects values ≤ 0 before reaching the store instruction. The only way to return to cleartext is to **restart the adapter process** (which reinitializes `0x11f408` to 0 in `.bss`).

Options:
- Send type 0xCD (`HUDComand_A_Reboot`) — full adapter reboot
- Send type 0xCE (`HUDComand_A_ResetUSB`) — USB gadget reset (may restart process)
- USB disconnect/reconnect (adapter auto-resets on detach)

#### When in the Session to Enable

The binary does not enforce timing — 0xF0 can be sent at any point after USB connection. However, practical considerations:
- Send **after Open (0x01)** and **before streaming starts** (Phase 8)
- Send before any sensitive configuration data (BoxSettings contains credentials)
- Do NOT send during active streaming — existing in-flight messages will fail decryption

#### Security Assessment

| Property | Value | Rating |
|----------|-------|--------|
| Key space | 16 rotations of known hardcoded key | **CRITICAL** — trivially brute-forceable |
| IV | Deterministic from crypto_mode | **HIGH** — same mode always produces same IV |
| Key visibility | Hidden behind UPX packing | **LOW** — trivial to unpack |
| All adapters share same key | Yes | **CRITICAL** — universal key for all CPC200 units |
| Disable capability | None | Session-scoped (reboot to clear) |
| HW acceleration | `/dev/hwaes` kernel module available | Performance acceptable |

### Available Functions (libdmsdpplatform.so)

```cpp
AES_128CTREncry()     // CTR mode available but NOT used for USB protocol
AES_128CTRDecrypt()
AES_128OFBEncry()     // OFB mode available but NOT used
AES_128OFBDecrypt()
// Also: AES-CFB128 support found in AppleCarPlay binary
```

---

## Firmware Encryption (AES-128-CBC)

For complete firmware `.img` encryption analysis including model-specific keys, file format, key extraction method, and encryption/decryption scripts, see `01_Firmware_Architecture/firmware_encryption.md`.

**Summary:** AES-128-CBC with key `AutoPlay9uPT4n17` (A15W model), 16-byte IV same as key, no padding (last partial block left unencrypted). Model-specific key variants exist for U2W, U2AW, U2AC, and HWFS. Decrypted contents are `.tar.gz` archives containing firmware files.

---

## Hardware AES Engine

| Path | Purpose |
|------|---------|
| `/dev/hwaes` | Hardware AES acceleration |

The firmware can optionally use hardware AES for improved performance.

---

## OpenSSL Functions Used

From `libcrypto.so.1.1`:

```cpp
AES_set_encrypt_key()
AES_set_decrypt_key()
AES_cbc_encrypt()
HMAC_Init_ex()
HMAC_Update()
HMAC_Final()
SHA256_Init()
SHA256_Update()
SHA256_Final()
SHA512_*()
```

---

## Huawei Key Store (HiCar)

From `libHwKeystoreSDK.so`:

| Function | Purpose |
|----------|---------|
| `HwKeystoreInit()` | Initialize key store |
| `HwKeystoreGenerateKey()` | Generate key pair |
| `HwKeystoreSign()` | Sign data |
| `HwKeystoreVerify()` | Verify signature |

---

## References

- Source: `GM_research/cpc200_research/docs/analysis/ANALYSIS_UPDATE_2025_01_15.md`
- Source: `pi-carplay-4.1.3/firmware_binaries/PROTOCOL_ANALYSIS.md`
- Binary analysis: `libdmsdpplatform.so`, `libdmsdpcrypto.so`
