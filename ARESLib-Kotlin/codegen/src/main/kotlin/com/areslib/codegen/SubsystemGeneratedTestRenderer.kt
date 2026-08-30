package com.areslib.codegen

import com.areslib.subsystem.*

/** Renders generated behavioral verification for each declarative subsystem contract. */
internal object SubsystemGeneratedTestRenderer {
    fun render(document: SubsystemDocument, pkg: String): String {
        val firstTarget = document.stateFields.firstOrNull { it.role == SubsystemFieldRole.TARGET }
        val assertion = firstTarget?.let {
            if (it.type == SubsystemValueType.DOUBLE) {
                "assertEquals(${it.defaultKotlinLiteral()}, state.${it.fieldId}, 0.0)"
            } else {
                "assertEquals(${it.defaultKotlinLiteral()}, state.${it.fieldId})"
            }
        } ?: "assertNotNull(state)"
        val imports = when (document.platform) {
            SubsystemPlatform.FTC -> """import org.junit.Assert.assertEquals
            import org.junit.Assert.assertFalse
            import org.junit.Assert.assertNotNull
            import org.junit.Assert.assertTrue
            import org.junit.Test"""
            SubsystemPlatform.FRC -> """import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Assertions.assertFalse
            import org.junit.jupiter.api.Assertions.assertNotNull
            import org.junit.jupiter.api.Assertions.assertTrue
            import org.junit.jupiter.api.Test"""
        }
        val firstActuator = document.actuatorLeaders().firstOrNull()
        val actuatorAssertions = firstActuator?.let { device ->
            val command = "io.${device.commandName()}"
            val observed = "io.${device.hardwareId}Command"
            val neutral = device.invertedExpression(requireNotNull(device.safeOutput).kotlinDouble())
            val validCommand = when (device.kind) {
                SubsystemHardwareKind.MOTOR -> 6.0
                SubsystemHardwareKind.POSITIONAL_SERVO -> 0.75
                SubsystemHardwareKind.CONTINUOUS_SERVO -> 0.5
                SubsystemHardwareKind.INDICATOR_LIGHT -> 0.75
                SubsystemHardwareKind.PRISM_DRIVER -> 1500.0
                SubsystemHardwareKind.SOLENOID -> 1.0
                else -> error("Not actuator")
            }.kotlinDouble()
            val followerActiveAssertions = document.followersOf(device.hardwareId).joinToString("\n") { follower ->
                val expected = follower.invertedExpression(
                    follower.following!!.transformedExpression(validCommand),
                )
                "        assertEquals($expected, io.${follower.hardwareId}Command, 0.0)"
            }
            val followerNeutralAssertions = document.followersOf(device.hardwareId).joinToString("\n") { follower ->
                val expected = follower.invertedExpression(requireNotNull(follower.safeOutput).kotlinDouble())
                "        assertEquals($expected, io.${follower.hardwareId}Command, 0.0)"
            }
            val postFailureAssertions = if (document.safety.latchOutputFaults) {
                """
                    assertTrue(io.outputFaultLatched)
                    $command(3.0)
                    assertEquals($neutral, $observed, 0.0)
                """.trimIndent()
            } else {
                """
                    assertFalse(io.outputFaultLatched)
                    $command($validCommand)
                    assertEquals(${device.invertedExpression(validCommand)}, $observed, 0.0)
                """.trimIndent()
            }
            """
                    $command($validCommand)
                    assertEquals(${device.invertedExpression(validCommand)}, $observed, 0.0)
            $followerActiveAssertions
                    io.failNextWrite = true
                    $command(4.0)
                    assertEquals($neutral, $observed, 0.0)
            $followerNeutralAssertions
            $postFailureAssertions
                    assertTrue(io.recoverWithNeutral())
                    assertFalse(io.outputFaultLatched)
            """.trimIndent()
        }.orEmpty()
        val homingAssertions = if (document.requiresHoming()) {
            val evidenceAssignments = document.safety.homing.evidence.joinToString("\n") { evidence ->
                when (evidence.comparison) {
                    SubsystemHomingComparison.TRUE -> "        io.${evidence.fieldId} = true"
                    SubsystemHomingComparison.FALSE -> "        io.${evidence.fieldId} = false"
                    SubsystemHomingComparison.AT_OR_ABOVE,
                    SubsystemHomingComparison.ABS_AT_OR_ABOVE -> "        io.${evidence.fieldId} = ${requireNotNull(evidence.threshold).kotlinDouble()}"
                    SubsystemHomingComparison.AT_OR_BELOW,
                    SubsystemHomingComparison.ABS_AT_OR_BELOW -> "        io.${evidence.fieldId} = 0.0"
                }
            }
            """
                    assertFalse(io.homed)
                    io.configurationHealthy = true
                    io.calibrated = true
                    io.refresh()
                    assertFalse(io.homed)
$evidenceAssignments
                    io.refresh()
                    assertTrue(io.homingConditionMet)
                    assertTrue(io.commandHoming())
                    assertTrue(io.establishHome())
                    assertTrue(io.homed)
            """.trimIndent()
        } else "        assertTrue(io.homed)"
        val currentField = document.hardware.flatMap { it.measurements }
            .firstOrNull { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
            ?.fieldId
        val currentAssertions = if (document.safety.requiresCurrentMonitoring) currentField?.let { fieldId ->
            """
                    io.$fieldId = -1.0
                    io.refresh()
                    assertFalse(io.currentReadingValid)
            """.trimIndent()
        } ?: error("Validated current monitoring requires a current measurement")
        else "        assertTrue(io.currentReadingValid)"
        val controlLimitAssertions = document.controlLoops.sortedBy { it.loopId }.joinToString("\n\n") { loop ->
            val actuator = document.hardware.first { it.hardwareId == loop.actuatorId }
            val target = requireNotNull(document.field(loop.targetFieldId))
            val extremeTarget = when (target.type) {
                SubsystemValueType.DOUBLE -> "1.0e9"
                SubsystemValueType.INT -> "1000000000"
                SubsystemValueType.BOOLEAN -> "true"
                SubsystemValueType.STRING -> error("Validated control target must be numeric or boolean")
            }
            val low = actuator.invertedExpression(loop.minimumOutput.kotlinDouble())
            val high = actuator.invertedExpression(loop.maximumOutput.kotlinDouble())
            """
                    run {
                        val io = Mock${document.kotlinTypeName}IO()
                        io.configurationHealthy = true
                        io.homed = true
                        io.calibrated = true
                        io.refresh()
                        val controller = ${document.kotlinTypeName}Controller(io)
                        controller.update(${document.kotlinTypeName}State(
                            ${target.fieldId} = $extremeTarget,
                            feedbackValid = true,
                            feedbackTimestampMs = io.feedbackTimestampMs,
                            configurationHealthy = true,
                            homed = true,
                            calibrated = true,
                            currentReadingValid = true,
                        ), 1.0)
                        val command = io.${actuator.hardwareId}Command
                        assertTrue(command.isFinite())
                        assertTrue(command >= minOf($low, $high))
                        assertTrue(command <= maxOf($low, $high))
                    }
            """.trimIndent()
        }
        val homingControllerTest = if (document.requiresHoming()) {
            val evidenceAssignments = document.safety.homing.evidence.joinToString("\n") { evidence ->
                when (evidence.comparison) {
                    SubsystemHomingComparison.TRUE -> "        io.${evidence.fieldId} = true"
                    SubsystemHomingComparison.FALSE -> "        io.${evidence.fieldId} = false"
                    SubsystemHomingComparison.AT_OR_ABOVE,
                    SubsystemHomingComparison.ABS_AT_OR_ABOVE -> "        io.${evidence.fieldId} = ${requireNotNull(evidence.threshold).kotlinDouble()}"
                    SubsystemHomingComparison.AT_OR_BELOW,
                    SubsystemHomingComparison.ABS_AT_OR_BELOW -> "        io.${evidence.fieldId} = 0.0"
                }
            }
            val dwell = document.safety.homing.dwellMs
            """
                @Test
                fun `${SubsystemGeneratedTestNames.HOMING_DWELL}`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    val controller = ${document.kotlinTypeName}Controller(io)
                    val state = ${document.kotlinTypeName}State(
                        feedbackValid = true,
                        configurationHealthy = true,
                        homed = false,
                        homingRequested = true,
                        calibrated = true,
                        currentReadingValid = true,
                    )
                    io.configurationHealthy = true
                    io.calibrated = true
            $evidenceAssignments
                    io.refresh()
                    RobotClock.useMockTime(1_000L)
                    try {
                        controller.update(state, 1.0)
                        assertFalse(io.homed)
                        RobotClock.useMockTime(${1_000L + (dwell - 1L).coerceAtLeast(0L)}L)
                        controller.update(state, 1.0)
                        assertFalse(io.homed)
                        RobotClock.useMockTime(${1_000L + dwell}L)
                        controller.update(state, 1.0)
                        assertTrue(io.homed)
                    } finally {
                        RobotClock.useSystemTime()
                    }
                }

            """.trimIndent()
        } else ""
        val firstTargetOverride = firstTarget?.let { field ->
            when (field.type) {
                SubsystemValueType.DOUBLE -> "${field.fieldId} = ${(field.maximum ?: 1.0).kotlinDouble()},"
                SubsystemValueType.INT -> "${field.fieldId} = ${(field.maximum?.toInt() ?: 1)},"
                SubsystemValueType.BOOLEAN -> "${field.fieldId} = true,"
                SubsystemValueType.STRING -> "${field.fieldId} = \"active\","
            }
        }.orEmpty()
        val generatedCapabilities = subsystemTargetCapabilities(listOf(document))
        val directSetterAssertions = document.stateFields
            .filter { it.role == SubsystemFieldRole.TARGET }
            .sortedBy { it.fieldId }
            .joinToString("\n\n") { field ->
                val value = when (field.type) {
                    SubsystemValueType.DOUBLE -> (field.maximum ?: 1.0).kotlinDouble()
                    SubsystemValueType.INT -> (field.maximum?.toInt() ?: 1).toString()
                    SubsystemValueType.BOOLEAN -> "true"
                    SubsystemValueType.STRING -> "\"generated-test-value\""
                }
                val assertion = if (field.type == SubsystemValueType.DOUBLE) {
                    "assertEquals($value, ${document.kotlinTypeName}Subsystem.state(store.state).${field.fieldId}, 0.0)"
                } else {
                    "assertEquals($value, ${document.kotlinTypeName}Subsystem.state(store.state).${field.fieldId})"
                }
                """
                    subsystem.set${field.fieldId.pascalCase()}(store, $value)
                    $assertion
                """.trimIndent()
            }
        val registeredActionAssertions = generatedCapabilities.joinToString("\n\n") { capability ->
            val parameter = capability.descriptor.parameters.singleOrNull()
            val actionValue = when {
                parameter == null -> "null"
                parameter.options.isNotEmpty() -> parameter.options.last().quoted()
                capability.valueType == SubsystemValueType.DOUBLE ->
                    (parameter.maximum ?: parameter.defaultNumber ?: 1.0).kotlinDouble()
                capability.valueType == SubsystemValueType.INT ->
                    (parameter.maximum ?: parameter.defaultNumber ?: 1.0).toInt().toString()
                capability.valueType == SubsystemValueType.BOOLEAN -> "true"
                else -> (parameter.defaultText ?: "generated-test-value").quoted()
            }
            """
                    run {
                        val before = store.state
                        val task = requireNotNull(registry.createActionTask(${capability.descriptor.key.quoted()}, $actionValue))
                        val actions = task.initialize(store.state)
                        assertTrue(actions.isNotEmpty())
                        actions.forEach { store.dispatch(it) }
                        assertTrue(store.state !== before)
                    }
            """.trimIndent()
        }
        val targetSetterSequenceTest = if (generatedCapabilities.isNotEmpty()) {
            """
                @Test
                fun `${SubsystemGeneratedTestNames.GENERATED_ACTIONS}`() {
                    val subsystem = ${document.kotlinTypeName}Subsystem(Mock${document.kotlinTypeName}IO())
                    val store = Store(RobotState(superstructure = SuperstructureState(
                        subsystems = mapOf(${document.kotlinTypeName}Subsystem.ID to ${document.kotlinTypeName}State())
                    )))
                    val registry = ${pkg.substringBeforeLast('.')}.GeneratedSubsystemRegistry

$directSetterAssertions

$registeredActionAssertions
                }

            """.trimIndent()
        } else ""
        val controllerNeutralAssertion = firstActuator?.let { device ->
            "assertEquals(${requireNotNull(device.safeOutput).kotlinDouble()}, io.${device.hardwareId}Command, 0.0)"
        } ?: "assertNotNull(controller)"
        val neutralRecoveryControllerTest = if (document.safety.requiresExplicitNeutralRecovery) {
            """
                @Test
                fun `${SubsystemGeneratedTestNames.NEUTRAL_RECOVERY}`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    val controller = ${document.kotlinTypeName}Controller(io)
                    io.configurationHealthy = true
                    RobotClock.useMockTime(1_000L)
                    try {
                        io.refresh()
                        val firstRequest = ${document.kotlinTypeName}State(
                            feedbackValid = true,
                            feedbackTimestampMs = 1_000L,
                            configurationHealthy = true,
                            homed = true,
                            calibrated = true,
                            currentReadingValid = true,
                            outputFaultLatched = true,
                            commandSequence = 7L,
                            neutralRecoveryRequestSequence = 1L,
                        )
                        controller.update(firstRequest, 1.0)
                        assertEquals(1, io.neutralRecoveryAttempts)
                        assertFalse(io.outputFaultLatched)
                        val safeCallsAfterRecovery = io.safeCalls
                        controller.update(firstRequest.copy(outputFaultLatched = false), 1.0)
                        assertEquals(1, io.neutralRecoveryAttempts)
                        assertTrue(io.safeCalls > safeCallsAfterRecovery)

                        val safeCallsDuringHold = io.safeCalls
                        controller.update(firstRequest.copy(
                            outputFaultLatched = false,
                            commandSequence = 8L,
                            $firstTargetOverride
                        ), 1.0)
                        assertEquals(safeCallsDuringHold, io.safeCalls)

                        io.outputFaultLatched = true
                        io.failNextWrite = true
                        controller.update(firstRequest.copy(
                            outputFaultLatched = true,
                            commandSequence = 8L,
                            neutralRecoveryRequestSequence = 2L,
                        ), 1.0)
                        assertEquals(2, io.neutralRecoveryAttempts)
                        assertTrue(io.outputFaultLatched)
                        controller.update(firstRequest.copy(
                            outputFaultLatched = true,
                            commandSequence = 8L,
                            neutralRecoveryRequestSequence = 2L,
                        ), 1.0)
                        assertEquals(2, io.neutralRecoveryAttempts)
                        assertTrue(io.outputFaultLatched)
                    } finally {
                        RobotClock.useSystemTime()
                    }
                }

            """.trimIndent()
        } else ""
        val calibrationControllerTest = if (document.safety.requiresCalibration) {
            """
                @Test
                fun `${SubsystemGeneratedTestNames.CALIBRATION}`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    val controller = ${document.kotlinTypeName}Controller(io)
                    io.configurationHealthy = true
                    RobotClock.useMockTime(1_000L)
                    try {
                        io.refresh()
                        val staleRequest = ${document.kotlinTypeName}State(
                            feedbackValid = false,
                            feedbackTimestampMs = 1_000L,
                            configurationHealthy = true,
                            homed = true,
                            calibrated = false,
                            currentReadingValid = true,
                            commandSequence = 5L,
                            calibrationConfirmationRequestSequence = 1L,
                        )
                        controller.update(staleRequest, 1.0)
                        assertEquals(0, io.calibrationEstablishmentAttempts)
                        controller.update(staleRequest.copy(feedbackValid = true), 1.0)
                        assertEquals(0, io.calibrationEstablishmentAttempts)

                        controller.update(staleRequest.copy(
                            feedbackValid = true,
                            outputFaultLatched = true,
                            calibrationConfirmationRequestSequence = 2L,
                        ), 1.0)
                        assertEquals(0, io.neutralRecoveryAttempts)
                        assertEquals(0, io.calibrationEstablishmentAttempts)

                        io.failNextWrite = true
                        controller.update(staleRequest.copy(
                            feedbackValid = true,
                            calibrationConfirmationRequestSequence = 3L,
                        ), 1.0)
                        assertEquals(1, io.neutralRecoveryAttempts)
                        assertEquals(0, io.calibrationEstablishmentAttempts)
                        assertTrue(io.outputFaultLatched)

                        assertTrue(io.recoverWithNeutral())
                        controller.update(staleRequest.copy(
                            feedbackValid = true,
                            calibrationConfirmationRequestSequence = 4L,
                        ), 1.0)
                        assertEquals(3, io.neutralRecoveryAttempts)
                        assertEquals(1, io.calibrationEstablishmentAttempts)
                        assertTrue(io.calibrated)
                        val safeCallsAfterCalibration = io.safeCalls
                        controller.update(staleRequest.copy(
                            feedbackValid = true,
                            calibrated = true,
                            calibrationConfirmationRequestSequence = 4L,
                        ), 1.0)
                        assertTrue(io.safeCalls > safeCallsAfterCalibration)
                        val safeCallsDuringHold = io.safeCalls
                        controller.update(staleRequest.copy(
                            feedbackValid = true,
                            calibrated = true,
                            commandSequence = 6L,
                            calibrationConfirmationRequestSequence = 4L,
                            $firstTargetOverride
                        ), 1.0)
                        assertEquals(safeCallsDuringHold, io.safeCalls)
                    } finally {
                        RobotClock.useSystemTime()
                    }
                }

            """.trimIndent()
        } else ""
        val staleFeedbackTest = document.safety.feedbackTimeoutMs?.let { feedbackTimeoutMs ->
            """
                @Test
                fun `${SubsystemGeneratedTestNames.STALE_FEEDBACK}`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    val subsystem = ${document.kotlinTypeName}Subsystem(io)
                    val store = Store(RobotState(superstructure = SuperstructureState(
                        subsystems = mapOf(${document.kotlinTypeName}Subsystem.ID to ${document.kotlinTypeName}State())
                    )))
                    io.configurationHealthy = true
                    io.homed = true
                    io.calibrated = true
                    io.refresh()
                    subsystem.readSensors(store, io.feedbackTimestampMs + ${feedbackTimeoutMs + 1L}L)
                    assertFalse(${document.kotlinTypeName}Subsystem.state(store.state).feedbackValid)
                    val controller = ${document.kotlinTypeName}Controller(io)
                    controller.update(${document.kotlinTypeName}State(
                        feedbackValid = false,
                        configurationHealthy = true,
                        homed = true,
                        calibrated = true,
                        currentReadingValid = true,
                        $firstTargetOverride
                    ), 1.0)
                    $controllerNeutralAssertion
                }

            """.trimIndent()
        }.orEmpty()
        return """
            package $pkg

            import com.areslib.Store
            import com.areslib.state.RobotState
            import com.areslib.state.SuperstructureState
            import com.areslib.util.RobotClock
            $imports

            class ${document.kotlinTypeName}GeneratedTest {
                @Test
                fun `${SubsystemGeneratedTestNames.SAFE_STARTUP}`() {
                    val state = ${document.kotlinTypeName}State()
                    val io = Mock${document.kotlinTypeName}IO()
                    $assertion
                    io.safe()
                    assertFalse(io.outputFaultLatched)
                }

                @Test
                fun `${SubsystemGeneratedTestNames.OUTPUT_FAULT_POLICY}`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    io.configurationHealthy = true
                    io.homed = true
                    io.calibrated = true
                    io.refresh()
            $actuatorAssertions
                }

                @Test
                fun `${SubsystemGeneratedTestNames.HOMING_AND_CURRENT}`() {
                    val io = Mock${document.kotlinTypeName}IO()
            $homingAssertions
            $currentAssertions
                }

                @Test
                fun `${SubsystemGeneratedTestNames.CONTROL_LIMITS}`() {
$controlLimitAssertions
                }

            $homingControllerTest

            $neutralRecoveryControllerTest

            $calibrationControllerTest

            $targetSetterSequenceTest

            $staleFeedbackTest

                @Test
                fun `${SubsystemGeneratedTestNames.DISABLED_STOP}`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    io.configurationHealthy = true
                    io.homed = true
                    io.calibrated = true
                    io.refresh()
                    val controller = ${document.kotlinTypeName}Controller(io)
                    controller.update(${document.kotlinTypeName}State(
                        feedbackValid = true,
                        configurationHealthy = true,
                        homed = true,
                        calibrated = true,
                        currentReadingValid = true,
                        $firstTargetOverride
                    ), 0.0)
                    $controllerNeutralAssertion
                }

                @Test
                fun `${SubsystemGeneratedTestNames.INVALID_AND_CLEANUP}`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    io.failNextRefresh = true
                    io.refresh()
                    assertFalse(io.feedbackValid)
                    io.close()
                    assertTrue(io.closed)
                    io.close()
                    assertTrue(io.closed)
                }
            }
        """.trimIndent() + "\n"
    }
}

