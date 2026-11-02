# EffectCraft Android phase tracker

Updated: 2026-11-02 (M0 accepted per owner direction; M1 isolated as priority; no M2–M4 work until M1 exit)
Latest implementation/test commit: `6b1b45f4cc779f3eaa914a8956837044bfeb68cb` (canonical Transform Scale X/Y and Z Rotation native mutation, JNI, inspector controls and Rust/Kotlin metadata tests; CI #168 pending (scale/rotation test))
Branch: `feat/android-app-foundation`
PR: [#1](https://github.com/sunmughan/After-Effects-Android/pull/1)

This tracker is the source of truth for acceptance. A phase is **complete only when its code, tests, and evidence meet every required gate**. A green bootstrap CI run does not imply the product roadmap is complete.

## M0 — Foundation, toolchain and CI
**Status: COMPLETE (owner accepted)** · Evidence commit: `1f32b1a11a468f9433d4781f831c56d6abd2702a`
- [x] Native Android/Compose project and offline manifest.
- [x] Pinned upstream EffectCraft revision, Rust bridge crate, ARM64 cross-build and Rust host tests.
- [x] CI: Rust tests, ARM64 bridge build, APK assembly, Kotlin unit tests, emulator instrumentation, dependency/license inventory.
- [x] CI concurrency group cancels stale PR commits so only the newest revision consumes a runner.
- [x] Current-head CI run [41411204104](https://github.com/sunmughan/After-Effects-Android/actions/runs/41411204104) completed **success** (2026-11-02).
- [x] M0 accepted by project owner based on repository foundation, pinned native toolchain and successful baseline CI evidence [run 41411204104](https://github.com/sunmughan/After-Effects-Android/actions/runs/41411204104). Remaining wrapper/physical ARM64 items are tracked as M6/M7 hardening, not M0 blockers.
- [x] Validate corrected layer-move edge test and visibility persistence changes: assertion corrected in `427f54a112a0167d6d3272613d94a7518bac6d78`; subsequent unit tests passed on run #92 (emulator infrastructure failed before instrumentation). Latest interaction instrumentation is awaiting run #97.
- [ ] Verify native ABI packaging/loading on an Android ARM64 device (emulator CI currently compiles ARM64 but instrumentation runs x86_64).
- [x] Diagnosed latest emulator job failure: unit tests and APK succeeded; x86_64 Pixel 6 API 35 cold boot on runner took ~816 seconds and adb remained offline/broke pipes, never reached instrumentation. CI changed to smaller Pixel 2/API 30, explicitly permit Linux acceleration, and increase boot/job budgets in `8f6b406e6934f9c9fc9177f1d88a2c607e33325c`; the run remains slow/in progress and a fresh latest-head CI must verify this configuration.
- [x] CI root-cause follow-up: an earlier emulator instrumentation attempt failed before tests because Android `activity`/`package` services were unavailable (`cmd: Can't find service`). Workflow now waits for `sys.boot_completed` and package service readiness before connected tests; current-head validation is pending.
- [ ] Finish repository audit, dependency/license review and release build reproducibility.
- **Exit gate:** clean checkout builds/tests with pinned wrapper/toolchain; native library loads on ARM64 device; audit evidence checked in.

## M1 — Professional native editor foundation
**Status: IN PROGRESS — PRIORITY ONLY (estimated implementation 80%; not acceptance-complete)**
- [x] Adaptive Compose phone/tablet workspace, offline starter composition and local draft persistence.
- [x] Add/select/remove layers, opacity slider, timeline scrubbing and transport simulation.
- [x] Emulator smoke tests for launch, New, add-layer action and draft round-trip.
- [x] Layer reorder (up/down) wired in the inspector; pure reducer has tests for order, identity, edge clamping and unknown IDs (CI verification pending for this implementation commit).
- [x] Bounded (50 snapshots) project undo/redo history wired for new composition, add/remove/reorder and opacity changes; emulator test covers add-layer undo/redo (CI pending).
- [x] Layer visibility toggle is stateful, persisted in `.efx-draft` schema v1 with backward-compatible default `true`, and undoable; instrumentation draft round-trip covers hidden layer.
- [x] CI failure diagnosis: unit test had an incorrect expected ID for moving first layer to end; corrected in `427f54a112a0167d6d3272613d94a7518bac6d78` (the subsequent run's unit tests passed; emulator infrastructure failed before test installation).
- [x] Layer naming UI in inspector with Apply/trim/blank validation; pure rename reducer unit tests preserve stable IDs.
- [x] Phone inspector layer rename instrumentation assertion.
- [x] Accessible semantics labels for layer visibility, layer opacity and timeline playhead; instrumentation now asserts visibility toggle, 50% opacity, playhead scrub to frame 60/timecode 00:00:02:00, play/pause, and added-layer reorder/delete.
- [ ] Verify the expanded interaction instrumentation on current head (latest CI run pending); fix test/API failures rather than weakening assertions.
- [x] Composition settings panel edits width/height (320–3840 px), frame rate (1–120), and duration (1–3600 frames); changes enter bounded undo history and clamp the playhead when duration shrinks.
- [x] Instrumentation for composition frame-rate apply + undo; CI validation pending.
- [x] Instrumentation for timeline layer selection (inspector reflects selected layer opacity) and editor Save → app-private draft reload.
- [ ] Fix CI prerequisite Rust test mismatch on current head (run #168 failure: layer-stack test expected `C,A,B`, implementation swap correctly yields `C,B,A`; correct assertion and rerun).
- [ ] Run all expanded M1 instrumentation on current head; fix actual failures without reducing assertions.
- [ ] Add tablet/foldable responsive layout smoke coverage and confirm controls remain accessible/scrollable at compact widths.
- [ ] Final M1 acceptance audit: offline create/edit/save/reload, layer select/add/delete/reorder/rename/visibility/opacity, composition settings + undo/redo, timeline scrub/play/pause, persistence and adaptive UI all pass.
- **Exit gate:** all listed editor interactions are stateful and tested; no placeholder interaction is represented as a real engine feature.

## M2 — Project formats, import/save and media
**Status: PAUSED BY OWNER UNTIL M1 COMPLETE (existing work preserved; no further scope now)**
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
- [x] Canonical imported-project editor now exposes real upstream-model visibility, reorder and delete actions; JNI envelopes are revalidated, re-inspected, and atomically cached after each mutation (`8d32c9a`, `e168c6d`, `77ef2c9`).
- [x] Rust tests exercise canonical layer stack order, visibility switch and deletion with ID-preserving serialization round-trip (`8fdf841`, assertion cleanup `ffab2a0`).
- [x] Native project inspection now exposes all item IDs/names/types/parents plus footage paths, dimensions, audio/video flags, missing status and image-sequence paths; Kotlin parses this asset inventory and canonical metadata parser tests cover valid/invalid envelopes (`e6ee3d8`, `5bd95fa`, `11606b2`).
- [x] Imported-project sheet lists linked media assets and visibly flags upstream `missing` items (`bc0a4c0`).
- [x] SAF relink action copies selected footage into app-private `files/assets/` for offline access, then updates the upstream footage path and clears its `missing` flag through native model/JNI; failed copy/edit leaves the canonical project cache unchanged (`694e8c9`, `c3f77da`, `e255e27`, `bc03047`, `21047c9`). Rust tests cover path update, missing-flag reset and invalid ID/blank path (`a61ad38`).
- [ ] Physical ARM64 test of SAF copy → native relink → export; image-sequence relinking is intentionally not exposed as a single-file operation.
- [x] Add Rust canonical Text-layer creation operation (`build::layer` with upstream `LayerSource::Text`), trimmed-name validation, composition existence checks, generated layer ID, serialization/metadata tests; expose JNI/Kotlin bridge (`3c10203`, `83985cb`).
- [x] CI root-cause fix: `cargo test` had unresolved direct fixture crates `effectcraft_color`/`effectcraft_time` despite transitive upstream dependencies; add exact pinned upstream dependencies (`69507b8`).
- [x] Add Text-layer creation action to imported-project UI; native unit tests cover real model creation, invalid names/missing composition and serialization.
- [ ] Verify canonical import → add/rename/visibility/reorder/delete → export through JNI on physical ARM64 device; CI host tests validate the upstream model but emulator ABI does not load ARM64 JNI.
- [x] Restore canonical project metadata from app-private cache on app restart; composition/layer list uses scrollable selection.
- [ ] SAF write permission/recovery UX and editable canonical project repository adapter
- [ ] Canonical project repository adapter; version/schema validation and migration.
- [x] SAF URI relinking copies footage to an offline app-private file and persists the canonical path/missing flag (`e255e27`, `694e8c9`, `a61ad38`).
- [ ] Durable asset manifest, deduplicated storage, integrity hashes, relink rollback/cleanup and sequence import.
- **Format boundary:** bootstrap `.efx-draft` is private Android JSON, not `.ecproj`. Adobe `.aep` is proprietary; native import is not promised without a legally available compatible parser/conversion route.

## M3 — Actual preview/render engine
**Status: PAUSED BY OWNER UNTIL M1 COMPLETE (existing work preserved; no further scope now)**
- [x] Pin upstream `effectcraft-render` at the same immutable revision; native `render_frame_rgba` parses canonical `.ecproj`, renders through the real CPU `Renderer`/persistent `MediaPool`, and emits bounded RGBA with dimensions. Rust test asserts a 320×180 composition produces a real 160×90 frame payload and rejects bad IDs/negative frames (`b75fe05`, `4f0aa79`).
- [x] JNI frame request/output bridge and Kotlin wrapper (`4f0aa79`, `5fa1cf2`).
- [x] Imported canonical editor now renders frames off the UI thread, displays returned pixels as an image, and scrubs frame index (`05e2c4a`, `4c86391`). This is actual upstream CPU compositing—not the decorative draft viewer.
- [ ] Replace main `.efx-draft` placeholder canvas with project/session-backed rendering; no shared native session yet.
- [x] Add pinned upstream `effectcraft-media::MediaPool` as a persistent native `FootageSource`; renderer now decodes app-private relinked still/video/audio files through upstream FilmCraft codecs instead of `NoFootage` (`bc7ddc7`, `de7bce1`).
- [x] Host Rust test writes a PNG fixture to a local path and asserts its red pixels appear in the CPU composition frame (`b76e545`, `3da9fb9`); latest CI must validate cross-build and decode test.
- [ ] Verify media decoder feature/codec support and memory cache limits on Android ARM64; video/audio performance and cancellation are not yet measured.
- [ ] JNI session lifecycle, native ownership/threading, cancellation, resize and renderer teardown.
- [ ] GPU/Vulkan acceleration plus tested CPU fallback/device-loss recovery.
- [ ] Verify ARM64 native frame on physical phone/tablet; record device, ABI, SHA and pixel evidence.

## M4 — Editing/effects/timeline parity
**Status: PAUSED BY OWNER UNTIL M1 COMPLETE (existing work preserved; no further scope now)**
- [x] Canonical Transform/Opacity is now a real upstream `Property` mutation (0–100, key-aware at frame zero), exposed by JNI, reflected in metadata and edited with a commit-on-release slider; each change reserializes the `.ecproj` and refreshes CPU preview (`9a0af7d`, `340e601`, `7997036`, `1166a2c`, `71509df`). Rust tests cover property mutation/range/ID; Kotlin parser test covers opacity metadata (`e049130`). CI/device validation pending.
- [x] Canonical Transform/Position X/Y edits preserve Z and key-aware set at frame zero; native model test verifies exact vector and rejects non-finite coordinates/unknown IDs (`b2422dc`); JNI/Kotlin bridge and inspector numeric controls Apply into project cache (`a8a94c2`, `764c311`, `21f4a80`). Parser tests cover X/Y metadata (`0a96985`). CI/device validation pending.
- [x] Canonical Transform Scale X/Y and Z Rotation editing: Rust mutations preserve Scale Z and set key-aware frame-zero values, bounded finite input validation, JNI/Kotlin APIs, metadata parsing, inspector Apply controls and CPU preview refresh; Rust round-trip/error tests and Kotlin metadata test added (`6b1b45f`). Current-head CI validation pending.
- [ ] Anchor/3D rotation editing and text/shape/effect parameter UI; canonical layer lock and selection parity.
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
