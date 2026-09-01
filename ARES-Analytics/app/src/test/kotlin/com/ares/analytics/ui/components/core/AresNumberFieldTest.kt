package com.ares.analytics.ui.components.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AresNumberFieldTest {
    @Test
    fun `parser accepts ordinary finite values`() {
        assertEquals(12.5, parseFiniteDoubleInput("12.5"))
        assertEquals(-0.25, parseFiniteDoubleInput("-0.25"))
        assertEquals(1.0e-3, parseFiniteDoubleInput("1e-3"))
    }

    @Test
    fun `parser rejects incomplete and non-finite values`() {
        assertNull(parseFiniteDoubleInput(""))
        assertNull(parseFiniteDoubleInput("-"))
        assertNull(parseFiniteDoubleInput("NaN"))
        assertNull(parseFiniteDoubleInput("Infinity"))
        assertNull(parseFiniteDoubleInput("-Infinity"))
    }
}
