package com.areslib.state

/** Stable validation categories for canonical field documents. */
enum class RobotFieldValidationCode {
    FIELD_TYPE,
    FIELD_DIMENSIONS,
    APRIL_TAGS_REQUIRED,
    APRIL_TAG_DUPLICATE,
    APRIL_TAG_INVALID,
    APRIL_TAG_METADATA,
    OBSTACLE_INVALID,
    ELEMENT_TYPE_INVALID,
    ELEMENT_INVALID,
    WAYPOINT_INVALID,
}

/** A fail-closed canonical field validation error and the editor elements it concerns. */
data class RobotFieldValidationIssue(
    val code: RobotFieldValidationCode,
    val message: String,
    val elementIds: Set<String> = emptySet(),
)

/**
 * Shared, deterministic validation for field documents consumed by robots and desktop tools.
 *
 * Zero dimensions retain their documented meaning of "use the league default". Explicit
 * dimensions must otherwise be finite and positive. This validator performs no I/O and is not
 * intended for a periodic robot loop.
 */
object RobotFieldValidator {
    @JvmStatic
    fun validate(
        config: RobotFieldConfig,
        requiredFieldType: FieldType? = null,
        requireAprilTags: Boolean = false,
    ): List<RobotFieldValidationIssue> = buildList {
        if (requiredFieldType != null && config.fieldType != requiredFieldType) {
            add(
                RobotFieldValidationIssue(
                    RobotFieldValidationCode.FIELD_TYPE,
                    "Canonical season field must declare ${requiredFieldType.name} geometry",
                )
            )
        }

        if (!isValidDimension(config.widthMeters) || !isValidDimension(config.heightMeters)) {
            add(
                RobotFieldValidationIssue(
                    RobotFieldValidationCode.FIELD_DIMENSIONS,
                    "Field dimensions must be finite and positive when explicitly configured",
                )
            )
        }

        if (requireAprilTags && config.apriltags.isEmpty()) {
            add(
                RobotFieldValidationIssue(
                    RobotFieldValidationCode.APRIL_TAGS_REQUIRED,
                    "${requiredFieldType?.name ?: config.fieldType.name} field must declare its AprilTag layout",
                )
            )
        }

        config.apriltags
            .groupBy(RobotFieldAprilTag::id)
            .filterValues { it.size > 1 }
            .forEach { (tagId, tags) ->
                add(
                    RobotFieldValidationIssue(
                        RobotFieldValidationCode.APRIL_TAG_DUPLICATE,
                        "${requiredFieldType?.name ?: config.fieldType.name} field contains duplicate AprilTag IDs",
                        tags.mapTo(linkedSetOf()) { it.editorElementId(tagId) },
                    )
                )
            }

        config.apriltags.filterNot(::isValidAprilTag).forEach { tag ->
            add(
                RobotFieldValidationIssue(
                    RobotFieldValidationCode.APRIL_TAG_INVALID,
                    "${requiredFieldType?.name ?: config.fieldType.name} field contains an invalid AprilTag",
                    setOf(tag.editorElementId(tag.id)),
                )
            )
        }

        if (config.fieldType == FieldType.FTC) {
            config.apriltags.filter { tag -> tag.family.isBlank() || tag.sizeMeters == null }.forEach { tag ->
                add(
                    RobotFieldValidationIssue(
                        RobotFieldValidationCode.APRIL_TAG_METADATA,
                        "FTC AprilTag ${tag.id} needs a family and physical size for VisionPortal generation",
                        setOf(tag.editorElementId(tag.id)),
                    )
                )
            }
            config.apriltags.filter { tag ->
                tag.family.isNotBlank() && canonicalFtcAprilTagFamily(tag.family) == null
            }.forEach { tag ->
                add(
                    RobotFieldValidationIssue(
                        RobotFieldValidationCode.APRIL_TAG_METADATA,
                        "FTC AprilTag ${tag.id} family '${tag.family}' is not supported by VisionPortal",
                        setOf(tag.editorElementId(tag.id)),
                    )
                )
            }
        }

        val obstacleIds = hashSetOf<String>()
        config.obstacles.forEach { obstacle ->
            val validId = obstacle.id.isNotBlank() && obstacleIds.add(obstacle.id)
            val validShape = when (obstacle.shape.lowercase()) {
                "polygon" -> obstacle.points.size >= 3 && obstacle.points.all { it.x.isFinite() && it.y.isFinite() }
                "circle", "rectangle" -> obstacle.width.isFinite() && obstacle.height.isFinite() &&
                    obstacle.width > 0.0 && obstacle.height > 0.0
                else -> false
            }
            val validPhysics = obstacle.x.isFinite() && obstacle.y.isFinite() && obstacle.rotation.isFinite() &&
                obstacle.friction.isFinite() && obstacle.friction >= 0.0 &&
                obstacle.restitution.isFinite() && obstacle.restitution in 0.0..1.0
            if (!validId || !validShape || !validPhysics) {
                add(
                    RobotFieldValidationIssue(
                        RobotFieldValidationCode.OBSTACLE_INVALID,
                        "Field contains an invalid obstacle",
                        obstacle.id.takeIf(String::isNotBlank)?.let(::setOf) ?: emptySet(),
                    )
                )
            }
        }

        val typeIds = hashSetOf<String>()
        config.elementTypes.forEach { type ->
            val valid = type.id.isNotBlank() && typeIds.add(type.id) &&
                type.shape.lowercase() in VALID_ELEMENT_SHAPES &&
                type.width.isFinite() && type.width > 0.0 &&
                type.height.isFinite() && type.height > 0.0 &&
                type.depth.isFinite() && type.depth > 0.0 &&
                (type.diameter == null || type.diameter.isFinite() && type.diameter > 0.0) &&
                type.massKg.isFinite() && type.massKg > 0.0 &&
                type.friction.isFinite() && type.friction >= 0.0 &&
                type.restitution.isFinite() && type.restitution in 0.0..1.0
            if (!valid) {
                add(
                    RobotFieldValidationIssue(
                        RobotFieldValidationCode.ELEMENT_TYPE_INVALID,
                        "Field contains an invalid game-piece type",
                        type.id.takeIf(String::isNotBlank)?.let(::setOf) ?: emptySet(),
                    )
                )
            }
        }

        val elementIds = hashSetOf<String>()
        config.elements.forEach { element ->
            val valid = element.id.isNotBlank() && elementIds.add(element.id) &&
                element.elementTypeId in typeIds && element.x.isFinite() && element.y.isFinite() &&
                element.rotation.isFinite()
            if (!valid) {
                add(
                    RobotFieldValidationIssue(
                        RobotFieldValidationCode.ELEMENT_INVALID,
                        "Field contains an invalid game-piece placement",
                        element.id.takeIf(String::isNotBlank)?.let(::setOf) ?: emptySet(),
                    )
                )
            }
        }

        val waypointIds = hashSetOf<String>()
        config.fieldWaypoints.forEach { waypoint ->
            val valid = waypoint.id.isNotBlank() && waypointIds.add(waypoint.id) &&
                waypoint.x.isFinite() && waypoint.y.isFinite() && waypoint.headingDegrees.isFinite()
            if (!valid) {
                add(
                    RobotFieldValidationIssue(
                        RobotFieldValidationCode.WAYPOINT_INVALID,
                        "Field contains an invalid waypoint",
                        waypoint.id.takeIf(String::isNotBlank)?.let(::setOf) ?: emptySet(),
                    )
                )
            }
        }
    }

