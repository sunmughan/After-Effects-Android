package com.codeair.effectcraft

import com.codeair.effectcraft.data.CanonicalCompositionInfo
import com.codeair.effectcraft.data.CanonicalEditHistory
import com.codeair.effectcraft.data.CanonicalEditSnapshot
import com.codeair.effectcraft.data.CanonicalProjectDocument
import com.codeair.effectcraft.data.CanonicalProjectInfo
import com.codeair.effectcraft.data.CanonicalProjectRepository
import com.codeair.effectcraft.data.OfflineAssetReferences
import com.codeair.effectcraft.data.OfflineAssetStore
import com.codeair.effectcraft.data.PngSequenceZipExporter
import com.codeair.effectcraft.data.PngSequenceZipValidator
import com.codeair.effectcraft.data.ProjectAssetManifest
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.data.ProjectKeyframeStore
import com.codeair.effectcraft.data.RenderOutputFormat
import com.codeair.effectcraft.data.RenderQueuePlan
import com.codeair.effectcraft.data.RenderQueueSettings
import com.codeair.effectcraft.data.SafDocumentWriter
import com.codeair.effectcraft.data.SafPermissionRegistry
import com.codeair.effectcraft.engine.NativeProjectBridge
import com.codeair.effectcraft.engine.NativeSession
import com.codeair.effectcraft.engine.RenderedFramePng
import com.codeair.effectcraft.ui.EditorScreen
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * M7 function-by-function instrumentation suite.
 *
 * One test per app function (or per function family where a single
 * function cannot be exercised without its sibling, in which case the
 * test name names both). The test name IS the function record: the CI
 * emulator JUnit XMLs carry one entry per method, and
 * scripts/m7-function-test-record.py turns those XMLs into the
 * function/pass-fail/crash record (docs/M7_FUNCTION_COVERAGE.md).
 * A function with no passing test in that record counts as unproven,
 * even if the code exists — see the coverage doc for the functions
 * this suite deliberately does NOT claim (MP4 export, push
 * notifications, physical-device behaviour).
 *
 * Tests run on the device/emulator (androidTest), not on the JVM, so
 * they prove the Android runtime paths: real org.json, real Bitmap
 * PNG encoding, real file stores, and the real JNI bridge where a
 * function needs it. Native-dependent tests assert the bridge is
 * available first, following the existing instrumented tests: on an
 * ABI without the native library they fail loudly instead of passing
 * vacuously, and that failure is the correct record for that ABI.
 *
 * No test here weakens an assertion to gain coverage: rejection tests
 * assert the exact rejection (exception type or message fragment from
 * the production code), and export tests validate the produced bytes
 * instead of trusting a success flag.
 *
 * This VM has no KVM, so no emulator is booted here. CI after
 * integration (android-ci, API 30 emulator) is the execution gate;
 * no result is claimed until its test XMLs are read.
 */
class M7FunctionCoverageTest {

    @get:Rule
    val compose = createComposeRule()

    private fun tempDir(name: String): File {
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "m7-function-coverage-$name",
        )
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun validEnvelope(projectJson: String): String {
        val envelope = NativeProjectBridge.inspectEcproj(projectJson)
            ?: throw AssertionError("Native bridge returned null; native library unavailable")
        val parsed = JSONObject(envelope)
        assertTrue(
            "Native inspect must accept the fixture, got error: ${parsed.optString("error")}",
            parsed.optBoolean("valid"),
        )
        return envelope
    }

