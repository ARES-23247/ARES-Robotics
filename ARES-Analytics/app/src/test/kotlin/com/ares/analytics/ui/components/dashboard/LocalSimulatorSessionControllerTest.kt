package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.RobotTopicContract
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalSimulatorSessionControllerTest {
    @Test
    fun `applyTelemetryFrame decodes FTC OpMode list and selects preferred TeleOp`() {
        val controller = LocalSimulatorSessionController(league = League.FTC)
        assertEquals(null, controller.selectedOpMode)

        controller.applyTelemetryFrame(
            TelemetryFrame(
                timestampMs = 1_000L,
                sessionId = "sim-test",
                key = "/$TELEOP_LIST_TOPIC",
                value = 0.0,
                stringValue = """["org.firstinspires.ftc.teamcode.AresHardwareTestOpMode","org.firstinspires.ftc.teamcode.ARESStarterTeleOp"]""",
            ),
        )

        assertEquals(2, controller.teleOps.size)
        assertEquals("org.firstinspires.ftc.teamcode.ARESStarterTeleOp", controller.selectedOpMode)
        assertFalse(controller.isFtcAutonomousSelection)
    }

    @Test
    fun `FTC running state and status text update when active OpMode acknowledges TELEOP_RUNNING`() {
        val controller = LocalSimulatorSessionController(
            league = League.FTC,
            initialTeleOps = listOf("org.firstinspires.ftc.teamcode.ARESStarterTeleOp"),
            initialSelectedOpMode = "org.firstinspires.ftc.teamcode.ARESStarterTeleOp",
        )

        assertEquals("OFFLINE", controller.statusText(isConnected = false, isSimulatorProcessRunning = false, keyboardDriveEnabled = false))
        assertEquals("CONNECTING", controller.statusText(isConnected = false, isSimulatorProcessRunning = true, keyboardDriveEnabled = false))
        assertEquals("WAITING FOR TELEOP", controller.statusText(isConnected = true, isSimulatorProcessRunning = true, keyboardDriveEnabled = false))

        controller.applyTelemetryFrame(
            TelemetryFrame(
                timestampMs = 1_100L,
                sessionId = "sim-test",
                key = ACTIVE_OPMODE_CLASS_TOPIC,
                value = 0.0,
                stringValue = "org.firstinspires.ftc.teamcode.ARESStarterTeleOp",
            ),
        )
        controller.applyTelemetryFrame(
            TelemetryFrame(
                timestampMs = 1_100L,
                sessionId = "sim-test",
                key = ACTIVE_OPMODE_STATE_TOPIC,
                value = 0.0,
                stringValue = TELEOP_RUNNING_STATE,
            ),
        )

        assertTrue(controller.isRunning(isConnected = true))
        assertEquals("TELEOP RUNNING", controller.statusText(isConnected = true, isSimulatorProcessRunning = true, keyboardDriveEnabled = false))

        controller.driveReceiverStatusCode = 3
        controller.driveReceiverLeaseAgeMs = 40L
        assertTrue(controller.receiverReady)
        assertEquals("CONTROL ACTIVE", controller.statusText(isConnected = true, isSimulatorProcessRunning = true, keyboardDriveEnabled = true))
    }

    @Test
    fun `applyLocalAutonomousCatalog populates fallback routines and labels`() {
        val controller = LocalSimulatorSessionController(league = League.FRC)
        controller.applyLocalAutonomousCatalog(
            localIds = listOf("center-two-piece", "do-nothing"),
            labels = mapOf("center-two-piece" to "Center Two Piece", "do-nothing" to "Do Nothing"),
        )

        assertEquals(listOf("center-two-piece", "do-nothing"), controller.availableAutos)
        assertEquals("do-nothing", controller.requestedAuto)
        assertEquals("Center Two Piece", controller.autonomousLabels["center-two-piece"])

        controller.applyTelemetryFrame(
            TelemetryFrame(
                timestampMs = 2_000L,
                sessionId = "sim-test",
                key = RobotTopicContract.SELECTED_AUTONOMOUS_ROUTINE,
                value = 0.0,
                stringValue = "center-two-piece",
            ),
        )
        assertEquals("center-two-piece", controller.robotSelectedAuto)
    }
}
