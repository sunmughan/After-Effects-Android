# M7 — Function-by-function emulator test coverage

> Rule (coordinator, 2026-11-04): every app function is covered one by
> one; each test records function, pass/fail, and crash evidence where
> applicable; a function with no test evidence counts as unproven.
> This document is the registry that makes that rule checkable. It
> does not itself claim any result: a row is proven only when the
> named test appears as PASS in a test record produced from real CI
> emulator JUnit XMLs (see "How to produce the record" below).
>
> Scope guards: the decided package id is `com.codeair.effectcraft`
> (see `docs/M7_RELEASE_BUILD.md` §1); the `ec-pkg` rename is
> integrated on the feature branch and this M7 integration ports the
> suite and the registry to `com.codeair.effectcraft` throughout.
> Push notifications (FCM) are owned by a separate workstream and are
> not duplicated here; they appear below only as explicitly out of
> scope for this suite, not as covered by it.

## How to produce the record (after integration, from CI only)

1. Run `android-ci` on the integrated branch (API 30 emulator job).
   This VM has no KVM, so no emulator is booted here and no local
   emulator result is claimed in this document.
2. Download the `effectcraft-android-test-results` artifact for that
   run (it carries the actual JUnit XMLs).
3. Run:
   ```bash
   python3 scripts/m7-function-test-record.py <downloaded-xml-dir> > m7-function-test-record.md
   ```
   The output is one row per testcase: class, method (the function),
   PASS / FAIL / ERROR / SKIPPED, and the failure/error message +
   text as crash evidence (truncated at 600 chars). The script exits
   1 if any testcase failed or errored.
4. Cross-check the record against the tables below: every row marked
   "Test" must appear as PASS for its function to count as proven.
   A function whose test is absent, skipped, failed, or errored
   counts as UNPROVEN, full stop. Do not weaken an assertion to turn
   a failure into a pass; fix the function or record it as unproven.

## 1. Launch, drafts, offline workspace

| Function | Test evidence |
|---|---|
| App launches offline, workspace + timeline visible, draft preview labelled unrendered | `EditorSmokeTest.editorLaunchesWithOfflineCompositionWorkspace` |
| New composition, add layer, save draft, reopen offline project, layer stack restored | `EditorSmokeTest.savedOfflineProjectsCanBeOpenedWithoutNetwork`, `EditorSmokeTest.editorSaveButtonPersistsTheCurrentOfflineLayerStack` |
| Draft store round-trip, interrupted-write recovery, atomic temp promotion/discarding | `DraftProjectStoreRecoveryInstrumentedTest.*` (4 tests) |
| Draft layer add / reorder / delete / rename / visibility / opacity via inspector, with undo/redo | `EditorSmokeTest.*` (inspector + layer-mutation tests) |
| Composition settings (width/height/FPS/duration) apply and participate in undo | `EditorSmokeTest.compositionSettingsApplyAndParticipateInUndo` |
| Timeline playhead scrub + transport toggle, selection updates inspector | `EditorSmokeTest.timelinePlayheadScrubsAndTransportToggles`, `timelineSelectionUpdatesInspectorProperties` |
| Adaptive phone/tablet layout (inspector + preview side by side on wide window) | `AdaptiveWorkspaceSmokeTest.wideWindowKeepsInspectorAndPreviewSideBySide` |
| Single-document cache preserves source bytes | `EditorSmokeTest.canonicalEcprojCachePreservesSourceBytes` |

## 2. Open / import (canonical projects)

