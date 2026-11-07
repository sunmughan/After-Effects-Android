package com.codeair.effectcraft

import com.codeair.effectcraft.data.ProjectDocument
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the package rename: the app's Kotlin namespace must be
 * com.codeair.effectcraft, matching applicationId com.codeair.effectcraft.
 * This test fails to compile until the rename lands (red), then passes.
 */
class PackageIdentityTest {

    @Test
    fun appCodeLivesUnderCodeairPackage() {
        val className = ProjectDocument::class.java.name
        assertTrue(
            "Expected app code under com.codeair.effectcraft, got $className",
            className.startsWith("com.codeair.effectcraft."),
        )
    }
}
