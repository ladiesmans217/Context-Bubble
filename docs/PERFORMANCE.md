# Performance contract

## Idle invariants

- One shallow classic Android overlay view and event-driven Accessibility package/focus state.
- No microphone, `AudioRecord`, WebRTC, Supabase Realtime socket, AI request, screenshot, polling, sensor, wake lock, or continuous animation.
- No full Accessibility tree traversal without a user action.
- Network work runs through constrained WorkManager. Memory delta sync is periodic every six hours and event-triggered after approved shared changes; it keeps no idle socket.
- Realtime resources close after 20 seconds idle and always before the two-minute session ceiling.

## Interaction design

- Drag is touch-slop gated, frame-coalesced, allocation-light, and performs no database/network/image work.
- Overlay text updates are frame-coalesced; I/O remains off the main thread.
- Screenshots are downsampled before upload/display, bitmaps and audio buffers are bounded, and history/memory lists are paged or bounded.
- Semantic search retrieves 20 encrypted candidates, reranks after decryption, and supplies at most eight memories/about 4,000 characters.
- Baseline Profiles cover startup, service restoration, bubble tap, insertion, Settings, Vault, cloud sync, first SSE output, and Realtime start.

## Release gates on Motorola moto g54 5G

- Bubble visual response under 50 ms p95.
- Recording indicator under 150 ms p95 after the hold threshold.
- First SSE text and first Realtime remote audio under 1.5 seconds p95 on stable Wi-Fi.
- Warm cloud memory search under 150 ms p95 server-side; 50-record delta sync under one second p95.
- Frame p95 no more than 16.7 ms at 60 Hz or 8.3 ms at 120 Hz; zero frozen frames over 700 ms.
- Idle CPU under 0.5%; cloud work adds at most 0.1% idle CPU and 5 MB idle PSS.
- Idle process PSS at or below 80 MB with no more than 10% soak drift.
- Screen-off drain no more than 0.3% battery/hour over an eight-hour control run; no app-owned idle wake lock.
- Relevant release APK split under 40 MB.
- No crash, ANR, protected-screen exposure, wrong-field insertion, duplicated consequential action, or unbounded PSS/Storage/database/worker/connection growth.

## Required evidence

Use Macrobenchmark, Baseline/Startup Profiles, Perfetto, FrameTimeline/JankStats, `dumpsys gfxinfo`, `dumpsys meminfo`, Android Memory and Power Profilers, a 1,000-cycle overlay run, 200 dictations, 24-hour service soak, eight-hour screen-off A/B run, and seven-day dogfood. A source build is not evidence for time-dependent gates; measurements belong in `docs/VALIDATION.md` only after collection.

The deployed retrieval gate is `npm run eval:retrieval -- --cleanup`. It uses a disposable dev user, 50 English plus 50 Hinglish queries, requires Recall@5 ≥85% for each language, and reports search p95. Failure requires switching the entire deployment to the versioned 384-dimensional OpenAI embedding path and re-embedding; models are never mixed in one index.
