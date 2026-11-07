# M7 — Reproducible release build instructions

> Scope guard: EffectCraft for Android is built for the owner personally
> (closed/personal). This document is a build and evidence procedure, not a
> public release, not a Play Store submission, and not a license grant. No
> open license is added or implied by anything in this workstream, the
> repository visibility is unchanged, and nothing here merges any PR or
> publishes anything anywhere. A "release artifact" below means a signed
> APK/AAB produced by the release workflow for the owner's own device
> testing, nothing more, until the owner separately orders otherwise.

## 1. What a release build is in this project

- Application ID: `com.codeair.effectcraft` (owner-decided package name,
  coordinator update 2026-11-04). The pre-rename id carried by the original `ec-m7` workstream branch
  is gone: the
  rename (`ec-pkg`) is integrated on the feature branch and this M7
  integration ports every M7 file to `com.codeair.effectcraft`, so a
  release built from the integrated branch carries the decided id.
  Release notes still quote the id actually built, verified from the
  artifact, never assumed.
- Version at the time of writing: `versionName 0.1.0`, `versionCode 1`
  (same file). Version scheme: `versionCode` increments by 1 on every
  release, `versionName` follows semver. Do not bump these in a build
  step; a version change is a separate, owner-approved commit, and the
  release notes must quote the values actually built.
