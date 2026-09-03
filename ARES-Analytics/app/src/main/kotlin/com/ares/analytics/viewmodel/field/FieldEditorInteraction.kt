package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class FieldMeasurementUnit(
    val label: String,
    val abbreviation: String,
    val metersPerUnit: Double
) {
    METERS("Meters", "m", 1.0),
    INCHES("Inches", "in", 0.0254),
    FEET("Feet", "ft", 0.3048);

    fun fromMeters(meters: Double): Double = meters / metersPerUnit
    fun toMeters(value: Double): Double = value * metersPerUnit
}

enum class FieldValidationSeverity { ERROR, WARNING }

data class FieldValidationIssue(
    val severity: FieldValidationSeverity,
    val message: String,
    val elementIds: Set<String> = emptySet()
)

data class FieldEditorLayout(
    val obstacles: List<Obstacle> = emptyList(),
    val gamePieces: List<GamePiece> = emptyList(),
    val aprilTags: List<AprilTagPlacement> = emptyList(),
    val fieldWaypoints: List<FieldWaypoint> = emptyList()
)

enum class FieldPrefabKind { RECTANGLE, CIRCLE, GAME_PIECE, APRIL_TAG, WAYPOINT }

data class FieldPrefab(
    val id: String,
    val name: String,
    val category: String,
    val kind: FieldPrefabKind,
    val gamePieceType: String? = null,
    val widthMeters: Double = 0.5,
    val heightMeters: Double = 0.5,
    val radiusMeters: Double = 0.1
)

object FieldPrefabCatalog {
    private val shared = listOf(
        FieldPrefab("field-wall", "Field wall", "Structure", FieldPrefabKind.RECTANGLE, widthMeters = 1.0, heightMeters = 0.05),
        FieldPrefab("scoring-zone", "Scoring zone", "Zones", FieldPrefabKind.RECTANGLE, widthMeters = 0.6, heightMeters = 0.4),
        FieldPrefab("apriltag", "AprilTag", "Vision", FieldPrefabKind.APRIL_TAG),
        FieldPrefab("named-waypoint", "Named waypoint", "Navigation", FieldPrefabKind.WAYPOINT)
    )

    private val ftc = listOf(
        FieldPrefab("decode-ball", "DECODE ball", "Game pieces", FieldPrefabKind.GAME_PIECE, gamePieceType = "Decode (Ball)"),
        FieldPrefab("yellow-sample", "Yellow sample", "Game pieces", FieldPrefabKind.GAME_PIECE, gamePieceType = "Sample (Yellow)"),
        FieldPrefab("red-sample", "Red sample", "Game pieces", FieldPrefabKind.GAME_PIECE, gamePieceType = "Sample (Red)"),
        FieldPrefab("blue-sample", "Blue sample", "Game pieces", FieldPrefabKind.GAME_PIECE, gamePieceType = "Sample (Blue)"),
        FieldPrefab("specimen", "Specimen", "Game pieces", FieldPrefabKind.GAME_PIECE, gamePieceType = "Specimen"),
        FieldPrefab("truss-post", "Truss post", "Structure", FieldPrefabKind.CIRCLE, radiusMeters = 0.09)
    )

    private val frc = listOf(
        FieldPrefab("note", "Note", "Game pieces", FieldPrefabKind.GAME_PIECE, gamePieceType = "Note"),
        FieldPrefab("high-note", "High note", "Game pieces", FieldPrefabKind.GAME_PIECE, gamePieceType = "High Note"),
        FieldPrefab("stage-post", "Stage post", "Structure", FieldPrefabKind.CIRCLE, radiusMeters = 0.14),
        FieldPrefab("speaker-opening", "Speaker opening", "Scoring", FieldPrefabKind.RECTANGLE, widthMeters = 1.05, heightMeters = 0.15)
    )

    private val xrp = listOf(
        FieldPrefab("orbit-ball", "Orbit Odyssey Ball", "Game pieces", FieldPrefabKind.GAME_PIECE, gamePieceType = "Orbit Ball"),
        FieldPrefab("space-station", "Space Station Obstacle", "Structure", FieldPrefabKind.RECTANGLE, widthMeters = 0.4, heightMeters = 0.4),
        FieldPrefab("moon-crater", "Moon Crater", "Structure", FieldPrefabKind.CIRCLE, radiusMeters = 0.15)
    )

    fun forLeague(league: League): List<FieldPrefab> = shared + when (league) {
        League.FTC -> ftc
        League.FRC -> frc
        League.XRP -> xrp
    }
    fun find(league: League, id: String): FieldPrefab? = forLeague(league).firstOrNull { it.id == id }
}

