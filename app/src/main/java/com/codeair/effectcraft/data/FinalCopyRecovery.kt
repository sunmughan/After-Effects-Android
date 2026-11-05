package com.codeair.effectcraft.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * Best-effort process-death recovery for the final SAF provider copy,
 * M5-L7, for the PNG sequence ZIP and the staged MP4 only.
 *
 * The model is the honest one worker 52 established for render kills,
 * extended to the copy phase: detection plus cleanup plus restart from
 * frame 0, never resume, and never a claim of universal rollback.
 *
 * How it works: the caller publishes a `<staging>.copying` marker
 * immediately before the final SAF copy begins and deletes it when the
 * copy finishes or fails. A process kill bypasses every finally block,
 * so a marker left behind after death, together with its staging file,
 * is the detectable partial copy state. Recovery deletes the app-side
 * staging file and marker. That is all this process can clean.
 *
 * Residual limit, stated plainly: a partial file already written by the
 * SAF provider into the destination cannot be rolled back by this
 * process. After death this process cannot tell a completed destination
 * from a partial one, so it must never delete the destination on
 * recovery: deleting a completed export would destroy user data. Any
 * bytes already handed to the provider before the kill stay with the
 * provider. Only the user or the provider app can delete a provider-side
 * partial, so recovery reports it as possibly remaining and the startup
 * message tells the user to check the destination they chose.
 *
 * Call-site safety: recovery must only run where no copy of the covered
 * formats is in flight. Startup in a fresh process satisfies that for
 * every format, because no export has started yet. A next-export sweep
 * must be scoped to that export's own format, so it can never delete a
 * live copy marker belonging to another export type running at the same
 * time. The MP4 leg has no same-type in-flight guard at its launch site,
 * so MP4 relies on the startup sweep only; the PNG leg is serialized by
 * its foreground service guard, so its runner also sweeps its own format
 * before starting. No codec, audio, or MP4 encoding code is touched.
 */
object FinalCopyRecovery {
    const val COPY_MARKER_SUFFIX = ".copying"
    const val FORMAT_PNG_SEQUENCE = "png-sequence"
    const val FORMAT_MP4 = "mp4"

    data class RecoveryResult(
        val cleaned: Int,
        val orphanMarkersRemoved: Int,
        val retained: Int,
        val failed: List<String>,
        val cleanedFormats: List<String>,
        val providerPartialMayRemain: Boolean
    )

    /**
     * Publishes a copy marker for [stagingFile] before the final SAF copy.
     * The marker is written to a sibling temporary file, fsynced, and
     * atomically promoted, so a marker on disk is always complete. On
     * failure the temporary file and any partial marker are removed and
     * the exception is thrown; the staging file is never deleted here,
     * because it holds the validated export the copy is about to write.
     * Callers treat a marker failure as best effort and copy unmarked
     * rather than failing the export over a diagnostic file.
     */
    fun beginCopy(stagingFile: File, format: String): File {
        require(stagingFile.isFile) { "Staged export file is missing" }
        val stagingDir = stagingFile.parentFile
        require(stagingDir != null && stagingDir.isDirectory) { "Staging directory is not usable" }
        val markerFile = File(stagingDir, stagingFile.name + COPY_MARKER_SUFFIX)
        val marker = JSONObject().apply {
            put("format", format)
            put("phase", "copying")
            put("stagedName", stagingFile.name)
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
            throw failure
        }
        return markerFile
    }

    /** Deletes a copy marker after the copy finishes or fails. Best effort. */
    fun finishCopy(markerFile: File): Boolean = markerFile.delete()

    /** Counts copy markers in [stagingDir], optionally scoped to [formats]. */
    fun pendingCopyInterruptions(stagingDir: File, formats: Set<String>? = null): Int {
        if (!stagingDir.isDirectory) return 0
        return stagingDir.listFiles().orEmpty().count { file ->
            file.isFile && file.name.endsWith(COPY_MARKER_SUFFIX) &&
                (formats == null || markerFormat(file) in formats)
        }
    }

    /**
     * Cleans app-side copy state: staging files plus markers for copies
     * that died mid-copy, and orphan markers whose staging file is gone.
     * When [formats] is given, only markers whose recorded format is in
     * the set are cleaned; unparseable markers are retained in that mode,
     * because their export type cannot be proven. With no filter, every
     * copy marker is cleaned, which is only safe at startup in a fresh
     * process where no copy is in flight. The destination is never
     * touched: [RecoveryResult.providerPartialMayRemain] is true whenever
     * anything was cleaned, as the user-visible signal that a
     * provider-side partial may still sit at the chosen destination.
     */
    fun recover(stagingDir: File, formats: Set<String>? = null): RecoveryResult {
        if (!stagingDir.isDirectory) {
            return RecoveryResult(0, 0, 0, emptyList(), emptyList(), false)
        }
        var cleaned = 0
        var orphanMarkersRemoved = 0
        var retained = 0
        val failed = mutableListOf<String>()
        val cleanedFormats = mutableListOf<String>()
        stagingDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(COPY_MARKER_SUFFIX) }
            .forEach { marker ->
                val format = markerFormat(marker)
                if (formats != null && format !in formats) {
                    retained++
                    return@forEach
                }
                val stagingName = marker.name.removeSuffix(COPY_MARKER_SUFFIX)
                val staging = File(stagingDir, stagingName)
                if (!staging.isFile) {
                    if (marker.delete()) {
                        orphanMarkersRemoved++
                        if (format != null) cleanedFormats += format
                    } else {
                        failed += marker.name
                    }
                    return@forEach
                }
                val stagingDeleted = staging.delete()
                val markerDeleted = marker.delete()
                if (stagingDeleted && markerDeleted) {
                    cleaned++
                    if (format != null) cleanedFormats += format
                } else {
                    failed += marker.name
                }
            }
        return RecoveryResult(
            cleaned = cleaned,
            orphanMarkersRemoved = orphanMarkersRemoved,
            retained = retained,
            failed = failed,
            cleanedFormats = cleanedFormats.distinct().sorted(),
            providerPartialMayRemain = cleaned > 0 || orphanMarkersRemoved > 0
        )
    }

    /** The recorded format of a copy marker, or null when it cannot be read. */
    private fun markerFormat(marker: File): String? = runCatching {
        JSONObject(marker.readText(Charsets.UTF_8)).optString("format").ifEmpty { null }
    }.getOrNull()
}
