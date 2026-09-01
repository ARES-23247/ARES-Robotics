package com.ares.analytics.ui.components.pathplanner

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.*
import com.ares.analytics.util.IndicatorLightColorMapper
import com.ares.analytics.util.PrismColorMapper
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * Renders native auto destinations as physical robot footprints with one unambiguous heading.
 * Legacy tangent and holonomic-rotation handles intentionally do not appear in this mode.
 */
data class IndicatorLightRenderState(
    val position: Double,
    val forwardFraction: Double,
    val leftFraction: Double,
)

fun DrawScope.drawAutoGoals(
    pathCache: PathCacheHolder,
    waypoints: List<Waypoint>,
    selectedWaypointIndex: Int,
    playbackPose: Waypoint?,
    robotDimensions: RobotDimensions,
    w: Float,
    h: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League
) {
    val dimensions = robotDimensions.normalized()
    waypoints.forEachIndexed { index, waypoint ->
        drawAutoFootprint(
            pathCache = pathCache,
            pose = waypoint,
            dimensions = dimensions,
            color = when {
                index == selectedWaypointIndex -> AresAmber
                index == 0 -> AresCyan
                else -> AresGreen
            },
            fillAlpha = if (index == 0) 0.16f else 0.10f,
            w = w,
            h = h,
            fieldWidthM = fieldWidthM,
            fieldHeightM = fieldHeightM,
            league = league
        )
    }
    playbackPose?.let { pose ->
        drawAutoFootprint(
            pathCache = pathCache,
            pose = pose,
            dimensions = dimensions,
            color = AresGold,
            fillAlpha = 0.28f,
            w = w,
            h = h,
            fieldWidthM = fieldWidthM,
            fieldHeightM = fieldHeightM,
            league = league
        )
    }
}

private fun DrawScope.drawAutoFootprint(
    pathCache: PathCacheHolder,
    pose: Waypoint,
    dimensions: RobotDimensions,
    color: Color,
    fillAlpha: Float,
    w: Float,
    h: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League
) {
    val center = getCanvasOffsetBase(pose, w, h, fieldWidthM, fieldHeightM, league)
    val pixelsPerMeter = minOf(w / fieldWidthM.toFloat(), h / fieldHeightM.toFloat())
    val lengthPx = dimensions.lengthMeters.toFloat() * pixelsPerMeter
    val widthPx = dimensions.widthMeters.toFloat() * pixelsPerMeter
    val headingRadians = pose.rotationDeg?.let(Math::toRadians) ?: pose.headingRad ?: 0.0
    val canvasHeading = -Math.toDegrees(headingRadians).toFloat() - if (league == League.FTC) 90f else 0f

    rotate(canvasHeading, pivot = center) {
        val topLeft = Offset(center.x - lengthPx / 2f, center.y - widthPx / 2f)
        drawRect(color.copy(alpha = fillAlpha), topLeft, Size(lengthPx, widthPx))
        drawRect(
            color = color,
            topLeft = topLeft,
            size = Size(lengthPx, widthPx),
            style = Stroke(width = 2.dp.toPx())
        )
        val frontX = center.x + lengthPx / 2f
        drawLine(
            color = AresAmber,
            start = Offset(frontX, center.y - widthPx / 2f),
            end = Offset(frontX, center.y + widthPx / 2f),
            strokeWidth = 3.dp.toPx()
        )
        val arrowSize = minOf(lengthPx, widthPx) * 0.20f
        val arrow = pathCache.reusableArrowPath.apply {
            reset()
            moveTo(frontX + arrowSize, center.y)
            lineTo(frontX, center.y - arrowSize * 0.6f)
            lineTo(frontX, center.y + arrowSize * 0.6f)
            close()
        }
        drawPath(arrow, AresAmber)
        drawCircle(
            color = AresAmber,
            radius = 4.dp.toPx(),
            center = Offset(frontX + arrowSize, center.y)
        )
        drawCircle(
            color = AresBackground,
            radius = 1.5.dp.toPx(),
            center = Offset(frontX + arrowSize, center.y)
        )
    }
    drawCircle(color = color, radius = 5.dp.toPx(), center = center)
    drawCircle(color = AresBackground, radius = 2.dp.toPx(), center = center)
}

