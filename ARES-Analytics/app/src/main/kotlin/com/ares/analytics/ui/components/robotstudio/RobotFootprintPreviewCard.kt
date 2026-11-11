package com.ares.analytics.ui.components.robotstudio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState
import java.util.Locale
import kotlin.math.max

internal const val METERS_TO_INCHES = 39.37007874
internal const val FTC_SIZING_BOX_METERS = 0.4572 // 18.0 in
internal const val FRC_DEFAULT_BUMPER_THICKNESS_METERS = 0.08255 // 3.25 in per side
internal const val FRC_MAX_FRAME_PERIMETER_INCHES = 120.0

data class RobotFootprintSummary(
    val league: League,
    val outerLengthMeters: Double,
    val outerWidthMeters: Double,
    val outerLengthInches: Double,
    val outerWidthInches: Double,
    val outerPerimeterInches: Double,
    val bumperThicknessMeters: Double,
    val bumperThicknessInches: Double,
    val frameLengthMeters: Double,
    val frameWidthMeters: Double,
    val frameLengthInches: Double,
    val frameWidthInches: Double,
    val framePerimeterInches: Double,
    val hasValidDimensions: Boolean,
    val fitsLeagueSizingRule: Boolean,
    val badgeLabel: String,
    val ruleSummary: String,
    val clearanceDetail: String,
)

