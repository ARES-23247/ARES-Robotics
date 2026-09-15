package com.areslib.ftc.hardware

import com.areslib.input.ControllerState
import com.qualcomm.robotcore.hardware.Gamepad
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.*

class FtcGamepadAdapterAllocationTest {
    @Volatile private var escaped: ControllerState? = null

    @Test fun `polling allocates only the required immutable snapshot`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!; bean.isThreadAllocatedMemoryEnabled = true
        val gamepad = Gamepad(); val adapter = FtcGamepadAdapter(gamepad)
        gamepad.left_stick_x = 0.5f; gamepad.left_stick_y = -0.25f
        gamepad.right_stick_x = -0.25f; gamepad.right_stick_y = 0.75f
        repeat(50_000) { escaped = ControllerState(leftStickX = it.toDouble()); escaped = adapter.getControllerState() }
        val thread = Thread.currentThread().id; var consecutive = 0; var referenceBytes = 0L; var adapterBytes = 0L
        for (window in 0 until 10) {
            val beforeReference = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { escaped = ControllerState(leftStickX = it.toDouble()) }
            referenceBytes = bean.getThreadAllocatedBytes(thread) - beforeReference
            val beforeAdapter = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { escaped = adapter.getControllerState() }
            adapterBytes = bean.getThreadAllocatedBytes(thread) - beforeAdapter
            consecutive = if (referenceBytes > 0 && adapterBytes == referenceBytes) consecutive + 1 else 0
            if (consecutive == 2) break
        }
        assertEquals(2, consecutive, "snapshot $referenceBytes bytes, adapter $adapterBytes bytes")
        assertTrue(escaped!!.leftStickX > 0.0)
        println("10,000 polls: snapshot=$referenceBytes bytes, adapter=$adapterBytes bytes")
    }
}
