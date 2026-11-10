package com.areslib.ftc.vision

import com.areslib.state.FieldType
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldValidator
import com.areslib.state.canonicalFtcAprilTagFamily
import org.firstinspires.ftc.robotcore.external.matrices.VectorF
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion
import org.firstinspires.ftc.vision.apriltag.AprilTagLibrary
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor
import kotlin.math.cos
import kotlin.math.sin

/**
 * Converts a reviewed canonical FTC field into the SDK's immutable AprilTag library.
 *
 * This work happens during camera initialization, not in the periodic loop. The field document
 * remains authoritative; generated projects do not duplicate tag poses in Kotlin. Tags must share
 * one FTC-supported family because [AprilTagProcessor] selects one detector family per processor.
 * SDK metadata is expressed in inches (including field position, as expected by its robot-pose
 * solver). The canonical tag's +X outward/+Y left/+Z up basis is converted to the SDK optical tag
 * basis. Moving targets belong in a cluster library, never this stationary field library.
 */
object FtcAprilTagLibraryFactory {
    @JvmStatic
    fun create(field: RobotFieldConfig): AprilTagLibrary {
        require(field.fieldType == FieldType.FTC) { "FTC VisionPortal requires an FTC field document" }
        val issues = RobotFieldValidator.validate(field, FieldType.FTC, requireAprilTags = true)
        require(issues.isEmpty()) { issues.joinToString(" ") { it.message } }

        val builder = AprilTagLibrary.Builder().setAllowOverwrite(false)
        field.apriltags.sortedBy(RobotFieldAprilTag::id).forEach { tag ->
            val size = requireNotNull(tag.sizeMeters) { "FTC AprilTag ${tag.id} is missing its physical size" }
            val quaternion = tag.toQuaternion()
            builder.addTag(
                tag.id,
                tag.name.ifBlank { "AprilTag ${tag.id}" },
                size / 0.0254,
                VectorF((tag.x/0.0254).toFloat(), (tag.y/0.0254).toFloat(), (tag.z/0.0254).toFloat()),
                DistanceUnit.INCH,
                Quaternion(
                    quaternion.w.toFloat(),
                    quaternion.x.toFloat(),
                    quaternion.y.toFloat(),
                    quaternion.z.toFloat(),
                    0L,
                ),
            )
        }
        return builder.build()
    }

    /** Applies the canonical library and its single detector family to an SDK processor builder. */
    @JvmStatic
    fun configure(builder: AprilTagProcessor.Builder, field: RobotFieldConfig): AprilTagProcessor.Builder {
        val families = field.apriltags.map { normalizeFamily(it.family) }.distinct()
        require(families.size == 1) { "FTC VisionPortal requires every AprilTag to use one detector family" }
        return builder
            .setTagFamily(families.single())
            .setTagLibrary(create(field))
    }

    private fun normalizeFamily(value: String): AprilTagProcessor.TagFamily = when (
        requireNotNull(canonicalFtcAprilTagFamily(value)) { "Unsupported FTC AprilTag family '$value'" }
    ) {
        "36h11" -> AprilTagProcessor.TagFamily.TAG_36h11
        "25h9" -> AprilTagProcessor.TagFamily.TAG_25h9
        "16h5" -> AprilTagProcessor.TagFamily.TAG_16h5
        "standard41h12" -> AprilTagProcessor.TagFamily.TAG_standard41h12
        else -> error("Unreachable canonical FTC AprilTag family")
    }

    private fun RobotFieldAprilTag.toQuaternion(): QuaternionValues {
        val halfRoll = Math.toRadians(roll) * 0.5
        val halfPitch = Math.toRadians(pitch) * 0.5
        val halfYaw = Math.toRadians(yaw) * 0.5
        val cr = cos(halfRoll)
        val sr = sin(halfRoll)
        val cp = cos(halfPitch)
        val sp = sin(halfPitch)
        val cy = cos(halfYaw)
        val sy = sin(halfYaw)
        val w = cr * cp * cy + sr * sp * sy
        val x = sr * cp * cy - cr * sp * sy
        val y = cr * sp * cy + sr * cp * sy
        val z = cr * cp * sy - sr * sp * cy
        // Right-multiply by the fixed optical-tag basis quaternion (0.5,-0.5,-0.5,0.5).
        return QuaternionValues(0.5*(w+x+y-z), 0.5*(-w+x+y+z), 0.5*(-w-x+y-z), 0.5*(w-x+y+z))
    }

    private data class QuaternionValues(val w: Double, val x: Double, val y: Double, val z: Double)
}
