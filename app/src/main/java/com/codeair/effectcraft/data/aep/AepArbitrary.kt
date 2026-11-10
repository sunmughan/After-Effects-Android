package com.codeair.effectcraft.data.aep

/**
 * Arbitrary-data effect parameters (type 11): the values of Hue/Saturation and
 * Levels that are not ordinary parameters, plus Curves, Mesh Warp and Cryptomatte.
 *
 * Port of `container/arbitrary.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). In the effect's value group
 * (`sspc/tdgp`) the parameter's `tdbs` is followed by a `LIST aRbs` holding one
 * `aRbp`: the data.
 */
object AepArbitrary {

    /** Hue/Saturation's data (180 bytes, `ADBE HUE SATURATION-0003`): the master hue,
     * saturation and lightness as `i32` at bytes 0, 4 and 8, then six colour ranges
     * (Reds, Yellows, Greens, Cyans, Blues, Magentas) of 28 bytes each: four `i32`
     * range points in degrees and the range's hue, saturation and lightness. */
    class HueSaturationData(val master: IntArray, val ranges: List<Pair<IntArray, IntArray>>) {
        fun rangesAreDefault(): Boolean =
            ranges.zip(DEFAULT_RANGES).all { (range, d) ->
                range.first.contentEquals(d) && range.second.contentEquals(intArrayOf(0, 0, 0))
            }

        companion object {
            /** The default data: master 0, 0, 0 and every range at its default points. */
            val DEFAULT_RANGES: List<IntArray> = listOf(
                intArrayOf(315, 345, 15, 45), intArrayOf(15, 45, 75, 105),
                intArrayOf(75, 105, 135, 165), intArrayOf(135, 165, 195, 225),
                intArrayOf(195, 225, 255, 285), intArrayOf(255, 285, 315, 345)
            )

            /** Decodes the data; null when it is not 180 bytes. */
            fun parse(data: ByteArray): HueSaturationData? {
                if (data.size != 180) return null
                val master = intArrayOf(beI32(data, 0) ?: return null, beI32(data, 4) ?: return null, beI32(data, 8) ?: return null)
                val ranges = ArrayList<Pair<IntArray, IntArray>>(6)
                for (k in 0 until 6) {
                    val base = 12 + k * 28
                    val points = intArrayOf(
                        beI32(data, base) ?: return null,
                        beI32(data, base + 4) ?: return null,
                        beI32(data, base + 8) ?: return null,
                        beI32(data, base + 12) ?: return null
                    )
                    val hsl = intArrayOf(
                        beI32(data, base + 16) ?: return null,
                        beI32(data, base + 20) ?: return null,
                        beI32(data, base + 24) ?: return null
                    )
                    ranges.add(points to hsl)
                }
                return HueSaturationData(master, ranges)
            }
        }
    }

    /** Levels' data (108 bytes, `ADBE Easy Levels2-0002`): a `u32` 0x000f10a7 and a `u32` 1,
     * then five channels (RGB, Red, Green, Blue, Alpha) of five `f32`: input black,
     * input white, gamma, output black, output white. */
    class LevelsData(val header: LongArray, val channels: List<FloatArray>) {
        companion object {
            /** The values of a channel at its default. */
            val DEFAULT_CHANNEL = floatArrayOf(0.0f, 1.0f, 1.0f, 0.0f, 1.0f)

            /** Decodes the data; null when it is not 108 bytes. */
            fun parse(data: ByteArray): LevelsData? {
                if (data.size != 108) return null
                val header = longArrayOf(beU32(data, 0) ?: return null, beU32(data, 4) ?: return null)
                val channels = ArrayList<FloatArray>(5)
                for (k in 0 until 5) {
                    val base = 8 + k * 20
                    val channel = FloatArray(5)
                    for (j in 0 until 5) channel[j] = beF32(data, base + j * 4) ?: return null
                    channels.add(channel)
                }
                return LevelsData(header, channels)
            }
        }
    }

    /** Curves' data (1644 bytes, `ADBE CurvesCustom-0001`): `u16` 1, `u16` 1, five 256-byte
     * lookup tables (RGB, Red, Green, Blue, Alpha), then five 72-byte point records: 16 slots
     * of (`u16` input, `u16` output), both 0..255, the number of points as `u32` at 64. */
    class CurvesData(val curves: List<List<Pair<Int, Int>>>) {
        companion object {
            const val SIZE = 1644
            const val POINTS = 1284

            /** Decodes the data; null when it is not 1644 bytes or a point count is beyond 16. */
            fun parse(data: ByteArray): CurvesData? {
                if (data.size != SIZE) return null
                val curves = ArrayList<List<Pair<Int, Int>>>(5)
                for (c in 0 until 5) {
                    val base = POINTS + c * 72
                    val n = beU32(data, base + 64)?.toInt() ?: return null
                    if (n > 16) return null
                    val pts = ArrayList<Pair<Int, Int>>(n)
                    for (k in 0 until n) {
                        val at = base + k * 4
                        val x = beU16(data, at) ?: return null
                        val y = beU16(data, at + 2) ?: return null
                        pts.add(x to y)
                    }
                    curves.add(pts)
                }
                return CurvesData(curves)
            }
        }
    }