- Build variant: `release` — R8 minification on, resource shrinking on
  (`app/build.gradle.kts` `buildTypes.release`), ProGuard rules in
  `app/proguard-rules.pro` (JNI keeps only; see that file's comments).
  Debug builds are unchanged and are not release artifacts.
- Native libraries: both `arm64-v8a` (physical phones) and `x86_64`
  (emulator) are built in release mode from `native/` and packaged into
  one universal APK and one AAB. There is no per-ABI split in this
  pipeline and no `armeabi-v7a` or 32-bit `x86` library.
- Offline by design: the editor works with no account and no network.
  The manifest requests `INTERNET` only for FCM token/message
  transport, and `FirebaseInit` no-ops safely when no Firebase config
  exists, so a build without `google-services.json` runs fully offline.
  A release build does not gain any other network access.
- Release without secrets (CI check): `android-ci.yml` assembles the
  release APK and AAB with no signing secrets and verifies the APK
  carries no signature files — a missing secret degrades to unsigned
  (Gradle logs it loudly) instead of failing CI or silently signing
  with the debug key. Those unsigned outputs are build-shape evidence
  only, are not uploaded, and are never release artifacts. The signed
  release path is §4 below, and the release workflow additionally
  rejects any APK whose signer is the Android debug certificate.
- Firebase: the `google-services` plugin (4.4.4) applies only when
  `app/google-services.json` exists, so unregistered builds are
  unaffected; registration status, fingerprints, and console steps are
  in `docs/firebase-registration.md`.
- Fragment pin: `androidx.fragment:fragment:1.9.1` is an explicit
  dependency because `play-services-basement` (transitive via
  firebase-messaging) pulls fragment 1.1.0 and nothing else requests a
  newer one; the Activity Result API needs >= 1.3.0 and
  `:app:lintVitalRelease` fails release builds below it. Do not remove
  the pin without re-running the unsigned release assemble in CI.

## 2. Exact toolchain (must match, or the build is not this release)

These pins are taken from the repository itself, not from memory. They
are the same pins as `android-ci.yml`; any drift is a different build.

| Tool | Pin | Where it is pinned |
|---|---|---|
| JDK | Temurin 17 | `.github/workflows/android-release.yml`, `android-ci.yml` |
| Gradle | 9.5.0 (committed wrapper; `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`) | wrapper properties, both workflows |
| Android Gradle Plugin | 9.3.1 | root `build.gradle.kts` |
| Kotlin / Compose compiler plugin | 2.4.21 | root `build.gradle.kts` |
| Android SDK | compileSdk / targetSdk 37, minSdk 26 | `app/build.gradle.kts` |
| Android NDK | 28.2.13676358 | both workflows |
| Android cmdline-tools (CI) | 12266719 | both workflows |
| Rust | 1.99.1, targets `aarch64-linux-android` + `x86_64-linux-android` | `rust-toolchain.toml`, both workflows |
| cargo-ndk | installed with `--locked --force` in CI | both workflows |

Local builds additionally need an Android SDK with Platform 37 and the
NDK above installed; this sandbox has neither, which is why local
verification in this workstream is limited to reading the config and
validating workflow YAML (see §5). CI after integration is the build
verification gate — no local build is claimed here.

## 3. Signing secrets (owner-supplied, never committed)

The release job needs exactly these four GitHub Actions repository
secrets (Settings > Secrets and variables > Actions, on
`sunmughan/After-Effects-Android`):

| Secret | Contents |
|---|---|
| `EFFECTCRAFT_RELEASE_KEYSTORE_BASE64` | Base64 encoding of the release keystore file |
| `EFFECTCRAFT_RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `EFFECTCRAFT_RELEASE_KEY_ALIAS` | Key alias inside the keystore |
| `EFFECTCRAFT_RELEASE_KEY_PASSWORD` | Key password |

Rules that are enforced by the workflow and by this document alike:

- If any one of the four is absent or empty, the job fails in its
  "Check release signing secrets" step, before Gradle runs, naming the
  missing secret(s). It never falls back to a debug key and never
  produces an unsigned APK/AAB labelled as a release.
- The keystore is decoded only to `$RUNNER_TEMP` on the ephemeral
  runner (mode `0600`), never into the workspace, never into an
  uploaded artifact, and deleted by an always-run cleanup step.
- No secret value is printed, logged, committed, or pasted into any
  document, issue, or chat message. Anyone who needs to set the secrets
  does so in the GitHub secrets UI, never by sending a value to an agent.
- There is no release signing key in this environment and none was
  created for this workstream. Inventing a placeholder keystore to
  "make the job pass" is forbidden: a build signed with a throwaway key
  could never be updated on a device by a later build signed with the
  real key, and its signature would prove nothing.
- Key custody is the owner's: whoever holds the keystore + passwords
  controls future updates to any installed build. Losing them means a
  future build cannot update the installed app in place (Android
  requires the same signing key for updates); it would have to be
  uninstalled and reinstalled, losing app-private data.

## 4. Producing a release artifact (CI, the only supported route)

1. Confirm current-head `android-ci.yml` is green on the branch being
   released, and that the M7 gate checklist
   (`docs/M7_RELEASE_GATE_CHECKLIST.md`) has been reviewed. A release
   build on top of red CI proves nothing.
2. Confirm the four secrets in §3 exist. (Their values cannot be read
   back from GitHub; presence is confirmed by a successful run, or by
   the named-missing-secret failure if one is absent.)
3. In GitHub Actions, run **Android release** (`workflow_dispatch`)
   from the release branch. It is deliberately not triggered by push
   or pull request.
4. When it finishes, download the `effectcraft-android-release`
   artifact (retention 90 days; archive it outside CI before expiry if
   it must outlive that window). It contains:
   - `app-release.apk` and `app-release.aab`
   - `mapping.txt` (R8 mapping — keep it with the artifact; without it,
     a release stack trace cannot be de-obfuscated)
   - `build/audit-release/` — Rust license inventory, Rust + Android
     release dependency trees, Android license report, and
     `release-artifact-sha256.txt`
5. Verify before any install, from the workflow log and the artifact:
   - the "Verify release artifacts are signed and hash them" step
     passed (META-INF signature files present, plus apksigner
     verification when available on the runner, else jarsigner);
   - the "Reject release APK signed with the debug key" step passed:
     the signer certificate in the APK is not the Android debug
     certificate (`CN=Android Debug`), so the build cannot be a
     debug-key accident;
   - the "Print signing certificate fingerprints" step printed the
     SHA-1 and SHA-256 of the signer certificate actually present in
     the APK. Copy those values exactly as printed for the Firebase
     registration step; never invent, retype from memory, or reuse
     fingerprints from another build. Fingerprints are not secrets
     (they are embedded in the APK), but a wrong fingerprint breaks
     Firebase/Google sign-in registration for that build;
   - the SHA-256 values in the log match `release-artifact-sha256.txt`
     and match the downloaded files (`sha256sum app-release.apk
     app-release.aab` locally).
6. Install only on a physical ARM64 device (see
   `docs/M7_SUPPORT_MATRIX.md` and the gate checklist). Emulator
   install of the same APK exercises the `x86_64` library, not the
   physical-device path, and is not physical proof.

## 5. Reproducing a build locally (reference only, not the release route)

A local build with the same toolchain, from the same commit, with the
same signing key, using the committed wrapper:

```bash
# From native/, once per ABI (mirrors the workflow):
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
cargo ndk -t x86_64 -o ../app/src/main/jniLibs build --release

# From the repository root, with the keystore outside the repo:
./gradlew --no-daemon :app:assembleRelease :app:bundleRelease \
  -Peffectcraft.release.keystoreFile=/path/to/release.keystore \
  -Peffectcraft.release.keystorePassword='<keystore password>' \
  -Peffectcraft.release.keyAlias='<key alias>' \
  -Peffectcraft.release.keyPassword='<key password>'
```

Do not put the keystore inside the repository, do not add it to git,
and do not pass real passwords in a shared log. A local build without
the four properties is unsigned and is not a release artifact, even if
the file names look the same.

## 6. Expected hash behaviour (read this before comparing two builds)

- The workflow prints SHA-256 for the APK and the AAB. That hash
  identifies *that* artifact, lets a downloader verify the file was
  not corrupted or swapped, and lets install evidence be matched to
  exactly one artifact. It is the primary artifact identity.
- Two release builds from the same commit, same toolchain, and the
  **same signing key** are *expected* to match in APK content entries
  and in packaged native `.so` bytes (the same levels the debug
  reproducibility job checks). Raw byte-for-byte SHA-256 equality
  across two separate release runs is **not** guaranteed by this
  pipeline and must not be claimed until a release reproducibility run
  has actually demonstrated it.
- A build signed with a different key, or an unsigned build, is a
  different artifact even if its content matches: its SHA-256 will
  differ and it cannot update an installation of the other build.
- A SHA-256 alone never proves a build is signed. The signature
  verification step (§4 step 5) and, ultimately, a physical install
  are separate evidence. If the signature step failed, the hash is
  irrelevant.

## 7. What this pipeline does NOT do

- No merge of any PR, no release to Google Play, no visibility change,
  no public distribution. Those are separate owner decisions, each
  needing its own approval; this workflow cannot do any of them (its
  permissions are `contents: read`).
- No `.aep` (Adobe, proprietary) or `.efkproj` (Effekseer) support is
  added or claimed. See the support matrix for what is actually
  supported.
- No version bump, no changelog invention, no license selection. The
  Android repository's own license remains unselected (see
  `docs/M7_THIRD_PARTY_NOTICES.md`); third-party notices are not a
  substitute for it.
