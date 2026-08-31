package org.aresfirst.marvin.robot

import com.areslib.control.tuning.PIDFCoefficients
import com.areslib.control.tuning.SimpleFeedforwardCoeffs
import com.areslib.hardware.actuator.FlywheelIO
import org.aresfirst.marvin.hardware.FrcFlywheelTuningStatus
import com.areslib.state.MechanismTuningState
import com.areslib.state.RobotState
import com.areslib.state.SubsystemTuningState
import com.areslib.state.TuningState
import com.areslib.telemetry.ITelemetry
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FrcSysIdControllerTest {
    @Test
    fun `flywheel dynamic routine applies voltage and emits canonical sample`() {
        val telemetry = FakeTelemetry().apply { strings["SysId/Command"] = "START_FLYWHEEL_DYNAMIC" }
        val flywheel = FakeFlywheel(measuredRpm = 1200.0)
        val controller = FrcSysIdController(telemetry, flywheel)

        controller.update(
            1_000L,
            RobotState(),
            enabledForTuning = true,
            hardwareSafetyPermitted = true
        )

        assertEquals(6.0, flywheel.lastAppliedVoltage, 1e-9)
        assertEquals("DYNAMIC", telemetry.strings["SysId/Status"])
        assertArrayEquals(
            doubleArrayOf(1000.0, 6.0, 0.0, 1200.0 * 2.0 * Math.PI / 60.0, 0.0),
            telemetry.arrays["SysId/Data"],
            1e-9
        )
    }

    @Test
    fun `routine is rejected outside test mode`() {
        val telemetry = FakeTelemetry().apply { strings["SysId/Command"] = "START_FLYWHEEL_DYNAMIC" }
        val flywheel = FakeFlywheel(measuredRpm = 1000.0)

        FrcSysIdController(telemetry, flywheel).update(
            1_000L,
            RobotState(),
            enabledForTuning = false,
            hardwareSafetyPermitted = true
        )

        assertEquals(0.0, flywheel.lastAppliedVoltage, 1e-9)
        assertEquals("FRC_SYSID_REQUIRES_TEST_ENABLED", telemetry.strings["SysId/Error"])
        assertEquals("NONE", telemetry.strings["SysId/Status"])

        // Leaving the command asserted must not arm it later without a new edge.
        FrcSysIdController(telemetry, flywheel).also { controller ->
            controller.update(2_000L, RobotState(), enabledForTuning = false, hardwareSafetyPermitted = true)
            controller.update(2_020L, RobotState(), enabledForTuning = true, hardwareSafetyPermitted = true)
        }
        assertEquals(0.0, flywheel.lastAppliedVoltage, 1e-9)
    }

    @Test
    fun `identified gains are applied to flywheel controller`() {
        val telemetry = FakeTelemetry()
        val flywheel = FakeFlywheel(measuredRpm = 0.0)
        val gains = PIDFCoefficients(0.4, 0.01, 0.03, 0.0)
        val feedforward = SimpleFeedforwardCoeffs(0.2, 0.12, 0.01)
        val state = RobotState(
            tuning = TuningState(
                subsystem = SubsystemTuningState(
                    flywheel = MechanismTuningState(feedforward, gains)
                )
            )
        )

        FrcSysIdController(telemetry, flywheel).update(
            1_000L,
            state,
            enabledForTuning = true,
            hardwareSafetyPermitted = true
        )

        assertEquals(gains, flywheel.configuredGains)
        assertEquals(feedforward, flywheel.configuredFeedforward)
    }

    @Test
    fun `sysid refuses brownout and aborts an active routine on current derating`() {
        val telemetry = FakeTelemetry().apply { strings["SysId/Command"] = "START_FLYWHEEL_DYNAMIC" }
        val flywheel = FakeFlywheel(measuredRpm = 1_000.0)
        val controller = FrcSysIdController(telemetry, flywheel)

        controller.update(
            1_000L,
            RobotState(),
            enabledForTuning = true,
            hardwareSafetyPermitted = true,
            brownedOut = true
        )
        assertEquals(0.0, flywheel.lastAppliedVoltage, 1e-9)
        assertEquals("SYSID_REQUIRES_FULL_POWER", telemetry.strings["SysId/Error"])

        telemetry.strings["SysId/Command"] = "STOP"
        controller.update(1_020L, RobotState(), enabledForTuning = true, hardwareSafetyPermitted = true)
        telemetry.strings["SysId/Command"] = "START_FLYWHEEL_DYNAMIC"
        controller.update(1_040L, RobotState(), enabledForTuning = true, hardwareSafetyPermitted = true)
        assertEquals(6.0, flywheel.lastAppliedVoltage, 1e-9)

        controller.update(
            1_060L,
            RobotState(),
            enabledForTuning = true,
            hardwareSafetyPermitted = true,
            powerScale = 0.8
        )
        assertEquals(0.0, flywheel.lastAppliedVoltage, 1e-9)
        assertEquals("SYSID_POWER_DERATING_ABORT", telemetry.strings["SysId/Error"])
        assertEquals("NONE", telemetry.strings["SysId/Status"])
    }

    @Test
    fun `failed flywheel tuning is retried instead of being cached`() {
        val telemetry = FakeTelemetry()
        val flywheel = FakeFlywheel(measuredRpm = 0.0, failFirstTuning = true)
        val tuning = MechanismTuningState(
            SimpleFeedforwardCoeffs(0.2, 0.12, 0.01),
            PIDFCoefficients(0.4, 0.01, 0.03, 0.0)
        )
        val state = RobotState(tuning = TuningState(subsystem = SubsystemTuningState(flywheel = tuning)))
        val controller = FrcSysIdController(telemetry, flywheel)

        controller.update(1_000L, state, enabledForTuning = true, hardwareSafetyPermitted = true)
        assertEquals("FLYWHEEL_TUNING_APPLY_FAILED", telemetry.strings["SysId/Error"])
        controller.update(1_020L, state, enabledForTuning = true, hardwareSafetyPermitted = true)

        assertEquals(2, flywheel.tuningAttempts)
        assertEquals(true, flywheel.lastTuningApplySuccessful)
    }

    @Test
    fun `sysid command asserted during a tuning failure is consumed rather than armed later`() {
        val telemetry = FakeTelemetry().apply { strings["SysId/Command"] = "START_FLYWHEEL_DYNAMIC" }
        val flywheel = FakeFlywheel(measuredRpm = 1_000.0, failFirstTuning = true)
        val tuning = MechanismTuningState(
            SimpleFeedforwardCoeffs(0.2, 0.12, 0.01),
            PIDFCoefficients(0.4, 0.01, 0.03, 0.0)
        )
        val state = RobotState(tuning = TuningState(subsystem = SubsystemTuningState(flywheel = tuning)))
        val controller = FrcSysIdController(telemetry, flywheel)

        controller.update(1_000L, state, enabledForTuning = true, hardwareSafetyPermitted = true)
        controller.update(1_020L, state, enabledForTuning = true, hardwareSafetyPermitted = true)

        assertEquals(2, flywheel.tuningAttempts)
        assertEquals(0.0, flywheel.lastAppliedVoltage, 1e-9)
        assertEquals("NONE", telemetry.strings["SysId/Status"])
    }

    @Test
    fun `sysid start is rejected while mechanism hardware safety is inhibited`() {
        val telemetry = FakeTelemetry().apply { strings["SysId/Command"] = "START_FLYWHEEL_DYNAMIC" }
        val flywheel = FakeFlywheel(measuredRpm = 1_000.0)
        val controller = FrcSysIdController(telemetry, flywheel)

        controller.update(
            1_000L,
            RobotState(),
            enabledForTuning = true,
            hardwareSafetyPermitted = false
        )

        assertEquals(0.0, flywheel.lastAppliedVoltage, 1e-9)
        assertEquals("NONE", telemetry.strings["SysId/Status"])
        assertEquals("MECHANISM_HARDWARE_SAFETY_INHIBITED", telemetry.strings["SysId/Error"])

        // The rejected edge is consumed; merely clearing the latch cannot arm a stale command.
        controller.update(
            1_020L,
            RobotState(),
            enabledForTuning = true,
            hardwareSafetyPermitted = true
        )
        assertEquals(0.0, flywheel.lastAppliedVoltage, 1e-9)
    }

    @Test
    fun `active sysid stops immediately when mechanism safety latches mid run`() {
        val telemetry = FakeTelemetry().apply { strings["SysId/Command"] = "START_FLYWHEEL_DYNAMIC" }
        val flywheel = FakeFlywheel(measuredRpm = 1_000.0)
        val controller = FrcSysIdController(telemetry, flywheel)
        controller.update(
            1_000L,
            RobotState(),
            enabledForTuning = true,
            hardwareSafetyPermitted = true
        )
        assertEquals(6.0, flywheel.lastAppliedVoltage, 1e-9)

        controller.update(
            1_020L,
            RobotState(),
            enabledForTuning = true,
            hardwareSafetyPermitted = false
        )

        assertEquals(0.0, flywheel.lastAppliedVoltage, 1e-9)
        assertEquals("NONE", telemetry.strings["SysId/Status"])
        assertEquals("MECHANISM_HARDWARE_SAFETY_INHIBITED", telemetry.strings["SysId/Error"])
    }

    private class FakeFlywheel(
        private var measuredRpm: Double,
        private val failFirstTuning: Boolean = false
    ) : FlywheelIO, FrcFlywheelTuningStatus {
        var lastAppliedVoltage = 0.0
        var configuredGains: PIDFCoefficients? = null
        var configuredFeedforward: SimpleFeedforwardCoeffs? = null
        var tuningAttempts = 0
        override var lastTuningApplySuccessful = true

        override val velocityRpm: Double get() = measuredRpm
        override val velocityValid: Boolean = true
        override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) { measuredRpm = rpm }
        override fun setAppliedVoltage(volts: Double) { lastAppliedVoltage = volts }
        override fun configureVelocityController(gains: PIDFCoefficients, feedforward: SimpleFeedforwardCoeffs) {
            tuningAttempts++
            configuredGains = gains
            configuredFeedforward = feedforward
            lastTuningApplySuccessful = !failFirstTuning || tuningAttempts > 1
        }
    }

    private class FakeTelemetry : ITelemetry {
        val strings = mutableMapOf<String, String>()
        val arrays = mutableMapOf<String, DoubleArray>()
        override fun putNumber(key: String, value: Double) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putDoubleArray(key: String, value: DoubleArray) { arrays[key] = value.copyOf() }
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = strings[key] ?: defaultValue
    }
}
