package com.areslib.ftc.hardware

import com.areslib.hardware.actuator.MotorIO
import com.areslib.hardware.actuator.ServoIO
import com.areslib.hardware.sensor.ColorSensorIO
import com.areslib.hardware.sensor.DistanceSensorIO
import com.areslib.hardware.sensor.MultizoneDistanceSensorIO
import com.areslib.hardware.drive.OdometryIO
import com.areslib.math.geometry.Pose2d

/**
 * Hardware IO abstraction layer for SRS Hub Analog Inputs.
 */
class SrsHubAnalogIO(private val srsHub: SrsHubDriver, private val port: Int) : AnalogVoltageInput {
    override val voltage: Double
        get() = srsHub.getAnalogVoltage(port)
}

/**
 * Hardware IO abstraction layer for SRS Hub Digital Inputs.
 */
class SrsHubDigitalIO(private val srsHub: SrsHubDriver, private val port: Int) {
    fun getState(): Boolean {
        return srsHub.getDigitalState(port)
    }
}

/**
 * Hardware IO abstraction layer for SRS Hub Servos.
 */
class SrsHubServoIO(private val srsHub: SrsHubDriver, private val port: Int) : ServoIO {
    private var currentTarget = 0.0

    override var position: Double
        get() = currentTarget
        set(value) {
            currentTarget = value
            srsHub.setPwmDutyCycle(port, value)
        }
}

/**
 * SRS Hub Adapter for the goBILDA Prism RGB LED Driver (SKU 3118-2855-0001).
 * Commands microsecond pulse widths (500µs to 2500µs) directly through SRS Hub PWM port [port].
 *
 * @param srsHub The SrsHubDriver instance.
 * @param port SRS Hub PWM Servo port index (0 to 3).
 */
class SrsHubPrismDriverIO(
    private val srsHub: SrsHubDriver,
    val port: Int = 0
) : com.areslib.hardware.actuator.PrismDriverIO {

    override var currentPulseWidthUs: Int = 1000
        private set

    override var maxBrightnessPercent: Int = 75

    override fun setPulseWidthUs(pulseWidthUs: Int) {
        val clampedUs = pulseWidthUs.coerceIn(500, 2500)
        currentPulseWidthUs = clampedUs

        // Map 500µs - 2500µs to SRS Hub PWM duty cycle (0.0 to 1.0)
        val normalizedPos = (clampedUs - 500.0) / 2000.0
        srsHub.setPwmDutyCycle(port, normalizedPos)
    }

    override fun setSolidColorRgb(r: Int, g: Int, b: Int) {
        val rNorm = (r.coerceIn(0, 255)) / 255.0
        val gNorm = (g.coerceIn(0, 255)) / 255.0
        val bNorm = (b.coerceIn(0, 255)) / 255.0

        val maxC = maxOf(rNorm, gNorm, bNorm)
        val minC = minOf(rNorm, gNorm, bNorm)
        val delta = maxC - minC

        val hue = when {
            delta < 1e-4 -> 0.0
            maxC == rNorm -> ((gNorm - bNorm) / delta) % 6.0
            maxC == gNorm -> ((bNorm - rNorm) / delta) + 2.0
            else -> ((rNorm - gNorm) / delta) + 4.0
        } * 60.0

        val normalizedHue = (if (hue < 0) hue + 360.0 else hue) / 360.0
        val pulseWidth = (1050 + normalizedHue * 899.0).toInt()
        setPulseWidthUs(pulseWidth)
    }

    override fun safe() {
        setPreset(com.areslib.hardware.actuator.PrismPwmPreset.SOLID_RED)
    }

    override fun refresh() {}

    override fun logTelemetry(telemetry: com.areslib.telemetry.ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/PulseWidthUs", currentPulseWidthUs.toDouble())
    }
}

/**
 * Hardware IO abstraction layer for SRS Hub Encoders.
 */
class SrsHubEncoderIO(private val srsHub: SrsHubDriver, private val port: Int) : MotorIO {
    override var power: Double
        get() = 0.0
        set(@Suppress("UNUSED_PARAMETER") value) {}

    override val velocity: Double
        get() = 0.0

    override val position: Double
        get() = srsHub.readEncoder(port).toDouble()

    override fun resetEncoder() {}
}

/**
 * Wrapper for an absolute analog encoder plugged into the SRS Hub.
 */
class SrsHubAbsoluteAnalogEncoder(
    private val srsHub: SrsHubDriver,
    private val port: Int,
    private val version: com.areslib.hardware.actuator.RevEncoderVersion = com.areslib.hardware.actuator.RevEncoderVersion.V1,
    private val ticksPerRev: Double = 8192.0
) : MotorIO {
    private var offset = 0.0

    override var power: Double
        get() = 0.0
        set(@Suppress("UNUSED_PARAMETER") value) {}

    override val velocity: Double
        get() = 0.0

    override val position: Double
        get() {
            val normalized = srsHub.getAnalogVoltage(port) / version.maxVoltage
            return (normalized * ticksPerRev) - offset
        }

    override fun resetEncoder() {
        offset = (srsHub.getAnalogVoltage(port) / version.maxVoltage) * ticksPerRev
    }
}

