package com.areslib.telemetry

import com.areslib.util.RobotClock
import kotlin.math.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GamepadSamplingAuditTest {
    @AfterEach fun restoreClock() = RobotClock.useSystemTime()

    @Test fun `analog callbacks observe all sampled controls from the current frame`() {
        val pad = AresGamepad()
        val state = GamepadState(leftStickX = 0.25f, rightStickY = 0.5f, rightTrigger = 0.75f, rightBumper = true)
        var calls = 0
        pad.leftStick.bindStick { _, _ ->
            calls++
            assertEquals(0.5, pad.rightStick.shapedY, 0.0)
            assertEquals(0.75, pad.rightTrigger.shapedValue, 0.0)
            assertTrue(pad.rightBumper.isPressed)
        }
        pad.update(state)
        assertEquals(1, calls)
    }

    @Test fun `consumer mutation of the caller buffer cannot split an input frame`() {
        val pad = AresGamepad()
        val state = GamepadState(rightStickX = 0.5f, rightTrigger = 0.75f, a = true)
        pad.leftStick.bindStick { _, _ -> state.rightStickX = -1f; state.rightTrigger = 0f; state.a = false }
        pad.update(state)
        assertEquals(0.5, pad.rightStick.shapedX, 0.0)
        assertEquals(0.75, pad.rightTrigger.shapedValue, 0.0)
        assertTrue(pad.a.isPressed)
        pad.update(state)
        assertEquals(-1.0, pad.rightStick.shapedX, 0.0)
        assertFalse(pad.a.isPressed)
    }

    @Test fun `button callbacks observe simultaneous later button transitions`() {
        val pad = AresGamepad()
        val observations = mutableListOf<Boolean>()
        pad.a.onPress { observations += pad.f12.isPressed }
        pad.a.onRelease { observations += pad.f12.isPressed }
        val state = GamepadState(a = true, f12 = true)
        pad.update(state)
        state.a = false; state.f12 = false
        pad.update(state)
        assertEquals(listOf(true, false), observations)
    }

    @Test fun `all digital controls quarantine held init input until release and repress`() {
        val fields = GamepadState::class.java.declaredFields.filter { it.type == Boolean::class.javaPrimitiveType }
        assertEquals(35, fields.size)
        val pad = AresGamepad()
        val state = GamepadState()
        val presses = IntArray(fields.size); val releases = IntArray(fields.size); val levels = IntArray(fields.size)
        val buttons = fields.mapIndexed { index, field ->
            field.isAccessible = true
            val getter = "get" + field.name.replaceFirstChar { it.uppercaseChar() }
            (AresGamepad::class.java.getMethod(getter).invoke(pad) as AresGamepad.BindableButton).also {
                it.onPress { presses[index]++ }; it.onRelease { releases[index]++ }; it.whilePressed { levels[index]++ }
            }
        }
        fun setAll(value: Boolean) = fields.forEach { it.setBoolean(state, value) }
        setAll(true); pad.prime(state); pad.update(state)
        assertTrue(buttons.all { it.isPressed })
        assertTrue(presses.all { it == 0 } && levels.all { it == 0 })
        setAll(false); pad.update(state)
        assertTrue(releases.all { it == 0 })
        setAll(true); pad.update(state); pad.update(state)
        assertTrue(presses.all { it == 1 } && levels.all { it == 2 })
        setAll(false); pad.update(state)
        assertTrue(releases.all { it == 1 } && buttons.none { it.isPressed })
    }

    private fun slewPad() = AresGamepad().apply {
        leftTrigger.withSlewRateLimit(1.0)
        leftStick.withSlewRateLimit(1.0)
    }
    private fun assertSlew(pad: AresGamepad, expected: Double) {
        assertEquals(expected, pad.leftTrigger.shapedValue, 1e-12)
        assertEquals(expected, hypot(pad.leftStick.shapedX, pad.leftStick.shapedY), 1e-12)
    }
    private fun fullInput() = GamepadState(leftTrigger = 1f, leftStickX = 1f)

    @Test fun `minimum signed timestamp is a real sample and not a repeated first update`() {
        RobotClock.useMockTime(Long.MIN_VALUE)
        val pad = slewPad(); val state = fullInput()
        pad.update(state); assertSlew(pad, 0.02)
        pad.update(state); assertSlew(pad, 0.02)
    }

    @Test fun `large forward time advance uses the bounded slew interval without overflow`() {
        RobotClock.useMockTime(Long.MIN_VALUE + 1)
        val pad = slewPad(); val state = fullInput()
        pad.update(state)
        RobotClock.useMockTime(Long.MAX_VALUE)
        pad.update(state); assertSlew(pad, 0.22)
    }

    @Test fun `large backward time jump never becomes a positive slew interval`() {
        RobotClock.useMockTime(Long.MAX_VALUE)
        val pad = slewPad(); val state = fullInput()
        pad.update(state)
        RobotClock.useMockTime(Long.MIN_VALUE + 2)
        pad.update(state); assertSlew(pad, 0.02)
    }

    @Test fun `invalid analog feedback neutralizes active slew and recovery starts from neutral`() {
        RobotClock.useMockTime(0)
        val pad = slewPad(); val state = fullInput()
        pad.prime(state)
        state.leftTrigger = Float.NaN; state.leftStickX = Float.POSITIVE_INFINITY
        RobotClock.useMockTime(20); pad.update(state); assertSlew(pad, 0.0)
        state.leftTrigger = 1f; state.leftStickX = 1f
        RobotClock.useMockTime(40); pad.update(state); assertSlew(pad, 0.02)
    }

    @Test fun `one invalid stick component neutralizes the entire requested vector`() {
        val pad = AresGamepad()
        pad.update(GamepadState(leftStickX = Float.NaN, leftStickY = 0.75f))
        assertEquals(0.0, hypot(pad.leftStick.shapedX, pad.leftStick.shapedY), 0.0)
        assertEquals(0f, pad.leftStick.y)
    }

    @Test fun `near one deadband preserves full scale clipped diagonal magnitude`() {
        val pad = AresGamepad()
        pad.leftStick.withDeadband(Math.nextDown(1.0)).withExponentialCurve(5.0)
        pad.update(GamepadState(leftStickX = 1f, leftStickY = 1f))
        assertEquals(1.0, hypot(pad.leftStick.shapedX, pad.leftStick.shapedY), 1e-12)
        assertEquals(pad.leftStick.shapedX, pad.leftStick.shapedY, 0.0)
    }

    @Test fun `radial and scalar shaping match independent polar and signed magnitude oracles`() {
        val samples = floatArrayOf(-2f, -1f, -0.8f, -0.2f, -Float.MIN_VALUE, 0f, Float.MIN_VALUE, 0.07f, 0.5f, 1f, 2f)
        for (threshold in doubleArrayOf(0.0, 0.08, 0.7, Math.nextDown(1.0))) {
            for (exponent in doubleArrayOf(1.0, 1.5, 2.0, 5.0)) {
                val pad = AresGamepad()
                pad.leftStick.withDeadband(threshold).withExponentialCurve(exponent)
                pad.rightTrigger.withDeadband(threshold).withExponentialCurve(exponent)
                for (x in samples) for (y in samples) {
                    pad.update(GamepadState(leftStickX = x, leftStickY = y, rightTrigger = x))
                    val rawX = x.toDouble().coerceIn(-1.0, 1.0); val rawY = y.toDouble().coerceIn(-1.0, 1.0)
                    val radius = hypot(rawX, rawY).coerceAtMost(1.0)
                    val shapedRadius = ((radius - threshold) / (1.0 - threshold)).coerceAtLeast(0.0).pow(exponent)
                    val angle = atan2(rawY, rawX)
                    val label = "x=$x y=$y deadband=$threshold exponent=$exponent"
                    assertEquals(cos(angle) * shapedRadius, pad.leftStick.shapedX, 1e-12, label)
                    assertEquals(sin(angle) * shapedRadius, pad.leftStick.shapedY, 1e-12, label)
                    val scalar = sign(rawX) * ((abs(rawX) - threshold) / (1.0 - threshold)).coerceAtLeast(0.0).pow(exponent)
                    assertEquals(scalar, pad.rightTrigger.shapedValue, 1e-12, label)
                    assertTrue(hypot(pad.leftStick.shapedX, pad.leftStick.shapedY) <= 1.0 + 1e-12, label)
                }
            }
        }
    }

    @Test fun `changing directions remains within the radial slew distance and approaches the target`() {
        val pad = AresGamepad(); val state = GamepadState()
        pad.leftStick.withSlewRateLimit(2.0)
        RobotClock.useMockTime(0); pad.prime(state)
        var previousX = 0.0; var previousY = 0.0
        repeat(200) { index ->
            state.leftStickX = cos(index * 0.19).toFloat(); state.leftStickY = sin(index * 0.19).toFloat()
            RobotClock.useMockTime((index + 1L) * 20L); pad.update(state)
            val dx = pad.leftStick.shapedX - previousX; val dy = pad.leftStick.shapedY - previousY
            assertTrue(hypot(dx, dy) <= 0.04 + 1e-12)
            assertTrue(hypot(pad.leftStick.shapedX, pad.leftStick.shapedY) <= 1.0 + 1e-12)
            val targetX = pad.leftStick.x.toDouble(); val targetY = pad.leftStick.y.toDouble()
            assertTrue(dx * (targetX - previousX) + dy * (targetY - previousY) >= -1e-10)
            previousX = pad.leftStick.shapedX; previousY = pad.leftStick.shapedY
        }
    }
}
