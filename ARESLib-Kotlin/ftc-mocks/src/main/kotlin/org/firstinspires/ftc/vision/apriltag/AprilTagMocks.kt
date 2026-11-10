@file:Suppress("UNUSED_PARAMETER")
package org.firstinspires.ftc.vision.apriltag

import org.firstinspires.ftc.robotcore.external.matrices.VectorF
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion

/**
 * Class implementation for April Tag Pose Ftc.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class AprilTagPoseFtc @JvmOverloads constructor(
    @JvmField val x: Double = 0.0,
    @JvmField val y: Double = 0.0,
    @JvmField val z: Double = 0.0,
    @JvmField val yaw: Double = 0.0,
    @JvmField val pitch: Double = 0.0,
    @JvmField val roll: Double = 0.0,
    @JvmField val range: Double = 0.0,
    @JvmField val bearing: Double = 0.0,
    @JvmField val elevation: Double = 0.0,
)

/**
 * Class implementation for April Tag Detection.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
abstract class AprilTagDetection(
    @JvmField val ftcPose: AprilTagPoseFtc?,
    @JvmField val frameAcquisitionNanoTime: Long,
    @JvmField val distanceUnit: DistanceUnit,
)

/** SDK 12 singleton fields are not members of the common detection base. */
class AprilTagSingleDetection(
    @JvmField val id: Int,
    ftcPose: AprilTagPoseFtc? = null,
    frameAcquisitionNanoTime: Long = 0,
    distanceUnit: DistanceUnit = DistanceUnit.INCH,
) : AprilTagDetection(ftcPose, frameAcquisitionNanoTime, distanceUnit)

class AprilTagClusterDetection(
    @JvmField val percentClusterFound: Int,
    @JvmField val metadata: AprilTagClusterMetadata,
    distanceUnit: DistanceUnit,
    ftcPose: AprilTagPoseFtc?,
    frameAcquisitionNanoTime: Long,
) : AprilTagDetection(ftcPose, frameAcquisitionNanoTime, distanceUnit)

class AprilTagClusterMetadata(@JvmField val name: String, @JvmField val shortName: String)

/**
 * Class implementation for April Tag Processor.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
open class AprilTagProcessor {
    open val freshDetections: ArrayList<AprilTagDetection>? = null

    enum class TagFamily { TAG_36h11, TAG_25h9, TAG_16h5, TAG_standard41h12 }

    class Builder {
        var tagFamily: TagFamily = TagFamily.TAG_36h11
            private set
        var tagLibrary: AprilTagLibrary? = null
            private set

        fun setTagFamily(value: TagFamily): Builder = apply { tagFamily = value }
        fun setTagLibrary(value: AprilTagLibrary): Builder = apply { tagLibrary = value }
        fun build(): AprilTagProcessor = AprilTagProcessor()
    }
}

/** Desktop mock of the immutable SDK metadata record used by VisionPortal. */
class AprilTagMetadata(
    @JvmField val id: Int,
    @JvmField val name: String,
    @JvmField val tagsize: Double,
    @JvmField val fieldPosition: VectorF,
    @JvmField val distanceUnit: DistanceUnit,
    @JvmField val fieldOrientation: Quaternion,
)

/** Desktop mock that preserves the SDK lookup and deterministic builder behavior. */
class AprilTagLibrary private constructor(private val tags: List<AprilTagMetadata>) {
    fun getAllTags(): Array<AprilTagMetadata> = tags.toTypedArray()
    fun lookupTag(id: Int): AprilTagMetadata? = tags.firstOrNull { it.id == id }

    class Builder {
        private val tags = linkedMapOf<Int, AprilTagMetadata>()
        private var allowOverwrite: Boolean = false

        fun setAllowOverwrite(value: Boolean): Builder = apply { allowOverwrite = value }

        fun addTag(
            id: Int,
            name: String,
            size: Double,
            fieldPosition: VectorF,
            distanceUnit: DistanceUnit,
            fieldOrientation: Quaternion,
        ): Builder = apply {
            require(allowOverwrite || id !in tags) { "AprilTag $id is already registered" }
            tags[id] = AprilTagMetadata(id, name, size, fieldPosition, distanceUnit, fieldOrientation)
        }

        fun build(): AprilTagLibrary = AprilTagLibrary(tags.values.toList())
    }
}
