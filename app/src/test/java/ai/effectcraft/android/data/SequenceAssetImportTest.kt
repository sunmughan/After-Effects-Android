package ai.effectcraft.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files

class SequenceAssetImportTest {
    @Test
    fun importsOrderedFramesAndRecordsSequenceEntry() {
        val root = Files.createTempDirectory("effectcraft-sequence").toFile()
        try {
            val store = OfflineAssetStore(root)
            val manifests = ProjectAssetManifestStore(File(root, "manifests"))
            val manifest = manifests.load("seq-project")
            val entry = SequenceAssetImport.importSequence(
                store,
                manifest,
                11,
                listOf(
                    SequenceAssetImport.FrameInput("frame_001.png", ByteArrayInputStream(byteArrayOf(1))),
                    SequenceAssetImport.FrameInput("frame_002.png", ByteArrayInputStream(byteArrayOf(2))),
                    // Identical bytes deduplicate to the first frame's payload.
                    SequenceAssetImport.FrameInput("frame_003.png", ByteArrayInputStream(byteArrayOf(1)))
                )
            )
            manifests.save(manifest)
            assertEquals(ProjectAssetManifest.Kind.SEQUENCE, entry.kind)
            assertEquals(listOf("frame_001.png", "frame_002.png", "frame_003.png"), entry.frames.map { it.name })
            assertEquals(entry.frames[0].sha256, entry.frames[2].sha256)
            assertEquals(2, store.list().size)
            assertEquals(entry, manifests.load("seq-project").entryFor(11))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedSequenceRestoresPreviousEntryWithoutDeletingPayloads() {
        val root = Files.createTempDirectory("effectcraft-sequence-fail").toFile()
        try {
            val store = OfflineAssetStore(root)
            val manifest = ProjectAssetManifest("seq-fail")
            val previousHash = "d".repeat(64)
            manifest.link(5, previousHash)
            val failing = object : InputStream() {
                override fun read(): Int = throw java.io.IOException("synthetic read failure")
            }
            val failure = runCatching {
                SequenceAssetImport.importSequence(
                    store,
                    manifest,
                    5,
                    listOf(
                        SequenceAssetImport.FrameInput("ok.png", ByteArrayInputStream(byteArrayOf(9))),
                        SequenceAssetImport.FrameInput("bad.png", failing)
                    )
                )
            }.exceptionOrNull()
            assertTrue(failure != null)
            // Rollback: the previous single entry is intact in the manifest.
            assertEquals(previousHash, manifest.entryFor(5)?.sha256)
            // The frame imported before the failure stays in the content-addressed
            // store: deleting it could remove a payload another project references,
            // so garbage collection (not rollback) owns its cleanup.
            assertEquals(1, store.list().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsEmptyOrDuplicateFrameNamesBeforeImporting() {
        val root = Files.createTempDirectory("effectcraft-sequence-bad").toFile()
        try {
            val store = OfflineAssetStore(root)
            val manifest = ProjectAssetManifest("seq-bad")
            assertTrue(runCatching {
                SequenceAssetImport.importSequence(store, manifest, 1, emptyList())
            }.isFailure)
            assertTrue(runCatching {
                SequenceAssetImport.importSequence(
                    store, manifest, 1,
                    listOf(
                        SequenceAssetImport.FrameInput("dup.png", ByteArrayInputStream(byteArrayOf(1))),
                        SequenceAssetImport.FrameInput("dup.png", ByteArrayInputStream(byteArrayOf(2)))
                    )
                )
            }.isFailure)
            assertTrue(store.list().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
