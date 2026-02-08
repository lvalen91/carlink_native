# Video Code Reasoning Documentation Index

## Purpose

This directory contains detailed analysis and reasoning for video pipeline changes in carlink_native. It serves as:

1. **Historical Record** - Why changes were made
2. **Reference for Context Compaction** - Essential information when conversation context is summarized
3. **Cross-Session Knowledge** - Information for other Claude sessions working on this codebase

---

## ⚠️ CRITICAL: Read First

1. **`13_PROJECTION_VIDEO_PHILOSOPHY.md`** - Core design philosophy. Projection is live UI, not cinema. Never Do checklist.
2. **`18_RECEIVER_CONTRACT_AND_OPERATIONS.md`** - Receiver responsibilities, telemetry, early poisoning detection.
3. **`15_REV57_DECODER_POISONING_ANALYSIS.md`** - Rev [57] root cause analysis and fixes.
4. **`14_PROJECTION_MODEL_IMPLEMENTATION.md`** - Rev [56] implementation details and USB capture analysis.
5. **`09_CURRENT_CAPABILITIES_AND_GUIDANCE.md`** - Available resources and expectations.

With direct SSH access to the CPC200-CCPA adapter and ADB access to GM Info 3.7, plus complete code history and documentation, there is NO excuse for hallucination or guesswork. Read the documentation before making changes.

---

## Document Summary

| Document | Purpose | Key Content |
|----------|---------|-------------|
| `00_RESOURCE_LOCATIONS.md` | Index of all resources | File paths, logcat location, archive locations |
| `01_VIDEO_PIPELINE_ARCHITECTURE.md` | System architecture | CarPlay → Adapter → OS → App flow |
| `02_VIDEO_CODE_EVOLUTION.md` | Code history | Revision changes, rationale, outcomes |
| `03_LOGCAT_ANALYSIS.md` | Evidence analysis | Detailed logcat examination |
| `04_CURRENT_STATE_ASSESSMENT.md` | Current code state | Rev [55] code path analysis |
| `05_SYNTHESIS_AND_RECOMMENDATIONS.md` | Recommendations | Actions for Rev [56] |
| `06_ORIGINAL_CARLINK_ANALYSIS.md` | Original Flutter project | Carlink troubleshooting history |
| `07_GITHUB_COMMIT_HISTORY.md` | Complete lineage | All repos: abuharsky → Carlink → carlink_native → MotoInsight |
| `08_ARCHIVE_CODE_ANALYSIS.md` | Archive deep dive | All 42 archives analyzed, undocumented changes found |
| `09_CURRENT_CAPABILITIES_AND_GUIDANCE.md` | **READ FIRST** | Available access, expectations, testing protocol |
| `10_VIDEO_CODE_DEEP_ANALYSIS.md` | Code analysis | What's correct, improvable, needs correction, unexplainable |
| `11_VIDEO_DATA_PATH_COMPLETE.md` | Data flow trace | Complete path: CPC200-CCPA → USB → App → MediaCodec → Display |
| `12_GM_CINEMO_VS_CARLINK_ANALYSIS.md` | **CRITICAL** | GM native vs carlink_native: buffering is the problem |
| `13_PROJECTION_VIDEO_PHILOSOPHY.md` | **CRITICAL** | Design philosophy: Projection is live UI, not cinema |
| `14_PROJECTION_MODEL_IMPLEMENTATION.md` | **Rev [56]** | Implementation details, decoder discipline, USB capture analysis |
| `15_REV57_DECODER_POISONING_ANALYSIS.md` | **Rev [57]** | Decoder poisoning root cause, multi-factor cascade, planned fixes |
| `16_DECODER_POISONING_CONCEPTS.txt` | Theory | Decoder poisoning theory and rules from concepts.txt |
| `17_USB_CAPTURE_STREAM_ANALYSIS.md` | **QUANTITATIVE** | Frame-by-frame stream structure, timing, NAL sequences, 215K frames |
| `18_RECEIVER_CONTRACT_AND_OPERATIONS.md` | **OPERATIONAL** | Receiver contract, telemetry rules, early detection, self-healing playbook |

