package com.areslib.ftc.hardware

import com.areslib.hardware.actuator.MotorIO
import com.areslib.hardware.actuator.ServoIO
import com.areslib.hardware.sensor.ColorSensorIO
import com.areslib.hardware.sensor.DistanceSensorIO
import com.areslib.hardware.sensor.MultizoneDistanceSensorIO
import com.areslib.hardware.drive.OdometryIO
import com.areslib.math.geometry.Pose2d

/**
 * Analog voltage input IO adapter for SRS Hub analog ports $[0 \dots 3]$.
 *
 * @param srsHub Active [SrsHubDriver] instance.
 * @param port SRS Hub analog port channel $[0 \dots 3]$.
 *
 * @see AnalogVoltageInput
 */
class SrsHubAnalogIO(private val srsHub: SrsHubDriver, private val port: Int) : AnalogVoltageInput {
    /** Measured analog input voltage in Volts ($V$). */
    override val voltage: Double
        get() = srsHub.getAnalogVoltage(port)
}

/**
 * Digital channel IO adapter for SRS Hub digital ports $[0 \dots 3]$.
 *
 * @param srsHub Active [SrsHubDriver] instance.
 * @param port SRS Hub digital port channel $[0 \dots 3]$.
 */
class SrsHubDigitalIO(private val srsHub: SrsHubDriver, private val port: Int) {
    /** Reads digital pin logic state (`true` for HIGH, `false` for LOW). */
    fun getState(): Boolean {
        return srsHub.getDigitalState(port)
    }
}

/**
 * PWM Servo output IO adapter for SRS Hub servo ports $[0 \dots 3]$.
 *
 * @param srsHub Active [SrsHubDriver] instance.
 * @param port SRS Hub PWM servo port channel $[0 \dots 3]$.
 *
 * @see ServoIO
 */
class SrsHubServoIO(private val srsHub: SrsHubDriver, private val port: Int) : ServoIO {
    private var currentTarget = 0.0

    /** Target normalized servo position $[0.0, 1.0]$. Writes trigger SRS Hub PWM duty cycle updates. */
    override var position: Double
        get() = currentTarget
        set(value) {
            currentTarget = value
            srsHub.setPwmDutyCycle(port, value)
        }
}

/**
 * SRS Hub Adapter for the GoBilda Prism RGB LED Driver (SKU 3118-2855-0001).
 * Commands microsecond pulse widths ($500\mu s \dots 2500\mu s$) directly through SRS Hub PWM port [port].
 *
 * @param srsHub Active [SrsHubDriver] instance.
 * @param port SRS Hub PWM Servo port index $[0 \dots 3]$ (default 0).
 *
 * @see com.areslib.hardware.actuator.PrismDriverIO
 */
class SrsHubPrismDriverIO(
    private val srsHub: SrsHubDriver,
    val port: Int = 0
) : com.areslib.hardware.actuator.PrismDriverIO {

    override var currentPulseWidthUs: Int = 1000
        private set

    override var maxBrightnessPercent: Int = 75

    /**
     * Commands equivalent PWM pulse width in microseconds ($\mu s$), mapping $[500\mu s, 2500\mu s]$ to normalized $[0.0, 1.0]$.
     *
     * @param pulseWidthUs Pulse width duration in microseconds ($\mu s$).
     */
    override fun setPulseWidthUs(pulseWidthUs: Int) {
        val clampedUs = pulseWidthUs.coerceIn(500, 2500)
        currentPulseWidthUs = clampedUs

        // Map 500µs - 2500µs to SRS Hub PWM duty cycle (0.0 to 1.0)
        val normalizedPos = (clampedUs - 500.0) / 2000.0
        srsHub.setPwmDutyCycle(port, normalizedPos)
    }

    /**
     * Converts RGB color values into equivalent HSV hue degrees and sets matching PWM pulse width.
     *
     * @param r Red intensity $[0, 255]$.
     * @param g Green intensity $[0, 255]$.
     * @param b Blue intensity $[0, 255]$.
     */
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

    /** Resets the Prism driver to solid red safe preset. */
    override fun safe() {
        setPreset(com.areslib.hardware.actuator.PrismPwmPreset.SOLID_RED)
    }

    /** No-op refresh hook (actuator is write-only). */
    override fun refresh() {}

    /** Logs current pulse width ($\mu s$) to telemetry network. */
    override fun logTelemetry(telemetry: com.areslib.telemetry.ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/PulseWidthUs", currentPulseWidthUs.toDouble())
    }
}

/**
 * Quadrature encoder IO adapter for SRS Hub encoder channels $[0 \dots 3]$.
 *
 * @param srsHub Active [SrsHubDriver] instance.
 * @param port SRS Hub encoder port channel $[0 \dots 3]$.
 *
 * @see MotorIO
 */
class SrsHubEncoderIO(private val srsHub: SrsHubDriver, private val port: Int) : MotorIO {
    override var power: Double
        get() = 0.0
        set(@Suppress("UNUSED_PARAMETER") value) {}

