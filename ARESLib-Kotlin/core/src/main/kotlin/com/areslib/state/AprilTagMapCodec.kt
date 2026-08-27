package com.areslib.state

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Supported interchange formats for reviewed AprilTag field layouts. */
enum class AprilTagMapFormat { ARES_FIELD, LIMELIGHT_FMAP, WPILIB_JSON }

/**
 * Parsed AprilTag layout in ARES field coordinates.
 *
 * WPILib layouts carry field dimensions but omit tag family, name, and physical size. Limelight
 * maps carry family and size but omit field dimensions and tag names. Callers must surface those
 * losses during preview instead of silently inventing metadata.
 */
data class AprilTagMapImportResult(
    val format: AprilTagMapFormat,
    val tags: List<RobotFieldAprilTag>,
    val fieldLengthMeters: Double? = null,
    val fieldWidthMeters: Double? = null,
    val omittedMetadata: Set<String> = emptySet(),
)

/**
 * Deterministic adapters for ARES, Limelight `.fmap`, and WPILib AprilTag JSON layouts.
 *
 * Positions are meters. ARES orientation is right-handed roll/pitch/yaw in degrees using the
 * intrinsic X/Y/Z convention represented by `Rz(yaw) * Ry(pitch) * Rx(roll)`. Limelight matrices
 * are row-major 4x4 transforms and store tag size in millimeters. WPILib rotations are normalized
 * W/X/Y/Z quaternions. This object performs file-format work only and is never used in a periodic
 * robot loop.
 */
object AprilTagMapCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @JvmStatic
    fun decodeAresField(json: String): AprilTagMapImportResult {
        val field = RobotFieldDocument.decode(json)
        requireValidTags(field.apriltags)
        return AprilTagMapImportResult(
            format = AprilTagMapFormat.ARES_FIELD,
            tags = field.apriltags,
            fieldLengthMeters = field.resolvedWidthMeters,
            fieldWidthMeters = field.resolvedHeightMeters,
        )
    }

    @JvmStatic
    fun decodeLimelightFmap(json: String): AprilTagMapImportResult {
        val fmap = requireNotNull(gson.fromJson(json, LimelightFmap::class.java)) {
            "Limelight map is empty"
        }
        val tags = fmap.fiducials.map { fiducial ->
            require(fiducial.transform.size == MATRIX_SIZE) {
                "Limelight AprilTag ${fiducial.id} must contain one row-major 4x4 transform"
            }
            val transform = fiducial.transform
            require(transform.all(Double::isFinite)) {
                "Limelight AprilTag ${fiducial.id} contains a non-finite transform value"
            }
            val euler = matrixToEulerDegrees(transform)
            RobotFieldAprilTag(
                id = fiducial.id,
                family = fiducial.family.trim(),
                sizeMeters = fiducial.size.takeIf { it > 0.0 }?.div(MILLIMETERS_PER_METER),
                x = transform[3],
                y = transform[7],
                z = transform[11],
                roll = euler.roll,
                pitch = euler.pitch,
                yaw = euler.yaw,
                editorId = "apriltag-${fiducial.id}",
            )
        }
        requireValidTags(tags)
        return AprilTagMapImportResult(
            format = AprilTagMapFormat.LIMELIGHT_FMAP,
            tags = tags,
            omittedMetadata = setOf("field dimensions", "tag names"),
        )
    }

    /**
     * Decodes a Limelight map into the canonical coordinate frame of [field].
     *
     * Limelight `.fmap` transforms use a field-center origin. ARES FTC fields use that same origin,
     * while ARES FRC fields use WPILib's blue-corner origin. The target field dimensions are
     * therefore required to translate FRC positions without guessing.
     */
    @JvmStatic
    fun decodeLimelightFmapForField(json: String, field: RobotFieldConfig): AprilTagMapImportResult {
        val decoded = decodeLimelightFmap(json)
        if (field.fieldType == FieldType.FTC) return decoded
        val halfLength = field.resolvedWidthMeters * 0.5
        val halfWidth = field.resolvedHeightMeters * 0.5
        return decoded.copy(
            tags = decoded.tags.map { tag -> tag.copy(x = tag.x + halfLength, y = tag.y + halfWidth) },
        )
    }

    @JvmStatic
    fun encodeLimelightFmap(tags: List<RobotFieldAprilTag>): String {
        requireValidTags(tags)
        val fiducials = tags.sortedBy(RobotFieldAprilTag::id).map { tag ->
            require(tag.family.isNotBlank()) {
                "AprilTag ${tag.id} needs a family before Limelight export"
            }
            val sizeMeters = requireNotNull(tag.sizeMeters) {
                "AprilTag ${tag.id} needs a physical size before Limelight export"
            }
            LimelightFiducial(
                id = tag.id,
                family = tag.family,
                size = sizeMeters * MILLIMETERS_PER_METER,
                transform = eulerDegreesToMatrix(tag),
                unique = 1,
            )
        }
        return gson.toJson(LimelightFmap(fiducials))
    }

    /** Encodes a canonical field using Limelight's field-center coordinate frame. */
    @JvmStatic
    fun encodeLimelightFmap(field: RobotFieldConfig): String {
        val tags = if (field.fieldType == FieldType.FTC) {
            field.apriltags
        } else {
            val halfLength = field.resolvedWidthMeters * 0.5
            val halfWidth = field.resolvedHeightMeters * 0.5
            field.apriltags.map { tag -> tag.copy(x = tag.x - halfLength, y = tag.y - halfWidth) }
        }
        return encodeLimelightFmap(tags)
    }

    @JvmStatic
    fun decodeWpilib(json: String): AprilTagMapImportResult {
        val layout = requireNotNull(gson.fromJson(json, WpilibLayout::class.java)) {
            "WPILib AprilTag layout is empty"
        }
        require(layout.field.length.isFinite() && layout.field.length > 0.0) {
            "WPILib field length must be finite and positive"
        }
        require(layout.field.width.isFinite() && layout.field.width > 0.0) {
            "WPILib field width must be finite and positive"
        }
        val tags = layout.tags.map { tag ->
            val translation = tag.pose.translation
            val quaternion = tag.pose.rotation.quaternion.normalized(tag.id)
            val euler = quaternionToEulerDegrees(quaternion)
            RobotFieldAprilTag(
                id = tag.id,
                x = translation.x,
                y = translation.y,
                z = translation.z,
                roll = euler.roll,
                pitch = euler.pitch,
                yaw = euler.yaw,
                editorId = "apriltag-${tag.id}",
            )
        }
        requireValidTags(tags)
        return AprilTagMapImportResult(
            format = AprilTagMapFormat.WPILIB_JSON,
            tags = tags,
            fieldLengthMeters = layout.field.length,
            fieldWidthMeters = layout.field.width,
            omittedMetadata = setOf("tag family", "tag size", "tag names"),
        )
    }

    @JvmStatic
    fun encodeWpilib(field: RobotFieldConfig): String {
        requireValidTags(field.apriltags)
        val layout = WpilibLayout(
            field = WpilibField(field.resolvedWidthMeters, field.resolvedHeightMeters),
            tags = field.apriltags.sortedBy(RobotFieldAprilTag::id).map { tag ->
                WpilibTag(
                    id = tag.id,
                    pose = WpilibPose(
                        translation = WpilibTranslation(tag.x, tag.y, tag.z),
                        rotation = WpilibRotation(eulerDegreesToQuaternion(tag)),
                    ),
                )
            },
        )
        return gson.toJson(layout)
    }

    private fun requireValidTags(tags: List<RobotFieldAprilTag>) {
        val ids = hashSetOf<Int>()
        tags.forEach { tag ->
            require(tag.id > 0 && ids.add(tag.id)) { "AprilTag IDs must be positive and unique" }
            require(tag.x.isFinite() && tag.y.isFinite() && tag.z.isFinite()) {
                "AprilTag ${tag.id} position must be finite"
            }
            require(tag.roll.isFinite() && tag.pitch.isFinite() && tag.yaw.isFinite()) {
                "AprilTag ${tag.id} orientation must be finite"
            }
            require(tag.sizeMeters == null || tag.sizeMeters.isFinite() && tag.sizeMeters > 0.0) {
                "AprilTag ${tag.id} size must be finite and positive when provided"
            }
        }
    }

    private fun eulerDegreesToMatrix(tag: RobotFieldAprilTag): List<Double> {
        val roll = Math.toRadians(tag.roll)
        val pitch = Math.toRadians(tag.pitch)
        val yaw = Math.toRadians(tag.yaw)
        val cr = cos(roll)
        val sr = sin(roll)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val cy = cos(yaw)
        val sy = sin(yaw)
        return listOf(
            cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr, tag.x,
            sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr, tag.y,
            -sp, cp * sr, cp * cr, tag.z,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    private fun matrixToEulerDegrees(matrix: List<Double>): EulerDegrees {
        val horizontal = sqrt(matrix[0] * matrix[0] + matrix[4] * matrix[4])
        val singular = horizontal < 1e-9
        val roll: Double
        val pitch = atan2(-matrix[8], horizontal)
        val yaw: Double
        if (singular) {
            roll = atan2(-matrix[6], matrix[5])
            yaw = 0.0
        } else {
            roll = atan2(matrix[9], matrix[10])
            yaw = atan2(matrix[4], matrix[0])
        }
        return EulerDegrees(Math.toDegrees(roll), Math.toDegrees(pitch), Math.toDegrees(yaw))
    }

    private fun eulerDegreesToQuaternion(tag: RobotFieldAprilTag): WpilibQuaternion {
        val roll = Math.toRadians(tag.roll) * 0.5
        val pitch = Math.toRadians(tag.pitch) * 0.5
        val yaw = Math.toRadians(tag.yaw) * 0.5
        val cr = cos(roll)
        val sr = sin(roll)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val cy = cos(yaw)
        val sy = sin(yaw)
        return WpilibQuaternion(
            w = cr * cp * cy + sr * sp * sy,
            x = sr * cp * cy - cr * sp * sy,
            y = cr * sp * cy + sr * cp * sy,
            z = cr * cp * sy - sr * sp * cy,
        )
    }

    private fun quaternionToEulerDegrees(q: WpilibQuaternion): EulerDegrees {
        val roll = atan2(2.0 * (q.w * q.x + q.y * q.z), 1.0 - 2.0 * (q.x * q.x + q.y * q.y))
        val pitchTerm = 2.0 * (q.w * q.y - q.z * q.x)
        val pitch = if (abs(pitchTerm) >= 1.0) Math.copySign(Math.PI / 2.0, pitchTerm) else asin(pitchTerm)
        val yaw = atan2(2.0 * (q.w * q.z + q.x * q.y), 1.0 - 2.0 * (q.y * q.y + q.z * q.z))
        return EulerDegrees(Math.toDegrees(roll), Math.toDegrees(pitch), Math.toDegrees(yaw))
    }

    private fun WpilibQuaternion.normalized(tagId: Int): WpilibQuaternion {
        require(w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite()) {
            "WPILib AprilTag $tagId quaternion must be finite"
        }
        val norm = sqrt(w * w + x * x + y * y + z * z)
        require(norm > 1e-12) { "WPILib AprilTag $tagId quaternion must be non-zero" }
        return WpilibQuaternion(w / norm, x / norm, y / norm, z / norm)
    }

    private data class EulerDegrees(val roll: Double, val pitch: Double, val yaw: Double)

    private const val MATRIX_SIZE = 16
    private const val MILLIMETERS_PER_METER = 1000.0
}

private data class LimelightFiducial(
    val id: Int = 0,
    val family: String = "",
    val size: Double = 0.0,
    val transform: List<Double> = emptyList(),
    val unique: Int = 1,
)

private data class LimelightFmap(val fiducials: List<LimelightFiducial> = emptyList())

private data class WpilibLayout(
    val field: WpilibField = WpilibField(),
    val tags: List<WpilibTag> = emptyList(),
)

private data class WpilibField(val length: Double = 0.0, val width: Double = 0.0)

private data class WpilibTag(
    @SerializedName("ID") val id: Int = 0,
    val pose: WpilibPose = WpilibPose(),
)

private data class WpilibPose(
    val translation: WpilibTranslation = WpilibTranslation(),
    val rotation: WpilibRotation = WpilibRotation(),
)

private data class WpilibTranslation(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
)

private data class WpilibRotation(
    val quaternion: WpilibQuaternion = WpilibQuaternion(),
)

private data class WpilibQuaternion(
    @SerializedName("W") val w: Double = 1.0,
    @SerializedName("X") val x: Double = 0.0,
    @SerializedName("Y") val y: Double = 0.0,
    @SerializedName("Z") val z: Double = 0.0,
)
