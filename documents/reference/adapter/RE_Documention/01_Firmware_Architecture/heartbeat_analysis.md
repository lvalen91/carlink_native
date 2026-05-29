# CPC200-CCPA Heartbeat Mechanism

**Source:** Binary analysis of `ARMadb-driver_unpacked` + practical testing
**Firmware:** 2025.10.XX
**Last Updated:** 2026-01-20

---

## Executive Summary

The heartbeat mechanism serves as a **connection supervision watchdog**. The firmware monitors incoming heartbeat messages and resets the USB connection if no heartbeat is received within the timeout window.

**Key Findings:**
- **Three timeout values documented:**
  - Binary constant: **15,000ms** (0x3a98 at address 0x21112)
  - Practical testing: **~10 seconds** from USB connection
  - Observed disconnect: **~11.7 seconds** with `SendHeartBeat=1`
- Testing shows **~10 seconds is when to expect issues**, even though binary shows 15s. Design for 10-second budget.
- Heartbeat timer must start **BEFORE** initialization messages, but first heartbeat sent **AFTER interval** (not immediately)
- Recommended interval: **2000ms** (provides multiple heartbeats within timeout window)
- **Both initialization AND first heartbeat must complete within ~10 seconds of USB connection**

---

## Critical Implementation Requirement

### Cold Start Timing (CRITICAL)

**Problem discovered January 2026:** The firmware watchdog timer starts when USB connection is established. Both initialization AND the first heartbeat must complete within ~10 seconds.

| Sequence | First Heartbeat Timing | Result |
|----------|------------------------|--------|
| **Wrong** | Immediately at t=0 (before init) | May confuse firmware, unstable |
| **Wrong** | After init completes + 2s delay | May exceed watchdog timeout |
| **Correct** | Timer starts before init, first HB fires after interval | Stable |

**Correct initialization sequence (observed in capture sessions):**
```
1. USB Reset
2. 3-second mandatory wait
3. Open USB connection
4. START HEARTBEAT TIMER (do NOT send immediately)  ← Timer starts, first HB in 2s
5. Send initialization messages (~1.5s total)
6. Start reading loop
7. First heartbeat fires at t=2000ms (after init complete)
```

**Timeline for correct implementation:**
```
t=0ms     → USB connection opened, heartbeat timer started
t=0ms     → Begin sending init messages
t=~1500ms → Init messages complete
t=2000ms  → FIRST heartbeat sent (timer fires)
t=4000ms  → Second heartbeat
...
```

**Why this works:**
- The firmware watchdog expects activity within ~10 seconds of USB connection
- Init messages provide activity during the first ~1.5 seconds
- First heartbeat arrives at t=2s, well within the timeout
- Subsequent heartbeats maintain the connection

**Why sending heartbeat immediately (t=0) is WRONG:**
- Sending heartbeat before any init messages may confuse the firmware
- The adapter may not be ready to process heartbeat before session parameters (Open message)
- Creates race conditions between heartbeat and init message processing

| Condition | Implementation | Result |
|-----------|----------------|--------|
| Cold Start | Timer before init, first HB after interval | Stable |
| Warm Reconnect | Same pattern | Stable |

---

## Message Format

```
Header (16 bytes, no payload):
+------------------+------------------+------------------+------------------+
|   0x55AA55AA     |   0x00000000     |   0x000000AA     |   0xFFFFFF55     |
|   (magic)        |   (length=0)     |   (type=170)     |   (type check)   |
+------------------+------------------+------------------+------------------+

Hex: AA 55 AA 55 00 00 00 00 AA 00 00 00 55 FF FF FF
```

- **Type:** 0xAA (170 decimal)
- **Payload:** None (header-only message)
- **Direction:** Host → adapter only on FW `2025.10.15.1127CAY` — see note below

> **Capture-verified 2026-04-20** (macOS CarLink client, FW `2025.10.15.1127CAY`, 1452s session): **716 TX heartbeats, 0 RX heartbeats**. The adapter does not echo 0xAA on this firmware. The "bidirectional" description in earlier revisions of this doc was aspirational (both sides *could* send, per binary support) — in practice on 2025.10.XX only the host emits them. Adapter-origin heartbeats have not been observed in any capture session 2026-02 → 2026-04. Treat 0xAA as host→adapter one-way for current firmware.

---

## Timeout Values (Hardcoded in Firmware)

| Constant | Hex | Decimal | Address | Purpose |
|----------|-----|---------|---------|---------|
| Host No Response | `0x3a98` | **15,000 ms** | 0x21112 | Max gap before connection reset |
| Send to Host | `0x1194` | **4,500 ms** | 0x210a0 | USB write failure timeout |
| Timing Unit | `0x3e8` | **1,000 ms** | 0x18e6e | Base timing calculation |
| Min Spacing | `0x1f4` | **500 ms** | 0x18e82 | Minimum message spacing |

