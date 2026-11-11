package com.areslib.routine

import kotlin.time.Duration
import kotlin.time.DurationUnit

/** Prevents nested routine receivers from accidentally calling the wrong builder. */
@DslMarker
annotation class AresRoutineDsl

internal fun defaultRoutineDisplayName(id: String): String {
    val spaced = id
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
    if (spaced.isEmpty()) return id
    return spaced.split(Regex("\\s+")).joinToString(" ") { token ->
        token.replaceFirstChar { it.uppercase() }
    }
}

/**
 * Creates a validated, trigger-neutral [RoutineDocument] used by both the visual Routine Builder
 * and text-first autonomous/macro authoring.
 *
 * When [name] is omitted, a human-readable display name is derived from [id]
 * (for example, `"score-preload"` -> `"Score Preload"`).
 *
 * ### Example
 * ```kotlin
 * val scorePreload = routine("score-preload") {
 *     driveTo(xMeters = 1.25, yMeters = 0.45, headingDegrees = 90.0) {
 *         whileDriving("intake.collect")
 *         onArrival("arm.scoreHeight")
 *     }
 *     waitUntil(condition = "arm.atTarget", timeoutSeconds = 1.5)
 *     together {
 *         action("outtake.release")
 *         waitSeconds(0.4)
 *     }
 *     action("arm.stow")
 * }
 * ```
 */
fun routine(
    id: String,
    name: String = defaultRoutineDisplayName(id),
    revision: Int = 1,
    description: String? = null,
    block: RoutineBuilder.() -> Unit
): RoutineDocument = RoutineBuilder().apply(block).build(id, name, revision, description)

@AresRoutineDsl
class RoutineBuilder internal constructor() {
    private val steps = mutableListOf<RoutineStep>()

    /** Replaces the generated identity when a hand-authored DSL needs a durable external reference. */
    fun identified(stepId: String, block: RoutineBuilder.() -> Unit) {
        val nested = RoutineBuilder().apply(block).snapshot()
        require(nested.size == 1) { "identified { } must contain exactly one step" }
        steps += nested.single().copy(stepId = stepId)
    }

    /** Runs one project action. Arguments are validated against the generated capability catalog. */
    fun action(key: String, block: RoutineArgumentsBuilder.() -> Unit = {}) {
        steps += RoutineStep.action(key, RoutineArgumentsBuilder().apply(block).build())
    }

    /**
     * Runs one project action by [key]. Alias for [action] that aligns with step-oriented sequence authoring.
     */
    fun step(key: String, block: RoutineArgumentsBuilder.() -> Unit = {}) {
        action(key, block)
    }

    /**
     * Runs one project action by [key]. Alias for [action] that matches [com.areslib.sequencer.RobotSequence.namedCommand].
     */
    fun namedCommand(key: String, block: RoutineArgumentsBuilder.() -> Unit = {}) {
        action(key, block)
    }

    /** Drives to an exact CCW-positive field pose. Degrees are accepted only at this UI boundary. */
    fun driveTo(
        xMeters: Double,
        yMeters: Double,
        headingDegrees: Double,
        block: DriveStepBuilder.() -> Unit = {}
    ) {
        val builder = DriveStepBuilder(RoutinePose(xMeters, yMeters, Math.toRadians(headingDegrees))).apply(block)
        steps += RoutineStep.driveTo(builder.build())
    }

    /** Non-blocking deterministic delay in seconds. */
    fun waitSeconds(seconds: Double) {
        steps += RoutineStep.wait(seconds)
    }

    /**
     * Non-blocking deterministic delay using a Kotlin [Duration].
     * Aligns with [com.areslib.sequencer.RobotSequence.waitFor].
     */
    fun waitFor(duration: Duration) {
        require(duration.isFinite() && !duration.isNegative()) { "Wait duration must be finite and non-negative" }
        waitSeconds(duration.toDouble(DurationUnit.SECONDS))
    }

    /** Waits for a named Redux condition, with a mandatory fail-safe timeout in seconds. */
    fun waitUntil(
        condition: String,
        timeoutSeconds: Double,
        arguments: RoutineArgumentsBuilder.() -> Unit = {}
    ) {
        steps += RoutineStep.waitUntil(
            condition,
            timeoutSeconds,
            RoutineArgumentsBuilder().apply(arguments).build()
        )
    }

