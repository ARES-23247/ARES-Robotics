package com.areslib.sim.infra

import com.areslib.telemetry.SimInputBridge
import com.qualcomm.robotcore.hardware.Gamepad
import java.awt.event.KeyEvent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimGamepadManagerTest {
    @Test
    fun `GLFW runtime stays initialized until the final lease of a generation closes`() {
        val backend = RecordingGlfwBackend()
        val lifecycle = GlfwLifecycleCoordinator(backend)

        val first = requireNotNull(lifecycle.acquire())
        val second = requireNotNull(lifecycle.acquire())
        assertEquals(first.generation, second.generation)
        lifecycle.release(first)
        assertEquals(listOf("init"), backend.events)

        val third = requireNotNull(lifecycle.acquire())
        assertEquals(second.generation, third.generation)
        lifecycle.release(second)
        lifecycle.release(third)
        assertEquals(listOf("init", "terminate"), backend.events)

        val nextGeneration = requireNotNull(lifecycle.acquire())
        assertTrue(nextGeneration.generation > first.generation)
        lifecycle.release(nextGeneration)
    }

    @Test
    fun `close joins poller before a later manager initializes a new GLFW generation`() {
        val backend = RecordingGlfwBackend()
        val lifecycle = GlfwLifecycleCoordinator(backend)

        fun startManager(): Pair<SimGamepadManager, CountDownLatch> {
            val entered = CountDownLatch(1)
            val manager = SimGamepadManager(
                lifecycle,
                SimGamepadPollingLoop { shouldContinue, _ ->
                    backend.events.add("poll-start")
                    entered.countDown()
                    try {
                        while (shouldContinue()) Thread.sleep(10L)
                    } finally {
                        backend.events.add("poll-exit")
                    }
                },
            )
            manager.startPolling { }
            return manager to entered
        }

        val (first, firstEntered) = startManager()
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        first.close()
        val firstTerminateIndex = backend.events.indexOf("terminate")
        assertTrue(firstTerminateIndex > backend.events.indexOf("poll-exit"))

        val (second, secondEntered) = startManager()
        assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
        val secondInitIndex = backend.events.lastIndexOf("init")
        assertTrue(secondInitIndex > firstTerminateIndex)
        second.close()
        assertEquals(2, backend.events.count { it == "init" })
        assertEquals(2, backend.events.count { it == "terminate" })
    }

    @Test
    fun `fresh remote lease overrides local input and expiry restores it unchanged`() {
        val manager = SimGamepadManager()
        manager.handleKeyPressed(KeyEvent.VK_W) { }
        manager.isRedAlliance = true
        assertEquals(4.0, manager.getChassisSpeeds().vxMetersPerSecond, 1e-9)
        assertTrue(manager.effectiveIsRedAlliance)

        manager.applyRemoteCommand(command(vx = 2.0, redAlliance = false))
        assertTrue(manager.hasRemoteAuthority)
        assertEquals(2.0, manager.getChassisSpeeds().vxMetersPerSecond, 1e-9)
        assertFalse(manager.effectiveIsRedAlliance)

        val driver = Gamepad()
        manager.writeEffectiveGamepads(driver, Gamepad())
        assertEquals(-0.5f, driver.left_stick_y, 1e-6f)
        manager.recordAppliedDriveFrame(fieldCentric = false)
        assertFalse(manager.appliedIsFieldCentric)

        manager.clearRemoteCommand()
        assertFalse(manager.hasRemoteAuthority)
        assertEquals(4.0, manager.getChassisSpeeds().vxMetersPerSecond, 1e-9)
        assertTrue(manager.effectiveIsRedAlliance)
    }

    @Test
    fun `desired mechanism toggles inject one press for either state transition`() {
        val manager = SimGamepadManager()
        val driver = Gamepad()
        val operator = Gamepad()

        manager.observeAcceptedMechanismState(intakeAccepted = false, flywheelAccepted = false)
        manager.writeEffectiveGamepads(driver, operator)
        assertFalse(driver.left_bumper)
        manager.isIntaking = true
        manager.writeEffectiveGamepads(driver, operator)
        assertTrue(driver.left_bumper)
        manager.observeAcceptedMechanismState(intakeAccepted = true, flywheelAccepted = false)
        manager.writeEffectiveGamepads(driver, operator)
        assertFalse(driver.left_bumper)
        assertTrue(manager.appliedIsIntaking)
        manager.isIntaking = false
        manager.writeEffectiveGamepads(driver, operator)
        assertTrue(driver.left_bumper)
    }

    @Test
    fun `rejected toggle is retried after a release frame until accepted state converges`() {
        val manager = SimGamepadManager()
        val driver = Gamepad()
        val operator = Gamepad()
        manager.observeAcceptedMechanismState(intakeAccepted = true, flywheelAccepted = false)
        manager.isFlywheelOn = true

        manager.writeEffectiveGamepads(driver, operator)
        assertTrue(driver.right_bumper)

        // The season interlock rejected the edge, so accepted state remains false. The next frame
        // releases the synthetic button and the following frame retries it.
        manager.observeAcceptedMechanismState(intakeAccepted = true, flywheelAccepted = false)
        manager.writeEffectiveGamepads(driver, operator)
        assertFalse(driver.right_bumper)
        manager.writeEffectiveGamepads(driver, operator)
        assertTrue(driver.right_bumper)

        manager.observeAcceptedMechanismState(intakeAccepted = false, flywheelAccepted = true)
        manager.writeEffectiveGamepads(driver, operator)
        assertFalse(driver.right_bumper)
        assertTrue(manager.appliedIsFlywheelOn)
    }

    private fun command(vx: Double, redAlliance: Boolean) = SimInputBridge.CommandFrame(
        vx = vx,
        vy = 0.0,
        omega = 0.0,
        isIntaking = false,
        isFlywheelOn = false,
        isTransferring = false,
        isTeleopMode = true,
        isFieldCentric = true,
        isRedAlliance = redAlliance,
        isButtonAPressed = false,
        isButtonBPressed = false,
        isButtonXPressed = false,
        isPoseReset = false,
        sessionNonce = 1L,
        sequence = 1L,
        clientMonotonicMs = 1L,
        receivedAtMs = 1L,
    )

    private class RecordingGlfwBackend : GlfwLifecycleBackend {
        val events = Collections.synchronizedList(mutableListOf<String>())
        override fun initialize(): Boolean {
            events.add("init")
            return true
        }

        override fun terminate() {
            events.add("terminate")
        }
    }
}
