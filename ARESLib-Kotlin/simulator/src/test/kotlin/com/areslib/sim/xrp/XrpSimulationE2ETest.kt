package com.areslib.sim.xrp

import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XrpSimulationE2ETest {

    @Test
    fun testXrpEndToEndSimulationAndTelemetry() {
        println("[XRP E2E Test] Starting XRP End-to-End Simulation Test...")

        // Locate Orbit Odyssey field preset
        val candidatePaths = listOf(
            File("../ARES-Analytics/app/src/main/resources/field-presets/xrp/orbit_odyssey_2026.json"),
            File("../../ARES-Analytics/app/src/main/resources/field-presets/xrp/orbit_odyssey_2026.json"),
            File("c:/Users/david/dev/robotics/ARES-Robotics/ARES-Analytics/app/src/main/resources/field-presets/xrp/orbit_odyssey_2026.json")
        )
        val fieldPresetFile = candidatePaths.firstOrNull { it.isFile }
        val fieldArg = fieldPresetFile?.absolutePath ?: ""

        // Launch XRP Simulator in headless background thread
        XrpSimLauncher.isRunning = true
        val simThread = Thread {
            try {
                XrpSimLauncher.main(
                    if (fieldArg.isNotEmpty()) arrayOf("--headless", "--field-config", fieldArg)
                    else arrayOf("--headless")
                )
            } catch (t: Throwable) {
                System.err.println("[XRP E2E Test] Exception in simThread:")
                t.printStackTrace()
            }
        }
        simThread.isDaemon = true
        simThread.start()

        try {
            // Wait for NT4 server & simulation loop to initialize
            var server = NT4Instance.defaultInstance.defaultServer
            var attempts = 0
            while (server == null && attempts < 50) {
                Thread.sleep(50)
                server = NT4Instance.defaultInstance.defaultServer
                attempts++
            }
            assertNotNull(server, "NT4 Server should be running")

            // Wait for first pose frame publication
            var initialFrame = NT4Server.getDoubleArray("ARES/SimulatorPoseFrame", DoubleArray(0))
            var frameAttempts = 0
            while (initialFrame.size < 10 && frameAttempts < 50) {
                Thread.sleep(50)
                initialFrame = NT4Server.getDoubleArray("ARES/SimulatorPoseFrame", DoubleArray(0))
                frameAttempts++
            }
            assertEquals(10, initialFrame.size, "Simulator must publish 10-element SimulatorPoseFrame")

            val initialTrueX = initialFrame[0]
            val initialTrueY = initialFrame[1]
            println("[XRP E2E Test] Initial pose: X=$initialTrueX, Y=$initialTrueY")

            // Inject leased forward drive frame from Driver Station (vx = 0.6 m/s)
            val driveCommand = doubleArrayOf(0.6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            NT4Server.publishTopic("ARES/Input/driveFrame", driveCommand)

            // Step forward for 300ms
            Thread.sleep(350)

            // Read updated pose frame
            val postDriveFrame = NT4Server.getDoubleArray("ARES/SimulatorPoseFrame", DoubleArray(0))
            assertEquals(10, postDriveFrame.size)
            val postTrueX = postDriveFrame[0]
            val postEstX = postDriveFrame[3]
            val postOdomX = postDriveFrame[6]
            val sequence = postDriveFrame[9]

            println("[XRP E2E Test] Post-drive pose: trueX=$postTrueX, estX=$postEstX, odomX=$postOdomX, seq=$sequence")

            assertTrue(postTrueX > initialTrueX, "Physical body should translate forward along X (was $postTrueX, initial $initialTrueX)")
            assertTrue(postEstX > initialTrueX, "OTOS estimate should translate forward along X (was $postEstX)")
            assertTrue(sequence > initialFrame[9], "Simulator frame sequence should increment")

            // Verify topic mirrors
            val poseX = NT4Server.getDouble("Drive/Pose_X", 0.0)
            assertEquals(postEstX, poseX, 1e-4)

            // Inject rotational drive frame (omega = 2.0 rad/s CCW)
            val turnCommand = doubleArrayOf(0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            NT4Server.publishTopic("ARES/Input/driveFrame", turnCommand)
            Thread.sleep(300)

            val postTurnFrame = NT4Server.getDoubleArray("ARES/SimulatorPoseFrame", DoubleArray(0))
            val postHeading = postTurnFrame[2]
            println("[XRP E2E Test] Post-turn heading: $postHeading rad")
            assertTrue(postHeading > 0.0, "Robot heading should rotate CCW-positive (was $postHeading)")

        } finally {
            println("[XRP E2E Test] Stopping simulation...")
            XrpSimLauncher.isRunning = false
            simThread.interrupt()
        }
    }
}
