package com.areslib.frc

import com.areslib.action.RobotAction
import com.areslib.frc.hardware.FlywheelIO
import com.areslib.frc.hardware.CowlIO
import com.areslib.frc.hardware.IntakeIO
import com.areslib.frc.hardware.FeederIO
import com.areslib.frc.hardware.FloorIO
import com.areslib.frc.hardware.ClimberIO
import com.areslib.sim.model.FlywheelSim
import com.areslib.sim.model.IntakePivotSim
import com.areslib.state.RobotState
import com.areslib.telemetry.ITelemetry
import com.areslib.frc.marvin.*
import com.areslib.frc.sim.Dyn4jPhysicsWorld
import com.areslib.frc.sim.Dyn4jSimTelemetryPublisher
import com.areslib.frc.sim.Dyn4jSwerveModuleSim
import org.dyn4j.dynamics.Body
import org.dyn4j.geometry.Vector2
import edu.wpi.first.networktables.NetworkTableInstance
import com.areslib.state.RobotFieldDocument
import com.areslib.sim.field.SimGamePieceBodyFactory
import com.areslib.sim.field.SimGamePieceMetadata

/** Mutable 2.5-D projectile state; positions are meters and velocities are meters per second. */
class FlyingBall(
    var x: Double,
    var y: Double,
    var z: Double,
    var vx: Double,
    var vy: Double,
    var vz: Double,
    val metadata: SimGamePieceMetadata = DEFAULT_FRC_PIECE_METADATA,
)

/**
 * Deterministic desktop model for Marvin's drivetrain, mechanisms, and game-piece flow.
 *
 * Dyn4j owns the planar robot and grounded pieces while [FlyingBall] supplies the vertical axis
 * for launched pieces. Field X/Y are blue-origin meters, headings are CCW-positive radians, and
 * [step] receives seconds. Public IO adapters expose the same units and validity contracts as the
 * RoboRIO hardware adapters. The optional feeder detector defaults to unavailable, in which case
 * collection emits an explicit inventory action instead of silently losing the collected piece.
 *
 * The mutable `sim*` fields are the private simulation bus shared with adapters in `sim.io`.
 * Callers should command and observe the typed IO properties instead of mutating those fields.
 */
