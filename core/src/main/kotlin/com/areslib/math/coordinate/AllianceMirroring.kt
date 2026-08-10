package com.areslib.math.coordinate

import com.areslib.state.Alliance
import com.areslib.math.geometry.*
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
import com.areslib.math.wrapAngle

/**
 * Defines the geometric field symmetry mapping between Red and Blue alliance field halves.
 */
enum class FieldSymmetry {
    /** Rotational (180° point reflection) symmetry about the center of the field. */
    ROTATIONAL,

    /** Mirrored (line reflection) symmetry across the field center dividing axis. */
    MIRRORED
}

/**
 * Universal Alliance Pose and Path Mirroring Engine.
 *
 * Automatically mirrors 2D field poses, translations, and dense trajectory paths for the active alliance
 * (Blue Alliance poses are passed through unmodified, while Red Alliance poses are mirrored according
 * to the specified [FieldSymmetry]).
 *
 * ### Mathematical Formulations:
 * 1. **Center-Origin Rotational Symmetry (180° Point Reflection)**:
 *    $$\begin{bmatrix} x' \\ y' \end{bmatrix} = \begin{bmatrix} -x \\ -y \end{bmatrix}, \quad \theta' = \text{wrapAngle}(\theta + \pi)$$
 * 2. **Center-Origin Mirrored Symmetry (Reflection across X-axis)**:
 *    $$\begin{bmatrix} x' \\ y' \end{bmatrix} = \begin{bmatrix} x \\ -y \end{bmatrix}, \quad \theta' = \text{wrapAngle}(-\theta), \quad \kappa' = -\kappa$$
 * 3. **Corner-Origin Field Extensions** ($L_{\text{field}}, W_{\text{field}}$):
 *    - Rotational: $x' = L_{\text{field}} - x, \; y' = W_{\text{field}} - y, \; \theta' = \text{wrapAngle}(\theta + \pi)$
 *    - Reflectional: $x' = L_{\text{field}} - x, \; y' = y, \; \theta' = \text{wrapAngle}(\pi - \theta)$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y)$: Field-centric meters ($m$)
 * - Heading $(\theta)$: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 * - Path Curvature ($\kappa$): Radians per meter ($rad/m$)
 *
 * @see CoordinateTransformers
 * @see Path
 */
object AllianceMirroring {

    /**
     * Mirrors a 2D spatial pose [pose] based on active [alliance] color and field [symmetry].
     *
     * @param pose Input [Pose2d] in field-centric meters ($m$) and radians ($rad$).
     * @param alliance Active team alliance color ([Alliance.BLUE] returns [pose] unchanged).
     * @param symmetry Field geometry symmetry layout ([FieldSymmetry.ROTATIONAL] or [FieldSymmetry.MIRRORED]).
     * @param fieldLength Total X-axis length of the field in meters ($m$).
     * @param fieldWidth Total Y-axis width of the field in meters ($m$).
     * @return The alliance-adjusted [Pose2d].
     */
    fun mirror(
        pose: Pose2d,
        alliance: Alliance,
        symmetry: FieldSymmetry,
        fieldLength: Double = CoordinateTransformers.FTC_FIELD_SIZE,
        fieldWidth: Double = CoordinateTransformers.FTC_FIELD_SIZE
    ): Pose2d {
        if (alliance == Alliance.BLUE) return pose
        val isCenterOrigin = kotlin.math.abs(fieldLength - CoordinateTransformers.FTC_FIELD_SIZE) < 1e-3
        return if (isCenterOrigin) {
            when (symmetry) {
                FieldSymmetry.ROTATIONAL -> Pose2d(
                    x = -pose.x,
                    y = -pose.y,
                    heading = Rotation2d(wrapAngle(pose.heading.radians + Math.PI))
                )
                FieldSymmetry.MIRRORED -> Pose2d(
                    x = pose.x,
                    y = -pose.y,
                    heading = Rotation2d(wrapAngle(-pose.heading.radians))
                )
            }
        } else {
            when (symmetry) {
                FieldSymmetry.ROTATIONAL -> Pose2d(
                    x = fieldLength - pose.x,
                    y = fieldWidth - pose.y,
                    heading = Rotation2d(wrapAngle(pose.heading.radians + Math.PI))
                )
                FieldSymmetry.MIRRORED -> Pose2d(
                    x = fieldLength - pose.x,
                    y = pose.y,
                    heading = Rotation2d(wrapAngle(Math.PI - pose.heading.radians))
                )
            }
        }
    }

