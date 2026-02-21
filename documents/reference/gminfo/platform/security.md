# GM Info 3.7 Security Architecture

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** December 2025 - February 2026

---

## Android Security by Firmware Version

| Feature | Y175 (June 2024) | Y177 (March 2025) | Y181 (July 2025) |
|---------|-------------------|---------------------|-------------------|
| SELinux | Enforcing | **PERMISSIVE** | Enforcing (462 denials in logcat — CBC, RVC, SXM, but no USB/CarPlay denials) |
| dm-verity | Enabled | Enabled | Enabled |
| FBE (File-Based Encryption) | Yes | Yes | Yes |
| Security Patch | 2024-05-05 | 2025-03-05 | 2025-06-05 |
| Kernel | 4.19.283 | 4.19.305 | 4.19.305 |

**WARNING:** Y177 (March 2025) has SELinux **permissive** — this is a significant security regression. All SELinux policy violations are logged but not enforced, allowing any process to bypass mandatory access controls. See [Y177 Attack Vectors](#y177-attack-vectors-selinux-permissive) below.

---

## Secure Boot Chain

The boot chain is a 5-layer trust hierarchy from hardware root to runtime filesystem integrity:

| Layer | Component | Mechanism | Details |
|-------|-----------|-----------|---------|
| 1 | Intel CSE | Hardware root of trust | OTP fuses, immutable, first code executed |
| 2 | SOC_ABL (SoC Application Boot Loader) | Intel IPK format | ~6MB signed image, `.oemkeys` contains RSA-2048 public key for chain validation |
| 3 | GHS INTEGRITY Hypervisor | SHA-1 + CRC validation | `BSP_CRCCheck`, `BSP_SHA1Check` verify hypervisor and VM images before launch |
| 4 | AVB (Android Verified Boot) | RSA-2048 / SHA-256 | `vbmeta` signature verified by GHS VMM1 before Android kernel loads |
| 5 | dm-verity | Runtime block integrity | Protects system, vendor, and product partitions; hash tree verified on read |

---

## VIP MCU Security

VIP security function ADDRESS: `0x000b67d0` (NOT a firmware version). VIP firmware version: **2B.174.4.1** (Build 24Mar22-0256).

| Version | VIP_APP File ID | Security Function | Details |
|---------|-----------------|-------------------|---------|
| Y177 | 86283151 | 4-byte stub (always returns 0) | `mov 0, r10; jmp [lp]` — security function disabled |
| Y181 | 86331656 | 906-byte full implementation | Security function active, calls validation subroutines |

### Y181 VIP Security Function Detail

The 906-byte security function at `0x000b67d0` performs multi-stage validation:

- **Validation calls:** `0x000ecd84`, `0x000b6652`, `0x000aee28`
- **Debug flag:** Loaded from RAM address `0x3e06` — controls security bypass behavior
- **Callers:** `0x000b6b06` (primary entry), `0x000b6e82` (secondary/fallback entry)
- **SELinux mode control:** The result of this function determines whether the SoC receives a security-validated or bypass response, which in turn controls whether `gm_protokey` sets SELinux to enforcing or permissive (see [ProtoKey Authentication](#protokey-authentication))

Between Y177 and Y181, 549,518 bytes differ in VIP firmware — 28.4% of the total firmware image. The re-enablement of the VIP security function in Y181 represents a significant hardening compared to Y177.

---

## ProtoKey Authentication

The VIP MCU mediates a seed-key exchange that determines Android's SELinux enforcement mode:

### Normal Operation (EEPROM 0x0440 = 0x00, secured)

1. VIP requests seed from BCM (Body Control Module) via GMLAN
2. BCM responds with 32-byte seed (ECUID + random nonce)
3. VIP forwards seed to SoC over HDLC IPC
4. SoC passes seed to `gm_protokey` service
5. `gm_protokey` computes key response, VIP validates
6. Validation passes → SELinux **ENFORCING**

### Bypassed Operation (EEPROM 0x0440 = 0xFF, bypass)

1. VIP reads SBI flag, sees 0xFF
2. VIP returns 0xFF (bypass indicator) to SoC
3. SoC passes to `gm_protokey`
4. `gm_protokey` detects bypass → SELinux **PERMISSIVE**

### gm_protokey Service

- **Binary:** `/vendor/bin/gm_protokey`
- **Init:** class `main`, user `root`
- **SELinux domain:** `gm_protokey`
- **Function:** Translates VIP security response into SELinux enforcement mode

---

## MEC (Module Event Counter)

The MEC counter tracks consecutive SoC boot failures and progressively disables recovery mechanisms:

| MEC Value | Behavior |
|-----------|----------|
| MEC = 0 | Normal operation. VIP monitors GHS hypervisor startup, enforces timeouts. |
| MEC != 0 (e.g. 207) | Ignores hypervisor startup timeout. Allows extended boot times. |
| MEC >= 3 | After 3 consecutive SoC failures: hypervisor timer **DISABLED**, CSM reset **DISABLED**. System enters degraded monitoring state. |

---

## EEPROM Security

**Device:** ST M24C64, 8KB, I2C address `0x50`

- Security flags located at offsets `0x0440`, `0x0A80`, `0x0B40`
- CRC is present but **NOT enforced** at boot (write-and-reboot bypass possible)
- CalGroup markers are runtime-generated, not static EEPROM values
- **I2C buses 0, 1, 2, AND 3 are world-writable** — this is a privilege escalation vector, as any process with filesystem access can read/write EEPROM contents
  - i2c-0: general purpose
  - i2c-1: general purpose
  - i2c-2: system bus
  - i2c-3: audioserver bus
- **BOTH** `0x0440` AND `0x0A80` must be modified for a complete security bypass
- OTA updates **ACTIVELY RESET** security flags back to secured state (0x00)

### EEPROM Memory Map

| Offset Range | Function | Details |
|--------------|----------|---------|
| `0x0000`-`0x005F` | Header & Boot Flags | `0x0001` boot state: `0x00`=virgin, `0x01`=normal, `0xFF`=corrupted |
| `0x0400`-`0x045F` | Security Configuration | **OUTSIDE CRC protection** — modifications not detected |
| `0x0440` | Primary SBI Flag | Data byte at `0x0441`: `0x00`=secured, `0xFF`=bypass |
| `0x05C0`-`0x05D1` | VIN (Vehicle ID Number) | 17-byte VIN storage |
| `0x0A80` | Backup SBI Flag | Data byte at `0x0A81`: same encoding as primary (`0x00`=secured, `0xFF`=bypass) |
| `0x0B40` | Debug Mode Flag | Data byte at `0x0B41`: `0x00`=off, `0x01`=debug enabled |
| `0x16E0` | CRC Location 1 | NOT enforced at boot |
| `0x19E0` | CRC Location 2 | NOT enforced at boot |
| `0x1A80` | CRC Location 3 | NOT enforced at boot |
| `0x1B40` | CRC Location 4 | NOT enforced at boot |

**CRC bypass confirmed:** Tested with corrupted VIN at `0x05C0` — system boots normally, CRC mismatch ignored.

### EEPROM Framing Markers

| Marker Byte | Type |
|-------------|------|
| `0x5A` | Security |
| `0x69` | Configuration |
| `0xF0` | Data |
| `0xC3` | Calibration |

### EEPROM Attack Surface

The combination of unenforced CRC and world-writable I2C buses means that EEPROM contents (including security flags and calibration data) can be modified by any user-space process without requiring elevated privileges. Changes persist across reboots since CRC validation is not enforced during boot. The security configuration region (`0x0400`-`0x045F`) is deliberately outside CRC protection, meaning even if CRC were enforced, SBI flag modifications would not be detected.

---

## Kernel Security Mitigations

| Mitigation | Status |
|------------|--------|
| KASLR | Enabled |
| Stack Protector | Enabled |
| SMEP/SMAP | Enabled |
| Spectre v2 | Mitigated (IBRS/IBPB) |

### Kernel CONFIG Flags

#### Enabled (hardening active)

| Config Flag | Purpose |
|-------------|---------|
| `CONFIG_STACKPROTECTOR_STRONG` | Stack buffer overflow detection (strong variant) |
| `CONFIG_STRICT_KERNEL_RWX` | Kernel text/rodata marked read-only, data non-executable |
| `CONFIG_STRICT_MODULE_RWX` | Same protections applied to loadable kernel modules |
| `CONFIG_RANDOMIZE_BASE` | KASLR — randomize kernel base address |
| `CONFIG_RANDOMIZE_MEMORY` | Randomize physical memory mapping |
| `CONFIG_RETPOLINE` | Spectre v2 mitigation via retpoline thunks |
| `CONFIG_X86_SMAP` | Supervisor Mode Access Prevention — kernel cannot access userspace memory |
| `CONFIG_X86_INTEL_UMIP` | User-Mode Instruction Prevention — blocks SGDT/SIDT/SLDT/SMSW/STR from ring 3 |
| `CONFIG_X86_INTEL_MEMORY_PROTECTION_KEYS` | Hardware memory protection keys (PKU) |
| `CONFIG_SECCOMP` | Syscall filtering for sandboxed processes |
| `CONFIG_HARDENED_USERCOPY` | Runtime bounds checking on copy_to/from_user |
| `CONFIG_INIT_ON_ALLOC_DEFAULT_ON` | Zero-fill heap allocations (info leak prevention) |
| `CONFIG_SLAB_FREELIST_RANDOM` | Randomize SLAB freelist order (heap spray mitigation) |
| `CONFIG_BPF_JIT_ALWAYS_ON` | Force BPF JIT (disables interpreter, reduces attack surface) |
| `CONFIG_X86_INTEL_TSX_MODE_OFF` | TSX disabled (mitigates TAA/MDS side-channel attacks) |

#### Disabled (attack surface reduction)

| Config Flag | Significance |
|-------------|-------------|
| `CONFIG_USER_NS` is not set | No unprivileged user namespaces — blocks container escape primitives |
| `CONFIG_MODIFY_LDT_SYSCALL` is not set | No LDT modification — blocks certain exploitation techniques |
| `CONFIG_LEGACY_VSYSCALL_NONE` | No legacy vsyscall page — removes known-address gadget source |
| `CONFIG_KVM` is not set | No KVM virtualization — reduces hypervisor attack surface |
| `CONFIG_NF_TABLES` is not set | No nftables — eliminates entire class of netfilter vulnerabilities |
| `CONFIG_USERFAULTFD` is not set | No userfaultfd — blocks common race condition exploitation technique |
| `CONFIG_IO_URING` is not set | No io_uring — eliminates major recent vulnerability source |

#### Missing (gaps in hardening)

| Config Flag | Impact |
|-------------|--------|
| `CONFIG_SLAB_FREELIST_HARDENED` | Not set — freelist pointer obfuscation absent, heap metadata corruption easier |
| `CONFIG_SECURITY_YAMA` | Not set — no ptrace scope restrictions, any process can ptrace others |

#### SELinux Kernel Support

`CONFIG_SECURITY_SELINUX_DEVELOP=y` — the kernel **supports** permissive mode at compile time. Whether SELinux is actually enforcing or permissive at runtime is controlled by the VIP security function result via `gm_protokey`. This is why Y177 (VIP stub returns 0) runs permissive while Y181 (VIP validates) runs enforcing.

---

## File-Based Encryption (FBE)

| Property | Value |
|----------|-------|
| `ro.crypto.state` | `encrypted` |
| `ro.crypto.type` | `file` |
| Cipher | `aes-256-xts:aes-256-cts` |
| Metadata Encryption | `dm-default-key` |
| Keystore Backend | Trusty TEE via GHS (`ro.hardware.keystore=trusty`) |

---

## Trusty TEE (Trusted Execution Environment)

The Trusty TEE runs under GHS INTEGRITY hypervisor, providing hardware-backed key storage and cryptographic operations.

### TEE Services

| Service | Function |
|---------|----------|
| `TEE_Keymaster` | Hardware-backed key generation, storage, and operations |
| `TEE_HW_Crypto` | Hardware cryptographic acceleration |
| `TEE_Storage` | Secure storage via RPMB (Replay Protected Memory Block) |

### ELF Sections

TEE code is loaded from dedicated ELF sections in the hypervisor image:
- `.tee_keymaster.text` — Keymaster executable code
- `.tee_keymaster.data` — Keymaster mutable data
- `.tee_keymaster.rodata` — Keymaster read-only data

### HAL Configuration

- **HAL:** `keymaster@3.0` (Trusty-GHS implementation)
- **Init script:** `init.trusty-ghs.rc`
- **Properties set at init:**
  - `ro.hardware.gatekeeper=trusty`
  - `ro.hardware.keystore=trusty`

---

## GHS Device Interfaces

The GHS INTEGRITY hypervisor exposes the following device interfaces under `/dev/ghs/`:

| Interface | Purpose |
|-----------|---------|
| `textlog` | Hypervisor text logging |
| `audit` | Security audit log |
| `snapshot-dbg` | Debug snapshot capture |
| `ipc` | Inter-VM communication |
| `cal` | Calibration data interface |
| `camera` | Camera subsystem bridge |
| `chime` | Chime/alert audio interface |
| `tee-att` | TEE attestation |
| `emmc-health` | eMMC health monitoring |
| `ota-isys` | OTA update interface (ISYS) |
| `gpu-dbg` | GPU debug interface |

---

## GM SELinux Domains

### Critical Service Domains

| Domain | Function | Notes |
|--------|----------|-------|
| `gm_vnd_IPCServer` | VIP MCU IPC communication | Runs as **ROOT**, handles HDLC protocol on `/dev/ttyS1` |
| `gm_diagnosticsd` | UDS diagnostic service | Handles $10/$22/$27/$2E/$31/$34/$36/$37 commands |
| `gm_update_engine` | OTA firmware updates | Has GSI blocker: `dontaudit gm_update_engine gsi_metadata_file` |
| `gm_vehicle_hal` | Vehicle HAL implementation | Bridge between Android and VIP/CAN |
| `gm_protokey` | Security key validation | Controls SELinux enforcement mode |

### Vehicle HAL Clients

The following domains have access to Vehicle HAL properties:

- `system_server`
- `gmConnectionService`
- `camerad`
- `plmanager`
- `vehicleaudiocontrol`

### GSI Blocker

The SELinux policy includes `dontaudit gm_update_engine gsi_metadata_file` — this silently blocks Generic System Image (GSI) installation attempts without logging a denial, preventing aftermarket Android ROM installation.

---

## Key CVEs

| CVE | CVSS | Description | Y177 Impact | Y181 Impact |
|-----|------|-------------|-------------|-------------|
| CVE-2024-53104 | 7.8 | UVC (USB Video Class) vulnerability | **EXPLOITABLE** (SELinux permissive) | Blocked (SELinux enforcing) |
| CVE-2024-36971 | 7.8 | dst_cache UAF (use-after-free) | **EXPLOITABLE** (SELinux permissive) | Blocked (SELinux enforcing) |
| CVE-2024-53150 | 7.8 | USB out-of-bounds read (Cellebrite chain) | **EXPLOITABLE** (SELinux permissive) | Blocked (SELinux enforcing) |
| CVE-2024-53197 | 7.8 | USB ALSA out-of-bounds access (Cellebrite chain) | **EXPLOITABLE** (SELinux permissive) | Blocked (SELinux enforcing) |
| CVE-2023-2163 | 8.8 | BPF verifier range tracking | **Exploitable** (requires CAP_BPF) | Blocked (SELinux enforcing) |
| CVE-2024-1086 | 7.8 | nf_tables use-after-free | **NOT APPLICABLE** | **NOT APPLICABLE** |

**CVE-2024-53150 / CVE-2024-53197:** Part of the Cellebrite USB exploitation chain. Added to CISA KEV (Known Exploited Vulnerabilities) catalog in April 2025. These are actively exploited in the wild for mobile forensics. On Y177 with SELinux permissive, the USB stack is fully exploitable. Y181 SELinux enforcing policy contains USB driver access to authorized domains only.

**CVE-2023-2163:** BPF verifier allows out-of-bounds memory access. Requires `CAP_BPF` capability. On Y177, SELinux permissive means capability checks are the only barrier. `CONFIG_BPF_JIT_ALWAYS_ON` reduces interpreter attack surface but does not prevent verifier bypass.

**CVE-2024-1086:** Not applicable — `CONFIG_NF_TABLES` is not set in kernel config, so the vulnerable nf_tables subsystem is not compiled into the kernel.

Both Y177 CVEs are exploitable due to SELinux being in permissive mode, which removes the mandatory access control layer that would otherwise contain exploitation. On Y181 with SELinux enforcing, exploitation is blocked by policy even if the underlying kernel vulnerability remains unpatched.

---

## Attack Surface Analysis

### Network Services

| Bind Address | Port | Risk | Notes |
|--------------|------|------|-------|
| `0.0.0.0` | 6363 | **HIGH** | Binds all interfaces, accessible from USB network |
| `0.0.0.0` | 7000 | **HIGH** | Binds all interfaces |
| `0.0.0.0` | 49156 | **HIGH** | Binds all interfaces |

### IPC / Serial

| Interface | Config | Permissions | Notes |
|-----------|--------|-------------|-------|
| `/dev/ttyS1` | 1 Mbps, HDLC v16 | `root:root` | 20 IPC channels to VIP MCU |
| `/dev/ttyACM1` | Debug UART | `crw-rw-rw-` | **World-writable** debug serial — any process can read/write |

### I2C Buses

| Bus | Permissions | Usage |
|-----|-------------|-------|
| i2c-0 | **World-writable** | General purpose (includes EEPROM at 0x50) |
| i2c-1 | **World-writable** | General purpose |
| i2c-2 | **World-writable** | System bus |
| i2c-3 | **World-writable** | Audioserver bus |

### GPIO (World-Writable)

| GPIO | Function | Risk |
|------|----------|------|
| gpio458 | MCU control | MCU reset/boot mode manipulation |
| gpio460 | MCU control | MCU reset/boot mode manipulation |
| gpio464 | MCU control | MCU reset/boot mode manipulation |
| gpio466 | MCU control | MCU reset/boot mode manipulation |

These GPIOs control MCU reset and boot mode pins. World-writable access means any user-space process can force the VIP MCU into reset or bootloader mode.

### CAN / UDS Diagnostic Services

| Service ID | Function |
|------------|----------|
| `$10` | Diagnostic Session Control |
| `$22` | Read Data By Identifier |
| `$27` | Security Access (seed-key) |
| `$2E` | Write Data By Identifier |
| `$31` | Routine Control |
| `$34` | Request Download |
| `$36` | Transfer Data |
| `$37` | Request Transfer Exit |

- **CSM (Cluster/Switch Module):** CAN address `0x80`
- **CGM (Central Gateway Module):** CAN address `0x45`

---

## Y177 Attack Vectors (SELinux Permissive)

With SELinux in permissive mode on Y177, the following attack vectors become available that are blocked on Y175/Y181:

### Direct Hardware Access

- **I2C EEPROM manipulation:** Shell-level access to I2C buses allows direct read/write of EEPROM security flags (`0x0440`, `0x0A80`). With SELinux permissive, no domain transitions or access checks prevent `i2cset`/`i2cget` from any process context.
- **GPIO MCU control:** World-writable GPIOs (458/460/464/466) can be toggled from any process to force VIP MCU into reset or bootloader mode, bypassing the security function entirely.
- **Debug serial access:** `/dev/ttyACM1` with `crw-rw-rw-` permissions allows any process to interact with the debug UART. SELinux enforcing would restrict access to authorized domains only.

### Kernel Exploitation

- **All listed CVEs are exploitable:** Without SELinux enforcing, neverallow rules are not applied. Kernel exploits (CVE-2024-53104, CVE-2024-36971, CVE-2024-53150, CVE-2024-53197) can achieve arbitrary code execution without MAC containment.
- **BPF verifier bypass (CVE-2023-2163):** CAP_BPF is the only remaining barrier. SELinux enforcing would additionally restrict BPF access to authorized domains.
- **ptrace unrestricted:** Without `CONFIG_SECURITY_YAMA` and with SELinux permissive, any process can ptrace any other process of the same UID, enabling runtime code injection and credential theft.

### Persistence

- **EEPROM SBI flag modification:** Write `0xFF` to both `0x0441` and `0x0A81` to persist security bypass across reboots. OTA updates will reset these flags, but manual re-application is trivial with I2C access.
- **Debug mode activation:** Write `0x01` to `0x0B41` to enable debug mode persistently.

---

## SELinux Denial Analysis (Y181)

The 462 SELinux denials observed in Y181 logcat are concentrated in three subsystems:

- **CBC** (Camera/Backup Camera) — access to hardware resources
- **RVC** (Rear View Camera) — video pipeline permissions
- **SXM** (SiriusXM satellite radio) — service communication

Notably, **no USB or CarPlay-related denials** were observed, indicating that the USB host stack and CINEMO/NME CarPlay framework operate within their assigned SELinux domains without policy violations.
