# M7 — Release notes template

> How to use this template: copy it for a specific release, fill every
> `[bracket]` from the actual release run (workflow log, artifact,
> checklist), and delete nothing that you cannot fill — an unfilled
> field stays visible as "NOT VERIFIED" so a reader never mistakes a
> gap for a pass. Do not copy notes from a previous release. Do not
> invent a changelog, a version, or a device result.
>
> Scope guard: closed/personal build for the owner. These notes
> describe an artifact for the owner's own device testing. They are
> not a public release announcement, not a Play listing, and not a
> license grant.

---

# EffectCraft for Android — Release notes

- **Version:** `[versionName from app/build.gradle.kts as built]`
  (versionCode `[versionCode as built]`)
- **Application ID:** `[id actually built — decided id is
  `com.codeair.effectcraft`, which is the id the integrated branch
  builds; quote the built APK's id, verified from the artifact]`
- **Branch / commit built:** `[branch] @ [full commit SHA]`
- **Release workflow run:** `[Actions run URL + run number]`
- **Release artifact:** `[artifact name effectcraft-android-release]`
- **Artifacts and SHA-256 (from the workflow log and
  `release-artifact-sha256.txt`, verified against the downloaded files):**
  - APK: `[sha256]`
  - AAB: `[sha256]`
- **Signed with:** `[key alias is NOT printed here; record only "release
  key held by the owner" or "NOT SIGNED — NOT A RELEASE" if the
  signature step failed. Never record a password, keystore content, or
  secret value.]`
- **Signature verification:** `[apksigner verify result, or jarsigner
  result + note that apksigner was unavailable, or FAILED]`
- **Signing certificate fingerprints (copied exactly from the release
  workflow's "Print signing certificate fingerprints" step, for
  Firebase registration — never invented or reused from another
  build):** SHA-1 `[value or NOT PRINTED]`, SHA-256 `[value or
  NOT PRINTED]`
- **Current-head debug CI at release time:** `[android-ci run URL,
  result, and the test counts it actually reported — Rust / JVM /
  emulator — or NOT GREEN]`
- **R8 mapping:** `[mapping.txt archived with the artifact: yes/no]`
- **License inventory for this artifact:** `[release run's
  build/audit-release/ reviewed: yes/no; any missing-license entry:
  none / list]`
- **Artifact retention:** CI artifact retained until `[date = run date
  + 90 days]`; archived outside CI at `[location or NOT ARCHIVED]`.

## What this build contains

- `[One line per real change since the previous release artifact,
  each traceable to a commit. If there is no previous release
  artifact, say so and list the scope in one paragraph instead of
  inventing a diff.]`
- Supported in this build: canonical `.ecproj`/`.ecprojx` open/edit/
  CPU preview, PNG-sequence ZIP export, offline drafts and media
  library (see `docs/M7_SUPPORT_MATRIX.md` for the full list).
- Not in this build: video/audio export, `.aep` support, `.efkproj`
  support, full effect/3D parity, pinch/twist gestures (see the
  support matrix — do not delete this paragraph to make the notes
  look better).

## Known limitations (copy from the support matrix; add any found in testing)

- `[Limitation + the step/device where it was observed, or "none
  newly observed in this release's device run"]`
- Physical-device proof for this artifact: `[device model, Android
  build, ABI, and checklist result — or PENDING if the device run has
  not happened]`

## Install notes (owner's own device only)

1. Verify the APK SHA-256 against the values above before installing.
2. Install on a physical ARM64 device (`arm64-v8a`). An emulator
   install tests the `x86_64` library, not the physical path.
3. The app works offline and requests no network permission; test it
   with no network and no account.
4. Updating an existing install requires the same signing key as the
   installed build. A build signed with a different key cannot update
   it in place and must be installed after uninstalling (which loses
   app-private data).

## Open gates at the time of these notes

- `[Each M7 gate from docs/M7_RELEASE_GATE_CHECKLIST.md that is still
  unticked, with its reason. Never write "all gates passed" unless
  every gate in that checklist is ticked on real evidence.]`
