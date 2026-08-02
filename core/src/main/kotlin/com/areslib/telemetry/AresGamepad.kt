package com.areslib.telemetry

/**
 * A declarative, command-based wrapper for [GamepadState].
 * 
 * Allows students to map human-readable descriptions to button actions.
 * This class tracks the previous state to detect button edge transitions
 * (onPress, onRelease) without requiring manual boolean logic.
 * 
 * To conform to the ARESLib-Kotlin Redux architecture, the executable
 * block should ideally dispatch a `RobotAction` to the central store.
 * 
 * Example usage:
 * ```kotlin
 * val driver = AresGamepad()
 * 
 * driver.a.onPress("Spin up shooter to 3500 RPM") {
 *     store.dispatch(SuperstructureAction.SpinUpShooter(3500.0))
 * }
 * 
 * // Inside your high-frequency control loop (50Hz-100Hz):
 * driver.update(latestGamepadState)
 * ```
 */
/**
 * Class implementation for Ares Gamepad.
 *
 * Real-time telemetry streaming, diagnostic logging, and NetworkTables 4 communication handler.
 */
class AresGamepad {
    
    private var previousState = GamepadState()
    private var currentState = GamepadState()
    
    val a = BindableButton { it.a }
    val b = BindableButton { it.b }
    val x = BindableButton { it.x }
    val y = BindableButton { it.y }
    val dpadUp = BindableButton { it.dpadUp }
    val dpadDown = BindableButton { it.dpadDown }
    val dpadLeft = BindableButton { it.dpadLeft }
    val dpadRight = BindableButton { it.dpadRight }
    val leftBumper = BindableButton { it.leftBumper }
    val rightBumper = BindableButton { it.rightBumper }
    val leftStickButton = BindableButton { it.leftStickButton }
    val rightStickButton = BindableButton { it.rightStickButton }
    val start = BindableButton { it.start }
    val back = BindableButton { it.back }
    val touchpad = BindableButton { it.touchpad }
    val share = BindableButton { it.share }
    val options = BindableButton { it.options }
    val c = BindableButton { it.c }
    val z = BindableButton { it.z }
    val m1 = BindableButton { it.m1 }
    val m2 = BindableButton { it.m2 }
    val m3 = BindableButton { it.m3 }
    val m4 = BindableButton { it.m4 }

    val leftStick = BindableStick { state -> Pair(state.leftStickX, state.leftStickY) }
    val rightStick = BindableStick { state -> Pair(state.rightStickX, state.rightStickY) }
    val leftStickX = BindableAxis { it.leftStickX }
    val leftStickY = BindableAxis { it.leftStickY }
    val rightStickX = BindableAxis { it.rightStickX }
    val rightStickY = BindableAxis { it.rightStickY }
    val leftTrigger = BindableAxis { it.leftTrigger }
    val rightTrigger = BindableAxis { it.rightTrigger }

    private val allButtons = listOf(
        a, b, x, y, 
        dpadUp, dpadDown, dpadLeft, dpadRight, 
        leftBumper, rightBumper, 
        leftStickButton, rightStickButton, 
        start, back,
        touchpad, share, options,
        c, z, m1, m2, m3, m4
    )

    /**
     * Updates the internal state of the gamepad and triggers any bound actions.
     * This method is allocation-free and should be called in the hot path.
     * 
     * @param newState The latest polled gamepad state.
     */
    fun update(newState: GamepadState) {
        previousState.copyFrom(currentState)
        currentState.copyFrom(newState)

        leftStick.updateValue(newState)
        rightStick.updateValue(newState)
        leftStickX.updateValue(newState)
        leftStickY.updateValue(newState)
        rightStickX.updateValue(newState)
        rightStickY.updateValue(newState)
        leftTrigger.updateValue(newState)
        rightTrigger.updateValue(newState)
        
        // Iterate through all bindable buttons and trigger actions if transitions occurred
        // Using a standard loop to avoid allocations (iterator object creation) on the hot path
        for (i in allButtons.indices) {
            val button = allButtons[i]
            val wasPressed = button.stateSelector(previousState)
            val isPressed = button.stateSelector(currentState)
            button.isPressed = isPressed
            
            when {
                isPressed && !wasPressed -> button.firePress()
                !isPressed && wasPressed -> button.fireRelease()
            }
            
            if (isPressed) {
                button.fireWhilePressed()
            }
        }
    }

    /**
     * Class implementation for Bindable Button.
     *
     * Real-time telemetry streaming, diagnostic logging, and NetworkTables 4 communication handler.
     */
    class BindableButton(val stateSelector: (GamepadState) -> Boolean) {
        var isPressed: Boolean = false
            internal set
        private var onPressAction: (() -> Unit)? = null
        private var onReleaseAction: (() -> Unit)? = null
        private var whilePressedAction: (() -> Unit)? = null

        fun label(@Suppress("UNUSED_PARAMETER") description: String) {
            // No-op at runtime, used statically for ARES-Analytics parsing
        }

        /**
         * Binds an action to execute exactly once when the button transitions from unpressed to pressed.
         * 
         * @param description Human-readable description of this action (used by ARES-Analytics telemetry).
         * @param action The block of code to execute. Must not block the thread.
         */
        fun onPress(@Suppress("UNUSED_PARAMETER") description: String, action: () -> Unit) {
            this.onPressAction = action
        }

        /**
         * Binds an action to execute exactly once when the button transitions from pressed to unpressed.
         * 
         * @param description Human-readable description of this action (used by ARES-Analytics telemetry).
         * @param action The block of code to execute. Must not block the thread.
         */
        fun onRelease(@Suppress("UNUSED_PARAMETER") description: String, action: () -> Unit) {
            this.onReleaseAction = action
        }

        /**
         * Binds an action to execute continuously every loop cycle while the button is held down.
         * 
         * @param description Human-readable description of this action (used by ARES-Analytics telemetry).
         * @param action The block of code to execute. Must not block the thread.
         */
        fun whilePressed(@Suppress("UNUSED_PARAMETER") description: String, action: () -> Unit) {
            this.whilePressedAction = action
        }

        internal fun firePress() {
            onPressAction?.invoke()
        }

        internal fun fireRelease() {
            onReleaseAction?.invoke()
        }

        internal fun fireWhilePressed() {
            whilePressedAction?.invoke()
        }
    }

    /**
     * Class implementation for Bindable Axis.
     *
     * Real-time telemetry streaming, diagnostic logging, and NetworkTables 4 communication handler.
     */
    class BindableAxis(private val valueSelector: (GamepadState) -> Float) {
        var value: Float = 0.0f
            private set

        fun label(@Suppress("UNUSED_PARAMETER") description: String) {
            // No-op at runtime, used statically for ARES-Analytics parsing
        }

        internal fun updateValue(state: GamepadState) {
            value = valueSelector(state)
        }
    }

    /**
     * Class implementation for Bindable Stick.
     *
     * Real-time telemetry streaming, diagnostic logging, and NetworkTables 4 communication handler.
     */
    class BindableStick(private val valueSelector: (GamepadState) -> Pair<Float, Float>) {
        var x: Float = 0.0f
            private set
        var y: Float = 0.0f
            private set

        fun label(@Suppress("UNUSED_PARAMETER") description: String) {
            // No-op at runtime, used statically for ARES-Analytics parsing
        }

        internal fun updateValue(state: GamepadState) {
            val (newX, newY) = valueSelector(state)
            x = newX
            y = newY
        }
    }
}