fun DrawScope.drawPlannedSpline(
    pathCache: PathCacheHolder,
    splinePoints: List<Waypoint>,
    waypoints: List<Waypoint>,
    w: Float,
    h: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League
) {
    val cachedSplinePath = pathCache.splinePath
    val splinePath = if (cachedSplinePath != null && pathCache.splinePoints === splinePoints && pathCache.w == w && pathCache.h == h) {
        cachedSplinePath
    } else {
        val path = pathCache.splinePath?.apply { reset() } ?: Path()
        if (splinePoints.isNotEmpty()) {
            val firstOffset = getCanvasOffsetBase(splinePoints.first(), w, h, fieldWidthM, fieldHeightM, league)
            path.moveTo(firstOffset.x, firstOffset.y)
            for (i in 1 until splinePoints.size) {
                val offset = getCanvasOffsetBase(splinePoints[i], w, h, fieldWidthM, fieldHeightM, league)
                path.lineTo(offset.x, offset.y)
            }
        }
        pathCache.splinePoints = splinePoints
        pathCache.w = w
        pathCache.h = h
        pathCache.splinePath = path
        path
    }
    if (waypoints.size >= 2) {
        drawPath(path = splinePath, color = AresPathPlanned, style = Stroke(width = 4f, pathEffect = pathCache.dashEffect10))
    }
}

fun DrawScope.drawActualPathAndDeviations(
    pathCache: PathCacheHolder,
    actualPath: List<Waypoint>,
    waypoints: List<Waypoint>,
    w: Float,
    h: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League,
    showDeviations: Boolean = false
) {
    val cachedActualPath = pathCache.actualPath
    val cachedPointsRemainAnExactPrefix =
        pathCache.actualPoints.size <= actualPath.size &&
            pathCache.actualPoints.indices.all { index -> pathCache.actualPoints[index] == actualPath[index] }
    val actualPathObj = if (cachedActualPath != null &&
        cachedPointsRemainAnExactPrefix &&
        pathCache.w == w && pathCache.h == h) {
        val path = cachedActualPath
        if (pathCache.actualLastDrawnIndex < actualPath.size - 1 && pathCache.actualLastDrawnIndex >= 0) {
            for (i in (pathCache.actualLastDrawnIndex + 1) until actualPath.size) {
                val offset = getCanvasOffsetBase(actualPath[i], w, h, fieldWidthM, fieldHeightM, league)
                path.lineTo(offset.x, offset.y)
            }
        } else if (pathCache.actualLastDrawnIndex == -1 && actualPath.isNotEmpty()) {
            val firstOffset = getCanvasOffsetBase(actualPath.first(), w, h, fieldWidthM, fieldHeightM, league)
            path.moveTo(firstOffset.x, firstOffset.y)
            for (i in 1 until actualPath.size) {
                val offset = getCanvasOffsetBase(actualPath[i], w, h, fieldWidthM, fieldHeightM, league)
                path.lineTo(offset.x, offset.y)
            }
        }
        pathCache.actualLastDrawnIndex = actualPath.size - 1
        pathCache.actualPoints = actualPath
        path
    } else {
        val path = Path()
        if (actualPath.isNotEmpty()) {
            val firstOffset = getCanvasOffsetBase(actualPath.first(), w, h, fieldWidthM, fieldHeightM, league)
            path.moveTo(firstOffset.x, firstOffset.y)
            for (i in 1 until actualPath.size) {
                val offset = getCanvasOffsetBase(actualPath[i], w, h, fieldWidthM, fieldHeightM, league)
                path.lineTo(offset.x, offset.y)
            }
        }
        pathCache.actualPoints = actualPath
        pathCache.actualLastDrawnIndex = actualPath.size - 1
        pathCache.w = w
        pathCache.h = h
        pathCache.actualPath = path
        path
    }

    if (actualPath.size >= 2) {
        drawPath(path = actualPathObj, color = AresPathActual, style = Stroke(width = 3f))

        if (showDeviations && waypoints.size >= 2) {

            val step = maxOf(1, actualPath.size / 20)
            for (i in 0 until actualPath.size step step) {
                val actualWp = actualPath[i]
                val actualOffset = getCanvasOffsetBase(actualWp, w, h, fieldWidthM, fieldHeightM, league)
                var closestWp = waypoints.first()
                var minDistance = Double.MAX_VALUE
                val wpSize = waypoints.size
                for (j in 0 until wpSize) {
                    val plannedWp = waypoints[j]
                    val dist = sqrt((actualWp.x - plannedWp.x).pow(2) + (actualWp.y - plannedWp.y).pow(2))
                    if (dist < minDistance) {
                        minDistance = dist
                        closestWp = plannedWp
                    }
                }
                val plannedOffset = getCanvasOffsetBase(closestWp, w, h, fieldWidthM, fieldHeightM, league)
                val deviationM = minDistance
                val deviationColor = when {
                    deviationM < 0.02 -> AresGreen
                    deviationM < 0.05 -> AresAmber
                    else -> AresRed
                }
                drawLine(color = deviationColor, start = actualOffset, end = plannedOffset, strokeWidth = 1.5f)
            }
        }
    }
}

