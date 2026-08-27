package com.ares.analytics.ui.components.core

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresTextPrimary

/**
 * Standardized header layout for dashboard cards in ARES-Analytics.
 * Renders leading icon, title, optional status badge chip, and a bottom divider.
 *
 * @param title Header title string.
 * @param icon Optional leading icon ImageVector.
 * @param iconTint Color tint for the leading icon.
 * @param statusText Optional status text string for status badge.
 * @param statusColor Optional color for status badge.
 * @param showDivider Whether to render a horizontal divider below the header (defaults to true).
 * @param trailingContent Optional trailing custom composable content.
 */
@Composable
fun CardHeader(
    title: String,
    icon: ImageVector? = null,
    iconTint: Color = AresTextPrimary,
    statusText: String? = null,
    statusColor: Color? = null,
    showDivider: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (statusText != null && statusColor != null) {
                    StatusBadge(text = statusText, color = statusColor)
                }
                trailingContent?.invoke()
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = AresBorder
            )
        }
    }
}
