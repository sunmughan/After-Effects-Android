package com.codeair.effectcraft.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Mp4BoxInspectorTest {
    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        header.putInt(8 + body.size)
        header.put(type.toByteArray(Charsets.US_ASCII))
        out.write(header.array())
        out.write(body)
        return out.toByteArray()
    }

    private fun u32(value: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()

    private fun u64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()

    private fun mvhd(timescale: Long, duration: Long, version: Int = 0): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(version.toByte(), 0, 0, 0)) // version/flags
        if (version == 0) {
            body.write(u32(0)) // creation time
            body.write(u32(0)) // modification time
            body.write(u32(timescale))
            body.write(u32(duration))
        } else {
            body.write(u64(0)) // creation time
            body.write(u64(0)) // modification time
            body.write(u32(timescale)) // timescale stays 32-bit in v1
            body.write(u64(duration))
        }
        return box("mvhd", body.toByteArray())
    }

    private fun mdhd(timescale: Long, duration: Long, version: Int = 0): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(version.toByte(), 0, 0, 0))
        if (version == 0) {
            body.write(u32(0))
            body.write(u32(0))
            body.write(u32(timescale))
            body.write(u32(duration))
        } else {
            body.write(u64(0))
            body.write(u64(0))
            body.write(u32(timescale))
            body.write(u64(duration))
        }
        body.write(ByteArray(4)) // language + pre-defined
        return box("mdhd", body.toByteArray())
    }

    private fun hdlr(handler: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(ByteArray(4))
        body.write(u32(0)) // pre-defined
        body.write(handler.toByteArray(Charsets.US_ASCII))
        body.write(ByteArray(12))
        body.write(0) // empty name + terminator
        return box("hdlr", body.toByteArray())
    }

    private fun stsd(codec: String): ByteArray {
        val entry = ByteArrayOutputStream()
        val entryBody = ByteArrayOutputStream()
        entryBody.write(ByteArray(6)) // reserved
        entryBody.write(ByteArray(2)) // data reference index
        entryBody.write(ByteArray(2)) // pre-defined
        entryBody.write(ByteArray(2)) // reserved
        entryBody.write(ByteArray(12)) // pre-defined
        entryBody.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(640).array())
        entryBody.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(360).array())
        entry.write(box(codec, entryBody.toByteArray()))
        val body = ByteArrayOutputStream()
        body.write(ByteArray(4)) // version/flags
        body.write(u32(1)) // entry count
        body.write(entry.toByteArray())
        return box("stsd", body.toByteArray())
    }

    private fun stsz(sampleCount: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(ByteArray(4))
        body.write(u32(0)) // sample size (0 = per-sample table)
        body.write(u32(sampleCount.toLong()))
        repeat(sampleCount) { body.write(u32(1024)) }
        return box("stsz", body.toByteArray())
    }

    private fun mp4(codec: String = "avc1", samples: Int = 6, timescale: Long = 1000, duration: Long = 6000, version: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val ftyp = ByteArrayOutputStream()
        ftyp.write("isom".toByteArray(Charsets.US_ASCII))
        ftyp.write(u32(0))
        ftyp.write("isomiso2".toByteArray(Charsets.US_ASCII))
        out.write(box("ftyp", ftyp.toByteArray()))
        val stbl = ByteArrayOutputStream()
        stbl.write(stsd(codec))
        stbl.write(stsz(samples))
        val minf = ByteArrayOutputStream()
        minf.write(box("stbl", stbl.toByteArray()))
        val mdia = ByteArrayOutputStream()
        mdia.write(mdhd(timescale, duration, version))
        mdia.write(hdlr("vide"))
        mdia.write(box("minf", minf.toByteArray()))
        val trak = ByteArrayOutputStream()
        trak.write(box("tkhd", ByteArray(80)))
        trak.write(box("mdia", mdia.toByteArray()))
        val moov = ByteArrayOutputStream()
        moov.write(mvhd(timescale, duration, version))
        moov.write(box("trak", trak.toByteArray()))
        out.write(box("moov", moov.toByteArray()))
        out.write(box("mdat", ByteArray(16)))
        return out.toByteArray()
    }

    @Test
    fun inspectsVideoTrackCodecDurationAndSampleCount() {
        val result = Mp4BoxInspector.inspect(mp4())
        assertEquals("avc1", result.videoTrack.codec)
        assertEquals(640, result.videoTrack.width)
        assertEquals(360, result.videoTrack.height)
        assertEquals(1000L, result.videoTrack.timescale)
        assertEquals(6000L, result.videoTrack.duration)
        assertEquals(6L, result.videoTrack.sampleCount)
        assertEquals(1000L, result.movieTimescale)
        assertEquals(6000L, result.movieDuration)
    }

    @Test
    fun readsVersionOneMovieAndMediaHeaderTimescaleAndDuration() {
        val result = Mp4BoxInspector.inspect(mp4(timescale = 48_000, duration = 288_000, version = 1))
        assertEquals(48_000L, result.movieTimescale)
        assertEquals(288_000L, result.movieDuration)
        assertEquals(48_000L, result.videoTrack.timescale)
        assertEquals(288_000L, result.videoTrack.duration)
    }

    @Test
    fun rejectsNonAvc1TracksTruncatedFilesAndNonMp4Data() {
        assertThrows(IllegalArgumentException::class.java) { Mp4BoxInspector.inspect(mp4(codec = "mp4v")) }
        assertThrows(IllegalArgumentException::class.java) { Mp4BoxInspector.inspect(mp4().copyOf(20)) }
        assertThrows(IllegalArgumentException::class.java) { Mp4BoxInspector.inspect(ByteArray(64)) }
        assertThrows(IllegalArgumentException::class.java) { Mp4BoxInspector.inspect(ByteArray(0)) }
    }

    @Test
    fun rejectsZeroSampleTracks() {
        assertThrows(IllegalArgumentException::class.java) { Mp4BoxInspector.inspect(mp4(samples = 0)) }
    }

    @Test
    fun skipsLargesizeMdatBeforeMoov() {
        // Layout of mp4(): ftyp (24 bytes) + moov + mdat (24 bytes, last).
        // Rebuild it with a 64-bit largesize mdat between ftyp and moov, as
        // Android's MediaMuxer writes: the parser must skip it by largesize
        // and still find the video track in moov.
        val base = mp4()
        val ftyp = base.copyOfRange(0, 24)
        val moov = base.copyOfRange(24, base.size - 24)
        val mdatHeader = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        mdatHeader.putInt(1)
        mdatHeader.put("mdat".toByteArray(Charsets.US_ASCII))
        mdatHeader.putLong(16L + 16L)
        val out = ByteArrayOutputStream()
        out.write(ftyp)
        out.write(mdatHeader.array())
        out.write(ByteArray(16))
        out.write(moov)
        val result = Mp4BoxInspector.inspect(out.toByteArray())
        assertEquals("avc1", result.videoTrack.codec)
        assertEquals(6L, result.videoTrack.sampleCount)
    }

    @Test
    fun acceptsZeroSizeTrailingMdat() {
        // Size 0 means "extends to end of parent": zero the trailing mdat's
        // size field and the file must still inspect.
        val bytes = mp4()
        ByteBuffer.wrap(bytes, bytes.size - 24, 4).order(ByteOrder.BIG_ENDIAN).putInt(0)
        val result = Mp4BoxInspector.inspect(bytes)
        assertEquals("avc1", result.videoTrack.codec)
        assertEquals(6L, result.videoTrack.sampleCount)
    }
}
