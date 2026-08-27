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
            controllers = listOf(ControllerAssignment("driver", "Driver", "vader5", devicePort = 0)),
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
            controllers = listOf(ControllerAssignment("driver", "Driver", "vader5", devicePort = 0)),
            bindings = listOf(binding, binding.copy(bindingId = "bad.two"))
        )
        val issues = validateControlScheme(document, ControlValidationContext.fromCatalog(catalog, emptySet()))

        assertTrue(issues.any { it.code == "invalid_hysteresis" })
        assertTrue(issues.any { it.code == "unknown_action" })
        assertTrue(issues.any { it.code == "ambiguous_binding" })
    }

    @Test
    fun `requires explicit unique controller ports`() {
        val document = ControlSchemeDocument(
            documentId = "ports",
            name = "Port validation",
            controllers = listOf(
                ControllerAssignment("driver", "Driver", "vader5", devicePort = null),
                ControllerAssignment("operator", "Operator", "vader5", devicePort = 1),
                ControllerAssignment("coach", "Coach", "vader5", devicePort = 1),
            ),
            bindings = emptyList(),
        )

        val issues = validateControlScheme(document)

        assertTrue(issues.any { it.code == "missing_device_port" })
        assertTrue(issues.any { it.code == "duplicate_device_port" })
    }

    @Test
    fun `drive axes validate as analog value bindings`() {
        val document = ControlSchemeDocument(
            documentId = "drive",
            name = "Drive controls",
            controllers = listOf(ControllerAssignment("driver", "Driver", "vader5", devicePort = 0)),
            bindings = listOf(
                driveAxisBinding("drive.vx", "vx", "left_stick_y"),
                driveAxisBinding("drive.vy", "vy", "left_stick_x"),
                driveAxisBinding("drive.omega", "omega", "right_stick_x"),
            ),
        )
        val context = ControlValidationContext(
            profileControls = mapOf("vader5" to setOf("left_stick_x", "left_stick_y", "right_stick_x"))
        )

        val issues = validateControlScheme(document, context)

        assertTrue(issues.none { it.severity == ControlValidationSeverity.ERROR })
        assertTrue(
            ControlSchemeCodec.decode(ControlSchemeCodec.encode(document)).bindings.all {
                it.target.kind == ControlTargetKind.DRIVE
            },
        )
    }

    @Test
    fun `drive targets reject unknown axes arguments and non-value sources`() {
        val issues = validateControlScheme(
            ControlSchemeDocument(
                documentId = "drive-bad",
                name = "Bad drive controls",
                controllers = listOf(ControllerAssignment("driver", "Driver", "vader5", devicePort = 0)),
                bindings = listOf(
                    driveAxisBinding("drive.bad-axis", "throttle", "left_stick_y"),
                    driveAxisBinding("drive.args", "vx", "left_stick_y")
                        .copy(target = ControlTargetDocument(ControlTargetKind.DRIVE, "vx", arguments = mapOf("gain" to "2"))),
                    ControlBindingDocument(
                        "drive.digital",
                        "Digital drive",
                        ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("a")),
                        ControlEvent.PRESS,
                        ControlTargetDocument(ControlTargetKind.DRIVE, "vx"),
                    ),
                ),
            ),
            ControlValidationContext(profileControls = mapOf("vader5" to setOf("left_stick_y", "a"))),
        )

        assertTrue(issues.any { it.code == "unknown_drive_axis" })
        assertTrue(issues.any { it.code == "invalid_drive_arguments" })
        assertTrue(issues.any { it.code == "invalid_drive_binding" })
    }

    @Test
    fun `each drive axis accepts exactly one enabled binding`() {
        val document = ControlSchemeDocument(
            documentId = "drive-dupe",
            name = "Duplicate drive controls",
            controllers = listOf(ControllerAssignment("driver", "Driver", "vader5", devicePort = 0)),
            bindings = listOf(
                driveAxisBinding("drive.vx", "vx", "left_stick_y"),
                driveAxisBinding("drive.vx-alt", "vx", "right_stick_y"),
                driveAxisBinding("drive.vx-disabled", "vx", "left_stick_x").copy(enabled = false),
            ),
        )
        val context = ControlValidationContext(
            profileControls = mapOf("vader5" to setOf("left_stick_x", "left_stick_y", "right_stick_y"))
        )

        val issues = validateControlScheme(document, context)

        assertTrue(issues.count { it.code == "duplicate_drive_axis" } == 1)
        assertTrue(issues.none { it.code == "ambiguous_binding" }, "distinct axes are not input conflicts")
    }

    private fun driveAxisBinding(bindingId: String, axis: String, controlId: String): ControlBindingDocument =
        ControlBindingDocument(
            bindingId = bindingId,
            displayName = "Drive $axis",
            source = ControlSourceDocument(ControlSourceKind.AXIS_VALUE, "driver", listOf(controlId)),
            event = ControlEvent.VALUE,
            target = ControlTargetDocument(ControlTargetKind.DRIVE, axis),
            analogPolicy = AnalogControlPolicyDocument(),
        )
}
