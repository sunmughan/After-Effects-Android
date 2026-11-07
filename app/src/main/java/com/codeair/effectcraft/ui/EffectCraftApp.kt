package com.codeair.effectcraft.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import java.io.FileOutputStream
import com.codeair.effectcraft.data.AudioStatus
import com.codeair.effectcraft.data.CanonicalEditHistory
import com.codeair.effectcraft.data.CanonicalEditSnapshot
import com.codeair.effectcraft.data.CanonicalLayerSelection
import com.codeair.effectcraft.data.CanonicalProjectInfo
import com.codeair.effectcraft.data.CanonicalProjectDocument
import com.codeair.effectcraft.data.CanonicalProjectRepository
import com.codeair.effectcraft.data.DraftProjectStore
import com.codeair.effectcraft.data.EcprojDocumentStore
import com.codeair.effectcraft.data.EditorRecoveryJournal
import com.codeair.effectcraft.data.OfflineAssetStore
import com.codeair.effectcraft.data.OfflineAssetReferences
import com.codeair.effectcraft.data.ProjectAssetManifestStore
import com.codeair.effectcraft.data.SafPermissionRegistry
import com.codeair.effectcraft.engine.BudgetViolationException
import com.codeair.effectcraft.engine.NativeProjectBridge
import com.codeair.effectcraft.engine.NativeSession
import com.codeair.effectcraft.engine.RenderedFramePng
import com.codeair.effectcraft.data.LayerDocument
import com.codeair.effectcraft.data.ProjectEditorActions
import com.codeair.effectcraft.data.ProjectDocument
import com.codeair.effectcraft.data.ProjectFileFormats
import com.codeair.effectcraft.data.ProjectKeyframeStore
import com.codeair.effectcraft.data.FinalCopyRecovery
import com.codeair.effectcraft.data.ForegroundPngSequenceExport
import com.codeair.effectcraft.data.Mp4ExportWorker
import com.codeair.effectcraft.export.PngSequenceExportService
import com.codeair.effectcraft.data.Mp4OutputValidator
import com.codeair.effectcraft.data.RenderOutputFormat
import com.codeair.effectcraft.data.RenderQueuePlan
import com.codeair.effectcraft.data.RenderQueueSettings
import com.codeair.effectcraft.data.SafDocumentWriter
import com.codeair.effectcraft.data.StaleSequenceExportCleanup
import com.codeair.effectcraft.data.StaleVideoExportCleanup
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
    var canonicalImportJob by remember { mutableStateOf<Job?>(null) }
    var canonicalImportInProgress by remember { mutableStateOf(false) }
    var canonicalImportBytes by remember { mutableStateOf(0L) }
    var frameExportInProgress by remember { mutableStateOf(false) }
    var pendingFrameExport by remember { mutableIntStateOf(0) }
    var sequenceExportJob by remember { mutableStateOf<Job?>(null) }
    var serviceSequenceExportActive by remember { mutableStateOf(PngSequenceExportService.isRunning) }
    var sequenceExportInProgress by remember { mutableStateOf(false) }
    var sequenceExportWritingOutput by remember { mutableStateOf(false) }
    var sequenceExportProgress by remember { mutableIntStateOf(0) }
    var pendingSequenceSettings by remember { mutableStateOf<RenderQueueSettings?>(null) }
    var pendingSequenceCompositionId by remember { mutableStateOf<Long?>(null) }
    var mp4ExportJob by remember { mutableStateOf<Job?>(null) }
    var mp4ExportInProgress by remember { mutableStateOf(false) }
    var mp4ExportWritingOutput by remember { mutableStateOf(false) }
    var mp4ExportProgress by remember { mutableIntStateOf(0) }
    var pendingMp4Settings by remember { mutableStateOf<RenderQueueSettings?>(null) }
    var pendingMp4CompositionId by remember { mutableStateOf<Long?>(null) }
    val ecprojStore = remember(context) { EcprojDocumentStore(context) }
    val offlineAssetStore = remember(context) { OfflineAssetStore(File(context.filesDir, "media-library")) }
    val offlineAssetReferences = remember(context) { OfflineAssetReferences(File(context.filesDir, "media-library/references.properties")) }
    val projectKeyframeStore = remember(context) { ProjectKeyframeStore(File(context.filesDir, "keyframes")) }
    val manifestStore = remember(context) { ProjectAssetManifestStore(File(context.filesDir, "asset-manifests")) }
    val projectRepository = remember(context) { CanonicalProjectRepository(File(context.filesDir, "canonical-projects-repo")) }
    val safRegistry = remember(context) { SafPermissionRegistry(File(context.filesDir, "saf-permissions.properties")) }
    var importedEcproj by remember { mutableStateOf(ecprojStore.load()) }
    var repositoryProjectId by remember { mutableStateOf<String?>(null) }
    var repositoryProjects by remember { mutableStateOf(emptyList<CanonicalProjectRepository.Metadata>()) }
    var safGrants by remember(safRegistry) { mutableStateOf(safRegistry.grants()) }
    var manifestMissingCount by remember { mutableStateOf(0) }
    var pendingRelinkItemId by remember { mutableStateOf<Long?>(null) }
    var pendingReplaceAssetHash by remember { mutableStateOf<String?>(null) }
    var canonicalProjectInfo by remember { mutableStateOf<CanonicalProjectInfo?>(null) }
    var canonicalSelectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var canonicalUndoStack by remember { mutableStateOf(emptyList<CanonicalEditSnapshot>()) }
    var canonicalRedoStack by remember { mutableStateOf(emptyList<CanonicalEditSnapshot>()) }
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
    // Crash recovery: the autosave journal holds edits that never reached
    // an explicit save. A journal for the latest committed project wins when
    // it is newer; otherwise the newest journal overall wins when it is newer
    // than the latest committed draft, which is how a never-saved project
    // survives process death. Torn and stale journals are deleted by recovery.
    val recoveryJournal = remember(context) { EditorRecoveryJournal(File(context.filesDir, "recovery-journal")) }
    val committedDraft = projectStore.loadLatest()
    val recoveredDraft = runCatching {
        val forCommitted = committedDraft?.let { recoveryJournal.recover(it.id, it) }
        if (forCommitted != null) {
            forCommitted
        } else {
            val newest = recoveryJournal.recoverNewest()
            if (committedDraft != null && newest != null &&
                newest.updatedAtEpochMillis <= committedDraft.updatedAtEpochMillis
            ) {
                null
            } else {
                newest
            }
        }
    }.getOrNull()
    var project by remember {
        mutableStateOf(recoveredDraft ?: committedDraft ?: ProjectDocument.starter())
    }
    var savedProjects by remember { mutableStateOf(projectStore.listSavedProjects()) }
    var offlineAssets by remember(offlineAssetStore) { mutableStateOf(offlineAssetStore.list()) }
    var mediaVerificationJob by remember { mutableStateOf<Job?>(null) }
    var mediaVerificationInProgress by remember { mutableStateOf(false) }
    var referencedOfflineAssetIds by remember(offlineAssetReferences) { mutableStateOf(offlineAssetReferences.referencedAssetIds()) }
    var selectedLayerId by remember {
        mutableStateOf(project.layers.firstOrNull()?.id.orEmpty())
    }
    var selectedClipId by remember { mutableStateOf<String?>(null) }
    var playheadFrame by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var saveMessage by remember {
        mutableStateOf(if (recoveredDraft != null) "Recovered unsaved changes" else "Stored on this device")
    }
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

    // Switches the working canonical project to a saved repository entry.
    // The single-document cache is updated first so startup reconciliation
    // keeps matching the cache to this entry, and the render session follows
    // through the importedEcproj effect. Nothing is mutated when the entry
    // cannot be loaded or the native bridge cannot validate it.
    fun openRepositoryProject(id: String) {
        runCatching {
            val source = projectRepository.load(id) ?: error("Repository project not found")
            val native = NativeProjectBridge.inspectEcproj(source)
                ?: error("Native validation is unavailable on this ABI; project was not opened")
            CanonicalProjectDocument.validateNativeEnvelope(native)
            ecprojStore.save(source)
            importedEcproj = source
            repositoryProjectId = id
            canonicalSelectedIds = emptySet()
            // A switch must not leave the previous project's history
            // reachable: Canonical Undo commits its snapshot into the
            // active repository entry, so a stale stack would overwrite
            // this project with the previous project's document.
            canonicalUndoStack = emptyList()
            canonicalRedoStack = emptyList()
            playheadFrame = 0
            isPlaying = false
            refreshReferences()
            saveMessage = "Opened ${projectRepository.metadata(id)?.displayName ?: "canonical project"}"
        }.onFailure { error -> saveMessage = "Open failed · ${error.message ?: "storage error"}" }
    }

    // The repository list refreshes whenever the active entry changes, which
    // covers startup reconciliation, imports, and picker switches with one rule.
    LaunchedEffect(repositoryProjectId) {
        repositoryProjects = runCatching { projectRepository.list() }.getOrDefault(emptyList())
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

    /**
     * Applies a clip trim/split outcome through the same undo system as
     * every other draft edit: a change pushes the pre-edit snapshot and
     * clears redo (and is journaled by the LaunchedEffect on [project]),
     * while a refusal only reports its reason. This is the draft
     * editor's undo; CanonicalEditHistory governs canonical JSON edits,
     * which clips never touch.
     */
    fun applyClipEdit(outcome: ClipEditOutcome, layerId: String, successMessage: String): Boolean {
        return when (outcome) {
            is ClipEditOutcome.Changed -> {
                val layers = project.layers.map { layer ->
                    if (layer.id == layerId) layer.copy(clips = outcome.clips) else layer
                }
                applyProjectChange(project.copy(layers = layers), successMessage)
                true
            }
            is ClipEditOutcome.Unchanged -> {
                saveMessage = outcome.reason
                false
            }
        }
    }

    /** Drops the clip selection when its clip no longer exists in [project]. */
    fun pruneClipSelection() {
        selectedClipId = selectedClipId?.takeIf { id ->
            project.layers.any { layer -> layer.clips.any { it.id == id } }
        }
    }

    val validateEcprojLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) saveMessage = "Project import cancelled" else {
            canonicalImportJob?.cancel()
            canonicalImportInProgress = true
            canonicalImportBytes = 0L
            saveMessage = "Importing canonical project…"
            canonicalImportJob = exportScope.launch {
                val importJob = currentCoroutineContext()[Job]
                try {
                    val readGranted = runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
                    val displayName = withContext(Dispatchers.IO) {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "selected document"
                    }
                    ProjectFileFormats.importError(displayName)?.let(::error)
                    val rawDocument = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            CanonicalProjectDocument.readUtf8(input, onProgress = { bytes ->
                                if (importJob?.isActive != true) throw CancellationException("Project import cancelled")
                                if (bytes - canonicalImportBytes >= 256 * 1024) {
                                    canonicalImportBytes = bytes
                                    saveMessage = "Importing project · $bytes bytes"
                                }
                            })
                        } ?: error("Unable to read selected document")
                    }
                    currentCoroutineContext().ensureActive()
                    val source = withContext(Dispatchers.Default) { CanonicalProjectDocument.toCanonicalJson(rawDocument) }
                    val native = withContext(Dispatchers.Default) { NativeProjectBridge.inspectEcproj(source) }
                        ?: error("Native .ecproj validation is unavailable on this ABI; file was not imported")
                    val parsedInfo = withContext(Dispatchers.Default) { CanonicalProjectDocument.validateNativeEnvelope(native) }
                    currentCoroutineContext().ensureActive()
                    val repositoryMetadata = withContext(Dispatchers.IO) {
                        currentCoroutineContext().ensureActive()
                        val created = projectRepository.create(displayName, source, "ecproj", displayName)
                        try {
                            currentCoroutineContext().ensureActive()
                            ecprojStore.save(source)
                        } catch (failure: Throwable) {
                            runCatching { projectRepository.delete(created.id) }
                            throw failure
                        }
                        offlineAssetReferences.clear()
                        if (readGranted) runCatching { safRegistry.record(uri.toString(), SafPermissionRegistry.Kind.READ, displayName) }
                        created
                    }
                    repositoryProjectId = repositoryMetadata.id
                    if (readGranted) safGrants = safRegistry.grants()
                    importedEcproj = source
                    canonicalProjectInfo = parsedInfo
                    canonicalSelectedIds = parsedInfo.compositions.firstOrNull()?.layers?.firstOrNull()?.let { setOf(it.id) } ?: emptySet()
                    canonicalUndoStack = emptyList()
                    canonicalRedoStack = emptyList()
                    refreshReferences()
                    canonicalImportBytes = rawDocument.toByteArray(Charsets.UTF_8).size.toLong()
                    saveMessage = "Canonical EffectCraft project validated · ${parsedInfo.compositions.size} compositions"
                } catch (cancelled: CancellationException) {
                    saveMessage = "Project import cancelled"
                } catch (failure: Throwable) {
                    saveMessage = "Import failed · ${failure.message ?: "invalid project"}"
                } finally {
                    // A cancelled predecessor must not clear the state of a
                    // newer import started from the picker.
                    if (canonicalImportJob === importJob) {
                        canonicalImportInProgress = false
                        canonicalImportJob = null
                    }
                }
            }
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
                    val exportJob = currentCoroutineContext()[Job]
                    val stagedPng = File(context.cacheDir, "effectcraft-frame-${System.nanoTime()}.png")
                    try {
                        saveMessage = "Rendering frame $frame at full resolution…"
                        val rendered = withContext(Dispatchers.IO) {
                            // A gap frame in the draft timeline renders as the
                            // background frame without opening the native
                            // session at all; see ClipRenderCoverage. Only
                            // when the draft timeline and this composition
                            // share a duration do the clips describe it.
                            if (ClipRenderCoverage.appliesTo(project, composition.durationFrames) &&
                                !ClipRenderCoverage.isFrameCovered(project, frame)
                            ) {
                                ClipRenderCoverage.backgroundFrame(composition.width, composition.height)
                            } else {
                                val session = NativeSession.open(source, 0)
                                    ?: error("Native renderer unavailable for this project")
                                try {
                                    session.render(composition.id, frame.toLong())
                                        ?: error("Native renderer did not return a frame")
                                } finally {
                                    session.close()
                                }
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        saveMessage = "Encoding PNG in app-private staging…"
                        val dimensions = withContext(Dispatchers.IO) {
                            FileOutputStream(stagedPng).use { output -> RenderedFramePng.write(rendered, output) }
                        }
                        currentCoroutineContext().ensureActive()
                        saveMessage = "Copying PNG to selected destination…"
                        withContext(Dispatchers.IO) {
                            SafDocumentWriter.copyFileToDocument(
                                context = context,
                                sourceFile = stagedPng,
                                destination = uri,
                                ensureActive = { exportJob?.ensureActive() }
                            )
                        }
                        saveMessage = "Exported frame $frame · ${dimensions.width}×${dimensions.height} PNG"
                    } catch (cancelled: CancellationException) {
                        saveMessage = "Frame export cancelled"
                        throw cancelled
                    } catch (failure: Throwable) {
                        saveMessage = "Frame export failed · ${failure.message ?: "render or write error"}"
                    } finally {
                        stagedPng.delete()
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
            // PNG sequence export now runs in the foreground service, not
            // in this composable's coroutine scope, so rendering survives
            // the user leaving the editor and shows a cancellable
            // notification. The service reports through its status holder,
            // which the polling effect below mirrors into editor state.
            val compositionFrames = canonicalProjectInfo?.compositions
                ?.firstOrNull { it.id == compositionId }?.durationFrames
            if (compositionFrames == null) {
                saveMessage = "Canonical composition is no longer available"
            } else {
                runCatching {
                    RenderQueuePlan.create(settings, compositionFrames)
                    sequenceExportInProgress = true
                    sequenceExportWritingOutput = false
                    sequenceExportProgress = 0
                    saveMessage = "Rendering PNG sequence via foreground service"
                    serviceSequenceExportActive = true
                    ContextCompat.startForegroundService(
                        context,
                        PngSequenceExportService.startIntent(
                            context, uri, compositionId, settings, compositionFrames
                        )
                    )
                }.onFailure { failure ->
                    serviceSequenceExportActive = false
                    sequenceExportInProgress = false
                    sequenceExportWritingOutput = false
                    saveMessage = "PNG sequence export failed · ${failure.message ?: "service could not start"}"
                }
            }
        }
    }

    // Mirrors foreground-service export status into editor state. A
    // finished status is only honoured after an unfinished status has
    // been seen for this run, so a stale finished status from a previous
    // export cannot clear the new run before the service reports.
    LaunchedEffect(serviceSequenceExportActive) {
        if (!serviceSequenceExportActive) return@LaunchedEffect
        var seenActive = false
        while (serviceSequenceExportActive) {
            val current = PngSequenceExportService.status
            if (current != null) {
                if (!current.finished) {
                    seenActive = true
                    sequenceExportProgress = current.percent
                    sequenceExportWritingOutput =
                        current.phase == ForegroundPngSequenceExport.Phase.COPYING
                    saveMessage = current.message
                } else if (seenActive) {
                    sequenceExportProgress = current.percent
                    sequenceExportWritingOutput = false
                    saveMessage = current.message
                    sequenceExportInProgress = false
                    serviceSequenceExportActive = false
                }
            }
            if (serviceSequenceExportActive) delay(200)
        }
    }

    val exportMp4Launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri: Uri? ->
        val settings = pendingMp4Settings
        val compositionId = pendingMp4CompositionId
        pendingMp4Settings = null
        pendingMp4CompositionId = null
        if (uri == null) {
            saveMessage = "MP4 export cancelled"
        } else if (settings == null || compositionId == null || importedEcproj == null) {
            saveMessage = "Import a canonical composition before exporting MP4"
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        } else if (frameExportInProgress || sequenceExportInProgress) {
            saveMessage = "Finish or cancel the current render before starting MP4"
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        } else {
            val source = importedEcproj!!
            mp4ExportInProgress = true
            mp4ExportWritingOutput = false
            mp4ExportProgress = 0
            mp4ExportJob = exportScope.launch {
                var session: NativeSession? = null
                var outputCompleted = false
                var stagedMp4: File? = null
                var mp4CopyMarker: File? = null
                val exportJob = currentCoroutineContext()[Job]
                try {
                    val composition = canonicalProjectInfo?.compositions
                        ?.firstOrNull { it.id == compositionId }
                        ?: error("Canonical composition is no longer available")
                    // When the draft timeline and this composition share a
                    // duration, the draft clips describe the export: the
                    // plan is clip-validated and gap frames render as
                    // background. Otherwise the export is exactly as before.
                    val clipsApply = ClipRenderCoverage.appliesTo(project, composition.durationFrames)
                    val plan = if (clipsApply) RenderQueuePlan.createForClips(settings, project)
                    else RenderQueuePlan.create(settings, composition.durationFrames)
                    saveMessage = "Rendering H.264 MP4 · 0/${plan.frameCount} (0%)"
                    val staged = File(context.cacheDir, "effectcraft-video-${System.nanoTime()}.mp4")
                    stagedMp4 = staged
                    val stagedResult = withContext(Dispatchers.IO) {
                        val nativeRenderSide = minOf(
                            maxOf(settings.width, settings.height),
                            RenderedFramePng.MAX_NATIVE_RENDER_SIDE
                        )
                        session = NativeSession.open(source, nativeRenderSide)
                            ?: error("Native renderer unavailable for this project")
                        val audio = Mp4ExportWorker.resolveAudio(
                            items = canonicalProjectInfo?.items ?: emptyList(),
                            verifyPayload = { sha256, _ -> offlineAssetStore.verify(sha256) },
                            fileFor = { sha256 -> offlineAssetStore.fileFor(sha256) }
                        )
                        val nativeRender: (Int) -> ByteArray = { frame ->
                            if (exportJob?.isActive != true) throw CancellationException("MP4 export cancelled")
                            session?.render(compositionId, frame.toLong())
                                ?: error("Native renderer returned no frame at $frame")
                        }
                        val renderRgba = if (clipsApply) {
                            ClipRenderCoverage.clipAwareRenderRgba(project, settings.width, settings.height, nativeRender)
                        } else {
                            nativeRender
                        }
                        Mp4ExportWorker.exportToStaged(
                            plan = plan,
                            stagedFile = staged,
                            renderRgba = renderRgba,
                            audioFile = audio.file,
                            hasAudioItems = audio.hasAudioItems,
                            compositionFrameRateNum = composition.frameRateNum,
                            compositionFrameRateDen = composition.frameRateDen,
                            isCancelled = { exportJob?.isActive != true },
                            onProgress = { completed, percent ->
                                mp4ExportProgress = percent
                                saveMessage = "Rendering H.264 MP4 · $completed/${plan.frameCount} ($percent%)"
                            }
                        )
                    }
                    // Only the validated staged file is ever copied to SAF.
                    // M5-L7: publish the copy marker immediately before the
                    // final SAF copy, so a kill mid-copy leaves a detectable
                    // marker plus staging for startup recovery. A marker
                    // failure is best effort: the copy still runs, only
                    // undetected. The marker is deleted in the finally below
                    // on every non-kill path; no codec or encoding code here
                    // is changed.
                    mp4ExportWritingOutput = true
                    saveMessage = "Copying validated MP4 to selected destination…"
                    withContext(Dispatchers.IO) {
                        mp4CopyMarker = runCatching {
                            FinalCopyRecovery.beginCopy(staged, FinalCopyRecovery.FORMAT_MP4)
                        }.getOrNull()
                        SafDocumentWriter.copyFileToDocument(
                            context = context,
                            sourceFile = staged,
                            destination = uri,
                            ensureActive = { exportJob?.ensureActive() }
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    val verified = withContext(Dispatchers.IO) {
                        Mp4OutputValidator.validate(
                            context = context,
                            uri = uri,
                            plan = plan,
                            expectAudio = stagedResult.audioStatus == AudioStatus.INCLUDED
                        )
                    }
                    // The destination is a byte copy of the staged file, so its
                    // validator report must agree with the staged result exactly;
                    // any disagreement deletes the destination (see finally).
                    check(verified.frameCount == stagedResult.framesWritten) {
                        "MP4 validation frame count differs from staged export"
                    }
                    check(verified.durationUs == stagedResult.durationUs) {
                        "MP4 validation duration differs from staged export"
                    }
                    if (stagedResult.audioStatus == AudioStatus.INCLUDED) {
                        check(verified.audioSamples == stagedResult.audioSamplesWritten) {
                            "MP4 validation AAC sample count differs from staged export"
                        }
                        check(verified.audioFirstPtsUs == stagedResult.audioFirstPtsUs &&
                            verified.audioLastPtsUs == stagedResult.audioLastPtsUs) {
                            "MP4 validation AAC presentation window differs from staged export"
                        }
                        check(verified.audioTrackDurationUs == stagedResult.audioTrackDurationUs) {
                            "MP4 validation AAC track duration differs from staged export"
                        }
                    } else {
                        check(verified.audioSamples == 0L) {
                            "MP4 unexpectedly contains AAC audio for a video-only export"
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    outputCompleted = true
                    mp4ExportProgress = 100
                    val audioTiming = if (stagedResult.audioStatus == AudioStatus.INCLUDED &&
                        stagedResult.audioFirstPtsUs != null && stagedResult.audioLastPtsUs != null) {
                        " · AAC PTS ${stagedResult.audioFirstPtsUs / 1_000}–${stagedResult.audioLastPtsUs / 1_000} ms"
                    } else ""
                    saveMessage = "Exported & verified ${verified.frameCount} H.264 frames · ${verified.width}×${verified.height} @ ${settings.frameRate} FPS · ${stagedResult.audioStatus.describe()}$audioTiming"
                } catch (cancelled: CancellationException) {
                    saveMessage = "MP4 export cancelled"
                    throw cancelled
                } catch (failure: Throwable) {
                    saveMessage = "MP4 export failed · ${failure.message ?: "render or codec error"}"
                } finally {
                    session?.close()
                    stagedMp4?.delete()
                    mp4CopyMarker?.let { runCatching { FinalCopyRecovery.finishCopy(it) } }
                    if (!outputCompleted) runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    mp4ExportInProgress = false
                    mp4ExportWritingOutput = false
                    mp4ExportJob = null
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
        // only staging ZIPs/MP4s older than the recovery window; never touch active exports.
        // A removed staged MP4 means that export must be restarted: a MediaMuxer
        // stream cannot resume after a kill, so recovery is reported, not hidden.
        runCatching {
            val cleanup = withContext(Dispatchers.IO) { StaleSequenceExportCleanup.cleanup(context.cacheDir) }
            val videoCleanup = withContext(Dispatchers.IO) { StaleVideoExportCleanup.cleanup(context.cacheDir) }
            val exportRecovery = withContext(Dispatchers.IO) { ForegroundPngSequenceExport.recover(context.cacheDir) }
            // M5-L7: startup sweep for kills during the final SAF copy, all
            // formats. This runs in a fresh process where no copy is in
            // flight, so every copy marker here belongs to a dead export.
            // It cleans only app-side staging and markers; a provider-side
            // partial at the chosen destination cannot be removed here and
            // is reported as possibly remaining.
            val copyRecovery = withContext(Dispatchers.IO) { FinalCopyRecovery.recover(context.cacheDir) }
            val recovered = buildList {
                if (cleanup.removed > 0) add("${cleanup.removed} abandoned PNG sequence archive(s)")
                if (videoCleanup.removed > 0) add("${videoCleanup.removed} abandoned MP4 export(s) — restart the export if needed")
                if (exportRecovery.cleaned > 0) add("${exportRecovery.cleaned} interrupted PNG sequence export(s) cleaned, restart the export if needed")
                if (exportRecovery.orphanMarkersRemoved > 0) add("${exportRecovery.orphanMarkersRemoved} orphan export marker(s) removed")
                if (copyRecovery.cleaned > 0) add("${copyRecovery.cleaned} interrupted final copy/copies (${copyRecovery.cleanedFormats.joinToString()}) cleaned: app staging and marker removed, restart the export from the start; a partial file may still remain at the destination you chose, delete it there if present")
                if (copyRecovery.orphanMarkersRemoved > 0) add("${copyRecovery.orphanMarkersRemoved} orphan final-copy marker(s) removed; a partial file may still remain at the destination you chose, delete it there if present")
            }
            if (recovered.isNotEmpty()) saveMessage = "Recovered " + recovered.joinToString(" and ")
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

    // Autosave journal: every working-project change is journaled off the UI
    // thread before the user can be interrupted by a crash, rotation, or
    // process death. A journal failure must never break editing, so it is
    // swallowed here; the committed draft remains the fallback. Journals for
    // abandoned projects are pruned so they can never resurrect later.
    LaunchedEffect(project) {
        runCatching {
            withContext(Dispatchers.IO) {
                recoveryJournal.write(project)
                recoveryJournal.discardAllExcept(project.id)
            }
        }
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
    val selectedCanonicalTransformComposition = canonicalProjectInfo?.compositions
        ?.firstOrNull { composition -> composition.layers.any { it.id in canonicalSelectedIds } }
    val selectedCanonicalTransformKeyframes = selectedCanonicalTransformComposition?.layers
        ?.filter { it.id in canonicalSelectedIds }
        ?.flatMap { layer -> layer.transformKeyframes.values.flatten() }
        ?.distinct()?.sorted().orEmpty()
    val selectedCanonicalTransformDurationFrames = selectedCanonicalTransformComposition?.durationFrames
        ?: project.durationFrames
    val selectedCanonicalColorKeys = selectedCanonicalTransformComposition?.layers
        ?.filter { it.id in canonicalSelectedIds }
        ?.flatMap { com.codeair.effectcraft.data.colorKeysOf(it) }
        ?.distinctBy { it.frame to it.components }?.sortedBy { it.frame }.orEmpty()
    /**
     * Commits an already validated canonical project source: inspect, parse,
     * save to the local cache and repository, refresh the render session,
     * and keep the selection on layers that still exist (falling back to
     * the first layer). Throws on failure, so callers only record history
     * after a commit has actually landed.
     */
    fun commitCanonicalSource(updated: String, successMessage: String, selectionOverride: Set<Long>? = null) {
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
        val allLayers = parsed.compositions.flatMap { it.layers }
        val pruned = CanonicalLayerSelection.pruneSelection(selectionOverride ?: canonicalSelectedIds, allLayers)
        canonicalSelectedIds = if (pruned.isNotEmpty()) pruned
            else allLayers.firstOrNull()?.let { setOf(it.id) } ?: emptySet()
        saveMessage = successMessage
    }

    fun applyCanonicalEdit(envelope: String?, successMessage: String) {
        runCatching {
            val response = JSONObject(envelope ?: error("Native EffectCraft bridge unavailable on this ABI"))
            check(response.optBoolean("valid")) { response.optString("error", "Canonical edit failed") }
            val updated = response.getString("project")
            val before = importedEcproj
            val beforeSelection = canonicalSelectedIds
            commitCanonicalSource(updated, successMessage)
            if (before != null && updated != before) {
                canonicalUndoStack = CanonicalEditHistory.record(
                    canonicalUndoStack, CanonicalEditSnapshot(before, beforeSelection)
                )
                canonicalRedoStack = emptyList()
            }
        }.onFailure { error -> saveMessage = "Canonical edit failed · ${error.message ?: "invalid project"}" }
    }

    /**
     * Applies several native canonical edits as one history step: each step
     * transforms the previous step's project, and only the final project is
     * committed. A failed step leaves the saved project untouched, and a
     * batch that changes nothing records no history.
     */
    fun applyCanonicalEditBatch(steps: List<(String) -> String?>, successMessage: String) {
        val before = importedEcproj ?: run {
            saveMessage = "Import a canonical .ecproj first"
            return
        }
        val beforeSelection = canonicalSelectedIds
        runCatching {
            var current = before
            steps.forEach { step ->
                val envelope = step(current) ?: error("Native EffectCraft bridge unavailable on this ABI")
                val response = JSONObject(envelope)
                check(response.optBoolean("valid")) { response.optString("error", "Canonical edit failed") }
                current = response.getString("project")
            }
            if (current == before) {
                saveMessage = "Canonical edit made no change"
            } else {
                commitCanonicalSource(current, successMessage)
                canonicalUndoStack = CanonicalEditHistory.record(
                    canonicalUndoStack, CanonicalEditSnapshot(before, beforeSelection)
                )
                canonicalRedoStack = emptyList()
            }
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
            canonicalTransformKeyframes = selectedCanonicalTransformKeyframes,
            canonicalTransformDurationFrames = selectedCanonicalTransformDurationFrames,
            canonicalColorKeys = selectedCanonicalColorKeys,
            canonicalColorDurationFrames = selectedCanonicalTransformDurationFrames,
            isPlaying = isPlaying,
            saveMessage = saveMessage,
            savedProjects = savedProjects,
            repositoryProjects = repositoryProjects,
            activeRepositoryProjectId = repositoryProjectId,
            onOpenRepositoryProject = { id -> openRepositoryProject(id) },
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
                    pruneClipSelection()
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
                    pruneClipSelection()
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
                    selectedClipId = null
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
            isImportingCanonicalProject = canonicalImportInProgress,
            onCancelCanonicalImport = { canonicalImportJob?.cancel() },
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
            onCancelSequenceExport = {
                if (serviceSequenceExportActive) {
                    context.startService(PngSequenceExportService.cancelIntent(context))
                } else {
                    sequenceExportJob?.cancel()
                }
            },
            sequenceExportProgress = sequenceExportProgress,
            isExportingMp4 = mp4ExportInProgress,
            isMp4ExportWritingOutput = mp4ExportWritingOutput,
            onOpenMp4Settings = {
                if (sequenceExportInProgress || frameExportInProgress) saveMessage = "Finish or cancel the current render first"
                else saveMessage = "H.264 MP4 settings opened"
            },
            onCancelMp4Sequence = { if (!mp4ExportWritingOutput) mp4ExportJob?.cancel() },
            mp4ExportProgress = mp4ExportProgress,
            onExportMp4Sequence = { width, height, fps, startFrame, endFrame, bitrate, outputFormat ->
                val composition = canonicalProjectInfo?.compositions?.firstOrNull()
                if (importedEcproj == null || composition == null) {
                    saveMessage = "Import a canonical composition before exporting MP4"
                } else if (mp4ExportInProgress || sequenceExportInProgress || frameExportInProgress) {
                    saveMessage = "Another render is already running"
                } else {
                    runCatching {
                        val settings = RenderQueueSettings(width, height, fps, startFrame, endFrame, outputFormat, bitrate)
                        RenderQueuePlan.create(settings, composition.durationFrames)
                        pendingMp4Settings = settings
                        pendingMp4CompositionId = composition.id
                        exportMp4Launcher.launch("EffectCraft-video.mp4")
                    }.onFailure { failure ->
                        pendingMp4Settings = null
                        pendingMp4CompositionId = null
                        saveMessage = "MP4 plan rejected · ${failure.message ?: "unsupported composition"}"
                    }
                }
            },
            onSave = {
                runCatching { projectStore.save(project) }
                    .onSuccess {
                        // The edits are committed now, so the journal for this
                        // project must go: keeping it would resurrect old edits.
                        runCatching { recoveryJournal.discard(project.id) }
                        savedProjects = projectStore.listSavedProjects()
                        saveMessage = "Saved locally · ${project.name}"
                    }
                    .onFailure { saveMessage = "Save failed · ${it.message ?: "storage error"}" }
            },
            onNewProject = {
                applyProjectChange(ProjectDocument.starter(), "New offline composition")
                projectKeyframeTracks = emptyList()
                selectedLayerId = project.layers.firstOrNull()?.id.orEmpty()
                selectedClipId = null
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
                    selectedClipId = null
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
            selectedClipId = selectedClipId,
            onSelectClip = { clipId ->
                val layer = project.layers.firstOrNull { l -> l.clips.any { it.id == clipId } }
                if (layer != null) {
                    selectedLayerId = layer.id
                    selectedClipId = clipId
                }
            },
            onTrimClip = { newStart, newEnd ->
                val layer = project.layers.firstOrNull { it.id == selectedLayerId }
                val clipId = selectedClipId
                if (layer == null || clipId == null) {
                    saveMessage = "Select a clip before trimming"
                } else {
                    applyClipEdit(
                        ClipTimeline.trimClip(layer.clips, clipId, newStart, newEnd, project.durationFrames),
                        layer.id,
                        "Clip trimmed · unsaved"
                    )
                }
            },
            onSplitClipAtPlayhead = {
                val layer = project.layers.firstOrNull { it.id == selectedLayerId }
                if (layer == null) {
                    saveMessage = "Select a layer before splitting"
                } else {
                    val target = selectedClipId?.let { id -> layer.clips.firstOrNull { it.id == id } }
                        ?: ClipTimeline.clipAt(layer.clips, playheadFrame)
                    if (target == null) {
                        saveMessage = "No clip under the playhead on the selected layer"
                    } else {
                        applyClipEdit(
                            ClipTimeline.splitClip(layer.clips, target.id, playheadFrame, project.durationFrames),
                            layer.id,
                            "Clip split at frame $playheadFrame · unsaved"
                        )
                    }
                }
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
                    // A shorter composition trims clips that ran past it and
                    // drops clips that started at or after the new end, so
                    // the project stays valid after the change.
                    val layers = project.layers.map { layer ->
                        layer.copy(clips = ClipTimeline.clampToDuration(layer.clips, duration))
                    }
                    applyProjectChange(
                        project.copy(width = width, height = height, frameRate = fps, durationFrames = duration, layers = layers),
                        "Composition updated · unsaved"
                    )
                    pruneClipSelection()
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
            onAddCanonicalLayerTransformKeyframe = { compositionId, layerId, property, frame, x, y, z ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerTransformKeyframeEcproj(source, compositionId, layerId, property, frame.toLong(), x, y, z),
                    "Canonical $property keyframe added · frame $frame · saved locally"
                )
            },
            onSetCanonicalLayerTransformInterpolation = { compositionId, layerId, property, frame, preset ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerTransformKeyframeInterpolationEcproj(source, compositionId, layerId, property, frame.toLong(), preset),
                    "Canonical $property interpolation set to $preset · frame $frame · saved locally"
                )
            },
            onSetCanonicalKeyframeRoving = { compositionId, layerId, property, frame, roving ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerKeyframeRovingEcproj(source, compositionId, layerId, "transform:$property", frame.toLong(), roving),
                    "Canonical $property roving ${if (roving) "on" else "off"} · frame $frame · saved locally"
                )
            },
            onSetCanonicalKeyframeSpatialTangents = { compositionId, layerId, property, frame, inX, inY, inZ, outX, outY, outZ, spatialAuto ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerKeyframeSpatialTangentsEcproj(source, compositionId, layerId, "transform:$property", frame.toLong(), inX, inY, inZ, outX, outY, outZ, spatialAuto),
                    "Canonical $property motion path ${if (spatialAuto) "auto" else "manual"} · frame $frame · saved locally"
                )
            },
            onMoveCanonicalCurveKey = { compositionId, layerId, selector, fromFrame, toFrame ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.moveLayerKeyframeEcproj(source, compositionId, layerId, selector, fromFrame.toLong(), toFrame.toLong()),
                    "Canonical $selector keyframe moved $fromFrame to $toFrame · saved locally"
                )
            },
            onMoveCanonicalTransformKeyframe = { fromFrame, toFrame ->
                val source = importedEcproj
                val composition = canonicalProjectInfo?.compositions?.firstOrNull { comp -> comp.layers.any { it.id in canonicalSelectedIds } }
                if (source == null || composition == null) {
                    saveMessage = "Import a canonical .ecproj first"
                } else {
                    // The TRANSFORM lane aggregates every selected layer and
                    // property, so a drag only moves when exactly one key
                    // owns the grabbed frame; otherwise the inspector's
                    // per-property Move control or the curve graph disambiguates.
                    val candidates = composition.layers.filter { it.id in canonicalSelectedIds }.flatMap { layer ->
                        layer.transformKeyframes
                            .filter { (_, frames) -> fromFrame in frames }
                            .map { (property, _) -> layer.id to "transform:$property" }
                    }
                    val owner = com.codeair.effectcraft.data.CanonicalKeyframeDrag.resolveMove(candidates)
                    if (owner == null) {
                        saveMessage = "Dragged frame $fromFrame has no unique key · use the inspector Move control or curve graph"
                    } else if (fromFrame == toFrame) {
                        saveMessage = "Canonical ${owner.second} keyframe already at frame $toFrame"
                    } else {
                        applyCanonicalEdit(
                            NativeProjectBridge.moveLayerKeyframeEcproj(source, composition.id, owner.first, owner.second, fromFrame.toLong(), toFrame.toLong()),
                            "Canonical ${owner.second} keyframe moved $fromFrame to $toFrame · saved locally"
                        )
                    }
                }
            },
            onRemoveCanonicalCurveKey = { compositionId, layerId, selector, frame ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else {
                    val envelope = NativeProjectBridge.removeLayerCurveKeyframeEcproj(source, compositionId, layerId, selector, frame.toLong())
                    val response = runCatching { JSONObject(envelope ?: "") }.getOrNull()
                    val valid = response?.optBoolean("valid") == true
                    val removed = response?.optBoolean("removed") == true
                    if (!valid) {
                        saveMessage = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Unable to remove curve key"
                    } else if (!removed) {
                        saveMessage = "No canonical $selector keyframe · frame $frame"
                    } else {
                        applyCanonicalEdit(envelope, "Canonical $selector keyframe removed · frame $frame · saved locally")
                    }
                }
            },
            onSetCanonicalCurveHandle = { compositionId, layerId, selector, frame, side, speed, influence ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerKeyframeBezierEcproj(source, compositionId, layerId, selector, frame.toLong(), side, speed, influence),
                    "Canonical $selector Bezier $side handle set · frame $frame · saved locally"
                )
            },
            onAddCanonicalCurveKey = { compositionId, layerId, selector, frame, x, y, z ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else {
                    val edited = when {
                        selector == "transform:opacity" -> NativeProjectBridge.setLayerOpacityKeyframeEcproj(source, compositionId, layerId, frame.toLong(), x)
                        selector.startsWith("transform:") -> NativeProjectBridge.setLayerTransformKeyframeEcproj(
                            source, compositionId, layerId, selector.removePrefix("transform:"), frame.toLong(), x, y, z
                        )
                        selector.startsWith("param:") -> {
                            val uid = selector.removePrefix("param:").toLongOrNull()
                            if (uid == null) null else NativeProjectBridge.setLayerParamKeyframeEcproj(source, compositionId, layerId, uid, frame.toLong(), x, y, z)
                        }
                        else -> null
                    }
                    applyCanonicalEdit(edited, "Canonical $selector keyframe added · frame $frame · saved locally")
                }
            },
            onRemoveCanonicalLayerTransformKeyframe = { compositionId, layerId, property, frame ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else {
                    val envelope = NativeProjectBridge.removeLayerTransformKeyframeEcproj(source, compositionId, layerId, property, frame.toLong())
                    val removed = runCatching { JSONObject(envelope ?: "").optBoolean("removed", true) }.getOrDefault(true)
                    val valid = runCatching { JSONObject(envelope ?: "").optBoolean("valid") }.getOrDefault(false)
                    if (valid && !removed) {
                        saveMessage = "No canonical $property keyframe · frame $frame"
                    } else {
                        applyCanonicalEdit(envelope, "Canonical $property keyframe removed · frame $frame · saved locally")
                    }
                }
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
            onSetCanonicalLayerAnchor = { compositionId, layerId, x, y ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerAnchorEcproj(source, compositionId, layerId, x, y),
                    "Canonical anchor point updated · saved locally"
                )
            },
            onSetCanonicalLayerOrientation = { compositionId, layerId, x, y, z ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerOrientationEcproj(source, compositionId, layerId, x, y, z),
                    "Canonical orientation updated · saved locally"
                )
            },
            onSetCanonicalLayerRotationX = { compositionId, layerId, degrees ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerRotationXEcproj(source, compositionId, layerId, degrees),
                    "Canonical X rotation updated · saved locally"
                )
            },
            onSetCanonicalLayerRotationY = { compositionId, layerId, degrees ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerRotationYEcproj(source, compositionId, layerId, degrees),
                    "Canonical Y rotation updated · saved locally"
                )
            },
            onSetCanonicalLayerLocked = { compositionId, layerId, locked ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerLockedEcproj(source, compositionId, layerId, locked),
                    if (locked) "Canonical layer locked · saved locally" else "Canonical layer unlocked · saved locally"
                )
            },
            onSetCanonicalTextLayerText = { compositionId, layerId, text ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setTextLayerTextEcproj(source, compositionId, layerId, text),
                    "Canonical text updated · saved locally"
                )
            },
            onSetCanonicalTextLayerTextWholeLayer = { compositionId, layerId, text ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setTextLayerTextWholeLayerEcproj(source, compositionId, layerId, text),
                    "Canonical text updated everywhere · saved locally"
                )
            },
            onSetCanonicalTextLayerKeyframe = { compositionId, layerId, uid, frame, text, remove ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else if (remove) {
                    val envelope = NativeProjectBridge.removeLayerCurveKeyframeEcproj(source, compositionId, layerId, "param:$uid", frame.toLong())
                    val response = runCatching { JSONObject(envelope ?: "") }.getOrNull()
                    val valid = response?.optBoolean("valid") == true
                    val removed = response?.optBoolean("removed") == true
                    if (!valid) saveMessage = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Unable to remove Source Text keyframe"
                    else if (!removed) saveMessage = "No Source Text keyframe · frame $frame"
                    else applyCanonicalEdit(envelope, "Source Text keyframe removed · frame $frame · saved locally")
                } else applyCanonicalEdit(
                    NativeProjectBridge.setTextLayerTextKeyframeEcproj(source, compositionId, layerId, frame.toLong(), text),
                    "Source Text keyframe added · frame $frame · saved locally"
                )
            },
            onSetCanonicalTextLayerSize = { compositionId, layerId, size ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setTextLayerSizeEcproj(source, compositionId, layerId, size),
                    "Canonical text size updated · saved locally"
                )
            },
            onSetCanonicalTextLayerTracking = { compositionId, layerId, tracking ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setTextLayerTrackingEcproj(source, compositionId, layerId, tracking),
                    "Canonical text tracking updated · saved locally"
                )
            },
            onSetCanonicalTextLayerFont = { compositionId, layerId, font ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setTextLayerFontEcproj(source, compositionId, layerId, font),
                    "Canonical text font updated · saved locally"
                )
            },
            onSetCanonicalTextLayerFontKeyframe = { compositionId, layerId, uid, frame, font, remove ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else if (remove) {
                    val envelope = NativeProjectBridge.removeLayerCurveKeyframeEcproj(source, compositionId, layerId, "param:$uid", frame.toLong())
                    val response = runCatching { JSONObject(envelope ?: "") }.getOrNull()
                    val valid = response?.optBoolean("valid") == true
                    val removed = response?.optBoolean("removed") == true
                    if (!valid) saveMessage = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Unable to remove font keyframe"
                    else if (!removed) saveMessage = "No font keyframe · frame $frame"
                    else applyCanonicalEdit(envelope, "Font keyframe removed · frame $frame · saved locally")
                } else applyCanonicalEdit(
                    NativeProjectBridge.setTextLayerFontKeyframeEcproj(source, compositionId, layerId, frame.toLong(), font),
                    "Font keyframe added · frame $frame · saved locally"
                )
            },
            onSetCanonicalTextLayerTrackingKeyframe = { compositionId, layerId, uid, frame, tracking, remove ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else if (remove) {
                    val envelope = NativeProjectBridge.removeLayerCurveKeyframeEcproj(source, compositionId, layerId, "param:$uid", frame.toLong())
                    val response = runCatching { JSONObject(envelope ?: "") }.getOrNull()
                    val valid = response?.optBoolean("valid") == true
                    val removed = response?.optBoolean("removed") == true
                    if (!valid) saveMessage = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Unable to remove tracking keyframe"
                    else if (!removed) saveMessage = "No Source Text key · frame $frame"
                    else applyCanonicalEdit(envelope, "Source Text tracking key removed · frame $frame · saved locally")
                } else applyCanonicalEdit(
                    NativeProjectBridge.setTextLayerTrackingKeyframeEcproj(source, compositionId, layerId, frame.toLong(), tracking),
                    "Source Text tracking key added · frame $frame · saved locally"
                )
            },
            onSetCanonicalLayerScalarProperty = { compositionId, layerId, uid, value ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerScalarPropertyEcproj(source, compositionId, layerId, uid, value),
                    "Canonical parameter updated · saved locally"
                )
            },
            onSetCanonicalLayerVecProperty = { compositionId, layerId, uid, x, y, z ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerVecPropertyEcproj(source, compositionId, layerId, uid, x, y, z),
                    "Canonical parameter updated · saved locally"
                )
            },
            onSetCanonicalLayerColorProperty = { compositionId, layerId, uid, red, green, blue, alpha ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerColorPropertyEcproj(source, compositionId, layerId, uid, red, green, blue, alpha),
                    "Canonical color parameter updated · saved locally"
                )
            },
            onSetCanonicalLayerGradientProperty = { compositionId, layerId, uid, gradientJson ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerGradientPropertyEcproj(source, compositionId, layerId, uid, gradientJson),
                    "Canonical gradient parameter updated · saved locally"
                )
            },
            onSetCanonicalLayerPathKeyframe = { compositionId, layerId, uid, frame, pathJson, remove ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else if (remove) {
                    val envelope = NativeProjectBridge.removeLayerCurveKeyframeEcproj(source, compositionId, layerId, "param:$uid", frame.toLong())
                    val response = runCatching { JSONObject(envelope ?: "") }.getOrNull()
                    val valid = response?.optBoolean("valid") == true
                    val removed = response?.optBoolean("removed") == true
                    if (!valid) saveMessage = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Unable to remove ShapePath keyframe"
                    else if (!removed) saveMessage = "No ShapePath keyframe · frame $frame"
                    else applyCanonicalEdit(envelope, "ShapePath keyframe removed · frame $frame · saved locally")
                } else applyCanonicalEdit(
                    NativeProjectBridge.setLayerPathKeyframeEcproj(source, compositionId, layerId, uid, frame.toLong(), pathJson),
                    "ShapePath keyframe added · frame $frame · saved locally"
                )
            },
            onSetCanonicalLayerPathProperty = { compositionId, layerId, uid, pathJson ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerPathPropertyEcproj(source, compositionId, layerId, uid, pathJson),
                    "Canonical path parameter updated · saved locally"
                )
            },
            onSetCanonicalLayerReferenceProperty = { compositionId, layerId, uid, targetLayerId ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerReferencePropertyEcproj(source, compositionId, layerId, uid, targetLayerId),
                    "Canonical Layer Control updated · saved locally"
                )
            },
            onSetCanonicalLayerReferenceKeyframe = { compositionId, layerId, uid, frame, targetLayerId, remove ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else if (remove) {
                    val envelope = NativeProjectBridge.removeLayerCurveKeyframeEcproj(source, compositionId, layerId, "param:$uid", frame.toLong())
                    val response = runCatching { JSONObject(envelope ?: "") }.getOrNull()
                    val valid = response?.optBoolean("valid") == true
                    val removed = response?.optBoolean("removed") == true
                    if (!valid) saveMessage = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Unable to remove Layer Control keyframe"
                    else if (!removed) saveMessage = "No Layer Control keyframe · frame $frame"
                    else applyCanonicalEdit(envelope, "Layer Control keyframe removed · frame $frame · saved locally")
                } else applyCanonicalEdit(
                    NativeProjectBridge.setLayerReferenceKeyframeEcproj(source, compositionId, layerId, uid, frame.toLong(), targetLayerId),
                    "Layer Control hold keyframe added · frame $frame · saved locally"
                )
            },
            onSetCanonicalLayerColorKeyframe = { compositionId, layerId, uid, frame, red, green, blue, alpha, remove ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else if (remove) {
                    val envelope = NativeProjectBridge.removeLayerCurveKeyframeEcproj(source, compositionId, layerId, "param:$uid", frame.toLong())
                    val response = runCatching { JSONObject(envelope ?: "") }.getOrNull()
                    val valid = response?.optBoolean("valid") == true
                    val removed = response?.optBoolean("removed") == true
                    if (!valid) saveMessage = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Unable to remove color keyframe"
                    else if (!removed) saveMessage = "No color keyframe · frame $frame"
                    else applyCanonicalEdit(envelope, "Canonical color keyframe removed · frame $frame · saved locally")
                } else applyCanonicalEdit(
                    NativeProjectBridge.setLayerColorKeyframeEcproj(source, compositionId, layerId, uid, frame.toLong(), red, green, blue, alpha),
                    "Canonical color keyframe added · frame $frame · saved locally"
                )
            },
            onSetCanonicalLayerBoolProperty = { compositionId, layerId, uid, value ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerBoolPropertyEcproj(source, compositionId, layerId, uid, value),
                    "Canonical checkbox parameter updated · saved locally"
                )
            },
            onSetCanonicalLayerPopupProperty = { compositionId, layerId, uid, index ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else applyCanonicalEdit(
                    NativeProjectBridge.setLayerPopupPropertyEcproj(source, compositionId, layerId, uid, index),
                    "Canonical popup parameter updated · saved locally"
                )
            },
            onSetCanonicalLayerBoolKeyframe = { compositionId, layerId, uid, frame, value, remove ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else if (remove) {
                    val envelope = NativeProjectBridge.removeLayerCurveKeyframeEcproj(source, compositionId, layerId, "param:$uid", frame.toLong())
                    val response = runCatching { JSONObject(envelope ?: "") }.getOrNull()
                    val valid = response?.optBoolean("valid") == true
                    val removed = response?.optBoolean("removed") == true
                    if (!valid) saveMessage = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Unable to remove checkbox keyframe"
                    else if (!removed) saveMessage = "No checkbox keyframe · frame $frame"
                    else applyCanonicalEdit(envelope, "Canonical checkbox keyframe removed · frame $frame · saved locally")
                } else applyCanonicalEdit(
                    NativeProjectBridge.setLayerBoolKeyframeEcproj(source, compositionId, layerId, uid, frame.toLong(), value),
                    "Canonical checkbox hold keyframe added · frame $frame · saved locally"
                )
            },
            onSetCanonicalLayerPopupKeyframe = { compositionId, layerId, uid, frame, index, remove ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else if (remove) {
                    val envelope = NativeProjectBridge.removeLayerCurveKeyframeEcproj(source, compositionId, layerId, "param:$uid", frame.toLong())
                    val response = runCatching { JSONObject(envelope ?: "") }.getOrNull()
                    val valid = response?.optBoolean("valid") == true
                    val removed = response?.optBoolean("removed") == true
                    if (!valid) saveMessage = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Unable to remove popup keyframe"
                    else if (!removed) saveMessage = "No popup keyframe · frame $frame"
                    else applyCanonicalEdit(envelope, "Canonical popup keyframe removed · frame $frame · saved locally")
                } else applyCanonicalEdit(
                    NativeProjectBridge.setLayerPopupKeyframeEcproj(source, compositionId, layerId, uid, frame.toLong(), index),
                    "Canonical popup hold keyframe added · frame $frame · saved locally"
                )
            },
            onRelinkCanonicalAsset = { itemId ->
                pendingRelinkItemId = itemId
                relinkAssetLauncher.launch(arrayOf("*/*"))
            },
            canonicalSelectedLayerIds = canonicalSelectedIds,
            onCanonicalSelectionChange = { canonicalSelectedIds = it },
            canUndoCanonical = canonicalUndoStack.isNotEmpty(),
            canRedoCanonical = canonicalRedoStack.isNotEmpty(),
            onUndoCanonical = {
                val current = importedEcproj
                if (current != null) {
                    val step = CanonicalEditHistory.undo(
                        canonicalUndoStack, canonicalRedoStack,
                        CanonicalEditSnapshot(current, canonicalSelectedIds)
                    )
                    if (step != null) {
                        runCatching {
                            commitCanonicalSource(step.snapshot.projectJson, "Canonical undo · saved locally", step.snapshot.selectedLayerIds)
                        }.onSuccess {
                            canonicalUndoStack = step.undoStack
                            canonicalRedoStack = step.redoStack
                        }.onFailure { error -> saveMessage = "Canonical undo failed · ${error.message ?: "invalid project"}" }
                    }
                }
            },
            onRedoCanonical = {
                val current = importedEcproj
                if (current != null) {
                    val step = CanonicalEditHistory.redo(
                        canonicalUndoStack, canonicalRedoStack,
                        CanonicalEditSnapshot(current, canonicalSelectedIds)
                    )
                    if (step != null) {
                        runCatching {
                            commitCanonicalSource(step.snapshot.projectJson, "Canonical redo · saved locally", step.snapshot.selectedLayerIds)
                        }.onSuccess {
                            canonicalUndoStack = step.undoStack
                            canonicalRedoStack = step.redoStack
                        }.onFailure { error -> saveMessage = "Canonical redo failed · ${error.message ?: "invalid project"}" }
                    }
                }
            },
            onSetCanonicalLayerOpacityForSelection = { compositionId, layerIds, opacityPercent ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else {
                    val layers = canonicalProjectInfo?.compositions?.firstOrNull { it.id == compositionId }?.layers ?: emptyList()
                    val steps = layers.filter { it.id in layerIds && !it.locked }
                        .map { layer ->
                            { current: String -> NativeProjectBridge.setLayerOpacityEcproj(current, compositionId, layer.id, opacityPercent) }
                        }
                    if (steps.isEmpty()) saveMessage = "No editable selected layers"
                    else applyCanonicalEditBatch(steps, "Canonical opacity updated for ${steps.size} layer(s) · saved locally")
                }
            },
            onSetCanonicalLayerVisibilityForSelection = { compositionId, layerIds, visible ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else {
                    val layers = canonicalProjectInfo?.compositions?.firstOrNull { it.id == compositionId }?.layers ?: emptyList()
                    val steps = layers.filter { it.id in layerIds && !it.locked }
                        .map { layer ->
                            { current: String -> NativeProjectBridge.setLayerVisibilityEcproj(current, compositionId, layer.id, visible) }
                        }
                    if (steps.isEmpty()) saveMessage = "No editable selected layers"
                    else applyCanonicalEditBatch(steps, "Canonical visibility updated for ${steps.size} layer(s) · saved locally")
                }
            },
            onGestureEditCanonicalSelection = { compositionId, positions, scales, rotations ->
                val source = importedEcproj
                if (source == null) saveMessage = "Import a canonical .ecproj first"
                else {
                    val steps = mutableListOf<(String) -> String?>()
                    positions.forEach { (layerId, position) ->
                        steps.add { current: String -> NativeProjectBridge.setLayerPositionEcproj(current, compositionId, layerId, position.first, position.second) }
                    }
                    scales.forEach { (layerId, scale) ->
                        steps.add { current: String -> NativeProjectBridge.setLayerScaleEcproj(current, compositionId, layerId, scale.first, scale.second) }
                    }
                    rotations.forEach { (layerId, degrees) ->
                        steps.add { current: String -> NativeProjectBridge.setLayerRotationEcproj(current, compositionId, layerId, degrees) }
                    }
                    if (steps.isEmpty()) saveMessage = "No editable selected layers"
                    else applyCanonicalEditBatch(steps, "Canonical gesture edit applied · saved locally")
                }
            },
            onRenderCanonicalFrame = { compositionId, frame ->
                // Session-backed: the parsed project, MediaPool and preview
                // size live natively for the whole workspace lifetime. When
                // no session is open (no canonical project, or native
                // unavailable) this returns null and the viewer keeps its
                // honest "not rendered / unavailable" label. A render-budget
                // violation is different from unavailability: it must surface
                // as editor status, never crash the preview coroutine and
                // never silently drop the frame.
                runCatching {
                    renderSessionHolder.session?.render(compositionId, frame)
                }.onFailure { failure ->
                    if (failure is BudgetViolationException) {
                        saveMessage = "Preview blocked by render budget · ${failure.message}"
                    } else {
                        throw failure
                    }
                }.getOrNull()
            },
            onQueryBackendStatus = {
                renderSessionHolder.session?.backendStatus()?.displayText()
            },
            onQueryMediaCacheStatus = {
                renderSessionHolder.session?.mediaPoolStats()?.displayText()
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
            },
            onVerifyOfflineAssets = {
                if (!mediaVerificationInProgress) {
                    mediaVerificationInProgress = true
                    saveMessage = "Verifying offline media integrity…"
                    mediaVerificationJob = exportScope.launch {
                        try {
                            val invalid = withContext(Dispatchers.IO) { offlineAssetStore.verifyAll() }
                            offlineAssets = offlineAssetStore.list()
                            saveMessage = if (invalid.isEmpty()) {
                                "Offline media verified · ${offlineAssets.size} asset(s) intact"
                            } else {
                                "Media integrity failed · ${invalid.size} asset(s) missing or corrupted"
                            }
                        } catch (failure: Throwable) {
                            saveMessage = "Media verification failed · ${failure.message ?: "storage error"}"
                        } finally {
                            mediaVerificationInProgress = false
                            mediaVerificationJob = null
                        }
                    }
                }
            },
            isVerifyingOfflineAssets = mediaVerificationInProgress
        )
    }
}
