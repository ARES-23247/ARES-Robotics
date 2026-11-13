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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.domain.navigation.buildContextualBreadcrumbs
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresCyanGlow
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

enum class SectionNavigationPresentation { TABS, MENU }

private val contextualSubScreenTargets = setOf(
    NavigationTarget.PROJECT_IDENTITY,
    NavigationTarget.DRIVEBASE_BUILDER,
    NavigationTarget.SUBSYSTEM_GEN,
    NavigationTarget.HARDWARE_SETUP,
)

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
    modifier: Modifier = Modifier,
    subContextLabel: String? = null,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    onNavigateBack: (() -> Unit)? = null,
    onNavigateForward: (() -> Unit)? = null,
) {
    val section = activeTarget.section()
    val targets = section?.targets().orEmpty()
    val title = section?.label ?: activeTarget.groupLabel()
    val cleanSubContext = subContextLabel?.trim()?.takeIf(String::isNotBlank)
    val showBreadcrumbTrail = cleanSubContext != null || activeTarget in contextualSubScreenTargets || section == null

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresBorder.copy(alpha = 0.55f)),
    ) {
        BoxWithConstraints {
            val reserveForHistory = if (onNavigateBack != null || onNavigateForward != null) 72f else 0f
            val useMenu = sectionNavigationPresentation(
                availableWidthDp = (maxWidth.value - reserveForHistory).coerceAtLeast(120f),
                destinationCount = targets.size,
            ) == SectionNavigationPresentation.MENU

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onNavigateBack != null || onNavigateForward != null) {
                    NavigationHistoryButtons(
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        onNavigateBack = onNavigateBack,
                        onNavigateForward = onNavigateForward,
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        showBreadcrumbTrail -> {
                            ContextualBreadcrumbRow(
                                sectionTitle = title,
                                sectionDefaultTarget = section?.defaultTarget(),
                                activeTarget = activeTarget,
                                subContextLabel = cleanSubContext,
                                siblingTargets = targets,
                                onNavigate = onNavigate,
                            )
                        }
                        useMenu -> {
                            SectionDestinationMenu(
                                title = title,
                                activeTarget = activeTarget,
                                targets = targets,
                                onNavigate = onNavigate,
                            )
                        }
                        else -> {
                            Row(
                                Modifier.fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    section?.icon ?: activeTarget.icon,
                                    null,
                                    tint = AresCyan,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                if (targets.size > 1) {
                                    Spacer(Modifier.size(3.dp))
                                    targets.forEach { target ->
                                        val selected = target == activeTarget
                                        Row(
                                            Modifier.clip(RoundedCornerShape(7.dp))
                                                .background(if (selected) AresCyanGlow else Color.Transparent)
                                                .clickable { onNavigate(target) }
                                                .padding(horizontal = 9.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Icon(
                                                target.icon,
                                                null,
                                                tint = if (selected) AresCyan else AresTextTertiary,
                                                modifier = Modifier.size(15.dp),
                                            )
                                            Text(
                                                target.label,
                                                color = if (selected) AresCyan else AresTextSecondary,
                                                fontSize = 11.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NavigationHistoryButtons(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onNavigateBack: (() -> Unit)?,
    onNavigateForward: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        IconButton(
            onClick = { onNavigateBack?.invoke() },
            enabled = canGoBack && onNavigateBack != null,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Navigate back (Alt+Left)",
                tint = if (canGoBack) AresTextPrimary else AresTextTertiary.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(
            onClick = { onNavigateForward?.invoke() },
            enabled = canGoForward && onNavigateForward != null,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Navigate forward (Alt+Right)",
                tint = if (canGoForward) AresTextPrimary else AresTextTertiary.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ContextualBreadcrumbRow(
    sectionTitle: String,
    sectionDefaultTarget: NavigationTarget?,
    activeTarget: NavigationTarget,
    subContextLabel: String?,
    siblingTargets: List<NavigationTarget>,
    onNavigate: (NavigationTarget) -> Unit,
) {
    val segments = remember(sectionTitle, sectionDefaultTarget, activeTarget, subContextLabel) {
        buildContextualBreadcrumbs(
            sectionLabel = sectionTitle,
            parentTarget = sectionDefaultTarget,
            activeTarget = activeTarget,
            subContextLabel = subContextLabel,
        )
    }
    var siblingsExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(activeTarget.icon, null, tint = AresCyan, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(2.dp))
            segments.forEachIndexed { idx, segment ->
                if (idx > 0) {
                    Text("/", color = AresTextTertiary, fontSize = 12.sp)
                }
                val target = segment.target
                val clickable = target != null && (!segment.isCurrent || target != activeTarget)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (segment.isCurrent) AresCyanGlow else Color.Transparent)
                        .let { mod ->
                            if (clickable && target != null) mod.clickable { onNavigate(target) } else mod
                        }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = segment.label,
                        color = when {
                            segment.isCurrent -> AresCyan
                            clickable -> AresTextPrimary
                            else -> AresTextSecondary
                        },
                        fontSize = 12.sp,
                        fontWeight = if (segment.isCurrent) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        if (siblingTargets.size > 1) {
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { siblingsExpanded = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(sectionTitle, color = AresTextSecondary, fontSize = 11.sp)
                    Icon(Icons.Default.ArrowDropDown, "Switch $sectionTitle view", tint = AresTextSecondary, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(
                    expanded = siblingsExpanded,
                    onDismissRequest = { siblingsExpanded = false },
                    modifier = Modifier.width(240.dp).background(AresSurfaceElevated),
                ) {
                    siblingTargets.forEach { target ->
                        DropdownMenuItem(
                            text = { Text(target.label, color = if (target == activeTarget) AresCyan else AresTextPrimary) },
                            leadingIcon = { Icon(target.icon, null, tint = if (target == activeTarget) AresCyan else AresTextSecondary) },
                            onClick = {
                                siblingsExpanded = false
                                onNavigate(target)
                            },
                        )
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

/** Global destinations that keep core authoring, pit, and analytics workflows one click away. */
val shellQuickDestinations = listOf(
    NavigationTarget.ROBOT_STUDIO,
    NavigationTarget.PIT_DIAGNOSTICS,
    NavigationTarget.ACADEMY,
    NavigationTarget.PATH_PLANNER,
    NavigationTarget.MATCH_STRATEGY,
    NavigationTarget.RUN_HISTORY,
    NavigationTarget.ENGINEERING_NOTEBOOK,
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
