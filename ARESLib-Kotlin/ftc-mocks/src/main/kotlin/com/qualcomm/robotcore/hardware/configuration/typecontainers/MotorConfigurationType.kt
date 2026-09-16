package com.qualcomm.robotcore.hardware.configuration.typecontainers

/**
 * Minimal desktop counterpart of the SDK encoder-resolution getter/setter used by ARES.
 *
 * Represents motor configuration metadata from the FTC robot configuration XML files.
 * In desktop simulation, this type container holds physical motor specifications such as
 * ticks per revolution, maximum RPM, and gear reduction ratios required by control loops
 * to convert between raw encoder ticks and engineering units (radians, meters).
 *
 * The default value of [ticksPerRev] is [Double.NaN], reflecting that encoder calibration
 * is unassigned until explicitly supplied by the simulated robot fixture or hardware map.
 */
class MotorConfigurationType {
    /**
     * Number of encoder ticks per output shaft revolution for this motor configuration.
     *
     * Unknown ([Double.NaN]) until the fixture or test harness supplies its encoder calibration;
     * no guessed healthy default is assumed in simulation to ensure unconfigured motor fixtures
     * fail fast rather than silently producing corrupt velocity or position estimates.
     */
    var ticksPerRev: Double = Double.NaN

    /**
     * Constructs a default [MotorConfigurationType] with unassigned encoder resolution.
     */
    constructor()
}
