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
| **Algorithm (Firmware)** | AES-128-CBC (via OpenSSL `AES_set_encrypt_key`, `AES_cbc_encrypt`) |
| **Algorithm (AutoKit App)** | **AES/CFB/NoPadding** (via `javax.crypto.Cipher`) |
| **Key** | `SkBRDy3gmrw1ieH0` (16 bytes, hardcoded) |
| **Key Derivation** | Rotated permutation: `key[i] = "SkBRDy3gmrw1ieH0".charAt((nonce + i) % 16)` |
| **IV Construction** | 16-byte sparse scatter: `iv[1]=nonce[0], iv[4]=nonce[1], iv[9]=nonce[2], iv[12]=nonce[3]` |
| **Nonce** | Random positive int32 (`Random().nextInt(Integer.MAX_VALUE)`), must be > 0 |
| **Exempt Types (Firmware)** | 0x06 (Video), 0x07 (Audio), 0x2A (Dashboard), 0x2C (AltVideo) |
| **Exempt Types (AutoKit App)** | 0x06 (Video), 0x07 (Audio) only — firmware exempts 0x2A/0x2C server-side regardless |
| **Magic Marker** | `0x55BB55BB` = encrypted, `0x55AA55AA` = cleartext |
| **HW Acceleration** | `/dev/hwaes` kernel module (misc 10:0, confirmed live) |

#### CBC vs CFB Discrepancy (Mar 2026)

**CRITICAL:** The firmware binary uses `AES_cbc_encrypt` (OpenSSL CBC mode), but the manufacturer's AutoKit app (v2025.03.19.1126) uses `AES/CFB/NoPadding` (Java CFB mode). Both use the same 128-bit key, IV, and key derivation formula.

**Source evidence:**
- **Firmware (CBC):** ARM assembly at `0x17ee4` calls `AES_cbc_encrypt` via PLT `0x14a60`. OpenSSL function name unambiguous.
- **AutoKit app (CFB):** Java source line 2539: `Cipher.getInstance("AES/CFB/NoPadding")` — string literal, no ambiguity.

**Possible explanations:**
1. **CFB-8 vs CBC equivalence**: For single-block payloads, AES-CFB8 and AES-CBC produce identical output (first block XOR). If most encrypted payloads are ≤16 bytes, both modes would interoperate. However, multi-block payloads would diverge.
2. **Firmware uses HW AES path**: The firmware may route through `/dev/hwaes` (ioctl `0xC00C6206`) which could implement CFB despite the wrapper function being named `AES_cbc_encrypt` — the NXP i.MX6 DCP engine supports both modes.
3. **Version-specific**: The AutoKit app may target newer firmware versions that switched to CFB, while the binary analysis was performed on firmware 2025.10.15.

**Recommendation:** Host implementations should support **both CBC and CFB** for maximum compatibility, or test empirically with the specific firmware version. The key, IV, and exempt-type logic are identical regardless of mode.

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
- Do NOT send during active streaming — existing in-flight messages will fail decryption

**Manufacturer's AutoKit app (v2025.03.19.1126) timing:**
The AutoKit app sends CMD_ENABLE_CRYPT **BEFORE the Open message**, as step 2 of the initialization sequence:
1. Send AppInfo (type 0xA0) — host identification
2. **Send CMD_ENABLE_CRYPT (type 0xF0)** — random positive int32 nonce
3. Send SendFile `/tmp/screen_dpi`
4. Send SendFile `/etc/android_work_mode`
5. Send Open (type 0x01) — session parameters
6. Wait for Open response, then send BoxSettings

This means all subsequent messages (including Open, BoxSettings, and any credentials) are encrypted from the start.

**AutoKit nonce generation (f.java line 1870-1872):**
```java
while (this.n <= 0) {
    this.n = new Random().nextInt(Integer.MAX_VALUE);
}
```
The nonce is always a positive int32 (1 to 2,147,483,647). It is passed to the USB layer via `d.W(this.n)` for the read thread to use for decryption.

**AutoKit encryption receive handler (f.java line 1273-1276):**
```java
case 240:  // CMD_ENABLE_CRYPT response
    s.e("BoxProtocol,onCmd: recv CMD_ENABLE_CRYPT");
    this.m = true;  // enable encryption flag
    return;
```
On receiving the adapter's 0xF0 ack, the app sets `m=true`, which gates all subsequent encrypt/decrypt calls in the message handler.

**AutoKit encrypt/decrypt method (f.java inner class `l`, method `i(boolean z)`):**
```java
private void i(boolean z) {  // z=true: encrypt, z=false: decrypt
    if (!f.this.m || this.a.f1462b == 0) return;  // skip if not enabled or empty
    int i2 = this.a.f1463c;  // message type
    if (z && i2 != 6 && i2 != 7) {  // outbound: set 0x55BB magic for non-video/audio
        this.a.a();  // sets magic to 0x55BB55BB
    }
    if (this.a.b()) {  // b() checks if magic == 0x55BB55BB
        byte[] key = new byte[16];
        for (int i3 = 0; i3 < 16; i3++) {
            key[i3] = (byte) "SkBRDy3gmrw1ieH0".charAt((f.this.n + i3) % 16);
        }
        byte[] iv = new byte[16];
        iv[1]  = (byte) (f.this.n & 0xFF);
        iv[4]  = (byte) ((f.this.n >> 8) & 0xFF);
        iv[9]  = (byte) ((f.this.n >> 16) & 0xFF);
        iv[12] = (byte) ((f.this.n >> 24) & 0xFF);
        Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding");
        cipher.init(z ? 1 : 2, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        System.arraycopy(cipher.doFinal(payload), 0, buffer, 0, payloadLen);
    }
}
```

**Key behavioral notes from AutoKit:**
- Outbound encryption: only types != 6 (Video) and != 7 (Audio) get magic set to `0x55BB55BB`
- Inbound decryption: checks `b()` (magic == `0x55BB55BB`) before decrypting — if adapter sends cleartext (0x55AA55AA), no decryption is attempted
- Error handling: catches all exceptions, logs `"handleCryptData: "` + stack trace, continues without crashing
- The encrypt/decrypt is **in-place** — `cipher.doFinal()` result is copied back over the original payload buffer

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
// AES-CFB128 support found in AppleCarPlay binary — AND used by AutoKit app (see CBC vs CFB note above)
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
