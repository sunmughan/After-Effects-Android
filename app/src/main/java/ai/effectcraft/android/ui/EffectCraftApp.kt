package ai.effectcraft.android.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import ai.effectcraft.android.data.CanonicalProjectInfo
import ai.effectcraft.android.data.CanonicalProjectDocument
import ai.effectcraft.android.data.CanonicalProjectRepository
import ai.effectcraft.android.data.DraftProjectStore
import ai.effectcraft.android.data.EcprojDocumentStore
import ai.effectcraft.android.data.OfflineAssetStore
import ai.effectcraft.android.data.OfflineAssetReferences
import ai.effectcraft.android.data.ProjectAssetManifestStore
import ai.effectcraft.android.data.SafPermissionRegistry
import ai.effectcraft.android.engine.NativeProjectBridge
import ai.effectcraft.android.engine.NativeSession
import ai.effectcraft.android.engine.RenderedFramePng
import ai.effectcraft.android.data.LayerDocument
import ai.effectcraft.android.data.ProjectEditorActions
import ai.effectcraft.android.data.ProjectDocument
import ai.effectcraft.android.data.ProjectFileFormats
import ai.effectcraft.android.data.ProjectKeyframeStore
import ai.effectcraft.android.data.PngSequenceZipExporter
import ai.effectcraft.android.data.PngSequenceZipValidator
import ai.effectcraft.android.data.RenderQueuePlan
import ai.effectcraft.android.data.RenderQueueSettings
import ai.effectcraft.android.data.StaleSequenceExportCleanup
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope

private data class EditorHistorySnapshot(
    val project: ProjectDocument,
    val selectedLayerId: String,
    val playheadFrame: Int
)

