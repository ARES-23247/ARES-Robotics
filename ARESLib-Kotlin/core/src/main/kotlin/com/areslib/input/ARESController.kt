package com.areslib.input

/**
 * A state-machine controller wrapper providing robust edge-detection and state tracking
 * for a cross-platform robotics environment.
 *
 * This class avoids enumerations and instead uses pure Kotlin lambdas to select
 * state values.
 * Example: `if (controller.onPressed { it.a }) { ... }`
 */
class ARESController {
    
    var previousState: ControllerState = ControllerState()
        private set
        
    var currentState: ControllerState = ControllerState()
        private set

    /**
     * Updates the internal state machine. Must be called exactly once per robot loop.
     *
     * @param newState The freshly polled ControllerState from the hardware adapter.
     */
    fun update(newState: ControllerState) {
        previousState = currentState
        currentState = newState
    }

    /**
     * Checks if a specific boolean input (button, d-pad, bumper) was just pressed this loop.
     *
     * @param buttonSelector A lambda selecting the boolean field from ControllerState.
     * @return True if the button is currently true but was false in the previous loop.
     */
    inline fun onPressed(buttonSelector: (ControllerState) -> Boolean): Boolean =
        buttonSelector(currentState) && !buttonSelector(previousState)

    /**
     * Checks if a specific boolean input (button, d-pad, bumper) was just released this loop.
     *
     * @param buttonSelector A lambda selecting the boolean field from ControllerState.
     * @return True if the button is currently false but was true in the previous loop.
     */
    inline fun onReleased(buttonSelector: (ControllerState) -> Boolean): Boolean =
        !buttonSelector(currentState) && buttonSelector(previousState)

    /**
     * Checks if a specific boolean input is currently held down.
     *
     * @param buttonSelector A lambda selecting the boolean field from ControllerState.
     * @return True if the button is currently true.
     */
    inline fun isHeld(buttonSelector: (ControllerState) -> Boolean): Boolean =
        buttonSelector(currentState)

    /**
     * Treats an analog axis (like a trigger) as a digital button and checks if it was
     * just pressed past a specific threshold.
     *
     * @param triggerSelector A lambda selecting the analog axis from ControllerState.
     * @param threshold The value above which the axis is considered "pressed".
     * @return True if the axis crossed the threshold this loop.
     */
    inline fun triggerPressed(triggerSelector: (ControllerState) -> Double, threshold: Double = 0.5): Boolean =
        triggerSelector(currentState) > threshold && triggerSelector(previousState) <= threshold

    /**
     * Treats an analog axis (like a trigger) as a digital button and checks if it is
     * currently held past a specific threshold.
     *
     * @param triggerSelector A lambda selecting the analog axis from ControllerState.
     * @param threshold The value above which the axis is considered "pressed".
     * @return True if the axis is currently above the threshold.
     */
    inline fun triggerHeld(triggerSelector: (ControllerState) -> Double, threshold: Double = 0.5): Boolean =
        triggerSelector(currentState) > threshold
}
