package com.areslib.math.coordinate

import com.areslib.state.Alliance
import com.areslib.math.geometry.*
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
import com.google.gson.annotations.SerializedName

/**
 * Defines the geometric field symmetry mapping between Red and Blue alliance field halves.
 */
enum class FieldSymmetry {
    /** Rotational (180° point reflection) symmetry about the center of the field. */
    @SerializedName("rotational")
    ROTATIONAL,

    /** Mirrored (line reflection) symmetry across the field center dividing axis. */
    @SerializedName("mirrored")
    MIRRORED
}

/** Defines whether field coordinates are measured from the center or a field corner. */
enum class FieldOrigin {
    /** The field center is `(0, 0)` and coordinates may be negative. */
    CENTER,

    /** One field corner is `(0, 0)` and positions normally lie within field length and width. */
    CORNER
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
 *
 * Blue returns the original object without validation. Red requires finite geometry/raw angles
 * and valid used field extents; invalid transforms throw IllegalArgumentException. Center
 * reflection flips Y, while corner reflection flips X, preserving the two authored conventions.
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
     * @param fieldOrigin Explicit coordinate origin; it is never inferred from field dimensions.
     * @return The alliance-adjusted [Pose2d].
     */
    fun mirror(
        pose: Pose2d,
        alliance: Alliance,
        symmetry: FieldSymmetry,
        fieldLength: Double = CoordinateTransformers.FTC_FIELD_SIZE,
        fieldWidth: Double = CoordinateTransformers.FTC_FIELD_SIZE,
        fieldOrigin: FieldOrigin = FieldOrigin.CENTER
    ): Pose2d {
        if (alliance == Alliance.BLUE) return pose
        return if (fieldOrigin == FieldOrigin.CENTER) {
            when (symmetry) {
                FieldSymmetry.ROTATIONAL -> CoordinateTransformers.flipPoseRotational(pose, alliance)
                FieldSymmetry.MIRRORED -> coordinatePose(
                    x = pose.x,
                    y = -pose.y,
                    rawHeading = allianceHeading(pose.heading.rawRadians, symmetry, fieldOrigin)
                )
            }
        } else {
            when (symmetry) {
                FieldSymmetry.ROTATIONAL -> CoordinateTransformers.flipCornerPoseRotational(pose, alliance, fieldLength, fieldWidth)
                FieldSymmetry.MIRRORED -> CoordinateTransformers.mirrorPoseReflectionalX(pose, alliance, fieldLength)
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
     * @param fieldOrigin Explicit coordinate origin; it is never inferred from field dimensions.
     * @return The alliance-adjusted [Translation2d].
     */
    fun mirror(
        translation: Translation2d,
        alliance: Alliance,
        symmetry: FieldSymmetry,
        fieldLength: Double = CoordinateTransformers.FTC_FIELD_SIZE,
        fieldWidth: Double = CoordinateTransformers.FTC_FIELD_SIZE,
        fieldOrigin: FieldOrigin = FieldOrigin.CENTER
    ): Translation2d {
        if (alliance == Alliance.BLUE) return translation
        return if (fieldOrigin == FieldOrigin.CENTER) {
            when (symmetry) {
                FieldSymmetry.ROTATIONAL -> CoordinateTransformers.flipTranslationRotational(translation, alliance)
                FieldSymmetry.MIRRORED -> coordinateTranslation(translation.x, -translation.y)
            }
        } else {
            when (symmetry) {
                FieldSymmetry.ROTATIONAL -> coordinateTranslation(
                    x = coordinateExtent(fieldLength) - translation.x,
                    y = coordinateExtent(fieldWidth) - translation.y
                )
                FieldSymmetry.MIRRORED -> CoordinateTransformers.mirrorTranslationReflectionalX(translation, alliance, fieldLength)
            }
        }
    }

    /**
     * Mirrors an entire trajectory path [path] for the Red alliance.
     * Automatically flips coordinates, tangent headings, and path curvature signs for reflectional symmetry.
     * Traversal is linear for linked and random-access lists. Red allocates independent points and
     * poses but preserves the caller-owned event list. Do not mutate input points during this call.
     *
     * @param path Input trajectory [Path].
     * @param alliance Active team alliance color.
     * @param symmetry Field geometry symmetry layout.
     * @param fieldLength Total field X length in meters ($m$).
     * @param fieldWidth Total field Y width in meters ($m$).
     * @param fieldOrigin Explicit coordinate origin; it is never inferred from field dimensions.
     * @return The alliance-adjusted [Path].
     */
    fun mirror(
        path: Path,
        alliance: Alliance,
        symmetry: FieldSymmetry,
        fieldLength: Double = CoordinateTransformers.FTC_FIELD_SIZE,
        fieldWidth: Double = CoordinateTransformers.FTC_FIELD_SIZE,
        fieldOrigin: FieldOrigin = FieldOrigin.CENTER
    ): Path {
        if (alliance == Alliance.BLUE) return path
        if (fieldOrigin == FieldOrigin.CORNER) {
            coordinateExtent(fieldLength)
            if (symmetry == FieldSymmetry.ROTATIONAL) coordinateExtent(fieldWidth)
        }
        val numPoints = path.points.size
        val mirroredPoints = ArrayList<PathPoint>(numPoints)
        var previousDistance = 0.0
        for (point in path.points) {
            require(point.velocityMps.isFinite() && point.curvature.isFinite() &&
                point.distanceMeters.isFinite() && point.distanceMeters >= previousDistance) {
                "Path values must be finite with nonnegative ordered distance"
            }
            previousDistance = point.distanceMeters
            val mirroredPose = mirror(point.pose, alliance, symmetry, fieldLength, fieldWidth, fieldOrigin)
            val mirroredCurvature = when (symmetry) {
                FieldSymmetry.ROTATIONAL -> point.curvature
                FieldSymmetry.MIRRORED -> -point.curvature
            }
            val mirroredTangent = allianceHeading(point.tangentRadians, symmetry, fieldOrigin)
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
