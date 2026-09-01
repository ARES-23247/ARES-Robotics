package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IntegrationInstructions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.BuildConfig
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.core.openExternalLink
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresCyanGlow
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

@Composable
internal fun ThirdPartyIntegrationsSection(
    league: League,
    eventCode: String,
    onEventCodeChange: (String) -> Unit,
    toaApiKey: String,
    onToaApiKeyChange: (String) -> Unit,
    tbaApiKey: String,
    onTbaApiKeyChange: (String) -> Unit,
) {
    ProfileSettingsCard {
        ProfileSectionHeader(
            icon = Icons.Default.IntegrationInstructions,
            title = "Third-Party API Integrations",
        )
        Text(
            "Match metadata and schedules can be synced from FRC or FTC event aggregators.",
            color = AresTextSecondary,
            fontSize = 11.sp,
        )
        ProfileTextField(
            value = eventCode,
            onValueChange = onEventCodeChange,
            label = "Event Code / ID (e.g. USNYTUT)",
        )
        if (league == League.FTC) {
            ProfileTextField(
                value = toaApiKey,
                onValueChange = onToaApiKeyChange,
                label = "The Orange Alliance (TOA) API Key",
            )
        } else {
            ProfileTextField(
                value = tbaApiKey,
                onValueChange = onTbaApiKeyChange,
                label = "The Blue Alliance (TBA) API Key",
            )
        }
    }
}

@Composable
internal fun GeminiAssistanceSection(
    aiMode: String,
    onAiModeChange: (String) -> Unit,
    geminiModel: String,
    onGeminiModelChange: (String) -> Unit,
    geminiApiKey: String,
    onGeminiApiKeyChange: (String) -> Unit,
    vertexServiceAccountPath: String,
    onVertexServiceAccountPathChange: (String) -> Unit,
    vertexProjectId: String,
    onVertexProjectIdChange: (String) -> Unit,
    vertexLocation: String,
    onVertexLocationChange: (String) -> Unit,
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }

    ProfileSettingsCard {
        ProfileSectionHeader(icon = Icons.Default.Psychology, title = "Gemini assistance")
        Text(
            "Choose the provider used by telemetry diagnostics and the review-only assistants in Subsystem Builder, Drivebase Builder, and Controller Bindings.",
            color = AresTextSecondary,
            fontSize = 11.sp,
        )
        if (aiMode == "STUDIO" && geminiApiKey.isBlank()) {
            Text(
                "Add a Google AI Studio API key, then save this profile before using an editor assistant.",
                color = AresGold,
                fontSize = 11.sp,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            AiProviderOption(
                label = "Google AI Studio (API Key)",
                selected = aiMode == "STUDIO",
                onClick = { onAiModeChange("STUDIO") },
            )
            AiProviderOption(
                label = "GCP Vertex AI (Service Account)",
                selected = aiMode == "VERTEX",
                onClick = { onAiModeChange("VERTEX") },
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = geminiModel,
                onValueChange = {},
                readOnly = true,
                label = { Text("AI Model Selection") },
                modifier = Modifier.fillMaxWidth().clickable { modelMenuExpanded = !modelMenuExpanded },
                trailingIcon = {
                    IconButton(onClick = { modelMenuExpanded = !modelMenuExpanded }) {
                        Icon(
                            imageVector = if (modelMenuExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = AresTextSecondary,
                        )
                    }
                },
                colors = profileTextFieldColors(),
            )
            DropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
            ) {
                listOf("gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite").forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model, color = AresTextPrimary) },
                        onClick = {
                            onGeminiModelChange(model)
                            modelMenuExpanded = false
                        },
                    )
                }
            }
        }

        if (aiMode == "STUDIO") {
            ProfileTextField(
                value = geminiApiKey,
                onValueChange = onGeminiApiKeyChange,
                label = "Gemini API Key",
                visualTransformation = PasswordVisualTransformation(),
            )
        } else {
            ProfileTextField(
                value = vertexServiceAccountPath,
                onValueChange = onVertexServiceAccountPathChange,
                label = "GCP Service Account JSON Key File Path",
            )
            ProfileTextField(
                value = vertexProjectId,
                onValueChange = onVertexProjectIdChange,
                label = "GCP Project ID",
            )
            ProfileTextField(
                value = vertexLocation,
                onValueChange = onVertexLocationChange,
                label = "GCP Location Region",
            )
        }
    }
}

