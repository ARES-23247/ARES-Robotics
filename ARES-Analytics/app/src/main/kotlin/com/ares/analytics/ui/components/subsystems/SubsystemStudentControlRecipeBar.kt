package com.ares.analytics.ui.components.subsystems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.screens.ConceptHelp
import com.ares.analytics.ui.screens.HomingConceptLab
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.areslib.subsystem.SubsystemContinuousInputDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFeedforwardDocument
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHomingComparison
import com.areslib.subsystem.SubsystemHomingEvidenceDocument
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemMotionProfileDocument
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType

/**
 * 1-click student control law recipes that configure strategy, feedforward model,
 * motion profile limits, and matching canonical units in one step.
 */
enum class SubsystemStudentControlRecipe(
    val id: String,
    val title: String,
    val subtitle: String,
    val formulaBadge: String,
    val strategy: SubsystemControlStrategy,
    val feedforwardKind: SubsystemFeedforwardKind,
    val unit: String,
) {
    HOLD_ANGLE_GRAVITY(
        id = "hold_angle_gravity",
        title = "Hold Angle + Gravity FF",
        subtitle = "Pivoting arm / wrist that fights cosine gravity",
        formulaBadge = "Profiled PID + kG·cos(θ) (rad)",
        strategy = SubsystemControlStrategy.PROFILED_POSITION_PID,
        feedforwardKind = SubsystemFeedforwardKind.ARM,
        unit = "rad",
    ),
    HOLD_HEIGHT_ELEVATOR(
        id = "hold_height_elevator",
        title = "Hold Height + Soft Limits",
        subtitle = "Vertical elevator / slide with constant gravity load",
        formulaBadge = "Profiled PID + kG (m)",
        strategy = SubsystemControlStrategy.PROFILED_POSITION_PID,
        feedforwardKind = SubsystemFeedforwardKind.ELEVATOR,
        unit = "m",
    ),
    SPIN_VELOCITY_FLYWHEEL(
        id = "spin_velocity_flywheel",
        title = "Spin Velocity Flywheel",
        subtitle = "Shooter wheel regulating launch speed",
        formulaBadge = "Velocity PID + kS + kV·ω (rad/s)",
        strategy = SubsystemControlStrategy.VELOCITY_PID,
        feedforwardKind = SubsystemFeedforwardKind.SIMPLE_MOTOR,
        unit = "rad/s",
    ),
    RUN_ROLLER_VOLTAGE(
        id = "run_roller_voltage",
        title = "Run Roller Voltage",
        subtitle = "Intake / indexer roller using bounded open-loop voltage",
        formulaBadge = "Direct Bounded Output (-12..+12 V)",
        strategy = SubsystemControlStrategy.DIRECT,
        feedforwardKind = SubsystemFeedforwardKind.NONE,
        unit = "V",
    ),
}

