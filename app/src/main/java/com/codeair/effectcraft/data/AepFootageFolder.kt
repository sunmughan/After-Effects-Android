package com.codeair.effectcraft.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream

/**
 * The footage-folder side of a `.aep` import: the picker grants only the
 * single `.aep` URI, so the footage sitting next to it in the same folder is
 * unreachable until the user picks that folder (ACTION_OPEN_DOCUMENT_TREE).
 * This copies the missing footage out of the granted tree into [destDir]
 * (the app's own `ae-imports` directory), so the footage resolves to real
 * files the renderer can read — a SAF `content://` URI is not a path the
 * canonical document can hold.
 *
 * Only the files the loss report lists as missing are copied, matched by
 * file name (the stored path is from the machine that made the project).
 * A matched directory is an image-sequence folder: its files are copied
 * into a same-named sub-directory. One level of sub-folders is searched.
 */
object AepFootageFolder {

    data class Entry(val documentId: String, val name: String, val isDir: Boolean)

    /** The children of one tree node (top level when [parentId] is the tree id). */
    fun children(resolver: ContentResolver, treeUri: Uri, parentId: String): List<Entry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val out = ArrayList<Entry>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                out.add(
                    Entry(
                        documentId = cursor.getString(idCol),
                        name = cursor.getString(nameCol) ?: "",
                        isDir = cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                )
            }
        }
        return out
    }

    /**
     * Copies the files in [missingPaths] out of the tree at [treeUri] into
     * [destDir]. Returns the directories holding the copied media (the
     * resolver's candidate dirs): [destDir] itself for single files, and a
     * same-named sub-directory per copied sequence folder. Entries that are
     * not found are skipped, never an error.
     */
    fun copyMissing(
        resolver: ContentResolver,
        treeUri: Uri,
        missingPaths: List<String>,
        destDir: File
    ): List<File> {
        destDir.mkdirs()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val top = children(resolver, treeUri, rootId)
        // One level of sub-folders, so footage in a sub-folder is found too.
        val all = ArrayList(top)
        for (entry in top) {
            if (entry.isDir) runCatching { all.addAll(children(resolver, treeUri, entry.documentId)) }
        }
        fun find(name: String): Entry? =
            all.firstOrNull { it.name == name } ?: all.firstOrNull { it.name.equals(name, ignoreCase = true) }

        val dirs = LinkedHashSet<File>()
        for (path in missingPaths) {
            val base = path.substringAfterLast('/').substringAfterLast('\\')
            if (base.isEmpty()) continue
            val entry = find(base) ?: continue
            if (entry.isDir) {
                val subDir = File(destDir, base).apply { mkdirs() }
                for (child in runCatching { children(resolver, treeUri, entry.documentId) }.getOrDefault(emptyList())) {
                    if (!child.isDir) {
                        if (copyDocument(resolver, treeUri, child, File(subDir, child.name))) {
                            dirs.add(subDir)
                        }
                    }
                }
            } else if (copyDocument(resolver, treeUri, entry, File(destDir, base))) {
                dirs.add(destDir)
            }
        }
        return dirs.toList()
    }

    private fun copyDocument(resolver: ContentResolver, treeUri: Uri, entry: Entry, dest: File): Boolean =
        runCatching {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)
            val input = resolver.openInputStream(uri) ?: return@runCatching false
            input.use { stream ->
                FileOutputStream(dest).use { output -> stream.copyTo(output) }
            }
            dest.exists() && dest.length() > 0
        }.getOrDefault(false)
}
