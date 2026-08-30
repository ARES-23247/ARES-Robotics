package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FTCCoordinateSystem
import com.ares.analytics.shared.FieldImageConfig
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.areslib.state.FieldType
import com.areslib.state.FtcFieldCoordinateSystem
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldElementInstance
import com.areslib.state.RobotFieldElementType
import com.areslib.state.RobotFieldImageConfig
import com.areslib.state.RobotFieldObstacle
import com.areslib.state.RobotFieldPoint
import com.areslib.state.RobotFieldWaypoint

/**
 * Adapter between the editor presentation models and the canonical ARES field document.
 *
 * The presentation models intentionally expose only authorable fields. Canonical properties that
 * are not currently editable (for example obstacle physics or element depth/movability) are copied
 * from the prior document by stable ID so an unrelated editor save cannot erase runtime behavior.
 */
internal object FieldDocumentMapper {
    fun newDocument(league: League, image: FieldImageConfig = defaultImageConfig(league)): RobotFieldConfig =
        RobotFieldConfig(
            id = "${league.name.lowercase()}-field",
            name = "${league.name} Field",
            fieldType = league.toFieldType(),
            widthMeters = image.widthMeters,
            heightMeters = image.heightMeters,
            image = image.toCanonical()
        )

    fun withEditorData(
        base: RobotFieldConfig,
        league: League,
        image: FieldImageConfig,
        obstacles: List<Obstacle>,
        gamePieces: List<GamePiece>,
        gamePieceTypes: List<com.ares.analytics.shared.GamePieceType>,
        aprilTags: List<AprilTagPlacement>,
        fieldWaypoints: List<FieldWaypoint>
    ): RobotFieldConfig {
        require(gamePieceTypes.map { it.id }.distinct().size == gamePieceTypes.size) { "Game-piece catalog IDs must be unique" }
        require(gamePieceTypes.all { it.id.isNotBlank() && it.name.isNotBlank() }) { "Game-piece catalog IDs and names are required" }
        val priorTypes = base.elementTypes.associateBy { it.id }
        val existingTypes = gamePieceTypes.associate { type ->
            type.id to with(this) { type.toCanonical(priorTypes[type.id]) }
        }.toMutableMap()
        val typesByName = existingTypes.values.associateBy { it.name.lowercase() }.toMutableMap()
        val existingElements = base.elements.associateBy { it.id }
        val existingObstacles = base.obstacles.associateBy { it.id }

        val elements = gamePieces.map { piece ->
            val prior = existingElements[piece.id]
            val priorType = prior?.let { existingTypes[it.elementTypeId] }
            val type = piece.typeId?.let { typeId ->
                requireNotNull(existingTypes[typeId]) { "Game piece '${piece.name}' references missing catalog type '$typeId'" }
            } ?: if (priorType?.name == piece.type) {
                priorType
            } else {
                typesByName[piece.type.lowercase()] ?: defaultElementType(piece.type).also {
                    existingTypes[it.id] = it
                    typesByName[it.name.lowercase()] = it
                }
            }
            RobotFieldElementInstance(
                id = piece.id,
                elementTypeId = type.id,
                name = piece.name,
                x = piece.x,
                y = piece.y,
                rotation = prior?.rotation ?: 0.0,
                locked = piece.locked
            )
        }

        return base.copy(
            revision = base.revision + 1L,
            fieldType = league.toFieldType(),
            widthMeters = image.widthMeters,
            heightMeters = image.heightMeters,
            image = image.toCanonical(),
            obstacles = obstacles.map { obstacle -> obstacle.toCanonical(existingObstacles[obstacle.id]) },
            apriltags = aprilTags.map { it.toCanonical() },
            elementTypes = existingTypes.values.sortedBy { it.id },
            elements = elements,
            fieldWaypoints = fieldWaypoints.map { it.toCanonical() }
        )
    }

    fun image(document: RobotFieldConfig): FieldImageConfig {
        val config = document.image
        return FieldImageConfig(
            imagePath = config?.imagePath.orEmpty(),
            rotationDegrees = config?.rotationDegrees ?: 0.0,
            cropLeft = config?.cropLeft ?: 0.0,
            cropRight = config?.cropRight ?: 1.0,
            cropTop = config?.cropTop ?: 0.0,
            cropBottom = config?.cropBottom ?: 1.0,
            widthMeters = document.resolvedWidthMeters,
            heightMeters = document.resolvedHeightMeters,
            ftcCoordinateSystem = when (config?.ftcCoordinateSystem) {
                FtcFieldCoordinateSystem.SQUARE -> FTCCoordinateSystem.SQUARE
                FtcFieldCoordinateSystem.DIAMOND -> FTCCoordinateSystem.DIAMOND
                null -> FTCCoordinateSystem.SQUARE
            }
        )
    }