---

## ✅ Rev [57] - Decoder Poisoning Fixes (IMPLEMENTED 2026-01-30)

**Rev [56] Issues Identified:**
- Progressive video degradation (ghosting, tailing, pixelation)
- Corruption persists through IDR frames
- Only manual reset clears corruption

**Root Cause: Staleness Check Time Base Bug (CRITICAL)**
- Staleness check compared `System.currentTimeMillis()` (~1.7T ms epoch) to `sourcePtsMs` (~32K ms stream-relative)
- Incompatible time bases produced ~1.7 trillion ms "age" for every frame
- Frames only passed when `ringBuffer.isEmpty()` (race condition)
- **IDR frames were being discarded**, preventing decoder recovery

**Implemented Fixes:**
| Fix | Description | Status |
|-----|-------------|--------|
| Staleness check | DISABLED - was fundamentally broken (time base mismatch) | ✅ Done |
| Stall timeout | Reduced 500ms → 200ms for faster recovery | ✅ Done |
| Corruption detection | Track IDRs vs output, reset if poisoned | ✅ Done |
| Proper staleness | TODO in code: correct time base, 40ms threshold, protect IDR | 📝 Documented |

**Build:** `app-debug.apk` compiled successfully

See `15_REV57_DECODER_POISONING_ANALYSIS.md` for full details.

---

## ✅ Rev [56] - Projection Model Implemented

**The code has been restructured to follow projection video philosophy.**

| Before (Rev [55]) | After (Rev [56]) |
|-------------------|------------------|
| 2-4MB buffer | 192KB jitter buffer |
| 120 packets (~2s) | 12 packets (~200ms) |
| Drop oldest on overflow | Skip to newest IDR |
| Counter-based QC | Removed entirely |
| Source PTS queue | Monotonic synthetic PTS |
| No IDR gate | IDR gate + stall timeout |

**Decoder Discipline (New):**
- IDR Gate: Don't decode P-frames until first IDR received
- Stall Timeout: Reset and request keyframe if no output for 500ms

**USB Capture Analysis Confirmed (215K frames, 10 sessions):**
- 100% of sessions start with SPS+PPS+IDR (verified 10/10)
- SPS+PPS ALWAYS bundled with IDR - never standalone
- IDR interval: median 2000ms, range 83ms-2117ms
- Frame rate: 2-62 fps variable (not constant)
- Jitter: 25.6ms std dev → 30-40ms staleness threshold appropriate

See `17_USB_CAPTURE_STREAM_ANALYSIS.md` for complete quantitative data.

---

## Critical Findings (Quick Reference)

### Historical Problem (Rev [55], resolved)
- **Codec input starvation**: 3,461 ring buffer writes, only ~5 codec inputs
- **Quality Control dropping frames**: 14,000+ frames dropped, situation not improving
- **Temporary fix**: Surface recreation works for ~30 seconds then degrades
- **Resolution**: QC removed in [56], ring buffer eliminated via DIRECT_HANDOFF in [61+], PacketRingByteBuffer deleted

### Project Lineage
```
abuharsky/carplay (Jan 2024) - Original ~216 lines
        ↓
lvalen91/Carlink (Aug 2025) - Flutter expansion ~860 lines
        ↓
carlink_native (Dec 2025) - Native rewrite, H264Renderer peaked ~1888 lines, now ~540 lines
        ↓
MotoInsight fork (Jan 2026) - Independent approach ~1249 lines
```

### Code Complexity Arc
| Version | Lines | Change |
|---------|-------|--------|
| Original | ~216 | Basic MediaCodec |
| [13] | ~1060 | First "stable" claim |
| [31] | ~1100 | ByteBuffer fix |
| [52] | ~1558 | Intel VPU workaround |
| [55] | ~1888 | QC, Source PTS, reduced buffers (peak complexity) |
| [56] | — | Projection model, QC removed, buffer 4MB→192KB |
| [61] | ~538 | Simplified, DIRECT_HANDOFF, PacketRingByteBuffer deleted |
| [66] | ~540 | Pre-allocated audio, atomic counters, dead code removed, NAL-aware drop logging, VideoDebugLogger wired, [SELF_HEALING] TODO enhanced |

