package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresTextSecondary

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetFullscreenButton(isFullscreen: Boolean, onClick: () -> Unit) {
    val label = if (isFullscreen) "Restore to dashboard" else "Expand to full screen"
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = rememberTooltipState(),
        tooltip = { PlainTooltip { Text(label) } },
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
            Icon(
                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = label,
                tint = if (isFullscreen) AresCyan else AresTextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
