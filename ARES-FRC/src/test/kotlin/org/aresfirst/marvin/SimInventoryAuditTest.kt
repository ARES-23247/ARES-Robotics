package org.aresfirst.marvin

import com.areslib.Store
import com.areslib.sim.field.SimGamePieceBodyFactory
import com.areslib.sim.field.SimGamePieceMetadata
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import org.aresfirst.marvin.marvin.*
import org.aresfirst.marvin.sim.Dyn4jPhysicsWorld
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SimInventoryAuditTest {
    private fun world(sim: Dyn4jSimulation) = Dyn4jSimulation::class.java.getDeclaredField("physicsWorld")
        .apply { isAccessible = true }.get(sim) as Dyn4jPhysicsWorld

    @Suppress("UNCHECKED_CAST")
    private fun inventory(sim: Dyn4jSimulation) = Dyn4jSimulation::class.java.getDeclaredField("inventoryPieces")
        .apply { isAccessible = true }.get(sim) as java.util.ArrayDeque<SimGamePieceMetadata>

    private fun scenario(count: Int, capture: Boolean, shoot: Boolean, detector: Boolean,
                         priorDetected: Boolean = false, expected: Int, expectedCaptured: Boolean = capture) {
        Dyn4jSimulation(feederPieceDetectorConfigured = detector).use { sim ->
            val physics = world(sim)
            val candidate = physics.balls.first()
            for (ball in physics.balls.toList()) if (ball !== candidate || !capture) {
                physics.world.removeBody(ball)
                physics.balls.remove(ball)
            }
            candidate.transform.setTranslation(2.6, 2.0)
            val capturedMetadata = SimGamePieceBodyFactory.metadata(candidate)
            sim.intakePivotSim.reset(90.0)
            sim.intakeIO.setRollerVoltage(if (capture) 12.0 else 0.0)
            sim.feederIO.setAppliedVoltage(if (shoot) 1.2 else 0.0)
            sim.simFeederPieceDetected = priorDetected
            sim.simCowlAngle = 30.0
            sim.flywheelSim.javaClass.getDeclaredField("angularVelocityRadPerSec")
                .apply { isAccessible = true }.setDouble(sim.flywheelSim, 4000.0 * 2.0 * Math.PI / 60.0)
            val initial = MarvinState(inventoryCount = count, transferActive = shoot,
                flywheelActive = true,
                flywheel = FlywheelState(velocityRpm = 4000.0, targetVelocityRpm = 4000.0,
                    velocityValid = true, allMotorsAtTarget = true),
                feeder = FeederState(gamePieceDetected = priorDetected, previousGamePieceDetected = priorDetected,
                    pieceDetectionValid = detector))
            val store = Store(RobotState(superstructure = SuperstructureState(custom = initial)), MarvinReducer::reduce)
            val subsystem = MarvinSuperstructure(sim.flywheelIO, sim.cowlIO, sim.intakeIO,
                sim.feederIO, sim.floorIO, sim.climberIO)
            val actions = sim.step(store.state, 0.001).toList()
            actions.forEach(store::dispatch)
            assertEquals(expected, store.state.superstructure.marvin.inventoryCount, "count after simulator events")
            assertEquals(expected, inventory(sim).size, "physical inventory agrees with Redux")
            val shot = shoot && count.coerceIn(0, MarvinConfig.INVENTORY_CAPACITY) > 0
            assertEquals(if (shot) 1 else 0, physics.flyingBalls.size)
            assertEquals(if (capture && !expectedCaptured) 1 else 0, physics.balls.size)
            assertEquals(count.coerceIn(0, MarvinConfig.INVENTORY_CAPACITY) + if (capture) 1 else 0,
                inventory(sim).size + physics.balls.size + physics.flyingBalls.size, "piece conservation")
            if (expectedCaptured && expected > 0) assertEquals(capturedMetadata, inventory(sim).peekLast())
            repeat(3) { subsystem.readSensors(store, 1000L + it) }
            assertEquals(expected, store.state.superstructure.marvin.inventoryCount, "sensor reads cannot recount events")
            assertEquals(detector, store.state.superstructure.marvin.feeder.pieceDetectionValid)
            assertTrue(actions.count { it is SetInventoryCount } <= 1, "one coalesced inventory event per frame")
        }
    }

    @Test fun `simultaneous collection and shot conserve inventory without detector`() {
        for (count in listOf(1, 2, 39)) scenario(count, true, true, false, expected = count)
    }

    @Test fun `simultaneous collection and shot conserve inventory with either prior detector state`() {
        for (count in listOf(1, 2, 39)) for (prior in listOf(false, true))
            scenario(count, true, true, true, prior, count)
    }

    @Test fun `shots with remaining inventory cannot be credited as new detector arrivals`() {
        for (count in listOf(2, 40)) scenario(count, false, true, true, expected = count - 1)
    }

    @Test fun `last shot and repeated sensor reads decrement exactly once`() {
        for (detector in listOf(false, true)) scenario(1, false, true, detector, detector, 0)
    }

    @Test fun `collection is credited even when virtual detector was already held`() {
        for (count in listOf(0, 3, 39)) for (prior in listOf(false, true))
            scenario(count, true, false, true, prior, count + 1)
    }

    @Test fun `capacity rejects collection before shooting frees a slot`() {
        for (detector in listOf(false, true)) scenario(40, true, true, detector, expected = 39, expectedCaptured = false)
    }

    @Test fun `newly captured piece cannot fire from an initially empty hopper in the same frame`() {
        for (detector in listOf(false, true)) scenario(0, true, true, detector, expected = 1)
    }

    @Test fun `idle frames do not emit inventory actions`() {
        for (detector in listOf(false, true)) Dyn4jSimulation(feederPieceDetectorConfigured = detector).use { sim ->
            val state = RobotState(superstructure = SuperstructureState(custom = MarvinState(inventoryCount = 3)))
            assertTrue(sim.step(state, 0.001).none { it is SetInventoryCount })
        }
    }

    @Test fun `direct extreme state counts are repaired before event arithmetic`() {
        for (detector in listOf(false, true)) {
            scenario(Int.MIN_VALUE, true, true, detector, expected = 1)
            scenario(Int.MAX_VALUE, false, true, detector, expected = 39)
        }
    }

    @Test fun `ordinary count assignments preserve detector observations and duplicate snapshots reuse state`() {
        val feeder = FeederState(targetVelocityRps = 2.0, gamePieceDetected = true,
            previousGamePieceDetected = false, pieceDetectionValid = true)
        val initial = RobotState(superstructure = SuperstructureState(custom = MarvinState(feeder = feeder)))
        val assigned = MarvinReducer.reduce(initial, SetInventoryCount(3, 1000L))
        assertSame(feeder, assigned.superstructure.marvin.feeder)
        val observed = MarvinReducer.reduce(assigned, SetInventoryCount(3, 1001L, true))
        assertTrue(observed.superstructure.marvin.feeder.previousGamePieceDetected)
        assertEquals(2.0, observed.superstructure.marvin.feeder.targetVelocityRps)
        val repeated = MarvinReducer.reduce(observed, SetInventoryCount(3, 1002L, true))
        assertSame(observed.superstructure, repeated.superstructure)
        assertEquals(1002L, repeated.timestampMs)
    }

    @Test fun `virtual detector observation still stops active slamtake on sensor read`() {
        Dyn4jSimulation(feederPieceDetectorConfigured = true).use { sim ->
            val store = Store(RobotState(superstructure = SuperstructureState(custom = MarvinState())), MarvinReducer::reduce)
            store.dispatch(StartSlamtake(1000L))
            sim.simFeederPieceDetected = true
            store.dispatch(SetInventoryCount(1, 1001L, true))
            MarvinSuperstructure(sim.flywheelIO, sim.cowlIO, sim.intakeIO, sim.feederIO,
                sim.floorIO, sim.climberIO).readSensors(store, 1002L)
            val result = store.state.superstructure.marvin
            assertEquals(1, result.inventoryCount)
            assertFalse(result.slamtakeActive)
            assertEquals(0.0, result.intake.targetRollerVelocityRps)
            assertEquals(0.0, result.floor.targetVelocityRps)
            assertEquals(0.0, result.feeder.targetVelocityRps)
        }
    }
}
