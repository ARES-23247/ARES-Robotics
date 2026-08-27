package com.areslib.frc.sim.io

import com.areslib.frc.Dyn4jSimulation
import com.areslib.frc.marvin.FlywheelState
import com.areslib.frc.marvin.MarvinState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SimulatedIOTest {

    @Test
    fun testAllSimulatedIOs() {
        val sim = Dyn4jSimulation(seed = 42L)

        // 1. Cowl
        val cowl = SimulatedCowlIO(sim)
        cowl.setTargetAngle(10.0)
        assertTrue(sim.simCowlVoltage != 0.0)
        cowl.setAppliedVoltage(5.0)
        assertEquals(5.0, sim.simCowlVoltage)
        assertEquals(sim.simCowlAngle / 32.0, cowl.angleRotations)
        assertEquals(1.0, cowl.currentAmps)

        // 2. Climber
        val climber = SimulatedClimberIO(sim)
        climber.setTargetPositionRotations(0.1)
        assertTrue(sim.simClimberVoltage != 0.0)
        climber.setAppliedVoltage(-6.0)
        assertEquals(-6.0, sim.simClimberVoltage)
        assertEquals(sim.simClimberPositionRotations, climber.positionRotations)
        assertEquals(1.5, climber.currentAmps)

        // 3. Intake
        val intake = SimulatedIntakeIO(sim)
        intake.setPivotAngle(45.0)
        assertTrue(sim.simIntakePivotVoltage != 0.0)
        intake.setPivotVoltage(8.0)
        assertEquals(8.0, sim.simIntakePivotVoltage)
        intake.setRollerVoltage(10.0)
        assertEquals(10.0, sim.simIntakeRollerVoltage)
        assertEquals(sim.intakePivotSim.angleDegrees, intake.pivotAngleDegrees)
        assertEquals(2.4, intake.pivotCurrentAmps, 1e-6)
        assertEquals(2.0, intake.rollerCurrentAmps, 1e-6)

        // 4. Feeder
        val feeder = SimulatedFeederIO(sim)
        feeder.setAppliedVoltage(4.0)
        assertEquals(4.0, sim.simFeederVoltage)
        assertFalse(feeder.isBeamBroken)
        assertFalse(feeder.pieceDetectionValid)
        assertEquals(0.4, feeder.currentAmps, 1e-6)

        // 5. Floor
        val floor = SimulatedFloorIO(sim)
        floor.setAppliedVoltage(3.0)
        assertEquals(3.0, sim.simFloorVoltage)
        assertEquals(0.45, floor.currentAmps, 1e-6)
        assertEquals(sim.simFloorVelocityRps, floor.velocityRps)

        // 6. Flywheel
        val flywheel = SimulatedFlywheelIO(sim)
        flywheel.setVelocityRpm(4000.0)
        assertTrue(sim.simFlywheelVoltage != 0.0)
        flywheel.setAppliedVoltage(9.0)
        assertEquals(9.0, sim.simFlywheelVoltage)
        assertEquals(sim.flywheelSim.velocityRpm, flywheel.velocityRpm)
        assertTrue(flywheel.currentAmps >= 0.0)
        assertEquals(30.0, flywheel.tempCelsius)
        assertTrue(flywheel.velocityValid)
    }

    @Test
    fun feederDetectorIsOnlyValidWhenExplicitlyConfigured() {
        val unconfiguredSim = Dyn4jSimulation(seed = 42L)
        unconfiguredSim.simFeederPieceDetected = true
        val absentDetector = SimulatedFeederIO(unconfiguredSim)
        assertFalse(absentDetector.pieceDetectionValid)
        assertFalse(absentDetector.isBeamBroken)

        val configuredSim = Dyn4jSimulation(seed = 42L, feederPieceDetectorConfigured = true)
        configuredSim.simFeederPieceDetected = true
        val configuredDetector = SimulatedFeederIO(configuredSim, detectorConfigured = true)
        assertTrue(configuredDetector.pieceDetectionValid)
        assertTrue(configuredDetector.isBeamBroken)
    }

    @Test
    fun `model matched flywheel control reaches the production readiness band`() {
        val sim = Dyn4jSimulation(seed = 42L)
        val flywheel = SimulatedFlywheelIO(sim)
        val targetRpm = 4_000.0

        repeat(300) {
            flywheel.setVelocityRpm(targetRpm)
            sim.flywheelSim.update(sim.simFlywheelVoltage, 0.02)
        }

        assertEquals(targetRpm, flywheel.velocityRpm, 150.0)
        val state = MarvinState(
            flywheel = FlywheelState(
                velocityRpm = flywheel.velocityRpm,
                velocityValid = true,
                allMotorsAtTarget = true,
                targetVelocityRpm = targetRpm
            )
        )
        assertTrue(state.isFlywheelAtSpeed)
    }
}
