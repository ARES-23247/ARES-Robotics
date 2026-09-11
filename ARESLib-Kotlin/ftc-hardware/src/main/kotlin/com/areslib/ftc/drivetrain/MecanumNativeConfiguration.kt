package com.areslib.ftc.drivetrain

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.PIDFCoefficients

/** Cold-path, owned snapshots of channel-specific RUN_USING_ENCODER coefficients. */
internal class MecanumNativeConfiguration(private val motors: Array<DcMotorEx>) {
    private val coefficients = Array(motors.size) { index ->
        val value = motors[index].getPIDFCoefficients(DcMotor.RunMode.RUN_USING_ENCODER)
        require(valid(value.p, value.i, value.d, value.f)) { "Invalid native motor coefficients" }
        PIDFCoefficients(value.p, value.i, value.d, value.f)
    }
    var valid = true

    fun matches(kp: Double, ki: Double, kd: Double, kf: Double?): Boolean =
        valid && coefficients.all { it.p == kp && it.i == ki && it.d == kd && (kf == null || it.f == kf) }

    // Call only after neutral succeeds. Publish the snapshot only after every channel accepts it;
    // a failed partial write must never suppress a retry or replace the saved F coefficients.
    fun apply(kp: Double, ki: Double, kd: Double, kf: Double?) {
        valid = false
        for (index in motors.indices) {
            motors[index].setPIDFCoefficients(
                DcMotor.RunMode.RUN_USING_ENCODER,
                PIDFCoefficients(kp, ki, kd, kf ?: coefficients[index].f)
            )
        }
        for (value in coefficients) {
            value.p = kp
            value.i = ki
            value.d = kd
            if (kf != null) value.f = kf
        }
        valid = true
    }

    companion object {
        fun valid(kp: Double, ki: Double, kd: Double, kf: Double?): Boolean =
            kp.isFinite() && ki.isFinite() && kd.isFinite() && (kf == null || kf.isFinite())
    }
}

/** Resolve every channel even after a failed lookup so all reachable motors can be stopped. */
internal fun resolveMecanumMotors(hardwareMap: HardwareMap, names: Array<String>): Array<DcMotorEx> {
    val resolved = arrayOfNulls<DcMotorEx>(names.size)
    var failure: Exception? = null
    for (index in names.indices) {
        try {
            resolved[index] = hardwareMap.get(DcMotorEx::class.java, names[index])
        } catch (cause: Exception) {
            if (failure == null) failure = cause else failure.addSuppressed(cause)
        }
    }
    try {
        require(names.all { it.isNotBlank() } && names.toSet().size == names.size) {
            "Drivetrain motor names must be nonblank and distinct"
        }
        for (index in resolved.indices) for (prior in 0 until index) {
            require(resolved[index] == null || resolved[index] !== resolved[prior]) {
                "Drivetrain channels must resolve to distinct motors"
            }
        }
    } catch (cause: Exception) {
        if (failure == null) failure = cause else failure.addSuppressed(cause)
    }
    if (failure != null) {
        for (motor in resolved) {
            try { motor?.power = 0.0 } catch (cause: Exception) { failure.addSuppressed(cause) }
        }
        throw failure
    }
    return Array(names.size) { checkNotNull(resolved[it]) }
}
