package com.codeair.effectcraft.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import org.json.JSONObject

/**
 * Foreground-service export pipeline for the PNG sequence ZIP only.
 *
 * This is the process-interruption part of M5-L8, and nothing else: no
 * codec, no audio, no MP4. It wraps the existing staged export discipline
 * (render to an app-private staging ZIP, validate it, copy only the
 * validated file to the destination) with two additions the in-app
 * coroutine path does not have:
 *
 * 1. An interruption marker. [begin] writes `<staging>.exporting` before
 *    any frame renders. A normal finish, cancel, or failure deletes the
 *    staging file and the marker in a finally block. A process kill
 *    bypasses that finally block, so the marker plus a partial staging
 *    file is the detectable partial state a kill leaves behind.
 * 2. Recovery, not resume. [recover] deletes stale marker/staging pairs
 *    and orphan markers. A killed export is never resumed: a streaming
 *    ZIP cannot be safely appended after a kill, and the validated-copy
 *    rule means the destination never received the partial file, so the
 *    only honest recovery is cleanup plus a user restart from frame 0.
 *    What survives a kill, stated plainly: the source project, the
 *    destination document only if the copy had already completed (in
 *    which case the export had succeeded), and the partial staging file
 *    plus marker for cleanup. What does not survive: render progress,
 *    the in-memory frame position, and any partial SAF copy, whose
 *    provider-side partial (if the kill landed mid-copy) cannot be
 *    rolled back by this process; that provider-copy case is a separate
 *    M5 line and is not claimed here.
 *
 * The marker age rule matches [StaleSequenceExportCleanup]: a marker
 * younger than the retention window is retained, because it may belong
 * to a live export. Recovery never deletes a live export's files.
 * The caller owns destination rollback: [run] attempts
 * [rollbackDestination] on every failure or cancel after [begin],
 * best effort, because a CreateDocument destination is an empty
 * document until the copy writes it and must not be left looking like
 * a finished export.
 */
object ForegroundPngSequenceExport {
    enum class Phase { RENDERING, VALIDATING, COPYING, DONE, CANCELLED, FAILED }

    data class Progress(
        val completedFrames: Int,
        val percent: Int,
        val phase: Phase,
        val completedBytes: Long = 0L,
        val totalBytes: Long = 0L
    )

    data class Result(val framesValidated: Int, val uncompressedBytes: Long, val destinationBytes: Long)

    data class StartedExport(val stagingFile: File, val markerFile: File, val plan: RenderQueuePlan)

    data class RecoveryResult(
        val cleaned: Int,
        val retained: Int,
        val orphanMarkersRemoved: Int,
        val failed: List<String>
    )

    const val MARKER_SUFFIX = ".exporting"

