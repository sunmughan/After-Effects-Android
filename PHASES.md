# EffectCraft Android — Phase Acceptance Tracker

Repository: [sunmughan/After-Effects-Android](https://github.com/sunmughan/After-Effects-Android)  
Work branch: `feat/android-app-foundation` · [PR #1 (Draft)](https://github.com/sunmughan/After-Effects-Android/pull/1)  
Last checked: 2026-11-07 22:37 IST  
Current branch HEAD: `814dd66e65989137dd80d554ebf9c38b158edbf5`

> This file is the durable acceptance ledger, not a chronological automation transcript. Keep one concise entry per phase and link implementation/test commits. Do not append repeated run summaries; update this ledger in place.

## Current CI / test evidence

- **CI #1122** — [run 42388159501](https://github.com/sunmughan/After-Effects-Android/actions/runs/42388159501), tested code SHA `8b79218c84f78830b42c106d8f0a53d815f7e925`: native ARM64+x86_64, Rust, Kotlin/JVM and unsigned release APK/AAB checks passed; Android API 30 failed only `AdaptiveWorkspaceSmokeTest.motionPathOverlayDrawsForSpatialPositionTrack` in both emulator attempts. Exact job [128449918817 logs](https://github.com/sunmughan/After-Effects-Android/actions/runs/42388159501) show a 60 s Compose `waitUntil` timeout at test line 1007 after render request `(10, 0)`; this is a presentation/semantics synchronization failure, not a native render assertion.
- **Test correction** — [commit 814dd66e](https://github.com/sunmughan/After-Effects-Android/commit/814dd66e65989137dd80d554ebf9c38b158edbf5): no nested `waitForIdle()` inside `waitUntil`; separately await the rendered-frame semantics node and then the overlay node. Assertions still require both frame and overlay to exist/display; no timeout inflation beyond two bounded 30 s waits and no assertions removed.
- **CI #1123** — [run 42393023883](https://github.com/sunmughan/After-Effects-Android/actions/runs/42393023883), older SHA `dd042e58`, was still in progress at the last check (release APK/AAB step); it does not include the test correction.
- **CI #1124** — [run 42394930220](https://github.com/sunmughan/After-Effects-Android/actions/runs/42394930220), exact new test SHA `814dd66e`, queued/pending at last check. Its full JVM, API30, native and independent cold APK/native comparison verdict is required. Do not infer success from queue/checkout.
- Local Gradle/JDK/Android SDK execution is unavailable in this automation runtime; GitHub Actions is the build/test gate.

## Phase status and acceptance gates

### M0 — Repository, toolchain, native bridge and reproducibility
**Status: ACCEPTED historically; current-head revalidation pending.**

Acceptance: pinned Gradle Wrapper/toolchain; native Rust/JNI bridge; ARM64 and x86_64 libraries; Rust/JVM tests; API 30 instrumentation; debug APK; independent cold rebuild with APK/native byte match; no weakened tests.

Evidence: [CI #1011, run 42315397626](https://github.com/sunmughan/After-Effects-Android/actions/runs/42315397626), SHA `9ebcdc36d02c37f9ed52a14fa1e6f748d3c47dbf`: both Assemble/test and cold reproducibility jobs passed; Rust 59/59, API30 165/165, JVM and dual-ABI packaging green. Earlier explicit baseline #774 also passed.  
Key source/toolchain: [Gradle wrapper/build workflow](https://github.com/sunmughan/After-Effects-Android/tree/feat/android-app-foundation).  
Remaining: rerun all gates on the latest branch head; physical ARM64 runtime is M6/M7.

### M1 — Professional Android editor foundation
**Status: PARTIAL (scoped offline editor foundation and smoke acceptance).**

Acceptance: offline project creation/open/save/recovery; adaptive phone/tablet/foldable workspace; layer selection/add/delete/reorder/visibility; properties/opacity; timeline/playhead/playback; undo/redo; responsive, accessible controls; state persistence and API30 instrumentation.

Evidence: scoped baseline accepted in historical emulator suites (including delete/reorder/undo/redo and offline open/save). Responsive fixes: [remove-layer action](https://github.com/sunmughan/After-Effects-Android/commit/eb7b254e), [regression](https://github.com/sunmughan/After-Effects-Android/commit/a7e9895b). Latest CI #1122 found a workspace-preview test synchronization failure; correction [814dd66e](https://github.com/sunmughan/After-Effects-Android/commit/814dd66e) pending #1124.  
Remaining: full professional interaction parity, complete accessibility/TalkBack and physical device/foldable UX acceptance.

### M2 — Project import/save, format compatibility and media
**Status: PARTIAL; canonical and offline-media foundations implemented.**

Acceptance: parse/serialize/validate supported project formats; SAF open/save; migration and rollback; missing-media relink; content-addressed media integrity; offline operation; cancellation/progress; corruption fail-closed; concurrency-safe catalog/reference updates.

Evidence/commits:
- Canonical JSON/XML, validation/migration and BOM compatibility; unsupported-format guardrails.
- Async cancellable SAF import and transactional publication/rollback.
- Offline SHA-256 media store, references, relink/GC safeguards and fail-closed corruption handling.
- Cross-instance project-cache serialization: [894abf61](https://github.com/sunmughan/After-Effects-Android/commit/894abf61).
- Cross-instance offline media catalog serialization + 120-import regression: [8b79218c](https://github.com/sunmughan/After-Effects-Android/commit/8b79218c84f78830b42c106d8f0a53d815f7e925).
- Historical M2 parser/round-trip and migration tests; current exact-head verification remains pending.

Blockers/limits: broad Effekseer `.efkproj` parser/converter and version fixtures are not integrated. Adobe `.aep` is proprietary; no licensed/available parser, so native import is not claimed. Continue with supported EffectCraft JSON/XML and explicit missing-media relinking.

### M3 — Actual composition preview/render integration
**Status: PARTIAL (real native CPU/session preview integrated; device/GPU acceptance open).**

Acceptance: real Rust composition frames via JNI/NativeSession; lifecycle/cancellation; Android frame presentation; GPU path and tested software fallback; Surface recreation/device loss; media decoding and preview correctness.

Evidence: actual Rust CPU/session compositor and JNI lifecycle, native render requests, TextureView/Bitmap presentation and renderer/cache instrumentation. Preview cache hashes canonical project once per accepted revision; stale-frame eviction and Bitmap lifecycle commits: [087704fd](https://github.com/sunmughan/After-Effects-Android/commit/087704fd), [eefac4e1](https://github.com/sunmughan/After-Effects-Android/commit/eefac4e1), [a834d86c](https://github.com/sunmughan/After-Effects-Android/commit/a834d86c).  
Remaining: physical ARM64 GPU/Surface/device-loss and media decode parity/performance; latest workspace overlay semantics test must pass #1124.

### M4 — Functional layer/effect/timeline editing
**Status: IN PROGRESS; broad canonical editing exists, full upstream parity open.**

Acceptance: canonical properties and transforms update the actual project/preview; Position/Scale/Rotation/Anchor/Orientation/Rotation X/Y keys; curve/key navigation, add/move/remove, interpolation/easing; ShapePath vertices, cubic handles, close/open, insert/delete/reverse/align/smooth/corner and feather preservation; effect/property and Layer Control authoring.

Evidence: native-backed property/transform/keyframe edits, discrete hold keys, Bezier graph and ShapePath inspector/canvas; lossless de Casteljau insertion [b3f2c00b](https://github.com/sunmughan/After-Effects-Android/commit/b3f2c00b); closure/removal/tangent/selection instrumentation and responsive editor corrections. Current M4 commits are linked in PR history and earlier tracker entries.  
Remaining: complete upstream effect stack, interpolation/roving/spatial parity, exhaustive edit→serialize→reopen→preview golden fixtures and broader device UX.

### M5 — Render/export pipeline
**Status: PARTIAL (PNG/MP4/audio foundations and UI wiring implemented).**

Acceptance: resolution/FPS/frame-range/codec validation; actual composition frames; streaming export; progress/cancel; audio/media synchronization; output integrity/playback validation; SAF destination and cleanup/recovery.

Evidence: PNG sequence ZIP and H.264 MP4 pipeline, AAC mix/remux, frame timestamps, output validation, cancellation propagation, bounded MediaCodec EOS drain. Relevant fixes: [MP4 timestamps](https://github.com/sunmughan/After-Effects-Android/commit/12f20781), [AAC EOS drain](https://github.com/sunmughan/After-Effects-Android/commit/91e2e2e8), audio cancellation regression [0ef68d16](https://github.com/sunmughan/After-Effects-Android/commit/0ef68d16).  
Remaining: long-running/process-death recovery, audio sync/mux validation across devices, codec/resolution/FPS matrix and end-to-end exported media playback.

### M6 — Performance, memory, recovery, offline/device/accessibility
**Status: PARTIAL; instrumentation and bounded caches/recovery implemented, physical qualification open.**

Acceptance: performance/frame-time budgets; memory/cache accounting; leak/crash recovery; offline and storage failure robustness; API/ABI/device matrix; TalkBack/large font/foldable UX; thermal/long-session tests.

Evidence: crash-safe journals/atomic persistence; fail-closed offline assets; 256 MiB media pool + independently reported 64 MiB layer cache; revision-scoped preview cache eviction and stale-frame rejection; decoded Bitmap reuse/recycling. Cache lifecycle: [57ab6edd](https://github.com/sunmughan/After-Effects-Android/commit/57ab6edd), [209dbf27](https://github.com/sunmughan/After-Effects-Android/commit/209dbf27); cache budget regression [00c869b8](https://github.com/sunmughan/After-Effects-Android/commit/00c869b8).  
Remaining: physical ARM64 performance/memory/thermal, low-memory/process-death, API/ABI and accessibility matrix.

### M7 — Release readiness
**Status: NOT ACCEPTED.**

Acceptance: all M0–M6 gates; exact-head CI fully green including cold reproducibility; configurable release signing without committed/exposed secrets; signed APK/AAB artifacts; physical install/run and smoke; release notes, limitations, checksums and authorized release/merge.

Evidence: CI assembles unsigned release APK/AAB and verifies no debug-key signature fallback.  
Blockers: owner-controlled signing secrets/keystore and physical ARM64 device access are unavailable to this run. Continue unsigned reproducible artifacts and release documentation; never fabricate signing/install evidence.

## Release decision

**EffectCraft is NOT release-complete and PR #1 must remain Draft.** No native Adobe `.aep` compatibility claim. No phase is marked complete merely because a workflow was queued or a prior SHA passed.
