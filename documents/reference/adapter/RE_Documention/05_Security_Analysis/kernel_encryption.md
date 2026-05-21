# CPC200-CCPA Kernel Encryption Analysis

**Purpose:** Document kernel encryption findings and decryption attempts
**Analysis Date:** 2026-01-29
**Status:** Encryption key NOT yet identified

---

## Overview

The kernel stored in flash (mtd1) is encrypted using a separate mechanism from the firmware image encryption. The firmware encryption key (`AutoPlay9uPT4n17`) does NOT decrypt the kernel.

---

## Kernel Binary Properties

| Property | Value |
|----------|-------|
| Location | mtd1 (/dev/mtd1) |
| Size | 3,407,872 bytes (3.3MB) |
| Flash Offset | 0x40000 (256KB from start) |
| RAM Load Address | 0x80800000 |
| Encryption | Unknown algorithm/key |
| Compression | Unknown (likely gzip after decryption) |

---

## U-Boot Decryption References

### Key Strings in U-Boot

| Offset | String | Significance |
|--------|--------|--------------|
| 0x1641e | `UPGkey: %s` | Upgrade/kernel key logging |
| 0x1643b | `do_decrypt_decompress ret=%d dstLen=%d` | Decryption function |
| 0x17483 | `heweiencrypt` | HeWei (manufacturer) encryption marker |
| 0x16484 | `Can't start, Bad Keys.` | Key validation failure |

### Potential Hash/Key Values at 0x800-0x900

```
0x800: q86ce5527082c08c ab96d863d895861a 8
0x820: V1bc3d222374b83000d70fe 20a83afcd2
0x850: u9e02fb58972989f6c628561fee75c 6cc
0x878: z0b8dbc1388ff2249f1df e7dbf7a8f8b5
0x8a0: j4261f4de0511ee9f2832680f763 d0077
0x8c8: vecb99e6ffea7be1e5419350f725da86b
0x8f0: ?7479ae0220b1464b40b14bc0f04d1244
```

These appear to be SHA256 hashes or device-specific identifiers, NOT encryption keys.

---

## Decryption Attempts

### Keys Tested

| Key | Algorithm | Result |
|-----|-----------|--------|
| `AutoPlay9uPT4n17` | AES-128-CBC | Failed - random output |
| `W2EC1X1NbZ58TXtn` | AES-128-CBC | Not tested |
| `q86ce5527082c08c` | AES-128-CBC | Failed |
| `ab96d863d895861a` | AES-128-CBC | Failed |
| `V1bc3d222374b830` | AES-128-CBC | Failed |
| `u9e02fb58972989f` | AES-128-CBC | Failed |

### Observations

1. Output is completely random - no magic bytes visible
2. No ARM boot signatures (0xea000000, etc.) found
3. No compression signatures (1f8b for gzip, etc.) found
4. Encryption appears to cover entire partition

---

## Boot Flow Analysis

### U-Boot norboot Command

```
sf probe 0                              # Probe SPI flash
sf read 0x80800000 0x100000 0x4F0000    # Read kernel (5MB) to RAM
sf read 0x83000000 0x5F0000 0x10000     # Read DTB (64KB) to RAM
bootz 0x80800000 - 0x83000000           # Boot zImage with DTB
```

### Decryption Location

Decryption likely occurs between `sf read` and `bootz` commands. The `do_decrypt_decompress` function handles this.

---

## Memory Protection

### CONFIG_STRICT_DEVMEM

The kernel has `CONFIG_STRICT_DEVMEM` enabled, blocking direct `/dev/mem` access to kernel memory regions:

```bash
dd if=/dev/mem of=/mnt/UPAN/kernel.bin bs=4096 skip=$((0x80008000/4096)) count=1400
# Result: dd: /dev/mem: Bad address
```

### Accessible Memory

```
/proc/kallsyms - Full kernel symbols (48,959 entries)
/proc/iomem    - Memory map
/proc/modules  - Loaded modules
```

### Anti-Debug: ptrace and ftrace Disabled (Verified 2026-05-21)

The kernel **rejects `ptrace` entirely** — every request (`PTRACE_SEIZE`, `PTRACE_ATTACH`, …)
returns `EINVAL`, kernel-wide. This was verified to fail even when attaching to a self-spawned
process; there is no yama LSM and nothing else tracing the target. This is a deliberate Carlinkit
anti-RE hardening measure — it is the mechanism behind the known "blocked strace" behaviour.

There is also **no ftrace** — `/sys/kernel/debug/tracing` is absent.

**Consequence:** no in-place syscall / I²C / function tracing is possible on this firmware. RE
must instead rely on packet capture, `/proc/<PID>/mem` dumps, static analysis, or `LD_PRELOAD`
shims.

Source: `MFi_research/CAPTURE_SESSION.md` §2, §8.

---

## Alternative Extraction Methods

### Method 1: Kernel Module

Create a kernel module that:
1. Allocates buffer in kernel space
2. Copies kernel text section to buffer
3. Exposes via /proc or sysfs
4. Requires compiling against 3.14.52 kernel headers

### Method 2: JTAG/SWD

