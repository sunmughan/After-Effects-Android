# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

 > **Current status: M0 complete (owner accepted); M1 remains IN PROGRESS; M2–M4 paused.** M1 code now includes a responsive two-row phone header and status semantics (`9ec1a3f5`), corrected status/rename instrumentation (`3cfcfbd`), and isolated wide-window test activity (`14e6d9b`, `4e91c149`, `1acb7756`). Run #204 passed Rust, ARM64 cross-build, APK assembly and JVM unit tests but failed 7 of 14 Android instrumentation tests; these were actual header/status visibility and test-harness issues, now fixed in code. Fresh current-head CI after `25e1521a` and tracker commit is pending; M1 is not accepted. The draft preview is still a placeholder, not a real renderer. Adobe `.aep` is not supported. Acceptance/evidence is tracked in [PHASES.md](PHASES.md).

## Product direction

- Native Android application; no WebView dependency.
- One adaptive workspace for phones, tablets, and foldables.
- Offline-first project creation, editing-state persistence, and recovery.
- Shared project and rendering core integration with [EffectCraft](https://github.com/storytold/effectcraft) as a dedicated follow-up milestone.
- Vulkan/GPU rendering and hardware media pipelines only after device-level compatibility work.

## Build

Open the project in Android Studio with JDK 17 and Android SDK Platform 37, or install Gradle 9.5.0 and run:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

A Gradle Wrapper JAR has not been committed in this bootstrap; the CI workflow provisions Gradle 9.5.0 directly.

The minimum Android version and required SDK packages are documented in [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md).

## Offline by design

The Android manifest intentionally does not request the `INTERNET` permission. The bootstrap application stores draft projects in app-private storage. The ARM64 JNI bridge is built in CI; Android Storage Access Framework import/export and complete project editing/rendering remain implementation milestones.

## Development status

The M0 repository/dependency audit is tracked in [docs/M0_REPOSITORY_AUDIT.md](docs/M0_REPOSITORY_AUDIT.md). M0 is accepted complete by the project owner. Remaining reproducibility/device/release hardening is tracked separately; M1 is the active delivery focus.

See [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md) for product milestones and [docs/NATIVE_ENGINE_INTEGRATION.md](docs/NATIVE_ENGINE_INTEGRATION.md) for the pinned Rust bridge, build commands, and engine integration gates.