fun DrawScope.drawContextPath(
    pathCache: PathCacheHolder,
    contextPath: List<Waypoint>,
    contextWaypoints: List<Waypoint>?,
    w: Float,
    h: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League
) {
    if (contextPath.size >= 2) {
        val path = pathCache.reusablePath.apply { reset() }
        val firstOffset = getCanvasOffsetBase(contextPath.first(), w, h, fieldWidthM, fieldHeightM, league)
        path.moveTo(firstOffset.x, firstOffset.y)
        for (i in 1 until contextPath.size) {
            val offset = getCanvasOffsetBase(contextPath[i], w, h, fieldWidthM, fieldHeightM, league)
            path.lineTo(offset.x, offset.y)
        }
        drawPath(
            path = path,
            color = AresPathPlanned.copy(alpha = 0.35f),
            style = Stroke(
                width = 3.dp.toPx(),
                pathEffect = pathCache.dashEffect5
            )
        )
    }

    if (contextWaypoints != null) {
        for (wp in contextWaypoints) {
            val offset = getCanvasOffsetBase(wp, w, h, fieldWidthM, fieldHeightM, league)
            drawCircle(
                color = AresPathPlanned.copy(alpha = 0.5f),
                radius = 5.dp.toPx(),
                center = offset
            )
            drawCircle(
                color = AresBackground.copy(alpha = 0.5f),
                radius = 2.dp.toPx(),
                center = offset
            )
        }
    }
}

