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

## Current engineering status (2026-11-08 04:10 IST)

**Release is NOT complete.** PR #1 was merged into `main` at `4ae22776` on 2026-11-07, but merge is not product/release acceptance; ongoing fixes are on `feat/android-app-foundation`. The authoritative per-phase acceptance criteria, exact test evidence, commit links and blockers are maintained in [PHASES.md](PHASES.md). CI #1131 failed one M2 concurrency assertion (480 tests, 1 failed). CI #1137 then failed Kotlin test compilation because the new exception diagnostic contained malformed escaped quotes at ProjectAssetManifestTest.kt:174; corrected in [`e7f78b60`](https://github.com/sunmughan/After-Effects-Android/commit/e7f78b6037e9e9907963149973a1c6c440c7162b). CI #1142 was cancelled by the next source push. Exact-head #1143 (`da3f6e5e`) failed two XML tests: `android.util.Xml.newSerializer` is an unmocked Android SDK stub in local JVM tests, and the parser's trailing-root exception did not match the asserted diagnostic. Fix [`81c10049`](https://github.com/sunmughan/After-Effects-Android/commit/81c100490938668c45ddcb2ee62d15de89e08fdc) adds kxml2 and uses its concrete parser/serializer, with a stable strict trailing-root error. CI #1144 is running on `81c10049`; no pass is claimed yet. Canonical repository/manifest lock keys resolve symlink aliases (`b8a6df4c`, `8a2cf102`); regression [`45e812e0`](https://github.com/sunmughan/After-Effects-Android/commit/45e812e0cbe89f96351db99a14de6975063d8fb2) proves an alias-root save waits through rollback. Do not mark M2 complete until tests and cold reproducibility pass.

| Phase | Status | Remaining gate |
|---|---|---|
| M0 | CI #1124 exact SHA green (61 Rust, 175 API30, JVM, dual ABI, cold APK compare) | Latest-head revalidation pending in #1136 |
| M1 | Scoped editor foundation; corrected workspace test passed API30 175/175 in #1124 | Full professional parity, accessibility and physical device UX |
| M2 | Partial; canonical import/save + offline media; cross-instance manifest/repository locks, symlink regression, `.ecprojx` typed round-trip | #1143 failed 2 XML JVM tests; KXml parser/serializer fix `81c10049` awaiting #1144. Effekseer/provider/relink matrix remains |
| M3 | Partial; real Rust CPU/session preview | Physical GPU/Surface/device-loss/media qualification |
| M4 | In progress; transform/keyframe/ShapePath editing; keyframe store cross-instance serialized (`542c1785`) with 120-update regression (`faf1d30c`) | #1128 superseded; full upstream effects/interpolation/roving/spatial parity |
| M5 | Partial; PNG/MP4/audio export foundations | Long-run recovery, device codec/audio/output validation |
| M6 | Partial; cache/recovery/memory safeguards | Physical performance, thermal, low-memory and accessibility matrix |
| M7 | Not accepted | Protected signing, signed artifacts, physical install/run and release gates |

### Latest CI checkpoint

- [CI #1124](https://github.com/sunmughan/After-Effects-Android/actions/runs/42394930220) **passed** exact source SHA `814dd66e`: Rust 61/61, JVM, dual ABI, unsigned APK/AAB, API30 instrumentation 175/175, and independent cold debug-APK byte comparison.
- The Compose motion-path overlay synchronization correction [814dd66e](https://github.com/sunmughan/After-Effects-Android/commit/814dd66e65989137dd80d554ebf9c38b158edbf5) is verified; the prior #1122 timeout is resolved.
- [CI #1125](https://github.com/sunmughan/After-Effects-Android/actions/runs/42399063894) on merge SHA `4ae22776` has a successful assemble/test job (61 Rust, 175 API30); its independent cold rebuild/compare remains in progress.
- CI #1128 (M4 concurrent keyframe regression) was cancelled by subsequent source commits; no result is claimed.
- [CI #1131](https://github.com/sunmughan/After-Effects-Android/actions/runs/42405449095) failed one M2 concurrency assertion (480 JVM tests, 1 failure). [CI #1137](https://github.com/sunmughan/After-Effects-Android/actions/runs/42410827252) failed test compilation due a malformed nested quote in the exception diagnostic (line 174); [e7f78b60](https://github.com/sunmughan/After-Effects-Android/commit/e7f78b6037e9e9907963149973a1c6c440c7162b) corrected it. [CI #1139](https://github.com/sunmughan/After-Effects-Android/actions/runs/42415039779) was cancelled by a subsequent code commit. [CI #1142](https://github.com/sunmughan/After-Effects-Android/actions/runs/42415141123) was cancelled by a subsequent code push. [CI #1143](https://github.com/sunmughan/After-Effects-Android/actions/runs/42415201194) is in progress on exact source `da3f6e5e` (symlink and `.ecprojx` regression coverage); no pass is claimed.

## Format and release limitations

- Adobe After Effects `.aep` is proprietary; no licensed/available parser is integrated, so native `.aep` import is **not** claimed.
- Effekseer `.efkproj` conversion/parser compatibility is not complete.
- Unsigned APK/AAB CI output is not a signed production release. Signing secrets/keystore must remain owner-controlled; physical ARM64 install/run evidence is still required.
- PR #1 is already merged; do not conflate merge with M0–M7 release acceptance. Keep release status NOT COMPLETE.

## Contributing / roadmap

Work is tracked on `feat/android-app-foundation` in [PR #1](https://github.com/sunmughan/After-Effects-Android/pull/1). Implement the next unfinished acceptance criterion, add a regression, run the applicable checks, and update [PHASES.md](PHASES.md) in place. Do not append repeated run transcripts or mark a phase green from a queued/cancelled workflow.
