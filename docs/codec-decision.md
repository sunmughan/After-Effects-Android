# Codec and audio decision record for M5-L8

Status: DECIDED by the owner 2026-11-06. H.264 AVC in MP4 with AAC audio
is the only default. H.265/HEVC in MP4 is an explicit opt-in only, offered
after a runtime probe confirms a hardware HEVC encoder exists on the
device. No AV1 encode. This record is the decision; the implementation
below it is real code (probe, plan, exporter, validator, export dialog),
not a proposal. The M5-L8 bullet stays unchecked until the codec/audio
part is proven by the acceptance tests in section 8, including the
physical-device items that CI cannot prove.

Scope: M5-L8 only, codec/audio part. The foreground-service part of M5-L8
was recorded separately in Run 104 for PNG-sequence export only. This
record does not change that result.

Branch: feat/android-app-foundation. App minimum SDK is 26, target SDK is
37 (app/build.gradle.kts). All availability statements below are for that
floor unless a newer API level is named.

Sources checked for availability statements:
- Android supported media formats: https://developer.android.com/media/platform/supported-formats?authuser=5
- Android MediaMuxer reference: https://developer.android.com/reference/kotlin/android/media/MediaMuxer?authuser=256

## 1. What codec, container, audio, and sync code already exists

MP4 work in the repo is real but partial. It is not a finished codec
delivery, and it must not be described as one. Proven means covered by
tests named below on emulator CI. Partial means code exists but a named
gap remains. Missing means no code exists.

### Proven (emulator CI only, no physical-device proof)

- H.264 video encoding path: `app/src/main/java/com/codeair/effectcraft/data/Mp4SequenceExporter.kt`
  streams canonical native RGBA frames into a MediaCodec `video/avc`
  encoder with `COLOR_FormatYUV420Flexible` input and a MediaMuxer
  `MUXER_OUTPUT_MPEG_4` output. It requires even width and height, sets
  an I-frame interval of 2 seconds, takes bitrate from the plan, and
  stamps video presentation times as frame index times 1,000,000 / export
  FPS. If no encoder on the device accepts flexible YUV420 input, it
  throws instead of producing a fake file. Instrumented test
  `Mp4SequenceExporterInstrumentedTest.flexibleYuv420EncoderProducesReadableH264Mp4`
  covers this path on the emulator.
- Staged validation before SAF copy: `app/src/main/java/com/codeair/effectcraft/data/Mp4ExportWorker.kt`
  encodes to a staged file, validates it, and only then lets the caller
  copy it to the SAF destination. Instrumented test
  `Mp4ExportWorkerInstrumentedTest.stagedVideoOnlyExportValidatesAndCopiesThroughSafWriter`
  covers the video-only staged path.
- Container header inspection: `app/src/main/java/com/codeair/effectcraft/data/Mp4BoxInspector.kt`
  is a pure JVM parser for ISO-BMFF boxes. It checks `ftyp` and `moov`,
  reads `mvhd`/`mdhd` timescale and duration for version 0 and version 1
  layouts, and reports the first video track codec, dimensions, and
  sample count. It accepts only `avc1` as the video codec. It reads
  headers only and never validates audio.
- Staged MP4 validation: `app/src/main/java/com/codeair/effectcraft/data/Mp4ExportValidator.kt`
  uses MediaExtractor and requires a present H.264 video track, a video
  sample count exactly equal to the plan frame count, and a duration
  within one frame of the plan duration. When audio is expected, it also
  requires an AAC track with at least one sample.
- Plan limits: `app/src/main/java/com/codeair/effectcraft/data/RenderQueuePlan.kt`
  defines the only MP4 format today, `MP4_H264`, with even dimensions,
  bitrate in 250,000 to 50,000,000 bits per second (default 8,000,000),
  frame rate 1 to 120, at most 10,000 frames, and an 8 GiB uncompressed
  RGBA budget. No other codec or container is a plan option today.

### Partial

