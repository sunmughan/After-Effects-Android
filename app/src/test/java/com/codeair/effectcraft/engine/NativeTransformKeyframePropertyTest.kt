package com.codeair.effectcraft.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTransformKeyframePropertyTest {
    @Test
    fun acceptsEveryNativeTransformKeyframeProperty() {
        listOf("position", "scale", "rotation", "anchor", "orientation", "rotationX", "rotationY")
            .forEach { property -> assertTrue("expected supported property: $property", isSupportedTransformKeyframeProperty(property)) }
    }

    @Test
    fun rejectsUnknownAndNonTransformProperties() {
        listOf("", "rotationZ", "positionX", "opacity", "transform:anchor", "ANCHOR")
            .forEach { property -> assertFalse("expected rejected property: $property", isSupportedTransformKeyframeProperty(property)) }
    }
}