class Dyn4jSimulation(
    seed: Long = 42L,
    private val feederPieceDetectorConfigured: Boolean = false
) : AutoCloseable {

    constructor(
        config: com.areslib.state.RobotFieldConfig,
        seed: Long = 42L,
        feederPieceDetectorConfigured: Boolean = false
    ) : this(seed, feederPieceDetectorConfigured) {
        buildWorld(config)
    }

    private val physicsWorld = Dyn4jPhysicsWorld(
        robotLengthMeters = com.areslib.frc.config.CanonicalDrivebaseConfig.simulationRobotLengthMeters,
        robotWidthMeters = com.areslib.frc.config.CanonicalDrivebaseConfig.simulationRobotWidthMeters,
    )
    private val swerveSim = Dyn4jSwerveModuleSim(
        kpLinear = com.areslib.frc.config.CanonicalDrivebaseConfig.simulationLinearKp,
        kpAngular = com.areslib.frc.config.CanonicalDrivebaseConfig.simulationAngularKp,
    )
    private val telemetryPublisher = Dyn4jSimTelemetryPublisher()
    private val fieldConfigSubscriber = NetworkTableInstance.getDefault()
        .getStringTopic("ARES/Input/fieldConfig")
        .subscribe("")
    private var closed = false
    private var lastFieldConfigJson = ""

    private var shootCooldownTimer = 0.0
    private val inventoryPieces = java.util.ArrayDeque<SimGamePieceMetadata>()
    private var fallbackPieceSequence = 0L

    internal val flywheelSim = FlywheelSim()
    internal val intakePivotSim = IntakePivotSim()

    internal var simFlywheelVoltage = 0.0
    internal var simCowlVoltage = 0.0
    internal var simIntakePivotVoltage = 0.0
    internal var simIntakeRollerVoltage = 0.0
    internal var simFeederVoltage = 0.0
    internal var simFloorVoltage = 0.0
    internal var simFloorVelocityRps = 0.0
    internal var simClimberVoltage = 0.0
    internal var simClimberPositionRotations = 0.0
    internal var simCowlAngle = 0.0
    internal var simFeederPieceDetected = false
    var flywheelRotationAngle = 0.0
        private set

    val flywheelIO: FlywheelIO = com.areslib.frc.sim.io.SimulatedFlywheelIO(this)
    val cowlIO: CowlIO = com.areslib.frc.sim.io.SimulatedCowlIO(this)
    val intakeIO: IntakeIO = com.areslib.frc.sim.io.SimulatedIntakeIO(this)
    val feederIO: FeederIO = com.areslib.frc.sim.io.SimulatedFeederIO(this, feederPieceDetectorConfigured)
    val floorIO: FloorIO = com.areslib.frc.sim.io.SimulatedFloorIO(this)
    val climberIO: ClimberIO = com.areslib.frc.sim.io.SimulatedClimberIO(this)

    private val scratchActions = mutableListOf<RobotAction>()
    private val blueHubCenter = Vector2(
        MarvinConfig.FieldTargets.blueSpeaker.x,
        MarvinConfig.FieldTargets.blueSpeaker.y
    )
    private val redHubCenter = Vector2(
        MarvinConfig.FieldTargets.redSpeaker.x,
        MarvinConfig.FieldTargets.redSpeaker.y
    )
    private val random = java.util.Random(seed)
    private val debug = java.lang.Boolean.getBoolean("ares.debug")

    /** Advances all models by [dt] seconds and returns reusable Redux actions for this step. */
    fun step(state: RobotState, dt: Double): List<RobotAction> {
        scratchActions.clear()
        val actions = scratchActions
        val timestamp = com.areslib.util.RobotClock.currentTimeMillis()

        if (dt <= 0.0) return actions

        reconcileInventoryMetadata(state.superstructure.marvin.inventoryCount)

        val fieldConfigJson = fieldConfigSubscriber.get()
        if (fieldConfigJson.isNotBlank() && fieldConfigJson != lastFieldConfigJson) {
            lastFieldConfigJson = fieldConfigJson
            try {
                buildWorld(RobotFieldDocument.decode(fieldConfigJson))
            } catch (error: Exception) {
                System.err.println("[FRC Sim] Ignoring invalid live field document: ${error.message}")
            }
        }

        if (shootCooldownTimer > 0.0) {
            shootCooldownTimer -= dt
        }

        swerveSim.update(state, physicsWorld.robotBody)
        physicsWorld.step(dt)

        flywheelSim.update(simFlywheelVoltage, dt)
        intakePivotSim.update(simIntakePivotVoltage, dt)

        val flywheelRps = flywheelSim.velocityRpm / 60.0
        flywheelRotationAngle += (flywheelRps * 2.0 * Math.PI) * dt

        simCowlAngle += (simCowlVoltage * 15.0) * dt
        simCowlAngle = simCowlAngle.coerceIn(0.0, 70.0)

        val targetFloorVelocityRps = (simFloorVoltage / 12.0) * 125.5
        simFloorVelocityRps += (targetFloorVelocityRps - simFloorVelocityRps) * 15.0 * dt
        simFloorVelocityRps = simFloorVelocityRps.coerceIn(-125.5, 125.5)

        val climberVelocity = (simClimberVoltage / 12.0) * 1.0
        simClimberPositionRotations += climberVelocity * dt
        simClimberPositionRotations = simClimberPositionRotations.coerceIn(0.0, 1.73)

        val t = physicsWorld.robotBody.transform
        val robotX = t.translationX
        val robotY = t.translationY
        val robotHeading = t.rotationAngle

        val intakeDeployed = intakePivotSim.angleDegrees > 45.0
        val intakeSpinning = simIntakeRollerVoltage > 1.0

        if (intakeDeployed && intakeSpinning && state.superstructure.marvin.inventoryCount < 40) {
            for (i in physicsWorld.balls.indices.reversed()) {
                val ball = physicsWorld.balls[i]
                val bx = ball.transform.translationX
                val by = ball.transform.translationY
                if (isInsideIntakeCaptureZone(robotX, robotY, robotHeading, bx, by)) {
                    val metadata = SimGamePieceBodyFactory.metadata(ball) ?: nextFallbackPiece("captured")
                    physicsWorld.world.removeBody(ball)
                    physicsWorld.balls.removeAt(i)
                    inventoryPieces.addLast(metadata)
                    if (feederPieceDetectorConfigured) {
                        simFeederPieceDetected = true
                    } else {
                        actions.add(SetInventoryCount(state.superstructure.marvin.inventoryCount + 1, timestamp))
                    }
                    if (debug) println("BALL INGESTED!")
                    break
                }
            }
        }

        val flywheelAtSpeed = state.superstructure.marvin.isFlywheelAtSpeed
        // Gate on the feeder actuation contract (KV feed-forward), not an arbitrary voltage:
        // the production shoot path only ever applies 1.2 V, which the previous >2.0 gate
        // rejected — making the simulated robot unable to score through real control paths.
        val feederSpinning = kotlin.math.abs(simFeederVoltage) >
            com.areslib.frc.marvin.MarvinSuperstructure.FEEDER_SPIN_THRESHOLD_VOLTS
        if (flywheelAtSpeed && feederSpinning && state.superstructure.marvin.inventoryCount > 0 && shootCooldownTimer <= 0.0) {
            shootCooldownTimer = 0.15
            val newCount = state.superstructure.marvin.inventoryCount - 1
            actions.add(com.areslib.frc.marvin.SetInventoryCount(newCount, timestamp))
            simFeederPieceDetected = newCount > 0

            val vLaunch = flywheelRps * 0.18
            val hoodRad = Math.toRadians(simCowlAngle)
            var launchAngleRad = robotHeading
            if (com.areslib.frc.marvin.MarvinConfig.SHOT_CONFIG.shooterFacesRearward) {
                launchAngleRad += Math.PI
            }
            val vPlanar = vLaunch * kotlin.math.cos(hoodRad)
            val launchVx = vPlanar * kotlin.math.cos(launchAngleRad)
            val launchVy = vPlanar * kotlin.math.sin(launchAngleRad)
            val vVert = vLaunch * kotlin.math.sin(hoodRad)

            val robotVx = physicsWorld.robotBody.linearVelocity.x
            val robotVy = physicsWorld.robotBody.linearVelocity.y
            
            val headingCos = kotlin.math.cos(robotHeading)
            val headingSin = kotlin.math.sin(robotHeading)
            val exitX = MarvinConfig.MechanismGeometry.SHOOTER_EXIT_X_METERS
            val exitY = MarvinConfig.MechanismGeometry.SHOOTER_EXIT_Y_METERS
            val bx = robotX + headingCos * exitX - headingSin * exitY
            val by = robotY + headingSin * exitX + headingCos * exitY
            val bz = MarvinConfig.MechanismGeometry.SHOOTER_EXIT_HEIGHT_METERS
            val vx = robotVx + launchVx
            val vy = robotVy + launchVy
            val vz = vVert

            val metadata = inventoryPieces.pollFirst() ?: nextFallbackPiece("launched")
            val flyingBall = FlyingBall(bx, by, bz, vx, vy, vz, metadata)
            physicsWorld.flyingBalls.add(flyingBall)
            if (debug) println("BALL SHOT (2.5D)! Pos: ($bx, $by, $bz), Vel: ($vx, $vy, $vz). Inventory left: $newCount")
        }

        val g = 9.80665
        for (i in physicsWorld.flyingBalls.indices.reversed()) {
            val fb = physicsWorld.flyingBalls[i]
            fb.x += fb.vx * dt
            fb.y += fb.vy * dt
            fb.z += fb.vz * dt
            fb.vz -= g * dt

            // Only the current alliance's speaker is a scoring target. Treating both field ends
            // as valid made opponent-speaker shots disappear and re-enter at center as if scored.
            val hubCenter = when (state.drive.alliance) {
                com.areslib.state.Alliance.BLUE -> blueHubCenter
                com.areslib.state.Alliance.RED -> redHubCenter
            }
            val dx = fb.x - hubCenter.x
            val dy = fb.y - hubCenter.y
            val scored = Math.hypot(dx, dy) < 0.6 && fb.z >= 1.6 && fb.z <= 2.8

            when {
                scored -> {
                    physicsWorld.flyingBalls.removeAt(i)
                    if (debug) println("BALL SCORED! Ejecting to center...")
                    
                    val ejectAngle = random.nextDouble() * 2.0 * Math.PI
                    val ejectSpeed = 1.5 + random.nextDouble() * 1.5
                    val evx = Math.cos(ejectAngle) * ejectSpeed
                    val evy = Math.sin(ejectAngle) * ejectSpeed

                    val ball = SimGamePieceBodyFactory.createBody(
                        metadata = fb.metadata,
                        x = com.areslib.math.coordinate.CoordinateTransformers.FRC_FIELD_LENGTH / 2.0,
                        y = com.areslib.math.coordinate.CoordinateTransformers.FRC_FIELD_WIDTH / 2.0,
                    )
                    ball.linearVelocity.set(evx, evy)
                    
                    physicsWorld.world.addBody(ball)
                    physicsWorld.balls.add(ball)
                }
                fb.z <= fb.metadata.thicknessMeters / 2.0 -> {
                    physicsWorld.flyingBalls.removeAt(i)
                    if (debug) println("BALL LANDED! Spawning back as dynamic 2D body at (${fb.x}, ${fb.y})")

                    val fieldWidth = com.areslib.math.coordinate.CoordinateTransformers.FRC_FIELD_LENGTH
                    val fieldHeight = com.areslib.math.coordinate.CoordinateTransformers.FRC_FIELD_WIDTH
                    val cx = fb.x.coerceIn(0.1, fieldWidth - 0.1)
                    val cy = fb.y.coerceIn(0.1, fieldHeight - 0.1)

                    val ball = SimGamePieceBodyFactory.createBody(fb.metadata, cx, cy)
                    ball.linearVelocity.set(fb.vx, fb.vy)

                    physicsWorld.world.addBody(ball)
                    physicsWorld.balls.add(ball)
                }
            }
        }

        return actions
    }

    /** Returns ground-truth pose and field-relative velocity in meters, radians, and seconds. */
    fun getPoseUpdate(): RobotAction.PoseUpdate {
        val t = physicsWorld.robotBody.transform
        return RobotAction.PoseUpdate(
            xMeters = t.translationX,
            yMeters = t.translationY,
            headingRadians = t.rotationAngle,
            timestampMs = com.areslib.util.RobotClock.currentTimeMillis(),
            angularVelocityRadiansPerSecond = physicsWorld.robotBody.angularVelocity,
            xVelocityMetersPerSecond = physicsWorld.robotBody.linearVelocity.x,
            yVelocityMetersPerSecond = physicsWorld.robotBody.linearVelocity.y
        )
    }

    /** Publishes mechanism and piece poses for desktop 3-D visualization. */
    fun publishVisualization(state: RobotState, telemetry: ITelemetry) {
        val truth = physicsWorld.robotBody.transform
        telemetryPublisher.publishVisualization(
            state = state,
            telemetry = telemetry,
            intakeAngleDegrees = intakePivotSim.angleDegrees,
            simCowlAngle = simCowlAngle,
            flywheelRotationAngle = flywheelRotationAngle,
            balls = physicsWorld.balls,
            flyingBalls = physicsWorld.flyingBalls,
            trueX = truth.translationX,
            trueY = truth.translationY,
            trueHeading = truth.rotationAngle,
        )
    }

    /** Teleports the robot to blue-origin field meters and a CCW-positive heading in radians. */
    fun resetPose(x: Double, y: Double, heading: Double) {
        physicsWorld.resetPose(x, y, heading)
    }

    /** Replaces non-robot bodies with the obstacles and elements in [config]. */
    fun buildWorld(config: com.areslib.state.RobotFieldConfig) {
        physicsWorld.buildWorld(config)
    }

    private fun reconcileInventoryMetadata(inventoryCount: Int) {
        val boundedCount = inventoryCount.coerceAtLeast(0)
        while (inventoryPieces.size > boundedCount) inventoryPieces.removeLast()
        while (inventoryPieces.size < boundedCount) inventoryPieces.addLast(nextFallbackPiece("inventory"))
    }

    private fun nextFallbackPiece(prefix: String): SimGamePieceMetadata =
        SimGamePieceBodyFactory.fallback(
            instanceId = "frc-$prefix-${fallbackPieceSequence++}",
            typeId = "frc-fuel",
            diameterMeters = 0.127,
            massKg = 0.235,
        )

    override fun close() {
        if (closed) return
        closed = true
        fieldConfigSubscriber.close()
    }
}

private val DEFAULT_FRC_PIECE_METADATA = SimGamePieceBodyFactory.fallback(
    instanceId = "frc-flying-test-piece",
    typeId = "frc-fuel",
    diameterMeters = 0.127,
    massKg = 0.235,
)

/** Pure robot-frame aperture check used by the runtime simulation and geometry regressions. */
internal fun isInsideIntakeCaptureZone(
    robotX: Double,
    robotY: Double,
    robotHeading: Double,
    pieceX: Double,
    pieceY: Double
): Boolean {
    val dx = pieceX - robotX
    val dy = pieceY - robotY
    val cosHeading = kotlin.math.cos(robotHeading)
    val sinHeading = kotlin.math.sin(robotHeading)
    val localX = cosHeading * dx + sinHeading * dy
    val localY = -sinHeading * dx + cosHeading * dy
    return localX >= MarvinConfig.MechanismGeometry.INTAKE_CAPTURE_MIN_X_METERS &&
        localX <= MarvinConfig.MechanismGeometry.INTAKE_CAPTURE_MAX_X_METERS &&
        kotlin.math.abs(localY) <= MarvinConfig.MechanismGeometry.INTAKE_CAPTURE_HALF_WIDTH_METERS
}