    /** Waits for a named Redux condition, with a mandatory fail-safe [timeout] [Duration]. */
    fun waitUntil(
        condition: String,
        timeout: Duration,
        arguments: RoutineArgumentsBuilder.() -> Unit = {}
    ) {
        require(timeout.isFinite() && timeout.isPositive()) { "Wait timeout must be finite and positive" }
        waitUntil(condition, timeout.toDouble(DurationUnit.SECONDS), arguments)
    }

    /** Runs all children and completes only after every child completes. */
    fun together(block: RoutineBuilder.() -> Unit) {
        steps += RoutineStep.together(RoutineBuilder().apply(block).snapshot())
    }

    /**
     * Runs all children concurrently and completes only after every child completes.
     * Alias for [together] that matches [com.areslib.sequencer.RobotSequence.parallel].
     */
    fun parallel(block: RoutineBuilder.() -> Unit) {
        together(block)
    }

    /** Runs all children and safely interrupts the remainder when the first child completes. */
    fun firstToFinish(block: RoutineBuilder.() -> Unit) {
        steps += RoutineStep.firstToFinish(RoutineBuilder().apply(block).snapshot())
    }

    /**
     * Runs all children concurrently and safely interrupts the remainder when the first child completes.
     * Alias for [firstToFinish] that matches [com.areslib.sequencer.RobotSequence.race].
     */
    fun race(block: RoutineBuilder.() -> Unit) {
        firstToFinish(block)
    }

    /** Runs companions until [main] completes, then safely interrupts them. */
    fun deadline(main: RoutineBuilder.() -> Unit, companions: RoutineBuilder.() -> Unit) {
        val deadlineSteps = RoutineBuilder().apply(main).snapshot()
        require(deadlineSteps.size == 1) { "deadline main { } must contain exactly one step" }
        steps += RoutineStep.deadline(deadlineSteps.single(), RoutineBuilder().apply(companions).snapshot())
    }

    /** Reuses another routine without duplicating its steps. */
    fun call(routineId: String) {
        steps += RoutineStep.call(routineId)
    }

    /** Repeats a bounded block. Unbounded loops are intentionally unavailable. */
    fun repeatTimes(count: Int, block: RoutineBuilder.() -> Unit) {
        steps += RoutineStep.repeat(count, RoutineBuilder().apply(block).snapshot())
    }

    /** Selects one branch from the state observed when this node begins. */
    fun branch(
        condition: String,
        arguments: RoutineArgumentsBuilder.() -> Unit = {},
        block: RoutineBranchBuilder.() -> Unit
    ) {
        val branch = RoutineBranchBuilder().apply(block)
        steps += RoutineStep.branch(
            condition,
            branch.trueSteps(),
            branch.falseSteps(),
            RoutineArgumentsBuilder().apply(arguments).build()
        )
    }

    internal fun snapshot(): List<RoutineStep> = steps.toList()

    internal fun build(id: String, name: String, revision: Int, description: String?): RoutineDocument =
        RoutineDocument(
            documentId = id,
            revision = revision,
            name = name,
            description = description,
            steps = snapshot()
        ).also { document ->
            val errors = validateRoutine(document).filter { it.severity == RoutineValidationSeverity.ERROR }
            require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
        }
}
/** Strongly typed argument payload shared by action, condition, and branch DSL nodes. */
class RoutineArgumentsBuilder internal constructor() {
    private val values = linkedMapOf<String, String>()

    fun number(key: String, value: Double) {
        require(value.isFinite()) { "Argument '$key' must be finite" }
        put(key, value.toString())
    }

    fun integer(key: String, value: Long) = put(key, value.toString())

    fun boolean(key: String, value: Boolean) = put(key, value.toString())

    fun text(key: String, value: String) = put(key, value)

    fun option(key: String, value: Enum<*>) = put(key, value.name)

    fun option(key: String, value: String) = put(key, value)

    private fun put(key: String, value: String) {
        require(key.matches(ARGUMENT_KEY)) { "Argument '$key' is not a stable key" }
        check(values.putIfAbsent(key, value) == null) { "Argument '$key' was already declared" }
    }

    internal fun build(): Map<String, String> = values.toMap()

