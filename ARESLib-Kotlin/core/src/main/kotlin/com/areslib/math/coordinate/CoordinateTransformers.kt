package com.areslib.math.coordinate

import com.areslib.state.Alliance
import com.areslib.math.geometry.*
import com.areslib.math.wrapAngle

internal fun coordinateExtent(value: Double): Double {
    require(value.isFinite() && value > 0.0) { "Field extent must be finite and positive" }
    return value
}

internal fun coordinatePose(x: Double, y: Double, rawHeading: Double): Pose2d {
    require(x.isFinite() && y.isFinite() && rawHeading.isFinite()) { "Transformed pose must be finite" }
    return Pose2d(x, y, Rotation2d(rawHeading))
}

internal fun coordinateTranslation(x: Double, y: Double): Translation2d {
    require(x.isFinite() && y.isFinite()) { "Transformed position must be finite" }
    return Translation2d(x, y)
}

/** Normalize before adding pi, which can disappear when added to a huge raw angle. */
internal fun allianceHeading(raw: Double, symmetry: FieldSymmetry, origin: FieldOrigin): Double {
    require(raw.isFinite()) { "Raw heading must be finite before alliance transformation" }
    val angle = wrapAngle(raw)
    return when (symmetry) {
        FieldSymmetry.ROTATIONAL -> wrapAngle(angle + Math.PI)
        FieldSymmetry.MIRRORED -> if (origin == FieldOrigin.CENTER) wrapAngle(-angle) else wrapAngle(Math.PI - angle)
    }
}

/**
 * Coordinate System Origin Mapping and Field Origin Transformation Utilities.
 *
 * Converts spatial poses between parallel-axis center-origin and minimum-X/minimum-Y
 * corner-origin frames. These functions translate the origin; they do not rotate axes or
 * choose a vendor's coordinate convention. Callers select dimensions for their field layout.
 *
 * ### Mathematical Formulations:
 * 1. **Center-to-Corner Transformation**:
 *    $$\mathbf{p}_{\text{corner}} = \begin{bmatrix} x_{\text{center}} + \frac{L_{\text{field}}}{2} \\ y_{\text{center}} + \frac{W_{\text{field}}}{2} \end{bmatrix}, \quad \theta_{\text{corner}} = \theta_{\text{center}}$$
 * 2. **Corner-to-Center Transformation**:
 *    $$\mathbf{p}_{\text{center}} = \begin{bmatrix} x_{\text{corner}} - \frac{L_{\text{field}}}{2} \\ y_{\text{corner}} - \frac{W_{\text{field}}}{2} \end{bmatrix}, \quad \theta_{\text{center}} = \theta_{\text{corner}}$$
 * 3. **Reflection across the line x = fieldLength / 2**:
 *    $$x' = L_{\text{field}} - x, \quad y' = y, \quad \theta' = \text{wrapAngle}(\pi - \theta)$$
 *
 * ### Physical Constants & Units:
 * - `FTC_FIELD_SIZE`: $3.6576\,m$ ($12\,\text{ft} \times 12\,\text{ft}$)
 * - `FRC_FIELD_LENGTH`: $16.54175\,m$, `FRC_FIELD_WIDTH`: $8.21055\,m$
 * - Position $(x, y)$: Meters ($m$)
 * - Heading $(\theta)$: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 *
 * @see AllianceMirroring
 *
 * Operations require finite results and finite raw headings; used field extents must be finite
 * and positive. Invalid transformations throw IllegalArgumentException. Blue alliance methods
 * return their original object without validation. New transformed poses/positions allocate.
 */
object CoordinateTransformers {
    /** FTC competition field bounding side length ($12\,\text{ft} = 3.6576\,m$). */
    const val FTC_FIELD_SIZE = 3.6576

    /** FRC compatibility-default field length along X-axis; use an explicit layout when different. */
    const val FRC_FIELD_LENGTH = 16.54175

    /** FRC compatibility-default field width along Y-axis; use an explicit layout when different. */
    const val FRC_FIELD_WIDTH = 8.21055

    /**
     * Translates a center-origin pose to the parallel-axis minimum-coordinate corner frame.
     *
     * @param centerPose Pose with origin at $(0,0)$ in the middle of the field.
     * @param fieldLength Bounding length of the field along X-axis in meters ($m$).
     * @param fieldWidth Bounding width of the field along Y-axis in meters ($m$).
     * @return Pose mapped to the minimum-X/minimum-Y corner origin in meters ($m$).
     */
    @JvmOverloads
    @JvmStatic
    fun centerToCorner(
        centerPose: Pose2d,
        fieldLength: Double = FTC_FIELD_SIZE,
        fieldWidth: Double = FTC_FIELD_SIZE
    ): Pose2d {
        return coordinatePose(
            x = centerPose.x + (coordinateExtent(fieldLength) / 2.0),
            y = centerPose.y + (coordinateExtent(fieldWidth) / 2.0),
            rawHeading = centerPose.heading.rawRadians
        )
    }

