# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

 > **Current status: M0/M1/M2 in progress; M3 CPU preview integration started.** Latest-head CI is pending (run #138) after adding M1 settings/selection/save and canonical project/media/render changes; prior CI has passed native ARM64 build, Rust tests, APK and unit tests, but emulator cold-boot/ADB failures have prevented a clean current-head instrumentation result. The main offline `.efx-draft` viewer remains a placeholder. Imported canonical `.ecproj` projects now support upstream-model text add/rename, visibility, reorder/delete, media inventory and SAF footage relinking into app-private offline storage; their editor sheet invokes the pinned upstream CPU compositor and decodes copied media with `MediaPool`, displays actual rendered frames with frame scrubbing, and edits canonical Transform/Opacity (0–100) plus Position X/Y through the native model. The native preview now uses upstream `MediaPool` to decode app-private relinked footage; codec/performance behavior and ARM64 JNI/device rendering remain unverified. Adobe `.aep` is not supported. Acceptance/evidence is tracked in [PHASES.md](PHASES.md).

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
