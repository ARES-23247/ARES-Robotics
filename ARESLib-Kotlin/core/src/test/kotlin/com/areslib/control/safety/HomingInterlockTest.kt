package com.areslib.control.safety

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HomingInterlockTest {

    private class MockMechanism(
        override val configurationValid: Boolean,
        override val homed: Boolean
    ) : MechanismConfigurationStatus, MechanismHomingStatus

    @Test
    fun testIsAllConfigured() {
        val emptyDevices = emptyArray<MechanismConfigurationStatus>()
        assertTrue(HomingInterlock.isAllConfigured(emptyDevices))

        val allValid = arrayOf(
            MockMechanism(configurationValid = true, homed = true),
            MockMechanism(configurationValid = true, homed = false)
        )
        assertTrue(HomingInterlock.isAllConfigured(allValid))

        val oneInvalid = arrayOf(
            MockMechanism(configurationValid = true, homed = true),
            MockMechanism(configurationValid = false, homed = true)
        )
        assertFalse(HomingInterlock.isAllConfigured(oneInvalid))
    }

    @Test
    fun testIsAllHomed() {
        val emptyDevices = emptyArray<MechanismHomingStatus>()
        assertTrue(HomingInterlock.isAllHomed(emptyDevices))

        val allHomed = arrayOf(
            MockMechanism(configurationValid = true, homed = true),
            MockMechanism(configurationValid = false, homed = true)
        )
        assertTrue(HomingInterlock.isAllHomed(allHomed))

        val oneUnhomed = arrayOf(
            MockMechanism(configurationValid = true, homed = true),
            MockMechanism(configurationValid = true, homed = false)
        )
        assertFalse(HomingInterlock.isAllHomed(oneUnhomed))
    }

    @Test
    fun testIsSafetyHealthy() {
        assertTrue(HomingInterlock.isSafetyHealthy(configurationValid = true, homingValid = true, fatalUpdateFailure = null))
        assertFalse(HomingInterlock.isSafetyHealthy(configurationValid = false, homingValid = true, fatalUpdateFailure = null))
        assertFalse(HomingInterlock.isSafetyHealthy(configurationValid = true, homingValid = false, fatalUpdateFailure = null))
        assertFalse(HomingInterlock.isSafetyHealthy(configurationValid = true, homingValid = true, fatalUpdateFailure = RuntimeException("Hardware fault")))
    }

    @Test
    fun testIsHomingRequestAllowed() {
        assertTrue(HomingInterlock.isHomingRequestAllowed(isDisabled = true, isTestEnabled = false))
        assertFalse(HomingInterlock.isHomingRequestAllowed(isDisabled = false, isTestEnabled = false))
        assertFalse(HomingInterlock.isHomingRequestAllowed(isDisabled = true, isTestEnabled = true))
        assertFalse(HomingInterlock.isHomingRequestAllowed(isDisabled = false, isTestEnabled = true))
    }

    @Test
    fun testIsHomingChordPressed() {
        assertTrue(HomingInterlock.isHomingChordPressed(driverBack = true, driverStart = true, operatorBack = true, operatorStart = true))
        assertFalse(HomingInterlock.isHomingChordPressed(driverBack = false, driverStart = true, operatorBack = true, operatorStart = true))
        assertFalse(HomingInterlock.isHomingChordPressed(driverBack = true, driverStart = false, operatorBack = true, operatorStart = true))
        assertFalse(HomingInterlock.isHomingChordPressed(driverBack = true, driverStart = true, operatorBack = false, operatorStart = true))
        assertFalse(HomingInterlock.isHomingChordPressed(driverBack = true, driverStart = true, operatorBack = true, operatorStart = false))
    }
}
