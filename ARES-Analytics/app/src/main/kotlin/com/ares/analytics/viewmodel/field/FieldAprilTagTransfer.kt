package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.AprilTagExportFormat
import com.ares.analytics.viewmodel.AprilTagImportPreview
import com.areslib.state.AprilTagMapCodec
import com.areslib.state.RobotFieldConfig

/** Pure AprilTag map transformations used by the field-editor workflow. */
internal object FieldAprilTagTransfer {
    fun decode(
        content: String,
        fileName: String,
        field: RobotFieldConfig,
        existingTags: List<AprilTagPlacement>,
        league: League,
    ): AprilTagImportPreview {
        val decoded = when {
            fileName.endsWith(".fmap", ignoreCase = true) ->
                AprilTagMapCodec.decodeLimelightFmapForField(content, field)
            else -> runCatching { AprilTagMapCodec.decodeWpilib(content) }
                .recoverCatching { AprilTagMapCodec.decodeAresField(content) }
                .recoverCatching { AprilTagMapCodec.decodeLimelightFmapForField(content, field) }
                .getOrThrow()
        }
        val placements = decoded.tags.map { tag ->
            AprilTagPlacement(
                id = tag.editorId.ifBlank { "apriltag_${tag.id}" },
                tagId = tag.id,
                name = tag.name,
                family = tag.family,
                sizeMeters = tag.sizeMeters,
                x = tag.x,
                y = tag.y,
                z = tag.z,
                rollDegrees = tag.roll,
                pitchDegrees = tag.pitch,
                yawDegrees = tag.yaw,
            )
        }
        val existingIds = existingTags.mapTo(hashSetOf()) { it.tagId }
        val conflicts = placements.count { it.tagId in existingIds }
        val warnings = buildList {
            decoded.omittedMetadata.sorted().forEach { omitted ->
                add("The source does not contain $omitted; review those values before hardware use.")
            }
            if (conflicts > 0) {
                add("$conflicts imported tag ID(s) already exist. Merge keeps the current versions; replace uses the import.")
            }
            if (league == League.FTC && placements.any { it.family.isBlank() || it.sizeMeters == null }) {
                add("FTC VisionPortal requires a family and physical size for every tag before deployment.")
            }
        }
        return AprilTagImportPreview(
            format = decoded.format,
            tags = placements,
            fieldLengthMeters = decoded.fieldLengthMeters,
            fieldWidthMeters = decoded.fieldWidthMeters,
            warnings = warnings,
            sourceName = fileName,
        )
    }

    fun encode(document: RobotFieldConfig, format: AprilTagExportFormat): String = when (format) {
        AprilTagExportFormat.LIMELIGHT_FMAP -> AprilTagMapCodec.encodeLimelightFmap(document)
        AprilTagExportFormat.WPILIB_JSON -> AprilTagMapCodec.encodeWpilib(document)
    }
}