    fun obstacles(document: RobotFieldConfig): List<Obstacle> = document.obstacles.map { obstacle ->
        when (obstacle.shape.lowercase()) {
            "circle" -> Obstacle.Circle(
                id = obstacle.id,
                name = obstacle.name,
                centerX = obstacle.x,
                centerY = obstacle.y,
                radius = obstacle.width,
                locked = obstacle.locked,
                colorHex = obstacle.color
            )
            "polygon" -> Obstacle.Polygon(
                id = obstacle.id,
                name = obstacle.name,
                vertices = obstacle.points.map { PathPoint(it.x, it.y) },
                locked = obstacle.locked,
                colorHex = obstacle.color
            )
            else -> Obstacle.Rectangle(
                id = obstacle.id,
                name = obstacle.name,
                centerX = obstacle.x,
                centerY = obstacle.y,
                width = obstacle.width,
                height = obstacle.height,
                rotation = obstacle.rotation,
                locked = obstacle.locked,
                colorHex = obstacle.color
            )
        }
    }

    fun gamePieces(document: RobotFieldConfig): List<GamePiece> {
        val types = document.elementTypes.associateBy { it.id }
        return document.elements.map { element ->
            GamePiece(
                id = element.id,
                name = element.name.ifBlank { element.id },
                x = element.x,
                y = element.y,
                type = types[element.elementTypeId]?.name ?: element.elementTypeId,
                typeId = element.elementTypeId,
                locked = element.locked
            )
        }
    }

    fun gamePieceTypes(document: RobotFieldConfig): List<com.ares.analytics.shared.GamePieceType> {
        if (document.elementTypes.isNotEmpty()) {
            return document.elementTypes.map { it.toGamePieceType() }
        }
        val league = if (document.fieldType == FieldType.FTC) League.FTC else League.FRC
        return defaultGamePieceTypes(league)
    }

    fun defaultGamePieceTypes(league: League): List<com.ares.analytics.shared.GamePieceType> = when (league) {
        League.FTC -> listOf(
            com.ares.analytics.shared.GamePieceType("ftc-sample-yellow", "Sample (Yellow)", "box", 0.15, 0.15, 0.05, "#FDD835", 0.20, 0.6, 0.3),
            com.ares.analytics.shared.GamePieceType("ftc-sample-red", "Sample (Red)", "box", 0.15, 0.15, 0.05, "#E53935", 0.20, 0.6, 0.3),
            com.ares.analytics.shared.GamePieceType("ftc-sample-blue", "Sample (Blue)", "box", 0.15, 0.15, 0.05, "#1E88E5", 0.20, 0.6, 0.3),
            com.ares.analytics.shared.GamePieceType("ftc-specimen", "Specimen", "box", 0.15, 0.04, 0.08, "#00ACC1", 0.22, 0.6, 0.2),
            com.ares.analytics.shared.GamePieceType("ftc-decode-ball", "Decode (Ball)", "sphere", 0.15, 0.15, 0.15, "#43A047", 0.15, 0.5, 0.7),
        )
        League.FRC -> listOf(
            com.ares.analytics.shared.GamePieceType("frc-note", "Note", "circle", 0.3556, 0.3556, 0.05, "#F57C00", 0.235, 0.6, 0.3),
            com.ares.analytics.shared.GamePieceType("frc-coral", "Coral", "cylinder", 0.25, 0.10, 0.10, "#9C27B0", 0.30, 0.6, 0.2),
            com.ares.analytics.shared.GamePieceType("frc-algae", "Algae", "sphere", 0.20, 0.20, 0.20, "#26A69A", 0.18, 0.5, 0.6),
        )
    }

    private fun RobotFieldElementType.toGamePieceType(): com.ares.analytics.shared.GamePieceType = com.ares.analytics.shared.GamePieceType(
        id = id,
        name = name.ifBlank { id },
        shape = shape,
        diameter = diameter ?: width,
        width = width,
        height = height,
        colorHex = color,
        massKg = massKg,
        friction = friction,
        restitution = restitution
    )

    fun com.ares.analytics.shared.GamePieceType.toCanonical(
        prior: RobotFieldElementType? = null
    ): RobotFieldElementType = (prior ?: RobotFieldElementType(depth = width, movable = true)).copy(
        id = id,
        name = name,
        shape = shape,
        width = width,
        height = height,
        diameter = diameter,
        color = colorHex,
        massKg = massKg,
        friction = friction,
        restitution = restitution
    )

