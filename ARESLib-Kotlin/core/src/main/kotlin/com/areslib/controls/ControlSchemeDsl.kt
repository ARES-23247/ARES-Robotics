package com.areslib.controls

import com.areslib.routine.RoutineArgumentsBuilder

@DslMarker
annotation class AresControlsDsl

internal fun defaultControlSchemeDisplayName(id: String): String {
    val trimmed = id.trim()
    if (trimmed.isEmpty()) return id
    return trimmed
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .split(Regex("[_\\-\\s.]+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { token ->
            token.lowercase().replaceFirstChar(Char::titlecase)
        }
}

/**
 * Builds the same versioned [ControlSchemeDocument] edited by ARES Robotics Studio.
 *
 * When [name] is omitted, it is humanized from [id] (for example, `"competition_default"` becomes
 * `"Competition Default"`).
 *
 * ### Complete Example
 * ```kotlin
 * val competitionControls = controlScheme("competition_default") {
 *     controller("driver", profile = "xbox-series", devicePort = 0) {
 *         axis("left_stick_y")
 *             .deadband(0.05)
 *             .curve(2.0)
 *             .invert()
 *             .slewRate(3.5)
 *             .drive(DriveAxisKeys.VX)
 *
 *         axis("left_stick_x")
 *             .deadband(0.05)
 *             .curve(2.0)
 *             .invert()
 *             .drive(DriveAxisKeys.VY)
 *
 *         axis("right_stick_x")
 *             .deadband(0.05)
 *             .scale(0.85)
 *             .invert()
 *             .drive(DriveAxisKeys.OMEGA)
 *
 *         button("a").debounce(0.03).onPress { action("intake.collect") }
 *         button("a").onRelease { action("intake.stop") }
 *         trigger("right_trigger", pressAt = 0.65, releaseAt = 0.50).whilePressed {
 *             action("shooter.spin") { number("rpm", 4200.0) }
 *         }
 *         chord("left_bumper", "right_bumper").onPress {
 *             routine("score.sequence", RoutineInvocationPolicy.TOGGLE_CANCEL)
 *         }
 *     }
 * }
 * ```
 */
fun controlScheme(
    id: String,
    name: String = defaultControlSchemeDisplayName(id),
    revision: Int = 1,
    description: String? = null,
    block: ControlSchemeBuilder.() -> Unit
): ControlSchemeDocument = ControlSchemeBuilder().apply(block).build(id, name, revision, description)

@AresControlsDsl
class ControlSchemeBuilder internal constructor() {
    private val controllers = mutableListOf<ControllerAssignment>()
    private val bindings = mutableListOf<ControlBindingDocument>()
    private val allocatedBindingIds = linkedSetOf<String>()

    fun controller(
        slot: String,
        profile: String,
        devicePort: Int,
        displayName: String = slot.replaceFirstChar(Char::titlecase),
        block: ControllerControlsBuilder.() -> Unit
    ) {
        controllers += ControllerAssignment(slot, displayName, profile, devicePort)
        ControllerControlsBuilder(slot, ::addBinding).apply(block)
    }

    private fun addBinding(binding: ControlBindingDocument) {
        var candidate = binding.bindingId
        var suffix = 2
        while (!allocatedBindingIds.add(candidate)) {
            val ending = ".$suffix"
            candidate = binding.bindingId.take(64 - ending.length) + ending
            suffix++
        }
        bindings += if (candidate == binding.bindingId) binding else binding.copy(bindingId = candidate)
    }

    internal fun build(id: String, name: String, revision: Int, description: String?): ControlSchemeDocument =
        ControlSchemeDocument(
            documentId = id,
            revision = revision,
            name = name,
            description = description,
            controllers = controllers.toList(),
            bindings = bindings.toList()
        ).also { document ->
            val errors = validateControlScheme(document).filter { it.severity == ControlValidationSeverity.ERROR }
            require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
        }
}

@AresControlsDsl
class ControllerControlsBuilder internal constructor(
    private val slot: String,
    private val addBinding: (ControlBindingDocument) -> Unit
) {
    fun button(controlId: String): DigitalControlBindingBuilder = digital(
        ControlSourceDocument(ControlSourceKind.BUTTON, slot, listOf(controlId)),
        controlId
    )

    fun chord(first: String, second: String, vararg additional: String): DigitalControlBindingBuilder {
        val controls = listOf(first, second) + additional
        return digital(
            ControlSourceDocument(ControlSourceKind.CHORD, slot, controls),
            controls.joinToString("-")
        ).priority(100).suppressSingles()
    }

    fun trigger(
        controlId: String,
        pressAt: Double = 0.65,
        releaseAt: Double = 0.55,
        direction: ControlThresholdDirection = ControlThresholdDirection.ABOVE,
        transform: AxisTransformDocument = AxisTransformDocument(inputMinimum = 0.0, inputCenter = 0.0)
    ): DigitalControlBindingBuilder = digital(
        ControlSourceDocument(
            kind = ControlSourceKind.AXIS_THRESHOLD,
            controllerSlot = slot,
            controlIds = listOf(controlId),
            transform = transform,
            pressThreshold = pressAt,
            releaseThreshold = releaseAt,
            thresholdDirection = direction
        ),
        controlId
    )

    fun axis(
        controlId: String,
        transform: AxisTransformDocument = AxisTransformDocument()
    ): AnalogControlBindingBuilder = AnalogControlBindingBuilder(
        slot,
        controlId,
        ControlSourceDocument(
            ControlSourceKind.AXIS_VALUE,
            slot,
            listOf(controlId),
            transform = transform
        ),
        addBinding
    )

    fun zone(
        controlId: String,
        minimum: Double,
        maximum: Double,
        hysteresis: Double = 0.05,
        transform: AxisTransformDocument = AxisTransformDocument()
    ): AnalogControlBindingBuilder = AnalogControlBindingBuilder(
        slot,
        controlId,
        ControlSourceDocument(
            ControlSourceKind.AXIS_ZONE,
            slot,
            listOf(controlId),
            transform = transform,
            zoneMinimum = minimum,
            zoneMaximum = maximum,
            zoneHysteresis = hysteresis
        ),
        addBinding
    )

    private fun digital(source: ControlSourceDocument, controlLabel: String) = DigitalControlBindingBuilder(
        slot,
        controlLabel,
        source,
        addBinding
    )
}

@AresControlsDsl
class BindingTargetBuilder internal constructor() {
    private var target: ControlTargetDocument? = null

    fun action(key: String, arguments: RoutineArgumentsBuilder.() -> Unit = {}) {
        set(ControlTargetDocument(ControlTargetKind.ACTION, key, RoutineArgumentsBuilder().apply(arguments).build()))
    }

    fun routine(key: String, policy: RoutineInvocationPolicy = RoutineInvocationPolicy.RESTART_EXISTING) {
        set(ControlTargetDocument(ControlTargetKind.ROUTINE, key, routinePolicy = policy))
    }

    fun cancelRoutine(key: String) {
        set(ControlTargetDocument(ControlTargetKind.CANCEL_ROUTINE, key))
    }

    /** Binds an analog axis value to a continuous drivetrain axis in [DriveAxisKeys]. */
    fun drive(axisKey: String) {
        set(ControlTargetDocument(ControlTargetKind.DRIVE, axisKey))
    }

    private fun set(value: ControlTargetDocument) {
        check(target == null) { "A binding event may declare exactly one action or routine target" }
        target = value
    }

    internal fun build(): ControlTargetDocument = requireNotNull(target) {
        "Binding target requires action { }, routine { }, cancelRoutine { }, or drive(...)"
    }
}

@AresControlsDsl
class DigitalControlBindingBuilder internal constructor(
    private val slot: String,
    private val sourceLabel: String,
    private var source: ControlSourceDocument,
    private val addBinding: (ControlBindingDocument) -> Unit
) {
    private var timing = ControlTimingDocument()
    private var priority = 0
    private var suppressSingles = false
    private var displayName: String? = null
    private var explicitId: String? = null

    fun named(name: String, id: String? = null): DigitalControlBindingBuilder = apply {
        displayName = name
        explicitId = id
    }

    fun debounce(pressSeconds: Double, releaseSeconds: Double = pressSeconds): DigitalControlBindingBuilder = apply {
        timing = timing.copy(pressDebounceSeconds = pressSeconds, releaseDebounceSeconds = releaseSeconds)
    }

    fun cooldown(seconds: Double): DigitalControlBindingBuilder = apply {
        timing = timing.copy(cooldownSeconds = seconds)
    }

    fun holdAfter(seconds: Double): DigitalControlBindingBuilder = apply {
        timing = timing.copy(holdAfterSeconds = seconds)
    }

    fun repeat(afterSeconds: Double, everySeconds: Double): DigitalControlBindingBuilder = apply {
        timing = timing.copy(repeatAfterSeconds = afterSeconds, repeatEverySeconds = everySeconds)
    }

    fun maximumActive(seconds: Double): DigitalControlBindingBuilder = apply {
        timing = timing.copy(maximumActiveSeconds = seconds)
    }

    fun chordWindow(seconds: Double): DigitalControlBindingBuilder = apply {
        require(source.kind == ControlSourceKind.CHORD) { "chordWindow is available only for chord bindings" }
        source = source.copy(chordWindowSeconds = seconds)
    }

    /** Configures input deadband on an analog threshold (`trigger`) binding without constructing [AxisTransformDocument]. */
    fun deadband(value: Double): DigitalControlBindingBuilder = updateTriggerTransform { copy(deadband = value) }

    /** Configures response curve exponent on an analog threshold (`trigger`) binding. */
    fun curve(exponent: Double): DigitalControlBindingBuilder = updateTriggerTransform { copy(exponent = exponent) }

    /** Configures axis inversion on an analog threshold (`trigger`) binding. */
    fun invert(inverted: Boolean = true): DigitalControlBindingBuilder = updateTriggerTransform { copy(inverted = inverted) }

    /** Scales the normalized output range of an analog threshold (`trigger`) binding to `[0.0, factor]` or `[-factor, factor]`. */
    fun scale(factor: Double): DigitalControlBindingBuilder = updateTriggerTransform {
        require(factor > 0.0 && factor.isFinite()) { "scale factor must be finite and positive" }
        val minOut = if (inputMinimum >= 0.0) 0.0 else -factor
        copy(outputMinimum = minOut, outputMaximum = factor)
    }

    /** Configures the output range of an analog threshold (`trigger`) binding. */
    fun outputRange(minimum: Double, maximum: Double): DigitalControlBindingBuilder =
        updateTriggerTransform { copy(outputMinimum = minimum, outputMaximum = maximum) }

    /** Configures the raw input range of an analog threshold (`trigger`) binding. */
    fun inputRange(minimum: Double, center: Double = 0.0, maximum: Double): DigitalControlBindingBuilder =
        updateTriggerTransform { copy(inputMinimum = minimum, inputCenter = center, inputMaximum = maximum) }

    fun priority(value: Int): DigitalControlBindingBuilder = apply { priority = value }

    fun suppressSingles(): DigitalControlBindingBuilder = apply { suppressSingles = true }

    fun onPress(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.PRESS, target)
    fun onRelease(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.RELEASE, target)
    fun whileHeld(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.HELD, target)

    /** Alias for [whileHeld] matching [com.areslib.telemetry.AresGamepad.BindableButton.whilePressed]. */
    fun whilePressed(target: BindingTargetBuilder.() -> Unit) = whileHeld(target)

    fun onHold(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.HOLD, target)
    fun onRepeat(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.REPEAT, target)

    private fun updateTriggerTransform(
        block: AxisTransformDocument.() -> AxisTransformDocument
    ): DigitalControlBindingBuilder = apply {
        require(source.kind == ControlSourceKind.AXIS_THRESHOLD) {
            "Axis transform modifiers are available only on trigger(...) or axis(...)/zone(...) bindings"
        }
        val current = source.transform ?: AxisTransformDocument(inputMinimum = 0.0, inputCenter = 0.0)
        source = source.copy(transform = current.block())
    }

    private fun bind(event: ControlEvent, target: BindingTargetBuilder.() -> Unit) {
        val builtTarget = BindingTargetBuilder().apply(target).build()
        val id = explicitId ?: stableBindingId(slot, sourceLabel, event, builtTarget.key)
        addBinding(
            ControlBindingDocument(
                bindingId = id,
                displayName = displayName ?: "$sourceLabel ${event.friendlyName()} ${builtTarget.key}",
                source = source,
                event = event,
                target = builtTarget,
                timing = timing,
                priority = priority,
                suppressConstituentBindings = suppressSingles
            )
        )
    }
}

@AresControlsDsl
class AnalogControlBindingBuilder internal constructor(
    private val slot: String,
    private val sourceLabel: String,
    private var source: ControlSourceDocument,
    private val addBinding: (ControlBindingDocument) -> Unit
) {
    private var policy = AnalogControlPolicyDocument()
    private var displayName: String? = null
    private var explicitId: String? = null

    fun named(name: String, id: String? = null): AnalogControlBindingBuilder = apply {
        displayName = name
        explicitId = id
    }

    /** Configures input deadband without manually constructing [AxisTransformDocument]. */
    fun deadband(value: Double): AnalogControlBindingBuilder = updateTransform { copy(deadband = value) }

    /** Configures response curve exponent (`1.0` linear, `2.0` quadratic, `3.0` cubic). */
    fun curve(exponent: Double): AnalogControlBindingBuilder = updateTransform { copy(exponent = exponent) }

    /** Inverts the shaped axis output. */
    fun invert(inverted: Boolean = true): AnalogControlBindingBuilder = updateTransform { copy(inverted = inverted) }

    /** Symmetrically scales the normalized output range to `[-factor, +factor]`. */
    fun scale(factor: Double): AnalogControlBindingBuilder = updateTransform {
        require(factor > 0.0 && factor.isFinite()) { "scale factor must be finite and positive" }
        copy(outputMinimum = -factor, outputMaximum = factor)
    }

    /** Explicitly configures the shaped output range `[minimum, maximum]`. */
    fun outputRange(minimum: Double, maximum: Double): AnalogControlBindingBuilder =
        updateTransform { copy(outputMinimum = minimum, outputMaximum = maximum) }

    /** Explicitly configures the raw input range `[minimum, maximum]` and neutral [center]. */
    fun inputRange(minimum: Double, center: Double = 0.0, maximum: Double): AnalogControlBindingBuilder =
        updateTransform { copy(inputMinimum = minimum, inputCenter = center, inputMaximum = maximum) }

    fun onlyOnChange(epsilon: Double = 1e-3): AnalogControlBindingBuilder = apply {
        policy = policy.copy(emitOnlyOnChange = true, changeEpsilon = epsilon)
    }

    fun slew(risePerSecond: Double, fallPerSecond: Double = risePerSecond): AnalogControlBindingBuilder = apply {
        policy = policy.copy(riseRatePerSecond = risePerSecond, fallRatePerSecond = fallPerSecond)
    }

    /** Alias for [slew] matching standard `slewRate(...)` terminology across ARES DSLs. */
    fun slewRate(risePerSecond: Double, fallPerSecond: Double = risePerSecond): AnalogControlBindingBuilder =
        slew(risePerSecond, fallPerSecond)

    fun rearmNearNeutral(threshold: Double): AnalogControlBindingBuilder = apply {
        policy = policy.copy(rearmNeutralThreshold = threshold)
    }

    fun onValue(valueArgument: String = "value", target: BindingTargetBuilder.() -> Unit) =
        bind(ControlEvent.VALUE, valueArgument, target)

    /** Shorthand for `onValue { drive(axisKey) }` to bind an analog stick directly to a [DriveAxisKeys] axis. */
    fun drive(axisKey: String) = onValue { drive(axisKey) }

    fun onEnter(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.ZONE_ENTER, "value", target)
    fun whileInside(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.ZONE_ACTIVE, "value", target)
    fun onExit(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.ZONE_EXIT, "value", target)

    private fun updateTransform(
        block: AxisTransformDocument.() -> AxisTransformDocument
    ): AnalogControlBindingBuilder = apply {
        val current = source.transform ?: AxisTransformDocument()
        source = source.copy(transform = current.block())
    }

    private fun bind(event: ControlEvent, valueArgument: String, target: BindingTargetBuilder.() -> Unit) {
        val builtTarget = BindingTargetBuilder().apply(target).build()
        val id = explicitId ?: stableBindingId(slot, sourceLabel, event, builtTarget.key)
        addBinding(
            ControlBindingDocument(
                bindingId = id,
                displayName = displayName ?: "$sourceLabel ${event.friendlyName()} ${builtTarget.key}",
                source = source,
                event = event,
                target = builtTarget,
                analogPolicy = policy.copy(valueArgumentKey = valueArgument)
            )
        )
    }
}

private fun stableBindingId(slot: String, source: String, event: ControlEvent, target: String): String =
    "$slot.$source.${event.name.lowercase()}.$target"
        .replace(INVALID_BINDING_ID_CHARACTERS, "-")
        .take(64)

private fun ControlEvent.friendlyName(): String = name.lowercase().replace('_', ' ')

private val INVALID_BINDING_ID_CHARACTERS = Regex("[^A-Za-z0-9._-]+")