- AAC audio passthrough only: `app/src/main/java/com/codeair/effectcraft/data/AudioTrackSelection.kt`
  and the re-mux step in `Mp4ExportWorker.kt` copy the first AAC
  (`audio/mp4a-latm`) track sample-for-sample through
  MediaExtractor/MediaMuxer. The export range is computed from
  composition FPS, not export FPS, so audio stays aligned when the two
  differ, and audio timestamps are shifted by the range start. When the
  AAC track yields zero samples inside the range, the re-muxed file is
  discarded and the validated video-only file stands. Instrumented test
  `Mp4ExportWorkerInstrumentedTest.aacFixtureIsPassedThroughWithSamplesInRange`
  covers the in-range case, and
  `Mp4ExportWorkerInstrumentedTest.junkAudioFileExportsVideoOnlyWithUnsupportedReason`
  covers the unsupported case.
- Audio status is always visible, never silent: `AudioStatus` reports
  INCLUDED, NO_AUDIO_IN_COMPOSITION, AUDIO_FILE_UNAVAILABLE,
  AUDIO_TRACK_UNSUPPORTED, or AUDIO_CLIPS_SILENT (editor audio clips
  exist but none audible covers the export range), and the UI shows
  the video-only reason.
- Editor audio editing (feat/ec-audio-editing): audio clips reuse the
  `ClipDocument`/`ClipTimeline` model with per-clip gain (0..2), fades
  and mute (`AudioClipDocument.kt`, `AudioTimeline.kt`), persist in
  the draft store and recovery journal through `AudioTrackJson.kt`,
  and are edited in the timeline UI (`EditorScreen.kt`). At export,
  when the draft timeline applies to the export, `AudioMixdown.kt`
  decodes each audible clip's source window to PCM
  (`AudioDecoder.kt`), resamples to 44.1 kHz stereo, mixes all clips
  at their timeline positions with gain, fades and mute (silence
  where no clip covers), encodes one AAC LC track with
  composition-timeline PTS, and muxes it through the same re-mux step
  as passthrough. WAV, MP3, FLAC and Opus transcode; any other format,
  a missing file, or an undecodable source fails the export with the
  clip id and the reason instead of exporting video-only. Instrumented
  test `AudioMixdownInstrumentedTest` covers the WAV mixdown, mute,
  fail-closed, and staged export with audio included.
- MP4 export is wired to the editor UI: `app/src/main/java/com/codeair/effectcraft/ui/EffectCraftApp.kt`
  offers an MP4 export dialog (dimensions, FPS, frame range, bitrate),
  a `CreateDocument("video/mp4")` SAF path, progress messages, and
  deletion of the partial document on cancel or failure.
- Aspect-preserving contain (letterbox/pillarbox): `app/src/main/java/com/codeair/effectcraft/engine/Yuv420FrameConverter.kt`
  scales each frame into the exact plan dimensions without stretching,
  centers it, and composites unused area to opaque black before I420
  conversion. Same-aspect output fills the frame. This is not a crop;
  crop/cover remains unimplemented. JVM fit-geometry and API30 pixel/
  chroma regressions are committed; fresh exact-head CI is pending.

### Missing

- No HEVC, AV1, VP9, ProRes, or any other video codec code exists. A
  repository search for HEVC, AV1, ProRes, VP9, crop, and letterbox
  finds no such implementation.
- Audio transcoding now exists for editor audio clips (see the
  "Editor audio editing" bullet under Partial): WAV, MP3, FLAC and
  Opus sources decode to PCM and re-encode to AAC in the mixdown. A
  non-AAC source on the legacy path (no editor audio clips) still
  exports video-only with the unsupported reason. No time-stretch, no
  synthesized audio, and no drift detection or correction exist.
- AAC timing diagnostics are now measured by the staged validator (Automation run 11): first/last audio sample PTS and MediaExtractor's audio track duration are surfaced in the staged result and export status. This is measurement only. The validator still does not enforce audio start/end against video, any lip-sync tolerance, drift detection, or drift correction; no threshold or reject/trim policy has been approved.
- No crop or letterbox option exists in code, in the plan, or in the
  export dialog.
