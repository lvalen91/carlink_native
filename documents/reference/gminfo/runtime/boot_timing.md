# GM Info 3.7 Boot Timing Analysis

**Device:** GM Info 3.7 (gminfo37)
**Platform:** Intel Apollo Lake (Broxton)
**Android Version:** 12 (API 32)
**Research Date:** February 2026
**Evidence:** 3.7GB logcat corpus (27 files, Feb 11-19, 2026), 4.7MB metrics (30 captures), 45MB CPC200 logs

---

## Pre-Kernel Boot Phases (estimated)

| Phase | Description | Time Range |
|-------|-------------|------------|
| Phase 0 | Power On | T=0ms |
| Phase 1 | VIP MCU Boot (Renesas RH850/P1M-E) | T=0-50ms |
| Phase 2 | Intel CSE + ABL (UEFI bootloader) | T=50-200ms |
| Phase 3 | GHS Hypervisor Init (cal_hypervisor_startup_timeout_ms=300) | T=200-500ms |
| Phase 4 | AVB Verification (cal_soc_soh_timeout_msec=1500) | T=500-1500ms |
| Phase 5 | Android Full Boot (kernel start → screen enabled) | T=1500-24000ms |

Estimated total power-on to screen: ~25.5s (1.5s pre-kernel + ~23s Android boot).

## Android Boot Timing (5-Boot Sample)

All times in ms from kernel start. Sources: logcat 20260212 (3 boots), 20260218 (2 boots).

| Stage | Boot1 | Boot2 | Boot3 | Boot4 | Boot5 |
|-------|-------|-------|-------|-------|-------|
| boot_progress_start | 4273 | 4336 | 4412 | 4458 | 4300 |
| preload_start | 5341 | 5564 | 5675 | 5520 | 5411 |
| preload_end | 8609 | 8754 | 8977 | 8849 | 8769 |
| system_run | 9206 | 9379 | 9595 | 9455 | 9437 |
| pms_start | 10105 | 10283 | 10560 | 10349 | 10594 |
| pms_system_scan_start | 11064 | 11234 | 11592 | 11268 | 11718 |
| pms_data_scan_start | 12081 | 12229 | 12514 | 12206 | 12618 |
| pms_scan_end | 12100 | 12249 | 12533 | 12243 | 12652 |
| pms_ready | 12468 | 12582 | 12943 | 12548 | 12993 |
| ams_ready | 15764 | 15682 | 16159 | 16167 | 16531 |
| enable_screen | 22026 | 22903 | 22792 | 23290 | 24149 |

### Key Observations

- **Zygote preload:** 13,900 classes in ~2,900ms, 64 resources in ~42ms
- **Zygote to SystemServer fork:** ~140ms
- **preload_end to enable_screen:** ~13-15s (consistent across all boots)
- **Total kernel-to-screen:** 22.0-24.1s range, ~23s average
- 13 Davey! events during boot (all SystemUI), none during streaming

## Service Start Order (from logcat)

- **Early:** hwservicemanager, lowmemorykiller, vold, installd
- **Mid:** system_server, SurfaceFlinger, AudioFlinger
- **Late:** CarPlay services, OnStar, third-party apps

## Boot Storm

- 513 LMK kills total across 27 logcat files (see memory_pressure.md for details)
- Memory pressure settles after ~30s
- All GM services stable after ~45s
