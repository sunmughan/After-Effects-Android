# M7 — Support matrix and known limitations

> Scope guard: closed/personal build for the owner. "Supported" below
> means "implemented in this codebase and covered by the stated
> evidence" — never "proven on a physical device" unless the evidence
> column says so. Anything marked pending is pending, even if the code
> exists.

## 1. Platform support

| Item | Value (source) | Notes |
|---|---|---|
| Application ID (decided) | `com.codeair.effectcraft` | Owner decision 2026-11-04; the `ec-pkg` rename is integrated on the feature branch and this M7 integration ports every M7 file to it (see build doc §1); release notes still quote the id actually built, verified from the artifact |
| Version at time of writing | `0.1.0` (code `1`) | Same file; quote the built values, not this doc, at release time |
| minSdk | API 26 (Android 8.0) | Same file |
| targetSdk / compileSdk | API 37 | Same file |
| Devices above target API 37 | Not verified | A newer Android version is not "supported" until it is tested; do not claim it from the target value |
| ABIs in the universal APK/AAB | `arm64-v8a`, `x86_64` | Both native release builds in the release workflow |
| Physical phones | `arm64-v8a` only in practice | The physical install gate must use an ARM64 device |
| Emulator | `x86_64` | Emulator evidence is not physical-device evidence |
| 32-bit-only devices (`armeabi-v7a`, `x86`) | Not supported | No native library is built for them; the JNI bridge reports unavailable (lazy-load design) and canonical features that need it fail visibly instead of opening a half-validated project. Confirm this behaviour on a device before ever describing it as tested. |
| Network | None required; manifest requests no `INTERNET` | Offline-first; a release build must still launch and work with no network and no account |
| Form factors | Phone, tablet, foldable (adaptive workspace) | Layout shell exists; the full device matrix in `docs/ANDROID_TEST_MATRIX.md` (minimum / mainstream / high-end / large-screen / foldable / pen) is the gate, and most of its rows are still open (see §4) |

## 2. What is supported (implemented + CI-covered at the stated level)

- Canonical Shape authoring: Add Rect creates a real upstream shape layer with rectangle geometry/fill through JNI; API30 instrumentation and compositor pixel regression passed in CI #1207 at branch SHA 75aa1a35. Newly created Text/Shape layers are selected automatically by the latest implementation checkpoint; that selection fix awaits exact-head CI.

- Canonical EffectCraft projects: `.ecproj` (JSON) and `.ecprojx` (XML)
  import through SAF, with native schema validation; import is
  persisted only after validation succeeds and metadata contains at
  least one composition with positive dimensions/FPS and finite
  positive duration.
- Saved-project repository browsing and switching between saved
  canonical entries in the editor (Projects picker).
- Editing: layer add/select/remove/reorder, rename, visibility,
  opacity, Position/Scale/Rotation keyframes (add/remove) and
  opacity keyframes through the native bridge, with upstream
  interpolation; inspector Linear / Easy Ease / Hold presets at keyed
  frames; timeline markers that seek keys; undo/redo for covered
  actions.
- Preview: actual upstream CPU compositor frames through JNI
  (canonical preview), with a shared native session lifecycle
  (create / render / update / resize / cancel / close) and a GPU
  backend-status / recovery path in code.
- Offline media library: app-private assets, relinking footage to
  canonical items, reference-aware removal guard, durable
  item → SHA-256 references, atomic persistence.
- Drafts: offline draft store in app-private storage, durable
  fsync-before-promotion for drafts/keyframes, interrupted-write
  recovery, stale staging cleanup, asset quotas/rollback.
- Export: native frame → PNG, and configurable PNG-sequence ZIP
  (dimensions, FPS/range, resampling, manifest, staged ZIP integrity)
  via SAF, with bounded copy and rollback/best-effort provider
  deletion on failure; H.264 MP4 sequence export and AAC audio mixdown
  are implemented with MediaCodec and output validators (device codec/audio
  synchronization matrix remains pending).
- Accessibility (partial): semantics/labels added for covered
  controls, including a screen-reader click on the transform lane.
  TalkBack/manual verification is still pending (§4).

