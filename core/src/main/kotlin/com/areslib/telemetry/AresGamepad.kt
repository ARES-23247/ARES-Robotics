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
    val f1 = BindableButton { it.f1 }
    val f2 = BindableButton { it.f2 }
    val f3 = BindableButton { it.f3 }
    val f4 = BindableButton { it.f4 }
    val f5 = BindableButton { it.f5 }
    val f6 = BindableButton { it.f6 }
    val f7 = BindableButton { it.f7 }
    val f8 = BindableButton { it.f8 }
    val f9 = BindableButton { it.f9 }
    val f10 = BindableButton { it.f10 }
    val f11 = BindableButton { it.f11 }
    val f12 = BindableButton { it.f12 }

    val leftStick = BindableStick({ it.leftStickX }, { it.leftStickY })
    val rightStick = BindableStick({ it.rightStickX }, { it.rightStickY })
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
        c, z, m1, m2, m3, m4,
        f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12
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

            // Inputs held during INIT are deliberately quarantined until they are released. This
            // prevents both edge and level bindings from energizing hardware as Play is pressed.
            if (button.suppressedUntilRelease) {
                if (!isPressed) button.suppressedUntilRelease = false
                continue
            }
            
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
     * Samples controls without invoking bindings.
     *
     * A pressed button is suppressed until a later release, so a control held through the FTC
     * INIT-to-START transition cannot become either an `onPress` or `whilePressed` command.
     */
    fun prime(newState: GamepadState) {
        previousState.copyFrom(newState)
        currentState.copyFrom(newState)
        leftStick.updateValue(newState)
        rightStick.updateValue(newState)
        leftStickX.updateValue(newState)
        leftStickY.updateValue(newState)
        rightStickX.updateValue(newState)
        rightStickY.updateValue(newState)
        leftTrigger.updateValue(newState)
        rightTrigger.updateValue(newState)
        for (i in allButtons.indices) {
            val button = allButtons[i]
            val isPressed = button.stateSelector(newState)
            button.isPressed = isPressed
            button.suppressedUntilRelease = isPressed
        }
    }

    /** A digital input with edge- and level-triggered bindings. */
    class BindableButton(val stateSelector: (GamepadState) -> Boolean) {
        var isPressed: Boolean = false
            internal set
        internal var suppressedUntilRelease: Boolean = false
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

    /** One continuously sampled analog input. */
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

    /** Two continuously sampled analog axes without allocating a [Pair] each loop. */
    class BindableStick(
        private val xSelector: (GamepadState) -> Float,
        private val ySelector: (GamepadState) -> Float
    ) {
        var x: Float = 0.0f
            private set
        var y: Float = 0.0f
            private set

        fun label(@Suppress("UNUSED_PARAMETER") description: String) {
            // No-op at runtime, used statically for ARES-Analytics parsing
        }

        internal fun updateValue(state: GamepadState) {
            x = xSelector(state)
            y = ySelector(state)
        }
    }
}
