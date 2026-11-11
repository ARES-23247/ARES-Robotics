package com.ares.analytics.ui.components.pathplanner

import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutineHeadingMode
import com.areslib.routine.RoutinePose
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Freehand Pen Stroke Simplifier (Ramer-Douglas-Peucker + Catmull-Rom $C^1$ Tangent Fitting)
 * and Swept Robot Footprint Collision Detector for the Studio FieldCanvas.
 */
object FreehandStrokeSplineFitter {

    /**
     * Simplifies a raw pointer-drag polyline using Ramer-Douglas-Peucker.
     */
    fun simplifyRamerDouglasPeucker(
        points: List<Waypoint>,
        epsilonMeters: Double = 0.08,
    ): List<Waypoint> {
        val finite = points.filter { it.x.isFinite() && it.y.isFinite() }
        if (finite.size <= 2) return finite
        return rdpSimplify(finite, epsilonMeters.coerceAtLeast(0.005))
    }

    /**
     * Simplifies a raw pointer-drag polyline into smooth editable [Waypoint] anchors with
     * Catmull-Rom $C^1$ tangent headings and proportional control handle lengths.
     */
    fun simplifyStroke(
        points: List<Waypoint>,
        epsilonMeters: Double = 0.08,
    ): List<Waypoint> {
        val finite = points.filter { it.x.isFinite() && it.y.isFinite() }
        if (finite.size <= 1) return finite

        // Dedup very close consecutive samples (< 1.5 cm)
        val deduped = ArrayList<Waypoint>(finite.size)
        for (pt in finite) {
            val prev = deduped.lastOrNull()
            if (prev == null || hypot(pt.x - prev.x, pt.y - prev.y) >= 0.015) {
                deduped.add(pt)
            }
        }
        if (deduped.size == 1 && finite.size >= 2) {
            val last = finite.last()
            if (hypot(last.x - deduped[0].x, last.y - deduped[0].y) > 1e-4) {
                deduped.add(last)
            }
        }
        if (deduped.size < 2) return deduped

        val simplified = rdpSimplify(deduped, epsilonMeters.coerceAtLeast(0.01))
        return attachSmoothTangents(simplified)
    }

    /**
     * Fits a freehand stroke into smooth [Waypoint] anchors, optionally anchoring the start of the
     * stroke to [startingWaypoint].
     */
    fun fitStrokeToWaypoints(
        stroke: List<Waypoint>,
        epsilonMeters: Double = 0.08,
        startingWaypoint: Waypoint? = null,
    ): List<Waypoint> {
        val combined = buildList {
            startingWaypoint?.let(::add)
            addAll(stroke)
        }
        return simplifyStroke(combined, epsilonMeters)
    }

    /**
     * Converts a freehand stroke into editable [RoutineDriveStep] entries starting from the
     * current last pose (if present). Interior anchors automatically enable `blendHandoff = true`
     * (`pathSkip`) and `RoutineHeadingMode.TANGENTIAL`; the final anchor sets `blendHandoff = false`.
     */
    fun strokeToRoutineDriveSteps(
        existingWaypoints: List<Waypoint>,
        strokePoints: List<Waypoint>,
        epsilonMeters: Double = 0.08,
        motionPresetKey: String = "fast",
    ): List<RoutineDriveStep> {
        val combined = buildList {
            existingWaypoints.lastOrNull()?.let(::add)
            addAll(strokePoints)
        }
        val simplified = simplifyStroke(combined, epsilonMeters)
        val newAnchors = if (existingWaypoints.isNotEmpty() && simplified.size >= 2) {
            simplified.drop(1)
        } else {
            simplified
        }
        if (newAnchors.isEmpty()) return emptyList()

        return newAnchors.mapIndexed { index, wp ->
            val isInterior = index < newAnchors.lastIndex
            val headingRad = wp.headingRad ?: 0.0
            RoutineDriveStep(
                target = RoutinePose(
                    xMeters = wp.x,
                    yMeters = wp.y,
                    headingRadians = headingRad,
                ),
                motionPresetKey = motionPresetKey,
                headingMode = RoutineHeadingMode.TANGENTIAL,
                blendHandoff = isInterior,
            )
        }
    }