| Function | Test evidence |
|---|---|
| Import byte limit enforced (`readUtf8`) | `M7FunctionCoverageTest.import_readUtf8EnforcesByteLimit` |
| `.ecprojx` XML normalizes to canonical JSON / XML round-trip of typed values | `M7FunctionCoverageTest.import_ecprojxXmlDocumentNormalizesToCanonicalJson`, `EditorSmokeTest.effectCraftEcprojxXmlRoundTripsTypedProjectValues` |
| Native envelope validation accepts a real composition (positive dims/FPS/duration) | `M7FunctionCoverageTest.import_nativeEnvelopeValidationAcceptsRealComposition` |
| Native envelope validation rejects invalid / composition-less envelopes | `M7FunctionCoverageTest.import_nativeEnvelopeValidationRejectsInvalidEnvelope` |
| Native bridge loads and invokes the canonical parser on emulator | `NativeSessionInstrumentedTest.nativeBridgeLoadsAndInvokesCanonicalParserOnEmulator` |
| Repository create / load / update / delete round-trip | `M7FunctionCoverageTest.repository_createLoadUpdateDeleteRoundTrip` |
| Repository rejects unsupported format, blank name, empty document | `M7FunctionCoverageTest.repository_rejectsUnsupportedFormatAndBlankName` |
| Repository picker lists entries, marks active entry Current, routes chosen id to switch | `RepositoryPickerTest.repositoryPickerListsEntriesAndSwitchesProject` |
| System SAF picker UI itself (open/save document chooser) | NONE — UNPROVEN as an emulator test: the picker is a system UI that instrumentation does not drive. Its app-side consequences (bounded read, native validation, SAF copy + rollback, grant registry) are tested in §5; a picker-flow pass must come from a manual/device run, not from this suite. |

## 3. Canonical editing (one row per bridge function)

All rows in this section are proven through `NativeProjectBridge`
(the exact path the editor UI uses) plus re-inspection through
`CanonicalProjectInfo` and, where the edit changes pixels, a session
render — the pattern of `CanonicalEditPreviewSyncTest`. "Session"
below means the test also renders from the edited project in a
`NativeSession`.

| Function | Test evidence |
|---|---|
| Rename layer (no pixel change) | `CanonicalEditPreviewSyncTest.renameEditUpdatesCanonicalDataAndPreviewSessionWithoutChangingPixels` |
| Add text layer | Fixture step of `CanonicalEditPreviewSyncTest.baseFixture` + `M7FunctionCoverageTest` keyframe/lock tests |
| Position / Scale / Rotation edits (pixels) | `CanonicalEditPreviewSyncTest.position/scale/rotationEditUpdatesCanonicalDataAndPreviewPixels` |
| Anchor / Orientation / RotationX / RotationY edits (session) | `CanonicalEditPreviewSyncTest.anchor/orientation/rotationX/rotationYEditUpdatesCanonicalDataAndPreviewSession` |
| Text source / size / tracking edits (pixels) | `CanonicalEditPreviewSyncTest.textSource/textSize/textTrackingEditUpdatesCanonicalDataAndPreviewPixels` |
| Shape scalar / vector parameter edits (session) | `CanonicalEditPreviewSyncTest.shapeScalar/shapeVectorEditUpdatesCanonicalDataAndPreviewSession` |
| Effect scalar / vector parameter edits (session) | `CanonicalEditPreviewSyncTest.effectScalar/effectVectorEditUpdatesCanonicalDataAndPreviewSession` |
| Opacity edit (pixels) | `CanonicalEditPreviewSyncTest.opacityEditUpdatesCanonicalDataAndPreviewPixels` |
| Opacity keyframe add / remove / interpolate / hold-past-last-key / last-key removal preserves static opacity | `CanonicalEditPreviewSyncTest.opacityKeyframeAdd/Remove/Interpolates/HoldsPastLastKey`, `lastOpacityKeyframeRemovedPreservesStaticOpacityInCanonicalDataAndPreview` |
| Opacity keyframes authored at playhead, persisted per project | `EditorSmokeTest.opacityKeyframesCanBeAuthoredAtPlayheadAndPersistPerProject`, `AdaptiveWorkspaceSmokeTest.canonicalOpacityKeyframeControlsAuthorAtPreviewFrame` |
| Transform keyframe author / remove / interpolation preset | `M7FunctionCoverageTest.timeline_transformKeyframeAuthorRemoveAndInterpolation` |
| Interpolation preset routing from the inspector UI to the bridge callback | `CanonicalSelectionEditorTest.tapLongPressGestureUndoRedoAndReparseKeepCanonicalSelection` (captures the `onSetCanonicalLayerTransformInterpolation` callback arguments, including the `easy_ease` preset) |
| Visibility edit (pixels) | `CanonicalEditPreviewSyncTest.visibilityEditUpdatesCanonicalDataAndPreviewPixels` |
| Move (reorder) layer | `CanonicalEditPreviewSyncTest.moveEditUpdatesCanonicalDataAndPreviewSession` |
| Delete layer (pixels) | `CanonicalEditPreviewSyncTest.deleteEditUpdatesCanonicalDataAndPreviewPixels` |
| Lock blocks edits; unlock restores editing; locked layer disables edit controls | `CanonicalEditPreviewSyncTest.lockEditBlocksEditsAndUnlockRestoresCanonicalDataAndPreview`, `M7FunctionCoverageTest.timeline_lockedLayerRejectsEdits`, `CanonicalSelectionEditorTest.lockedCanonicalLayerDisablesItsEditControls`, `canonicalAnchor3dRotationTextShapeEffectAndLockControlsTargetSelectedLayer` |
| Canonical undo / redo (pixels) | `CanonicalEditPreviewSyncTest.undo/redoRestoresCanonicalDataAndPreviewPixels`, `M7FunctionCoverageTest.undoRedo_canonicalHistoryRestoresSnapshots` (history unit behaviour) |
| Preview drag gesture updates canonical data + preview pixels; tap/long-press gesture, undo/redo, reparse keep selection | `CanonicalEditPreviewSyncTest.previewDragGestureUpdatesCanonicalDataAndPreviewPixels`, `CanonicalSelectionEditorTest.tapLongPressGestureUndoRedoAndReparseKeepCanonicalSelection` |
| Batch multi-selection edit (pixels); selection change survives reparse and preview renders selected project | `CanonicalEditPreviewSyncTest.batchMultiSelectionEditUpdatesCanonicalDataAndPreviewPixels`, `selectionChangeSurvivesReparseAndPreviewRendersSelectedProject` |
| Footage relink (`relinkFootageEcproj`) end-to-end success | `M2DeviceFlowsInstrumentedTest.safCopyNativeRelinkExport` uses a real pinned-upstream canonical fixture containing missing footage item 2, imports and verifies local bytes, relinks through JNI, asserts `missing=false` and the new path, persists the project+manifest, verifies invalid-ID/blank-path rejection leaves state unchanged, then exports/re-inspects the path. The native leg is ARM64-gated (`assumeNativeArm64`); x86_64 CI emulator skips that leg and is not physical-device proof. |

