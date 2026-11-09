package com.codeair.effectcraft.data.aep

/**
 * The After Effects version a project is written for (`head` bytes 4 to 7) and the version of
 * the After Effects that wrote it (`svap`).
 *
 * Port of `container/version.rs` from the official EffectCraft project
 * (storytold/effectcraft, MIT OR Apache-2.0). Both hold the same packed `u32`. A project saved
 * normally has the same word in both; File ▸ Save a Copy As 25.x / 24.x of After Effects 2026
 * keeps `svap` (the 2026 version) and writes the older version into `head`.
 */
object AepVersion {

    /** An After Effects version as packed in `head` / `svap`: `26.2.1x2` is major 26, minor 2,
     * bug-fix 1, build 2 (the form of the scripting API's `app.version`).
     *
     * Bit layout of the big-endian word (bit 31 first):
     *
     * | bits | field |
     * |---|---|
     * | 31-26 | not decoded (`000011` in every sample) |
     * | 25-24, 21-19 | major (high two bits, then low three bits) |
     * | 23-22 | not decoded (`00` in every sample) |
     * | 18-15 | minor |
     * | 14-11 | bug-fix |
     * | 10-8 | stage (6 in every sample) |
     * | 7-0 | build | */
    class AeVersion(
        val major: Int,
        val minor: Int,
        val bugfix: Int,
        /** The release stage (6 in every sample; not shown in [text]). */
        val stage: Int,
        val build: Int,
        /** The word as stored. */
        val raw: Long
    ) {
        companion object {
            /** Decodes a stored word; null for a word that holds no version (zero, all ones, or
             * major 0: After Effects itself refuses a file whose `head` version is zero). */
            fun fromWord(raw: Long): AeVersion? {
                if (raw == 0L || raw == 0xFFFFFFFFL) return null
                val b0 = ((raw ushr 24) and 0xFF).toInt()
                val b1 = ((raw ushr 16) and 0xFF).toInt()
                val b2 = ((raw ushr 8) and 0xFF).toInt()
                val b3 = (raw and 0xFF).toInt()
                val major = ((b0 and 0x03) shl 3) or ((b1 ushr 3) and 0x07)
                if (major == 0) return null
                return AeVersion(
                    major = major,
                    minor = ((b1 and 0x07) shl 1) or (b2 ushr 7),
                    bugfix = (b2 ushr 3) and 0x0f,
                    stage = b2 and 0x07,
                    build = b3,
                    raw = raw
                )
            }
        }

        /** `26.2.1x2`. */
        fun text(): String = "$major.$minor.$bugfix" + "x$build"

        override fun toString(): String = text()

        override fun equals(other: Any?): Boolean =
            other is AeVersion && major == other.major && minor == other.minor &&
                bugfix == other.bugfix && stage == other.stage && build == other.build &&
                raw == other.raw

        override fun hashCode(): Int {
            var result = major
            result = 31 * result + minor
            result = 31 * result + bugfix
            result = 31 * result + stage
            result = 31 * result + build
            result = 31 * result + raw.hashCode()
            return result
        }
    }
}

/** The version the file is written for: `head` bytes 4 to 7. Null when the chunk is missing,
 * short or holds no version. */
fun AepContainer.AepFile.fileVersion(): AepVersion.AeVersion? {
    val head = chunk("head")?.bytes() ?: return null
    val word = beU32(head, 4) ?: return null
    return AepVersion.AeVersion.fromWord(word)
}

/** The version of the After Effects that wrote the file: `svap` bytes 0 to 3. Null when the
 * chunk is missing, short or holds no version. */
fun AepContainer.AepFile.savedByVersion(): AepVersion.AeVersion? {
    val svap = chunk("svap")?.bytes() ?: return null
    val word = beU32(svap, 0) ?: return null
    return AepVersion.AeVersion.fromWord(word)
}

/** The `u16` at `head` byte 0: 97 (`0x61`) in a 2026 file, 96 in a copy saved as 25.x, 95 as
 * 24.x. It steps with the major version of [fileVersion]; what it counts is not known, so
 * nothing is derived from it. */
fun AepContainer.AepFile.headRevision(): Int? {
    val head = chunk("head")?.bytes() ?: return null
    return beU16(head, 0)
}
