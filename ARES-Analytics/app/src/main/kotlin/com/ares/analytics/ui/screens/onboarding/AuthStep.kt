package com.ares.analytics.ui.screens.onboarding

import com.ares.analytics.ui.theme.AresOnAccent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ares.analytics.service.AuthState
import com.ares.analytics.shared.models.DriveDestinationConfig
import com.ares.analytics.shared.models.DriveDestinationType
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

/** Optional Google Drive setup. Local onboarding never depends on this card. */
@Composable
fun AuthStep(
    authState: AuthState,
    managedGoogleSignInAvailable: Boolean,
    driveDestination: DriveDestinationConfig?,
    isDriveDestinationBusy: Boolean,
    driveDestinationError: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSignInClick: () -> Unit,
    onConfigureDestination: (DriveDestinationType, String, String?, String?) -> Unit,
    onPickExistingDestination: (DriveDestinationType, String) -> Unit,
) {
    var destinationType by remember { mutableStateOf(DriveDestinationType.PERSONAL_FOLDER) }
    var destinationMenuExpanded by remember { mutableStateOf(false) }
    var destinationName by remember { mutableStateOf("ARES Robotics Studio") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (authState is AuthState.Authenticated) Icons.Default.CloudDone else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = if (authState is AuthState.Authenticated) AresGreen else AresTextTertiary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cloud sync (optional)", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        if (authState is AuthState.Authenticated) "Signed in as ${authState.displayName}"
                        else "Skip this to keep logs and settings on this computer.",
                        color = AresTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Hide cloud settings" else "Show cloud settings",
                    tint = AresTextSecondary,
                )
            }

            if (expanded) {
                Text(
                    "Cloud sync copies laptop-managed data to Google Drive. Robots still work offline and never upload directly.",
                    color = AresTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )

                if (authState !is AuthState.Authenticated) {
                    if (!managedGoogleSignInAvailable) {
                        Text(
                            "Google sign-in is unavailable in this development build. You can finish setup and use every local ARES feature without it.",
                            color = AresGold,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onSignInClick,
                            enabled = managedGoogleSignInAvailable,
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text("Sign in with Google", color = AresBackground, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { onExpandedChange(false) }) {
                            Text("Use ARES without Google")
                        }
                    }
                    Text(
                        "ARES identifies itself to Google, but your files stay in your account or the team folder you choose next. ARES requests access only to files it creates or you explicitly select.",
                        color = AresTextTertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (authState is AuthState.Error) {
                    Text(authState.message, color = AresError, style = MaterialTheme.typography.bodySmall)
                }

                if (authState is AuthState.Authenticated) {
                    if (driveDestination != null) {
                        Text("Drive destination ready", color = AresGreen, fontWeight = FontWeight.Bold)
                        Text(
                            "${driveDestination.displayName} · ${driveDestination.accountEmail}",
                            color = AresTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("Now choose where this workspace stores ARES files.", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            "ARES will only list files inside this destination. You can change it later without deleting either local or Drive data.",
                            color = AresTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { destinationMenuExpanded = true },
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
                                expanded = destinationMenuExpanded,
                                onDismissRequest = { destinationMenuExpanded = false },
                            ) {
                                DriveDestinationType.entries.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type.studentLabel()) },
                                        onClick = {
                                            destinationType = type
                                            destinationMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = destinationName,
                            onValueChange = { destinationName = it },
                            label = { Text("Destination name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (destinationType == DriveDestinationType.SHARED_FOLDER ||
                            destinationType == DriveDestinationType.SHARED_DRIVE
                        ) {
                            Text(
                                if (destinationType == DriveDestinationType.SHARED_DRIVE) {
                                    "Google will open a folder picker. Choose a folder inside the Shared Drive."
                                } else {
                                    "Google will open a folder picker. Choose the shared team folder so ARES receives access only to that folder."
                                },
                                color = AresTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = {
                                if (destinationType == DriveDestinationType.SHARED_FOLDER ||
                                    destinationType == DriveDestinationType.SHARED_DRIVE
                                ) {
                                    onPickExistingDestination(destinationType, destinationName)
                                } else {
                                    onConfigureDestination(destinationType, destinationName, null, null)
                                }
                            },
                            enabled = !isDriveDestinationBusy && destinationName.isNotBlank(),
                        ) {
                            Text(
                                if (destinationType == DriveDestinationType.SHARED_FOLDER ||
                                    destinationType == DriveDestinationType.SHARED_DRIVE
                                ) "Choose folder in Google Drive" else "Create this destination",
                            )
                        }
                        driveDestinationError?.let { error ->
                            Text(error, color = AresError, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun DriveDestinationType.studentLabel(): String = when (this) {
    DriveDestinationType.PERSONAL_FOLDER -> "Personal Drive folder"
    DriveDestinationType.TEAM_FOLDER -> "Create a team folder"
    DriveDestinationType.SHARED_FOLDER -> "Join an existing shared folder"
    DriveDestinationType.SHARED_DRIVE -> "Google Shared Drive"
}