fun computeRobotFootprintSummary(
    league: League,
    outerLengthMeters: Double,
    outerWidthMeters: Double,
    bumperThicknessMetersOverride: Double? = null,
): RobotFootprintSummary {
    val valid = outerLengthMeters.isFinite() && outerWidthMeters.isFinite() &&
        outerLengthMeters > 0.0 && outerWidthMeters > 0.0
    val safeLengthM = if (valid) outerLengthMeters else 0.0
    val safeWidthM = if (valid) outerWidthMeters else 0.0
    val lengthIn = safeLengthM * METERS_TO_INCHES
    val widthIn = safeWidthM * METERS_TO_INCHES
    val outerPerimIn = 2.0 * (lengthIn + widthIn)

    val defaultBumperM = when (league) {
        League.FRC -> FRC_DEFAULT_BUMPER_THICKNESS_METERS
        League.FTC -> 0.0
        League.XRP -> 0.0
    }
    val rawBumperM = (bumperThicknessMetersOverride ?: defaultBumperM).coerceAtLeast(0.0)
    val maxPossibleBumperM = (minOf(safeLengthM, safeWidthM) * 0.35).coerceAtLeast(0.0)
    val effectiveBumperM = if (valid) minOf(rawBumperM, maxPossibleBumperM) else 0.0
    val bumperIn = effectiveBumperM * METERS_TO_INCHES

    val frameLengthM = (safeLengthM - 2.0 * effectiveBumperM).coerceAtLeast(0.0)
    val frameWidthM = (safeWidthM - 2.0 * effectiveBumperM).coerceAtLeast(0.0)
    val frameLengthIn = frameLengthM * METERS_TO_INCHES
    val frameWidthIn = frameWidthM * METERS_TO_INCHES
    val framePerimIn = 2.0 * (frameLengthIn + frameWidthIn)

    if (!valid) {
        return RobotFootprintSummary(
            league = league,
            outerLengthMeters = 0.0,
            outerWidthMeters = 0.0,
            outerLengthInches = 0.0,
            outerWidthInches = 0.0,
            outerPerimeterInches = 0.0,
            bumperThicknessMeters = 0.0,
            bumperThicknessInches = 0.0,
            frameLengthMeters = 0.0,
            frameWidthMeters = 0.0,
            frameLengthInches = 0.0,
            frameWidthInches = 0.0,
            framePerimeterInches = 0.0,
            hasValidDimensions = false,
            fitsLeagueSizingRule = false,
            badgeLabel = "ENTER VALID DIMENSIONS",
            ruleSummary = "Enter positive robot length and width in meters to preview the chassis footprint.",
            clearanceDetail = "Awaiting positive decimal values.",
        )
    }

    return when (league) {
        League.FTC -> {
            val fits = safeLengthM <= FTC_SIZING_BOX_METERS + 1e-4 &&
                safeWidthM <= FTC_SIZING_BOX_METERS + 1e-4
            val marginLengthIn = 18.0 - lengthIn
            val marginWidthIn = 18.0 - widthIn
            val badge = if (fits) "✓ FITS 18\" × 18\" FTC SIZING BOX" else "⚠ EXCEEDS 18\" × 18\" FTC SIZING BOX"
            val summary = "FTC Starting Sizing Box: 18.0\" × 18.0\" (0.457 m × 0.457 m)"
            val detail = if (fits) {
                String.format(
                    Locale.US,
                    "Clearance margin: %.1f\" length, %.1f\" width inside 18\" box",
                    marginLengthIn.coerceAtLeast(0.0),
                    marginWidthIn.coerceAtLeast(0.0),
                )
            } else {
                String.format(
                    Locale.US,
                    "Over limit by %.1f\" length, %.1f\" width (max 18.0\" × 18.0\")",
                    (-marginLengthIn).coerceAtLeast(0.0),
                    (-marginWidthIn).coerceAtLeast(0.0),
                )
            }
            RobotFootprintSummary(
                league = league,
                outerLengthMeters = safeLengthM,
                outerWidthMeters = safeWidthM,
                outerLengthInches = lengthIn,
                outerWidthInches = widthIn,
                outerPerimeterInches = outerPerimIn,
                bumperThicknessMeters = effectiveBumperM,
                bumperThicknessInches = bumperIn,
                frameLengthMeters = frameLengthM,
                frameWidthMeters = frameWidthM,
                frameLengthInches = frameLengthIn,
                frameWidthInches = frameWidthIn,
                framePerimeterInches = framePerimIn,
                hasValidDimensions = true,
                fitsLeagueSizingRule = fits,
                badgeLabel = badge,
                ruleSummary = summary,
                clearanceDetail = detail,
            )
        }
        League.FRC -> {
            val fits = framePerimIn <= FRC_MAX_FRAME_PERIMETER_INCHES + 0.05
            val badge = if (fits) "✓ FRC FRAME PERIMETER ≤ 120\"" else "⚠ EST. FRAME PERIMETER > 120\""
            val summary = String.format(
                Locale.US,
                "Est. Frame: %.1f\" × %.1f\" (%.1f\" perimeter) · Bumpers: %.2f\"/side",
                frameLengthIn,
                frameWidthIn,
                framePerimIn,
                bumperIn,
            )
            val detail = if (fits) {
                String.format(
                    Locale.US,
                    "Outer bumper perimeter: %.1f\" (%.2f m × %.2f m) · %.1f\" below 120\" frame reference",
                    outerPerimIn,
                    safeLengthM,
                    safeWidthM,
                    (FRC_MAX_FRAME_PERIMETER_INCHES - framePerimIn).coerceAtLeast(0.0),
                )
            } else {
                String.format(
                    Locale.US,
                    "Estimated frame perimeter (%.1f\") exceeds 120.0\" FRC reference by %.1f\"",
                    framePerimIn,
                    framePerimIn - FRC_MAX_FRAME_PERIMETER_INCHES,
                )
            }
            RobotFootprintSummary(
                league = league,
                outerLengthMeters = safeLengthM,
                outerWidthMeters = safeWidthM,
                outerLengthInches = lengthIn,
                outerWidthInches = widthIn,
                outerPerimeterInches = outerPerimIn,
                bumperThicknessMeters = effectiveBumperM,
                bumperThicknessInches = bumperIn,
                frameLengthMeters = frameLengthM,
                frameWidthMeters = frameWidthM,
                frameLengthInches = frameLengthIn,
                frameWidthInches = frameWidthIn,
                framePerimeterInches = framePerimIn,
                hasValidDimensions = true,
                fitsLeagueSizingRule = fits,
                badgeLabel = badge,
                ruleSummary = summary,
                clearanceDetail = detail,
            )
        }
        League.XRP -> {
            val fits = safeLengthM <= 0.50 && safeWidthM <= 0.50
            val badge = if (fits) "✓ XRP DESKTOP FOOTPRINT" else "⚠ LARGE XRP FOOTPRINT"
            val summary = String.format(
                Locale.US,
                "XRP Chassis: %.1f cm × %.1f cm (%.1f\" × %.1f\")",
                safeLengthM * 100.0,
                safeWidthM * 100.0,
                lengthIn,
                widthIn,
            )
            val detail = String.format(
                Locale.US,
                "Outer perimeter: %.1f\" · Standard XRP kit is ~19 cm × 19 cm (0.19 m)",
                outerPerimIn,
            )
            RobotFootprintSummary(
                league = league,
                outerLengthMeters = safeLengthM,
                outerWidthMeters = safeWidthM,
                outerLengthInches = lengthIn,
                outerWidthInches = widthIn,
                outerPerimeterInches = outerPerimIn,
                bumperThicknessMeters = effectiveBumperM,
                bumperThicknessInches = bumperIn,
                frameLengthMeters = frameLengthM,
                frameWidthMeters = frameWidthM,
                frameLengthInches = frameLengthIn,
                frameWidthInches = frameWidthIn,
                framePerimeterInches = framePerimIn,
                hasValidDimensions = true,
                fitsLeagueSizingRule = fits,
                badgeLabel = badge,
                ruleSummary = summary,
                clearanceDetail = detail,
            )
        }
    }
}

