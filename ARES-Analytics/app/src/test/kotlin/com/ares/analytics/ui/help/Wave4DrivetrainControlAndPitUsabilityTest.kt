package com.ares.analytics.ui.help

import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.subsystems.SubsystemStudentControlRecipe
import com.ares.analytics.ui.components.subsystems.applySubsystemControlRecipe
import com.ares.analytics.ui.screens.StudentPitCheckpointPreset
import com.ares.analytics.ui.screens.projectArea
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderState
import com.ares.analytics.viewmodel.drivebase.DrivebaseChassisPreset
import com.ares.analytics.viewmodel.drivebase.applyChassisPreset
import com.ares.analytics.viewmodel.drivebase.availableChassisPresets
import com.ares.analytics.viewmodel.superstructure.SuperstructureCoordinationPreset
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioState
import com.ares.analytics.viewmodel.superstructure.applySuperstructureCoordinationPreset
import com.areslib.subsystem.*
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureStatePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Wave4DrivetrainControlAndPitUsabilityTest {

    @Test
    fun `availableChassisPresets returns league-appropriate COTS drivetrain presets`() {
        val ftcPresets = availableChassisPresets(League.FTC)
        assertEquals(
            listOf(
                DrivebaseChassisPreset.GOBILDA_STRAFER_MECANUM,
                DrivebaseChassisPreset.REV_STARTER_BOT_DIFFERENTIAL,
            ),
            ftcPresets,
        )

        val frcPresets = availableChassisPresets(League.FRC)
        assertEquals(
            listOf(
                DrivebaseChassisPreset.SDS_MK4I_L2_SWERVE,
                DrivebaseChassisPreset.WEST_COAST_DRIVE_DIFFERENTIAL,
            ),
            frcPresets,
        )

        val xrpPresets = availableChassisPresets(League.XRP)
        assertEquals(
            listOf(DrivebaseChassisPreset.SPARKFUN_STANDARD_XRP),
            xrpPresets,
        )
    }

    @Test
    fun `applyChassisPreset configures goBILDA Strafer Mecanum and SDS MK4i L2 Swerve geometry`() {
        val initialFtc = DrivebaseBuilderState(
            projectPath = "test-ftc-project",
            projectId = "test-ftc-robot",
            league = League.FTC,
        )
        val straferDoc = applyChassisPreset(initialFtc, DrivebaseChassisPreset.GOBILDA_STRAFER_MECANUM)
        assertEquals(DrivebaseKind.FTC_MECANUM, straferDoc.kind)
        assertEquals(0.052, straferDoc.geometry.wheelRadiusMeters, 1e-6)
        assertEquals(0.36, straferDoc.geometry.trackWidthMeters, 1e-6)
        assertEquals(0.34, straferDoc.geometry.wheelBaseMeters, 1e-6)

        val initialFrc = DrivebaseBuilderState(
            projectPath = "test-frc-project",
            projectId = "test-frc-robot",
            league = League.FRC,
        )
        val mk4iDoc = applyChassisPreset(initialFrc, DrivebaseChassisPreset.SDS_MK4I_L2_SWERVE)
        assertEquals(DrivebaseKind.FRC_CTRE_SWERVE, mk4iDoc.kind)
        assertEquals(0.0508, mk4iDoc.geometry.wheelRadiusMeters, 1e-6)
        assertEquals(0.58, mk4iDoc.geometry.trackWidthMeters, 1e-6)
        assertEquals(0.58, mk4iDoc.geometry.wheelBaseMeters, 1e-6)
    }

    @Test
    fun `applySubsystemControlRecipe populates beginner-friendly control law and feedforward`() {
        val baseDoc = SubsystemTemplates.create(
            template = SubsystemTemplate.entries.first { it.name.contains("ARM") },
            documentId = "arm",
            kotlinTypeName = "Arm",
            platform = SubsystemPlatform.FTC,
        )

        val withArmRecipe = applySubsystemControlRecipe(
            document = baseDoc,
            recipe = SubsystemStudentControlRecipe.HOLD_ANGLE_GRAVITY,
        )
        val armLoop = withArmRecipe.controlLoops.first()
        assertEquals(SubsystemControlStrategy.PROFILED_POSITION_PID, armLoop.strategy)
        assertEquals(SubsystemFeedforwardKind.ARM, armLoop.feedforward.kind)
        assertTrue(armLoop.kP > 0.0)
        assertTrue(armLoop.feedforward.kG > 0.0)
        assertEquals("rad", withArmRecipe.stateFields.first { it.fieldId == armLoop.targetFieldId }.unit)

        val withFlywheelRecipe = applySubsystemControlRecipe(
            document = baseDoc,
            recipe = SubsystemStudentControlRecipe.SPIN_VELOCITY_FLYWHEEL,
        )
        val flywheelLoop = withFlywheelRecipe.controlLoops.first()
        assertEquals(SubsystemControlStrategy.VELOCITY_PID, flywheelLoop.strategy)
        assertEquals(SubsystemFeedforwardKind.SIMPLE_MOTOR, flywheelLoop.feedforward.kind)
        assertEquals("rad/s", withFlywheelRecipe.stateFields.first { it.fieldId == flywheelLoop.targetFieldId }.unit)
    }

    @Test
    fun `applySuperstructureCoordinationPreset wires stow and intake handoff states and transitions`() {
        val initialDoc = SuperstructureDocument(
            superstructureId = "robot_coordinator",
            displayName = "Robot Coordinator",
            initialStateId = "idle",
            faultStateId = "idle",
            states = listOf(
                SuperstructureStatePreset(
                    stateId = "idle",
                    displayName = "Idle",
                ),
            ),
        )
        val studioState = SuperstructureStudioState(
            projectPath = "test-project",
            draft = initialDoc,
        )

        val stowedDoc = applySuperstructureCoordinationPreset(
            state = studioState,
            document = initialDoc,
            preset = SuperstructureCoordinationPreset.STOW_ALL_SAFELY,
        )
        assertTrue(stowedDoc.states.any { it.stateId == "stow" })

        val handoffDoc = applySuperstructureCoordinationPreset(
            state = studioState.copy(draft = stowedDoc),
            document = stowedDoc,
            preset = SuperstructureCoordinationPreset.INTAKE_HAND_OFF,
        )
        assertTrue(handoffDoc.states.any { it.stateId == "intake" })
        assertTrue(handoffDoc.states.any { it.stateId == "handoff" })
        assertTrue(handoffDoc.transitions.any { it.sourceStateId == "intake" && it.targetStateId == "handoff" })
    }

    @Test
    fun `StudentPitCheckpointPreset provides non-blank messages and projectArea categorizes paths`() {
        assertEquals(4, StudentPitCheckpointPreset.entries.size)
        StudentPitCheckpointPreset.entries.forEach { preset ->
            assertFalse(preset.label.isBlank())
            assertFalse(preset.commitMessage.isBlank())
            assertFalse(preset.helperHint.isBlank())
        }
        assertEquals("Drivebase", projectArea(".ares/drivetrains/default.json"))
        assertEquals("Subsystems", projectArea(".ares/subsystems/intake.json"))
        assertEquals("Autonomous routines", projectArea(".ares/routines/center_auto.json"))
    }
}
