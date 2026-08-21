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
                size,
                VectorF(tag.x.toFloat(), tag.y.toFloat(), tag.z.toFloat()),
                DistanceUnit.METER,
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
        return QuaternionValues(
            w = cr * cp * cy + sr * sp * sy,
            x = sr * cp * cy - cr * sp * sy,
            y = cr * sp * cy + sr * cp * sy,
            z = cr * cp * sy - sr * sp * cy,
        )
    }

    private data class QuaternionValues(val w: Double, val x: Double, val y: Double, val z: Double)
}