**Practical Observation (January 2026):**
Three different timeout values exist in documentation:
- **Binary constant:** 15,000ms (0x3a98) at address 0x21112
- **Practical testing:** Watchdog triggers at ~10 seconds from USB connection
- **Observed behavior:** 11.7s disconnect when heartbeat is late

Testing has shown that **~10 seconds is when to expect issues**. The discrepancy between the binary 15s value and practical 10s observation suggests the timer may start before the first message is received, or there's additional overhead in the USB stack. **Design for a 10-second budget to be safe.**

---

## Recommended Intervals

| Interval | First HB Time | Within 10s Budget? | Safety Margin |
|----------|---------------|-------------------|---------------|
| 1000 ms | t=1000ms | ✅ Yes | High |
| **2000 ms** | t=2000ms | ✅ Yes | **Good (recommended)** |
| 3000 ms | t=3000ms | ✅ Yes | Acceptable |
| 5000 ms | t=5000ms | ✅ Yes | Minimal |
| 8000 ms | t=8000ms | ⚠️ Marginal | Risky |
| 10000 ms | t=10000ms | ❌ No | **Will fail** |

**Calculation:** Init takes ~1.5s, so first heartbeat at interval X arrives at t=X. Must be < 10s.

Timer constants at `0x71150` contain value `0x07d0` (2000ms), suggesting this as the intended interval.

---

## Binary Analysis

### Timeout Check Logic (0x21108-0x21118)

```assembly
0x21104  blx   dbus_connection_unref
0x21108  ldr.w r3, [sb]           ; Load last heartbeat timestamp from [sb]
0x2110c  mov   r7, r0             ; r7 = current time (from previous call)
0x2110e  cbz   r3, 0x2111c        ; If no timestamp, skip to auto-detect
0x21110  subs  r3, r7, r3         ; r3 = elapsed = current_time - last_heartbeat
0x21112  movw  r2, 0x3a98         ; r2 = 15000 milliseconds
0x21116  cmp   r3, r2             ; Compare elapsed vs 15000
0x21118  bls.w 0x20c50            ; Branch if elapsed <= 15000 (OK)
                                   ; Fall through if elapsed > 15000 (TIMEOUT)
```

**Pseudocode:**
```c
elapsed_time = current_time - last_heartbeat_received;
if (elapsed_time > 15000) {
    log("Host No Response, we will reset connection!!!");
    reset_connection();
}
```

### Send to Host Timeout (0x210a0-0x210ae)

```assembly
0x210a0  movw  r2, 0x1194         ; r2 = 4500 milliseconds
0x210a4  vcvt.s32.f64 s15, d7     ; Convert timing value
0x210a8  vmov  r3, s15
0x210ac  cmp   r3, r2             ; Compare elapsed vs 4500
0x210ae  ble   0x21032            ; Branch if <= 4500 (OK)
                                   ; Fall through if > 4500 (TIMEOUT)
```

### Key Functions

| Address | Function | Purpose |
|---------|----------|---------|
| `0x18088` | Message pre-processor | Validates USB header and magic bytes |
| `0x18244` | Decrypt/validate | Processes incoming messages |
| `0x18e2c` | Message dispatcher | Routes by type (0xAA → heartbeat handler) |
| `0x21080` | Timeout checker | Checks elapsed time, triggers reset |
| `0x6327a` | D-Bus dispatch | Emits `HUDComand_A_HeartBeat` signal |

### D-Bus Signal Flow

When heartbeat (type 0xAA) is received:
1. Header validated at `0x18088`
2. Decrypted/validated at `0x18244`
3. Routed at `0x18e2c`
4. D-Bus signal emitted at `0x6327a`:

```assembly
0x6327a  ldr   r4, [0x6334c]      ; Load "HUDComand_A_HeartBeat" string ptr
0x6327c  b     0x63362            ; Jump to D-Bus emit routine
```

---

## Configuration

### SendHeartBeat

**Config key:** `SendHeartBeat` in `/etc/riddle.conf`
**String location:** `0x6e515`
**Config table:** `0x71480`

```
Offset 0x71480: 1e 00 00 00  01 00 00 00  1e 00 00 00  15 e5 07 00
                ^^^^^^^^^^   ^^^^^^^^^^   ^^^^^^^^^^   ^^^^^^^^^^^
                min=30       default=1    max=30       -> string ptr
```

- **Default:** 1 (enabled)
- **Type:** Boolean

**When disabled (`riddleBoxCfg SendHeartBeat 0`):**
1. Outbound heartbeat emission stops (D-Bus signal not dispatched)
2. Timeout monitoring **continues** (15-second check still runs)
3. Host must still send heartbeats or connection resets
4. Effect is bidirectional - both sides need heartbeats

