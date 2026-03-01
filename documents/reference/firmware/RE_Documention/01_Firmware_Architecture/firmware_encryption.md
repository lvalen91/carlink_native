# CPC200-CCPA Firmware Encryption

**Purpose:** Reverse engineering of Carlinkit .img firmware format
**Consolidated from:** pi-carplay firmware analysis (January 2026)

---

## Important: This is Separate from USB Encryption

**This document covers FIRMWARE IMAGE encryption** - the protection of `.img` update files.

This is **completely separate** from USB communication encryption (CMD_ENABLE_CRYPT):

| Encryption Type | Algorithm | Key | Purpose |
|-----------------|-----------|-----|---------|
| **Firmware .img** (this doc) | AES-128-CBC | `AutoPlay9uPT4n17` | Protect firmware files |
| USB Communication | AES-128-CTR | `W2EC1X1NbZ58TXtn` | Encrypt runtime USB traffic |

See `03_Security_Analysis/crypto_stack.md` for complete cryptographic stack documentation.

---

## File Format Overview

```
+------------------------------------------------------------------+
|                    AES-128-CBC Encrypted Data                     |
|                   (block-aligned, 16-byte blocks)                 |
+------------------------------------------------------------------+
|  Unencrypted Remainder (0-15 bytes if file size % 16 != 0)       |
+------------------------------------------------------------------+
```

**Decrypted Contents:**
```
.img (encrypted) -> .tar.gz (gzip compressed) -> tar archive containing:
|- etc/
|   |- init.d/rcS
|   |- boa/cgi-bin/
|   |- software_version
|   +- ...
|- lib/
|- script/
|- tmp/
|   |- once.sh        (pre-update script)
|   |- finish.sh      (post-update script)
|   +- remove_unnecessary_file.sh
+- usr/
    +- lib/
```

---

## Encryption Details

### A15W Model

| Property | Value |
|----------|-------|
| **Algorithm** | AES-128-CBC |
| **Key** | `AutoPlay9uPT4n17` |
| **IV** | `AutoPlay9uPT4n17` (same as key) |
| **Key (hex)** | `4175746f506c617939755054346e3137` |
| **Block Size** | 16 bytes |
| **Padding** | None - last partial block left unencrypted |

### Other Model Keys

| Model | Key | Source |
|-------|-----|--------|
| U2W | `CarPlay5KBP6ClJv` | ludwig-v repo |
| U2AW | `CarPlayBbnF6ecFP` | ludwig-v repo |
| U2AC | `CarPlayiHXF1o74i` | ludwig-v repo |
| HWFS modules | `8e15c895KBP6ClJv` | ludwig-v repo |
| **A15W** | `AutoPlay9uPT4n17` | **Extracted from ARMimg_maker** |

### Key Extraction Method

The A15W key was extracted by:

1. Unpacking `ARMimg_maker` using ludwig-v's modified UPX:
   ```bash
   # On the adapter (ARM device)
   /path/to/modified/upx -d /usr/sbin/ARMimg_maker -o /tmp/ARMimg_maker_unpacked
   ```

2. Searching for 16-character alphanumeric strings:
   ```bash
   strings ARMimg_maker_unpacked | grep -E "^[A-Za-z0-9]{16}$"
   ```

---

## Decryption Process

### Using OpenSSL

```bash
AES_KEY="AutoPlay9uPT4n17"
KEY_HEX=$(printf "%s" "$AES_KEY" | od -A n -t x1 | tr -d ' \n')
IV_HEX="$KEY_HEX"

# Calculate block alignment
FILESIZE=$(stat -f %z input.img)  # macOS
# FILESIZE=$(stat -c%s input.img)  # Linux
TRUNCATED=$((FILESIZE - (FILESIZE % 16)))
REMAINDER=$((FILESIZE % 16))

# Decrypt block-aligned portion
head -c $TRUNCATED input.img > temp_enc.bin
openssl enc -d -aes-128-cbc -nopad -K "$KEY_HEX" -iv "$IV_HEX" \
    -in temp_enc.bin -out output.tar.gz

# Append unencrypted remainder
if [ $REMAINDER -gt 0 ]; then
    tail -c $REMAINDER input.img >> output.tar.gz
fi

rm temp_enc.bin
```

### Verification

```bash
# Check for gzip magic bytes (1f 8b)
xxd output.tar.gz | head -1
# Expected: 00000000: 1f8b 0800 ...

# Verify gzip integrity
gunzip -t output.tar.gz

# Extract contents
tar -xzf output.tar.gz -C ./extracted/
```

---

## Encryption Process

To create a custom `.img` from a modified `tar.gz`:

```bash
AES_KEY="AutoPlay9uPT4n17"
KEY_HEX=$(printf "%s" "$AES_KEY" | od -A n -t x1 | tr -d ' \n')
IV_HEX="$KEY_HEX"

FILESIZE=$(stat -f %z input.tar.gz)
TRUNCATED=$((FILESIZE - (FILESIZE % 16)))
REMAINDER=$((FILESIZE % 16))

# Encrypt block-aligned portion
head -c $TRUNCATED input.tar.gz > temp_plain.bin
openssl enc -e -aes-128-cbc -nopad -K "$KEY_HEX" -iv "$IV_HEX" \
    -in temp_plain.bin -out output.img

# Append unencrypted remainder
if [ $REMAINDER -gt 0 ]; then
    tail -c $REMAINDER input.tar.gz >> output.img
fi

rm temp_plain.bin
```

---

## ARMimg_maker Binary Analysis

