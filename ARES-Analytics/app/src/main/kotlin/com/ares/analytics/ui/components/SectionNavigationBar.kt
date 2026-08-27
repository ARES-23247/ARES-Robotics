package com.ares.analytics.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresCyanGlow
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

enum class SectionNavigationPresentation { TABS, MENU }

/**
 * Chooses a discoverable menu before a tab row would clip or require invisible horizontal
 * scrolling. The estimate intentionally scales with destination count rather than screen size.
 */
fun sectionNavigationPresentation(
    availableWidthDp: Float,
    destinationCount: Int,
): SectionNavigationPresentation {
    if (destinationCount <= 1) return SectionNavigationPresentation.TABS
    val estimatedTabWidthDp = 120f + destinationCount * 115f
    return if (availableWidthDp < estimatedTabWidthDp) SectionNavigationPresentation.MENU
    else SectionNavigationPresentation.TABS
}

@Composable
fun SectionNavigationBar(
    activeTarget: NavigationTarget,
    onNavigate: (NavigationTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val section = activeTarget.section()
    val targets = section?.targets().orEmpty()
    val title = section?.label ?: activeTarget.groupLabel()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresBorder.copy(alpha = 0.55f))
    ) {
        BoxWithConstraints {
            val useMenu = sectionNavigationPresentation(maxWidth.value, targets.size) == SectionNavigationPresentation.MENU
            if (useMenu) {
                SectionDestinationMenu(
                    title = title,
                    activeTarget = activeTarget,
                    targets = targets,
                    onNavigate = onNavigate,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(section?.icon ?: activeTarget.icon, null, tint = AresCyan, modifier = Modifier.size(18.dp))
                    Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (targets.size > 1) {
                        Spacer(Modifier.size(3.dp))
                        targets.forEach { target ->
                            val selected = target == activeTarget
                            Row(
                                Modifier.clip(RoundedCornerShape(7.dp))
                                    .background(if (selected) AresCyanGlow else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { onNavigate(target) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(target.icon, null, tint = if (selected) AresCyan else AresTextTertiary, modifier = Modifier.size(15.dp))
                                Text(target.label, color = if (selected) AresCyan else AresTextSecondary, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                            }
                        }
                    } else if (section == null) {
                        Text("/", color = AresTextTertiary, modifier = Modifier.padding(horizontal = 3.dp))
                        Text(activeTarget.label, color = AresTextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionDestinationMenu(
    title: String,
    activeTarget: NavigationTarget,
    targets: List<NavigationTarget>,
    onNavigate: (NavigationTarget) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(activeTarget.icon, null, tint = AresCyan, modifier = Modifier.size(17.dp))
            Text(title, color = AresTextSecondary, fontSize = 11.sp)
            Text(activeTarget.label, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, "Open $title destinations", tint = AresTextSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(260.dp).background(AresSurfaceElevated),
        ) {
            targets.forEach { target ->
                DropdownMenuItem(
                    text = { Text(target.label, color = if (target == activeTarget) AresCyan else AresTextPrimary) },
                    leadingIcon = { Icon(target.icon, null, tint = if (target == activeTarget) AresCyan else AresTextSecondary) },
                    onClick = {
                        expanded = false
                        onNavigate(target)
                    },
                )
            }
        }
    }
}

/** Global destinations that replace the dashboard-only quick-jump strip. */
val shellQuickDestinations = listOf(
    NavigationTarget.ROBOT_STUDIO,
    NavigationTarget.ACADEMY,
    NavigationTarget.PATH_PLANNER,
    NavigationTarget.RUN_HISTORY,
    NavigationTarget.IMPORT_CENTER,
    NavigationTarget.TUNING,
)

@Composable
fun QuickNavigationMenu(
    onNavigate: (NavigationTarget) -> Unit,
    compact: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        if (compact) {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Bolt, "Quick jump", tint = AresCyan, modifier = Modifier.size(18.dp))
            }
        } else {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable { expanded = true }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Default.Bolt, null, tint = AresCyan, modifier = Modifier.size(17.dp))
                Text("Quick jump", color = AresTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ArrowDropDown, null, tint = AresTextSecondary, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(240.dp).background(AresSurfaceElevated),
        ) {
            shellQuickDestinations.forEach { target ->
                DropdownMenuItem(
                    text = { Text(target.label, color = AresTextPrimary) },
                    leadingIcon = { Icon(target.icon, null, tint = AresCyan) },
                    onClick = {
                        expanded = false
                        onNavigate(target)
                    },
                )
            }
        }
    }
}
