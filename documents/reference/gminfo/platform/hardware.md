# GM Info 3.7 Hardware Specifications

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** December 2025 - February 2026

---

## Hardware Component Summary

| Component | Spec |
|-----------|------|
| CPU | Intel Atom x7-A3960 (Goldmont/Apollo Lake), 4 cores, base 800MHz / boost 2.4GHz, 14nm, 10W TDP, AEC-Q100 |
| CPU Part# | LH8066803226000 |
| CPU ID | "IoT CPU 1.0" (cpu family 6, model 92, stepping 10) |
| CPU Clock | 1881.6 MHz (hypervisor-visible), BogoMIPS 3763.20 |
| CPU Cache | 1024 KB L2 per core |
| CPU Address | 39-bit physical, 48-bit virtual |
| DalvikVM ISA | x86_64, variant: silvermont |
| GPU | Intel HD Graphics 505 (Gen9, 18 EUs, 300-750MHz) |
| GPU Driver | Intel iHD driver 2.0.0 (GLES backend — Vulkan driver exists but NOT used at runtime) |
| RAM | 6 GB DDR3L physical (5,663,432 kB / 5.40 GB visible to kernel; ~604 MB reserved by GHS hypervisor). No swap (SwapTotal=0). Committed_AS: ~76 GB virtual (heavy overcommit) |
| Storage | 64GB Samsung KLMBG2JETD-B041 eMMC |
| SPI Flash | IS25LP016 SPI NOR, 16Mbit/2MB, SOIC8, Quad SPI 3.3V, week 43/2022. Purpose: crash/diagnostic logs or calibration backup |
| Display | Chimei Innolux DD134IA-01B, 2400x960 @ 60Hz, ~13.4" |
| Display DPI | Physical: xDpi=192.911 yDpi=193.523; system lcd_density=200 (xhdpi) |
| Touchscreen | Atmel maXTouch, 16-point multitouch, I2C bus 7 @ 0x4B |
| OpenGL ES | 3.2 (Mesa 21.1.5) |
| Kernel | Linux 4.19.305 LTS (Y181) / 4.19.283 (Y175) |
| VIP MCU | Renesas RH850/P1M-E (TM52176) — power, CAN, early boot, EEPROM |
| Hypervisor | GHS INTEGRITY IoT 2020.18.19 MY22-026 (Type-1 bare-metal) |
| WiFi | Broadcom BCM (802.11ac), driver `dhd` |
| Bluetooth | 5.0 |
| Ethernet | Intel I211, 1Gbps, gPTP master |
| Audio HAL | Harman "Titan" HarmanAudioControl (vendor.hardware.audio@5.0), Dirana3 amplifier plugin, speakerNum=4, micNum=0 |
| Audio Transport | Ethernet AVB to NXP TDF8532 codec → external amplifier (CSM) |
| GPS | u-blox receiver, GPS+DR fusion, UART to GENIVI pipeline |
| EEPROM | ST M24C64, 8KB, I2C |

---

## CPU Details

Kernel identifies the processor as **"IoT CPU 1.0"** (cpu family 6, model 92, stepping 10). The hypervisor-visible reported clock is **1881.6 MHz** with a BogoMIPS of **3763.20**. Each core has **1024 KB** of L2 cache. Address space is **39-bit physical, 48-bit virtual**.

The DalvikVM ISA variant is **silvermont** (x86_64 architecture, 64-bit only).

### CPU Flags

- **Virtualization:** `hypervisor` flag confirms GHS virtualization visible to Linux guest
- **Hardware Crypto:** `aes`, `sha_ni`, `rdrand`, `rdseed`
- **Spectre Mitigations:** `ibrs`, `ibpb`, `stibp`, `ssbd`
- **Known CPU Bugs:** `spectre_v1`, `spectre_v2`, `spec_store_bypass`

---

## Display Details

- **Panel PnP ID:** "CMN" (Chimei/Innolux), product ID 41268, manufactured week 36/2020
- **VSYNC:** 16.666 ms
- **Presentation deadline:** 14.666 ms
- **HWC:** 2.1 (iahwcomposer — Intel Automotive HWComposer), explicit sync, high quality scaling, no DCIP3
- **Composition:** DEVICE + CLIENT composition, triple buffering, only 1 DRM display active (3 defined in hwc_display.ini)
- **System UI:** Left bar 189px, Top/Bottom decor 95px each → App area 1416x770 (windowed), 2400x960 (full)
- **GM CarPlay windowed area:** 1416x842 (after subtracting bars), rendered at 30fps
- **HDR:** No HDR, no wide color gamut, no VRR (single 60Hz mode)
- **Max luminance:** 500 nits
- **Backlight:** min=0.035, max=1.0, default=0.398
- **Density:** 1.25 (200 DPI / 160 base)
- **Flags:** FLAG_SECURE and FLAG_SUPPORTS_PROTECTED_BUFFERS supported
- **SurfaceFlinger:** GLES backend, no blur support

