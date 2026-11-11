package com.ares.analytics.ui.screens

import com.ares.analytics.service.verification.RobotVerificationReport
import com.ares.analytics.service.verification.VerificationEvidenceLevel
import com.ares.analytics.service.verification.VerificationLayer
import com.ares.analytics.service.verification.VerificationReportItem
import com.ares.analytics.service.verification.VerificationResultStatus
import com.ares.analytics.service.verification.VerificationRunProvenance
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.robotstudio.RobotStudioSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RobotVerificationReportScreenTest {

    private val sampleItems = listOf(
        VerificationReportItem(
            id = "project.identity.configuration",
            layer = VerificationLayer.CONFIGURATION,
            title = "Project Identity",
            explanation = "Project metadata is valid.",
            status = VerificationResultStatus.PASSED,
            evidenceLevel = VerificationEvidenceLevel.CONFIGURATION_REVIEWED,
            source = ".ares/project.json",
        ),
        VerificationReportItem(
            id = "drivetrain.configuration",
            layer = VerificationLayer.CONFIGURATION,
            title = "Drivetrain Configuration",
            explanation = "Drivetrain wheel radius must be positive.",
            status = VerificationResultStatus.FAILED,
            evidenceLevel = VerificationEvidenceLevel.CONFIGURATION_REVIEWED,
            source = ".ares/drivetrains/swerve_drive.aresdrive",
        ),
        VerificationReportItem(
            id = "subsystem.intake_pivot.configuration",
            layer = VerificationLayer.CONFIGURATION,
            title = "Intake Pivot Configuration",
            explanation = "Subsystem schema valid.",
            status = VerificationResultStatus.PASSED,
            evidenceLevel = VerificationEvidenceLevel.CONFIGURATION_REVIEWED,
            source = ".ares/subsystems/intake_pivot.aressubsystem",
        ),
        VerificationReportItem(
            id = "superstructure.scoring_coordinator.configuration",
            layer = VerificationLayer.CONFIGURATION,
            title = "Scoring Coordinator Configuration",
            explanation = "Blocked by invalid intake pivot target.",
            status = VerificationResultStatus.BLOCKED,
            evidenceLevel = VerificationEvidenceLevel.CONFIGURATION_REVIEWED,
            source = ".ares/superstructures/scoring_coordinator.aressuperstructure",
        ),
        VerificationReportItem(
            id = "autonomous.configuration",
            layer = VerificationLayer.CONFIGURATION,
            title = "Autonomous Catalog",
            explanation = "Not yet measured in simulation.",
            status = VerificationResultStatus.NOT_RUN,
            evidenceLevel = VerificationEvidenceLevel.CONFIGURATION_REVIEWED,
            source = ".ares/routines/autonomous-catalog.json",
        ),
        VerificationReportItem(
            id = "project.physical-readiness",
            layer = VerificationLayer.PHYSICAL_VALIDATION,
            title = "Supervised Physical Validation",
            explanation = "Check motor directions on blocks before enabling on field.",
            status = VerificationResultStatus.NOT_RUN,
            evidenceLevel = VerificationEvidenceLevel.PHYSICAL_VALIDATION_REQUIRED,
            source = ".ares/hardware-review.json",
        ),
    )

    @Test
    fun `filterVerificationItems filters by status and search query`() {
        val all = filterVerificationItems(sampleItems, VerificationStatusFilter.ALL, "")
        assertEquals(6, all.size)

        val failedOnly = filterVerificationItems(sampleItems, VerificationStatusFilter.FAILED, "")
        assertEquals(listOf("drivetrain.configuration"), failedOnly.map { it.id })

        val blockedOnly = filterVerificationItems(sampleItems, VerificationStatusFilter.BLOCKED, "")
        assertEquals(listOf("superstructure.scoring_coordinator.configuration"), blockedOnly.map { it.id })

        val notRunOnly = filterVerificationItems(sampleItems, VerificationStatusFilter.NOT_RUN, "")
        assertEquals(2, notRunOnly.size)

        val searchPivot = filterVerificationItems(sampleItems, VerificationStatusFilter.ALL, "intake_pivot")
        assertEquals(listOf("subsystem.intake_pivot.configuration"), searchPivot.map { it.id })

        val combinedFilter = filterVerificationItems(sampleItems, VerificationStatusFilter.PASSED, "metadata")
        assertEquals(listOf("project.identity.configuration"), combinedFilter.map { it.id })
    }

    @Test
    fun `resolveVerificationQuickJump maps items to corresponding studio stages`() {
        assertEquals(
            VerificationQuickJumpTarget("Open Project Identity", RobotStudioSelection.Identity),
            resolveVerificationQuickJump(sampleItems[0]),
        )
        assertEquals(
            VerificationQuickJumpTarget("Open Drivetrain", RobotStudioSelection.Drivetrain),
            resolveVerificationQuickJump(sampleItems[1]),
        )
        assertEquals(
            VerificationQuickJumpTarget("Open Subsystem", RobotStudioSelection.Subsystem("intake_pivot")),
            resolveVerificationQuickJump(sampleItems[2]),
        )
        assertEquals(
            VerificationQuickJumpTarget("Open Superstructure", RobotStudioSelection.Superstructure),
            resolveVerificationQuickJump(sampleItems[3]),
        )
        assertEquals(
            VerificationQuickJumpTarget("Open Autonomous", RobotStudioSelection.Autonomous),
            resolveVerificationQuickJump(sampleItems[4]),
        )
        assertEquals(
            VerificationQuickJumpTarget("Open Port Map & Review", RobotStudioSelection.PortMap),
            resolveVerificationQuickJump(sampleItems[5]),
        )
    }

    @Test
    fun `formatVerificationReportMarkdown produces structured markdown table`() {
        val report = RobotVerificationReport(
            projectPath = "/tmp/test-robot",
            league = League.FRC,
            provenance = VerificationRunProvenance(
                runId = "run-1234",
                canonicalContentHash = "sha256-abcdef",
                aresVersion = "2.2.8",
                generatorVersion = "2.2.8",
                studioVersion = "2.2.8",
                command = listOf("./gradlew", "test"),
                startedAt = "2026-10-15T10:00:00Z",
                finishedAt = "2026-10-15T10:00:05Z",
                buildExitCode = 1,
            ),
            items = sampleItems,
        )

        val markdown = formatVerificationReportMarkdown(report)
        assertTrue(markdown.contains("# ARES Robot Verification Report"))
        assertTrue(markdown.contains("**League**: FRC"))
        assertTrue(markdown.contains("2 passed · 1 failed · 1 blocked · 2 not measured"))
        assertTrue(markdown.contains("| Status | Layer | Check | Evidence Level | Explanation |"))
        assertTrue(markdown.contains("Drivetrain Configuration"))
        assertTrue(markdown.contains("Intake Pivot Configuration"))
    }
}