    fun begin(plan: RenderQueuePlan, stagingDir: File): StartedExport {
        require(stagingDir.isDirectory || stagingDir.mkdirs()) { "Staging directory is not usable" }
        val stagingFile = File(
            stagingDir,
            StaleSequenceExportCleanup.FILE_PREFIX + System.nanoTime() + StaleSequenceExportCleanup.FILE_SUFFIX
        )
        require(stagingFile.createNewFile()) { "Staging file already exists" }
        val markerFile = File(stagingDir, stagingFile.name + MARKER_SUFFIX)
        val marker = JSONObject().apply {
            put("format", "png-sequence")
            put("width", plan.settings.width)
            put("height", plan.settings.height)
            put("frameRate", plan.settings.frameRate)
            put("startFrame", plan.settings.startFrame)
            put("endFrame", plan.settings.endFrame)
            put("frameCount", plan.frameCount)
            put("startedAtMillis", System.currentTimeMillis())
        }
        val markerTemp = File(stagingDir, markerFile.name + ".tmp")
        try {
            FileOutputStream(markerTemp).use { output ->
                output.write(marker.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(markerTemp.toPath(), markerFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(markerTemp.toPath(), markerFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            markerTemp.delete()
            markerFile.delete()
            stagingFile.delete()
            throw failure
        }
        return StartedExport(stagingFile, markerFile, plan)
    }

    /**
     * Runs one PNG sequence export end to end on the calling thread, so
     * the foreground service can run it off the UI thread without this
     * object owning any Android threading. [copyToDestination] writes the
     * validated staging file to the final destination and reports bytes;
     * [rollbackDestination] deletes a partial or empty destination.
     */
    fun run(
        plan: RenderQueuePlan,
        stagingDir: File,
        renderPng: (frame: Int) -> ByteArray,
        copyToDestination: (staging: File, ensureActive: () -> Unit, onBytesCopied: (Long) -> Unit) -> Long,
        rollbackDestination: () -> Unit,
        isCancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {}
    ): Result {
        // Next-export sweep, scoped to this export's own format only, so a
        // live copy marker of another export type is never touched. A kill
        // during an earlier final copy left a `.copying` marker plus staging
        // behind; clearing it here is what makes a restart from frame 0
        // start clean even if the startup sweep did not run. The marker
        // itself is owned by FinalCopyRecovery (M5-L7); the render marker
        // lifecycle below is unchanged.
        runCatching {
            FinalCopyRecovery.recover(stagingDir, formats = setOf(FinalCopyRecovery.FORMAT_PNG_SEQUENCE))
        }
        val started = begin(plan, stagingDir)
        var copyMarker: File? = null
        try {
            checkNotCancelled(isCancelled)
            FileOutputStream(started.stagingFile).use { archiveOutput ->
                PngSequenceZipExporter.export(
                    plan = plan,
                    output = archiveOutput,
                    renderPng = renderPng,
                    isCancelled = isCancelled,
                    onProgress = { completed, percent ->
                        onProgress(Progress(completed, percent, Phase.RENDERING))
                    }
                )
            }
            checkNotCancelled(isCancelled)
            onProgress(Progress(plan.frameCount, 100, Phase.VALIDATING))
            val validated = PngSequenceZipValidator.validate(started.stagingFile, plan)
            checkNotCancelled(isCancelled)
            val stagedBytes = started.stagingFile.length().coerceAtLeast(1L)
            // M5-L7: publish the copy marker immediately before the final
            // SAF copy. A kill from here on leaves marker plus staging
            // behind for startup or next-export recovery. A marker failure
            // is best effort: the copy still runs, only undetected.
            copyMarker = runCatching {
                FinalCopyRecovery.beginCopy(started.stagingFile, FinalCopyRecovery.FORMAT_PNG_SEQUENCE)
            }.getOrNull()
            onProgress(Progress(plan.frameCount, 0, Phase.COPYING, 0L, stagedBytes))
            val destinationBytes = copyToDestination(
                started.stagingFile,
                { checkNotCancelled(isCancelled) },
                { copied ->
                    val bounded = copied.coerceIn(0L, stagedBytes)
                    val percent = ((bounded * 100L) / stagedBytes).toInt().coerceIn(0, 100)
                    onProgress(Progress(plan.frameCount, percent, Phase.COPYING, bounded, stagedBytes))
                }
            )
            onProgress(Progress(plan.frameCount, 100, Phase.COPYING, stagedBytes, stagedBytes))
            onProgress(Progress(plan.frameCount, 100, Phase.DONE))
            return Result(validated.framesValidated, validated.uncompressedBytes, destinationBytes)
        } catch (failure: Throwable) {
            if (failure is CancellationException) {
                onProgress(Progress(plan.frameCount, 100, Phase.CANCELLED))
            } else {
                onProgress(Progress(plan.frameCount, 100, Phase.FAILED))
            }
            runCatching(rollbackDestination)
            throw failure
        } finally {
            started.stagingFile.delete()
            started.markerFile.delete()
            copyMarker?.let { runCatching { FinalCopyRecovery.finishCopy(it) } }
        }
    }

    /** Counts interruption markers in [stagingDir], live or stale. */
    fun pendingInterruptions(stagingDir: File): Int {
        if (!stagingDir.isDirectory) return 0
        return stagingDir.listFiles().orEmpty().count { it.isFile && it.name.endsWith(MARKER_SUFFIX) }
    }

    /**
     * Cleans up only stale interruption state. A marker is stale when its
     * own age reaches [maxAgeMillis]; a younger marker is retained even
     * if its staging file is missing, because a live export may still be
     * between begin and its first write. A stale marker with no staging
     * file counts as an orphan removal, not a cleaned export.
     */
    fun recover(
        stagingDir: File,
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = StaleSequenceExportCleanup.DEFAULT_MAX_AGE_MILLIS
    ): RecoveryResult {
        require(maxAgeMillis > 0L) { "Staging archive retention must be positive" }
        if (!stagingDir.isDirectory) return RecoveryResult(0, 0, 0, emptyList())
        var cleaned = 0
        var retained = 0
        var orphanMarkersRemoved = 0
        val failed = mutableListOf<String>()
        stagingDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(MARKER_SUFFIX) }
            .forEach { marker ->
                val modified = marker.lastModified()
                val age = if (modified <= 0L || modified > nowMillis) 0L else nowMillis - modified
                if (age < maxAgeMillis) {
                    retained++
                    return@forEach
                }
                val stagingName = marker.name.removeSuffix(MARKER_SUFFIX)
                val staging = File(stagingDir, stagingName)
                if (!staging.isFile) {
                    if (marker.delete()) orphanMarkersRemoved++ else failed += marker.name
                    return@forEach
                }
                val stagingDeleted = staging.delete()
                val markerDeleted = marker.delete()
                if (stagingDeleted && markerDeleted) cleaned++ else failed += marker.name
            }
        return RecoveryResult(cleaned, retained, orphanMarkersRemoved, failed)
    }

    private fun checkNotCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("PNG sequence export cancelled")
    }
}
