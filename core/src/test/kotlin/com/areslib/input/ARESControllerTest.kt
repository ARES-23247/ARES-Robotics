package com.areslib.input

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ARESControllerTest {

    @Test
    fun testOnPressedEdgeDetection() {
        val controller = ARESController()
        
        // Initial state: all false
        assertFalse(controller.onPressed { it.a })
        
        // Press A
        controller.update(ControllerState(a = true))
        assertTrue(controller.onPressed { it.a }, "onPressed should be true on exactly the loop it was pressed")
        assertTrue(controller.isHeld { it.a })
        
        // Hold A
        controller.update(ControllerState(a = true))
        assertFalse(controller.onPressed { it.a }, "onPressed should be false if it was already pressed last loop")
        assertTrue(controller.isHeld { it.a })
        
        // Release A
        controller.update(ControllerState(a = false))
        assertFalse(controller.onPressed { it.a })
        assertTrue(controller.onReleased { it.a }, "onReleased should be true exactly when transitioned to false")
        assertFalse(controller.isHeld { it.a })
    }

    @Test
    fun testAnalogTriggerThresholds() {
        val controller = ARESController()
        
        // Trigger at 0.0
        controller.update(ControllerState(leftTrigger = 0.0))
        assertFalse(controller.triggerPressed({ it.leftTrigger }, 0.5))
        assertFalse(controller.triggerHeld({ it.leftTrigger }, 0.5))
        
        // Trigger moved to 0.4 (below threshold)
        controller.update(ControllerState(leftTrigger = 0.4))
        assertFalse(controller.triggerPressed({ it.leftTrigger }, 0.5))
        assertFalse(controller.triggerHeld({ it.leftTrigger }, 0.5))
        
        // Trigger moved to 0.6 (crossed threshold)
        controller.update(ControllerState(leftTrigger = 0.6))
        assertTrue(controller.triggerPressed({ it.leftTrigger }, 0.5))
        assertTrue(controller.triggerHeld({ it.leftTrigger }, 0.5))
        
        // Trigger held at 0.8
        controller.update(ControllerState(leftTrigger = 0.8))
        assertFalse(controller.triggerPressed({ it.leftTrigger }, 0.5))
        assertTrue(controller.triggerHeld({ it.leftTrigger }, 0.5))
    }
}