The i.MX6UL has JTAG accessible via test pads:
1. Connect JTAG debugger
2. Halt CPU during boot (after decryption)
3. Dump RAM at 0x80008000

### Method 3: U-Boot Modification

1. Modify U-Boot to dump decrypted kernel
2. Flash modified U-Boot to mtd0
3. Boot and capture output
4. **Risk:** Could brick device if U-Boot fails

### Method 4: Cold Boot Attack

1. Boot device normally
2. Power cycle quickly
3. Some RAM may retain data
4. Dump with external tool

---

## Encryption Algorithm Candidates

Based on `heweiencrypt` marker and embedded system norms:

| Algorithm | Likelihood | Notes |
|-----------|------------|-------|
| AES-128-CBC | Medium | Common, but key unknown |
| AES-256-CBC | Low | Key would need to be 32 bytes |
| DES/3DES | Low | Outdated, unlikely |
| ChaCha20 | Low | Less common in bootloaders |
| XOR | Low | Too weak for kernel |
| Custom | Medium | "HeWei" may indicate proprietary |

---

## Key Derivation Possibilities

The key may be derived from:
1. **Hardware ID** - OTP fuses in i.MX6
2. **Device Serial** - Unique per device
3. **Hardcoded** - In U-Boot binary (not found yet)
4. **External** - Read from secure storage

### i.MX6 OTP/Fuse Locations

| Register | Purpose |
|----------|---------|
| OCOTP_CFG0-6 | Unique ID |
| OCOTP_SRK0-7 | Secure boot keys |
| OCOTP_MAC0-1 | Ethernet MAC |

---

## U-Boot Disassembly Analysis

### Analyzed Functions

| Offset | Function | Purpose |
|--------|----------|---------|
| 0xb9f0-0xba66 | Image header processor | Parses image headers, checks for `heweiencrypt` marker |
| 0xb824 | Entry counter | Counts entries in image header |
| 0xb836-0xb884 | Data extractor | Extracts data from header structure (0x40 offset) |
| 0xa918 | Printf wrapper | Called for all log output |
| 0x13270 | Memory calculator | Size/checksum calculations |

### Code Flow Analysis

The image processing at 0xb9f0-0xba66:
1. Loads address from literal pool pointing to `heweiencrypt` string
2. Checks header byte at offset 0x1e, masks with 0xfd, compares to 4
3. If match, processes image data starting at offset 0x40
4. Iterates through entries, calling functions at 0xb824 and 0xb836
5. Each iteration processes 4-byte aligned data with byte-reversal (endianness)

### Address Table at 0x1c000

Contains function pointer table with addresses in format `XX XX 80 87 17 00 00 00`:
- 0x1c2b0: Points to 0x1781641e (UPGkey string)
- Multiple entries for string references and function pointers

### Key Derivation Theory

Based on disassembly analysis:
1. The key is NOT a simple hardcoded string
2. `UPGkey: %s` is printed during boot - key is dynamically generated
3. The hashes at 0x800-0x900 differ between U-Boot versions (device/build specific)
4. Key likely derived from combination of:
   - OTP fuse values (device unique ID)
   - Build-time generated values
   - Possible XOR with known constant

### Critical Discovery: Device-Specific Encryption

**The encrypted kernel in flash differs from the encrypted kernel in firmware images.**

| Source | First 16 bytes |
|--------|----------------|
| Flash dump (mtd1.bin) | `9cf8 9a27 dc93 3d73 2248 4be2 0733 b7bb` |
| Firmware (AutoBox.img) | `988e 6d54 faa3 1d3d 1378 c275 e18d b611` |

This confirms the kernel encryption is **device-specific** - the same kernel is encrypted differently for each device. The decryption key must be:
- Derived from hardware-specific values (OTP fuses)
- Generated during U-Boot initialization
- Unique to each physical device

### Keys Tested (All Failed)

| Key | Type | Result |
|-----|------|--------|
| `AutoPlay9uPT4n17` | Firmware encryption | Random output |
| `W2EC1X1NbZ58TXtn` | USB/Session encryption | Random output |
| `heweiencryptXXXX` | Marker string | Random output |
| `86ce5527082c08c...` | Hash from header | Random output |
| Various combinations | Hybrid keys | Random output |

### Recommended Extraction Method

**Serial Console Capture** - Most reliable approach:
1. Connect UART to ttymxc0 (115200 baud)
2. Boot device
3. Capture "UPGkey: %s" output
4. Use captured key for decryption

---

## Next Steps

1. ~~Disassemble U-Boot~~ - DONE - Key functions identified
2. **Capture serial boot log** - To see UPGkey value printed
3. **Check OTP fuses** - Via `/sys/bus/platform/devices/21bc000.ocotp-fuse/`
4. **Kernel module approach** - Bypass /dev/mem restriction
5. **Compare U-Boot versions** - May reveal key patterns

---

## References

- Flash dump: `/mnt/UPAN/flash_dump/`
- U-Boot binary: `mtd0.bin` (256KB)
- Encrypted kernel: `mtd1.bin` (3.3MB)
- Kernel symbols: `kallsyms_full.txt`
