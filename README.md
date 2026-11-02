# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

 > **Current status: M0 complete (owner accepted); M1 ~80% implementation and priority; M2–M4 paused until M1 acceptance.** CI #178 (commit `11409871`) failed three JVM unit tests: two canonical metadata tests used Android's unmocked `org.json` SDK stubs, and one delete-layer test asserted against the wrong list. Fixes committed in `b151a301` (real JVM JSON dependency) and `c30385e1` (correct one-layer invariant). Added exact opacity/visibility Undo+Redo instrumentation in `cceea410`; latest-head CI verification is pending. Expanded M1 interaction and 1080dp adaptive workspace smoke tests are present; the `.efx-draft` preview remains a placeholder. Existing canonical `.ecproj` work is preserved but not advancing while M1 is gated. Adobe `.aep` is not supported. Acceptance/evidence is tracked in [PHASES.md](PHASES.md).

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
