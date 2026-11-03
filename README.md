# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

> **Current status: M0 and M1 accepted on CI #286 (18/18 emulator instrumentation passed); M2 is in progress.** Exact tested commit: `bd274c7879cdd596d767303ace8791012543f199`. M2 import-format guard is commit `53b6bc6e844423703216f99eb8e3d0318f05d106`; latest HEAD `55932dbcb17381026f131cf7304bb64ab90a13a6` has CI #289 in progress. EffectCraft `.ecproj` JSON and `.ecprojx` XML are supported; Effekseer `.efkproj` and Adobe `.aep` remain explicitly unsupported.

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

M0 foundation and M1 editor acceptance are complete at the tested CI #286 commit. M2 canonical import guard work is in progress; its exact-head CI and new JVM tests remain pending.

M0/M1 evidence: [CI #286](https://github.com/sunmughan/After-Effects-Android/actions/runs/41588154232) (dual ABI, 13 Rust tests, JVM suite, 18/18 emulator tests and audit artifact). M2 import-format guard commit `53b6bc6e` is awaiting CI #287. M0/M1 evidence: [CI #286](https://github.com/sunmughan/After-Effects-Android/actions/runs/41588154232) (dual ABI, 13 Rust tests, JVM suite, 18/18 emulator tests and audit artifact). M2 import-format guard commit `53b6bc6e` awaits current-head CI #289. See [PHASES.md](PHASES.md) for per-phase acceptance, test evidence, commits and blockers; [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md) for product milestones; and [docs/NATIVE_ENGINE_INTEGRATION.md](docs/NATIVE_ENGINE_INTEGRATION.md) for the pinned Rust bridge and engine integration gates.