@Composable
fun RobotFootprintPreviewCard(
    league: League,
    robotLengthMetersText: String,
    robotWidthMetersText: String,
    bumperThicknessMetersOverride: Double? = null,
    modifier: Modifier = Modifier,
) {
    val lengthM = robotLengthMetersText.trim().toDoubleOrNull() ?: 0.0
    val widthM = robotWidthMetersText.trim().toDoubleOrNull() ?: 0.0
    val summary = remember(league, lengthM, widthM, bumperThicknessMetersOverride) {
        computeRobotFootprintSummary(league, lengthM, widthM, bumperThicknessMetersOverride)
    }

    val statusColor = when {
        !summary.hasValidDimensions -> AresGold
        summary.fitsLeagueSizingRule -> AresGreen
        else -> AresError
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: 2D Top-Down Schematic Canvas
            Surface(
                modifier = Modifier.size(164.dp),
                color = AresSurfaceElevated,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                    RobotFootprintCanvas(summary = summary)
                }
            }

            // Right: Dimension Readout & Sizing Rules
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "2D CHASSIS & BUMPER FOOTPRINT PREVIEW",
                        color = AresTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Surface(
                        color = statusColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, statusColor),
                    ) {
                        Text(
                            summary.badgeLabel,
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                if (summary.hasValidDimensions) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FootprintMetricPill(
                            label = "Outer Length (+X)",
                            value = String.format(Locale.US, "%.3f m (%.1f\")", summary.outerLengthMeters, summary.outerLengthInches),
                        )
                        FootprintMetricPill(
                            label = "Outer Width (+Y)",
                            value = String.format(Locale.US, "%.3f m (%.1f\")", summary.outerWidthMeters, summary.outerWidthInches),
                        )
                        FootprintMetricPill(
                            label = "Outer Perimeter",
                            value = String.format(Locale.US, "%.1f\" (%.2f m)", summary.outerPerimeterInches, summary.outerPerimeterInches / METERS_TO_INCHES),
                        )
                    }
                }

                Text(
                    summary.ruleSummary,
                    color = AresTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    summary.clearanceDetail,
                    color = if (summary.fitsLeagueSizingRule || !summary.hasValidDimensions) AresTextSecondary else AresError,
                    fontSize = 11.sp,
                )
                Text(
                    "Frame convention: +X Forward (arrow), +Y Left, +θ Counter-Clockwise",
                    color = AresTextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun FootprintMetricPill(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, color = AresTextTertiary, fontSize = 9.sp)
        Text(value, color = AresTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RobotFootprintCanvas(
    summary: RobotFootprintSummary,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)

        // Subtle center axes
        drawLine(
            color = AresBorder.copy(alpha = 0.6f),
            start = Offset(0f, center.y),
            end = Offset(w, center.y),
            strokeWidth = 1f,
        )
        drawLine(
            color = AresBorder.copy(alpha = 0.6f),
            start = Offset(center.x, 0f),
            end = Offset(center.x, h),
            strokeWidth = 1f,
        )

        if (!summary.hasValidDimensions) return@Canvas

        val referenceMaxM = when (summary.league) {
            League.FTC -> max(FTC_SIZING_BOX_METERS, max(summary.outerLengthMeters, summary.outerWidthMeters)) * 1.18
            League.FRC -> max(1.0, max(summary.outerLengthMeters, summary.outerWidthMeters)) * 1.18
            League.XRP -> max(0.25, max(summary.outerLengthMeters, summary.outerWidthMeters)) * 1.18
        }
        val pxPerMeter = (minOf(w, h) / referenceMaxM).toFloat()

        // FTC 18" sizing box reference outline (dashed)
        if (summary.league == League.FTC) {
            val boxPx = (FTC_SIZING_BOX_METERS * pxPerMeter).toFloat()
            drawRect(
                color = if (summary.fitsLeagueSizingRule) AresGold.copy(alpha = 0.65f) else AresError.copy(alpha = 0.85f),
                topLeft = Offset(center.x - boxPx / 2f, center.y - boxPx / 2f),
                size = Size(boxPx, boxPx),
                style = Stroke(
                    width = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f),
                ),
            )
        }

        // Robot outer footprint (top-down: +X forward is UP on canvas, +Y left is LEFT on canvas)
        // So canvas width = outerWidthMeters (Y), canvas height = outerLengthMeters (X)
        val outerW = (summary.outerWidthMeters * pxPerMeter).toFloat().coerceAtLeast(16f)
        val outerH = (summary.outerLengthMeters * pxPerMeter).toFloat().coerceAtLeast(16f)
        val outerTopLeft = Offset(center.x - outerW / 2f, center.y - outerH / 2f)

        val borderColor = if (summary.fitsLeagueSizingRule) AresCyan else AresError

        // Outer bumper fill & border
        drawRoundRect(
            color = borderColor.copy(alpha = 0.16f),
            topLeft = outerTopLeft,
            size = Size(outerW, outerH),
            cornerRadius = CornerRadius(6f, 6f),
        )
        drawRoundRect(
            color = borderColor,
            topLeft = outerTopLeft,
            size = Size(outerW, outerH),
            cornerRadius = CornerRadius(6f, 6f),
            style = Stroke(width = 2f),
        )

        // Inner frame perimeter for FRC (or inset chassis line for FTC/XRP)
        val insetPx = if (summary.bumperThicknessMeters > 0.0) {
            (summary.bumperThicknessMeters * pxPerMeter).toFloat().coerceAtMost(minOf(outerW, outerH) * 0.35f)
        } else {
            minOf(outerW, outerH) * 0.08f
        }
        val frameW = (outerW - 2f * insetPx).coerceAtLeast(8f)
        val frameH = (outerH - 2f * insetPx).coerceAtLeast(8f)
        val frameTopLeft = Offset(center.x - frameW / 2f, center.y - frameH / 2f)

        drawRect(
            color = AresSurface,
            topLeft = frameTopLeft,
            size = Size(frameW, frameH),
        )
        drawRect(
            color = AresTextSecondary.copy(alpha = 0.65f),
            topLeft = frameTopLeft,
            size = Size(frameW, frameH),
            style = Stroke(width = 1.2f),
        )

        // 4 Corner wheel/module indicators
        val wheelW = (frameW * 0.16f).coerceIn(4f, 10f)
        val wheelH = (frameH * 0.22f).coerceIn(6f, 14f)
        val wheelInsetX = wheelW * 0.4f
        val wheelInsetY = wheelH * 0.35f
        val wheelOffsets = listOf(
            Offset(frameTopLeft.x + wheelInsetX, frameTopLeft.y + wheelInsetY),
            Offset(frameTopLeft.x + frameW - wheelW - wheelInsetX, frameTopLeft.y + wheelInsetY),
            Offset(frameTopLeft.x + wheelInsetX, frameTopLeft.y + frameH - wheelH - wheelInsetY),
            Offset(frameTopLeft.x + frameW - wheelW - wheelInsetX, frameTopLeft.y + frameH - wheelH - wheelInsetY),
        )
        wheelOffsets.forEach { offset ->
            drawRoundRect(
                color = AresCyan.copy(alpha = 0.75f),
                topLeft = offset,
                size = Size(wheelW, wheelH),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }

        // Forward (+X) arrow pointing UP
        val arrowStart = Offset(center.x, center.y + frameH * 0.22f)
        val arrowTip = Offset(center.x, center.y - frameH * 0.28f)
        drawLine(
            color = AresGreen,
            start = arrowStart,
            end = arrowTip,
            strokeWidth = 2.5f,
        )
        val headSize = (minOf(frameW, frameH) * 0.14f).coerceIn(4f, 9f)
        val arrowHead = Path().apply {
            moveTo(arrowTip.x, arrowTip.y)
            lineTo(arrowTip.x - headSize, arrowTip.y + headSize * 1.2f)
            lineTo(arrowTip.x + headSize, arrowTip.y + headSize * 1.2f)
            close()
        }
        drawPath(arrowHead, color = AresGreen)
    }
}
