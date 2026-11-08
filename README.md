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

## Current engineering status (2026-11-08 08:05 IST)

**Release is NOT complete.** PR #1 merged into `main` at `4ae22776`; post-merge work is in [draft PR #2](https://github.com/sunmughan/After-Effects-Android/pull/2), not merged. [PHASES.md](PHASES.md) is the authoritative M0–M7 acceptance ledger.

### CI baseline and latest implementation
- [CI #1163](https://github.com/sunmughan/After-Effects-Android/actions/runs/42433344604) passed exact baseline SHA `f8f616dc`: Rust 61/61, ARM64+x86_64 JNI, JVM, unsigned APK/AAB, API30 M1 smoke, and independent cold debug-APK comparison. This is **before** the new offline-reference symlink lock/test commits.
- M2 fix [60dc6bcd](https://github.com/sunmughan/After-Effects-Android/commit/60dc6bcd49a9b9439a70ab8435128a2e8cfaa9c4) canonicalizes `OfflineAssetReferences` lock keys so real and symlink paths to one file serialize. Regression [46c27832](https://github.com/sunmughan/After-Effects-Android/commit/46c2783257fc1b9aa8a5363267fed2bddd74586f) runs 8 independent writers × 25 writes (200 unique mappings) through both paths and checks reopen integrity/staging cleanup. Exact-head CI is pending.
- Earlier KXML packaging/dependency failures (#1145 R8 duplicate XmlPull classes, #1151 unpublished KObjects coordinate) were corrected with Maven Central KXML 2.3.0 plus a deterministic JAR transform excluding `org/xmlpull/**`: [cf01caf5](https://github.com/sunmughan/After-Effects-Android/commit/cf01caf53568a13e951edb55fe0a1088230f917c), [54bf36e1](https://github.com/sunmughan/After-Effects-Android/commit/54bf36e1ff0fb639617199586681dfeb68e7510e), [d9c88793](https://github.com/sunmughan/After-Effects-Android/commit/d9c88793ab29a590af78d3b071ec4c8de1ba7731). Exact-head verification is required for release R8 and parser tests.

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