### HNPInterval

**Config key:** `HNPInterval` (Host No Pong Interval)
**String location:** `0x6e490`
**Config table:** `0x713b0`

Likely controls the timeout period, but 15-second value appears hardcoded at `0x21112`.

---

## Timeout Messages (Firmware Strings)

| File Offset | Runtime Addr | Message |
|-------------|--------------|---------|
| `0x5d530` | `0x6d530` | `Host No Response, we will reset connection!!!` |
| `0x5d56f` | `0x6d56f` | `Send to Host timeout, we will reset connection!!!` |
| `0x5d55f` | `0x6d55f` | `tickPass %d ms` |
| `0x5d605` | `0x6d605` | `Need reset every time when timeout!!!` |

### String Locations

| Binary | Offset | String |
|--------|--------|--------|
| ARMadb-driver_unpacked | `0x5b583` | `HUDComand_A_HeartBeat` |
| ARMadb-driver_unpacked | `0x6e515` | `SendHeartBeat` |
| ARMadb-driver_unpacked | `0x6e490` | `HNPInterval` |
| riddleBoxCfg_unpacked | `0x18f54` | `SendHeartBeat` |

---

## Timer Constants (0x71150)

```
Offset 71150: e8 03 00 00  2c 01 00 00  d0 07 00 00
              ^^^^^^^^^^   ^^^^^^^^^^   ^^^^^^^^^^
              1000 ms      300 ms       2000 ms
```

The 2000ms value aligns with the recommended heartbeat interval.

---

## Design Rationale

Based on firmware analysis, heartbeat serves four purposes:

1. **USB Connection Supervision** - USB bulk transfers can become stale without application awareness
2. **Crash Detection** - Detects if host application has crashed or frozen
3. **Resource Cleanup** - Allows firmware to free resources and accept new connections
4. **Cold Start Stabilization** - Provides synchronization signal during firmware boot

The firmware implements a watchdog pattern: if no heartbeat is seen within the 15-second timeout window, assume the connection is dead and reset.

---

## Developer Guidelines

### Timing Requirements

**The ~10-second watchdog window requires:**
1. USB connection established
2. Init messages sent (~1.5s)
3. First heartbeat received by adapter (at t=2s if using 2000ms interval)

**Total time budget:** ~10 seconds from USB open to stable heartbeat

### Implementation Pattern (Observed in Capture Sessions)

```python
def start():
    # 1. Start heartbeat timer FIRST (but don't send immediately!)
    heartbeat_timer = Timer(interval=2000ms, callback=send_heartbeat)
    heartbeat_timer.start()  # First callback fires in 2000ms

    # 2. Send init messages (takes ~1.5s with 120ms delays)
    for msg in init_messages:
        send(msg)
        sleep(120ms)

    # 3. Start read loop
    start_read_loop()

    # 4. First heartbeat fires automatically at t=2000ms
```

### DO NOT

1. **NEVER** send heartbeat immediately at t=0 before init messages
2. **NEVER** start heartbeat timer AFTER init messages complete
3. **NEVER** use intervals longer than 5000ms (risk of timeout)

### Failure Indicators

- `Host No Response, we will reset connection!!!` in TTY log
- `projectionDisconnected` message ~10-12 seconds after connection
- Session terminating before stable streaming state
- Wireless connection never established (USB-only works)

### Expected Log Pattern (Correct)

```
14:53:47.834 > [DONGLE] Starting dongle connection sequence
14:53:47.835 > [DONGLE] Heartbeat timer started (first HB in 2000ms)
14:53:47.840 > [DONGLE] Sending init message 1/12
14:53:47.960 > [DONGLE] Sending init message 2/12
...
14:53:49.147 > [DONGLE] Initialization sequence completed
14:53:49.835 > [DONGLE] First heartbeat sent (t=2000ms)
14:53:51.835 > [DONGLE] Heartbeat sent (t=4000ms)
```

---

## Known Issues (Resolved)

### Duplicate Heartbeat Timer Bug (Fixed 2025-11-05)

**Problem:** Two heartbeat timers running simultaneously, offset by ~260ms.

**Root Causes:**
- Missing `await` in projectionDisconnected handler
- Incorrect `Future.delayed` usage

**Resolution:**
- Added `await` before `restart()` call
- Fixed `Future.delayed()` to properly sequence stop/wait/start
- Ensures one heartbeat timer per adapter session

---

## References

- Binary: `cpc200_ccpa_firmware_binaries/unpacked/ARMadb-driver_unpacked`
- Tools: rizin, strings, xxd
- Discovery: November 2025 (cold start timing)
- Binary Analysis: January 2026
- Testing: 100% success rate across all test scenarios
