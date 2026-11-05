**Automation run 11 progress (2026-11-05):** M5 MP4 validator captures AAC first/last sample PTS and track duration, passes measurements through StagedResult, and displays the PTS window in export status. Emulator assertions cover nonnegative/ordered PTS, last sample before video end, positive audio duration and result propagation. Diagnostic only—no sync tolerance/drift correction; physical playback remains open. See PHASES.md Automation run 11.

**Run 107 progress (2026-11-05):** CI #718 exact log exposed Android-test compile errors in M7FunctionCoverageTest: Compose `and` and `onNode` imports are unavailable in the pinned library. Fixed with a production accessibility description on the export FPS field (`51c57fba`) and stable `onNodeWithContentDescription("Export FPS")` test selector (`0d8ce18`); original invalid-FPS/zero-export assertions remain. CI #724 (41952018730, HEAD 91e873f4) is still at runner disk cleanup and is superseded by the tracker-head commit; exact-head CI #725 (HEAD 84d3ab0a) is pending. No pass claimed. See PHASES.md Run 107.

**Run 106 progress (2026-11-05):** MP4 audio resolver now accepts only existing readable regular offline payloads and verifies SHA-256 + indexed size through OfflineAssetStore before MediaExtractor; missing/directory/unresolved/corrupt SHA assets surface as AUDIO_FILE_UNAVAILABLE instead of AUDIO_TRACK_UNSUPPORTED. Added resolver regressions for missing/unreadable/unverified payloads and same-size bit-rot rejection through OfflineAssetStore. Resolver commits `2bc4acec`, `44b47ee0`, `a161343e`, `29b1db6b`; same-size corruption test `c0b4201f`; docs `8ddb7c25`, `34ed2c2c`, `69480e90`, `a7d80972`. CI #705–#714 were superseded; exact-head #715 (41948923308, `a7d80972`) is in progress at runner disk cleanup; no verdict yet. See PHASES.md Run 106.

**Run 103 progress (2026-11-05):** M5 foreground PNG ZIP export now fsyncs and atomically publishes its interruption marker before rendering; SAF-copy progress is byte-accurate 0→100% (previously showed 100% before copying). Added JVM marker-metadata and monotonic copy-progress regressions. CI #695's one failure was the offline permission allowlist missing the new foreground-service permissions, fixed in 246d7123; #697 was still at ARM64 build and is superseded by this head. Exact-head verification pending. See PHASES.md Run 103.

# EffectCraft for Android

**Run 100 progress (2026-11-05):** exact CI #685 (run 41925868774) built ARM64+x86_64 native/JNI successfully but Rust tests failed to compile: three cache-lifecycle fixtures still called the new 7-argument render_parsed_frame with six args (E0061). Fixed by passing explicit None cache in uncached CPU/live/retired fixtures (`c0656d28`); assertions and production paths unchanged. Fresh exact-head CI is pending; no pass claimed. See PHASES.md Run 100.

**Run 99 progress (2026-11-05):** CI #679 (run 41920303396) had two API30 failures: rotation test expected stale `Background` after New → Layer (fixed in commit `8cd508da`), and 48-layer/288-key preview measured 36.431ms mean / 46.7% jank against agent-proposed budgets. Native preview now owns an upstream content-keyed `LayerCache` (64 MiB cap) across frames (`b4e2e1f9`); JNI exposes cache counters and the benchmark asserts reuse/budget (`ea713959`). CI #682/#683 were superseded; latest exact-head CI pending. No benchmark improvement/pass claimed until JUnit is inspected. See PHASES.md Run 99.

**Run 100 CI follow-up:** #686 (c0656d28) was cancelled by subsequent docs pushes before Rust tests; #688 (HEAD `0b1e881`) is pending. Cancellation is supersession, not test evidence. Run 100's exact compile error and fix are in PHASES.md. Latest documentation commit will trigger a new exact-head run; no pass claimed.

### Run 90 — preserve standalone EditorScreen test/API compatibility
- Follow-up review found that direct Compose test fixtures instantiate EditorScreen by named arguments and do not exercise the production MP4 callbacks. The newly added MP4-only parameters now have safe no-op/default values, preserving existing test fixtures and internal preview embedding while production EffectCraftApp supplies real callbacks. This is compatibility, not a fake encoder path: only the production app wires SAF/MediaCodec.
- Commit changes the parameter defaults only; exact-head CI #625 is pending for eb229b7a and will be superseded by this commit. Continue to inspect #626 and add a direct MP4 settings interaction test once the current compile is verified.
- M5 still needs current-head unit + emulator encoder/demux, SAF output validation/playback, audio and durable background queue. No M5 completion claim.