- No foreground-service execution for MP4 exists. MP4 runs as a UI-held
  coroutine job in `EffectCraftApp.kt`. The foreground service proven
  in Run 104 (`app/src/main/java/com/codeair/effectcraft/export/PngSequenceExportService.kt`
  with `app/src/main/java/com/codeair/effectcraft/data/ForegroundPngSequenceExport.kt`)
  covers PNG-sequence ZIP export only. Its recovery model is cleanup
  plus user restart from frame 0, never resume, and that model is the
  one any MP4 service work should reuse, not invent a new one.
- No device matrix exists. Encoder availability is probed at runtime
  with MediaCodecList, so a device without a flexible-YUV420 AVC
  encoder fails visibly today. No claim is made about which physical
  devices pass, and no playback check in an external player exists.

## 2. Candidate video codecs and containers

Availability notes below follow the Android supported-formats page.
Encoder availability on a given device is always a runtime probe, never
a guarantee from the API level alone, except where the page states an
encoder is mandatory from a given release.

| Codec | Container | API 26+ availability | Emulator vs device note |
|---|---|---|---|
| H.264 AVC (video/avc) | MP4 (MPEG_4) | Baseline Profile encoder since Android 3.0; decoder required, encoder recommended per the formats page | Emulator CI already encodes it (API 30 x86_64 software encoder). Physical devices vary by hardware encoder profile, level, resolution, and bitrate, so each tier still needs a device check. |
| H.265 HEVC (video/hevc) | MP4 (MPEG_4) | Decoder since Android 5.0; encoder is device dependent and must be probed, not assumed at API 26 | Same emulator/device split as H.264, but with a narrower encoder population. A device that decodes HEVC may still have no HEVC encoder. |
| AV1 (video/av01) | MP4 (MPEG_4) | Decoder since Android 10; encoder mandatory only beginning with Android 14 | On API 26 to 33 an AV1 encoder is optional per device. Software encoding where present is slow and costly. Cannot be the default for a minSdk 26 app. |
| VP9 (video/x-vnd.on2.vp9) | WebM (MUXER_OUTPUT_WEBM) | Decoder since Android 4.4; encoder is device dependent and must be probed | WebM is a MediaMuxer output, so the container is available, but encoder population and player/share compatibility are narrower than MP4. |
| ProRes | MOV (not a MediaMuxer output) | No Android platform MediaCodec ProRes encoder | Would need a bundled third-party encoder and a MOV muxer written for the app. That is a separate product and licensing decision, not a codec switch. |
| H.263 / MPEG-4 SP | MP4 / 3GP | Legacy platform codecs | Worse size and quality than H.264 for the same content. No reason to ship them in a new export path. |

File size and quality tradeoffs, stated qualitatively because no
bitrate ladder has been measured for this app yet:

- H.264 is the compatibility baseline: it plays almost everywhere, at
  the cost of larger files than HEVC or AV1 at the same perceived
  quality.
- HEVC typically compresses better than H.264 at the same quality, so
  files are smaller, but encoding is more expensive and older players
  and editors may not accept it.
- AV1 typically compresses better than HEVC at the same quality, but
  encoding cost is highest, and on this app floor it is only a
  guaranteed encoder from Android 14.
- VP9 sits between H.264 and HEVC on compression in practice, but its
  WebM container is less universally accepted for sharing and editing
  than MP4.
- ProRes is an editing intermediate, not a delivery codec: very large
  files, very low compression effort, meant for further editing rather
  than sharing. That is the wrong shape for a first mobile export.

Licensing and patent notes, limited to what can be verified here:

- This record states no license fee, royalty term, or patent position
  for any codec, because those terms depend on the distribution model
  and the licensor, and they are not stated in the Android platform
  documentation. H.264 and HEVC are patent-pool administered formats,
  and AV1 is published through AOMedia; the owner must confirm the
  applicable position before a first release ships any of them. The
  Android documentation listing a codec does not itself grant a patent
  license. If the owner wants a codec whose licensing position is
  unclear, that confirmation is a release gate, not a post-release task.

## 3. Candidate audio codecs and sync models

### Audio codecs

- AAC LC passthrough (current code, recommended for first delivery):
  AAC LC encoding and decoding are platform codecs with standard
  sampling rates from 8 to 48 kHz per the formats page. Passthrough
  avoids a transcode generation of quality loss and avoids encode cost.
  Its limits are the point of the user question: only AAC sources, only
  the first AAC track, no mixing, no level or fade handling.