## 3. What is NOT supported (do not claim, do not imply)

- Adobe After Effects `.aep` — proprietary format, no licensed parser
  in this project. Never describe a project as ".aep-compatible".
- Effekseer `.efkproj` — no parser/converter in this project.
- Full audio preview/sync parity and codec/device matrix — not yet qualified.
  MP4/H.264 export and AAC mixdown are implemented; see
  Mp4SequenceExporterInstrumentedTest, Mp4ExportWorkerInstrumentedTest
  and AudioMixdownInstrumentedTest. PNG-sequence ZIP remains supported.
- Full effect-parameter and 3D transform parity — not implemented;
  only the transform/opacity families in §2 are covered.
- Pinch/twist gestures — the pinch/twist detector is not wired;
  rotation remains editable through the inspector and keyable at the
  timeline frame.
- Bezier/easing graph editing and drag-to-key editing — not
  implemented (preset editor only).
- Google Play distribution, public distribution, or any store listing
  — out of scope for this workstream and not performed by the release
  workflow.
- Any `.aep`/`.efkproj` compatibility, any "production-ready" claim,
  and any claim that emulator tests equal device proof.

## 4. Known limitations and open gates (honest, as of this workstream)

These are the reasons M7 cannot be marked complete by this workstream
alone. Each maps to a phase gate that another workstream or the owner
must still close with real evidence:

1. **Physical-device proof: PENDING (not available to this agent).**
   No physical ARM64 install/run/smoke evidence exists from this
   workstream. No install, launch, edit, preview, or export on a real
   phone is claimed. The gate checklist requires device model, Android
   build, ABI, and the release artifact's SHA-256 recorded together.
2. **Current-head CI green: REQUIRED, not yet seen for this branch.**
   At the time of writing, no CI run exists yet for the integrated
   M7 head; the M7 gate dispatches `android-ci` on the exact
   integrated head first. A release dispatch on red or unrun CI is
   not a release.
3. **Release signing secrets: NOT PRESENT in this environment.**
   The four secrets in `docs/M7_RELEASE_BUILD.md` §3 must be supplied
   by the owner. Until then the release job will (correctly) fail at
   its secrets gate. That failure is the designed behaviour, not a bug
   to work around.
4. **R8 release build unproven.** Minification/shrinking is new in
   this workstream. The first release run must prove the R8 build
   launches and that the JNI bridge still resolves (a release smoke
   run on device, plus the checklist's release-specific checks). A
   debug green run does not prove the release variant.
5. **M0 owner acceptance (#13) still open**, and the Android
   repository's own license still unselected. Both block any external
   distribution claim, regardless of build success.
6. **M2 physical gates open**: physical SAF picker/relink, media and
   multi-project manifests, missing-media recovery.
7. **M3 physical gates open**: physical ARM64 GPU/Surface path,
   device-loss recovery on hardware, interruptible CPU rendering.
8. **M4 parity open**: full effect stack, 3D/spatial/roving parity,
   pinch/twist and complete interpolation parity (see §3).
9. **M5 open**: video/audio, background render service, interruption
   recovery, physical memory/thermal acceptance.
10. **M6 open**: full device/API/performance matrix, TalkBack/manual
    accessibility, process-kill/device recovery beyond the implemented
    helpers, large-project memory/frame-pacing/thermal behaviour.
11. **Release reproducibility unproven**: raw SHA-256 equality across
    two release runs has not been demonstrated (see the build doc §6).
12. **License inventory is per-run**: the notices doc's §5 inventory
    must be re-read from the actual release run; older counts and
    reports are not current evidence.

## 5. Minimum smoke evidence for a device run (summary)

The full, tickable version is `docs/M7_RELEASE_GATE_CHECKLIST.md`.
In short, a device run must record: device model + Android build +
ABI, artifact SHA-256, cold offline launch, open a real supported
project, edit (layer/opacity/transform key), preview/render a frame,
export a PNG-sequence ZIP, force-stop and reopen without losing the
project or playhead, and rotate/resize without losing selection.
Any failure is recorded as a failure with the step that failed — a
partial run is not reported as a pass.
