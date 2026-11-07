package com.codeair.effectcraft.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/**
 * Lifecycle-safe MP4 export worker built on the sibling [Mp4SequenceExporter].
 *
 * The worker owns no Activity or composition scope: the UI holds its job in
 * remembered state, and the Activity already handles orientation and
 * screen-size configuration changes itself (see the manifest), so a rotation
 * neither recreates the Activity nor cancels the job. Process death is
 * reported honestly, not papered over: a MediaMuxer stream cannot be resumed
 * or appended after a kill, so there is no partial-MP4 resume —
 * [StaleVideoExportCleanup] discards the abandoned staged file at startup
 * and the UI reports that the export must be restarted. A foreground service
 * would keep the process alive longer but would not change that recovery
 * story, so it is left as follow-up work, not claimed here.
 *
 * Flow, mirroring the ZIP exporter's staged-integrity discipline:
 * 1. Encode video-only into [stagedFile] with the sibling exporter (real
 *    MediaCodec H.264 + MediaMuxer, frames from the caller's render path).
 * 2. Validate the staged file with [Mp4ExportValidator] BEFORE any SAF copy.
 * 3. When an AAC track is available, re-mux video + AAC sample-for-sample
 *    into a second staged file (audio timestamps shifted into the export
 *    range), validate that file with audio expected, and only then promote
 *    it over [stagedFile]. When the AAC track yields zero samples inside the
 *    range, the re-muxed file is discarded and the validated video-only
 *    file stands — a valid video export must never fail only because its
 *    audio does not overlap the exported range.
 * 4. The caller copies only the validated file to SAF and deletes the
 *    staging file in a finally block. Cancellation is cooperative: checks
 *    run before every frame render and inside the copy loops.
 *
 * Audio has two paths (see [AudioTrackSelection] and [AudioMixdown]):
 * the legacy path passes an AAC track through sample-for-sample, and
 * the editor-audio path decodes the draft's audio clips (AAC, WAV,
 * MP3, FLAC, Opus) to PCM, mixes them to one AAC track honouring
 * timeline position, trim, gain, fades and mute, and muxes that in.
 * A missing, undecodable or unsupported clip source fails the export
 * with its reason. No audio track is ever synthesized: silence in the
 * mix is real zero samples where no clip covers, and the video-only
 * reason is always visible via [AudioStatus.describe], never silent.
 */
object Mp4ExportWorker {
    data class StagedResult(
        val framesWritten: Int,
        val audioStatus: AudioStatus,
        val videoSamples: Long,
        val durationUs: Long,
        val audioSamplesWritten: Long,
        val audioFirstPtsUs: Long? = null,
        val audioLastPtsUs: Long? = null,
        val audioTrackDurationUs: Long? = null
    )

    data class AudioResolution(
        val file: File?,
        val hasAudioItems: Boolean
    )

    /**
     * The editor's audio clips for a mixdown export. Non-null only when
     * the draft timeline applies to this export (same duration as the
     * exported composition) and the draft holds at least one audio clip:
     * the clips are then authoritative and replace the legacy AAC
     * passthrough for this export.
     */
    data class AudioMixRequest(
        val clips: List<AudioClipDocument>,
        val frameRateNum: Int,
        val frameRateDen: Int
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
        verifyPayload: (sha256: String, file: File) -> Boolean = { _, file -> file.isFile && file.canRead() },
        fileFor: (sha256: String) -> File?
    ): AudioResolution {
        val audioItems = items.filter { it.hasAudio && !it.missing && it.path != null }
        if (audioItems.isEmpty()) return AudioResolution(null, false)
        for (item in audioItems) {
            val name = item.path!!.substringAfterLast('/')
            if (name.length > 65 && name.substring(0, 64).matches(Regex("[a-f0-9]{64}"))) {
                val hash = name.substring(0, 64)
                val candidate = runCatching { fileFor(hash) }.getOrNull()
                val file = candidate?.takeIf { it.isFile && it.canRead() }
                if (file != null && runCatching { verifyPayload(hash, file) }.getOrDefault(false)) {
                    return AudioResolution(file, true)
                }
            }
        }
        return AudioResolution(null, true)
    }

    /**
     * Export-range bounds in composition-timeline microseconds, from the
     * canonical composition frame rate — not the export frame rate — so
     * audio stays in sync when the two differ.
     */
    fun audioRangeUs(
        plan: RenderQueuePlan,
        compositionFrameRateNum: Int,
        compositionFrameRateDen: Int
    ): Pair<Long, Long> {
        val den = compositionFrameRateDen.coerceAtLeast(1).toLong()
        val num = compositionFrameRateNum.coerceAtLeast(1).toLong()
        val startUs = plan.settings.startFrame.toLong() * 1_000_000L * den / num
        val endUs = (plan.settings.endFrame.toLong() + 1L) * 1_000_000L * den / num
        return startUs to endUs
    }

