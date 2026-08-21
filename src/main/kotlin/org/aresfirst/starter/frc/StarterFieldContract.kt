package org.aresfirst.starter.frc

import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldValidator
import edu.wpi.first.apriltag.AprilTagFieldLayout
import edu.wpi.first.apriltag.AprilTag
import edu.wpi.first.math.geometry.Pose3d
import edu.wpi.first.math.geometry.Rotation3d
import edu.wpi.first.math.geometry.Translation3d

/** One decoded field document shared by simulation, visualization, and robot vision. */
data class StarterFieldContract(
    val config: RobotFieldConfig,
    val aprilTagLayout: AprilTagFieldLayout,
)

/** Fail-closed, unit-testable loader for the checked-in Field Studio document. */
internal object StarterFieldContractLoader {
    var error: String? = null
        private set

    fun load(bytes: ByteArray): StarterFieldContract? {
        error = null
        val config = runCatching { RobotFieldDocument.decode(bytes.decodeToString()) }
            .getOrElse { failure ->
                error = failure.message ?: failure::class.java.simpleName
                return null
            }
        val issues = RobotFieldValidator.validate(
            config = config,
            requiredFieldType = FieldType.FRC,
            requireAprilTags = false,
        )
        if (issues.isNotEmpty()) {
            error = issues.first().message
            return null
        }
        val layout = runCatching { canonicalAprilTagLayout(config) }
            .getOrElse { failure ->
                error = failure.message ?: failure::class.java.simpleName
                return null
            }
        return StarterFieldContract(config, layout)
    }
}

private fun canonicalAprilTagLayout(config: RobotFieldConfig): AprilTagFieldLayout =
    AprilTagFieldLayout(
        config.apriltags.sortedBy { it.id }.map { tag ->
            AprilTag(
                tag.id,
                Pose3d(
                    Translation3d(tag.x, tag.y, tag.z),
                    Rotation3d(
                        Math.toRadians(tag.roll),
                        Math.toRadians(tag.pitch),
                        Math.toRadians(tag.yaw),
                    ),
                ),
            )
        },
        config.resolvedWidthMeters,
        config.resolvedHeightMeters,
    )

internal fun loadStarterFieldContract(bytes: ByteArray): StarterFieldContract? =
    StarterFieldContractLoader.load(bytes)

internal fun unavailableFrcField(): RobotFieldConfig = RobotFieldConfig(
    id = "unavailable-starter-frc-field",
    name = "Unavailable starter FRC field",
    fieldType = FieldType.FRC,
    widthMeters = 16.54175,
    heightMeters = 8.21055,
    apriltags = emptyList(),
)
