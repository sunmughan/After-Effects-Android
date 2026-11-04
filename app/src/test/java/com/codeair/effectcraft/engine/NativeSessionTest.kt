package com.codeair.effectcraft.engine

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM-host behaviour of the session wrapper: the native library is not
 * loadable on the JVM host, so opening any session must return null rather
 * than throwing. Positive session behaviour (render/resize/update/cancel/
 * close) is covered by the native Rust host tests and by the emulator
 * instrumentation test, which run against the real .so.
 */
class NativeSessionTest {

    @Test
    fun openReturnsNullWithoutNativeLibrary() {
        assertNull(NativeSession.open("{\"schema\":1,\"items\":{}}"))
        assertNull(NativeSession.open("{}"))
        assertNull(NativeSession.open(""))
    }

    @Test
    fun openRejectsOutOfRangePreviewSizeWithoutNativeLibrary() {
        assertNull(NativeSession.open("{\"schema\":1,\"items\":{}}", 4097))
        assertNull(NativeSession.open("{\"schema\":1,\"items\":{}}", -1))
    }
}