    fun exportToStaged(
        plan: RenderQueuePlan,
        stagedFile: File,
        renderRgba: (frame: Int) -> ByteArray,
        audioFile: File?,
        hasAudioItems: Boolean,
        compositionFrameRateNum: Int,
        compositionFrameRateDen: Int,
        audioMix: AudioMixRequest? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (completedFrames: Int, percent: Int) -> Unit = { _, _ -> }
    ): StagedResult {
        Mp4ExportRequirements.checkPlan(plan)
        val encoded = FileOutputStream(stagedFile).use { stream ->
            Mp4SequenceExporter.export(
                plan = plan,
                output = stream.fd,
                renderRgba = { frame ->
                    if (isCancelled()) throw CancellationException("MP4 export cancelled")
                    renderRgba(frame)
                },
                isCancelled = isCancelled,
                onProgress = onProgress
            )
        }
        val validated = Mp4ExportValidator.validate(stagedFile, plan, expectAudio = false)
        val (rangeStartUs, rangeEndUs) = audioRangeUs(plan, compositionFrameRateNum, compositionFrameRateDen)
        val audioStatus: AudioStatus
        var audioSamples = 0L
        when {
            audioMix != null -> {
                // The editor's audio clips are authoritative for this
                // export: decode, mix and re-encode them to one AAC track.
                // A missing, undecodable or unsupported clip source fails
                // the export here with its reason (see AudioMixdown) —
                // never a silent video-only export. No audible clip in
                // range is different: that is a valid video-only export
                // with the reason stated.
                val mixedFile = File(stagedFile.parentFile, stagedFile.nameWithoutExtension + "-mixed-audio.mp4")
                try {
                    val mixed = AudioMixdown.mixToAac(
                        clips = audioMix.clips,
                        frameRateNum = audioMix.frameRateNum,
                        frameRateDen = audioMix.frameRateDen,
                        rangeStartUs = rangeStartUs,
                        rangeEndUs = rangeEndUs,
                        outputFile = mixedFile,
                        isCancelled = isCancelled
                    )
                    if (mixed.aacSamplesWritten > 0L) {
                        val promoted = muxAndPromote(stagedFile, mixedFile, plan, encoded, rangeStartUs, rangeEndUs, isCancelled)
                        if (promoted != null) return promoted
                        audioStatus = AudioStatus.AUDIO_CLIPS_SILENT
                    } else {
                        audioStatus = AudioStatus.AUDIO_CLIPS_SILENT
                    }
                } finally {
                    mixedFile.delete()
                }
            }
            !hasAudioItems -> audioStatus = AudioStatus.NO_AUDIO_IN_COMPOSITION
            audioFile == null -> audioStatus = AudioStatus.AUDIO_FILE_UNAVAILABLE
            probeAacTrack(audioFile) == null -> audioStatus = AudioStatus.AUDIO_TRACK_UNSUPPORTED
            else -> {
                val promoted = muxAndPromote(stagedFile, audioFile, plan, encoded, rangeStartUs, rangeEndUs, isCancelled)
                if (promoted != null) return promoted
                audioStatus = AudioStatus.AUDIO_TRACK_UNSUPPORTED
            }
        }
        return StagedResult(
            framesWritten = encoded.framesEncoded,
            audioStatus = audioStatus,
            videoSamples = validated.videoSamples,
            durationUs = validated.durationUs,
            audioSamplesWritten = audioSamples
        )
    }

