package com.areslib.math.coordinate

import com.areslib.math.geometry.*

/**
 * Defines the physical boundary topology of the FTC competition field.
 */
enum class FieldLayout {
    /** Standard Square layout: Red Alliance Wall on right (+X wall, tags on $\pm Y$ walls). */
    SQUARE_STANDARD,

    /** Diamond layout (e.g., RES-Q): Field rotated $45^\circ$, Alliance walls adjacent. */
    DIAMOND
}

/**
 * Pre-defined AprilTag 3D landmark coordinate maps mapped to the EKF world frame of reference.
 *
 * Provides static 3D target poses $\mathbf{p}_{\text{tag}} = [x, y, z, \phi, \theta, \psi]^T$ for vision-based
 * Extended Kalman Filter (EKF) pose estimation.
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y, z)$: Meters ($m$) relative to field center origin $(0,0,0)$
 * - Orientation $(\phi, \theta, \psi)$: Euler angles in radians ($rad$) with **CCW-positive** yaw
 *   - Tag facing $-Y$ wall: yaw = $-\frac{\pi}{2}$
 *   - Tag facing $+Y$ wall: yaw = $+\frac{\pi}{2}$
 *
 * ### Zero-GC Guarantee:
 * Uses pre-allocated static maps `SQUARE_STANDARD_TAGS` and `DIAMOND_TAGS` to avoid allocations during vision updates.
 *
 * @see FieldLayout
 */
object FieldLayouts {
    
    /** Standard Square field AprilTag coordinates mapped to EKF/WPILib 3D world frame. */
    val SQUARE_STANDARD_TAGS = mapOf(
        // Blue tags on +Y wall, facing -Y (-90 degrees)
        1 to Pose3d(Translation3d(1.5, 1.8, 0.5), Rotation3d(0.0, 0.0, -Math.PI / 2)),
        2 to Pose3d(Translation3d(-1.5, 1.8, 0.5), Rotation3d(0.0, 0.0, -Math.PI / 2)),
        // Red tags on -Y wall, facing +Y (+90 degrees)
        3 to Pose3d(Translation3d(1.5, -1.8, 0.5), Rotation3d(0.0, 0.0, Math.PI / 2)),
        4 to Pose3d(Translation3d(-1.5, -1.8, 0.5), Rotation3d(0.0, 0.0, Math.PI / 2))
    )

    /** Diamond field AprilTag coordinates mapped to EKF/WPILib 3D world frame. */
    val DIAMOND_TAGS = mapOf(
        1 to Pose3d(Translation3d(1.5, 1.5, 0.5), Rotation3d(0.0, 0.0, Math.PI)),
        2 to Pose3d(Translation3d(1.5, -1.5, 0.5), Rotation3d(0.0, 0.0, Math.PI)),
        3 to Pose3d(Translation3d(-1.5, 1.5, 0.5), Rotation3d(0.0, 0.0, 0.0)),
        4 to Pose3d(Translation3d(-1.5, -1.5, 0.5), Rotation3d(0.0, 0.0, 0.0))
    )

    /**
     * Retrieves the static AprilTag 3D coordinate landmark map for the specified [layout].
     *
     * @param layout Field topology configuration ([FieldLayout.SQUARE_STANDARD] or [FieldLayout.DIAMOND]).
     * @return Map of integer Tag ID to field-centric [Pose3d] landmark coordinates in meters ($m$) and radians ($rad$).
     */
    fun getTagsForLayout(layout: FieldLayout): Map<Int, Pose3d> {
        return when (layout) {
            FieldLayout.SQUARE_STANDARD -> SQUARE_STANDARD_TAGS
            FieldLayout.DIAMOND -> DIAMOND_TAGS
        }
    }
}

