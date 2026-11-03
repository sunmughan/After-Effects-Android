package ai.effectcraft.android.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies a completed app-private artifact into a SAF document with cancellation
 * checks between bounded chunks. If the destination cannot be completed, the
 * newly-created document is best-effort deleted so providers do not retain a
 * truncated PNG/ZIP that looks like a successful export.
 */
object SafDocumentWriter {
    const val DEFAULT_BUFFER_SIZE = 64 * 1024

    fun copyTo(
        source: InputStream,
        openOutput: () -> OutputStream,
        rollbackDestination: () -> Unit,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        ensureActive: () -> Unit = {},
        onBytesCopied: (Long) -> Unit = {}
    ): Long {
        require(bufferSize in 1..(1024 * 1024)) { "Copy buffer must be between 1 byte and 1 MiB" }
        var copied = 0L
        try {
            source.use { input ->
                openOutput().use { output ->
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) {
                            // Defensive progress for unusual InputStreams that return
                            // zero for a non-empty read request.
                            val single = input.read()
                            if (single < 0) break
                            output.write(single)
                            copied += 1
                        } else {
                            output.write(buffer, 0, count)
                            copied += count
                        }
                        onBytesCopied(copied)
                    }
                    ensureActive()
                    output.flush()
                }
            }
            return copied
        } catch (failure: Throwable) {
            runCatching(rollbackDestination).onFailure { rollbackFailure ->
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    /** Android SAF adapter; the pure stream routine above is JVM-testable. */
    fun copyFileToDocument(
        context: Context,
        sourceFile: java.io.File,
        destination: Uri,
        ensureActive: () -> Unit = {},
        onBytesCopied: (Long) -> Unit = {}
    ): Long = copyTo(
        source = sourceFile.inputStream(),
        openOutput = {
            context.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("Unable to open SAF output document")
        },
        rollbackDestination = {
            DocumentsContract.deleteDocument(context.contentResolver, destination)
        },
        ensureActive = ensureActive,
        onBytesCopied = onBytesCopied
    )
}
