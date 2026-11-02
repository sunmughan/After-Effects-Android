package ai.effectcraft.android.ui

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
import ai.effectcraft.android.data.DraftProjectStore
import ai.effectcraft.android.data.LayerDocument
import ai.effectcraft.android.data.ProjectEditorActions
import ai.effectcraft.android.data.ProjectDocument
import kotlinx.coroutines.delay

@Composable
fun EffectCraftApp(projectStore: DraftProjectStore) {
    var project by remember {
        mutableStateOf(projectStore.loadLatest() ?: ProjectDocument.starter())
    }
    var selectedLayerId by remember {
        mutableStateOf(project.layers.firstOrNull()?.id.orEmpty())
    }
    var playheadFrame by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("Stored on this device") }
    var undoStack by remember { mutableStateOf(emptyList<ProjectDocument>()) }
    var redoStack by remember { mutableStateOf(emptyList<ProjectDocument>()) }

    fun applyProjectChange(next: ProjectDocument, message: String = "Unsaved changes") {
        undoStack = (undoStack + project).takeLast(50)
        redoStack = emptyList()
        project = next
        saveMessage = message
    }

    LaunchedEffect(isPlaying, project.durationFrames) {
        while (isPlaying) {
            delay(33L)
            playheadFrame = (playheadFrame + 1) % project.durationFrames.coerceAtLeast(1)
        }
    }

    val selectedLayer = project.layers.firstOrNull { it.id == selectedLayerId }

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
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            onUndo = {
                if (undoStack.isNotEmpty()) {
                    redoStack = redoStack + project
                    project = undoStack.last()
                    undoStack = undoStack.dropLast(1)
                    if (project.layers.none { it.id == selectedLayerId }) selectedLayerId = project.layers.firstOrNull()?.id.orEmpty()
                    saveMessage = "Undo · unsaved"
                }
            },
            onRedo = {
                if (redoStack.isNotEmpty()) {
                    undoStack = (undoStack + project).takeLast(50)
                    project = redoStack.last()
                    redoStack = redoStack.dropLast(1)
                    if (project.layers.none { it.id == selectedLayerId }) selectedLayerId = project.layers.firstOrNull()?.id.orEmpty()
                    saveMessage = "Redo · unsaved"
                }
            },
            onSave = {
                runCatching { projectStore.save(project) }
                    .onSuccess { saveMessage = "Saved locally · ${it.name}" }
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
                    applyProjectChange(project.copy(layers = project.layers.filterNot { it.id == selectedLayer.id }), "Layer removed · unsaved")
                    selectedLayerId = project.layers.first().id
                } else {
                    saveMessage = "A composition must keep at least one layer"
                }
            },
            onMoveLayer = { offset ->
                applyProjectChange(project.copy(
                    layers = ProjectEditorActions.moveLayer(project.layers, selectedLayerId, offset)
                ))
            },
            onOpacityChange = { opacity ->
                applyProjectChange(project.copy(
                    layers = project.layers.map { layer ->
                        if (layer.id == selectedLayerId) layer.copy(opacity = opacity) else layer
                    }
                ))
            },
            onScrub = { playheadFrame = it.coerceIn(0, project.durationFrames - 1) },
            onTogglePlayback = { isPlaying = !isPlaying }
        )
    }
}