    /**
     * Translates a minimum-coordinate corner pose to the parallel-axis center frame.
     *
     * @param cornerPose Pose whose origin is the minimum-X/minimum-Y corner of the field.
     * @param fieldLength Bounding length of the field along X-axis in meters ($m$).
     * @param fieldWidth Bounding width of the field along Y-axis in meters ($m$).
     * @return Pose mapped to the center of the field in meters ($m$).
     */
    @JvmOverloads
    @JvmStatic
    fun cornerToCenter(
        cornerPose: Pose2d,
        fieldLength: Double = FTC_FIELD_SIZE,
        fieldWidth: Double = FTC_FIELD_SIZE
    ): Pose2d {
        return coordinatePose(
            x = cornerPose.x - (coordinateExtent(fieldLength) / 2.0),
            y = cornerPose.y - (coordinateExtent(fieldWidth) / 2.0),
            rawHeading = cornerPose.heading.rawRadians
        )
    }

    /**
     * Flips a pose relative to the field center (180° rotation) for the Red Alliance.
     *
     * @param pose Center-origin input pose.
     * @param alliance Active alliance color.
     * @return Rotated pose if Red alliance, or original pose if Blue.
     */
    fun flipPoseRotational(pose: Pose2d, alliance: Alliance): Pose2d {
        if (alliance == Alliance.BLUE) return pose
        return coordinatePose(
            x = -pose.x,
            y = -pose.y,
            rawHeading = allianceHeading(pose.heading.rawRadians, FieldSymmetry.ROTATIONAL, FieldOrigin.CENTER)
        )
    }

    /**
     * Flips a translation vector relative to the field center (180° rotation) for the Red Alliance.
     *
     * @param translation Center-origin input translation.
     * @param alliance Active alliance color.
     * @return Rotated translation if Red alliance.
     */
    fun flipTranslationRotational(translation: Translation2d, alliance: Alliance): Translation2d {
        if (alliance == Alliance.BLUE) return translation
        return coordinateTranslation(-translation.x, -translation.y)
    }

    /**
     * Flips an absolute corner-origin pose using rotational symmetry (180° rotation) about field center.
     *
     * @param pose Corner-origin input pose.
     * @param alliance Active alliance color.
     * @param fieldLength Field X length in meters ($m$).
     * @param fieldWidth Field Y width in meters ($m$).
     * @return Rotated corner-origin pose if Red alliance.
     */
    fun flipCornerPoseRotational(pose: Pose2d, alliance: Alliance, fieldLength: Double = FTC_FIELD_SIZE, fieldWidth: Double = FTC_FIELD_SIZE): Pose2d {
        if (alliance == Alliance.BLUE) return pose
        return coordinatePose(
            x = coordinateExtent(fieldLength) - pose.x,
            y = coordinateExtent(fieldWidth) - pose.y,
            rawHeading = allianceHeading(pose.heading.rawRadians, FieldSymmetry.ROTATIONAL, FieldOrigin.CORNER)
        )
    }

    /**
     * Mirrors an absolute corner-origin pose using reflectional mirroring across the center line perpendicular to X-axis.
     *
     * @param pose Corner-origin input pose.
     * @param alliance Active alliance color.
     * @param fieldLength Field X length in meters ($m$).
     * @return Reflected corner-origin pose if Red alliance.
     */
    fun mirrorPoseReflectionalX(pose: Pose2d, alliance: Alliance, fieldLength: Double = FTC_FIELD_SIZE): Pose2d {
        if (alliance == Alliance.BLUE) return pose
        return coordinatePose(
            x = coordinateExtent(fieldLength) - pose.x,
            y = pose.y,
            rawHeading = allianceHeading(pose.heading.rawRadians, FieldSymmetry.MIRRORED, FieldOrigin.CORNER)
        )
    }

    /**
     * Mirrors an absolute corner-origin translation using reflectional mirroring across the center line perpendicular to X-axis.
     *
     * @param translation Corner-origin input translation.
     * @param alliance Active alliance color.
     * @param fieldLength Field X length in meters ($m$).
     * @return Reflected corner-origin translation if Red alliance.
     */
    fun mirrorTranslationReflectionalX(translation: Translation2d, alliance: Alliance, fieldLength: Double = FTC_FIELD_SIZE): Translation2d {
        if (alliance == Alliance.BLUE) return translation
        return coordinateTranslation(coordinateExtent(fieldLength) - translation.x, translation.y)
    }
}