---

## I2C Bus Topology

| Bus | Permissions | Owner | Use |
|-----|-------------|-------|-----|
| i2c-0 | crw-rw-rw- | root | EEPROM (M24C64 at 0x50) |
| i2c-1 | crw-rw-rw- | root | ELMOS antenna controller |
| i2c-2 | crw-rw-rw- | system | General system |
| i2c-3 | crw-rw-rw- | audioserver | TDF8532 codec control |
| i2c-4 to i2c-6 | crw------- | root | Reserved |
| i2c-7 | crw-rw---- | system | Atmel maXTouch (0x4B) touchscreen |
| i2c-8 to i2c-10 | crw------- | root | Reserved |

**Security note:** i2c-0 and i2c-1 are world-writable, which is a potential security concern (EEPROM and antenna controller accessible by any process).

**Additional devices:** Faceplate controller at 0x12. GYRO/ACCEL sensors not present.

---

## Dual-Processor Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Intel Atom x7-A3960 (SoC)                                      │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ GHS INTEGRITY IoT 2020.18.19 MY22-026 (Type-1, bare-metal) │ │
│ │ ┌───────────────────┐  ┌──────────────────────────────────┐ │ │
│ │ │ GHS Tasks (14+)   │  │ Android 12 (Guest VM)            │ │ │
│ │ │ AVB verify        │  │ AAOS + Harman + GM services      │ │ │
│ │ │ kethernet TCP/IP  │  │ CarPlay (CINEMO/NME)             │ │ │
│ │ │ IPU4 camera       │  │ Android Auto (AOSP)              │ │ │
│ │ │ TEE keymaster     │  │ OnStar / Diagnostics             │ │ │
│ │ │ GHS cal service   │  │                                  │ │ │
│ │ │ GHS logger        │  │                                  │ │ │
│ │ └───────────────────┘  └──────────────────────────────────┘ │ │
│ └─────────────────────────────────────────────────────────────┘ │
└─────────────────┬───────────────────────────────────────────────┘
                  │ HDLC over UART /dev/ttyS1 (19 IPC channels, protocol v16)
