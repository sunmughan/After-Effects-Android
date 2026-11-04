package com.codeair.effectcraft.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wiring contract for M4 #7: opacity keys that arrive in canonical metadata
 * must evaluate with exactly the draft timeline's linear-interpolation and
 * endpoint-hold semantics, so the value the editor shows at a frame is the
 * value the native renderer evaluates at that frame.
 */
class CanonicalOpacityKeyframeTest {
    private fun trackFromMetadata(): PropertyTrack {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[{"id":2,"name":"Headline","source":"Text","visible":true,"opacityPercent":100.0,"opacityKeys":[{"frame":0,"value":100.0},{"frame":30,"value":0.0}]}]}]}"""
        val layer = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single()
        return PropertyTrack("opacity", layer.opacityKeys)
    }

    @Test
    fun canonicalOpacityKeysInterpolateLinearlyAndHoldEndpoints() {
        val track = trackFromMetadata()

        assertEquals(50f, PropertyTrackEditor.evaluate(track, 15.0)!!, 0.001f)
        assertEquals(100f, PropertyTrackEditor.evaluate(track, 0.0)!!, 0.001f)
        assertEquals(0f, PropertyTrackEditor.evaluate(track, 30.0)!!, 0.001f)
        assertEquals(0f, PropertyTrackEditor.evaluate(track, 60.0)!!, 0.001f)
        assertEquals(100f, PropertyTrackEditor.evaluate(track, -5.0)!!, 0.001f)
    }

    @Test
    fun unkeyframedCanonicalLayerEvaluatesToNothing() {
        val metadata = """{"valid":true,"compositions":[{"id":10,"name":"Main","width":640,"height":360,"frameRateNum":30,"frameRateDen":1,"durationSeconds":3.0,"layers":[{"id":2,"name":"Headline","source":"Text","visible":true,"opacityPercent":80.0}]}]}"""
        val layer = CanonicalProjectInfo.parse(metadata).compositions.single().layers.single()

        assertNull(PropertyTrackEditor.evaluate(PropertyTrack("opacity", layer.opacityKeys), 10.0))
    }
}