fun applySubsystemControlRecipe(
    document: SubsystemDocument,
    recipe: SubsystemStudentControlRecipe,
    loopId: String? = null,
): SubsystemDocument {
    val targetLoop = loopId?.let { id -> document.controlLoops.firstOrNull { it.loopId == id } }
        ?: document.controlLoops.firstOrNull()
        ?: return document

    val actuator = document.hardware.firstOrNull { it.hardwareId == targetLoop.actuatorId }
        ?: document.hardware.firstOrNull { it.kind == SubsystemHardwareKind.MOTOR && it.following == null }
        ?: return document

    val desiredMeasurementSource = when (recipe) {
        SubsystemStudentControlRecipe.HOLD_ANGLE_GRAVITY,
        SubsystemStudentControlRecipe.HOLD_HEIGHT_ELEVATOR,
        -> SubsystemMeasurementSource.MOTOR_POSITION_NATIVE
        SubsystemStudentControlRecipe.SPIN_VELOCITY_FLYWHEEL -> SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND
        SubsystemStudentControlRecipe.RUN_ROLLER_VOLTAGE -> null
    }

    val measurementFieldId = desiredMeasurementSource?.let { source ->
        actuator.measurements.firstOrNull { it.source == source }?.fieldId
    } ?: targetLoop.measurementFieldId
        ?: document.stateFields.firstOrNull { it.role == SubsystemFieldRole.MEASUREMENT && it.type == SubsystemValueType.DOUBLE }?.fieldId

    val (minBound, maxBound) = when (recipe) {
        SubsystemStudentControlRecipe.HOLD_ANGLE_GRAVITY -> 0.0 to 2.0
        SubsystemStudentControlRecipe.HOLD_HEIGHT_ELEVATOR -> 0.0 to 1.0
        SubsystemStudentControlRecipe.SPIN_VELOCITY_FLYWHEEL -> 0.0 to 450.0
        SubsystemStudentControlRecipe.RUN_ROLLER_VOLTAGE -> -12.0 to 12.0
    }

    val updatedFields = document.stateFields.map { field ->
        when (field.fieldId) {
            targetLoop.targetFieldId -> field.copy(
                unit = recipe.unit,
                minimum = minBound,
                maximum = maxBound,
                defaultNumber = (field.defaultNumber ?: 0.0).coerceIn(minBound, maxBound),
            )
            measurementFieldId -> field.copy(unit = recipe.unit)
            else -> field
        }
    }

    val updatedLoops = document.controlLoops.map { loop ->
        if (loop.loopId != targetLoop.loopId) return@map loop
        when (recipe) {
            SubsystemStudentControlRecipe.HOLD_ANGLE_GRAVITY -> loop.copy(
                displayName = "Arm Angle Hold (PID + Gravity FF)",
                strategy = SubsystemControlStrategy.PROFILED_POSITION_PID,
                measurementFieldId = measurementFieldId,
                kP = 4.0,
                kI = 0.0,
                kD = 0.25,
                tolerance = 0.03,
                motionProfile = SubsystemMotionProfileDocument(maximumVelocity = 3.0, maximumAcceleration = 6.0),
                feedforward = SubsystemFeedforwardDocument(
                    kind = SubsystemFeedforwardKind.ARM,
                    kS = 0.18,
                    kG = 0.45,
                    kV = 1.2,
                    kA = 0.05,
                    gravityAngleFieldId = measurementFieldId,
                ),
                continuousInput = SubsystemContinuousInputDocument(enabled = false),
                minimumOutput = -12.0,
                maximumOutput = 12.0,
            )
            SubsystemStudentControlRecipe.HOLD_HEIGHT_ELEVATOR -> loop.copy(
                displayName = "Elevator Height Hold (Profiled PID + kG)",
                strategy = SubsystemControlStrategy.PROFILED_POSITION_PID,
                measurementFieldId = measurementFieldId,
                kP = 12.0,
                kI = 0.0,
                kD = 0.5,
                tolerance = 0.015,
                motionProfile = SubsystemMotionProfileDocument(maximumVelocity = 1.2, maximumAcceleration = 2.5),
                feedforward = SubsystemFeedforwardDocument(
                    kind = SubsystemFeedforwardKind.ELEVATOR,
                    kS = 0.20,
                    kG = 0.55,
                    kV = 2.4,
                    kA = 0.08,
                ),
                continuousInput = SubsystemContinuousInputDocument(enabled = false),
                minimumOutput = -12.0,
                maximumOutput = 12.0,
            )
            SubsystemStudentControlRecipe.SPIN_VELOCITY_FLYWHEEL -> loop.copy(
                displayName = "Flywheel Velocity (kS + kV + kP)",
                strategy = SubsystemControlStrategy.VELOCITY_PID,
                measurementFieldId = measurementFieldId,
                kP = 0.08,
                kI = 0.0,
                kD = 0.0,
                tolerance = 5.0,
                feedforward = SubsystemFeedforwardDocument(
                    kind = SubsystemFeedforwardKind.SIMPLE_MOTOR,
                    kS = 0.22,
                    kV = 0.035,
                    kA = 0.004,
                ),
                continuousInput = SubsystemContinuousInputDocument(enabled = false),
                minimumOutput = 0.0,
                maximumOutput = 12.0,
            )
            SubsystemStudentControlRecipe.RUN_ROLLER_VOLTAGE -> loop.copy(
                displayName = "Direct Roller Voltage (-12..+12 V)",
                strategy = SubsystemControlStrategy.DIRECT,
                measurementFieldId = null,
                feedforward = SubsystemFeedforwardDocument(kind = SubsystemFeedforwardKind.NONE),
                continuousInput = SubsystemContinuousInputDocument(enabled = false),
                minimumOutput = -12.0,
                maximumOutput = 12.0,
            )
        }
    }

    return document.copy(
        stateFields = updatedFields,
        controlLoops = updatedLoops,
    )
}

@Composable
fun SubsystemStudentControlRecipeBar(
    document: SubsystemDocument,
    viewModel: SubsystemGeneratorViewModel,
    selectedLoopId: String? = null,
    modifier: Modifier = Modifier,
) {
    if (document.controlLoops.isEmpty()) return
    val activeLoop = selectedLoopId?.let { id -> document.controlLoops.firstOrNull { it.loopId == id } }
        ?: document.controlLoops.first()

    Surface(
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "1-CLICK STUDENT CONTROL LAW RECIPES",
                color = AresCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Not sure whether to pick Position PID, Profiled PID, Velocity PID, or Direct Voltage? Pick what your mechanism physically does to auto-configure strategy, feedforward, and units:",
                color = AresTextSecondary,
                fontSize = 10.sp,
            )
            SubsystemStudentControlRecipe.entries.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { recipe ->
                        val isSelected = activeLoop.strategy == recipe.strategy &&
                            activeLoop.feedforward.kind == recipe.feedforwardKind
                        Surface(
                            color = if (isSelected) AresCyan.copy(alpha = 0.14f) else AresSurface,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, if (isSelected) AresCyan else AresBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    viewModel.edit { doc ->
                                        applySubsystemControlRecipe(doc, recipe, activeLoop.loopId)
                                    }
                                },
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        recipe.title,
                                        color = if (isSelected) AresCyan else AresTextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                    )
                                    Text(
                                        if (isSelected) "ACTIVE" else "APPLY",
                                        color = if (isSelected) AresGreen else AresCyan,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(recipe.subtitle, color = AresTextSecondary, fontSize = 9.sp)
                                Text(
                                    recipe.formulaBadge,
                                    color = AresGold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
