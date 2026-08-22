package com.areslib.state

import com.google.gson.annotations.SerializedName
import java.io.File
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.coordinate.FieldSymmetry

enum class FieldType {
    @SerializedName("ftc") FTC,
    @SerializedName("frc") FRC
}

enum class AxisDirection {
    @SerializedName("up") UP,
    @SerializedName("down") DOWN,
    @SerializedName("left") LEFT,
    @SerializedName("right") RIGHT
}

enum class DriverStationSide {
    @SerializedName("north") NORTH,
    @SerializedName("south") SOUTH,
    @SerializedName("east") EAST,
    @SerializedName("west") WEST
}

enum class FtcFieldCoordinateSystem {
    @SerializedName("diamond") DIAMOND,
    @SerializedName("square") SQUARE
}

enum class ObstacleType {
    @SerializedName("blocking") BLOCKING,
    @SerializedName("ramp") RAMP
}

/**
 * Class implementation for Robot Field Point.
 *
 * Pure Redux state definition and deterministic reducer transition handler.
 */
data class RobotFieldPoint(
    val x: Double = 0.0,
    val y: Double = 0.0
)

/**
 * Class implementation for Robot Field Obstacle.
 *
 * Pure Redux state definition and deterministic reducer transition handler.
 */
data class RobotFieldObstacle(
    val id: String = "",
    val name: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 0.1,
    val height: Double = 0.1,
    val isBlocking: Boolean = true,
    val obstacleType: ObstacleType = ObstacleType.BLOCKING,
    val rampDirection: AxisDirection? = null,
    val shape: String = "rectangle", // "rectangle" or "polygon"
    val points: List<RobotFieldPoint> = emptyList(),
    val friction: Double = 0.5,
    val restitution: Double = 0.3,
    /** Counter-clockwise rotation in degrees. */
    val rotation: Double = 0.0,
    val locked: Boolean = false,
    val color: String = "#E53935"
)

/**
 * Class implementation for Robot Field April Tag.
 *
 * Pure Redux state definition and deterministic reducer transition handler.
 */
data class RobotFieldAprilTag(
    val id: Int = 0,
    /** Human-readable season label such as "Blue reef 18". */
    val name: String = "",
    /** AprilTag family identifier, for example `36h11`; blank means unspecified. */
    val family: String = "",
    /** Physical black-square edge length in meters; null means the source format omitted it. */
    val sizeMeters: Double? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    /** Right-handed roll about +X in degrees. */
    val roll: Double = 0.0,
    /** Right-handed pitch about +Y in degrees. */
    val pitch: Double = 0.0,
    /** Right-handed, CCW-positive yaw about +Z in degrees. */
    val yaw: Double = 0.0,
    val editorId: String = "",
    val locked: Boolean = false
)

/**
 * Class implementation for Robot Field Element Type.
 *
 * Pure Redux state definition and deterministic reducer transition handler.
 */
data class RobotFieldElementType(
    val id: String = "",
    val name: String = "",
    val shape: String = "box", // "box", "cylinder", "sphere"
    val width: Double = 0.1,
    val height: Double = 0.1,
    val depth: Double = 0.1,
    val diameter: Double? = null,
    val color: String = "#FFFFFF",
    val massKg: Double = 1.0,
    val movable: Boolean = false,
    val friction: Double = 0.6,
    val restitution: Double = 0.3
)

/**
 * Class implementation for Robot Field Element Instance.
 *
 * Pure Redux state definition and deterministic reducer transition handler.
 */
data class RobotFieldElementInstance(
    val id: String = "",
    val elementTypeId: String = "",
    val name: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val rotation: Double = 0.0,
    val locked: Boolean = false
)

/** Rendering calibration for the field image stored beside the field document. */
data class RobotFieldImageConfig(
    val imagePath: String = "field_image.png",
    val rotationDegrees: Double = 0.0,
    val cropLeft: Double = 0.0,
    val cropRight: Double = 1.0,
    val cropTop: Double = 0.0,
    val cropBottom: Double = 1.0,
    val ftcCoordinateSystem: FtcFieldCoordinateSystem = FtcFieldCoordinateSystem.SQUARE
)

