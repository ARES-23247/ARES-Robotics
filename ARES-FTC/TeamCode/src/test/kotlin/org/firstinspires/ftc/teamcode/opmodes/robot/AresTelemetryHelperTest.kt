package org.firstinspires.ftc.teamcode.opmodes.robot

import org.junit.Assert.assertEquals
import org.junit.Test

class AresTelemetryHelperTest {
    @Test
    fun `low battery warning rounds to one decimal without locale-sensitive formatting`() {
        assertEquals("<font color='red'><b>11.4V (LOW)</b></font>", formatLowBatteryVoltage(11.41))
        assertEquals("<font color='red'><b>11.5V (LOW)</b></font>", formatLowBatteryVoltage(11.46))
    }
}
