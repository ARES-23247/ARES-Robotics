package com.ares.analytics.shared

import kotlinx.serialization.Serializable

const val DEFAULT_GEMINI_MODEL = "gemini-3.6-flash"

/** A point in field coordinates, in meters. */
@Serializable
data class PathPoint(val x: Double, val y: Double)

/** FTC field rendering convention selected by the user-authored field image. */
@Serializable
enum class FTCCoordinateSystem { DIAMOND, SQUARE }

/**
 * Describes how `field_image.png` maps to field coordinates.
 * Crop values are normalized fractions; dimensions are meters and rotation is degrees.
 */
@Serializable
data class FieldImageConfig(
    val imagePath: String = "",
    val rotationDegrees: Double = 0.0,
    val cropLeft: Double = 0.0,
    val cropRight: Double = 1.0,
    val cropTop: Double = 0.0,
    val cropBottom: Double = 1.0,
    val widthMeters: Double = 3.65,
    val heightMeters: Double = 3.65,
    val ftcCoordinateSystem: FTCCoordinateSystem = FTCCoordinateSystem.SQUARE
)

/**
 * Editable field collision geometry. Positions and dimensions are meters;
 * [Rectangle.rotation] is degrees counter-clockwise in field coordinates.
 */
@Serializable
sealed class Obstacle {
    abstract val id: String
    abstract val name: String
    abstract val locked: Boolean
    abstract val colorHex: String

    @Serializable
    data class Polygon(
        override val id: String,
        override val name: String,
        val vertices: List<PathPoint>,
        override val locked: Boolean = false,
        override val colorHex: String = "#E53935"
    ) : Obstacle()

    @Serializable
    data class Circle(
        override val id: String,
        override val name: String,
        val centerX: Double,
        val centerY: Double,
        val radius: Double,
        override val locked: Boolean = false,
        override val colorHex: String = "#E53935"
    ) : Obstacle()

    @Serializable
    data class Rectangle(
        override val id: String,
        override val name: String,
        val centerX: Double,
        val centerY: Double,
        val width: Double,
        val height: Double,
        val rotation: Double = 0.0,
        override val locked: Boolean = false,
        override val colorHex: String = "#E53935"
    ) : Obstacle()
}

/** User-authored game-piece placement in field coordinates (meters). */
@Serializable
data class GamePiece(
    val id: String,
    val name: String,
    val x: Double,
    val y: Double,
    val type: String = "Custom",
    /** Stable catalog ID. Null is accepted only while migrating older name-only editor data. */
    val typeId: String? = null,
    val locked: Boolean = false,
    /** Optional live-simulator visual data; authored placements continue to resolve through their catalog. */
    val rotationRadians: Double = 0.0,
    val widthMeters: Double? = null,
    val heightMeters: Double? = null,
    val simulationShape: String? = null,
    val colorRgb: Int? = null,
)

/** Catalog definition of a game-piece archetype with visual and physics properties. */
@Serializable
data class GamePieceType(
    val id: String,
    val name: String,
    val shape: String = "circle", // "circle", "box", "sphere", "cylinder"
    val diameter: Double = 0.15,
    val width: Double = 0.15,
    val height: Double = 0.15,
    val colorHex: String = "#FFEB3B",
    val massKg: Double = 0.20,
    val friction: Double = 0.6,
    val restitution: Double = 0.3
)

/** AprilTag placement in meters with right-handed roll/pitch/yaw in degrees. */
@Serializable
data class AprilTagPlacement(
    val id: String,
    val tagId: Int,
    val x: Double,
    val y: Double,
    val z: Double = 0.5,
    val yawDegrees: Double = 0.0,
    val locked: Boolean = false,
    val name: String = "",
    val family: String = "",
    val sizeMeters: Double? = null,
    val rollDegrees: Double = 0.0,
    val pitchDegrees: Double = 0.0,
)

/** Named field pose in meters with a CCW-positive heading in degrees. */
@Serializable
data class FieldWaypoint(
    val id: String,
    val name: String,
    val x: Double,
    val y: Double,
    val headingDegrees: Double,
    val locked: Boolean = false
)