    /**
     * Convenience overload for converting a standalone freehand stroke into [RoutineDriveStep] entries
     * where the first anchor in the stroke represents the starting pose and subsequent anchors are drive goals.
     */
    fun strokeToRoutineDriveSteps(
        stroke: List<Waypoint>,
        epsilonMeters: Double = 0.08,
        motionPresetKey: String = "fast",
    ): List<RoutineDriveStep> {
        if (stroke.size < 2) return emptyList()
        return strokeToRoutineDriveSteps(
            existingWaypoints = listOf(stroke.first()),
            strokePoints = stroke.drop(1),
            epsilonMeters = epsilonMeters,
            motionPresetKey = motionPresetKey,
        )
    }

    /**
     * Evaluates swept robot footprint (onion-skin) collisions along [trajectoryPoses] against [obstacles].
     * Returns the indices in [trajectoryPoses] whose oriented robot footprint intersects at least one obstacle.
     */
    fun findCollidingTrajectoryIndices(
        trajectoryPoses: List<Waypoint>,
        robotDimensions: RobotDimensions,
        obstacles: List<Obstacle>,
        stride: Int = 5,
    ): Set<Int> {
        if (trajectoryPoses.isEmpty() || obstacles.isEmpty()) return emptySet()
        val dims = robotDimensions.normalized()
        val halfL = dims.lengthMeters * 0.5
        val halfW = dims.widthMeters * 0.5
        val step = stride.coerceAtLeast(1)
        val colliding = LinkedHashSet<Int>()

        for (index in trajectoryPoses.indices) {
            if (index % step != 0 && index != trajectoryPoses.lastIndex) continue
            val pose = trajectoryPoses[index]
            if (!pose.x.isFinite() || !pose.y.isFinite()) continue
            val heading = pose.rotationDeg?.let(Math::toRadians) ?: pose.headingRad ?: resolveHeading(trajectoryPoses, index)
            if (footprintIntersectsObstacles(pose.x, pose.y, heading, halfL, halfW, obstacles)) {
                colliding.add(index)
            }
        }
        return colliding
    }

    /**
     * Evaluates swept robot footprint (onion-skin) collisions along [trajectoryPoints] against both
     * [obstacles] and the field perimeter (`fieldWidthM` × `fieldHeightM`).
     */
    fun findCollidingTrajectoryIndices(
        trajectoryPoints: List<Waypoint>,
        obstacles: List<Obstacle>,
        robotDimensions: RobotDimensions,
        fieldWidthM: Double,
        fieldHeightM: Double,
        league: League,
    ): Set<Int> {
        if (trajectoryPoints.isEmpty()) return emptySet()
        val dims = robotDimensions.normalized()
        val halfL = dims.lengthMeters * 0.5
        val halfW = dims.widthMeters * 0.5
        val centeredOrigin = league == League.FTC || league == League.XRP
        val minX = if (centeredOrigin) -fieldWidthM * 0.5 else 0.0
        val maxX = if (centeredOrigin) fieldWidthM * 0.5 else fieldWidthM
        val minY = if (centeredOrigin) -fieldHeightM * 0.5 else 0.0
        val maxY = if (centeredOrigin) fieldHeightM * 0.5 else fieldHeightM
        val colliding = LinkedHashSet<Int>()

        for (index in trajectoryPoints.indices) {
            val pose = trajectoryPoints[index]
            if (!pose.x.isFinite() || !pose.y.isFinite()) continue
            val heading = pose.rotationDeg?.let(Math::toRadians) ?: pose.headingRad ?: resolveHeading(trajectoryPoints, index)
            val cosH = abs(cos(heading))
            val sinH = abs(sin(heading))
            val extentX = halfL * cosH + halfW * sinH
            val extentY = halfL * sinH + halfW * cosH
            val outOfBounds = (pose.x - extentX < minX) || (pose.x + extentX > maxX) ||
                (pose.y - extentY < minY) || (pose.y + extentY > maxY)
            if (outOfBounds || footprintIntersectsObstacles(pose.x, pose.y, heading, halfL, halfW, obstacles)) {
                colliding.add(index)
            }
        }
        return colliding
    }

