package com.areslib.telemetry

import com.areslib.util.RobotClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign

private const val ANALOG_DEFAULT_FIRST_UPDATE_SECONDS = 0.02
private const val ANALOG_MAX_SLEW_DT_SECONDS = 0.2

/** Primitive gamepad-axis source. Its [Float] return avoids boxed values in the robot loop. */
fun interface GamepadAxisSource {
    fun read(state: GamepadState): Float
}

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
 * driver.leftStick
 *     .withDeadband(0.08)
 *     .withExponentialCurve(2.0)
 *     .withSlewRateLimit(3.0)
 *
 * driver.x.toggle("Toggle intake", currentState = { store.state.superstructure.intakeEnabled }) {
 *     store.dispatch(SuperstructureAction.SetIntakeEnabled(it))
 * }
 * 
 * // Inside your high-frequency control loop (50Hz-100Hz):
 * driver.update(latestGamepadState)
 * ```
 */
class AresGamepad {

    /** Primitive callback used by analog bindings without boxing a [Double] every robot loop. */
    fun interface AxisConsumer {
        fun accept(value: Double)
    }

    /** Primitive two-axis callback used by shaped stick bindings without per-loop tuple allocation. */
    fun interface StickConsumer {
        fun accept(x: Double, y: Double)
    }
    
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

    val leftStick = BindableStick(
        GamepadAxisSource { it.leftStickX },
        GamepadAxisSource { it.leftStickY }
    )
    val rightStick = BindableStick(
        GamepadAxisSource { it.rightStickX },
        GamepadAxisSource { it.rightStickY }
    )
    val leftStickX = BindableAxis(GamepadAxisSource { it.leftStickX })
    val leftStickY = BindableAxis(GamepadAxisSource { it.leftStickY })
    val rightStickX = BindableAxis(GamepadAxisSource { it.rightStickX })
    val rightStickY = BindableAxis(GamepadAxisSource { it.rightStickY })
    val leftTrigger = BindableAxis(GamepadAxisSource { it.leftTrigger })
    val rightTrigger = BindableAxis(GamepadAxisSource { it.rightTrigger })

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

        val timestampMs = RobotClock.currentTimeMillis()
        leftStick.updateValue(newState, timestampMs, notifyConsumer = true, resetSlew = false)
        rightStick.updateValue(newState, timestampMs, notifyConsumer = true, resetSlew = false)
        leftStickX.updateValue(newState, timestampMs, notifyConsumer = true, resetSlew = false)
        leftStickY.updateValue(newState, timestampMs, notifyConsumer = true, resetSlew = false)
        rightStickX.updateValue(newState, timestampMs, notifyConsumer = true, resetSlew = false)
        rightStickY.updateValue(newState, timestampMs, notifyConsumer = true, resetSlew = false)
        leftTrigger.updateValue(newState, timestampMs, notifyConsumer = true, resetSlew = false)
        rightTrigger.updateValue(newState, timestampMs, notifyConsumer = true, resetSlew = false)
        
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
        val timestampMs = RobotClock.currentTimeMillis()
        leftStick.updateValue(newState, timestampMs, notifyConsumer = false, resetSlew = true)
        rightStick.updateValue(newState, timestampMs, notifyConsumer = false, resetSlew = true)
        leftStickX.updateValue(newState, timestampMs, notifyConsumer = false, resetSlew = true)
        leftStickY.updateValue(newState, timestampMs, notifyConsumer = false, resetSlew = true)
        rightStickX.updateValue(newState, timestampMs, notifyConsumer = false, resetSlew = true)
        rightStickY.updateValue(newState, timestampMs, notifyConsumer = false, resetSlew = true)
        leftTrigger.updateValue(newState, timestampMs, notifyConsumer = false, resetSlew = true)
        rightTrigger.updateValue(newState, timestampMs, notifyConsumer = false, resetSlew = true)
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
        fun onPress(@Suppress("UNUSED_PARAMETER") description: String = "", action: () -> Unit) {
            this.onPressAction = action
        }

        /** Declarative alias for [onPress]. */
        fun bindTo(@Suppress("UNUSED_PARAMETER") description: String = "", action: () -> Unit) {
            onPress(description, action)
        }

        /**
         * Binds an action to execute exactly once when the button transitions from pressed to unpressed.
         * 
         * @param description Human-readable description of this action (used by ARES-Analytics telemetry).
         * @param action The block of code to execute. Must not block the thread.
         */
        fun onRelease(@Suppress("UNUSED_PARAMETER") description: String = "", action: () -> Unit) {
            this.onReleaseAction = action
        }

        /**
         * Binds an action to execute continuously every loop cycle while the button is held down.
         * 
         * @param description Human-readable description of this action (used by ARES-Analytics telemetry).
         * @param action The block of code to execute. Must not block the thread.
         */
        fun whilePressed(@Suppress("UNUSED_PARAMETER") description: String = "", action: () -> Unit) {
            this.whilePressedAction = action
        }

        /** Declarative alias for [whilePressed]. */
        fun whileHeld(@Suppress("UNUSED_PARAMETER") description: String = "", action: () -> Unit) {
            whilePressed(description, action)
        }

        /**
         * Requests the inverse of the authoritative Redux-backed [currentState] on each press.
         *
         * The binding deliberately owns no hidden toggle latch. If safety rejects a request, the
         * next press derives from the unchanged store state instead of drifting out of sync.
         */
        fun toggle(
            @Suppress("UNUSED_PARAMETER") description: String = "",
            currentState: () -> Boolean,
            action: (Boolean) -> Unit
        ) {
            onPress(description) {
                action(!currentState())
            }
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

    /** One continuously sampled analog input with deadband, curvature, and slew shaping. */
    class BindableAxis(
        private val valueSource: GamepadAxisSource,
    ) {
        var value: Float = 0.0f
            private set

        var shapedValue: Double = 0.0
            private set

        private var deadbandThreshold: Double = 0.0
        private var curveExponent: Double = 1.0
        private var slewRateLimit: Double = 0.0 // 0.0 means disabled
        private var lastSlewValue: Double = 0.0
        private var lastUpdateTimeMs: Long = Long.MIN_VALUE
        private var axisConsumer: AxisConsumer? = null

        fun label(@Suppress("UNUSED_PARAMETER") description: String) {
            // No-op at runtime, used statically for ARES-Analytics parsing
        }

        /** Configures a deadband with smooth linear rescaling above threshold. */
        fun withDeadband(threshold: Double): BindableAxis {
            require(threshold.isFinite() && threshold >= 0.0 && threshold < 1.0) {
                "Axis deadband must be finite and in [0.0, 1.0)"
            }
            this.deadbandThreshold = threshold
            return this
        }

        /** Configures an exponential curve for fine precision near origin (1.0 = linear). */
        fun withExponentialCurve(exponent: Double): BindableAxis {
            require(exponent.isFinite() && exponent in 1.0..5.0) {
                "Axis exponent must be finite and in [1.0, 5.0]"
            }
            this.curveExponent = exponent
            return this
        }

        /** Configures a maximum rate of change (units per second) to protect mechanisms. */
        fun withSlewRateLimit(unitsPerSecond: Double): BindableAxis {
            require(unitsPerSecond.isFinite() && unitsPerSecond >= 0.0) {
                "Axis slew rate must be finite and non-negative"
            }
            this.slewRateLimit = unitsPerSecond
            return this
        }

        /** Binds a primitive consumer invoked by [update], never by INIT-safe [prime]. */
        fun bindAxis(consumer: AxisConsumer): BindableAxis {
            this.axisConsumer = consumer
            return this
        }

        internal fun updateValue(
            state: GamepadState,
            timestampMs: Long,
            notifyConsumer: Boolean,
            resetSlew: Boolean
        ) {
            val selected = valueSource.read(state).toDouble()
            val raw = if (selected.isFinite()) selected.coerceIn(-1.0, 1.0) else 0.0
            value = raw.toFloat()

            // 1. Deadband with linear rescaling
            val afterDeadband = if (abs(raw) < deadbandThreshold) {
                0.0
            } else {
                val s = sign(raw)
                s * (abs(raw) - deadbandThreshold) / (1.0 - deadbandThreshold)
            }

            // 2. Exponential curvature
            val afterCurve = if (curveExponent == 1.0) {
                afterDeadband
            } else {
                val s = sign(afterDeadband)
                s * (abs(afterDeadband).pow(curveExponent))
            }

            // 3. Slew rate limiter
            val finalVal = if (resetSlew) {
                afterCurve
            } else if (slewRateLimit > 0.0) {
                val dt = if (lastUpdateTimeMs == Long.MIN_VALUE) {
                    ANALOG_DEFAULT_FIRST_UPDATE_SECONDS
                } else {
                    ((timestampMs - lastUpdateTimeMs).coerceAtLeast(0L) / 1_000.0)
                        .coerceAtMost(ANALOG_MAX_SLEW_DT_SECONDS)
                }
                val maxDelta = slewRateLimit * dt
                val delta = (afterCurve - lastSlewValue).coerceIn(-maxDelta, maxDelta)
                lastSlewValue + delta
            } else {
                afterCurve
            }

            lastSlewValue = finalVal
            lastUpdateTimeMs = timestampMs
            shapedValue = finalVal

            if (notifyConsumer) axisConsumer?.accept(finalVal)
        }

    }

    /** Two continuously sampled analog axes with radial deadband and curve shaping. */
    class BindableStick(
        private val xSource: GamepadAxisSource,
        private val ySource: GamepadAxisSource,
    ) {
        var x: Float = 0.0f
            private set
        var y: Float = 0.0f
            private set

        var shapedX: Double = 0.0
            private set
        var shapedY: Double = 0.0
            private set

        private var deadbandThreshold: Double = 0.0
        private var curveExponent: Double = 1.0
        private var slewRateLimit: Double = 0.0
        private var lastSlewX: Double = 0.0
        private var lastSlewY: Double = 0.0
        private var lastUpdateTimeMs: Long = Long.MIN_VALUE
        private var stickConsumer: StickConsumer? = null

        fun label(@Suppress("UNUSED_PARAMETER") description: String) {
            // No-op at runtime, used statically for ARES-Analytics parsing
        }

        /** Configures a radial deadband (preserves vector angle while zeroing near center). */
        fun withDeadband(threshold: Double): BindableStick {
            require(threshold.isFinite() && threshold >= 0.0 && threshold < 1.0) {
                "Stick deadband must be finite and in [0.0, 1.0)"
            }
            this.deadbandThreshold = threshold
            return this
        }

        /** Configures an exponential curve for both axes. */
        fun withExponentialCurve(exponent: Double): BindableStick {
            require(exponent.isFinite() && exponent in 1.0..5.0) {
                "Stick exponent must be finite and in [1.0, 5.0]"
            }
            this.curveExponent = exponent
            return this
        }

        /**
         * Configures a radial maximum rate of change in stick-deflection units per second.
         * The vector direction is preserved while its change in magnitude is limited.
         */
        fun withSlewRateLimit(unitsPerSecond: Double): BindableStick {
            require(unitsPerSecond.isFinite() && unitsPerSecond >= 0.0) {
                "Stick slew rate must be finite and non-negative"
            }
            this.slewRateLimit = unitsPerSecond
            return this
        }

        /** Binds a primitive consumer invoked by [update], never by INIT-safe [prime]. */
        fun bindStick(consumer: StickConsumer): BindableStick {
            stickConsumer = consumer
            return this
        }

        internal fun updateValue(
            state: GamepadState,
            timestampMs: Long,
            notifyConsumer: Boolean,
            resetSlew: Boolean
        ) {
            val selectedX = xSource.read(state).toDouble()
            val selectedY = ySource.read(state).toDouble()
            var rawX = if (selectedX.isFinite()) selectedX.coerceIn(-1.0, 1.0) else 0.0
            var rawY = if (selectedY.isFinite()) selectedY.coerceIn(-1.0, 1.0) else 0.0
            val rawMagnitude = hypot(rawX, rawY)
            if (rawMagnitude > 1.0) {
                rawX /= rawMagnitude
                rawY /= rawMagnitude
            }
            x = rawX.toFloat()
            y = rawY.toFloat()

            // 1. Radial deadband
            val magnitude = hypot(rawX, rawY)
            var dbX = if (magnitude < deadbandThreshold) {
                0.0
            } else if (deadbandThreshold > 0.0 && magnitude > 0.0) {
                val scaledMagnitude = (magnitude - deadbandThreshold) / (1.0 - deadbandThreshold)
                (rawX / magnitude) * scaledMagnitude
            } else {
                rawX
            }

            var dbY = if (magnitude < deadbandThreshold) {
                0.0
            } else if (deadbandThreshold > 0.0 && magnitude > 0.0) {
                val scaledMagnitude = (magnitude - deadbandThreshold) / (1.0 - deadbandThreshold)
                (rawY / magnitude) * scaledMagnitude
            } else {
                rawY
            }

            // 2. Exponential curve
            if (curveExponent != 1.0) {
                val curMag = hypot(dbX, dbY)
                if (curMag > 0.0) {
                    val shapedMag = curMag.pow(curveExponent)
                    dbX = (dbX / curMag) * shapedMag
                    dbY = (dbY / curMag) * shapedMag
                }
            }

            // 3. Slew rate limiter
            if (!resetSlew && slewRateLimit > 0.0) {
                val dt = if (lastUpdateTimeMs == Long.MIN_VALUE) {
                    ANALOG_DEFAULT_FIRST_UPDATE_SECONDS
                } else {
                    ((timestampMs - lastUpdateTimeMs).coerceAtLeast(0L) / 1_000.0)
                        .coerceAtMost(ANALOG_MAX_SLEW_DT_SECONDS)
                }
                val maxDelta = slewRateLimit * dt
                val dx = dbX - lastSlewX
                val dy = dbY - lastSlewY
                val deltaMagnitude = hypot(dx, dy)
                val scale = if (deltaMagnitude > maxDelta && deltaMagnitude > 0.0) {
                    maxDelta / deltaMagnitude
                } else 1.0
                dbX = lastSlewX + dx * scale
                dbY = lastSlewY + dy * scale
            }

            lastSlewX = dbX
            lastSlewY = dbY
            lastUpdateTimeMs = timestampMs

            shapedX = dbX
            shapedY = dbY
            if (notifyConsumer) stickConsumer?.accept(dbX, dbY)
        }

    }
}
