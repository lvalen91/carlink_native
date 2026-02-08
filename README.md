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
