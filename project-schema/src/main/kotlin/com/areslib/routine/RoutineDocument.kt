package com.areslib.routine

import java.util.UUID

/** Current on-disk schema for `.aresroutine` documents. */
const val ARES_ROUTINE_SCHEMA_VERSION: Int = 2

/** Creates a persistent authoring identity for one routine node. */
fun newRoutineStepId(): String = "step-${UUID.randomUUID()}"

/**
 * A versioned, trigger-neutral robot routine.
 *
 * A document intentionally does not contain a starting pose, controller button, or match mode.
 * Those belong to entry points that invoke the routine. This lets the same scoring routine run
 * from autonomous, a controller binding, another routine, or a test.
 */
data class RoutineDocument(
    val schemaVersion: Int = ARES_ROUTINE_SCHEMA_VERSION,
    val documentId: String,
    val revision: Int = 1,
    val parentContentHash: String? = null,
    val name: String,
    val description: String? = null,
    val steps: List<RoutineStep>
)

/** Serializable CCW-positive field pose used by drive steps and autonomous entry points. */
data class RoutinePose(
    val xMeters: Double,
    val yMeters: Double,
    val headingRadians: Double
)

/** Supported control-flow and behavior nodes in a routine document. */
enum class RoutineStepKind {
    ACTION,
    DRIVE_TO,
    WAIT,
    WAIT_UNTIL,
    TOGETHER,
    FIRST_TO_FINISH,
    DEADLINE,
    CALL,
    REPEAT,
    BRANCH
}
/** An action fired at a normalized point along a drive step. */
data class RoutineDriveMarker(
    val progress: Double,
    val actionKey: String
)

/**
 * Declarative field-motion request.
 *
 * Preset and engine are stable string keys so this shared format does not depend on an editor,
 * platform trajectory implementation, or action-catalog schema.
 */
data class RoutineDriveStep(
    val target: RoutinePose,
    val motionPresetKey: String = "balanced",
    val preferredEngineKey: String? = null,
    val markers: List<RoutineDriveMarker> = emptyList(),
    val duringActionKeys: List<String> = emptyList(),
    val arrivalActionKeys: List<String> = emptyList()
)

/**
 * One serializable routine node.
 *
 * The document uses a tagged, flat payload rather than JVM polymorphism so files remain portable
 * across Android, desktop, and RoboRIO Gson versions. [validateRoutine] enforces the exact fields
 * allowed by each [kind]. Action arguments are encoded strings and interpreted according to the
 * independently generated action catalog.
 */
data class RoutineStep(
    val kind: RoutineStepKind,
    val stepId: String = newRoutineStepId(),
    val actionKey: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val drive: RoutineDriveStep? = null,
    val durationSeconds: Double? = null,
    val timeoutSeconds: Double? = null,
    val conditionKey: String? = null,
    val routineId: String? = null,
    val repeatCount: Int? = null,
    val children: List<RoutineStep> = emptyList(),
    val deadline: RoutineStep? = null,
    val elseChildren: List<RoutineStep> = emptyList()
) {
    companion object {
        fun action(
            key: String,
            arguments: Map<String, String> = emptyMap(),
            stepId: String = newRoutineStepId()
        ): RoutineStep = RoutineStep(kind = RoutineStepKind.ACTION, stepId = stepId, actionKey = key, arguments = arguments)

        fun driveTo(drive: RoutineDriveStep, stepId: String = newRoutineStepId()): RoutineStep =
            RoutineStep(kind = RoutineStepKind.DRIVE_TO, stepId = stepId, drive = drive)

        fun wait(seconds: Double, stepId: String = newRoutineStepId()): RoutineStep =
            RoutineStep(kind = RoutineStepKind.WAIT, stepId = stepId, durationSeconds = seconds)

        fun waitUntil(
            conditionKey: String,
            timeoutSeconds: Double,
            arguments: Map<String, String> = emptyMap(),
            stepId: String = newRoutineStepId()
        ): RoutineStep = RoutineStep(
            kind = RoutineStepKind.WAIT_UNTIL,
            stepId = stepId,
            conditionKey = conditionKey,
            timeoutSeconds = timeoutSeconds,
            arguments = arguments
        )

        fun together(children: List<RoutineStep>, stepId: String = newRoutineStepId()): RoutineStep =
            RoutineStep(kind = RoutineStepKind.TOGETHER, stepId = stepId, children = children)

        fun firstToFinish(children: List<RoutineStep>, stepId: String = newRoutineStepId()): RoutineStep =
            RoutineStep(kind = RoutineStepKind.FIRST_TO_FINISH, stepId = stepId, children = children)

        fun deadline(
            deadline: RoutineStep,
            companions: List<RoutineStep>,
            stepId: String = newRoutineStepId()
        ): RoutineStep = RoutineStep(
            kind = RoutineStepKind.DEADLINE,
            stepId = stepId,
            deadline = deadline,
            children = companions
        )

        fun call(routineId: String, stepId: String = newRoutineStepId()): RoutineStep =
            RoutineStep(kind = RoutineStepKind.CALL, stepId = stepId, routineId = routineId)

        fun repeat(
            count: Int,
            children: List<RoutineStep>,
            stepId: String = newRoutineStepId()
        ): RoutineStep = RoutineStep(
            kind = RoutineStepKind.REPEAT,
            stepId = stepId,
            repeatCount = count,
            children = children
        )

        fun branch(
            conditionKey: String,
            whenTrue: List<RoutineStep>,
            whenFalse: List<RoutineStep> = emptyList(),
            arguments: Map<String, String> = emptyMap(),
            stepId: String = newRoutineStepId()
        ): RoutineStep = RoutineStep(
            kind = RoutineStepKind.BRANCH,
            stepId = stepId,
            conditionKey = conditionKey,
            arguments = arguments,
            children = whenTrue,
            elseChildren = whenFalse
        )
    }
}
