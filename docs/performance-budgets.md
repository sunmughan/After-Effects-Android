# M6-L3 - Proposed performance budgets and large-project benchmarks

Status: PROPOSAL, not user-approved. The numeric thresholds below
were an open user question when M6-L3 was written, so they are stated
explicitly here, in `engine/PerformanceBudgets.kt`, and in the
benchmark tests, so the user can correct any value. No threshold in
this document is presented as user-approved.

Scope: emulator CI only (API 30, x86_64, Pixel 2 profile, the CI
workflow's emulator). Nothing here proves physical-device behavior.

## 1. Proposed budgets

| Budget | Proposed value | Where it is enforced |
| --- | --- | --- |
| Native media-pool bytes | At most 256 MiB (the existing Android contract in `MediaPoolStats.ANDROID_BUDGET_BYTES`) | `LargeProjectBenchmarkInstrumentedTest.largeProjectLoadStaysWithinMemoryBudgets` |
| Peak Dalvik heap in use after large-fixture load plus render | At most 256 MiB (`Runtime.totalMemory - freeMemory`, after a GC hint) | Same memory test |
| Preview frame pacing, 30 fps composition | Mean at most 33.33 ms, p95 at most 66.67 ms, frames slower than 33.33 ms at most 20% | `largeProjectPreviewFramePacingMeetsProposedBudget` |
| Sustained-render proxy (NOT a thermal test) | Second-half mean at most 2x first-half mean over 30 further frames | `largeProjectSustainedRenderHasNoThermalProxyRegression` |

The heap budget is deliberately generous: it is a ceiling against
unbounded growth, not a claim about typical use. The pacing budgets
are emulator-relative: the same numbers on a physical device may
need revision once device data exists, and that revision is a user
decision, not a silent test edit.

## 2. Large-project fixture definition ("large")

Built reproducibly in the benchmark test, never hand-written:

- 1 composition, 320x180, 30 fps (the canonical empty fixture from
  `NativeSessionInstrumentedTest.FIXTURE_JSON`).
- 48 text layers, added one by one through
  `NativeProjectBridge.addTextLayerEcproj`, the same bridge path the
  editor uses.
- 5 opacity keyframes added per layer (frames 15, 30, 45, 60, 75),
  added through `NativeProjectBridge.setLayerOpacityKeyframeEcproj`:
  240 added keyframes. The bridge seeds frame 0 with the first key,
  so the inspected fixture carries 288 observed opacity keys
  (6 per layer). Each layer also gets short text ("Layer N") through
  `setTextLayerTextEcproj`, so preview rendering exercises glyph
  painting, not just empty layer traversal.
- 0 media items: the fixture is text layers only, so it benchmarks
  layer/keyframe scale and preview rendering, not media decode.
  Media decode at scale is still unmeasured and remains open.
- The built fixture is re-inspected through
  `NativeProjectBridge.inspectEcproj` and parsed with
  `CanonicalProjectInfo` before any benchmark runs, so a bad build
  fails at setup, never as a silent pass. The fixture test asserts
  exactly 48 layers and 288 observed opacity keys.

## 3. What the benchmarks record

Every benchmark logs one greppable line under tag
`EffectCraftBenchmark` carrying the actual measured values
(media-pool bytes, heap bytes, PSS, frame times, pacing report),
and asserts the measured values against the proposed budgets above.
Results are also visible in the CI test-results artifact as named
JUnit tests; there is no separate machine-readable file because the
CI workflow already publishes the JUnit XMLs that carry the
per-test outcome.

## 4. Thermal statement, stated honestly

The CI emulator cannot prove device thermal behavior: it has no
physical thermal sensor, no battery thermal path, and its host CPU
is not the phone SoC. Therefore:

- NO thermal pass is claimed by this work, on any run.
- The sustained-render test only checks that a second block of 30
  frames does not run dramatically slower than the first block
  (a throttling proxy, factor 2). Passing it proves the render loop
  completes steadily on the emulator, nothing about device heat.
- A real thermal gate still needs a physical device: sustained
  preview/export on hardware with thermal state recorded. That gate
  stays open after L3, whatever the emulator numbers say.

## 5. What remains open after this work

- Physical-device memory, pacing, and thermal measurements.
- Large projects with real media (footage, images, audio) at scale;
  this fixture has none by design.
- 720p/1080p preview pacing; benchmarks run at 160 max side.
- User approval or correction of every proposed threshold above.
