package com.ares.analytics.ui.components.core

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresThemeSettings

/**
 * Shared shell for the canonical robot editors. Steps always receive their own horizontally
 * scrollable row, while identity and actions stack before either can crowd the center canvas.
 */
@Composable
fun ResponsiveBuilderHeader(
    identity: @Composable () -> Unit,
    steps: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresSurface,
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(8.dp),
    ) {
        BoxWithConstraints {
            val stacked = maxWidth < 760.dp || AresThemeSettings.largeTextMode
            val actionsScroll = rememberScrollState()
            val stepsScroll = rememberScrollState()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (stacked) {
                    identity()
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(actionsScroll),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        actions()
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) { identity() }
                        Row(
                            modifier = Modifier.horizontalScroll(actionsScroll),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            actions()
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(stepsScroll),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    steps()
                }
            }
        }
    }
}
