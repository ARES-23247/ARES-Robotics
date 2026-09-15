package com.qualcomm.robotcore.hardware.configuration.typecontainers

/** Minimal desktop counterpart of the SDK encoder-resolution getter/setter used by ARES. */
class MotorConfigurationType {
    /** Unknown until the fixture supplies its encoder calibration; no guessed healthy default. */
    var ticksPerRev: Double = Double.NaN
}
