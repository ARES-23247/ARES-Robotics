package com.areslib.sim.xrp

import com.areslib.networktables.NT4Server
import com.areslib.state.RobotFieldManager
import com.areslib.telemetry.TelemetryTopicConstants
import com.areslib.util.RobotClock
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class XrpSimulationE2ETest {
    @Test fun testXrpEndToEndSimulationAndTelemetry() = runScenario(mecanum = false)
    @Test fun testMecanumLauncherAcceptsItsModeAndCanonicalFrames() = runScenario(mecanum = true)

    private fun runScenario(mecanum: Boolean) {
        RobotClock.useSystemTime()
        val previousConfig = RobotFieldManager.activeConfig
        val server = NT4Server.createInstance("127.0.0.1", 0)
        val failure = AtomicReference<Throwable?>(null)
        val fieldPresetFile = locateMonorepoFieldPreset()
        val args = mutableListOf("--headless", "--field-config", fieldPresetFile.absolutePath)
        if (mecanum) args += "--mecanum"
        val thread = Thread {
            try { XrpSimLauncher.main(args.toTypedArray()) } catch (caught: Throwable) { failure.set(caught) }
        }
        thread.isDaemon = true
        thread.start()
        fun pose() = NT4Server.getDoubleArray("ARES/SimulatorPoseFrame", doubleArrayOf())
        fun awaitReady() {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (pose().size != 10 && System.nanoTime() < deadline) {
                failure.get()?.let { throw AssertionError("Simulator failed", it) }
                Thread.sleep(10)
            }
            assertEquals(10, pose().size, "This run must publish its initial pose")
        }
        var sequence = 0L
        fun send(vx: Double = 0.0, vy: Double = 0.0, omega: Double = 0.0) {
            val seq = sequence++
            NT4Server.publishTopic(TelemetryTopicConstants.DRIVE_INPUT_FRAME,
                doubleArrayOf(2.0, 4601.0, seq.toDouble(), (seq * 20).toDouble(), vx, vy, omega, 8.0))
            Thread.sleep(20)
        }
        try {
            awaitReady()
            assertEquals("xrp-2026-orbit-odyssey", RobotFieldManager.activeConfig.id)
            val initial = pose()
            repeat(5) { send() }
            repeat(15) { if (mecanum) send(vy = 0.4) else send(vx = 0.4) }
            val moved = pose()
            val axis = if (mecanum) 1 else 0
            assertTrue(moved[axis] > initial[axis] + 0.04, "Physical body must move after leased input")
            assertTrue(moved[axis + 3] > initial[axis] + 0.04, "Redux estimator must consume odometry")
            assertTrue(moved[9] > initial[9])
            repeat(15) { send(omega = 2.0) }
            assertTrue(pose()[2] > 0.1, "Rotation remains CCW positive")
            // Retained input must expire without another publication.
            Thread.sleep(600)
            val expired = pose()
            Thread.sleep(100)
            val settled = pose()
            assertEquals(expired[0], settled[0], 1e-6)
            assertEquals(expired[1], settled[1], 1e-6)
            assertEquals(expired[2], settled[2], 1e-6)
            val ack = NT4Server.getDoubleArray(TelemetryTopicConstants.DRIVE_INPUT_ACK, doubleArrayOf())
            assertEquals(9, ack.size)
            assertEquals(0.0, ack[5]); assertEquals(0.0, ack[6]); assertEquals(0.0, ack[7])
            send(vx = 0.5)
            assertEquals(0.0, NT4Server.getDoubleArray(TelemetryTopicConstants.DRIVE_INPUT_ACK, doubleArrayOf())[5])
        } finally {
            XrpSimLauncher.isRunning = false
            thread.interrupt()
            thread.join(5000)
            try {
                assertFalse(thread.isAlive, "Simulator must terminate before restoring shared state")
                assertSame(server, NT4Server.getInstance(), "Launcher must preserve a borrowed server")
                failure.get()?.let { throw AssertionError("Simulator thread failed", it) }
            } finally {
                server.stop()
                NT4Server.resetSharedState()
                RobotFieldManager.setActiveConfig(previousConfig)
                RobotClock.useSystemTime()
            }
        }
    }

    private fun locateMonorepoFieldPreset(): File {
        val relativePath = "ARES-Analytics/app/src/main/resources/field-presets/xrp/orbit_odyssey_2026.json"
        return generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .map { directory -> directory.resolve(relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Canonical XRP field preset was not found")
    }
}
