package com.ares.analytics.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresOnAccent

@Composable
internal fun WorkspaceDeletionDialog(
    pendingWorkspace: Pair<String, String>?,
    onConfirm: (workspaceId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (workspaceId, displayName) = pendingWorkspace ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = AresError,
            )
        },
        title = { Text("Remove this workspace?") },
        text = {
            Text(
                "ARES will remove the saved workspace settings for $displayName. " +
                    "Your robot project files and imported run data will not be deleted.",
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(workspaceId) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AresError,
                    contentColor = AresOnAccent,
                ),
            ) {
                Text("Remove workspace")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep workspace") }
        },
    )
}