    private fun isValidDimension(value: Double): Boolean = value == 0.0 || value.isFinite() && value > 0.0

    private fun isValidAprilTag(tag: RobotFieldAprilTag): Boolean =
        tag.id > 0 && tag.x.isFinite() && tag.y.isFinite() && tag.z.isFinite() &&
            tag.roll.isFinite() && tag.pitch.isFinite() && tag.yaw.isFinite() &&
            (tag.sizeMeters == null || tag.sizeMeters.isFinite() && tag.sizeMeters > 0.0)

    private fun RobotFieldAprilTag.editorElementId(fallbackId: Int): String =
        editorId.ifBlank { "apriltag-$fallbackId" }

    private val VALID_ELEMENT_SHAPES = setOf("box", "circle", "cylinder", "sphere")
}

/**
 * Converts FTC SDK and Limelight spellings to the detector family name stored by ARES.
 * Returns null rather than guessing when the family is not supported by FTC VisionPortal.
 */
fun canonicalFtcAprilTagFamily(value: String): String? = when (value.trim().lowercase()) {
    "36h11", "tag36h11", "tag_36h11", "apriltag3_36h11_classic" -> "36h11"
    "25h9", "tag25h9", "tag_25h9", "apriltag3_25h9_classic" -> "25h9"
    "16h5", "tag16h5", "tag_16h5", "apriltag3_16h5_classic" -> "16h5"
    "standard41h12", "41h12", "tagstandard41h12", "tag_standard41h12",
    "apriltag3_41h12_standard", "apriltag3_standard41h12" -> "standard41h12"
    else -> null
}