## 4. Preview / render, PNG export, PNG-sequence ZIP export

| Function | Test evidence |
|---|---|
| Native session open / render / resize / cancel / close | `NativeSessionInstrumentedTest.sessionRendersResizesCancelsAndCloses` |
| Session rejects invalid project; open rejects invalid input | `NativeSessionInstrumentedTest.sessionOpenRejectsInvalidProject`, `M7FunctionCoverageTest.preview_sessionRejectsInvalidProjectAndClosedRender` |
| Session backend status + recovery after simulated GPU loss | `NativeSessionInstrumentedTest.sessionReportsBackendAndRecoversAfterSimulatedLoss` |
| Rendered frame encodes as PNG with correct dimensions (decode-back check) | `M7FunctionCoverageTest.preview_nativeSessionRendersFrameEncodableAsPng`, `NativeSessionInstrumentedTest.pngExportPreservesRgbaPixelsAndDimensions` |
| Rendered frame scales to requested dimensions | `M7FunctionCoverageTest.preview_renderedFrameScalesToRequestedDimensions`, `NativeSessionInstrumentedTest.pngSequenceExportScalesToRequestedDimensions` |
| PNG export rejects malformed / oversized frames before bitmap allocation | `NativeSessionInstrumentedTest.pngExportRejectsMalformedAndOversizedFramesBeforeBitmapAllocation` |
| PNG-sequence ZIP export round-trips and validates (manifest + frames) | `M7FunctionCoverageTest.export_pngSequenceZipRoundTripsAndValidates` |
| PNG-sequence ZIP cancellation aborts; validator rejects manifest/plan mismatch | `M7FunctionCoverageTest.export_pngSequenceZipCancellationAbortsAndValidatorRejectsMismatch` |
| Render-queue plan validation rejects out-of-bounds settings | `M7FunctionCoverageTest.export_renderQueuePlanRejectsOutOfBoundsSettings` |
| Sequence settings dialog: valid settings call back with composition-derived values | `M7FunctionCoverageTest.settings_pngSequenceSettingsRenderValidSettings` |
| Sequence settings dialog: blank numeric field blocks export with validation message | `M7FunctionCoverageTest.settings_pngSequenceSettingsRejectsBlankNumericField` |
| MP4 / video export | M5 landed video export after this registry was written: `Mp4ExportWorkerInstrumentedTest` (staged video-only export, junk-audio video-only export, AAC passthrough) + `Mp4SequenceExporterInstrumentedTest.flexibleYuv420EncoderProducesReadableH264Mp4`. Proven by those tests on the gate run, not by the M7 suite itself; the M7 suite deliberately contains no MP4 test that could be misread as MP4 coverage. |
| Audio preview / export / sync | NONE — UNPROVEN, not implemented (see support matrix). |

