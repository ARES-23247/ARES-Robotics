package com.ares.analytics.ui.help

import com.ares.analytics.service.dashboard.DashboardWidgetType
import com.ares.analytics.ui.components.dashboard.DashboardWidgetRegistry
import com.ares.analytics.ui.components.dashboard.extractSwerveModuleVectors
import com.ares.analytics.ui.components.subsystems.SubsystemAuthoringComplexityMode
import com.ares.analytics.ui.components.subsystems.buildGuiToCodeBridgeSummary
import com.ares.analytics.ui.components.subsystems.stagePlainEnglishSummary
import com.ares.analytics.viewmodel.SubsystemBuilderStage
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentAuthoringAndDiagnosticsTest {

    @Test
    fun `translateDiagnosticForStudent maps safety disarm, port assignment, opmode, sensor feedback, and build checks`() {
        val disarm = translateDiagnosticForStudent(
            "Controls are currently DISARMED (clicking outside or opening a drawer disarms for safety). Click 'Arm control' above to drive."
        )
        assertEquals("Safety Disarm (Outputs Neutral)", disarm.categoryTitle)
        assertTrue(disarm.howToFixIt.contains("Arm control"))

        val port = translateDiagnosticForStudent("Unassigned hardware port on Expansion Hub motor 0")
        assertEquals("Hardware Port Not Assigned", port.categoryTitle)
        assertTrue(port.howToFixIt.contains("Hardware & IO"))

        val opMode = translateDiagnosticForStudent("Generated autonomous was blocked and outputs were neutralized.")
        assertEquals("Robot Mode Needs Attention", opMode.categoryTitle)
        assertTrue(opMode.howToFixIt.contains("Start driving") || opMode.howToFixIt.contains("Stop"))

        val sensor = translateDiagnosticForStudent("Encoder feedback is NaN or not homed yet")
        assertEquals("Sensor Feedback Safety Check", sensor.categoryTitle)
        assertTrue(sensor.howToFixIt.contains("sensor") || sensor.howToFixIt.contains("Open-Loop"))

        val build = translateDiagnosticForStudent("Verify & build the current robot project before launching its simulator.")
        assertEquals("Project Verification Required", build.categoryTitle)
        assertTrue(build.howToFixIt.contains("Verify & launch"))
    }

    @Test
    fun `stagePlainEnglishSummary tailors guidance for Student Essentials vs Full Engineering mode`() {
        val studentPurpose = stagePlainEnglishSummary(
            SubsystemBuilderStage.PURPOSE,
            SubsystemAuthoringComplexityMode.STUDENT_ESSENTIALS,
        )
        val engPurpose = stagePlainEnglishSummary(
            SubsystemBuilderStage.PURPOSE,
            SubsystemAuthoringComplexityMode.FULL_ENGINEERING,
        )
        assertTrue(studentPurpose.contains("Step 1 (Identity & Template)"))
        assertTrue(engPurpose.contains("GENERATED vs. USER-OWNED"))

        val studentReview = stagePlainEnglishSummary(
            SubsystemBuilderStage.REVIEW,
            SubsystemAuthoringComplexityMode.STUDENT_ESSENTIALS,
        )
        assertTrue(studentReview.contains("GUI ↔ Code Bridge"))
    }

    @Test
    fun `buildGuiToCodeBridgeSummary distinguishes read-only generated files from safe team extensions across platforms`() {
        val ftcIntake = SubsystemTemplates.create(
            SubsystemTemplate.SIMPLE_ACTUATOR,
            "intake",
            "Intake",
            SubsystemPlatform.FTC,
        )
        val ftcBridge = buildGuiToCodeBridgeSummary(ftcIntake)
        assertEquals(".ares/subsystems/intake.aressubsystem", ftcBridge.descriptorFilePath)
        assertTrue(ftcBridge.generatedReadOnlyFolder.contains("build/generated/ares/"))
        assertTrue(ftcBridge.safeTeamExtensionPath.contains("Extensions.kt"))
        assertTrue(ftcBridge.exampleActionExpression.startsWith("subsystem.intake.set."))
        assertTrue(ftcBridge.exampleExtensionSnippet.contains("never overwrites this file"))

        val xrpSensor = SubsystemTemplates.create(
            SubsystemTemplate.DISTANCE_SENSOR,
            "range",
            "Range",
            SubsystemPlatform.XRP,
        )
        val xrpBridge = buildGuiToCodeBridgeSummary(xrpSensor)
        assertEquals(".ares/subsystems/range.aressubsystem", xrpBridge.descriptorFilePath)
        assertTrue(xrpBridge.generatedReadOnlyFolder.contains("build/generated/ares/python/"))
        assertTrue(xrpBridge.safeTeamExtensionPath.contains("range_extensions.py"))
    }

    @Test
    fun `swerve chassis vector extraction supports both Swerve Module and Drive Swerve topic namespaces`() {
        val legacyTopics = mapOf(
            "Swerve/Module0/AngleDeg" to 45.0,
            "Swerve/Module0/SpeedMps" to 2.5,
            "Swerve/Module3/AngleDeg" to -30.0,
            "Swerve/Module3/SpeedMps" to 1.25,
        )
        val legacyModules = extractSwerveModuleVectors(legacyTopics)
        assertEquals(4, legacyModules.size)
        assertEquals(45.0, legacyModules[0].angleDeg, 1e-6)
        assertEquals(2.5, legacyModules[0].speedMps, 1e-6)
        assertEquals(-30.0, legacyModules[3].angleDeg, 1e-6)
        assertEquals(1.25, legacyModules[3].speedMps, 1e-6)

        val canonicalTopics = mapOf(
            "Drive/Swerve/FL/Angle" to 90.0,
            "Drive/Swerve/FL/Speed" to 3.0,
            "Drive/Swerve/BR/Angle" to 180.0,
            "Drive/Swerve/BR/Speed" to 4.0,
        )
        val canonicalModules = extractSwerveModuleVectors(canonicalTopics)
        assertEquals(90.0, canonicalModules[0].angleDeg, 1e-6)
        assertEquals(3.0, canonicalModules[0].speedMps, 1e-6)
        assertEquals(180.0, canonicalModules[3].angleDeg, 1e-6)
        assertEquals(4.0, canonicalModules[3].speedMps, 1e-6)
    }

    @Test
    fun `dashboard widget registry includes newly activated data_table and video_sync replay widgets`() {
        assertNotNull(DashboardWidgetRegistry.find(DashboardWidgetType.of("data_table")))
        assertNotNull(DashboardWidgetRegistry.find(DashboardWidgetType.of("video_sync")))
        assertNotNull(DashboardWidgetRegistry.find(DashboardWidgetType.of("swerve_animator")))
    }
}
