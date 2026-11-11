package com.ares.analytics.ui.components.subsystems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.ares.analytics.ui.components.core.AresPresetChipBar
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.ui.util.copyTextToSystemClipboard
import com.ares.analytics.viewmodel.SubsystemBuilderStage
import com.ares.analytics.viewmodel.SubsystemDiffLineKind
import com.ares.analytics.viewmodel.SubsystemPreviewFile
import com.ares.analytics.viewmodel.SubsystemRemovalRequest
import com.ares.analytics.viewmodel.subsystem.SubsystemCookbookPreset
import com.ares.analytics.viewmodel.subsystem.availableCookbookPresets
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform

/**
 * Controls progressive disclosure in the Subsystem Builder so students start with
 * clear, plain-English essentials while mentors and advanced students can toggle
 * into full engineering detail in 1 click.
 */
enum class SubsystemAuthoringComplexityMode(
    val label: String,
    val badge: String,
) {
    STUDENT_ESSENTIALS(label = "Student Essentials", badge = "DEFAULT"),
    FULL_ENGINEERING(label = "Full Engineering", badge = "ADVANCED"),
}

data class GuiToCodeBridgeSummary(
    val descriptorFilePath: String,
    val generatedReadOnlyFolder: String,
    val safeTeamExtensionPath: String,
    val exampleActionExpression: String,
    val exampleExtensionSnippet: String,
)

/**
 * Returns a plain-English summary of the active Subsystem Builder stage tailored
 * to the selected complexity mode.
 */
fun stagePlainEnglishSummary(
    stage: SubsystemBuilderStage,
    mode: SubsystemAuthoringComplexityMode,
): String = when (stage) {
    SubsystemBuilderStage.PURPOSE -> when (mode) {
        SubsystemAuthoringComplexityMode.STUDENT_ESSENTIALS ->
            "Step 1 (Identity & Template): Give your mechanism a clear name (like 'Intake' or 'Arm') and pick a starter template that matches how it moves."
        SubsystemAuthoringComplexityMode.FULL_ENGINEERING ->
            "Step 1 (Identity, Archetype & Ownership): Configure canonical subsystem IDs, Kotlin type names, starter archetype defaults, and GENERATED vs. USER-OWNED source contracts."
    }

    SubsystemBuilderStage.HARDWARE -> when (mode) {
        SubsystemAuthoringComplexityMode.STUDENT_ESSENTIALS ->
            "Step 2 (Motors & Sensors): Add each physical motor, servo, or sensor on this mechanism and choose which port or CAN ID it plugs into."
        SubsystemAuthoringComplexityMode.FULL_ENGINEERING ->
            "Step 2 (Hardware Topology & IO Adapters): Declare actuators and feedback sensors, bus/port bindings, gearing/unit scales, and cached hardware read contracts."
    }

    SubsystemBuilderStage.STATE_AND_BEHAVIOR, SubsystemBuilderStage.SAFETY -> when (mode) {
        SubsystemAuthoringComplexityMode.STUDENT_ESSENTIALS ->
            "Step 3 (Goals & Control): Define what target value your robot commands (like 'Target Roller Voltage' or 'Target Arm Angle') and how the motor reaches that goal."
        SubsystemAuthoringComplexityMode.FULL_ENGINEERING ->
            "Step 3 (Immutable Redux State, Control Laws & Safety Interlocks): Configure measured/target state fields, PID/profiled/open-loop controllers, soft limits, and fail-closed recovery rules."
    }

    SubsystemBuilderStage.TUNING,
    SubsystemBuilderStage.CAPABILITIES,
    SubsystemBuilderStage.SIMULATION_AND_TESTING,
    SubsystemBuilderStage.REVIEW -> when (mode) {
        SubsystemAuthoringComplexityMode.STUDENT_ESSENTIALS ->
            "Step 4 (Check & Create Code): Review any highlighted warnings, click 'Save & create files', and use the GUI ↔ Code Bridge below to see where to write custom team logic."
        SubsystemAuthoringComplexityMode.FULL_ENGINEERING ->
            "Step 4 (Live Tuning, Commissioning, Fault Injection & Artifact Review): Inspect live tunable parameters, run interactive safety fault scenarios, and verify generated artifact ownership."
    }
}

