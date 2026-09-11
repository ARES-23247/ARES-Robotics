package org.aresfirst.marvin.reducer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import org.aresfirst.marvin.marvin.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MarvinStateAuditTest {
    private fun state(marvin: MarvinState = MarvinState()) = RobotState(superstructure = SuperstructureState(custom = marvin))
    private fun reduce(state: RobotState, action: RobotAction) = MarvinReducer.reduce(state, action)

    @Test fun `cancelled completed duplicate and invalid slamtake events cannot restart outputs`() {
        val started = reduce(state(), StartSlamtake(1000L))
        val stopped = reduce(started, StopSlamtake(1200L))
        val completed = reduce(started, SlamtakeTimerExpired(2, 3000L))
        for (inactive in listOf(state(), stopped, completed)) {
            val next = reduce(inactive, SlamtakeTimerExpired(1, 4000L))
            assertSame(inactive.superstructure, next.superstructure)
            assertEquals(0.0, next.superstructure.marvin.intake.targetRollerVelocityRps)
        }
        val retracted = reduce(started, SlamtakeTimerExpired(1, 1600L))
        assertSame(retracted.superstructure, reduce(retracted, SlamtakeTimerExpired(1, 1700L)).superstructure)
        assertSame(started.superstructure, reduce(started, SlamtakeTimerExpired(7, 1700L)).superstructure)
        val restarted = reduce(stopped, StartSlamtake(5000L))
        assertSame(restarted.superstructure, reduce(restarted, SlamtakeTimerExpired(1, 1600L)).superstructure)
    }

    @Test fun `inventory assignments and detector edges stay within the existing forty piece capacity`() {
        for (count in listOf(Int.MIN_VALUE, -1, 0, 39, 40, 41, Int.MAX_VALUE)) {
            assertEquals(count.coerceIn(0, 40), reduce(state(), SetInventoryCount(count)).superstructure.marvin.inventoryCount)
        }
        for (count in listOf(39, 40, Int.MAX_VALUE)) {
            val next = reduce(state(MarvinState(inventoryCount = count)),
                SuperstructureSensorUpdate(0.0, 0.0, 0.0, true, pieceDetectionValid = true))
            assertEquals(40, next.superstructure.marvin.inventoryCount)
        }
        val corrupt = state(MarvinState(inventoryCount = Int.MIN_VALUE, transferActive = true,
            feeder = FeederState(gamePieceDetected = true, pieceDetectionValid = true)))
        val next = reduce(corrupt, SuperstructureSensorUpdate(0.0, 0.0, 0.0, false, pieceDetectionValid = true))
        assertEquals(0, next.superstructure.marvin.inventoryCount)
    }

    @Test fun `unchanged sensor frames preserve nested state and still advance root timestamp`() {
        val original = state()
        val next = reduce(original, SuperstructureSensorUpdate(0.0, 0.0, 0.0, false, timestampMs = 1234L))
        assertSame(original.superstructure, next.superstructure)
        assertEquals(1234L, next.timestampMs)
        val stopped = reduce(original, SetMechanismSafetyInhibit(true, 2000L))
        val unchanged = reduce(stopped, SuperstructureSensorUpdate(0.0, 0.0, 0.0, false, timestampMs = 2001L))
        assertSame(stopped.superstructure, unchanged.superstructure)
        assertSame(stopped.drive, unchanged.drive)
        assertTrue(unchanged.drive.isXLock)
    }

    @Test fun `invalid raw flywheel values do not repeatedly copy the same sanitized sample`() {
        val original = state()
        for (raw in listOf(4000.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)) {
            val next = reduce(original, SuperstructureSensorUpdate(raw, 0.0, 0.0, false, flywheelVelocityValid = false))
            assertSame(original.superstructure, next.superstructure)
            assertEquals(0.0, next.superstructure.marvin.flywheel.velocityRpm)
        }
    }

    @Test fun `valid samples repair nonfinite cached measurements despite unchanged validity flags`() {
        val original = state(MarvinState(
            flywheel = FlywheelState(velocityRpm = Double.NaN, velocityValid = true, allMotorsAtTarget = true),
            cowl = CowlState(angleRotations = Double.NaN, angleValid = true),
            intake = IntakeState(pivotAngleDegrees = Double.NaN, pivotAngleValid = true),
            floor = FloorState(velocityRps = Double.NaN, currentAmps = Double.NaN),
            climber = ClimberState(positionRotations = Double.NaN, positionValid = true)))
        val next = reduce(original, SuperstructureSensorUpdate(3000.0, 1.2, 80.0, false,
            flywheelVelocityValid = true, flywheelAllMotorsAtTarget = true, cowlAngleValid = true,
            intakeAngleValid = true, floorVelocityRps = 4.0, floorCurrentAmps = 2.0,
            climberPositionRotations = 0.2, climberPositionValid = true)).superstructure.marvin
        assertEquals(3000.0, next.flywheel.velocityRpm)
        assertEquals(1.2, next.cowl.angleRotations)
        assertEquals(80.0, next.intake.pivotAngleDegrees)
        assertEquals(4.0, next.floor.velocityRps)
        assertEquals(2.0, next.floor.currentAmps)
        assertEquals(0.2, next.climber.positionRotations)
    }

    @Test fun `default Marvin fallback is shared and immutable copies remain independent`() {
        val first = SuperstructureState().marvin
        val second = SuperstructureState().marvin
        assertSame(first, second)
        val changed = first.withFlywheelSpeed(2000.0).withIntakePivot(true)
        assertEquals(0.0, second.flywheel.targetVelocityRpm)
        assertFalse(second.intake.isDeployed)
        assertEquals(2000.0, changed.flywheel.targetVelocityRpm)
    }

    @Test fun `sensor deadbands retain values but validity edges always bypass them`() {
        val first = reduce(state(), SuperstructureSensorUpdate(3000.0, 1.0, 80.0, false,
            flywheelVelocityValid = true, flywheelAllMotorsAtTarget = true, cowlAngleValid = true,
            intakeAngleValid = true, floorVelocityRps = 4.0, floorCurrentAmps = 2.0,
            climberPositionRotations = 0.2, climberPositionValid = true))
        val stable = reduce(first, SuperstructureSensorUpdate(3001.0, 1.001, 80.001, false,
            flywheelVelocityValid = true, flywheelAllMotorsAtTarget = true, cowlAngleValid = true,
            intakeAngleValid = true, floorVelocityRps = 4.001, floorCurrentAmps = 2.01,
            climberPositionRotations = 0.201, climberPositionValid = true))
        assertEquals(first.superstructure, stable.superstructure)
        val invalid = reduce(stable, SuperstructureSensorUpdate(3001.0, 1.001, 80.001, false)).superstructure.marvin
        assertFalse(invalid.flywheel.velocityValid)
        assertFalse(invalid.cowl.angleValid)
        assertFalse(invalid.intake.pivotAngleValid)
        assertFalse(invalid.climber.positionValid)
        assertEquals(0.0, invalid.flywheel.velocityRpm)
    }

    @Test fun `fault gate suppresses commands while sensor observations remain available`() {
        val stopped = reduce(state(), LatchMechanismSafetyFault("test failure"))
        for (action in listOf<RobotAction>(SetFlywheelSpeed(4000.0), SetCowlAngle(1.2), SetIntakePivot(true),
            SetIntakeRollers(10.0), SetFeederSpeed(10.0), SetFloorSpeed(10.0), SetClimberVoltage(6.0),
            SetClimberPositionRotations(0.2), SetFlywheelActive(true), StartTransfer(), StartSlamtake())) {
            assertEquals(stopped.superstructure, reduce(stopped, action).superstructure)
        }
        val observed = reduce(stopped, SuperstructureSensorUpdate(3000.0, 1.2, 0.0, false,
            flywheelVelocityValid = true, cowlAngleValid = true))
        assertEquals(3000.0, observed.superstructure.marvin.flywheel.velocityRpm)
        assertTrue(observed.superstructure.marvin.mechanismSafetyInhibited)
        assertEquals("test failure", observed.superstructure.marvin.mechanismSafetyFaultReason)
    }

    @Test fun `detector invalidation clears live state but preserves trusted edge across repeated invalid frames`() {
        val detected = state(MarvinState(inventoryCount = 1,
            feeder = FeederState(gamePieceDetected = true, pieceDetectionValid = true)))
        val invalidAction = SuperstructureSensorUpdate(0.0, 0.0, 0.0, true, pieceDetectionValid = false)
        val invalid = reduce(detected, invalidAction)
        assertFalse(invalid.superstructure.marvin.feeder.gamePieceDetected)
        assertTrue(invalid.superstructure.marvin.feeder.previousGamePieceDetected)
        val stillInvalid = reduce(invalid, invalidAction)
        assertSame(invalid.superstructure, stillInvalid.superstructure)
        val recovered = reduce(stillInvalid, SuperstructureSensorUpdate(0.0, 0.0, 0.0, true, pieceDetectionValid = true))
        assertEquals(1, recovered.superstructure.marvin.inventoryCount)
        assertTrue(recovered.superstructure.marvin.feeder.gamePieceDetected)
    }

    @Test fun `simulator metadata reconciliation is bounded even for directly supplied corrupt counts`() {
        org.aresfirst.marvin.Dyn4jSimulation().use { sim ->
            val reconcile = sim.javaClass.getDeclaredMethod("reconcileInventoryMetadata", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val pieces = sim.javaClass.getDeclaredField("inventoryPieces").apply { isAccessible = true }
            for (count in listOf(Int.MAX_VALUE, 0, 39, 40, Int.MIN_VALUE)) {
                reconcile.invoke(sim, count)
                assertEquals(count.coerceIn(0, 40), (pieces.get(sim) as Collection<*>).size)
            }
        }
    }

    @Test fun `nonfinite climber position commands select neutral voltage rather than a travel endpoint`() {
        val original = state(MarvinState(climber = ClimberState(positionRotations = 0.2,
            positionValid = true, targetPositionRotations = 0.1, targetVoltage = 6.0,
            controlMode = ClimberControlMode.POSITION_ROTATIONS)))
        for (position in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val next = reduce(original, SetClimberPositionRotations(position)).superstructure.marvin.climber
            assertEquals(ClimberControlMode.VOLTAGE, next.controlMode)
            assertEquals(0.0, next.targetVoltage)
            assertEquals(0.1, next.targetPositionRotations, "Inactive position target should remain unchanged")
        }
        val low = reduce(original, SetClimberPositionRotations(-1.0)).superstructure.marvin.climber
        val high = reduce(original, SetClimberPositionRotations(100.0)).superstructure.marvin.climber
        assertEquals(MarvinConfig.MechanismLimits.climberMinRotations, low.targetPositionRotations)
        assertEquals(MarvinConfig.MechanismLimits.climberMaxRotations, high.targetPositionRotations)
        assertEquals(ClimberControlMode.POSITION_ROTATIONS, high.controlMode)
    }
}