    private companion object {
        val ARGUMENT_KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
    }
}

@AresRoutineDsl
class DriveStepBuilder internal constructor(private val target: RoutinePose) {
    private var preset = "balanced"
    private var preferredEngine: String? = null
    private val markers = mutableListOf<RoutineDriveMarker>()
    private val duringActions = mutableListOf<String>()
    private val arrivalActions = mutableListOf<String>()
    private var headingMode: RoutineHeadingMode = RoutineHeadingMode.LINEAR
    private var lookAtPoint: RoutinePose? = null
    private var blendHandoff: Boolean = false

    fun motionPreset(key: String) {
        preset = key
    }

    /** Optional explicit engine; normally the trajectory selector chooses the best available one. */
    fun preferEngine(key: String?) {
        preferredEngine = key
    }

    /** Fires an action once at normalized route progress `[0, 1]`. */
    fun atProgress(progress: Double, action: String) {
        markers += RoutineDriveMarker(progress, action)
    }

    /** Keeps an action/resource active for the drive step according to its generated adapter. */
    fun whileDriving(action: String) {
        duringActions += action
    }

    /** Fires an action after the drive reaches its destination. */
    fun onArrival(action: String) {
        arrivalActions += action
    }

    /** Configures the heading interpolation law along this drive segment. */
    fun headingMode(mode: RoutineHeadingMode) {
        headingMode = mode
    }

    /** Continuously rotates the robot to face `(xMeters, yMeters)` on the field during this drive segment. */
    fun pointTowards(xMeters: Double, yMeters: Double) {
        headingMode = RoutineHeadingMode.POINT_TOWARDS
        lookAtPoint = RoutinePose(xMeters = xMeters, yMeters = yMeters, headingRadians = 0.0)
    }

    /** Enables momentum-preserving Foresight stopping-pose handoff (`pathSkip`) into the next drive step. */
    /** Alias for [blendIntoNext] matching [RoutineDriveStep.blendHandoff]. */
    fun blendHandoff(enabled: Boolean = true) = blendIntoNext(enabled)

    fun blendIntoNext(enabled: Boolean = true) {
        blendHandoff = enabled
    }

    internal fun build() = RoutineDriveStep(
        target = target,
        motionPresetKey = preset,
        preferredEngineKey = preferredEngine,
        markers = markers.toList(),
        duringActionKeys = duringActions.toList(),
        arrivalActionKeys = arrivalActions.toList(),
        headingMode = headingMode,
        lookAtPoint = lookAtPoint,
        blendHandoff = blendHandoff
    )
}

@AresRoutineDsl
class RoutineBranchBuilder internal constructor() {
    private var whenTrue: List<RoutineStep>? = null
    private var whenFalse: List<RoutineStep>? = null

    fun then(block: RoutineBuilder.() -> Unit) {
        check(whenTrue == null) { "branch then { } may only be declared once" }
        whenTrue = RoutineBuilder().apply(block).snapshot()
    }

    fun otherwise(block: RoutineBuilder.() -> Unit) {
        check(whenFalse == null) { "branch otherwise { } may only be declared once" }
        whenFalse = RoutineBuilder().apply(block).snapshot()
    }

    internal fun trueSteps(): List<RoutineStep> = requireNotNull(whenTrue) { "branch requires then { }" }
    internal fun falseSteps(): List<RoutineStep> = whenFalse.orEmpty()
}

/** Formats [RoutineValidationIssue] entries into an actionable multi-line diagnostic report. */
fun formatRoutineValidationReport(issues: List<RoutineValidationIssue>): String {
    if (issues.isEmpty()) return "Routine document is valid."
    val errors = issues.filter { it.severity == RoutineValidationSeverity.ERROR }
    val warnings = issues.filter { it.severity == RoutineValidationSeverity.WARNING }
    return buildString {
        appendLine("Routine validation found ${errors.size} error(s) and ${warnings.size} warning(s):")
        errors.forEach { issue ->
            appendLine("  [ERROR] ${issue.documentId}.${issue.path} (${issue.code}): ${issue.message}")
        }
        warnings.forEach { issue ->
            appendLine("  [WARN]  ${issue.documentId}.${issue.path} (${issue.code}): ${issue.message}")
        }
    }.trimEnd()
}
