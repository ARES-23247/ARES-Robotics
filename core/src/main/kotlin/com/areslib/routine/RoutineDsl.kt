package com.areslib.routine

/** Prevents nested routine receivers from accidentally calling the wrong builder. */
@DslMarker
annotation class AresRoutineDsl

/** Creates the same trigger-neutral document used by the visual Routine Builder. */
fun routine(
    id: String,
    name: String,
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
    fun action(key: String, vararg arguments: Pair<String, Any>) {
        steps += RoutineStep.action(key, arguments.toRoutineArguments())
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

    /** Non-blocking deterministic delay. */
    fun waitSeconds(seconds: Double) {
        steps += RoutineStep.wait(seconds)
    }

    /** Waits for a named Redux condition, with a mandatory fail-safe timeout. */
    fun waitUntil(condition: String, timeoutSeconds: Double, vararg arguments: Pair<String, Any>) {
        steps += RoutineStep.waitUntil(condition, timeoutSeconds, arguments.toRoutineArguments())
    }

    /** Runs all children and completes only after every child completes. */
    fun together(block: RoutineBuilder.() -> Unit) {
        steps += RoutineStep.together(RoutineBuilder().apply(block).snapshot())
    }

    /** Runs all children and safely interrupts the remainder when the first child completes. */
    fun firstToFinish(block: RoutineBuilder.() -> Unit) {
        steps += RoutineStep.firstToFinish(RoutineBuilder().apply(block).snapshot())
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
        vararg arguments: Pair<String, Any>,
        block: RoutineBranchBuilder.() -> Unit
    ) {
        val branch = RoutineBranchBuilder().apply(block)
        steps += RoutineStep.branch(
            condition,
            branch.trueSteps(),
            branch.falseSteps(),
            arguments.toRoutineArguments()
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

@AresRoutineDsl
class DriveStepBuilder internal constructor(private val target: RoutinePose) {
    private var preset = "balanced"
    private var preferredEngine: String? = null
    private val markers = mutableListOf<RoutineDriveMarker>()
    private val duringActions = mutableListOf<String>()
    private val arrivalActions = mutableListOf<String>()

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

    internal fun build() = RoutineDriveStep(
        target = target,
        motionPresetKey = preset,
        preferredEngineKey = preferredEngine,
        markers = markers.toList(),
        duringActionKeys = duringActions.toList(),
        arrivalActionKeys = arrivalActions.toList()
    )
}

@AresRoutineDsl
class RoutineBranchBuilder internal constructor() {
    private var whenTrue: List<RoutineStep>? = null
    private var whenFalse: List<RoutineStep> = emptyList()

    fun then(block: RoutineBuilder.() -> Unit) {
        check(whenTrue == null) { "branch then { } may only be declared once" }
        whenTrue = RoutineBuilder().apply(block).snapshot()
    }

    fun otherwise(block: RoutineBuilder.() -> Unit) {
        check(whenFalse.isEmpty()) { "branch otherwise { } may only be declared once" }
        whenFalse = RoutineBuilder().apply(block).snapshot()
    }

    internal fun trueSteps(): List<RoutineStep> = requireNotNull(whenTrue) { "branch requires then { }" }
    internal fun falseSteps(): List<RoutineStep> = whenFalse
}

private fun Array<out Pair<String, Any>>.toRoutineArguments(): Map<String, String> = associate { (key, value) ->
    key to when (value) {
        is Double -> require(value.isFinite()) { "Argument '$key' must be finite" }.let { value.toString() }
        is Float -> require(value.isFinite()) { "Argument '$key' must be finite" }.let { value.toString() }
        is Number, is Boolean, is String, is Enum<*> -> value.toString()
        else -> error("Argument '$key' must be a number, Boolean, String, or enum")
    }
}
