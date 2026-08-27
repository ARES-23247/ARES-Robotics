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
class AprilTagPoseFtc {
    var x: Double = 0.0 // inches
    var y: Double = 0.0 // inches
    var z: Double = 0.0 // inches
    var pitch: Double = 0.0 // degrees
    var roll: Double = 0.0 // degrees
    var yaw: Double = 0.0 // degrees
}

/**
 * Class implementation for April Tag Detection.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class AprilTagDetection {
    var id: Int = 0
    var ftcPose: AprilTagPoseFtc = AprilTagPoseFtc()
}

/**
 * Class implementation for April Tag Processor.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
open class AprilTagProcessor {
    val freshDetections: List<AprilTagDetection>? = null

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
