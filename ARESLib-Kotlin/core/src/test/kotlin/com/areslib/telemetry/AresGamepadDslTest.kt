package com.areslib.telemetry

import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.math.hypot

class AresGamepadDslTest {

    private lateinit var gamepad: AresGamepad
    private lateinit var state: GamepadState

    @BeforeEach
    fun setUp() {
        RobotClock.useMockTime(0L)
        gamepad = AresGamepad()
        state = GamepadState()
    }

    @AfterEach
    fun tearDown() {
        RobotClock.useSystemTime()
    }

    @Test
    fun testButtonPressAndReleaseBindings() {
        var pressCount = 0
        var releaseCount = 0

        gamepad.a.onPress("Test Action") {
            pressCount++
        }
        gamepad.a.onRelease("Release Action") {
            releaseCount++
        }

        // Initially not pressed
        state.a = false
        gamepad.update(state)
        assertEquals(0, pressCount)
        assertEquals(0, releaseCount)

        // Pressed
        state.a = true
        gamepad.update(state)
        assertEquals(1, pressCount)
        assertEquals(0, releaseCount)

        // Held (no new press)
        gamepad.update(state)
        assertEquals(1, pressCount)
        assertEquals(0, releaseCount)

        // Released
        state.a = false
        gamepad.update(state)
        assertEquals(1, pressCount)
        assertEquals(1, releaseCount)
    }

    @Test
    fun testButtonToggleBinding() {
        var activeState = false

        gamepad.x.toggle("Toggle Shooter", currentState = { activeState }) { isEnabled ->
            activeState = isEnabled
        }

        state.x = true
        gamepad.update(state)
        assertTrue(activeState)

        state.x = false
        gamepad.update(state)
        assertTrue(activeState)

        state.x = true
        gamepad.update(state)
        assertFalse(activeState)
    }

    @Test
    fun `toggle derives every request from Redux-backed state instead of a hidden latch`() {
        var authoritativeState = false
        val requests = mutableListOf<Boolean>()
        gamepad.x.toggle("Toggle guarded mechanism", currentState = { authoritativeState }) { requested ->
            requests += requested
            // Deliberately reject the request: authoritative Redux state stays false.
        }

        state.x = true
        gamepad.update(state)
        state.x = false
        gamepad.update(state)
        state.x = true
        gamepad.update(state)

        assertEquals(listOf(true, true), requests)
        assertFalse(authoritativeState)
    }

    @Test
    fun testAxisDeadbandShaping() {
        gamepad.leftTrigger.withDeadband(0.10)

        // Within deadband -> zero
        state.leftTrigger = 0.05f
        gamepad.update(state)
        assertEquals(0.0, gamepad.leftTrigger.shapedValue, 1e-6)

        // Outside deadband -> linearly rescaled from 0 to 1
        state.leftTrigger = 0.55f
        gamepad.update(state)
        // (0.55 - 0.10) / (1.0 - 0.10) = 0.45 / 0.90 = 0.50
        assertEquals(0.50, gamepad.leftTrigger.shapedValue, 1e-4)

        // Full deflection -> 1.0
        state.leftTrigger = 1.0f
        gamepad.update(state)
        assertEquals(1.0, gamepad.leftTrigger.shapedValue, 1e-6)
    }

    @Test
    fun testAxisExponentialCurveShaping() {
        gamepad.rightStickX
            .withDeadband(0.0)
            .withExponentialCurve(2.0)

        state.rightStickX = 0.5f
        gamepad.update(state)
        assertEquals(0.25, gamepad.rightStickX.shapedValue, 1e-4)

        state.rightStickX = -0.5f
        gamepad.update(state)
        assertEquals(-0.25, gamepad.rightStickX.shapedValue, 1e-4)
    }

    @Test
    fun testStickRadialDeadband() {
        gamepad.leftStick.withDeadband(0.20)

        // Vector magnitude < 0.20 -> zeros both components
        state.leftStickX = 0.10f
        state.leftStickY = 0.10f
        // magnitude = sqrt(0.01 + 0.01) = ~0.1414 < 0.20
        gamepad.update(state)
        assertEquals(0.0, gamepad.leftStick.shapedX, 1e-6)
        assertEquals(0.0, gamepad.leftStick.shapedY, 1e-6)

        // Vector magnitude = 0.60
        state.leftStickX = 0.60f
        state.leftStickY = 0.0f
        gamepad.update(state)
        // scaled = (0.60 - 0.20) / (1.0 - 0.20) = 0.40 / 0.80 = 0.50
        assertEquals(0.50, gamepad.leftStick.shapedX, 1e-4)
        assertEquals(0.0, gamepad.leftStick.shapedY, 1e-4)
    }

