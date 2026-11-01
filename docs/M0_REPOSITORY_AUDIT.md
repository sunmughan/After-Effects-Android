# M0 repository, dependency and build audit

Audit date: 2026-11-01
Audited branch: `feat/android-app-foundation`
Upstream source pin: `storytold/effectcraft@e71dd629c56c78a82959ee50c271da9209c55b80`

## Repository baseline

- Android application is a Kotlin/Jetpack Compose project; it is not a WebView wrapper.
- Android module: `app/`; JNI crate: `native/`; Gradle settings are centralized in `settings.gradle.kts`.
- The Android manifest does not request `INTERNET`; the bootstrap draft store writes to app-private storage.
- No root `Cargo.toml`, Rust workspace, or Gradle version catalog exists in this Android repository. Rust is isolated in `native/Cargo.toml`.
- No Gradle Wrapper JAR is currently committed. CI installs a pinned Gradle distribution directly. This is reproducible in CI but less convenient for local contributors; wrapper generation remains a follow-up.
- No root project license has been selected for the new Android-specific source. Do not infer one from the upstream dependency.

## Toolchain and dependency inventory

| Area | Current pin / policy | Audit finding |
|---|---|---|
| JDK | Temurin 17 in CI | Explicit |
| Gradle | 9.5.0 in CI | Explicit; no wrapper checked in |
| Android Gradle Plugin | 9.3.1 | Explicit |
| Kotlin / Compose compiler plugin | 2.4.21 | Explicit |
| Android SDK | compile/target API 37; min API 26 | Explicit |
| Android NDK | 28.2.13676358 in CI | Explicit |
| Rust | 1.99.1 in `rust-toolchain.toml` and CI | Exact toolchain pin; upstream workspace declares Rust 1.95 minimum |
| Android Rust target | aarch64-linux-android | ARM64 only at present |
| Rust project model | EffectCraft git revision above | Immutable revision |
| JNI | jni 0.21.1 | Version constrained |
| JSON | serde_json 1.x | Major-version constrained |
| Compose libraries | BOM 2026.08.00 | BOM aligned |

## Upstream architecture and integration boundary

The pinned upstream repository is a Rust workspace (edition 2024, declared Rust 1.95) with separate crates for project data, engine, render/GPU, media, export, interchange, and UI. The Android bridge currently depends only on `effectcraft-project`. It deserializes canonical project JSON and reports composition metadata; it does **not** yet link the engine/session, GPU compositor, codecs, audio, or export pipeline.

The upstream workspace uses `wgpu` 30.0.1 and `egui/eframe` 0.37 in its desktop UI stack. Android must not pull the desktop UI application wholesale. Engine and media crate feature/dependency closure must be audited independently before adding them to the Android binary.

## Licensing and attribution

- The pinned EffectCraft source declares `MIT OR Apache-2.0` and the repository metadata reports Apache-2.0.
- Upstream `LICENSE-MIT`, `LICENSE-APACHE`, and `NOTICE` are retained under `licenses/effectcraft/` for downstream distribution compliance.
- Upstream NOTICE identifies third-party fonts and other bundled material with separate licenses and points to upstream `ATTRIBUTION.md`. Android currently consumes only the project-model crate; it does not bundle the upstream desktop app assets or fonts.
- The Android bridge's direct Rust crates include `jni` (MIT OR Apache-2.0) and `serde_json` (MIT OR Apache-2.0). A CI-generated Cargo metadata report now enumerates every resolved Rust package and its declared license expression; the runtime dependency tree accompanies it for review.
- CI also records Gradle's resolved `debugRuntimeClasspath` dependency tree. This lists Android runtime transitive versions but does not itself certify each Maven artifact's license; a complete third-party notice review remains a release gate.
- Before distributing APK/AABs, generate and review a complete dependency license inventory for the resolved Cargo and Gradle graphs, include all required notices, and confirm any media/codec dependencies and patent-sensitive formats with appropriate legal review.
- The upstream product name, logos, and brand assets are not granted by the software license; do not copy upstream branding without separate permission.

## CI baseline and discovered defect

The Android CI workflow provisions JDK, Gradle, a pinned Rust toolchain, Android SDK and NDK. The initial ARM64 build failed because `cargo-ndk` was invoked from the repository root; it now runs from `native/` and places the shared library under `app/src/main/jniLibs`. A full run at commit `d14528f5a4919cdaef9e9edc42186124dc3fd16b` passed the ARM64 native build, Rust tests, debug APK assembly, Kotlin unit tests, and artifact upload. Follow-up runs validate the exact Rust 1.99.1 pin and new dependency-inventory artifact on the current head.

A successful workflow must complete all of: ARM64 native build, Rust tests, debug APK assembly, Kotlin unit tests, and artifact upload. A green historical run for a prior SHA is not evidence that the current head passes.

## M0 exit criteria

- [x] Repository structure and Android/native boundaries inspected.
- [x] Toolchain and direct dependency pins inventoried.
- [x] Upstream immutable revision and crate boundaries inspected.
- [x] Upstream licensing/NOTICE files retained and redistribution risks documented.
- [x] CI provisioning issue isolated and corrected in workflow.
- [ ] Current branch head passes the complete Android CI workflow.
- [ ] Resolved transitive Cargo/Gradle license inventory generated and reviewed.
- [x] Exact Rust 1.99.1 toolchain pinned in source and CI.
- [ ] Current-head CI succeeds with dependency inventory reports uploaded.
- [ ] Review the generated Rust license expressions and Gradle runtime dependency tree before binary distribution.

M0 remains **in progress** until current-head CI and the generated dependency report review are complete. The passing build proves the hosted build/test path, not a physical phone/tablet smoke test. No claim of production readiness or device validation is made here.
