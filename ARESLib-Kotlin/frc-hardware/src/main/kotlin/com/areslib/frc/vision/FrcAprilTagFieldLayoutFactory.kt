package com.areslib.frc.vision

import com.areslib.state.FieldType
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldValidator
import edu.wpi.first.apriltag.AprilTag
import edu.wpi.first.apriltag.AprilTagFieldLayout
import edu.wpi.first.math.geometry.Pose3d
import edu.wpi.first.math.geometry.Rotation3d
import edu.wpi.first.math.geometry.Translation3d

/**
 * Converts the reviewed ARES field document into WPILib's canonical AprilTag layout.
 *
 * ARES and WPILib both use meters and a right-handed, CCW-positive field frame. This conversion
 * runs once during robot/camera initialization and never enters the periodic control path.
 */
object FrcAprilTagFieldLayoutFactory {
    @JvmStatic
    fun create(field: RobotFieldConfig): AprilTagFieldLayout {
        require(field.fieldType == FieldType.FRC) { "WPILib AprilTag layout requires an FRC field document" }
        val issues = RobotFieldValidator.validate(field, FieldType.FRC, requireAprilTags = true)
        require(issues.isEmpty()) { issues.joinToString(" ") { it.message } }
        return AprilTagFieldLayout(
            field.apriltags.sortedBy(RobotFieldAprilTag::id).map { tag ->
                AprilTag(
                    tag.id,
                    Pose3d(
                        Translation3d(tag.x, tag.y, tag.z),
                        Rotation3d(
                            Math.toRadians(tag.roll),
                            Math.toRadians(tag.pitch),
                            Math.toRadians(tag.yaw),
                        ),
                    ),
                )
            },
            field.resolvedWidthMeters,
            field.resolvedHeightMeters,
        )
    }
}
