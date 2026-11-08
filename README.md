# EffectCraft for Android

An offline-first native Android motion-graphics editor for phones, tablets, and foldables. This branch is a product implementation in progress—not a CI-only sample.

- Native Kotlin/Jetpack Compose UI; no WebView.
- Rust composition/project core bridged through JNI.
- App-private draft workspace and offline project/media storage.
- Supported canonical project paths: EffectCraft `.ecproj` JSON and `.ecprojx` XML, with validation/migration and SAF import/export foundations.
- Actual native CPU/session preview frames; GPU/device compatibility and media parity still require qualification.
- Layer, transform, keyframe, Bezier/ShapePath editing and PNG/MP4 export foundations are implemented incrementally.

## Build

Requirements: JDK 17, Android SDK Platform 37 and the pinned Android NDK described in the implementation docs. The committed official Gradle Wrapper is 9.5.0 with a distribution SHA-256.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

For native bridge and emulator instrumentation, use the reproducible workflow in [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml). The full setup and minimum Android version are documented in [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md).

## Offline by design

The Android manifest intentionally does not request `INTERNET`. Project drafts, canonical documents, media payloads and recovery state are stored locally; SAF is used for user-selected import/export locations. No cloud service is required for editing.

## Current engineering status (2026-11-08 10:35 IST)

**Release is NOT complete.** PR #1 merged into `main` at `4ae22776`; post-merge work is in [draft PR #2](https://github.com/sunmughan/After-Effects-Android/pull/2), not merged. [PHASES.md](PHASES.md) is the authoritative M0–M7 acceptance ledger.

