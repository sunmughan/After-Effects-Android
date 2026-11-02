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
            onSave = {
                runCatching { projectStore.save(project) }
                    .onSuccess { saveMessage = "Saved locally · ${it.name}" }
                    .onFailure { saveMessage = "Save failed · ${it.message ?: "storage error"}" }
            },
            onNewProject = {
                project = ProjectDocument.starter()
                selectedLayerId = project.layers.firstOrNull()?.id.orEmpty()
                playheadFrame = 0
                isPlaying = false
                saveMessage = "New offline composition"
            },
            onAddLayer = {
                val layer = LayerDocument.newShape(project.layers.size + 1)
                project = project.copy(layers = project.layers + layer)
                selectedLayerId = layer.id
                saveMessage = "Unsaved changes"
            },
            onSelectLayer = { selectedLayerId = it },
            onDeleteLayer = {
                if (project.layers.size > 1 && selectedLayer != null) {
                    project = project.copy(layers = project.layers.filterNot { it.id == selectedLayer.id })
                    selectedLayerId = project.layers.first().id
                    saveMessage = "Layer removed · unsaved"
                } else {
                    saveMessage = "A composition must keep at least one layer"
                }
            },
            onMoveLayer = { offset ->
                project = project.copy(
                    layers = ProjectEditorActions.moveLayer(project.layers, selectedLayerId, offset)
                )
                saveMessage = "Unsaved changes"
            },
            onOpacityChange = { opacity ->
                project = project.copy(
                    layers = project.layers.map { layer ->
                        if (layer.id == selectedLayerId) layer.copy(opacity = opacity) else layer
                    }
                )
                saveMessage = "Unsaved changes"
            },
            onScrub = { playheadFrame = it.coerceIn(0, project.durationFrames - 1) },
            onTogglePlayback = { isPlaying = !isPlaying }
        )
    }
}
