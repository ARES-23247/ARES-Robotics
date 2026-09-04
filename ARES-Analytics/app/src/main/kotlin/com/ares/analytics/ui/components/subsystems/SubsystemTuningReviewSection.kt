package com.ares.analytics.ui.components.subsystems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.SubsystemBuilderStage
import com.ares.analytics.viewmodel.SubsystemProblemSeverity
import com.areslib.codegen.SubsystemArtifactGroup
import com.areslib.codegen.SubsystemArtifactOwnership
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue

@Composable
fun SubsystemTuningReviewSection(
    state: SubsystemGeneratorState,
    viewModel: SubsystemGeneratorViewModel,
    modifier: Modifier = Modifier,
) {
    val document = state.draft?.document ?: return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.activeStage) {
            SubsystemBuilderStage.TUNING -> TuningParametersCard(document, state.selectedTuningParameterUid, viewModel)
            SubsystemBuilderStage.CAPABILITIES -> CapabilityInspectorCard(document)
            SubsystemBuilderStage.SIMULATION_AND_TESTING -> VerificationInspectorCard(document, viewModel)
            SubsystemBuilderStage.REVIEW -> {
                TuningParametersCard(document, state.selectedTuningParameterUid, viewModel)
                ProblemsCard(state, viewModel)
                ReviewSummaryCard(document, state)
                ArtifactPlanCard(state, viewModel)
            }
            else -> Unit
        }
    }
}