### CI baseline and latest implementation
- [CI #1183](https://github.com/sunmughan/After-Effects-Android/actions/runs/42444715221) passed both jobs at exact source/test SHA `ccf60a7c`: Rust 61/61, ARM64+x86_64 JNI, JVM, debug APK, unsigned release APK/AAB+R8, API30 instrumentation 175/175 (0 skipped/failed), and independent cold APK reproducibility (both debug APK SHA-256 `705278b2d16f75017b1844a5a03e6ccee3fc4993afea7eba63b125afe4aab790`). This verifies strict UTF-8 import rejection. Newer duplicate XML member guard commits [69335d64](https://github.com/sunmughan/After-Effects-Android/commit/69335d64f69134520c8b5474717f3e3f793445c9) + [2568aabb](https://github.com/sunmughan/After-Effects-Android/commit/2568aabb4cd3f08ebcded1438f2867e8e4d80bce) are awaiting exact-head CI.
- [CI #1179](https://github.com/sunmughan/After-Effects-Android/actions/runs/42440451096) passed both jobs at exact source/test SHA `d2ef6771`: ARM64+x86_64 JNI, Rust 61/61, JVM, debug APK, unsigned release APK/AAB+R8, API30 instrumentation 175/175 (0 skipped/failed), and independent cold APK byte comparison. This verifies XML declaration-free/comment-before-root parsing plus all prior changes through that SHA.
- M2 fix [60dc6bcd](https://github.com/sunmughan/After-Effects-Android/commit/60dc6bcd49a9b9439a70ab8435128a2e8cfaa9c4) canonicalizes `OfflineAssetReferences` lock keys so real and symlink paths to one file serialize. Regression [46c27832](https://github.com/sunmughan/After-Effects-Android/commit/46c2783257fc1b9aa8a5363267fed2bddd74586f) runs 8 independent writers × 25 writes (200 unique mappings) through both paths and checks reopen integrity/staging cleanup. Push CI #1170 [run 42439234144](https://github.com/sunmughan/After-Effects-Android/actions/runs/42439234144) is running on test SHA `46c27832`; PR check #1172 [run 42439260823](https://github.com/sunmughan/After-Effects-Android/actions/runs/42439260823) is pending.
- Earlier KXML packaging/dependency failures (#1145 R8 duplicate XmlPull classes, #1151 unpublished KObjects coordinate) were corrected with Maven Central KXML 2.3.0 plus a deterministic JAR transform excluding `org/xmlpull/**`: [cf01caf5](https://github.com/sunmughan/After-Effects-Android/commit/cf01caf53568a13e951edb55fe0a1088230f917c), [54bf36e1](https://github.com/sunmughan/After-Effects-Android/commit/54bf36e1ff0fb639617199586681dfeb68e7510e), [d9c88793](https://github.com/sunmughan/After-Effects-Android/commit/d9c88793ab29a590af78d3b071ec4c8de1ba7731). Exact-head verification is required for release R8 and parser tests.

- M2 XML parsing now rejects duplicate object member names rather than silently overwriting values: [implementation 69335d64](https://github.com/sunmughan/After-Effects-Android/commit/69335d64f69134520c8b5474717f3e3f793445c9), [regression 2568aabb](https://github.com/sunmughan/After-Effects-Android/commit/2568aabb4cd3f08ebcded1438f2867e8e4d80bce); exact-head CI pending.
- M2 XML content detection accepts declaration-free `.ecprojx` with comments before the root; implementation [0a489e9c](https://github.com/sunmughan/After-Effects-Android/commit/0a489e9c7db218f034d3700e98e7642bc1a81655), regression [d2ef6771](https://github.com/sunmughan/After-Effects-Android/commit/d2ef67715a98129d547539f1db1704be2cd49beb); fully accepted by CI #1179 above. New import boundary strictly decodes UTF-8 with `CodingErrorAction.REPORT`, rejecting malformed bytes rather than silently replacing them; implementation/test [ccf60a7c](https://github.com/sunmughan/After-Effects-Android/commit/ccf60a7cf89b7846d00a3126fb786d6e50953098). Current exact-head CI #1183 [run](https://github.com/sunmughan/After-Effects-Android/actions/runs/42444715221) is in progress.
- Footage relink is covered by `M2DeviceFlowsInstrumentedTest.safCopyNativeRelinkExport` using the pinned upstream missing-footage fixture; the real JNI leg is ARM64-gated, so emulator skip is not physical proof (details in [PHASES.md](PHASES.md) and [function coverage](docs/M7_FUNCTION_COVERAGE.md)).

### Phase snapshot
| Phase | Status | Remaining acceptance |
|---|---|---|
| M0 | Historical acceptance | Revalidate latest-head dual ABI/JVM/API30/cold reproducibility |
| M1 | Scoped offline adaptive editor accepted | Professional interaction/accessibility and physical phone/tablet/foldable UX |
| M2 | Canonical EffectCraft JSON/XML, SAF, offline media and transaction integrity | Exact-head CI; broader Effekseer interoperability and missing-media relinking |
| M3 | Actual Rust CPU/session preview and Android frame presentation | GPU/Surface/device-loss/media parity and physical performance |
| M4 | Transform/keyframe/Bezier/ShapePath editing foundations | Full effect/interpolation/roving/spatial parity and golden edit/reopen/preview tests |
| M5 | PNG/MP4/audio export foundations | Device codec/audio sync, long-run cancellation/recovery and output playback |
| M6 | Recovery/offline/cache/memory safeguards | Physical performance/thermal/low-memory/accessibility/API/ABI matrix |
| M7 | Unsigned APK/AAB and signing safety checks | Owner-controlled signing, signed artifacts and physical install/run |

### Format and release limitations
- Adobe After Effects `.aep` is proprietary; no licensed/available parser is integrated and native support is **not** claimed.
- Effekseer `.efkproj` parser/converter compatibility remains incomplete.
- CI unsigned artifacts are not production-signed releases. Keystore/passwords remain owner-controlled and must never be committed/exposed.
- Local Gradle/Android SDK execution is unavailable in this runtime; hosted Actions is the build/test gate. Emulator CI is not physical ARM64 GPU/thermal/low-memory/install evidence.

Continue implementation phase-by-phase and fix failures without weakening tests. Update [PHASES.md](PHASES.md) in place. **EffectCraft release remains NOT COMPLETE.**
