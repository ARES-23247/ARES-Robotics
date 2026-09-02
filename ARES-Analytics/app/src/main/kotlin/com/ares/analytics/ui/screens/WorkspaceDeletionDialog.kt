package com.ares.analytics.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.ares.analytics.ui.components.core.AresDialog
import com.ares.analytics.ui.components.core.AresDialogVariant
import com.ares.analytics.ui.theme.AresTextSecondary

@Composable
internal fun WorkspaceDeletionDialog(
    pendingWorkspace: Pair<String, String>?,
    onConfirm: (workspaceId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (workspaceId, displayName) = pendingWorkspace ?: return
    AresDialog(
        title = "Remove this workspace?",
        onDismiss = onDismiss,
        icon = Icons.Default.Delete,
        variant = AresDialogVariant.DESTRUCTIVE,
        confirmText = "Remove workspace",
        onConfirm = { onConfirm(workspaceId) },
        dismissText = "Keep workspace",
    ) {
        Text(
            "ARES will remove the saved workspace settings for $displayName. " +
                "Your robot project files and imported run data will not be deleted.",
            color = AresTextSecondary,
        )
    }
}
