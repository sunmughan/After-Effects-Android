# EffectCraft Android — Phase Acceptance Tracker

Repository: [sunmughan/After-Effects-Android](https://github.com/sunmughan/After-Effects-Android)  
Work branch: `feat/android-app-foundation` · [PR #1 (merged 2026-11-07)](https://github.com/sunmughan/After-Effects-Android/pull/1)  
Last checked: 2026-11-08 00:44 IST  
Latest implementation commits: keyframe store `542c1785`, regression `faf1d30c`; M2 manifest root-lock implementation/test `a5cfbe18`, shared-lock correction `bd3861f3`, Unit-contract correction `5392b24a`; PHASES final sync `a7dc0c39`, README sync `6cd172bf`. PR #1 merged at `4ae22776`; post-merge work remains on the feature branch.

> This file is the durable acceptance ledger, not a chronological automation transcript. Keep one concise entry per phase and link implementation/test commits. Do not append repeated run summaries; update this ledger in place.

## Current CI / test evidence

- **CI #1124 — PASS**: [run 42394930220](https://github.com/sunmughan/After-Effects-Android/actions/runs/42394930220), exact source SHA `814dd66e`. Assemble/test job and independent cold debug-APK rebuild/compare both succeeded. Rust bridge **61/61**, Kotlin/JVM suite passed, unsigned APK+AAB and no-debug-signing checks passed, Android API 30 instrumentation **175/175** (11 test classes; 0 skipped/failed). This verifies the workspace motion-path synchronization correction.
- **CI #1125 (workflow_dispatch on merge commit `4ae22776`)**: Assemble/test job passed, including Rust **61/61** and API30 **175/175**; independent cold APK comparison remains in progress at last check. Do not treat the whole run as green until its second job concludes.
- **M4 implementation**: cross-instance keyframe-store locking and narrow atomic-move fallback [`542c1785`](https://github.com/sunmughan/After-Effects-Android/commit/542c178523e09332ae4e5dca37cf771729bc620d); concurrent independent-store regression [`faf1d30c`](https://github.com/sunmughan/After-Effects-Android/commit/faf1d30cca90fbd5c048f46ac328d7221b159bc5), 6 writers × 20 keyframes. CI #1128 was cancelled when the next source commit arrived; no result is claimed.
- **M2 manifest transaction concurrency**: initial root-lock implementation [`a5cfbe18`](https://github.com/sunmughan/After-Effects-Android/commit/a5cfbe18e4e18c6782372cdc3f13bb7813b79200), shared-lock implementation [`bd3861f3`](https://github.com/sunmughan/After-Effects-Android/commit/bd3861f3607710e6db1d6a0886d205eef08d1793), Unit-contract correction [`5392b24a`](https://github.com/sunmughan/After-Effects-Android/commit/5392b24ad6d113fa9dbd5afa91b41c0bee85f8b6), and deterministic rollback-vs-independent-save regression (included in `a5cfbe18`). Exact CI #1131 [run 42405449095](https://github.com/sunmughan/After-Effects-Android/actions/runs/42405449095) is queued on `5392b24a`; #1130 was superseded. No test result is claimed.
- Local Gradle/JDK/Android SDK execution is unavailable in this runtime; GitHub Actions is the build/test gate. No hardware/device acceptance is inferred from emulator CI.
- **PR #1** was merged into `main` at [`4ae22776`](https://github.com/sunmughan/After-Effects-Android/commit/4ae22776dae28757ac56ba526b14af0f8e84a00d) on 2026-11-07. Subsequent roadmap fixes are being committed to `feat/android-app-foundation`; PR #1 cannot be kept Draft/open after merge. No new PR is claimed.

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

Evidence: scoped baseline accepted in historical emulator suites (including delete/reorder/undo/redo and offline open/save). Responsive fixes: [remove-layer action](https://github.com/sunmughan/After-Effects-Android/commit/eb7b254e), [regression](https://github.com/sunmughan/After-Effects-Android/commit/a7e9895b). The workspace-preview semantics synchronization correction [814dd66e](https://github.com/sunmughan/After-Effects-Android/commit/814dd66e) passed exact-head CI #1124: API30 instrumentation 175/175 and independent cold APK comparison both passed.  
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
- Project manifest batch replacement now shares a canonical-root process lock with independent save/load/delete/GC instances; regression injects a mid-batch publish failure and proves a waiting save runs after rollback rather than being overwritten.
- Historical M2 parser/round-trip and migration tests; current exact-head verification remains pending.

Blockers/limits: broad Effekseer `.efkproj` parser/converter and version fixtures are not integrated. Adobe `.aep` is proprietary; no licensed/available parser, so native import is not claimed. Continue with supported EffectCraft JSON/XML and explicit missing-media relinking.

### M3 — Actual composition preview/render integration
**Status: PARTIAL (real native CPU/session preview integrated; device/GPU acceptance open).**

Acceptance: real Rust composition frames via JNI/NativeSession; lifecycle/cancellation; Android frame presentation; GPU path and tested software fallback; Surface recreation/device loss; media decoding and preview correctness.

Evidence: actual Rust CPU/session compositor and JNI lifecycle, native render requests, TextureView/Bitmap presentation and renderer/cache instrumentation. Preview cache hashes canonical project once per accepted revision; stale-frame eviction and Bitmap lifecycle commits: [087704fd](https://github.com/sunmughan/After-Effects-Android/commit/087704fd), [eefac4e1](https://github.com/sunmughan/After-Effects-Android/commit/eefac4e1), [a834d86c](https://github.com/sunmughan/After-Effects-Android/commit/a834d86c).  
Remaining: physical ARM64 GPU/Surface/device-loss and media decode parity/performance; latest workspace overlay semantics test must pass #1124.

### M4 — Functional layer/effect/timeline editing
**Status: IN PROGRESS; broad canonical editing exists, full upstream parity open.**

- Keyframe editor durability now serializes independent `ProjectKeyframeStore` instances sharing a canonical directory with one process-local lock; atomic rename fallback is limited to `AtomicMoveNotSupportedException`. Implementation [542c1785](https://github.com/sunmughan/After-Effects-Android/commit/542c178523e09332ae4e5dca37cf771729bc620d); regression [faf1d30c](https://github.com/sunmughan/After-Effects-Android/commit/faf1d30cca90fbd5c048f46ac328d7221b159bc5): six independent writers × 20 updates, reload asserts all 120 tracks/frames. CI #1128 cancelled/superseded; M2 manifest transaction test is now queued in #1131.

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
