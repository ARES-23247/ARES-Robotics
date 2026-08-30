package com.ares.analytics.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresCyanGlow
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.ui.theme.AresBrandDestination
import com.ares.analytics.ui.theme.openAresBrandDestination
import com.ares.analytics.ui.theme.rememberAresLogoPainter

// rememberPlainTooltipPositionProvider: the recommended
// rememberTooltipPositionProvider is not shipped by this Compose
// version; migrate at the next Compose bump.
@Suppress("DEPRECATION")
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun Sidebar(
    activeTarget: NavigationTarget,
    isConnected: Boolean,
    adbConnected: Boolean,
    isSimRunning: Boolean,
    league: League,
    onNavigate: (NavigationTarget) -> Unit,
    onOpenCommandPalette: () -> Unit,
    onToggleTerminal: () -> Unit
) {
    val activeSection = activeTarget.section()
    Column(
        modifier = Modifier.fillMaxHeight().width(88.dp).background(AresSurface)
            .border(width = 1.dp, color = AresBorder, shape = RoundedCornerShape(0.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Visit the ARES 23247 team website") } },
                state = rememberTooltipState(),
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    IconButton(
                        onClick = { openAresBrandDestination(AresBrandDestination.TEAM_WEBSITE) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Image(
                            painter = rememberAresLogoPainter(),
                            contentDescription = "ARES 23247 team logo — open team website",
                            modifier = Modifier.size(42.dp),
                        )
                    }
                    if (isSimRunning) {
                        Box(
                            Modifier
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(AresSurface)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(AresGreen),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            primaryNavigationSections.filter { it != NavigationSection.SETTINGS }.forEach { section ->
                SidebarSectionIcon(section, activeSection == section) { onNavigate(section.defaultTarget()) }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            UtilityButton("Find any screen (Ctrl+K)", Icons.Default.Search, activeTarget in developerToolTargets, onOpenCommandPalette)
            UtilityButton("Help & Learn", Icons.AutoMirrored.Filled.HelpOutline, activeTarget == NavigationTarget.ACADEMY) { onNavigate(NavigationTarget.ACADEMY) }
            UtilityButton("Terminal Console", Icons.Default.Terminal, false, onToggleTerminal)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                ConnectionIndicator(isConnected, "NT4")
                if (league == League.FTC) ConnectionIndicator(adbConnected, "ADB", AresCyan)
            }
            SidebarSectionIcon(NavigationSection.SETTINGS, activeSection == NavigationSection.SETTINGS) {
                onNavigate(NavigationSection.SETTINGS.defaultTarget())
            }
        }
    }
}

@Composable
private fun SidebarSectionIcon(section: NavigationSection, isActive: Boolean, onClick: () -> Unit) {
    val iconColor by animateColorAsState(if (isActive) AresCyan else AresTextTertiary, spring(stiffness = Spring.StiffnessMediumLow))
    val bgColor by animateColorAsState(if (isActive) AresCyanGlow else Color.Transparent, spring(stiffness = Spring.StiffnessMediumLow))
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(bgColor), contentAlignment = Alignment.Center) {
            Icon(section.icon, section.label, tint = iconColor, modifier = Modifier.size(21.dp))
        }
        Text(
            section.label,
            color = if (isActive) AresCyan else AresTextTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// rememberPlainTooltipPositionProvider: the recommended
// rememberTooltipPositionProvider is not shipped by this Compose
// version; migrate at the next Compose bump.
@Suppress("DEPRECATION")
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun UtilityButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(icon, label, tint = if (active) AresCyan else AresTextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

// rememberPlainTooltipPositionProvider: the recommended
// rememberTooltipPositionProvider is not shipped by this Compose
// version; migrate at the next Compose bump.
@Suppress("DEPRECATION")
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ConnectionIndicator(connected: Boolean, label: String, activeColor: Color = AresGreen) {
    val dotColor by animateColorAsState(if (connected) activeColor else AresTextTertiary, spring(stiffness = Spring.StiffnessMediumLow))
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("$label: ${if (connected) "Connected" else "Disconnected"}") } },
        state = rememberTooltipState()
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (connected) dotColor.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            Text(
                "$label ${if (connected) "on" else "off"}",
                color = if (connected) dotColor else AresTextTertiary,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