@Composable
internal fun AccessibilityOptionsSection(
    colorblindMode: Boolean,
    onColorblindModeChange: (Boolean) -> Unit,
    highContrastMode: Boolean,
    onHighContrastModeChange: (Boolean) -> Unit,
    touchOptimizedMode: Boolean,
    onTouchOptimizedModeChange: (Boolean) -> Unit,
    largeTextMode: Boolean,
    onLargeTextModeChange: (Boolean) -> Unit,
    developerMode: Boolean,
    onDeveloperModeChange: (Boolean) -> Unit,
) {
    ProfileSettingsCard {
        ProfileSectionHeader(icon = Icons.Default.Settings, title = "Accessibility & Usability Options")
        Text(
            "Optimize the mission control interface for different environments and readability requirements.",
            color = AresTextSecondary,
            fontSize = 11.sp,
        )
        ProfileToggle(
            title = "Colorblind-Friendly Palette",
            description = "Uses blue/orange status accents while retaining words, icons, and borders so color is never the only signal.",
            checked = colorblindMode,
            onCheckedChange = onColorblindModeChange,
        )
        ProfileToggle(
            title = "Enhanced High Contrast",
            description = "Boosts contrast of secondary text, tertiary text, and borders to pass strict WCAG AAA guidelines.",
            checked = highContrastMode,
            onCheckedChange = onHighContrastModeChange,
        )
        ProfileToggle(
            title = "Touch Target Optimization",
            description = "Increases minimum touch target sizes of interactive elements for field operations under high-pressure scenarios.",
            checked = touchOptimizedMode,
            onCheckedChange = onTouchOptimizedModeChange,
        )
        ProfileToggle(
            title = "Larger Interface Text",
            description = "Increases text throughout the app while preserving the operating system's existing text scale.",
            checked = largeTextMode,
            onCheckedChange = onLargeTextModeChange,
        )
        HorizontalDivider(color = AresBorder.copy(alpha = 0.6f))
        ProfileToggle(
            title = "Developer Tools",
            description = "Shows Database, Developer Reference, and advanced authoring tools in the command palette.",
            checked = developerMode,
            onCheckedChange = onDeveloperModeChange,
        )
    }
}

@Composable
internal fun ProductInformationSection() {
    ProfileSettingsCard(verticalSpacing = 10) {
        Text(
            "${BuildConfig.PRODUCT_NAME} ${BuildConfig.VERSION}",
            fontWeight = FontWeight.Bold,
            color = AresTextPrimary,
            fontSize = 16.sp,
        )
        Text(BuildConfig.PRODUCT_TAGLINE, color = AresCyan, fontSize = 12.sp)
        Text(
            "Previously ${BuildConfig.LEGACY_PRODUCT_NAME}; existing projects, settings, and credentials remain compatible.",
            color = AresTextSecondary,
            fontSize = 11.sp,
        )
        HorizontalDivider(color = AresBorder.copy(alpha = 0.6f))
        Text(
            "License and source",
            fontWeight = FontWeight.Bold,
            color = AresTextPrimary,
            fontSize = 15.sp,
        )
        Text(
            "ARES Robotics Studio is licensed under GNU AGPL v3 or later and is provided without warranty. Separate commercial licensing is available from the ARES project.",
            color = AresTextSecondary,
            fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                runCatching { openExternalLink("https://github.com/ARES-23247/ARES-Robotics") }
            }) {
                Text("View source")
            }
            OutlinedButton(onClick = {
                runCatching { openExternalLink("https://github.com/ARES-23247/ARES-Robotics/blob/main/LICENSE") }
            }) {
                Text("Read license")
            }
        }
    }
}

@Composable
private fun ProfileSettingsCard(
    verticalSpacing: Int = 12,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing.dp),
            content = content,
        )
    }
}

@Composable
private fun ProfileSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
        Text(title, fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 15.sp)
    }
}

@Composable
private fun AiProviderOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onClick)) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = AresCyan),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = AresTextPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun ProfileToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AresTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = AresTextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = AresCyan, checkedTrackColor = AresCyanGlow),
        )
    }
}

@Composable
private fun ProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = visualTransformation,
        colors = profileTextFieldColors(),
    )
}

@Composable
private fun profileTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AresCyan,
    unfocusedBorderColor = AresBorder,
)
