# GM Info 3.7 Firmware Versions

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** December 2025 - February 2026

---

## Firmware Version Comparison

| Field | Y175 (June 2024) | Y177 (March 2025) | Y181 (July 2025) |
|-------|-------------------|---------------------|-------------------|
| Build | W213E-Y175.5.2-SIHM22B-383.1 | W231E-Y177.6.1-SIHM22B-499.2 | W231E-Y181.3.2-SIHM22B-499.3 |
| Kernel | 4.19.283-PKT-230612T042614Z | 4.19.305 | 4.19.305 |
| Security Patch | 2024-05-05 | ~2025-03 | 2025-06-05 |
| Bootloader | 2121-1 | — | 2344 |
| Built | Wed Jun 26 2024 | — | — |
| SELinux | Enforcing | **Permissive** | Enforcing |
| VIP security fn | Present | **Stubbed (4B)** | Full (906B) |
| VIP firmware diff | — | 28.4% vs Y181 | Baseline |
| Rollback | — | Y181→Y177 blocked | — |

### Key Observations

- **Y177 is a security regression** — both SELinux and VIP security function were weakened compared to Y175 and Y181
- **Kernel upgraded** from 4.19.283 (Y175) to 4.19.305 (Y177/Y181), gaining security patches
- **Rollback protection** prevents downgrading from Y181 back to Y177, enforced by GHS rollback counter in `misc` partition
- **VIP firmware delta** between Y177 and Y181 is 549,518 bytes (28.4%), indicating substantial changes beyond just the security function

### Y175 vs Y181 Behavioral Differences

| Area | Y175 | Y181 |
|------|------|------|
| Ethernet manager | `ethctrlmgr` | `ethmvlctrlmgr` (swapped) |
| Platform prefix | W213E | W231E |
| SIHM variant | 383 | 499 |
| Bootloader | 2121-1 | 2344 |
| DABridge USB topology | 1-12.x | 1-6.x |

---

## VIP Firmware File Details

| Field | Y177 | Y181 |
|-------|------|------|
| VIP_APP part number | 86283151 | 86331656 |
| VIP_APP build | 25Feb28-0330 | 25Jun19-2209 |
| VIP_BOOT | 85056831 | 85056831 (IDENTICAL) |
| SOC_HOSTOS | 85098662 | 85098662 (IDENTICAL) |
| SOC_ABL | 85738845 | 85738845 (IDENTICAL) |

VIP_BOOT, SOC_HOSTOS, and SOC_ABL are **identical** between Y177 and Y181. The security behavioral difference between Y177 and Y181 resides **entirely** in the VIP_APP firmware.

---

## GHS INTEGRITY Details

The GHS INTEGRITY hypervisor is **identical** between Y177 and Y181:

- **Version:** INTEGRITY IoT 2020.18.19 MY22-026
- **SOC_HOSTOS:** 14.9MB ELF 32-bit x86 statically linked
- **Build path:** `/home/mal/gm_release/MY22-026/final/iot/rtos/`

The security difference between Y177 and Y181 is entirely in VIP firmware — the hypervisor layer is unchanged.

---

## DPS / CalDef (Two Separate Systems)

The GM Info 3.7 maintains two distinct calibration systems:

### CalDef — Android/SoC Calibration

- Calibration entries stored on the system partition
- Manages Android-level configuration and feature flags
- Part of the OTA-updated system image

#### Security Calibrations

| CalDef Key | Type | Value |
|-----------|------|-------|
| DEVICE_REGISTRATION_ENABLE_CHECK_OVERRIDE | bool | FALSE |
| EXPIRATION_ENFORCEMENT | bool | TRUE |
| GIS763_PROGRAMMING_ENABLED | bool | TRUE |
| GIS763_SigningCertificateIssuer | string | CN=GPD/OU=GMNA GPD PRODUCTION/O=GENERAL MOTORS LLC |

### VIP Calibrations

- 31 `cal_` values stored in the RH850 VIP MCU firmware
- Controls power management, CAN parameters, and timing values
- Updated independently from Android OTA

### DPS (Data Protection Service)

- Uses **AES-CMAC** for authentication
- **Server-based key provisioning** — keys are not stored locally in plaintext
- DPS **cannot write EEPROM directly** — EEPROM writes go through the VIP MCU's I2C interface
- Handles license verification and calibration integrity

#### DPS Module IDs and Signing

**TSS-signed modules:**

| Module ID | Name |
|-----------|------|
| 1 | VIP_APP |
| 21 | SOC_HOSTOS |
| 22 | SOC_SYSTEM |
| 26 | SOC_VENDOR |

**Unsigned modules:**

| Module ID | Name |
|-----------|------|
| 23 | SOC_BOOT |
| 24 | SOC_UTILS |
| 28 | SXM |
| 29 | GPS |
| 51 | TUNER |
| 52 | ETH_SWITCH |
| 55 | SOC_ACPIO |
| 56 | SOC_VBMETA |
| 57 | SOC_PRODUCT |
| 71 | VIP_BOOT |
| 72 | SOC_ABL |

#### UDS Diagnostic Services

DPS communicates via UDS (Unified Diagnostic Services):

| Service ID | Name | Notes |
|-----------|------|-------|
| $10 | DiagnosticSessionControl | Session management |
| $11 | ECUReset | Reset ECU |
| $22 | ReadDataByID | Read calibration/data |
| $27 | SecurityAccess | 32-byte seed: ECUID(16B) + random(16B) |
| $2E | WriteDataByID | Write calibration/data |
| $31 | RoutineControl | Start/stop/query routines |
| $34 | RequestDownload | Initiate firmware download |
| $36 | TransferData | Transfer firmware blocks |
| $37 | TransferExit | Complete firmware transfer |

---

## Build Identification

| Field | Value |
|-------|-------|
| Build Fingerprint | gm/full_gminfo37_gb/gminfo37:12/W231E-Y181.3.2-SIHM22B-499.3/231:user/release-keys |
| Hardware SKU | gv221 |
| Device | gminfo37 |
| Product | full_gminfo37_gb |
| Build Type | user |
| Tags | release-keys |

---

## Version Naming Convention

GM firmware versions follow the pattern: `W[platform]-Y[version].[minor].[patch]-[variant]-[build]`

- **W213E** — Platform identifier (213 = Y175 hardware generation, E = variant)
- **W231E** — Platform identifier (231 = Y177/Y181 hardware generation, E = variant)
- **Y181** — Major version (Y-series, version 181)
- **3.2** — Minor and patch version
- **SIHM22B** — Build variant identifier
- **499.3** — Build number (383.x for Y175, 499.x for Y177/Y181)
