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
import ai.effectcraft.android.data.LayerDocument
import ai.effectcraft.android.data.ProjectEditorActions
import ai.effectcraft.android.data.ProjectDocument
import ai.effectcraft.android.data.ProjectFileFormats
import kotlinx.coroutines.delay

private data class EditorHistorySnapshot(
    val project: ProjectDocument,
    val selectedLayerId: String,
    val playheadFrame: Int
)

@Composable
fun EffectCraftApp(projectStore: DraftProjectStore) {
    val context = LocalContext.current
    val ecprojStore = remember(context) { EcprojDocumentStore(context) }
    val offlineAssetStore = remember(context) { OfflineAssetStore(File(context.filesDir, "media-library")) }
    val offlineAssetReferences = remember(context) { OfflineAssetReferences(File(context.filesDir, "media-library/references.properties")) }
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

    LaunchedEffect(importedEcproj) {
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
                importedEcproj?.let { NativeProjectBridge.renderEcprojFrame(it, compositionId, frame, 512) }
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
