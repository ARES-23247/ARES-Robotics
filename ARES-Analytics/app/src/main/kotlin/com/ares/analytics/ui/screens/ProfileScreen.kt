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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.IntegrationInstructions
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
import com.ares.analytics.service.ManagedToolchainInstallState
import com.ares.analytics.service.ManagedToolchainPaths
import com.ares.analytics.service.ManagedToolchainService
import com.ares.analytics.service.ToolchainReadiness
import com.ares.analytics.service.isValidGoogleDesktopClientId
import com.ares.analytics.service.isValidGoogleOAuthBrokerUrl
import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.shared.League
import com.ares.analytics.shared.WorkspaceConfig
import com.ares.analytics.shared.DriveDestinationType
import com.ares.analytics.shared.WorkspaceCollaborationMode
import com.ares.analytics.shared.AppJson
import com.ares.analytics.ui.components.core.chooseProjectDirectory
import com.ares.analytics.ui.components.core.openExternalLink
import com.ares.analytics.ui.theme.*
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.viewmodel.ProfileIntent
import com.ares.analytics.viewmodel.ProfileViewModel
import javax.swing.JFileChooser
import kotlinx.serialization.encodeToString
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
    var robotMenuExpanded by remember { mutableStateOf(false) }
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
    var showAdvanced by remember { mutableStateOf(false) }
    var showDestinationSetup by remember(config.driveDestination) {
        mutableStateOf(config.driveDestination == null)
    }
    var destinationType by remember { mutableStateOf(DriveDestinationType.PERSONAL_FOLDER) }
    var destinationName by remember(config.robotName, config.teamId) {
        mutableStateOf(
            "ARES ${config.robotName.ifBlank { "Team ${config.teamId}" }}",
        )
    }
    var destinationTypeMenuExpanded by remember { mutableStateOf(false) }
    var destinationNotice by remember { mutableStateOf<String?>(null) }

    // Match integration overrides
    var eventCode by remember(state.eventCode) { mutableStateOf(state.eventCode) }
    var toaApiKey by remember(state.toaApiKey) { mutableStateOf(state.toaApiKey) }
    var tbaApiKey by remember(state.tbaApiKey) { mutableStateOf(state.tbaApiKey) }

    // AI Diagnostics overrides
    var aiMode by remember(state.aiMode) { mutableStateOf(state.aiMode) }
    var geminiApiKey by remember(state.geminiApiKey) { mutableStateOf(state.geminiApiKey) }
    var geminiModel by remember(state.geminiModel) { mutableStateOf(state.geminiModel) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                    Text("Workspace Active Robot Profile", fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 15.sp)
                }
                Text(
                    if (hasCanonicalProjectIdentity) {
                        "Identity is owned by .ares/project.json. Change the team, robot, season, display name, or league in Robot Studio so every builder uses the same values."
                    } else {
                        "Select a project directory. Legacy workspaces may use these cached identity fields until Robot Studio creates the canonical project document."
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
                    OutlinedTextField(
                        value = projectPath,
                        onValueChange = { projectPath = it },
                        label = { Text("Robot project directory") },
                        supportingText = {
                            Text(projectPathError ?: "Robot source, autos, field data, and build files use this folder.")
                        },
                        isError = projectPathError != null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AresCyan,
                            unfocusedBorderColor = AresBorder
                        )
                    )
                    OutlinedButton(
                        onClick = {
                            chooseProjectDirectory(projectPath)?.let { projectPath = it.path }
                        }
                    ) {
                        Text("Browse…")
                    }
                }

                // Shared Roster Dropdown (if loaded)
                if (state.robotProfiles.isNotEmpty() && !hasCanonicalProjectIdentity) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = if (robotName.isNotBlank()) "$robotName ($robotId)" else robotId,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Select Registered Team Robot Profile") },
                            modifier = Modifier.fillMaxWidth().clickable { robotMenuExpanded = !robotMenuExpanded },
                            trailingIcon = {
                                IconButton(onClick = { robotMenuExpanded = !robotMenuExpanded }) {
                                    Icon(imageVector = if (robotMenuExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = AresTextSecondary)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                        )
                        DropdownMenu(
                            expanded = robotMenuExpanded,
                            onDismissRequest = { robotMenuExpanded = false }
                        ) {
                            state.robotProfiles.forEach { robot ->
                                DropdownMenuItem(
                                    text = { Text("${robot.name} (${robot.robotId})", color = AresTextPrimary) },
                                    onClick = {
                                        robotId = robot.robotId
                                        robotName = robot.name
                                        league = robot.league
                                        seasonId = robot.seasonId
                                        robotMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = teamId,
                        onValueChange = { teamId = it.filter { c -> c.isDigit() } },
                        label = { Text("Team ID Number") },
                        readOnly = hasCanonicalProjectIdentity,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )
                    OutlinedTextField(
                        value = robotId,
                        onValueChange = { robotId = it.filter { c -> c.isLetterOrDigit() || c == '-' } },
                        label = { Text("Robot ID") },
                        readOnly = hasCanonicalProjectIdentity,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = robotName,
                        onValueChange = { robotName = it },
                        label = { Text("Robot Friendly Name") },
                        readOnly = hasCanonicalProjectIdentity,
                        modifier = Modifier.weight(1.5f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )
                    OutlinedTextField(
                        value = seasonId,
                        onValueChange = { seasonId = it },
                        label = { Text("Season ID") },
                        readOnly = hasCanonicalProjectIdentity,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
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
                                .clickable(enabled = !hasCanonicalProjectIdentity) { league = l }
                                .border(1.dp, if (league == l) AresCyan else AresBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            RadioButton(
                                selected = league == l,
                                onClick = { league = l },
                                enabled = !hasCanonicalProjectIdentity,
                                colors = RadioButtonDefaults.colors(selectedColor = AresCyan),
                            )
                            Text(l.name, color = if (league == l) AresCyan else AresTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. Robot build tools
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.IntegrationInstructions, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                    Text("Robot build tools", fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 15.sp)
                }
                Text(
                    "The installed ARES app already has its own runtime. These optional tools are used only to build, simulate, or deploy ${config.league.name} robot projects.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
                toolchains.components.forEach { component ->
                    Row(
                        modifier = Modifier.fillMaxWidth().border(1.dp, AresBorder, RoundedCornerShape(8.dp)).padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when (component.readiness) {
                                ToolchainReadiness.READY -> "READY"
                                ToolchainReadiness.OPTIONAL_DOWNLOAD -> "INSTALL"
                                ToolchainReadiness.MANUAL_SETUP_REQUIRED -> "ACTION NEEDED"
                            },
                            color = when (component.readiness) {
                                ToolchainReadiness.READY -> AresGreen
                                ToolchainReadiness.OPTIONAL_DOWNLOAD -> AresCyan
                                ToolchainReadiness.MANUAL_SETUP_REQUIRED -> AresAmber
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(component.name, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(component.detail, color = AresTextSecondary, fontSize = 11.sp)
                            component.location?.let { Text(it, color = AresTextTertiary, fontSize = 10.sp) }
                        }
                    }
                }
                when (val install = toolchainInstallState) {
                    is ManagedToolchainInstallState.Working -> {
                        install.fraction?.let { fraction ->
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = AresCyan)
                        }
                        Text(install.message, color = AresTextSecondary, fontSize = 11.sp)
                    }
                    is ManagedToolchainInstallState.Succeeded -> Text(install.message, color = AresGreen, fontSize = 11.sp)
                    is ManagedToolchainInstallState.Failed -> Text(install.message, color = AresAmber, fontSize = 11.sp)
                    ManagedToolchainInstallState.Idle -> Unit
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (toolchains.components.any { it.name.startsWith("Java") && it.readiness != ToolchainReadiness.READY }) {
                        Button(
                            onClick = {
                                if (ManagedToolchainPaths.managedJdkInstallationSupported()) {
                                    scope.launch { runCatching { managedToolchainService.installManagedJdk21(config.league) } }
                                } else {
                                    openExternalLink(ManagedToolchainPaths.JDK_21_DOWNLOAD_URL)
                                }
                            },
                            enabled = toolchainInstallState !is ManagedToolchainInstallState.Working,
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        ) {
                            Text(if (ManagedToolchainPaths.managedJdkInstallationSupported()) "Install private JDK 21" else "Download JDK 21")
                        }
                    }
                    OutlinedButton(onClick = { scope.launch { managedToolchainService.refresh(config.league) } }) {
                        Text("Recheck tools")
                    }
                    OutlinedButton(onClick = {
                        openExternalLink("https://github.com/ARES-23247/ARES-Analytics/blob/master/docs/start/ROBOT_BUILD_TOOLS.md")
                    }) { Text("Setup guide") }
                }
            }
        }

        // 3. Google Drive Cloud Sync
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                    Text("Google Drive Roster & Cloud Sync", fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 15.sp)
                }

                when (val auth = state.authState) {
                    is AuthState.Unauthenticated -> {
                        Text(
                            "Sign in to choose a personal or team Drive folder. The ARES client identifies this app; your files stay in the Google account and folder you select.",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                        val customClientValid = isValidGoogleDesktopClientId(googleClientId) &&
                            isValidGoogleOAuthBrokerUrl(googleOAuthBrokerUrl)
                        val signInAvailable = if (useCustomGoogleClient) customClientValid else state.managedGoogleSignInAvailable
                        if (!signInAvailable) {
                            Text(
                                if (useCustomGoogleClient) {
                                    "Complete the organization client ID and secure token-service URL in Advanced administrator settings, or turn off the custom client."
                                } else {
                                    "This development build has no managed Google client. Install an official ARES release or configure a custom client as an administrator."
                                },
                                color = AresGold,
                                fontSize = 11.sp,
                            )
                        }
                        Button(
                            onClick = {
                                val updatedConfig = config.copy(
                                    googleOAuthUseCustomClient = useCustomGoogleClient,
                                    googleClientId = googleClientId.takeIf { useCustomGoogleClient && it.isNotBlank() },
                                    googleOAuthBrokerUrl = googleOAuthBrokerUrl.takeIf {
                                        useCustomGoogleClient && it.isNotBlank()
                                    },
                                    googleClientSecret = null,
                                )
                                onConfigChanged(updatedConfig)
                                viewModel.onIntent(ProfileIntent.GoogleSignIn(updatedConfig))
                            },
                            enabled = signInAvailable,
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)
                        ) {
                            Text("Sign in with Google", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "ARES requests only your basic identity and access to Drive files created or explicitly selected for ARES. Google Drive is optional.",
                            color = AresTextTertiary,
                            fontSize = 10.sp,
                        )
                        config.driveDestination?.let { saved ->
                            Text(
                                "Saved destination: ${saved.displayName} · reconnect as ${saved.accountEmail}",
                                color = AresTextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    is AuthState.Authenticating -> {
                        CircularProgressIndicator(color = AresCyan, modifier = Modifier.size(24.dp))
                        Text("Verifying authorization flow via system browser...", color = AresTextSecondary, fontSize = 12.sp)
                    }
                    is AuthState.Authenticated -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Signed in as: ${auth.displayName}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                            Text("Email: ${auth.email}", fontSize = 11.sp, color = AresTextSecondary)
                            Text(
                                "Storage destination: ${config.driveDestination?.displayName ?: "choose a workspace folder"}",
                                fontSize = 11.sp,
                                color = AresCyan,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.onIntent(ProfileIntent.PerformDeltaSync(auth.idToken)) },
                                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)
                            ) {
                                Text("Sync Google Drive Now", color = AresBackground, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { viewModel.onIntent(ProfileIntent.SignOut) },
                                colors = ButtonDefaults.buttonColors(containerColor = AresBorder)
                            ) {
                                Text("Sign Out", color = AresTextPrimary)
                            }
                        }
                    }
                    is AuthState.Error -> {
                        Text("Authorization Error: ${auth.message}", color = AresError, fontSize = 12.sp)
                        Button(
                            onClick = {
                                val updatedConfig = config.copy(
                                    googleOAuthUseCustomClient = useCustomGoogleClient,
                                    googleClientId = googleClientId.takeIf { useCustomGoogleClient && it.isNotBlank() },
                                    googleOAuthBrokerUrl = googleOAuthBrokerUrl.takeIf {
                                        useCustomGoogleClient && it.isNotBlank()
                                    },
                                    googleClientSecret = null,
                                )
                                viewModel.onIntent(ProfileIntent.GoogleSignIn(updatedConfig))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        ) {
                            Text("Try Google sign-in again", color = AresOnAccent)
                        }
                    }
                }

                if (state.authState is AuthState.Authenticated) {
                    HorizontalDivider(color = AresBorder)
                    val destination = config.driveDestination
                    if (destination != null && !showDestinationSetup) {
                        Text(
                            if (destination.collaborationMode == WorkspaceCollaborationMode.PERSONAL) {
                                "Personal workspace Drive destination"
                            } else {
                                "Team workspace Drive destination"
                            },
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(destination.displayName, color = AresCyan, fontSize = 12.sp)
                        Text("Signed-in account: ${destination.accountEmail}", color = AresTextSecondary, fontSize = 11.sp)
                        val status = state.driveDestinationStatus
                        if (status != null) {
                            Text(status.ownerLabel, color = AresTextSecondary, fontSize = 11.sp)
                            Text(status.sharingLabel, color = AresTextSecondary, fontSize = 11.sp)
                            Text(
                                if (status.canRead && status.canWrite) "Access: Read and write verified" else "Access: Needs attention",
                                color = if (status.canRead && status.canWrite) AresGreen else AresError,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else if (state.isDriveDestinationBusy) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AresCyan)
                                Text("Checking folder permissions…", color = AresTextSecondary, fontSize = 11.sp)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.onIntent(ProfileIntent.RefreshDriveDestination(config)) }) {
                                Text("Check access")
                            }
                            status?.webViewLink?.let { link ->
                                OutlinedButton(onClick = { openExternalLink(link) }) {
                                    Text("Open in Drive")
                                }
                            }
                            OutlinedButton(onClick = { showDestinationSetup = true }) {
                                Text("Change destination")
                            }
                            OutlinedButton(
                                onClick = {
                                    destinationNotice = exportDriveDestinationRecord(destination)
                                },
                            ) {
                                Text("Export destination record")
                            }
                            OutlinedButton(
                                onClick = {
                                    onConfigChanged(config.copy(driveDestination = null))
                                    showDestinationSetup = true
                                },
                            ) {
                                Text("Disconnect destination")
                            }
                        }
                        Text(
                            "Changing or disconnecting never deletes Drive files. Import or download any remote-only sessions before switching, then sync local sessions to the new destination.",
                            color = AresTextTertiary,
                            fontSize = 10.sp,
                        )
                        destinationNotice?.let { notice ->
                            Text(notice, color = AresTextSecondary, fontSize = 10.sp)
                        }
                    } else {
                        Text("Choose where this workspace stores ARES files", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "ARES will list and synchronize only inside the folder or Shared Drive saved here. Other Drive files are never scanned.",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { destinationTypeMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    when (destinationType) {
                                        DriveDestinationType.PERSONAL_FOLDER -> "Personal Drive folder"
                                        DriveDestinationType.TEAM_FOLDER -> "Create a team folder"
                                        DriveDestinationType.SHARED_FOLDER -> "Join an existing shared folder"
                                        DriveDestinationType.SHARED_DRIVE -> "Google Shared Drive"
                                    },
                                )
                            }
                            DropdownMenu(
                                expanded = destinationTypeMenuExpanded,
                                onDismissRequest = { destinationTypeMenuExpanded = false },
                            ) {
                                DriveDestinationType.entries.forEach { type ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                when (type) {
                                                    DriveDestinationType.PERSONAL_FOLDER -> "Personal Drive folder"
                                                    DriveDestinationType.TEAM_FOLDER -> "Create a team folder"
                                                    DriveDestinationType.SHARED_FOLDER -> "Join existing shared folder"
                                                    DriveDestinationType.SHARED_DRIVE -> "Google Shared Drive"
                                                },
                                            )
                                        },
                                        onClick = {
                                            destinationType = type
                                            destinationTypeMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = destinationName,
                            onValueChange = { destinationName = it },
                            label = { Text("Destination name") },
                            supportingText = {
                                Text(
                                    if (destinationType == DriveDestinationType.TEAM_FOLDER) {
                                        "ARES creates the folder; share it with students and mentors in Google Drive."
                                    } else {
                                        "This label helps students recognize the correct workspace."
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (destinationType == DriveDestinationType.SHARED_FOLDER ||
                            destinationType == DriveDestinationType.SHARED_DRIVE
                        ) {
                            Text(
                                if (destinationType == DriveDestinationType.SHARED_DRIVE) {
                                    "Google will open a folder picker. Choose a folder inside the Shared Drive; Workspace membership remains authoritative."
                                } else {
                                    "Google will open a folder picker. Choose the shared team folder so ARES receives access to that folder only."
                                },
                                color = AresTextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val sharedSelection = destinationType == DriveDestinationType.SHARED_FOLDER ||
                                        destinationType == DriveDestinationType.SHARED_DRIVE
                                    viewModel.onIntent(
                                        if (sharedSelection) ProfileIntent.PickExistingDriveDestination(
                                            config = config,
                                            type = destinationType,
                                            displayName = destinationName,
                                        ) else ProfileIntent.ConfigureDriveDestination(
                                            config = config,
                                            type = destinationType,
                                            displayName = destinationName,
                                        ),
                                    )
                                },
                                enabled = !state.isDriveDestinationBusy && destinationName.isNotBlank(),
                            ) {
                                Text(
                                    if (destinationType == DriveDestinationType.SHARED_FOLDER ||
                                        destinationType == DriveDestinationType.SHARED_DRIVE
                                    ) "Choose folder in Google Drive" else "Create this destination",
                                )
                            }
                            if (destination != null) {
                                OutlinedButton(onClick = { showDestinationSetup = false }) { Text("Cancel") }
                            }
                        }
                    }
                    state.errorMessage?.let { message ->
                        Text(message, color = AresError, fontSize = 11.sp)
                    }
                }

                // Collapsible Advanced Google Developer Credentials
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = AresTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Advanced administrator settings", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                if (showAdvanced) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Use your organization's OAuth client", color = AresTextPrimary, fontSize = 12.sp)
                            Text(
                                "For schools that manage their own Google Cloud policies, quotas, and branding. The app uses PKCE; your administrator's HTTPS token service keeps Google credentials out of student installers.",
                                color = AresTextSecondary,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(checked = useCustomGoogleClient, onCheckedChange = { useCustomGoogleClient = it })
                    }
                    if (useCustomGoogleClient) {
                        OutlinedTextField(
                            value = googleClientId,
                            onValueChange = { googleClientId = it },
                            label = { Text("Desktop OAuth client ID") },
                            supportingText = { Text("Ends in .apps.googleusercontent.com. Never enter a client secret.") },
                            isError = googleClientId.isNotBlank() && !isValidGoogleDesktopClientId(googleClientId),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder),
                        )
                        OutlinedTextField(
                            value = googleOAuthBrokerUrl,
                            onValueChange = { googleOAuthBrokerUrl = it },
                            label = { Text("Organization token-service URL") },
                            supportingText = {
                                Text("HTTPS URL supplied by your administrator. Do not enter a client secret here.")
                            },
                            isError = googleOAuthBrokerUrl.isNotBlank() &&
                                !isValidGoogleOAuthBrokerUrl(googleOAuthBrokerUrl),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AresCyan,
                                unfocusedBorderColor = AresBorder,
                            ),
                        )
                    }
                }
            }
        }

        // 3. Third-Party Integrations
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.IntegrationInstructions, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                    Text("Third-Party API Integrations", fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 15.sp)
                }
                Text("Match metadata and schedules can be synced from FRC or FTC event aggregators.", color = AresTextSecondary, fontSize = 11.sp)

                OutlinedTextField(
                    value = eventCode,
                    onValueChange = { eventCode = it },
                    label = { Text("Event Code / ID (e.g. USNYTUT)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                )

                if (league == League.FTC) {
                    OutlinedTextField(
                        value = toaApiKey,
                        onValueChange = { toaApiKey = it },
                        label = { Text("The Orange Alliance (TOA) API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )
                } else {
                    OutlinedTextField(
                        value = tbaApiKey,
                        onValueChange = { tbaApiKey = it },
                        label = { Text("The Blue Alliance (TBA) API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )
                }
            }
        }

        // 4. AI Diagnostics
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Psychology, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                    Text("Gemini assistance", fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 15.sp)
                }
                Text(
                    "Choose the provider used by telemetry diagnostics and the review-only assistants in Subsystem Builder, Drivebase Builder, and Controller Bindings.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
                if (aiMode == "STUDIO" && geminiApiKey.isBlank()) {
                    Text("Add a Google AI Studio API key, then save this profile before using an editor assistant.", color = AresGold, fontSize = 11.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { aiMode = "STUDIO" }) {
                        RadioButton(selected = aiMode == "STUDIO", onClick = { aiMode = "STUDIO" }, colors = RadioButtonDefaults.colors(selectedColor = AresCyan))
                        Spacer(Modifier.width(4.dp))
                        Text("Google AI Studio (API Key)", color = AresTextPrimary, fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { aiMode = "VERTEX" }) {
                        RadioButton(selected = aiMode == "VERTEX", onClick = { aiMode = "VERTEX" }, colors = RadioButtonDefaults.colors(selectedColor = AresCyan))
                        Spacer(Modifier.width(4.dp))
                        Text("GCP Vertex AI (Service Account)", color = AresTextPrimary, fontSize = 13.sp)
                    }
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
                                Icon(imageVector = if (modelMenuExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = AresTextSecondary)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false }
                    ) {
                        listOf("gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite").forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model, color = AresTextPrimary) },
                                onClick = {
                                    geminiModel = model
                                    modelMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                if (aiMode == "STUDIO") {
                    OutlinedTextField(
                        value = geminiApiKey,
                        onValueChange = { geminiApiKey = it },
                        label = { Text("Gemini API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )
                } else {
                    OutlinedTextField(
                        value = vertexServiceAccountPath,
                        onValueChange = { vertexServiceAccountPath = it },
                        label = { Text("GCP Service Account JSON Key File Path") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )
                    OutlinedTextField(
                        value = vertexProjectId,
                        onValueChange = { vertexProjectId = it },
                        label = { Text("GCP Project ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )
                    OutlinedTextField(
                        value = vertexLocation,
                        onValueChange = { vertexLocation = it },
                        label = { Text("GCP Location Region") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )
                }
            }
        }

        // 4b. Accessibility & Usability Options
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                    Text("Accessibility & Usability Options", fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 15.sp)
                }
                Text("Optimize the mission control interface for different environments and readability requirements.", color = AresTextSecondary, fontSize = 11.sp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Colorblind-Friendly Palette", color = AresTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Uses blue/orange status accents while retaining words, icons, and borders so color is never the only signal.", color = AresTextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = colorblindMode,
                        onCheckedChange = { colorblindMode = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = AresCyan, checkedTrackColor = AresCyanGlow)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enhanced High Contrast", color = AresTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Boosts contrast of secondary text, tertiary text, and borders to pass strict WCAG AAA guidelines.", color = AresTextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = highContrastMode,
                        onCheckedChange = { highContrastMode = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = AresCyan, checkedTrackColor = AresCyanGlow)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Touch Target Optimization", color = AresTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Increases minimum touch target sizes of interactive elements for field operations under high-pressure scenarios.", color = AresTextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = touchOptimizedMode,
                        onCheckedChange = { touchOptimizedMode = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = AresCyan, checkedTrackColor = AresCyanGlow)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Larger Interface Text", color = AresTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Increases text throughout the app while preserving the operating system's existing text scale.",
                            color = AresTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = largeTextMode,
                        onCheckedChange = { largeTextMode = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = AresCyan, checkedTrackColor = AresCyanGlow)
                    )
                }

                HorizontalDivider(color = AresBorder.copy(alpha = 0.6f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Developer Tools", color = AresTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Shows Database, Developer Reference, and advanced authoring tools in the command palette.", color = AresTextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = developerMode,
                        onCheckedChange = { developerMode = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = AresCyan, checkedTrackColor = AresCyanGlow)
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        runCatching {
                            openExternalLink("https://github.com/ARES-23247/ARES-Analytics")
                        }
                    }) {
                        Text("View source")
                    }
                    OutlinedButton(onClick = {
                        runCatching {
                            openExternalLink("https://github.com/ARES-23247/ARES-Analytics/blob/master/LICENSE")
                        }
                    }) {
                        Text("Read license")
                    }
                }
            }
        }

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

private fun exportDriveDestinationRecord(destination: com.ares.analytics.shared.DriveDestinationConfig): String {
    val chooser = JFileChooser().apply {
        dialogTitle = "Export ARES Drive destination record"
        selectedFile = java.io.File("ares-drive-destination.json")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return "Export cancelled."
    return runCatching {
        val selected = chooser.selectedFile.let { file ->
            if (file.extension.equals("json", ignoreCase = true)) file else java.io.File(file.parentFile, "${file.name}.json")
        }
        writeFileAtomically(selected) { temporary ->
            temporary.writeText(AppJson.encodeToString(destination))
        }
        "Destination record exported to ${selected.name}. It contains folder/account identifiers, never OAuth tokens."
    }.getOrElse { failure ->
        "Destination record could not be exported: ${failure.message ?: failure.javaClass.simpleName}"
    }
}
