package org.aresfirst.marvin.sim.io

import org.aresfirst.marvin.Dyn4jSimulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimulatedSafetyContractTest {

    @Test
    fun `position targets preserve geometry while effort scale bounds simulated voltage`() {
        val sim = Dyn4jSimulation(seed = 42L)
        val cowl = SimulatedCowlIO(sim)
        val intake = SimulatedIntakeIO(sim)
        val climber = SimulatedClimberIO(sim)

        cowl.setTargetAngle(rotations = 1.80, maxEffortScale = 0.25)
        intake.setPivotAngle(degrees = 90.0, maxEffortScale = 0.25)
        climber.setTargetPositionRotations(rotations = 2.0, maxEffortScale = 0.25)
        assertEquals(3.0, sim.simCowlVoltage, 1e-9)
        assertEquals(3.0, sim.simIntakePivotVoltage, 1e-9)
        assertEquals(3.0, sim.simClimberVoltage, 1e-9)

        cowl.setTargetAngle(rotations = 1.80, maxEffortScale = 0.0)
        intake.setPivotAngle(degrees = 90.0, maxEffortScale = -1.0)
        climber.setTargetPositionRotations(rotations = 2.0, maxEffortScale = 0.0)
        assertEquals(0.0, sim.simCowlVoltage, 1e-9)
        assertEquals(0.0, sim.simIntakePivotVoltage, 1e-9)
        assertEquals(0.0, sim.simClimberVoltage, 1e-9)

        cowl.setTargetAngle(rotations = 1.80, maxEffortScale = 2.0)
        intake.setPivotAngle(degrees = 90.0, maxEffortScale = 2.0)
        climber.setTargetPositionRotations(rotations = 2.0, maxEffortScale = 2.0)
        assertEquals(12.0, sim.simCowlVoltage, 1e-9)
        assertEquals(12.0, sim.simIntakePivotVoltage, 1e-9)
        assertEquals(12.0, sim.simClimberVoltage, 1e-9)
    }

    @Test
    fun `safe contract zeros every simulated mechanism effort`() {
        val sim = Dyn4jSimulation(seed = 42L)

        sim.flywheelIO.setAppliedVoltage(9.0)
        sim.cowlIO.setAppliedVoltage(-7.0)
        sim.intakeIO.setPivotVoltage(6.0)
        sim.intakeIO.setRollerVoltage(-5.0)
        sim.feederIO.setAppliedVoltage(4.0)
        sim.floorIO.setAppliedVoltage(-3.0)
        sim.climberIO.setAppliedVoltage(2.0)

        sim.flywheelIO.safe()
        sim.cowlIO.safe()
        sim.intakeIO.safe()
        sim.feederIO.safe()
        sim.floorIO.safe()
        sim.climberIO.safe()

        assertEquals(0.0, sim.simFlywheelVoltage, 1e-9)
        assertEquals(0.0, sim.simCowlVoltage, 1e-9)
        assertEquals(0.0, sim.simIntakePivotVoltage, 1e-9)
        assertEquals(0.0, sim.simIntakeRollerVoltage, 1e-9)
        assertEquals(0.0, sim.simFeederVoltage, 1e-9)
        assertEquals(0.0, sim.simFloorVoltage, 1e-9)
        assertEquals(0.0, sim.simClimberVoltage, 1e-9)
    }

    @Test
    fun `non-finite simulated commands fail closed and physical targets clamp`() {
        val sim = Dyn4jSimulation(seed = 42L)

        sim.cowlIO.setTargetAngle(Double.NaN, Double.NaN)
        sim.intakeIO.setPivotAngle(Double.POSITIVE_INFINITY, Double.NaN)
        sim.climberIO.setTargetPositionRotations(Double.NEGATIVE_INFINITY, Double.NaN)
        sim.flywheelIO.setVelocityRpm(Double.NaN, Double.NaN)
        sim.feederIO.setAppliedVoltage(Double.NaN)
        sim.floorIO.setAppliedVoltage(Double.POSITIVE_INFINITY)

        assertTrue(sim.simCowlVoltage.isFinite())
        assertEquals(0.0, sim.simIntakePivotVoltage, 1e-9)
        assertEquals(0.0, sim.simClimberVoltage, 1e-9)
        assertEquals(0.0, sim.simFlywheelVoltage, 1e-9)
        assertEquals(0.0, sim.simFeederVoltage, 1e-9)
        assertEquals(0.0, sim.simFloorVoltage, 1e-9)
    }
}
