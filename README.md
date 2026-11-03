# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

> **Progress (2026-11-03):** M1 scoped editor foundation accepted. M0 technical CI/reproducibility is green, but license choice and owner acceptance remain explicit blockers. Last completed green [CI #380](https://github.com/sunmughan/After-Effects-Android/actions/runs/41650672565) on `f7d0ba3`: 68/68 JVM, 25/25 emulator, and APK content/native reproducibility PASS. Current branch includes M4 canonical opacity keyframe serialization/native interpolation and M5 SAF PNG-sequence ZIP with staged integrity validation, editable width/height/FPS/frame range, bounded native render + actual bitmap resampling, and `sequence.json` timing metadata. A discovered 320×180 compatibility gap was fixed by allowing output dimensions 1–4096 while retaining the 8.39 MP/frame and 8 GiB queue budgets; CI for the accumulated M4/M5 changes is pending. M2 physical ARM64 SAF/repository picker gates remain; M3 physical GPU and Surface/Texture proof remain; M4 transform/effect/keyframe graph parity remains; M5 is still PNG-only with no video/audio codecs. Effekseer `.efkproj` and proprietary Adobe `.aep` remain unsupported.

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
- M3 uses the actual upstream CPU compositor through JNI for canonical frames. Exact-head CI #369 (`d696c56`) now verifies the shared native session/viewer lifecycle, cooperative cancellation, resize and teardown (Rust 21/21, JVM 61/61, emulator 22/22; cold APK content/native ABI reproducibility PASS). GPU/Vulkan, interruptible in-flight CPU rendering and physical ARM64 remain open. M4 opacity keyframes have K+/K− timeline authoring and durable project draft tracks, but no canonical/native interpolation. M5 is PARTIAL: single full-resolution native frame→PNG SAF export with progress/cancel-before-write is committed (`09c1f1b`); exact-head CI pending, full render queue/codec/audio/recovery remains. M6 reliability/device matrix and M7 release remain open.

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