fun DrawScope.drawRobotRepresentations(
    pathCache: PathCacheHolder,
    actualPath: List<Waypoint>,
    estimatedPose: Waypoint?,
    playbackPose: Waypoint?,
    visionPoses: List<Waypoint>,
    odomPose: Waypoint? = null,
    showTruePose: Boolean = true,
    showEkfPose: Boolean = true,
    showOdomPose: Boolean = true,
    showVisionPoses: Boolean = true,
    w: Float,
    h: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League,
    indicatorLights: List<IndicatorLightRenderState> = emptyList(),
    prismPulseWidthUs: Double? = null,
) {
    val activeRobotWp = actualPath.lastOrNull()
    val leagueHeadingOffset = if (league == League.FTC) 90f else 0f
    if (activeRobotWp != null && showTruePose) {
        val robotOffset = getCanvasOffsetBase(activeRobotWp, w, h, fieldWidthM, fieldHeightM, league)
        val robotSizePx = ((0.45 / fieldWidthM) * w).toFloat()

        drawContext.canvas.save()
        drawContext.transform.rotate(degrees = -Math.toDegrees(activeRobotWp.headingRad ?: 0.0).toFloat() - leagueHeadingOffset, pivot = robotOffset)

        // Prism is mounted as underbody lighting on Lightbot. Draw the accepted output beneath the
        // footprint so the chassis remains readable and a replay never hides pose evidence.
        prismPulseWidthUs?.let { pulseWidthUs ->
            val prismColor = PrismColorMapper.pulseWidthToColor(pulseWidthUs)
            if (prismColor.alpha > 0f) {
                val glowInset = robotSizePx * 0.13f
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(prismColor.copy(alpha = 0.38f), prismColor.copy(alpha = 0.08f)),
                        center = robotOffset,
                        radius = robotSizePx * 0.9f,
                    ),
                    topLeft = Offset(robotOffset.x - robotSizePx / 2 - glowInset, robotOffset.y - robotSizePx / 2 - glowInset),
                    size = Size(robotSizePx + glowInset * 2, robotSizePx + glowInset * 2),
                )
            }
        }
        drawRect(color = AresCyan.copy(alpha = 0.2f), topLeft = Offset(robotOffset.x - robotSizePx / 2, robotOffset.y - robotSizePx / 2), size = Size(robotSizePx, robotSizePx))
        drawRect(color = AresCyan, topLeft = Offset(robotOffset.x - robotSizePx / 2, robotOffset.y - robotSizePx / 2), size = Size(robotSizePx, robotSizePx), style = Stroke(width = 2.dp.toPx()))
        drawLine(color = AresAmber, start = Offset(robotOffset.x + robotSizePx / 2, robotOffset.y - robotSizePx / 2), end = Offset(robotOffset.x + robotSizePx / 2, robotOffset.y + robotSizePx / 2), strokeWidth = 3.dp.toPx())
        val arrowPath = pathCache.reusableArrowPath.apply {
            reset()
            moveTo(robotOffset.x + robotSizePx / 2, robotOffset.y - robotSizePx / 4)
            lineTo(robotOffset.x + robotSizePx / 2 + robotSizePx / 4, robotOffset.y)
            lineTo(robotOffset.x + robotSizePx / 2, robotOffset.y + robotSizePx / 4)
            close()
        }
        drawPath(path = arrowPath, color = AresAmber)

        // Two independently controlled side-mounted indicator lamps. Array order is stable because
        // the caller sorts semantic generated hardware IDs before rendering.
        indicatorLights.forEach { indicator ->
            if (indicator.position < 0.0) return@forEach
            val lightColor = IndicatorLightColorMapper.positionToColor(indicator.position)
            val lightSize = robotSizePx * 0.18f
            val lightCenter = Offset(
                robotOffset.x + (indicator.forwardFraction * robotSizePx).toFloat(),
                robotOffset.y - (indicator.leftFraction * robotSizePx).toFloat(),
            )
            val lightTopLeft = Offset(lightCenter.x - lightSize / 2, lightCenter.y - lightSize / 2)
            drawRect(
                color = lightColor.copy(alpha = 0.85f),
                topLeft = lightTopLeft,
                size = Size(lightSize, lightSize),
                style = Fill
            )
            // Border
            drawRect(
                color = lightColor,
                topLeft = lightTopLeft,
                size = Size(lightSize, lightSize),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        drawContext.canvas.restore()
    }

    if (estimatedPose != null && showEkfPose) {
        val robotOffset = getCanvasOffsetBase(estimatedPose, w, h, fieldWidthM, fieldHeightM, league)
        val robotSizePx = ((0.45 / fieldWidthM) * w).toFloat()

        drawContext.canvas.save()
        drawContext.transform.rotate(degrees = -Math.toDegrees(estimatedPose.headingRad ?: 0.0).toFloat() - leagueHeadingOffset, pivot = robotOffset)

        // Draw Limelight FOV Cone projecting forward (+X robot-relative points RIGHT in this rotated context)
        val cameraOffsetPx = ((0.18 / fieldWidthM) * w).toFloat()
        val rangePx = ((4.0 / fieldWidthM) * w).toFloat()
        val fovCenter = Offset(robotOffset.x + cameraOffsetPx, robotOffset.y)

        drawArc(
            color = AresGold.copy(alpha = 0.08f),
            startAngle = -30f,
            sweepAngle = 60f,
            useCenter = true,
            topLeft = Offset(fovCenter.x - rangePx, fovCenter.y - rangePx),
            size = Size(rangePx * 2f, rangePx * 2f)
        )
        drawArc(
            color = AresGold.copy(alpha = 0.25f),
            startAngle = -30f,
            sweepAngle = 60f,
            useCenter = true,
            topLeft = Offset(fovCenter.x - rangePx, fovCenter.y - rangePx),
            size = Size(rangePx * 2f, rangePx * 2f),
            style = Stroke(width = 1.dp.toPx(), pathEffect = pathCache.dashEffect4)
        )

        drawRect(color = AresAmber.copy(alpha = 0.15f), topLeft = Offset(robotOffset.x - robotSizePx / 2, robotOffset.y - robotSizePx / 2), size = Size(robotSizePx, robotSizePx))
        drawRect(color = AresAmber, topLeft = Offset(robotOffset.x - robotSizePx / 2, robotOffset.y - robotSizePx / 2), size = Size(robotSizePx, robotSizePx), style = Stroke(width = 1.5.dp.toPx(), pathEffect = pathCache.dashEffect10))
        drawLine(color = AresAmber, start = Offset(robotOffset.x + robotSizePx / 2, robotOffset.y - robotSizePx / 2), end = Offset(robotOffset.x + robotSizePx / 2, robotOffset.y + robotSizePx / 2), strokeWidth = 2.dp.toPx())
        val arrowPath = pathCache.reusableArrowPath.apply {
            reset()
            moveTo(robotOffset.x + robotSizePx / 2, robotOffset.y - robotSizePx / 4)
            lineTo(robotOffset.x + robotSizePx / 2 + robotSizePx / 4, robotOffset.y)
            lineTo(robotOffset.x + robotSizePx / 2, robotOffset.y + robotSizePx / 4)
            close()
        }
        drawPath(path = arrowPath, color = AresAmber)
        drawContext.canvas.restore()
    }

    if (odomPose != null && showOdomPose) {
        val robotOffset = getCanvasOffsetBase(odomPose, w, h, fieldWidthM, fieldHeightM, league)
        val robotSizePx = ((0.45 / fieldWidthM) * w).toFloat()

        drawContext.canvas.save()
        drawContext.transform.rotate(degrees = -Math.toDegrees(odomPose.headingRad ?: 0.0).toFloat() - leagueHeadingOffset, pivot = robotOffset)
        drawRect(color = AresGreen.copy(alpha = 0.15f), topLeft = Offset(robotOffset.x - robotSizePx / 2, robotOffset.y - robotSizePx / 2), size = Size(robotSizePx, robotSizePx))
        drawRect(color = AresGreen, topLeft = Offset(robotOffset.x - robotSizePx / 2, robotOffset.y - robotSizePx / 2), size = Size(robotSizePx, robotSizePx), style = Stroke(width = 1.5.dp.toPx(), pathEffect = pathCache.dashEffect10))
        drawLine(color = AresGreen, start = Offset(robotOffset.x + robotSizePx / 2, robotOffset.y - robotSizePx / 2), end = Offset(robotOffset.x + robotSizePx / 2, robotOffset.y + robotSizePx / 2), strokeWidth = 2.dp.toPx())
        val arrowPath = pathCache.reusableArrowPath.apply {
            reset()
            moveTo(robotOffset.x + robotSizePx / 2, robotOffset.y - robotSizePx / 4)
            lineTo(robotOffset.x + robotSizePx / 2 + robotSizePx / 4, robotOffset.y)
            lineTo(robotOffset.x + robotSizePx / 2, robotOffset.y + robotSizePx / 4)
            close()
        }
        drawPath(path = arrowPath, color = AresGreen)
        drawContext.canvas.restore()
    }

    if (playbackPose != null) {
        val robotOffset = getCanvasOffsetBase(playbackPose, w, h, fieldWidthM, fieldHeightM, league)
        val robotSizePx = ((0.45 / fieldWidthM) * w).toFloat()

        drawContext.canvas.save()
        drawContext.transform.rotate(degrees = -Math.toDegrees(playbackPose.headingRad ?: 0.0).toFloat() - leagueHeadingOffset, pivot = robotOffset)
        drawRect(color = AresCyan.copy(alpha = 0.3f), topLeft = Offset(robotOffset.x - robotSizePx / 2, robotOffset.y - robotSizePx / 2), size = Size(robotSizePx, robotSizePx))
        drawRect(color = AresCyan, topLeft = Offset(robotOffset.x - robotSizePx / 2, robotOffset.y - robotSizePx / 2), size = Size(robotSizePx, robotSizePx), style = Stroke(width = 2.dp.toPx()))
        drawLine(color = AresCyan, start = Offset(robotOffset.x + robotSizePx / 2, robotOffset.y - robotSizePx / 2), end = Offset(robotOffset.x + robotSizePx / 2, robotOffset.y + robotSizePx / 2), strokeWidth = 3.dp.toPx())
        val arrowPath = pathCache.reusableArrowPath.apply {
            reset()
            moveTo(robotOffset.x + robotSizePx / 2, robotOffset.y - robotSizePx / 4)
            lineTo(robotOffset.x + robotSizePx / 2 + robotSizePx / 4, robotOffset.y)
            lineTo(robotOffset.x + robotSizePx / 2, robotOffset.y + robotSizePx / 4)
            close()
        }
        drawPath(path = arrowPath, color = AresCyan)
        drawContext.canvas.restore()
    }

    if (showVisionPoses) {
        visionPoses.forEach { pose ->
            val robotOffset = getCanvasOffsetBase(pose, w, h, fieldWidthM, fieldHeightM, league)
            val robotSizePx = ((0.45 / fieldWidthM) * w).toFloat()

            drawContext.canvas.save()
            drawContext.transform.rotate(degrees = -Math.toDegrees(pose.headingRad ?: 0.0).toFloat() - leagueHeadingOffset, pivot = robotOffset)
            drawRect(color = AresGold.copy(alpha = 0.15f), topLeft = Offset(robotOffset.x - robotSizePx / 2, robotOffset.y - robotSizePx / 2), size = Size(robotSizePx, robotSizePx))
            drawRect(color = AresGold, topLeft = Offset(robotOffset.x - robotSizePx / 2, robotOffset.y - robotSizePx / 2), size = Size(robotSizePx, robotSizePx), style = Stroke(width = 1.5.dp.toPx(), pathEffect = pathCache.dashEffect4))
            drawLine(color = AresGold, start = Offset(robotOffset.x + robotSizePx / 2, robotOffset.y - robotSizePx / 2), end = Offset(robotOffset.x + robotSizePx / 2, robotOffset.y + robotSizePx / 2), strokeWidth = 2.dp.toPx())
            val arrowPath = pathCache.reusableArrowPath.apply {
                reset()
                moveTo(robotOffset.x + robotSizePx / 2, robotOffset.y - robotSizePx / 4)
                lineTo(robotOffset.x + robotSizePx / 2 + robotSizePx / 4, robotOffset.y)
                lineTo(robotOffset.x + robotSizePx / 2, robotOffset.y + robotSizePx / 4)
                close()
            }
            drawPath(path = arrowPath, color = AresGold)
            drawContext.canvas.restore()
        }
    }
}


