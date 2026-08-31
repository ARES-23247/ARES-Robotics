package com.areslib.control.assist

import com.areslib.control.feedback.GravityFeedforward
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlChampionshipTest {

    @Test
    fun testGravityFeedforward() {
        // Elevator feedforward
        val elevatorFF = GravityFeedforward.calculateElevator(kG = 0.15)
        assertEquals(0.15, elevatorFF, 1e-6)

        // Arm feedforward relative to horizontal
        val armHorizontal = GravityFeedforward.calculateArm(angleRadians = 0.0, kG = 0.5)
        assertEquals(0.5, armHorizontal, 1e-6)

        val armVertical = GravityFeedforward.calculateArm(angleRadians = Math.PI / 2.0, kG = 0.5)
        assertEquals(0.0, armVertical, 1e-6)
    }

}

