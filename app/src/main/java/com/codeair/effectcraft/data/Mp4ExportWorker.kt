package com.codeair.effectcraft.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * 3. When the editor timeline holds audio clips, decode every clip,
 *    mix it to PCM with [AudioMixer] (per-clip volume and fades,
 *    overlapping clips summed and clamped), re-encode to AAC and mux
 *    it with the video into a second staged file, validate that file
 *    with audio expected, and only then promote it over [stagedFile].
 *    Mixed audio that decodes to silence, or a decode failure, falls
 *    through to step 4 instead of failing the video export.
 * 4. When an AAC track is available, re-mux video + AAC sample-for-sample
 *    into a second staged file (audio timestamps shifted into the export
 *    range), validate that file with audio expected, and only then promote
 *    it over [stagedFile]. When the AAC track yields zero samples inside the
 *    range, the re-muxed file is discarded and the validated video-only
 *    file stands — a valid video export must never fail only because its
 *    audio does not overlap the exported range.
 * 5. The caller copies only the validated file to SAF and deletes the
 *    staging file in a finally block. Cancellation is cooperative: checks
 *    run before every frame render and inside the copy loops.
 *
 * Audio is mixed in-app whenever the timeline holds clips (see [AudioStatus.MIXED]);
 * the AAC passthrough below is only the fallback for an empty timeline.
 * The video-only reason is always visible via [AudioStatus.describe], never silent.
 * Audio has two paths (see [AudioTrackSelection] and [AudioMixdown]):
 * the legacy path passes an AAC track through sample-for-sample, and
 * the editor-audio path decodes the draft's audio clips (AAC, WAV,
 * MP3, FLAC, Opus) to PCM, mixes them to one AAC track honouring
 * timeline position, trim, gain, fades and mute, and muxes that in.
 * A missing, undecodable or unsupported clip source fails the export
 * with its reason. No audio track is ever synthesized: silence in the
 * mix is real zero samples where no clip covers, and the video-only
 * reason is always visible via [AudioStatus.describe], never silent.
 * Precedence when both audio inputs are present: [AudioMixRequest]
 * (project-document clips, duration-matched to the export) wins and
 * the standalone-timeline mix is skipped, so the audio is never mixed twice.
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
        audioTimeline: AudioTimeline? = null,
        pcmSource: AudioPcmSource? = null,
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
        // Mixed timeline audio takes precedence over passthrough: the
        // editor timeline is the user's explicit audio, decoded and
        // re-encoded in-app. Silence or a decode failure falls through
        // to passthrough below instead of failing the video export.
        // Skipped when audioMix is present: the project-document clips
        // are authoritative for the export and must not be mixed twice.
        if (audioMix == null && audioTimeline != null && !audioTimeline.isEmpty && pcmSource != null) {
            val mixed = runCatching {
                AudioMixer.mix(
                    audioTimeline, rangeStartUs, rangeEndUs,
                    AudioMixer.EXPORT_SAMPLE_RATE_HZ, AudioMixer.EXPORT_CHANNEL_COUNT, pcmSource
                )
            }.getOrNull()
            if (mixed != null && !AudioMixer.isSilent(mixed)) {
                val remuxed = File(stagedFile.parentFile, stagedFile.nameWithoutExtension + "-mixed.mp4")
                try {
                    // Any encode/validate failure falls through to
                    // passthrough below; a failed mix must never fail
                    // the video export.
                    val mixedResult = runCatching {
                        val mixedSamples = muxMixedAudio(
                            stagedFile, mixed,
                            AudioMixer.EXPORT_SAMPLE_RATE_HZ, AudioMixer.EXPORT_CHANNEL_COUNT,
                            remuxed, isCancelled
                        )
                        check(mixedSamples > 0L) { "Mixed AAC encode produced no samples" }
                        val remuxedValidated = Mp4ExportValidator.validate(remuxed, plan, expectAudio = true)
                        check(remuxedValidated.audioSamples == mixedSamples) {
                            "Muxed AAC sample count ${remuxedValidated.audioSamples} differs from written count $mixedSamples"
                        }
                        check(remuxedValidated.audioFirstPtsUs != null && remuxedValidated.audioLastPtsUs != null) {
                            "Validated AAC track is missing its presentation-time window"
                        }
                        if (!remuxed.renameTo(stagedFile)) {
                            remuxed.copyTo(stagedFile, overwrite = true)
                        }
                        StagedResult(
                            framesWritten = encoded.framesEncoded,
                            audioStatus = AudioStatus.MIXED,
                            videoSamples = remuxedValidated.videoSamples,
                            durationUs = remuxedValidated.durationUs,
                            audioSamplesWritten = mixedSamples,
                            audioFirstPtsUs = remuxedValidated.audioFirstPtsUs,
                            audioLastPtsUs = remuxedValidated.audioLastPtsUs,
                            audioTrackDurationUs = remuxedValidated.audioTrackDurationUs
                        )
                    }.getOrNull()
                    if (mixedResult != null) return mixedResult
                } finally {
                    remuxed.delete()
                }
            }
        }
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
     * Muxes the video track of [videoFile] with [pcm] (interleaved float
     * PCM at [sampleRateHz]/[channelCount]) re-encoded to AAC. Returns
     * the number of AAC frames written. PCM is fed in 1024-frame chunks
     * with presentation times from the frame index, so the audio track
     * stays in sync with the video frame times. The AAC encoder's
     * priming delay means the first frame's presentation time is 0 as
     * encoded; players compensate, leaving at most one AAC frame
     * (~21 ms at 48 kHz) of A/V offset.
     */
    internal fun muxMixedAudio(
        videoFile: File,
        pcm: FloatArray,
        sampleRateHz: Int,
        channelCount: Int,
        outputFile: File,
        isCancelled: () -> Boolean
    ): Long {
        require(pcm.isNotEmpty()) { "Mixed PCM is empty" }
        require(pcm.size % channelCount == 0) { "Mixed PCM is not a whole number of frames" }
        val totalFrames = pcm.size / channelCount
        val videoExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        var encoderStarted = false
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrackIndex = (0 until videoExtractor.trackCount).firstOrNull { index ->
                videoExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: error("Staged MP4 is missing its video track")
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mx = muxer
            val videoOut = mx.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            val enc = encoder
            val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRateHz, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            enc.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            encoderStarted = true
            val info = MediaCodec.BufferInfo()
            var framesFed = 0
            var audioOut = -1
            var muxerStarted = false
            var audioFramesWritten = 0L
            var inputEosFed = false
            var sawOutputEos = false
            fun drain(timeoutUs: Long): Boolean {
                var eos = false
                while (true) {
                    if (isCancelled()) throw CancellationException("MP4 export cancelled")
                    when (val outputIndex = enc.dequeueOutputBuffer(info, timeoutUs)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> return eos
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC encoder changed output format more than once" }
                            audioOut = mx.addTrack(enc.outputFormat)
                            mx.start()
                            muxerStarted = true
                        }
                        else -> if (outputIndex >= 0) {
                            val encoded = enc.getOutputBuffer(outputIndex)
                            try {
                                if (info.size > 0 &&
                                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                                ) {
                                    check(muxerStarted) { "AAC encoder emitted samples before its output format" }
                                    requireNotNull(encoded) { "AAC encoder returned a null output buffer" }
                                    encoded.position(info.offset)
                                    encoded.limit(info.offset + info.size)
                                    mx.writeSampleData(audioOut, encoded, info)
                                    audioFramesWritten++
                                }
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                            } finally {
                                enc.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                }
            }
            while (!sawOutputEos) {
                if (isCancelled()) throw CancellationException("MP4 export cancelled")
                if (!inputEosFed) {
                    val inputIndex = enc.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val remaining = totalFrames - framesFed
                        if (remaining <= 0) {
                            enc.queueInputBuffer(
                                inputIndex, 0, 0,
                                framesFed.toLong() * 1_000_000L / sampleRateHz,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEosFed = true
                        } else {
                            val chunkFrames = minOf(1024, remaining)
                            val buffer = enc.getInputBuffer(inputIndex)
                                ?: throw IllegalStateException("AAC encoder returned no input buffer")
                            buffer.clear()
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            for (frame in 0 until chunkFrames) {
                                for (channel in 0 until channelCount) {
                                    val value = (pcm[(framesFed + frame) * channelCount + channel] * 32767f)
                                        .coerceIn(-32768f, 32767f)
                                    buffer.putShort(value.toInt().toShort())
                                }
                            }
                            val ptsUs = framesFed.toLong() * 1_000_000L / sampleRateHz
                            enc.queueInputBuffer(inputIndex, 0, chunkFrames * channelCount * 2, ptsUs, 0)
                            framesFed += chunkFrames
                        }
                    }
                }
                sawOutputEos = drain(if (inputEosFed) 10_000L else 0L)
            }
            check(muxerStarted) { "AAC encoder produced no output track" }
            copyTrack(videoExtractor, videoTrackIndex, mx, videoOut, shift = null, 0L, 0L, isCancelled)
            mx.stop()
            mx.release()
            muxer = null
            return audioFramesWritten
        } finally {
            runCatching { videoExtractor.release() }
            if (encoderStarted) runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
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
