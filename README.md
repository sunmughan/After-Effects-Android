# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

> **Progress (2026-11-04, latest: accessible Bezier curve-key manipulation):** M1 scoped editor accepted; M0 owner sign-off remains open. M2 canonical formats/repository/offline assets are implemented with integrity and recovery work but physical SAF/media gates remain; Effekseer .efkproj and proprietary Adobe .aep unsupported. M3 actual upstream CPU renderer/native session is integrated; physical ARM64 GPU/Surface validation remains. M4 native opacity/transform/parameter keyframes, timeline navigation, per-side interpolation and draggable Bezier graph are implemented; this run adds TalkBack custom actions to move graph keys, exact-head CI #538 pending. M5 validated PNG/sequence ZIP only, no video/audio. M6 persistence durability and accessibility semantics implemented; physical performance/TalkBack matrix open. M7 release gates not started. CI #537 on graph-editor head passed Assemble/test (Rust 38, JVM 146, emulator 68) while reproducibility job remained in progress. See PHASES Run 70.

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

- **M0 foundation: NOT COMPLETE** — technical build/test and cold reproducibility passed at CI #346 (13 Rust, 36 JVM, 19/19 emulator; 129/129 APK entries and packaged native ABIs match), but license decision and owner acceptance remain explicit open gates.
- **M1 editor foundation: COMPLETE (scoped)** — CI #346 passes 19/19 emulator tests including named native parser invocation, delete/reorder/Undo/Redo, offline project open/save, XML round trip, media-library empty state and adaptive workspace.
- **M2: IN PROGRESS.** EffectCraft `.ecproj` JSON and `.ecprojx` XML import/export, native validation and format guardrails are present. OfflineAssetStore, SAF relinking, persistent project manifests, sequence import, grant recovery and reference-aware GC/replace are implemented (cluster commit `4a75700c`); #349 exposed a missing `java.io.File` import in its sequence test, fixed in `a9879121`. Exact-head JVM/emulator verification pending. Effekseer `.efkproj` has no parser/converter; Adobe `.aep` is proprietary with no licensed parser. `.efx-draft` uses offline Open, not canonical import.
- M3 uses the actual upstream CPU compositor through JNI for canonical frames. Exact-head CI #369 (`d696c56`) verifies shared native session/viewer lifecycle, cancellation, resize and teardown (Rust 21/21, JVM 61/61, emulator 22/22; cold APK content/native ABI reproducibility PASS). GPU backend status/device-loss recovery exists, but physical ARM64 GPU/Surface path and interruptible CPU rendering remain open.
- **M4 IN PROGRESS:** canonical opacity and Position/Scale/Rotation keyframes serialize through the upstream model and affect actual rendered frames; transform key add/remove, metadata, inspector nearest-key jump and interactive timeline markers are implemented. Latest additions expose a screen-reader semantic click on the transform lane; exact-head accessibility CI #507+ pending. Ease/Bezier graph, drag-key editing and remaining animatable property parity remain.
- **M5 PARTIAL:** actual native frame→PNG and configurable PNG-sequence ZIP (dimensions, FPS/range, resampling, manifest, staged ZIP integrity) export via SAF. Bounded SAF copy has rollback callback/best-effort provider deletion; provider behavior varies. Video codecs/audio, background render service, interruption recovery and physical memory/thermal acceptance remain.
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
