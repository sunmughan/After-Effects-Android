# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

> **Current status (2026-11-03): M1 scoped editor foundation accepted; M0 technical gates are green but licensing and owner acceptance remain open.** CI #346 passed dual-ABI native builds, JNI packaging, Rust 13/13, JVM 36/36, emulator 19/19 and cold APK-content reproducibility (129/129 entries, both packaged native ABIs identical; only ephemeral debug signing differs). M2 is in progress: canonical JSON/XML, content-addressed offline media, project asset manifests, sequence import, SAF recovery/relink and reference-safe GC are implemented in commits including `4a75700c`; current-head verification was interrupted by a test-source compile error (missing `java.io.File` import in `SequenceAssetImportTest`), fixed in `a9879121`. M3 already invokes the upstream CPU compositor through JNI for canonical frames; this run adds a bounded 12 MiB/12-entry immutable RGBA LRU (commits `41d49ae1`, `f491c832`, `4e0e4f0c`) and a serialized/coalesced render gate to protect the shared native MediaPool (`31e03bd1`, `f610ab73`) plus five cache tests including 8-way concurrent request coalescing (`0d007c18`). Exact-head CI is pending. CI #349 exposed a missing `java.io.File` import in sequence-import tests, fixed in `a9879121`; JUnit upload now warns rather than obscuring a compile failure (`bb826629`). M4 opacity keyframes persist and have K+/K− timeline authoring, but do not yet drive canonical/native evaluation. Effekseer `.efkproj` and proprietary Adobe `.aep` remain unsupported.> **Current status: M1 scoped editor foundation accepted; M0 technical CI green but legal/owner gates remain open.** Exact branch-tip CI #337 (commit `7478f235`) passed dual-ABI native builds, JNI packaging, 13 Rust tests, 32 JVM tests, and 19/19 API 30 emulator instrumentation; per-test JUnit XMLs are published and inspected. M2 is in progress: canonical JSON/XML import, format guardrails, SHA-256 content-addressed offline media storage, native footage relinking, and an offline media library browser/removal flow are implemented. Durable footage-item→asset SHA references now protect currently linked assets from deletion (`OfflineAssetReferences`, `2fcff068`, `ae50ab2d). CI #309 exposed a stale dedupe filename assertion after extension preservation; corrected in `ff9b71ed`. A fresh-store catalog persistence/sort test was added in `114e257e`; exact-head CI is pending. Tracker now distinguishes accepted M0/M1 from incomplete M2–M7. Effekseer `.efkproj` and Adobe `.aep` remain explicitly unsupported.

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
