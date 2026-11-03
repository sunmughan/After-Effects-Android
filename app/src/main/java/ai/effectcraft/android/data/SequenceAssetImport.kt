package ai.effectcraft.android.data

import java.io.InputStream

/**
 * Imports an image sequence as one logical manifest entry: every frame is
 * imported through [OfflineAssetStore] (so identical frames deduplicate to a
 * single payload) and the ordered frame list is recorded on the manifest.
 *
 * Rollback semantics, deliberate and asymmetric: if a frame import fails,
 * the manifest entry is restored to its previous value and the failure is
 * rethrown, but payloads imported before the failure are NOT deleted. A
 * payload hash may already be referenced by another project, so only
 * garbage collection — which reads every manifest — may delete payloads.
 */
object SequenceAssetImport {
    data class FrameInput(val name: String, val input: InputStream, val mimeType: String? = null)

    fun importSequence(
        store: OfflineAssetStore,
        manifest: ProjectAssetManifest,
        itemId: Long,
        frames: List<FrameInput>,
        lastKnownPath: String? = null
    ): ProjectAssetManifest.Entry {
        ProjectAssetManifest.validateItemId(itemId)
        require(frames.isNotEmpty()) { "An image sequence needs at least one frame" }
        frames.forEach { ProjectAssetManifest.validateFrameName(it.name) }
        require(frames.map { it.name }.toSet().size == frames.size) {
            "Image sequence frame names must be unique"
        }
        val previous = manifest.entryFor(itemId)
        try {
            val imported = frames.map { frame ->
                ProjectAssetManifest.Frame(frame.name, store.import(frame.input, frame.name, frame.mimeType).sha256)
            }
            return manifest.linkSequence(itemId, imported, lastKnownPath)
        } catch (failure: Throwable) {
            if (previous == null) manifest.restoreAbsent(itemId) else manifest.restore(previous)
            throw failure
        }
    }
}