- AAC encode from decoded PCM (deferred): decode any supported source,
  resample if needed, and encode AAC. This is what makes WAV, MP3,
  FLAC, and other composition audio exportable with sound instead of
  video-only. It adds decode plus encode cost, a resampling decision,
  and a quality decision, and it needs its own sync tests. It is the
  natural second audio step if the user wants non-AAC sources in v1.
- Opus in WebM (deferred with WebM): only relevant if VP9/WebM is
  chosen. It does not belong in an MP4-first delivery.
- No audio track (must stay supported): a composition without audio,
  or an export whose audio does not overlap the range, must still
  produce a valid video-only MP4 labeled with the reason, as today.

### Sync models

- Current model: video presentation times come from the export frame
  index and export FPS. Audio timestamps are shifted by the
  composition-FPS range start, and samples outside the selected
  composition range are trimmed. The staged validator now reports
  first/last AAC PTS and track duration (Automation run 11), but does
  not compare them with video duration or apply a sync threshold.
  There is no drift detection or correction beyond shift and range
  trimming.
- Proposed first-delivery model (proposal, not approved): keep shift
  plus range trimming, and add measurement: first audio presentation
  time after shifting, last audio presentation time, audio duration,
  and video duration, all checked in the staged validator before SAF
  copy. On drift, the export must fail visibly or trim visibly, never
  silently stretch audio, because time-stretching changes pitch and
  tempo and needs DSP work that is out of scope.
- What happens on drift (proposed): if the audio end differs from the
  video end by more than the agreed tolerance, the export is rejected
  before copy with a stated reason, or the audio is trimmed to the
  video duration with that trimming stated in the status. The choice
  between reject and trim is part of the user question in section 7.
- What happens with no audio track: the export succeeds as video-only
  and the status states the reason, exactly as `AudioStatus` does
  today. Silence is never synthesized to pad a missing track.
- Known sync risk to verify, not assumed solved: AAC encoders add
  priming and delay samples, and passthrough timestamps from
  MediaExtractor may not account for that delay the same way on every
  device. The acceptance tests in section 8 must measure start offset
  on emulator and on at least one physical device before any sync
  claim is made.

## 4. Crop and letterbox scope question

Definitions:

- Stretch (current behavior): the frame is scaled to the exact plan
  dimensions, distorting the picture when aspect ratios differ.
- Crop: the frame is cut to the target aspect ratio, losing picture at
  the edges. Which edges are lost needs a rule (center crop is the
  simplest) and ideally a preview.
- Letterbox or pillarbox: the full frame is preserved and padded with
  bars to reach the target aspect ratio. Nothing is lost, but the
  output carries bars.

Recommendation (proposal, not approved): crop and letterbox are OUT
of the first codec delivery, for these reasons: the codec decision
itself is already blocked on the user, the existing stretch behavior
is already validated for the default case where plan dimensions match
the composition, and a crop or letterbox choice needs a settings UI
choice, a preview, and its own tests. Shipping the codec first keeps
the decision small and testable. Two conditions go with this
recommendation: first, the export settings must label the current
behavior as stretch when the chosen dimensions change the aspect
ratio, so no user is surprised by distortion; second, letterbox is
the recommended immediate follow-up, because it preserves the full
frame, while crop follows after it, because crop discards picture
and needs a preview control to be safe.

If the user instead wants aspect handling in v1, the cost is explicit:
one more settings choice, one more scaling path in
`Yuv420FrameConverter`, preview coverage, and acceptance tests for
both padded and cropped output dimensions and content.

## 5. Decided first-delivery subset (owner decision 2026-11-06)

- Video: H.264 AVC in MP4, using the existing MediaCodec and MediaMuxer
  path. Keep even dimensions, the 250 kbps to 50 Mbps bitrate range
  with the 8 Mbps default, the 2 second I-frame interval, and the
  flexible-YUV420 encoder probe with a visible failure when no encoder
  exists. H.264 is the only default; the export dialog offers no other
  codec unless the HEVC opt-in below is available.
