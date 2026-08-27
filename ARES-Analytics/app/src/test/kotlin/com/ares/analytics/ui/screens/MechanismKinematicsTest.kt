package com.ares.analytics.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MechanismKinematicsTest {

    @Test
    fun testArmMotorSizingCalculation() {
        val analysis = KinematicsMath.calculateMotorSizing(
            motor = RoboticsMotor.NEO,
            motorCount = 1,
            gearRatio = 60.0,
            mechanismKind = MechanismSizingKind.ARM,
            massKg = 3.0,
            armLengthM = 0.50,
            travelDistance = Math.toRadians(90.0)
        )

        // Peak load torque for 3kg arm of length 0.5m (CG at 0.25m): 3.0 * 9.81 * 0.25 = ~7.36 Nm
        assertEquals(7.3575, analysis.loadTorqueNm, 1e-2)
        // Output stall torque: 2.6 * 60 * 0.85 = ~132.6 Nm
        assertEquals(132.6, analysis.outputStallTorqueNm, 1e-1)
        // Stall margin: ~18.0x
        assertTrue(analysis.stallMargin > 15.0, "High gear ratio should provide ample torque margin")
        assertTrue(analysis.meetsTeachingMarginGuideline, "Fixture should meet the teaching margin guideline")
        assertTrue(analysis.travelTimeSec < 2.0, "90-degree travel time should be fast")
    }

    @Test
    fun testUndergearedMechanismWarning() {
        val analysis = KinematicsMath.calculateMotorSizing(
            motor = RoboticsMotor.NEO,
            motorCount = 1,
            gearRatio = 2.0, // severely undergeared for a 10kg arm!
            mechanismKind = MechanismSizingKind.ARM,
            massKg = 10.0,
            armLengthM = 0.80
        )

        assertFalse(analysis.meetsTeachingMarginGuideline, "Undergeared fixture should require review")
        assertTrue(analysis.stallMargin < 1.0, "Undergeared arm should have stall margin < 1.0")
        assertFalse(analysis.warningMessage.isNullOrBlank(), "Warning message should be generated")
    }

    @Test
    fun rejectsInvalidSizingInputs() {
        assertFailsWith<IllegalArgumentException> {
            KinematicsMath.calculateMotorSizing(RoboticsMotor.NEO, motorCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            KinematicsMath.calculateMotorSizing(RoboticsMotor.NEO, massKg = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            KinematicsMath.calculateMotorSizing(RoboticsMotor.NEO, efficiency = 1.2)
        }
    }
}
