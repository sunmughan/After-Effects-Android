# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

> **Current status: bootstrap in progress.** This repository started empty. The first milestone establishes the Android application shell, adaptive editing workspace, and local draft persistence. It is not yet the full EffectCraft rendering engine, and its starter draft format is not yet compatible with upstream `.ecproj` projects.

## Product direction

- Native Android application; no WebView dependency.
- One adaptive workspace for phones, tablets, and foldables.
- Offline-first project creation, editing-state persistence, and recovery.
- Shared project and rendering core integration with [EffectCraft](https://github.com/storytold/effectcraft) as a dedicated follow-up milestone.
- Vulkan/GPU rendering and hardware media pipelines only after device-level compatibility work.

## Build

Open the project in Android Studio with a compatible JDK and Android SDK, or use the Gradle wrapper:

```bash
./gradlew :app:assembleDebug
```

The minimum Android version and required SDK packages are documented in [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md).

## Offline by design

The Android manifest intentionally does not request the `INTERNET` permission. The bootstrap application stores draft projects in app-private storage. Import/export through Android's Storage Access Framework and upstream project-format compatibility are tracked as implementation milestones.

## Development status

See [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md) for architecture decisions, milestone boundaries, and the known limitations of the current bootstrap.
