package com.areslib.controls

/** Current schema written to project-local `.arescontrols` documents. */
const val ARES_CONTROL_SCHEME_SCHEMA_VERSION: Int = 1

/** Stable logical controller roles. Projects may add roles beyond driver and operator. */
data class ControllerAssignment(
    val slot: String,
    val displayName: String,
    val profileId: String
)

/** Physical input shape used by one binding. */
enum class ControlSourceKind {
    BUTTON,
    CHORD,
    AXIS_THRESHOLD,
    AXIS_VALUE,
    AXIS_ZONE
}

/** Event emitted by a binding after its timing and hysteresis policy is satisfied. */
enum class ControlEvent {
    PRESS,
    RELEASE,
    HELD,
    HOLD,
    REPEAT,
    VALUE,
    ZONE_ENTER,
    ZONE_ACTIVE,
    ZONE_EXIT
}

/** Direction used when turning an analog value into a digital input. */
enum class ControlThresholdDirection { ABOVE, BELOW }

/** What a binding invokes after its event fires. */
enum class ControlTargetKind {
    ACTION,
    ROUTINE,
    CANCEL_ROUTINE
}

/** How a routine binding behaves if the same routine is already active. */
enum class RoutineInvocationPolicy {
    IGNORE_IF_RUNNING,
    RESTART_EXISTING,
    QUEUE,
    PARALLEL,
    TOGGLE_CANCEL
}

/** Serializable analog shaping settings compiled to `AxisTransform`. */
data class AxisTransformDocument(
    val inputMinimum: Double = -1.0,
    val inputCenter: Double = 0.0,
    val inputMaximum: Double = 1.0,
    val outputMinimum: Double = -1.0,
    val outputMaximum: Double = 1.0,
    val deadband: Double = 0.0,
    val exponent: Double = 1.0,
    val inverted: Boolean = false
)

/**
 * One physical or virtual input source.
 *
 * [controlIds] contains one profile control for buttons and axes, and two or more controls for a
 * chord. Threshold and zone fields are used only by their matching [kind]. Durations are seconds
 * in project files; generated robot code converts them to monotonic nanoseconds once at startup.
 */
data class ControlSourceDocument(
    val kind: ControlSourceKind,
    val controllerSlot: String,
    val controlIds: List<String>,
    val transform: AxisTransformDocument? = null,
    val pressThreshold: Double? = null,
    val releaseThreshold: Double? = null,
    val thresholdDirection: ControlThresholdDirection = ControlThresholdDirection.ABOVE,
    val zoneMinimum: Double? = null,
    val zoneMaximum: Double? = null,
    val zoneHysteresis: Double = 0.0,
    val chordWindowSeconds: Double = 0.075
)

/** Debounce, hold, repeat, cooldown, and fail-safe timing for a digital binding. */
data class ControlTimingDocument(
    val pressDebounceSeconds: Double = 0.0,
    val releaseDebounceSeconds: Double = 0.0,
    val holdAfterSeconds: Double? = null,
    val repeatAfterSeconds: Double? = null,
    val repeatEverySeconds: Double? = null,
    val cooldownSeconds: Double = 0.0,
    val maximumActiveSeconds: Double? = null
)

/** Analog update-rate and disconnect behavior. */
data class AnalogControlPolicyDocument(
    val emitOnlyOnChange: Boolean = false,
    val changeEpsilon: Double = 1e-6,
    val riseRatePerSecond: Double? = null,
    val fallRatePerSecond: Double? = null,
    val rearmNeutralThreshold: Double = 0.05,
    /** Argument name that receives the current analog value for action targets. */
    val valueArgumentKey: String = "value"
)

/** Action or routine reference invoked by a binding. */
data class ControlTargetDocument(
    val kind: ControlTargetKind,
    val key: String,
    val arguments: Map<String, String> = emptyMap(),
    val routinePolicy: RoutineInvocationPolicy = RoutineInvocationPolicy.RESTART_EXISTING
)

/** One visual-editor binding, retained by a stable ID across revisions. */
data class ControlBindingDocument(
    val bindingId: String,
    val displayName: String,
    val source: ControlSourceDocument,
    val event: ControlEvent,
    val target: ControlTargetDocument,
    val timing: ControlTimingDocument = ControlTimingDocument(),
    val analogPolicy: AnalogControlPolicyDocument? = null,
    /** Higher-priority chord bindings may suppress their constituent button bindings. */
    val priority: Int = 0,
    val suppressConstituentBindings: Boolean = false,
    val enabled: Boolean = true
)

/**
 * Versioned, offline-first controller mapping edited by Analytics and compiled onto the robot.
 *
 * The document contains no platform code or hardware access. Targets are stable catalog/routine
 * keys, allowing the same file to drive FTC, FRC, simulator, code generation, and GUI validation.
 */
data class ControlSchemeDocument(
    val schemaVersion: Int = ARES_CONTROL_SCHEME_SCHEMA_VERSION,
    val documentId: String,
    val revision: Int = 1,
    val parentContentHash: String? = null,
    val name: String,
    val description: String? = null,
    val controllers: List<ControllerAssignment>,
    val bindings: List<ControlBindingDocument>
)
