# EffectCraft Android phase tracker

Updated: 2026-11-03 (M1 saved-project open/list implemented; CI verification pending)
Latest implementation commit: `3b09f989b6ac9dc8573abeba60e7aa46b95db6e0` connects the main workspace viewer to real upstream CPU-rendered canonical `.ecproj` frames at the current playhead; bootstrap drafts are explicitly labelled unrendered instead of displaying a decorative fake. SAF imports now enforce a 64 MiB read limit and require native schema validation plus at least one valid composition before caching. Current-head CI #246 was queued; no pass is claimed.

### Run 15 — Android-test compile fix and offline save-upsert regression (2026-11-03)
- Current-head CI [#254](https://github.com/sunmughan/After-Effects-Android/actions/runs/41567257298) completed failure after native ARM64/x86_64 builds, JNI packaging verification, Rust tests, dependency/license inventory, debug APK assembly, and JVM tests all passed.
- Exact failure at `:app:compileDebugAndroidTestKotlin`: `AdaptiveWorkspaceSmokeTest.kt:29` omitted newly required `savedProjects` and `onOpenProject` arguments from the direct `EditorScreen` composable invocation. Corrected in commit `2aafaa478b2f2b3162d8b6d65eddbe95bf8280bd` by passing `emptyList()` and a no-op callback; no production API or test assertion was weakened.
- A temporary `DeleteDiagTest` was deliberately added in that commit to print a semantics dump for the previously flaky delete-status assertion; it deliberately fails and is diagnostic only. It was removed in `8ff29ce8db221c1944deeb82aac5c6a8bd162bd3` before final acceptance. It is not part of the product test suite.
- Strengthened `savedOfflineProjectsCanBeOpenedWithoutNetwork` in `a69fbfa721e66afb5b935d09c6eeca60b04c8339`: save a 4-layer project, add a fifth layer and save the same stable project ID again, then save a second project; assert catalog contains exactly two entries and opening the first restores Shape 5. This covers save-upsert/deduplication and latest-revision loading rather than only opening an initial snapshot.
- CI #255 at temporary diagnostic HEAD `2aafaa47` was superseded by the save-upsert test and diagnostic removal. Fresh current-head CI is required; no pass is claimed. Next run must inspect all jobs/logs and resolve the actual delete-status semantics assertion from test output, then verify the complete instrumentation suite.
- Current implementation/test commits: `2aafaa47` (adaptive call-site compile fix), `a69fbfa7` (offline catalog upsert regression), `8ff29ce8` (remove temporary diagnostic). Tracker/README evidence commit follows.

### Run 13 — CI compile failure diagnosed and corrected (2026-11-03)
- CI run [#247](https://github.com/sunmughan/After-Effects-Android/actions/runs/41556118199) reached both native ABI builds, JNI packaging checks, Rust tests and dependency/license inventory successfully, then failed at `:app:compileDebugKotlin`.
- Exact compiler diagnostics: `EffectCraftApp.kt:72` conflicting local declarations `source`; `CanonicalProjectDocument` unresolved at lines 74/78. Root cause was a stale unbounded bufferedReader read duplicated beside the new bounded streaming read, plus a missing import.
- Corrective code commit `5701135514b3763534071d0fc05dea91c1459670` (`fix(import): resolve canonical document compilation errors`) removes the duplicate read and imports `CanonicalProjectDocument`. No tests were weakened.
- This is a concrete M2 import-boundary correctness fix and restores the intended 64 MiB streaming limit. Fresh current-head CI must verify Kotlin compile, APK, JVM tests and emulator instrumentation; no pass is claimed.
- Local Gradle/Android SDK is unavailable in this automation runtime; exact failure logs came from GitHub Actions. Next run must inspect the new run and continue with any compile/test failures.

### Run 14 — multi-project offline save/open workflow (2026-11-03)
- M1 adds a saved-draft catalog: enumerate valid .efx-draft documents newest-first, de-duplicate by stable project ID, and load a selected project by ID. Save updates the existing file for that ID even if its display name changes, rather than creating stale duplicates.
- Compose header exposes Open offline project (compact icon on phone; Open action on wider layout); a scrollable picker shows saved project name, dimensions, FPS and layer count. Selecting a draft restores project, layer selection, playhead=0, stops playback and clears history from the previous project.
- Saved-project list refreshes after successful Save; no network permission or network service added.
- Instrumentation `savedOfflineProjectsCanBeOpenedWithoutNetwork` (test commit `6fa242dd32ab1385b1007f5db8870259010b1a75`) clears its draft fixture, saves a 4-layer draft, saves a separate 3-layer draft, opens the 4-layer entry and asserts Shape 4 and local-open status. The editor suite now contains 16 instrumentation tests.
- Implementation commit `4e92ab53c42a5f4bc09bfd0f95f1716039e852bb` (`feat(editor): open saved offline projects`); test-isolation commit `6fa242dd32ab1385b1007f5db8870259010b1a75`; README progress commit `85f6efd6805e954b59e919230a5454d333402b89`. Exact-head CI verification is pending. M1 remains IN PROGRESS; no phase completion is claimed.
This tracker is the source of truth for acceptance. A phase is **complete only when its code, tests, and evidence meet every required gate**. A green bootstrap CI run does not imply the product roadmap is complete.

### Run 12 — selection-aware editor history and accessibility (2026-11-03)
- Implementation commit `099cd972be1be909a0f54646ab90dd044b1a9e43` (`fix(editor): restore selection and playhead with undo history`).
- Undo/Redo snapshots now include the project model, selected layer ID and playhead frame (bounded to 50). Undo of layer deletion restores the deleted layer as selected; Redo restores the post-delete adjacent selection. Playhead is clamped to restored duration.
- Timeline layer rows expose accessibility descriptions that distinguish selected and unselected layers.
- Added instrumentation `deletingLayerUndoAndRedoRestoreTheExpectedTimelineSelection`: move added Shape 4, delete it, assert Accent Shapes selected, Undo asserts Shape 4 selected, Redo asserts Accent Shapes selected. Existing assertions are preserved.
- This is concrete M1 functionality, not a CI-only change. Local Android/Gradle execution is unavailable in this automation container; GitHub Actions is the build/test environment.
- Run #242 was in ARM64 cold compilation when this commit advanced the branch; concurrency cancelled the stale run. Fresh current-head run #243 is expected. No build/test pass is claimed.
- Next: inspect #243 job/step logs and emulator result; fix failures, then verify the full M1 suite at exact HEAD. M0 remains INCOMPLETE pending green current-head dual-ABI/JNI/emulator CI; M1 acceptance stays pending.

## M0 — Foundation, toolchain and CI
**Status: INCOMPLETE — baseline CI green, closure gates remain** · Baseline evidence commit: `1f32b1a11a468f9433d4781f831c56d6abd2702a`
- [x] Native Android/Compose project and offline manifest.
- [x] Pinned upstream EffectCraft revision, Rust bridge crate, ARM64 cross-build and Rust host tests.
- [x] CI: Rust tests, ARM64 bridge build, APK assembly, Kotlin unit tests, emulator instrumentation, dependency/license inventory.
- [x] CI concurrency group cancels stale PR commits so only the newest revision consumes a runner.
- [x] Current-head CI run [41411204104](https://github.com/sunmughan/After-Effects-Android/actions/runs/41411204104) completed **success** (2026-11-02).
- [x] Run #247 built ARM64 + x86_64 JNI libraries, verified both packaged ABIs, passed Rust tests and generated dependency/license inventory; it then exposed the Kotlin import compile error recorded in Run 13.
- [ ] Fresh current-head CI after `57011355` must pass Kotlin compilation/APK, JVM tests and emulator JNI load/parser instrumentation.
- [x] Official Gradle Wrapper (9.5.0) committed in `eb2c6498e75079550c0c3fb0a5441b36102a2207`; distribution SHA-256 pinned in `gradle/wrapper/gradle-wrapper.properties`; CI selects `./gradlew` when present. Exact-head clean-checkout build is still pending.
- [x] Validate corrected layer-move edge test and visibility persistence changes: assertion corrected in `427f54a112a0167d6d3272613d94a7518bac6d78`; subsequent unit tests passed on run #92 (emulator infrastructure failed before instrumentation). Latest interaction instrumentation is awaiting run #97.
- [x] M0 runtime gate is emulator x86_64 JNI invocation; physical ARM64 device validation is explicitly moved to M6 compatibility (not a foundation blocker).
- [x] Diagnosed latest emulator job failure: unit tests and APK succeeded; x86_64 Pixel 6 API 35 cold boot on runner took ~816 seconds and adb remained offline/broke pipes, never reached instrumentation. CI changed to smaller Pixel 2/API 30, explicitly permit Linux acceleration, and increase boot/job budgets in `8f6b406e6934f9c9fc9177f1d88a2c607e33325c`; the run remains slow/in progress and a fresh latest-head CI must verify this configuration.
- [x] CI root-cause follow-up: an earlier emulator instrumentation attempt failed before tests because Android `activity`/`package` services were unavailable (`cmd: Can't find service`). Workflow now waits for `sys.boot_completed` and package service readiness before connected tests; current-head validation is pending.
- [x] Repository structure, direct/transitive dependency and license inventory are audited in `docs/M0_REPOSITORY_AUDIT.md`; owner-selected Android license and full release NOTICE/legal review are release governance gates (M7), not a reason to block technical M0. Wrapper reproducibility is in CI; exact-head clean-checkout proof pending.
- [x] Wrapper JAR/scripts/properties and distribution checksum are committed (`eb2c6498e75079550c0c3fb0a5441b36102a2207`); current CI invokes `./gradlew`. Exact-head validation pending.
- [ ] Confirm native ABI load and an actual JNI parser call on emulator; ARM64 device smoke test is M6.
- **Exit gate:** pinned clean-checkout build, Rust + Kotlin tests, dual ABI package, Android emulator JNI test, audit inventory generated/reviewed; CI green on exact HEAD. Run #247 is the latest completed failure; fresh CI after `57011355` is required; no pass claimed yet.

## M1 — Professional native editor foundation
**Status: IN PROGRESS — M0 current-head CI closure pending; offline multi-project workflow now implemented**
- [x] Adaptive Compose phone/tablet workspace, offline starter composition and local draft persistence.
- [x] Add/select/remove layers, opacity slider, timeline scrubbing and transport simulation.
- [x] Emulator smoke tests for launch, New, add-layer action and draft round-trip.
- [x] Layer reorder (up/down) wired in the inspector; pure reducer has tests for order, identity, edge clamping and unknown IDs (CI verification pending for this implementation commit).
- [x] Delete-selection continuity: reducer chooses the adjacent surviving layer after deleting the selected first/middle layer and preserves selection when the last-layer invariant blocks deletion; regression test `deletingSelectedLayerSelectsAdjacentLayerAndPreservesSelectionWhenDeletionIsRejected` added in `fb2c9bc5` (CI #240 pending).
- [x] Bounded (50 snapshots) project undo/redo history wired for new composition, add/remove/reorder and opacity changes; emulator test covers add-layer undo/redo (CI pending).
- [x] Layer visibility toggle is stateful, persisted in `.efx-draft` schema v1 with backward-compatible default `true`, and undoable; instrumentation draft round-trip covers hidden layer.
- [x] CI failure diagnosis: unit test had an incorrect expected ID for moving first layer to end; corrected in `427f54a112a0167d6d3272613d94a7518bac6d78` (the subsequent run's unit tests passed; emulator infrastructure failed before test installation).
- [x] Layer naming UI in inspector with Apply/trim/blank validation; pure rename reducer unit tests preserve stable IDs.
- [x] Phone inspector layer rename instrumentation assertion.
- [x] Accessible semantics labels for layer visibility, layer opacity and timeline playhead; instrumentation asserts visibility toggle, exact 50% opacity, playhead scrub to frame 60/timecode 00:00:02:00, playback advances beyond frame 60, pause/play, and added-layer reorder/delete. `cceea410` extends the property test to verify exact values across Undo/Redo; playback progression assertion is `61aae48e`.
- [x] Pure project validation enforces composition dimensions (320–3840), frame rate (1–120), duration (1–3600), at least one layer, unique IDs, nonblank project/layer names, and finite normalized opacity. Draft save/load reject invalid models; unit coverage in `77001484`.
- [ ] Verify expanded interaction instrumentation on current head. Run #204 at `9ddc9fe9` booted the emulator and executed all 14 tests; 7 failed due to actual header/status visibility and test-harness query/layout issues. Root causes and corrective commits are recorded at the top of this tracker. Fresh latest-head run after `25e1521a` must pass all 14 tests; do not mark acceptance until then. Run #192 (HEAD `73341011`) booted Pixel 2/API 30 successfully and reached `compileDebugAndroidTestKotlin`; it failed only because `SemanticsNodeInteractionCollection.assertCountEquals` is unavailable in the configured Compose test dependency. Fixed without weakening the deletion assertion in `77efd7df`. Latest M1 implementation commit `77001484` is followed by documentation commits `7d114cca` and `76c7bde0`; run #202 for the README head is queued, and a fresh latest-head CI after tracker update is required. Successful instrumentation and all-green current-head CI are still required. Run #185 at `43211772` passed JVM unit tests after the fixes: (1) `CanonicalProjectInfoTest.parsesCompositionLayerAndLinkedMediaInventory`, (2) `CanonicalProjectInfoTest.rejectsInvalidNativeMetadataEnvelope` now use JVM `org.json` dependency from `b151a301`; (3) deletion invariant test fixed in `c30385e1`. No assertions were removed or weakened. Instrumentation was blocked before test execution by runner lacking `/dev/kvm` permissions; workflow fix `5a4d3d73` now attempts to grant access; run #189 reached emulator startup but failed earlier at Android-test Kotlin compilation; corrected test imports committed in `6b139f12`; fresh run #190 is in progress.
- [x] Composition settings panel edits width/height (320–3840 px), frame rate (1–120), and duration (1–3600 frames); changes enter bounded undo history and clamp the playhead when duration shrinks.
- [x] Instrumentation for composition frame-rate apply + undo, now verifies exact 30 FPS model restoration after Undo; CI validation pending.
- [x] Instrumentation for timeline layer selection (inspector reflects selected layer opacity) and editor Save → app-private draft reload.
- [x] Fixed native layer-stack test expectation (`C,B,A`) in `0469e00`; run #174's Rust bridge test step passed.
- [ ] Run all expanded M1 instrumentation on current head; fix actual failures without reducing assertions. Earlier compile error at `EditorScreen.kt:660` (`LaunchedEffect` unresolved) was fixed in `d86fab9`. Run #185 skipped tests due to emulator startup failure (KVM access denied); workflow fix `5a4d3d73` awaits fresh run. JVM unit tests and APK assembly are green at `43211772`.
- [x] Added wide-window adaptive workspace instrumentation in `1acb7756`: isolated test Activity allows test-supplied Compose content at forced 1080dp width and asserts PROPERTIES + COMPOSITION VIEWER side-by-side. CI verification pending. Foldable physical/posture and compact-width scrolling still need device validation.
- [ ] Final M1 acceptance audit: offline create/edit/save/reload, layer select/add/delete/reorder/rename/visibility/opacity, composition settings + undo/redo, timeline scrub/play/pause, persistence and adaptive UI all pass.
- **Exit gate:** all listed editor interactions are stateful and tested; no placeholder interaction is represented as a real engine feature. Latest actual emulator failure is #204 (7/14 tests); responsive-header and test harness fixes are committed in `9ec1a3f5`, `3cfcfbd`, `14e6d9b`, `4e91c149`, `1acb7756`, and `25e1521a`. Current-head full CI/emulator evidence still required.

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
- [x] Main workspace viewer now requests the current imported canonical composition frame through JNI on `Dispatchers.Default`, decodes RGBA and displays the actual upstream CPU renderer output; scrubbing/playback refreshes the frame (`3b09f989`). The bootstrap `.efx-draft` remains non-renderable and is explicitly labelled `Draft preview not rendered` rather than faking output.
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


### Run 8 — current-head compile failure and correction (2026-11-03)
- Inspected PR #1 (open) and branch HEAD ff01549c33c4b2a5ae4aac3960e8b6d79f033872; latest CI #212 failed at :app:compileDebugKotlin (job 125451748029). Exact diagnostics at EditorScreen.kt:326 and :387: unresolved reference androidx.foundation in fully qualified androidx.foundation.BorderStroke.
- Root cause: Compose BorderStroke belongs to androidx.compose.foundation, and the file did not import it. Fixed in implementation commit fb336098ef288745d07a2bda00475d4a5f8daf1d: added import androidx.compose.foundation.BorderStroke and replaced both bad references with BorderStroke(...).
- Latest CI #213 is queued on fb336098; no successful build or instrumentation claim until it completes.
- M1 instrumentation source at this head contains 14 cases covering launch/offline workspace, New, add layer, undo/redo, rename, visibility/opacity with exact Undo/Redo values, timeline scrub/play/pause, reorder/delete, composition FPS/Undo, selection/inspector, local save/reload, .ecproj byte cache and draft round-trip. Current acceptance remains pending execution on latest HEAD.
- Next action: inspect #213 all jobs/logs; fix any compile/test/runner failures without reducing assertions; then run full current-head acceptance. No M2/M3/M4 work started.


### Run 8 follow-up — composition-FPS playback correction
- Code review found M1 timeline playback advanced every fixed 33 ms regardless of composition FPS. This made 60/120 FPS compositions preview at approximately 30 FPS.
- Implemented ProjectEditorActions.frameIntervalMillis(frameRate) (rounded, FPS bounded 1–120) and wired LaunchedEffect to project.frameRate; effect restarts when FPS changes. Commit aad16bf2e8eb19720fc10599ed72c28095174236.
- Added JVM tests for 1/30/60/120 FPS and invalid lower/upper inputs. Current-head CI has been triggered; no pass claimed yet.
- Compile fix fb336098 is included in this code ancestry; CI #213 was running at last inspection, but the latest branch includes additional playback change and docs. Verify current-head run rather than extrapolating older status.


### Run 9 — latest compile diagnostics and fixes (2026-11-03)
- Inspected open PR #1 and branch HEAD `96f43d58257956616bab6b08a7646643bc22757a`; CI #216 failed in `:app:compileDebugKotlin`. Exact errors: `ProjectEditorActions.kt:9` missing `kotlin.math.roundToLong` import; `EditorScreen.kt:326,387` incorrectly qualified `androidx.foundation.BorderStroke` (correct package is `androidx.compose.foundation`).
- Fixed UI imports/references in `262b4abab4b510657fe26fa72ad9dca8a4a53b8d`; fixed FPS rounding import in `f3444c9728d2dc0ac7c17369f94f43bd23e31265`. Source re-read confirms correct import and no stale qualification.
- CI #217 (`262b4aba`) is running/queued behind SDK setup; #218 (`f3444c97`, current code head) is pending. Neither is a success yet. Current-head full CI remains required.
- M1 playback frame-rate unit cases already exist and cover 1/30/60/120 FPS and bounds; instrumentation suite remains 14 tests and needs a green current-head run. No M1 acceptance or M2 advancement.


### Run 10 — M0 native-runtime and Wrapper closure work (2026-11-03)
- CI #222 on d3e221da was inspected from the job log: Rust host tests, ARM64 cross-build, dependency/license report, APK assembly and JVM unit tests all passed; emulator instrumentation ran 14 tests and failed 3. Two failures were test-host/process configuration (EditorTestActivity launched in the separate test process) and two status checks used assertIsDisplayed() on the accessibility-description node rather than the visible status Text; no production edit failure was reported. Corrections committed: cc89e55 now asserts the actual visible status text and separately verifies the accessibility label; 46c30fb hosts the adaptive test Activity in the target process.
- M0 runtime test gap closed in source: c4d93e44 pins the Android emulator Rust target; a0d035dc builds and checks both arm64-v8a and x86_64 JNI .so packages and CI instrumentation calls NativeProjectBridge.isAvailable() plus the native parser through JNI. This is emulator ABI execution, not physical ARM64 device evidence (physical validation remains M6).
- 2ff85b80 adds one-time official Gradle Wrapper generation (Gradle 9.5.0, distribution SHA-256) and switches CI tasks to ./gradlew; the first workflow execution must produce and push gradlew, gradlew.bat, wrapper JAR and properties. Wrapper generation/commit and a clean-checkout Wrapper build are not yet verified.
- Fresh CI #229 (2ff85b80) was queued at last check; it has not produced jobs or a result. Current-head M0 remains INCOMPLETE; M1 is held. Next: inspect #229/#230 logs, confirm wrapper bootstrap commit, fix every failure, and require green JNI instrumentation + clean Wrapper build before M0 completion.


### Run 11 — CI runtime hardening (2026-11-03)
- CI #237 on `9743f952` completed ARM64 native compilation successfully; x86_64 compilation was still active at 22:33 UTC. Because a cold dual-ABI compile was approaching the former 45-minute job timeout, it is superseded rather than treated as a test result.
- Workflow now caches Cargo registry/git/target and `cargo-ndk`, with a lockfile/toolchain-keyed cache and restore prefix; Android job timeout raised to 90 minutes so first cache-miss dual-ABI build can finish. This is a build-time optimization, not a test bypass.
- The replacement CI run must verify both ABIs, Rust tests, dependency/license report, Gradle Wrapper build, APK/unit tests and all 14 emulator instrumentation cases. M0 remains INCOMPLETE and M1 remains pending until that exact-head run passes.


### Run 13 — canonical workspace preview and strict project import (2026-11-03)
- Product commit `3b09f989b6ac9dc8573abeba60e7aa46b95db6e0` (PR #1 branch `feat/android-app-foundation`).
- Connected the main workspace composition viewer to the pinned upstream CPU renderer: selected canonical composition + playhead are rendered through JNI off the UI thread, decoded to a Bitmap and shown in the viewer; playback/scrubbing updates the frame. This is actual render output, not a visual mock.
- Removed the temporary `DeleteDiagTest` log-dump instrumentation. Added a durable M1 assertion that an offline draft is clearly labelled `Draft preview not rendered`; no fabricated rendered frame is shown.
- Added `CanonicalProjectDocument`: SAF input is streamed with a 64 MiB maximum; import is persisted only after JNI schema validation succeeds and the native metadata contains at least one composition with positive dimensions/FPS and finite positive duration. A missing native ABI/parser now fails import clearly instead of caching arbitrary JSON as a valid project.
- Added five JVM tests for bounded UTF-8 reading, size-limit rejection, valid native envelope, empty-composition rejection and invalid dimension rejection. Existing test assertions were retained.
- Current code CI #246 was pending at commit time. Docs update triggers fresh current-head CI; full dual-ABI/Rust/JVM/15-test emulator verification remains required. M0 and M1 are not complete.
