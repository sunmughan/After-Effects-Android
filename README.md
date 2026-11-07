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

## Current engineering status (2026-11-07)

**Release is NOT complete.** The authoritative per-phase acceptance criteria, exact test evidence, commit links and blockers are maintained in [PHASES.md](PHASES.md).

| Phase | Status | Remaining gate |
|---|---|---|
| M0 | Accepted at historical exact SHA | Re-run current-head dual ABI/JVM/API30/cold reproducibility |
| M1 | Scoped editor foundation accepted | Full professional parity, accessibility and physical device UX |
| M2 | Partial; canonical import/save + offline media foundations | Broader Effekseer compatibility, provider/relink matrix |
| M3 | Partial; real Rust CPU/session preview | Physical GPU/Surface/device-loss/media qualification |
| M4 | In progress; transform/keyframe/ShapePath editing | Full upstream effects/interpolation/roving/spatial parity |
| M5 | Partial; PNG/MP4/audio export foundations | Long-run recovery, device codec/audio/output validation |
| M6 | Partial; cache/recovery/memory safeguards | Physical performance, thermal, low-memory and accessibility matrix |
| M7 | Not accepted | Protected signing, signed artifacts, physical install/run and release gates |

### Latest CI checkpoint

- [CI #1122](https://github.com/sunmughan/After-Effects-Android/actions/runs/42388159501) failed only the API30 workspace motion-path overlay readiness test after native/JVM/release checks passed. The failure was a Compose timeout at test line 1007 after the native render request fired.
- Test correction: [814dd66e](https://github.com/sunmughan/After-Effects-Android/commit/814dd66e65989137dd80d554ebf9c38b158edbf5) now waits separately for the rendered-frame node and overlay node, avoiding nested `waitForIdle` in a polling predicate while retaining both assertions.
- Exact-head [CI #1124](https://github.com/sunmughan/After-Effects-Android/actions/runs/42394930220) is pending on that test commit. No pass is claimed until JVM, API30, native and independent cold APK/native comparisons complete.

## Format and release limitations

- Adobe After Effects `.aep` is proprietary; no licensed/available parser is integrated, so native `.aep` import is **not** claimed.
- Effekseer `.efkproj` conversion/parser compatibility is not complete.
- Unsigned APK/AAB CI output is not a signed production release. Signing secrets/keystore must remain owner-controlled; physical ARM64 install/run evidence is still required.
- Keep PR #1 Draft until M0–M7 gates in [PHASES.md](PHASES.md) are accepted.

## Contributing / roadmap

Work is tracked on `feat/android-app-foundation` in [PR #1](https://github.com/sunmughan/After-Effects-Android/pull/1). Implement the next unfinished acceptance criterion, add a regression, run the applicable checks, and update [PHASES.md](PHASES.md) in place. Do not append repeated run transcripts or mark a phase green from a queued/cancelled workflow.
