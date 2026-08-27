package com.ares.analytics.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextTertiary

/**
 * Standardized glassmorphism card container for ARES-Analytics.
 * Provides unified rounded corners, border styling, surface elevation, optional clickability, and internal padding.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = AresSurfaceElevated,
    borderColor: Color = AresBorder,
    borderWidth: Dp = 1.dp,
    cornerRadius: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val containerModifier = modifier
        .clip(RoundedCornerShape(cornerRadius))
        .background(backgroundColor)
        .border(borderWidth, borderColor, RoundedCornerShape(cornerRadius))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(contentPadding)

    Column(
        modifier = containerModifier,
        content = content
    )
}

/**
 * Standardized card header with title, optional icon, and optional status badge.
 */
@Composable
fun CardHeader(
    title: String,
    icon: ImageVector? = null,
    iconTint: Color = AresCyan,
    statusText: String? = null,
    statusColor: Color = AresTextTertiary,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AresTextPrimary
            )
        }
        if (statusText != null) {
            StatusIndicatorPill(
                text = statusText,
                color = statusColor
            )
        }
    }
}
