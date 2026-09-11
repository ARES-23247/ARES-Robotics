package org.aresfirst.marvin.sim

import org.dyn4j.dynamics.Body
import org.dyn4j.dynamics.Force
import org.dyn4j.dynamics.Torque
import com.areslib.state.RobotState

/**
 * Applies proportional force and torque so the Dyn4j chassis tracks Redux drive setpoints.
 *
 * Redux linear velocities are interpreted according to `DriveState.isFieldCentric`; field-frame
 * commands are used directly and robot-frame commands are rotated once into Dyn4j's blue-origin
 * world. Angular velocity is CCW-positive radians per second. Each queued effort owns its values;
 * objects are recycled only after Dyn4j accumulates them. Like Dyn4j, this class is single-threaded.
 */
class Dyn4jSwerveModuleSim(
    private val kpLinear: Double = 50.0,
    private val kpAngular: Double = 20.0,
) {
    init {
        require(kpLinear.isFinite() && kpLinear > 0.0) { "Linear tracking gain must be finite and positive" }
        require(kpAngular.isFinite() && kpAngular > 0.0) { "Angular tracking gain must be finite and positive" }
    }

    private var freeForce: TrackingForce? = null
    private var freeTorque: TrackingTorque? = null
    private var freeForceCount = 0
    private var freeTorqueCount = 0

    // Cache only consumed objects, never pending ones or bodies. Manual queue clearing can simply
    // discard pending objects; the next tick borrows a different object, with no stale ownership flag.
    // Bound retained memory after bursts; normal update/step loops need just one of each.
    private inner class TrackingForce : Force() {
        var next: TrackingForce? = null
        override fun isComplete(elapsedTime: Double): Boolean {
            if (freeForceCount < 8) {
                next = freeForce
                freeForce = this
                freeForceCount++
            }
            return true
        }
    }

    private inner class TrackingTorque : Torque() {
        var next: TrackingTorque? = null
        override fun isComplete(elapsedTime: Double): Boolean {
            if (freeTorqueCount < 8) {
                next = freeTorque
                freeTorque = this
                freeTorqueCount++
            }
            return true
        }
    }

    private fun borrowForce(x: Double, y: Double): TrackingForce {
        val force = freeForce
        if (force == null) return TrackingForce().apply { set(x, y) }
        freeForce = force.next
        force.next = null
        freeForceCount--
        force.set(x, y)
        return force
    }

    private fun borrowTorque(value: Double): TrackingTorque {
        val torque = freeTorque
        if (torque == null) return TrackingTorque().apply { set(value) }
        freeTorque = torque.next
        torque.next = null
        freeTorqueCount--
        torque.set(value)
        return torque
    }

    /** Queues one tick's effort. Rejects invalid inputs/results before queuing or waking the body. */
    fun update(state: RobotState, robotBody: Body) {
        val heading = robotBody.transform.rotationAngle
        val targetVx = state.drive.xVelocityMetersPerSecond
        val targetVy = state.drive.yVelocityMetersPerSecond
        val targetOmega = state.drive.angularVelocityRadiansPerSecond
        val velocity = robotBody.linearVelocity
        val omega = robotBody.angularVelocity
        require(heading.isFinite() && targetVx.isFinite() && targetVy.isFinite() && targetOmega.isFinite()) {
            "Swerve heading and velocity command must be finite"
        }
        require(velocity.x.isFinite() && velocity.y.isFinite() && omega.isFinite()) {
            "Swerve velocity feedback must be finite"
        }
        val worldVx: Double
        val worldVy: Double
        if (state.drive.isFieldCentric) {
            worldVx = targetVx
            worldVy = targetVy
        } else {
            val cosine = kotlin.math.cos(heading)
            val sine = kotlin.math.sin(heading)
            worldVx = targetVx * cosine - targetVy * sine
            worldVy = targetVx * sine + targetVy * cosine
        }
        
        val forceX = (worldVx - velocity.x) * kpLinear
        val forceY = (worldVy - velocity.y) * kpLinear
        val torque = (targetOmega - omega) * kpAngular
        require(forceX.isFinite() && forceY.isFinite() && torque.isFinite()) {
            "Swerve tracking effort must be representable"
        }

        // These overloads wake movable bodies themselves and retain the supplied effort objects.
        if (robotBody.mass.mass != 0.0) robotBody.applyForce(borrowForce(forceX, forceY))
        if (robotBody.mass.inertia != 0.0) robotBody.applyTorque(borrowTorque(torque))
    }
}