### Key Issues Addressed Over Time
- setCallback order (Carlink Dec 2025)
- ringBuffer.reset on codec reset (Carlink Dec 2025)
- Intel SYNC mode failure (Carlink Dec 2025)
- ByteBuffer memory sharing ([31])
- Intel VPU flush insufficiency ([52])
- Various video stability issues (ongoing)

### Current State [66]
- ~540 lines (down from peak 1888 in [55])
- DIRECT_HANDOFF: USB → `feedDirect()` → MediaCodec inline, no buffer, no queue
- NAL-type-aware drop logging: IDR drops → WARNING, P-frame drops → 30s batch stats
- VideoDebugLogger wired to all lifecycle events (init, start, stop, reset, error, formatChanged)
- Monotonic `frameCounter` for PTS (source PTS from adapter used for logging only)
- `firstFrameLogged` flag survives `logStats()` counter resets (was firing every 30s)
- [SELF_HEALING] watchdog fully researched, not yet implemented (see H264Renderer.java TODO)

### Historical Recommendation for [56] (COMPLETED)
1. ~~Disable Quality Control~~ Done
2. ~~Disable Source PTS queue~~ Done (monotonic counter)
3. ~~Buffer sizing~~ N/A (buffer eliminated via DIRECT_HANDOFF)
4. ~~Simplify callback path~~ Done
5. ~~Add diagnostic logging~~ Done (comprehensive pipeline stats + NAL-aware drops)

---

## Quantified Stream Behavior (from USB Captures)

> **Full frame-by-frame sequence with timing:** See `17_USB_CAPTURE_STREAM_ANALYSIS.md` Section 7

### Stream Structure Pattern
```
[SPS+PPS+IDR] → [P] → [P] → ... → [P] → [SPS+PPS+IDR] → [P] → [P] → ...
     ↑                              ↑          ↑
  Session                      GOP end     Periodic
   Start                     (16-53 frames)  keyframe
```

### Session Start (VERIFIED)
```
100% of sessions begin with: [SPS+PPS+IDR] bundle
First packet always contains all three NAL units
No need to request keyframe at session start
```

### NAL Unit Patterns
```
~97%: [P-slice] - single NAL per packet
~3%:  [SPS → PPS → IDR] - bundled, never standalone
SPS: 22 bytes (constant), PPS: 4 bytes (constant)
```

### IDR Periodicity (538 intervals analyzed)
```
Median: 2000ms (standard ~2s GOP)
Range:  83ms - 2117ms (typical)
Distribution:
  66%: 2.0-2.5s (standard)
  22%: 1.5-2.0s (typical)
  7%:  <500ms (keyframe requests honored)
```

### Frame Timing
```
16-17ms (60fps): 56% of intervals
50ms (20fps):    31% of intervals
100ms+ (idle):   ~3% of intervals

Jitter std dev: 25.6ms
Within ±20ms:   55% of frames
Within ±40ms:   85% of frames
```

### Frame Sizes
```
IDR avg: 49KB (range: 29KB-138KB)
P avg:   23KB (range: 0.4KB-134KB)
Overall: 24KB average
```

---

## Key Metrics

### From Logcat Session (17:46 - 18:06)
| Metric | Value | Problem? |
|--------|-------|----------|
| Ring Buffer Writes | 3,461 | Normal |
| Codec Inputs | ~5 | **CRITICAL** |
| Input Efficiency | 0.14% | **CRITICAL** |
| QC Dropped Frames | 14,535 | Excessive |
| Peak Frame Lag | 958 | **CRITICAL** |
| Peak Decode Latency | 92 sec | **CRITICAL** |
| Stable Period FPS | 25.6 | Good |
| Stable Period Duration | ~30 sec | Too short |

---

## File Locations