- HEVC opt-in: H.265/HEVC in MP4 is offered only as an explicit opt-in
  after `HevcEncoderProbe` confirms a hardware HEVC encoder exists on
  the device (flexible YUV420 input, hardware-accelerated flag on API
  29+, name heuristic below that). A software HEVC encoder never opens
  the opt-in. The export dialog shows the H.265 button disabled with a
  stated reason when no hardware encoder exists. `RenderOutputFormat.MP4_HEVC`
  shares the MP4 dimension and bitrate rules; `Mp4SequenceExporter`,
  `Mp4ExportValidator`, and `Mp4BoxInspector` accept the HEVC track
  (hev1/hvc1). Full HEVC encode is proven only on a device with a
  hardware HEVC encoder; CI emulator coverage is the probe's
  self-consistency test, not an encode.
- Audio: AAC LC passthrough for the legacy path (first AAC track
  only, composition-FPS range shift plus trimming to the range,
  video-only fallback labeled with the reason); editor audio clips
  take the mixdown path instead — decode, resample to 44.1 kHz
  stereo, mix to one AAC track with gain, fades and mute, and mux
  with the same range shift. No time-stretch.
- No AV1 encode: AV1 encoding is not implemented and not offered. The
  encoder is mandatory only from Android 14 and cannot serve a minSdk
  26 app; it stays rejected for v1.
- Sync: shift plus trim, with staged measurement of audio start, audio
  end, and video duration, and a tolerance gate (proposed: 100 ms, to
  be confirmed by the user) applied before SAF copy.
- Aspect: stretch labeled in settings; crop and letterbox out of v1
  as in section 4.
- Execution: move MP4 export under the foreground-service pattern
  already proven for PNG sequences (progress notification, user
  cancel, staged validation before copy, cleanup plus restart from
  frame 0 on interruption, never resume), because L8 itself demands
  the foreground service and the current UI coroutine job does not
  survive process interruption the same way. This is part of the
  first-delivery recommendation, not a claim that it exists today.
- Resolution tiers: 720p and 1080p as the v1 targets, with 4K only on
  device tiers that pass measured budgets, matching the separate M5
  resolution line. No 4K promise for v1.

## 6. Rejected options, with reasons

- HEVC as the v1 default: encoder population at API 26 is device
  dependent, so a v1 default would fail visibly on more devices than
  H.264. Revisit as an opt-in second codec after the H.264 path and
  its device matrix are proven.
- AV1 as the v1 default: encoder mandatory only from Android 14, so
  it cannot serve a minSdk 26 app as a default. Revisit when the app
  floor rises or as an opt-in on API 34 and higher.
- VP9/WebM as the v1 default: narrower sharing and editing
  compatibility than MP4, and encoder availability is also device
  dependent. No advantage over H.264/MP4 for a first delivery.
- ProRes/MOV: no platform encoder, no MediaMuxer MOV output, and file
  sizes suited to editing intermediates rather than mobile sharing.
  Reject for v1; reconsider only if the user explicitly wants an
  editing-intermediate export.
