package com.ares.analytics.ui.components.subsystems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.subsystemTemplateOptions
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.isAresGenerated
import com.areslib.subsystem.SubsystemTemplate

@Composable
fun SubsystemPurposeSection(
    state: SubsystemGeneratorState,
    viewModel: SubsystemGeneratorViewModel,
    modifier: Modifier = Modifier,
) {
    val document = state.draft?.document ?: return
    var pendingTemplate by remember(document.uid) { mutableStateOf<SubsystemTemplate?>(null) }
    var showAdvancedNaming by remember(document.uid) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Purpose & Naming
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AresSurface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SUBSYSTEM IDENTITY & PURPOSE", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = document.displayName,
                    onValueChange = { name ->
                        val previousDerivedName = subsystemKotlinTypeName(document.displayName)
                        val typeName = if (
                            document.kotlinTypeName.isBlank() || document.kotlinTypeName == previousDerivedName
                        ) {
                            subsystemKotlinTypeName(name)
                        } else {
                            document.kotlinTypeName
                        }
                        viewModel.edit { it.copy(displayName = name, kotlinTypeName = typeName) }
                    },
                    label = { Text("Mechanism name (e.g. Intake)") },
                    supportingText = { Text("This is the name students will see throughout ARES.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                TextButton(
                    onClick = { showAdvancedNaming = !showAdvancedNaming },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(if (showAdvancedNaming) "Hide advanced code naming" else "Advanced code naming", fontSize = 11.sp)
                }

                if (showAdvancedNaming) {
                    OutlinedTextField(
                        value = document.kotlinTypeName,
                        onValueChange = { typeName -> viewModel.edit { it.copy(kotlinTypeName = typeName) } },
                        label = { Text("Kotlin type name (e.g. IntakeSubsystem)") },
                        supportingText = {
                            Text("ARES manages this automatically. Change it only when matching existing source code.")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                OutlinedTextField(
                    value = document.description,
                    onValueChange = { desc -> viewModel.edit { it.copy(description = desc) } },
                    label = { Text("Description & Role") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }

        // Archetype / Template Picker
        if (document.implementation.kind.isAresGenerated()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AresSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("MECHANISM ARCHETYPE / STARTER TEMPLATE", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("Selecting a starter archetype populates common sensors, targets, and control loops.", color = AresTextTertiary, fontSize = 10.sp)
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        subsystemTemplateOptions.chunked(3).forEach { rowOptions ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowOptions.forEach { tplOption ->
                                    val isSelected = document.template == tplOption.template
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) AresCyan else AresBorder,
                                                shape = RoundedCornerShape(8.dp),
                                            )
                                            .clickable(enabled = !isSelected) { pendingTemplate = tplOption.template },
                                        color = if (isSelected) AresCyan.copy(alpha = 0.08f) else AresSurfaceElevated,
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(tplOption.label, color = if (isSelected) AresCyan else AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                if (isSelected) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = AresCyan.copy(alpha = 0.2f),
                                                    ) {
                                                        Text("Active", color = AresCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                                    }
                                                }
                                            }
                                            Text(tplOption.description, color = AresTextSecondary, fontSize = 10.sp, maxLines = 2)
                                        }
                                    }
                                }
                                // Fill remaining space in last row if not full
                                repeat(3 - rowOptions.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AresSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("EXISTING USER-OWNED IMPLEMENTATION", color = AresGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "This definition documents Kotlin source your team already owns. ARES validates its classes, simulation support, and actions without overwriting your custom files.",
                                color = AresTextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                pendingTemplate = SubsystemTemplate.SIMPLE_ACTUATOR
                            },
                        ) {
                            Text("Reset to Blank Starter", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Redux Runtime Architecture Reminder Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AresSurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Memory, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Redux & Hardware IO Contract", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(
                        "Hardware IO (sensors cached once/loop) → Reducer (pure state transforms) → Subsystem Controller (pure math outputs) → IO writeOutputs.",
                        color = AresTextSecondary,
                        fontSize = 10.sp,
        )
    }

    pendingTemplate?.let { template ->
        val option = subsystemTemplateOptions.firstOrNull { it.template == template }
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            title = { Text(if (document.implementation.kind == SubsystemImplementationKind.HAND_AUTHORED) "Replace registration with generated starter?" else "Replace this subsystem draft?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(option?.label ?: template.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase))
                    Text(option?.description.orEmpty(), color = AresTextSecondary)
                    Text(
                        "This replaces the draft hardware, state values, controllers, tuning, and safety settings. It does not write files yet, and Undo can restore the current draft.",
                        color = AresGold,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.applyTemplate(template)
                    pendingTemplate = null
                }) { Text("Replace draft") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTemplate = null }) { Text("Keep current draft") }
            },
        )
    }
}

        }
    }
}

internal fun subsystemKotlinTypeName(displayName: String): String {
    val identifier = displayName
        .trim()
        .split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotBlank)
        .joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }
        .ifBlank { "NewSubsystem" }
    return if (identifier.first().isDigit()) "Mechanism$identifier" else identifier
}
