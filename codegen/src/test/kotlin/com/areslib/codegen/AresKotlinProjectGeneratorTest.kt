package com.areslib.codegen

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.catalog.ConditionDescriptor
import com.areslib.catalog.ResourceAccess
import com.areslib.catalog.ResourceClaim
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
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcHubCommandTransport
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresRuntimeOptionsDocument
import com.areslib.subsystem.SubsystemTargetCapability
import com.areslib.subsystem.SubsystemValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AresKotlinProjectGeneratorTest {
    @Test
    fun `generated project exposes canonical FTC runtime choices`() {
        val result = AresKotlinProjectGenerator.generate(
            KotlinProjectCodegenRequest(
                packageName = "org.example.generated",
                catalog = catalog(actions = emptyList()),
                routines = emptyList(),
                projectMetadata = AresProjectMetadataDocument(
                    projectId = "test-project",
                    identity = AresProjectIdentityDocument("99999", "2026", "test-robot", "Test Robot"),
                    league = AresLeague.FTC,
                    coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                    robotLengthMeters = 0.44,
                    robotWidthMeters = 0.44,
                    fieldLengthMeters = 3.6576,
                    fieldWidthMeters = 3.6576,
                    runtimeOptions = AresRuntimeOptionsDocument(
                        AresFtcRuntimeOptionsDocument(AresFtcHubCommandTransport.ARES_PHOTON, true),
                    ),
                ),
            ),
        )

        assertTrue("object RuntimeOptions" in result.source)
        assertTrue("FTC_HUB_COMMAND_TRANSPORT: String = \"ARES_PHOTON\"" in result.source)
        assertTrue("FTC_LIMELIGHT_PROXY_ENABLED: Boolean = true" in result.source)
    }

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
            .substringAfter("/** Stable robot boundary for capabilities referenced by generated project documents. */\n")
            .substringBefore("\n\n/** Generated from the project's canonical ARES documents. Do not edit by hand. */")

        assertEquals(
            """interface GeneratedAresProjectCapabilities {
    /** Creates a hand-authored or season action by its catalog key, or null when unavailable. */
    fun createActionTask(actionKey: String, arguments: Map<String, String>): Task? = null

    /**
     * Receives the combined teleop drivetrain command once per frame. Values are normalized
     * (-1..1) after each axis binding's transform, field-centric with CCW-positive rotation;
     * the implementation scales them by drivetrain limits and applies alliance mirroring.
     * [active] is false when the scheme has no drive bindings, so sinks without generated
     * drivetrain control stay inert.
     */
    fun onDriveCommand(vx: Double, vy: Double, omega: Double, active: Boolean) = Unit

    /** Creates a hand-authored condition predicate by its catalog key, or null when unavailable. */
    fun createCondition(conditionKey: String, arguments: Map<String, String>): ((RobotState) -> Boolean)? = null

    /** Platform trajectory adapter; returning null rejects a drive step safely. */
    fun createDriveTask(step: RoutineDriveStep): Task? = null
}""",
            golden
        )
    }

    @Test
    fun `READ claims do not create parallel conflicts and are omitted from generated resource sets`() {
        val feed = action("shooter.feed").copy(
            resources = listOf(
                ResourceClaim("shooter.flywheel", ResourceAccess.READ),
                ResourceClaim("shooter.cowl", ResourceAccess.READ),
                ResourceClaim("shooter.feeder", ResourceAccess.EXCLUSIVE)
            )
        )
        val spinUp = action("shooter.prepare").copy(
            resources = listOf(
                ResourceClaim("shooter.flywheel", ResourceAccess.EXCLUSIVE),
                ResourceClaim("shooter.cowl", ResourceAccess.EXCLUSIVE)
            )
        )

        // Parallelizing a READ-only consumer with the EXCLUSIVE owner is exactly the shot
        // feed / spin-up pattern; flattening READ claims in made generation reject this.
        val result = generate(
            catalog = catalog(actions = listOf(feed, spinUp)),
            routines = listOf(
                simpleRoutine(
                    "parallel-feed",
                    RoutineStep.together(
                        listOf(RoutineStep.action("shooter.feed"), RoutineStep.action("shooter.prepare"))
                    )
                )
            )
        )

        assertTrue("\"shooter.feed\" -> setOf(\"shooter.feeder\")" in result.source)
        assertTrue("\"shooter.prepare\" -> setOf(\"shooter.cowl\", \"shooter.flywheel\")" in result.source)
    }

    @Test
    fun `EXCLUSIVE claims shared across parallel branches still fail generation closed`() {
        val a = action("intake.collect").copy(resources = listOf(ResourceClaim("floor", ResourceAccess.EXCLUSIVE)))
        val b = action("shooter.feed").copy(resources = listOf(ResourceClaim("floor", ResourceAccess.EXCLUSIVE)))

        val failure = assertFailsWith<IllegalArgumentException> {
            generate(
                catalog = catalog(actions = listOf(a, b)),
                routines = listOf(
                    simpleRoutine(
                        "conflicting",
                        RoutineStep.together(
                            listOf(RoutineStep.action("intake.collect"), RoutineStep.action("shooter.feed"))
                        )
                    )
                )
            )
        }
        // The joined ERROR text carries the human-readable conflict, not the issue code.
        assertTrue("both require 'floor'" in (failure.message ?: ""))
    }

    @Test
    fun `hand authored catalog additions do not change the robot runtime interface`() {
        fun interfaceSource(actions: List<ActionDescriptor>): String = generate(
            catalog = catalog(actions = actions, conditions = listOf(condition("robot.ready"))),
            routines = emptyList(),
        ).source
            .substringAfter("/** Stable robot boundary for capabilities referenced by generated project documents. */\n")
            .substringBefore("\n\n/** Generated from the project's canonical ARES documents. Do not edit by hand. */")

        val oneAction = listOf(action("intake.stop"))
        val twoActions = oneAction + action(
            "arm.raise",
            listOf(numberParameter("height", required = true)),
        )
        val source = generate(
            catalog = catalog(actions = twoActions),
            routines = listOf(simpleRoutine("raise", RoutineStep.action("arm.raise", mapOf("height" to "0.5")))),
        ).source

        assertEquals(interfaceSource(oneAction), interfaceSource(twoActions))
        assertFalse(source.contains("fun actionArmRaise"))
        assertTrue(source.contains("parsed.requiredNumber(\"height\""))
        assertTrue(source.contains("registry.createActionTask(key, arguments)"))
    }

    @Test
    fun `project without control schemes emits a stable no-op controller runtime surface`() {
        val source = generate(
            catalog = catalog(actions = listOf(action("intake.stop"))),
            routines = listOf(simpleRoutine("stop", RoutineStep.action("intake.stop")))
        ).source

        assertTrue(source.contains("GeneratedControlTaskSink"))
        assertTrue(source.contains("val runtimeDefinition = GeneratedProjectDefinition("))
        assertTrue(source.contains("knownControlSchemeIds: Set<String> = emptySet()"))
        assertTrue(source.contains("DEFAULT_CONTROL_SCHEME_ID: String? = null"))
        assertTrue(source.contains("createControllerRuntimes"))
        assertTrue(source.contains("Map<Int, ControllerBindingRuntime>"))
        assertTrue(source.contains("return emptyMap()"))
        assertFalse(source.contains("com.areslib.input.AnalogBinding"))
        assertFalse(source.contains("com.areslib.input.DigitalBinding"))
        assertTrue(source.contains("com.areslib.routine.RoutineManager"))
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
            controllers = listOf(ControllerAssignment("driver", "Driver", profile.documentId, devicePort = 0)),
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
        assertTrue(result.source.contains("DEFAULT_CONTROL_SCHEME_ID: String? = \"competition\""))
        assertTrue(result.source.contains("0 to ControllerBindingRuntime("))
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
            controllers = listOf(ControllerAssignment("driver", "Driver", profile.documentId, devicePort = 0)),
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

    @Test
    fun `generation requires one active scheme and a platform-supported controller port`() {
        val projectCatalog = catalog(actions = listOf(action("intake.toggle")))
        val profile = ControllerProfileDocument(
            documentId = "standard",
            displayName = "Standard",
            controls = listOf(control("a", ControllerControlTypeDocument.BUTTON, glfw = 0, ftc = 0)),
        )
        fun scheme(id: String, port: Int) = ControlSchemeDocument(
            documentId = id,
            name = id,
            controllers = listOf(ControllerAssignment("driver", "Driver", profile.documentId, port)),
            bindings = listOf(
                ControlBindingDocument(
                    bindingId = "$id.toggle",
                    displayName = "Toggle",
                    source = ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("a")),
                    event = ControlEvent.PRESS,
                    target = ControlTargetDocument(ControlTargetKind.ACTION, "intake.toggle"),
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            AresKotlinProjectGenerator.generate(
                KotlinProjectCodegenRequest(
                    packageName = "org.example.generated",
                    catalog = projectCatalog,
                    routines = emptyList(),
                    controlSchemes = listOf(scheme("primary", 0), scheme("practice", 1)),
                    controllerProfiles = listOf(profile),
                    targetInputPlatform = ControllerInputPlatform.FTC,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AresKotlinProjectGenerator.generate(
                KotlinProjectCodegenRequest(
                    packageName = "org.example.generated",
                    catalog = projectCatalog,
                    routines = emptyList(),
                    controlSchemes = listOf(scheme("primary", 2)),
                    controllerProfiles = listOf(profile),
                    targetInputPlatform = ControllerInputPlatform.FTC,
                ),
            )
        }
    }

    @Test
    fun `generated subsystem analog binding uses bounded task sink instead of an unimplemented continuous method`() {
        val subsystemAction = action(
            "subsystem.arm.set.power",
            listOf(numberParameter("value", required = true)),
        )
        val target = SubsystemTargetCapability(
            subsystemId = "arm",
            fieldId = "power",
            valueType = SubsystemValueType.DOUBLE,
            descriptor = subsystemAction,
        )
        val profile = ControllerProfileDocument(
            documentId = "standard",
            displayName = "Standard",
            controls = listOf(control("left_stick_y", ControllerControlTypeDocument.AXIS, glfw = 1, ftc = 1)),
        )
        fun request(policy: AnalogControlPolicyDocument) = KotlinProjectCodegenRequest(
            packageName = "org.example.generated",
            catalog = catalog(actions = listOf(subsystemAction)),
            routines = emptyList(),
            controlSchemes = listOf(
                ControlSchemeDocument(
                    documentId = "competition",
                    name = "Competition",
                    controllers = listOf(ControllerAssignment("driver", "Driver", profile.documentId, 0)),
                    bindings = listOf(
                        ControlBindingDocument(
                            bindingId = "arm.power",
                            displayName = "Arm power",
                            source = ControlSourceDocument(ControlSourceKind.AXIS_VALUE, "driver", listOf("left_stick_y")),
                            event = ControlEvent.VALUE,
                            target = ControlTargetDocument(ControlTargetKind.ACTION, subsystemAction.key),
                            analogPolicy = policy,
                        ),
                    ),
                ),
            ),
            controllerProfiles = listOf(profile),
            targetInputPlatform = ControllerInputPlatform.FTC,
            subsystemActions = listOf(target),
            subsystemRegistryFqn = "org.example.generated.subsystems.GeneratedSubsystemRegistry",
        )

        val source = AresKotlinProjectGenerator.generate(
            request(AnalogControlPolicyDocument(emitOnlyOnChange = true, changeEpsilon = 0.01)),
        ).source

        assertFalse(source.contains("fun controlActionSubsystemArmSetPower"))
        assertTrue(source.contains("task = registry.actionSubsystemArmSetPower("))
        assertFailsWith<IllegalArgumentException> {
            AresKotlinProjectGenerator.generate(request(AnalogControlPolicyDocument(emitOnlyOnChange = false)))
        }
    }

    @Test
    fun `drivetrain axis bindings render an accumulator a drive sink and inert baselines`() {
        val profile = ControllerProfileDocument(
            documentId = "standard",
            displayName = "Standard",
            controls = listOf(
                control("left_stick_y", ControllerControlTypeDocument.AXIS, glfw = 1, ftc = 1),
                control("left_stick_x", ControllerControlTypeDocument.AXIS, glfw = 0, ftc = 0),
                control("right_stick_x", ControllerControlTypeDocument.AXIS, glfw = 2, ftc = 2),
            ),
        )
        fun driveBinding(id: String, axis: String, controlId: String) = ControlBindingDocument(
            bindingId = id,
            displayName = "Drive $axis",
            source = ControlSourceDocument(ControlSourceKind.AXIS_VALUE, "driver", listOf(controlId)),
            event = ControlEvent.VALUE,
            target = ControlTargetDocument(ControlTargetKind.DRIVE, axis),
            analogPolicy = AnalogControlPolicyDocument(),
        )
        fun request(bindings: List<ControlBindingDocument>) = KotlinProjectCodegenRequest(
            packageName = "org.example.generated",
            catalog = catalog(actions = emptyList()),
            routines = emptyList(),
            controlSchemes = listOf(
                ControlSchemeDocument(
                    documentId = "competition",
                    name = "Competition",
                    controllers = listOf(ControllerAssignment("driver", "Driver", profile.documentId, 0)),
                    bindings = bindings,
                ),
            ),
            controllerProfiles = listOf(profile),
            targetInputPlatform = ControllerInputPlatform.FTC,
        )

        val generated = AresKotlinProjectGenerator.generate(
            request(
                listOf(
                    driveBinding("drive.vx", "vx", "left_stick_y"),
                    driveBinding("drive.vy", "vy", "left_stick_x"),
                    driveBinding("drive.omega", "omega", "right_stick_x"),
                ),
            ),
        ).source

        assertTrue(generated.contains("val HAS_GENERATED_DRIVE_BINDINGS: Boolean = true"))
        assertTrue(generated.contains("driveAxisValues[0] = value"))
        assertTrue(generated.contains("driveAxisValues[1] = value"))
        assertTrue(generated.contains("driveAxisValues[2] = value"))
        assertTrue(
            generated.contains(
                "registry.onDriveCommand(driveAxisValues[0], driveAxisValues[1], driveAxisValues[2], HAS_GENERATED_DRIVE_BINDINGS)"
            ),
        )
        assertFalse(generated.contains("taskSink.submit"), "drive bindings never submit tasks")

        val withoutDrive = generate(catalog(actions = emptyList()), emptyList()).source
        assertTrue(withoutDrive.contains("val HAS_GENERATED_DRIVE_BINDINGS: Boolean = false"))
        assertTrue(withoutDrive.contains("fun emitDriveCommand(registry: GeneratedAresProjectCapabilities)"))

        assertFailsWith<IllegalArgumentException> {
            AresKotlinProjectGenerator.generate(
                request(
                    listOf(
                        driveBinding("drive.vx", "vx", "left_stick_y"),
                        driveBinding("drive.vx-alt", "vx", "left_stick_x"),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AresKotlinProjectGenerator.generate(request(listOf(driveBinding("drive.bad", "throttle", "left_stick_y"))))
        }
    }

    @Test
    fun `generated orchestration action delegates to its typed registry and affects the content hash`() {
        val descriptor = action("machine.activate")
        val projectCatalog = catalog(actions = listOf(descriptor))
        val baseline = generate(projectCatalog, emptyList())
        val generated = AresKotlinProjectGenerator.generate(
            KotlinProjectCodegenRequest(
                packageName = "org.example.generated",
                catalog = projectCatalog,
                routines = emptyList(),
                generatedActionRegistryBindings = mapOf(
                    descriptor.key to "org.example.generated.superstructure.GeneratedSuperstructureRegistry",
                ),
            ),
        )

        assertTrue(generated.source.contains("fun actionMachineActivate(): Task"))
        assertTrue(generated.source.contains("GeneratedSuperstructureRegistry.createActionTask(\"machine.activate\")"))
        assertNotEquals(baseline.contentHash, generated.contentHash)

        assertFailsWith<IllegalArgumentException> {
            AresKotlinProjectGenerator.generate(
                KotlinProjectCodegenRequest(
                    packageName = "org.example.generated",
                    catalog = catalog(actions = listOf(action("machine.set", listOf(numberParameter("value", true))))),
                    routines = emptyList(),
                    generatedActionRegistryBindings = mapOf(
                        "machine.set" to "org.example.generated.superstructure.GeneratedSuperstructureRegistry",
                    ),
                ),
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
