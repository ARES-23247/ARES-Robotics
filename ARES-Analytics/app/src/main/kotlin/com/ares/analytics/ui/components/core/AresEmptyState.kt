package com.ares.analytics.ui.components.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

/** Centered empty, idle, or unavailable content with optional icon and explanation. */
@Composable
fun AresEmptyState(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconSize: Dp = 40.dp,
    iconTint: Color = AresTextTertiary,
    titleColor: Color = AresTextSecondary,
    titleFontSize: TextUnit = 14.sp,
    titleFontWeight: FontWeight = FontWeight.Normal,
    descriptionColor: Color = AresTextTertiary,
    descriptionFontSize: TextUnit = 11.sp,
    descriptionModifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = verticalArrangement,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = titleColor, fontSize = titleFontSize, fontWeight = titleFontWeight)
            if (description != null) {
                Text(description, color = descriptionColor, fontSize = descriptionFontSize, modifier = descriptionModifier)
            }
        }
        content?.invoke(this)
    }
}
