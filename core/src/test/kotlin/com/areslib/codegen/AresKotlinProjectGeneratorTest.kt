package com.areslib.codegen

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.catalog.ConditionDescriptor
import com.areslib.controls.AnalogControlPolicyDocument
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSourceDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControlTimingDocument
import com.areslib.controls.ControllerAnchorDocument
import com.areslib.controls.ControllerAssignment
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputMappingDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AresKotlinProjectGeneratorTest {
    @Test
    fun `golden registry is typed readable and stable`() {
        val result = generate(
            catalog = catalog(
                actions = listOf(
                    action(
                        "arm.raise",
                        listOf(numberParameter("height", required = true))
                    )
                ),
                conditions = listOf(condition("arm.ready"))
            ),
            routines = listOf(simpleRoutine("simple", RoutineStep.action("arm.raise", mapOf("height" to "0.5"))))
        )
        val golden = result.source
            .substringAfter("/** Typed robot implementations for every capability in the generated catalog. */\n")
            .substringBefore("\n\n/** Robot scheduler boundary")

        assertEquals(
            """interface GeneratedAresProjectCapabilities {
    /** Implements action key arm.raise. */
    fun actionArmRaise(height: Double): Task

    /** Implements condition key arm.ready. */
    fun conditionArmReady(): (RobotState) -> Boolean

    /** Platform trajectory adapter; returning null rejects a drive step safely. */
    fun createDriveTask(step: RoutineDriveStep): Task? = null
}""",
            golden
        )
    }

    @Test
    fun `generation is independent of collection and catalog ordering`() {
        val firstCatalog = catalog(
            actions = listOf(action("z.stop"), action("a.start")),
            conditions = listOf(condition("z.ready"), condition("a.ready"))
        )
        val secondCatalog = firstCatalog.copy(
            actions = firstCatalog.actions.reversed(),
            conditions = firstCatalog.conditions.reversed()
        )
        val routines = listOf(
            simpleRoutine("z_routine", RoutineStep.action("z.stop")),
            simpleRoutine("a_routine", RoutineStep.action("a.start"))
        )

        val first = generate(firstCatalog, routines)
        val second = generate(secondCatalog, routines.reversed())

        assertEquals(first.contentHash, second.contentHash)
        assertEquals(first.sourceHash, second.sourceHash)
        assertEquals(first.source, second.source)
        assertTrue(first.source.indexOf("\"a_routine\" to") < first.source.indexOf("\"z_routine\" to"))
    }

    @Test
    fun `Kotlin literals escape templates quotes slashes controls and Unicode separators`() {
        val special = "quote \" slash \\ template \$robot\nline\t\u2028"
        val result = generate(
            catalog = catalog(actions = listOf(action("note.write", listOf(textParameter("message"))))),
            routines = listOf(
                RoutineDocument(
                    documentId = "escaping",
                    name = special,
                    description = special,
                    steps = listOf(RoutineStep.action("note.write", mapOf("message" to special)))
                )
            )
        )

        assertTrue(result.source.contains("quote \\\" slash \\\\ template \\\$robot\\nline\\t\\u2028"))
        assertFalse(result.source.contains("template \$robot\nline"))
    }

    @Test
    fun `embedded hashes detect stale or edited generated source`() {
        val original = generate(
            catalog = catalog(actions = listOf(action("intake.stop"))),
            routines = listOf(simpleRoutine("stop", RoutineStep.action("intake.stop")))
        )
        assertTrue(original.contentHash.matches(Regex("[a-f0-9]{64}")))
        assertTrue(original.sourceHash.matches(Regex("[a-f0-9]{64}")))
        assertTrue(AresKotlinProjectGenerator.hasValidEmbeddedSourceHash(original.source))
        assertFalse(AresKotlinProjectGenerator.hasValidEmbeddedSourceHash(original.source.replace("intake.stop", "intake.start")))

        val changed = generate(
            catalog = catalog(actions = listOf(action("intake.stop"))),
            routines = listOf(simpleRoutine("stop", RoutineStep.wait(0.25)))
        )
        assertNotEquals(original.contentHash, changed.contentHash)
        assertNotEquals(original.sourceHash, changed.sourceHash)
    }

    @Test
    fun `invalid references and typed arguments fail generation closed`() {
        val knownCatalog = catalog(actions = listOf(action("arm.raise", listOf(numberParameter("height", true)))))

        assertFailsWith<IllegalArgumentException> {
            generate(knownCatalog, listOf(simpleRoutine("unknown", RoutineStep.action("arm.lower"))))
        }
        assertFailsWith<IllegalArgumentException> {
            generate(knownCatalog, listOf(simpleRoutine("missing", RoutineStep.action("arm.raise"))))
        }
        assertFailsWith<IllegalArgumentException> {
            generate(
                knownCatalog,
                listOf(simpleRoutine("malformed", RoutineStep.action("arm.raise", mapOf("height" to "fast"))))
            )
        }
    }

    @Test
    fun `autonomous selector and platform-specific controller runtimes are emitted`() {
        val projectCatalog = catalog(
            actions = listOf(
                action("intake.toggle"),
                action("drive.throttle", listOf(numberParameter("value", true)))
            )
        )
        val routine = simpleRoutine("score_one", RoutineStep.action("intake.toggle"))
        val profile = ControllerProfileDocument(
            documentId = "vader5pro",
            displayName = "Flydigi Vader 5 Pro",
            controls = listOf(
                control("c_button", ControllerControlTypeDocument.BUTTON, glfw = 2, ftc = 7),
                control("rear_m4", ControllerControlTypeDocument.BUTTON, glfw = 12, ftc = 19),
                control("right_trigger", ControllerControlTypeDocument.AXIS, glfw = 5, ftc = 3)
            )
        )
        val controls = ControlSchemeDocument(
            documentId = "competition",
            name = "Competition",
            controllers = listOf(ControllerAssignment("driver", "Driver", profile.documentId)),
            bindings = listOf(
                ControlBindingDocument(
                    bindingId = "toggle_intake",
                    displayName = "Toggle intake",
                    source = ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("c_button")),
                    event = ControlEvent.RELEASE,
                    target = ControlTargetDocument(ControlTargetKind.ACTION, "intake.toggle"),
                    timing = ControlTimingDocument(pressDebounceSeconds = 0.02, cooldownSeconds = 0.1)
                ),
                ControlBindingDocument(
                    bindingId = "score_chord",
                    displayName = "Score chord",
                    source = ControlSourceDocument(
                        ControlSourceKind.CHORD,
                        "driver",
                        listOf("c_button", "rear_m4"),
                        chordWindowSeconds = 0.08
                    ),
                    event = ControlEvent.HOLD,
                    target = ControlTargetDocument(ControlTargetKind.ROUTINE, "score_one"),
                    timing = ControlTimingDocument(holdAfterSeconds = 0.25),
                    priority = 10,
                    suppressConstituentBindings = true
                ),
                ControlBindingDocument(
                    bindingId = "throttle",
                    displayName = "Throttle",
                    source = ControlSourceDocument(ControlSourceKind.AXIS_VALUE, "driver", listOf("right_trigger")),
                    event = ControlEvent.VALUE,
                    target = ControlTargetDocument(ControlTargetKind.ACTION, "drive.throttle"),
                    analogPolicy = AnalogControlPolicyDocument(valueArgumentKey = "value")
                )
            )
        )
        val autonomous = AutonomousCatalogDocument(
            projectId = "test-project",
            defaultEntryId = "score",
            entries = listOf(
                AutonomousCatalogEntry(
                    entryId = "score",
                    displayName = "Score One",
                    routineId = routine.documentId,
                    startingPose = RoutinePose(1.0, 2.0, 0.5)
                )
            )
        )

        val result = AresKotlinProjectGenerator.generate(
            KotlinProjectCodegenRequest(
                packageName = "org.example.generated",
                catalog = projectCatalog,
                routines = listOf(routine),
                autonomousCatalog = autonomous,
                controlSchemes = listOf(controls),
                controllerProfiles = listOf(profile),
                targetInputPlatform = ControllerInputPlatform.FTC
            )
        )

        assertTrue(result.source.contains("buttonIndex = 7"))
        assertTrue(result.source.contains("buttonIndexes = intArrayOf(7, 19)"))
        assertFalse(result.source.contains("buttonIndex = 2"))
        assertTrue(result.source.contains("axisIndex = 3"))
        assertTrue(result.source.contains("SuppressingButtonChordSource("))
        assertTrue(result.source.contains("SuppressibleButtonSource("))
        assertTrue(result.source.contains("pressDebounceNanos = 80000000L"))
        assertTrue(result.source.contains("simultaneityWindowNanos = 80000000L"))
        assertTrue(result.source.contains("fun controlActionDriveThrottle(value: Double): Unit"))
        assertTrue(result.source.contains("registry.controlActionDriveThrottle("))
        assertFalse(result.source.contains("task = registry.actionDriveThrottle("))
        assertTrue(result.source.contains("value = value"))
        assertTrue(result.source.contains("if (reason == BindingReleaseReason.INPUT_RELEASED)"))
        assertTrue(result.source.contains("DEFAULT_AUTONOMOUS_ENTRY_ID: String? = \"score\""))
    }

    @Test
    fun `controller generation rejects a profile learned only on another platform`() {
        val projectCatalog = catalog(actions = listOf(action("intake.toggle")))
        val routine = simpleRoutine("safe", RoutineStep.action("intake.toggle"))
        val profile = ControllerProfileDocument(
            documentId = "desktop-only",
            displayName = "Desktop only",
            controls = listOf(control("a", ControllerControlTypeDocument.BUTTON, glfw = 0, ftc = null))
        )
        val controls = ControlSchemeDocument(
            documentId = "competition",
            name = "Competition",
            controllers = listOf(ControllerAssignment("driver", "Driver", profile.documentId)),
            bindings = listOf(
                ControlBindingDocument(
                    bindingId = "toggle",
                    displayName = "Toggle",
                    source = ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("a")),
                    event = ControlEvent.PRESS,
                    target = ControlTargetDocument(ControlTargetKind.ACTION, "intake.toggle")
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            AresKotlinProjectGenerator.generate(
                KotlinProjectCodegenRequest(
                    packageName = "org.example.generated",
                    catalog = projectCatalog,
                    routines = listOf(routine),
                    controlSchemes = listOf(controls),
                    controllerProfiles = listOf(profile),
                    targetInputPlatform = ControllerInputPlatform.FTC
                )
            )
        }
    }

    private fun generate(
        catalog: CapabilityCatalogDocument,
        routines: Collection<RoutineDocument>
    ): GeneratedKotlinSource = AresKotlinProjectGenerator.generate(
        KotlinProjectCodegenRequest(
            packageName = "org.example.generated",
            catalog = catalog,
            routines = routines
        )
    )

    private fun catalog(
        actions: List<ActionDescriptor>,
        conditions: List<ConditionDescriptor> = emptyList()
    ) = CapabilityCatalogDocument(projectId = "test-project", actions = actions, conditions = conditions)

    private fun action(key: String, parameters: List<CapabilityParameterDescriptor> = emptyList()) =
        ActionDescriptor(key, key, "Action $key", parameters = parameters)

    private fun condition(key: String) = ConditionDescriptor(key, key, "Condition $key")

    private fun numberParameter(key: String, required: Boolean) = CapabilityParameterDescriptor(
        key = key,
        displayName = key,
        description = "Number $key",
        type = CapabilityParameterType.NUMBER,
        required = required
    )

    private fun textParameter(key: String) = CapabilityParameterDescriptor(
        key = key,
        displayName = key,
        description = "Text $key",
        type = CapabilityParameterType.TEXT
    )

    private fun simpleRoutine(id: String, step: RoutineStep) = RoutineDocument(
        documentId = id,
        name = id,
        steps = listOf(step)
    )

    private fun control(
        id: String,
        type: ControllerControlTypeDocument,
        glfw: Int,
        ftc: Int?
    ): ControllerControlDocument = ControllerControlDocument(
        controlId = id,
        displayName = id,
        type = type,
        anchor = ControllerAnchorDocument(0.5, 0.5),
        mappings = buildList {
            if (type == ControllerControlTypeDocument.BUTTON) {
                add(ControllerInputMappingDocument(ControllerInputPlatform.DESKTOP_GLFW, buttonIndex = glfw))
                ftc?.let { add(ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = it)) }
            } else {
                add(ControllerInputMappingDocument(ControllerInputPlatform.DESKTOP_GLFW, axisIndex = glfw))
                ftc?.let { add(ControllerInputMappingDocument(ControllerInputPlatform.FTC, axisIndex = it)) }
            }
        }
    )
}
