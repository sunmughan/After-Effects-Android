# Device evidence plan for M5-L9 and M5-L10 (Agent 11)

Status: PROPOSAL ONLY. This plan is not user-approved, not an
implementation, and not a PASS for M5-L9 or M5-L10. No device evidence
exists yet. Emulator CI is not device evidence and is never counted as
device evidence here. The M5-L9 and M5-L10 bullets stay unchecked with
no PASS label until the user approves the merged scope (Run 109),
approves the device list, the budgets, and the 4K tier rule below,
and a real physical device session is run and recorded.

Branch: feat/android-app-foundation, PR #1 still open draft, not
merged. App minimum SDK is 26, target SDK is 37
(app/build.gradle.kts). All availability statements below are for
that floor unless a newer API level is named.

Scope: this plan covers M5-L9 and M5-L10 only, as one merged scope.
It does not change M5-L7 (Run 108 rewording proposal, awaiting the
user), M5-L8 (foreground-service part in Run 104, codec decision
record in Run 105), any M4 line, any M6 line, or any phase header.
The merged bullet proposal itself is recorded in Run 109 in
PHASES.md; this file is the execution plan for the evidence that
merged bullet would need.

## 1. Proposed device list (proposals, blanks for the user)

The device matrix is an open user decision. The rows below are
proposals by form factor and API level. The user fills in the actual
devices; no device below is presented as user-chosen until the user
fills it in. At least one physical device per row is proposed before
any tier is qualified, because a tier result is recorded per device
model (section 4), never inherited from another model.

| Form factor | Proposed API level band | Proposed role | Actual device model (user to fill) | Actual API level (user to fill) | RAM and SoC (user to fill) |
| --- | --- | --- | --- | --- | --- |
| Phone, minimum | API 26 to API 28 | Lowest supported floor: launch, 720p export, budget check | | | |
| Phone, mainstream | API 30 to API 33 | Main 720p and 1080p evidence device | | | |
| Phone, high end | API 34 or newer | First candidate for 4K tier qualification | | | |
| Tablet | API 30 or newer | Large screen 720p and 1080p evidence | | | |
| Foldable | API 33 or newer | Folded and unfolded 720p and 1080p evidence, state retained across fold | | | |

Rules for the list, all proposals:

- A physical device only. An emulator row never qualifies a tier and
  never passes a case in this plan.
- The user's own device may fill one row, but the user decides which
  row and whether to run it. No device is assumed to be the user's
  device until the user says so.
- If the user supplies fewer devices than the rows above, the plan
  shrinks to the supplied devices and the uncovered rows stay open.
  The plan never silently substitutes an emulator for a missing
  physical device.

## 2. Test cases: 720p and 1080p

One case set per resolution, per device. The same project fixture is
used for every case so results are comparable across devices: a
composition with a fixed frame count, FPS, and layer set, recorded
in the results row (section 5). The fixture definition itself is a
user-approved detail still open; until the user approves it, Agent 11
records whatever fixture was actually used, word for word, in the
results row, and never presents a fixture as standard that the user
has not approved.

### Case E720: 720p export

1. Export the fixture at 1280x720 through the shipped export path
   (PNG sequence ZIP or staged MP4, as available on the build under
   test; the format used is recorded, never assumed).
2. Record: export wall time, frame count exported, frame count
   expected, output file size, and whether the export completed,
   was cancelled by the user, or failed.
3. Pass (proposal): every expected frame exported, output validates
   (ZIP manifest and frame check, or MP4 validator, matching the
   format used), and memory and thermal budgets in section 3 hold.

### Case P720: 720p playback check

1. Play back the exported 720p output on the same device with the
   device media player or the app preview, end to end.
2. Record: playback completed without visible corruption, freeze, or
   audio and video mismatch where audio is present, plus any
   observed defect in plain words.
3. Pass (proposal): playback completes with no observed defect. A
   defect is recorded as a fail for this case even if the export
   case passed, because export success and watchable output are
   different claims.

### Case E1080: 1080p export

Same steps as E720, at 1920x1080. Pass rule is the same as E720,
with the 1080p budgets in section 3.

### Case P1080: 1080p playback check

Same steps as P720, for the 1080p output. Pass rule is the same as
P720.

### What is recorded for every case, without exception

Duration (fixture duration and exported frame count), frame count
(expected and exported), memory peak (section 3), thermal readings
(section 3), export wall time, output format, and pass or fail.
A case with a missing measurement is recorded as incomplete, not as
a pass. An incomplete case never flips a bullet.

## 3. Memory and thermal measurement method (proposals)

### Memory

- Tool or API (proposal): peak Dalvik heap from
  `Runtime.totalMemory - Runtime.freeMemory`, sampled during export
  (at export start, every second during export where the app can
  sample without blocking the render thread, and at export end),
  plus PSS from `dumpsys meminfo` or Android Studio Profiler where
  the session runner has them available. The method actually used is
  recorded in the results row.
- Proposed pass thresholds (proposals only, not user-approved):
  peak Dalvik heap at most 256 MiB, and native media-pool bytes at
  most 256 MiB where the app exposes that counter on the device
  build. These two numbers are carried from the worker 49 M6-L3
  proposed thresholds in docs/performance-budgets.md, where they
  were also proposals, not user-approved values.
- Where M5 export budgets differ from M6-L3 (stated plainly):
  M6-L3 measured a 320x180 text-only preview fixture on emulator CI
  (48 text layers, 288 observed opacity keys, 0 media items), so its
  preview pacing budgets do not transfer to export. The M6-L3
  preview pacing budgets (30 fps mean at most 33.33 ms, p95 at most
  66.67 ms, frames slower than 33.33 ms at most 20 percent) do NOT
  apply to export cases here; export records wall time and frame
  count instead of a per-frame pacing pass. The M6-L3
  sustained-render proxy (second-half mean at most 2x first-half
  mean over 30 further frames) is NOT a thermal measurement and is
  replaced for export by the thermal rule below, because an
  emulator pacing proxy cannot prove device heat.