    override val velocity: Double
        get() = 0.0

    /** Encoder count position read directly from 256-byte bulk telemetry buffer. */
    override val position: Double
        get() = srsHub.readEncoder(port).toDouble()

    /** No-op reset hook (managed by SRS Hub internal registers). */
    override fun resetEncoder() {}
}

/**
 * Absolute analog encoder IO adapter for SRS Hub analog ports $[0 \dots 3]$.
 *
 * @param srsHub Active [SrsHubDriver] instance.
 * @param port SRS Hub analog port channel $[0 \dots 3]$.
 * @param version REV encoder revision variant.
 * @param ticksPerRev Encoder tick resolution rating ($ticks/rev$, default 8192.0).
 *
 * @see MotorIO
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

    /** Position in ticks calculated from analog voltage telemetry. */
    override val position: Double
        get() {
            val normalized = srsHub.getAnalogVoltage(port) / version.maxVoltage
            return (normalized * ticksPerRev) - offset
        }

    /** Calibrates zero position offset reference to current analog voltage position. */
    override fun resetEncoder() {
        offset = (srsHub.getAnalogVoltage(port) / version.maxVoltage) * ticksPerRev
    }
}

/**
 * Absolute PWM encoder IO adapter for SRS Hub PWM ports $[0 \dots 3]$.
 *
 * @param srsHub Active [SrsHubDriver] instance.
 * @param port SRS Hub PWM port channel $[0 \dots 3]$.
 * @param version REV encoder revision variant.
 * @param ticksPerRev Encoder tick resolution rating ($ticks/rev$, default 8192.0).
 *
 * @see MotorIO
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

    /** Refreshes SRS Hub telemetry registers. */
    fun updateInputs() {
        srsHub.update()
    }

    override val velocity: Double
        get() = 0.0

    /** Position in ticks calculated from PWM pulse width duration ($\mu s$). */
    override val position: Double
        get() {
            val pulseUs = srsHub.getPwmPulseWidth(port).toDouble()
            val range = version.maxPulseUs - version.minPulseUs
            val normalized = if (range != 0.0) (pulseUs - version.minPulseUs) / range else 0.0
            val clampedNormalized = if (normalized.isFinite()) normalized.coerceIn(0.0, 1.0) else 0.0
            return (clampedNormalized * ticksPerRev) - offset
        }

    /** Calibrates zero position offset reference to current PWM pulse width. */
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
 * Multizone distance sensor IO adapter for VL53L5CX sensors connected to SRS Hub I2C ports.
 *
 * @param srsHub Active [SrsHubDriver] instance.
 * @param port SRS Hub I2C port channel $[0 \dots 3]$.
 * @param rows Matrix row resolution (default 8).
 * @param columns Matrix column resolution (default 8).
 *
 * @see MultizoneDistanceSensorIO
 */
class SrsHubVL53L5CX(
    private val srsHub: SrsHubDriver,
    private val port: Int,
    override val rows: Int = 8,
    override val columns: Int = 8
) : MultizoneDistanceSensorIO {
    private var lastDistances = DoubleArray(0)

    /** Latest zone distances converted to meters ($m$). Access is zero-GC. */
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
 * Multi-spectral color and distance sensor IO adapter for REV Color Sensor V3 connected to SRS Hub I2C ports.
 *
 * @param srsHub Active [SrsHubDriver] instance.
 * @param port SRS Hub I2C port channel $[0 \dots 3]$.
 *
 * @see ColorSensorIO
 * @see DistanceSensorIO
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
 * Distance sensor IO adapter for REV 2m Distance Sensors (VL53L0X) connected to SRS Hub I2C ports.
 *
 * @param srsHub Active [SrsHubDriver] instance.
 * @param port SRS Hub I2C port channel $[0 \dots 3]$.
 *
 * @see DistanceSensorIO
 */
class SrsHubVL53L0X(
    private val srsHub: SrsHubDriver,
    private val port: Int
) : DistanceSensorIO {
    override val distanceMeters: Double
        get() = srsHub.getI2cDistanceMeters(port)
}

/**
 * Hardware odometry IO adapter for GoBilda Pinpoint odometry computers connected to SRS Hub I2C ports.
 *
 * @param srsHub Active [SrsHubDriver] instance.
 * @param port SRS Hub I2C port channel $[0 \dots 3]$.
 *
 * @see OdometryIO
 */
class SrsHubPinpointOdometry(
    private val srsHub: SrsHubDriver,
    private val port: Int
) : OdometryIO {
    /** Resets Pinpoint odometry position counter on SRS Hub port [port]. */
    override fun initialize(startPose: Pose2d) {
        srsHub.resetI2cOdometry(port)
        srsHub.registerPinpoint(port)
    }

    /** Updates odometry pose inputs into [OdometryInputs] container in meters ($m$) and radians ($rad$, CCW+). */
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

