package com.ares.analytics.ui.components.robotstudio

import com.ares.analytics.viewmodel.robotstudio.RobotStudioAction
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStage
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageId
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageStatus
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState
import kotlin.test.Test
import kotlin.test.assertEquals

class RobotStudioPresentationTest {
    @Test
    fun `loading and inspection failures never claim readiness`() {
        assertEquals("Checking", RobotStudioState().progressPresentation().label)
        assertEquals(
            "CHECKING",
            RobotStudioState().validationPresentation(RobotStudioSelection.Identity).status.label,
        )

        val failed = RobotStudioState(loading = false, error = "Could not inspect project")
        assertEquals("Unavailable", failed.progressPresentation().label)
        assertEquals(
            "UNAVAILABLE",
            failed.validationPresentation(RobotStudioSelection.Identity).status.label,
        )
    }

    @Test
    fun `robot structure badge ignores downstream build and simulation stages`() {
        val state = RobotStudioState(
            loading = false,
            stages = listOf(
                stage(RobotStudioStageId.PROJECT_IDENTITY, RobotStudioStageStatus.READY),
                stage(RobotStudioStageId.HARDWARE, RobotStudioStageStatus.READY),
                stage(RobotStudioStageId.COORDINATION, RobotStudioStageStatus.OPTIONAL),
                stage(RobotStudioStageId.AUTONOMOUS, RobotStudioStageStatus.READY),
                stage(RobotStudioStageId.CONTROLS, RobotStudioStageStatus.READY),
                stage(RobotStudioStageId.GENERATE_VERIFY, RobotStudioStageStatus.INVALID),
                stage(RobotStudioStageId.SIMULATE, RobotStudioStageStatus.BLOCKED),
            ),
        )

        assertEquals("Ready", state.progressPresentation().label)
    }

    @Test
    fun `an empty issue list does not turn a blocked stage into pass`() {
        val state = RobotStudioState(
            loading = false,
            stages = listOf(stage(RobotStudioStageId.HARDWARE, RobotStudioStageStatus.BLOCKED)),
        )

        val validation = state.validationPresentation(RobotStudioSelection.PortMap)

        assertEquals("BLOCKED", validation.status.label)
        assertEquals(RobotStudioPresentationTone.ERROR, validation.status.tone)
        assertEquals("Blocked for test", validation.explanation)
    }

    @Test
    fun `selection maps to its canonical readiness stage`() {
        val controls = stage(RobotStudioStageId.CONTROLS, RobotStudioStageStatus.NEEDS_ACTION)
        val state = RobotStudioState(loading = false, stages = listOf(controls))

        assertEquals(controls, state.stageFor(RobotStudioSelection.Controls))
        assertEquals(null, state.stageFor(RobotStudioSelection.Identity))
        assertEquals("NOT CHECKED", state.validationPresentation(RobotStudioSelection.Identity).status.label)
    }

    @Test
    fun `pane breakpoints preserve center-canvas space`() {
        assertEquals(
            RobotStudioPanePresentation(collapseTree = false, collapseInspector = false),
            robotStudioPanePresentation(1_820f, largeText = false),
        )
        assertEquals(
            RobotStudioPanePresentation(collapseTree = false, collapseInspector = true),
            robotStudioPanePresentation(1_440f, largeText = false),
        )
        assertEquals(
            RobotStudioPanePresentation(collapseTree = true, collapseInspector = true),
            robotStudioPanePresentation(1_190f, largeText = false),
        )
        assertEquals(
            RobotStudioPanePresentation(collapseTree = true, collapseInspector = true),
            robotStudioPanePresentation(1_300f, largeText = true),
        )
    }

    @Test
    fun `readiness refresh fingerprint advances only after persisted edits settle`() {
        assertEquals(null, robotStudioPersistedRevision(loading = true, hasUnsavedChanges = false, listOf("a")))
        assertEquals(null, robotStudioPersistedRevision(loading = false, hasUnsavedChanges = true, listOf("a")))

        val before = robotStudioPersistedRevision(false, false, listOf("identity-a", 1, "controls-a"))
        val after = robotStudioPersistedRevision(false, false, listOf("identity-a", 2, "controls-a"))

        assertEquals("identity-a|1|controls-a", before)
        assertEquals("identity-a|2|controls-a", after)
    }

    private fun stage(id: RobotStudioStageId, status: RobotStudioStageStatus) = RobotStudioStage(
        id = id,
        title = id.name,
        outcome = "Test outcome",
        status = status,
        explanation = "${status.label} for test",
        issues = emptyList(),
        storage = ".ares/test.json",
        consumer = "test",
        action = RobotStudioAction.OPEN_PROJECT_IDENTITY,
        actionLabel = "Open",
    )
}
