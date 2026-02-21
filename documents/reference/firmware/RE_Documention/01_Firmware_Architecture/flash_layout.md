# CPC200-CCPA Flash Layout & Memory Map

**Purpose:** Document flash partition layout and memory mapping
**Analysis Date:** 2026-01-29
**Device:** Carlinkit A15W (Freescale i.MX6 UltraLite)

---

## Flash Partition Layout

### MTD Partitions (from `/proc/mtd`)

| Partition | Device | Offset | Size | Filesystem | Purpose |
|-----------|--------|--------|------|------------|---------|
| mtd0 | /dev/mtd0 | 0x000000 | 256KB | Raw | U-Boot bootloader |
| mtd1 | /dev/mtd1 | 0x040000 | 3328KB | Raw | Linux kernel (encrypted) |
| mtd2 | /dev/mtd2 | 0x380000 | 12800KB | JFFS2 | Root filesystem |

**Total Flash:** 16MB QSPI NOR (21e0000.qspi)

### Boot Command Line

```
console=ttyLogFile0 root=/dev/mtdblock2 rootfstype=jffs2
mtdparts=21e0000.qspi:256k(uboot),3328K(kernel),12800K(rootfs)
rootwait quiet rw
```

---

## U-Boot Analysis (mtd0.bin)

### Key Strings Found

| Offset | String | Purpose |
|--------|--------|---------|
| 0x1641e | `UPGkey: %s` | Upgrade key logging |
| 0x1643b | `do_decrypt_decompress ret=%d dstLen=%d` | Decryption function |
| 0x17483 | `heweiencrypt` | HeWei encryption marker |
| 0x16484 | `Can't start, Bad Keys.` | Key validation error |

### U-Boot Environment

```
bootdelay=0
bootcmd=run norboot; ...
norargs=setenv bootargs console=ttymxc0,115200 root=/dev/mtdblock3 rootfstype=jffs2 ...
norboot=echo Booting from nor flash ...;run norargs;sf probe 0;sf read 0x80800000 0x100000 0x4F0000;sf read 0x83000000 0x5F0000 0x10000;bootz 0x80800000 - 0x83000000
```

### Boot Process

1. U-Boot loads from QSPI flash offset 0x0
2. Reads kernel from flash at 0x100000 (1MB) to RAM at 0x80800000
3. Reads DTB from flash at 0x5F0000 to RAM at 0x83000000
4. Executes `bootz 0x80800000 - 0x83000000`

---

## Kernel Analysis (mtd1.bin)

| Property | Value |
|----------|-------|
| Location | mtd1 (/dev/mtd1) |
| Size | 3,407,872 bytes (3.3MB) |
| Flash Offset | 0x40000 (256KB from start) |

The kernel is encrypted with a different key than firmware images. For kernel encryption analysis, see `03_Security_Analysis/kernel_encryption.md`.

### Running Kernel Info

```
Linux version 3.14.52+g94d07bb (hcw@ubuntu) (gcc version 4.9.2 (GCC))
#12 SMP PREEMPT Fri Sep 26 16:45:10 CST 2025
```

---

## Memory Map (from running system)

### Physical Memory Layout

| Range | Size | Purpose |
|-------|------|---------|
| 0x80008000-0x8058b327 | ~5.7MB | Kernel code |
| 0x805c0000-0x806684c7 | ~0.6MB | Kernel data |

### Key I/O Regions

| Address | Device |
|---------|--------|
| 0x02020000 | UART (serial) |
| 0x02184000 | USB OTG |
| 0x02190000 | MMC/SD (mmc0) |
| 0x021e0000 | QSPI Flash |
| 0x02280000 | DCP (crypto engine) |

---

## Crypto Hardware

### MXS-DCP Engine

| Path | Purpose |
|------|---------|
| /sys/bus/platform/devices/2280000.crypto | DCP crypto accelerator |
| /sys/bus/platform/drivers/mxs-dcp | DCP driver |

### Available Crypto Algorithms (from `/proc/crypto`)

- stdrng (kernel RNG)
- michael_mic (TKIP MIC)
- ecb(arc4) (WEP)
- AES (via DCP hardware)

---

## Kernel Symbols

**48,959 symbols extracted** from `/proc/kallsyms`

### Key Symbols

| Address | Symbol |
|---------|--------|
| 0x80008200 | `_stext` (kernel start) |
| 0x80008200 | `asm_do_IRQ` |
| 0x80008500 | `secondary_startup` |
| 0x80008700 | `do_one_initcall` |
| ... | (48,959 total) |

---

## Flash Dump Files

Dumped to `/mnt/UPAN/flash_dump/`:

| File | Size | Contents |
|------|------|----------|
| mtd0.bin | 256KB | U-Boot bootloader |
| mtd1.bin | 3.3MB | Encrypted kernel |
| mtd2.bin | 12.5MB | JFFS2 rootfs |
| kallsyms_full.txt | ~2MB | Kernel symbols |
| cmdline.txt | 138B | Boot command line |
| cpuinfo.txt | 352B | CPU information |
| modules.txt | 187B | Loaded modules |

---

## Kernel Decryption Strategy

### Option 1: U-Boot Analysis
- Locate `do_decrypt_decompress` function
- Extract key from code or environment
- Replicate decryption algorithm

### Option 2: Runtime Extraction
- Requires `/dev/mem` access (currently blocked by CONFIG_STRICT_DEVMEM)
- Could use kernel module to bypass restriction
- Dump from physical address 0x80008000

### Option 3: JTAG
- i.MX6UL has JTAG interface
- Could halt CPU and dump RAM
- Requires hardware access

---

## References

- Device: Carlinkit A15W
- SoC: Freescale i.MX6 UltraLite
- Flash dump date: 2026-01-29
- Kernel version: 3.14.52+g94d07bb