### Run 89 — wire H.264 MP4 into the editor export flow (2026-11-04)
- M5 product integration: the export settings dialog now switches between PNG ZIP and H.264 MP4, exposes bitrate for video, and validates composition dimensions/frame range/FPS before invoking the output picker. The editor toolbar exposes MP4 export and a cancel/progress action.
- EffectCraftApp adds a `CreateDocument("video/mp4")` SAF path. It opens a canonical NativeSession once, streams requested frame RGBA into `Mp4SequenceExporter` on Dispatchers.IO, updates progress, closes the session, and deletes the partial document on cancellation/failure. Audio is explicitly not included; no fake fallback is produced if the device has no flexible-YUV420 AVC encoder.
- Source implementation and UI wiring are in this commit; exact-head CI has not yet compiled or instrumented this change. Codec execution/demux, physical device validation, and playback/output validation remain acceptance gates.
- Prior reliable evidence: CI #621 (run 41859059337, b19db13f) was fully green including cold APK comparison, unit 179/179, connected 78/78, Rust 41/41; it predates this MP4 UI integration. Current #623 (41867527215) was in progress on prior docs head b9506c19 and is superseded by this implementation.
- M5 remains IN PROGRESS: PNG/ZIP and H.264 encoder path are implemented and MP4 is now reachable from UI/SAF, but exact-head compile/instrumentation and real codec/demux acceptance, audio, and durable background recovery are open. M0 reproducibility/owner acceptance, M2 physical SAF/media, M3 GPU/device loss, M4 remaining curve/property parity, M6 device/performance/accessibility, M7 signed release/install remain open. No native Adobe .aep or Effekseer .efkproj support claim.

### Run 84 — correct M4 acceptance summary for precise Bezier controls
- Phase summary/checklist now records the committed curve key move/delete, per-side Bezier handle drag and Speed/Influence slider UI, including the preview-coherence correction. This replaces the stale unchecked bullet that said graph editing/key dragging was still absent; roving/spatial and wider property parity remain explicitly unchecked.
- CI #587 is running on d65dae3f, which contains f148ad82 + de61d322; #588 on docs head ab4c8d80 is queued. No acceptance is claimed before exact logs/JUnit/XML are inspected.

### Run 83 — CI evidence and M0 gate correction
- CI #582 Assemble/test passed (39 Rust, 72/72 emulator, JVM task success); full workflow was cancelled before cold APK reproducibility verdict. #587 queued for current docs head d65dae3f.
- Owner's personal-only/no-open-license intent is settled; M0 remains incomplete for reproducibility + owner acceptance #13.
- Bezier precise speed/influence controls committed; tests await #587.

### Run 82 — Bezier preview-state correction
- Fixed speed/influence sliders to compose against the current local graph preview instead of stale canonical values; bounded speed to 1e6 before Float conversion. Commit de61d322.
- CI #585 was superseded; exact-head #586 pending. No test pass claimed for the UI yet.

### Run 75 — roadmap evidence consolidated
- Phase summaries now reflect CI #545's completed Assemble/test job (Rust 38/38, JVM task success, emulator 70/70) while correctly leaving the cancelled cold APK comparison unaccepted. M0 owner acceptance remains open; M1 scoped foundation accepted; M2/M3/M4/M5/M6/M7 remain at their documented partial/in-progress states.
- M4 includes native Anchor/Orientation/Rotation X/Y keyframes and graph Add-at-playhead; recursive Anchor lookup fix and connected test committed. Exact combined-head CI #560 is pending; no pass claimed for Runs 73–74.

### Run 74 — 3D transform graph wiring
- Fixed nested Anchor property lookup in native transform keyframe authoring/interpolation/removal/metadata. Curve graph Add-at-playhead now supports all transform selectors and seeds values for Anchor, Orientation, X/Y Rotation; added API 30 regression for graph + inspector add/remove. Commits 1ede3dfc, 6a0ef932, b5f08a7a, b0600faf.
- Exact-head CI after combined changes is pending; no pass claimed. M4 easing/roving/effect parity remains open, as do M0 owner acceptance, M2/M3 physical validation, M5 video/audio, M6 device matrix and M7 release.

### Run 73 — native 3D transform keyframes (2026-11-04)
- Native key authoring/metadata/interpolation/removal now includes Anchor, Orientation XYZ, X/Y Rotation in addition to Position/Scale/Rotation. Inspector Add/Remove actions and curve selector options are wired; Rust serialization/interpolation/removal and API 30 callback regressions added. Commits cea771d3, 7215e073, 177e4d7b.
- Exact-head CI #552 is pending; no pass claimed. M4 still needs full effect parity, spatial/roving interpolation, graph controls and gesture work; M0 owner acceptance, M2/M3 physical validation, M5 video/audio, M6 device matrix and M7 release gates remain open.

### Run 72 — native curve-key long-press deletion (2026-11-04)
- Bezier graph supports pointer long-press deletion within the key hit radius, routed through canonical remove-key callback; drag editing remains separate. API 30 regression asserts frame-15 hit/callback on a 90-frame graph. Commits e9956366 (implementation), 5e8d541c (test).
- CI #544 exposed an instrumentation compile issue (non-public SemanticsProperties.CustomActions); fixed using official performCustomAccessibilityActionWithLabel in d4fdd620. CI #545 on that correction passed native/Rust/JVM and 70/70 emulator tests; cold APK reproducibility was still in progress. New long-press test exact-head CI #547 is pending; no pass claimed.
- M4 graph key editing continues; easing/velocity graph and full effect parity remain open. M0 owner acceptance, M2/M3 physical device gates, M5 video/audio, M6 device matrix and M7 release remain open.

# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

> **Progress (2026-11-04, latest: M6 benchmark validation + #641 UI failure fix):** M1 scoped editor accepted; M0 owner acceptance and current-head cold reproducibility remain open. M2 canonical .ecproj JSON/.ecprojx XML and offline media workflows exist; physical SAF/recovery acceptance remains; .efkproj/.aep support is not claimed. M3 upstream compositor/session is integrated; physical GPU/Surface/device-loss validation remains. M4 2D/3D transforms, keyframes, Bezier and directional ease implemented; current-head preset tap failure from #641 was corrected in #644 (pending). M5 H.264 export is wired to editor settings/SAF; codec/audio/durable recovery remain. M6 fsynced recovery journal, cache-accounting validation and emulator 48-layer/288-key performance benchmark implementation committed; thresholds are proposals, #644 pending, physical performance/thermal/accessibility matrix open. M7 release gates incomplete. See [PHASES.md](PHASES.md). M1 scoped editor accepted; M0 owner acceptance and current-head cold reproducibility remain open. M2 canonical `.ecproj` JSON + `.ecprojx` XML and offline media workflows exist; physical SAF/recovery acceptance remains; `.efkproj`/`.aep` support is not claimed. M3 actual upstream compositor/session is integrated; cancellation reaches native work but CPU `comp_frame` is non-preemptible and physical GPU/Surface validation remains. M4 2D/3D transforms, keyframes, Bezier handles/Speed/Influence and directional ease are implemented; settled CI #621 verifies the earlier graph scope; #628 containing directional ease was superseded/cancelled before verdict, #631 is in progress on test head 25997663 and #633 is pending current docs head a3769591. M5 H.264 MediaCodec→MediaMuxer export is wired to editor settings and SAF `video/mp4` output (eb229b7a); codec/demux/UI exact-head CI ( #631 in progress; #633 queued for docs HEAD), audio/background recovery remain open. M6 fsynced recovery journal plus strict native media-pool counter/budget validation (c1554fb1/25997663) are committed, awaiting exact-head CI; physical performance/accessibility/device matrix remains. M7 release gates are not complete. See [PHASES.md](PHASES.md) for evidence and blockers.

Run 60 connects the canonical preview scrubber and workspace timeline playhead in both directions, with a Compose regression requesting native frame 15. Exact-head CI is pending; transform keyframe authoring remains unimplemented.

## Product direction

- Native Android application; no WebView dependency.
- One adaptive workspace for phones, tablets, and foldables.
- Offline-first project creation, editing-state persistence, and recovery.
- Shared project and rendering core integration with [EffectCraft](https://github.com/storytold/effectcraft) as a dedicated follow-up milestone.
- Vulkan/GPU rendering and hardware media pipelines only after device-level compatibility work.

## Build

Open the project in Android Studio with JDK 17 and Android SDK Platform 37, or use the committed pinned Gradle Wrapper:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The official Gradle Wrapper (9.5.0 with distribution SHA-256) is committed: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle-wrapper.properties`.

The minimum Android version and required SDK packages are documented in [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md).

## Offline by design

The Android manifest intentionally does not request the `INTERNET` permission. The bootstrap application stores draft projects in app-private storage. The ARM64 JNI bridge is built in CI; Android Storage Access Framework import/export and complete project editing/rendering remain implementation milestones.

## Development status

- **M0 foundation: NOT COMPLETE** — CI #582 Assemble/test on source 33af117d passed dual ABI/JNI, Rust 39/39, Gradle/JVM tasks and API 30 emulator 72/72; its cold APK comparison was cancelled before verdict. Current exact head d65dae3f is queued in #587. Owner has resolved license intent as personal-only/no open license (Run 64); owner acceptance #13 and current-head reproducibility remain open.
- **M1 editor foundation: COMPLETE (scoped)** — CI #346 passes 19/19 emulator tests including named native parser invocation, delete/reorder/Undo/Redo, offline project open/save, XML round trip, media-library empty state and adaptive workspace.
- **M2: IN PROGRESS.** EffectCraft `.ecproj` JSON and `.ecprojx` XML import/export, native validation and format guardrails are present. OfflineAssetStore, SAF relinking, persistent project manifests, sequence import, grant recovery and reference-aware GC/replace are implemented (cluster commit `4a75700c`); #349 exposed a missing `java.io.File` import in its sequence test, fixed in `a9879121`. Exact-head JVM/emulator verification pending. Effekseer `.efkproj` has no parser/converter; Adobe `.aep` is proprietary with no licensed parser. `.efx-draft` uses offline Open, not canonical import.
- M3 uses the actual upstream CPU compositor through JNI for canonical frames. Exact-head CI #369 (`d696c56`) verifies shared native session/viewer lifecycle, cancellation, resize and teardown (Rust 21/21, JVM 61/61, emulator 22/22; cold APK content/native ABI reproducibility PASS). GPU backend status/device-loss recovery exists, but physical ARM64 GPU/Surface path and interruptible CPU rendering remain open.
- **M4 IN PROGRESS:** canonical opacity and 2D/3D transform keyframes serialize through upstream model and update actual CPU preview. Curve graph supports key move/reorder/delete, per-side Bezier handles and precise Speed/Influence sliders with local preview + canonical commit-on-release (f148ad82, preview-coherence fix de61d322). JVM/API30 regressions are committed; CI #587 executing on feature source d65dae3f and #588 queued for current docs head. Roving/spatial paths and broader property parity remain open.
- **M5 IN PROGRESS:** native PNG + configurable/validated PNG-sequence ZIP and streaming H.264 MP4 are implemented; MP4 settings, bitrate, progress/cancel and SAF `video/mp4` output are integrated in the editor. Exact-head codec/demux/UI validation, audio, durable background recovery and physical device budgets remain open. Bounded SAF copy has rollback callback/best-effort provider deletion; provider behavior varies. Video codecs/audio, background render service, interruption recovery and physical memory/thermal acceptance remain.
- **M6 IN PROGRESS:** durable fsync-before-promotion, interrupted draft/keyframe recovery, stale staging cleanup, asset quotas/rollback and accessibility semantics implemented; exact latest-head tests plus physical device/API/performance/TalkBack matrix remain. **M7 NOT STARTED:** signed configurable APK/AAB, release notes/license review and install/run evidence remain.

M0/M1 acceptance evidence: [CI #286](https://github.com/sunmughan/After-Effects-Android/actions/runs/41588154232). See [PHASES.md](PHASES.md) for per-phase acceptance, test evidence, commits, blockers and release gates; [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md) for product milestones; and [docs/NATIVE_ENGINE_INTEGRATION.md](docs/NATIVE_ENGINE_INTEGRATION.md) for JNI/render integration details.


### Run 11 — offline media library UI and regression correction (2026-11-03)
- CI #309 completed native ARM64+x86_64, JNI packaging, 13 Rust tests, license inventory, APK and Kotlin compile, then failed unit tests: 29 completed, 1 failed at `OfflineAssetStoreTest.kt:48` because the test expected a bare hash filename after payloads were intentionally changed to hash + source extension. Fixed assertion to verify one hash-prefixed `.bin` payload; commit `ff9b71ed`.
- Added offline media library browser/removal confirmation in editor; catalog refreshes after SAF relink and removal. Commits `1ba479ca`, `313cae9f`, `2f829e6f`.
- Exact-head CI #312 is pending; no current-head pass claimed. See PHASES.md Run 11 for logs, evidence and remaining M2 blockers.

- Offline media library now browses app-private assets and confirms removals; catalog refreshes after relink/remove. Catalog recreation/sort test `114e257e` supplements the four existing store tests. Full M2 asset-reference safety and recovery remain open.

- Reference-aware media deletion is guarded in both UI and app callback; catalog test covers restart/sort, and reference tests cover persistence/replacement/removal. Latest implementation commit `2fcff068`; exact-head CI pending.

- API 30 instrumentation now checks the offline media library header action and empty-state dialog (`fe195564`); current-head CI pending.

- Canonical import/relink persistence is now transactional with rollback of asset-reference mappings on project-cache save failure (`79fb8566`).

### Run 26 — current-tip CI evidence + project-scoped keyframe persistence (2026-11-03)
- Exact branch-tip CI [#337](https://github.com/sunmughan/After-Effects-Android/actions/runs/41609309110) on `7478f235f57c4f6b5df73b371157b60895a6d283` completed **success**: dual-ABI native builds, JNI packaging, Rust 13/13, APK assembly, JVM 32/32, Android API 30 emulator instrumentation 19/19 (0 skipped/failed), and build/audit + test-result artifacts uploaded.
- Inspected `effectcraft-android-test-results` artifact ID `13218447950` (110,777 bytes). Seven JVM suites contain 32 testcases, all 0 failures/errors/skips; connected XML contains 19/19. Named tests individually prove native parser invocation, delete/reorder/Undo/Redo, offline project open/save, XML round trip and media-library empty state.
- M4 added `ProjectKeyframeStore`: versioned project-scoped JSON with SHA-256-derived filenames, atomic writes, sorted scalar tracks, same-frame upsert, key removal/empty-track cleanup, project clear and strict identity/property/frame/value/schema validation. Four JVM test methods cover persistence/isolation/replacement, removal/clear, invalid inputs and project-document mismatch. Implementation, four JVM tests and tracker updates are included in commit `b4a7b286ebe1d9af9fb6ccfbe1ecafba6993804e`; exact-head CI for the new M4 code is pending.
- Stored keyframes remain editor-side draft data; they are not yet wired to timeline UI, canonical `.ecproj` serialization or native renderer evaluation. M4 remains incomplete. M0 remains NOT COMPLETE pending license and owner acceptance; M1 scoped foundation remains COMPLETE; M2–M7 remain open.

### Run 28 — reproducibility accepted; opacity keyframe UI connected (2026-11-03)
- CI #346 passed exact-head Android build/test and cold reproducibility: JVM 36/36 (ProjectKeyframeStore 4/4), emulator 19/19; artifact #13221995403 inspected. Repro artifact #13223045108 confirms 129/129 content entries and both packaged ABI libraries match; raw debug signature differs by ephemeral key. M0 licensing/owner gates remain open.
- Timeline now has K+/K− opacity keyframe actions, selected-layer count/frame markers, immediate project-scoped persistence and removal; new instrumentation covers frames 0/30 and storage reload. New UI/test exact-head CI pending; keyframes do not yet affect canonical/native rendering.


### Run 33 — native session acceptance + first real PNG export (2026-11-03)
- CI #369 exact HEAD `d696c56`: both jobs SUCCESS; Rust 21/21, JVM 61/61, emulator 22/22; test artifact #13231085479 inspected (14 suites/61 cases and connected 22, all zero failures/errors/skips). NativeSession emulator render/resize/cancel/close and invalid-project tests passed.
- Repro artifact #13232051534 inspected: 129/129 APK content entries match, no content differences, both packaged ABI .so hashes match, content/native verdict PASS. Raw debug signing differs by ephemeral key only.
- M3 shared session/lifecycle is now acceptance-evidenced; GPU/Vulkan, in-flight CPU cancellation and physical ARM64 still open.
- M5 implementation commit #09c1f1b adds one-frame full-resolution native render→PNG SAF export, validated RGBA conversion/pixel budget, status progress and cancel-before-write. Two emulator tests cover pixel fidelity and malformed/oversized input; exact-head CI pending. Full queue, codec/audio, cancellation/recovery and output validation remain.


### Run 35 — render queue planning groundwork (2026-11-03)
- Added bounded `RenderQueuePlan` for inclusive PNG-sequence frame ranges, deterministic ZIP entry names, frame progress and resolution/FPS validation. Hard limits: 10,000 frames, 8,388,608 pixels/frame and 8 GiB estimated RGBA. Four JVM regression methods cover range, progress, invalid inputs and UHD budget.
- This is a planner only: the current app exports one native-rendered PNG frame; ZIP sequence execution, video codecs/audio and UI settings remain unimplemented. Exact-head CI pending.


### Run 36 — streaming PNG sequence ZIP core (2026-11-03)
- Added a streaming ZIP executor that renders one PNG at a time from a bounded plan, validates PNG dimensions, writes stable frame entries, reports progress and checks cancellation. Three JVM tests cover byte-preserving ZIP output, progress, cancellation and invalid frames. Commits `1910de56` + `432f85ca`.
- Standalone kotlinc + executable smoke harness passed ZIP order/payload, 50/100 progress, cancellation and dimension rejection. CI on this exact head is pending.
- Not yet wired to Android SAF export UI; cancellation can leave a partial output and must be handled by staging/cleanup. Video/audio not implemented.


### Run 44 — offline media storage quota
- OfflineAssetStore now has a configurable 2 GiB aggregate library budget (in addition to 512 MiB per asset), checked before publishing new content; SHA-256 duplicates count once and retain existing metadata. Index-write failure removes a newly published unindexed payload.
- Added JVM quota/dedupe/cleanup regressions. Exact-head CI #392 fixes the prior Rust ImageEncoder trait compile failure and is running; this new change requires a fresh CI run.


### Run 45 — composition-bounded render frame range
- PNG-sequence range is now validated against canonical composition duration before SAF output creation and rechecked before rendering; invalid end-frame selections no longer create empty ZIP documents.
- Added composition-duration boundary regression. Latest exact-head CI is pending.

 
### Run 46 — media index write rollback regression
- Corrected OfflineAssetStore import rollback to remove newly staged content when index publication fails; added fault-injection coverage for index temp-path failure. Aggregate media quota/dedupe changes from Run 44 are preserved.
- Reconstructed commit tree retains Run 44 media quota and Run 45 composition-bounded PNG sequence frame validation.
\n### Run 47 — abandoned render staging recovery\n- Startup removes only EffectCraft sequence cache ZIPs older than 24 hours, preserving recent/unknown/future timestamps, unrelated files and directories. This recovers files left when process death bypasses coroutine cleanup.\n- Added 3 JVM tests and integrated the cleanup in startup reconciliation. Commits `c202d8e8`, `fdeecc98`, `19c263a8`; fixture correction `cc72496d`; tracker `1cf4e00d`. Local kotlinc smoke passed for stale deletion and unknown/future/directory retention; startup scan runs on Dispatchers.IO. CI/JUnit acceptance pending.\n\n\n### Run 51 — draft recovery instrumentation (2026-11-04)\n- Added two Android device tests for interrupted atomic saves: promote a complete newer temp; discard malformed temp without touching the committed draft. Commit `db1f87f5`; test assertions preserve both safety paths.\n- CI #422 was inspected at job/step granularity: native ARM64+x86_64, ABI checks, Rust and dependency inventory passed; APK assemble was active, unit/emulator pending. Exact-head CI for the new test is pending; no green claim.\n- See `PHASES.md` Run 51 for acceptance detail and blockers.\n\n\n### Run 52 — SAF export write safety (2026-11-04)\n- Added bounded, cancellation-aware SAF copy with rollback callback and 4 JVM tests; integrated app-private staging plus best-effort provider document deletion for incomplete PNG/ZIP exports. Commits `e29b1356`, `deef8110`, `68346260`.\n- CI #421 green on older containing head; #427 queued and fresh exact-head run after integration pending. See PHASES.md Run 52.\n\n\n### Run 53 — SAF source-open rollback edge case\n- SAF copy now opens the staging input inside its rollback-protected scope; a staging read-open failure also deletes the just-created destination when provider permits. Added a fifth JVM regression method. Commits `d4ac0db2`, `81656806`, `7fd56189`.\n- Exact-head CI pending; see PHASES.md Run 53.\n\n\n### Run 54 — SAF input-open rollback correction\n- Fixed actual helper signature and all JVM test call sites to use `openInput`; source-open failures now enter rollback. Source re-read confirms consistency; exact-head compile/test pending. Commits `2f6e3015`, `95f5d1da`.\n

### Run 56 — canonical preview twist rotation (2026-11-04)
- Preview gestures now apply twist rotation to selected unlocked canonical layers through the native canonical rotation setter, grouped with pan/zoom into one edit-history batch. Rotation input is bounded and invalid values ignored; two JVM regression methods added and the existing selection/gesture emulator test extended. Implementation commit [79ff0094](https://github.com/sunmughan/After-Effects-Android/commit/79ff0094153ef9a000ea46dd7dda34997c3dc1f2).
- CI #444 failed Android test compilation on unresolved `assertDoesNotExist`; corrected in 86cac29 with equivalent zero-node count assertion. #445 at 86cac29 had native/Rust/JVM/APK steps green and emulator in progress; rotation commit requires fresh exact-head verification. See PHASES Run 56.


### Run 57 — canonical gesture instrumentation repairs (2026-11-04)
- #445 failed two emulator cases: canonical test fixture never supplied selected IDs (rendered "○ Headline" rather than expected "● Headline"), and preview swipe started at exact left boundary so callback did not fire. Fixed fixture selection and inset swipe coordinates in [c45eadf5](https://github.com/sunmughan/After-Effects-Android/commit/c45eadf55d941d16a432c0b701c4e96203fefee2). Tests remain intact.
- #448 exact-head CI is pending on source + test repair; #446 was cancelled by a newer push, #447 docs run in progress on prior source. See PHASES Run 57.

### Run 61 — durable offline writes + gesture CI diagnosis (2026-11-04)
- DraftProjectStore and ProjectKeyframeStore now fsync complete temp-file bytes before atomic promotion (commits `6c3ffc24`, `36a1430d`); validated interrupted-write recovery remains in place.
- CI #457 failed the canonical preview gesture wait at 5 seconds. Current #458 changes preview input to proven single-pointer drag and commits one position edit on drag end; pinch/twist is explicitly not wired at this head. #458 remains in progress; exact latest #460 (36a1430d) is pending, and docs commit #27860f81 requires its own current-head run.
- M6 fsync/recovery regression tests need exact-head JVM/emulator verification. See PHASES.md Run 61.

### Run 62 — native transform keyframe controls (2026-11-04)
- Added JNI and inspector Key Position/Scale/Rotation actions at the shared timeline frame. Native properties are seeded at frame zero and keyed through upstream model serialization/interpolation; Compose regression checks the position-key callback values. Commits `ac7a279f`, `25a47596`, `cf06fb8a`, `76680c13`, `d0a67bea`, `1b4fa83a`.
- Latest source/test exact-head CI is pending (#467 queued after #462 docs run); no test pass claimed. Preview drag uses single-pointer detector only; pinch/twist not wired. See PHASES.md Run 62.


### Run 63 — native transform key removal + CI green
- CI #471 passed exact tested HEAD `71414e3`: ARM64+x86_64 native, JNI ABI, Rust 33/33, JVM, API 30 emulator 34/34, cold APK reproducibility. M4 #8 (selection/gesture/history) is marked PASS in PHASES with exact named evidence.
- Added Position/Scale/Rotation remove-key controls at the shared playhead, native idempotent key deletion/static restoration, canonical save/history wiring and Rust/Compose regression coverage in [aef523c0](https://github.com/sunmughan/After-Effects-Android/commit/aef523c0ff239b494df26232fe96624fff62db55). Exact-head CI #473 pending.


### Run 64 — native transform keyframe navigation (2026-11-04)
- Canonical inspector now lists Position/Scale/Rotation key frames from native metadata and jumps the shared preview/workspace playhead to the nearest key on tap. Rust metadata uses the same Tick/frame-rate conversion as renderer; Kotlin parser sorts/deduplicates and has a new JVM regression. Code commit a37275a5.
- CI #475 failed test compilation because JUnit fail() in Elvis fallback widened bridge values to Any; corrected with throw AssertionError in c618641e, no test weakening. #476/#477 were cancelled by subsequent pushes; #482 is still in progress on an older docs head and latest exact-head #485 is pending. No pass claimed.

- Transform-key metadata JVM fixture corrected to assert absent empty tracks (commit e051cec1); latest-head CI pending.

- Added API 30 instrumentation for native transform keyframe navigation: scrub to frame 20, jump to nearest Position key at 15 and assert shared playhead (commit c020ac86); exact-head CI pending.


### Run 65 — canonical transform key markers on timeline
- Selected canonical Position/Scale/Rotation key frames now appear as an accessible TRANSFORM timeline track, aggregated from native inspection metadata. The API 30 regression asserts frames 0/15/30 are visible and continues to verify the jump action synchronizes workspace playhead/native preview.
- Commit 39635738ee64fc2fe68108567b3450601828bc18; exact-head CI #488 queued. CI #487 on older ca69f6a0 had completed dual-ABI native build/package checks and was running Rust tests at inspection. No test pass claimed for the new timeline row until #488 finishes.

- Run 65 follow-up: timeline transform markers are tappable and scrub to the nearest native key; API 30 regression checks playhead/frame request. Commits 91795861 + b3872b8a; exact-head CI pending after successive pushes.

- Marker regression placement follow-up (2fd99d5d): tap the workspace timeline before opening the canonical modal, then verify nearest-key seek; exact-head CI pending.

- Canonical transform track now uses the selected imported composition's duration (not local draft duration) for marker position and tap seeking; test fixture covers 90-frame canonical duration (618553d4). Exact-head CI pending.


### Run 66 — accessible transform keyframe seeking (2026-11-04)
- TRANSFORM marker lane exposes a screen-reader semantic click action that seeks the nearest canonical key relative to the current playhead; connected regression now uses Compose `performClick()` rather than only pointer input. Commits `8e17934c`, `a69ae2d5`, tracker `944e5c88`.
- CI #501 failure was a visibility assertion after a successful frame-15 seek; the label was below the modal scroll position. Fix `b217fea3` scrolls the frame label into view without removing the assertion. CI #502 was running on `b217fea3`; the accessibility commits need a fresh exact-head CI. No new test pass claimed.

- Accessibility regression refinement (`e77da4ea`): semantic click seeks nearest key to current playhead, so test begins at frame 29 and expects frame 30. Tracker `d1b39c87`; CI after this test/docs head pending.


### Current scheduled engineering check — accessible timeline + acceptance tracker (2026-11-04)
- Added a screen-reader semantic click action to the native transform marker lane, seeking the nearest key to the current playhead; pointer tap behavior is unchanged (`8e17934c`). Connected test now exercises `performClick()` at frame 29 and expects key 30 (`e77da4ea`).
- CI #501 failure was only a stale frame-label visibility assertion after successful frame-15 seek; the test now scrolls the unchanged assertion into view (`b217fea3`). Current accessibility/test/docs exact-head CI #510 is queued; earlier #507 is running on a superseded test head. No current-head green claim.
- PHASES updated: M4 transform author/remove/nav is checked as implemented while easing/graph remains open; M5 ZIP validation and SAF rollback are evidence-recorded; M6 semantic accessibility action is checked as code, TalkBack/device matrix remains open (`60a726c8`).

### Run 67 — transform interpolation presets
- Canonical transform inspector now lets users select Position/Scale/Rotation and apply Linear, Easy Ease or Hold to a key at the current playhead. Native upstream keyframe structs are edited and serialized; edit history, project persistence and renderer refresh use the existing canonical edit path.
- Rust regression checks interpolation/ease serialization and rejection paths; API 30 Compose regression checks Easy Ease callback for Position frame 15. Implementation `82446105293936632411bf242d7f67e50c4cc5fa`, import cleanup `109eb4864437b4f0463cab706eac120fd51e945f`, instrumentation `2f9f4f6bc2265ea05d63bccd42432657f188bef1`. CI #514 remains in progress on older f81305e1 (x86_64 native build step 13); #516 queued on earlier interpolation code and current exact-head CI pending. No pass claimed.


### Run 70 — accessible curve-key manipulation
- Curve graph keys expose TalkBack custom actions to move a selected key to the shared playhead; pointer dragging remains unchanged. Connected regression asserts callback values for Position frame 15→0. Implementation/test commit `fd8a5791853cbaad809a769e0bd63e3a18e9720a`.
- CI #537 on the immediately preceding curve graph commit `7ec85e56` passed its Assemble/test job: Rust 38/38, JVM 146/146 and API 30 emulator 68/68, all zero failures/errors/skips; test XMLs inspected. Cold APK reproducibility job was still in progress. Exact-head CI #538 queued for the accessibility commit; no new regression pass claimed.
- M4 Bezier graph supports per-side handle editing and key move/reorder for transform, opacity and native UID parameter tracks; broader effect UI parity remains. M6 TalkBack/manual/device matrix and all M0/M2/M3/M5/M7 gates remain open. No `.aep`/`.efkproj` compatibility claim.


### Run 71 — native curve-key deletion (2026-11-04)
- Native canonical curve-key removal now covers transform and param UID tracks, last-key static restoration, and idempotent missing-key behavior. TalkBack exposes Delete key actions and commits through the normal edit/history/save/preview path. Rust + API 30 regression coverage is committed; exact-head CI #541 is pending.
- Run #539's Kotlin failure (missing `customActions` import) was diagnosed from the exact log; #540 adds the import and is running. See [PHASES.md](PHASES.md) Run 71 for evidence and blockers.


### Run 76 — first transform keyframe correction (2026-11-04)
- CI #561 failed Rust 38/39: first Orientation key at frame 15 had zero value. Diagnosed upstream set_animated seeding static values at the requested non-zero tick; fixed by zero-seeding, writing the authored key, and deleting only the synthetic zero seed for first non-zero-frame keys. Regression asserts exact key count/time/value for Orientation and Rotation Y.
- Commit [e5a05113](https://github.com/sunmughan/After-Effects-Android/commit/e5a0511349e4edee672d153a0d6010f6df02ab93). Fresh exact-head CI pending; no pass claimed. See PHASES.md Run 76.

- Run 76 follow-up: Orientation regression now inserts frame 15 then frame 30 and checks both authored vectors and exact key times, preventing regressions in multi-key tracks after removing the synthetic zero seed. Test commit [04baebc7](https://github.com/sunmughan/After-Effects-Android/commit/04baebc7763fd929892db46bdb909cc8eec10ef8); exact-head CI pending.


### Run 79 — stabilize curve selector regression and align Kotlin/native transform whitelist (2026-11-04)
- Inspected PR #1 branch HEAD 5f89d50c21f9788c9a20e107d834074ce139b900, current Actions run #578 and failed predecessor runs #574–#577. CI #574 asserted Anchor curve add but captured Position; #575 failed header visibility; #576 attempted nonexistent Compose assertExists; #577 used fetch-only assertion but still failed to select Anchor. Root cause: Anchor X is inside a horizontally scrolling FilterChip row; vertical performScrollTo did not reveal the clipped chip, and touch taps on clipped bounds were intermittent.
- Production curve selector row/chips now carry stable test tags and selector content descriptions. Regression scrolls the row horizontally until Anchor X is displayed, then invokes its semantics click and verifies the selected graph header before asserting Add-at-playhead payload. This tests a deterministic, accessible action rather than retrying taps.
- M4 integration fix: Kotlin NativeProjectBridge whitelist for transform key insertion/interpolation/removal now matches native seven-property set (position, scale, rotation, anchor, orientation, rotationX, rotationY). Added pure JVM whitelist tests for all accepted and rejected values. Prior restrictive guard caused the bridge to reject Anchor/Orientation/X/Y Rotation even though Rust implemented them.
- Current #578 (41830354093) was in progress at inspection on prior HEAD 5f89d50c, ARM64 native build step; this commit supersedes it and triggers fresh exact-head verification. No local Gradle/JDK/Rust toolchain is available in the automation execution environment; CI is the build/test gate.
- Commit includes EditorScreen selector semantics, Android regression stabilization, Kotlin bridge whitelist and JVM tests. M4 remains IN PROGRESS pending CI and further effect/curve parity. M0 owner/license sign-off and reproducibility, M2 physical SAF/media, M3 GPU/physical device, M5 video/audio, M6 performance/accessibility/device matrix and M7 signed release/install remain open. No native Adobe .aep or Effekseer .efkproj support claimed.


### Run 80 — 3D transform curve metadata and native curve-operation parity (2026-11-04)
- M4 follow-up: native metadata already emitted Anchor/Orientation/Rotation X/Y key indices and full curves, but CanonicalProjectInfo.parse discarded them (only Position/Scale/Rotation were parsed); CurveGraphSection likewise had no 3D track cases. Kotlin parser now retains all seven transform tracks/curves and the graph resolves their current values/keys.
- Kotlin JNI curve selector validation now accepts the same Transform selector set as Rust (position, scale, rotation, opacity, anchor, orientation, rotationX, rotationY) plus nonnegative numeric param UIDs for Bezier edits, key moves and removals. Previously native resolver supported these selectors but Kotlin rejected 3D graph operations before JNI. Added JVM selector whitelist tests and canonical metadata parser regression for sorted 3D keys/curve values.
- Rust regression now exercises Anchor Bezier handle editing and moving its key to frame 5, checking canonical tick/interpolation/ease. This is native behavior test, not just metadata fixture.
- Implementation is committed with Run 79 selector stabilization/bridge whitelist in predecessor b17f791; this follow-up commits parser/graph/native coverage. CI #579 on b17f791 is pending; current exact-head CI will be triggered. No local Gradle/Cargo toolchain available; CI is gate. M4 remains IN PROGRESS; M0 owner/license/repro, M2 physical SAF, M3 GPU/device, M5 video/audio, M6 physical UX/performance, M7 signed release/install remain open. No .aep/.efkproj compatibility claim.
