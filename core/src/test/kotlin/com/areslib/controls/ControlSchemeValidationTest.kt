package com.areslib.controls

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlSchemeValidationTest {
    private val catalog = CapabilityCatalogDocument(
        projectId = "test",
        actions = listOf(
            ActionDescriptor("intake.run", "Run intake", "Runs the intake."),
            ActionDescriptor("intake.stop", "Stop intake", "Stops the intake.")
        )
    )

    @Test
    fun `validates chords analog triggers actions and routines`() {
        val document = ControlSchemeDocument(
            documentId = "competition",
            name = "Competition controls",
            controllers = listOf(ControllerAssignment("driver", "Driver", "vader5")),
            bindings = listOf(
                ControlBindingDocument(
                    bindingId = "intake.chord",
                    displayName = "Run intake chord",
                    source = ControlSourceDocument(
                        ControlSourceKind.CHORD,
                        "driver",
                        listOf("left_bumper", "right_bumper")
                    ),
                    event = ControlEvent.PRESS,
                    target = ControlTargetDocument(ControlTargetKind.ACTION, "intake.run"),
                    priority = 10,
                    suppressConstituentBindings = true
                ),
                ControlBindingDocument(
                    bindingId = "shoot.trigger",
                    displayName = "Trigger macro",
                    source = ControlSourceDocument(
                        ControlSourceKind.AXIS_THRESHOLD,
                        "driver",
                        listOf("right_trigger"),
                        pressThreshold = 0.65,
                        releaseThreshold = 0.55
                    ),
                    event = ControlEvent.PRESS,
                    target = ControlTargetDocument(ControlTargetKind.ROUTINE, "shoot.sequence")
                )
            )
        )
        val context = ControlValidationContext.fromCatalog(
            catalog,
            routineIds = setOf("shoot.sequence"),
            profileControls = mapOf("vader5" to setOf("left_bumper", "right_bumper", "right_trigger"))
        )

        assertTrue(validateControlScheme(document, context).none { it.severity == ControlValidationSeverity.ERROR })
        assertEquals(
            ControlSchemeCodec.contentHash(document),
            ControlSchemeCodec.contentHash(ControlSchemeCodec.decode(ControlSchemeCodec.encode(document)))
        )
    }

    @Test
    fun `rejects unknown targets invalid thresholds and ambiguous bindings`() {
        val source = ControlSourceDocument(
            ControlSourceKind.AXIS_THRESHOLD,
            "driver",
            listOf("right_trigger"),
            pressThreshold = 0.5,
            releaseThreshold = 0.7
        )
        val binding = ControlBindingDocument(
            "bad.one",
            "Bad binding",
            source,
            ControlEvent.PRESS,
            ControlTargetDocument(ControlTargetKind.ACTION, "missing.action")
        )
        val document = ControlSchemeDocument(
            documentId = "bad",
            name = "Bad",
            controllers = listOf(ControllerAssignment("driver", "Driver", "vader5")),
            bindings = listOf(binding, binding.copy(bindingId = "bad.two"))
        )
        val issues = validateControlScheme(document, ControlValidationContext.fromCatalog(catalog, emptySet()))

        assertTrue(issues.any { it.code == "invalid_hysteresis" })
        assertTrue(issues.any { it.code == "unknown_action" })
        assertTrue(issues.any { it.code == "ambiguous_binding" })
    }
}
