# Native engine integration

Last reviewed: 2026-11-01

## Pinned upstream source

The Android bridge currently pins the upstream EffectCraft repository to:

- Repository: https://github.com/storytold/effectcraft
- Commit: `e71dd629c56c78a82959ee50c271da9209c55b80`
- Imported crate: `effectcraft-project`
- License: retain both upstream MIT and Apache-2.0 license texts and attribution notices when vendoring or redistributing upstream code.

The pin is deliberate. Do not change it to a moving branch or tag without reviewing the upstream API, lockfile, dependency graph, and license/NOTICE changes.

## Current Rust bridge slice

`native/` is a standalone Rust `cdylib`, separate from the upstream desktop Cargo workspace. It depends on the pinned upstream `effectcraft-project` crate and exposes a small JNI function to Kotlin:

- Accepts canonical EffectCraft project JSON.
- Deserializes it using the upstream `Project` model (not a Kotlin reimplementation).
- Returns schema, item count, composition count, dimensions and layer counts as JSON.
- Returns a structured validation error for invalid project JSON.

Kotlin entry point: `NativeProjectBridge.inspectEcproj(json)`.

The JNI library is loaded lazily. The Compose shell remains launchable in development when the native library has not yet been built. The Android CI workflow builds `arm64-v8a` and places the shared library in `app/src/main/jniLibs` before assembling the APK.

This is the first project-format bridge, not a renderer. It does not yet decode media, render frames, edit projects through the upstream command registry, or export video.

## Why the desktop frontend is not embedded

The upstream architecture places the UI at L5 (`effectcraft-ui-egui`) and keeps the project, render and engine crates below it. The desktop app additionally wires eframe/winit, native file dialogs, CPAL audio, desktop menus and OS integrations. Android should not inherit that desktop shell.

The intended Android stack is:

```text
Kotlin / Jetpack Compose
  ├── Android lifecycle, SAF, MediaCodec, audio focus, input
  └── JNI boundary
        ├── effectcraft-project  (integrated first)
        ├── effectcraft-engine   (next: Session + command registry)
        ├── effectcraft-render   (CPU preview baseline)
        ├── effectcraft-gpu      (wgpu/Vulkan capability path)
        ├── effectcraft-media    (offline footage decode)
        └── effectcraft-export  (offline render queue)
```

## Integration order and gates

### N1 — Canonical project model (in progress)

- [x] Pin upstream source revision.
- [x] Add a Rust `cdylib` and JNI boundary.
- [x] Deserialize projects through upstream `effectcraft-project`.
- [x] Add an ARM64 Android native build to CI.
- [ ] Add valid upstream `.ecproj` fixtures and round-trip compatibility tests.
- [ ] Wire open/save through Android Storage Access Framework with persisted URI grants.

### N2 — Engine session

- [ ] Audit the exact `effectcraft-engine` and `effectcraft-host` native dependency closure.
- [ ] Expose a narrow C/JNI API for session creation, command dispatch, undo/redo and project snapshots.
- [ ] Keep all editor mutations in the upstream command registry; do not duplicate edit semantics in Kotlin.
- [ ] Add lifecycle-safe session ownership and cancellation.

### N3 — Real composition preview

- [ ] Render a single known composition frame through `effectcraft-render` on Android.
- [ ] Establish a CPU reference image and pixel-difference test.
- [ ] Add wgpu Vulkan rendering only after device capability and driver-loss testing.
- [ ] Stream bounded frame buffers to Android; avoid copying full-resolution frames unnecessarily.

### N4 — Media and export

- [ ] Validate upstream media and FilmCraft codec dependencies for Android ABIs.
- [ ] Add SAF-backed media resolution and offline cache management.
- [ ] Add Android MediaCodec acceleration as an optional backend, retaining software fallback.
- [ ] Add audio preview, render queue, foreground export service and cancellation.

## Build commands

Host bridge tests:

```bash
cargo test --manifest-path native/Cargo.toml
```

ARM64 Android library (requires Android NDK and cargo-ndk):

```bash
cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release --manifest-path native/Cargo.toml
```

Android APK and unit tests:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

## Acceptance boundary

A successful Rust bridge build proves that the pinned upstream project model can be compiled for ARM64 and called through JNI. It does not prove the full compositor, GPU path, media codecs, audio, or export pipeline works on Android. Those require separate build gates and physical-device tests on phones and tablets.
