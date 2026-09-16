package com.ares.analytics.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.AuthState
import com.ares.analytics.service.isValidGoogleDesktopClientId
import com.ares.analytics.service.isValidGoogleOAuthBrokerUrl
import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.DriveDestinationConfig
import com.ares.analytics.shared.models.DriveDestinationType
import com.ares.analytics.shared.models.WorkspaceCollaborationMode
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.components.core.openExternalLink
import com.ares.analytics.ui.components.forms.AresTextField
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.util.DesktopFileChoosers
import com.ares.analytics.viewmodel.ProfileIntent
import com.ares.analytics.viewmodel.ProfileState
import com.ares.analytics.viewmodel.ProfileViewModel
import kotlinx.serialization.encodeToString

@Composable
internal fun ProfileCloudStorageSection(
    config: WorkspaceConfig,
    onConfigChanged: (WorkspaceConfig) -> Unit,
    state: ProfileState,
    viewModel: ProfileViewModel,
    useCustomGoogleClient: Boolean,
    onUseCustomGoogleClientChange: (Boolean) -> Unit,
    googleClientId: String,
    onGoogleClientIdChange: (String) -> Unit,
    googleOAuthBrokerUrl: String,
    onGoogleOAuthBrokerUrlChange: (String) -> Unit,
) {
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

    AresCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    AresTextField(
                        value = destinationName,
                        onValueChange = { destinationName = it },
                        label = "Destination name",
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
                    Switch(checked = useCustomGoogleClient, onCheckedChange = onUseCustomGoogleClientChange)
                }
                if (useCustomGoogleClient) {
                    AresTextField(
                        value = googleClientId,
                        onValueChange = onGoogleClientIdChange,
                        label = "Desktop OAuth client ID",
                        supportingText = { Text("Ends in .apps.googleusercontent.com. Never enter a client secret.") },
                        isError = googleClientId.isNotBlank() && !isValidGoogleDesktopClientId(googleClientId),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    AresTextField(
                        value = googleOAuthBrokerUrl,
                        onValueChange = onGoogleOAuthBrokerUrlChange,
                        label = "Organization token-service URL",
                        supportingText = {
                            Text("HTTPS URL supplied by your administrator. Do not enter a client secret here.")
                        },
                        isError = googleOAuthBrokerUrl.isNotBlank() &&
                            !isValidGoogleOAuthBrokerUrl(googleOAuthBrokerUrl),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        }
    }
}

private fun exportDriveDestinationRecord(destination: DriveDestinationConfig): String {
    val selected = DesktopFileChoosers.chooseSaveFile(
        dialogTitle = "Export ARES Drive destination record",
        defaultFileName = "ares-drive-destination.json",
        filterDescription = "JSON Files (*.json)",
        extensions = listOf("json"),
    ) ?: return "Export cancelled."

    return runCatching {
        writeFileAtomically(selected) { temporary ->
            temporary.writeText(AppJson.encodeToString(destination))
        }
        "Destination record exported to ${selected.name}. It contains folder/account identifiers, never OAuth tokens."
    }.getOrElse { failure ->
        "Destination record could not be exported: ${failure.message ?: failure.javaClass.simpleName}"
    }
}
