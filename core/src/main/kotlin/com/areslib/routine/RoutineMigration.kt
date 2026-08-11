package com.areslib.routine

import com.areslib.auto.AutoRoutine
import com.areslib.auto.AutoStep
import com.areslib.auto.AutoStepKind

/** Legacy-auto migration output keeps autonomous-only metadata outside the neutral routine. */
data class AutoRoutineMigration(
    val document: RoutineDocument,
    val entryPoint: AutonomousRoutineEntryPoint
)

/** Converts the previous `.aresauto` model without losing drive markers or concurrency semantics. */
fun migrateAutoRoutine(auto: AutoRoutine): AutoRoutineMigration {
    val document = RoutineDocument(
        documentId = auto.documentId,
        revision = auto.revision,
        parentContentHash = auto.parentContentHash,
        name = auto.name,
        steps = auto.steps.map(::migrateAutoStep)
    )
    val entryPoint = AutonomousRoutineEntryPoint(
        routineId = document.documentId,
        startingPose = RoutinePose(
            xMeters = auto.startingPose.xMeters,
            yMeters = auto.startingPose.yMeters,
            headingRadians = auto.startingPose.headingRadians
        )
    )
    val errors = validateRoutine(document).filter { it.severity == RoutineValidationSeverity.ERROR }
    require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
    return AutoRoutineMigration(document, entryPoint)
}
private fun migrateAutoStep(step: AutoStep): RoutineStep = when (step.kind) {
    AutoStepKind.DRIVE -> {
        val drive = requireNotNull(step.drive) { "Legacy drive step is missing its payload" }
        RoutineStep.driveTo(
            RoutineDriveStep(
                target = RoutinePose(
                    drive.target.xMeters,
                    drive.target.yMeters,
                    drive.target.headingRadians
                ),
                motionPresetKey = drive.preset.name.lowercase(),
                preferredEngineKey = drive.preferredEngine?.name?.lowercase(),
                markers = drive.markers.map { RoutineDriveMarker(it.progress, it.commandKey) },
                duringActionKeys = drive.duringCommands,
                arrivalActionKeys = drive.arrivalCommands
            )
        )
    }
    AutoStepKind.COMMAND -> RoutineStep.action(requireNotNull(step.commandKey))
    AutoStepKind.WAIT -> RoutineStep.wait(requireNotNull(step.durationSeconds))
    AutoStepKind.TOGETHER -> RoutineStep.together(step.children.map(::migrateAutoStep))
    AutoStepKind.FIRST_TO_FINISH -> RoutineStep.firstToFinish(step.children.map(::migrateAutoStep))
}
