package com.areslib.sim.field

import com.areslib.sim.SimInteractionModel
import org.dyn4j.dynamics.Body
import org.dyn4j.dynamics.BodyFixture
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Vector2
import org.dyn4j.world.World
import kotlin.math.cos
import kotlin.math.sin

/**
 * Class implementation for Mecanum Interaction Model.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class MecanumInteractionModel : SimInteractionModel {
    private val intakeRange = 0.35 // Meters from robot center
    private val shootForce = 8.0 // Linear impulse
    private var transferWasApplied = false

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
        robotY: Double
    ): Int {
        var newInventory = currentInventoryCount
        // Calculate front of robot vector
        val frontX = robotX + cos(robotHeading) * intakeRange
        val frontY = robotY + sin(robotHeading) * intakeRange

        // 1. INTAKE LOGIC
        if (intakeApplied && newInventory < 3) {
            for (index in gamePieces.indices) {
                val piece = gamePieces[index]
                val dx = piece.transform.translationX - frontX
                val dy = piece.transform.translationY - frontY
                if (dx * dx + dy * dy < INTAKE_RADIUS_SQUARED) {
                    world.removeBody(piece)
                    gamePieces.removeAt(index)
                    newInventory++
                    break // Intake one at a time
                }
            }
        }

        // 2. SHOOTING LOGIC
        if (transferApplied && !transferWasApplied && flywheelApplied && newInventory > 0) {
            // Spawn a new ball
            val newBall = Body()
            val shape = Geometry.createCircle(0.075) // 0.15 diameter Note
            val fixture = BodyFixture(shape)
            fixture.friction = 0.6
            fixture.restitution = 0.3
            fixture.density = 0.24 / shape.getArea()
            newBall.addFixture(fixture)
            newBall.setMass(MassType.NORMAL)
            newBall.linearDamping = 1.5
            newBall.angularDamping = 1.5

            // Place it slightly in front of the robot so it doesn't collide with the drivetrain
            val spawnX = robotX + cos(robotHeading) * 0.4
            val spawnY = robotY + sin(robotHeading) * 0.4
            newBall.translate(spawnX, spawnY)

            // Apply shooting force
            val forceX = cos(robotHeading) * shootForce
            val forceY = sin(robotHeading) * shootForce
            newBall.applyImpulse(Vector2(forceX, forceY))

            world.addBody(newBall)
            gamePieces.add(newBall)
            newInventory--
            
        }

        transferWasApplied = transferApplied

        return newInventory
    }

    override fun reset() {
        transferWasApplied = false
    }

    private companion object {
        const val INTAKE_RADIUS_SQUARED = 0.12 * 0.12
    }
}
