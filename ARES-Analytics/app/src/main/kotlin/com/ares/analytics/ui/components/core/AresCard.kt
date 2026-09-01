package com.ares.analytics.ui.components.core

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresSurfaceElevated

/** Shared bordered surface for dashboard widgets and Studio editor sections. */
@Composable
fun AresCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = AresSurfaceElevated,
    borderColor: Color = AresBorder,
    cornerRadius: Dp = 12.dp,
    contentPadding: Dp = 16.dp,
    contentSpacing: Dp = 0.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, borderColor),
        shape = shape,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

/** Standard card treatment for Robot Studio editor sections. */
@Composable
fun AresEditorCard(
    modifier: Modifier = Modifier.fillMaxWidth(),
    contentPadding: Dp = 12.dp,
    contentSpacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    AresCard(
        modifier = modifier,
        cornerRadius = 10.dp,
        contentPadding = contentPadding,
        contentSpacing = contentSpacing,
        content = content,
    )
}
