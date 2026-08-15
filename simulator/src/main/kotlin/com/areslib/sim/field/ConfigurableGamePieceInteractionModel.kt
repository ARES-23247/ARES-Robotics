package com.areslib.sim.field

import com.areslib.sim.SimInteractionModel
import com.areslib.simulation.SimAppliedOutputRegistry
import com.areslib.simulation.SimAppliedOutputSignal
import com.areslib.subsystem.SimInteractionRole
import com.areslib.subsystem.SubsystemDocument
import org.dyn4j.dynamics.Body
import org.dyn4j.dynamics.BodyFixture
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Vector2
import org.dyn4j.world.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private data class DescriptorInteractionBinding(
    val role: SimInteractionRole,
    val signal: SimAppliedOutputSignal,
    val threshold: Double,
)

/** Descriptor-driven physical game-piece intake, inventory, and ejection model. */
class ConfigurableGamePieceInteractionModel private constructor(
    /** Maximum number of game pieces the robot can physically hold simultaneously. */
    val maxCapacity: Int,
    /** Forward distance from robot center to the intake/exit point in meters. */
    val intakeRangeMeters: Double,
    /** Radius of the intake capture zone in meters. */
    val intakeRadiusMeters: Double,
    /** Requested planar launch speed in meters per second. */
    val launchSpeedMps: Double,
    /** Launch elevation; the 2-D field model uses its horizontal velocity component. */
    val launchElevationDeg: Double,
    /** Diameter of simulated game pieces in meters. */
    val pieceDiameterMeters: Double,
    /** Mass of simulated game piece in kilograms. */
    val pieceMassKg: Double,
    private val bindings: Array<DescriptorInteractionBinding>,
) : SimInteractionModel {

    constructor(
        maxCapacity: Int = 1,
        intakeRangeMeters: Double = 0.35,
        intakeRadiusMeters: Double = 0.15,
        launchSpeedMps: Double = 8.0,
        pieceDiameterMeters: Double = 0.15,
        pieceMassKg: Double = 0.24,
    ) : this(
        maxCapacity = maxCapacity,
        intakeRangeMeters = intakeRangeMeters,
        intakeRadiusMeters = intakeRadiusMeters,
        launchSpeedMps = launchSpeedMps,
        launchElevationDeg = 0.0,
        pieceDiameterMeters = pieceDiameterMeters,
        pieceMassKg = pieceMassKg,
        bindings = emptyArray(),
    )

    private var launchWasApplied = false

    init {
        require(maxCapacity >= 1) { "Capacity must be at least one" }
        require(intakeRangeMeters.isFinite() && intakeRangeMeters > 0.0) { "Intake range must be finite and positive" }
        require(intakeRadiusMeters.isFinite() && intakeRadiusMeters > 0.0) { "Capture radius must be finite and positive" }
        require(launchSpeedMps.isFinite() && launchSpeedMps > 0.0) { "Launch speed must be finite and positive" }
        require(launchElevationDeg.isFinite() && launchElevationDeg in 0.0..90.0) { "Launch elevation must be from 0 to 90 degrees" }
        require(pieceDiameterMeters.isFinite() && pieceDiameterMeters > 0.0) { "Piece diameter must be finite and positive" }
        require(pieceMassKg.isFinite() && pieceMassKg > 0.0) { "Piece mass must be finite and positive" }
    }

    override fun update(
        world: World<Body>,
        robotBody: Body,
        gamePieces: MutableList<Body>,
        intakeApplied: Boolean,
        flywheelApplied: Boolean,
        transferApplied: Boolean,
        currentInventoryCount: Int,
        robotHeading: Double,
        robotX: Double,
        robotY: Double,
    ): Int {
        var newInventory = currentInventoryCount.coerceIn(0, maxCapacity)
        val descriptorDriven = bindings.isNotEmpty()
        val collectorActive = if (descriptorDriven) roleActive(SimInteractionRole.INTAKE_COLLECTOR) else intakeApplied
        val launcherActive = if (descriptorDriven) {
            roleActive(SimInteractionRole.PROJECTILE_LAUNCHER)
        } else {
            transferApplied && flywheelApplied
        }
        val frontX = robotX + cos(robotHeading) * intakeRangeMeters
        val frontY = robotY + sin(robotHeading) * intakeRangeMeters
        val captureRadiusSq = intakeRadiusMeters * intakeRadiusMeters

        if (collectorActive && newInventory < maxCapacity) {
            for (index in gamePieces.indices) {
                val piece = gamePieces[index]
                val dx = piece.transform.translationX - frontX
                val dy = piece.transform.translationY - frontY
                if (dx * dx + dy * dy < captureRadiusSq) {
                    world.removeBody(piece)
                    gamePieces.removeAt(index)
                    newInventory++
                    break
                }
            }
        }

        if (launcherActive && !launchWasApplied && newInventory > 0) {
            val newPiece = Body()
            val radius = pieceDiameterMeters / 2.0
            val shape = Geometry.createCircle(radius)
            val fixture = BodyFixture(shape)
            fixture.friction = 0.6
            fixture.restitution = 0.3
            fixture.density = pieceMassKg / shape.getArea()
            newPiece.addFixture(fixture)
            newPiece.setMass(MassType.NORMAL)
            newPiece.linearDamping = 1.5
            newPiece.angularDamping = 1.5
            newPiece.translate(
                robotX + cos(robotHeading) * (intakeRangeMeters + radius + 0.05),
                robotY + sin(robotHeading) * (intakeRangeMeters + radius + 0.05),
            )

            val planarSpeed = launchSpeedMps * cos(Math.toRadians(launchElevationDeg))
            newPiece.linearVelocity = Vector2(
                robotBody.linearVelocity.x + cos(robotHeading) * planarSpeed,
                robotBody.linearVelocity.y + sin(robotHeading) * planarSpeed,
            )
            world.addBody(newPiece)
            gamePieces.add(newPiece)
            newInventory--
        }

        launchWasApplied = launcherActive
        return newInventory
    }

    private fun roleActive(role: SimInteractionRole): Boolean {
        for (index in bindings.indices) {
            val binding = bindings[index]
            if (binding.role == role && abs(binding.signal.value) >= binding.threshold) return true
        }
        return false
    }

    override fun reset() {
        launchWasApplied = false
    }

    companion object {
        /** Creates one deterministic model from validated subsystem descriptors. */
        fun fromSubsystems(subsystems: List<SubsystemDocument>): ConfigurableGamePieceInteractionModel {
            val interactions = subsystems.asSequence()
                .filter { it.implementation.simulation.interaction.role != SimInteractionRole.NONE }
                .sortedBy { it.uid }
                .toList()
            require(interactions.count { it.implementation.simulation.interaction.role == SimInteractionRole.INTAKE_COLLECTOR } <= 1) {
                "Only one intake collector interaction is supported by the chassis field model"
            }
            require(interactions.count { it.implementation.simulation.interaction.role == SimInteractionRole.PROJECTILE_LAUNCHER } <= 1) {
                "Only one projectile launcher interaction is supported by the chassis field model"
            }

            var maxCapacity = 1
            var intakeRange = 0.35
            var intakeRadius = 0.15
            var launchSpeed = 8.0
            var launchElevation = 0.0
            val bindings = ArrayList<DescriptorInteractionBinding>(interactions.size)
            for (subsystem in interactions) {
                val interaction = subsystem.implementation.simulation.interaction
                val actuatorId = requireNotNull(interaction.triggerActuatorId) {
                    "Subsystem '${subsystem.uid}' interaction is missing triggerActuatorId"
                }
                bindings += DescriptorInteractionBinding(
                    role = interaction.role,
                    signal = SimAppliedOutputRegistry.register(subsystem.uid, actuatorId),
                    threshold = interaction.triggerThreshold,
                )
                when (interaction.role) {
                    SimInteractionRole.INTAKE_COLLECTOR -> {
                        maxCapacity = maxCapacity.coerceAtLeast(interaction.storageCapacity)
                        intakeRange = interaction.intakeDistanceMeters
                        intakeRadius = interaction.captureRadiusMeters
                    }
                    SimInteractionRole.PROJECTILE_LAUNCHER -> {
                        launchSpeed = interaction.launchSpeedMps
                        launchElevation = interaction.launchElevationDeg
                    }
                    SimInteractionRole.CONVEYOR_INDEXER -> maxCapacity = maxCapacity.coerceAtLeast(interaction.storageCapacity)
                    SimInteractionRole.NONE -> Unit
                }
            }
            return ConfigurableGamePieceInteractionModel(
                maxCapacity = maxCapacity,
                intakeRangeMeters = intakeRange,
                intakeRadiusMeters = intakeRadius,
                launchSpeedMps = launchSpeed,
                launchElevationDeg = launchElevation,
                pieceDiameterMeters = 0.15,
                pieceMassKg = 0.24,
                bindings = bindings.toTypedArray(),
            )
        }
    }
}
