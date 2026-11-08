# EffectCraft for Android

An offline-first native Android motion-graphics editor for phones, tablets, and foldables. This branch is a product implementation in progress—not a CI-only sample.

- Native Kotlin/Jetpack Compose UI; no WebView.
- Rust composition/project core bridged through JNI.
- App-private draft workspace and offline project/media storage.
- Supported canonical project paths: EffectCraft `.ecproj` JSON and `.ecprojx` XML, with validation/migration and SAF import/export foundations.
- Actual native CPU/session preview frames; GPU/device compatibility and media parity still require qualification.
- Layer, transform, keyframe, Bezier/ShapePath editing and PNG/MP4 export foundations are implemented incrementally.

## Engineering progress (2026-11-09 00:59 IST)

- **M2 duplicate-member escape test fixture corrected:** [f27d312e](https://github.com/sunmughan/After-Effects-Android/commit/f27d312eb4e34ff46f73bad812e362173ac43c3a) changes the Kotlin raw JSON fixture to single-backslash `\\t` and `\\u0009` escapes so both keys decode to a tab. Standalone Kotlin scanner compilation and four structural/escape cases pass; exact Android CI remains pending.
- **M2 duplicate JSON scanner Kotlin compile failure fixed:** CI [#1312](https://github.com/sunmughan/After-Effects-Android/actions/runs/42508463104) reached Kotlin compilation after native ARM64/x86_64 and Rust tests passed, then failed on unsupported escape sequences in the scanner's control-character literals. Corrected the implementation to decode JSON escapes via explicit character codes and scan JSON whitespace without fragile Kotlin escapes; added duplicate escaped-tab key regression. This is a source compile fix; fresh exact-head CI is required for acceptance. Details and gate status in [PHASES.md](PHASES.md).

## Engineering progress (2026-11-08 18:12 IST)

- **M4 Add Triangle implemented:** the native bridge creates a canonical editable 3-point Polygon Shape with fill, JNI + Kotlin API + Compose inspector + saved project state wiring are connected. Native test round-trips and requires visible pixels from the real upstream compositor; API30 instrumentation taps Add Triangle and checks the callback. [Native/test fdc86fa8](https://github.com/sunmughan/After-Effects-Android/commit/fdc86fa853e95b2af9afc39a60ddaa86c286d096), [JNI 14ddb5a8](https://github.com/sunmughan/After-Effects-Android/commit/14ddb5a84d3f62fe74dea103383698e11ac7577b), [app 9b560709](https://github.com/sunmughan/After-Effects-Android/commit/9b5607095235491978f1f9a16608ef5cf60a4a97), [UI b95c1ad5](https://github.com/sunmughan/After-Effects-Android/commit/b95c1ad58bb580fa3187005874082e61ed100242), [instrumentation 7a89868f](https://github.com/sunmughan/After-Effects-Android/commit/7a89868f5531935253adb4545006ae54e4c0098f).
- Exact latest-head PR CI [#1255](https://github.com/sunmughan/After-Effects-Android/actions/runs/42481722017) is pending at `7a89868f`; do not claim Add Triangle acceptance until native/JNI/JVM/API30 and independent cold APK comparison pass.
- [CI #1244](https://github.com/sunmughan/After-Effects-Android/actions/runs/42475277378) at `555d3e68`: primary assemble/test job passed; cold reproducibility job failed at Temurin JDK download HTTP 504 twice (not a source/test failure; no second build ran). Retry was superseded by later pushes. Latest exact-head CI is the acceptance gate.
- Previous complete accepted checkpoint [CI #1222](https://github.com/sunmughan/After-Effects-Android/actions/runs/42460342703) at `3fbc4a96`: Rust 63/63, both JNI ABIs, JVM, unsigned APK/AAB + R8, API30 176/176, independent cold APK byte match. This accepts Add Ellipse only.
- M4 Add Star callback fix [d0b14cbe](https://github.com/sunmughan/After-Effects-Android/commit/d0b14cbeef0c6baa325442d5180196c9f2699b06), project-wide Shape UID allocator [448f2537](https://github.com/sunmughan/After-Effects-Android/commit/448f25377c44f79074776c1cda8ac4394e3d9e0c), and real white→red fill edit compositor test [52d1caad](https://github.com/sunmughan/After-Effects-Android/commit/52d1caad505f8891ef053cb1ddff19054ee52189) remain tracked in [PHASES.md](PHASES.md).
- **Release remains NOT complete.** M0–M7 acceptance, Effekseer interoperability, physical GPU/device/media qualification, codec/audio sync, owner-controlled signing and physical installation/run remain open. Adobe `.aep` is proprietary and unsupported natively here; no compatibility claim.


## Engineering progress (2026-11-08 18:38 IST)

- **Kotlin method-size failure fixed in source:** CI #1257 at `ec0bdf4b` failed with ASM `MethodTooLargeException` in the Compose-generated `CanonicalProjectEditor` method after Rust/JNI had passed. Split the transform/keyframe, Source Text, and Shape/effect parameter panels into smaller composables [97a1ae3d](https://github.com/sunmughan/After-Effects-Android/commit/97a1ae3d45fd511763e286dd10d1c8f2d8198310). A faulty [a40aa02c](https://github.com/sunmughan/After-Effects-Android/commit/a40aa02c68ce16426ef63b3b2f32b76e88a0a122) follow-up accidentally truncated the source file; the complete editor was restored and text-helper scope corrected at [a356f5d0](https://github.com/sunmughan/After-Effects-Android/commit/a356f5d0bc0725e34cf403918931623868cfc752), then indentation normalized [7f464d0d](https://github.com/sunmughan/After-Effects-Android/commit/7f464d0dc0c1e9f57abd15099cf107eab6044e09). No tests/features were removed.
- CI #1261 (a40aa02c), #1263 (4d75f182), and #1267 (7f464d0d) were superseded/cancelled; #1267 stopped during runner cleanup, before compilation. Latest PR check #1269 is queued at docs SHA 772e1287 with restored source SHA 7f464d0d. Compiler fix is not accepted until Kotlin, Rust/JNI, JVM, release R8/APK/AAB, API30 instrumentation and cold APK reproducibility all pass.
- Release remains NOT complete; PR #2 remains Draft. See [PHASES.md](PHASES.md) for exact acceptance evidence and blockers.

## Build

Requirements: JDK 17, Android SDK Platform 37 and the pinned Android NDK described in the implementation docs. The committed official Gradle Wrapper is 9.5.0 with a distribution SHA-256.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

For native bridge and emulator instrumentation, use the reproducible workflow in [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml). The full setup and minimum Android version are documented in [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md).

## Offline by design

The Android manifest intentionally does not request `INTERNET`. Project drafts, canonical documents, media payloads and recovery state are stored locally; SAF is used for user-selected import/export locations. No cloud service is required for editing.

## Current engineering status (2026-11-08 18:38 IST)

**Release is NOT complete.** PR #1 merged into `main` at `4ae22776`; post-merge work is in [draft PR #2](https://github.com/sunmughan/After-Effects-Android/pull/2), not merged. [PHASES.md](PHASES.md) is the authoritative M0–M7 acceptance ledger.

### CI baseline and latest implementation
- [CI #1183](https://github.com/sunmughan/After-Effects-Android/actions/runs/42444715221) passed both jobs at exact source/test SHA `ccf60a7c`: Rust 61/61, ARM64+x86_64 JNI, JVM, debug APK, unsigned release APK/AAB+R8, API30 instrumentation 175/175 (0 skipped/failed), and independent cold APK reproducibility (both debug APK SHA-256 `705278b2d16f75017b1844a5a03e6ccee3fc4993afea7eba63b125afe4aab790`). This verifies strict UTF-8 import rejection. Newer duplicate XML member guard commits [69335d64](https://github.com/sunmughan/After-Effects-Android/commit/69335d64f69134520c8b5474717f3e3f793445c9) + [2568aabb](https://github.com/sunmughan/After-Effects-Android/commit/2568aabb4cd3f08ebcded1438f2867e8e4d80bce) are awaiting exact-head CI.
- [CI #1183](https://github.com/sunmughan/After-Effects-Android/actions/runs/42444715221) passed both jobs at exact source/test SHA `ccf60a7c`: Rust 61/61, ARM64+x86_64 JNI, JVM, debug APK, unsigned release APK/AAB+R8, API30 175/175 (0 skipped/failed), and independent cold APK comparison (both SHA-256 `705278b2d16f75017b1844a5a03e6ccee3fc4993afea7eba63b125afe4aab790`). This verifies strict UTF-8 rejection.
- M4 now authors a real canonical Shape layer via JNI (rectangle path + fill), exposed as Add Rect in the canonical inspector and rendered by the upstream compositor. Commits: [Rust c41828df](https://github.com/sunmughan/After-Effects-Android/commit/c41828df155086b10f8f309e88e45fdd8085fa5b), [native render regression bc75f91e](https://github.com/sunmughan/After-Effects-Android/commit/bc75f91ecaa3abd20727dfa81d053d27a4da0d91), [JNI 77901d69](https://github.com/sunmughan/After-Effects-Android/commit/77901d69dd88ab2624ca58b161cc4e57c8cdcb73), [UI 5e3fe4df](https://github.com/sunmughan/After-Effects-Android/commit/5e3fe4df532f1c713866358f13fd99f15c560b57), [app wiring 75ef2892](https://github.com/sunmughan/After-Effects-Android/commit/75ef2892ae22c28d78f948824ae25d41c565b1bb), [device test 7a449a7a](https://github.com/sunmughan/After-Effects-Android/commit/7a449a7a98596f2c79ddc0ce5466b4a5fc874ff7). Exact-head CI #1203 is queued.
- [CI #1179](https://github.com/sunmughan/After-Effects-Android/actions/runs/42440451096) passed both jobs at exact source/test SHA `d2ef6771`: ARM64+x86_64 JNI, Rust 61/61, JVM, debug APK, unsigned release APK/AAB+R8, API30 instrumentation 175/175 (0 skipped/failed), and independent cold APK byte comparison. This verifies XML declaration-free/comment-before-root parsing plus all prior changes through that SHA.
- M2 fix [60dc6bcd](https://github.com/sunmughan/After-Effects-Android/commit/60dc6bcd49a9b9439a70ab8435128a2e8cfaa9c4) canonicalizes `OfflineAssetReferences` lock keys so real and symlink paths to one file serialize. Regression [46c27832](https://github.com/sunmughan/After-Effects-Android/commit/46c2783257fc1b9aa8a5363267fed2bddd74586f) runs 8 independent writers × 25 writes (200 unique mappings) through both paths and checks reopen integrity/staging cleanup. Push CI #1170 [run 42439234144](https://github.com/sunmughan/After-Effects-Android/actions/runs/42439234144) is running on test SHA `46c27832`; PR check #1172 [run 42439260823](https://github.com/sunmughan/After-Effects-Android/actions/runs/42439260823) is pending.
- Earlier KXML packaging/dependency failures (#1145 R8 duplicate XmlPull classes, #1151 unpublished KObjects coordinate) were corrected with Maven Central KXML 2.3.0 plus a deterministic JAR transform excluding `org/xmlpull/**`: [cf01caf5](https://github.com/sunmughan/After-Effects-Android/commit/cf01caf53568a13e951edb55fe0a1088230f917c), [54bf36e1](https://github.com/sunmughan/After-Effects-Android/commit/54bf36e1ff0fb639617199586681dfeb68e7510e), [d9c88793](https://github.com/sunmughan/After-Effects-Android/commit/d9c88793ab29a590af78d3b071ec4c8de1ba7731). Exact-head verification is required for release R8 and parser tests.

- M2 XML parsing now rejects duplicate object member names rather than silently overwriting values: [implementation 69335d64](https://github.com/sunmughan/After-Effects-Android/commit/69335d64f69134520c8b5474717f3e3f793445c9), [regression 2568aabb](https://github.com/sunmughan/After-Effects-Android/commit/2568aabb4cd3f08ebcded1438f2867e8e4d80bce); exact-head CI pending.
- M2 XML parser rejects duplicate object member names instead of silently overwriting them: [69335d64](https://github.com/sunmughan/After-Effects-Android/commit/69335d64f69134520c8b5474717f3e3f793445c9) + [2568aabb](https://github.com/sunmughan/After-Effects-Android/commit/2568aabb4cd3f08ebcded1438f2867e8e4d80bce); exact-head CI pending.
- M2 XML content detection accepts declaration-free `.ecprojx` with comments before the root; implementation [0a489e9c](https://github.com/sunmughan/After-Effects-Android/commit/0a489e9c7db218f034d3700e98e7642bc1a81655), regression [d2ef6771](https://github.com/sunmughan/After-Effects-Android/commit/d2ef67715a98129d547539f1db1704be2cd49beb); fully accepted by CI #1179 above. New import boundary strictly decodes UTF-8 with `CodingErrorAction.REPORT`, rejecting malformed bytes rather than silently replacing them; implementation/test [ccf60a7c](https://github.com/sunmughan/After-Effects-Android/commit/ccf60a7cf89b7846d00a3126fb786d6e50953098). Current exact-head CI #1183 [run](https://github.com/sunmughan/After-Effects-Android/actions/runs/42444715221) is in progress.
- Footage relink is covered by `M2DeviceFlowsInstrumentedTest.safCopyNativeRelinkExport` using the pinned upstream missing-footage fixture; the real JNI leg is ARM64-gated, so emulator skip is not physical proof (details in [PHASES.md](PHASES.md) and [function coverage](docs/M7_FUNCTION_COVERAGE.md)).

### Phase snapshot
| Phase | Status | Remaining acceptance |
|---|---|---|
| M0 | Historical acceptance | Revalidate latest-head dual ABI/JVM/API30/cold reproducibility |
| M1 | Scoped offline adaptive editor accepted | Professional interaction/accessibility and physical phone/tablet/foldable UX |
| M2 | Canonical EffectCraft JSON/XML, SAF, offline media and transaction integrity | Exact-head CI; broader Effekseer interoperability and missing-media relinking |
| M3 | Actual Rust CPU/session preview and Android frame presentation | GPU/Surface/device-loss/media parity and physical performance |
| M4 | Transform/keyframe/Bezier/ShapePath + native Rect/Ellipse creation | Full effect/interpolation/roving/spatial parity and golden edit/reopen/preview tests |
| M5 | PNG/MP4/audio export foundations | Device codec/audio sync, long-run cancellation/recovery and output playback |
| M6 | Recovery/offline/cache/memory safeguards | Physical performance/thermal/low-memory/accessibility/API/ABI matrix |
| M7 | Unsigned APK/AAB and signing safety checks | Owner-controlled signing, signed artifacts and physical install/run |

### Format and release limitations
- Adobe After Effects `.aep` is proprietary; no licensed/available parser is integrated and native support is **not** claimed.
- Effekseer `.efkproj` parser/converter compatibility remains incomplete.
- CI unsigned artifacts are not production-signed releases. Keystore/passwords remain owner-controlled and must never be committed/exposed.
- Local Gradle/Android SDK execution is unavailable in this runtime; hosted Actions is the build/test gate. Emulator CI is not physical ARM64 GPU/thermal/low-memory/install evidence.

Continue implementation phase-by-phase and fix failures without weakening tests. Update [PHASES.md](PHASES.md) in place. **EffectCraft release remains NOT COMPLETE.**


## Latest engineering checkpoint (2026-11-08 19:19 IST)

- PR #2 was merged at [11821cd5](https://github.com/sunmughan/After-Effects-Android/commit/11821cd57278c5a00c2d284d809604206c28903a); its head branch was deleted. The requested `feat/android-app-foundation` branch has been recreated from current `main` (`3d7445c9`) for continued work. PR #1 remains merged.
- [CI #1270](https://github.com/sunmughan/After-Effects-Android/actions/runs/42484015152) failed Kotlin compilation at SHA `9a5b2894`: the canonical inspector's extracted helper boundary had an incomplete `fontSize @Composable` statement, cascading into unresolved helper and syntax errors. Rust dual-ABI builds and native tests passed; downstream checks were skipped.
- Fixed the statement and restored the top-level composable boundary in [3f86c5ac](https://github.com/sunmughan/After-Effects-Android/commit/3f86c5ac89eae327c49f1a7a731642a4f2b3b2c4). This fix awaits fresh PR CI; do not treat it as accepted yet.
- Full acceptance evidence and remaining M0–M7 blockers: [PHASES.md](PHASES.md). Release is not complete.


- **M2 XML edge case:** valid processing instructions after the EffectCraft XML root are now accepted as exporter metadata (the parser still rejects extra roots/non-whitespace trailing text). Implementation [4a8b7e63](https://github.com/sunmughan/After-Effects-Android/commit/4a8b7e63bc7fb79989252f270e0825d40271bca0), regression [1f0f38b9](https://github.com/sunmughan/After-Effects-Android/commit/1f0f38b948e711c7a467870ed093af23be0121e5). Exact-head CI #1275 pending.


## Engineering progress (2026-11-08 20:42 IST)

- PR #3 merged and its branch deleted; recreated feat/android-app-foundation from main (including [post-merge compile repair ed80c34a](https://github.com/sunmughan/After-Effects-Android/commit/ed80c34aa2439831913fb56ebffea168cf045ab0)). Current continuation: [draft PR #4](https://github.com/sunmughan/After-Effects-Android/pull/4).
- **M4 explicit transform-key Move control regression:** [e4f476d2](https://github.com/sunmughan/After-Effects-Android/commit/e4f476d22814562ef0ed97c089d7f611e02114a6) cleans up target-frame state placement; [e33d56cb](https://github.com/sunmughan/After-Effects-Android/commit/e33d56cba15b538ec0f1fd39d4d605d9ec294287) adds Android instrumentation that enters target frame 20 and asserts the exact canonical move callback. This supplements, rather than replaces, the curve graph accessibility-action test.
- Exact-head [CI #1284](https://github.com/sunmughan/After-Effects-Android/actions/runs/42493757859) is pending at e33d56cb; Kotlin/Rust/JNI/JVM/release/API30/cold reproducibility gates are not yet accepted. No local Android SDK/Gradle is available in this runtime.
- Release remains NOT complete; see PHASES.md for phase-by-phase acceptance, test evidence, blockers, and the Adobe .aep / Effekseer .efkproj limitations.


- Latest M4 source [7a4e7a15](https://github.com/sunmughan/After-Effects-Android/commit/7a4e7a15f1ff3a301f5f64997c96104e2f2a0572) uses Android's numeric keyboard and digit-only/10-digit input for the transform keyframe target. Instrumentation [e33d56cb](https://github.com/sunmughan/After-Effects-Android/commit/e33d56cba15b538ec0f1fd39d4d605d9ec294287) covers target frame 20 via the explicit Move control. Exact-head CI is pending; no acceptance claim.


## Engineering progress (2026-11-08 21:46 IST)

- **CI #1290 failure diagnosed and test corrected:** the build, Rust dual-ABI bridge, Rust/JVM suites and unsigned release R8/APK/AAB passed; API30 instrumentation had 1 failure among 177 tests. The explicit transform-key Move test appended `20` to a prefilled `0`, then searched for a semantics label containing `020`; the test's cursor/selection assumption was not stable. [95713180](https://github.com/sunmughan/After-Effects-Android/commit/95713180dea7f2d9fd0bf09b7d6e1c186ff214ef) clears the numeric field, enters `20`, checks the Move control is enabled and verifies the same exact callback. No feature or assertion was weakened.
- Exact failure evidence: [Actions #1290](https://github.com/sunmughan/After-Effects-Android/actions/runs/42493884493), `AdaptiveWorkspaceSmokeTest.explicitTransformKeyMoveControlTargetsSelectedLayerAndFrame`; API30 176/177 passed, one failed. Cold reproducibility was skipped after the primary job failure. New CI at `95713180` is required before acceptance.
- Tracker: [PHASES.md](PHASES.md) records the precise failure, source/test SHA, remediation and pending fresh run. PR #4 remains Draft. Release remains **NOT complete**; physical GPU/media/device, signed artifact/install and full M0–M7 gates are outstanding.



- **M4 keyframe target validation hardened:** [f097ac95](https://github.com/sunmughan/After-Effects-Android/commit/f097ac955557c326f5d95bbdf6600c45cffbac0f) extends explicit Move instrumentation with a mixed/negative oversized input, verifies digit-only 10-character sanitization and overflow-disabled Move, then clears and confirms frame 20 succeeds. This is additional boundary coverage; no tests removed. See [PHASES.md](PHASES.md).

- Follow-up [47604d3c](https://github.com/sunmughan/After-Effects-Android/commit/47604d3cccca4d6387e8de00f9fff08b092991e3) removes the obsolete test comment that described appending to the prefilled frame. The source now explicitly describes clearing and entering a target frame. Current exact test SHA remains `f097ac95`; tracker/docs follow-up [22b273f8](https://github.com/sunmughan/After-Effects-Android/commit/22b273f88558c5d2a2b731c2404184a0369e5670). CI acceptance is still pending.


## Engineering progress (2026-11-08 22:55 IST)

- CI #1304 pinpointed the explicit transform Move instrumentation failure: its fixture omitted `transformKeyframes`, leaving the button disabled by design. [729f8409](https://github.com/sunmughan/After-Effects-Android/commit/729f840984aa0407df863c7805049e27fc578861) adds canonical position keys (0/15/30); no app gate was relaxed.
- M4 native test [c09fe485](https://github.com/sunmughan/After-Effects-Android/commit/c09fe4854a61ef39a3b4c7d14eba5cd07cffa9ce) now verifies moving a Bezier key onto an occupied frame replaces the neighbor and preserves the moved key's value/interpolation in serialized project data. Fresh exact-head CI is required.
- [PHASES.md](PHASES.md) is the authoritative M0–M7 ledger. Release remains NOT complete; physical device/GPU/media/install and owner-controlled signing are still blockers; Adobe `.aep` is not natively supported.


## Engineering progress (2026-11-08 23:35 IST)

- **CI #1309 green at exact docs HEAD 1a826388:** 70 Rust tests passed; API30 instrumentation 177/177; both CI jobs succeeded, including independent debug APK byte/content and ARM64/x86_64 JNI .so reproducibility. [Run #1309](https://github.com/sunmughan/After-Effects-Android/actions/runs/42504897643), [test artifact](https://github.com/sunmughan/After-Effects-Android/actions/runs/42504897643/artifacts/13627114212), [repro report](https://github.com/sunmughan/After-Effects-Android/actions/runs/42504897643/artifacts/13628206022). This evidence applies only to that SHA.
- **M2 import integrity:** commit [09d2dad2](https://github.com/sunmughan/After-Effects-Android/commit/09d2dad24cb5eab8dedcd6a35099010b07290376) adds duplicate JSON object member rejection before JSONObject parsing, including escaped-equivalent names, with JVM tests for nested collisions and false-positive-safe string/array handling. Fresh CI at this new HEAD is pending.
- See [PHASES.md](PHASES.md) for the acceptance ledger, exact SHAs, failures, evidence and blockers. Release is not complete; Effekseer .efkproj and Adobe .aep native parser support are not claimed, and physical device/signing gates remain open.



## Engineering progress — 2026-11-09

- **Exact-head CI #1317 passed** at `791a44b9`: Rust 70/70, ARM64+x86_64 JNI, Kotlin/JVM, unsigned release APK/AAB + R8, API30 177/177 and independent cold APK/JNI reproducibility. [Run #1317](https://github.com/sunmughan/After-Effects-Android/actions/runs/42515531444). This is historical evidence for that SHA only; newer Hexagon work needs fresh CI.
- **M4 Add Hexagon implemented end-to-end:** upstream native six-sided polygon + fill, JNI bridge, canonical inspector button, app save/selection integration, native actual-pixel compositor regression and Android callback instrumentation. Commits: [d029dc8a](https://github.com/sunmughan/After-Effects-Android/commit/d029dc8aadf7c16f7ad62341398cedcab79fbed0), [7ac65cdb](https://github.com/sunmughan/After-Effects-Android/commit/7ac65cdb6c5a55bba38d1161448a3d97934c2d65), [6a766299](https://github.com/sunmughan/After-Effects-Android/commit/6a766299bc0d327aec6d398d11e41dd6e1fcf61e), [4ef2ec2d](https://github.com/sunmughan/After-Effects-Android/commit/4ef2ec2d052117462dc3be9a497c6a5c158fdfb0), [d6d8cedd](https://github.com/sunmughan/After-Effects-Android/commit/d6d8cedd0b8664416878de73da76163c08756e86).
- See [PHASES.md](PHASES.md) for acceptance criteria, exact evidence, phase status and blockers. Release remains **NOT COMPLETE**; physical-device and signing gates remain open.


## Engineering progress — 2026-11-09 02:48 IST

- CI [#1328](https://github.com/sunmughan/After-Effects-Android/actions/runs/42520953988) built the Hexagon implementation and ran 177 API30 tests, with two failures caused by test navigation across the canonical inspector's outer sheet scroll and nested layer-list scroll containers. No product/runtime failure was indicated; cold reproducibility was skipped after instrumentation failed.
- Fixed the tests to scroll the sheet header and then the inner layer row: [Adaptive test f94933c9](https://github.com/sunmughan/After-Effects-Android/commit/f94933c9692568bdab2dd6a71fbd2a2603fcf13a), [selection test edde8aeb](https://github.com/sunmughan/After-Effects-Android/commit/edde8aeb3486696cfee02e9bd59f5de1dc5e9dee). Assertions remain intact. Fresh exact-head CI is pending.
- [PHASES.md](PHASES.md) records failure logs, root cause, commits and acceptance gate. Release remains **NOT COMPLETE**; see the phase ledger for device/signing/format blockers.


## Engineering progress — 2026-11-09 03:02 IST

- **M4 Add Pentagon:** native Rust Shape+Fill authoring and actual compositor pixel/JSON round-trip regression, JNI/Kotlin bridge, canonical inspector action, save/selection wiring and Android callback test are committed ([native 5da1ad74](https://github.com/sunmughan/After-Effects-Android/commit/5da1ad742634360d4d62781e4c542a502df8fb82), [bridge 28f5f6b7](https://github.com/sunmughan/After-Effects-Android/commit/28f5f6b706f93d4794d8ecf264ee6d7be0850119), [UI a6b22d7f](https://github.com/sunmughan/After-Effects-Android/commit/a6b22d7fe081fe9a8cc4c9f4ecb13d470357ff06), [app 1affcbc5](https://github.com/sunmughan/After-Effects-Android/commit/1affcbc5b91393ff30d7021df371ffe3e8280c1d), [test cff48e65](https://github.com/sunmughan/After-Effects-Android/commit/cff48e65d068456088c46c50aa375013b0530dd8)).
- CI #1328 exposed two clipped layer-row assertions due to nested sheet/list scroll state. Test navigation was repaired with explicit sheet-header then inner-list scrolling (f94933c9, edde8aeb); assertions remain. Exact-head CI #1341 is pending at cff48e65; #1331 was superseded/cancelled.
- [PHASES.md](PHASES.md) records exact failure evidence, remediation, new feature and acceptance gates. EffectCraft release remains NOT COMPLETE.


- Follow-up after CI #1328: test navigation now scrolls the outer `Layers` section into view before the nested row, not just the sheet header ([Adaptive ce94ed30](https://github.com/sunmughan/After-Effects-Android/commit/ce94ed309e98d5df78d7021907404c9db935bb11), [CanonicalSelection 56758bbf](https://github.com/sunmughan/After-Effects-Android/commit/56758bbfac3054ab328795d1be1c4dca385f61d3)). Latest full verification #1345 is queued; no acceptance until all gates pass.


## Engineering progress — 2026-11-09 03:16 IST

- CI #1347 failed after 177 API30 tests with two canonical layer-row visibility failures; native/JNI, Kotlin/JVM, debug and unsigned release packaging passed. The nested 210dp independently scrollable layer list was the product/UI root cause.
- Fixed the actual inspector scroll architecture at [dfec81cb](https://github.com/sunmughan/After-Effects-Android/commit/dfec81cb18549e736d0af586c38b45995ccb4143): layer rows now use the same outer inspector scroll container, improving touch/semantics/TalkBack reachability for off-fold layers. Existing test assertions remain intact. Fresh exact-head CI and cold APK reproducibility are pending.
- Acceptance remains M0 historical / M1–M6 partial / M7 NOT ACCEPTED. Physical GPU/media/codec/audio/performance/accessibility/install-run and owner signing gates remain open; Adobe .aep and complete Effekseer .efkproj support are not claimed.


- **Long layer-stack regression:** Android instrumentation now creates a 53-layer canonical composition, scrolls to Layer 54, verifies it is displayed, then returns to Background and continues the selection/edit assertions ([a3db126e](https://github.com/sunmughan/After-Effects-Android/commit/a3db126e1e7d95419aea24e81970f9f60255d3cd)). This covers the single-scroll inspector fix [dfec81cb](https://github.com/sunmughan/After-Effects-Android/commit/dfec81cb18549e736d0af586c38b45995ccb4143); fresh exact-head CI is pending.
