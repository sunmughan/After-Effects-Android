# EffectCraft Android — Phase Acceptance Tracker

Repository: [sunmughan/After-Effects-Android](https://github.com/sunmughan/After-Effects-Android)  
Work branch: `feat/android-app-foundation` · [PR #1 (merged 2026-11-07)](https://github.com/sunmughan/After-Effects-Android/pull/1) · [PR #2 (draft follow-up)](https://github.com/sunmughan/After-Effects-Android/pull/2)  
Last checked: 2026-11-08 16:47 IST (engineering checkpoint)
Latest fully accepted CI SHA `3fbc4a96` includes Add Ellipse (#1222: 176/176 API30 + cold APK match). Add Star callback compile defect from #1237 is corrected in [d0b14cbe](https://github.com/sunmughan/After-Effects-Android/commit/d0b14cbeef0c6baa325442d5180196c9f2699b06); exact push CI #1239 is queued. Shape UID allocator correction [448f2537](https://github.com/sunmughan/After-Effects-Android/commit/448f25377c44f79074776c1cda8ac4394e3d9e0c) is included. PR #1 merged at 4ae22776; PR #2 remains Draft.ncludes native parametric Ellipse creation and UI regression (#1222: 176/176 API30 tests + cold APK match). Add Star callback compilation was repaired in [0d880772](https://github.com/sunmughan/After-Effects-Android/commit/0d88077290cfdc42201cd8e1b5227b8dbfa4e1b1); current M4 global property-UID allocator correction is [448f2537](https://github.com/sunmughan/After-Effects-Android/commit/448f25377c44f79074776c1cda8ac4394e3d9e0c), with exact-head CI #1236 queued. CI #1232's prior JDK setup failed on external Temurin HTTP 504 and its retry was superseded by newer commits. PR #1 merged at 4ae22776; PR #2 remains Draft.  

> This file is the durable acceptance ledger, not a chronological automation transcript. Keep one concise entry per phase and link implementation/test commits. Do not append repeated run summaries; update this ledger in place.

## Current CI / test evidence

- **CI #1237 — source compilation failure fixed** at SHA `4af939a8`: `EditorScreen.kt:1862` invoked `onAddCanonicalStarLayer` from inside `CanonicalProjectEditor`, but the child receives the forwarded callback as `onAddStarLayer`. The parent forwarding itself was already present; the previous 0d880772 change fixed declaration/wiring but left this call-site name wrong. Corrected the invocation to `onAddStarLayer(...)` in [d0b14cbe](https://github.com/sunmughan/After-Effects-Android/commit/d0b14cbeef0c6baa325442d5180196c9f2699b06); no assertions/tests were weakened. Fresh push CI [#1239](https://github.com/sunmughan/After-Effects-Android/actions/runs/42475134041) is queued at exact SHA `d0b14cbe`.
- **CI #1232 — runner download failure (not a source failure)** at documentation SHA `bbbcab33`: [run 42466606804](https://github.com/sunmughan/After-Effects-Android/actions/runs/42466606804) failed during `actions/setup-java@v4` because the Temurin JDK 17 release download returned HTTP 504 twice (runner disk was 26% used after cleanup; no Gradle/Rust/test step ran). The failed JDK job was explicitly rerun; retry is now in progress. No code/test gate was skipped or weakened.
- **M4 UID collision fix committed**: shape creation had reset `build::Ids` to 10,000 for every Rect/Ellipse/Star, risking repeated Shape property/group UIDs across layers and leaving `Project.next_id` behind. [`448f2537`](https://github.com/sunmughan/After-Effects-Android/commit/448f25377c44f79074776c1cda8ac4394e3d9e0c) now allocates from the canonical `project.next_id` counter and adds a Rust regression that creates all three shapes sequentially, recursively verifies item/layer/property UID uniqueness, checks the serialized allocator is ahead of every UID, and round-trips the project. Exact push CI [#1234](https://github.com/sunmughan/After-Effects-Android/actions/runs/42472660181) is pending at SHA `448f2537`; no acceptance claim yet.
- **CI #1227 — FAILURE diagnosed and fixed** at documentation SHA `a7f0b3fc`: Kotlin `EditorScreen.kt:1860` referenced `onAddCanonicalStarLayer` inside `CanonicalProjectEditor`, but the callback had not been declared/forwarded into that private composable. No native/test failure occurred before compilation. [Repair 0d880772](https://github.com/sunmughan/After-Effects-Android/commit/0d88077290cfdc42201cd8e1b5227b8dbfa4e1b1) adds the private callback parameter and wires the parent argument; exact push CI #1229 is running.
- **CI #1228 — workflow_dispatch in progress** at unrelated SHA `a928f36b`; not current branch acceptance.
- **CI #1222 — PASS at exact feature-branch SHA 3fbc4a96**: [PR run 42460342703](https://github.com/sunmughan/After-Effects-Android/actions/runs/42460342703), 2026-11-08 14:01 IST. Both jobs passed: Rust 63/63, dual JNI ABIs, JVM, unsigned APK/AAB + R8, API30 **176/176** (0 skipped/failed), independent cold APK match (`43c55725694bfb8075f196de53cf2a3b4a15931ba429ed3ed3cdd1c03262b5ba`). This accepts Add Ellipse native creation and Android callback test.  
- **CI #1210 — PASS at exact feature-branch SHA c1252f95**: [PR run 42456232210](https://github.com/sunmughan/After-Effects-Android/actions/runs/42456232210), 2026-11-08 12:48 IST. Both jobs completed successfully: Rust 63/63, ARM64+x86_64 JNI, JVM, debug APK, unsigned release APK/AAB + R8, API30 smoke and independent cold APK comparison. This accepts add-layer selection regression on that SHA; it is not the later Ellipse implementation.
- **CI #1207 — PASS at SHA 75aa1a35**: [run 42449625616](https://github.com/sunmughan/After-Effects-Android/actions/runs/42449625616). Rust 61/61, dual ABI, JVM, unsigned APK/AAB+R8, API30 and cold comparison; native Add Rect pixel acceptance.
- **CI #1220/#1221 — superseded/cancelled** at `f9bc2db1`/`2c51abad`; later PR run #1222 passed the complete Ellipse implementation at `3fbc4a96`.  
- **CI #1226 — IN PROGRESS** at Add Star implementation SHA `a5529895`: [run 42464359539](https://github.com/sunmughan/After-Effects-Android/actions/runs/42464359539); PR check #1226 queued/running. No pass claim yet.
- **CI #1183 — PASS**: [run 42444715221](https://github.com/sunmughan/After-Effects-Android/actions/runs/42444715221), SHA ccf60a7c: strict malformed-UTF8 rejection; dual ABI, Rust 61/61, JVM, unsigned APK/AAB+R8, API30 175/175, cold APK compare.
- **CI #1179 — PASS**: [run 42440451096](https://github.com/sunmughan/After-Effects-Android/actions/runs/42440451096), SHA d2ef6771: XML declaration-free/comment-before-root import, 175/175 API30, dual ABI, R8 and cold reproducibility.
- **CI #1151 failure repaired**: invalid unpublished KObjects coordinate replaced by Maven Central KXML 2.3.0 and Gradle Jar transform stripping bundled org/xmlpull/** [cf01caf5](https://github.com/sunmughan/After-Effects-Android/commit/cf01caf53568a13e951edb55fe0a1088230f917c); #1210 proves release R8 passes on SHA c1252f95.
- **CI #1183 — PASS**: [run 42444715221](https://github.com/sunmughan/After-Effects-Android/actions/runs/42444715221), SHA ccf60a7c: strict malformed-UTF8 rejection; dual ABI, Rust 61/61, JVM, unsigned APK/AAB+R8, API30 175/175, cold APK compare.
- **CI #1179 — PASS**: [run 42440451096](https://github.com/sunmughan/After-Effects-Android/actions/runs/42440451096), SHA d2ef6771: XML declaration-free/comment-before-root import, 175/175 API30, dual ABI, R8 and cold reproducibility.
- **CI #1151 failure repaired**: invalid unpublished KObjects coordinate replaced by Maven Central KXML 2.3.0 and Gradle Jar transform stripping bundled org/xmlpull/** [cf01caf5](https://github.com/sunmughan/After-Effects-Android/commit/cf01caf53568a13e951edb55fe0a1088230f917c); #1207 proves release R8 passes at branch head.
- **Local checks**: local Android SDK/Gradle is unavailable in this runtime. GitHub Actions is the executable build/test gate. Physical-device acceptance is never inferred from emulator CI.

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
**Status: PARTIAL; canonical/offline-media foundations and import-integrity fixes CI-accepted through c1252f95; format interoperability/relink parity still open.**

Acceptance: parse/serialize/validate supported project formats; SAF open/save; migration and rollback; missing-media relink; content-addressed media integrity; offline operation; cancellation/progress; corruption fail-closed; concurrency-safe catalog/reference updates.

Evidence/commits:
- Canonical JSON/XML, validation/migration and BOM compatibility; unsupported-format guardrails.
- Async cancellable SAF import and transactional publication/rollback.
- Offline SHA-256 media store, references, relink/GC safeguards and fail-closed corruption handling.
- Cross-instance project-cache serialization: [894abf61](https://github.com/sunmughan/After-Effects-Android/commit/894abf61).
- Canonical repository serializes create/update/load/list/delete/migration across independent instances sharing a normalized root [`d967e7d3`](https://github.com/sunmughan/After-Effects-Android/commit/d967e7d3a707f9b08e1817e40883dd339ed41779); lock identity now resolves symlink aliases via canonical path [`b8a6df4c`](https://github.com/sunmughan/After-Effects-Android/commit/b8a6df4c0bfaac4d20fe38ffb95420f38184d070). Regression runs two concurrent writers and verifies reopened SHA/sidecar integrity and backup cleanup [`3e96834f`](https://github.com/sunmughan/After-Effects-Android/commit/3e96834fe549d2afb76bcd394a96edd557c3d441). Atomic-move fallback now catches only `AtomicMoveNotSupportedException` in document, sidecar and recovery promotions [`76f06b4a`](https://github.com/sunmughan/After-Effects-Android/commit/76f06b4a8527289736d068d2b072eafaf08aba2c). Exact-head CI #1139 queued after the #1137 test-compilation failure was corrected; see above.
- Cross-instance offline media catalog serialization + 120-import regression: [8b79218c](https://github.com/sunmughan/After-Effects-Android/commit/8b79218c84f78830b42c106d8f0a53d815f7e925).
- `OfflineAssetReferences` lock identity now resolves filesystem symlink aliases through `canonicalFile` with a normalized absolute fallback; unique staging and narrow atomic-move fallback are retained: [implementation 60dc6bcd](https://github.com/sunmughan/After-Effects-Android/commit/60dc6bcd49a9b9439a70ab8435128a2e8cfaa9c4). Regression [46c27832](https://github.com/sunmughan/After-Effects-Android/commit/46c2783257fc1b9aa8a5363267fed2bddd74586f) drives 8 independent writers × 25 updates through real and symlink paths (200 unique footage IDs), reopens the canonical file, checks every mapping and verifies staging cleanup. Push CI #1170 [run 42439234144](https://github.com/sunmughan/After-Effects-Android/actions/runs/42439234144) is running on exact test SHA `46c27832`; PR check #1172 [run 42439260823](https://github.com/sunmughan/After-Effects-Android/actions/runs/42439260823) is pending. Parent baseline #1163 passed SHA `f8f616dc` but does not include these commits.
- Footage relink is not an unproven empty-fixture case anymore: `M2DeviceFlowsInstrumentedTest.safCopyNativeRelinkExport` has a real upstream missing-footage fixture, checks JNI relink/path/missing flag, durable repository+manifest update, invalid relink non-mutation and exported path. Native leg is ARM64-gated; x86_64 emulator skips it. Coverage ledger corrected in [docs commit above](https://github.com/sunmughan/After-Effects-Android/blob/feat/android-app-foundation/docs/M7_FUNCTION_COVERAGE.md); physical ARM64 execution remains outstanding.
- Project manifest batch replacement now shares a canonical-root process lock with independent save/load/delete/GC instances; regression injects a mid-batch publish failure and proves a waiting save runs after rollback rather than being overwritten.
- XML content sniffing accepts a declaration-free document with a leading comment before `<EffectCraftProject`; implementation [0a489e9c](https://github.com/sunmughan/After-Effects-Android/commit/0a489e9c7db218f034d3700e98e7642bc1a81655), regression [d2ef6771](https://github.com/sunmughan/After-Effects-Android/commit/d2ef67715a98129d547539f1db1704be2cd49beb). Exact CI #1179 passed all gates at SHA `d2ef6771` (175/175 API30, dual ABI, JVM/Rust, unsigned APK/AAB+R8, cold reproducibility). New strict UTF-8 import decoding rejects malformed byte sequences rather than silently substituting U+FFFD; implementation+regression [ccf60a7c](https://github.com/sunmughan/After-Effects-Android/commit/ccf60a7cf89b7846d00a3126fb786d6e50953098), exact-head CI #1183 [run 42444715221](https://github.com/sunmughan/After-Effects-Android/actions/runs/42444715221) is in progress.
- XML round-trip/strict-parser regression suite [`da3f6e5e`](https://github.com/sunmughan/After-Effects-Android/commit/da3f6e5e6e57c51d024ced9f4db2e5bddc817dc1); JVM implementation uses KXML 2.5.0 without bundling XmlPull API classes (dependency commits above). CI #1145 exposed R8 duplicate platform/program API; corrected, but latest release minification/API30/cold reproducibility must still pass.

Blockers/limits: broad Effekseer `.efkproj` parser/converter and version fixtures are not integrated. Adobe `.aep` is proprietary; no licensed/available parser, so native import is not claimed. Continue with supported EffectCraft JSON/XML and explicit missing-media relinking.

### M3 — Actual composition preview/render integration
**Status: PARTIAL (real native CPU/session preview integrated; device/GPU acceptance open).**

Acceptance: real Rust composition frames via JNI/NativeSession; lifecycle/cancellation; Android frame presentation; GPU path and tested software fallback; Surface recreation/device loss; media decoding and preview correctness.

Evidence: actual Rust CPU/session compositor and JNI lifecycle, native render requests, TextureView/Bitmap presentation and renderer/cache instrumentation. Preview cache hashes canonical project once per accepted revision; stale-frame eviction and Bitmap lifecycle commits: [087704fd](https://github.com/sunmughan/After-Effects-Android/commit/087704fd), [eefac4e1](https://github.com/sunmughan/After-Effects-Android/commit/eefac4e1), [a834d86c](https://github.com/sunmughan/After-Effects-Android/commit/a834d86c).  
Remaining: physical ARM64 GPU/Surface/device-loss and media decode parity/performance; latest workspace overlay semantics test must pass #1124.

### M4 — Functional layer/effect/timeline editing
**Status: IN PROGRESS; native Add Rect and Add Ellipse creation plus new-layer focus are CI-accepted at SHA 3fbc4a96 (#1222); Add Star is implemented in the next commit and pending exact-head CI; full upstream parity remains open.**

Acceptance: canonical properties/transforms update the real project and preview; add/select/delete/reorder/visibility/opacity; Position/Scale/Rotation/Anchor/Orientation and keyframe/interpolation editing; ShapePath and effect parameters; edit → serialize → reopen → preview evidence.

Evidence:
- Native Add Rect creates a real upstream Shape layer (200×100 path + opaque fill), exposed through JNI/Compose and rendered by the upstream compositor; native pixel and Android instrumentation tests: [c41828df](https://github.com/sunmughan/After-Effects-Android/commit/c41828df155086b10f8f309e88e45fdd8085fa5b), [bc75f91e](https://github.com/sunmughan/After-Effects-Android/commit/bc75f91ecaa3abd20727dfa81d053d27a4da0d91), [77901d69](https://github.com/sunmughan/After-Effects-Android/commit/77901d69dd88ab2624ca58b161cc4e57c8cdcb73), [5e3fe4df](https://github.com/sunmughan/After-Effects-Android/commit/5e3fe4df532f1c713866358f13fd99f15c560b57), [75ef2892](https://github.com/sunmughan/After-Effects-Android/commit/75ef2892ae22c28d78f948824ae25d41c565b1bb), [7a449a7a](https://github.com/sunmughan/After-Effects-Android/commit/7a449a7a98596f2c79ddc0ce5466b4a5fc874ff7).
- Add Ellipse authors a real upstream parametric ellipse (160×160 + opaque fill), with native pixel-render and Android callback regression; exact feature-tip acceptance is CI #1222 at `3fbc4a96` (176/176 API30 + cold APK match).  
- **UID correctness**: Rect/Ellipse/Star builders now borrow the canonical project-wide `next_id` allocator instead of resetting property UIDs to 10,000 per layer. Regression creates all three sequentially, checks item/layer/group/property IDs are globally unique and the serialized counter remains above them: [`448f2537`](https://github.com/sunmughan/After-Effects-Android/commit/448f25377c44f79074776c1cda8ac4394e3d9e0c). Exact-head CI #1234 pending.
- Add Star uses upstream `build::shape_star` (5 points, 120/50 radii) + opaque fill; Rust round-trip/compositor pixel test, JNI, Compose action and Android callback regression are in [a5529895](https://github.com/sunmughan/After-Effects-Android/commit/a55298952ce3afe0532fdef3ea1d00ee25e96687); exact-head CI pending.
- Add-layer selection now focuses the new Text/Shape layer in the inspector: [c1252f95](https://github.com/sunmughan/After-Effects-Android/commit/c1252f95c942c6fbe52bc78e1c0d14c890a53c6b) + [CanonicalLayerSelectionTest](https://github.com/sunmughan/After-Effects-Android/blob/feat/android-app-foundation/app/src/test/java/com/codeair/effectcraft/data/CanonicalLayerSelectionTest.kt). Exact CI #1210 passed at that SHA: Rust 63/63, dual ABI, JVM, unsigned APK/AAB+R8, API30 and independent cold compare.
- New parametric Ellipse path (160×160) + white fill is authored by upstream builders, serializes as canonical project data and renders real compositor pixels; JNI bridge + Add Ellipse UI + instrumented callback regression: [native 3a989ee3](https://github.com/sunmughan/After-Effects-Android/commit/3a989ee381257e9135d3aef64b5a66658d3b4d68), [JNI a78bb5cf](https://github.com/sunmughan/After-Effects-Android/commit/a78bb5cf330ef28771fe2319f273f816eb474c0e), [app ab23c18d](https://github.com/sunmughan/After-Effects-Android/commit/ab23c18deae4162f35dce3b89d6671b274c808dc), [UI 34e2800d](https://github.com/sunmughan/After-Effects-Android/commit/34e2800d6f3b7b004a9854ecb83de727ead335d4), [instrumentation f9bc2db1](https://github.com/sunmughan/After-Effects-Android/commit/f9bc2db1641d6cf4d1c52e9344c2d66a99e6bdd5). Exact-head CI #1220 is pending.
- Keyframe-store cross-instance transaction serialization and 120-key regression: [542c1785](https://github.com/sunmughan/After-Effects-Android/commit/542c178523e09332ae4e5dca37cf771729bc620d), [faf1d30c](https://github.com/sunmughan/After-Effects-Android/commit/faf1d30cca90fbd5c048f46ac328d7221b159bc5). ShapePath/de Casteljau, curve inspector and canonical property families are present.

Remaining: complete upstream effect stack, 3D/spatial/roving/interpolation parity, golden edit→reopen→preview fixtures, and physical-device UX.
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

**EffectCraft is NOT release-complete.** PR #1 is already merged (not Draft); do not conflate that merge with M0–M7 acceptance. No native Adobe `.aep` compatibility claim. No phase is marked complete merely because a workflow was queued or a prior SHA passed.
