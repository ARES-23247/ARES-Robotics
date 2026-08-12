package com.areslib.controls

import com.areslib.routine.RoutineArgumentsBuilder

@DslMarker
annotation class AresControlsDsl

/** Builds the same versioned control document edited by Analytics. */
fun controlScheme(
    id: String,
    name: String,
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
        displayName: String = slot.replaceFirstChar(Char::titlecase),
        block: ControllerControlsBuilder.() -> Unit
    ) {
        controllers += ControllerAssignment(slot, displayName, profile)
        ControllerControlsBuilder(slot, ::addBinding).apply(block)
    }

    private fun addBinding(binding: ControlBindingDocument) {
        var candidate = binding.bindingId
        var suffix = 2
        while (!allocatedBindingIds.add(candidate)) candidate = "${binding.bindingId}.$suffix".also { suffix++ }
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

    fun chord(first: String, second: String, vararg additional: String): DigitalControlBindingBuilder = digital(
        ControlSourceDocument(ControlSourceKind.CHORD, slot, listOf(first, second) + additional),
        (listOf(first, second) + additional).joinToString("-")
    ).priority(100).suppressSingles()

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

    private fun set(value: ControlTargetDocument) {
        check(target == null) { "A binding event may declare exactly one action or routine target" }
        target = value
    }

    internal fun build(): ControlTargetDocument = requireNotNull(target) {
        "Binding target requires action { }, routine { }, or cancelRoutine { }"
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

    fun priority(value: Int): DigitalControlBindingBuilder = apply { priority = value }

    fun suppressSingles(): DigitalControlBindingBuilder = apply { suppressSingles = true }

    fun onPress(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.PRESS, target)
    fun onRelease(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.RELEASE, target)
    fun whileHeld(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.HELD, target)
    fun onHold(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.HOLD, target)
    fun onRepeat(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.REPEAT, target)

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
    private val source: ControlSourceDocument,
    private val addBinding: (ControlBindingDocument) -> Unit
) {
    private var policy = AnalogControlPolicyDocument()
    private var displayName: String? = null
    private var explicitId: String? = null

    fun named(name: String, id: String? = null): AnalogControlBindingBuilder = apply {
        displayName = name
        explicitId = id
    }

    fun onlyOnChange(epsilon: Double = 1e-3): AnalogControlBindingBuilder = apply {
        policy = policy.copy(emitOnlyOnChange = true, changeEpsilon = epsilon)
    }

    fun slew(risePerSecond: Double, fallPerSecond: Double = risePerSecond): AnalogControlBindingBuilder = apply {
        policy = policy.copy(riseRatePerSecond = risePerSecond, fallRatePerSecond = fallPerSecond)
    }

    fun rearmNearNeutral(threshold: Double): AnalogControlBindingBuilder = apply {
        policy = policy.copy(rearmNeutralThreshold = threshold)
    }

    fun onValue(valueArgument: String = "value", target: BindingTargetBuilder.() -> Unit) =
        bind(ControlEvent.VALUE, valueArgument, target)

    fun onEnter(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.ZONE_ENTER, "value", target)
    fun whileInside(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.ZONE_ACTIVE, "value", target)
    fun onExit(target: BindingTargetBuilder.() -> Unit) = bind(ControlEvent.ZONE_EXIT, "value", target)

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
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .take(64)

private fun ControlEvent.friendlyName(): String = name.lowercase().replace('_', ' ')