/**
 * Builds a concrete explanation of how the visual `.aressubsystem` GUI connects
 * to generated read-only plumbing vs. safe-to-edit student code files.
 */
fun buildGuiToCodeBridgeSummary(document: SubsystemDocument): GuiToCodeBridgeSummary {
    val cleanId = document.documentId.ifBlank { "intake" }
    val typeName = document.kotlinTypeName.ifBlank {
        subsystemKotlinTypeName(document.displayName.ifBlank { "Intake" })
    }
    val primaryTarget = document.stateFields.firstOrNull { it.role == SubsystemFieldRole.TARGET }
    val targetFieldId = primaryTarget?.fieldId ?: "targetVoltage"
    val targetLabel = primaryTarget?.displayName ?: "Target Voltage"

    return if (document.platform == SubsystemPlatform.XRP) {
        GuiToCodeBridgeSummary(
            descriptorFilePath = ".ares/subsystems/$cleanId.aressubsystem",
            generatedReadOnlyFolder = "build/generated/ares/python/ (regenerated automatically — do not hand-edit)",
            safeTeamExtensionPath = "src/subsystems/${cleanId}_extensions.py (safe for your team's custom Python helper functions)",
            exampleActionExpression = "subsystem.$cleanId.set.$targetFieldId",
            exampleExtensionSnippet = buildString {
                appendLine("# Safe Student Extension File: src/subsystems/${cleanId}_extensions.py")
                appendLine("# ARES never overwrites this file when you click 'Save & create files' in the GUI.")
                appendLine("def run_${cleanId}_preset(robot_state):")
                appendLine("    \"\"\"Example student helper for ${document.displayName.ifBlank { "Mechanism" }} ($targetLabel).\"\"\"")
                appendLine("    return {\"action\": \"subsystem.$cleanId.set.$targetFieldId\", \"value\": 0.75}")
            }.trimEnd(),
        )
    } else {
        GuiToCodeBridgeSummary(
            descriptorFilePath = ".ares/subsystems/$cleanId.aressubsystem",
            generatedReadOnlyFolder = "build/generated/ares/ (State, Action, Controller & Registry — regenerated automatically)",
            safeTeamExtensionPath = "src/main/kotlin/.../subsystems/${typeName}Extensions.kt (safe for your team's custom Kotlin helpers)",
            exampleActionExpression = "subsystem.$cleanId.set.$targetFieldId",
            exampleExtensionSnippet = buildString {
                appendLine("// Safe Student Extension File: ${typeName}Extensions.kt")
                appendLine("// ARES never overwrites this file when you click 'Save & create files' in the GUI.")
                appendLine("// Generated read-only state/actions live in build/generated/ares/.")
                appendLine("fun ${typeName}State.isReadyForScoring(): Boolean {")
                appendLine("    // Read immutable state values cached once per control loop:")
                appendLine("    return enabled && !faulted")
                appendLine("}")
            }.trimEnd(),
        )
    }
}

