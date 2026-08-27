package com.areslib.frc.hardware

import com.areslib.hardware.actuator.ClimberIO as SharedClimberIO
import com.areslib.hardware.actuator.CowlIO as SharedCowlIO
import com.areslib.hardware.actuator.FeederIO as SharedFeederIO
import com.areslib.hardware.actuator.FloorIO as SharedFloorIO
import com.areslib.hardware.actuator.FlywheelIO as SharedFlywheelIO
import com.areslib.hardware.actuator.IntakeIO as SharedIntakeIO

// Season-package aliases; interface contracts, units, and safe defaults live in ARESLib.
typealias FlywheelIO = SharedFlywheelIO
typealias CowlIO = SharedCowlIO
typealias IntakeIO = SharedIntakeIO
typealias FeederIO = SharedFeederIO
typealias ClimberIO = SharedClimberIO
typealias FloorIO = SharedFloorIO