fun DrawScope.drawWaypoints(
    pathCache: PathCacheHolder,
    waypoints: List<Waypoint>,
    selectedWaypointIndex: Int,
    isDraggingHeading: Boolean,
    isDraggingPrevHeading: Boolean,
    w: Float,
    h: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League
) {
    waypoints.forEachIndexed { idx, wp ->
        val offset = getCanvasOffsetBase(wp, w, h, fieldWidthM, fieldHeightM, league)
        val isSelected = idx == selectedWaypointIndex
        val color = if (isSelected) AresCyan else AresTextPrimary

        // --- Tangent heading handle (amber arrowhead) ---
        val resolvedHeading = resolveHeading(waypoints, idx)
        val hasExplicitHeading = wp.headingRad != null
        val handleMeters = Waypoint(wp.x + wp.nextControlLength * cos(resolvedHeading), wp.y + wp.nextControlLength * sin(resolvedHeading))
        val arrowEnd = getCanvasOffsetBase(handleMeters, w, h, fieldWidthM, fieldHeightM, league)

        // Tangent line
        val tangentAlpha = when {
            !hasExplicitHeading && !isSelected -> 0.15f  // auto heading, not selected: very dim
            !hasExplicitHeading -> 0.35f                  // auto heading, selected: dim
            isSelected -> 0.9f                            // explicit, selected: bright
            else -> 0.4f                                  // explicit, not selected
        }
        drawLine(color = color.copy(alpha = tangentAlpha), start = offset, end = arrowEnd, strokeWidth = if (hasExplicitHeading) 2.dp.toPx() else 1.dp.toPx())

        // Arrowhead at end of tangent line
        val handleColor = when {
            isSelected && isDraggingHeading -> AresCyan
            !hasExplicitHeading -> Color.Gray    // ghost color for auto heading
            else -> AresAmber
        }
        val handleAlpha = when {
            !hasExplicitHeading && !isSelected -> 0.2f
            !hasExplicitHeading -> 0.4f
            isSelected -> 1f
            else -> 0.5f
        }
        val arrowSize = if (isSelected) 8.dp.toPx() else 6.dp.toPx()
        val dx = arrowEnd.x - offset.x
        val dy = arrowEnd.y - offset.y
        val len = sqrt(dx * dx + dy * dy)
        if (len > 1f) {
            val ux = dx / len; val uy = dy / len
            val perpX = -uy; val perpY = ux
            val arrowPath = pathCache.reusableArrowPath.apply {
                reset()
                moveTo(arrowEnd.x, arrowEnd.y)
                lineTo(arrowEnd.x - ux * arrowSize * 1.8f + perpX * arrowSize, arrowEnd.y - uy * arrowSize * 1.8f + perpY * arrowSize)
                lineTo(arrowEnd.x - ux * arrowSize * 0.8f, arrowEnd.y - uy * arrowSize * 0.8f)
                lineTo(arrowEnd.x - ux * arrowSize * 1.8f - perpX * arrowSize, arrowEnd.y - uy * arrowSize * 1.8f - perpY * arrowSize)
                close()
            }
            drawPath(path = arrowPath, color = handleColor.copy(alpha = handleAlpha))
            drawPath(path = arrowPath, color = handleColor, style = Stroke(width = 1.5f))
        } else {
            drawCircle(color = handleColor.copy(alpha = handleAlpha), radius = arrowSize, center = arrowEnd)
        }

        // --- Prev tangent heading handle ---
        val prevHandleMeters = Waypoint(wp.x + wp.prevControlLength * cos(resolvedHeading + Math.PI), wp.y + wp.prevControlLength * sin(resolvedHeading + Math.PI))
        val prevArrowEnd = getCanvasOffsetBase(prevHandleMeters, w, h, fieldWidthM, fieldHeightM, league)

        drawLine(color = color.copy(alpha = tangentAlpha), start = offset, end = prevArrowEnd, strokeWidth = if (hasExplicitHeading) 2.dp.toPx() else 1.dp.toPx())
        val prevHandleColor = when {
            isSelected && isDraggingPrevHeading -> AresCyan
            !hasExplicitHeading -> Color.Gray
            else -> AresAmber
        }
        val prevDx = prevArrowEnd.x - offset.x
        val prevDy = prevArrowEnd.y - offset.y
        val prevLen = sqrt(prevDx * prevDx + prevDy * prevDy)
        if (prevLen > 1f) {
            val ux = prevDx / prevLen; val uy = prevDy / prevLen
            val perpX = -uy; val perpY = ux
            val arrowPath = pathCache.reusableArrowPath.apply {
                reset()
                moveTo(prevArrowEnd.x, prevArrowEnd.y)
                lineTo(prevArrowEnd.x - ux * arrowSize * 1.8f + perpX * arrowSize, prevArrowEnd.y - uy * arrowSize * 1.8f + perpY * arrowSize)
                lineTo(prevArrowEnd.x - ux * arrowSize * 0.8f, prevArrowEnd.y - uy * arrowSize * 0.8f)
                lineTo(prevArrowEnd.x - ux * arrowSize * 1.8f - perpX * arrowSize, prevArrowEnd.y - uy * arrowSize * 1.8f - perpY * arrowSize)
                close()
            }
            drawPath(path = arrowPath, color = prevHandleColor.copy(alpha = handleAlpha))
            drawPath(path = arrowPath, color = prevHandleColor, style = Stroke(width = 1.5f))
        } else {
            drawCircle(color = prevHandleColor.copy(alpha = handleAlpha), radius = arrowSize, center = prevArrowEnd)
        }

        // --- Waypoint center dot (drawn on top) ---
        drawCircle(color = color, radius = 8.dp.toPx(), center = offset)
        drawCircle(color = AresBackground, radius = 4.dp.toPx(), center = offset)

    }
}
