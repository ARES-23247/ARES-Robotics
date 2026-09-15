package com.areslib.state

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.areslib.math.geometry.Quaternion
import com.areslib.math.geometry.Rotation3d
import java.io.IOException
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Supported interchange formats for reviewed AprilTag field layouts. */
enum class AprilTagMapFormat { ARES_FIELD, LIMELIGHT_FMAP, WPILIB_JSON }

/**
 * Parsed AprilTag layout. Raw foreign-format decoders preserve the file's coordinate frame;
 * field-aware decoders transform positions and orientations to the target ARES field frame.
 *
 * WPILib layouts carry field dimensions but omit tag family, name, and physical size. Limelight
 * maps can carry family, size, and field dimensions; tag names are omitted. Callers must surface
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
 * fixed-axis X/Y/Z convention represented by `Rz(yaw) * Ry(pitch) * Rx(roll)`. Limelight matrices
 * are row-major 4x4 transforms and store tag size in millimeters. WPILib rotations are normalized
 * W/X/Y/Z quaternions. This object performs file-format work only and is never used in a periodic
 * robot loop. Foreign layouts must explicitly contain their tag arrays and complete poses;
 * unknown JSON is not an empty layout. Zero ARES dimensions select league defaults; other
 * dimensions must be finite and positive. Omitted optional tag metadata remains unspecified.
 * All imports return caller-owned collections with immutable tag values. Unknown extra JSON
 * fields are ignored. The caller owns file-size limits and must not mutate input lists mid-export.
 */
object AprilTagMapCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val elementAdapter = gson.getAdapter(JsonElement::class.java)

    /**
     * Detects one unambiguous format and parses JSON once. WPILib/ARES imports align blue-wall
     * frames; Limelight transforms are centered in the destination's canonical axes. Returned
     * dimensions use destination X/Y axes. Display-axis preferences are not coordinate frames.
     */
    @JvmStatic
    fun decodeForField(json: String, field: RobotFieldConfig): AprilTagMapImportResult {
        requireFieldDimensions(field)
        val root = parseRoot(json)
        val ares = root.has("schemaVersion") || root.has("apriltags")
        val wpilib = root.has("tags")
        val fmap = root.has("fiducials")
        require(listOf(ares, wpilib, fmap).count { it } == 1) { "AprilTag map must identify exactly one supported format" }
        return when {
            ares -> decodeAresField(root, field)
            wpilib -> AprilTagMapFrames.fromWpilib(decodeWpilib(root), field)
            else -> AprilTagMapFrames.fromFmap(decodeLimelightFmap(root), field)
        }
    }

    @JvmStatic
    fun decodeAresField(json: String): AprilTagMapImportResult = decodeAresField(parseRoot(json))

    private fun decodeAresField(root: JsonObject, target: RobotFieldConfig? = null): AprilTagMapImportResult {
        require(root.integerMember("schemaVersion") == CURRENT_FIELD_SCHEMA_VERSION) {
            "Unsupported ARES field schema version"
        }
        // Check numbers before Gson's tree adapter can coerce strings or truncate identifiers.
        for (name in listOf("widthMeters", "heightMeters")) if (root.has(name)) root.numberMember(name)
        if (root.has("apriltags")) root.arrayMember("apriltags").forEach { item ->
            val tag = item.objectValue("AprilTag")
            tag.integerMember("id")
            for (name in TAG_NUMBERS) if (tag.has(name)) tag.numberMember(name)
            for (name in TAG_STRINGS) if (tag.has(name)) {
                val value = tag.get(name)
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string" }
            }
            if (tag.has("sizeMeters") && !tag.get("sizeMeters").isJsonNull) tag.numberMember("sizeMeters")
        }
        val field = gson.fromJson(root, RobotFieldConfig::class.java)
        requireFieldDimensions(field)
        requireValidTags(field.apriltags)
        val decoded = AprilTagMapImportResult(
            format = AprilTagMapFormat.ARES_FIELD,
            tags = field.apriltags,
            fieldLengthMeters = field.resolvedWidthMeters,
            fieldWidthMeters = field.resolvedHeightMeters,
        )
        return if (target == null) decoded else AprilTagMapFrames.reframe(decoded, field, target)
    }

    @JvmStatic
    fun decodeLimelightFmap(json: String): AprilTagMapImportResult = decodeLimelightFmap(parseRoot(json))

    private fun decodeLimelightFmap(root: JsonObject): AprilTagMapImportResult {
        val length = root.optionalDimension("fieldlength")
        val width = root.optionalDimension("fieldwidth")
        val tags = root.arrayMember("fiducials").map { item ->
            val fiducial = item.objectValue("fiducial")
            val id = fiducial.integerMember("id")
            val values = fiducial.arrayMember("transform")
            require(values.size() == MATRIX_SIZE) {
                "Limelight AprilTag $id must contain one row-major 4x4 transform"
            }
            val transform = values.map { it.numberValue("transform component") }
            requireRigidTransform(transform, id)
            val family = if (fiducial.has("family")) {
                val value = fiducial.get("family")
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "AprilTag family must be a string" }
                value.asString.trim()
            } else ""
            val size = if (fiducial.has("size")) {
                val millimeters = fiducial.numberMember("size")
                val meters = millimeters / MILLIMETERS_PER_METER
                require(millimeters > 0.0 && meters > 0.0) { "AprilTag $id size must be positive and representable in meters" }
                meters
            } else null
            val euler = matrixToEulerDegrees(transform)
            RobotFieldAprilTag(
                id = id,
                family = family,
                sizeMeters = size,
                x = transform[3],
                y = transform[7],
                z = transform[11],
                roll = euler.roll,
                pitch = euler.pitch,
                yaw = euler.yaw,
                editorId = "apriltag-$id",
            )
        }
        requireValidTags(tags)
        return AprilTagMapImportResult(
            format = AprilTagMapFormat.LIMELIGHT_FMAP,
            tags = tags,
            fieldLengthMeters = length,
            fieldWidthMeters = width,
            omittedMetadata = buildSet {
                if (length == null || width == null) add("field dimensions")
                add("tag names")
                if (tags.any { it.family.isBlank() }) add("tag family")
                if (tags.any { it.sizeMeters == null }) add("tag size")
            },
        )
    }

    /**
     * Decodes a Limelight map into the canonical coordinate frame of [field].
     *
     * Limelight `.fmap` transforms use a field-center origin. ARES FTC and XRP fields use that
     * same origin, while ARES FRC fields use a corner origin. Source field dimensions determine
     * that offset; the target dimension is used only for an axis omitted by the source file.
     */
    @JvmStatic
    fun decodeLimelightFmapForField(json: String, field: RobotFieldConfig): AprilTagMapImportResult {
        requireFieldDimensions(field)
        return AprilTagMapFrames.fromFmap(decodeLimelightFmap(json), field)
    }

    @JvmStatic
    fun encodeLimelightFmap(tags: List<RobotFieldAprilTag>): String = encodeFmap(tags, null, null)

    private fun encodeFmap(tags: List<RobotFieldAprilTag>, length: Double?, width: Double?): String {
        requireValidTags(tags)
        val fiducials = tags.sortedBy(RobotFieldAprilTag::id).map { tag ->
            require(tag.family.isNotBlank()) {
                "AprilTag ${tag.id} needs a family before Limelight export"
            }
            val sizeMeters = requireNotNull(tag.sizeMeters) {
                "AprilTag ${tag.id} needs a physical size before Limelight export"
            }
            val sizeMillimeters = sizeMeters * MILLIMETERS_PER_METER
            require(sizeMillimeters.isFinite()) { "AprilTag ${tag.id} size is not representable in millimeters" }
            LimelightFiducial(
                id = tag.id,
                family = tag.family,
                size = sizeMillimeters,
                transform = eulerDegreesToMatrix(tag),
                unique = 1,
            )
        }
        return gson.toJson(LimelightFmap(fiducials, length, width))
    }

    /** Encodes a canonical field using Limelight's field-center coordinate frame. */
    @JvmStatic
    fun encodeLimelightFmap(field: RobotFieldConfig): String {
        requireFieldDimensions(field)
        val tags = if (field.fieldType != FieldType.FRC) {
            field.apriltags
        } else {
            val halfLength = field.resolvedWidthMeters * 0.5
            val halfWidth = field.resolvedHeightMeters * 0.5
            field.apriltags.map { tag -> tag.copy(x = tag.x - halfLength, y = tag.y - halfWidth) }
        }
        return encodeFmap(tags, field.resolvedWidthMeters, field.resolvedHeightMeters)
    }

    /** Reads positions in WPILib's blue-wall corner frame; this raw decoder does not select a league. */
    @JvmStatic
    fun decodeWpilib(json: String): AprilTagMapImportResult = decodeWpilib(parseRoot(json))

    private fun decodeWpilib(root: JsonObject): AprilTagMapImportResult {
        val field = root.get("field").objectValue("field")
        val length = field.numberMember("length")
        val width = field.numberMember("width")
        require(length > 0.0) {
            "WPILib field length must be finite and positive"
        }
        require(width > 0.0) {
            "WPILib field width must be finite and positive"
        }
        val tags = root.arrayMember("tags").map { item ->
            val tag = item.objectValue("tag")
            val id = tag.integerMember("ID")
            val pose = tag.get("pose").objectValue("pose")
            val translation = pose.get("translation").objectValue("translation")
            val rotation = pose.get("rotation").objectValue("rotation").get("quaternion").objectValue("quaternion")
            val quaternion = Quaternion(
                rotation.numberMember("W"), rotation.numberMember("X"),
                rotation.numberMember("Y"), rotation.numberMember("Z"),
            )
            require(quaternion.w != 0.0 || quaternion.x != 0.0 || quaternion.y != 0.0 || quaternion.z != 0.0) {
                "WPILib AprilTag $id quaternion must be non-zero"
            }
            val normalized = Rotation3d(quaternion.normalize())
            RobotFieldAprilTag(
                id = id,
                x = translation.numberMember("x"),
                y = translation.numberMember("y"),
                z = translation.numberMember("z"),
                roll = Math.toDegrees(normalized.x),
                pitch = Math.toDegrees(normalized.y),
                yaw = Math.toDegrees(normalized.z),
                editorId = "apriltag-$id",
            )
        }
        requireValidTags(tags)
        return AprilTagMapImportResult(
            format = AprilTagMapFormat.WPILIB_JSON,
            tags = tags,
            fieldLengthMeters = length,
            fieldWidthMeters = width,
            omittedMetadata = setOf("tag family", "tag size", "tag names"),
        )
    }

    /** Converts a canonical field to WPILib's blue-wall corner frame, including orientation and extents. */
    @JvmStatic
    fun encodeWpilibForField(field: RobotFieldConfig): String {
        requireFieldDimensions(field)
        requireValidTags(field.apriltags)
        return encodeWpilib(AprilTagMapFrames.toWpilib(field))
    }

    /** Serializes stored positions unchanged; callers must supply WPILib-frame coordinates. */
    @JvmStatic
    fun encodeWpilib(field: RobotFieldConfig): String {
        requireFieldDimensions(field)
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
        requireNotNull(tags) { "AprilTags must be a list" }
        val ids = hashSetOf<Int>()
        tags.forEach { tag ->
            requireNotNull(tag) { "AprilTag entries must be objects" }
            requireNotNull(tag.family) { "AprilTag family must be a string" }
            requireNotNull(tag.name) { "AprilTag name must be a string" }
            requireNotNull(tag.editorId) { "AprilTag editorId must be a string" }
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
        val horizontal = hypot(matrix[0], matrix[4])
        val singular = horizontal < 1e-12
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
        val q = Rotation3d(Math.toRadians(tag.roll), Math.toRadians(tag.pitch), Math.toRadians(tag.yaw)).q
        return WpilibQuaternion(q.w, q.x, q.y, q.z)
    }

    private fun requireFieldDimensions(field: RobotFieldConfig) {
        requireNotNull(field.fieldType) { "Field type is required" }
        require(field.widthMeters == 0.0 || field.widthMeters.isFinite() && field.widthMeters > 0.0) {
            "Field length must be finite and positive, or zero for the league default"
        }
        require(field.heightMeters == 0.0 || field.heightMeters.isFinite() && field.heightMeters > 0.0) {
            "Field width must be finite and positive, or zero for the league default"
        }
    }

    private fun requireRigidTransform(m: List<Double>, id: Int) {
        require(abs(m[12]) <= 1e-9 && abs(m[13]) <= 1e-9 && abs(m[14]) <= 1e-9 && abs(m[15] - 1.0) <= 1e-9) {
            "AprilTag $id transform must have homogeneous bottom row [0,0,0,1]"
        }
        // Tolerate decimal rounding in interchange files, not scale, shear or reflections.
        for (a in 0..2) for (b in a..2) {
            val dot = m[a * 4] * m[b * 4] + m[a * 4 + 1] * m[b * 4 + 1] + m[a * 4 + 2] * m[b * 4 + 2]
            require(abs(dot - if (a == b) 1.0 else 0.0) <= 1e-5) { "AprilTag $id transform rotation must be orthonormal" }
        }
        val determinant = m[0] * (m[5] * m[10] - m[6] * m[9]) -
            m[1] * (m[4] * m[10] - m[6] * m[8]) + m[2] * (m[4] * m[9] - m[5] * m[8])
        require(abs(determinant - 1.0) <= 3e-5) { "AprilTag $id transform rotation must be right-handed" }
    }

    private fun parseRoot(json: String): JsonObject {
        // Invoke the adapter directly: Gson.fromJson enables leniency on older FTC Gson versions.
        // JsonReader's legacy strict mode is available on both the SDK runtime and desktop Gson.
        val value = try {
            JsonReader(StringReader(json)).use { reader ->
                @Suppress("DEPRECATION")
                reader.isLenient = false
                elementAdapter.read(reader).also {
                    require(reader.peek() == JsonToken.END_DOCUMENT) { "AprilTag map must contain one JSON value" }
                }
            }
        } catch (failure: JsonParseException) {
            throw IllegalArgumentException("Invalid AprilTag map JSON", failure)
        } catch (failure: IOException) {
            throw IllegalArgumentException("Invalid AprilTag map JSON", failure)
        }
        return value.objectValue("map")
    }

    private fun JsonElement?.objectValue(label: String): JsonObject {
        require(this != null && isJsonObject) { "$label must be an object" }
        return asJsonObject
    }

    private fun JsonObject.arrayMember(name: String) = get(name).let { value ->
        require(value != null && value.isJsonArray) { "$name must be an array" }
        value.asJsonArray
    }

    private fun JsonElement?.numberValue(label: String): Double {
        require(this != null && isJsonPrimitive && asJsonPrimitive.isNumber) { "$label must be a number" }
        return asDouble.also { require(it.isFinite()) { "$label must be finite" } }
    }

    private fun JsonObject.numberMember(name: String): Double = get(name).numberValue(name)

    private fun JsonObject.optionalDimension(name: String): Double? = if (!has(name)) null else {
        numberMember(name).also { require(it > 0.0) { "$name must be positive" } }
    }

    private fun JsonObject.integerMember(name: String): Int {
        get(name).numberValue(name)
        return try { get(name).asBigDecimal.intValueExact() }
        catch (failure: ArithmeticException) { throw IllegalArgumentException("$name must be an exact 32-bit integer", failure) }
    }

    private data class EulerDegrees(val roll: Double, val pitch: Double, val yaw: Double)

    private const val MATRIX_SIZE = 16
    private const val MILLIMETERS_PER_METER = 1000.0
    private val TAG_NUMBERS = arrayOf("x", "y", "z", "roll", "pitch", "yaw")
    private val TAG_STRINGS = arrayOf("family", "name", "editorId")
}

// Output-only wire DTOs. No implicit pose or tag defaults are used during import.
private data class LimelightFiducial(
    val id: Int,
    val family: String,
    val size: Double,
    val transform: List<Double>,
    val unique: Int,
)

private data class LimelightFmap(
    val fiducials: List<LimelightFiducial>,
    val fieldlength: Double?,
    val fieldwidth: Double?,
)

private data class WpilibLayout(
    val field: WpilibField,
    val tags: List<WpilibTag>,
)

private data class WpilibField(val length: Double, val width: Double)

private data class WpilibTag(
    @SerializedName("ID") val id: Int,
    val pose: WpilibPose,
)

private data class WpilibPose(
    val translation: WpilibTranslation,
    val rotation: WpilibRotation,
)

private data class WpilibTranslation(
    val x: Double,
    val y: Double,
    val z: Double,
)

private data class WpilibRotation(
    val quaternion: WpilibQuaternion,
)

private data class WpilibQuaternion(
    @SerializedName("W") val w: Double,
    @SerializedName("X") val x: Double,
    @SerializedName("Y") val y: Double,
    @SerializedName("Z") val z: Double,
)
