package com.areslib.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CapabilityArgumentReaderTest {
    @Test
    fun `strictly decodes typed values and defaults`() {
        val reader = CapabilityArgumentReader(
            capabilityKey = "shooter.fire",
            arguments = mapOf("rpm" to "4250.5", "enabled" to "TRUE", "mode" to "speaker"),
            allowedKeys = setOf("rpm", "enabled", "mode", "note")
        )

        assertEquals(4250.5, reader.requiredNumber("rpm", minimum = 0.0, maximum = 6_000.0))
        assertEquals(true, reader.requiredBoolean("enabled"))
        assertEquals("speaker", reader.requiredEnum("mode", setOf("speaker", "amp")))
        assertEquals("fallback", reader.requiredText("note", "fallback"))
        assertNull(reader.optionalText("missing"))
    }

    @Test
    fun `rejects unknown malformed and out of range values`() {
        assertFailsWith<IllegalArgumentException> {
            CapabilityArgumentReader("arm.move", mapOf("typo" to "1"), setOf("height"))
        }
        assertFailsWith<IllegalArgumentException> {
            CapabilityArgumentReader("arm.move", mapOf("height" to "NaN"), setOf("height"))
                .requiredNumber("height")
        }
        assertFailsWith<IllegalArgumentException> {
            CapabilityArgumentReader("arm.move", mapOf("height" to "2"), setOf("height"))
                .requiredNumber("height", maximum = 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            CapabilityArgumentReader("arm.move", emptyMap(), setOf("height"))
                .requiredNumber("height")
        }
    }
}