- H.263 / MPEG-4 SP: legacy quality and size, no benefit over H.264.
- Audio transcode in v1: was deferred as the second audio step ("adds
  decode, resample, encode, and sync test surface before the
  passthrough path has a device matrix"). That step has since landed
  for editor audio clips (feat/ec-audio-editing, see the "Editor audio
  editing" bullet under Partial); the legacy passthrough path is
  unchanged.
- Audio time-stretch to fix drift: changes pitch and tempo, needs DSP,
  and hides a sync error instead of reporting it. Reject; report or
  trim instead.
- Crop or letterbox in v1: needs settings UI, preview, and tests that
  would delay the codec decision. Defer as in section 4, with stretch
  labeled in the meantime.
- Silent video-only fallback: rejected already by the current code
  and reaffirmed here. Every video-only export must state its reason.

## 7. Open questions that only the user can answer

1. Video codec and container for v1: DECIDED 2026-11-06. H.264 AVC in
   MP4 is the only default; HEVC is an explicit opt-in after the
   hardware probe; no AV1 encode.
2. Audio codec for v1: is AAC passthrough only accepted, with non-AAC
   sources exporting video-only and labeled, or must v1 also transcode
   non-AAC sources to AAC?
3. Sync model and tolerance: is shift plus trim with a 100 ms audio
   end tolerance accepted, or does the user want a tighter or looser
   tolerance, and on a tolerance breach should the export be rejected
   or trimmed with a stated note?
4. Crop and letterbox scope: are crop and letterbox out of v1 as
   recommended in section 4, or must one of them (and if so, which)
   be included in the first codec delivery?
5. Resolution and bitrate for v1: are 720p and 1080p with the current
   8 Mbps default the right v1 targets, or does the user want a
   different default bitrate or a 4K tier in v1?
6. Execution for v1: must MP4 export run under the foreground service
   before L8 can be considered done, or is the current UI coroutine
   job acceptable for v1 with the service as follow-up?
7. Playback and sharing targets: where will these files be played and
   shared (Android players, YouTube, editors, messaging apps)? The
   answer affects whether H.264/MP4 is sufficient or a second codec
   is needed sooner.

No codec work beyond this record should start until questions 2 to 4
are answered, because each answer changes the implementation. Question 1
is decided (see above).

## 8. Acceptance tests that would prove the chosen codec

These tests are proposed, not passing, and none of them is claimed
today. Each must run on emulator CI, and the device items must also
run on at least one physical device before any device claim is made.

1. Container validity: the output has `ftyp` and `moov`, parses with
   `Mp4BoxInspector`, and opens with MediaExtractor. The video track
   codec is `avc1` for the recommended subset, with dimensions equal
   to the plan and even.
2. Duration: the output duration matches the plan duration within one
   frame, as `Mp4ExportValidator` already checks for video.
3. Frame count: the video sample count exactly equals the plan frame
   count, with no dropped or duplicated frames, as the validator
   already checks.
4. Bitrate and profile sanity: the encoder output format reports a
   profile and bitrate within the plan range, and a 720p and a 1080p
   export both complete within a stated time and memory budget on the
   test device tier.
5. Audio present: when an in-range AAC track exists, the output has
   an AAC track with samples, and the validator passes with audio
   expected.
6. Audio sync tolerance: the first audio presentation time after
   shifting is within the agreed tolerance of zero, and the audio end
   is within the agreed tolerance (proposed 100 ms) of the video end.
   A fixture with a known audio start offset must produce the
   expected shifted result.
7. No audio track: a composition without audio exports successfully
   as video-only with the status stating the reason, and the file
   still passes container, duration, and frame-count checks.
8. Non-AAC source: under the passthrough-only recommendation, a
   non-AAC source exports video-only with the unsupported reason and
   no fake audio track. If the user instead chooses transcode, this
   test changes to require audible AAC output, and must be rewritten
   before implementation.
9. Cancel through the foreground service: canceling a codec export
   leaves no final output document, deletes staged files, and reports
   cancellation. This test requires the MP4 foreground-service work
   in section 5 and cannot pass on the current UI job alone.
10. Interruption and recovery through the foreground service: killing
    the export process leaves a detectable staged state, startup
    recovery cleans it, and a user restart from frame 0 produces a
    valid file. Recovery is cleanup plus restart, never resume, and a
    provider-side partial from a kill during the final SAF copy is
    out of scope, matching the PNG model.
11. Encoder absent: on a device or emulator image with no flexible
    YUV420 AVC encoder, the export fails visibly with a stated reason
    and produces no partial final file.
12. Playback spot check: the output decodes fully frame by frame with
    MediaCodec on the emulator, and plays in at least one common
    external player on a physical device. Emulator decoding alone is
    not a playback claim.

## 9. What this record does not do

- It implements no audio transcode, no crop, no letterbox,
  and no foreground service for MP4. The HEVC opt-in IS implemented
  (probe, plan format, exporter MIME, validator, inspector, dialog
  gate); full HEVC encode is proven only on a device with a hardware
  HEVC encoder.
- It does not flip the M5-L8 bullet, which stays unchecked with no
  PASS label.
- It does not approve any threshold: the 100 ms sync tolerance, the
  8 Mbps default, and the 720p/1080p tiers are proposals carried over
  from existing code or stated here for the user to confirm.
- It proves nothing on a physical device and claims no .aep
  compatibility.
