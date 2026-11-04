package com.codeair.effectcraft.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure, JVM-testable MP4 box parser: walks top-level boxes, then moov/trak/
 * mdia/minf/stbl, and reports the first video track's codec, dimensions,
 * timescale, duration and stsz sample count. It reads headers only and
 * skips payload boxes (mdat) by size, so it never loads media samples.
 * The on-device export validator uses MediaExtractor instead; this parser
 * proves the container layout independently on the JVM.
 */
object Mp4BoxInspector {
    data class VideoTrack(
        val codec: String,
        val width: Int,
        val height: Int,
        val timescale: Long,
        val duration: Long,
        val sampleCount: Long
    )

    data class Result(
        val videoTrack: VideoTrack,
        val movieTimescale: Long,
        val movieDuration: Long
    )

    fun inspect(bytes: ByteArray): Result {
        require(bytes.size >= 8) { "Not an MP4 file: too short for a box header" }
        val boxes = parseBoxes(bytes, 0, bytes.size)
        require(boxes.any { it.type == "ftyp" }) { "MP4 is missing its ftyp box" }
        val moov = boxes.firstOrNull { it.type == "moov" }
            ?: throw IllegalArgumentException("MP4 is missing its moov box")
        val moovBoxes = parseBoxes(bytes, moov.bodyOffset, moov.end)
        val mvhd = moovBoxes.firstOrNull { it.type == "mvhd" }
            ?: throw IllegalArgumentException("MP4 moov is missing mvhd")
        val (movieTimescale, movieDuration) = readTimescaleDuration(bytes, mvhd)
        for (trak in moovBoxes.filter { it.type == "trak" }) {
            val video = readVideoTrack(bytes, trak) ?: continue
            require(video.sampleCount > 0L) { "MP4 video track contains no samples" }
            return Result(video, movieTimescale, movieDuration)
        }
        throw IllegalArgumentException("MP4 contains no video track")
    }

    private class Box(val type: String, val bodyOffset: Int, val end: Int)

    private fun parseBoxes(bytes: ByteArray, start: Int, end: Int): List<Box> {
        val boxes = mutableListOf<Box>()
        var offset = start
        while (offset + 8 <= end) {
            val size32 = readU32(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            // ISO BMFF box sizes: 1 means a 64-bit largesize follows the type
            // field (Android's MediaMuxer writes large mdat boxes this way),
            // and 0 means the box extends to the end of its parent. Both must
            // be skipped by size, never read as a literal 1- or 0-byte box.
            val (headerSize, boxEnd) = when (size32) {
                1L -> {
                    require(offset + 16 <= end) { "MP4 box $type has a truncated largesize header" }
                    val large = readU64(bytes, offset + 8)
                    require(large >= 16) { "MP4 box $type has an invalid largesize" }
                    val endLong = offset.toLong() + large
                    require(endLong <= end) { "MP4 box $type extends past its parent" }
                    16 to endLong.toInt()
                }
                0L -> 8 to end
                else -> {
                    require(size32 >= 8) { "MP4 box $type has an invalid size" }
                    val endInt = offset + size32.toInt()
                    require(endInt <= end) { "MP4 box $type extends past its parent" }
                    8 to endInt
                }
            }
            boxes += Box(type, offset + headerSize, boxEnd)
            offset = boxEnd
        }
        require(offset == end) { "MP4 box region ends with trailing bytes" }
        return boxes
    }

    private fun readTimescaleDuration(bytes: ByteArray, box: Box): Pair<Long, Long> {
        val version = bytes[box.bodyOffset].toInt() and 0xff
        require(version == 0 || version == 1) { "Unsupported mvhd/mdhd version $version" }
        return if (version == 0) {
            readU32(bytes, box.bodyOffset + 12) to readU32(bytes, box.bodyOffset + 16)
        } else {
            readU64(bytes, box.bodyOffset + 20) to readU64(bytes, box.bodyOffset + 28)
        }
    }

    private fun readVideoTrack(bytes: ByteArray, trak: Box): VideoTrack? {
        val trakBoxes = parseBoxes(bytes, trak.bodyOffset, trak.end)
        val mdia = trakBoxes.firstOrNull { it.type == "mdia" } ?: return null
        val mdiaBoxes = parseBoxes(bytes, mdia.bodyOffset, mdia.end)
        val hdlr = mdiaBoxes.firstOrNull { it.type == "hdlr" } ?: return null
        val handler = String(bytes, hdlr.bodyOffset + 8, 4, Charsets.US_ASCII)
        if (handler != "vide") return null
        val mdhd = mdiaBoxes.firstOrNull { it.type == "mdhd" }
            ?: throw IllegalArgumentException("Video track is missing mdhd")
        val (timescale, duration) = readTimescaleDuration(bytes, mdhd)
        val minf = mdiaBoxes.firstOrNull { it.type == "minf" }
            ?: throw IllegalArgumentException("Video track is missing minf")
        val stbl = parseBoxes(bytes, minf.bodyOffset, minf.end).firstOrNull { it.type == "stbl" }
            ?: throw IllegalArgumentException("Video track is missing stbl")
        val stblBoxes = parseBoxes(bytes, stbl.bodyOffset, stbl.end)
        val stsd = stblBoxes.firstOrNull { it.type == "stsd" }
            ?: throw IllegalArgumentException("Video track is missing stsd")
        val entryCount = readU32(bytes, stsd.bodyOffset + 4).toInt()
        require(entryCount >= 1) { "Video track stsd is empty" }
        val entryOffset = stsd.bodyOffset + 8
        require(entryOffset + 8 <= stsd.end) { "Video track stsd entry is truncated" }
        val entrySize = readU32(bytes, entryOffset).toInt()
        val codec = String(bytes, entryOffset + 4, 4, Charsets.US_ASCII)
        require(codec == "avc1") { "Video track codec $codec is not H.264 (avc1)" }
        require(entryOffset + entrySize <= stsd.end) { "Video track stsd entry extends past stsd" }
        // VisualSampleEntry: 8 (box header) + 8 (sample entry header) +
        // 2 + 2 + 12 (pre-defined/reserved), then width/height.
        val widthOffset = entryOffset + 8 + 8 + 16
        val width = readU16(bytes, widthOffset)
        val height = readU16(bytes, widthOffset + 2)
        val stsz = stblBoxes.firstOrNull { it.type == "stsz" }
            ?: throw IllegalArgumentException("Video track is missing stsz")
        val sampleCount = readU32(bytes, stsz.bodyOffset + 8)
        return VideoTrack(codec, width, height, timescale, duration, sampleCount)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        require(offset + 2 <= bytes.size) { "MP4 field extends past the file" }
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        require(offset + 4 <= bytes.size) { "MP4 field extends past the file" }
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
    }

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        require(offset + 8 <= bytes.size) { "MP4 field extends past the file" }
        return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).long
    }
}
