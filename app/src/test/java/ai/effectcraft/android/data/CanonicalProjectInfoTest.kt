package ai.effectcraft.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalProjectInfoTest {
    @Test
    fun parsesCompositionLayerAndLinkedMediaInventory() {
        val metadata = """{"valid":true,"schema":1,"itemCount":2,"compositionCount":1,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"layerCount":1,"layers":[{"id":2,"name":"Headline","source":"Text","visible":false}]}],"items":[{"id":10,"name":"Main","type":"Composition","parentId":null,"media":{}},{"id":20,"name":"Footage","type":"Video","parentId":null,"media":{"path":"content://media/1","width":1920,"height":1080,"hasVideo":true,"hasAudio":true,"missing":false,"sequence":[]}}]}"""

        val project = CanonicalProjectInfo.parse(metadata)

        assertEquals(1, project.compositions.size)
        assertEquals(640, project.compositions.single().width)
        assertEquals("Headline", project.compositions.single().layers.single().name)
        assertFalse(project.compositions.single().layers.single().visible)
        assertEquals(2, project.items.size)
        val footage = project.items.last()
        assertEquals("Video", footage.type)
        assertEquals("content://media/1", footage.path)
        assertEquals(1920, footage.width)
        assertTrue(footage.hasVideo)
        assertTrue(footage.hasAudio)
        assertFalse(footage.missing)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidNativeMetadataEnvelope() {
        CanonicalProjectInfo.parse("""{"valid":false,"error":"invalid project"}""")
    }
}
