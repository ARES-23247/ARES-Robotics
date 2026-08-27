package com.areslib.sim.opmode

import com.areslib.ftc.photon.AresFtcRuntimeOptions
import com.areslib.ftc.photon.AresFtcRuntimeOptionsProvider
import com.areslib.ftc.photon.FtcHubCommandTransport
import com.areslib.telemetry.RobotStatusTracker
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.eventloop.opmode.Autonomous
import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import com.qualcomm.robotcore.hardware.HardwareMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SimOpModeLifecycleTest {
    @Test
    fun `simulator reports selected runtime policy without claiming hardware photon is active`() {
        val lifecycle = requireNotNull(SimOpModeLifecycle.wrap(PhotonPolicyOpMode()))

        lifecycle.initialize(HardwareMap())

        assertEquals(FtcHubCommandTransport.ARES_PHOTON.name, RobotStatusTracker.ftcHubCommandTransport)
        assertFalse(RobotStatusTracker.ftcPhotonActive)
        assertTrue(RobotStatusTracker.ftcLimelightProxyConfigured)
        assertFalse(RobotStatusTracker.ftcLimelightProxyActive)
        lifecycle.stop()
    }

    @Test
    fun `annotation-derived auto and teleop states remain distinct through lifecycle`() {
        val auto = requireNotNull(SimOpModeLifecycle.wrap(RecordingAutoOpMode()))
        val teleop = requireNotNull(SimOpModeLifecycle.wrap(RecordingIterativeOpMode()))

        assertEquals(SimOpModeKind.AUTONOMOUS, auto.modeKind)
        assertEquals(SimOpModeState.DISABLED, auto.publishedState)
        auto.initialize(HardwareMap())
        assertEquals(SimOpModeState.AUTO_INIT, auto.publishedState)
        auto.start()
        assertEquals(SimOpModeState.AUTO_RUNNING, auto.publishedState)
        auto.stop()
        assertEquals(SimOpModeState.DISABLED, auto.publishedState)

        assertEquals(SimOpModeKind.TELEOP, teleop.modeKind)
        teleop.initialize(HardwareMap())
        assertEquals(SimOpModeState.TELEOP_INIT, teleop.publishedState)
        teleop.start()
        assertEquals(SimOpModeState.TELEOP_RUNNING, teleop.publishedState)
        teleop.stop()
    }

    @Test
    fun `iterative opmode receives the complete SDK lifecycle`() {
        val opMode = RecordingIterativeOpMode()
        val lifecycle = requireNotNull(SimOpModeLifecycle.wrap(opMode))
        val hardwareMap = HardwareMap()

        lifecycle.initialize(hardwareMap)
        lifecycle.tick()
        lifecycle.tick()
        lifecycle.start()
        lifecycle.tick()
        lifecycle.stop()
        lifecycle.stop()

        assertSame(hardwareMap, opMode.hardwareMap)
        assertEquals(1, opMode.initCount)
        assertEquals(2, opMode.initLoopCount)
        assertEquals(1, opMode.startCount)
        assertEquals(1, opMode.loopCount)
        assertEquals(1, opMode.stopCount)
        assertTrue(opMode.everyCallbackWasExternallyPaced)
        assertFalse(com.areslib.ftc.core.FtcOpModeLifecycleController.isCurrentFrameExternallyPaced())
        assertFalse(lifecycle.isStarted)
        assertTrue(lifecycle.stopRequested)
    }

    @Test
    fun `linear opmode retains its worker lifecycle`() {
        val opMode = RecordingLinearOpMode()
        val lifecycle = requireNotNull(SimOpModeLifecycle.wrap(opMode))

        lifecycle.initialize(HardwareMap())
        assertTrue(opMode.entered.await(1, TimeUnit.SECONDS))
        lifecycle.tick()
        lifecycle.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (opMode.activeLoopCount == 0 && System.nanoTime() < deadline) Thread.yield()

        assertTrue(lifecycle.isStarted)
        assertTrue(opMode.activeLoopCount > 0)
        lifecycle.stop()
        assertFalse(lifecycle.isStarted)
        assertTrue(opMode.isStopRequested)
    }

    @Test
    fun `resolver accepts iterative classes and rejects unknown names without substitution`() {
        val resolved = SimOpModeRunner.createOpModeInstance(null, RecordingIterativeOpMode::class.java.name)

        assertNotNull(resolved)
        assertTrue(resolved?.rawOpMode is RecordingIterativeOpMode)
        assertNull(SimOpModeRunner.createOpModeInstance(null, "missing.OpModeThatMustNotFallback"))
    }

    @Test
    fun `supported class without FTC mode annotation is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SimOpModeLifecycle.wrap(UnannotatedOpMode())
        }
    }

    @Test
    fun `failed iterative init rolls back through stop exactly once`() {
        val opMode = ThrowingInitOpMode()
        val lifecycle = requireNotNull(SimOpModeLifecycle.wrap(opMode))

        assertThrows(IllegalStateException::class.java) {
            lifecycle.initialize(HardwareMap())
        }
        lifecycle.stop()
        assertEquals(1, opMode.stopCount)
        assertFalse(lifecycle.isStarted)
    }

    @Test
    fun `noncooperative linear stop retains ownership and makes later init terminal`() {
        val opMode = NonCooperativeLinearOpMode()
        val lifecycle = requireNotNull(SimOpModeLifecycle.wrap(opMode))
        val slot = SimOpModeLifecycleSlot(lifecycle)
        lifecycle.initialize(HardwareMap())
        assertTrue(opMode.entered.await(1, TimeUnit.SECONDS))

        try {
            val failure = assertThrows(IllegalStateException::class.java) { slot.stopActive() }

            assertTrue(failure.message.orEmpty().contains("terminal"))
            assertTrue(slot.isTerminal)
            assertSame(lifecycle, slot.activeMode)
            assertTrue(lifecycle.hasPendingTermination)
            var candidateConstructed = false
            assertThrows(IllegalStateException::class.java) {
                slot.install {
                    candidateConstructed = true
                    requireNotNull(SimOpModeLifecycle.wrap(RecordingIterativeOpMode()))
                }
            }
            assertFalse("terminal transition must reject INIT before constructing a candidate", candidateConstructed)
        } finally {
            opMode.allowExit = true
            slot.stopActiveForShutdown()
        }

        assertNull(slot.activeMode)
        assertFalse(lifecycle.hasPendingTermination)
    }

    @TeleOp(name = "Recording iterative")
    class RecordingIterativeOpMode : OpMode() {
        var initCount = 0
        var initLoopCount = 0
        var startCount = 0
        var loopCount = 0
        var stopCount = 0
        var everyCallbackWasExternallyPaced = true

        override fun init() {
            initCount++
        }

        override fun init_loop() {
            initLoopCount++
            everyCallbackWasExternallyPaced = everyCallbackWasExternallyPaced &&
                com.areslib.ftc.core.FtcOpModeLifecycleController.isCurrentFrameExternallyPaced()
        }

        override fun start() {
            startCount++
        }

        override fun loop() {
            loopCount++
            everyCallbackWasExternallyPaced = everyCallbackWasExternallyPaced &&
                com.areslib.ftc.core.FtcOpModeLifecycleController.isCurrentFrameExternallyPaced()
        }

        override fun stop() {
            stopCount++
        }
    }

    @TeleOp(name = "Photon policy")
    class PhotonPolicyOpMode : OpMode(), AresFtcRuntimeOptionsProvider {
        override val aresFtcRuntimeOptions = AresFtcRuntimeOptions(
            hubCommandTransport = FtcHubCommandTransport.ARES_PHOTON,
            limelightProxyEnabled = true,
        )

        override fun init() = Unit
        override fun loop() = Unit
    }

    @TeleOp(name = "Recording linear")
    class RecordingLinearOpMode : LinearOpMode() {
        val entered = CountDownLatch(1)
        @Volatile var activeLoopCount = 0

        override fun runOpMode() {
            entered.countDown()
            waitForStart()
            while (opModeIsActive()) {
                activeLoopCount++
                Thread.yield()
            }
        }
    }

    @TeleOp(name = "Noncooperative linear")
    class NonCooperativeLinearOpMode : LinearOpMode() {
        val entered = CountDownLatch(1)
        @Volatile var allowExit = false

        override fun runOpMode() {
            entered.countDown()
            while (!allowExit) {
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    // Intentionally ignore the simulator's cooperative stop signals.
                }
            }
        }
    }

    @TeleOp(name = "Throwing init")
    class ThrowingInitOpMode : OpMode() {
        var stopCount = 0
        override fun init() = throw IllegalStateException("bad init")
        override fun loop() = Unit
        override fun stop() {
            stopCount++
        }
    }

    class UnannotatedOpMode : OpMode() {
        override fun init() = Unit
        override fun loop() = Unit
    }

    @Autonomous(name = "Recording auto")
    class RecordingAutoOpMode : OpMode() {
        override fun init() = Unit
        override fun loop() = Unit
    }
}