### Thermal

- Tool or API (proposal): on API 29 and newer,
  `PowerManager.getCurrentThermalStatus()`, sampled at export
  start, at least once mid-export, and at export end, plus the
  device skin or battery temperature reading from the system where
  available. On API 26 to API 28 there is no thermal status API, so
  the thermal reading is recorded as unavailable and the thermal
  case for that device is recorded as unmeasured, never as a pass.
  Where no thermal reading method is available at all, the same
  rule applies: unmeasured, not passed.
- Proposed pass rule (proposal only, not user-approved): no
  sustained export run raises the device thermal status to
  THERMAL_STATUS_SEVERE or above during export. A single transient
  sample at SEVERE that clears before the next sample is recorded
  in full and flagged for the user to judge; it is not silently
  averaged away, and Agent 11 does not upgrade it to a pass without
  a user decision.
- Thermal proxy warning: if only a pacing proxy is available (frame
  times slowing in the second half of a run), that proxy is recorded
  as a proxy only. It is never reported as a thermal pass.

## 4. 4K tier qualification rule (proposal only)

Tier-qualification rule, stated explicitly as a proposal:

1. A device model qualifies for 4K only after that exact model has
   passed cases E720, P720, E1080, and P1080 in section 2 on a
   physical device, within the budgets in section 3.
2. The 4K case itself (E4K export at 3840x2160, plus P4K playback
   check, same steps and recording as section 2) must then pass on
   that same model, within the same memory budgets and the same
   thermal rule.
3. A tier is recorded per device model, never per form factor and
   never per API band. A high end phone passing 4K does not qualify
   a tablet, a foldable, or another phone model, and no emulator
   result qualifies any tier.
4. Until the user approves this rule, 4K stays gated: no 4K claim is
   made for any device, and a 4K export attempted on an unqualified
   model is recorded as unqualified, not as a pass or a fail of the
   tier itself.

How a tier is measured and recorded: the results row (section 5)
for the 4K case carries a `tier` field with the value
`qualified-4K`, `not-qualified-4K`, or `unmeasured-4K`. The tier
field changes only when a new recorded 4K case justifies it, and
the PHASES.md Run entry that claims a tier cites the run id of
that 4K case, so the claim can be traced back to one row.

## 5. Who runs the session, and how results are recorded

### Who runs the session

- The user runs the session on their own device, in a single
  session per device where practical. The user is never asked to
  format a device, and export testing does not flash anything, so
  no flashing step is part of this plan. Where a step would touch
  slots or firmware in any related work, the inactive slot first
  rule applies to that work, not to this export plan; it is stated
  here only so Agent 11 does not confuse the two.
- Agent 11 prepares the fixture, the case list, and the blank
  results rows before the session, and transcribes the user's
  measured values after it, without editing a measured value to fit
  a budget. If a value is missing, the row says missing.

### Recording format (one row per case, per device)

| Field | What goes in it |
| --- | --- |
| run id | One id per physical session, for example `DEV-2026-11-05-01`. The same run id is cited in at most one bullet claim. |
| date | Session date. |
| device model | Exact model string from the device, as the user reports it. |
| API level | Exact API level of the device build. |
| build | EffectCraft build under test: branch commit SHA and version, as reported. |
| export settings | Resolution, FPS, inclusive frame range, frame count, output format. |
| duration | Fixture duration and exported frame count. |
| frame count | Expected frames and exported frames. |
| memory peak | Peak Dalvik heap in MiB, media-pool bytes where available, method used. |
| thermal readings | Thermal status samples (start, mid, end) or `unavailable`, method used. |
| export wall time | Seconds from export start to validated output. |
| pass or fail | Per case: `pass`, `fail`, or `incomplete`, with a one-line reason. |
| tier | For 4K rows only: `qualified-4K`, `not-qualified-4K`, or `unmeasured-4K`. |

### How the same run is never counted twice

- One run id belongs to one session on one device. If the same
  session is relevant to two bullets, the Run entry for the second
  bullet cites the first entry and states plainly that the run is
  already counted there; it does not re-claim the run as fresh
  evidence. This is the same rule that motivated the L9 and L10
  merge: L9 and L10 describe one tier scope, so one device run
  satisfies the merged scope once, and is never tallied as two
  passes.
- A results row is never edited after it is recorded, except by a
  new row that cites the old row and states what changed and why.
  Corrections are append-only.

## 6. What remains open

- User device list: the table in section 1 is blank until the user
  fills it in. No device is assumed.
- User 4K tier approval: the rule in section 4 is a proposal until
  the user approves it. Until then, 4K is gated for every device.
- User budget approval: every threshold in section 3 is a proposal
  until the user approves it. The M6-L3 numbers are referenced as
  proposals, not as approved values, and the M5 differences in
  section 3 are part of the proposal the user must approve.
- User fixture approval: the comparison fixture for section 2 is
  not yet user-approved; Agent 11 records the fixture actually
  used, word for word, until the user approves a standard one.
- User merge approval: the L9 and L10 merged bullet (Run 109) is a
  proposal until the user approves it. Neither L9 nor L10 is
  flipped, deleted, or replaced by this plan.
- Physical-device proof itself: no device session has run yet, so
  no device evidence exists. This plan creates the rows that a
  future session will fill; it does not fill them.