@Composable
fun SubsystemAuthoringModeAndCodeBridgeBanner(
    document: SubsystemDocument,
    activeStage: SubsystemBuilderStage,
    mode: SubsystemAuthoringComplexityMode,
    onModeChange: (SubsystemAuthoringComplexityMode) -> Unit,
    onApplyCookbookPreset: ((SubsystemCookbookPreset) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showCodeBridge by remember(document.uid) { mutableStateOf(false) }
    val bridge = remember(document) { buildGuiToCodeBridgeSummary(document) }
    val summaryText = stagePlainEnglishSummary(activeStage, mode)
    val cookbookPresets = remember(document.platform) { availableCookbookPresets(document.platform) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (mode == SubsystemAuthoringComplexityMode.STUDENT_ESSENTIALS) {
                        Icons.Default.School
                    } else {
                        Icons.Default.Engineering
                    },
                    contentDescription = null,
                    tint = AresCyan,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = summaryText,
                        color = AresTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (mode == SubsystemAuthoringComplexityMode.STUDENT_ESSENTIALS) {
                            "Student Essentials Mode keeps the builder focused on the core steps. Switch to Full Engineering anytime for commissioning & fault-lab tools."
                        } else {
                            "Full Engineering Mode shows all commissioning checklists, fault-injection previews, and advanced code contracts."
                        },
                        color = AresTextSecondary,
                        fontSize = 10.sp,
                    )
                }

                SubsystemAuthoringComplexityMode.entries.forEach { option ->
                    val selected = mode == option
                    FilterChip(
                        selected = selected,
                        onClick = { onModeChange(option) },
                        modifier = Modifier.height(30.dp),
                        label = {
                            Text(
                                text = option.label,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AresCyan,
                            selectedLabelColor = AresOnAccent,
                            containerColor = AresSurfaceElevated,
                            labelColor = AresTextPrimary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (selected) AresCyan else AresBorder,
                            selectedBorderColor = AresCyan,
                            enabled = true,
                            selected = selected,
                        ),
                    )
                }

                OutlinedButton(
                    onClick = { showCodeBridge = !showCodeBridge },
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, if (showCodeBridge) AresGreen else AresBorder),
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = if (showCodeBridge) AresGreen else AresCyan,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (showCodeBridge) "Hide GUI ↔ Code Bridge" else "GUI ↔ Code Bridge",
                        fontSize = 10.sp,
                        color = AresTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (onApplyCookbookPreset != null && activeStage == SubsystemBuilderStage.PURPOSE && cookbookPresets.isNotEmpty()) {
                Text(
                    text = "1-Click Student Cookbook Presets:",
                    color = AresTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                AresPresetChipBar(
                    items = cookbookPresets,
                    labelFor = { "${it.title} · ${it.subtitle}" },
                    onSelect = onApplyCookbookPreset,
                    accentColorFor = { AresGreen },
                )
            }

            if (showCodeBridge) {
                GuiToCodeBridgeCardContent(bridge = bridge)
            }
        }
    }
}

