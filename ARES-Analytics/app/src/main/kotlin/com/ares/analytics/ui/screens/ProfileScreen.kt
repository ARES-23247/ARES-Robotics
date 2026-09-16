package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.BuildConfig
import com.ares.analytics.service.AuthState
import com.ares.analytics.service.ManagedToolchainService
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.components.core.chooseProjectDirectory
import com.ares.analytics.ui.components.core.openExternalLink
import com.ares.analytics.ui.components.forms.AresTextField
import com.ares.analytics.ui.theme.*
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.viewmodel.ProfileIntent
import com.ares.analytics.viewmodel.ProfileViewModel
import kotlinx.coroutines.launch

/**
 * User account profile, developer preferences, and workspace configuration screen.
 *
 * Configures active user identity, team membership, telemetry chart preferences, theme colors, and AI assistant prompt defaults.
 *
 * @param viewModel [ProfileViewModel] for handling profile mutations.
 * @param authState Active OAuth authentication flow state.
 * @param onLogout Callback for terminating active user session.
 *
 * @see ProfileViewModel
 * @see WorkspaceConfig
 */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    managedToolchainService: ManagedToolchainService,
    config: WorkspaceConfig,
    onConfigChanged: (WorkspaceConfig) -> Unit,
    authState: AuthState = AuthState.Unauthenticated,
    onLogout: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val toolchains by managedToolchainService.snapshot.collectAsState()
    val toolchainInstallState by managedToolchainService.installState.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(config.league) {
        managedToolchainService.refresh(config.league)
    }

    LaunchedEffect(state.pendingConfigUpdate) {
        state.pendingConfigUpdate?.let { updated ->
            onConfigChanged(updated)
            viewModel.onIntent(ProfileIntent.ConfigUpdateApplied)
        }
    }

    LaunchedEffect(config) {
        viewModel.onIntent(ProfileIntent.LoadConfig(config))
    }

    // Workspace Active Settings overrides
    var teamId by remember(config.teamId) { mutableStateOf(config.teamId) }
    var robotId by remember(config.robotId) { mutableStateOf(config.robotId) }
    var robotName by remember(config.robotName) { mutableStateOf(config.robotName) }
    var league by remember(config.league) { mutableStateOf(config.league) }
    var seasonId by remember(config.seasonId) { mutableStateOf(config.seasonId) }
    var projectPath by remember(config.projectPath) { mutableStateOf(config.projectPath) }
    var colorblindMode by remember(config.colorblindMode) { mutableStateOf(config.colorblindMode) }
    var highContrastMode by remember(config.highContrastMode) { mutableStateOf(config.highContrastMode) }
    var touchOptimizedMode by remember(config.touchOptimizedMode) { mutableStateOf(config.touchOptimizedMode) }
    var largeTextMode by remember(config.largeTextMode) { mutableStateOf(config.largeTextMode) }
    var developerMode by remember(config.developerMode) { mutableStateOf(config.developerMode) }
    val hasCanonicalProjectIdentity = remember(projectPath) {
        java.io.File(projectPath, ".ares/project.json").isFile
    }

    // Optional credential overrides
    var googleClientId by remember(state.googleClientId) { mutableStateOf(state.googleClientId) }
    var googleOAuthBrokerUrl by remember(state.googleOAuthBrokerUrl) {
        mutableStateOf(state.googleOAuthBrokerUrl)
    }
    var useCustomGoogleClient by remember(state.googleOAuthUseCustomClient) {
        mutableStateOf(state.googleOAuthUseCustomClient)
    }

    // Match integration overrides
    var eventCode by remember(state.eventCode) { mutableStateOf(state.eventCode) }
    var toaApiKey by remember(state.toaApiKey) { mutableStateOf(state.toaApiKey) }
    var tbaApiKey by remember(state.tbaApiKey) { mutableStateOf(state.tbaApiKey) }

    // AI Diagnostics overrides
    var aiMode by remember(state.aiMode) { mutableStateOf(state.aiMode) }
    var geminiApiKey by remember(state.geminiApiKey) { mutableStateOf(state.geminiApiKey) }
    var geminiModel by remember(state.geminiModel) { mutableStateOf(state.geminiModel) }
    var vertexServiceAccountPath by remember(state.vertexServiceAccountPath) { mutableStateOf(state.vertexServiceAccountPath) }
    var vertexProjectId by remember(state.vertexProjectId) { mutableStateOf(state.vertexProjectId) }
    var vertexLocation by remember(state.vertexLocation) { mutableStateOf(state.vertexLocation) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Profile & Settings Dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AresTextPrimary)
        Text("Configure active workspace profiles, third-party integrations, and direct client-side diagnostics.", color = AresTextSecondary, fontSize = 12.sp)
        HorizontalDivider(color = AresBorder)

        // 1. Workspace Identity & Active Robot Selection
        AresCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                    Text("Workspace Active Robot Profile", fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 15.sp)
                }
                Text(
                    if (hasCanonicalProjectIdentity) {
                        "Identity is owned by .ares/project.json. Change the team, robot, season, display name, or league in Robot Studio so every builder uses the same values."
                    } else {
                        "Select a valid robot project. ARES requires .ares/project.json and will not substitute cached profile identity for a missing canonical project document."
                    },
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )

                val projectPathError = remember(projectPath, league) {
                    ProjectLayout.validationError(projectPath, league)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AresTextField(
                        value = projectPath,
                        onValueChange = { projectPath = it },
                        label = "Robot project directory",
                        supportingText = {
                            Text(projectPathError ?: "Robot source, autos, field data, and build files use this folder.")
                        },
                        isError = projectPathError != null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            chooseProjectDirectory(projectPath)?.let { projectPath = it.path }
                        }
                    ) {
                        Text("Browse…")
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AresTextField(
                        value = teamId,
                        onValueChange = { teamId = it.filter { c -> c.isDigit() } },
                        label = "Team ID Number",
                        readOnly = true,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    AresTextField(
                        value = robotId,
                        onValueChange = { robotId = it.filter { c -> c.isLetterOrDigit() || c == '-' } },
                        label = "Robot ID",
                        readOnly = true,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AresTextField(
                        value = robotName,
                        onValueChange = { robotName = it },
                        label = "Robot Friendly Name",
                        readOnly = true,
                        modifier = Modifier.weight(1.5f),
                        singleLine = true,
                    )
                    AresTextField(
                        value = seasonId,
                        onValueChange = { seasonId = it },
                        label = "Season ID",
                        readOnly = true,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }

                // League selection toggle group
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    League.entries.forEach { l ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, if (league == l) AresCyan else AresBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            RadioButton(
                                selected = league == l,
                                onClick = null,
                                enabled = false,
                                colors = RadioButtonDefaults.colors(selectedColor = AresCyan),
                            )
                            Text(l.name, color = if (league == l) AresCyan else AresTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. Robot build tools
        ProfileRobotBuildToolsSection(
            config = config,
            toolchains = toolchains,
            toolchainInstallState = toolchainInstallState,
            managedToolchainService = managedToolchainService,
        )

        // 3. Google Drive Cloud Sync
        ProfileCloudStorageSection(
            config = config,
            onConfigChanged = onConfigChanged,
            state = state,
            viewModel = viewModel,
            useCustomGoogleClient = useCustomGoogleClient,
            onUseCustomGoogleClientChange = { useCustomGoogleClient = it },
            googleClientId = googleClientId,
            onGoogleClientIdChange = { googleClientId = it },
            googleOAuthBrokerUrl = googleOAuthBrokerUrl,
            onGoogleOAuthBrokerUrlChange = { googleOAuthBrokerUrl = it },
        )

        ThirdPartyIntegrationsSection(
            league = league,
            eventCode = eventCode,
            onEventCodeChange = { eventCode = it },
            toaApiKey = toaApiKey,
            onToaApiKeyChange = { toaApiKey = it },
            tbaApiKey = tbaApiKey,
            onTbaApiKeyChange = { tbaApiKey = it },
        )

        GeminiAssistanceSection(
            aiMode = aiMode,
            onAiModeChange = { aiMode = it },
            geminiModel = geminiModel,
            onGeminiModelChange = { geminiModel = it },
            geminiApiKey = geminiApiKey,
            onGeminiApiKeyChange = { geminiApiKey = it },
            vertexServiceAccountPath = vertexServiceAccountPath,
            onVertexServiceAccountPathChange = { vertexServiceAccountPath = it },
            vertexProjectId = vertexProjectId,
            onVertexProjectIdChange = { vertexProjectId = it },
            vertexLocation = vertexLocation,
            onVertexLocationChange = { vertexLocation = it },
        )

        AccessibilityOptionsSection(
            colorblindMode = colorblindMode,
            onColorblindModeChange = { colorblindMode = it },
            highContrastMode = highContrastMode,
            onHighContrastModeChange = { highContrastMode = it },
            touchOptimizedMode = touchOptimizedMode,
            onTouchOptimizedModeChange = { touchOptimizedMode = it },
            largeTextMode = largeTextMode,
            onLargeTextModeChange = { largeTextMode = it },
            developerMode = developerMode,
            onDeveloperModeChange = { developerMode = it },
        )

        ProductInformationSection()

        // Save Button Footer
        Button(
            onClick = {
                val newConfig = config.copy(
                    teamId = teamId,
                    robotId = robotId,
                    robotName = robotName,
                    league = league,
                    seasonId = seasonId,
                    projectPath = projectPath,
                    googleOAuthUseCustomClient = useCustomGoogleClient,
                    googleClientId = googleClientId.takeIf { useCustomGoogleClient && it.isNotBlank() },
                    googleOAuthBrokerUrl = googleOAuthBrokerUrl.takeIf {
                        useCustomGoogleClient && it.isNotBlank()
                    },
                    googleClientSecret = null,
                    eventCode = eventCode.takeIf { it.isNotBlank() },
                    toaApiKey = toaApiKey.takeIf { it.isNotBlank() },
                    tbaApiKey = tbaApiKey.takeIf { it.isNotBlank() },
                    aiMode = aiMode.takeIf { it.isNotBlank() },
                    geminiApiKey = geminiApiKey.takeIf { it.isNotBlank() },
                    geminiModel = geminiModel.takeIf { it.isNotBlank() },
                    vertexServiceAccountPath = vertexServiceAccountPath.takeIf { it.isNotBlank() },
                    vertexProjectId = vertexProjectId.takeIf { it.isNotBlank() },
                    vertexLocation = vertexLocation.takeIf { it.isNotBlank() },
                    colorblindMode = colorblindMode,
                    highContrastMode = highContrastMode,
                    touchOptimizedMode = touchOptimizedMode,
                    largeTextMode = largeTextMode,
                    developerMode = developerMode
                )
                // MainViewModel owns the single persisted workspace update.
                onConfigChanged(newConfig)
            },
            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            enabled = ProjectLayout.validationError(projectPath, league) == null,
            modifier = Modifier.fillMaxWidth().height(if (touchOptimizedMode) 56.dp else 48.dp)
        ) {
            Text("Save Profile & Settings", color = AresBackground, fontWeight = FontWeight.Bold, fontSize = if (touchOptimizedMode) 18.sp else 16.sp)
        }
    }
}
