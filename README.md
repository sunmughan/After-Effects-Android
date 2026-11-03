# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

> **Current status: M0 INCOMPLETE; M1 acceptance pending.** CI #282 passed native ARM64/x86_64 builds, JNI packaging, Rust tests, dependency/license audit, APK assembly and JVM tests, but failed five Android instrumentation cases. Commit `9378f0e2855838ad9c66e2ce668f047c32e2471b` makes the phone inspector scrollable, gives Move/Delete controls explicit accessibility semantics, and corrects two test assumptions (four visible timeline rows and a longer composition-viewer heading). The temporary failing diagnostic was removed only after its CI failure message was read. Fresh current-head CI is pending; no green run is claimed. EffectCraft `.ecproj` JSON and `.ecprojx` XML are implemented; Effekseer `.efkproj` and Adobe `.aep` remain unsupported. See [PHASES.md](PHASES.md).

## Product direction

- Native Android application; no WebView dependency.
- One adaptive workspace for phones, tablets, and foldables.
- Offline-first project creation, editing-state persistence, and recovery.
- Shared project and rendering core integration with [EffectCraft](https://github.com/storytold/effectcraft) as a dedicated follow-up milestone.
- Vulkan/GPU rendering and hardware media pipelines only after device-level compatibility work.

## Build

Open the project in Android Studio with JDK 17 and Android SDK Platform 37, or use the committed pinned Gradle Wrapper:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The official Gradle Wrapper (9.5.0 with distribution SHA-256) is committed: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle-wrapper.properties`.

The minimum Android version and required SDK packages are documented in [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md).

## Offline by design

The Android manifest intentionally does not request the `INTERNET` permission. The bootstrap application stores draft projects in app-private storage. The ARM64 JNI bridge is built in CI; Android Storage Access Framework import/export and complete project editing/rendering remain implementation milestones.

## Development status

The M0 repository/dependency audit is tracked in [docs/M0_REPOSITORY_AUDIT.md](docs/M0_REPOSITORY_AUDIT.md). M0 is not complete yet. Its remaining closure gates are tracked in [PHASES.md](PHASES.md); M1 remains held until the exact current HEAD passes them.

See [PHASES.md](PHASES.md) for per-phase acceptance, test evidence, commits and blockers; [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md) for product milestones; and [docs/NATIVE_ENGINE_INTEGRATION.md](docs/NATIVE_ENGINE_INTEGRATION.md) for the pinned Rust bridge and engine integration gates.
