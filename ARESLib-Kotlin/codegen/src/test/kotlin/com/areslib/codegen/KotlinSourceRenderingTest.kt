package com.areslib.codegen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KotlinSourceRenderingTest {
    @Test
    fun `string literals escape templates quotes separators and control characters`() {
        assertEquals(
            "\"price=\\$5 \\\"quoted\\\" \\\\ path\\nnext\\tcell\\b\\u0001\\u2028\"",
            "price=$5 \"quoted\" \\ path\nnext\tcell\b\u0001\u2028".kotlinStringLiteral(),
        )
    }

    @Test
    fun `double literals are finite deterministic and normalize negative zero`() {
        assertEquals("0.0", (-0.0).kotlinDoubleLiteral())
        assertEquals("2.0", 2.0.kotlinDoubleLiteral())
        assertEquals("0.001", 0.001.kotlinDoubleLiteral())
        assertThrows(IllegalArgumentException::class.java) { Double.NaN.kotlinDoubleLiteral() }
    }

    @Test
    fun `pascal case is shared across generators`() {
        assertEquals("LeftIndicatorLight", "left-indicator_light".kotlinPascalCase())
    }
}
