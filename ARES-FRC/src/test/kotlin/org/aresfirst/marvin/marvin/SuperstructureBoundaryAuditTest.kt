package org.aresfirst.marvin.marvin

import com.areslib.Store
import com.areslib.hardware.actuator.*
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SuperstructureBoundaryAuditTest {
    private class Outputs {
        val writes = linkedMapOf<String, Double>()
        val failures = mutableMapOf<String, RuntimeException>()
        @Suppress("UNCHECKED_CAST")
        fun <T> io(type: Class<T>, name: String): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            if (method.name.startsWith("set")) {
                val key = "$name.${method.name}"
                writes[key] = args!![0] as Double
                failures[key]?.let { throw it }
            }
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false // Detector/position feedback is unavailable in timer fixtures.
                java.lang.Double.TYPE -> 0.0
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        } as T
        fun subsystem() = MarvinSuperstructure(io(FlywheelIO::class.java, "flywheel"), io(CowlIO::class.java, "cowl"),
            io(IntakeIO::class.java, "intake"), io(FeederIO::class.java, "feeder"),
            io(FloorIO::class.java, "floor"), io(ClimberIO::class.java, "climber"))
    }
    private fun state(m: MarvinState = MarvinState()) = RobotState(superstructure = SuperstructureState(custom = m))
    private fun store() = Store(state(), MarvinReducer::reduce)

    @Test fun `inhibited stop attempts every output and preserves multiple failures`() {
        val names = listOf("flywheel.setAppliedVoltage", "cowl.setAppliedVoltage", "intake.setPivotVoltage",
            "intake.setRollerVoltage", "feeder.setAppliedVoltage", "floor.setAppliedVoltage", "climber.setAppliedVoltage")
        for (failed in names) {
            val outputs = Outputs()
            val failure = IllegalStateException(failed)
            outputs.failures[failed] = failure
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                outputs.subsystem().writeOutputs(state(MarvinState(mechanismSafetyInhibited = true)), 1.0)
            })
            assertEquals(names.toSet(), outputs.writes.keys)
            assertTrue(outputs.writes.values.all { it == 0.0 })
        }
        val outputs = Outputs()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        outputs.failures[names[0]] = first
        outputs.failures[names[6]] = second
        assertSame(first, assertThrows(IllegalStateException::class.java) {
            outputs.subsystem().writeOutputs(state(MarvinState(mechanismSafetyFaultLatched = true)), 1.0)
        })
        assertEquals(listOf(second), first.suppressed.toList())
    }

    @Test fun `clock rewind cancels slamtake instead of leaving its rollers running`() {
        val store = store()
        store.dispatch(StartSlamtake(1000L))
        Outputs().subsystem().readSensors(store, 999L)
        val result = store.state.superstructure.marvin
        assertFalse(result.slamtakeActive)
        assertEquals(0.0, result.intake.targetRollerVelocityRps)
        assertEquals(0.0, result.floor.targetVelocityRps)
    }

    @Test fun `forward elapsed overflow and missed finish deadline terminate on this sensor frame`() {
        for ((start, now) in listOf(Long.MIN_VALUE to Long.MAX_VALUE, 1000L to 2500L, 1000L to 9000L)) {
            val store = store()
            store.dispatch(StartSlamtake(start))
            Outputs().subsystem().readSensors(store, now)
            assertFalse(store.state.superstructure.marvin.slamtakeActive)
            assertEquals(0.0, store.state.superstructure.marvin.floor.targetVelocityRps)
        }
    }

    @Test fun `normal timer boundaries retract at five hundred and finish at fifteen hundred milliseconds`() {
        val store = store()
        val subsystem = Outputs().subsystem()
        store.dispatch(StartSlamtake(1000L))
        subsystem.readSensors(store, 1499L)
        assertEquals(1, store.state.superstructure.marvin.slamtakePhase)
        subsystem.readSensors(store, 1500L)
        assertEquals(2, store.state.superstructure.marvin.slamtakePhase)
        subsystem.readSensors(store, 2499L)
        assertTrue(store.state.superstructure.marvin.slamtakeActive)
        subsystem.readSensors(store, 2500L)
        assertFalse(store.state.superstructure.marvin.slamtakeActive)
    }

    @Test fun `unknown active timer phase cancels all slamtake roller targets`() {
        val store = Store(state(MarvinState(slamtakeActive = true, slamtakePhase = 7,
            slamtakeStartTimeMs = 1000L, intake = IntakeState(targetRollerVelocityRps = 10.0),
            floor = FloorState(targetVelocityRps = 10.0), feeder = FeederState(targetVelocityRps = 10.0))), MarvinReducer::reduce)
        Outputs().subsystem().readSensors(store, 1100L)
        val result = store.state.superstructure.marvin
        assertFalse(result.slamtakeActive)
        assertEquals(0.0, result.intake.targetRollerVelocityRps)
        assertEquals(0.0, result.floor.targetVelocityRps)
        assertEquals(0.0, result.feeder.targetVelocityRps)
    }

    @Test fun `inactive climber position target does not block intake after switching to neutral voltage`() {
        val outputs = Outputs()
        outputs.subsystem().writeOutputs(state(MarvinState(
            intake = IntakeState(pivotAngleValid = true, targetAngleDegrees = 90.0),
            climber = ClimberState(positionValid = true, positionRotations = 0.0,
                targetPositionRotations = 1.5, targetVoltage = 0.0, controlMode = ClimberControlMode.VOLTAGE))), 1.0)
        assertEquals(90.0, outputs.writes["intake.setPivotAngle"])
        assertEquals(0.0, outputs.writes["climber.setAppliedVoltage"])
    }

    @Test fun `nonfinite position targets request zero effort instead of a valid minimum endpoint`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val outputs = Outputs()
            outputs.subsystem().writeOutputs(state(MarvinState(
                cowl = CowlState(angleValid = true, angleRotations = 0.5, targetAngleRotations = invalid),
                intake = IntakeState(pivotAngleValid = true, targetAngleDegrees = invalid),
                climber = ClimberState(positionValid = true, targetPositionRotations = invalid,
                    controlMode = ClimberControlMode.POSITION_ROTATIONS))), 1.0)
            assertEquals(0.0, outputs.writes["cowl.setAppliedVoltage"])
            assertEquals(0.0, outputs.writes["intake.setPivotVoltage"])
            assertEquals(0.0, outputs.writes["climber.setAppliedVoltage"])
            assertFalse(outputs.writes.containsKey("cowl.setTargetAngle"))
            assertFalse(outputs.writes.containsKey("intake.setPivotAngle"))
            assertFalse(outputs.writes.containsKey("climber.setTargetPositionRotations"))
        }
    }
}
