package org.aresfirst.marvin.sim.io

import org.aresfirst.marvin.Dyn4jSimulation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.areslib.simulation.SimulationFaultCommand
import com.areslib.simulation.SimulationFaultKind
import com.areslib.simulation.SimulationFaultTimeline
import com.areslib.util.RobotClock

class SimulatedFeedbackBoundaryTest {
    @Test fun `every direct voltage boundary clamps finite values and neutralizes nonfinite values`() {
        Dyn4jSimulation().use { sim ->
            val outputs: List<Pair<(Double) -> Unit, () -> Double>> = listOf(
                sim.flywheelIO::setAppliedVoltage to { sim.simFlywheelVoltage },
                sim.cowlIO::setAppliedVoltage to { sim.simCowlVoltage },
                sim.climberIO::setAppliedVoltage to { sim.simClimberVoltage },
                sim.intakeIO::setPivotVoltage to { sim.simIntakePivotVoltage },
                sim.intakeIO::setRollerVoltage to { sim.simIntakeRollerVoltage },
                sim.feederIO::setAppliedVoltage to { sim.simFeederVoltage },
                sim.floorIO::setAppliedVoltage to { sim.simFloorVoltage },
            )
            val cases = listOf(Double.NEGATIVE_INFINITY to 0.0, -20.0 to -12.0, -3.5 to -3.5,
                0.0 to 0.0, 3.5 to 3.5, 20.0 to 12.0, Double.POSITIVE_INFINITY to 0.0, Double.NaN to 0.0)
            for ((write, read) in outputs) for ((input, expected) in cases) {
                write(input)
                assertEquals(expected, read())
            }
        }
    }

    @Test fun `untrustworthy flywheel feedback rejects closed loop velocity writes`() {
        try {
            RobotClock.useMockTime(100L)
            Dyn4jSimulation().use { sim ->
                for (kind in listOf(SimulationFaultKind.INVALID_INPUT, SimulationFaultKind.STALE_INPUT, SimulationFaultKind.FROZEN_INPUT)) {
                    val io = SimulatedFlywheelIO(sim, SimulationFaultTimeline(listOf(
                        SimulationFaultCommand("fault", "frc.flywheel", kind, 100L, 200L),
                    )))
                    assertFalse(io.velocityValid)
                    io.setVelocityRpm(3000.0, 1.0)
                    assertEquals(0.0, sim.simFlywheelVoltage)
                    assertFalse(io.lastWriteAccepted)
                }
            }
        } finally { RobotClock.useSystemTime() }
    }

    @Test fun `invalid cowl feedback cannot produce an effort command`() {
        Dyn4jSimulation().use { sim ->
            for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                sim.simCowlAngle = value
                assertFalse(sim.cowlIO.angleValid)
                sim.cowlIO.setTargetAngle(1.0, 1.0)
                assertEquals(0.0, sim.simCowlVoltage)
            }
        }
    }

    @Test fun `invalid climber feedback cannot produce an effort command`() {
        Dyn4jSimulation().use { sim ->
            for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                sim.simClimberPositionRotations = value
                assertFalse(sim.climberIO.positionValid)
                sim.climberIO.setTargetPositionRotations(1.0, 1.0)
                assertEquals(0.0, sim.simClimberVoltage)
            }
        }
    }

    @Test fun `invalid pivot model feedback cannot produce an effort command`() {
        Dyn4jSimulation().use { sim ->
            sim.intakePivotSim.update(Double.NaN, 0.02)
            assertFalse(sim.intakeIO.pivotAngleValid)
            sim.intakeIO.setPivotAngle(60.0, 1.0)
            assertEquals(0.0, sim.simIntakePivotVoltage)
        }
    }

    @Test fun `invalid flywheel model feedback cannot produce an effort command`() {
        Dyn4jSimulation().use { sim ->
            sim.flywheelSim.update(Double.NaN, 0.02)
            assertFalse(sim.flywheelIO.velocityValid)
            sim.flywheelIO.setVelocityRpm(3000.0, 1.0)
            assertEquals(0.0, sim.simFlywheelVoltage)
        }
    }

    @Test fun `feeder and floor clamp voltage and keep detection validity explicit`() {
        Dyn4jSimulation().use { sim ->
            for ((input, output) in listOf(-20.0 to -12.0, 20.0 to 12.0, 3.0 to 3.0, Double.NaN to 0.0)) {
                sim.feederIO.setAppliedVoltage(input)
                sim.floorIO.setAppliedVoltage(input)
                assertEquals(output, sim.simFeederVoltage)
                assertEquals(output, sim.simFloorVoltage)
                assertEquals(kotlin.math.abs(output) * 0.1, sim.feederIO.currentAmps)
                assertEquals(kotlin.math.abs(output) * 0.15, sim.floorIO.currentAmps)
            }
            sim.simFloorVelocityRps = -2.5
            assertEquals(-2.5, sim.floorIO.velocityRps)
            sim.simFeederPieceDetected = true
            assertFalse(sim.feederIO.pieceDetectionValid)
            assertFalse(sim.feederIO.isBeamBroken)
            val configured = SimulatedFeederIO(sim, detectorConfigured = true)
            assertTrue(configured.pieceDetectionValid)
            assertTrue(configured.isBeamBroken)
            sim.simFeederPieceDetected = false
            assertFalse(configured.isBeamBroken)
        }
    }
}
