# Native EffectCraft engine integration

Last updated: 2026-11-02

## Pinned source

- Upstream: [storytold/effectcraft](https://github.com/storytold/effectcraft)
- Revision: `e71dd629c56c78a82959ee50c271da9209c55b80` (immutable pin in `native/Cargo.toml`)
- Android bridge crate: `native/`, built as `libeffectcraft_android.so`
- Rust toolchain: `1.99.1`; Android target `aarch64-linux-android`
- JNI dependency: `jni 0.21.1`

## What the bridge does today

`NativeProjectBridge.inspectEcprojNative(sourceJson)` passes JSON into upstream `effectcraft_project::Project` deserialization. On success it returns schema, item count, and composition IDs/dimensions/layer counts; invalid JSON/schema returns a structured error. Rust host tests currently verify rejection of an empty/non-project document.

**This is project validation/metadata inspection only.** It is not an engine session, frame renderer, media decoder, or exporter. The Compose composition viewer remains a decorative placeholder. The Android app's `.efx-draft` is a distinct bootstrap format and must never be mislabeled as `.ecproj`.

## CI build

The workflow currently:
1. installs JDK 17, Gradle 9.5.0, Rust 1.99.1, Android SDK and NDK 28.2;
2. runs `cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release` from `native/`;
3. runs Rust host tests and dependency/license inventory;
4. assembles the debug APK and Kotlin unit tests;
5. launches an API 35 x86_64 emulator for Compose instrumentation;
6. uploads APK and audit reports.

The emulator test ABI is x86_64, while the Rust shared library is currently packaged for ARM64. CI therefore verifies cross-compilation/package assembly but **does not prove ARM64 JNI loading or a physical-device renderer**.

## Engine integration gates

Do not add the upstream desktop `egui/eframe` app to Android. Audit and isolate the smallest compatible project/session/engine/render/media/export crate closure, platform features, shader backend and native dependencies first.

The integration sequence is:
- [ ] Confirm Android-compatible upstream session APIs and project mutation APIs at the pinned revision.
- [ ] Add JNI create/destroy session, open/save project, mutation and error APIs with explicit ownership/threading.
- [ ] Add valid upstream project fixtures and Kotlin/Rust serialization/round-trip tests.
- [ ] Add SAF open/save with persisted URI grants and failure-safe writes.
- [ ] Build renderer with Android feature gates and verified Vulkan/device fallback.
- [ ] Deliver actual frame pixels to a Surface/Texture path; test lifecycle, resize, GPU loss and memory.
- [ ] Validate media decoding and export separately; no assumption that desktop codec dependencies work on Android.
- [ ] Install and run on physical ARM64 phone/tablet, record device/OS/ABI/GPU/build SHA and frame evidence.

## Adobe After Effects files

Adobe `.aep` is a proprietary project format. This repository does not have an Adobe SDK/license or a proven parser/converter. Native `.aep` import must remain an explicit research/compatibility item; the target currently is EffectCraft's canonical `.ecproj` and formats actually supported by the upstream model. Never claim `.aep` round-trip or rendering without tests and legal/technical confirmation.
