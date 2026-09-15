package com.areslib.subsystem

import com.areslib.action.RobotAction
import kotlin.math.abs
import kotlin.math.hypot

internal fun validDriveLimits(linear: Double, angular: Double): Boolean =
    linear.isFinite() && linear > 0.0 && angular.isFinite() && angular > 0.0

/** Projects translation onto a speed disk without overflowing at finite extreme inputs. */
internal fun RobotAction.JoystickDriveIntent.setLimitedVelocities(
    vx: Double, vy: Double, omega: Double, linearLimit: Double, angularLimit: Double,
): Boolean {
    if (!vx.isFinite() || !vy.isFinite() || !omega.isFinite() || !validDriveLimits(linearLimit, angularLimit)) {
        targetXVelocity = 0.0
        targetYVelocity = 0.0
        targetAngularVelocity = 0.0
        return false
    }
    val largest = maxOf(abs(vx), abs(vy))
    if (largest > 0.0) {
        val unitX = vx / largest
        val unitY = vy / largest
        val norm = hypot(unitX, unitY)
        if (norm > linearLimit / largest) {
            targetXVelocity = (unitX / norm) * linearLimit
            targetYVelocity = (unitY / norm) * linearLimit
        } else {
            targetXVelocity = vx
            targetYVelocity = vy
        }
    } else {
        targetXVelocity = 0.0
        targetYVelocity = 0.0
    }
    targetAngularVelocity = omega.coerceIn(-angularLimit, angularLimit)
    return true
}