### Current Project
```
/Users/zeno/Downloads/carlink_native/
├── app/src/main/java/com/carlink/video/
│   └── H264Renderer.java (~540 lines, DIRECT_HANDOFF + NAL-aware drop logging)
├── app/src/main/java/com/carlink/util/
│   ├── VideoDebugLogger.java (wired to H264Renderer lifecycle)
│   └── AudioDebugLogger.java (audio pipeline diagnostics)
├── app/src/main/kotlin/com/carlink/audio/
│   ├── DualStreamAudioManager.kt (dual-stream media+nav, platform-tuned buffers)
│   ├── AudioRingBuffer.kt (lock-free SPSC, overwrite-oldest)
│   ├── MicrophoneCaptureManager.kt (ring buffer, VOICE_COMMUNICATION)
│   ├── AudioConfig.kt (DEFAULT/GM_AAOS/ARM buffer profiles)
│   └── AudioFormats.kt (decode_type → format mapping)
└── documents/
    ├── reference/ (platform docs)
    ├── revisions.txt (change log)
    └── video_code_reasoning/ (this directory)
```
Note: PacketRingByteBuffer.java was deleted — replaced by DIRECT_HANDOFF.

### Historical Archives
```
/Users/zeno/Downloads/project_archieve/
├── carlink_native_13.zip (STABLE BASELINE)
├── carlink_native_30.zip (Unstable - aggressive recovery)
├── carlink_native_52.zip (Intel VPU workaround)
├── carlink_native_54.zip (GM AAOS optimization)
└── carlink_native_55.zip (Historical)
```

### Logcat Capture
```
/Volumes/POTATO/logcat/logcat_20260130_024549.log (71MB)
```

---

## For Future Sessions

If you're a Claude session picking up this work:

1. **Read `13_PROJECTION_VIDEO_PHILOSOPHY.md`** - Core philosophy and Never Do checklist
2. **Read `18_RECEIVER_CONTRACT_AND_OPERATIONS.md`** - Receiver contract and operational guidance
3. **Read `15_REV57_DECODER_POISONING_ANALYSIS.md`** - Decoder poisoning root cause analysis
4. **Read the [SELF_HEALING] TODO in `H264Renderer.java`** - Comprehensive watchdog design with detection, recovery, cooldown, thread safety, and false positive mitigation. Research completed Feb 2026.
5. **Revision [13]** is the stable baseline - compare against it
6. **Test changes** using the protocol in document 05
7. **Log changes** in revisions.txt with entry [67]+

**Key Principles (memorize):**
- "A frame that arrives too late must never be decoded"
- "If corruption survives one IDR, reset immediately"
- "One reset is cheaper than ten corrupted frames"
- "The adapter is neutral. The receiver owns correctness."
- "Drop video. Buffer audio. Reset often. Never wait."

---

## Document History
- **Created:** 2026-01-29
- **Updated:** 2026-01-30 (Rev [57] analysis added)
- **Updated:** 2026-01-30 (Document 17 added: Quantitative USB capture analysis)
- **Updated:** 2026-01-30 (Document 17 expanded: Frame-by-frame sequence with timing)
- **Updated:** 2026-01-30 (Rev [57] implemented and compiled)
- **Updated:** 2026-01-30 (Document 13 enhanced: Integrated rules.txt - Never Do checklist, Audio/Sync philosophy)
- **Updated:** 2026-01-30 (Document 18 added: Receiver Contract and Operations Guide)
- **Updated:** 2026-02-07 (Rev [66]: NAL-aware drop logging, VideoDebugLogger wired, audio pipeline documented, [SELF_HEALING] TODO enhanced with research findings)
- **Current Revision:** [66]
- **Major milestones since [57]:** [58] stall detection fix + telemetry, [61] video code simplification + DIRECT_HANDOFF, [64] MAX_BUFFER_PACKETS 10→2, [66] pre-allocated audio buffers + atomic USB counters + dead code cleanup + NAL-aware drop logging + VideoDebugLogger wiring + firstFrameLogged fix + [SELF_HEALING] TODO enhanced with research