/**
 * Wrapper for an absolute PWM encoder plugged into the SRS Hub.
 */
class SrsHubAbsolutePWMEncoder(
    private val srsHub: SrsHubDriver,
    private val port: Int,
    private val version: com.areslib.hardware.actuator.RevEncoderVersion = com.areslib.hardware.actuator.RevEncoderVersion.V1,
    private val ticksPerRev: Double = 8192.0
) : MotorIO {
    private var offset = 0.0

    override var power: Double
        get() = 0.0
        set(@Suppress("UNUSED_PARAMETER") value) {}

    fun updateInputs() {
        srsHub.update()
    }

    override val velocity: Double
        get() = 0.0

    override val position: Double
        get() {
            val pulseUs = srsHub.getPwmPulseWidth(port).toDouble()
            val range = version.maxPulseUs - version.minPulseUs
            val normalized = if (range != 0.0) (pulseUs - version.minPulseUs) / range else 0.0
            val clampedNormalized = if (normalized.isFinite()) normalized.coerceIn(0.0, 1.0) else 0.0
            return (clampedNormalized * ticksPerRev) - offset
        }

    override fun resetEncoder() {
        val pulseUs = srsHub.getPwmPulseWidth(port).toDouble()
        val range = version.maxPulseUs - version.minPulseUs
        val normalized = if (range != 0.0) (pulseUs - version.minPulseUs) / range else 0.0
        val clampedNormalized = if (normalized.isFinite()) normalized.coerceIn(0.0, 1.0) else 0.0
        offset = clampedNormalized * ticksPerRev
    }
}

// ==========================================
// =         SRS HUB I2C SENSORS            =
// ==========================================

/**
 * Wrapper for a VL53L5CX multizone ToF sensor plugged into an I2C expansion port of the SRS Hub.
 */
class SrsHubVL53L5CX(
    private val srsHub: SrsHubDriver,
    private val port: Int,
    override val rows: Int = 8,
    override val columns: Int = 8
) : MultizoneDistanceSensorIO {
    private var lastDistances = DoubleArray(0)

    override val distancesMeters: DoubleArray
        get() {
            val raw = srsHub.getVL53L5CXDistances(port)
            if (raw.size != lastDistances.size) {
                lastDistances = DoubleArray(raw.size)
            }
            for (i in raw.indices) {
                lastDistances[i] = raw[i] / 1000.0
            }
            return lastDistances
        }
}

/**
 * Wrapper for an APDS9151 (REV Color Sensor V3) plugged into an I2C expansion port of the SRS Hub.
 */
class SrsHubRevColorSensorV3(
    private val srsHub: SrsHubDriver,
    private val port: Int
) : ColorSensorIO, DistanceSensorIO {
    override val red: Int
        get() = srsHub.getI2cColorRed(port)
    override val green: Int
        get() = srsHub.getI2cColorGreen(port)
    override val blue: Int
        get() = srsHub.getI2cColorBlue(port)
    override val alpha: Int
        get() = srsHub.getI2cColorAlpha(port)

    override val normalizedRgb: DoubleArray
        get() {
            val r = red
            val g = green
            val b = blue
            val a = alpha
            val sum = (r + g + b + a).toDouble()
            if (sum < 0.1) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            return doubleArrayOf(r / sum, g / sum, b / sum, a / sum)
        }

    override val distanceMeters: Double
        get() = srsHub.getI2cDistanceMeters(port)
}

/**
 * Wrapper for a VL53L0X (REV 2M Distance / Adafruit ToF) plugged into an I2C expansion port of the SRS Hub.
 */
class SrsHubVL53L0X(
    private val srsHub: SrsHubDriver,
    private val port: Int
) : DistanceSensorIO {
    override val distanceMeters: Double
        get() = srsHub.getI2cDistanceMeters(port)
}

/**
 * Wrapper for a GoBilda Pinpoint Odometry Computer plugged into an I2C expansion port of the SRS Hub.
 */
class SrsHubPinpointOdometry(
    private val srsHub: SrsHubDriver,
    private val port: Int
) : OdometryIO {
    override fun initialize(startPose: Pose2d) {
        srsHub.resetI2cOdometry(port)
        srsHub.registerPinpoint(port)
    }

    override fun updateInputs(inputs: com.areslib.hardware.drive.OdometryInputs) {
        srsHub.updateI2cOdometry()
        inputs.posX = srsHub.getI2cOdometryX(port) / 1000.0
        inputs.posY = srsHub.getI2cOdometryY(port) / 1000.0
        inputs.heading = srsHub.getI2cOdometryHeading(port)
        inputs.velX = srsHub.getI2cOdometryVelX(port) / 1000.0
        inputs.velY = srsHub.getI2cOdometryYVel(port) / 1000.0
        inputs.headingVelocity = srsHub.getI2cOdometryHeadingVel(port)
        inputs.timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
    }
}
