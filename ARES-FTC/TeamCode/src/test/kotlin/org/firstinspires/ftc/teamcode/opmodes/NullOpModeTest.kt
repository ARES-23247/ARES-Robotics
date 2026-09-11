package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.util.RobotClock
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*

class NullOpModeTest {
    @After fun restoreClock() = RobotClock.useSystemTime()

    private fun mode() = spy(NullOpMode()).apply {
        telemetry = mock(org.firstinspires.ftc.robotcore.external.Telemetry::class.java)
        hardwareMap = mock(com.qualcomm.robotcore.hardware.HardwareMap::class.java)
        doNothing().`when`(this).waitForStart()
        doNothing().`when`(this).idle()
    }

    @Test fun `telemetry is immediate throttled and recovers from clock discontinuity`() {
        val mode = mode()
        val times = listOf(0L, 249L, 250L, 10L, 10L, 260L, Long.MIN_VALUE, Long.MAX_VALUE)
        var index = 0
        doAnswer {
            if (index < times.size) { RobotClock.useMockTime(times[index++]); true } else false
        }.`when`(mode).opModeIsActive()
        mode.runOpMode()
        verify(mode.telemetry, times(7)).update()
        verify(mode, times(times.size)).idle()
        verifyNoInteractions(mode.hardwareMap)
    }

    @Test fun `inactive mode reports only observed initialization without claiming a hardware cause`() {
        val mode = mode()
        doReturn(false).`when`(mode).opModeIsActive()
        mode.runOpMode()
        verify(mode.telemetry).update()
        verify(mode, never()).idle()
        verifyNoInteractions(mode.hardwareMap)
        val values = mockingDetails(mode.telemetry).invocations.filter { it.method.name == "addData" }
        assertTrue(values.any { it.arguments[0] == "Status" })
        assertFalse(values.any { it.arguments.any { value -> value.toString().contains("Case A is true") } })
    }
}
