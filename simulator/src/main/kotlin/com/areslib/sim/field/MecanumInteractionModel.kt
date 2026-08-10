package com.areslib.sim.field

import com.areslib.sim.SimInteractionModel
import com.areslib.sim.model.MecanumRobotDouble
import com.areslib.sim.infra.VirtualDriverStation
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
class MecanumInteractionModel(private val robotDouble: MecanumRobotDouble) : SimInteractionModel {
    private val intakeRange = 0.35 // Meters from robot center
    private val shootForce = 8.0 // Linear impulse

    override fun update(
        world: World<Body>,
        robotBody: Body,
        gamePieces: MutableList<Body>,
        driverStation: VirtualDriverStation,
        currentInventoryCount: Int,
        robotHeading: Double,
        robotX: Double,
        robotY: Double
    ): Int {
        var newInventory = currentInventoryCount
        val intakeActive = driverStation.isIntaking
        val shooterActive = driverStation.isFlywheelOn

        // Calculate front of robot vector
        val frontX = robotX + cos(robotHeading) * intakeRange
        val frontY = robotY + sin(robotHeading) * intakeRange
        val frontVec = Vector2(frontX, frontY)

        // 1. INTAKE LOGIC
        if (intakeActive && newInventory < 3) {
            val iterator = gamePieces.iterator()
            while (iterator.hasNext()) {
                val piece = iterator.next()
                val dist = piece.transform.translation.distance(frontVec)
                if (dist < 0.12) {
                    world.removeBody(piece)
                    iterator.remove()
                    newInventory++
                    break // Intake one at a time
                }
            }
        }

        // 2. SHOOTING LOGIC
        val isTransferring = driverStation.isTransferring
        if (isTransferring && shooterActive && newInventory > 0) {
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
            
            // Consume the transfer button so it doesn't shoot 50 balls per second
            driverStation.isTransferring = false
        }

        return newInventory
    }
}