## 5. Offline flows: assets, manifests, SAF, keyframes

| Function | Test evidence |
|---|---|
| Offline media library opens and explains empty state | `EditorSmokeTest.offlineMediaLibraryOpensAndExplainsEmptyState` |
| Asset store import / dedupe-keeps-first / verify / open / delete | `M7FunctionCoverageTest.offline_assetStoreImportDedupeVerifyAndDelete` |
| Asset references link / unlink / clear | `M7FunctionCoverageTest.offline_assetReferencesLinkAndUnlink` |
| Project asset manifest link / missing / present / replace / remove | `M7FunctionCoverageTest.offline_projectAssetManifestLinkMissingAndReplace` |
| SAF permission registry grant / lost / recovery-needed / release | `M7FunctionCoverageTest.offline_safPermissionRegistryGrantLostAndRelease` |
| SAF document writer copy + rollback on failure (no truncated output kept as success) | `M7FunctionCoverageTest.offline_safDocumentWriterCopiesAndRollsBackOnFailure` |
| Keyframe store upsert / remove / clear per project | `M7FunctionCoverageTest.offline_keyframeStoreUpsertRemoveAndClear` |
| Stale sequence-export cleanup, sequence asset import | Existing JVM tests cover these helpers; no new instrumented test is added here because they operate on plain files with no Android API. If the coordinator requires device evidence for them, they must be promoted to androidTest explicitly — until then they count as JVM-proven only, not emulator-proven. |

## 6. Explicitly out of scope for this suite (not counted as covered, not claimed)

| Function | Status |
|---|---|
| Push notifications (FCM) | Separate workstream owns it. FCM code now exists in the app (`push` package) with its own unit tests (`PushMessageMapperTest`); this emulator suite makes no FCM claim. |
| Physical-device behaviour (ARM64 GPU/Surface, thermal, TalkBack manual, real SAF picker, real file-provider quirks) | Physical-device gate in `docs/M7_RELEASE_GATE_CHECKLIST.md` §B, not emulator evidence. |
| Pinch/twist gestures, Bezier graph editing, drag-to-key, full effect/3D parity, audio | Not implemented; see `docs/M7_SUPPORT_MATRIX.md` §3. No test in this suite claims them. (Video export is implemented since M5; see the MP4 row in §4.) |
| `.aep` / `.efkproj` import | Unsupported by design; rejection is guarded by format validation (§2), not by a compatibility test. |

## 7. Record status (fill in only from a real CI run)

- CI run: `[android-ci run URL + number, or NOT RUN]`
- Test record file: `[m7-function-test-record.md produced by the script above, or NOT PRODUCED]`
- Totals from that record: PASS `[n]` / FAIL `[n]` / ERROR `[n]` / SKIPPED `[n]`
- Functions still UNPROVEN after that run: `[list from §2–§5 NONE rows plus any failed/errored/skipped test, or NONE]`
- M7 function-coverage verdict: `[NOT READY until every §1–§5 "Test" row is PASS and every NONE row is either resolved by a new test or explicitly waived by the coordinator]`
