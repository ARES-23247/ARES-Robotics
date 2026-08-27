package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.SimulatorPoseFrameSnapshot
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.LivePoseState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Filters the global telemetry bus before field state reduction touches any Compose-facing state. */
internal fun isFieldViewerTopic(key: String): Boolean = when (key) {
    "ARES/TruePose/0", "ARES/TruePose/1", "ARES/TruePose/2",
    "ARES/EstimatedPose/0", "ARES/EstimatedPose/1", "ARES/EstimatedPose/2",
    "Drive/Pose_X", "Drive/Pose_Y", "Drive/Pose_Heading", "Drive/Drive_Heading",
    "Drive/Odom_X", "Drive/Odom_Y", "Drive/Odom_Heading",
    "Vision/HasTarget", "Vision/Pose_X", "Vision/Pose_Y", "Vision/Pose_Heading",
    "ARES/GamePieces/Count" -> true
    else -> key.startsWith("ARES/SimulatorPoseFrame/") ||
        key.startsWith("ARES/GamePiecesFrame/") ||
        key.startsWith("Vision/PoseArray/") ||
        key.startsWith("AdvantageScope/VisionPose/") ||
        key.startsWith("ARES/GamePieces/")
}

/** Stages one atomic typed game-piece frame and commits only at its final sequence element. */
internal class GamePieceFrameAccumulator {
    private var values = DoubleArray(0)
    private var count = -1
    var hasSeenFrame: Boolean = false
        private set

    fun reset() {
        values = DoubleArray(0)
        count = -1
        hasSeenFrame = false
    }

    fun accept(key: String, value: Double): Map<Int, GamePiece>? {
        val index = key.removePrefix(PREFIX).toIntOrNull() ?: return null
        hasSeenFrame = true
        when (index) {
            0 -> {
                if (value != VERSION) reset()
                return null
            }
            1 -> {
                val nextCount = value.toInt()
                if (!value.isFinite() || nextCount < 0 || nextCount > MAX_PIECES || nextCount.toDouble() != value) {
                    reset()
                    return null
                }
                count = nextCount
                val required = HEADER_WIDTH + count * RECORD_WIDTH + SEQUENCE_WIDTH
                if (values.size != required) values = DoubleArray(required)
                values[0] = VERSION
                values[1] = value
                return null
            }
        }
        if (count < 0 || index !in values.indices) return null
        values[index] = value
        if (index != values.lastIndex) return null

        val decoded = linkedMapOf<Int, GamePiece>()
        for (recordIndex in 0 until count) {
            val base = HEADER_WIDTH + recordIndex * RECORD_WIDTH
            val instanceKey = values[base + 0].toLong()
            val typeKey = values[base + 1].toLong()
            val shape = if (values[base + 7].toInt() == SHAPE_BOX) "box" else "circle"
            var mapKey = (instanceKey xor (instanceKey ushr 32)).toInt()
            while (mapKey in decoded) mapKey++
            decoded[mapKey] = GamePiece(
                id = "sim-$instanceKey",
                name = "Simulated ${shape.replaceFirstChar(Char::uppercase)}",
                x = values[base + 2],
                y = values[base + 3],
                type = "Simulated ${shape.replaceFirstChar(Char::uppercase)}",
                typeId = "sim-type-$typeKey",
                rotationRadians = values[base + 4],
                widthMeters = values[base + 5].takeIf { it.isFinite() && it > 0.0 },
                heightMeters = values[base + 6].takeIf { it.isFinite() && it > 0.0 },
                simulationShape = shape,
                colorRgb = values[base + 8].toInt().coerceIn(0, 0xFFFFFF),
            )
        }
        return decoded
    }

    private companion object {
        const val PREFIX = "ARES/GamePiecesFrame/"
        const val VERSION = 2.0
        const val HEADER_WIDTH = 2
        const val RECORD_WIDTH = 9
        const val SEQUENCE_WIDTH = 1
        const val SHAPE_BOX = 1
        const val MAX_PIECES = 10_000
    }
}

