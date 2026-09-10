package com.ares.analytics.ui.components.linkage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.areslib.math.kinematics.TwoDofLinkagePlant

/** Owns the local preview's run/fault/reset transitions. Never commands robot hardware. */
internal class LinkagePhysicsLabState(private val plant: TwoDofLinkagePlant) {
    var voltage1 by mutableStateOf(0.0)
    var voltage2 by mutableStateOf(0.0)
    var running by mutableStateOf(false)
        private set
    var fault by mutableStateOf<String?>(null)
        private set
    var theta1 by mutableStateOf(plant.joint1PositionRad)
        private set
    var theta2 by mutableStateOf(plant.joint2PositionRad)
        private set

    fun toggleRunning() {
        if (fault == null) running = !running
    }

    fun advance() {
        if (!running) return
        try {
            plant.step(voltage1, voltage2, 0.02)
            theta1 = plant.joint1PositionRad
            theta2 = plant.joint2PositionRad
        } catch (_: IllegalStateException) {
            running = false
            voltage1 = 0.0
            voltage2 = 0.0
            fault = "Simulation exceeded its numerical range. Review the physics settings and reset before running again."
        }
    }

    fun reset() {
        running = false
        voltage1 = 0.0
        voltage2 = 0.0
        plant.reset()
        theta1 = plant.joint1PositionRad
        theta2 = plant.joint2PositionRad
        fault = null
    }
}