@Composable
private fun TuningParametersCard(
    document: SubsystemDocument,
    selectedTuningParameterUid: String?,
    viewModel: SubsystemGeneratorViewModel,
) {
        EditorCard("Live Tunable Parameters (${document.tuningParameters.size})", Icons.Default.Tune) {
            Text(
                "Declare typed parameters that students can adjust live via named robot profiles (.arestuning).",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            document.tuningParameters.forEach { param ->
                SelectableRow(
                    title = param.displayName,
                    subtitle = "${param.type.name.lowercase()} · ${param.applyPolicy.name.replace('_', ' ').lowercase()} · ${param.componentUid}",
                    selected = selectedTuningParameterUid == param.uid,
                    onClick = { viewModel.selectTuningParameter(param.uid) },
                )
            }
            OutlinedButton(onClick = viewModel::addTuningParameter, modifier = Modifier.fillMaxWidth()) {
                Text("+ Add Tunable Parameter", fontSize = 11.sp)
            }
        }
}

@Composable
private fun CapabilityInspectorCard(document: SubsystemDocument) {
    val targets = document.stateFields.filter { it.role == SubsystemFieldRole.TARGET }
    EditorCard("Subsystem Actions & Autonomous Routines", Icons.Default.Build) {
        Text(
            "Each writable target becomes a typed action available in Controller Bindings and Autonomous Routines.",
            color = AresTextSecondary,
            fontSize = 11.sp,
        )
        if (targets.isEmpty()) {
            Text("No writable target values defined yet.", color = AresGold, fontSize = 11.sp)
        } else {
            targets.forEach { field ->
                Surface(
                    color = AresSurface,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, AresBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Set ${document.displayName} ${field.displayName}", color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("action: subsystem.${document.documentId}.set.${field.fieldId}", color = AresCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationInspectorCard(document: SubsystemDocument, viewModel: SubsystemGeneratorViewModel) {
    EditorCard("Simulation & Unit Verification", Icons.Default.Code) {
        ToggleRow("Generate desktop / mock IO adapter", document.generateMockIo) { value ->
            viewModel.edit { it.copy(generateMockIo = value) }
        }
        ToggleRow("Generate contract unit test", document.generateTest) { value ->
            viewModel.edit { it.copy(generateTest = value) }
        }
        Text(
            "Generated contract verification covers startup, stop, stale feedback, write failures, homing, current validity, parity, cleanup, and the declared allocation policy where applicable.",
            color = AresTextSecondary,
            fontSize = 10.sp,
        )
    }
    SubsystemFaultInjectionLab(document)
}

@Composable
private fun SubsystemFaultInjectionLab(document: SubsystemDocument) {
    var scenario by remember(document.documentId) { mutableStateOf(SubsystemFaultScenario.HEALTHY) }
    val result = evaluateSubsystemFaultScenario(document, scenario)
    EditorCard("Interactive Safety Preview", Icons.Default.Warning) {
        Text(
            "Choose a condition to see how the generated controller and IO safety boundary should respond. This preview never connects to or commands hardware.",
            color = AresTextSecondary,
            fontSize = 11.sp,
        )
        DropdownSelector(
            label = "Injected condition",
            selected = scenario.label,
            options = SubsystemFaultScenario.entries.map { it.label },
        ) { label -> scenario = SubsystemFaultScenario.entries.first { it.label == label } }
        Text(scenario.explanation, color = AresTextSecondary, fontSize = 10.sp)
        Surface(
            color = if (result.outputPermitted) AresGreen.copy(alpha = 0.12f) else AresError.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, if (result.outputPermitted) AresGreen else AresError),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    if (result.outputPermitted) "OUTPUT PERMITTED" else "SAFE NEUTRAL REQUIRED",
                    color = if (result.outputPermitted) AresGreen else AresError,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(result.status, color = AresTextPrimary, fontSize = 11.sp)
                Text("Recovery: ${result.recovery}", color = AresTextSecondary, fontSize = 10.sp)
            }
        }
        Text(
            "Evidence boundary: this explains the generated contract. Passing desktop tests is simulation evidence, not physical hardware validation.",
            color = AresGold,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ReviewSummaryCard(document: SubsystemDocument, state: SubsystemGeneratorState) {
    val errors = state.problems.count { it.severity == SubsystemProblemSeverity.ERROR }
    EditorCard("Ready-to-build Summary", Icons.Default.Build) {
        Text(
            if (errors == 0) "Descriptor validation passed. Review the generated file ownership and destination plan before saving."
            else "$errors blocking validation ${if (errors == 1) "error remains" else "errors remain"}; select a validation card to fix it.",
            color = if (errors == 0) AresGreen else AresError,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${document.hardware.size} devices · ${document.stateFields.size} immutable values · ${document.controlLoops.size} controller rules · ${document.tuningParameters.size} tunable parameters",
            color = AresTextSecondary,
            fontSize = 10.sp,
        )
        Text(
            "Runtime flow: cached hardware inputs → immutable Redux state → generated controller → IO contract → physical or simulated adapter",
            color = AresTextSecondary,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ProblemsCard(state: SubsystemGeneratorState, viewModel: SubsystemGeneratorViewModel) {
    if (state.problems.isEmpty()) return
    EditorCard("Validation Checks (${state.problems.size})", Icons.Default.Warning) {
        state.problems.forEach { problem ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.navigateToProblem(problem.path) },
                color = if (problem.severity == SubsystemProblemSeverity.ERROR) AresError.copy(alpha = 0.12f) else AresGold.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, if (problem.severity == SubsystemProblemSeverity.ERROR) AresError else AresGold),
                shape = RoundedCornerShape(6.dp),
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(problem.path, color = AresCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        problem.message,
                        color = if (problem.severity == SubsystemProblemSeverity.ERROR) AresError else AresGold,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactPlanCard(state: SubsystemGeneratorState, viewModel: SubsystemGeneratorViewModel) {
    val document = state.draft?.document ?: return
    if (state.previewFiles.isEmpty()) {
        if (document.platform == SubsystemPlatform.XRP) {
            EditorCard("Generated MicroPython", Icons.Default.Code) {
                Text(
                    "ARES will compile this descriptor with the project's native Python generator into " +
                        "build/generated/ares/python and regenerate its safety tests. Verify & build shows the exact result.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        return
    }

    EditorCard("Generated Code Artifacts (${state.previewFiles.size})", Icons.Default.Code) {
        val starterCount = state.previewFiles.count { it.ownership == SubsystemArtifactOwnership.GENERATED_STARTER }
        val plumbingCount = state.previewFiles.count { it.ownership == SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT }
        Text(
            "$starterCount customization starters · $plumbingCount generated plumbing files",
            color = AresTextSecondary,
            fontSize = 11.sp,
        )
        SubsystemArtifactGroup.entries.forEach { group ->
            val files = state.previewFiles.filter { it.group == group }
            if (files.isEmpty()) return@forEach
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.name.replace('_', ' '), color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                files.forEach { file ->
                    Surface(
                        color = AresSurface,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, AresBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(file.path.substringAfterLast('/'), color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(file.ownership.name.replace('_', ' ').lowercase(), color = AresTextTertiary, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TuningParameterInspectorBody(
    declaration: TuningParameterDeclaration,
    viewModel: SubsystemGeneratorViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Configure live tunable parameter name, type, and bounds.", color = AresTextSecondary, fontSize = 11.sp)
        TextInput("Parameter name", declaration.displayName) { value ->
            viewModel.updateTuningParameter(declaration.uid) { it.copy(displayName = value) }
        }
        TextInput("What changing this value does", declaration.description) { value ->
            viewModel.updateTuningParameter(declaration.uid) { it.copy(description = value) }
        }
        StableIdLabel("Parameter key", declaration.key, "Stable key used in .arestuning profile documents and robot callbacks.")
        TextInput("Rename parameter key (advanced)", declaration.key) { value ->
            viewModel.updateTuningParameter(declaration.uid) { it.copy(key = value) }
        }
        TextInput("Component UID", declaration.componentUid) { value ->
            viewModel.updateTuningParameter(declaration.uid) { it.copy(componentUid = value) }
        }
        EnumSelector("Parameter Type", declaration.type, TuningParameterType.entries) { type ->
            viewModel.changeTuningParameterType(declaration.uid, type)
        }
        if (declaration.type in setOf(TuningParameterType.DOUBLE, TuningParameterType.INT)) {
            TextInput("Physical unit (optional)", declaration.unit.orEmpty()) { value ->
                viewModel.updateTuningParameter(declaration.uid) { it.copy(unit = value.ifBlank { null }) }
            }
            NullableDoubleInput("Minimum allowed value", declaration.minimum) { value ->
                viewModel.updateTuningParameter(declaration.uid) { it.copy(minimum = value) }
            }
            NullableDoubleInput("Maximum allowed value", declaration.maximum) { value ->
                viewModel.updateTuningParameter(declaration.uid) { it.copy(maximum = value) }
            }
        }
        when (declaration.type) {
            TuningParameterType.DOUBLE -> DoubleInput("Default value", declaration.defaultValue.doubleValue ?: 0.0) { value ->
                viewModel.updateTuningParameter(declaration.uid) { it.copy(defaultValue = TuningValue(doubleValue = value)) }
            }
            TuningParameterType.INT -> IntInput("Default value", declaration.defaultValue.intValue ?: 0) { value ->
                viewModel.updateTuningParameter(declaration.uid) { it.copy(defaultValue = TuningValue(intValue = value)) }
            }
            TuningParameterType.BOOLEAN -> ToggleRow("Default value", declaration.defaultValue.booleanValue ?: false) { value ->
                viewModel.updateTuningParameter(declaration.uid) { it.copy(defaultValue = TuningValue(booleanValue = value)) }
            }
            TuningParameterType.TEXT -> TextInput("Default value", declaration.defaultValue.textValue.orEmpty()) { value ->
                viewModel.updateTuningParameter(declaration.uid) { it.copy(defaultValue = TuningValue(textValue = value)) }
            }
            TuningParameterType.ENUM -> {
                TextInput("Allowed choices (comma-separated)", declaration.enumOptions.joinToString(", ")) { raw ->
                    val options = com.ares.analytics.viewmodel.SubsystemTuningAuthoring.parseEnumOptions(raw)
                    viewModel.updateTuningParameter(declaration.uid) { current ->
                        current.copy(
                            enumOptions = options,
                            defaultValue = TuningValue(textValue = current.defaultValue.textValue?.takeIf { it in options } ?: options.firstOrNull().orEmpty()),
                        )
                    }
                }
                if (declaration.enumOptions.isNotEmpty()) {
                    DropdownSelector("Default choice", declaration.defaultValue.textValue ?: declaration.enumOptions.first(), declaration.enumOptions) { selected ->
                        viewModel.updateTuningParameter(declaration.uid) { it.copy(defaultValue = TuningValue(textValue = selected)) }
                    }
                }
            }
        }
        EnumSelector("Apply Policy", declaration.applyPolicy, TuningApplyPolicy.entries) { policy ->
            viewModel.updateTuningParameter(declaration.uid) { it.copy(applyPolicy = policy) }
        }
        FieldGuidance("LIVE_SAFE values may change while enabled only when the robot callback explicitly accepts them. DISABLED_ONLY is the safer default; REBUILD_REQUIRED changes generated or startup configuration.")
    }
}
