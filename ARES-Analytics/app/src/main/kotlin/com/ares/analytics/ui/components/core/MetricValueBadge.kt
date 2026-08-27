package com.ares.analytics.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*

enum class MetricBadgeSize { SMALL, MEDIUM, LARGE }

/**
 * Standardized metric display badge card with label, value, optional unit, and status coloring.
 */
@Composable
fun MetricValueBadge(
    label: String,
    value: String,
    unit: String? = null,
    subtext: String? = null,
    statusColor: Color = AresTextPrimary,
    size: MetricBadgeSize = MetricBadgeSize.MEDIUM,
    modifier: Modifier = Modifier
) {
    val (labelSize, valueSize, padding) = when (size) {
        MetricBadgeSize.SMALL -> Triple(9.sp, 13.sp, PaddingValues(vertical = 4.dp, horizontal = 6.dp))
        MetricBadgeSize.MEDIUM -> Triple(10.sp, 18.sp, PaddingValues(vertical = 6.dp, horizontal = 8.dp))
        MetricBadgeSize.LARGE -> Triple(11.sp, 24.sp, PaddingValues(vertical = 8.dp, horizontal = 12.dp))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(AresSurfaceElevated)
            .border(0.5.dp, AresBorder, RoundedCornerShape(6.dp))
            .padding(padding)
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label.uppercase(),
                fontSize = labelSize,
                color = AresTextTertiary,
                fontWeight = FontWeight.Bold
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = value,
                    fontSize = valueSize,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    fontFamily = FontFamily.Monospace
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        fontSize = labelSize,
                        color = AresTextTertiary,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
            if (subtext != null) {
                Text(
                    text = subtext,
                    fontSize = labelSize,
                    color = statusColor
                )
            }
        }
    }
}
