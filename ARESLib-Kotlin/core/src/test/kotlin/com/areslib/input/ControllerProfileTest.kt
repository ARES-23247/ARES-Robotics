package com.areslib.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControllerProfileTest {
    @Test
    fun `profile provides stable typed descriptors and identity matching`() {
        val profile = ControllerProfile(
            id = "vader-5-pro",
            displayName = "Flydigi Vader 5 Pro",
            controls = listOf(
                ButtonControlDescriptor(
                    id = "m1",
                    label = "Rear M1",
                    buttonIndex = 12,
                    anchor = ControlVisualAnchor(ControllerView.REAR, 0.3, 0.6),
                ),
                AxisControlDescriptor(
                    id = "right-trigger",
                    label = "Right Trigger",
                    axisIndex = 5,
                    defaultTransform = AxisTransform.trigger(),
                ),
            ),
            match = ControllerProfileMatch(vendorId = 0x04b4, nameContains = "Vader"),
        )

        assertEquals(12, profile.requireButton("m1").buttonIndex)
        assertEquals(5, profile.requireAxis("right-trigger").axisIndex)
        assertEquals(6, profile.requiredAxisCapacity)
        assertEquals(13, profile.requiredButtonCapacity)
        assertTrue(profile.match.matches(ControllerIdentity("Flydigi Vader 5 Pro", vendorId = 0x04b4)))
        assertFalse(profile.match.matches(ControllerIdentity("Other", vendorId = 0x04b4)))
        assertThrows(IllegalArgumentException::class.java) { profile.requireButton("right-trigger") }
    }

    @Test
    fun `duplicate stable control ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile(
                id = "bad",
                displayName = "Bad",
                controls = listOf(
                    ButtonControlDescriptor("a", "A", 0),
                    ButtonControlDescriptor("a", "Another A", 1),
                ),
            )
        }
    }

    @Test
    fun `visual anchors use normalized coordinates`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControlVisualAnchor(ControllerView.FRONT, 1.1, 0.5)
        }
    }
}