internal fun isFieldPoseTopic(key: String): Boolean = when (key) {
    "ARES/TruePose/0", "ARES/TruePose/1", "ARES/TruePose/2",
    "ARES/EstimatedPose/0", "ARES/EstimatedPose/1", "ARES/EstimatedPose/2",
    "Drive/Pose_X", "Drive/Pose_Y", "Drive/Pose_Heading", "Drive/Drive_Heading",
    "Drive/Odom_X", "Drive/Odom_Y", "Drive/Odom_Heading" -> true
    else -> key.startsWith("ARES/SimulatorPoseFrame/")
}

/**
 * Stages the packed simulator pose frame and preserves scalar compatibility for physical robots.
 *
 * NT4 suppresses unchanged scalar values, so no coordinate or heading can safely mark the end of a
 * frame. Current simulators therefore publish `ARES/SimulatorPoseFrame`, whose changing sequence
 * is element 9. Once that packed source appears, legacy pose scalars are ignored and Compose state
 * changes exactly once after all nine pose values have arrived.
 */
internal class FieldPoseFrameAccumulator {
    private var trueX: Double? = null
    private var trueY: Double? = null
    private var trueHeading: Double? = null
    private var ekfX: Double? = null
    private var ekfY: Double? = null
    private var ekfHeading: Double? = null
    private var odomX: Double? = null
    private var odomY: Double? = null
    private var odomHeading: Double? = null
    private var hasCompleteTruePose = false
    private var hasSeenEstimatedPose = false
    private var hasSeenPackedFrame = false
    private var hasSeenAtomicPackedFrame = false

    @Synchronized
    fun reset() {
        trueX = null
        trueY = null
        trueHeading = null
        ekfX = null
        ekfY = null
        ekfHeading = null
        odomX = null
        odomY = null
        odomHeading = null
        hasCompleteTruePose = false
        hasSeenEstimatedPose = false
        hasSeenPackedFrame = false
        hasSeenAtomicPackedFrame = false
    }

    /** Accepts the packed parent value as one immutable sample, bypassing lossy scalar fan-out. */
    @Synchronized
    fun accept(frame: SimulatorPoseFrameSnapshot) {
        trueX = frame.trueX
        trueY = frame.trueY
        trueHeading = frame.trueHeading
        ekfX = frame.ekfX
        ekfY = frame.ekfY
        ekfHeading = frame.ekfHeading
        odomX = frame.odomX
        odomY = frame.odomY
        odomHeading = frame.odomHeading
        hasCompleteTruePose = true
        hasSeenEstimatedPose = true
        hasSeenPackedFrame = true
        hasSeenAtomicPackedFrame = true
    }

    /** Returns true only when the staged values form the next safe Compose render snapshot. */
    @Synchronized
    fun accept(key: String, value: Double): Boolean {
        if (key.startsWith(SIMULATOR_POSE_FRAME_PREFIX)) {
            if (hasSeenAtomicPackedFrame) return false
            val index = key.substringAfterLast('/').toIntOrNull() ?: return false
            hasSeenPackedFrame = true
            when (index) {
                0 -> trueX = value
                1 -> trueY = value
                2 -> trueHeading = value
                3 -> ekfX = value
                4 -> ekfY = value
                5 -> ekfHeading = value
                6 -> odomX = value
                7 -> odomY = value
                8 -> odomHeading = value
                9 -> {
                    hasCompleteTruePose = true
                    hasSeenEstimatedPose = true
                    return true
                }
            }
            return false
        }

        if (hasSeenPackedFrame) return false

        return when (key) {
            "ARES/TruePose/0" -> true.also {
                trueX = value
                hasCompleteTruePose = true
            }
            "ARES/TruePose/1" -> true.also {
                trueY = value
                hasCompleteTruePose = true
            }
            "ARES/TruePose/2" -> true.also {
                trueHeading = value
                hasCompleteTruePose = true
            }
            "ARES/EstimatedPose/0" -> true.also {
                ekfX = value
                hasSeenEstimatedPose = true
            }
            "ARES/EstimatedPose/1" -> true.also {
                ekfY = value
                hasSeenEstimatedPose = true
            }
            "ARES/EstimatedPose/2" -> true.also {
                ekfHeading = value
                hasSeenEstimatedPose = true
            }
            "Drive/Pose_X" -> if (hasCompleteTruePose && hasSeenEstimatedPose) {
                false
            } else {
                true.also { ekfX = value }
            }
            "Drive/Pose_Y" -> if (hasCompleteTruePose && hasSeenEstimatedPose) {
                false
            } else {
                true.also { ekfY = value }
            }
            "Drive/Pose_Heading", "Drive/Drive_Heading" -> if (hasCompleteTruePose && hasSeenEstimatedPose) {
                false
            } else {
                true.also { ekfHeading = value }
            }
            "Drive/Odom_X" -> true.also { odomX = value }
            "Drive/Odom_Y" -> true.also { odomY = value }
            "Drive/Odom_Heading" -> true.also { odomHeading = value }
            else -> false
        }
    }