/** Named field pose in meters with a counter-clockwise heading in degrees. */
data class RobotFieldWaypoint(
    val id: String = "",
    val name: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val headingDegrees: Double = 0.0,
    val locked: Boolean = false
)

/**
 * Class implementation for Robot Field Config.
 *
 * Pure Redux state definition and deterministic reducer transition handler.
 */
data class RobotFieldConfig(
    val schemaVersion: Int = CURRENT_FIELD_SCHEMA_VERSION,
    val revision: Long = 0L,
    val id: String = "",
    val name: String = "",
    val gameYear: String = "",
    val fieldType: FieldType = FieldType.FTC,
    /** Explicit dimensions in meters; zero selects the league default. */
    val widthMeters: Double = 0.0,
    val heightMeters: Double = 0.0,
    val xAxisDirection: AxisDirection = AxisDirection.UP,
    val yAxisDirection: AxisDirection = AxisDirection.LEFT,
    val redDriverStation: DriverStationSide = DriverStationSide.SOUTH,
    val blueDriverStation: DriverStationSide = DriverStationSide.NORTH,
    /** Season-specific Red/Blue field geometry; this is distinct from driver-perspective controls. */
    val allianceSymmetry: FieldSymmetry = FieldSymmetry.ROTATIONAL,
    val obstacles: List<RobotFieldObstacle> = emptyList(),
    val apriltags: List<RobotFieldAprilTag> = emptyList(),
    val elementTypes: List<RobotFieldElementType> = emptyList(),
    val elements: List<RobotFieldElementInstance> = emptyList(),
    val fieldWaypoints: List<RobotFieldWaypoint> = emptyList(),
    val image: RobotFieldImageConfig? = null
) {
    val resolvedWidthMeters: Double
        get() = widthMeters.takeIf { it > 0.0 }
            ?: if (fieldType == FieldType.FTC) 3.6576 else 16.541

    val resolvedHeightMeters: Double
        get() = heightMeters.takeIf { it > 0.0 }
            ?: if (fieldType == FieldType.FTC) 3.6576 else 8.211

    /**
     * Resolves the starting pose based on the alliance's driver station wall.
     * Starts adjacent to the wall facing the field center.
     */
    fun getInitialPose(alliance: Alliance): Pose2d {
        if (fieldType == FieldType.FRC) {
            return if (alliance == Alliance.BLUE) {
                Pose2d(0.5, 4.1055, Rotation2d(0.0))
            } else {
                Pose2d(16.041, 4.1055, Rotation2d(Math.PI))
            }
        }

        val side = if (alliance == Alliance.BLUE) blueDriverStation else redDriverStation
        
        // Calculate coordinate based on which side is the driver wall
        val startX = when (side) {
            DriverStationSide.EAST -> 1.8
            DriverStationSide.WEST -> -1.8
            else -> 0.0
        }
        val startY = when (side) {
            DriverStationSide.NORTH -> 1.8
            DriverStationSide.SOUTH -> -1.8
            else -> 0.0
        }
        
        // Determine initial heading facing the field center
        val headingRad = when (side) {
            DriverStationSide.EAST -> Math.PI
            DriverStationSide.WEST -> 0.0
            DriverStationSide.NORTH -> -Math.PI / 2.0
            DriverStationSide.SOUTH -> Math.PI / 2.0
        }
        
        return Pose2d(startX, startY, Rotation2d(headingRad))
    }

    /**
     * Maps raw driver joystick commands to absolute EKF coordinates based on the configured driver station side.
     */
    fun mapJoystickIntents(joystickForward: Double, joystickLeft: Double, alliance: Alliance): Pair<Double, Double> {
        if (fieldType == FieldType.FRC) {
            return if (alliance == Alliance.BLUE) {
                Pair(joystickForward, joystickLeft)
            } else {
                Pair(-joystickForward, -joystickLeft)
            }
        }

        val side = if (alliance == Alliance.BLUE) blueDriverStation else redDriverStation
        
        var vx = 0.0
        var vy = 0.0
        
        when (side) {
            DriverStationSide.SOUTH -> {
                // Forward is +Y, Left is -X
                vy = joystickForward
                vx = -joystickLeft
            }
            DriverStationSide.NORTH -> {
                // Forward is -Y, Left is +X
                vy = -joystickForward
                vx = joystickLeft
            }
            DriverStationSide.WEST -> {
                // Forward is +X, Left is +Y
                vx = joystickForward
                vy = joystickLeft
            }
            DriverStationSide.EAST -> {
                // Forward is -X, Left is -Y
                vx = -joystickForward
                vy = -joystickLeft
            }
        }
        return Pair(vx, vy)
    }
}

