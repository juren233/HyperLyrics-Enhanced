package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialProviderDiagnosticCapabilityTest {
    @Test fun diagnosticCapabilityPreservesBinaryDescriptor() {
        val method = OfficialProviderHost::class.java.getDeclaredMethod("isDiagnosticEnabled")
        assertEquals(Boolean::class.javaPrimitiveType, method.returnType)
        assertEquals(0, method.parameterCount)
    }
}
