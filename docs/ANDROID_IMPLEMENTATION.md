# Android implementation status

Last updated: 2026-11-02

## What exists in this bootstrap

- A native Android application module using Kotlin and Jetpack Compose.
- Adaptive layout breakpoint: narrow layouts use a stacked phone workspace; wider layouts place the inspector beside the composition viewer.
- A starter 1080 × 1920 composition with selectable layer rows.
- Working layer add/select/remove/reorder interactions, visibility toggle, and editable opacity property.
- Timeline scrubbing and a lightweight transport/playhead simulation.
- Clip trim and split on the draft timeline: each layer holds clips
  (`ClipDocument`: half-open timeline `[start, end)` plus source
  in/out points, timeline length always equal to the source span).
  Trimming a timeline end shifts the matching source point; splitting
  at the playhead divides one clip into two preserving coverage
  exactly. Clips serialize into `.efx-draft` documents and the
  recovery journal, and trim/split go through the draft undo stack.
  A layer with no clips covers the full composition (legacy
  documents). A frame no visible layer covers is a gap: exports
  render it as the background/empty frame, never a stale frame.
- Local draft persistence in app-private storage with temp-file replacement.
- No network permission and no cloud dependency.

## Important limitation

This repository was empty when implementation began. The current preview is a UI placeholder; there is no renderer behind it yet. The .efx-draft JSON document is a bootstrap-only format and is **not** the upstream EffectCraft .ecproj format. Do not rename draft files to .ecproj or assume cross-platform compatibility.

The upstream [EffectCraft repository](https://github.com/storytold/effectcraft) is a separate Rust workspace. Its engine, GPU compositor, media codecs, licensing notices, native dependencies, and platform-specific feature gates still need to be integrated and validated for Android. This app does not currently compile or link that engine.

## Build environment

- JDK 17
- Gradle 9.5.0
- Android SDK Platform 37
- Android Gradle Plugin 9.3.1
- Kotlin Gradle Plugin and Compose compiler plugin 2.4.21
- Stable Compose BOM 2026.08.00
- Minimum Android API 26; target API 37

This bootstrap currently uses the explicit Kotlin Gradle plugin while AGP built-in Kotlin is disabled. Both are pinned to avoid drifting versions; migration to AGP built-in Kotlin should be done as a separate tested change.

Build from a machine with Gradle 9.5.0 installed:

~~~bash
gradle --version
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
~~~

Android Studio can also open this Gradle project. The repository bootstrap does not yet include the binary Gradle Wrapper JAR; generate/commit the wrapper after the initial toolchain build so developers can use ./gradlew reproducibly.

## Architecture direction

Keep Android platform services at the edge and put project semantics and rendering behind stable Rust interfaces.

~~~text
Android Activity / Compose UI
          |
          +-- Android lifecycle, SAF, input, audio and MediaCodec adapters
          |
          +-- JNI boundary (planned)
                    |
                    +-- EffectCraft Rust project / engine crates
                    +-- wgpu compositor (Vulkan first; tested fallback path)
                    +-- shared project format and export pipeline
~~~

The current Kotlin draft store is intentionally isolated. Once the upstream project model is integrated, replace it with a repository adapter that reads/writes the canonical project format and keeps safe atomic saves/recovery.

## Milestones

### M1 — Native Android editor foundation (in progress)
- [x] Empty repository initialized.
- [x] Native app module and offline manifest.
- [x] Adaptive phone/tablet editor shell.
- [x] Layer add/select/remove/reorder, visibility/opacity controls, timeline and draft-save interactions.
- [x] Hosted Android CI builds the ARM64 JNI bridge, runs Rust and Kotlin unit tests, and generates dependency/license inventories (latest M1 change awaits CI).
- [x] Android emulator instrumentation covers launch/offline workspace, New composition, layer addition, and draft persistence round-trip.
- [x] Emulator smoke-test execution is wired into the pull-request CI workflow; latest current-head verification remains pending.

### M2 — Shared Rust project and engine integration
- [x] Pin upstream EffectCraft commit and audit project/engine crate boundaries.
- [x] Add a standalone Rust JNI library using the upstream `effectcraft-project` model.
- [x] Add an ARM64 `cargo-ndk` build and Rust bridge tests to Android CI.
- [x] Add Kotlin lazy-loading boundary for the native library.
- [ ] Add valid upstream `.ecproj` fixtures and round-trip compatibility tests.
- [ ] Wire SAF-backed project open/save and persisted URI grants.
- [ ] Add engine session commands, undo/redo and composition frame rendering.
- [ ] Prove a rendered frame on a physical ARM64 phone and tablet.

### M3 — Adaptive real editor workflows
- [ ] Replace placeholder viewer with engine-backed composition rendering.
- [ ] Implement transform, keyframe, mask, text, shape and effect editors.
- [ ] Add undo/redo, robust selection, multi-touch gestures and stylus pressure.
- [ ] Add SAF import/export, persisted URI grants and media relinking.
- [ ] Migrate draft documents to the canonical versioned project format.

### M4 — Offline media and rendering
- [ ] Integrate audio preview and timeline synchronization.
- [ ] Validate software decode/encode, then add Android MediaCodec paths.
- [ ] Add render queue, progress, cancellation and foreground export service.
- [ ] Add proxy/cache controls, thermal and memory budgeting, GPU-loss recovery.

### M5 — Production hardening
- [ ] Test low/mid/high-end phones, tablets and foldables.
- [ ] Validate save recovery, process death, rotation and background/foreground lifecycle.
- [ ] Benchmark 720p, 1080p and 4K workflows; define device capability tiers.
- [ ] Package signed APK/AAB and publish reproducible build instructions.

## Offline acceptance checklist

A production release must be able to launch, create/open/save projects, edit, preview, render and export without an account, network access, cloud service, or remote license check. User media must remain accessible through app-managed storage or persisted SAF permissions. This bootstrap currently satisfies offline launch and local draft persistence only.


## Native bridge progress (2026-11-01)

The separate [native integration note](NATIVE_ENGINE_INTEGRATION.md) records the exact upstream pin and staged build gates. The first JNI slice validates project JSON through the upstream project model and reports composition metadata. The Android workflow is being expanded to compile and package the ARM64 shared library; a successful CI result is still required before calling this slice build-verified.
