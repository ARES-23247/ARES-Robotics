package org.aresfirst.marvin

import com.areslib.action.RobotAction
import com.areslib.state.*
import com.areslib.telemetry.ITelemetry
import edu.wpi.first.networktables.NetworkTableInstance
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.dyn4j.dynamics.Body

class Dyn4jSimulationTest {

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testHighCapacityInventoryLimit() {
        val sim = Dyn4jSimulation(seed = 42L, feederPieceDetectorConfigured = true)
        val state = RobotState(superstructure = SuperstructureState(custom = org.aresfirst.marvin.marvin.MarvinState(inventoryCount = 39)))

        // Get private 'balls' field via reflection from physicsWorld
        val physicsWorldField = Dyn4jSimulation::class.java.getDeclaredField("physicsWorld")
        physicsWorldField.isAccessible = true
        val physicsWorld = physicsWorldField.get(sim)
        val ballsField = physicsWorld::class.java.getDeclaredField("balls")
        ballsField.isAccessible = true
        val ballsList = ballsField.get(physicsWorld) as MutableList<Body>

        // Assert that we have spawned balls
        assertTrue(ballsList.isNotEmpty())

        // Deploy intake and start spinning rollers in simulation
        sim.intakeIO.setPivotAngle(90.0, 1.0)
        sim.intakeIO.setRollerVoltage(12.0)

        // Place one note inside the forward-facing intake aperture.
        ballsList[0].transform.setTranslation(2.6, 2.0)

        // Run step with intake deployed and active.
        // First run a few steps to let the pivot simulator update past 45 degrees
        var pivotDegrees = 0.0
        var pieceDetected = false
        for (i in 0..50) {
            sim.step(state, 0.02)
            pivotDegrees = sim.intakeIO.pivotAngleDegrees
            pieceDetected = sim.feederIO.isBeamBroken
            if (pivotDegrees > 45.0 && pieceDetected) break
        }
        assertTrue(pivotDegrees > 45.0, "Intake pivot should have deployed beyond 45 degrees")

        // Ingestion sets the simulated detector edge rather than mutating inventory directly.
        // The actual +1 inventory increment is applied by MarvinReducer on the
        // false->true transition of SuperstructureSensorUpdate.pieceDetected in the
        // full robot loop (MarvinSuperstructure.readSensors), so it is not observable
        // from Dyn4jSimulation.step() in isolation.
        assertTrue(pieceDetected, "Sim should signal feeder piece-detected after ball ingestion")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun defaultSimulatorCreditsInventoryWhenACollectedPieceHasNoVirtualDetector() {
        val sim = Dyn4jSimulation(seed = 42L)
        val state = RobotState(
            superstructure = SuperstructureState(
                custom = org.aresfirst.marvin.marvin.MarvinState(inventoryCount = 3)
            )
        )
        val physicsWorldField = Dyn4jSimulation::class.java.getDeclaredField("physicsWorld")
        physicsWorldField.isAccessible = true
        val physicsWorld = physicsWorldField.get(sim)
        val ballsField = physicsWorld::class.java.getDeclaredField("balls")
        ballsField.isAccessible = true
        val balls = ballsField.get(physicsWorld) as MutableList<Body>
        balls.first().transform.setTranslation(2.6, 2.0)
        sim.intakeIO.setPivotAngle(90.0, 1.0)
        sim.intakeIO.setRollerVoltage(12.0)

        var inventoryAction: org.aresfirst.marvin.marvin.SetInventoryCount? = null
        for (step in 0 until 60) {
            inventoryAction = sim.step(state, 0.02)
                .filterIsInstance<org.aresfirst.marvin.marvin.SetInventoryCount>()
                .firstOrNull()
            if (inventoryAction != null) break
        }

        assertNotNull(inventoryAction, "Default simulation must credit a collected field piece")
        assertEquals(4, inventoryAction!!.count)
        assertFalse(sim.feederIO.pieceDetectionValid)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testShootingAnd2_5DProjectileMotion() {
        val sim = Dyn4jSimulation(seed = 42L)
        
        // Setup state with active flywheel ready at 4000 RPM, and cowl angle
        val superstructure = SuperstructureState(
            custom = org.aresfirst.marvin.marvin.MarvinState(
                flywheelActive = true,
                flywheel = org.aresfirst.marvin.marvin.FlywheelState(
                    velocityRpm = 4000.0,
                    velocityValid = true,
                    allMotorsAtTarget = true,
                    targetVelocityRpm = 4000.0
                ),
                inventoryCount = 10
            )
        )
        val state = RobotState(superstructure = superstructure)

        // Set flywheel RPM instantly using reflection
        val flywheelSimField = Dyn4jSimulation::class.java.getDeclaredField("flywheelSim")
        flywheelSimField.isAccessible = true
        val flywheelSimInstance = flywheelSimField.get(sim) as com.areslib.sim.model.FlywheelSim
        val angularVelField = com.areslib.sim.model.FlywheelSim::class.java.getDeclaredField("angularVelocityRadPerSec")
        angularVelField.isAccessible = true
        angularVelField.set(flywheelSimInstance, 4000.0 * 2.0 * Math.PI / 60.0)

        // The visualization model stores degrees internally; public cowl IO uses rotations.
        val cowlAngleField = Dyn4jSimulation::class.java.getDeclaredField("simCowlAngle")
        cowlAngleField.isAccessible = true
        cowlAngleField.set(sim, 30.0)

        // Set feeder voltage to trigger ingestion/shoot
        sim.feederIO.setAppliedVoltage(12.0)

        // Run step forward to let cowl angle update and trigger shoot
        var actions: List<RobotAction> = emptyList()
        for (i in 0..20) {
            actions = sim.step(state, 0.02)
            if (actions.any { it is org.aresfirst.marvin.marvin.SetInventoryCount }) break
        }

        // Verify shoot action dispatched decrement
        val shootAction = actions.find { it is org.aresfirst.marvin.marvin.SetInventoryCount } as? org.aresfirst.marvin.marvin.SetInventoryCount
        assertNotNull(shootAction, "Should have triggered a shooting action")
        assertEquals(9, shootAction!!.count)

        // Check private 'flyingBalls' list via reflection from physicsWorld
        val physicsWorldField = Dyn4jSimulation::class.java.getDeclaredField("physicsWorld")
        physicsWorldField.isAccessible = true
        val physicsWorld = physicsWorldField.get(sim)
        val flyingField = physicsWorld::class.java.getDeclaredField("flyingBalls")
        flyingField.isAccessible = true
        val flyingList = flyingField.get(physicsWorld) as List<FlyingBall>

        assertEquals(1, flyingList.size, "Exactly one ball should be flying in 2.5D space")
        val fb = flyingList[0]
        assertEquals(0.7164096, fb.z, 1e-4, "Initial launch height should be exactly 0.7164096 meters after one step")
        assertTrue(fb.x < 2.0, "Rear-facing shooter must spawn and launch the piece behind the robot")
        assertTrue(fb.vx < 0.0, "A zero-heading rear shooter must launch toward field -X")
        assertTrue(fb.vz > 0.0, "Vertical velocity should be positive due to cowl angle")
    }

    /**
     * The production shoot path applies `FEEDER_KV × FEEDER_SHOOT_SPEED_RPS` volts —
     * 1.2 V at effort 1.0 — not a direct 12 V injection. The simulation spin gate must
     * accept that voltage or no driver/autonomous control path can ever launch a note.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun testProductionFeederVoltageLaunchesNote() {
        val productionVolts = org.aresfirst.marvin.marvin.MarvinSuperstructure.feederOutputVolts(
            org.aresfirst.marvin.marvin.MarvinConfig.FEEDER_SHOOT_SPEED_RPS,
            effortScale = 1.0
        )
        assertTrue(
            kotlin.math.abs(productionVolts) >
                org.aresfirst.marvin.marvin.MarvinSuperstructure.FEEDER_SPIN_THRESHOLD_VOLTS,
            "Production feeder voltage ($productionVolts V) must pass the simulation spin gate"
        )

        val sim = Dyn4jSimulation(seed = 42L)
        val state = RobotState(
            superstructure = SuperstructureState(
                custom = org.aresfirst.marvin.marvin.MarvinState(
                    flywheelActive = true,
                    flywheel = org.aresfirst.marvin.marvin.FlywheelState(
                        velocityRpm = 4000.0,
                        velocityValid = true,
                        allMotorsAtTarget = true,
                        targetVelocityRpm = 4000.0
                    ),
                    inventoryCount = 2
                )
            )
        )

        val flywheelSimField = Dyn4jSimulation::class.java.getDeclaredField("flywheelSim")
        flywheelSimField.isAccessible = true
        val flywheelSimInstance = flywheelSimField.get(sim) as com.areslib.sim.model.FlywheelSim
        val angularVelField = com.areslib.sim.model.FlywheelSim::class.java.getDeclaredField("angularVelocityRadPerSec")
        angularVelField.isAccessible = true
        angularVelField.set(flywheelSimInstance, 4000.0 * 2.0 * Math.PI / 60.0)

        val cowlAngleField = Dyn4jSimulation::class.java.getDeclaredField("simCowlAngle")
        cowlAngleField.isAccessible = true
        cowlAngleField.set(sim, 30.0)

        sim.feederIO.setAppliedVoltage(productionVolts)

        var shootAction: org.aresfirst.marvin.marvin.SetInventoryCount? = null
        for (i in 0..20) {
            shootAction = sim.step(state, 0.02)
                .filterIsInstance<org.aresfirst.marvin.marvin.SetInventoryCount>()
                .firstOrNull()
            if (shootAction != null) break
        }
        assertNotNull(shootAction, "Production feeder voltage (1.2 V) must trigger a simulated shot")
        assertEquals(1, shootAction!!.count)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testHubScoringAndCenterEjection() {
        val sim = Dyn4jSimulation(seed = 42L)
        val state = RobotState()

        // Get private 'flyingBalls' list via reflection from physicsWorld
        val physicsWorldField = Dyn4jSimulation::class.java.getDeclaredField("physicsWorld")
        physicsWorldField.isAccessible = true
        val physicsWorld = physicsWorldField.get(sim)
        val flyingField = physicsWorld::class.java.getDeclaredField("flyingBalls")
        flyingField.isAccessible = true
        val flyingList = flyingField.get(physicsWorld) as MutableList<FlyingBall>

        // Get private 'balls' field via reflection
        val ballsField = physicsWorld::class.java.getDeclaredField("balls")
        ballsField.isAccessible = true
        val ballsList = ballsField.get(physicsWorld) as MutableList<Body>
        val initialGroundBalls = ballsList.size

        // Mock a flying ball inside the Blue Speaker scoring zone.
        // at height z = 2.0 (inside 1.6 to 2.8 meters range)
        val scoredBall = FlyingBall(
            x = org.aresfirst.marvin.marvin.MarvinConfig.FieldTargets.blueSpeaker.x,
            y = org.aresfirst.marvin.marvin.MarvinConfig.FieldTargets.blueSpeaker.y,
            z = 2.0,
            vx = 0.1,
            vy = 0.1,
            vz = -1.0
        )
        val opponentSpeakerBall = FlyingBall(
            x = org.aresfirst.marvin.marvin.MarvinConfig.FieldTargets.redSpeaker.x,
            y = org.aresfirst.marvin.marvin.MarvinConfig.FieldTargets.redSpeaker.y,
            z = 2.0,
            vx = 0.1,
            vy = 0.1,
            vz = -1.0
        )
        flyingList.add(scoredBall)
        flyingList.add(opponentSpeakerBall)

        // Step simulation
        sim.step(state, 0.02)

        // RobotState defaults to BLUE: the blue shot scores, while the opponent speaker is not
        // credited and its projectile remains in flight.
        assertEquals(1, flyingList.size)
        assertSame(opponentSpeakerBall, flyingList.single())

        // Verify new ground ball was spawned (balls size increased)
        assertEquals(initialGroundBalls + 1, ballsList.size, "A new ground ball should be spawned")

        // Verify the newest ball is spawned at the canonical field center.
        val newestBall = ballsList.last()
        assertEquals(com.areslib.math.coordinate.CoordinateTransformers.FRC_FIELD_LENGTH / 2.0, newestBall.transform.translationX, 0.05, "Scored ball should eject to field X center")
        assertEquals(com.areslib.math.coordinate.CoordinateTransformers.FRC_FIELD_WIDTH / 2.0, newestBall.transform.translationY, 0.05, "Scored ball should eject to field Y center")
        assertEquals(
            scoredBall.metadata.instanceKey,
            com.areslib.sim.field.SimGamePieceBodyFactory.metadata(newestBall)?.instanceKey,
            "Scored-piece identity should survive center ejection",
        )
        
        // Verify it has non-zero ejection velocities
        assertTrue(newestBall.linearVelocity.magnitude > 1.0, "Ejected ball should have an outward velocity")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testLandingOnGround() {
        val sim = Dyn4jSimulation(seed = 42L)
        val state = RobotState()

        // Get private 'flyingBalls' list via reflection from physicsWorld
        val physicsWorldField = Dyn4jSimulation::class.java.getDeclaredField("physicsWorld")
        physicsWorldField.isAccessible = true
        val physicsWorld = physicsWorldField.get(sim)
        val flyingField = physicsWorld::class.java.getDeclaredField("flyingBalls")
        flyingField.isAccessible = true
        val flyingList = flyingField.get(physicsWorld) as MutableList<FlyingBall>

        // Mock a ball almost landing on the ground (z = 0.05, radius is 0.0635)
        val landingBall = FlyingBall(
            x = 6.0,
            y = 5.0,
            z = 0.05,
            vx = 3.0,
            vy = -2.0,
            vz = -5.0
        )
        flyingList.add(landingBall)

        // Step simulation
        sim.step(state, 0.02)

        // Verify flying ball was removed
        assertTrue(flyingList.isEmpty(), "Landed ball should be removed from flying list")

        // Get balls list to verify landing body translation and velocity
        val ballsField = physicsWorld::class.java.getDeclaredField("balls")
        ballsField.isAccessible = true
        val ballsList = ballsField.get(physicsWorld) as List<Body>

        val dynamicBody = ballsList.last()
        assertEquals(6.0, dynamicBody.transform.translationX, 0.1, "Landed ball should match final X position")
        assertEquals(5.0, dynamicBody.transform.translationY, 0.1, "Landed ball should match final Y position")
        assertEquals(3.0, dynamicBody.linearVelocity.x, 1e-3, "Residual linear velocity X should be preserved")
        assertEquals(-2.0, dynamicBody.linearVelocity.y, 1e-3, "Residual linear velocity Y should be preserved")
        assertEquals(
            landingBall.metadata.instanceKey,
            com.areslib.sim.field.SimGamePieceBodyFactory.metadata(dynamicBody)?.instanceKey,
            "Landed-piece identity should survive the 2.5-D to Dyn4j transition",
        )
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun testTelemetryPackaging() {
        val sim = Dyn4jSimulation(seed = 42L)
        val state = RobotState()

        // Get private lists via reflection from physicsWorld
        val physicsWorldField = Dyn4jSimulation::class.java.getDeclaredField("physicsWorld")
        physicsWorldField.isAccessible = true
        val physicsWorld = physicsWorldField.get(sim)
        val flyingField = physicsWorld::class.java.getDeclaredField("flyingBalls")
        flyingField.isAccessible = true
        val flyingList = flyingField.get(physicsWorld) as MutableList<FlyingBall>

        val ballsField = physicsWorld::class.java.getDeclaredField("balls")
        ballsField.isAccessible = true
        val ballsList = ballsField.get(physicsWorld) as List<Body>

        // Mock a flying ball
        flyingList.add(FlyingBall(3.0, 3.0, 2.5, 0.0, 0.0, 0.0))

        val mockTelemetry = object : ITelemetry {
            val arrays = mutableMapOf<String, DoubleArray>()
            val numbers = mutableMapOf<String, Double>()
            override fun putDoubleArray(key: String, value: DoubleArray) {
                arrays[key] = value
            }
            override fun putNumber(key: String, value: Double) { numbers[key] = value }
            override fun putString(key: String, value: String) {}
            override fun putBoolean(key: String, value: Boolean) {}
            override fun getNumber(key: String, defaultValue: Double): Double = defaultValue
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
            override fun getString(key: String, defaultValue: String): String = defaultValue
        }

        sim.publishVisualization(state, mockTelemetry)

        // Verify the double array exists and has correct sizing
        val poseArray = mockTelemetry.arrays["Robot/FuelPoses"]
        assertNotNull(poseArray)
        assertEquals(700, poseArray!!.size, "Array should be the full pre-allocated size")

        // Verify the flying ball is packaged at the correct index at the end
        val lastIdx = ballsList.size * 7
        assertEquals(3.0, poseArray[lastIdx])
        assertEquals(3.0, poseArray[lastIdx + 1])
        assertEquals(2.5, poseArray[lastIdx + 2], "Flying ball Z coordinate must be published correctly")
        assertEquals(1.0, poseArray[lastIdx + 3], "Identity quaternion qw must be 1.0")

        val gamePieceFrame = mockTelemetry.arrays[com.areslib.telemetry.TelemetryTopicConstants.GAME_PIECES_FRAME]
        assertNotNull(gamePieceFrame)
        assertEquals(2.0, gamePieceFrame!![0], 0.0)
        assertEquals((ballsList.size + flyingList.size).toDouble(), gamePieceFrame[1], 0.0)
        assertEquals(3.0, gamePieceFrame[2 + ballsList.size * 9 + 2], 0.0)
        assertEquals(3.0, gamePieceFrame[2 + ballsList.size * 9 + 3], 0.0)

        val poseFrame = mockTelemetry.arrays["ARES/SimulatorPoseFrame"]
        assertNotNull(poseFrame)
        assertEquals(10, poseFrame!!.size)
        assertEquals(2.0, poseFrame[0], 1e-9, "Dyn4j truth X should remain distinct from Redux state")
        assertEquals(0.0, poseFrame[3], 1e-9, "Default Redux EKF X should not be replaced by truth")
        assertEquals(0.0, poseFrame[6], 1e-9, "Default Redux odometry X should remain independently observable")
        assertEquals(2.0, mockTelemetry.numbers["ARES/TruePose/0"]!!, 1e-9)
    }

    @Test
    fun testCowlAngleUnitMapping() {
        val sim = Dyn4jSimulation()
        
        // Step simulation forward for 2 seconds (100 steps of 0.02) to let closed-loop settle
        val state = RobotState()
        for (i in 0 until 100) {
            sim.cowlIO.setTargetAngle(1.0, 1.0)
            sim.step(state, 0.02)
        }
        
        // The internal angle settles near 32 degrees; public feedback remains rotations.
        assertEquals(1.0, sim.cowlIO.angleRotations, 0.05, "Cowl feedback should match commanded rotations")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `live field document rebuilds the FRC simulator world`() {
        val nt = NetworkTableInstance.getDefault()
        val publisher = nt.getStringTopic("ARES/Input/fieldConfig").publish()
        val sim = Dyn4jSimulation(seed = 42L)
        val config = RobotFieldConfig(
            revision = 99L,
            id = "live-field-test",
            fieldType = FieldType.FRC,
            elementTypes = listOf(
                RobotFieldElementType(id = "note", shape = "sphere", diameter = 0.35)
            ),
            elements = listOf(
                RobotFieldElementInstance(id = "note-1", elementTypeId = "note", x = 3.0, y = 2.0)
            )
        )

        try {
            publisher.set(RobotFieldDocument.encode(config))
            nt.flush()
            sim.step(RobotState(), 0.02)

            val physicsWorldField = Dyn4jSimulation::class.java.getDeclaredField("physicsWorld")
            physicsWorldField.isAccessible = true
            val physicsWorld = physicsWorldField.get(sim)
            val ballsField = physicsWorld::class.java.getDeclaredField("balls")
            ballsField.isAccessible = true
            val balls = ballsField.get(physicsWorld) as List<Body>

            assertEquals(1, balls.size)
            assertEquals(3.0, balls.single().transform.translationX, 1e-9)
            assertEquals(2.0, balls.single().transform.translationY, 1e-9)
        } finally {
            publisher.set("")
            nt.flush()
            publisher.close()
        }
    }

    @Test
    fun `intake capture zone is oriented in the robot frame`() {
        assertTrue(isInsideIntakeCaptureZone(2.0, 2.0, 0.0, 2.6, 2.0))
        assertFalse(isInsideIntakeCaptureZone(2.0, 2.0, 0.0, 1.6, 2.0))
        assertFalse(isInsideIntakeCaptureZone(2.0, 2.0, 0.0, 2.0, 2.4))
        assertTrue(isInsideIntakeCaptureZone(2.0, 2.0, Math.PI / 2.0, 2.0, 2.6))
    }

    @Test
    fun `field-centric velocity is not rotated a second time by simulation`() {
        val fieldCentric = Dyn4jSimulation(seed = 42L)
        val robotCentric = Dyn4jSimulation(seed = 42L)
        try {
            fieldCentric.resetPose(6.0, 1.0, Math.PI / 2.0)
            robotCentric.resetPose(6.0, 1.0, Math.PI / 2.0)
            val fieldState = RobotState(
                drive = DriveState(xVelocityMetersPerSecond = 1.0, isFieldCentric = true)
            )
            val robotState = RobotState(
                drive = DriveState(xVelocityMetersPerSecond = 1.0, isFieldCentric = false)
            )
            repeat(20) {
                fieldCentric.step(fieldState, 0.02)
                robotCentric.step(robotState, 0.02)
            }

            val fieldPose = fieldCentric.getPoseUpdate()
            val robotPose = robotCentric.getPoseUpdate()
            assertTrue(fieldPose.xMeters - 6.0 > kotlin.math.abs(fieldPose.yMeters - 1.0))
            assertTrue(robotPose.yMeters - 1.0 > kotlin.math.abs(robotPose.xMeters - 6.0))
        } finally {
            fieldCentric.close()
            robotCentric.close()
        }
    }
}
