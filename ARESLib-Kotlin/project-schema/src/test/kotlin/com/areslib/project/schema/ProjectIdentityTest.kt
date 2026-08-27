package com.areslib.project.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProjectIdentityTest {
    @Test
    fun `typed project document and action identities reject ambiguous values`() {
        assertEquals("Lightbot", ProjectId("Lightbot").value)
        assertEquals("indicator-lights", ProjectDocumentId("indicator-lights").value)
        assertEquals(
            "subsystem.indicator-lights.cycleForward.leftColor",
            ProjectActionKey("subsystem.indicator-lights.cycleForward.leftColor").value,
        )

        assertThrows(IllegalArgumentException::class.java) { ProjectId("../robot") }
        assertThrows(IllegalArgumentException::class.java) { ProjectDocumentId("left light") }
        assertThrows(IllegalArgumentException::class.java) { ProjectActionKey("bad action") }
    }
}
