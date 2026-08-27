package com.ares.analytics.ui.components.core

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresSurfaceElevated

/**
 * Standardized dashboard card container for ARES-Analytics.
 * Provides unified rounded corners, border styling, surface elevation, and internal padding.
 *
 * @param modifier Custom modifier for sizing and placement.
 * @param backgroundColor Container background color (defaults to AresSurfaceElevated).
 * @param borderColor Border color (defaults to AresBorder).
 * @param contentPadding Outer content padding inside the card.
 * @param content Composable content lambda scoped within Column.
 */
@Composable
fun AnalyticsCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = AresSurfaceElevated,
    borderColor: Color = AresBorder,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}
