package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

@Composable
internal fun FtcAutonomousRoutineSelector(
    expanded: Boolean,
    enabled: Boolean,
    availableAutos: List<String>,
    labels: Map<String, String>,
    selectedAuto: String?,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
) {
    Box(Modifier.width(180.dp)) {
        Surface(
            onClick = { if (enabled && availableAutos.isNotEmpty()) onExpandedChange(true) },
            modifier = Modifier.fillMaxWidth().height(32.dp),
            color = AresSurfaceElevated,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    selectedAuto?.let { labels[it] ?: it } ?: "Choose routine",
                    color = if (selectedAuto == null) AresTextSecondary else AresTextPrimary,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
                Icon(Icons.Default.ArrowDropDown, null, tint = AresTextSecondary, modifier = Modifier.size(17.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.background(AresSurfaceElevated),
        ) {
            availableAutos.forEach { autoId ->
                DropdownMenuItem(
                    text = { Text(labels[autoId] ?: autoId, color = AresTextPrimary) },
                    onClick = {
                        onSelected(autoId)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}