    @Synchronized
    fun snapshot(current: LivePoseState): LivePoseState = current.copy(
        trueX = trueX ?: current.trueX,
        trueY = trueY ?: current.trueY,
        simHeading = trueHeading ?: current.simHeading,
        trueHeading = trueHeading ?: current.trueHeading,
        hasTruePoseData = hasCompleteTruePose || current.hasTruePoseData,
        ekfX = ekfX ?: current.ekfX,
        ekfY = ekfY ?: current.ekfY,
        ekfHeading = ekfHeading ?: current.ekfHeading,
        odomX = odomX ?: current.odomX,
        odomY = odomY ?: current.odomY,
        odomHeading = odomHeading ?: current.odomHeading,
    )

    private companion object {
        const val SIMULATOR_POSE_FRAME_PREFIX = "ARES/SimulatorPoseFrame/"
    }
}

/** Reduces normalized NT4 topic updates into the field viewer's live-pose state. */
class FieldTopicSubscriber(
    private val nt4ClientService: Nt4ClientService,
    private val scope: CoroutineScope,
    private val stateFlow: MutableStateFlow<FieldViewerState>,
    private val livePoseFlow: MutableStateFlow<LivePoseState>,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val poseAccumulator = FieldPoseFrameAccumulator()
    private val gamePieceAccumulator = GamePieceFrameAccumulator()

    init {
        scope.launch {
            nt4ClientService.isConnected.collect { connected ->
                if (!connected) {
                    poseAccumulator.reset()
                    gamePieceAccumulator.reset()
                }
                livePoseFlow.update { currentState ->
                    currentState.copy(
                        isConnected = connected,
                        hasTruePoseData = if (connected) currentState.hasTruePoseData else false,
                        visionHasTarget = if (connected) currentState.visionHasTarget else false,
                        visionX = if (connected && currentState.visionHasTarget) currentState.visionX else null,
                        visionY = if (connected && currentState.visionHasTarget) currentState.visionY else null,
                        visionHeading = if (connected && currentState.visionHasTarget) currentState.visionHeading else null,
                        visionPoses = if (connected && currentState.visionHasTarget) currentState.visionPoses else emptyMap(),
                        liveGamePieces = if (connected) currentState.liveGamePieces else emptyMap()
                    )
                }
            }
        }

        // Live simulator poses arrive atomically through simulatorPoseFrame, while replay persists
        // and emits the ten flattened array elements. Reset source ownership at each mode boundary
        // so live frames cannot overwrite rewind and replay frames are not rejected as legacy data.
        scope.launch {
            nt4ClientService.isReplayActive.collect {
                poseAccumulator.reset()
            }
        }

        // The parent double[] is decoded before it is flattened onto the lossy global telemetry
        // bus. Consuming this StateFlow prevents startup bursts from dropping one array element
        // and committing a new sequence marker with an older staged coordinate.
        scope.launch(processingDispatcher) {
            nt4ClientService.simulatorPoseFrame.collect { frame ->
                if (frame != null) {
                    poseAccumulator.accept(frame)
                    livePoseFlow.update(poseAccumulator::snapshot)
                }
            }
        }

        // Lighting is normalized once at the NT4 service boundary. Field rendering and the
        // dashboard therefore consume the same typed snapshot for generated and legacy robots.
        scope.launch(processingDispatcher) {
            nt4ClientService.robotLighting.collect { lighting ->
                livePoseFlow.update { current ->
                    current.copy(
                        indicatorLights = lighting.indicatorOutputs,
                        prismLights = lighting.prismOutputs,
                    )
                }
            }
        }

        // The global bus can exceed tens of thousands of frames per second when tuning schemas are
        // announced. Reduce only field-owned topics, and never do that work on Compose's UI thread.
        scope.launch(processingDispatcher) {
            nt4ClientService.telemetryFlow.collect { frame ->
                val key = frame.key
                if (!isFieldViewerTopic(key)) return@collect
                val value = frame.value

                if (isFieldPoseTopic(key)) {
                    if (poseAccumulator.accept(key, value)) {
                        livePoseFlow.update(poseAccumulator::snapshot)
                    }
                    return@collect
                }

                if (key.startsWith("ARES/GamePiecesFrame/")) {
                    gamePieceAccumulator.accept(key, value)?.let { pieces ->
                        livePoseFlow.update { current -> current.copy(liveGamePieces = pieces) }
                    }
                    return@collect
                }

                livePoseFlow.update { current ->
                    var next = current

                    when (key) {
                        "Vision/HasTarget" -> {
                            val hasTarget = value > 0.5
                            next = next.copy(visionHasTarget = hasTarget)
                            if (!hasTarget) {
                                next = next.copy(
                                    visionX = null,
                                    visionY = null,
                                    visionHeading = null,
                                    visionPoses = if (next.visionPoses.isNotEmpty()) emptyMap() else next.visionPoses
                                )
                            }
                        }
                        "Vision/Pose_X" -> if (next.visionHasTarget) next = next.copy(visionX = value)
                        "Vision/Pose_Y" -> if (next.visionHasTarget) next = next.copy(visionY = value)
                        "Vision/Pose_Heading" -> if (next.visionHasTarget) next = next.copy(visionHeading = value)
                    }

                    val isVisionPoseElement = key.startsWith("Vision/PoseArray/") ||
                        key.startsWith("AdvantageScope/VisionPose/")
                    when {
                        !isVisionPoseElement -> Unit
                        !next.visionHasTarget -> {
                            if (next.visionPoses.isNotEmpty()) next = next.copy(visionPoses = emptyMap())
                        }

                        else -> key.substringAfterLast("/").toIntOrNull()
                            ?.takeIf { next.visionPoses[it] != value }
                            ?.let { index ->
                                next = next.copy(visionPoses = next.visionPoses + (index to value))
                            }
                    }

                    if (!gamePieceAccumulator.hasSeenFrame && key == "ARES/GamePieces/Count") {
                        val count = value.toInt().coerceAtLeast(0)
                        val retained = next.liveGamePieces.filterKeys { it in 0 until count }
                        if (retained.size != next.liveGamePieces.size) {
                            next = next.copy(liveGamePieces = retained)
                        }
                    } else if (!gamePieceAccumulator.hasSeenFrame && key.startsWith("ARES/GamePieces/")) {
                        val arrayIdx = key.substringAfterLast("/").toIntOrNull()
                        if (arrayIdx != null) {
                            val pieceIdx = arrayIdx / 7
                            val attributeIdx = arrayIdx % 7
                            val currentPiece = next.liveGamePieces[pieceIdx] ?: GamePiece(
                                id = pieceIdx.toString(),
                                name = "Piece $pieceIdx",
                                x = 0.0,
                                y = 0.0,
                                type = "Decode (Ball)"
                            )
                            val updatedPiece = when (attributeIdx) {
                                0 -> currentPiece.copy(x = value)
                                1 -> currentPiece.copy(y = value)
                                else -> currentPiece
                            }

                            val newPieces = next.liveGamePieces.toMutableMap()
                            newPieces[pieceIdx] = updatedPiece
                            next = next.copy(liveGamePieces = newPieces)
                        }
                    }

                    next
                }

            }
        }
    }
}
