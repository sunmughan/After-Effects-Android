# EffectCraft for Android

An offline-first Android editor project targeting phones, tablets, and foldables.

> **Current status: M0 and scoped M1 accepted** (CI #289: 18/18 API 30 emulator instrumentation passed; CI #286 also records full dual-ABI/Rust/JVM/Wrapper evidence). M2 is in progress: canonical JSON/XML import, format guardrails, SHA-256 content-addressed offline media storage and native footage relinking are implemented. Media payload paths preserve the source extension for decoder dispatch; four asset-store JVM tests await exact-head CI. Effekseer `.efkproj` and Adobe `.aep` remain explicitly unsupported.

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

- **M0 foundation: COMPLETE** at tested commit `bd274c7879cdd596d767303ace8791012543f199`; CI #286 passed Wrapper/dual ABI/JNI, 13 Rust tests, JVM tests, 18/18 emulator instrumentation and dependency/license inventory.
- **M1 editor foundation: COMPLETE** at the same tested commit; all 18 emulator cases passed (offline project save/open/upsert, layers, properties, Undo/Redo, timeline, adaptive workspace and native parser invocation).
- **M2: IN PROGRESS.** EffectCraft `.ecproj` JSON and `.ecprojx` XML import/export, native schema validation and format guardrails are present. `OfflineAssetStore` (`020413c8`, `cd9a69f0`) adds bounded SHA-256 content-addressed app-private media with dedupe and atomic metadata; `7157542e` integrates SAF footage relinking to the immutable offline payload. Four asset-store JVM tests are committed but awaiting latest-tip CI. Project/asset manifest, media library UI, and broader migration/relink acceptance remain.- Supported canonical interchange: EffectCraft `.ecproj` JSON and `.ecprojx` XML. Effekseer `.efkproj` has no integrated parser/converter; Adobe `.aep` is proprietary and no licensed compatible parser is integrated. Android `.efx-draft` files use Open offline project, not canonical import.
- M3 actual upstream CPU composition preview through JNI is partial (shared session lifecycle, GPU fallback and device validation remain); M4 canonical transform editing plus scalar keyframe primitives are partial (keyframes not yet persisted/wired); M5 render/export queue, M6 reliability/device matrix and M7 release are not complete.

M0/M1 acceptance evidence: [CI #286](https://github.com/sunmughan/After-Effects-Android/actions/runs/41588154232). See [PHASES.md](PHASES.md) for per-phase acceptance, test evidence, commits, blockers and release gates; [docs/ANDROID_IMPLEMENTATION.md](docs/ANDROID_IMPLEMENTATION.md) for product milestones; and [docs/NATIVE_ENGINE_INTEGRATION.md](docs/NATIVE_ENGINE_INTEGRATION.md) for JNI/render integration details.
