# EffectCraft Android phase tracker

Updated: 2026-11-02 (canonical Text-layer creation UI)
Latest implementation commit: `59349a712fbc29847d59441744d3a1afc9a3f323` (canonical `.ecproj` layer rename + Text layer creation UI and cache persistence; CI pending)
Branch: `feat/android-app-foundation`
PR: [#1](https://github.com/sunmughan/After-Effects-Android/pull/1)

This tracker is the source of truth for acceptance. A phase is **complete only when its code, tests, and evidence meet every required gate**. A green bootstrap CI run does not imply the product roadmap is complete.

## M0 — Foundation, toolchain and CI
**Status: IN PROGRESS** · Evidence commit: `1f32b1a11a468f9433d4781f831c56d6abd2702a`
- [x] Native Android/Compose project and offline manifest.
- [x] Pinned upstream EffectCraft revision, Rust bridge crate, ARM64 cross-build and Rust host tests.
- [x] CI: Rust tests, ARM64 bridge build, APK assembly, Kotlin unit tests, emulator instrumentation, dependency/license inventory.
- [x] CI concurrency group cancels stale PR commits so only the newest revision consumes a runner.
- [x] Current-head CI run [41411204104](https://github.com/sunmughan/After-Effects-Android/actions/runs/41411204104) completed **success** (2026-11-02).
- [ ] Commit Gradle Wrapper and verify wrapper-based reproducible build.
- [ ] Validate corrected layer-move edge test and visibility persistence changes on current head; run 41415700009 failed at unit assertion (test expected wrong edge result); assertion corrected in `427f54a112a0167d6d3272613d94a7518bac6d78`, current implementation `462fa27cd7ed0bfcc8d0bc7dfaad4dec47803292` not yet observed in a fresh CI run.
- [ ] Verify native ABI packaging/loading on an Android ARM64 device (emulator CI currently compiles ARM64 but instrumentation runs x86_64).
- [x] Diagnosed latest emulator job failure: unit tests and APK succeeded; x86_64 Pixel 6 API 35 cold boot on runner took ~816 seconds and adb remained offline/broke pipes, never reached instrumentation. CI changed to smaller Pixel 2/API 30, explicitly permit Linux acceleration, and increase boot/job budgets in `8f6b406e6934f9c9fc9177f1d88a2c607e33325c`; the run remains slow/in progress and a fresh latest-head CI must verify this configuration.
- [x] CI root-cause follow-up: an earlier emulator instrumentation attempt failed before tests because Android `activity`/`package` services were unavailable (`cmd: Can't find service`). Workflow now waits for `sys.boot_completed` and package service readiness before connected tests; current-head validation is pending.
- [ ] Finish repository audit, dependency/license review and release build reproducibility.
- **Exit gate:** clean checkout builds/tests with pinned wrapper/toolchain; native library loads on ARM64 device; audit evidence checked in.

## M1 — Professional native editor foundation
**Status: IN PROGRESS**
- [x] Adaptive Compose phone/tablet workspace, offline starter composition and local draft persistence.
- [x] Add/select/remove layers, opacity slider, timeline scrubbing and transport simulation.
- [x] Emulator smoke tests for launch, New, add-layer action and draft round-trip.
- [x] Layer reorder (up/down) wired in the inspector; pure reducer has tests for order, identity, edge clamping and unknown IDs (CI verification pending for this implementation commit).
- [x] Bounded (50 snapshots) project undo/redo history wired for new composition, add/remove/reorder and opacity changes; emulator test covers add-layer undo/redo (CI pending).
- [x] Layer visibility toggle is stateful, persisted in `.efx-draft` schema v1 with backward-compatible default `true`, and undoable; instrumentation draft round-trip covers hidden layer.
- [x] CI failure diagnosis: unit test had an incorrect expected ID for moving first layer to end; corrected in `427f54a112a0167d6d3272613d94a7518bac6d78` (the subsequent run's unit tests passed; emulator infrastructure failed before test installation).
- [x] Layer naming UI in inspector with Apply/trim/blank validation; pure rename reducer unit tests preserve stable IDs.
- [x] Phone inspector layer rename instrumentation assertion added; latest-head CI pending.
- [ ] Composition settings UI.
- [ ] Strong instrumentation assertions for selection, reorder, delete, visibility, opacity and timeline state.
- **Exit gate:** all listed editor interactions are stateful and tested; no placeholder interaction is represented as a real engine feature.

## M2 — Project formats, import/save and media
**Status: IN PROGRESS / canonical model inspection**
- [x] JNI validates canonical upstream `.ecproj` JSON through pinned `effectcraft-project` model and returns composition metadata.
- [x] Added `docs/NATIVE_ENGINE_INTEGRATION.md` documenting actual JNI scope, platform ABI caveats, and render/project integration gates.
- [x] Rust host test round-trips canonical `Project::default().to_file_json()` and checks metadata inspection (empty project only).
- [x] Rust non-empty canonical project fixture exercises layer rename, stable ID preservation and JSON round-trip; Kotlin JNI wrapper exposes rename envelope.
- [x] Rust non-empty canonical `.ecproj` fixture tests rename, metadata and serialization round-trip.
- [x] Canonical editor UI can add a native Text layer to selected composition, rename it, refresh model metadata and atomically cache serialized project; upstream `build::layer` allocates stable ID and Transform properties.
- [ ] Kotlin/JNI ARM64 device round-trip test for SAF-open → rename → export.
- [x] SAF document picker, persisted URI read permission, app-private canonical document retention, and export.
- [x] Native metadata lists composition and layer IDs/names/source/visibility; Android canonical-project sheet selects composition/layer and renames via Rust project model, reserializes and atomically caches the edited `.ecproj` (ARM64 runtime only; CI x86_64 bridge cannot execute JNI).
- [x] Instrumentation test verifies the canonical document cache preserves source bytes.
- [x] Wire native canonical rename operation into imported-project editor UI and update imported document cache after edits.
- [x] Add Rust canonical Text-layer creation operation (`build::layer` with upstream `LayerSource::Text`), trimmed-name validation, composition existence checks, generated layer ID, serialization/metadata tests; expose JNI/Kotlin bridge (`3c10203`, `83985cb`).
- [x] CI root-cause fix: `cargo test` had unresolved direct fixture crates `effectcraft_color`/`effectcraft_time` despite transitive upstream dependencies; add exact pinned upstream dependencies (`69507b8`).
- [x] Add Text-layer creation action to imported-project UI; native unit tests cover real model creation, invalid names/missing composition and serialization.
- [ ] Verify canonical import → add/rename → export through JNI on physical ARM64 device.
- [x] Restore canonical project metadata from app-private cache on app restart; composition/layer list uses scrollable selection.
- [ ] SAF write permission/recovery UX and editable canonical project repository adapter
- [ ] Canonical project repository adapter; version/schema validation and migration.
- [ ] Media asset manifest, URI relinking and offline asset handling.
- **Format boundary:** bootstrap `.efx-draft` is private Android JSON, not `.ecproj`. Adobe `.aep` is proprietary; native import is not promised without a legally available compatible parser/conversion route.

## M3 — Actual preview/render engine
**Status: NOT STARTED**
- [ ] Integrate upstream engine/session APIs into Android native library with Android-compatible feature gates.
- [ ] Replace Compose placeholder canvas with renderer-backed frame surface.
- [ ] JNI session lifecycle, frame request/output, error propagation and teardown.
- [ ] GPU/Vulkan path plus tested fallback/device-loss recovery.
- [ ] Prove first real composition frame on ARM64 phone/tablet (record device, ABI, SHA).

## M4 — Editing/effects/timeline parity
**Status: NOT STARTED**
- [ ] Real transforms, text/shape/effect parameters and layer visibility/lock.
- [ ] Keyframe authoring, interpolation and timeline tracks.
- [ ] Undo/redo, gestures, multi-selection and selection persistence.
- [ ] Verify every edit updates engine preview and canonical project data.

## M5 — Render and export
**Status: NOT STARTED**
- [ ] Render queue and output settings (resolution, fps, codec/container).
- [ ] Progress, cancel, foreground service and interruption recovery.
- [ ] Audio/media synchronization and supported decode/encode paths.
- [ ] SAF export, output validation and failure-safe cleanup.
- [ ] Tested 720p/1080p; 4K only on device tiers meeting measured budgets.

## M6 — Performance, reliability and UX
**Status: NOT STARTED**
- [ ] Memory/frame pacing/thermal budgets and large-project benchmarks.
- [ ] Crash recovery, atomic save, process death and lifecycle/rotation.
- [ ] API 26+ phone/tablet/foldable matrix; accessibility and input polish.
- [ ] Offline acceptance including local media and no account/network requirement.

## M7 — Release readiness
**Status: NOT STARTED**
- [ ] All phase exit gates and current-head CI green.
- [ ] Release APK/AAB pipeline, signing via protected secrets (never committed), reproducible instructions.
- [ ] Physical ARM64 install/run and smoke evidence; documented known limitations.
- [ ] Release notes, licenses/notices, support matrix and artifact retention.
- **Final exit:** real supported projects can open, edit, preview/render and export on Android offline; all evidence reviewed. Do not mark complete on shell-only CI.
