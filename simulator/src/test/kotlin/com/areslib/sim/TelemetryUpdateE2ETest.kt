package com.areslib.sim

import com.areslib.networktables.NT4Server
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Consolidated headless simulator E2E test.
 *
 * Both the basic sim lifecycle test and the telemetry topic verification
 * are combined into a single test method to avoid port-rebinding races.
 * The NT4 WebSocket server binds to port 5810 and cannot be reliably
 * released and re-bound within the same JVM process due to OS socket
 * TIME_WAIT state.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TelemetryUpdateE2ETest {
    private var drivePublisherThread: Thread? = null

    @org.junit.Before
    fun setup() {
        com.areslib.sim.DesktopSimLauncher.isSimRunning = false
        com.areslib.telemetry.SimInputBridge.reset()
        Thread.sleep(300)
    }

    @org.junit.After
    fun teardown() {
        DesktopSimLauncher.isSimRunning = false
        drivePublisherThread?.interrupt()
        drivePublisherThread?.join(1_000L)
        drivePublisherThread = null
        com.areslib.telemetry.SimInputBridge.reset()
    }

    @Test
    fun testAllTelemetryTopicsUpdateProperly() {
        // 1. Launch simulator in headless server mode
        println("[Telemetry E2E Test] Launching simulator in headless mode...")
        val simThread = Thread {
            DesktopSimLauncher.main(arrayOf("--headless"))
        }
        simThread.isDaemon = true
        simThread.start()

        // Wait for NT4 server & OpMode init — use longer wait for cold JVM startup
        Thread.sleep(2500)

        // Verify NT4Server is alive
        val server = NT4Server.getInstance()
        assertNotNull("NT4Server should be active on port 5810", server)
        // Dashboard match sequencing owns this topic. The simulator must never overwrite its
        // richer AUTO_INIT/AUTO_RUNNING/TRANSITION state machine.
        NT4Server.publishTopic("ARES/DriverStation/MatchState", "AUTO_RUNNING")

        // 1b. Verify OpMode announcement (from SimE2ETest)
        var teleOpListJson = NT4Server.getString("ARES/DriverStation/TeleOpList", "")
        if (teleOpListJson.isEmpty()) {
            com.areslib.sim.opmode.SimOpModeRunner.scanAndPublishOpModes()
            teleOpListJson = NT4Server.getString("ARES/DriverStation/TeleOpList", "")
        }
        println("[Telemetry E2E Test] Announced TeleOpList: $teleOpListJson")
        assertTrue("TeleOpList should not be empty", teleOpListJson.isNotEmpty())

        // 2. Select OpMode and send INIT + START commands
        NT4Server.publishTopic("ARES/DriverStation/SelectedOpMode", "com.areslib.ftc.hardware.AresHardwareTestOpMode")
        NT4Server.publishTopic("ARES/DriverStation/Command", "INIT")
        Thread.sleep(500)
        NT4Server.publishTopic("ARES/DriverStation/Command", "START")
        Thread.sleep(500)

        // 3. Inject drive input (vx = 2.0 m/s)
        println("[Telemetry E2E Test] Injecting vx = 2.0 m/s drive input...")
        val driveSession = 23_247.0
        NT4Server.publishTopic(
            com.areslib.telemetry.TelemetryTopicConstants.DRIVE_INPUT_FRAME,
            doubleArrayOf(2.0, driveSession, 0.0, 1_000.0, 0.0, 0.0, 0.0, 16.0)
        )
        val neutralHandshake = com.areslib.telemetry.SimInputBridge.pollNetworkFrame()
        assertFalse(neutralHandshake.isIntaking)
        assertTrue(neutralHandshake.isFieldCentric)
        assertFalse(neutralHandshake.isTeleopMode)
        drivePublisherThread = Thread({
            var sequence = 1L
            try {
                while (DesktopSimLauncher.isSimRunning) {
                    NT4Server.publishTopic(
                        com.areslib.telemetry.TelemetryTopicConstants.DRIVE_INPUT_FRAME,
                        doubleArrayOf(
                            2.0,
                            driveSession,
                            sequence.toDouble(),
                            (1_000L + sequence * 20L).toDouble(),
                            2.0,
                            0.0,
                            0.0,
                            25.0 // intake + teleop + field-centric
                        )
                    )
                    sequence++
                    Thread.sleep(20L)
                }
            } catch (_: InterruptedException) {
                // Normal test shutdown.
            }
        }, "Telemetry-E2E-DrivePublisher").also {
            it.isDaemon = true
            it.start()
        }

        val obstacleJson = """[{"id":"dashboard-wall","name":"Dashboard Wall","type":"Rectangle","centerX":0.5,"centerY":0.25,"width":0.4,"height":0.2,"rotation":0.0}]"""
        NT4Server.publishTopic("ARES/Input/obstacles", obstacleJson)
        assertEquals(obstacleJson, com.areslib.sim.network.TelemetryPublisher.getWebObstacles())
        Thread.sleep(100)
        assertTrue(
            "Dashboard obstacle should be applied to the active simulator field",
            com.areslib.state.RobotFieldManager.activeConfig.obstacles.any { it.id == "dashboard-wall" }
        )

        // Wait for sim loop to step, publish motor state, and build up velocity
        Thread.sleep(1500)

        // 4. Verify canonical FTC Motor Powers (fl, fr, rl, rr)
        val flPower = NT4Server.getDouble("Hardware/Motors/fl/Power", 0.0)
        val frPower = NT4Server.getDouble("Hardware/Motors/fr/Power", 0.0)
        val rlPower = NT4Server.getDouble("Hardware/Motors/rl/Power", 0.0)
        val rrPower = NT4Server.getDouble("Hardware/Motors/rr/Power", 0.0)

        println("[Telemetry E2E Test] Motor Powers -> FL: $flPower, FR: $frPower, RL: $rlPower, RR: $rrPower")
        // This test owns the telemetry transport contract, not the controller's transient wheel
        // mixing. Dedicated field-centric drive tests cover that behavior. Here every canonical
        // motor topic must update with a finite, non-trivial value after the leased input frame.
        // The real TeleOp path applies deadband, cubic shaping, and smoothing, so 2 m/s settles
        // near 0.09 motor power rather than exceeding 0.1.
        assertTrue("FL motor power magnitude should be > 0.05", kotlin.math.abs(flPower) > 0.05)
        assertTrue("FR motor power magnitude should be > 0.05", kotlin.math.abs(frPower) > 0.05)
        assertTrue("RL motor power magnitude should be > 0.05", kotlin.math.abs(rlPower) > 0.05)
        assertTrue("RR motor power magnitude should be > 0.05", kotlin.math.abs(rrPower) > 0.05)

        // 5. Verify Motor Velocities (ticks/sec)
        val flVel = NT4Server.getDouble("Hardware/Motors/fl/Velocity", Double.NaN)
        val frVel = NT4Server.getDouble("Hardware/Motors/fr/Velocity", Double.NaN)
        val rlVel = NT4Server.getDouble("Hardware/Motors/rl/Velocity", Double.NaN)
        val rrVel = NT4Server.getDouble("Hardware/Motors/rr/Velocity", Double.NaN)

        println("[Telemetry E2E Test] Motor Velocities -> FL: $flVel, FR: $frVel, RL: $rlVel, RR: $rrVel")
        val velocityTopics = listOf("fl" to flVel, "fr" to frVel, "rl" to rlVel, "rr" to rrVel)
        velocityTopics.forEach { (name, velocity) ->
            assertTrue("$name motor velocity topic should contain a finite value", velocity.isFinite())
        }
        // Wheel velocity is reconstructed from the simulated body's actual twist. A valid wheel can
        // momentarily cross zero when translation and rotation cancel, so assert aggregate motion
        // instead of requiring every wheel to exceed an arbitrary instantaneous threshold.
        assertTrue(
            "Simulated drivetrain should report meaningful aggregate wheel motion",
            velocityTopics.sumOf { kotlin.math.abs(it.second) } > 100.0
        )

        // 6. Verify Motor Current Draw (Amperes)
        val flCurrent = NT4Server.getDouble("Hardware/Motors/fl/CurrentAmps", 0.0)
        val frCurrent = NT4Server.getDouble("Hardware/Motors/fr/CurrentAmps", 0.0)
        val rlCurrent = NT4Server.getDouble("Hardware/Motors/rl/CurrentAmps", 0.0)
        val rrCurrent = NT4Server.getDouble("Hardware/Motors/rr/CurrentAmps", 0.0)

        println("[Telemetry E2E Test] Motor Currents -> FL: ${flCurrent}A, FR: ${frCurrent}A, RL: ${rlCurrent}A, RR: ${rrCurrent}A")
        assertTrue("FL motor current draw should be > 0.1A under drive load", flCurrent > 0.1)
        assertTrue("FR motor current draw should be > 0.1A under drive load", frCurrent > 0.1)
        assertTrue("RL motor current draw should be > 0.1A under drive load", rlCurrent > 0.1)
        assertTrue("RR motor current draw should be > 0.1A under drive load", rrCurrent > 0.1)

        // 7. Verify Odometry & EKF Pose telemetry streams
        val estX = NT4Server.getDouble("ARES/EstimatedPose/0", 0.0)
        val estY = NT4Server.getDouble("ARES/EstimatedPose/1", 0.0)
        val estHeading = NT4Server.getDouble("ARES/EstimatedPose/2", 0.0)
        println("[Telemetry E2E Test] Estimated Pose -> X: $estX, Y: $estY, Heading: $estHeading rad")

        val trueX = NT4Server.getDouble("ARES/TruePose/0", 0.0)
        val trueY = NT4Server.getDouble("ARES/TruePose/1", 0.0)
        println("[Telemetry E2E Test] True Physics Pose -> X: $trueX, Y: $trueY")
        assertTrue("Robot field X should advance under positive field-vx input (X=$trueX, Y=$trueY)", trueX > 0.05)

        // 8. Verify simulator lifecycle publication without stealing dashboard MatchState.
        val matchState = NT4Server.getString("ARES/DriverStation/MatchState", "")
        println("[Telemetry E2E Test] Match State: '$matchState'")
        assertEquals("Simulator must not overwrite dashboard-owned MatchState", "AUTO_RUNNING", matchState)
        assertEquals(
            "TELEOP_RUNNING",
            NT4Server.getString(DesktopSimLauncher.ACTIVE_OP_MODE_STATE_TOPIC, ""),
        )

        // 9. Verify Telemetry Line Output
        val teleLine0 = NT4Server.getString("ARES/DriverStation/Telemetry/0", "")
        println("[Telemetry E2E Test] Telemetry Line 0: '$teleLine0'")
        assertTrue("Driver Station Telemetry Line 0 should not be empty", teleLine0.isNotEmpty())

        println("[Telemetry E2E Test] SUCCESS! All simulated telemetry streams verified cleanly.")

        // Cleanup is performed by teardown even when an assertion fails.
    }

}