> **Note:** ARMimg_maker was **removed in firmware 2025.10.15**. The binary is available from all prior firmware versions and can be re-added to custom firmware images. The on-device update flow (`update_box_ota.sh`) calls this binary for decryption, so custom firmware for 2025.10.15+ should include it.

| Property | Value |
|----------|-------|
| **Location** | `/usr/sbin/ARMimg_maker` |
| **Size (packed)** | 21,056 bytes |
| **Size (unpacked)** | 37,696 bytes |
| **Packing** | Modified UPX (Carlinkit-specific) |
| **Availability** | Present in all firmware versions prior to 2025.10.15; removed in 2025.10.15 |

### Key Strings (from unpacked binary)
```
AutoPlay9uPT4n17          # AES key
AES_encrypt               # OpenSSL function
AES_decrypt               # OpenSSL function
AES for ARMv4, CRYPTOGAMS by <appro@openssl.org>
./update.tar.gz           # Output filename
/proc/self/exe
```

### Behavior
1. Reads `.img` file from command line argument
2. Decrypts using AES-128-CBC with hardcoded key
3. Writes decrypted data to `./update.tar.gz` in current directory

---

## Firmware Update Flow

```
USB/OTA Download
      |
      v
/tmp/A15W_Update.img
      |
      v (ARMimg_maker)
/tmp/update.tar.gz
      |
      v (tar -xzf)
/tmp/update/
|- etc/
|- lib/
|- script/
|- tmp/once.sh
+- usr/
      |
      v (once.sh runs)
Files copied to rootfs
      |
      v (finish.sh runs)
Update complete, reboot
```

---

## Creating Custom Firmware

### Step 1: Decrypt Official Firmware
```bash
./FirmwareA15W.sh decrypt A15W_Update.img firmware.tar.gz
```

### Step 2: Extract
```bash
mkdir custom_firmware
tar -xzf firmware.tar.gz -C custom_firmware
```

### Step 3: Modify
```bash
# Example: Ensure dropbear stays enabled
sed -i 's/^#dropbear/dropbear/' custom_firmware/etc/init.d/rcS

# Example: Add custom script
cp my_script.sh custom_firmware/script/
```

### Step 4: Repack
```bash
cd custom_firmware
tar -czf ../custom.tar.gz .
cd ..
```

### Step 5: Encrypt
```bash
./FirmwareA15W.sh encrypt custom.tar.gz A15W_Update.img
```

### Step 6: Deploy
```bash
# Copy to USB drive
cp A15W_Update.img /Volumes/USB_DRIVE/

# Or SCP to adapter's USB mount
scp A15W_Update.img root@192.168.43.1:/mnt/UPAN/
```

---

## Security Considerations

| Issue | Impact |
|-------|--------|
| Hardcoded encryption key | Any adapter can be modified |
| Same key used for key and IV | Weak cryptographic practice |
| No signature verification | Custom firmware accepted without validation |
| Predictable key format | Other models use similar patterns |

---

## Working Firmware Tool

A working shell script `FirmwareA15W.sh` is available for encrypt/decrypt operations:

```bash
#!/bin/bash
# A15W Firmware encrypt/decrypt tool
# Key extracted from ARMimg_maker binary via reverse engineering
#
# Usage:
#   ./FirmwareA15W.sh decrypt A15W_Update.img output.tar.gz
#   ./FirmwareA15W.sh encrypt input.tar.gz A15W_Update.img

AES_KEY="AutoPlay9uPT4n17"
AES_IV="AutoPlay9uPT4n17"

# Convert key to hex
KEY_HEX=$(printf "%s" "$AES_KEY" | od -A n -t x1 | tr -d ' \n')
IV_HEX=$(printf "%s" "$AES_IV" | od -A n -t x1 | tr -d ' \n')

# Get file size (handle macOS vs Linux)
if [[ "$OSTYPE" == "darwin"* ]]; then
    FILESIZE=$(stat -f %z "$INPUT_FILE")
else
    FILESIZE=$(stat -c%s "$INPUT_FILE")
fi

# Calculate block alignment
TRUNCATED=$((FILESIZE - (FILESIZE % 16)))
REMAINDER=$((FILESIZE % 16))

# Decrypt or encrypt based on mode
if [ "$MODE" == "decrypt" ]; then
    head -c "$TRUNCATED" "$INPUT_FILE" > temp.bin
    openssl enc -d -aes-128-cbc -nopad -K "$KEY_HEX" -iv "$IV_HEX" \
        -in temp.bin -out "$OUTPUT_FILE"
    [ "$REMAINDER" -gt 0 ] && tail -c "$REMAINDER" "$INPUT_FILE" >> "$OUTPUT_FILE"
elif [ "$MODE" == "encrypt" ]; then
    head -c "$TRUNCATED" "$INPUT_FILE" > temp.bin
    openssl enc -e -aes-128-cbc -nopad -K "$KEY_HEX" -iv "$IV_HEX" \
        -in temp.bin -out "$OUTPUT_FILE"
    [ "$REMAINDER" -gt 0 ] && tail -c "$REMAINDER" "$INPUT_FILE" >> "$OUTPUT_FILE"
fi
rm -f temp.bin
```

---

## References

- Source: `cpc200_ccpa_firmware_binaries/IMG_FORMAT_ANALYSIS.md`
- Source: `cpc200_ccpa_firmware_binaries/FirmwareA15W.sh`
- Analysis Date: January 2026
- Firmware Version: 2025.10.15.1127
- External: ludwig-v/wireless-carplay-dongle-reverse-engineering