    fun aprilTags(document: RobotFieldConfig): List<AprilTagPlacement> = document.apriltags.map { tag ->
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
            locked = tag.locked
        )
    }

    fun fieldWaypoints(document: RobotFieldConfig): List<FieldWaypoint> = document.fieldWaypoints.map { waypoint ->
        FieldWaypoint(
            id = waypoint.id,
            name = waypoint.name,
            x = waypoint.x,
            y = waypoint.y,
            headingDegrees = waypoint.headingDegrees,
            locked = waypoint.locked
        )
    }

    fun defaultImageConfig(league: League): FieldImageConfig = when (league) {
        League.FTC -> FieldImageConfig(widthMeters = 3.6576, heightMeters = 3.6576)
        League.FRC -> FieldImageConfig(widthMeters = 16.541, heightMeters = 8.211)
    }

    private fun League.toFieldType(): FieldType = if (this == League.FTC) FieldType.FTC else FieldType.FRC

    private fun FieldImageConfig.toCanonical(): RobotFieldImageConfig = RobotFieldImageConfig(
        // A blank path is the explicit, portable representation of an image-free field. Only the
        // image-import workflow creates field_image.png; inventing that name during an unrelated
        // obstacle or game-piece edit makes every viewer report a file that never existed.
        imagePath = imagePath.trim(),
        rotationDegrees = rotationDegrees,
        cropLeft = cropLeft,
        cropRight = cropRight,
        cropTop = cropTop,
        cropBottom = cropBottom,
        ftcCoordinateSystem = when (ftcCoordinateSystem) {
            FTCCoordinateSystem.DIAMOND -> FtcFieldCoordinateSystem.DIAMOND
            FTCCoordinateSystem.SQUARE -> FtcFieldCoordinateSystem.SQUARE
        }
    )

    private fun Obstacle.toCanonical(prior: RobotFieldObstacle? = null): RobotFieldObstacle = when (this) {
        is Obstacle.Circle -> (prior ?: RobotFieldObstacle()).copy(
            id = id,
            name = name,
            x = centerX,
            y = centerY,
            width = radius,
            height = radius,
            shape = "circle",
            locked = locked,
            color = colorHex
        )
        is Obstacle.Rectangle -> (prior ?: RobotFieldObstacle()).copy(
            id = id,
            name = name,
            x = centerX,
            y = centerY,
            width = width,
            height = height,
            shape = "rectangle",
            rotation = rotation,
            locked = locked,
            color = colorHex
        )
        is Obstacle.Polygon -> (prior ?: RobotFieldObstacle()).copy(
            id = id,
            name = name,
            shape = "polygon",
            points = vertices.map { RobotFieldPoint(it.x, it.y) },
            locked = locked,
            color = colorHex
        )
    }

    private fun AprilTagPlacement.toCanonical(): RobotFieldAprilTag = RobotFieldAprilTag(
        id = tagId,
        name = name,
        family = family,
        sizeMeters = sizeMeters,
        x = x,
        y = y,
        z = z,
        roll = rollDegrees,
        pitch = pitchDegrees,
        yaw = yawDegrees,
        editorId = id,
        locked = locked
    )

    private fun FieldWaypoint.toCanonical(): RobotFieldWaypoint = RobotFieldWaypoint(
        id = id,
        name = name,
        x = x,
        y = y,
        headingDegrees = headingDegrees,
        locked = locked
    )

    private fun defaultElementType(name: String): RobotFieldElementType {
        val normalized = name.lowercase()
        val isNote = "note" in normalized
        val isBall = "ball" in normalized
        val isSample = "sample" in normalized || "specimen" in normalized
        return RobotFieldElementType(
            id = "game-piece-${normalized.replace(Regex("[^a-z0-9]+"), "-").trim('-')}",
            name = name,
            shape = when {
                isSample -> "box"
                isBall -> "sphere"
                else -> "cylinder"
            },
            width = if (isSample) 0.15 else 0.10,
            height = if (isSample) 0.05 else 0.10,
            depth = if (isSample) 0.15 else 0.10,
            diameter = when {
                isNote -> 0.3556
                isBall -> 0.15
                else -> null
            },
            color = when {
                "yellow" in normalized -> "#FDD835"
                "red" in normalized -> "#E53935"
                "blue" in normalized -> "#1E88E5"
                isNote -> "#F57C00"
                else -> "#FFFFFF"
            },
            massKg = when {
                isNote -> 0.235
                isSample -> 0.20
                else -> 0.24
            },
            movable = true
        )
    }
}
