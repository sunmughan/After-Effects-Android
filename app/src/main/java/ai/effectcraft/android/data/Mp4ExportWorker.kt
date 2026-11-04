package ai.effectcraft.android.data

import ai.effectcraft.android.engine.NativeSession
import ai.effectcraft.android.engine.RenderedFramePng
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Lifecycle-safe MP4 export worker (the foreground-service alternative the
 * M5 brief allows). It owns no Activity or composition scope: the UI holds
 * its job in remembered state, and the Activity already handles orientation
 * and screen-size configuration changes itself (see the manifest), so a
 * rotation neither recreates the Activity nor cancels the job. Process
 * death is different and is reported honestly, not papered over: a
 * MediaMuxer stream cannot be resumed or appended after a kill, so there
 * is no partial-MP4 resume — [StaleVideoExportCleanup] discards the
 * abandoned staged file at startup and the UI reports the interruption,
 * and the user restarts the export. A foreground service would keep the
 * process alive longer but would not change that recovery story, so it is
 * left as follow-up work, not claimed here.
 *
 * The worker renders through one [NativeSession] (same path as the PNG
 * exporter, with the same bounded native render side), encodes and muxes
 * to a staged app-private file, and validates that file before returning.
 * The caller copies only the validated file to SAF and deletes the
 * staging file in a finally block, exactly like the ZIP exporter.
 */
object Mp4ExportWorker {
    data class StagedResult(
        val framesWritten: Int,
        val audioStatus: AudioStatus,
        val videoSamples: Long,
        val durationUs: Long,
        val audioSamplesWritten: Long
    )

    data class AudioResolution(
        val file: File?,
        val hasAudioItems: Boolean
    )

    /**
     * Resolves the first usable offline audio payload for a composition:
     * an item flagged hasAudio whose native path names a content-addressed
     * offline payload (first 64 hex chars, the same rule the reference
     * refresh uses). Returns the first resolvable file; when audio items
     * exist but none resolve, the file is null and [AudioResolution]
     * still reports audio items, so the export is visibly video-only
     * instead of silently dropping the audio.
     */
    fun resolveAudio(
        items: List<CanonicalProjectItemInfo>,
        fileFor: (sha256: String) -> File?
    ): AudioResolution {
        val audioItems = items.filter { it.hasAudio && !it.missing && it.path != null }
        if (audioItems.isEmpty()) return AudioResolution(null, false)
        for (item in audioItems) {
            val name = item.path!!.substringAfterLast('/')
            if (name.length > 65 && name.substring(0, 64).matches(Regex("[a-f0-9]{64}"))) {
                val file = runCatching { fileFor(name.substring(0, 64)) }.getOrNull()
                if (file != null) return AudioResolution(file, true)
            }
        }
        return AudioResolution(null, true)
    }

    /**
     * Renders, encodes, muxes and validates one MP4 into [stagedFile].
     * [compositionFrameRateNum]/[compositionFrameRateDen] are the canonical
     * composition frame rate, used only for the audio clip range so audio
     * stays in sync when the export frame rate differs.
     */
    fun exportToStaged(
        plan: RenderQueuePlan,
        projectJson: String,
        compositionId: Long,
        audioFile: File?,
        hasAudioItems: Boolean,
        stagedFile: File,
        compositionFrameRateNum: Int,
        compositionFrameRateDen: Int,
        isCancelled: () -> Boolean = { false },
        onProgress: (completedFrames: Int, percent: Int) -> Unit = { _, _ -> }
    ): StagedResult {
        Mp4ExportRequirements.checkPlan(plan)
        val den = compositionFrameRateDen.coerceAtLeast(1)
        val num = compositionFrameRateNum.coerceAtLeast(1)
        val audioRangeStartUs = plan.settings.startFrame.toLong() * 1_000_000L * den / num
        val audioRangeEndUs = (plan.settings.endFrame.toLong() + 1L) * 1_000_000L * den / num
        val nativeRenderSide = minOf(
            maxOf(plan.settings.width, plan.settings.height),
            RenderedFramePng.MAX_NATIVE_RENDER_SIDE
        )
        val session = NativeSession.open(projectJson, nativeRenderSide)
            ?: error("Native renderer unavailable for this project")
        try {
            val encoded = Mp4VideoExporter.export(
                plan = plan,
                outputFile = stagedFile,
                renderEnvelope = { frame ->
                    if (isCancelled()) throw CancellationException("MP4 export cancelled")
                    session.render(compositionId, frame.toLong())
                },
                audioFile = audioFile,
                audioRangeStartUs = audioRangeStartUs,
                audioRangeEndUs = audioRangeEndUs,
                isCancelled = isCancelled,
                onProgress = onProgress
            )
            val audioStatus = when {
                !hasAudioItems -> AudioStatus.NO_AUDIO_IN_COMPOSITION
                audioFile == null -> AudioStatus.AUDIO_FILE_UNAVAILABLE
                encoded.audioTrackIncluded -> AudioStatus.INCLUDED
                else -> AudioStatus.AUDIO_TRACK_UNSUPPORTED
            }
            val validated = Mp4ExportValidator.validate(
                stagedFile, plan, expectAudio = encoded.audioTrackIncluded
            )
            return StagedResult(
                framesWritten = encoded.framesWritten,
                audioStatus = audioStatus,
                videoSamples = validated.videoSamples,
                durationUs = validated.durationUs,
                audioSamplesWritten = encoded.audioSamplesWritten
            )
        } finally {
            session.close()
        }
    }
}
