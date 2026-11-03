### Run 26 — exact-head JUnit evidence and project-scoped keyframe persistence (2026-11-03)
- Branch-tip CI [#337](https://github.com/sunmughan/After-Effects-Android/actions/runs/41609309110), commit `7478f235f57c4f6b5df73b371157b60895a6d283`, completed **success**. All workflow steps passed: pinned Wrapper, ARM64+x86_64 JNI builds/package verification, Rust 13/13, dependency/license inventory, APK, JVM 32/32, API 30 emulator 19/19 (0 skipped/failed), build/audit artifact and test-result artifact.
- Inspected artifact `effectcraft-android-test-results` ID `13218447950` (110,777 bytes): 7 JVM suites/32 testcases, 0 failures/errors/skips; connected XML individually records 19 testcases, 0 failures/errors/skips. Named proofs include `nativeBridgeLoadsAndInvokesCanonicalParserOnEmulator`, `inspectorCanReorderAndDeleteAddedLayer`, `deletingLayerUndoAndRedoRestoreTheExpectedTimelineSelection`, `savedOfflineProjectsCanBeOpenedWithoutNetwork`, `effectCraftEcprojxXmlRoundTripsTypedProjectValues`, and `offlineMediaLibraryOpensAndExplainsEmptyState`.
- M4 product work: added `ProjectKeyframeStore` (project-scoped/versioned JSON keyed by SHA-256 of project ID; atomic persistence; sorted scalar tracks; same-frame upsert; key removal and empty-track cleanup; project clear; strict project/layer/property/frame/finite-value/schema validation). Four JVM test methods cover project isolation/restart/upsert, removal/clear, invalid input, and mismatched project document.
- Implementation + four JVM tests + initial tracker update: commit `b4a7b286ebe1d9af9fb6ccfbe1ecafba6993804e`. README content commit after history cleanup: `213679b738373b0f8408b0e1132cc72e737d2049`; tracker chain commit: `16f35f8409ed9fe29732963a815a0f8d32db4b6e`. Exact-head CI for the new M4 code is pending. These keyframe tracks remain editor-side draft data and are **not** yet connected to timeline UI, canonical `.ecproj` serialization, or native renderer evaluation; M4 is not complete.
- M0 remains NOT COMPLETE despite technical CI green: licensing and owner acceptance are explicit open gates; no license or acceptance is invented. M1 scoped editor foundation remains COMPLETE at CI #337. M2–M7 remain open.
### Run 25 — per-test CI evidence publishing (2026-11-03)
- Change only: the CI workflow gains a second artifact step, `effectcraft-android-test-results`, uploading the JUnit XMLs the runners actually produce — JVM unit results from `app/build/test-results/testDebugUnitTest/` and emulator instrumentation results from `app/build/outputs/androidTest-results/connected/` — plus their HTML reports. It runs with `if: always()` so a failing run still publishes partial results. No test, threshold, build step, or other workflow step was changed, and no test result file was hand-written.
- Why: the prior green run #335 proved only aggregate counts (19/19 instrumentation) because its log contains no per-test names and its artifact contains no test XMLs, so criterion #7 (native bridge test `nativeBridgeLoadsAndInvokesCanonicalParserOnEmulator`) and the named delete tests stayed UNPROVEN at named-test level on strict evidence.
- Evidence the next green run on this change must contain before any named-test claim: the `effectcraft-android-test-results` artifact must list the unit-test XMLs and the connected-test XMLs, and those XMLs must individually show `nativeBridgeLoadsAndInvokesCanonicalParserOnEmulator`, `inspectorCanReorderAndDeleteAddedLayer`, and `deletingLayerUndoAndRedoRestoreTheExpectedTimelineSelection` as passed (with per-test counts). Until that artifact is inspected, no per-test proof is claimed by this entry. M0 header status is unchanged: NOT COMPLETE, licensing (#12) and owner acceptance (#13) remain open user gates.

### Run 24 — M0 header correction to current-tip CI #335 (2026-11-03)
- Exact-head CI [#335](https://github.com/sunmughan/After-Effects-Android/actions/runs/41605623282) on commit `5e9e60fa83f138a7859162390997c1032525a700` completed **success** (job "Assemble and test"). Rust bridge suite: 13 passed, 0 failed. Android API 30 x86_64 emulator instrumentation: 19 started / 19 finished, 0 skipped / 0 failed at every checkpoint. Build/audit artifact `effectcraft-android-build-and-audit`, 41,775,453 bytes, unexpired.
- Evidence qualification (must be preserved): the CI log contains no per-test names and the artifact contains no test XMLs, so the named bridge/delete tests are source-present and consistent with the 19/19 aggregate, not individually log-proven. Criterion #7 stays UNPROVEN at named-test level on strict evidence.
- This entry corrects the M0 section header, which previously claimed COMPLETE on stale CI #286 (commit `bd274c7879cdd596d767303ace8791012543f199`, 18/18 instrumentation). That stale claim is withdrawn: M0 is NOT complete while the open user gates remain — licensing (gate #12: no root LICENSE file; GitHub license API returns 404) and owner acceptance (gate #13: PR #1 still draft and conflicting, zero reviews). No license is chosen and no owner acceptance is recorded by this entry. No physical device proof is claimed.

### Run 23 — offline footage payload preserves decoder extension (2026-11-03)
- Code review caught an integration risk before acceptance: native media decoders may dispatch by filename extension. The content-addressed library now stores payload as `<sha256>.<safe-extension>` (lowercase extension from the original display name) rather than extensionless hash; the original first-import metadata/extension is retained on dedupe. Commits `3382df93` and `2a4008d1` also fixed deletion to retain index metadata before removing the index entry, so the exact payload is deleted. Regression updates `0df38e01` assert `.mov` suffix, decoder-facing file path and correct cleanup.
- The app relink flow uses this immutable content-addressed path; source extension is preserved for EffectCraft's media decoder. Hash IDs and extension metadata are validated against path traversal; filenames do not enter payload paths.
- Latest code HEAD before tracker update: `5ecf6c17a4028812ccb21ec9d3c19984b45ca49c`. CI #304 was active at Cargo cache setup for an earlier test commit; pushes #305+ supersede older workflow attempts. A completed exact-head Gradle/JVM/emulator run is still required; no tests are reported as passed in CI.
- M2 acceptance still requires integrating/validating project asset references (not only relink path), media library browse/replace/remove UX, missing-file recovery, and SAF permission handling. This run did not claim full M2 completion.

### Run 22 — content-addressed offline media import and relinking (2026-11-03)
- Inspected open PR #1, branch tip and recent Actions jobs. CI #289 on commit `3c24a35f1759e547b91b1b1d204a1dd6b0935d69` is the current confirmed M0/M1 technical acceptance: 18/18 API 30 x86_64 instrumentation tests passed; prior Run 21 documents exact dual-ABI/Rust/JVM/Wrapper checks. Run #294 on `2df2f65b` reached emulator instrumentation after ARM64+x86_64 builds, JNI packaging, Rust tests, dependency inventory, APK assembly and JVM tests passed, but was cancelled by newer pushes while instrumentation was running; it is not a pass.
- M2 product implementation: `OfflineAssetStore.kt` (commit `020413c8`, follow-up `cd9a69f0`) streams SAF media into app-private storage with a 512 MiB default cap, SHA-256 content-addressed immutable payloads, deduplication, MIME/name/size index persisted via atomic Properties replacement, read/open/delete operations and validated native relink path. Malformed IDs are rejected. No network permission is needed.
- Integrated canonical footage relinking in `EffectCraftApp.kt` commit `7157542e`: the selected SAF stream now imports through OfflineAssetStore and native relink points to the hash-addressed payload, replacing prior per-item/name copy logic. Existing canonical project update/validation remains.
- Added `OfflineAssetStoreTest` (commit `4fa95780`, corrected assertion `86fe9dd6`): streaming/open bytes + metadata, content dedupe/first metadata, size-limit cleanup/no publish, invalid digest rejection and deletion. Four JVM tests committed; not yet CI-verified. Source review corrected the malformed-ID test to expect rejection (not null).
- Current tip after integration: `7157542e6030477363e619f283ab342d6e549f60`. CI #298/#299/#300 are successive push runs and older runs are concurrency-cancelled; require latest-tip run to finish all unit and instrumentation checks before M2 test acceptance. No local Gradle execution is available in this connector-only run.
- M2 remains IN PROGRESS: asset library + native relink is implemented, but media picker UX/library browser, durable project-to-asset reference manifest/garbage collection, URI permission lifecycle and physical media playback tests remain. Effekseer .efkproj and Adobe .aep are still explicitly unsupported.
- M3 renderer is actual upstream CPU composition rendering through JNI (canonical preview only); shared session/resize/teardown/GPU and physical ARM64 rendering still open. M4 keyframe primitives remain unwired to persisted model/timeline; M5 export queue/audio/codec/progress/cancel not started; M6 device/performance/recovery/accessibility matrix and M7 release/signing/artifacts remain open.
- Tracker updates must retain exact per-phase acceptance criteria, evidence, implementation commits and blockers. Never infer green from cancelled runs.

# EffectCraft Android phase tracker

### Run 21 — current-tip green, delete test fixed, gates still open (2026-11-03)
- Exact-head CI [#289](https://github.com/sunmughan/After-Effects-Android/actions/runs/41592566593) on commit `3c24a35f1759e547b91b1b1d204a1dd6b0935d69` completed **success**. Android API 30 x86_64 emulator instrumentation: 18/18 passed, 0 skipped, 0 failed, including `inspectorCanReorderAndDeleteAddedLayer` and `deletingLayerUndoAndRedoRestoreTheExpectedTimelineSelection`, which failed in every earlier run in this task.
- Delete root cause, proven by diagnostic evidence in run 41582544878 and the follow-up failure in run 41586013157: the phone inspector sheet content overflowed its visible area, so the text-only Remove click never reached the delete action and the sheet dismissed instead, leaving the model and the default Unsaved changes status untouched. The fix is two parts: InspectorPanel is vertically scrollable with a fixed spacer instead of a weighted spacer (commit 9378f0e), and both delete tests scroll to the Delete action before clicking it (commit bd274c7). No delete assertion was weakened, removed, or hardcoded: the Layer removed status wait, the status assertion, and the Shape 4 removal assertion are unchanged.
- Tip breaks fixed in this task and still present: the adaptive test picker parameters (commit 2aafaa4), the missing HeaderBar onExportEcprojx parameter (commit 3f02c32), and the cargo-ndk setup failure (commit 3c24a35, --force added because the cached binary made a plain install fail twice on the tip). The temporary DeleteDiagTest was removed after its evidence was read; no diagnostic test remains.
- This entry claims CI green only. Licensing choice and owner acceptance remain separate open gates per the fix task brief, so M0 complete is not claimed by this entry. PR #1 remains unmerged and repo visibility was not changed by this task.

### Run 20 — M0/M1 acceptance and import-format guardrails (2026-11-03)
- Exact-head CI [#286](https://github.com/sunmughan/After-Effects-Android/actions/runs/41588154232) on commit `bd274c7879cdd596d767303ace8791012543f199` completed **success**. All 25 job steps succeeded: pinned Gradle Wrapper 9.5.0 clean-checkout invocation, ARM64 + x86_64 native bridge compilation/package verification, Rust tests (13 passed), dependency/license inventory, debug APK, JVM tests, Android API 30 x86_64 emulator instrumentation (18/18 passed, 0 skipped/failed), and build/audit artifact upload (artifact ID `13209746628`). Instrumentation explicitly includes `nativeBridgeLoadsAndInvokesCanonicalParserOnEmulator`, the EffectCraft XML typed-value round trip, offline multi-project save/open/upsert, layer actions/selection/Undo/Redo, opacity/visibility, timeline scrub/play/pause, and 1080dp adaptive workspace.
- This passed the technical M0 checks and the scoped M1 editor-foundation checks at the tested commit; it did not by itself complete M0 — see the Run 24 header correction, which records M0 as NOT complete while licensing and owner-acceptance gates remain open. Physical ARM64 phone/tablet validation, production rendering/export and broader lifecycle/performance remain later-phase requirements.
- M2 implementation commit `53b6bc6e844423703216f99eb8e3d0318f05d106` adds `ProjectFileFormats`: SAF canonical import now rejects known `.efkproj` (Effekseer parser/converter absent), `.aep` (proprietary/licensed parser absent), and Android `.efx-draft` (must use offline Open) with explicit actionable messages before reading/caching. `.ecproj` JSON/`.ecprojx` XML and extensionless content-based parsing remain allowed. Added 3 JVM tests for supported extensions, case-insensitivity, unsupported boundaries and draft routing; current-head CI #287 is running and those new tests are pending verification.
- Latest branch head at this update: `53b6bc6e844423703216f99eb8e3d0318f05d106`; no #287 pass is claimed yet.


### Run 19 — inspector reachability and instrumentation corrections (2026-11-03)
- CI [#282](https://github.com/sunmughan/After-Effects-Android/actions/runs/41582544878) completed ARM64/x86_64 native builds, JNI packaging checks, Rust tests, dependency/license inventory, debug APK assembly and JVM tests; Android instrumentation failed 5 cases.
- Exact diagnostic evidence from #282: the delete probe reported `BOUNDS left=0.0 top=0.0 right=0.0 bottom=0.0 | AFTER_REMOVE removed=0 unsavedChanges=1 keepOne=0 props=0 bg=1 title=1 accent=1 shape4=1 selShape4=1 selAccent=0`. The sheet dismissed while the model stayed unchanged; the text-only click was not reaching the delete action. The phone inspector content could overflow its sheet/short viewport.
- Product fix commit `9378f0e2855838ad9c66e2ce668f047c32e2471b`: InspectorPanel is vertically scrollable (including tablet/short-window use), the bottom weighted spacer no longer consumes scroll height, and Move/Delete actions expose explicit accessible content descriptions. Instrumentation waits for the actual displayed action and clicks its semantics node. This is a UI reachability/accessibility fix, not a weakened delete assertion.
- Corrected two false test assumptions from #282: saved offline project test now validates the restored five-layer model through DraftProjectStore because the visible timeline intentionally shows at most four rows; adaptive workspace test matches the actual `COMPOSITION VIEWER · OFFLINE DRAFT` heading by substring.
- Temporary deliberately-failing DeleteDiagTest was removed after reading #282's diagnostic message. Run #283's newer pre-click bounds probe was still assembling when this implementation push superseded it; no output from #283 is claimed. The useful #282 failure evidence is recorded above.
- Current product instrumentation: 17 EditorSmokeTest cases + 1 AdaptiveWorkspaceSmokeTest = 18; no diagnostic test remains. All assertions remain enabled.
- Fresh CI for commit `9378f0e` and the subsequent tracker/README update is required. No current-head pass or M1 acceptance is claimed.

### Run 18 — delete action diagnostic (resolved)
- Temporary diagnostic was used to expose the delete interaction failure. CI #282's failure message was read and is preserved in Run 19 above.
- Root cause was test interaction with a clipped/unreachable phone-inspector action; the inspector is now scrollable and delete/move controls have explicit accessibility semantics.
- The deliberately failing diagnostic test has been removed in commit `9378f0e2855838ad9c66e2ce668f047c32e2471b` after evidence capture. No product assertions were removed.
- The additional v4 pre-click-bounds run #283 was superseded before completion; it is not counted as evidence or a pass.

### Run 17 — scalar keyframe engine groundwork and diagnostic cleanup (2026-11-03)
- Added immutable `PropertyTrack` / `PropertyKeyframe` data types and `PropertyTrackEditor` operations: sorted upsert (same-frame replacement), key removal, finite/non-negative validation, linear interpolation, and constant endpoint hold. This is pure Kotlin groundwork; it is **not yet wired to persisted canonical project tracks or timeline UI**, so M4 keyframe authoring remains incomplete.
- Added JVM tests for key ordering/replacement, interpolation at/between/outside keys, removal/empty tracks and invalid values: `PropertyTrackEditorTest` (4 test methods). A separate `kotlinc` smoke harness compiled the primitive and passed upsert/order/interpolation/endpoints/removal/validation checks; this is not a substitute for Gradle/JUnit CI. Commits: implementation `547db7ca44ef964b702c3bad1e99f0f83f11172b`, tests `f4a7f9dcc4a7493642cc69f79ecf5d01d2ff7561`.
- Repository inspection found `app/src/androidTest/java/ai/effectcraft/android/DeleteDiagTest.kt` still present at branch HEAD despite Run 15 claiming its removal. It intentionally called `fail(report)` and would make every instrumentation run fail. Removed this temporary diagnostic-only test in `f0f086577a760156a759c9ed826cf694a3d547a9`; no product test or assertion was removed.
- Current CI #270 [run link](https://github.com/sunmughan/After-Effects-Android/actions/runs/41573602946) had ARM64 build pass and x86_64 build in progress at inspection; it targets older HEAD `3f02c32a`. The above commits supersede it under workflow concurrency; latest-head CI must run afresh. No CI or keyframe-test pass is claimed.
- Branch HEAD before this evidence update: `1639df22b245ae84c720adaf2d8b869672d21cd5`. CI #274/#275 are queued or pending; no full Gradle/JUnit/instrumentation result yet.

Updated: 2026-11-03 (M1 CI pending; scalar keyframe primitives added; diagnostic test removed)
Latest implementation commit: `f4a7f9dcc4a7493642cc69f79ecf5d01d2ff7561` adds a pure immutable scalar keyframe editor and four JVM tests; it is not yet connected to project persistence or timeline UI. Temporary intentionally-failing DeleteDiagTest was removed after branch inspection.

### Run 16 — EffectCraft XML interchange import/export (2026-11-03)
- Added an Android XML Pull Parser/Serializer converter for EffectCraft's documented lossless `.ecprojx` representation (`EffectCraftProject encoding="json" version="1"`, typed `obj/m/arr/str/num/bool/null` nodes). It normalizes XML to canonical JSON before the pinned native `Project` parser validates and caches the project; JSON `.ecproj` remains unchanged.
- Added SAF XML export via CreateDocument(`application/xml`) with the `.ecprojx` extension, preserving the existing JSON `.ecproj` export action. Compact and wide headers expose distinct accessible Export .ecprojx XML actions.
- Added instrumentation `effectCraftEcprojxXmlRoundTripsTypedProjectValues` covering nested object/array, string XML escaping, numeric, boolean and null values. Test is committed but has not yet run on Android.
- Format scope is explicit: upstream EffectCraft `.ecproj` JSON and its documented lossless `.ecprojx` XML are the implemented interchange paths. Effekseer `.efkproj` is a different format and remains unsupported (no Effekseer parser/converter integrated). Adobe `.aep` remains proprietary and unsupported without a licensed/available parser.
- Implementation commits: `2faf104c` (converter), `960629db` (SAF import normalization), `1f375981` + `49f55053` (round-trip instrumentation), `862a79cb` (XML SAF export), `ac3b42d6` + `47680f52` (UI action and adaptive test callback).
- Current M1 instrumentation count: 17 `EditorSmokeTest` cases + 1 adaptive workspace case = 18 total (diagnostic test removed). Offline save-upsert assertions are in the existing offline catalog test. Current-head CI is pending; no phase acceptance or test pass is claimed.

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
- Instrumentation `savedOfflineProjectsCanBeOpenedWithoutNetwork` (test commit `6fa242dd32ab1385b1007f5db8870259010b1a75`) clears its draft fixture, saves a 4-layer draft, saves a separate 3-layer draft, opens the 4-layer entry and asserts Shape 4 and local-open status. The editor suite now contains 17 `EditorSmokeTest` cases plus one adaptive workspace test (18 total).
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
**Status: NOT COMPLETE — technical CI green; legal/owner gates remain open** · latest exact-head evidence: commit `7478f235f57c4f6b5df73b371157b60895a6d283`, [run #337](https://github.com/sunmughan/After-Effects-Android/actions/runs/41609309110) (success; Rust 13/13, JVM 32/32, emulator 19/19; JUnit artifact inspected).
- **Open user gates blocking M0 completion:** (a) licensing — gate #12: no root LICENSE file and the GitHub license API returns 404; no license is chosen by this correction. (b) owner acceptance — gate #13: PR #1 is still draft and conflicting with zero reviews; no owner acceptance is recorded by this correction.
- **Superseded claim:** the prior header claimed COMPLETE on stale CI #286 (commit `bd274c7879cdd596d767303ace8791012543f199`, 18/18 instrumentation). That claim is withdrawn: under the tracker rule that a phase is complete only when its code, tests, and evidence meet every required gate, M0 cannot be marked complete while these gates remain open.
- [x] Native Android/Compose project and offline manifest; no INTERNET permission.
- [x] Pinned upstream EffectCraft revision, Rust/JNI bridge, Android toolchain and ABI targets.
- [x] Official Gradle Wrapper 9.5.0 with pinned distribution SHA-256; clean CI checkout invoked `./gradlew`.
- [x] ARM64 + x86_64 JNI libraries compiled and packaged; both shared objects verified non-empty.
- [x] Rust bridge suite: **13 passed, 0 failed**; includes canonical project edits/round-trips, offline PNG decode and bounded CPU-render frame.
- [x] Debug APK assembled; JVM unit tests passed.
- [x] Android API 30 x86_64 emulator: **19/19 instrumentation aggregate passed, 0 skipped/failed at every checkpoint** (run #335); native bridge loaded and canonical parser invoked via JNI. Prior run #286 evidence (18/18 at commit `bd274c7`) is superseded by #335.
- [x] Resolved Android/Rust dependency trees and license inventory generated; audit artifact uploaded (run #335 artifact `effectcraft-android-build-and-audit`, 41,775,453 bytes, unexpired).
- [x] CI job completed success on exact tested HEAD; no test was removed/weakened to obtain green.
- **Evidence limitation:** emulator proves x86_64 runtime. Physical ARM64 installation/smoke and performance matrix are M6, not M0. Further qualification: the #335 log contains no per-test names and the artifact contains no test XMLs, so the named bridge/delete tests are source-present and consistent with the 19/19 aggregate, not individually log-proven; criterion #7 stays UNPROVEN at named-test level on strict evidence. No physical device proof is claimed.
- **Exit gate: NOT SATISFIED — licensing (gate #12) and owner acceptance (gate #13) remain open user gates.**

## M1 — Professional native editor foundation
**Status: COMPLETE — scoped editor acceptance passed and re-verified at branch tip** · evidence: [CI #337](https://github.com/sunmughan/After-Effects-Android/actions/runs/41609309110), commit `7478f235f57c4f6b5df73b371157b60895a6d283`, Android instrumentation **19/19 passed** with named JUnit XML evidence (0 skipped/failed).
- [x] Adaptive Jetpack Compose phone/tablet workspace; 1080dp wide-window instrumentation verifies side-by-side properties and composition viewer.
- [x] Offline starter project, app-private draft persistence, saved-project catalog, stable-ID save/upsert/deduplication and open/reload of latest revision.
- [x] Layer select/add/delete/reorder/rename, visibility and opacity; selection continuity and last-layer invariant.
- [x] Bounded 50-snapshot Undo/Redo for scoped project/layer/composition changes; deletion Undo/Redo restores project, selection and playhead.
- [x] Composition width/height/FPS/duration editing with validation, history and playhead clamping.
- [x] Timeline selection, frame scrubbing/timecode, FPS-aware playback, play/pause and accessible semantics.
- [x] Offline draft schema v1 round-trip/backward-compatible visibility default; invalid models rejected.
- [x] All 18 current emulator cases passed, including `savedOfflineProjectsCanBeOpenedWithoutNetwork`, `effectCraftEcprojxXmlRoundTripsTypedProjectValues`, `deletingLayerUndoAndRedoRestoreTheExpectedTimelineSelection`, `editorSaveButtonPersistsTheCurrentOfflineLayerStack`, `nativeBridgeLoadsAndInvokesCanonicalParserOnEmulator`, and adaptive workspace.
- **Scope boundary:** bootstrap `.efx-draft` is an Android editing model, not upstream `.ecproj`; canonical engine-backed editing/preview remains M2/M3/M4. No mock render is represented as actual output.
- **Exit gate: SATISFIED for M1 foundation.** M2 onward continues independently.

## M2 — Project formats, import/save and media
**Status: IN PROGRESS — canonical import/edit and offline asset relinking implemented; exact latest-head CI pending**
- [x] JNI validates canonical upstream `.ecproj` JSON through pinned `effectcraft-project` model and returns composition metadata.
- [x] Added `docs/NATIVE_ENGINE_INTEGRATION.md` documenting actual JNI scope, platform ABI caveats, and render/project integration gates.
- [x] Rust host test round-trips canonical `Project::default().to_file_json()` and checks metadata inspection (empty project only).
- [x] Rust non-empty canonical project fixture exercises layer rename, stable ID preservation and JSON round-trip; Kotlin JNI wrapper exposes rename envelope.
- [x] Rust non-empty canonical `.ecproj` fixture tests rename, metadata and serialization round-trip.
- [x] Canonical editor UI can add a native Text layer to selected composition, rename it, refresh model metadata and atomically cache serialized project; upstream `build::layer` allocates stable ID and Transform properties.
- [ ] Kotlin/JNI ARM64 device round-trip test for SAF-open → rename → export.
- [x] SAF document picker, persisted URI read permission, app-private canonical document retention, and `.ecproj` JSON export.
- [x] Lossless EffectCraft `.ecprojx` XML import normalization and export serializer; native `.ecproj` validation remains the gate before caching (Run 16; current-head instrumentation pending).
- [x] Native metadata lists composition and layer IDs/names/source/visibility; Android canonical-project sheet selects composition/layer and renames via Rust project model, reserializes and atomically caches the edited `.ecproj` (ARM64 runtime only; CI x86_64 bridge cannot execute JNI).
- [x] Instrumentation test verifies the canonical document cache preserves source bytes.
- [x] `OfflineAssetStore` streams media with a 512 MiB cap, SHA-256 content-addressed immutable payloads, dedupe, atomic Properties index, validated IDs and list/get/open/delete (`020413c8`, `cd9a69f0`).
- [x] SAF footage relinking imports through the offline store and passes the hash-addressed payload path to native model relink; filename extension is retained for decoder dispatch and deletion uses retained index metadata (`7157542e`, `3382df93`, `2a4008d1`).
- [x] Four JVM asset-store tests cover import/read/metadata, dedupe, size-limit cleanup, malformed ID and deletion (`4fa95780`, `86fe9dd6`, `0df38e01`); exact-head CI pending.
- [ ] Asset library browse/replace/remove UX, project-to-asset manifest/garbage collection, missing-media recovery and SAF permission lifecycle acceptance.
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
- **Format boundary:** bootstrap `.efx-draft` is private Android JSON, not `.ecproj`. EffectCraft `.ecproj` JSON + `.ecprojx` XML are supported interchange formats. Effekseer `.efkproj` is not implemented. Adobe `.aep` is proprietary; native import is not promised without a legally available compatible parser/conversion route.

## M3 — Actual preview/render engine
**Status: PARTIAL — M0/M1 accepted; real upstream CPU composition rendering through JNI is active for canonical projects; shared session/GPU/device gates remain**
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
**Status: IN PROGRESS — canonical transform/property editing plus project-scoped scalar keyframe persistence; timeline/native evaluation not yet wired**
- [x] Canonical Transform/Opacity is now a real upstream `Property` mutation (0–100, key-aware at frame zero), exposed by JNI, reflected in metadata and edited with a commit-on-release slider; each change reserializes the `.ecproj` and refreshes CPU preview (`9a0af7d`, `340e601`, `7997036`, `1166a2c`, `71509df`). Rust tests cover property mutation/range/ID; Kotlin parser test covers opacity metadata (`e049130`). CI/device validation pending.
- [x] Canonical Transform/Position X/Y edits preserve Z and key-aware set at frame zero; native model test verifies exact vector and rejects non-finite coordinates/unknown IDs (`b2422dc`); JNI/Kotlin bridge and inspector numeric controls Apply into project cache (`a8a94c2`, `764c311`, `21f4a80`). Parser tests cover X/Y metadata (`0a96985`). CI/device validation pending.
- [x] Canonical Transform Scale X/Y and Z Rotation editing: Rust mutations preserve Scale Z and set key-aware frame-zero values, bounded finite input validation, JNI/Kotlin APIs, metadata parsing, inspector Apply controls and CPU preview refresh; Rust round-trip/error tests and Kotlin metadata test added (`6b1b45f`). Current-head CI validation pending.
- [ ] Anchor/3D rotation editing and text/shape/effect parameter UI; canonical layer lock and selection parity.
- [x] Pure Kotlin scalar `PropertyTrackEditor` supports sorted key upsert/removal, finite-value validation and linear interpolation/endpoint hold (implementation `547db7ca`, tests `f4a7f9dc`); not yet connected to canonical persistence, native evaluation or timeline UI.
- [x] `ProjectKeyframeStore` persists draft scalar tracks per project ID in versioned JSON using a SHA-256-derived filename and atomic replacement; sorted upsert/replacement, remove/clear and strict malformed-document/input rejection. Four JVM tests added in Run 26; current-head CI verification pending. Tracks are not yet connected to UI or native evaluation.
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

### Run 11 — asset dedupe assertion corrected; offline library browser implemented (2026-11-03)
- Inspected open PR #1, branch tip and recent Actions. Exact-head CI #309 (`1af2bb23`) passed ARM64 and x86_64 native builds, JNI packaging, 13 Rust tests, dependency/license inventory and debug APK assembly; Kotlin compilation passed. JVM suite reported **29 tests, 1 failure**: `OfflineAssetStoreTest.deduplicatesIdenticalPayloadsAndKeepsFirstLibraryMetadata`, assertion at line 48 expected a bare 64-hex filename. That expectation became stale when the decoder-compatibility fix intentionally changed payload naming to `<sha256>.<extension>`.
- Corrected—not weakened—the regression to assert exactly one payload beginning with the expected digest and the exact `<digest>.bin` payload exists. Test fix commit `ff9b71edab2bbd04d61c3af15dfb1fae3d1d024d`. Dedupe, first-metadata and extension-bearing path assertions remain.
- Implemented M2 offline media library browser in the editor header for phone/tablet: lists app-private assets (display name, extension, size, digest prefix), empty-state instructions, explicit removal confirmation and delete action. Catalog refreshes after successful SAF footage relink and after removal; removal reports errors in editor status. UI commits: `1ba479ca5f6101426ecbd882423b8304f20008c8`, `313cae9fc9320e23d1a519a49953e3f5d9f4cc8d`, `2f829e6fa7c7c8f08831ce87c56b8bd4d38bef8b`.
- Exact latest-tip CI #312 is pending at `313cae9f`; #310 was running with the test correction but was superseded by UI changes. Neither is green evidence. Require a fresh exact-head run for final UI signature correction and full unit/instrumentation suite.
- M2 still requires durable project-to-asset references/manifest, safe garbage collection (the current remove dialog warns that referenced footage can break), missing-media recovery and persisted SAF permission lifecycle. Do not mark M2 complete.
- M0/M1 technical checks passed at CI #286 (full dual ABI/JNI/Rust/JVM + 18/18 API30 instrumentation); latest #289 additionally passed 18/18 after editor interaction fixes. (M0-status note: this is technical evidence only, not an M0 completion claim — see the Run 24 header correction; M0 is NOT complete while licensing and owner-acceptance gates remain open.) M3 partial real CPU renderer; M4 partial transform/keyframe groundwork; M5–M7 open.
- Follow-up persistence test commit `114e257eb76b1842a5140663e64849f3f214956f`: added a fresh-store recreation check that reloads the persisted catalog, verifies case-insensitive display-name ordering, and reopens exact bytes; this complements dedupe/limit/delete tests.
- Latest code tip after this test is `114e257e`; documentation commits trigger new CI. Current latest run #314 was queued before this follow-up; a new exact-tip run must complete (native, Kotlin/JVM and emulator) before claiming media acceptance.

- M2 follow-up: added durable `OfflineAssetReferences` (item ID → SHA-256) with atomic persistence, validated IDs/hashes, replacement semantics, removal and clear-on-new-canonical-import; tests cover restart, replacement/unreference, removal and invalid inputs. Commits `cf4ce328`, `7db34fdb`, `64f2f482`, `cb3f6aea`, `be7751eb`, `ae349467`.
- Integrated relink to persist the selected footage-item→asset mapping and reset references on canonical import (`2fcff068`). Media library now marks referenced assets as Linked and disables removal; the app callback independently blocks deleting a referenced hash. UI safeguard commit `ae50ab2d`.
- Reference-aware M2 behavior is still bounded to the currently cached canonical document; multi-project asset manifests, native missing-media resolution and recovery are not implemented. Latest implementation tip `2fcff068`; run #326 queued before this commit. Fresh exact-head CI must validate the new Kotlin class/tests and editor changes.
- Added API 30 instrumentation `offlineMediaLibraryOpensAndExplainsEmptyState` to verify the header control is accessible, opens the library dialog and presents actionable empty-state copy (`fe195564c4981a61ee2c0fdd97d91018c3eb6af3`). This is UI regression coverage; exact-head CI has not run yet.

- Transactional follow-up `79fb8566b859b2e1089b409703e345abb8b56a6a`: canonical import saves the validated document before clearing prior project references; footage relink now restores the previous item→asset mapping if canonical cache save fails, preventing a half-applied reference update.

### Tip CI fix — AdaptiveWorkspaceSmokeTest signature catch-up (2026-11-03)
- Tip `ba502e4` CI run 41599624787 FAILED at `:app:compileDebugAndroidTestKotlin`: `AdaptiveWorkspaceSmokeTest.kt:29` passed no value for the new required `EditorScreen` parameters `offlineAssets`, `referencedOfflineAssetIds`, `onDeleteOfflineAsset` (added by media work, `EditorScreen.kt` lines ~108-109, 116). No production code was broken; only the second call site was stale.
- Fix: the adaptive test now passes `offlineAssets = emptyList()`, `referencedOfflineAssetIds = emptySet()`, `onDeleteOfflineAsset = {}` in the same argument positions as the production call site in `EffectCraftApp.kt`. The values match what the test intends to verify (starter project, no offline media, no canonical project; the wide-layout assertions on COMPOSITION VIEWER / PROPERTIES are untouched, and no assertion was weakened, deleted, or stubbed).
- No defaulted parameters were added: the codebase's established pattern is all-required `EditorScreen` parameters, and defaulting would hide the next signature change instead of surfacing it at compile time. Production behaviour is unchanged.
- Recurrence addressed (same pattern as Run 15, signature change breaking the adaptive call site): a grep over the tree confirms exactly two `EditorScreen(` call sites exist — production `EffectCraftApp.kt` and this test — so any future `EditorScreen` signature change must update both, and `:app:compileDebugAndroidTestKotlin` in CI is the gate that catches a missed one. That two-call-site invariant is now recorded here so the next signature change does not re-discover it.
- Local verification limits on this VM (stated plainly, not green): no Android SDK platform-37 exists locally (stable repo tops at API 36), the Gradle daemon cannot communicate in this sandbox (`Could not dispatch a message to the daemon`, loopback connection reset), and no emulator is available — so local unit/instrumentation runs were not possible. CI on the fix commit is the compile and instrumentation verifier for this change.
