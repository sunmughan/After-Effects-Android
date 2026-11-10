package com.codeair.effectcraft.data.aep

import java.io.File

/**
 * Resolves a footage path stored in a `.aep` to a file on this device.
 *
 * The stored path is usually an absolute path from the machine that made the
 * project (a Windows drive letter, backslashes), so it almost never exists
 * here. The project the user imports keeps its footage next to the `.aep` in
 * the same folder, so the resolver looks there: the stored path as-is first,
 * then the trailing components joined under each candidate directory (the
 * longest suffix first, so `scratch/seq/shot_0001.png` beats a bare
 * `shot_0001.png` when both exist), then the bare file name. Works for single
 * files and for image-sequence folders (whose stored path is the folder).
 */
object AepFootageResolver {

    /** The default resolver: the stored path as-is, when it exists. */
    fun default(storedPath: String): String? =
        File(storedPath).takeIf { it.exists() }?.absolutePath

    /** A `(String) -> String?` resolver over [dirs], falling back to [default]. */
    fun resolverFor(dirs: List<File>): (String) -> String? =
        { stored -> resolve(stored, dirs)?.absolutePath ?: default(stored) }

    /** The file (or sequence folder) [storedPath] resolves to under [dirs], or null. */
    fun resolve(storedPath: String, dirs: List<File>): File? {
        File(storedPath).takeIf { it.exists() }?.let { return it }
        val parts = normalise(storedPath)
        if (parts.isEmpty()) return null
        for (dir in dirs) {
            // Longest suffix first (at most 6 components, to bound the work).
            val maxSuffix = minOf(parts.size, 6)
            for (len in maxSuffix downTo 1) {
                val candidate = parts.drop(parts.size - len)
                    .fold(dir) { acc, part -> File(acc, part) }
                if (candidate.exists()) return candidate
            }
        }
        return null
    }

    /** The stored path as components: backslashes to slashes, drive letter and
     * leading `./` or `/` stripped, empty components dropped. */
    private fun normalise(storedPath: String): List<String> {
        var path = storedPath.replace('\\', '/')
        val colon = path.indexOf(':')
        if (colon in 1..2) path = path.substring(colon + 1)
        while (path.startsWith("./")) path = path.substring(2)
        while (path.startsWith("/")) path = path.substring(1)
        return path.split('/').filter { it.isNotEmpty() && it != "." }
    }
}
