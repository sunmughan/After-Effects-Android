# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

 > **Current status: M0 complete (owner accepted); M1 remains IN PROGRESS; M2–M4 paused.** CI #212 failed at Kotlin compile due to two unresolved androidx.foundation.BorderStroke references in EditorScreen.kt; corrected by importing androidx.compose.foundation.BorderStroke in commit fb336098. Current-head CI #213 is queued; no latest-head test pass is claimed. M1 instrumentation has 14 cases (including playback progression, visibility/opacity Undo/Redo, reorder/delete, composition FPS Undo, offline save/reload and wide-window layout), but acceptance awaits current-head execution. See [PHASES.md](PHASES.md).

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
