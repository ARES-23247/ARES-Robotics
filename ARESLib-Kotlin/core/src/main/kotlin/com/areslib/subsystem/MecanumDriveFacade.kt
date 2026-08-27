package com.areslib.subsystem

import com.areslib.Store
import com.areslib.control.tuning.PIDFCoefficients

/**
 * A highly simplified, student-facing modular facade for a Mecanum drivetrain subsystem.
 *
 * Inherits all coordinate transformation, heading holding, path following, and drive logic
 * from [HolonomicDriveFacade].
 */
class MecanumDriveFacade @kotlin.jvm.JvmOverloads constructor(
    store: Store,
    headingGains: PIDFCoefficients = PIDFCoefficients(2.2, 0.0, 0.12),
    headingDeadzoneDeg: Double = 0.75
) : HolonomicDriveFacade(store, headingGains, headingDeadzoneDeg)
