package ai.effectcraft.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafPermissionRegistryTest {
    @Test
    fun recordsReconcilesAndReleasesGrants() {
        val root = Files.createTempDirectory("effectcraft-saf").toFile()
        try {
            val registry = SafPermissionRegistry(File(root, "saf.properties"))
            registry.record("content://com.example/tree/docs", SafPermissionRegistry.Kind.READ, "Demo.ecproj")
            registry.record("content://com.example/tree/out", SafPermissionRegistry.Kind.WRITE, "Export.ecproj")
            assertTrue(registry.isGranted("content://com.example/tree/docs", SafPermissionRegistry.Kind.READ))
            assertFalse(registry.needsRecovery())

            // OS no longer lists the read grant: reconcile marks it LOST, not deleted.
            val changed = registry.reconcile(
                setOf(SafPermissionRegistry.GrantKey("content://com.example/tree/out", SafPermissionRegistry.Kind.WRITE))
            )
            assertEquals(1, changed.size)
            assertEquals(SafPermissionRegistry.Status.LOST, changed.single().status)
            assertTrue(registry.needsRecovery())
            assertEquals(
                listOf("Demo.ecproj"),
                registry.lost().map { it.displayName }
            )

            // Re-granting the same URI recovers it.
            registry.reconcile(
                setOf(
                    SafPermissionRegistry.GrantKey("content://com.example/tree/docs", SafPermissionRegistry.Kind.READ),
                    SafPermissionRegistry.GrantKey("content://com.example/tree/out", SafPermissionRegistry.Kind.WRITE)
                )
            )
            assertFalse(registry.needsRecovery())

            // Release removes the grant entirely and survives reload.
            assertTrue(registry.release("content://com.example/tree/docs"))
            assertFalse(registry.release("content://com.example/tree/docs"))
            val reopened = SafPermissionRegistry(File(root, "saf.properties"))
            assertEquals(listOf("Export.ecproj"), reopened.grants().map { it.displayName })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsInvalidGrantsAndMarksLostExplicitly() {
        val root = Files.createTempDirectory("effectcraft-saf-bad").toFile()
        try {
            val registry = SafPermissionRegistry(File(root, "saf.properties"))
            assertTrue(runCatching { registry.record("", SafPermissionRegistry.Kind.READ, "x") }.isFailure)
            assertTrue(runCatching { registry.record("content://x", SafPermissionRegistry.Kind.READ, "") }.isFailure)
            registry.record("content://x/doc", SafPermissionRegistry.Kind.READ, "Doc")
            assertTrue(registry.markLost("content://x/doc", SafPermissionRegistry.Kind.READ))
            assertFalse(registry.markLost("content://x/doc", SafPermissionRegistry.Kind.WRITE))
            assertFalse(registry.isGranted("content://x/doc", SafPermissionRegistry.Kind.READ))
        } finally {
            root.deleteRecursively()
        }
    }
}