object FieldEditorValidator {
    fun validate(
        league: League,
        widthMeters: Double,
        heightMeters: Double,
        obstacles: List<Obstacle>,
        gamePieces: List<GamePiece>,
        aprilTags: List<AprilTagPlacement>,
        waypoints: List<FieldWaypoint>
    ): List<FieldValidationIssue> {
        val issues = mutableListOf<FieldValidationIssue>()
        val bounds = FieldBounds.forLeague(league, widthMeters, heightMeters)

        obstacles.forEach { obstacle ->
            when (obstacle) {
                is Obstacle.Circle -> {
                    if (!obstacle.radius.isFinite() || obstacle.radius <= 0.0) {
                        issues += error("${obstacle.name} must have a positive radius", obstacle.id)
                    } else if (!bounds.containsCircle(obstacle.centerX, obstacle.centerY, obstacle.radius)) {
                        issues += warning("${obstacle.name} extends outside the field", obstacle.id)
                    }
                }
                is Obstacle.Rectangle -> {
                    if (!obstacle.width.isFinite() || !obstacle.height.isFinite() || obstacle.width <= 0.0 || obstacle.height <= 0.0) {
                        issues += error("${obstacle.name} must have positive width and height", obstacle.id)
                    } else if (obstacle.axisAlignedBounds()?.let(bounds::contains) != true) {
                        issues += warning("${obstacle.name} extends outside the field", obstacle.id)
                    }
                }
                is Obstacle.Polygon -> {
                    if (obstacle.vertices.size < 3) {
                        issues += error("${obstacle.name} needs at least three vertices", obstacle.id)
                    } else if (obstacle.vertices.any { !bounds.contains(it.x, it.y) }) {
                        issues += warning("${obstacle.name} extends outside the field", obstacle.id)
                    }
                }
            }
        }

        gamePieces.filterNot { bounds.contains(it.x, it.y) }.forEach {
            issues += warning("${it.name} is outside the field", it.id)
        }
        aprilTags.filterNot { bounds.containsWithMargin(it.x, it.y, APRILTAG_PERIMETER_MARGIN_METERS) }.forEach {
            issues += warning(
                "AprilTag ${it.tagId} is more than ${APRILTAG_PERIMETER_MARGIN_METERS} m outside the field perimeter",
                it.id,
            )
        }
        waypoints.filterNot { bounds.contains(it.x, it.y) }.forEach {
            issues += warning("${it.name} is outside the field", it.id)
        }

        aprilTags.groupBy { it.tagId }.filterValues { it.size > 1 }.forEach { (tagId, placements) ->
            issues += FieldValidationIssue(
                severity = FieldValidationSeverity.ERROR,
                message = "AprilTag ID $tagId is used ${placements.size} times",
                elementIds = placements.mapTo(linkedSetOf()) { it.id }
            )
        }

        for (leftIndex in obstacles.indices) {
            val left = obstacles[leftIndex]
            val leftBounds = left.axisAlignedBounds() ?: continue
            for (rightIndex in leftIndex + 1 until obstacles.size) {
                val right = obstacles[rightIndex]
                val rightBounds = right.axisAlignedBounds() ?: continue
                if (leftBounds.overlaps(rightBounds)) {
                    issues += FieldValidationIssue(
                        severity = FieldValidationSeverity.WARNING,
                        message = "${left.name} overlaps ${right.name}",
                        elementIds = setOf(left.id, right.id)
                    )
                }
            }
        }
        return issues
    }

    private fun error(message: String, id: String) =
        FieldValidationIssue(FieldValidationSeverity.ERROR, message, setOf(id))

    private fun warning(message: String, id: String) =
        FieldValidationIssue(FieldValidationSeverity.WARNING, message, setOf(id))

    /**
     * AprilTags are commonly mounted on the field wall with their pose origin at the tag face.
     * Official layouts can therefore place the face a few centimetres beyond the playable-area
     * dimensions. Keep ordinary objects inside the strict bounds, while allowing reviewed
     * perimeter mounts without teaching students that the official map is invalid.
     */
    internal const val APRILTAG_PERIMETER_MARGIN_METERS = 0.25
}

private data class FieldBounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double) {
    fun contains(x: Double, y: Double): Boolean = x.isFinite() && y.isFinite() && x in minX..maxX && y in minY..maxY
    fun containsWithMargin(x: Double, y: Double, margin: Double): Boolean =
        x.isFinite() && y.isFinite() &&
            x in (minX - margin)..(maxX + margin) &&
            y in (minY - margin)..(maxY + margin)
    fun contains(bounds: AxisAlignedBounds): Boolean =
        bounds.minX.isFinite() && bounds.maxX.isFinite() && bounds.minY.isFinite() && bounds.maxY.isFinite() &&
            bounds.minX >= minX && bounds.maxX <= maxX && bounds.minY >= minY && bounds.maxY <= maxY

    fun containsCircle(x: Double, y: Double, radius: Double): Boolean =
        x - radius >= minX && x + radius <= maxX && y - radius >= minY && y + radius <= maxY

    companion object {
        fun forLeague(league: League, width: Double, height: Double): FieldBounds =
            when (league) {
                League.FTC -> FieldBounds(-width / 2.0, width / 2.0, -height / 2.0, height / 2.0)
                League.FRC, League.XRP -> FieldBounds(0.0, width, 0.0, height)
            }
    }
}

private data class AxisAlignedBounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double) {
    fun overlaps(other: AxisAlignedBounds): Boolean =
        minX < other.maxX && maxX > other.minX && minY < other.maxY && maxY > other.minY
}

private fun Obstacle.axisAlignedBounds(): AxisAlignedBounds? = when (this) {
    is Obstacle.Circle -> AxisAlignedBounds(centerX - radius, centerX + radius, centerY - radius, centerY + radius)
    is Obstacle.Rectangle -> {
        val radians = Math.toRadians(rotation)
        val halfWidth = abs(width * kotlin.math.cos(radians)) / 2.0 + abs(height * kotlin.math.sin(radians)) / 2.0
        val halfHeight = abs(width * kotlin.math.sin(radians)) / 2.0 + abs(height * kotlin.math.cos(radians)) / 2.0
        AxisAlignedBounds(centerX - halfWidth, centerX + halfWidth, centerY - halfHeight, centerY + halfHeight)
    }
    is Obstacle.Polygon -> if (vertices.isEmpty()) null else AxisAlignedBounds(
        vertices.minOf(PathPoint::x),
        vertices.maxOf(PathPoint::x),
        vertices.minOf(PathPoint::y),
        vertices.maxOf(PathPoint::y)
    )
}
