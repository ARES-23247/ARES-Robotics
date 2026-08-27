package com.areslib.sim.model

import com.areslib.sim.infra.MockI2cDeviceSynch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-fidelity Digital Twin (Body Double) physical simulator for the ARES Swerve Robot.
 * Models a 4-module Swerve chassis and Superstructure (Intake, Transfer, Flywheel Shooter).
 * Seamlessly encodes real-time physical states into binary I2C registers for OctoQuad and SRS Hub.
 */
class SwerveRobotDouble {
    
    // Virtual I2C hardware clients
    val octoQuadI2c = MockI2cDeviceSynch()
    val srsHubI2c = MockI2cDeviceSynch()

    init {
        // Pre-populate OctoQuad register boundaries for driver chip verification checks
        octoQuadI2c.registers[0x00] = 0x51 // CHIP_ID
        octoQuadI2c.registers[0x01] = 3    // FW_MAJ
        octoQuadI2c.registers[0x02] = 0    // FW_MIN
    }

    // 1. Swerve Module States (Physical Properties)
    // Indexes: 0 = Front Left (FL), 1 = Front Right (FR), 2 = Back Left (BL), 3 = Back Right (BR)
    val moduleX = doubleArrayOf(0.225, 0.225, -0.225, -0.225)
    val moduleY = doubleArrayOf(0.225, -0.225, 0.225, -0.225)
    
    val drivePositions = DoubleArray(4) // Ticks
    val driveVelocities = DoubleArray(4) // Ticks/sec
    val steerAngles = DoubleArray(4) // Radians (0 to 2pi)

    // Applied actuator inputs from the control system
    val drivePowers = DoubleArray(4)
    val steerPowers = DoubleArray(4)

    // Actuator specs
    private val maxDriveSpeedMps = 4.0 // Max linear speed
    private val maxSteerSpeedRadS = 8.0 // Max steer angular velocity (CR Servo speed)
    private val driveEncoderTicksPerMeter = 2048.0 / (2.0 * Math.PI * 0.05) // 2048 ticks/rev, 5cm radius wheel
    
    // Absolute encoder PWM pulse bounds (REV Through-Bore)
    private val minPulseUs = 1.0
    private val maxPulseUs = 1024.0

    // 2. Superstructure States
    // --- Intake Motor ---
    var intakePower = 0.0
    val intakePosition = DoubleArray(1) // Ticks
    private val maxIntakeTicksPerSec = 1000.0

    // --- Transfer Motor ---
    var transferPower = 0.0
    val transferPosition = DoubleArray(1) // Ticks
    private val maxTransferTicksPerSec = 800.0

    // --- Flywheel Motor (with angular momentum) ---
    var flywheelPower = 0.0 // 0.0 to 1.0 commanded power
    var flywheelRPM = 0.0 // Current angular velocity in RPM
        private set
    val flywheelPosition = DoubleArray(1) // Ticks (encoder)
    
    // Flywheel physics parameters
    private val flywheelMOI = 0.002 // Moment of inertia (kg·m²) — 4" aluminum flywheel
    private val flywheelMotorStallTorque = 0.6 // Nm (goBILDA 5202 geared motor)
    private val flywheelMotorFreeSpeedRPM = 6000.0 // Free speed RPM
    private val flywheelFrictionTorque = 0.008 // Nm drag from bearings
    private val flywheelEncoderTicksPerRev = 2048.0
    var flywheelTargetRPM = 4000.0 // Setpoint for the P-controller

    // Game piece tracking for Floodgate load spikes
    var hasBallInIntake = false
    var hasBallInTransfer = false

