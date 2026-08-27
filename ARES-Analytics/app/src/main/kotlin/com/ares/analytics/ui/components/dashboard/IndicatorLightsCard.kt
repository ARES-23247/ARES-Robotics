package com.ares.analytics.ui.components.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.RobotLightingKind
import com.ares.analytics.service.RobotLightingReading
import com.ares.analytics.service.robotLightingTelemetry
import com.ares.analytics.ui.theme.*
import com.ares.analytics.util.IndicatorLightColorMapper
import com.ares.analytics.util.PrismColorMapper

internal fun lightingDisplayName(stableName: String): String = when (stableName.substringAfterLast('/')) {
    "primaryIndicator" -> "Left indicator"
    "secondaryIndicator" -> "Right indicator"
    "prismDriver" -> "Prism underbody"
    else -> stableName.substringAfterLast('/')
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replaceFirstChar { it.titlecase() }
}

/**
 * Dashboard widget card that displays every descriptor-owned lighting output and its accepted
 * hardware/simulator value in real time. Each output is shown with a representative color, its
 * stable generated name, device kind, and raw accepted value.
 *
 * Generated robots publish `Subsystems/<document>/AppliedOutputs/<hardware>/<kind>`. The legacy
 * `Superstructure/IndicatorLight/{name}` topic remains readable for recorded and hand-authored
 * robots, but new GUI-owned robots never need to duplicate their generated telemetry.
 */
@Composable
fun IndicatorLightsCard(
    nt4ClientService: Nt4ClientService,
    currentFrame: ReplayFrame? = null,
    modifier: Modifier = Modifier
) {
    val liveLighting by nt4ClientService.robotLighting.collectAsState()
    val displayedLights = remember(currentFrame?.sequence, liveLighting) {
        currentFrame?.let { robotLightingTelemetry(it.values).outputs } ?: liveLighting.outputs
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = AresAmber
                )
                Text(
                    "Robot Lighting",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary
                )
            }

            // Count badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AresSurfaceElevated)
                    .border(0.5.dp, AresBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${displayedLights.size} output${if (displayedLights.size != 1) "s" else ""}",
                    fontSize = 11.sp,
                    color = AresTextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        HorizontalDivider(color = AresBorder, thickness = 1.dp)

        if (displayedLights.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (currentFrame == null) "No lighting outputs detected" else "No lighting outputs in this recording",
                        fontSize = 13.sp,
                        color = AresTextTertiary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (currentFrame == null) {
                            "Waiting for generated subsystem lighting telemetry…"
                        } else {
                            "This is missing data, not an off-light reading."
                        },
                        fontSize = 11.sp,
                        color = AresTextTertiary.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            // Light rows
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for ((name, reading) in displayedLights.toSortedMap()) {
                    LightingOutputRow(name = name, reading = reading)
                }
            }
        }
    }
}

@Composable
private fun LightingOutputRow(
    name: String,
    reading: RobotLightingReading,
) {
    val displayColor = when (reading.kind) {
        RobotLightingKind.INDICATOR -> IndicatorLightColorMapper.positionToColor(reading.value)
        RobotLightingKind.PRISM -> PrismColorMapper.pulseWidthToColor(reading.value)
    }
    val colorName = when (reading.kind) {
        RobotLightingKind.INDICATOR -> IndicatorLightColorMapper.positionToName(reading.value)
        RobotLightingKind.PRISM -> "Prism program"
    }

    // Smooth color transition animation
    val animatedColor by animateColorAsState(
        targetValue = displayColor,
        animationSpec = tween(durationMillis = 150),
        label = "indicatorColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AresSurfaceElevated)
            .border(0.5.dp, AresBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Glowing color indicator circle
        Box(
            modifier = Modifier
                .size(20.dp)
                .shadow(
                    elevation = if (displayColor.alpha > 0f) 6.dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = animatedColor.copy(alpha = 0.5f),
                    spotColor = animatedColor.copy(alpha = 0.8f)
                )
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedColor,
                            animatedColor.copy(alpha = 0.7f)
                        )
                    )
                )
                .border(1.5.dp, animatedColor.copy(alpha = 0.9f), CircleShape)
        )

        // Light name
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = lightingDisplayName(name),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AresTextPrimary
            )
            Text(
                text = when (reading.kind) {
                    RobotLightingKind.INDICATOR -> "$colorName • ${String.format("%.3f", reading.value)}"
                    RobotLightingKind.PRISM -> "$colorName • ${String.format("%.0f µs", reading.value)}"
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = animatedColor
            )
        }

    }
}
