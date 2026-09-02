package com.ares.analytics.ui.components.dashboard

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalSimulatorControlBarTest {
    @Test
    fun `normal mecanum TeleOp is preferred regardless of announcement order`() {
        val teleOps = listOf(
            "org.firstinspires.ftc.teamcode.opmodes.NullOpMode",
            "org.firstinspires.ftc.teamcode.opmodes.ARESMecanumTeleOp",
            "org.firstinspires.ftc.teamcode.opmodes.ARESRemoteDriveOpMode",
        )

        assertEquals(teleOps[1], preferredSimulatorTeleOp(teleOps))
    }

    @Test
    fun `remote drive is the fallback when normal mecanum TeleOp is absent`() {
        val teleOps = listOf(
            "org.firstinspires.ftc.teamcode.opmodes.NullOpMode",
            "org.firstinspires.ftc.teamcode.opmodes.ARESRemoteDriveOpMode",
        )

        assertEquals(teleOps[1], preferredSimulatorTeleOp(teleOps))
        assertNull(preferredSimulatorTeleOp(emptyList()))
    }

    @Test
    fun `generated starter TeleOp wins over library and diagnostic modes`() {
        val teleOps = listOf(
            "com.areslib.ftc.hardware.AresHardwareTestOpMode",
            "org.firstinspires.ftc.teamcode.opmodes.ARESRemoteDriveOpMode",
            "org.firstinspires.ftc.teamcode.opmodes.ARESStarterTeleOp",
        )

        assertEquals(teleOps[2], preferredSimulatorTeleOp(teleOps))
    }

    @Test
    fun `running acknowledgement requires the exact selected class`() {
        assertTrue(simulatorOpModeAcknowledged("a.Starter", "a.Starter", "TELEOP_RUNNING", "TELEOP_RUNNING"))
        assertFalse(simulatorOpModeAcknowledged("a.Starter", "b.Test", "TELEOP_RUNNING", "TELEOP_RUNNING"))
        assertFalse(simulatorOpModeAcknowledged("a.Starter", "a.Starter", "TELEOP_INIT", "TELEOP_RUNNING"))
    }

    @Test
    fun `control receiver is ready only for a fresh armed or active lease`() {
        assertTrue(simulatorDriveReceiverReady(2, 0))
        assertTrue(simulatorDriveReceiverReady(3, 500))
        assertFalse(simulatorDriveReceiverReady(3, 501))
        assertFalse(simulatorDriveReceiverReady(4, 10))
        assertEquals("CONTROL LEASE EXPIRED", simulatorDriveReceiverStatus(4))
    }

    @Test
    fun `FRC TeleOp requires the explicit simulation Driver Station acknowledgement`() {
        assertTrue(frcSimulatorTeleOpEnabled("TELEOP_ENABLED"))
        assertTrue(frcSimulatorTeleOpEnabled(" teleop_enabled "))
        assertFalse(frcSimulatorTeleOpEnabled("WAITING_FOR_CONTROL"))
        assertFalse(frcSimulatorTeleOpEnabled("DISABLED"))
        assertFalse(frcSimulatorTeleOpEnabled(null))
    }

    @Test
    fun `FRC autonomous acknowledgement stays distinct from TeleOp`() {
        assertTrue(frcSimulatorAutonomousEnabled("AUTONOMOUS_ENABLED"))
        assertTrue(frcSimulatorAutonomousEnabled(" autonomous_enabled "))
        assertFalse(frcSimulatorAutonomousEnabled("TELEOP_ENABLED"))
        assertFalse(frcSimulatorAutonomousEnabled("WAITING_FOR_CONTROL"))
        assertFalse(frcSimulatorAutonomousEnabled(null))
    }

    @Test
    fun `FRC autonomous display distinguishes match mode from routine outcome`() {
        assertEquals(
            FrcAutonomousDisplayState.INACTIVE,
            frcAutonomousDisplayState("DISABLED", "Complete"),
        )
        assertEquals(
            FrcAutonomousDisplayState.RUNNING,
            frcAutonomousDisplayState("AUTONOMOUS_ENABLED", "Running"),
        )
        assertEquals(
            FrcAutonomousDisplayState.COMPLETE,
            frcAutonomousDisplayState("AUTONOMOUS_ENABLED", "Complete"),
        )
        assertEquals(
            FrcAutonomousDisplayState.BLOCKED,
            frcAutonomousDisplayState("AUTONOMOUS_ENABLED", "Blocked"),
        )
    }

    @Test
    fun `FRC autonomous selection preserves an explicit valid choice and otherwise fails safe`() {
        val available = listOf("do-nothing", "score-and-leave")

        assertEquals(
            "score-and-leave",
            preferredSimulatorAutonomous(available, "score-and-leave", "do-nothing"),
        )
        assertEquals(
            "score-and-leave",
            preferredSimulatorAutonomous(available, "deleted", "score-and-leave"),
        )
        assertEquals(
            "do-nothing",
            preferredSimulatorAutonomous(available, "deleted", "also-deleted"),
        )
        assertNull(preferredSimulatorAutonomous(emptyList(), null, null))
    }

    @Test
    fun `malformed Driver Station inventory cannot crash the dashboard`() {
        assertEquals(emptyList(), decodeSimulatorOpModes("not-json"))
        assertEquals(listOf("One", "Two"), decodeSimulatorOpModes("[\"One\",\"Two\"]"))
    }

    @Test
    fun `acknowledgement polling observes a later simulator lifecycle state`() = runTest {
        var reads = 0

        val acknowledged = awaitSimulatorOpModeAcknowledgement(
            selectedOpMode = "team.LightPracticeAuto",
            expectedState = AUTONOMOUS_RUNNING_STATE,
            isConnected = { true },
            snapshot = {
                reads += 1
                SimulatorOpModeSnapshot(
                    activeOpMode = "team.LightPracticeAuto",
                    activeState = if (reads < 3) AUTONOMOUS_INIT_STATE else AUTONOMOUS_RUNNING_STATE,
                )
            },
            timeoutMs = 1_000,
        )

        assertTrue(acknowledged)
        assertEquals(3, reads)
    }

    @Test
    fun `short autonomous completion is accepted as proof that start ran`() = runTest {
        val acknowledged = awaitSimulatorOpModeAcknowledgement(
            selectedOpMode = "team.LightPracticeAuto",
            expectedState = AUTONOMOUS_RUNNING_STATE,
            isConnected = { true },
            snapshot = {
                SimulatorOpModeSnapshot(
                    activeOpMode = "team.LightPracticeAuto",
                    activeState = "DISABLED",
                    autonomousStatus = "Complete",
                )
            },
            acceptedAutonomousStatuses = setOf("RUNNING", "COMPLETE"),
            timeoutMs = 1_000,
        )

        assertTrue(acknowledged)
        assertEquals(FrcAutonomousDisplayState.COMPLETE, ftcAutonomousDisplayState("Complete"))
        assertEquals(FrcAutonomousDisplayState.BLOCKED, ftcAutonomousDisplayState("Failed"))
    }

    @Test
    fun `acknowledgement polling stops when simulator disconnects`() = runTest {
        var connected = true

        val acknowledged = awaitSimulatorOpModeAcknowledgement(
            selectedOpMode = "team.LightPracticeAuto",
            expectedState = AUTONOMOUS_RUNNING_STATE,
            isConnected = {
                connected.also { connected = false }
            },
            snapshot = { SimulatorOpModeSnapshot(null, null) },
            timeoutMs = 1_000,
        )

        assertFalse(acknowledged)
    }

    @Test
    fun `FTC autonomous inventory uses autonomous lifecycle acknowledgements`() {
        val teleOps = listOf("team.AresTeleOp")
        val autos = listOf("team.LightPracticeAuto")

        assertEquals(FtcSimulatorOpModeKind.TELEOP, ftcSimulatorOpModeKind(teleOps.single(), teleOps, autos))
        assertEquals(FtcSimulatorOpModeKind.AUTONOMOUS, ftcSimulatorOpModeKind(autos.single(), teleOps, autos))
        assertNull(ftcSimulatorOpModeKind("team.Stale", teleOps, autos))
        assertEquals(AUTONOMOUS_INIT_STATE, FtcSimulatorOpModeKind.AUTONOMOUS.initState())
        assertEquals(AUTONOMOUS_RUNNING_STATE, FtcSimulatorOpModeKind.AUTONOMOUS.runningState())
        assertEquals(TELEOP_INIT_STATE, FtcSimulatorOpModeKind.TELEOP.initState())
        assertEquals(TELEOP_RUNNING_STATE, FtcSimulatorOpModeKind.TELEOP.runningState())
    }

    @Test
    fun `offline primary action launches the simulator instead of offering disabled drive`() {
        assertEquals(
            LocalSimulatorPrimaryAction.LAUNCH_SIMULATOR,
            localSimulatorPrimaryAction(
                isConnected = false,
                isSimulatorProcessRunning = false,
                isLaunchPreparationRunning = false,
                launchRequiresVerification = false,
                isTeleOpStarting = false,
                isTeleOpRunning = false,
            ),
        )
    }

    @Test
    fun `managed simulator launch waits for NT4 before offering TeleOp controls`() {
        assertEquals(
            LocalSimulatorPrimaryAction.WAIT_FOR_CONNECTION,
            localSimulatorPrimaryAction(
                isConnected = false,
                isSimulatorProcessRunning = true,
                isLaunchPreparationRunning = false,
                launchRequiresVerification = false,
                isTeleOpStarting = false,
                isTeleOpRunning = false,
            ),
        )
        assertEquals(
            LocalSimulatorPrimaryAction.START_DRIVING,
            localSimulatorPrimaryAction(
                isConnected = true,
                isSimulatorProcessRunning = true,
                isLaunchPreparationRunning = false,
                launchRequiresVerification = false,
                isTeleOpStarting = false,
                isTeleOpRunning = false,
            ),
        )
    }

    @Test
    fun `connected primary action reports TeleOp transitions`() {
        assertEquals(
            LocalSimulatorPrimaryAction.STARTING_TELEOP,
            localSimulatorPrimaryAction(
                isConnected = true,
                isSimulatorProcessRunning = true,
                isLaunchPreparationRunning = false,
                launchRequiresVerification = false,
                isTeleOpStarting = true,
                isTeleOpRunning = false,
            ),
        )
        assertEquals(
            LocalSimulatorPrimaryAction.TELEOP_RUNNING,
            localSimulatorPrimaryAction(
                isConnected = true,
                isSimulatorProcessRunning = true,
                isLaunchPreparationRunning = false,
                launchRequiresVerification = false,
                isTeleOpStarting = false,
                isTeleOpRunning = true,
            ),
        )
    }

    @Test
    fun `fresh session offers verification and launch as one visible workflow`() {
        assertEquals(
            LocalSimulatorPrimaryAction.VERIFY_AND_LAUNCH,
            localSimulatorPrimaryAction(
                isConnected = false,
                isSimulatorProcessRunning = false,
                isLaunchPreparationRunning = false,
                launchRequiresVerification = true,
                isTeleOpStarting = false,
                isTeleOpRunning = false,
            ),
        )
        assertEquals(
            LocalSimulatorPrimaryAction.VERIFYING_PROJECT,
            localSimulatorPrimaryAction(
                isConnected = false,
                isSimulatorProcessRunning = false,
                isLaunchPreparationRunning = true,
                launchRequiresVerification = true,
                isTeleOpStarting = false,
                isTeleOpRunning = false,
            ),
        )
        assertEquals("Building simulator", LocalSimulatorPrimaryAction.VERIFYING_PROJECT.label)
    }

    @Test
    fun `fresh valid project requests verification before simulator process`() {
        assertEquals(
            LocalSimulatorLaunchRequest.VERIFY_THEN_START,
            localSimulatorLaunchRequest(
                canRunSimulation = false,
                canRunBuild = true,
                isBuildRunning = false,
                isSimulatorRunning = false,
                isSimulatorOnline = false,
                isLaunchPending = false,
            ),
        )
        assertEquals(
            LocalSimulatorLaunchRequest.START_SIMULATOR,
            localSimulatorLaunchRequest(
                canRunSimulation = true,
                canRunBuild = true,
                isBuildRunning = false,
                isSimulatorRunning = false,
                isSimulatorOnline = false,
                isLaunchPending = false,
            ),
        )
    }

    @Test
    fun `launch request cannot duplicate an active build or simulator`() {
        assertEquals(
            LocalSimulatorLaunchRequest.NONE,
            localSimulatorLaunchRequest(
                canRunSimulation = true,
                canRunBuild = true,
                isBuildRunning = true,
                isSimulatorRunning = false,
                isSimulatorOnline = false,
                isLaunchPending = true,
            ),
        )
        assertEquals(
            LocalSimulatorLaunchRequest.NONE,
            localSimulatorLaunchRequest(
                canRunSimulation = true,
                canRunBuild = true,
                isBuildRunning = false,
                isSimulatorRunning = false,
                isSimulatorOnline = true,
                isLaunchPending = false,
            ),
        )
    }
}