/**
 * Builds the immutable AprilTag pose lookup consumed by localization and simulation.
 *
 * The canonical field document stores display-friendly degrees. Conversion to radians occurs once
 * at the runtime boundary; periodic vision code must reuse the returned map rather than rebuilding
 * it each frame.
 */
fun RobotFieldConfig.aprilTagPoseMap(): Map<Int, com.areslib.math.geometry.Pose3d> =
    LinkedHashMap<Int, com.areslib.math.geometry.Pose3d>(apriltags.size).also { poses ->
        apriltags.forEach { tag ->
            poses[tag.id] = com.areslib.math.geometry.Pose3d(
                com.areslib.math.geometry.Translation3d(tag.x, tag.y, tag.z),
                com.areslib.math.geometry.Rotation3d(
                    Math.toRadians(tag.roll),
                    Math.toRadians(tag.pitch),
                    Math.toRadians(tag.yaw),
                ),
            )
        }
    }

const val CURRENT_FIELD_SCHEMA_VERSION: Int = 2

/** Gson codec for the canonical, versioned field document. */
object RobotFieldDocument {
    private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()

    fun decode(json: String): RobotFieldConfig {
        val config = gson.fromJson(json, RobotFieldConfig::class.java)
            ?: throw IllegalArgumentException("Field document is empty")
        require(config.schemaVersion in 1..CURRENT_FIELD_SCHEMA_VERSION) {
            "Unsupported field schema version ${config.schemaVersion}; supported version is $CURRENT_FIELD_SCHEMA_VERSION"
        }
        return config
    }

    fun encode(config: RobotFieldConfig): String = gson.toJson(config)
}

/**
 * Object implementation for Robot Field Manager.
 *
 * Pure Redux state definition and deterministic reducer transition handler.
 */
object RobotFieldManager {
    // Default fallback layout
    var activeConfig: RobotFieldConfig = RobotFieldConfig(
        name = "Default FTC Field",
        fieldType = FieldType.FTC
    )
        private set

    /**
     * Loads the field config from a JSON file.
     * Useful for loading dynamic configurations copied directly from ARESWEB.
     */
    fun loadFromJsonFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false
            val jsonContent = file.readText()
            val loaded = RobotFieldDocument.decode(jsonContent)
            activeConfig = loaded
            true
        } catch (e: Exception) {
            println("ARES Field Manager Error: Failed to load field json from $filePath: ${e.message}")
            false
        }
    }

    /**
     * Sets the active configuration manually from compiled code.
     */
    fun setActiveConfig(config: RobotFieldConfig) {
        activeConfig = config
    }

    /**
     * Loads AprilTags from a Limelight .fmap file content.
     */
    fun parseFmapContent(jsonContent: String): List<RobotFieldAprilTag> {
        return try {
            AprilTagMapCodec.decodeLimelightFmap(jsonContent).tags
        } catch (e: Exception) {
            println("ARES Field Manager Error: Failed to parse fmap JSON: ${e.message}")
            emptyList()
        }
    }
}
