package com.areslib.math.coordinate

import com.areslib.state.Alliance
import com.areslib.math.geometry.*
import com.areslib.math.wrapAngle

/**
 * Coordinate System Origin Mapping and Field Origin Transformation Utilities.
 *
 * Converts spatial poses and translation vectors between **Center-Origin** reference frames
 * (AdvantageScope, Dyn4j simulator, WPILib EKF with origin at $(0,0)$ field center) and
 * **Corner-Origin** reference frames (PathPlanner, Driver Station layout with $(0,0)$ at bottom-right corner).
 *
 * ### Mathematical Formulations:
 * 1. **Center-to-Corner Transformation**:
 *    $$\mathbf{p}_{\text{corner}} = \begin{bmatrix} x_{\text{center}} + \frac{L_{\text{field}}}{2} \\ y_{\text{center}} + \frac{W_{\text{field}}}{2} \end{bmatrix}, \quad \theta_{\text{corner}} = \theta_{\text{center}}$$
 * 2. **Corner-to-Center Transformation**:
 *    $$\mathbf{p}_{\text{center}} = \begin{bmatrix} x_{\text{corner}} - \frac{L_{\text{field}}}{2} \\ y_{\text{corner}} - \frac{W_{\text{field}}}{2} \end{bmatrix}, \quad \theta_{\text{center}} = \theta_{\text{corner}}$$
 * 3. **Reflectional Mirroring Across X-Axis (PathPlanner Red Alliance)**:
 *    $$x' = L_{\text{field}} - x, \quad y' = y, \quad \theta' = \text{wrapAngle}(\pi - \theta)$$
 *
 * ### Physical Constants & Units:
 * - `FTC_FIELD_SIZE`: $3.6576\,m$ ($12\,\text{ft} \times 12\,\text{ft}$)
 * - `FRC_FIELD_LENGTH`: $16.54175\,m$, `FRC_FIELD_WIDTH`: $8.21055\,m$
 * - Position $(x, y)$: Meters ($m$)
 * - Heading $(\theta)$: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 *
 * @see AllianceMirroring
 */
object CoordinateTransformers {
    /** FTC competition field bounding side length ($12\,\text{ft} = 3.6576\,m$). */
    const val FTC_FIELD_SIZE = 3.6576

    /** Standard FRC competition field length along X-axis ($16.54175\,m$). */
    const val FRC_FIELD_LENGTH = 16.54175

    /** Standard FRC competition field width along Y-axis ($8.21055\,m$). */
    const val FRC_FIELD_WIDTH = 8.21055

    /**
     * Converts a Center-Origin pose (AdvantageScope/Dyn4j) to a Corner-Origin pose (PathPlanner).
     *
     * @param centerPose Pose with origin at $(0,0)$ in the middle of the field.
     * @param fieldLength Bounding length of the field along X-axis in meters ($m$).
     * @param fieldWidth Bounding width of the field along Y-axis in meters ($m$).
     * @return Pose mapped to PathPlanner's Bottom-Right corner origin in meters ($m$).
     */
    @JvmOverloads
    @JvmStatic
    fun centerToCorner(
        centerPose: Pose2d,
        fieldLength: Double = FTC_FIELD_SIZE,
        fieldWidth: Double = FTC_FIELD_SIZE
    ): Pose2d {
        return Pose2d(
            x = centerPose.x + (fieldLength / 2.0),
            y = centerPose.y + (fieldWidth / 2.0),
            heading = centerPose.heading
        )
    }

    /**
     * Converts a Corner-Origin pose (PathPlanner) to a Center-Origin pose (AdvantageScope/Dyn4j).
     *
     * @param cornerPose Pose with origin at $(0,0)$ at the bottom-right corner of the field.
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
        return Pose2d(
            x = cornerPose.x - (fieldLength / 2.0),
            y = cornerPose.y - (fieldWidth / 2.0),
            heading = cornerPose.heading
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
        return Pose2d(
            x = -pose.x,
            y = -pose.y,
            heading = Rotation2d(wrapAngle(pose.heading.radians + Math.PI))
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
        return Translation2d(-translation.x, -translation.y)
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
        return Pose2d(
            x = fieldLength - pose.x,
            y = fieldWidth - pose.y,
            heading = Rotation2d(wrapAngle(pose.heading.radians + Math.PI))
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
        return Pose2d(
            x = fieldLength - pose.x,
            y = pose.y,
            heading = Rotation2d(wrapAngle(Math.PI - pose.heading.radians))
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
        return Translation2d(fieldLength - translation.x, translation.y)
    }
}