    @Test
    fun `prime samples analog controls without invoking bound consumers`() {
        var axisCalls = 0
        var stickCalls = 0
        gamepad.leftTrigger.bindAxis { axisCalls++ }
        gamepad.leftStick.bindStick { _, _ -> stickCalls++ }
        state.leftTrigger = 1.0f
        state.leftStickX = 0.5f

        gamepad.prime(state)

        assertEquals(0, axisCalls)
        assertEquals(0, stickCalls)
        assertEquals(1.0, gamepad.leftTrigger.shapedValue, 1e-9)
        assertEquals(0.5, gamepad.leftStick.shapedX, 1e-9)

        RobotClock.useMockTime(20L)
        gamepad.update(state)
        assertEquals(1, axisCalls)
        assertEquals(1, stickCalls)
    }

    @Test
    fun `slew shaping starts safely and advances only with robot time`() {
        gamepad.leftTrigger.withSlewRateLimit(1.0)
        state.leftTrigger = 1.0f

        gamepad.update(state)
        assertEquals(0.02, gamepad.leftTrigger.shapedValue, 1e-9)

        gamepad.update(state)
        assertEquals(0.02, gamepad.leftTrigger.shapedValue, 1e-9)

        RobotClock.useMockTime(100L)
        gamepad.update(state)
        assertEquals(0.12, gamepad.leftTrigger.shapedValue, 1e-9)
    }

    @Test
    fun `stick slew limit is radial and preserves requested direction`() {
        gamepad.leftStick.withSlewRateLimit(1.0)
        state.leftStickX = 1.0f
        state.leftStickY = 0.5f

        gamepad.update(state)

        assertEquals(0.02, hypot(gamepad.leftStick.shapedX, gamepad.leftStick.shapedY), 1e-9)
        assertEquals(2.0, gamepad.leftStick.shapedX / gamepad.leftStick.shapedY, 1e-9)
    }

    @Test
    fun `invalid shaping configuration is rejected and nonfinite input fails neutral`() {
        assertThrows(IllegalArgumentException::class.java) { gamepad.leftTrigger.withDeadband(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { gamepad.leftTrigger.withDeadband(1.0) }
        assertThrows(IllegalArgumentException::class.java) { gamepad.leftTrigger.withExponentialCurve(0.5) }
        assertThrows(IllegalArgumentException::class.java) { gamepad.leftTrigger.withSlewRateLimit(Double.POSITIVE_INFINITY) }

        state.leftTrigger = Float.NaN
        state.leftStickX = Float.POSITIVE_INFINITY
        gamepad.update(state)
        assertEquals(0.0, gamepad.leftTrigger.shapedValue, 0.0)
        assertEquals(0.0, gamepad.leftStick.shapedX, 0.0)
    }

    @Test
    fun `custom analog bindings use the one primitive source contract`() {
        val axis = AresGamepad.BindableAxis(GamepadAxisSource(GamepadState::rightTrigger))
        val stick = AresGamepad.BindableStick(
            GamepadAxisSource(GamepadState::leftStickX),
            GamepadAxisSource(GamepadState::leftStickY),
        )
        state.rightTrigger = 0.6f
        state.leftStickX = 0.25f
        state.leftStickY = -0.5f

        axis.updateValue(state, timestampMs = 20L, notifyConsumer = false, resetSlew = true)
        stick.updateValue(state, timestampMs = 20L, notifyConsumer = false, resetSlew = true)

        assertEquals(0.6, axis.shapedValue, 1e-6)
        assertEquals(0.25, stick.shapedX, 1e-6)
        assertEquals(-0.5, stick.shapedY, 1e-6)
    }

    @Test
    fun `shaped axis and stick callbacks remain allocation free after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true

        val sink = DoubleArray(2)
        gamepad.leftTrigger
            .withDeadband(0.05)
            .withExponentialCurve(2.0)
            .bindAxis { sink[0] = it }
        gamepad.leftStick
            .withDeadband(0.05)
            .withExponentialCurve(2.0)
            .bindStick { x, y -> sink[0] = x; sink[1] = y }
        state.leftTrigger = 0.75f
        state.leftStickX = 0.5f
        state.leftStickY = -0.25f
        repeat(2_000) { gamepad.update(state) }

        val threadId = Thread.currentThread().id
        // HotSpot may attribute a fixed amount of JIT/OSR bookkeeping to the measured thread on
        // some Linux builds. Compare differently sized windows: real per-update allocation grows
        // about 10x, while fixed compiler bookkeeping does not.
        val shortBefore = bean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { gamepad.update(state) }
        val shortWindowBytes = bean.getThreadAllocatedBytes(threadId) - shortBefore
        val longBefore = bean.getThreadAllocatedBytes(threadId)
        repeat(100_000) { gamepad.update(state) }
        val longWindowBytes = bean.getThreadAllocatedBytes(threadId) - longBefore

        assertTrue(
            longWindowBytes <= shortWindowBytes * 2L + 1_024L,
            "Analog shaping must have zero per-update allocation growth " +
                "(10k=$shortWindowBytes, 100k=$longWindowBytes)",
        )
        assertTrue(sink[0].isFinite() && sink[1].isFinite())
    }
}
