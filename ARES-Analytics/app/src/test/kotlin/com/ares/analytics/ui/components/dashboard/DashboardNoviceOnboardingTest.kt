package com.ares.analytics.ui.components.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardNoviceOnboardingTest {
    @Test
    fun `describeDashboardOfflineNextStep adapts primary CTA for live-robot vs local-sim states`() {
        val defaultLiveState = describeDashboardOfflineNextStep(
            isLocalSimulatorSelected = false,
            canLaunchSimulator = true,
            isLaunchPreparationRunning = false,
        )
        assertEquals("Practice in Local Simulator", defaultLiveState.primarySimActionLabel)
        assertTrue(defaultLiveState.subtitle.contains("Local Simulator"))

        val localSimReadyState = describeDashboardOfflineNextStep(
            isLocalSimulatorSelected = true,
            canLaunchSimulator = true,
            isLaunchPreparationRunning = false,
        )
        assertEquals("Launch Local Simulator", localSimReadyState.primarySimActionLabel)

        val buildingState = describeDashboardOfflineNextStep(
            isLocalSimulatorSelected = true,
            canLaunchSimulator = false,
            isLaunchPreparationRunning = true,
        )
        assertEquals("Building Simulator…", buildingState.primarySimActionLabel)
    }

    @Test
    fun `computeSimulatorReadinessSteps progresses through all three novice readiness stages`() {
        val offlineSteps = computeSimulatorReadinessSteps(
            isConnected = false,
            isSimulatorProcessRunning = false,
            isLaunchPreparationRunning = false,
            isStarting = false,
            isRunning = false,
            isAutonomousRunning = false,
            isKeyboardDriveArmed = false,
            receiverReady = false,
        )
        assertEquals(3, offlineSteps.size)
        assertEquals(SimulatorStepStatus.ACTIVE, offlineSteps[0].status)
        assertEquals(SimulatorStepStatus.PENDING, offlineSteps[1].status)
        assertEquals(SimulatorStepStatus.PENDING, offlineSteps[2].status)

        val connectedSteps = computeSimulatorReadinessSteps(
            isConnected = true,
            isSimulatorProcessRunning = true,
            isLaunchPreparationRunning = false,
            isStarting = false,
            isRunning = false,
            isAutonomousRunning = false,
            isKeyboardDriveArmed = false,
            receiverReady = false,
        )
        assertEquals(SimulatorStepStatus.COMPLETE, connectedSteps[0].status)
        assertEquals(SimulatorStepStatus.ACTIVE, connectedSteps[1].status)
        assertEquals("Click Start driving", connectedSteps[1].actionHint)
        assertEquals(SimulatorStepStatus.PENDING, connectedSteps[2].status)

        val teleopDisarmedSteps = computeSimulatorReadinessSteps(
            isConnected = true,
            isSimulatorProcessRunning = true,
            isLaunchPreparationRunning = false,
            isStarting = false,
            isRunning = true,
            isAutonomousRunning = false,
            isKeyboardDriveArmed = false,
            receiverReady = true,
        )
        assertEquals(SimulatorStepStatus.COMPLETE, teleopDisarmedSteps[0].status)
        assertEquals(SimulatorStepStatus.COMPLETE, teleopDisarmedSteps[1].status)
        assertEquals(SimulatorStepStatus.ACTIVE, teleopDisarmedSteps[2].status)
        assertEquals("Click Arm control", teleopDisarmedSteps[2].actionHint)

        val armedSteps = computeSimulatorReadinessSteps(
            isConnected = true,
            isSimulatorProcessRunning = true,
            isLaunchPreparationRunning = false,
            isStarting = false,
            isRunning = true,
            isAutonomousRunning = false,
            isKeyboardDriveArmed = true,
            receiverReady = true,
        )
        assertTrue(armedSteps.all { it.status == SimulatorStepStatus.COMPLETE })
        assertEquals("WASD & Arrows ready", armedSteps[2].actionHint)
    }

    @Test
    fun `formatLocalSimulatorStatusHint explicitly warns when TeleOp is running but keyboard control is disarmed`() {
        val disarmedHint = formatLocalSimulatorStatusHint(
            isConnected = true,
            isFtc = true,
            isRunning = true,
            isKeyboardDriveArmed = false,
            receiverReady = true,
            selectedOpMode = "org.firstinspires.ftc.teamcode.ARESStarterTeleOp",
        )
        assertTrue(disarmedHint.contains("DISARMED"))
        assertTrue(disarmedHint.contains("Arm control"))

        val armedHint = formatLocalSimulatorStatusHint(
            isConnected = true,
            isFtc = true,
            isRunning = true,
            isKeyboardDriveArmed = true,
            receiverReady = true,
            selectedOpMode = "org.firstinspires.ftc.teamcode.ARESStarterTeleOp",
        )
        assertTrue(armedHint.contains("ARESStarterTeleOp armed"))
        assertTrue(armedHint.contains("A/D strafe"))
    }
}
