# EffectCraft Android — Phase Acceptance Tracker

Repository: [sunmughan/After-Effects-Android](https://github.com/sunmughan/After-Effects-Android)  
Implementation branch: `feat/android-app-foundation`  
[PR #1 merged](https://github.com/sunmughan/After-Effects-Android/pull/1) at `4ae22776`; [PR #4 draft follow-up](https://github.com/sunmughan/After-Effects-Android/pull/4) (PR #2 and #3 merged).  
Tracker updated: 2026-11-08 21:48 IST · test comment SHA 47604d3c · CI rerun pending

> This is the acceptance ledger. A phase is not complete because a feature is committed or a workflow is queued. CI evidence is tied to its exact SHA; device/credential gates remain explicit.

## Latest implementation / verification

- **M4 Add Triangle (new):** native `build::shape_star(..., star=false, points=3)` authors an editable three-sided polygon + fill; JNI, Kotlin bridge, Compose inspector action and app-state save/selection wiring are included. Native regression serializes/reopens and requires actual upstream compositor pixels; Android instrumentation taps Add Triangle and verifies composition/name callback. Commits: [native + pixel test fdc86fa8](https://github.com/sunmughan/After-Effects-Android/commit/fdc86fa853e95b2af9afc39a60ddaa86c286d096), [JNI bridge 14ddb5a8](https://github.com/sunmughan/After-Effects-Android/commit/14ddb5a84d3f62fe74dea103383698e11ac7577b), [app state 9b560709](https://github.com/sunmughan/After-Effects-Android/commit/9b5607095235491978f1f9a16608ef5cf60a4a97), [inspector b95c1ad5](https://github.com/sunmughan/After-Effects-Android/commit/b95c1ad58bb580fa3187005874082e61ed100242), [instrumentation 7a89868f](https://github.com/sunmughan/After-Effects-Android/commit/7a89868f5531935253adb4545006ae54e4c0098f). Exact-head CI [#1255](https://github.com/sunmughan/After-Effects-Android/actions/runs/42481722017) is pending; no acceptance claim yet.
- **CI #1244 source/build job succeeded; cold rebuild job failed only at external JDK bootstrap:** [run 42475277378](https://github.com/sunmughan/After-Effects-Android/actions/runs/42475277378), source SHA `555d3e68`. Assemble/test passed Rust, both JNI ABIs, JVM, debug + unsigned release APK/AAB/R8 and API30 smoke. Cold job's `actions/setup-java@v4` download from Adoptium returned HTTP 504 twice; no second Gradle build ran and reproducibility report was consequently absent. Disk cleanup left 108 GiB free (26% used), so this was not disk pressure. Failed cold job retry was superseded/cancelled by later pushes; current exact-head #1255 must pass cold comparison.
- **Earlier accepted baseline:** [CI #1222](https://github.com/sunmughan/After-Effects-Android/actions/runs/42460342703) passed exact SHA `3fbc4a96`: Rust 63/63, ARM64+x86_64 JNI, JVM, unsigned APK/AAB + R8, API30 176/176 (0 skipped/failed), and independent cold APK byte comparison. This accepts Add Ellipse, not the later Star/Triangle code.
- Add Star callback compile error from #1237 was fixed in [d0b14cbe](https://github.com/sunmughan/After-Effects-Android/commit/d0b14cbeef0c6baa325442d5180196c9f2699b06); M4 project-wide Shape property UID allocator correction [448f2537](https://github.com/sunmughan/After-Effects-Android/commit/448f25377c44f79074776c1cda8ac4394e3d9e0c); actual Star white→red edit→serialize→compositor regression [52d1caad](https://github.com/sunmughan/After-Effects-Android/commit/52d1caad505f8891ef053cb1ddff19054ee52189). Exact latest CI pending.
- Local Android SDK/Gradle execution is unavailable in this automation runtime; hosted Actions is the build/test gate. Emulator CI is not physical-device GPU/thermal/install proof.

## Latest compiler failure and source remediation — 2026-11-08 18:38 IST

- **CI #1257 failed at exact SHA `ec0bdf4b`** after both Rust/JNI ABI builds and Rust tests succeeded. `assembleDebug` stopped in Kotlin code generation with ASM `MethodTooLargeException` for `EditorScreenKt.CanonicalProjectEditor`; JVM tests, release packaging, instrumentation and cold APK comparison were skipped. This was a real source/compiler limit, not runner/JDK/network infrastructure.
- Root cause: the canonical inspector had accumulated preview, transform/keyframe interpolation, Source Text and shape/effect property UI into one Compose-generated JVM method exceeding the 64-KiB bytecode method limit.
- Refactor [97a1ae3d](https://github.com/sunmughan/After-Effects-Android/commit/97a1ae3d45fd511763e286dd10d1c8f2d8198310) extracts transform/keyframe, text and shape/effect parameter sections into dedicated composables, preserving canonical callbacks/state. A faulty follow-up [a40aa02c](https://github.com/sunmughan/After-Effects-Android/commit/a40aa02c68ce16426ef63b3b2f32b76e88a0a122) accidentally truncated the source file to the text helper; this was detected by checking file length/header and restored from the refactor commit with corrected text-helper scope at [a356f5d0](https://github.com/sunmughan/After-Effects-Android/commit/a356f5d0bc0725e34cf403918931623868cfc752). Call indentation was normalized in [7f464d0d](https://github.com/sunmughan/After-Effects-Android/commit/7f464d0dc0c1e9f57abd15099cf107eab6044e09).
- CI #1261 at a40aa02c and #1263 at 4d75f182 were superseded/cancelled before build acceptance. Exact-head CI #1267 at 7f464d0d was cancelled during hosted-runner cleanup when the docs commit superseded it. Latest PR check [CI #1269](https://github.com/sunmughan/After-Effects-Android/actions/runs/42483986534) is queued at docs HEAD 772e1287 (same restored source SHA 7f464d0d); it must prove Kotlin compilation, Rust/JNI, JVM, unsigned APK/AAB + R8, API30 instrumentation and independent cold APK reproducibility. No acceptance is claimed yet.
- The correction does not remove tests or reduce feature behavior. Local Android SDK/Gradle is unavailable in this runtime; hosted Actions is the compile/test authority.

### CI #1290 instrumentation failure and deterministic correction — 2026-11-08 21:46 IST

- Exact run [#1290](https://github.com/sunmughan/After-Effects-Android/actions/runs/42493884493) at docs SHA `e6c4517b` completed **failure**. The Assemble/test job successfully built ARM64 and x86_64 JNI, passed Rust tests, JVM tests, debug APK and unsigned release APK/AAB + R8. Android emulator instrumentation ran 177 tests with 1 failure; the independent cold APK comparison job was skipped because the primary job failed.
- Exact failing test: `AdaptiveWorkspaceSmokeTest.explicitTransformKeyMoveControlTargetsSelectedLayerAndFrame`. The assertion could not locate the semantics node `Move position keyframe to frame 020` after the test appended `20` to a prefilled `0` draft. This was a brittle test assumption about BasicTextField selection/cursor state, not a renderer/native failure.
- Corrected the test to explicitly clear the field before entering `20`, then assert the stable `Move position keyframe to frame 20` node is enabled before clicking it. It still asserts the full canonical callback `(composition 10, layer 2, transform:position, source 0, destination 20)`; no behavior or coverage was removed. [Fix commit 95713180](https://github.com/sunmughan/After-Effects-Android/commit/95713180dea7f2d9fd0bf09b7d6e1c186ff214ef).
- Follow-up hardening [f097ac95](https://github.com/sunmughan/After-Effects-Android/commit/f097ac955557c326f5d95bbdf6600c45cffbac0f) extends the same instrumentation: input `-x99999999999` must be sanitized to ten digits and its overflowing `Int` frame must leave Move disabled; the field is then cleared and valid frame `20` must enable Move and invoke the callback. This verifies digit filtering, length cap and overflow guard on-device without weakening the positive path.
- Fresh push CI is required at test SHA `f097ac95` (clarifying comment-only follow-up `47604d3c`); docs HEAD `618920b8` is queued. Cold APK comparison and full 177-test API30 suite remain unaccepted until exact-head CI passes. Local Android SDK/Gradle is unavailable here, so hosted Actions is the reproducible test authority.

## Phase acceptance

### M0 — Repository/toolchain/build/CI/native bridge
**Status: ACCEPTED historically; latest-head revalidation pending.**

Acceptance: pinned Gradle/JDK/SDK/NDK; reproducible Rust/JNI builds for ARM64 and x86_64; Rust/JVM tests; API30 instrumentation; debug/release APK+AAB; independent cold APK byte equality; no weakened tests.

Evidence: [CI #1011](https://github.com/sunmughan/After-Effects-Android/actions/runs/42315397626) at `9ebcdc36` (Rust 59/59, API30 165/165, JVM, dual ABI and cold rebuild). Newer complete accepted checkpoint #1222 is linked above.  
Remaining: latest-head all-gate cold comparison; hardware runtime qualification belongs to M6/M7.

### M1 — Professional Android editor foundation
**Status: PARTIAL — offline workspace and scoped editor flows accepted.**

Acceptance: offline create/open/save/recovery; adaptive phone/tablet/foldable workspace; layer select/add/delete/reorder/visibility; properties/opacity; timeline/playhead/playback; undo/redo; responsive accessibility and persistent state; instrumentation.

Evidence: canonical selection/add actions and API30 workspace/editor smoke at #1210/#1222; Add Rect/Ellipse/Star/Triangle callback tests in [CanonicalSelectionEditorTest](https://github.com/sunmughan/After-Effects-Android/blob/feat/android-app-foundation/app/src/androidTest/java/com/codeair/effectcraft/CanonicalSelectionEditorTest.kt).  
Remaining: full professional interaction parity, TalkBack/large-font/foldable/physical-device UX and complete M1 acceptance matrix.

### M2 — Project import, format compatibility and media assets
**Status: PARTIAL — canonical EffectCraft JSON/XML persistence and integrity foundations.**

Acceptance: strict parser/serializer; schema/version validation and migration; atomic import/save; media manifest/asset relinking; round-trip/golden fixtures; interoperable supported format matrix.

Evidence: `.ecproj` JSON + `.ecprojx` XML; strict UTF-8 [ccf60a7c](https://github.com/sunmughan/After-Effects-Android/commit/ccf60a7cf89b7846d00a3126fb786d6e50953098); duplicate XML member guard [69335d64](https://github.com/sunmughan/After-Effects-Android/commit/69335d64f69134520c8b5474717f3e3f793445c9) + [test 2568aabb](https://github.com/sunmughan/After-Effects-Android/commit/2568aabb4cd3f08ebcded1438f2867e8e4d80bce); comment-before-root XML sniff [0a489e9c](https://github.com/sunmughan/After-Effects-Android/commit/0a489e9c7db218f034d3700e98e7642bc1a81655) + [test d2ef6771](https://github.com/sunmughan/After-Effects-Android/commit/d2ef67715a98129d547539f1db1704be2cd49beb); canonical/symlink transaction locking [60dc6bcd](https://github.com/sunmughan/After-Effects-Android/commit/60dc6bcd49a9b9439a70ab8435128a2e8cfaa9c4) + [200-writer regression 46c27832](https://github.com/sunmughan/After-Effects-Android/commit/46c2783257fc1b9aa8a5363267fed2bddd74586).  
Remaining: broader Effekseer interoperability, complete media relink/migration fixtures and exact-head tests. Adobe `.aep` is proprietary; no licensed parser is integrated and native support is **not** claimed. `.efkproj` support is also not claimed.

### M3 — Actual composition preview/render integration
**Status: PARTIAL — real Rust CPU/session compositor and Android frame presentation.**

Acceptance: actual composition output (not mock); JNI renderer lifecycle; GPU/software fallback; media decode; surface/device-loss recovery; correctness/performance qualification.

Evidence: native `render_frame_rgba` and session/JNI preview, rendered-frame cache and Compose presentation; native Add Rect/Ellipse/Star/Triangle tests require visible compositor pixels.  
Remaining: physical GPU/Surface/device lifecycle, media parity, renderer fallback and device performance acceptance.

### M4 — Functional layer/effect/timeline/property editing
**Status: PARTIAL — native Shape creation and edit→render foundations; latest Triangle CI pending.**

Acceptance: editable layer/effect stack; transforms and keyframes/interpolation; Shape geometry/fill/stroke; properties; real preview refresh; golden edit→save→reopen→render tests.

Evidence:
- Add Rect [c41828df](https://github.com/sunmughan/After-Effects-Android/commit/c41828df155086b10f8f309e88e45fdd8085fa5b) + compositor test [bc75f91e](https://github.com/sunmughan/After-Effects-Android/commit/bc75f91ecaa3abd20727dfa81d053d27a4da0d91), accepted by #1207 at `75aa1a35`.
- Add Ellipse native [3a989ee3](https://github.com/sunmughan/After-Effects-Android/commit/3a989ee381257e9135d3aef64b5a66658d3b4d68), JNI/UI/test commits [a78bb5cf](https://github.com/sunmughan/After-Effects-Android/commit/a78bb5cf330ef28771fe2319f273f816eb474c0e), [34e2800d](https://github.com/sunmughan/After-Effects-Android/commit/34e2800d6f3b7b004a9854ecb83de727ead335d4), [f9bc2db1](https://github.com/sunmughan/After-Effects-Android/commit/f9bc2db1641d6cf4d1c52e9344c2d66a99e6bdd5); accepted #1222.
- Add Star [a5529895](https://github.com/sunmughan/After-Effects-Android/commit/a55298952ce3afe0532fdef3ea1d00ee25e96687), callback correction [d0b14cbe](https://github.com/sunmughan/After-Effects-Android/commit/d0b14cbeef0c6baa325442d5180196c9f2699b06), global UID fix [448f2537](https://github.com/sunmughan/After-Effects-Android/commit/448f25377c44f79074776c1cda8ac4394e3d9e0c), fill edit pixel test [52d1caad](https://github.com/sunmughan/After-Effects-Android/commit/52d1caad505f8891ef053cb1ddff19054ee52189).
- Add Triangle: native/JNI/app/UI/instrumentation [fdc86fa8](https://github.com/sunmughan/After-Effects-Android/commit/fdc86fa853e95b2af9afc39a60ddaa86c286d096), [14ddb5a8](https://github.com/sunmughan/After-Effects-Android/commit/14ddb5a84d3f62fe74dea103383698e11ac7577b), [9b560709](https://github.com/sunmughan/After-Effects-Android/commit/9b5607095235491978f1f9a16608ef5cf60a4a97), [b95c1ad5](https://github.com/sunmughan/After-Effects-Android/commit/b95c1ad58bb580fa3187005874082e61ed100242), [7a89868f](https://github.com/sunmughan/After-Effects-Android/commit/7a89868f5531935253adb4545006ae54e4c0098f); [CI #1255](https://github.com/sunmughan/After-Effects-Android/actions/runs/42481722017) pending at exact HEAD.
- Transform/keyframe/Bezier/ShapePath editors and canonical property setters are present; full upstream effect/interpolation/3D/roving parity and comprehensive golden fixtures remain.

### M5 — Render/export pipeline
**Status: PARTIAL — PNG sequence/ZIP, H.264 MP4 and audio mixdown foundations.**

Acceptance: resolution/FPS/frame-range/codec validation; real composition frames; streaming progress/cancel; audio synchronization; SAF output; integrity/playback validation and recovery.

Evidence: MP4 timestamp/EOS drain fixes [12f20781](https://github.com/sunmughan/After-Effects-Android/commit/12f20781), [91e2e2e8](https://github.com/sunmughan/After-Effects-Android/commit/91e2e2e8); audio cancellation regression [0ef68d16](https://github.com/sunmughan/After-Effects-Android/commit/0ef68d16).  
Remaining: physical codec/audio sync matrix, long-run/process-death recovery, exported-media playback across devices.

### M6 — Performance, memory, crash recovery, offline/device/accessibility
**Status: PARTIAL — bounded caches and recovery/instrumentation; physical qualification open.**

Acceptance: frame-time/memory budgets; leak/crash/process-death recovery; offline/storage-failure robustness; API/ABI/device matrix; accessibility and long-session/thermal tests.

Evidence: crash-safe journals/atomic persistence; offline asset handling; 256 MiB media pool + 64 MiB layer cache; revision-scoped preview cache and bitmap lifecycle [57ab6edd](https://github.com/sunmughan/After-Effects-Android/commit/57ab6edd), [209dbf27](https://github.com/sunmughan/After-Effects-Android/commit/209dbf27); budget test [00c869b8](https://github.com/sunmughan/After-Effects-Android/commit/00c869b8).  
Remaining: physical ARM64 performance/thermal/low-memory and accessibility/API/ABI qualification.

### M7 — Release readiness
**Status: NOT ACCEPTED.**

Acceptance: M0–M6 accepted; exact-head all-green CI and independent cold build; configurable release signing without committed secrets; signed APK/AAB; physical install/run; release notes, checksums, limitations and authorized release.

Evidence: CI builds unsigned APK/AAB and checks no debug-key fallback.  
Blockers: owner-controlled keystore/signing credentials and physical ARM64 device/install access are not available to this run. Continue unsigned reproducible artifacts and docs; never fabricate signing or install evidence.

## Release decision

**EffectCraft is NOT release-complete.** PR #1 is merged; that merge does not mean M0–M7 are accepted. No native Adobe `.aep` compatibility claim. Do not mark a phase complete merely because code is committed or CI is queued.


## Engineering checkpoint — Kotlin inspector syntax repair (2026-11-08 19:19 IST)

- Repository state changed since the prior run: PR #2 was **merged** at [11821cd5](https://github.com/sunmughan/After-Effects-Android/commit/11821cd57278c5a00c2d284d809604206c28903a). GitHub had deleted its head branch; this run recreated `feat/android-app-foundation` from current `main` (base SHA `3d7445c9`) so work can continue on the requested branch. PR #1 remains merged at `4ae22776`.
- **CI #1270 failed at SHA `9a5b2894`** in `:app:compileDebugKotlin` (job `128741895777`): the extracted inspector boundary had an incomplete statement: `fontSize @Composable`. Kotlin then reported unresolved extracted composables and cascading syntax/scope errors. Rust ARM64/x86_64 and Rust tests passed; JVM/release/instrumentation/cold jobs were skipped.
- Root fix: complete the unavailable-ABI notice as `fontSize = 11.sp)`, close `CanonicalProjectEditor` and start the extracted helper at a top-level `@Composable` boundary. The source is now structurally restored around lines 2087–2090; no feature or test was removed. Commit: [3f86c5ac](https://github.com/sunmughan/After-Effects-Android/commit/3f86c5ac89eae327c49f1a7a731642a4f2b3b2c4).
- **Verification pending:** this source commit has not yet run through PR CI. Create a fresh PR from the recreated branch and require Kotlin compilation, both native ABIs, JVM, release R8/APK/AAB, API30 instrumentation and independent cold APK comparison. Local Gradle/SDK remains unavailable in this automation runtime.
- M4 remains partial; Triangle/Star and inspector refactor are not accepted solely by commit. M0–M7 release status remains unchanged; no signed/physical install evidence and no native Adobe `.aep` claim.


## M2 follow-up — legal trailing XML processing instructions (2026-11-08 19:25 IST)

- During the pending compiler CI, audited the XML post-root validator. It allowed XML whitespace/comments but rejected `PROCESSING_INSTRUCTION`, although XML permits processing instructions after the document element. This could reject valid exporter metadata without changing the canonical project payload.
- Updated the trailing-event allowlist to accept `XmlPullParser.PROCESSING_INSTRUCTION` while continuing to reject a second root, non-whitespace text, and all other unexpected events: [4a8b7e63](https://github.com/sunmughan/After-Effects-Android/commit/4a8b7e63bc7fb79989252f270e0825d40271bca0).
- Added `ecprojxAcceptsProcessingInstructionAfterRoot`, parsing a real XML fixture with `<?effectcraft-export complete?>` after the root and asserting the project name survives: [1f0f38b9](https://github.com/sunmughan/After-Effects-Android/commit/1f0f38b948e711c7a467870ed093af23be0121e5).
- Latest source/test SHA: `1f0f38b9`; exact-head PR CI [#1275](https://github.com/sunmughan/After-Effects-Android/actions/runs/42486904072) queued/pending. Do not accept until Kotlin compile + JVM test (including this regression), both JNI ABIs, R8/APK/AAB, API30 and cold APK compare pass.


## Engineering checkpoint — keyframe move UI regression and branch recovery (2026-11-08 20:42 IST)

- PR #3 merged into main (merge commit [cab5a61e](https://github.com/sunmughan/After-Effects-Android/commit/cab5a61e6a4b062240cc1769a9f37c45cde1ea53)); its feature branch was deleted. Main's post-merge source repair [ed80c34a](https://github.com/sunmughan/After-Effects-Android/commit/ed80c34aa2439831913fb56ebffea168cf045ab0) restores the missing moveTargetDraft, removes an orphaned = 11.sp) and two stray closers, and drops an obsolete callback argument that no longer exists in EditorScreen. This was checked by a tokenizer-balance script; hosted CI remains the compiler authority.
- Recreated feat/android-app-foundation from main and opened [draft PR #4](https://github.com/sunmughan/After-Effects-Android/pull/4). PR #1 is still merged; PR #2 and #3 are historical merged continuations.
- **M4 concrete addition:** normalized moveTargetDraft declaration/row indentation in CanonicalTransformControls [e4f476d2](https://github.com/sunmughan/After-Effects-Android/commit/e4f476d22814562ef0ed97c089d7f611e02114a6) and added explicitTransformKeyMoveControlTargetsSelectedLayerAndFrame instrumentation [e33d56cb](https://github.com/sunmughan/After-Effects-Android/commit/e33d56cba15b538ec0f1fd39d4d605d9ec294287). It enters frame 20 in the inspector's explicit Move control and asserts the callback's composition/layer/selector/from/to values; this is separate from the already-tested curve graph accessibility action.
- Exact PR CI [#1284](https://github.com/sunmughan/After-Effects-Android/actions/runs/42493757859) is pending for source/test SHA e33d56cb. Required gates: Kotlin compile, Rust tests + ARM64/x86_64 JNI, JVM, unsigned release APK/AAB + R8, API30 instrumentation (including the new test), and independent cold APK byte comparison. No acceptance is claimed yet. Local Android SDK/Gradle is unavailable in this runtime.
- **Phase status unchanged:** M0 latest-head revalidation pending; M1/M2/M3/M4/M5/M6 partial; M7 not accepted. Physical GPU/device/codec/accessibility/install and owner-controlled signing remain concrete blockers; work continues in emulator/native CI and unsigned artifacts. Adobe .aep remains proprietary and unsupported natively; no claim is made.


- **M4 mobile input refinement (latest source):** transform keyframe destination now opens the numeric keyboard and filters to 10 digits before integer parsing; this avoids mobile QWERTY input and overflow/invalid-character drafts. Commit [7a4e7a15](https://github.com/sunmughan/After-Effects-Android/commit/7a4e7a15f1ff3a301f5f64997c96104e2f2a0572). The instrumentation still appends 20 to initial 0 (020 → frame 20), so it covers the real numeric-field/button flow. Latest source SHA 7a4e7a15; exact-head Actions status must be rechecked after this push.