    /** A stored 0..255 curve value as EffectCraft's fraction, as short as it is exact. */
    fun fraction(v: Int): String {
        val s = "%.6f".format(v / 255.0)
        return s.trimEnd('0').trimEnd('.')
    }

    /** Mesh Warp's data (`ADBE MESH WARP-0004`): `u32` rows plus one, `u32` columns plus one,
     * 12 more bytes, then per vertex, row-major, ten `f32`: the position and the handles top,
     * right, bottom and left, all as fractions 0..1 of the layer; a handle pointing out of the
     * mesh is stored (0, 0). */
    class MeshData(val columns: Int, val rows: Int, val tail: ByteArray, val vertices: List<Array<FloatArray>>) {
        companion object {
            /** Decodes the data; null when the size does not fit the grid or the grid is beyond 31 x 31 cells. */
            fun parse(data: ByteArray): MeshData? {
                val r1 = beU32(data, 0)?.toInt() ?: return null
                val c1 = beU32(data, 4)?.toInt() ?: return null
                if (c1 !in 2..32 || r1 !in 2..32) return null
                val count = c1 * r1
                if (data.size != 20 + count * 40) return null
                val tail = data.copyOfRange(8, 20)
                val vertices = ArrayList<Array<FloatArray>>(count)
                for (v in 0 until count) {
                    val base = 20 + 40 * v
                    val rec = Array(5) { FloatArray(2) }
                    for (j in 0 until 5) {
                        rec[j] = floatArrayOf(
                            beF32(data, base + 8 * j) ?: return null,
                            beF32(data, base + 8 * j + 4) ?: return null
                        )
                    }
                    vertices.add(rec)
                }
                return MeshData(c1 - 1, r1 - 1, tail, vertices)
            }

            /** The vertex at column `i`, row `j` of an undistorted mesh with its default handles
             * (one third of a cell along the grid lines; those pointing out of the mesh are (0, 0)). */
            fun defaultVertex(columns: Int, rows: Int, i: Int, j: Int): Array<FloatArray> {
                val cw = 1.0 / columns.coerceAtLeast(1)
                val rh = 1.0 / rows.coerceAtLeast(1)
                val x = i * cw
                val y = j * rh
                fun p(dx: Double, dy: Double, out: Boolean): FloatArray =
                    if (out) floatArrayOf(0.0f, 0.0f) else floatArrayOf((x + dx).toFloat(), (y + dy).toFloat())
                return arrayOf(
                    floatArrayOf(x.toFloat(), y.toFloat()),
                    p(0.0, -rh / 3.0, j == 0),
                    p(cw / 3.0, 0.0, i == columns),
                    p(0.0, rh / 3.0, j == rows),
                    p(-cw / 3.0, 0.0, i == 0)
                )
            }
        }
    }

    /** The Cryptomatte plug-in's Settings data (`Cryptomatte-0001`, little-endian): the magic
     * `cry1`; the Cryptomatte layer's name at 32, NUL-padded to 64 bytes; at 104 the `u32`
     * length of the selection text including its NUL; the selection text from byte 113. */
    class CryptomatteData(val layer: String, val selection: String) {
        companion object {
            /** Decodes the data; null when it is not this layout (no `cry1`, too short, a
             * length past the end, no zero byte at 112, a text without its NUL, text that is
             * not UTF-8). */
            fun parse(data: ByteArray): CryptomatteData? {
                if (data.size < 4 || !data.copyOfRange(0, 4).contentEquals("cry1".toByteArray(Charsets.US_ASCII))) return null
                fun text(b: ByteArray): String? {
                    val end = b.indexOf(0.toByte()).takeIf { it >= 0 } ?: b.size
                    return try {
                        val decoder = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        decoder.decode(java.nio.ByteBuffer.wrap(b, 0, end)).toString()
                    } catch (_: Exception) {
                        null
                    }
                }
                if (data.size < 96) return null
                val layer = text(data.copyOfRange(32, 96)) ?: return null
                if (data.size < 113) return null
                val len = leU32(data, 104)?.toInt() ?: return null
                if (data[112] != 0.toByte()) return null
                val selection = if (len == 0) {
                    ""
                } else {
                    val b = bytesAt(data, 113, len) ?: return null
                    val last = b.last()
                    if (last != 0.toByte() || b.dropLast(1).any { it == 0.toByte() }) return null
                    text(b) ?: return null
                }
                return CryptomatteData(layer, selection)
            }
        }
    }
}