@Composable
internal fun GuiToCodeBridgeCardContent(
    bridge: GuiToCodeBridgeSummary,
    modifier: Modifier = Modifier,
) {
    var copied by remember(bridge.exampleExtensionSnippet) { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "HOW THIS VISUAL BUILDER CONNECTS TO YOUR CODE",
                color = AresCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("1. Visual Descriptor (Saved by GUI)", color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(bridge.descriptorFilePath, color = AresCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("Stores your motors, sensors, targets, and safety limits.", color = AresTextSecondary, fontSize = 10.sp)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("2. Auto-Generated Plumbing (Read-Only)", color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(bridge.generatedReadOnlyFolder, color = AresTextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("Recreated every time you click 'Save & create files'. Never edit files inside build/generated/.", color = AresTextSecondary, fontSize = 10.sp)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("3. Your Team's Custom Code (Safe to Edit!)", color = AresGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(bridge.safeTeamExtensionPath, color = AresGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("Write custom helper functions here—ARES will never overwrite your team's extension files.", color = AresTextSecondary, fontSize = 10.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Example Student Extension Snippet (Action ID: ${bridge.exampleActionExpression}):",
                    color = AresTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = {
                        copied = copyTextToSystemClipboard(bridge.exampleExtensionSnippet)
                    },
                    modifier = Modifier.height(26.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, if (copied) AresGreen else AresCyan.copy(alpha = 0.5f)),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = if (copied) AresGreen else AresCyan,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (copied) "Copied Safe Extension Code!" else "Copy Safe Extension Code",
                        color = if (copied) AresGreen else AresTextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = bridge.exampleExtensionSnippet,
                color = AresTextPrimary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AresBackground, RoundedCornerShape(6.dp))
                    .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                    .padding(8.dp),
            )
        }
    }
}

@Composable
internal fun SubsystemConfirmationDialogs(
    pendingRemoval: SubsystemRemovalRequest?,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    pendingStarterReplacements: List<SubsystemPreviewFile>,
    onConfirmStarterReplacement: () -> Unit,
    onCancelStarterReplacement: () -> Unit,
) {
    pendingRemoval?.let { request ->
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = {
                Text(
                    if (request.persisted) "Remove ${request.displayName} from this project?" else "Discard this unsaved draft?",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (request.persisted) {
                        Text(
                            "ARES will remove only the reviewed canonical descriptor ${request.canonicalPath}. A recoverable copy will be kept at ${request.recoveryPath}.",
                            color = AresTextSecondary,
                            fontSize = 12.sp,
                        )
                        Text(
                            "Generated registry plumbing will be refreshed. Kotlin starter and USER-OWNED source files are never deleted by this action.",
                            color = AresTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Text(
                            "This subsystem has not been saved to the project. Discarding it changes no files.",
                            color = AresTextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    if (request.sourceFilesPreserved.isNotEmpty()) {
                        Text("Source preserved:", color = AresTextSecondary, fontSize = 11.sp)
                        request.sourceFilesPreserved.forEach { path ->
                            Text("• $path", color = AresTextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (request.discardsUnsavedChanges) {
                        Text(
                            "Unsaved edits in the open draft will also be discarded.",
                            color = AresGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmRemove,
                    colors = ButtonDefaults.buttonColors(containerColor = AresError, contentColor = AresOnAccent),
                ) {
                    Text(if (request.persisted) "Remove & keep recovery copy" else "Discard draft", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = onCancelRemove) {
                    Text("Keep subsystem")
                }
            },
            containerColor = AresSurfaceElevated,
        )
    }

    if (pendingStarterReplacements.isNotEmpty()) {
        SubsystemStarterReplacementReviewDialog(
            pendingStarterReplacements = pendingStarterReplacements,
            onConfirmStarterReplacement = onConfirmStarterReplacement,
            onCancelStarterReplacement = onCancelStarterReplacement,
        )
    }
}

@Composable
internal fun SubsystemStarterReplacementReviewDialog(
    pendingStarterReplacements: List<SubsystemPreviewFile>,
    onConfirmStarterReplacement: () -> Unit,
    onCancelStarterReplacement: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancelStarterReplacement,
        title = {
            Text("Review generated starter replacements", color = AresTextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "These files are marked GENERATED STARTER, but their contents differ from the new proposal. ARES will replace them only after this review. USER-OWNED files can never be replaced here.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                )
                pendingStarterReplacements.forEach { file ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            file.projectRelativePath,
                            color = AresCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                        Surface(
                            color = AresBackground,
                            border = BorderStroke(1.dp, AresBorder),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                file.diff.forEach { line ->
                                    val color = when (line.kind) {
                                        SubsystemDiffLineKind.ADDED -> AresGreen
                                        SubsystemDiffLineKind.REMOVED -> AresRed
                                        SubsystemDiffLineKind.CONTEXT -> AresTextSecondary
                                    }
                                    val prefix = when (line.kind) {
                                        SubsystemDiffLineKind.ADDED -> "+ "
                                        SubsystemDiffLineKind.REMOVED -> "− "
                                        SubsystemDiffLineKind.CONTEXT -> "  "
                                    }
                                    Text(
                                        prefix + line.text,
                                        color = color,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "Confirmation is bound to this exact proposal. If any file changes before apply, ARES will stop and require another review.",
                    color = AresGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmStarterReplacement,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Text("Replace reviewed starters", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancelStarterReplacement) {
                Text("Keep existing files")
            }
        },
        containerColor = AresSurfaceElevated,
    )
}
