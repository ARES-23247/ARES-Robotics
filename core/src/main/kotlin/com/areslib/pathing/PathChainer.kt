package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import kotlin.math.hypot
import com.areslib.math.wrapAngle
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Trajectory Path Segment Stitching and Velocity Continuity Chainer.
 *
 * Blends consecutive trajectory paths $[P_1, P_2, \dots, P_k]$ into a single continuous [Path],
 * re-parameterizing cumulative distance metrics $s(t)$ and maintaining velocity continuity across path junctions.
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y)$: Field-centric meters ($m$)
 * - Heading ($\theta$): Radians ($rad$), **CCW-positive**
 * - Velocity ($v$): Meters per second ($m/s$)
 * - Cumulative Distance ($s$): Meters ($m$)
 */
object PathChainer {

    /**
     * Chains a list of independent trajectory paths into a single continuous trajectory [Path].
     *
     * @param paths Ordered list of segment paths [Path].
     * @return Single concatenated [Path].
     */
    fun chainPaths(
        paths: List<Path>,
        maxVelocityMps: Double = 2.0,
        maxAccelerationMps2: Double = 1.5
    ): Path {
        require(maxVelocityMps.isFinite() && maxVelocityMps >= 0.0)
        require(maxAccelerationMps2.isFinite() && maxAccelerationMps2 > 0.0)
        val nonEmptyPaths = paths.filter { it.points.isNotEmpty() }
        if (nonEmptyPaths.isEmpty()) return Path(emptyList())
        if (nonEmptyPaths.size == 1) return nonEmptyPaths[0]

        val stitchedPoints = mutableListOf<PathPoint>()
        val stitchedEvents = mutableListOf<PathEvent>()

        // Initialize with first path
        stitchedPoints.addAll(nonEmptyPaths[0].points)
        stitchedEvents.addAll(nonEmptyPaths[0].events)

        for (pathIdx in 1 until nonEmptyPaths.size) {
            val nextPath = nonEmptyPaths[pathIdx]

            val distanceOffset = stitchedPoints.last().distanceMeters
            val jointDistance = distanceOffset
            val firstNextPoint = nextPath.points.first()
            val joinGap = hypot(
                firstNextPoint.pose.x - stitchedPoints.last().pose.x,
                firstNextPoint.pose.y - stitchedPoints.last().pose.y
            )
            require(joinGap < 0.05) {
                "Cannot chain discontinuous paths: segment $pathIdx starts ${"%.3f".format(joinGap)} m from the previous endpoint"
            }
            val localDistanceOrigin = firstNextPoint.distanceMeters
            val localToStitchedOffset = distanceOffset - localDistanceOrigin

            // Add events with offset
            for (event in nextPath.events) {
                stitchedEvents.add(
                    PathEvent(
                        event.eventName,
                        event.triggerDistanceMeters + localToStitchedOffset
                    )
                )
            }

            // Offset and add next points
            // Endpoints were verified to align, so drop the duplicate start point.
            val startIdx = 1

            for (i in startIdx until nextPath.points.size) {
                val pt = nextPath.points[i]
                stitchedPoints.add(
                    PathPoint(
                        pose = pt.pose,
                        velocityMps = pt.velocityMps,
                        distanceMeters = pt.distanceMeters + localToStitchedOffset,
                        curvature = pt.curvature,
                        tangentRadians = pt.tangentRadians
                    )
                )
            }

            // Perform blending around joint transition
            val blendWindow = 0.2 // meters
            val startBlendDist = jointDistance - blendWindow
            val endBlendDist = jointDistance + blendWindow

            // Joint anchor velocities/headings for interpolation
            val vA = stitchedPoints.firstOrNull { it.distanceMeters >= startBlendDist }?.velocityMps ?: maxVelocityMps
            val vB = stitchedPoints.firstOrNull { it.distanceMeters >= endBlendDist }?.velocityMps ?: maxVelocityMps

            val headingA = stitchedPoints.firstOrNull { it.distanceMeters >= startBlendDist }?.pose?.heading?.radians ?: 0.0
            val headingB = stitchedPoints.firstOrNull { it.distanceMeters >= endBlendDist }?.pose?.heading?.radians ?: 0.0

            for (idx in stitchedPoints.indices) {
                val pt = stitchedPoints[idx]
                if (pt.distanceMeters in startBlendDist..endBlendDist) {
                    val tau = (pt.distanceMeters - startBlendDist) / (2.0 * blendWindow)
                    
                    // Linear velocity interpolation
                    val blendedVel = (1.0 - tau) * vA + tau * vB

                    // Angle shortest-path interpolation
                    val diff = wrapAngle(headingB - headingA)
                    val blendedHeading = Rotation2d(headingA + diff * tau)

                    stitchedPoints[idx] = pt.copy(
                        pose = Pose2d(pt.pose.x, pt.pose.y, blendedHeading),
                        velocityMps = blendedVel
                    )
                }
            }
        }

        // Apply Trapezoidal Forward/Backward sweep on the fully stitched path to satisfy acceleration constraints
        if (stitchedPoints.isNotEmpty()) {
            // Sweep 1: Forward (Set start velocity to 0.0 to ramp up smoothly from standstill)
            stitchedPoints[0] = stitchedPoints[0].copy(velocityMps = 0.0)
            for (i in 1 until stitchedPoints.size) {
                val prev = stitchedPoints[i - 1]
                val curr = stitchedPoints[i]
                val ds = curr.distanceMeters - prev.distanceMeters
                if (ds > 1e-5) {
                    val maxReachable = sqrt(prev.velocityMps * prev.velocityMps + 2.0 * maxAccelerationMps2 * ds)
                    val newVel = min(curr.velocityMps, maxReachable)
                    stitchedPoints[i] = curr.copy(velocityMps = newVel)
                }
            }

            // Sweep 2: Backward (Ensure full deceleration at the very end point)
            stitchedPoints[stitchedPoints.size - 1] = stitchedPoints.last().copy(velocityMps = 0.0)
            for (i in stitchedPoints.size - 2 downTo 0) {
                val next = stitchedPoints[i + 1]
                val curr = stitchedPoints[i]
                val ds = next.distanceMeters - curr.distanceMeters
                if (ds > 1e-5) {
                    val maxReachable = sqrt(next.velocityMps * next.velocityMps + 2.0 * maxAccelerationMps2 * ds)
                    val newVel = min(curr.velocityMps, maxReachable)
                    stitchedPoints[i] = curr.copy(velocityMps = newVel)
                }
            }
        }

        return Path(stitchedPoints, stitchedEvents)
    }
}
