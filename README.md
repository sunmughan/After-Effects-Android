# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

 > **Current status: M0 INCOMPLETE (baseline CI passed; ARM64 device runtime, wrapper, and owner license/acceptance gates remain); M1 held; M2–M4 paused.** CI #216 exposed two Kotlin compile issues: missing `roundToLong` import and wrong `BorderStroke` package. Fixed in `262b4aba` and `f3444c97`; latest-head CI #218/#219 is pending, so no build or instrumentation pass is claimed. See [PHASES.md](PHASES.md).

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

The M0 repository/dependency audit is tracked in [docs/M0_REPOSITORY_AUDIT.md](docs/M0_REPOSITORY_AUDIT.md). M0 has not been accepted complete by the project owner. Its remaining closure gates are tracked in [PHASES.md](PHASES.md); do not advance M1 until M0 is closed.

See [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md) for product milestones and [docs/NATIVE_ENGINE_INTEGRATION.md](docs/NATIVE_ENGINE_INTEGRATION.md) for the pinned Rust bridge, build commands, and engine integration gates.