    internal fun footprintIntersectsObstacles(
        centerX: Double,
        centerY: Double,
        headingRad: Double,
        halfLength: Double,
        halfWidth: Double,
        obstacles: List<Obstacle>,
    ): Boolean {
        if (obstacles.isEmpty()) return false
        val cosH = cos(headingRad)
        val sinH = sin(headingRad)
        // Sample center, 4 corners, and 4 edge midpoints of the oriented robot footprint
        val localOffsets = arrayOf(
            0.0 to 0.0,
            halfLength to halfWidth,
            halfLength to -halfWidth,
            -halfLength to halfWidth,
            -halfLength to -halfWidth,
            halfLength to 0.0,
            -halfLength to 0.0,
            0.0 to halfWidth,
            0.0 to -halfWidth,
        )
        for (obstacle in obstacles) {
            for ((lx, ly) in localOffsets) {
                val wx = centerX + lx * cosH - ly * sinH
                val wy = centerY + lx * sinH + ly * cosH
                if (obstacle.distanceFromFilledShape(Waypoint(wx, wy)) <= 1e-3) {
                    return true
                }
            }
            // Also check whether a small obstacle is inside the robot OBB
            val obsCenter = when (obstacle) {
                is Obstacle.Circle -> Waypoint(obstacle.centerX, obstacle.centerY)
                is Obstacle.Rectangle -> Waypoint(obstacle.centerX, obstacle.centerY)
                is Obstacle.Polygon -> obstacle.vertices.firstOrNull()?.let { Waypoint(it.x, it.y) }
            }
            if (obsCenter != null) {
                val dx = obsCenter.x - centerX
                val dy = obsCenter.y - centerY
                val localX = abs(dx * cosH + dy * sinH)
                val localY = abs(-dx * sinH + dy * cosH)
                val extraRadius = (obstacle as? Obstacle.Circle)?.radius ?: 0.0
                if (localX <= halfLength + extraRadius && localY <= halfWidth + extraRadius) {
                    return true
                }
            }
        }
        return false
    }

    private fun rdpSimplify(points: List<Waypoint>, epsilon: Double): List<Waypoint> {
        if (points.size <= 2) return points
        val first = points.first()
        val last = points.last()
        var maxDist = -1.0
        var splitIndex = -1
        for (i in 1 until points.lastIndex) {
            val d = perpendicularDistance(points[i], first, last)
            if (d > maxDist) {
                maxDist = d
                splitIndex = i
            }
        }
        return if (maxDist > epsilon && splitIndex != -1) {
            val left = rdpSimplify(points.subList(0, splitIndex + 1), epsilon)
            val right = rdpSimplify(points.subList(splitIndex, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    private fun perpendicularDistance(pt: Waypoint, lineStart: Waypoint, lineEnd: Waypoint): Double {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        val length = hypot(dx, dy)
        if (length < 1e-9) return hypot(pt.x - lineStart.x, pt.y - lineStart.y)
        val t = (((pt.x - lineStart.x) * dx + (pt.y - lineStart.y) * dy) / (length * length)).coerceIn(0.0, 1.0)
        val projX = lineStart.x + t * dx
        val projY = lineStart.y + t * dy
        return hypot(pt.x - projX, pt.y - projY)
    }

    private fun attachSmoothTangents(points: List<Waypoint>): List<Waypoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return points
        return points.mapIndexed { idx, wp ->
            val heading = resolveHeading(points, idx)
            val prevDist = if (idx > 0) hypot(wp.x - points[idx - 1].x, wp.y - points[idx - 1].y) else 0.5
            val nextDist = if (idx < points.lastIndex) hypot(points[idx + 1].x - wp.x, points[idx + 1].y - wp.y) else prevDist
            val prevLen = (prevDist * 0.35).coerceIn(0.15, 1.2)
            val nextLen = (nextDist * 0.35).coerceIn(0.15, 1.2)
            wp.copy(
                headingRad = heading,
                rotationDeg = Math.toDegrees(heading),
                prevControlLength = prevLen,
                nextControlLength = nextLen,
            )
        }
    }
}