@Composable
fun EffectCraftApp(projectStore: DraftProjectStore) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var frameExportJob by remember { mutableStateOf<Job?>(null) }
    var frameExportInProgress by remember { mutableStateOf(false) }
    var pendingFrameExport by remember { mutableIntStateOf(0) }
    var sequenceExportJob by remember { mutableStateOf<Job?>(null) }
    var sequenceExportInProgress by remember { mutableStateOf(false) }
    var sequenceExportWritingOutput by remember { mutableStateOf(false) }
    var sequenceExportProgress by remember { mutableIntStateOf(0) }
    var pendingSequenceSettings by remember { mutableStateOf<RenderQueueSettings?>(null) }
    var pendingSequenceCompositionId by remember { mutableStateOf<Long?>(null) }
    val ecprojStore = remember(context) { EcprojDocumentStore(context) }
    val offlineAssetStore = remember(context) { OfflineAssetStore(File(context.filesDir, "media-library")) }
    val offlineAssetReferences = remember(context) { OfflineAssetReferences(File(context.filesDir, "media-library/references.properties")) }
    val projectKeyframeStore = remember(context) { ProjectKeyframeStore(File(context.filesDir, "keyframes")) }
    val manifestStore = remember(context) { ProjectAssetManifestStore(File(context.filesDir, "asset-manifests")) }
    val projectRepository = remember(context) { CanonicalProjectRepository(File(context.filesDir, "canonical-projects-repo")) }
    val safRegistry = remember(context) { SafPermissionRegistry(File(context.filesDir, "saf-permissions.properties")) }
    var importedEcproj by remember { mutableStateOf(ecprojStore.load()) }
    var repositoryProjectId by remember { mutableStateOf<String?>(null) }
    var safGrants by remember(safRegistry) { mutableStateOf(safRegistry.grants()) }
    var manifestMissingCount by remember { mutableStateOf(0) }
    var pendingRelinkItemId by remember { mutableStateOf<Long?>(null) }
    var pendingReplaceAssetHash by remember { mutableStateOf<String?>(null) }
    var canonicalProjectInfo by remember { mutableStateOf<CanonicalProjectInfo?>(null) }
    // The main workspace viewer renders through one shared native session
    // for the current canonical project — not a per-frame re-parse, and not
    // the draft placeholder. The holder (not the session itself) is what
    // composition remembers, so dispose always closes the latest session.
    val renderSessionHolder = remember { object { var session: NativeSession? = null } }

    /**
     * Moves the render session to [source]: opens it, swaps its project, or
     * closes it when there is no canonical project. A session that rejects
     * the update is closed and reopened so a failed edit never leaves the
     * viewer pinned to a half-updated native state.
     */
    fun syncRenderSession(source: String?) {
        val current = renderSessionHolder.session
        if (source == null) {
            current?.close()
            renderSessionHolder.session = null
            return
        }
        if (current == null) {
            renderSessionHolder.session = NativeSession.open(source)
            return
        }
        if (!current.updateProject(source)) {
            current.close()
            renderSessionHolder.session = NativeSession.open(source)
        }
    }
    var project by remember {
        mutableStateOf(projectStore.loadLatest() ?: ProjectDocument.starter())
    }
    var savedProjects by remember { mutableStateOf(projectStore.listSavedProjects()) }
    var offlineAssets by remember(offlineAssetStore) { mutableStateOf(offlineAssetStore.list()) }
    var referencedOfflineAssetIds by remember(offlineAssetReferences) { mutableStateOf(offlineAssetReferences.referencedAssetIds()) }
    var selectedLayerId by remember {
        mutableStateOf(project.layers.firstOrNull()?.id.orEmpty())
    }
    var playheadFrame by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("Stored on this device") }
    var undoStack by remember { mutableStateOf(emptyList<EditorHistorySnapshot>()) }
    var redoStack by remember { mutableStateOf(emptyList<EditorHistorySnapshot>()) }
    var projectKeyframeTracks by remember { mutableStateOf(projectKeyframeStore.load(project.id)) }

    // One place decides whether a payload is still referenced: every project
    // manifest, the legacy current-project references, and the payload files
    // the canonical document itself still points at. The last set is what
    // makes library Replace safe: after a manifest replace, the old payload
    // survives until the item is relinked in the canonical sheet and the
    // native path moves, and only then may garbage collection remove it.
    fun refreshReferences() {
        val nativeHashes = canonicalProjectInfo?.items?.mapNotNull { item ->
            item.path?.substringAfterLast('/')?.takeIf { name ->
                name.length > 65 && name.substring(0, 64).matches(Regex("[a-f0-9]{64}"))
            }?.substring(0, 64)
        }?.toSet() ?: emptySet()
        referencedOfflineAssetIds = manifestStore.allReferencedHashes() +
            offlineAssetReferences.referencedAssetIds() + nativeHashes
        manifestMissingCount = repositoryProjectId?.let { id ->
            runCatching { manifestStore.load(id).missingEntries().size }.getOrNull()
        } ?: 0
    }

    fun applyProjectChange(next: ProjectDocument, message: String = "Unsaved changes") {
        // Keep selection and playhead with the project snapshot. Undoing a layer
        // deletion must restore the deleted layer as selected; redo must restore
        // the adjacent selection that was chosen after deletion.
        undoStack = (undoStack + EditorHistorySnapshot(project, selectedLayerId, playheadFrame)).takeLast(50)
        redoStack = emptyList()
        project = next
        saveMessage = message
    }

    val validateEcprojLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val readGranted = runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
                val displayName = context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment?.substringAfterLast('/') ?: "selected document"
                ProjectFileFormats.importError(displayName)?.let(::error)
                val source = context.contentResolver.openInputStream(uri)?.use {
                    CanonicalProjectDocument.toCanonicalJson(CanonicalProjectDocument.readUtf8(it))
                } ?: error("Unable to read selected document")
                val native = NativeProjectBridge.inspectEcproj(source)
                    ?: error("Native .ecproj validation is unavailable on this ABI; file was not imported")
                val parsedInfo = CanonicalProjectDocument.validateNativeEnvelope(native)
                val status = "Canonical EffectCraft project validated · ${parsedInfo.compositions.size} compositions"
                ecprojStore.save(source)
                repositoryProjectId = projectRepository.create(displayName, source, "ecproj", displayName).id
                offlineAssetReferences.clear()
                if (readGranted) {
                    runCatching { safRegistry.record(uri.toString(), SafPermissionRegistry.Kind.READ, displayName) }
                    safGrants = safRegistry.grants()
                }
                importedEcproj = source
                canonicalProjectInfo = parsedInfo
                refreshReferences()
                saveMessage = status
            }.onFailure { error -> saveMessage = "Import failed · ${error.message ?: "invalid project"}" }
        }
    }
    val exportEcprojLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val source = importedEcproj ?: error("No canonical .ecproj has been imported")
                context.contentResolver.openOutputStream(uri)?.use { it.write(source.toByteArray(Charsets.UTF_8)) }
                    ?: error("Unable to write selected document")
                if (runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.isSuccess) {
                    runCatching { safRegistry.record(uri.toString(), SafPermissionRegistry.Kind.WRITE, "EffectCraft.ecproj") }
                    safGrants = safRegistry.grants()
                }
                saveMessage = "Exported canonical .ecproj"
            }.onFailure { error -> saveMessage = "Export failed · ${error.message ?: "storage error"}" }
        }
    }

    val exportRenderedFrameLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri: Uri? ->
        if (uri == null) {
            saveMessage = "Frame export cancelled"
        } else {
            val source = importedEcproj
            val composition = canonicalProjectInfo?.compositions?.firstOrNull()
            val frame = pendingFrameExport
            if (source == null || composition == null) {
                saveMessage = "Import a canonical project before rendering a frame"
            } else {
                frameExportInProgress = true
                frameExportJob = exportScope.launch {
                    try {
                        saveMessage = "Rendering frame $frame at full resolution…"
                        val rendered = withContext(Dispatchers.IO) {
                            val session = NativeSession.open(source, 0)
                                ?: error("Native renderer unavailable for this project")
                            try {
                                session.render(composition.id, frame.toLong())
                                    ?: error("Native renderer did not return a frame")
                            } finally {
                                session.close()
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        saveMessage = "Writing PNG…"
                        val dimensions = withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                RenderedFramePng.write(rendered, output)
                            } ?: error("Unable to open selected output document")
                        }
                        saveMessage = "Exported frame $frame · ${dimensions.width}×${dimensions.height} PNG"
                    } catch (cancelled: CancellationException) {
                        saveMessage = "Frame export cancelled"
                        throw cancelled
                    } catch (failure: Throwable) {
                        saveMessage = "Frame export failed · ${failure.message ?: "render or write error"}"
                    } finally {
                        frameExportInProgress = false
                        frameExportJob = null
                    }
                }
            }
        }
    }

    val exportPngSequenceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val settings = pendingSequenceSettings
        val compositionId = pendingSequenceCompositionId
        pendingSequenceSettings = null
        pendingSequenceCompositionId = null
        if (uri == null) {
            saveMessage = "PNG sequence export cancelled"
        } else if (settings == null || compositionId == null || importedEcproj == null) {
            saveMessage = "Import a canonical composition before exporting a PNG sequence"
        } else if (frameExportInProgress) {
            saveMessage = "Cancel the frame export before starting a sequence"
        } else {
            val source = importedEcproj!!
            sequenceExportInProgress = true
            sequenceExportWritingOutput = false
            sequenceExportProgress = 0
            sequenceExportJob = exportScope.launch {
                val tempArchive = File(context.cacheDir, "effectcraft-sequence-${System.nanoTime()}.zip")
                val exportJob = currentCoroutineContext()[Job]
                var session: NativeSession? = null
                try {
                    val compositionFrames = canonicalProjectInfo?.compositions
                        ?.firstOrNull { it.id == compositionId }?.durationFrames
                        ?: error("Canonical composition is no longer available")
                    val plan = RenderQueuePlan.create(settings, compositionFrames)
                    saveMessage = "Rendering PNG sequence · 0/${plan.frameCount} (0%)"
                    withContext(Dispatchers.IO) {
                        val nativeRenderSide = minOf(
                            maxOf(settings.width, settings.height),
                            RenderedFramePng.MAX_NATIVE_RENDER_SIDE
                        )
                        session = NativeSession.open(source, nativeRenderSide)
                            ?: error("Native renderer unavailable for this project")
                        FileOutputStream(tempArchive).use { archiveOutput ->
                            PngSequenceZipExporter.export(
                                plan = plan,
                                output = archiveOutput,
                                renderPng = { frame ->
                                    if (exportJob?.isActive != true) throw CancellationException("PNG sequence export cancelled")
                                    val rgba = session?.render(compositionId, frame.toLong())
                                        ?: error("Native renderer returned no frame at $frame")
                                    ByteArrayOutputStream().use { pngOutput ->
                                        RenderedFramePng.writeScaled(rgba, pngOutput, settings.width, settings.height)
                                        pngOutput.toByteArray()
                                    }
                                },
                                isCancelled = { exportJob?.isActive != true },
                                onProgress = { completed, percent ->
                                    sequenceExportProgress = percent
                                    saveMessage = "Rendering PNG sequence · $completed/${plan.frameCount} ($percent%)"
                                }
                            )
                        }
                        PngSequenceZipValidator.validate(tempArchive, plan)
                    }
                    currentCoroutineContext().ensureActive()
                    sequenceExportWritingOutput = true
                    saveMessage = "Copying completed ZIP to selected destination…"
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            tempArchive.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                        } ?: error("Unable to open ZIP destination")
                    }
                    sequenceExportProgress = 100
                    saveMessage = "Exported ${plan.frameCount} PNG frames · ${settings.width}×${settings.height} @ ${settings.frameRate} FPS"
                } catch (cancelled: CancellationException) {
                    saveMessage = "PNG sequence export cancelled"
                    throw cancelled
                } catch (failure: Throwable) {
                    saveMessage = "PNG sequence export failed · ${failure.message ?: "render or write error"}"
                } finally {
                    session?.close()
                    tempArchive.delete()
                    sequenceExportInProgress = false
                    sequenceExportWritingOutput = false
                    sequenceExportJob = null
                }
            }
        }
    }

    val exportEcprojxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val source = importedEcproj ?: error("No canonical EffectCraft project has been imported")
                val xml = CanonicalProjectDocument.toXml(source)
                context.contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
                    ?: error("Unable to write selected document")
                if (runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.isSuccess) {
                    runCatching { safRegistry.record(uri.toString(), SafPermissionRegistry.Kind.WRITE, "EffectCraft.ecprojx") }
                    safGrants = safRegistry.grants()
                }
                saveMessage = "Exported canonical .ecprojx"
            }.onFailure { error -> saveMessage = "XML export failed · ${error.message ?: "storage error"}" }
        }
    }

    val relinkAssetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val itemId = pendingRelinkItemId
        pendingRelinkItemId = null
        if (uri != null && itemId != null) {
            val source = importedEcproj
            if (source == null) {
                saveMessage = "Import a canonical .ecproj first"
            } else {
                runCatching {
                    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "asset"
                    val mimeType = context.contentResolver.getType(uri)
                    val offlineAsset = context.contentResolver.openInputStream(uri)?.use { input ->
                        offlineAssetStore.import(input, displayName, mimeType)
                    } ?: error("Unable to read selected media")
                    val target = offlineAssetStore.fileFor(offlineAsset.sha256)
                        ?: error("Imported offline media payload is unavailable")
                    val envelope = NativeProjectBridge.relinkFootageEcproj(source, itemId, target.absolutePath)
                        ?: error("Native EffectCraft bridge unavailable on this ABI")
                    val response = JSONObject(envelope)
                    check(response.optBoolean("valid")) { response.optString("error", "Media relink failed") }
                    val updated = response.getString("project")
                    val metadata = NativeProjectBridge.inspectEcproj(updated)
                        ?: error("Unable to inspect updated canonical project")
                    val parsed = CanonicalProjectInfo.parse(metadata)
                    // Relink rollback chain: manifest, then legacy reference,
                    // then canonical cache, then repository entry. Any failure
                    // restores every earlier step, so a failed relink never
                    // leaves a half-applied reference update behind. The
                    // imported payload itself is never deleted on rollback:
                    // its hash may be referenced by another project, so only
                    // garbage collection may remove it.
                    val manifestId = repositoryProjectId ?: error("No repository project for this import")
                    val manifest = manifestStore.load(manifestId)
                    val previousEntry = manifest.entryFor(itemId)
                    manifest.link(itemId, offlineAsset.sha256, target.absolutePath)
                    manifestStore.save(manifest)
                    val restoreManifest: () -> Unit = {
                        runCatching {
                            val current = manifestStore.load(manifestId)
                            if (previousEntry == null) current.restoreAbsent(itemId) else current.restore(previousEntry)
                            manifestStore.save(current)
                        }
                    }
                    val previousAsset = offlineAssetReferences.assetFor(itemId)
                    offlineAssetReferences.setReference(itemId, offlineAsset.sha256)
                    try {
                        ecprojStore.save(updated)
                    } catch (failure: Throwable) {
                        restoreManifest()
                        if (previousAsset == null) offlineAssetReferences.removeReference(itemId)
                        else offlineAssetReferences.setReference(itemId, previousAsset)
                        throw failure
                    }
                    try {
                        projectRepository.update(manifestId, updated)
                    } catch (failure: Throwable) {
                        restoreManifest()
                        if (previousAsset == null) offlineAssetReferences.removeReference(itemId)
                        else offlineAssetReferences.setReference(itemId, previousAsset)
                        runCatching { ecprojStore.save(source) }
                        throw failure
                    }
                    importedEcproj = updated
                    canonicalProjectInfo = parsed
                    offlineAssets = offlineAssetStore.list()
                    refreshReferences()
                    saveMessage = "Media copied offline · relinked"
                }.onFailure { error -> saveMessage = "Media relink failed · ${error.message ?: "storage error"}" }
            }
        }
    }

    // Launch reconciliation, once: the single-document cache is the working
    // copy, so a cached document with no repository entry is imported (this
    // also migrates pre-repository installs); SAF grants are reconciled
    // against what Android still reports, so a lost grant shows as LOST
    // instead of silently disappearing.
    LaunchedEffect(Unit) {
        // A process kill can bypass the export coroutine's finally block. Remove
        // only staging ZIPs older than the recovery window; never touch active exports.
        runCatching {
            val cleanup = withContext(Dispatchers.IO) { StaleSequenceExportCleanup.cleanup(context.cacheDir) }
            if (cleanup.removed > 0) saveMessage = "Recovered ${cleanup.removed} abandoned PNG sequence archive(s)"
        }
        runCatching {
            val cached = ecprojStore.load()
            repositoryProjectId = if (cached != null) {
                val matching = projectRepository.list().firstOrNull { projectRepository.load(it.id) == cached }
                matching?.id ?: projectRepository.importLegacyDocument(cached, "Imported project").id
            } else {
                projectRepository.list().firstOrNull()?.id
            }
            projectRepository.migrateAll()
        }
        val held = context.contentResolver.persistedUriPermissions.flatMap { permission ->
            buildList {
                if (permission.isReadPermission) add(SafPermissionRegistry.GrantKey(permission.uri.toString(), SafPermissionRegistry.Kind.READ))
                if (permission.isWritePermission) add(SafPermissionRegistry.GrantKey(permission.uri.toString(), SafPermissionRegistry.Kind.WRITE))
            }
        }.toSet()
        safRegistry.reconcile(held)
        safGrants = safRegistry.grants()
        refreshReferences()
    }

    LaunchedEffect(project.id) {
        projectKeyframeTracks = runCatching { projectKeyframeStore.load(project.id) }
            .getOrElse { failure -> saveMessage = "Keyframe load failed · ${failure.message ?: "storage error"}"; emptyList() }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            renderSessionHolder.session?.close()
            renderSessionHolder.session = null
        }
    }

    LaunchedEffect(importedEcproj) {
        // Session first: the workspace viewer below must never render a
        // frame from the previous project after this source changed.
        syncRenderSession(importedEcproj)
        canonicalProjectInfo = importedEcproj?.let { source ->
            NativeProjectBridge.inspectEcproj(source)?.let { runCatching { CanonicalProjectInfo.parse(it) }.getOrNull() }
        }
    }

    // Keeps manifest missing flags in sync with the native item metadata:
    // an item the engine reports missing is missing in the manifest, and a
    // relinked item stops being missing. The manifest never invents a flag
    // the native metadata does not support.
    LaunchedEffect(canonicalProjectInfo) {
        val info = canonicalProjectInfo
        val manifestId = repositoryProjectId
        if (info != null && manifestId != null) {
            runCatching {
                val manifest = manifestStore.load(manifestId)
                var changed = false
                info.items.forEach { item ->
                    if (item.missing) {
                        if (manifest.markMissing(item.id)) changed = true
                    } else if (manifest.entryFor(item.id) != null) {
                        if (manifest.markPresent(item.id)) changed = true
                    }
                }
                if (changed) manifestStore.save(manifest)
            }
            refreshReferences()
        }
    }

    val replaceAssetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val oldHash = pendingReplaceAssetHash
        pendingReplaceAssetHash = null
        if (uri != null && oldHash != null) {
            runCatching {
                val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "asset"
                val mimeType = context.contentResolver.getType(uri)
                val replacement = context.contentResolver.openInputStream(uri)?.use { input ->
                    offlineAssetStore.import(input, displayName, mimeType)
                } ?: error("Unable to read selected media")
                // Manifests and legacy references move to the new hash. The old
                // payload is deliberately kept: the canonical document's native
                // path still points at it until that item is relinked, and the
                // union referenced set keeps garbage collection from removing
                // it in the meantime.
                val changedEntries = manifestStore.replaceAssetEverywhere(oldHash, replacement.sha256)
                offlineAssetReferences.itemIdsFor(oldHash).forEach { itemId ->
                    offlineAssetReferences.setReference(itemId, replacement.sha256)
                }
                offlineAssets = offlineAssetStore.list()
                refreshReferences()
                saveMessage = "Media replaced in $changedEntries project link(s) · relink the item in the canonical sheet to move its native path"
            }.onFailure { error -> saveMessage = "Media replace failed · ${error.message ?: "storage error"}" }
        }
    }

    LaunchedEffect(isPlaying, project.durationFrames, project.frameRate) {
        while (isPlaying) {
            delay(ProjectEditorActions.frameIntervalMillis(project.frameRate))
            playheadFrame = (playheadFrame + 1) % project.durationFrames.coerceAtLeast(1)
        }
    }

    val selectedLayer = project.layers.firstOrNull { it.id == selectedLayerId }
    val selectedOpacityKeyframes = projectKeyframeTracks
        .firstOrNull { it.layerId == selectedLayerId && it.track.property == "opacity" }
        ?.track?.keyframes.orEmpty()
    fun applyCanonicalEdit(envelope: String?, successMessage: String) {
        runCatching {
            val response = JSONObject(envelope ?: error("Native EffectCraft bridge unavailable on this ABI"))
            check(response.optBoolean("valid")) { response.optString("error", "Canonical edit failed") }
            val updated = response.getString("project")
            val metadata = NativeProjectBridge.inspectEcproj(updated)
                ?: error("Unable to inspect updated canonical project")
            val parsed = CanonicalProjectInfo.parse(metadata)
            val previousSource = importedEcproj
            ecprojStore.save(updated)
            try {
                repositoryProjectId?.let { projectRepository.update(it, updated) }
            } catch (failure: Throwable) {
                if (previousSource != null) runCatching { ecprojStore.save(previousSource) }
                throw failure
            }
            // Synchronous, before either state write: the workspace viewer's
            // effect keys on canonicalProjectInfo, so it may render before a
            // LaunchedEffect(importedEcproj) sync runs. The edit itself is
            // already saved; a session failure only blanks the viewer.
            syncRenderSession(updated)
            importedEcproj = updated
            canonicalProjectInfo = parsed
            saveMessage = successMessage
        }.onFailure { error -> saveMessage = "Canonical edit failed · ${error.message ?: "invalid project"}" }
    }



    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0C11)
    ) {
        EditorScreen(
            project = project,
            selectedLayer = selectedLayer,
            selectedLayerId = selectedLayerId,
            playheadFrame = playheadFrame,
            selectedOpacityKeyframes = selectedOpacityKeyframes,
            isPlaying = isPlaying,
            saveMessage = saveMessage,
            savedProjects = savedProjects,
            offlineAssets = offlineAssets,
            referencedOfflineAssetIds = referencedOfflineAssetIds,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            onUndo = {
                if (undoStack.isNotEmpty()) {
                    redoStack = (redoStack + EditorHistorySnapshot(project, selectedLayerId, playheadFrame)).takeLast(50)
                    val previous = undoStack.last()
                    project = previous.project
                    selectedLayerId = previous.selectedLayerId
                        .takeIf { id -> project.layers.any { it.id == id } }
                        ?: project.layers.firstOrNull()?.id.orEmpty()
                    playheadFrame = previous.playheadFrame.coerceIn(0, project.durationFrames - 1)
                    undoStack = undoStack.dropLast(1)
                    saveMessage = "Undo · unsaved"
                }
            },
            onRedo = {
                if (redoStack.isNotEmpty()) {
                    undoStack = (undoStack + EditorHistorySnapshot(project, selectedLayerId, playheadFrame)).takeLast(50)
                    val next = redoStack.last()
                    project = next.project
                    selectedLayerId = next.selectedLayerId
                        .takeIf { id -> project.layers.any { it.id == id } }
                        ?: project.layers.firstOrNull()?.id.orEmpty()
                    playheadFrame = next.playheadFrame.coerceIn(0, project.durationFrames - 1)
                    redoStack = redoStack.dropLast(1)
                    saveMessage = "Redo · unsaved"
                }
            },
            onOpenProject = { projectId ->
                val opened = projectStore.load(projectId)
                if (opened == null) {
                    saveMessage = "Saved project is unavailable"
                } else {
                    project = opened
                    projectKeyframeTracks = projectKeyframeStore.load(opened.id)
                    selectedLayerId = opened.layers.firstOrNull()?.id.orEmpty()
                    playheadFrame = 0
                    isPlaying = false
                    undoStack = emptyList()
                    redoStack = emptyList()
                    saveMessage = "Opened locally · ${opened.name}"
                }
            },
            onDeleteOfflineAsset = { sha256 ->
                if (sha256 in referencedOfflineAssetIds) {
                    saveMessage = "Cannot remove media · linked by a project"
                } else runCatching { offlineAssetStore.delete(sha256) }
                    .onSuccess {
                        offlineAssets = offlineAssetStore.list()
                        saveMessage = "Offline media removed"
                    }
                    .onFailure { saveMessage = "Media removal failed · ${it.message ?: "storage error"}" }
            },
            onValidateEcproj = { validateEcprojLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
            onExportEcproj = {
                if (importedEcproj == null) saveMessage = "Import a canonical .ecproj first"
                else exportEcprojLauncher.launch("EffectCraft.ecproj")
            },
            onExportEcprojx = {
                if (importedEcproj == null) saveMessage = "Import a canonical EffectCraft project first"
                else exportEcprojxLauncher.launch("EffectCraft.ecprojx")
            },
            isExportingFrame = frameExportInProgress,
            onExportRenderedFrame = {
                if (sequenceExportInProgress) {
                    saveMessage = "Finish or cancel the sequence export before rendering a single frame"
                } else if (importedEcproj == null || canonicalProjectInfo?.compositions.isNullOrEmpty()) {
                    saveMessage = "Import a canonical composition before rendering a frame"
                } else if (frameExportInProgress) {
                    saveMessage = "A frame export is already running"
                } else {
                    pendingFrameExport = playheadFrame
                    exportRenderedFrameLauncher.launch("EffectCraft-frame-${playheadFrame.toString().padStart(6, '0')}.png")
                }
            },
            onCancelFrameExport = { frameExportJob?.cancel() },
            isExportingSequence = sequenceExportInProgress,
            isSequenceExportWritingOutput = sequenceExportWritingOutput,
            onExportPngSequence = { width, height, fps, startFrame, endFrame ->
                val composition = canonicalProjectInfo?.compositions?.firstOrNull()
                if (importedEcproj == null || composition == null) {
                    saveMessage = "Import a canonical composition before exporting a sequence"
                } else if (sequenceExportInProgress) {
                    saveMessage = "A PNG sequence export is already running"
                } else if (frameExportInProgress) {
                    saveMessage = "Cancel the frame export before starting a sequence"
                } else {
                    runCatching {
                        val settings = RenderQueueSettings(width, height, fps, startFrame, endFrame)
                        RenderQueuePlan.create(settings, composition.durationFrames)
                        pendingSequenceSettings = settings
                        pendingSequenceCompositionId = composition.id
                        exportPngSequenceLauncher.launch("EffectCraft-sequence.zip")
                    }.onFailure { failure ->
                        pendingSequenceSettings = null
                        pendingSequenceCompositionId = null
                        saveMessage = "PNG sequence plan rejected · ${failure.message ?: "unsupported composition"}"
                    }
                }
            },
            onCancelSequenceExport = { sequenceExportJob?.cancel() },
            sequenceExportProgress = sequenceExportProgress,
            onSave = {
                runCatching { projectStore.save(project) }
                    .onSuccess {
                        savedProjects = projectStore.listSavedProjects()
                        saveMessage = "Saved locally · ${project.name}"
                    }
                    .onFailure { saveMessage = "Save failed · ${it.message ?: "storage error"}" }
            },
            onNewProject = {
                applyProjectChange(ProjectDocument.starter(), "New offline composition")
                projectKeyframeTracks = emptyList()
                selectedLayerId = project.layers.firstOrNull()?.id.orEmpty()
                playheadFrame = 0
                isPlaying = false
            },
            onAddLayer = {
                val layer = LayerDocument.newShape(project.layers.size + 1)
                applyProjectChange(project.copy(layers = project.layers + layer))
                selectedLayerId = layer.id
            },
            onSelectLayer = { selectedLayerId = it },
            onDeleteLayer = {
                if (project.layers.size > 1 && selectedLayer != null) {
                    val (remainingLayers, nextSelection) = ProjectEditorActions.deleteLayerAndSelection(project.layers, selectedLayer.id)
                    applyProjectChange(project.copy(layers = remainingLayers), "Layer removed · unsaved")
                    selectedLayerId = nextSelection.orEmpty()
                } else {
                    saveMessage = "A composition must keep at least one layer"
                }
            },
            onRenameLayer = { name ->
                val renamed = ProjectEditorActions.renameLayer(project.layers, selectedLayerId, name)
                if (renamed != project.layers) applyProjectChange(project.copy(layers = renamed), "Layer renamed · unsaved")
            },
            onVisibilityChange = { visible ->
                applyProjectChange(project.copy(layers = ProjectEditorActions.setLayerVisibility(project.layers, selectedLayerId, visible)))
            },
            onMoveLayer = { offset ->
                applyProjectChange(project.copy(
                    layers = ProjectEditorActions.moveLayer(project.layers, selectedLayerId, offset)
                ))
            },
            onOpacityChange = { opacity ->
                applyProjectChange(project.copy(layers = ProjectEditorActions.setLayerOpacity(project.layers, selectedLayerId, opacity)))
            },
            onScrub = { playheadFrame = it.coerceIn(0, project.durationFrames - 1) },
            onAddOpacityKeyframe = {
                val layer = project.layers.firstOrNull { it.id == selectedLayerId }
                if (layer == null) saveMessage = "Select a layer before adding a keyframe"
                else runCatching {
                    projectKeyframeStore.upsert(project.id, layer.id, "opacity", playheadFrame, layer.opacity)
                    projectKeyframeTracks = projectKeyframeStore.load(project.id)
                    saveMessage = "Opacity keyframe added · frame $playheadFrame"
                }.onFailure { failure -> saveMessage = "Keyframe save failed · ${failure.message ?: "storage error"}" }
            },
            onRemoveOpacityKeyframe = {
                runCatching {
                    val removed = projectKeyframeStore.removeKeyframe(project.id, selectedLayerId, "opacity", playheadFrame)
                    projectKeyframeTracks = projectKeyframeStore.load(project.id)
                    saveMessage = if (removed) "Opacity keyframe removed · frame $playheadFrame" else "No opacity keyframe · frame $playheadFrame"
                }.onFailure { failure -> saveMessage = "Keyframe remove failed · ${failure.message ?: "storage error"}" }
            },
            onTogglePlayback = { isPlaying = !isPlaying },
            onApplyComposition = { width, height, fps, duration ->
                if (width in 320..3840 && height in 320..3840 && fps in 1..120 && duration in 1..3600) {
                    applyProjectChange(
                        project.copy(width = width, height = height, frameRate = fps, durationFrames = duration),
                        "Composition updated · unsaved"
                    )
                    playheadFrame = playheadFrame.coerceIn(0, duration - 1)
                } else {
                    saveMessage = "Composition settings outside supported limits"
                }
            },
            canonicalProject = canonicalProjectInfo,
            canEditCanonicalProject = NativeProjectBridge.isAvailable(),
            onRenameCanonicalLayer = { compositionId, layerId, name ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.renameLayerEcproj(source, compositionId, layerId, name),
                    "Canonical layer renamed · saved locally"
                )
            },
            onAddCanonicalTextLayer = { compositionId, name ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.addTextLayerEcproj(source, compositionId, name),
                    "Canonical Text layer added · saved locally"
                )
            },
            onSetCanonicalLayerVisibility = { compositionId, layerId, visible ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerVisibilityEcproj(source, compositionId, layerId, visible),
                    "Canonical visibility updated · saved locally"
                )
            },
            onMoveCanonicalLayer = { compositionId, layerId, offset ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.moveLayerEcproj(source, compositionId, layerId, offset),
                    "Canonical layer reordered · saved locally"
                )
            },
            onDeleteCanonicalLayer = { compositionId, layerId ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.deleteLayerEcproj(source, compositionId, layerId),
                    "Canonical layer removed · saved locally"
                )
            },
            onSetCanonicalLayerOpacity = { compositionId, layerId, opacityPercent ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerOpacityEcproj(source, compositionId, layerId, opacityPercent),
                    "Canonical opacity updated · saved locally"
                )
            },
            onAddCanonicalLayerOpacityKeyframe = { compositionId, layerId, frame, opacityPercent ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerOpacityKeyframeEcproj(source, compositionId, layerId, frame.toLong(), opacityPercent),
                    "Canonical opacity keyframe added · frame $frame · saved locally"
                )
            },
            onRemoveCanonicalLayerOpacityKeyframe = { compositionId, layerId, frame ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else {
                    // Removal is idempotent at the native layer: no key at the
                    // frame is a neutral status, not an edit failure.
                    val envelope = NativeProjectBridge.removeLayerOpacityKeyframeEcproj(source, compositionId, layerId, frame.toLong())
                    val removed = runCatching { JSONObject(envelope ?: "").optBoolean("removed", true) }.getOrDefault(true)
                    val valid = runCatching { JSONObject(envelope ?: "").optBoolean("valid") }.getOrDefault(false)
                    if (valid && !removed) {
                        saveMessage = "No canonical opacity keyframe · frame $frame"
                    } else {
                        applyCanonicalEdit(
                            envelope,
                            "Canonical opacity keyframe removed · frame $frame · saved locally"
                        )
                    }
                }
            },
            onSetCanonicalLayerPosition = { compositionId, layerId, x, y ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerPositionEcproj(source, compositionId, layerId, x, y),
                    "Canonical position updated · saved locally"
                )
            },
            onSetCanonicalLayerScale = { compositionId, layerId, x, y ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerScaleEcproj(source, compositionId, layerId, x, y),
                    "Canonical scale updated · saved locally"
                )
            },
            onSetCanonicalLayerRotation = { compositionId, layerId, degrees ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerRotationEcproj(source, compositionId, layerId, degrees),
                    "Canonical rotation updated · saved locally"
                )
            },
            onRelinkCanonicalAsset = { itemId ->
                pendingRelinkItemId = itemId
                relinkAssetLauncher.launch(arrayOf("*/*"))
            },
            onRenderCanonicalFrame = { compositionId, frame ->
                // Session-backed: the parsed project, MediaPool and preview
                // size live natively for the whole workspace lifetime. When
                // no session is open (no canonical project, or native
                // unavailable) this returns null and the viewer keeps its
                // honest "not rendered / unavailable" label.
                renderSessionHolder.session?.render(compositionId, frame)
            },
            safGrants = safGrants,
            missingManifestEntries = manifestMissingCount,
            onReleaseSafGrant = { uriString, kind ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(uriString),
                        if (kind == SafPermissionRegistry.Kind.READ) Intent.FLAG_GRANT_READ_URI_PERMISSION
                        else Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                safRegistry.release(uriString)
                safGrants = safRegistry.grants()
                saveMessage = "Storage access released"
            },
            onReplaceOfflineAsset = { sha256 ->
                pendingReplaceAssetHash = sha256
                replaceAssetLauncher.launch(arrayOf("*/*"))
            },
            onCollectUnusedOfflineAssets = {
                runCatching { offlineAssetStore.collectGarbage(referencedOfflineAssetIds) }
                    .onSuccess { result ->
                        offlineAssets = offlineAssetStore.list()
                        val failedNote = if (result.failed.isNotEmpty()) " · ${result.failed.size} could not be removed" else ""
                        saveMessage = "Removed ${result.deleted.size} unused file(s), ${result.orphanPayloadsRemoved} orphan(s)$failedNote"
                    }
                    .onFailure { saveMessage = "Unused-media cleanup failed · ${it.message ?: "storage error"}" }
            }
        )
    }
}
