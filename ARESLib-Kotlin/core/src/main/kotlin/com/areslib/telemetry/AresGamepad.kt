package com.areslib.telemetry

import com.areslib.util.RobotClock
import kotlin.math.hypot
import kotlin.math.pow

/**
 * A declarative, allocation-free wrapper for [GamepadState].
 *
 * Allows text-first TeleOp code to bind digital buttons, analog triggers, and shaped dual-axis
 * sticks without manual boolean edge tracking or per-frame heap allocations.
 *
 * All controls are sampled once per loop before callbacks fire, and any button or trigger held
 * during the FTC `INIT` phase ([prime]) is quarantined until released so hardware cannot energize
 * unexpectedly when `START` is pressed.
 *
 * ```kotlin
 * controls {
 *     val moveStick = driver.leftStick.withDeadband(0.06).withExponentialCurve(2.0).withSlewRateLimit(3.5)
 *     driver.a.onPress("Collect") { store.dispatch(IntakeAction.Collect) }
 *     driver.rightBumper.whileHeld("Boost") { boostActive = true }
 *     driver.x.toggle("Hood", currentState = { store.state.shooter.hoodRaised }) {
 *         store.dispatch(ShooterAction.SetHood(it))
 *     }
 *     driver.rightTrigger.onPress(threshold = 0.5) { store.dispatch(ShooterAction.Fire) }
 *     operator.leftStickY.withDeadband(0.08).withInversion().bindAxis { manualArmCommand = it }
 * }
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

    val leftStick = BindableStick(GamepadAxisSource { it.leftStickX }, GamepadAxisSource { it.leftStickY })
    val rightStick = BindableStick(GamepadAxisSource { it.rightStickX }, GamepadAxisSource { it.rightStickY })
    val leftStickX = BindableAxis(GamepadAxisSource { it.leftStickX })
    val leftStickY = BindableAxis(GamepadAxisSource { it.leftStickY })
    val rightStickX = BindableAxis(GamepadAxisSource { it.rightStickX })
    val rightStickY = BindableAxis(GamepadAxisSource { it.rightStickY })
    val leftTrigger = BindableAxis(GamepadAxisSource { it.leftTrigger })
    val rightTrigger = BindableAxis(GamepadAxisSource { it.rightTrigger })

    private val allButtons = listOf(
        a, b, x, y, dpadUp, dpadDown, dpadLeft, dpadRight, leftBumper, rightBumper,
        leftStickButton, rightStickButton, start, back, touchpad, share, options,
        c, z, m1, m2, m3, m4, f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12,
    )

    private val allAxes = listOf(leftStickX, leftStickY, rightStickX, rightStickY, leftTrigger, rightTrigger)

    /**
     * Updates the internal state of the gamepad and triggers any bound actions.
     * Allocation-free; all controls are sampled from one owned copy before any callback runs.
     */
    fun update(newState: GamepadState) {
        val reusable = previousState
        previousState = currentState
        currentState = reusable
        currentState.copyFrom(newState)

        val timestampMs = RobotClock.currentTimeMillis()
        leftStick.updateValue(currentState, timestampMs, notifyConsumer = false, resetSlew = false)
        rightStick.updateValue(currentState, timestampMs, notifyConsumer = false, resetSlew = false)
        for (i in allAxes.indices) {
            allAxes[i].updateValue(currentState, timestampMs, notifyConsumer = false, resetSlew = false)
        }
        for (i in allButtons.indices) {
            val button = allButtons[i]
            button.isPressed = button.stateSelector(currentState)
        }

        leftStick.notifyConsumer()
        rightStick.notifyConsumer()
        for (i in allAxes.indices) {
            allAxes[i].notifyConsumer()
        }

        // Standard indexed loop avoids iterator allocations on the hot path.
        for (i in allButtons.indices) {
            val button = allButtons[i]
            val wasPressed = button.stateSelector(previousState)
            val isPressed = button.isPressed

            // Inputs held during INIT are quarantined until released.
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
     * Any control held during [prime] is quarantined until a subsequent release.
     */
    fun prime(newState: GamepadState) {
        previousState.copyFrom(newState)
        currentState.copyFrom(newState)
        val timestampMs = RobotClock.currentTimeMillis()
        leftStick.updateValue(newState, timestampMs, notifyConsumer = false, resetSlew = true)
        rightStick.updateValue(newState, timestampMs, notifyConsumer = false, resetSlew = true)
        for (i in allAxes.indices) {
            allAxes[i].updateValue(newState, timestampMs, notifyConsumer = false, resetSlew = true)
        }
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

        fun label(@Suppress("UNUSED_PARAMETER") description: String) {}

        /** Binds an action to execute once when the button transitions from unpressed to pressed. */
        fun onPress(@Suppress("UNUSED_PARAMETER") description: String = "", action: () -> Unit) {
            this.onPressAction = action
        }

        /** Binds an action to execute once when the button transitions from pressed to unpressed. */
        fun onRelease(@Suppress("UNUSED_PARAMETER") description: String = "", action: () -> Unit) {
            this.onReleaseAction = action
        }

        /** Binds an action to execute every loop cycle while the button is held down. */
        fun whilePressed(@Suppress("UNUSED_PARAMETER") description: String = "", action: () -> Unit) {
            this.whilePressedAction = action
        }

        /** Alias for [whilePressed] matching [com.areslib.controls.DigitalControlBindingBuilder.whileHeld]. */
        fun whileHeld(description: String = "", action: () -> Unit) {
            whilePressed(description, action)
        }

        /** Requests the inverse of the authoritative Redux-backed [currentState] on each press. */
        fun toggle(
            @Suppress("UNUSED_PARAMETER") description: String = "",
            currentState: () -> Boolean,
            action: (Boolean) -> Unit,
        ) = onPress(description) { action(!currentState()) }

        internal fun firePress() { onPressAction?.invoke() }
        internal fun fireRelease() { onReleaseAction?.invoke() }
        internal fun fireWhilePressed() { whilePressedAction?.invoke() }
    }

    /**
     * One continuously sampled analog input with deadband, curvature, inversion, slew shaping,
     * and optional digital threshold trigger callbacks.
     */
    class BindableAxis(private val valueSource: GamepadAxisSource) {
        var value: Float = 0.0f
            private set
        var shapedValue: Double = 0.0
            private set

        /** True when the raw axis deflection meets or exceeds the configured trigger threshold. */
        var isPressed: Boolean = false
            private set

        private var deadbandThreshold: Double = 0.0
        private var curveExponent: Double = 1.0
        private var slewRateLimit: Double = 0.0
        private var inverted: Boolean = false
        private var lastSlewValue: Double = 0.0
        private var lastUpdateTimeMs: Long = 0L
        private var hasSlewTimestamp = false
        private var axisConsumer: AxisConsumer? = null

        private var triggerThreshold: Double = 0.5
        private var wasThresholdPressed: Boolean = false
        internal var suppressedUntilRelease: Boolean = false
        private var onPressAction: (() -> Unit)? = null
        private var onReleaseAction: (() -> Unit)? = null
        private var whilePressedAction: (() -> Unit)? = null

        fun label(@Suppress("UNUSED_PARAMETER") description: String) {}

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

        /** Inverts the sign of the shaped axis output (`shapedValue`). */
        fun withInversion(inverted: Boolean = true): BindableAxis {
            this.inverted = inverted
            return this
        }

        /** Binds a primitive consumer invoked by [update], never by INIT-safe [prime]. */
        fun bindAxis(consumer: AxisConsumer): BindableAxis {
            this.axisConsumer = consumer
            return this
        }

        /** Binds a callback that fires once when the axis crosses at or above [threshold] in `(0.0, 1.0]`. */
        fun onPress(@Suppress("UNUSED_PARAMETER") description: String = "", threshold: Double = 0.5, action: () -> Unit): BindableAxis {
            configureThreshold(threshold)
            this.onPressAction = action
            return this
        }

        /** Binds a callback that fires once when the axis drops below [threshold] in `(0.0, 1.0]`. */
        fun onRelease(@Suppress("UNUSED_PARAMETER") description: String = "", threshold: Double = 0.5, action: () -> Unit): BindableAxis {
            configureThreshold(threshold)
            this.onReleaseAction = action
            return this
        }

        /** Binds a callback that executes every active loop while the axis remains at or above [threshold]. */
        fun whilePressed(@Suppress("UNUSED_PARAMETER") description: String = "", threshold: Double = 0.5, action: () -> Unit): BindableAxis {
            configureThreshold(threshold)
            this.whilePressedAction = action
            return this
        }

        /** Alias for [whilePressed] matching [com.areslib.controls.DigitalControlBindingBuilder.whileHeld]. */
        fun whileHeld(description: String = "", threshold: Double = 0.5, action: () -> Unit): BindableAxis =
            whilePressed(description, threshold, action)

        private fun configureThreshold(threshold: Double) {
            require(threshold.isFinite() && threshold > 0.0 && threshold <= 1.0) {
                "Trigger threshold must be finite and in (0.0, 1.0]"
            }
            this.triggerThreshold = threshold
        }

        internal fun updateValue(state: GamepadState, timestampMs: Long, notifyConsumer: Boolean, resetSlew: Boolean) {
            val selected = valueSource.read(state).toDouble()
            val raw = if (selected.isFinite()) selected.coerceIn(-1.0, 1.0) else 0.0
            value = raw.toFloat()
            wasThresholdPressed = isPressed
            isPressed = raw >= triggerThreshold
            if (resetSlew) {
                wasThresholdPressed = isPressed
                suppressedUntilRelease = isPressed
            }

            val finalVal = shapeSingleAxisSample(
                selected, raw, deadbandThreshold, curveExponent, inverted,
                slewRateLimit, lastSlewValue, timestampMs, lastUpdateTimeMs, hasSlewTimestamp, resetSlew,
            )
            lastSlewValue = finalVal
            lastUpdateTimeMs = timestampMs
            hasSlewTimestamp = true
            shapedValue = finalVal
            if (notifyConsumer) notifyConsumer()
        }

        internal fun notifyConsumer() {
            axisConsumer?.accept(shapedValue)
            if (suppressedUntilRelease) {
                if (!isPressed) suppressedUntilRelease = false
                return
            }
            when {
                isPressed && !wasThresholdPressed -> onPressAction?.invoke()
                !isPressed && wasThresholdPressed -> onReleaseAction?.invoke()
            }
            if (isPressed) whilePressedAction?.invoke()
        }
    }

    /**
     * Two continuously sampled analog axes with radial deadband and curve shaping.
     * A nonfinite component invalidates the pair and immediately clears output and slew history.
     */
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
        private var lastUpdateTimeMs: Long = 0L
        private var hasSlewTimestamp = false
        private var stickConsumer: StickConsumer? = null

        fun label(@Suppress("UNUSED_PARAMETER") description: String) {}

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

        /** Configures a radial maximum rate of change in stick-deflection units per second. */
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
            resetSlew: Boolean,
        ) {
            val selectedX = xSource.read(state).toDouble()
            val selectedY = ySource.read(state).toDouble()
            val valid = selectedX.isFinite() && selectedY.isFinite()
            val rawX = if (valid) selectedX.coerceIn(-1.0, 1.0) else 0.0
            val rawY = if (valid) selectedY.coerceIn(-1.0, 1.0) else 0.0
            val rawMagnitude = hypot(rawX, rawY)
            val normalization = if (rawMagnitude > 1.0) 1.0 / rawMagnitude else 1.0
            x = (rawX * normalization).toFloat()
            y = (rawY * normalization).toFloat()

            val magnitude = rawMagnitude.coerceAtMost(1.0)
            val afterDeadband = if (magnitude <= deadbandThreshold) 0.0 else
                (magnitude - deadbandThreshold) / (1.0 - deadbandThreshold)
            val shapedMagnitude = if (curveExponent == 1.0) afterDeadband else afterDeadband.pow(curveExponent)
            val scaleToRadius = if (rawMagnitude > 0.0) shapedMagnitude / rawMagnitude else 0.0
            var dbX = rawX * scaleToRadius
            var dbY = rawY * scaleToRadius

            if (valid && !resetSlew && slewRateLimit > 0.0) {
                val dt = slewIntervalSeconds(timestampMs, lastUpdateTimeMs, hasSlewTimestamp)
                val maxDelta = slewRateLimit * dt
                val dx = dbX - lastSlewX
                val dy = dbY - lastSlewY
                val deltaMagnitude = hypot(dx, dy)
                val scale = if (deltaMagnitude > maxDelta && deltaMagnitude > 0.0) maxDelta / deltaMagnitude else 1.0
                dbX = lastSlewX + dx * scale
                dbY = lastSlewY + dy * scale
            }

            lastSlewX = dbX
            lastSlewY = dbY
            lastUpdateTimeMs = timestampMs
            hasSlewTimestamp = true

            shapedX = dbX
            shapedY = dbY
            if (notifyConsumer) stickConsumer?.accept(dbX, dbY)
        }

        internal fun notifyConsumer() {
            stickConsumer?.accept(shapedX, shapedY)
        }
    }
}
