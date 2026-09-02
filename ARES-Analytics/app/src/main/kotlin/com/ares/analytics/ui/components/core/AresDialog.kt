package com.ares.analytics.ui.components.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*

/**
 * Visual variant styling for AresDialog actions.
 */
enum class AresDialogVariant {
    DEFAULT,
    DESTRUCTIVE,
    WARNING,
    INFO
}

/**
 * Standardized modal dialog container for ARES-Analytics with header, custom body content, and action buttons.
 */
@Composable
fun AresDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    variant: AresDialogVariant = AresDialogVariant.DEFAULT,
    confirmText: String? = "Save",
    onConfirm: (() -> Unit)? = null,
    isConfirmEnabled: Boolean = true,
    dismissText: String? = "Cancel",
    scrollable: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val (accentColor, confirmBtnContainer, confirmBtnContent) = when (variant) {
        AresDialogVariant.DEFAULT -> Triple(AresCyan, AresCyan, AresBackground)
        AresDialogVariant.DESTRUCTIVE -> Triple(AresError, AresError, AresTextPrimary)
        AresDialogVariant.WARNING -> Triple(AresGold, AresGold, AresBackground)
        AresDialogVariant.INFO -> Triple(AresCyan, AresCyan, AresBackground)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                }
                Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            val bodyModifier = if (scrollable) {
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            }
            Column(
                modifier = bodyModifier,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        },
        confirmButton = {
            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            } else if (confirmText != null && onConfirm != null) {
                Button(
                    onClick = onConfirm,
                    enabled = isConfirmEnabled,
                    colors = ButtonDefaults.buttonColors(containerColor = confirmBtnContainer, contentColor = confirmBtnContent)
                ) {
                    Text(confirmText, color = confirmBtnContent, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (actions == null && dismissText != null) {
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextSecondary)
                ) {
                    Text(dismissText)
                }
            }
        },
        containerColor = AresSurface,
        shape = RoundedCornerShape(12.dp)
    )
}
