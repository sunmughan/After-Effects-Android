package com.codeair.effectcraft.data.aep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The folder import (AepFolderImport) on hand-built project item trees. */
class AepFolderImportTest {

    private fun u16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun u32(v: Long): ByteArray {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = (v ushr (24 - 8 * i)).toByte()
        return b
    }

    /** `idta`: kind u16 at 0, item id u32 at 16, label u8 at 58. */
    private fun idta(kind: Int, id: Long, label: Int?): ByteArray {
        val b = ByteArray(64)
        u16(kind).copyInto(b, 0)
        u32(id).copyInto(b, 16)
        if (label != null) b[58] = label.toByte()
        return b
    }

    private fun item(
        kind: Int,
        id: Long,
        label: Int?,
        name: String?,
        children: List<AepContainer.Chunk> = emptyList(),
        withContents: Boolean = false
    ): AepContainer.Chunk {
        val kids = ArrayList<AepContainer.Chunk>()
        kids.add(AepContainer.Chunk.data("idta", idta(kind, id, label)))
        if (name != null) kids.add(AepContainer.Chunk.data("Utf8", name.toByteArray(Charsets.UTF_8)))
        if (withContents) kids.add(AepContainer.Chunk.list("Sfdr", children))
        return AepContainer.Chunk.list("Item", kids)
    }

    private fun project(vararg items: AepContainer.Chunk): AepTypedView.Project =
        AepTypedView.Project(AepContainer.Chunk.list("Fold", items.toList()), null)

    private fun collect(p: AepTypedView.Project): Pair<AepFolderImport.FolderTree, List<AepImportReport.Loss>> {
        val col = AepImportReport.Collector()
        val tree = AepFolderImport.collect(p, col)
        val losses = col.finish(AepImportReport.AeVersionInfo("26.2.1x2", "26.2.1x2", true)).losses
        return tree to losses
    }

    @Test
    fun collectsFoldersWithParentsAndLabels() {
        val p = project(
            item(4, 1, 15, "Main"),
            item(
                1, 2, 2, "A", withContents = true, children = listOf(
                    item(4, 3, 15, "C1"),
                    item(
                        1, 4, 5, "B", withContents = true, children = listOf(
                            item(7, 7, 1, null)
                        )
                    )
                )
            ),
            item(
                1, 5, 1, "Solids", withContents = true, children = listOf(
                    item(7, 8, 1, null)
                )
            )
        )
        val (tree, losses) = collect(p)
        assertTrue(losses.isEmpty())
        assertEquals(
            listOf(
                AepFolderImport.FolderInfo("A", "Yellow", null),
                AepFolderImport.FolderInfo("B", "Lavender", "A"),
                AepFolderImport.FolderInfo("Solids", "Red", null)
            ),
            tree.folders
        )
        assertEquals(mapOf("C1" to "A"), tree.compParentByName)
        assertEquals(mapOf(7L to "B", 8L to "Solids"), tree.footageParentById)
    }

    @Test
    fun emptyFolderNameFallsBackToFolder() {
        val p = project(item(1, 2, null, null, withContents = true))
        val (tree, losses) = collect(p)
        assertTrue(losses.isEmpty())
        assertEquals(
            listOf(AepFolderImport.FolderInfo("Folder", "None", null)),
            tree.folders
        )
    }

    @Test
    fun missingContentsIsALossButFolderStillImports() {
        val p = project(item(1, 2, 2, "Empty"))
        val (tree, losses) = collect(p)
        assertEquals(1, tree.folders.size)
        assertEquals(1, losses.sumOf { it.count })
        assertEquals(AepImportReport.LossCategory.DamagedData, losses.single().category)
        assertTrue(losses.single().details.single().contains("Empty"))
    }

    @Test
    fun labelOutOfRangeIsNone() {
        val p = project(item(1, 2, 99, "Weird", withContents = true))
        val (tree, losses) = collect(p)
        assertTrue(losses.isEmpty())
        assertEquals("None", tree.folders.single().label)
    }

    @Test
    fun foldersAreCounted() {
        val col = AepImportReport.Collector()
        val p = project(
            item(1, 2, 2, "A", withContents = true),
            item(1, 3, 2, "B", withContents = true)
        )
        AepFolderImport.collect(p, col)
        assertEquals(2, col.imported.folders)
    }
}
