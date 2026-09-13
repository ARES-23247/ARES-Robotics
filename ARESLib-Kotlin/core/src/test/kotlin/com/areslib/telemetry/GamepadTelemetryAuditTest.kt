package com.areslib.telemetry

import com.areslib.state.RobotState
import org.junit.jupiter.api.Test
import kotlin.test.*

class GamepadTelemetryAuditTest {
    private val axes = listOf("leftStickX" to "LeftStick_X", "leftStickY" to "LeftStick_Y", "rightStickX" to "RightStick_X", "rightStickY" to "RightStick_Y", "leftTrigger" to "LeftTrigger", "rightTrigger" to "RightTrigger")
    private val buttons = listOf("a" to "A", "b" to "B", "x" to "X", "y" to "Y", "dpadUp" to "DpadUp", "dpadDown" to "DpadDown", "dpadLeft" to "DpadLeft", "dpadRight" to "DpadRight", "leftBumper" to "LeftBumper", "rightBumper" to "RightBumper", "leftStickButton" to "LeftStickButton", "rightStickButton" to "RightStickButton", "start" to "Start", "back" to "Back", "touchpad" to "Touchpad", "share" to "Share", "options" to "Options", "c" to "C", "z" to "Z", "m1" to "M1", "m2" to "M2", "m3" to "M3", "m4" to "M4") + (1..12).map { "f$it" to "F$it" }
    private fun filled(): GamepadState = GamepadState().apply {
        axes.forEachIndexed { index, (name, _) -> field(name).setFloat(this, (index + 1) / 8f) }
        buttons.forEach { (name, _) -> field(name).setBoolean(this, true) }
    }
    private fun field(name: String) = GamepadState::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun verify(out: AuditRecordingTelemetry, prefix: String, active: Boolean) {
        axes.forEachIndexed { index, (_, suffix) -> assertEquals(if (active) ((index + 1) / 8f).toDouble() else 0.0, out.numbers["$prefix/$suffix"], suffix) }
        buttons.forEach { (_, suffix) -> assertEquals(active, out.booleans["$prefix/$suffix"], suffix) }
    }
    @Test fun `shared frame publishes every input and neutralizes both disconnected gamepads`() {
        val out = AuditRecordingTelemetry(); val publisher = ARESNetworkStatePublisher(out)
        publisher.publish(RobotState(), filled(), filled(), flush = false)
        verify(out, "Gamepad1", true); verify(out, "Gamepad2", true)
        publisher.publish(RobotState(), flush = false)
        verify(out, "Gamepad1", false); verify(out, "Gamepad2", false)
    }
    @Test fun `public gamepad helper includes every snapshot field under the supplied prefix`() {
        val out = AuditRecordingTelemetry()
        out.logGamepad("Operator", filled()); verify(out, "Operator", true)
        out.logGamepad("Driver", GamepadState()); verify(out, "Driver", false)
        verify(out, "Operator", true)
    }
    @Test fun `snapshot copying and reset cover every declared input without aliasing`() {
        assertEquals((axes + buttons).map { it.first }.toSet(), GamepadState::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        val source = filled(); val copy = GamepadState(); copy.copyFrom(source); source.reset()
        for ((name, _) in axes + buttons) {
            val member = field(name)
            assertEquals(member.get(filled()), member.get(copy), name)
            assertEquals(member.get(GamepadState()), member.get(source), name)
        }
        copy.reset()
        for ((name, _) in axes + buttons) assertEquals(field(name).get(GamepadState()), field(name).get(copy), name)
    }

    @Test fun `repeated helper calls reuse topic strings for alternating gamepads`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val gamepad = GamepadState()
        val sink = object : ITelemetry {
            @Volatile var lastKey = ""
            var writes = 0
            override fun putNumber(key: String, value: Double) { lastKey = key; writes++ }
            override fun putBoolean(key: String, value: Boolean) { lastKey = key; writes++ }
            override fun putString(key: String, value: String) = error("unexpected string")
            override fun putDoubleArray(key: String, value: DoubleArray) = error("unexpected array")
            override fun getNumber(key: String, defaultValue: Double) = defaultValue
            override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
            override fun getString(key: String, defaultValue: String) = defaultValue
        }
        fun window(): Long {
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { sink.logGamepad("Driver", gamepad); sink.logGamepad("Operator", gamepad) }
            return bean.getThreadAllocatedBytes(thread) - before
        }
        repeat(5) { window() }
        repeat(2) {
            val allocated = window()
            println("Gamepad helper: $allocated bytes / 20000 calls")
            assertEquals(0L, allocated)
        }
        assertEquals(7 * 20_000 * 41, sink.writes)
    }

    @Test fun `custom prefix cache remains bounded while recently used mappings stay valid`() {
        repeat(256) { GamepadTelemetry.cachedTopics("Bound-$it") }
        val recent = GamepadTelemetry.cachedTopics("Bound-255")
        assertSame(recent, GamepadTelemetry.cachedTopics("Bound-255"))
        assertTrue(recent.all { it.startsWith("Bound-255/") })
        val local = GamepadTelemetry.javaClass.getDeclaredField("helperTopics").apply { isAccessible = true }.get(GamepadTelemetry) as ThreadLocal<*>
        assertEquals(16, (local.get() as Map<*, *>).size)
        assertTrue(GamepadTelemetry.cachedTopics("Bound-0").all { it.startsWith("Bound-0/") })
        assertEquals(16, (local.get() as Map<*, *>).size)
    }
}
