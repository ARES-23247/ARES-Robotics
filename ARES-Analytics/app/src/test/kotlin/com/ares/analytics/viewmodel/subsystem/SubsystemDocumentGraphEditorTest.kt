package com.ares.analytics.viewmodel.subsystem

import com.areslib.subsystem.FaultRecoveryActionKind
import com.areslib.subsystem.SimInteractionRole
import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFaultRecoveryDocument
import com.areslib.subsystem.SubsystemFeedforwardDocument
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemFollowerDocument
import com.areslib.subsystem.SubsystemFollowerTransform
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHomingComparison
import com.areslib.subsystem.SubsystemHomingDocument
import com.areslib.subsystem.SubsystemHomingEvidenceDocument
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemImplementationDocument
import com.areslib.subsystem.SubsystemLinkageDocument
import com.areslib.subsystem.SubsystemMeasurementDocument
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemSafetyDocument
import com.areslib.subsystem.SubsystemSimInteractionDocument
import com.areslib.subsystem.SubsystemSimulationDocument
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubsystemDocumentGraphEditorTest {
    @Test
    fun `hardware rename updates every owned hardware reference`() {
        val renamed = SubsystemDocumentGraphEditor.renameHardware(document(), "leader", "liftMotor")

        assertTrue(renamed.hardware.any { it.hardwareId == "liftMotor" })
        assertEquals("liftMotor", renamed.hardware.single { it.hardwareId == "follower" }.following?.leaderId)
        assertEquals("liftMotor", renamed.controlLoops.single().actuatorId)
        assertEquals("liftMotor", renamed.safety.homing.actuatorId)
        assertEquals("liftMotor", renamed.safety.faultRecovery.actuatorId)
        assertEquals("liftMotor", renamed.linkage.joint1ActuatorId)
        assertEquals("liftMotor", renamed.implementation.simulation.interaction.triggerActuatorId)
    }

    @Test
    fun `hardware removal clears all dangling ownership references`() {
        val removed = SubsystemDocumentGraphEditor.removeHardware(document(), "leader")

        assertTrue(removed.hardware.none { it.hardwareId == "leader" })
        assertNull(removed.hardware.single().following)
        assertTrue(removed.controlLoops.isEmpty())
        assertNull(removed.safety.homing.actuatorId)
        assertNull(removed.safety.faultRecovery.actuatorId)
        assertNull(removed.linkage.joint1ActuatorId)
        assertNull(removed.implementation.simulation.interaction.triggerActuatorId)
    }

    @Test
    fun `state field rename updates every owned field reference`() {
        val renamed = SubsystemDocumentGraphEditor.renameStateField(document(), "position", "angleRad")

        assertTrue(renamed.stateFields.any { it.fieldId == "angleRad" })
        assertEquals("angleRad", renamed.hardware.first().measurements.first().fieldId)
        assertEquals("angleRad", renamed.controlLoops.single().measurementFieldId)
        assertEquals("angleRad", renamed.controlLoops.single().feedforward.gravityAngleFieldId)
        assertEquals("angleRad", renamed.safety.homing.evidence.single().fieldId)
        assertEquals("angleRad", renamed.safety.faultRecovery.currentFieldId)
        assertEquals("angleRad", renamed.linkage.joint1AngleFieldId)
        assertEquals("angleRad", renamed.implementation.simulation.interaction.beamBreakFieldId)
    }

    @Test
    fun `state field removal removes dependent behavior instead of leaving invalid references`() {
        val removed = SubsystemDocumentGraphEditor.removeStateField(document(), "position")

        assertTrue(removed.stateFields.none { it.fieldId == "position" })
        assertTrue(removed.hardware.first().measurements.isEmpty())
        assertTrue(removed.controlLoops.isEmpty())
        assertTrue(removed.safety.homing.evidence.isEmpty())
        assertNull(removed.safety.faultRecovery.currentFieldId)
        assertNull(removed.linkage.joint1AngleFieldId)
        assertNull(removed.implementation.simulation.interaction.beamBreakFieldId)
    }

    @Test
    fun `making an actuator a follower removes conflicting controller and homing ownership`() {
        val followed = SubsystemDocumentGraphEditor.setFollower(
            document = document(),
            hardwareId = "leader",
            leaderId = "follower",
            transform = SubsystemFollowerTransform.INVERTED,
        )

        assertEquals("follower", followed.hardware.first().following?.leaderId)
        assertEquals(SubsystemFollowerTransform.INVERTED, followed.hardware.first().following?.transform)
        assertTrue(followed.controlLoops.isEmpty())
        assertEquals(SubsystemHomingDocument(), followed.safety.homing)
    }

    private fun document(): SubsystemDocument = SubsystemDocument(
        documentId = "test",
        displayName = "Test",
        kotlinTypeName = "Test",
        platform = SubsystemPlatform.FTC,
        hardware = listOf(
            SubsystemHardwareDocument(
                hardwareId = "leader",
                displayName = "Leader",
                kind = SubsystemHardwareKind.MOTOR,
                measurements = listOf(
                    SubsystemMeasurementDocument("position", SubsystemMeasurementSource.MOTOR_POSITION_NATIVE),
                ),
                safeOutput = 0.0,
            ),
            SubsystemHardwareDocument(
                hardwareId = "follower",
                displayName = "Follower",
                kind = SubsystemHardwareKind.MOTOR,
                following = SubsystemFollowerDocument("leader"),
                safeOutput = 0.0,
            ),
        ),
        stateFields = listOf(
            SubsystemStateFieldDocument(
                fieldId = "target",
                displayName = "Target",
                type = SubsystemValueType.DOUBLE,
                role = SubsystemFieldRole.TARGET,
                unit = "rad",
                defaultNumber = 0.0,
            ),
            SubsystemStateFieldDocument(
                fieldId = "position",
                displayName = "Position",
                type = SubsystemValueType.DOUBLE,
                role = SubsystemFieldRole.MEASUREMENT,
                unit = "rad",
                defaultNumber = 0.0,
            ),
        ),
        controlLoops = listOf(
            SubsystemControlLoopDocument(
                loopId = "positionLoop",
                displayName = "Position loop",
                strategy = SubsystemControlStrategy.POSITION_PID,
                actuatorId = "leader",
                targetFieldId = "target",
                measurementFieldId = "position",
                feedforward = SubsystemFeedforwardDocument(
                    kind = SubsystemFeedforwardKind.ARM,
                    gravityAngleFieldId = "position",
                ),
            ),
        ),
        safety = SubsystemSafetyDocument(
            homing = SubsystemHomingDocument(
                method = SubsystemHomingMethod.CUSTOM_MEASUREMENT,
                actuatorId = "leader",
                searchOutput = -1.0,
                evidence = listOf(
                    SubsystemHomingEvidenceDocument(
                        fieldId = "position",
                        comparison = SubsystemHomingComparison.AT_OR_BELOW,
                        threshold = 0.0,
                    ),
                ),
            ),
            faultRecovery = SubsystemFaultRecoveryDocument(
                enabled = true,
                actuatorId = "leader",
                currentFieldId = "position",
                recoveryAction = FaultRecoveryActionKind.NEUTRAL_STOP,
            ),
        ),
        linkage = SubsystemLinkageDocument(
            enabled = true,
            joint1ActuatorId = "leader",
            joint1AngleFieldId = "position",
        ),
        implementation = SubsystemImplementationDocument(
            simulation = SubsystemSimulationDocument(
                interaction = SubsystemSimInteractionDocument(
                    role = SimInteractionRole.INTAKE_COLLECTOR,
                    triggerActuatorId = "leader",
                    beamBreakFieldId = "position",
                ),
            ),
        ),
    )
}
