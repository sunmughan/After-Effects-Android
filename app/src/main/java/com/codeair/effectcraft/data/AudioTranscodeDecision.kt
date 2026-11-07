package com.codeair.effectcraft.data

/**
 * What export does with an audio source, decided from its MIME type
 * alone. AAC in MP4 is the only passthrough mime: its samples are
 * already in the export codec. WAV, MP3, FLAC and Opus are decoded to
 * PCM (MediaExtractor/MediaCodec) and re-encoded to AAC at export —
 * they are never silently dropped to video-only. On Android, WAV is
 * reported by MediaExtractor as `audio/raw` (raw PCM, no codec), and
 * that mime transcodes too. Anything else is unsupported and the
 * export fails closed with a reason instead of faking or synthesizing
 * audio.
 */
enum class AudioSourceDecision {
    PASSTHROUGH,
    TRANSCODE,
    UNSUPPORTED
}

object AudioTranscodeDecision {
    const val AAC_MIME = "audio/mp4a-latm"

    private val transcodeMimes = setOf(
        "audio/x-wav",
        "audio/wav",
        // Android's WAV extractor reports WAV as audio/raw: the samples
        // are raw PCM and need no codec, but they still go through the
        // decode/resample/re-encode path (see AudioDecoder).
        "audio/raw",
        "audio/mpeg",
        "audio/flac",
        "audio/opus",
        // ADTS AAC is decodable but is not MP4 AAC: it goes through the
        // decode/re-encode path, never the sample-for-sample mux path.
        "audio/aac"
    )

    /** Normalizes a MIME type for comparison: lowercase, surrounding space removed. */
    fun normalize(mime: String): String = mime.trim().lowercase()

    /** The export decision for one source MIME type. */
    fun decide(mime: String): AudioSourceDecision = when (normalize(mime)) {
        AAC_MIME -> AudioSourceDecision.PASSTHROUGH
        in transcodeMimes -> AudioSourceDecision.TRANSCODE
        else -> AudioSourceDecision.UNSUPPORTED
    }

    /** True when the source must be decoded and re-encoded to AAC. */
    fun requiresTranscode(mime: String): Boolean = decide(mime) == AudioSourceDecision.TRANSCODE

    /** A human-readable reason for the decision, for export status text. */
    fun describe(mime: String): String = when (decide(mime)) {
        AudioSourceDecision.PASSTHROUGH -> "AAC passthrough ($mime)"
        AudioSourceDecision.TRANSCODE -> "transcode to AAC ($mime)"
        AudioSourceDecision.UNSUPPORTED -> "unsupported audio format ($mime)"
    }
}