    /**
     * Steps the physical model of the robot forward by dt seconds.
     * Updates all relative and absolute sensors in the virtual I2C register maps.
     */
    fun update(dt: Double) {
        val endian = ByteOrder.LITTLE_ENDIAN

        // --- A. SWERVE CHASSIS PHYSICS ---
        for (i in 0 until 4) {
            // 1. Drive Motors (Movement)
            val targetVel = drivePowers[i] * maxDriveSpeedMps * driveEncoderTicksPerMeter
            // 1st-order filter for drive motor acceleration inertia
            driveVelocities[i] = driveVelocities[i] + (targetVel - driveVelocities[i]) * 10.0 * dt
            drivePositions[i] += driveVelocities[i] * dt

            // Update Mock OctoQuad Drive relative encoder registers (REG_ENC_0 + channel * 4) -> 32-bit Int
            val posBytes = ByteBuffer.allocate(4).order(endian).putInt(drivePositions[i].toInt()).array()
            System.arraycopy(posBytes, 0, octoQuadI2c.registers, 0x20 + (i * 4), 4)

            // Update Mock OctoQuad Drive velocity registers (REG_VEL_0 + channel * 4) -> 32-bit Int
            val velBytes = ByteBuffer.allocate(4).order(endian).putInt(driveVelocities[i].toInt()).array()
            System.arraycopy(velBytes, 0, octoQuadI2c.registers, 0x40 + (i * 4), 4)

            // 2. Steer Servos (Rotation via CR Servos)
            val steerOmega = steerPowers[i] * maxSteerSpeedRadS
            steerAngles[i] = (steerAngles[i] + steerOmega * dt) % (2.0 * Math.PI)
            if (steerAngles[i] < 0.0) steerAngles[i] += 2.0 * Math.PI

            // Map absolute angle to PWM pulse width (REV absolute PWM)
            val pulseUs = minPulseUs + (steerAngles[i] / (2.0 * Math.PI)) * (maxPulseUs - minPulseUs)

            // Update Mock OctoQuad Steer absolute encoder pulse width registers (Channel index 4 + i) -> 16-bit Short
            val channelIndex = 4 + i
            val pulseBytes = ByteBuffer.allocate(2).order(endian).putShort(pulseUs.toInt().toShort()).array()
            System.arraycopy(pulseBytes, 0, octoQuadI2c.registers, 0x80 + (channelIndex * 2), 2)
        }

        // --- B. SUPERSTRUCTURE AUXILIARY MOTORS PHYSICS ---
        
        // 1. Intake Motor
        val intakeVel = intakePower * maxIntakeTicksPerSec
        intakePosition[0] += intakeVel * dt
        val intakeBytes = ByteBuffer.allocate(4).order(endian).putInt(intakePosition[0].toInt()).array()
        System.arraycopy(intakeBytes, 0, srsHubI2c.registers, 9, 4) // SRS Port 0 (Offset 9)

        // 2. Transfer Motor
        val transferVel = transferPower * maxTransferTicksPerSec
        transferPosition[0] += transferVel * dt
        val transferBytes = ByteBuffer.allocate(4).order(endian).putInt(transferPosition[0].toInt()).array()
        System.arraycopy(transferBytes, 0, srsHubI2c.registers, 13, 4) // SRS Port 1 (Offset 13)

        // 3. Flywheel Motor — Angular Momentum with P-controller
        val controllerOutput = if (flywheelPower > 0.0) {
            val error = flywheelTargetRPM - flywheelRPM
            (error * 0.003).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        
        val currentSpeedFraction = flywheelRPM / flywheelMotorFreeSpeedRPM
        val motorTorque = flywheelMotorStallTorque * controllerOutput * (1.0 - currentSpeedFraction).coerceAtLeast(0.0)
        
        val frictionSign = if (flywheelRPM > 0.1) 1.0 else 0.0
        val netTorque = motorTorque - flywheelFrictionTorque * frictionSign
        
        val angularAccelRadS2 = netTorque / flywheelMOI
        
        val deltaRPM = (angularAccelRadS2 * dt) * (60.0 / (2.0 * Math.PI))
        flywheelRPM = (flywheelRPM + deltaRPM).coerceAtLeast(0.0)
        
        if (flywheelPower == 0.0 && flywheelRPM < 10.0) {
            flywheelRPM = 0.0
        }
        
        val flywheelTicksPerSec = (flywheelRPM / 60.0) * flywheelEncoderTicksPerRev
        flywheelPosition[0] += flywheelTicksPerSec * dt
        val shooterBytes = ByteBuffer.allocate(4).order(endian).putInt(flywheelPosition[0].toInt()).array()
        System.arraycopy(shooterBytes, 0, srsHubI2c.registers, 17, 4) // SRS Port 2 (Offset 17)

        // --- C. BATTERY LOAD & FLOODGATE V2 SWITCH TELEMETRY ---
        val idleCurrent = 1.2
        var driveCurrent = 0.0
        for (i in 0 until 4) {
            driveCurrent += abs(drivePowers[i]) * 12.0
        }
        val intakeCurrent = abs(intakePower) * 6.0 + (if (hasBallInIntake) 3.5 else 0.0)
        val transferCurrent = abs(transferPower) * 5.0 + (if (hasBallInTransfer) 2.0 else 0.0)
        
        val flywheelCurrentDraw = abs(flywheelPower) * 22.0 * (1.0 - currentSpeedFraction).coerceAtLeast(0.1)
        
        val totalCurrent = (idleCurrent + driveCurrent + intakeCurrent + transferCurrent + flywheelCurrentDraw).coerceIn(0.0, 80.0)
        
        val floodgateVoltage = (totalCurrent / 80.0) * 3.3
        
        val rawAnalogVal = ((floodgateVoltage / 3.3) * 65535.0).toInt().coerceIn(0, 65535)
        val analogBytes = ByteBuffer.allocate(2).order(endian).putShort(rawAnalogVal.toShort()).array()
        
        System.arraycopy(analogBytes, 0, srsHubI2c.registers, 6, 2)
    }
}