    /**
     * Mirrors a 2D translational vector [translation] based on active [alliance] color and field [symmetry].
     *
     * @param translation Input [Translation2d] in meters ($m$).
     * @param alliance Active team alliance color ([Alliance.BLUE] passes through).
     * @param symmetry Field geometry symmetry layout.
     * @param fieldLength Total field X length in meters ($m$).
     * @param fieldWidth Total field Y width in meters ($m$).
     * @return The alliance-adjusted [Translation2d].
     */
    fun mirror(
        translation: Translation2d,
        alliance: Alliance,
        symmetry: FieldSymmetry,
        fieldLength: Double = CoordinateTransformers.FTC_FIELD_SIZE,
        fieldWidth: Double = CoordinateTransformers.FTC_FIELD_SIZE
    ): Translation2d {
        if (alliance == Alliance.BLUE) return translation
        val isCenterOrigin = kotlin.math.abs(fieldLength - CoordinateTransformers.FTC_FIELD_SIZE) < 1e-3
        return if (isCenterOrigin) {
            when (symmetry) {
                FieldSymmetry.ROTATIONAL -> Translation2d(-translation.x, -translation.y)
                FieldSymmetry.MIRRORED -> Translation2d(translation.x, -translation.y)
            }
        } else {
            when (symmetry) {
                FieldSymmetry.ROTATIONAL -> Translation2d(
                    x = fieldLength - translation.x,
                    y = fieldWidth - translation.y
                )
                FieldSymmetry.MIRRORED -> Translation2d(
                    x = fieldLength - translation.x,
                    y = translation.y
                )
            }
        }
    }

    /**
     * Mirrors an entire trajectory path [path] for the Red alliance.
     * Automatically flips coordinates, tangent headings, and path curvature signs for reflectional symmetry.
     *
     * @param path Input trajectory [Path].
     * @param alliance Active team alliance color.
     * @param symmetry Field geometry symmetry layout.
     * @param fieldLength Total field X length in meters ($m$).
     * @param fieldWidth Total field Y width in meters ($m$).
     * @return The alliance-adjusted [Path].
     */
    fun mirror(
        path: Path,
        alliance: Alliance,
        symmetry: FieldSymmetry,
        fieldLength: Double = CoordinateTransformers.FTC_FIELD_SIZE,
        fieldWidth: Double = CoordinateTransformers.FTC_FIELD_SIZE
    ): Path {
        if (alliance == Alliance.BLUE) return path
        val numPoints = path.points.size
        val mirroredPoints = ArrayList<PathPoint>(numPoints)
        for (i in 0 until numPoints) {
            val point = path.points[i]
            val mirroredPose = mirror(point.pose, alliance, symmetry, fieldLength, fieldWidth)
            val mirroredCurvature = when (symmetry) {
                FieldSymmetry.ROTATIONAL -> point.curvature
                FieldSymmetry.MIRRORED -> -point.curvature
            }
            val mirroredTangent = when (symmetry) {
                FieldSymmetry.ROTATIONAL -> wrapAngle(point.tangentRadians + Math.PI)
                FieldSymmetry.MIRRORED -> if (
                    kotlin.math.abs(fieldLength - CoordinateTransformers.FTC_FIELD_SIZE) < 1e-3
                ) {
                    wrapAngle(-point.tangentRadians)
                } else {
                    wrapAngle(Math.PI - point.tangentRadians)
                }
            }
            mirroredPoints.add(
                point.copy(
                    pose = mirroredPose,
                    curvature = mirroredCurvature,
                    tangentRadians = mirroredTangent
                )
            )
        }
        return path.copy(points = mirroredPoints)
    }
}
