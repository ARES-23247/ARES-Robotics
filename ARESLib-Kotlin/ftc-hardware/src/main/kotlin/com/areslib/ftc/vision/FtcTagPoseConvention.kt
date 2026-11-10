package com.areslib.ftc.vision

/** Tag-local axes used by the reviewed field's rotations; field positions remain meters.
 * Select explicitly for imported layouts rather than inferring a basis from Euler angles. */
enum class FtcTagPoseConvention {
    /** SDK optical tag axes: +X right, +Y down, +Z into the printed face. Maintained FTC layouts. */
    FTC_OPTICAL,
    /** WPILib/PhotonVision tag axes: +X outward, +Y left, +Z up. */
    WPILIB_NWU,
}
