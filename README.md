# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

 > **Current status: M0 INCOMPLETE; M1 editor acceptance pending.** Gradle Wrapper 9.5.0 is committed (`eb2c6498`) with distribution checksum. CI #236's instrumentation defects were corrected in `9743f952`; CI #237 was superseded during x86_64 compilation to add Cargo caching and a 90-minute cold-build budget. Latest editor fix `fb2c9bc5` keeps layer selection valid after deleting the selected layer and adds a reducer regression test. CI #240 is pending on this head; no current-head pass is claimed. See [PHASES.md](PHASES.md).

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
