package com.ares.analytics.ui.help

import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.academy.StudentCookbookCategory
import com.ares.analytics.ui.components.academy.studentCookbookEntries
import com.ares.analytics.ui.components.tuning.StudentTuningSymptom
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.routine.StudentAutonomousRecipe
import com.ares.analytics.viewmodel.routine.buildStudentStarterRoutineDraft
import com.ares.analytics.viewmodel.subsystem.SubsystemCookbookPreset
import com.ares.analytics.viewmodel.subsystem.availableCookbookPresets
import com.ares.analytics.viewmodel.subsystem.createCookbookPresetDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Wave3StudentUsabilityAndPresetsTest {

    @Test
    fun `student autonomous starter recipes build valid routine drafts`() {
        val dimensions = RobotDimensions(lengthMeters = 0.45, widthMeters = 0.45)

        val (leaveLineDoc, leaveLineEntry) = buildStudentStarterRoutineDraft(
            recipe = StudentAutonomousRecipe.LEAVE_STARTING_LINE,
            league = League.FTC,
            dimensions = dimensions,
            firstActionKey = null,
        )
        assertEquals(leaveLineDoc.documentId, leaveLineEntry.routineId)
        assertEquals(1, leaveLineDoc.steps.size)

        val (driveAndScoreDoc, _) = buildStudentStarterRoutineDraft(
            recipe = StudentAutonomousRecipe.DRIVE_AND_TRIGGER_ACTION,
            league = League.FTC,
            dimensions = dimensions,
            firstActionKey = "subsystem.intake.set.targetVoltage",
        )
        assertEquals(2, driveAndScoreDoc.steps.size)

        val (outAndBackDoc, _) = buildStudentStarterRoutineDraft(
            recipe = StudentAutonomousRecipe.OUT_AND_BACK,
            league = League.FTC,
            dimensions = dimensions,
            firstActionKey = null,
        )
        assertEquals(3, outAndBackDoc.steps.size)
    }

    @Test
    fun `student tuning symptom helper provides plain-English diagnosis and gain targets`() {
        assertEquals(4, StudentTuningSymptom.entries.size)
        StudentTuningSymptom.entries.forEach { symptom ->
            assertTrue(symptom.symptomLabel.isNotBlank())
            assertTrue(symptom.primaryGain.isNotBlank())
            assertTrue(symptom.explanation.isNotBlank())
            assertTrue(symptom.safeStepAdvice.isNotBlank())
        }
    }

    @Test
    fun `subsystem cookbook presets filter by platform and create valid documents`() {
        val ftcPresets = availableCookbookPresets(SubsystemPlatform.FTC)
        assertTrue(ftcPresets.contains(SubsystemCookbookPreset.FTC_ROLLER_INTAKE))
        assertTrue(ftcPresets.contains(SubsystemCookbookPreset.FTC_SCORING_ARM))

        val frcPresets = availableCookbookPresets(SubsystemPlatform.FRC)
        assertEquals(listOf(SubsystemCookbookPreset.FRC_FLYWHEEL_SHOOTER), frcPresets)

        val xrpPresets = availableCookbookPresets(SubsystemPlatform.XRP)
        assertEquals(listOf(SubsystemCookbookPreset.XRP_ULTRASONIC_RANGER), xrpPresets)

        val firstArmDoc = createCookbookPresetDocument(
            preset = SubsystemCookbookPreset.FTC_SCORING_ARM,
            platform = SubsystemPlatform.FTC,
            existingDocuments = emptyList(),
        )
        assertEquals("scoring-arm", firstArmDoc.documentId)
        assertEquals(SubsystemTemplate.ARM_PIVOT, firstArmDoc.template)
        assertEquals(SubsystemPlatform.FTC, firstArmDoc.platform)
        assertTrue(firstArmDoc.description.contains("115°"))

        val secondArmDoc = createCookbookPresetDocument(
            preset = SubsystemCookbookPreset.FTC_SCORING_ARM,
            platform = SubsystemPlatform.FTC,
            existingDocuments = listOf(firstArmDoc),
        )
        assertEquals("scoring-arm-2", secondArmDoc.documentId)
    }

    @Test
    fun `academy student cookbook entries cover quick-start ftc frc xrp and code bridge`() {
        val entries = studentCookbookEntries()
        assertTrue(entries.size >= 5)
        assertTrue(entries.any { it.category == StudentCookbookCategory.QUICK_START && it.launchesSimulator })
        assertTrue(entries.any { it.category == StudentCookbookCategory.FTC })
        assertTrue(entries.any { it.category == StudentCookbookCategory.FRC })
        assertTrue(entries.any { it.category == StudentCookbookCategory.XRP })
        assertTrue(entries.any { it.category == StudentCookbookCategory.CODE_BRIDGE })
        entries.forEach { entry ->
            assertTrue(entry.codeSnippet.isNotBlank())
            assertTrue(entry.steps.isNotEmpty())
        }
    }
}
