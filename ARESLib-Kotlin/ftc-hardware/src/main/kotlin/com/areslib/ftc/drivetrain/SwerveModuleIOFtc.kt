package com.areslib.ftc.drivetrain

import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.AnalogInput
import com.areslib.hardware.drive.SwerveModuleIO
import com.areslib.hardware.drive.SwerveModuleInputs

/**
 * Physical Swerve Module IO Hardware Adapter for FTC target platforms.
 *
 * Wraps a drive `DcMotorEx`, steer `DcMotorEx`, and an absolute `AnalogInput` encoder for an FTC Swerve Pod (e.g., Axon, GoBilda Swerve).
 * Utilizes a dedicated 200Hz background thread (`ARES-SwerveModuleIOFtc-Analog-Thread`) for non-blocking analog voltage sampling.
 *
 * ### Hardware Boundary & Physical Units:
 * - Drive Motor Position: Radians ($rad$) using 2048 CPR tick conversion ($2\pi / 2048$).
 * - Drive Motor Velocity: Radians per second ($rad/s$).
 * - Steer Absolute Encoder: Radians ($rad$) scaled from $0.0\text{V} \dots 3.3\text{V}$ analog absolute voltage:
 *   $$\theta_{steer} = \frac{V_{analog}}{3.3} \cdot 2\pi \text{ rad}$$
 * - Angle Convention: **Counter-Clockwise (CCW) Positive** standard.
 * - Motor Duty Cycle Effort: Normalized ratio $[-1.0, 1.0]$.
 *
 * ### Zero-GC Execution Compliance:
 * High-frequency update functions ([updateInputs], [setDesiredPower]) mutate primitive properties on pre-allocated [SwerveModuleInputs] instances,
 * guaranteeing zero dynamic heap allocations during 50Hz–100Hz execution.
 *
 * @param driveMotor REV Expansion Hub `DcMotorEx` driving wheel rotation.
 * @param steerMotor REV Expansion Hub `DcMotorEx` steering module pod rotation.
 * @param analogEncoder Absolute analog position sensor (e.g. MA3, Lamprey, Axon encoder).
 *
 * @see SwerveModuleIO
 * @see SwerveModuleInputs
 */
class SwerveModuleIOFtc(
    private val driveMotor: DcMotorEx,
    private val steerMotor: DcMotorEx,
    private val analogEncoder: AnalogInput
) : SwerveModuleIO, AutoCloseable {


    private var lastDrivePosition = 0.0
    private var lastDriveVelocity = 0.0
    private var lastSteerAbsolute = 0.0
    private var lastWarningTime = 0L

    private val lock = Any()
    @Volatile private var running = true
    private var latestVoltage = 0.0
    private var latestVoltageValid = false

    private val samplingThread = Thread {
            while (running) {
                try {
                    val volt = analogEncoder.voltage
                    synchronized(lock) {
                        latestVoltageValid = volt.isFinite() && volt in 0.0..3.3
                        if (latestVoltageValid) latestVoltage = volt
                    }
                } catch (_: Exception) {
                    synchronized(lock) { latestVoltageValid = false }
                }
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "ARES-SwerveModuleIOFtc-Analog-Thread"
        }

    init {
        samplingThread.start()
    }

    /**
     * Polling update cycle reading drive position, drive velocity, and absolute steer angle into [SwerveModuleInputs].
     * Zero-GC allocation loop.
     *
     * @param inputs Telemetry struct populated with current physical sensor values.
     */
    override fun updateInputs(inputs: SwerveModuleInputs) {
        var drivePositionValid = false
        try {
            val position = driveMotor.currentPosition * 2.0 * Math.PI / 2048.0
            if (position.isFinite()) {
                lastDrivePosition = position
                drivePositionValid = true
            }
        } catch (e: Exception) {
            logWarning("Drive position read failed: ${e.message}")
        }

        var driveVelocityValid = false
        try {
            val velocity = driveMotor.velocity * 2.0 * Math.PI / 2048.0
            if (velocity.isFinite()) {
                lastDriveVelocity = velocity
                driveVelocityValid = true
            }
        } catch (e: Exception) {
            logWarning("Drive velocity read failed: ${e.message}")
        }

        val steerValid: Boolean
        synchronized(lock) {
            steerValid = latestVoltageValid
            if (steerValid) lastSteerAbsolute = (latestVoltage / 3.3) * 2.0 * Math.PI
        }

        inputs.drivePositionRads = lastDrivePosition
        inputs.driveVelocityRadsPerSec = lastDriveVelocity
        inputs.steerAbsolutePositionRads = lastSteerAbsolute
        inputs.drivePositionValid = drivePositionValid
        inputs.driveVelocityValid = driveVelocityValid
        inputs.steerAbsoluteValid = steerValid
        inputs.timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
    }

    /**
     * Commands motor duty-cycle powers for drive and steer actuators.
     * Zero-GC allocation loop.
     *
     * @param drivePower Normalized drive motor power (-1.0 to 1.0).
     * @param steerPower Normalized steer motor power (-1.0 to 1.0).
     */
    override fun setDesiredPower(drivePower: Double, steerPower: Double) {
        try {
            driveMotor.power = finitePower(drivePower)
        } catch (e: Exception) {
            logWarning("Drive setPower failed: ${e.message}")
        }

        try {
            steerMotor.power = finitePower(steerPower)
        } catch (e: Exception) {
            logWarning("Steer setPower failed: ${e.message}")
        }
    }

    private fun logWarning(msg: String) {
        val now = com.areslib.util.RobotClock.currentTimeMillis()
        if (now - lastWarningTime > 2000) {
            System.err.println("SwerveModuleIOFtc Warning: $msg")
            lastWarningTime = now
        }
    }

    /**
     * Terminates the analog sampling background thread and unregisters hardware resources.
     */
    override fun close() {
        running = false
        samplingThread.interrupt()
        if (Thread.currentThread() !== samplingThread) {
            try {
                samplingThread.join(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun finitePower(power: Double): Double =
        if (power.isFinite()) power.coerceIn(-1.0, 1.0) else 0.0
}