    /**
     * Re-muxes the staged video with the AAC track of [audioFile] into a
     * second staged file, validates it with audio expected, and promotes
     * it over [stagedFile]. Returns the staged result on success, or
     * null when the AAC track yields zero samples inside the range (the
     * validated video-only file stands — a valid video export must never
     * fail only because its audio does not overlap the exported range).
     */
    private fun muxAndPromote(
        stagedFile: File,
        audioFile: File,
        plan: RenderQueuePlan,
        encoded: Mp4SequenceExporter.Result,
        rangeStartUs: Long,
        rangeEndUs: Long,
        isCancelled: () -> Boolean
    ): StagedResult? {
        val remuxed = File(stagedFile.parentFile, stagedFile.nameWithoutExtension + "-audio.mp4")
        try {
            val audioSamples = muxAudio(stagedFile, audioFile, remuxed, rangeStartUs, rangeEndUs, isCancelled)
            if (audioSamples <= 0L) return null
            val remuxedValidated = Mp4ExportValidator.validate(remuxed, plan, expectAudio = true)
            check(remuxedValidated.audioSamples == audioSamples) {
                "Muxed AAC sample count ${remuxedValidated.audioSamples} differs from written count $audioSamples"
            }
            check(remuxedValidated.audioFirstPtsUs != null && remuxedValidated.audioLastPtsUs != null) {
                "Validated AAC track is missing its presentation-time window"
            }
            if (!remuxed.renameTo(stagedFile)) {
                // renameTo refuses an existing target; replace by copy instead.
                remuxed.copyTo(stagedFile, overwrite = true)
            }
            remuxed.delete()
            return StagedResult(
                framesWritten = encoded.framesEncoded,
                audioStatus = AudioStatus.INCLUDED,
                videoSamples = remuxedValidated.videoSamples,
                durationUs = remuxedValidated.durationUs,
                audioSamplesWritten = audioSamples,
                audioFirstPtsUs = remuxedValidated.audioFirstPtsUs,
                audioLastPtsUs = remuxedValidated.audioLastPtsUs,
                audioTrackDurationUs = remuxedValidated.audioTrackDurationUs
            )
        } finally {
            remuxed.delete()
        }
    }

    /** Index of the first AAC track in [file], or null when it holds none. */
    internal fun probeAacTrack(file: File): Int? {
        if (!file.isFile) return null
        val extractor = MediaExtractor()
        try {
            runCatching { extractor.setDataSource(file.absolutePath) }.getOrNull() ?: return null
            val candidates = (0 until extractor.trackCount).map { index ->
                val format = extractor.getTrackFormat(index)
                AudioCandidate(
                    mimeType = format.getString(MediaFormat.KEY_MIME) ?: "",
                    sampleRateHz = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0,
                    channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                )
            }
            return AudioTrackSelection.selectPassthroughIndex(candidates)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Copies the video track of [videoFile] sample-for-sample into
     * [outputFile], then appends the AAC track of [audioFile] with
     * timestamps shifted into [rangeStartUs, rangeEndUs). Returns the
     * number of audio samples written (0 when none falls in range).
     */
    internal fun muxAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        rangeStartUs: Long,
        rangeEndUs: Long,
        isCancelled: () -> Boolean
    ): Long {
        val audioTrackIndex = probeAacTrack(audioFile)
            ?: error("Audio file holds no AAC track to mux")
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrackIndex = (0 until videoExtractor.trackCount).firstOrNull { index ->
                videoExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: error("Staged MP4 is missing its video track")
            audioExtractor.setDataSource(audioFile.absolutePath)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoOut = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))
            val audioOut = muxer.addTrack(audioFormat)
            muxer.start()
            copyTrack(videoExtractor, videoTrackIndex, muxer, videoOut, shift = null, rangeStartUs, rangeEndUs, isCancelled)
            val audioSamples = copyTrack(audioExtractor, audioTrackIndex, muxer, audioOut, shift = rangeStartUs, rangeStartUs, rangeEndUs, isCancelled)
            muxer.stop()
            muxer.release()
            muxer = null
            return audioSamples
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            if (muxer != null) runCatching { muxer.release() }
        }
    }

    /**
     * Copies one selected track sample-for-sample. With [shift] null the
     * timestamps pass through; otherwise they are shifted into the export
     * range and samples outside it are skipped (the loop stops at the first
     * sample at or past the range end, since extractor samples are ordered).
     */
    private fun copyTrack(
        extractor: MediaExtractor,
        trackIndex: Int,
        muxer: MediaMuxer,
        muxerTrack: Int,
        shift: Long?,
        rangeStartUs: Long,
        rangeEndUs: Long,
        isCancelled: () -> Boolean
    ): Long {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var written = 0L
        while (true) {
            if (isCancelled()) throw CancellationException("MP4 export cancelled")
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val pts = extractor.sampleTime
            val flags = extractor.sampleFlags
            extractor.advance()
            val outPts = if (shift == null) {
                pts
            } else {
                val shifted = AudioTrackSelection.shiftedPts(pts, rangeStartUs, rangeEndUs)
                if (shifted == null && pts >= rangeEndUs) break
                if (shifted == null) continue
                shifted
            }
            info.set(0, size, outPts, flags)
            muxer.writeSampleData(muxerTrack, buffer, info)
            written++
        }
        return written
    }
}
