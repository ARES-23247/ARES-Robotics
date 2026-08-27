package com.ares.analytics.ui.components.core

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBackground

enum class PillVariant { FILLED, OUTLINED, TONAL }

/**
 * Standardized status indicator pill chip supporting icons, tonal tinting, and optional click handlers.
 */
@Composable
fun StatusIndicatorPill(
    text: String,
    color: Color,
    icon: ImageVector? = null,
    variant: PillVariant = PillVariant.TONAL,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(6.dp)
    val (bgColor, textColor, borderColor) = when {
        isSelected -> Triple(color, AresBackground, color)
        variant == PillVariant.FILLED -> Triple(color, AresBackground, color)
        variant == PillVariant.OUTLINED -> Triple(Color.Transparent, color, color)
        else -> Triple(color.copy(alpha = 0.15f), color, Color.Transparent)
    }

    Surface(
        modifier = modifier
            .clip(shape)
            .border(1.dp, if (isSelected) color else borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = bgColor,
        shape = shape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = text,
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    }
}
