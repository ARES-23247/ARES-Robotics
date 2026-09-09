package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.telemetry.TelemetryTopicConstants
import kotlin.test.*

class HealthSnapshotAuditTest {
    private fun health(vararg values: Pair<String, Double>) = ReplayFrame(1_000, linkedMapOf(*values)).toReplayHealthSnapshot()

    private fun liveHealth(frame: TelemetryFrame): ControllerHealthSnapshot {
        val tracker=ControllerHealthTracker()
        tracker.accept(frame,0,1)
        return tracker.snapshot(0,1).snapshot
    }

    @Test fun `documented battery alias resolves without a battery voltage substring`() {
        assertEquals(12.3, health("Battery/Voltage" to 12.3).batteryVoltage)
    }
    @Test fun `canonical values win independently of map insertion order`() {
        for (values in listOf(linkedMapOf("DSLog/BatteryVoltage" to 10.0, "Robot/BatteryVoltage" to 12.0),
            linkedMapOf("Robot/BatteryVoltage" to 12.0, "DSLog/BatteryVoltage" to 10.0))) {
            assertEquals(12.0, ReplayFrame(0, values).toReplayHealthSnapshot().batteryVoltage)
        }
    }
    @Test fun `unrelated topics containing health fragments are ignored`() {
        val h = health("Arm/LoopTimeMs" to 1.0, "Robot/BatteryVoltageSetpoint" to 15.0,
            "Custom/BrownoutCount" to 3.0, "Tests/LoopOverruns" to 4.0)
        assertNull(h.loopTimeMs); assertNull(h.batteryVoltage)
        assertNull(h.brownoutCount); assertNull(h.loopOverruns)
    }
    @Test fun `nonfinite loop and voltage values remain unknown`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val h = health("Robot/LoopTimeMs" to invalid, "Robot/BatteryVoltage" to invalid)
            assertNull(h.loopTimeMs); assertNull(h.batteryVoltage)
        }
    }
    @Test fun `negative and zero loop intervals cannot imply valid frequency`() {
        for (invalid in listOf(-1.0, 0.0, Double.MIN_VALUE)) assertNull(health("Robot/LoopTimeMs" to invalid).loopTimeMs)
        assertNull(health("Robot/BatteryVoltage" to -1.0).batteryVoltage)
        assertEquals(0.0, health("Robot/BatteryVoltage" to 0.0).batteryVoltage)
    }
    @Test fun `invalid counters do not become healthy zero or saturated integers`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1.5, Int.MAX_VALUE.toDouble() + 1.0)) {
            val h = health("Robot/BrownoutCount" to invalid, "Robot/LoopOverruns" to invalid)
            assertNull(h.brownoutCount); assertNull(h.loopOverruns)
        }
    }
    @Test fun `producer diagnostics and legacy counter names are recognized`() {
        val h = health("Diagnostics/Power/BrownoutCount" to 2.0, "Diagnostics/LoopOverruns" to 3.0)
        assertEquals(2, h.brownoutCount); assertEquals(3, h.loopOverruns)
    }
    @Test fun `invalid canonical value is not hidden by a valid lower priority alias`() {
        assertNull(health("Battery/Voltage" to 12.0, "Robot/BatteryVoltage" to Double.NaN).batteryVoltage)
    }
    @Test fun `runtime values use the same normalized keys in replay and live`() {
        val key = TelemetryTopicConstants.FTC_PHOTON_ACTIVE
        assertTrue(health("/$key" to 1.0).ftcRuntime.photonActive == true)
        assertTrue(liveHealth(TelemetryFrame(0,"live","/$key",1.0)).ftcRuntime.photonActive == true)
    }
    @Test fun `runtime flags outside boolean domain remain unknown`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.5, 2.0)) {
            val key = TelemetryTopicConstants.FTC_PHOTON_ACTIVE
            assertNull(health(key to invalid).ftcRuntime.photonActive)
            assertNull(liveHealth(TelemetryFrame(0,"live",key,invalid)).ftcRuntime.photonActive)
        }
    }
    @Test fun `selected runtime features with missing feedback remain unknown`() {
        val runtime = FtcRuntimeDashboardState(hubCommandTransport="ARES_PHOTON",limelightProxyConfigured=true).presentation()
        assertEquals("PHOTON SELECTED · STATUS UNKNOWN",runtime.transportLabel)
        assertEquals(FtcRuntimeTone.UNKNOWN,runtime.transportTone)
        assertEquals("LIMELIGHT PROXY SELECTED · STATUS UNKNOWN",runtime.proxyLabel)
        assertEquals(FtcRuntimeTone.UNKNOWN,runtime.proxyTone)
    }
    @Test fun `explicit runtime active inactive and disabled flags retain their meanings`() {
        val active=FtcRuntimeDashboardState("ARES_PHOTON",true,true,true).presentation()
        assertEquals(FtcRuntimeTone.HEALTHY,active.transportTone); assertEquals(FtcRuntimeTone.HEALTHY,active.proxyTone)
        val inactive=FtcRuntimeDashboardState("ARES_PHOTON",false,true,false).presentation()
        assertEquals(FtcRuntimeTone.WARNING,inactive.transportTone); assertEquals(FtcRuntimeTone.WARNING,inactive.proxyTone)
        val disabled=FtcRuntimeDashboardState("STANDARD_SDK",false,false,false).presentation()
        assertEquals("FTC SDK SELECTED",disabled.transportLabel); assertEquals("LIMELIGHT PROXY OFF",disabled.proxyLabel)
    }
    @Test fun `normalized duplicate source keys prefer exact canonical spelling`() {
        for (values in listOf(linkedMapOf("/Robot/BatteryVoltage" to 10.0,"Robot/BatteryVoltage" to 12.0),
            linkedMapOf("Robot/BatteryVoltage" to 12.0,"/Robot/BatteryVoltage" to 10.0))) {
            assertEquals(12.0,ReplayFrame(0,values).toReplayHealthSnapshot().batteryVoltage)
        }
    }
    @Test fun `missing health data stays absent`() {
        val h = health(); assertNull(h.loopTimeMs); assertNull(h.batteryVoltage)
        assertNull(h.brownoutCount); assertNull(h.loopOverruns)
    }
}
