package com.areslib.state

/**
 * Interchange aligns the source and destination blue-wall frames. FRC uses WPILib's corner
 * origin and inward +X. Centered FTC/XRP fields locate that wall with blueDriverStation;
 * display-only xAxisDirection/yAxisDirection do not rotate robot coordinates.
 * Quarter-turns rotate position and pre-multiply orientation by Rz, so roll/pitch remain
 * unchanged and yaw gains the frame rotation. Rectangular extents rotate with the frame.
 */
internal object AprilTagMapFrames {
    fun fromWpilib(decoded: AprilTagMapImportResult, target: RobotFieldConfig): AprilTagMapImportResult =
        reframe(decoded, RobotFieldConfig(fieldType = FieldType.FRC,
            widthMeters = requireNotNull(decoded.fieldLengthMeters),
            heightMeters = requireNotNull(decoded.fieldWidthMeters)), target)

    fun toWpilib(source: RobotFieldConfig): RobotFieldConfig {
        val decoded = AprilTagMapImportResult(AprilTagMapFormat.WPILIB_JSON, source.apriltags,
            source.resolvedWidthMeters, source.resolvedHeightMeters)
        val mapped = reframe(decoded, source, RobotFieldConfig(fieldType = FieldType.FRC))
        return source.copy(fieldType = FieldType.FRC, apriltags = mapped.tags,
            widthMeters = requireNotNull(mapped.fieldLengthMeters),
            heightMeters = requireNotNull(mapped.fieldWidthMeters))
    }

    fun reframe(decoded: AprilTagMapImportResult, source: RobotFieldConfig,
                target: RobotFieldConfig): AprilTagMapImportResult {
        val turns = (blueWallTurns(target) - blueWallTurns(source) + 4) % 4
        val sourceCorner = source.fieldType == FieldType.FRC
        val targetCorner = target.fieldType == FieldType.FRC
        if (turns == 0 && sourceCorner == targetCorner) return decoded
        val sx = source.resolvedWidthMeters
        val sy = source.resolvedHeightMeters
        val tx = if (turns % 2 == 0) sx else sy
        val ty = if (turns % 2 == 0) sy else sx
        val tags = decoded.tags.map { tag ->
            val x = if (sourceCorner) tag.x - sx * 0.5 else tag.x
            val y = if (sourceCorner) tag.y - sy * 0.5 else tag.y
            val rx = when (turns) { 0 -> x; 1 -> -y; 2 -> -x; else -> y }
            val ry = when (turns) { 0 -> y; 1 -> x; 2 -> -y; else -> -x }
            val nx = if (targetCorner) rx + tx * 0.5 else rx
            val ny = if (targetCorner) ry + ty * 0.5 else ry
            require(nx.isFinite() && ny.isFinite()) { "AprilTag ${tag.id} shifted position is not representable" }
            // Reduce before addition so even very large finite degrees retain the frame rotation.
            val yaw = if (turns == 0) tag.yaw else wrapDegrees(tag.yaw % 360.0 + turns * 90.0)
            tag.copy(x = nx, y = ny, yaw = yaw)
        }
        return decoded.copy(tags = tags, fieldLengthMeters = tx, fieldWidthMeters = ty)
    }

    fun fromFmap(decoded: AprilTagMapImportResult, target: RobotFieldConfig): AprilTagMapImportResult {
        if (target.fieldType != FieldType.FRC) return decoded
        // Source dimensions describe the file's origin. Target extents are a fallback only when
        // an axis is absent; replacement can then adopt each supplied dimension independently.
        val hx = (decoded.fieldLengthMeters ?: target.resolvedWidthMeters) * 0.5
        val hy = (decoded.fieldWidthMeters ?: target.resolvedHeightMeters) * 0.5
        return decoded.copy(tags = decoded.tags.map { tag ->
            val x = tag.x + hx
            val y = tag.y + hy
            require(x.isFinite() && y.isFinite()) { "AprilTag ${tag.id} shifted position is not representable" }
            tag.copy(x = x, y = y)
        })
    }

    private fun blueWallTurns(field: RobotFieldConfig): Int = if (field.fieldType == FieldType.FRC) 0 else {
        when (requireNotNull(field.blueDriverStation) { "Blue driver station is required for frame conversion" }) {
            DriverStationSide.WEST -> 0
            DriverStationSide.SOUTH -> 1
            DriverStationSide.EAST -> 2
            DriverStationSide.NORTH -> 3
        }
    }

    private fun wrapDegrees(value: Double): Double {
        var wrapped = value % 360.0
        if (wrapped >= 180.0) wrapped -= 360.0
        if (wrapped < -180.0) wrapped += 360.0
        return wrapped
    }
}