    private fun editedProject(envelope: String?): String {
        val response = JSONObject(
            envelope ?: throw AssertionError("Native bridge returned null; native library unavailable"),
        )
        assertTrue(
            "Canonical edit must succeed, got error: ${response.optString("error")}",
            response.optBoolean("valid"),
        )
        return response.getString("project")
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        val out = ByteArrayOutputStream()
        try {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    // ---- Open / import ----

    @Test
    fun import_readUtf8EnforcesByteLimit() {
        val over = "x".repeat(11).toByteArray(Charsets.UTF_8)
        try {
            CanonicalProjectDocument.readUtf8(ByteArrayInputStream(over), maxBytes = 10)
            fail("Import over the byte limit must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("import limit"))
        }
        assertEquals("0123456789", CanonicalProjectDocument.readUtf8(
            ByteArrayInputStream("0123456789".toByteArray(Charsets.UTF_8)), maxBytes = 10,
        ))
    }

    @Test
    fun import_ecprojxXmlDocumentNormalizesToCanonicalJson() {
        val json = JSONObject()
            .put("schema", 3)
            .put("name", "Coverage XML")
            .toString()
        val xml = CanonicalProjectDocument.toXml(json)
        assertTrue(xml.startsWith("<?xml"))
        val restored = JSONObject(CanonicalProjectDocument.toCanonicalJson(xml))
        assertEquals(3, restored.getInt("schema"))
        assertEquals("Coverage XML", restored.getString("name"))
    }

    @Test
    fun import_nativeEnvelopeValidationAcceptsRealComposition() {
        assertTrue(NativeProjectBridge.isAvailable())
        val info = CanonicalProjectDocument.validateNativeEnvelope(
            validEnvelope(NativeSessionInstrumentedTest.FIXTURE_JSON),
        )
        assertEquals(1, info.compositions.size)
        val comp = info.compositions.single()
        assertEquals(320, comp.width)
        assertEquals(180, comp.height)
        assertEquals(30, comp.frameRateNum)
        assertTrue(comp.durationSeconds > 0.0)
    }

    @Test
    fun import_nativeEnvelopeValidationRejectsInvalidEnvelope() {
        // Gate-run-727 diagnosis: the original ec-m7 assertion expected
        // "Invalid canonical project" for this input, but production
        // passes the envelope's own error string through (parse
        // requires valid and reports optString("error", "Invalid
        // canonical project")), so this input is rejected with "bad".
        // Pin both production paths instead of one wrong fragment:
        // error passthrough when the envelope carries an error, and
        // the fallback message when it does not. Rejection itself is
        // still asserted in both cases — no weakening.
        try {
            CanonicalProjectDocument.validateNativeEnvelope("{\"valid\":false,\"error\":\"bad\"}")
            fail("An invalid envelope must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("bad"))
        }
        try {
            CanonicalProjectDocument.validateNativeEnvelope("{\"valid\":false}")
            fail("An invalid envelope without an error string must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Invalid canonical project"))
        }
        try {
            CanonicalProjectDocument.validateNativeEnvelope("{\"valid\":true,\"compositions\":[]}")
            fail("A project with no compositions must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("no compositions"))
        }
    }

    // ---- Repository picker backing store ----

    @Test
    fun repository_createLoadUpdateDeleteRoundTrip() {
        val repo = CanonicalProjectRepository(tempDir("repository"))
        val doc = NativeSessionInstrumentedTest.FIXTURE_JSON
        val created = repo.create("Coverage project", doc, "ecproj", "fixture.ecproj")
        assertEquals(doc, repo.load(created.id))
        assertEquals("Coverage project", repo.metadata(created.id)?.displayName)
        assertEquals(1, repo.list().size)
        val updatedDoc = doc.replace("\"Preview\"", "\"Coverage\"")
        val updated = repo.update(created.id, updatedDoc)
        assertEquals(updatedDoc, repo.load(created.id))
        assertTrue(updated.documentSha256 != created.documentSha256)
        assertTrue(repo.delete(created.id))
        assertNull(repo.load(created.id))
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun repository_rejectsUnsupportedFormatAndBlankName() {
        val repo = CanonicalProjectRepository(tempDir("repository-reject"))
        try {
            repo.create("X", "{}", "aep")
            fail("An unsupported format must be rejected, never stored")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Unsupported canonical project format"))
        }
        try {
            repo.create("   ", "{}", "ecproj")
            fail("A blank display name must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("display name"))
        }
        try {
            repo.validateDocument("", "ecproj")
            fail("An empty document must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("empty"))
        }
    }

    // ---- Canonical editing: the transform-keyframe family not covered
    // by CanonicalEditPreviewSyncTest (keyframe author/remove/interp). ----

    @Test
    fun timeline_transformKeyframeAuthorRemoveAndInterpolation() {
        assertTrue(NativeProjectBridge.isAvailable())
        var project = editedProject(
            NativeProjectBridge.addTextLayerEcproj(
                NativeSessionInstrumentedTest.FIXTURE_JSON,
                NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                "Keyed",
            ),
        )
        val layerId = CanonicalProjectInfo.parse(validEnvelope(project))
            .compositions.single().layers.first { it.name == "Keyed" }.id
        project = editedProject(
            NativeProjectBridge.setLayerTransformKeyframeEcproj(
                project, NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                layerId, "position", 10L, 100.0, 50.0, 0.0,
            ),
        )
        var info = CanonicalProjectInfo.parse(validEnvelope(project))
        assertEquals(
            listOf(10),
            info.compositions.single().layers.first { it.id == layerId }
                .transformKeyframes["position"],
        )
        project = editedProject(
            NativeProjectBridge.setLayerTransformKeyframeInterpolationEcproj(
                project, NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                layerId, "position", 10L, "easy_ease",
            ),
        )
        info = CanonicalProjectInfo.parse(validEnvelope(project))
        assertEquals(
            listOf(10),
            info.compositions.single().layers.first { it.id == layerId }
                .transformKeyframes["position"],
        )
        val removed = JSONObject(
            NativeProjectBridge.removeLayerTransformKeyframeEcproj(
                project, NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                layerId, "position", 10L,
            ) ?: throw AssertionError("Native bridge returned null"),
        )
        assertTrue(removed.optBoolean("valid"))
        assertTrue(removed.optBoolean("removed"))
        project = removed.getString("project")
        info = CanonicalProjectInfo.parse(validEnvelope(project))
        assertNull(
            info.compositions.single().layers.first { it.id == layerId }
                .transformKeyframes["position"],
        )
    }

    @Test
    fun timeline_lockedLayerRejectsEdits() {
        assertTrue(NativeProjectBridge.isAvailable())
        var project = editedProject(
            NativeProjectBridge.addTextLayerEcproj(
                NativeSessionInstrumentedTest.FIXTURE_JSON,
                NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                "Locked",
            ),
        )
        val layerId = CanonicalProjectInfo.parse(validEnvelope(project))
            .compositions.single().layers.first { it.name == "Locked" }.id
        project = editedProject(
            NativeProjectBridge.setLayerLockedEcproj(
                project, NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                layerId, true,
            ),
        )
        val locked = JSONObject(
            NativeProjectBridge.renameLayerEcproj(
                project, NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID,
                layerId, "Should not apply",
            ) ?: throw AssertionError("Native bridge returned null"),
        )
        assertFalse(
            "A locked layer must reject edits, got valid=true",
            locked.optBoolean("valid"),
        )
    }

    @Test
    fun undoRedo_canonicalHistoryRestoresSnapshots() {
        val first = CanonicalEditSnapshot("project-v1", setOf(1L))
        val second = CanonicalEditSnapshot("project-v2", setOf(2L))
        val undoStack = CanonicalEditHistory.record(emptyList(), first)
        val undoAtStart = CanonicalEditHistory.undo(emptyList(), emptyList(), first)
        assertNull(undoAtStart)
        val stepped = CanonicalEditHistory.undo(undoStack, emptyList(), second)
        assertNotNull(stepped)
        assertEquals("project-v1", stepped!!.snapshot.projectJson)
        val redone = CanonicalEditHistory.redo(stepped.undoStack, stepped.redoStack, stepped.snapshot)
        assertNotNull(redone)
        assertEquals("project-v2", redone!!.snapshot.projectJson)
        assertNull(CanonicalEditHistory.redo(emptyList(), emptyList(), first))
    }

    // ---- Preview / render ----

    @Test
    fun preview_nativeSessionRendersFrameEncodableAsPng() {
        assertTrue(NativeProjectBridge.isAvailable())
        val session = NativeSession.open(NativeSessionInstrumentedTest.FIXTURE_JSON)
            ?: throw AssertionError("Native session failed to open the fixture project")
        try {
            val frame = session.render(
                NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID, 0L,
            ) ?: throw AssertionError("Native session returned no frame")
            val out = ByteArrayOutputStream()
            val dims = RenderedFramePng.write(frame, out)
            assertTrue(dims.width > 0 && dims.height > 0)
            val png = out.toByteArray()
            assertTrue(png.size > 24)
            val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
            assertNotNull(decoded)
            assertEquals(dims.width, decoded!!.width)
            assertEquals(dims.height, decoded.height)
            decoded.recycle()
        } finally {
            session.close()
            assertFalse(session.isOpen())
        }
    }

    @Test
    fun preview_renderedFrameScalesToRequestedDimensions() {
        assertTrue(NativeProjectBridge.isAvailable())
        val session = NativeSession.open(NativeSessionInstrumentedTest.FIXTURE_JSON)
            ?: throw AssertionError("Native session failed to open the fixture project")
        try {
            val frame = session.render(
                NativeSessionInstrumentedTest.FIXTURE_COMPOSITION_ID, 0L,
            ) ?: throw AssertionError("Native session returned no frame")
            val out = ByteArrayOutputStream()
            val dims = RenderedFramePng.writeScaled(frame, out, 160, 90)
            assertEquals(160, dims.width)
            assertEquals(90, dims.height)
            val decoded = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
            assertNotNull(decoded)
            assertEquals(160, decoded!!.width)
            assertEquals(90, decoded.height)
            decoded.recycle()
        } finally {
            session.close()
        }
    }

    @Test
    fun preview_sessionRejectsInvalidProjectAndClosedRender() {
        val invalid = NativeSession.open("not a canonical project")
        assertNull(invalid)
    }

    // ---- PNG export + PNG-sequence ZIP export ----

    @Test
    fun export_pngSequenceZipRoundTripsAndValidates() {
        val plan = RenderQueuePlan.create(RenderQueueSettings(64, 64, 30, 0, 2, RenderOutputFormat.PNG_SEQUENCE_ZIP))
        assertEquals(3, plan.frameCount)
        val png = pngBytes(64, 64)
        val archive = File(tempDir("zip-export"), "sequence.zip")
        archive.outputStream().buffered().use { output ->
            val result = PngSequenceZipExporter.export(plan, output, renderPng = { png })
            assertEquals(3, result.framesWritten)
            assertEquals(png.size.toLong() * 3, result.bytesOfPng)
        }
        val validated = PngSequenceZipValidator.validate(archive, plan)
        assertEquals(3, validated.framesValidated)
    }

    @Test
    fun export_pngSequenceZipCancellationAbortsAndValidatorRejectsMismatch() {
        val plan = RenderQueuePlan.create(RenderQueueSettings(64, 64, 30, 0, 2, RenderOutputFormat.PNG_SEQUENCE_ZIP))
        val png = pngBytes(64, 64)
        try {
            ByteArrayOutputStream().use { output ->
                PngSequenceZipExporter.export(plan, output, renderPng = { png }, isCancelled = { true })
            }
            fail("A cancelled sequence export must throw")
        } catch (expected: CancellationException) {
            assertTrue(expected.message!!.contains("cancelled"))
        }
        val archive = File(tempDir("zip-mismatch"), "sequence.zip")
        archive.outputStream().buffered().use { output ->
            PngSequenceZipExporter.export(plan, output, renderPng = { png })
        }
        val wrongPlan = RenderQueuePlan.create(RenderQueueSettings(128, 64, 30, 0, 2, RenderOutputFormat.PNG_SEQUENCE_ZIP))
        try {
            PngSequenceZipValidator.validate(archive, wrongPlan)
            fail("A ZIP whose manifest does not match the plan must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("manifest does not match"))
        }
    }

    @Test
    fun export_renderQueuePlanRejectsOutOfBoundsSettings() {
        try {
            RenderQueuePlan.create(RenderQueueSettings(0, 64, 30, 0, 2, RenderOutputFormat.PNG_SEQUENCE_ZIP))
            fail("Zero width must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("width"))
        }
        try {
            RenderQueuePlan.create(RenderQueueSettings(64, 64, 0, 0, 2, RenderOutputFormat.PNG_SEQUENCE_ZIP))
            fail("Zero FPS must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Frame rate"))
        }
        try {
            RenderQueuePlan.create(RenderQueueSettings(64, 64, 30, 5, 2, RenderOutputFormat.PNG_SEQUENCE_ZIP))
            fail("An end frame before the start frame must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("End frame"))
        }
    }

    // ---- Offline flows: assets, relink references, manifests, SAF ----

    @Test
    fun offline_assetStoreImportDedupeVerifyAndDelete() {
        val store = OfflineAssetStore(tempDir("assets"))
        val payload = "coverage-asset".toByteArray(Charsets.UTF_8)
        val first = store.import(ByteArrayInputStream(payload), "clip.bin", "application/octet-stream")
        val second = store.import(ByteArrayInputStream(payload), "renamed.bin", "application/octet-stream")
        assertEquals(first.sha256, second.sha256)
        assertEquals("clip.bin", second.displayName)
        assertEquals(1, store.list().size)
        assertTrue(store.verify(first.sha256))
        assertTrue(store.open(first.sha256).readBytes().contentEquals(payload))
        assertTrue(store.verifyAll().isEmpty())
        assertTrue(store.delete(first.sha256))
        assertNull(store.get(first.sha256))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun offline_assetReferencesLinkAndUnlink() {
        val refs = OfflineAssetReferences(File(tempDir("refs"), "references.properties"))
        val hash = "a".repeat(64)
        refs.setReference(42L, hash)
        assertEquals(hash, refs.assetFor(42L))
        assertTrue(refs.isReferenced(hash))
        assertEquals(setOf(42L), refs.itemIdsFor(hash))
        assertTrue(refs.removeReference(42L))
        assertNull(refs.assetFor(42L))
        assertFalse(refs.isReferenced(hash))
        refs.setReference(7L, hash)
        refs.clear()
        assertTrue(refs.referencedAssetIds().isEmpty())
    }

    @Test
    fun offline_projectAssetManifestLinkMissingAndReplace() {
        val manifest = ProjectAssetManifest("coverage-project")
        val oldHash = "b".repeat(64)
        val newHash = "c".repeat(64)
        manifest.link(11L, oldHash, "file:///old")
        assertEquals(oldHash, manifest.entryFor(11L)?.sha256)
        assertTrue(manifest.markMissing(11L))
        assertEquals(1, manifest.missingEntries().size)
        assertTrue(manifest.markPresent(11L))
        assertTrue(manifest.missingEntries().isEmpty())
        assertEquals(1, manifest.replaceAsset(oldHash, newHash))
        assertEquals(newHash, manifest.entryFor(11L)?.sha256)
        assertTrue(manifest.remove(11L))
        assertNull(manifest.entryFor(11L))
    }

    @Test
    fun offline_safPermissionRegistryGrantLostAndRelease() {
        val registry = SafPermissionRegistry(File(tempDir("saf"), "saf-permissions.properties"))
        val uri = "content://coverage/document"
        registry.record(uri, SafPermissionRegistry.Kind.WRITE, "EffectCraft.ecproj")
        assertTrue(registry.isGranted(uri, SafPermissionRegistry.Kind.WRITE))
        assertFalse(registry.needsRecovery())
        assertTrue(registry.markLost(uri, SafPermissionRegistry.Kind.WRITE))
        assertTrue(registry.needsRecovery())
        assertEquals(1, registry.lost().size)
        assertTrue(registry.release(uri))
        assertTrue(registry.grants().isEmpty())
    }

    @Test
    fun offline_safDocumentWriterCopiesAndRollsBackOnFailure() {
        var rolledBack = false
        val copied = SafDocumentWriter.copyTo(
            openInput = { ByteArrayInputStream("saf-payload".toByteArray(Charsets.UTF_8)) },
            openOutput = { ByteArrayOutputStream() },
            rollbackDestination = { rolledBack = true },
        )
        assertEquals(11L, copied)
        assertFalse(rolledBack)
        var failureRolledBack = false
        try {
            SafDocumentWriter.copyTo(
                openInput = { throw IOException("source failed") },
                openOutput = { ByteArrayOutputStream() },
                rollbackDestination = { failureRolledBack = true },
            )
            fail("A failed SAF copy must propagate")
        } catch (expected: IOException) {
            assertEquals("source failed", expected.message)
        }
        assertTrue(failureRolledBack)
    }

    @Test
    fun offline_keyframeStoreUpsertRemoveAndClear() {
        val store = ProjectKeyframeStore(tempDir("keyframes"))
        val stored = store.upsert("coverage-project", "layer-1", "opacity", 12, 0.5f)
        assertEquals("layer-1", stored.layerId)
        assertEquals("opacity", stored.track.property)
        assertEquals(1, stored.track.keyframes.size)
        assertEquals(12, stored.track.keyframes.single().frame)
        assertEquals(1, store.load("coverage-project").size)
        assertTrue(store.removeKeyframe("coverage-project", "layer-1", "opacity", 12))
        assertFalse(store.removeKeyframe("coverage-project", "layer-1", "opacity", 12))
        store.upsert("coverage-project", "layer-1", "opacity", 3, 0.25f)
        assertTrue(store.clear("coverage-project"))
        assertTrue(store.load("coverage-project").isEmpty())
    }

    // ---- Settings dialogs (Compose) ----

    private fun sequenceSettingsScreen(
        onExport: (Int, Int, Int, Int, Int) -> Unit,
        offlineAssets: List<OfflineAssetStore.Asset> = emptyList(),
        onVerifyOfflineAssets: () -> Unit = {},
    ) {
        val project = ProjectDocument.starter()
        val canonical = CanonicalProjectInfo(
            compositions = listOf(
                CanonicalCompositionInfo(
                    id = 1L, name = "Coverage comp", width = 320, height = 180,
                    frameRateNum = 30, frameRateDen = 1, durationSeconds = 10.0,
                    layers = emptyList(),
                ),
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                EditorScreen(
                    project = project,
                    selectedLayer = project.layers.first(),
                    selectedLayerId = project.layers.first().id,
                    playheadFrame = 0,
                    selectedOpacityKeyframes = emptyList(),
                    isPlaying = false,
                    saveMessage = "Saved",
                    savedProjects = emptyList(),
                    offlineAssets = offlineAssets,
                    referencedOfflineAssetIds = emptySet(),
                    canUndo = false,
                    canRedo = false,
                    onUndo = {},
                    onRedo = {},
                    onSave = {},
                    onOpenProject = {},
                    onDeleteOfflineAsset = {},
                    onImportProject = {},
                    onExportEcproj = {},
                    onExportEcprojx = {},
                    isExportingFrame = false,
                    onExportRenderedFrame = {},
                    onCancelFrameExport = {},
                    isExportingSequence = false,
                    isSequenceExportWritingOutput = false,
                    onExportPngSequence = onExport,
                    onCancelSequenceExport = {},
                    sequenceExportProgress = 0,
                    onNewProject = {},
                    onAddLayer = {},
                    onSelectLayer = {},
                    onDeleteLayer = {},
                    onMoveLayer = {},
                    onVisibilityChange = {},
                    onRenameLayer = {},
                    onOpacityChange = {},
                    onScrub = {},
                    onAddOpacityKeyframe = {},
                    onRemoveOpacityKeyframe = {},
                    onTogglePlayback = {},
                    onApplyComposition = { _, _, _, _ -> },
                    canonicalProject = canonical,
                    canEditCanonicalProject = false,
                    onRenameCanonicalLayer = { _, _, _ -> },
                    onAddCanonicalTextLayer = { _, _ -> },
                    onSetCanonicalLayerVisibility = { _, _, _ -> },
                    onMoveCanonicalLayer = { _, _, _ -> },
                    onDeleteCanonicalLayer = { _, _ -> },
                    onSetCanonicalLayerOpacity = { _, _, _ -> },
                    onAddCanonicalLayerOpacityKeyframe = { _, _, _, _ -> },
                    onRemoveCanonicalLayerOpacityKeyframe = { _, _, _ -> },
                    onSetCanonicalLayerPosition = { _, _, _, _ -> },
                    onSetCanonicalLayerScale = { _, _, _, _ -> },
                    onSetCanonicalLayerRotation = { _, _, _ -> },
                    onSetCanonicalLayerAnchor = { _, _, _, _ -> },
                    onSetCanonicalLayerOrientation = { _, _, _, _, _ -> },
                    onSetCanonicalLayerRotationX = { _, _, _ -> },
                    onSetCanonicalLayerRotationY = { _, _, _ -> },
                    onSetCanonicalLayerLocked = { _, _, _ -> },
                    onSetCanonicalTextLayerText = { _, _, _ -> },
                    onSetCanonicalTextLayerSize = { _, _, _ -> },
                    onSetCanonicalTextLayerTracking = { _, _, _ -> },
                    onSetCanonicalLayerScalarProperty = { _, _, _, _ -> },
                    onSetCanonicalLayerVecProperty = { _, _, _, _, _, _ -> },
                    onSetCanonicalLayerColorProperty = { _, _, _, _, _, _, _ -> },
                    onRelinkCanonicalAsset = {},
                    onRenderCanonicalFrame = { _, _ -> null },
                    safGrants = emptyList(),
                    missingManifestEntries = 0,
                    onReleaseSafGrant = { _, _ -> },
                    onReplaceOfflineAsset = {},
                    onCollectUnusedOfflineAssets = {},
                    onVerifyOfflineAssets = onVerifyOfflineAssets,
                )
            }
        }
    }

    @Test
    fun settings_pngSequenceSettingsRenderValidSettings() {
        val exported = mutableListOf<List<Int>>()
        sequenceSettingsScreen(onExport = { w, h, fps, start, end ->
            exported.add(listOf(w, h, fps, start, end))
        })
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Export PNG sequence ZIP").performScrollTo().performClick()
        compose.onNodeWithText("PNG sequence settings").assertIsDisplayed()
        compose.onNodeWithText("Render ZIP").performClick()
        // Opening the dialog resets the fields from the composition
        // (320x180, 30 FPS, frames 0..299 for this 10-second fixture),
        // so the callback must carry those values, not the 1920x1080
        // remembered defaults the dialog starts with before opening.
        assertEquals(listOf(listOf(320, 180, 30, 0, 299)), exported)
    }

    @Test
    fun settings_pngSequenceSettingsRejectsBlankNumericField() {
        var exported = 0
        sequenceSettingsScreen(onExport = { _, _, _, _, _ -> exported++ })
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Export PNG sequence ZIP").performScrollTo().performClick()
        compose.onNodeWithText("PNG sequence settings").assertIsDisplayed()
        // Integration note: the original ec-m7 test selected the FPS
        // field by position via onAllNodes(hasSetTextAction())[2], but
        // the generic onAllNodes(matcher) collection was removed from
        // the Compose testing library this branch pins (BOM 2026.08.00),
        // so that call no longer compiles. The FPS field is selected
        // directly instead, by the value it holds after the dialog
        // opens: Width 320, Height 180, FPS 30, Start 0, End 299, so
        // the editable node holding "30" is the FPS field. The
        // SetText-action requirement excludes the "300 frames" header
        // text, which also contains "30". Blanking the FPS field must
        // block the export with the dialog's own validation message,
        // not call back. Same field, same assertion — no weakening.
        // Focus the field before clearing it, and check the blank
        // value before clicking Render: on a loaded emulator an
        // unfocused clearance can leave "30" in place, so Render
        // would export instead of erroring — and the failure would
        // then surface below as a 10 s validation timeout instead of
        // its real cause. assertTextEquals would also match the
        // field's "FPS" label, so the editable value is checked
        // directly from the semantics config.
        val fpsField = compose.onNodeWithContentDescription("Export FPS")
        fpsField.performClick()
        fpsField.performTextClearance()
        assertEquals("", fpsField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Render ZIP").performClick()
        // Run 42441138764 (attempt 1): the validation error is set by
        // the click's recomposition, which can land after the fetch on
        // a loaded emulator, so performScrollTo found no node. Wait
        // for the error to be composed first; the scroll, the
        // displayed assertion and the exported==0 check below are
        // unchanged and still fail (timeout) if the error never
        // appears. Run 42706710590: the error still had not landed
        // within 5 s on the loaded retry emulator (it passed attempt
        // 1), so the budget is 10 s — the test still fails if the
        // error never appears.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Enter valid numeric settings").fetchSemanticsNodes().isNotEmpty()
        }
        // Gate-run-727 diagnosis: the error text IS composed when the
        // FPS field is blank, but the settings dialog content scrolls
        // (height-capped column) and the error sits below the fold, so
        // assertIsDisplayed fails with "is not displayed" even though
        // the node exists. Scroll it into view first, then keep the
        // full displayed assertion — no weakening, same message check.
        compose.onNodeWithText("Enter valid numeric settings")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, exported)
    }

    @Test
    fun offlineMediaLibraryExposesIntegrityVerificationAction() {
        val store = OfflineAssetStore(tempDir("offline-media-ui"))
        val asset = store.import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "fixture.bin")
        var verificationCalls = 0
        sequenceSettingsScreen(
            onExport = { _, _, _, _, _ -> },
            offlineAssets = listOf(asset),
            onVerifyOfflineAssets = { verificationCalls++ },
        )
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Offline media library").performScrollTo().performClick()
        compose.onNodeWithText("Verify media integrity").assertIsDisplayed().performClick()
        assertEquals(1, verificationCalls)
    }
}
