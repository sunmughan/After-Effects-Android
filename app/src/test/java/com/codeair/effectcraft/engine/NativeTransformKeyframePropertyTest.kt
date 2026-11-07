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
    fun acceptsAllTransformCurveSelectorsAndNumericParameterUids() {
        listOf("transform:position", "transform:scale", "transform:rotation", "transform:opacity", "transform:anchor", "transform:orientation", "transform:rotationX", "transform:rotationY", "param:0", "param:201")
            .forEach { selector -> assertTrue("expected supported selector: $selector", isSupportedCurveSelector(selector)) }
    }

    @Test
    fun rejectsUnknownCurveSelectorsAndMalformedParameterUids() {
        listOf("transform:rotationZ", "transform:positionX", "transform:translation", "param:abc", "param:-1", "position")
            .forEach { selector -> assertFalse("expected rejected selector: $selector", isSupportedCurveSelector(selector)) }
    }

    @Test
    fun rejectsUnknownAndNonTransformProperties() {
        listOf("", "rotationZ", "positionX", "opacity", "transform:anchor", "ANCHOR")
            .forEach { property -> assertFalse("expected rejected property: $property", isSupportedTransformKeyframeProperty(property)) }
    }
}
