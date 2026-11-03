# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

> **Current status: M1 scoped editor foundation accepted; M0 technical CI green but legal/owner gates remain open.** Exact branch-tip CI #337 (commit `7478f235`) passed dual-ABI native builds, JNI packaging, 13 Rust tests, 32 JVM tests, and 19/19 API 30 emulator instrumentation; per-test JUnit XMLs are published and inspected. M2 is in progress: canonical JSON/XML import, format guardrails, SHA-256 content-addressed offline media storage, native footage relinking, and an offline media library browser/removal flow are implemented. Durable footage-item→asset SHA references now protect currently linked assets from deletion (`OfflineAssetReferences`, `2fcff068`, `ae50ab2d). CI #309 exposed a stale dedupe filename assertion after extension preservation; corrected in `ff9b71ed`. A fresh-store catalog persistence/sort test was added in `114e257e`; exact-head CI is pending. Tracker now distinguishes accepted M0/M1 from incomplete M2–M7. Effekseer `.efkproj` and Adobe `.aep` remain explicitly unsupported.

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

- **M0 foundation: NOT COMPLETE** — technical build/test gates are green at current-tip CI #337 (13 Rust, 32 JVM, 19/19 emulator tests, dual ABI and JNI), but PHASES.md keeps the license decision and owner acceptance as explicit open gates.
- **M1 editor foundation: COMPLETE (scoped)** — current-tip CI #337 passes 19/19 emulator tests including named native parser invocation, delete/reorder/Undo/Redo, offline project open/save, XML round trip, media-library empty state and adaptive workspace.
- **M2: IN PROGRESS.** EffectCraft `.ecproj` JSON and `.ecprojx` XML import/export, native schema validation and format guardrails are present. `OfflineAssetStore` (`020413c8`, `cd9a69f0`) adds bounded SHA-256 content-addressed app-private media with dedupe and atomic metadata; `7157542e` integrates SAF footage relinking to the immutable offline payload. Four asset-store JVM tests are committed but awaiting latest-tip CI. Project/asset manifest, safe reference-aware deletion, missing-media recovery, and broader migration/relink acceptance remain.- Supported canonical interchange: EffectCraft `.ecproj` JSON and `.ecprojx` XML. Effekseer `.efkproj` has no integrated parser/converter; Adobe `.aep` is proprietary and no licensed compatible parser is integrated. Android `.efx-draft` files use Open offline project, not canonical import.
- M3 actual upstream CPU composition preview through JNI is partial (shared session lifecycle, GPU fallback and device validation remain); M4 canonical transforms and scalar keyframe primitives now include project-scoped durable keyframe storage, but editor UI/native evaluation is not wired yet; M5 render/export queue, M6 reliability/device matrix and M7 release are not complete.

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
- M4 added `ProjectKeyframeStore`: versioned project-scoped JSON with SHA-256-derived filenames, atomic writes, sorted scalar tracks, same-frame upsert, key removal/empty-track cleanup, project clear and strict identity/property/frame/value/schema validation. Four JVM test methods cover persistence/isolation/replacement, removal/clear, invalid inputs and project-document mismatch. Commit and fresh exact-head CI are pending.
- Stored keyframes remain editor-side draft data; they are not yet wired to timeline UI, canonical `.ecproj` serialization or native renderer evaluation. M4 remains incomplete. M0 remains NOT COMPLETE pending license and owner acceptance; M1 scoped foundation remains COMPLETE; M2–M7 remain open.