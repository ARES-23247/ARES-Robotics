package com.areslib.math.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

// Shared by mutable rotations and immutable retained poses. Inputs must be a unit quaternion.
// Rz(yaw) Ry(pitch) Rx(roll); at a pitch singularity choose roll zero and the matching yaw.
internal fun quaternionRoll(w: Double, x: Double, y: Double, z: Double): Double {
    if (atPitchSingularity(1.0 - 2.0 * (y*y + z*z), 2.0 * (w*z + x*y))) return 0.0
    return atan2(2.0 * (w*x + y*z), 1.0 - 2.0 * (x*x + y*y))
}

internal fun quaternionPitch(w: Double, x: Double, y: Double, z: Double): Double =
    atan2(2.0 * (w*y - z*x), hypot(1.0 - 2.0 * (y*y + z*z), 2.0 * (w*z + x*y)))

internal fun quaternionYaw(w: Double, x: Double, y: Double, z: Double): Double {
    val sinYawCosPitch = 2.0 * (w*z + x*y)
    val cosYawCosPitch = 1.0 - 2.0 * (y*y + z*z)
    if (atPitchSingularity(cosYawCosPitch, sinYawCosPitch)) {
        return atan2(2.0 * (w*z - x*y), 1.0 - 2.0 * (x*x + z*z))
    }
    return atan2(sinYawCosPitch, cosYawCosPitch)
}

// At this roundoff-sized boundary the two Euler axes are indistinguishable. Use the
// same decision for roll and yaw so their chosen representation remains coherent.
private fun atPitchSingularity(r00: Double, r10: Double): Boolean = abs(r00) + abs(r10) <= 1e-15
