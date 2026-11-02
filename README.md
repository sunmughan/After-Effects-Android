# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

 > **Current status: M0/M1 in progress; not a complete editor yet.** Latest-head CI is pending after adding composition settings (dimensions/fps/duration with undo), timeline selection, offline Save/reload, accessible property/playhead controls, and instrumented checks for layer reorder/delete, visibility, opacity, timeline scrubbing/transport and composition settings (each interaction test resets to a fresh composition); earlier CI passed native ARM64 bridge build, Rust tests, APK assembly and unit tests, but emulator cold-boot/ADB failures have prevented a clean current-head instrumentation result. The Android UI still has a placeholder composition viewer; canonical `.ecproj` SAF cache/validation/export is not full project editing/rendering. Layer visibility, names, opacity, reorder/delete, composition settings, timeline playhead and transport are stateful in local drafts; accessibility semantics and instrumentation assertions are being validated by run #97; canonical `.ecproj` layer rename and Text-layer creation are now implemented in Rust model APIs with unit coverage and JNI wrappers (UI add action/device JNI verification pending). SAF `.ecproj` import/cache/export is integrated with a native canonical-project sheet that lists compositions/layers and supports layer renaming and adding native Text layers through the pinned Rust model; ARM64 JNI runtime/device verification is pending. Real rendering remains a future phase. Roadmap acceptance and phase evidence are tracked in [PHASES.md](PHASES.md).

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

The M0 repository/dependency audit is tracked in [docs/M0_REPOSITORY_AUDIT.md](docs/M0_REPOSITORY_AUDIT.md). M0 is not considered complete until the current-head Android CI, resolved dependency-license inventory, and exact Rust toolchain pin are verified.

See [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md) for product milestones and [docs/NATIVE_ENGINE_INTEGRATION.md](docs/NATIVE_ENGINE_INTEGRATION.md) for the pinned Rust bridge, build commands, and engine integration gates.
