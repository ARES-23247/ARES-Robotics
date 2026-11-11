package com.ares.analytics.ui.components.robotstudio

import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.project.ProjectIdentityDraft
import com.ares.analytics.viewmodel.project.ProjectIdentityEditorState
import com.areslib.drivetrain.ftcMecanumDrivetrain
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotFootprintAndSpecTest {

    @Test
    fun `FTC footprint summary validates 18 inch sizing box compliance`() {
        val compliant = computeRobotFootprintSummary(
            league = League.FTC,
            outerLengthMeters = 0.4318, // 17.0 in
            outerWidthMeters = 0.4572,  // 18.0 in
        )
        assertTrue(compliant.hasValidDimensions)
        assertTrue(compliant.fitsLeagueSizingRule)
        assertTrue(compliant.badgeLabel.contains("FITS 18\" × 18\""))
        assertEquals(17.0, compliant.outerLengthInches, 0.05)
        assertEquals(18.0, compliant.outerWidthInches, 0.05)

        val oversized = computeRobotFootprintSummary(
            league = League.FTC,
            outerLengthMeters = 0.50, // ~19.7 in
            outerWidthMeters = 0.4572,
        )
        assertTrue(oversized.hasValidDimensions)
        assertFalse(oversized.fitsLeagueSizingRule)
        assertTrue(oversized.badgeLabel.contains("EXCEEDS"))
        assertTrue(oversized.clearanceDetail.contains("Over limit"))
    }

    @Test
    fun `FRC footprint summary computes frame perimeter and bumper allowance`() {
        // Outer 0.85m x 0.85m (~33.46" x 33.46"), minus 2 * 3.25" bumpers = ~26.96" x 26.96" frame (~107.9" perimeter <= 120")
        val frcCompliant = computeRobotFootprintSummary(
            league = League.FRC,
            outerLengthMeters = 0.85,
            outerWidthMeters = 0.85,
        )
        assertTrue(frcCompliant.hasValidDimensions)
        assertTrue(frcCompliant.fitsLeagueSizingRule)
        assertEquals(3.25, frcCompliant.bumperThicknessInches, 0.05)
        assertTrue(frcCompliant.framePerimeterInches < 120.0)
        assertTrue(frcCompliant.badgeLabel.contains("≤ 120\""))

        // Outer 1.10m x 1.10m (~43.3" x 43.3") -> frame ~36.8" x 36.8" -> 147.2" perimeter > 120"
        val frcOversized = computeRobotFootprintSummary(
            league = League.FRC,
            outerLengthMeters = 1.10,
            outerWidthMeters = 1.10,
        )
        assertFalse(frcOversized.fitsLeagueSizingRule)
        assertTrue(frcOversized.badgeLabel.contains("> 120\""))
    }

    @Test
    fun `invalid footprint dimensions report awaiting positive decimals`() {
        val invalid = computeRobotFootprintSummary(
            league = League.FTC,
            outerLengthMeters = -0.2,
            outerWidthMeters = 0.0,
        )
        assertFalse(invalid.hasValidDimensions)
        assertFalse(invalid.fitsLeagueSizingRule)
        assertEquals("ENTER VALID DIMENSIONS", invalid.badgeLabel)
    }

    @Test
    fun `whole robot spec sections and Kotlin DSL render complete project summary`() {
        val identityState = ProjectIdentityEditorState(
            loading = false,
            workspaceLeague = League.FTC,
            draft = ProjectIdentityDraft(
                projectId = "alpha-2026",
                teamId = "12345",
                seasonId = "2026",
                robotId = "alpha",
                displayName = "Alpha Bot",
                robotLengthMeters = "0.44",
                robotWidthMeters = "0.42",
                authoringModel = AresProjectAuthoringModel.HYBRID,
            ),
        )
        val drive = ftcMecanumDrivetrain("main-drive", "Main Mecanum")
        val sub = SubsystemTemplates.create(
            SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
            "intake",
            "Intake",
            SubsystemPlatform.FTC,
        )

        val snapshot = buildRobotProjectSpecSnapshot(
            identityState = identityState,
            drivetrain = drive,
            subsystems = listOf(sub),
            hardwareCount = 5,
            hardwareReviewStatus = "CURRENT",
        )

        val sections = generateRobotProjectSpecSections(snapshot)
        assertEquals(5, sections.size)
        assertTrue(sections[0].rows.any { it.primaryLabel == "Alpha Bot" })
        assertEquals(1, sections[1].rows.size)
        assertEquals(1, sections[2].rows.size)

        val dsl = renderRobotProjectKotlinDsl(snapshot)
        assertTrue(dsl.contains("aresProject(\"alpha-2026\", AresLeague.FTC)"))
        assertTrue(dsl.contains("identity(teamId = \"12345\", seasonId = \"2026\", robotId = \"alpha\", displayName = \"Alpha Bot\")"))
        assertTrue(dsl.contains("geometry(robotLengthMeters = 0.44, robotWidthMeters = 0.42)"))
        assertTrue(dsl.contains("ftcMecanumDrivetrain("))
        assertTrue(dsl.contains("subsystem("))
    }
}
