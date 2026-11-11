package com.codeair.effectcraft.data.aep

/**
 * Project folders, mirroring the official importer's Pass 1 (build.rs): the
 * item tree depth-first in file order, every folder as a Folder item with its
 * label and its parent, every composition and footage item knowing the folder
 * that holds it.
 *
 * The official importer also wraps the whole import in a root folder; this
 * app has no root-folder concept, so top-level items keep no parent.
 * Folder names are matched by name in the patch step (the same collision
 * caveat as compositions matched by name there): two folders with the same
 * name share one parent link, the first one wins.
 */
object AepFolderImport {

    /** A folder as it will be written into the canonical document. */
    data class FolderInfo(
        val name: String,
        val label: String,
        /** The enclosing folder's name; null for a top-level folder. */
        val parentName: String?
    )

    /** The collected tree: the folders, and the parent folder of each item. */
    data class FolderTree(
        val folders: List<FolderInfo>,
        /** Composition name to its folder's name. */
        val compParentByName: Map<String, String>,
        /** Footage item id to its folder's name. */
        val footageParentById: Map<Long, String>
    )

    /** Item and layer label colours (16 slots + None), in the order After
     * Effects stores them (byte 58 of `idta`). */
    private val LABELS = listOf(
        "None", "Red", "Yellow", "Aqua", "Pink", "Lavender", "Peach", "SeaFoam",
        "Blue", "Green", "Purple", "Orange", "Brown", "Fuchsia", "Cyan",
        "Sandstone", "DarkGreen"
    )

    fun labelName(index: Int?): String =
        if (index == null || index !in LABELS.indices) "None" else LABELS[index]

    /**
     * Collects the folder tree of a project. A folder without its contents
     * list is imported empty and reported (build.rs); a folder with an empty
     * contents list imports empty with no loss.
     */
    fun collect(
        project: AepTypedView.Project,
        col: AepImportReport.Collector
    ): FolderTree {
        val folders = ArrayList<FolderInfo>()
        val compParentByName = HashMap<String, String>()
        val footageParentById = HashMap<Long, String>()
        val stack = ArrayList<Pair<AepTypedView.Item, String?>>()
        for (item in project.items().reversed()) stack.add(item to null)
        while (stack.isNotEmpty()) {
            val (item, parentName) = stack.removeAt(stack.lastIndex)
            when (item.kind()) {
                AepTypedView.ItemKind.Folder -> {
                    val name = item.name()?.takeIf { it.isNotEmpty() } ?: "Folder"
                    folders.add(FolderInfo(name, labelName(item.label()), parentName))
                    col.imported.folders++
                    // After Effects writes an `Sfdr` even for an empty folder (and
                    // other chunks next to the items in it); without one the contents
                    // are lost.
                    if (!item.hasFolderContents()) {
                        col.lose(
                            AepImportReport.LossCategory.DamagedData,
                            "folder '$name': its contents list is missing, imported empty"
                        )
                    } else if (item.folderContentsUnreadable()) {
                        col.lose(
                            AepImportReport.LossCategory.DamagedData,
                            "folder '$name': its contents list is damaged, imported empty"
                        )
                    }
                    for (kid in item.children().reversed()) stack.add(kid to name)
                }
                AepTypedView.ItemKind.Composition -> {
                    val name = item.name() ?: continue
                    if (parentName != null) compParentByName[name] = parentName
                }
                AepTypedView.ItemKind.Footage -> {
                    val id = item.id() ?: continue
                    if (parentName != null) footageParentById[id] = parentName
                }
                else -> Unit
            }
        }
        return FolderTree(folders, compParentByName, footageParentById)
    }
}
