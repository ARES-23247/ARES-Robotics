package com.ares.analytics.ui.components.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Standardized modal dialog container for ARES-Analytics with header, custom body content, and action buttons.
 */
@Composable
fun AresDialog(
    title: String,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    confirmText: String? = "Save",
    onConfirm: (() -> Unit)? = null,
    isConfirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                }
                Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        },
        confirmButton = {
            if (confirmText != null && onConfirm != null) {
                Button(
                    onClick = onConfirm,
                    enabled = isConfirmEnabled,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)
                ) {
                    Text(confirmText, color = AresBackground, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextSecondary)
            ) {
                Text("Cancel")
            }
        },
        containerColor = AresSurface,
        shape = RoundedCornerShape(12.dp)
    )
}
