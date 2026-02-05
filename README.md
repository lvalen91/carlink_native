# carlink_native
Carlink Alternative without Flutter/Dart

# Work In progress

## Multiple updates expected. Assume they will each break something.

I'm focusing on my gminfo Intel AAOS radio. Both for Video and Audio performance. Many things have been carried over from the Flutter Carlink that have not been fixed. 

## Change to your package name, version, and version code for PlayStore Upload.
section: defaultConfig InFile: /app/build.gradle.kts

Remember kids: (mostly me)
```
Projection streams are live UI state, not video playback.
Do not buffer, pace, preserve, or “play” frames.
Late frames must be dropped. Corruption must trigger reset.

CarPlay / Android Auto h264 is not media.
It is a real-time projection of UI state.
Correctness is defined by latency, not completeness.
Buffers create corruption. Queues create lies.

Video is a best-effort, disposable representation of UI state.
Audio is a continuous time signal that must never stall.
Video may drop. Audio may buffer. Neither may block the other
```

Video:
- Represents live UI state
- Late == invalid
- Drop aggressively
- Reset on corruption
- Never wait

Audio:
- Represents continuous time
- Late == fill
- Buffer aggressively
- Never stall
- Never block video

---

## Known Video Issues & Future Improvements

### Video Freeze (Audio Continues)
**Symptom**: Live UI freezes while audio continues playing. Does not self-recover. User must manually use "Reset Video Decoder" button or navigate away from app.

**Root Cause**: MediaCodec can enter a bad state without triggering `onError()` callback. No automatic recovery mechanism exists.

**Workaround**: Manual reset via Settings → Reset Video Decoder, or navigate to home screen and back.

**Planned Improvements** (see `H264Renderer.java` TODOs):

1. **`[SELF_HEALING]`** - Priority: HIGH
   - Frozen = corruption. Corruption = reset. Don't try to recover, just reset.
   - Detect: frames arriving + nothing rendering = stuck. Action: `reset()`.

2. **`[LIVE_UI_OPTIMIZATION]`** - Priority: MEDIUM
   - Buffers are lies. 10 frames = 166ms of stale state.
   - Target: 1-2 frames (thread handoff only). Drops are correct behavior.

3. **`[DIRECT_HANDOFF]`** - Priority: LOW (after SELF_HEALING proven)
   - Feed codec directly or drop. Late frames must be dropped, not queued.

**For Future Debugging**: Frozen video = implement `[SELF_HEALING]`. Just reset.

---

## Reference: GM AAOS Native CarPlay

GM uses NO buffer, direct handoff, drops aggressively. Same philosophy, native implementation.
See `/gm_aaos/system/lib64/libNmeVideo*.so`.
