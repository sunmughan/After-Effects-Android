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

## Current engineering status (2026-11-08 06:40 IST)

**Release is NOT complete.** PR #1 merged into `main` at `4ae22776` (2026-11-07); that merge is not product acceptance. Post-merge work is tracked in [draft follow-up PR #2](https://github.com/sunmughan/After-Effects-Android/pull/2) (clean/mergeable; not merged). Ongoing engineering continues on `feat/android-app-foundation`. [PHASES.md](PHASES.md) is the authoritative acceptance ledger with evidence, commit SHAs and blockers.

### Latest build failure and correction

- [CI #1151](https://github.com/sunmughan/After-Effects-Android/actions/runs/42427767850), SHA `e1c2a568`: build failed before compilation because `com.github.kobjects:kxml2:v2.5.0` is unpublished/not resolvable (no upstream 2.5.0 release/tag). This was an incorrect dependency coordinate, not an R8 regression.
- Replaced the invalid KObjects coordinate with the published Maven Central KXML 2.3.0 artifact and a deterministic Gradle task that strips `org/xmlpull/**` from the packaged parser JAR (Android supplies the API), avoiding R8 duplicate program classes: [build commit cf01caf5](https://github.com/sunmughan/After-Effects-Android/commit/cf01caf53568a13e951edb55fe0a1088230f917c). Removed unused JitPack repository [d9c88793](https://github.com/sunmughan/After-Effects-Android/commit/d9c88793ab29a590af78d3b071ec4c8de1ba7731).
- Push CI #1147 was cancelled when the follow-up PR check started; PR checks #1148 and #1149 were superseded by documentation-only head updates. [Latest PR check #1150](https://github.com/sunmughan/After-Effects-Android/actions/runs/42427711665) was running on code-equivalent head `443dedc7` at last inspection, but the current README synchronization will cause another PR workflow attempt. The Android-safe KXML correction is **not accepted until a fresh run passes JVM, release R8, API30 instrumentation and independent cold APK comparison**.

### Phase snapshot

| Phase | Status | Remaining acceptance |
|---|---|---|
| M0 | Historically accepted (CI #1011) | Latest-head dual ABI/JVM/API30/cold reproducibility |
| M1 | Scoped offline adaptive editor accepted (CI #1124: API30 175/175) | Professional interaction parity, accessibility, physical device/foldable UX |
| M2 | Canonical EffectCraft JSON/XML, SAF, offline media, integrity and concurrency foundations | Android-safe XML dependency CI; broader Effekseer compatibility and media relinking |
| M3 | Actual Rust CPU/session preview and Android frame presentation | GPU/Surface/device-loss/media parity and physical performance |
| M4 | Transform/keyframe/Bezier/ShapePath editing foundations | Full effect/interpolation/roving/spatial parity and golden edit/reopen/preview tests |
| M5 | PNG/MP4/audio export foundations | Device codec/audio sync, long-run cancellation/recovery and output playback validation |
| M6 | Recovery, offline integrity, cache/memory and Bitmap lifecycle safeguards | Physical performance/thermal/low-memory/accessibility/API/ABI matrix |
| M7 | Unsigned APK/AAB and signing safety checks | Owner-controlled signing, signed artifacts and physical install/run |

### Verified evidence

- [CI #1124](https://github.com/sunmughan/After-Effects-Android/actions/runs/42394930220) passed exact SHA `814dd66e`: Rust 61/61, JVM, ARM64+x86_64 JNI, API30 175/175, unsigned APK/AAB and independent cold debug-APK comparison.
- #1131 exposed one M2 concurrency assertion; #1137 exposed malformed test diagnostic interpolation; both were corrected. #1143/#1144 exposed XML JVM parser issues; parser feature fix `e4e5bb85` then exposed the independent release R8 dependency conflict in #1145. See [PHASES.md](PHASES.md) for exact artifacts/commits.
- Local Gradle/JDK/Android SDK execution is unavailable in this runtime; hosted Actions is the build/test gate. Emulator CI does not substitute for physical ARM64 GPU, thermal, low-memory or install/run evidence.

### Format and release limitations

- Adobe After Effects `.aep` is proprietary; no licensed/available parser is integrated and native support is **not** claimed.
- Effekseer `.efkproj` parser/converter compatibility remains incomplete.
- Unsigned CI outputs are not production-signed releases. Keystore/passwords remain owner-controlled and must never be committed/exposed.
- PR #1 is merged/closed; do not call it Draft/open or equate merge with M0–M7 acceptance.

Continue implementation phase-by-phase, fix real failures without weakening tests, and update [PHASES.md](PHASES.md) in place. **EffectCraft release remains NOT COMPLETE.**