┌─────────────────▼───────────────────────────────────────────────┐
│ Renesas RH850/P1M-E (TM52176) — VIP MCU                        │
│ Power management, CAN gateway, early boot, EEPROM, PLC timers  │
└─────────────────────────────────────────────────────────────────┘
```

The system uses a dual-processor architecture where the Intel Atom x7-A3960 SoC handles all application-level processing under a GHS INTEGRITY Type-1 hypervisor, while the Renesas RH850/P1M-E VIP MCU manages low-level vehicle functions including power sequencing, CAN bus gateway, early boot, EEPROM access, and PLC timers.

Communication between the two processors occurs over HDLC on UART `/dev/ttyS1` with 19 IPC channels using protocol version 16. The GHS hypervisor runs as bare-metal on the Intel SoC, with Android 12 running as a guest VM alongside 14+ native GHS tasks for AVB verification, Ethernet networking, camera processing, TEE keymaster, calibration services, and logging.

---

## VIP MCU (Renesas RH850/P1M-E)

### PLC Timers

| Timer | Duration |
|-------|----------|
| SoH Timeout | 1500 ms |
| Shutdown | 3500 ms |
| Startup | 6000 ms |
| Unmount | 800 ms |
| Hypervisor Startup | 300 ms |

### VIP Calibration Values

31+ values stored in EEPROM/NVM, applied at every boot:

**Power:**
- `cal_background_sleep_timeout_sec` = 10
- `cal_sleep_fault_timeout_sec` = 900
- `cal_startup_fault_timeout_sec` = 15
- `cal_suspend_to_ram_en` = 1
- `cal_str_min_temp_threshold` = -10

**Infotainment:**
- `cal_veh_initiated_local_infotainment_timeout` = 600
- `cal_user_initiated_local_infotainment_timeout` = 600

**Display:**
- `cal_cluster_animation_ignore` = 0
- `cal_rsi_animation_ignore` = 1

**Debug:**
- `cal_vin_relearn_en` = 1
- `cal_maximum_flush_fault_time` = 36000

### VIP Calibration Index System

15 indexed groups sent VIP → SoC at every boot:

| Index | Size | Description |
|-------|------|-------------|
| 0 | 154 B | System config |
| 1 | 128 B | Audio/chime |
| 2 | 17 B | Mic |
| 3 | 21 B | DTC mask |

---

## IPC Protocol Details

| Parameter | Value |
|-----------|-------|
| Version | 4.1.0 |
| API | 0404 |
| Framing | HDLC |
| Window Size | 5 |
| IFRAME Timeout | 50 ms |
| UFRAME Timeout | 20 ms |
| Serial | 115200 8N1 |

**Module init order:** SS_SWC → PLC → J6_CDD → IPC_S → AMP_MGR_SWC → PROTOKEY → SBAT → IOHWAB_MIC → NAV

---

## GHS Hypervisor Details

### Binary Format

GHS INTEGRITY binary: ELF 32-bit LSB (Intel 80386), statically linked — GHS runs in 32-bit compatibility mode. VMM uses Intel VT-x with 64-bit EPT for Android guest.

### GHS Device Interfaces

Accessible under `/dev/ghs/`:

| Device | Purpose |
|--------|---------|
| ipc | Inter-processor communication |
| cal | Calibration service |
| chime | Turn signal/seatbelt/door chime generation |
| camera (ghs_camera_device) | Backup camera bypass |
| tee-att | TEE attestation |
| audit | Security audit log |
| textlog | Text logging |
| snapshot-dbg | Debug snapshots |
| emmc-health | eMMC health monitoring |
| ota-isys | OTA update interface |
| gpu-dbg | GPU debug |

### GHS Camera Bypass

Backup camera works **DURING Android reboot**. GHS receives CAN gear position and directly controls display overlay, independent of the Android guest. The `earlyEvs_harman` module exists but is **COMMENTED OUT** in the configuration — GHS handles camera directly without it.

---

## Kernel Configuration Highlights

| Category | Setting |
|----------|---------|
| Preemption | PREEMPT=y (full preemptive) |
| Timer | HZ=1000 |
| Scheduler | SCHED_TUNE + FAIR_GROUP_SCHED |
| Titan-specific | TITAN_X86_BROXTON_PROGRAM, APL_MY22_ANDROID, I2C_BUS_NUMBER_HACK |
| VirtIO | BLK / CONSOLE / PCI (GHS guest I/O) |
| DRM | I915 built-in |
| Audio | HDA_CORE=m (module) |
| Compression | LZ4 |
| Compiler | Clang 12.0.7 |

---

## DalvikVM Configuration

| Parameter | Value |
|-----------|-------|
| heapsize | 512m |
| heapgrowthlimit | 288m |
| dex2oat-threads | 2 (cores 0, 1) |
| usejit | true |
| zygote | zygote64 (64-bit only, no 32-bit) |

---

## System Properties

| Property | Value |
|----------|-------|
| persist.sys.cal.brand | GM_Brand_Chevrolet |
| persist.sys.cal.model | Silverado |
| ro.boot.product.hardware.sku | gv221 |
| persist.sys.wifi.only2g | 1 |
| config.disable_cameraservice | 1 |
| ro.radio.noril | true |

---

## Vehicle Integration

### GHS-Dependent (work without Android)

These functions operate at the hypervisor/VIP level and remain functional even if Android is rebooting or crashed:

- Turn signal chimes
- Door chimes
- Seatbelt chimes
- Reverse backup camera
- 360 surround view

### Android-Dependent

These functions require the Android guest VM to be running:

- Climate display
- Steering wheel button processing
- Navigation
- Audio playback / media
- CarPlay / Android Auto

---

## Vehicle Identification

| Field | Value |
|-------|-------|
| Vehicle | 2024 Chevrolet Silverado (ICE) |
| GMModel | 4 |
| GMBrand | 3 (Chevrolet) |
| GMTrim | 16 |
| Build | W231E-Y181.3.2-SIHM22B-499.3 |
| Hardware SKU | gv221 |
| Build Fingerprint | gm/full_gminfo37_gb/gminfo37:12/W231E-Y181.3.2-SIHM22B-499.3/231:user/release-keys |
