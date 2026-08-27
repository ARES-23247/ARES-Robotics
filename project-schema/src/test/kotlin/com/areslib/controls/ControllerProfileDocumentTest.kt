package com.areslib.controls

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControllerProfileDocumentTest {
    @Test
    fun `round trips learned Vader extra buttons deterministically`() {
        val profile = ControllerProfileDocument(
            documentId = "vader5-pro",
            displayName = "Flydigi Vader 5 Pro",
            deviceMatchers = listOf(ControllerDeviceMatcherDocument(nameContains = "Vader 5 Pro")),
            controls = listOf(
                ControllerControlDocument(
                    "m4",
                    "M4",
                    ControllerControlTypeDocument.BUTTON,
                    ControllerSurfaceDocument.REAR,
                    ControllerAnchorDocument(0.65, 0.65),
                    mappings = listOf(
                        ControllerInputMappingDocument(ControllerInputPlatform.DESKTOP_GLFW, buttonIndex = 19),
                        ControllerInputMappingDocument(ControllerInputPlatform.FRC, buttonIndex = 21)
                    )
                ),
                ControllerControlDocument(
                    "right_trigger",
                    "RT",
                    ControllerControlTypeDocument.AXIS,
                    anchor = ControllerAnchorDocument(0.83, 0.02),
                    mappings = listOf(
                        ControllerInputMappingDocument(ControllerInputPlatform.DESKTOP_GLFW, axisIndex = 5),
                        ControllerInputMappingDocument(ControllerInputPlatform.FRC, axisIndex = 3)
                    )
                )
            )
        )

        val decoded = ControllerProfileCodec.decode(ControllerProfileCodec.encode(profile))

        assertEquals(setOf("m4", "right_trigger"), decoded.learnedControlIds(ControllerInputPlatform.FRC))
        assertEquals(ControllerProfileCodec.contentHash(profile), ControllerProfileCodec.contentHash(decoded))
        assertTrue(validateControllerProfile(decoded).none { it.severity == ControlValidationSeverity.ERROR })
    }

    @Test
    fun `template may be unlearned but reports invalid conflicting type`() {
        val profile = ControllerProfileDocument(
            documentId = "template",
            displayName = "Template",
            controls = listOf(
                ControllerControlDocument(
                    "m1",
                    "M1",
                    ControllerControlTypeDocument.BUTTON,
                    ControllerSurfaceDocument.REAR,
                    ControllerAnchorDocument(0.3, 0.3),
                    mappings = listOf(
                        ControllerInputMappingDocument(ControllerInputPlatform.FTC, axisIndex = 4)
                    )
                )
            )
        )

        assertTrue(validateControllerProfile(profile).any { it.code == "wrong_index_type" })
    }
}
