package com.ares.analytics.ui.components.routine

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import java.util.Locale

internal fun routineStepTitle(kind: RoutineStepKind): String = when (kind) {
    RoutineStepKind.ACTION -> "Run robot action"
    RoutineStepKind.DRIVE_TO -> "Drive to field goal"
    RoutineStepKind.WAIT -> "Wait"
    RoutineStepKind.WAIT_UNTIL -> "Wait for robot state"
    RoutineStepKind.TOGETHER -> "Run together"
    RoutineStepKind.FIRST_TO_FINISH -> "Race: first to finish"
    RoutineStepKind.DEADLINE -> "Run until deadline"
    RoutineStepKind.CALL -> "Run reusable routine"
    RoutineStepKind.REPEAT -> "Repeat steps"
    RoutineStepKind.BRANCH -> "Choose based on robot state"
}

internal fun routineStepDescription(kind: RoutineStepKind): String = when (kind) {
    RoutineStepKind.ACTION -> "Run one action from this robot project"
    RoutineStepKind.DRIVE_TO -> "Move to a position and heading on the field"
    RoutineStepKind.WAIT -> "Pause for a fixed amount of time"
    RoutineStepKind.WAIT_UNTIL -> "Continue when a robot condition becomes true"
    RoutineStepKind.TOGETHER -> "Run every child step at the same time"
    RoutineStepKind.FIRST_TO_FINISH -> "Run children together and stop when one finishes"
    RoutineStepKind.DEADLINE -> "Run companions until the main step finishes"
    RoutineStepKind.CALL -> "Place another saved routine inside this routine"
    RoutineStepKind.REPEAT -> "Run a group of steps a fixed number of times"
    RoutineStepKind.BRANCH -> "Choose between two groups using robot state"
}

internal fun routineStepSubtitle(step: RoutineStep): String = when (step.kind) {
    RoutineStepKind.ACTION -> step.actionKey ?: "Choose an action"
    RoutineStepKind.DRIVE_TO -> step.drive?.let { drive ->
        "${formatRoutineNumber(drive.target.xMeters)} m, ${formatRoutineNumber(drive.target.yMeters)} m · ${drive.motionPresetKey}"
    } ?: "Missing target"
    RoutineStepKind.WAIT -> "${formatRoutineNumber(step.durationSeconds ?: 0.0)} seconds"
    RoutineStepKind.WAIT_UNTIL -> "${step.conditionKey ?: "Choose condition"} · timeout ${formatRoutineNumber(step.timeoutSeconds ?: 0.0)} s"
    RoutineStepKind.CALL -> step.routineId ?: "Choose routine"
    RoutineStepKind.REPEAT -> "${step.repeatCount ?: 0} times · ${step.children.size} step(s)"
    RoutineStepKind.BRANCH -> "${step.conditionKey ?: "Choose condition"} · ${step.children.size}/${step.elseChildren.size} step(s)"
    RoutineStepKind.DEADLINE -> "${step.children.size} companion step(s)"
    RoutineStepKind.TOGETHER,
    RoutineStepKind.FIRST_TO_FINISH -> "${step.children.size} parallel step(s)"
}

internal fun formatRoutineNumber(value: Double): String = String.format(Locale.US, "%.2f", value)

internal fun statusColor(status: String): Color =
    if (status.contains("fail", true) || status.contains("fix", true)) AresError else AresTextSecondary

@Composable
internal fun routineTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AresCyan,
    unfocusedBorderColor = AresBorder,
    focusedTextColor = AresTextPrimary,
    unfocusedTextColor = AresTextPrimary,
)
