package com.ares.analytics.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.AuthState
import com.ares.analytics.service.OAuthService
import com.ares.analytics.service.SyncEngineService
import com.ares.analytics.shared.League
import com.ares.analytics.shared.RobotProfile
import com.ares.analytics.shared.WorkspaceConfig
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch

/**
 * System administration screen for managing team identity, robot profiles, workspace settings, and OAuth credentials.
 *
 * Configures global workspace settings, active robot profiles, cloud API sync endpoints, and dev mode overrides.
 *
 * @param syncEngineService Log sync engine service for manual delta-sync triggers.
 * @param oauthService OAuth authentication management service.
 * @param config Active [WorkspaceConfig] record.
 * @param modifier Modifier to be applied to the layout.
 */
@Composable
fun AdminScreen(
    syncEngineService: SyncEngineService,
    oauthService: OAuthService,
    config: WorkspaceConfig,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val authState by oauthService.authState.collectAsState()
    var robotProfiles by remember { mutableStateOf<List<RobotProfile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    val isAuthenticated = authState is AuthState.Authenticated

    fun refreshRobots() {
        if (!isAuthenticated) return
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val localRobot = RobotProfile(
                    robotId = config.robotId,
                    league = config.league,
                    seasonId = config.seasonId,
                    name = config.robotName.takeIf { it.isNotBlank() } ?: "${config.robotId} (Local Active)"
                )
                robotProfiles = syncEngineService.mutateRemoteRobotProfiles { profiles ->
                    if (profiles.none { it.robotId == config.robotId }) profiles + localRobot else profiles
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load robot registry from Google Drive: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(isAuthenticated) {
        refreshRobots()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = AresCyan)
                Text(
                    "Team Administration Panel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary
                )
            }

            if (isAuthenticated) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { refreshRobots() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh", tint = AresTextSecondary)
                    }
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = AresBackground)
                        Spacer(Modifier.width(4.dp))
                        Text("Add Robot", color = AresBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        HorizontalDivider(color = AresBorder)

        if (!isAuthenticated) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        "Google Drive Roster Required",
                        fontWeight = FontWeight.Bold,
                        color = AresTextSecondary,
                        fontSize = 16.sp
                    )
                    Text(
                        "Please sign in with Google on the Profile screen to access the team's shared robot registry in Google Drive.",
                        color = AresTextTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.width(360.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            return
        }

        // Active Local Robot card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AresBackground, RoundedCornerShape(8.dp))
                .border(1.dp, AresCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Active Local Workspace Robot", color = AresCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(config.robotName.takeIf { it.isNotBlank() } ?: config.robotId, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("ID: ${config.robotId} • League: ${config.league.name} • Season: ${config.seasonId}", color = AresTextSecondary, fontSize = 11.sp)
            }
            Text(
                "ACTIVE",
                color = AresGreen,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(AresGreen.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        val currentError = errorMessage
        when {
            isLoading -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AresCyan)
                }
            }
            currentError != null -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(currentError, color = AresError)
                }
            }
            robotProfiles.isEmpty() -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "No robots registered on Google Drive yet.",
                        color = AresTextTertiary,
                        fontSize = 12.sp
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(robotProfiles) { robot ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AresSurfaceElevated)
                                .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val badgeBg = if (robot.league == League.FTC) AresGold else AresCyan
                                    Text(
                                        text = robot.league.name,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AresBackground,
                                        modifier = Modifier
                                            .background(badgeBg, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                    Text(
                                        text = robot.name,
                                        fontWeight = FontWeight.Bold,
                                        color = AresTextPrimary,
                                        fontSize = 14.sp
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "ID: ${robot.robotId} • Season ID: ${robot.seasonId}",
                                    fontSize = 11.sp,
                                    color = AresTextSecondary
                                )
                            }

                            // Do not allow deleting the local active robot from here
                            if (robot.robotId != config.robotId) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                robotProfiles = syncEngineService.mutateRemoteRobotProfiles { profiles ->
                                                    profiles.filter { it.robotId != robot.robotId }
                                                }
                                            } catch (e: Exception) {
                                                errorMessage = "Failed to delete robot profile: ${e.message}"
                                            }
                                        }
                                    }
                                ) {
                                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = AresError)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var robotId by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var league by remember { mutableStateOf(League.FTC) }
        var seasonId by remember { mutableStateOf("2026") }
        var dialogError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Register Team Robot Profile", color = AresTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This robot profile will be synced to Google Drive and shared with all team members.", color = AresTextSecondary, fontSize = 11.sp)

                    OutlinedTextField(
                        value = robotId,
                        onValueChange = { robotId = it.filter { c -> c.isLetterOrDigit() || c == '-' } },
                        label = { Text("Robot Unique ID (e.g. robot-a)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Friendly Name (e.g. ARES FTC Drivetrain)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )

                    OutlinedTextField(
                        value = seasonId,
                        onValueChange = { seasonId = it },
                        label = { Text("Season ID (e.g. 2026)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AresCyan, unfocusedBorderColor = AresBorder)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        League.entries.forEach { l ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { league = l }
                                    .border(1.dp, if (league == l) AresCyan else AresBorder, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                RadioButton(selected = league == l, onClick = { league = l }, colors = RadioButtonDefaults.colors(selectedColor = AresCyan))
                                Text(l.name, color = if (league == l) AresCyan else AresTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    dialogError?.let {
                        Text(it, color = AresError, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (robotId.isEmpty() || name.isEmpty() || seasonId.isEmpty()) {
                            dialogError = "All fields are required."
                            return@Button
                        }
                        if (robotProfiles.any { it.robotId == robotId }) {
                            dialogError = "A robot with this ID is already registered."
                            return@Button
                        }
                        scope.launch {
                            try {
                                val newRobot = RobotProfile(robotId, league, seasonId, name)
                                robotProfiles = syncEngineService.mutateRemoteRobotProfiles { profiles ->
                                    require(profiles.none { it.robotId == newRobot.robotId }) {
                                        "A robot with this ID is already registered."
                                    }
                                    profiles + newRobot
                                }
                                showAddDialog = false
                            } catch (e: Exception) {
                                dialogError = "Error registering profile: ${e.message}"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)
                ) {
                    Text("Register Profile", color = AresBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = AresTextPrimary)
                }
            }
        )
    }
}
