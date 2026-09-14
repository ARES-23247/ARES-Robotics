package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.GamePieceTelemetry
import com.ares.analytics.service.GamePieceFrameSnapshot
import com.ares.analytics.service.LegacyGamePieceSnapshot
import com.ares.analytics.service.VisionPoseArraySnapshot
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

/** Strict ordered fallback for flattened replay/scalar frames; each frame needs its own complete header. */
internal class GamePieceFrameAccumulator {
    private var values = DoubleArray(0)
    private var nextIndex = 0
    @Volatile var hasSeenFrame: Boolean = false
        private set

    @Synchronized fun reset() {
        nextIndex = 0
        hasSeenFrame = false
    }

    @Synchronized fun accept(key: String, value: Double): Map<Int, GamePiece>? {
        if (!key.startsWith(GamePieceTelemetry.PREFIX)) return null
        hasSeenFrame = true
        val index = key.removePrefix(GamePieceTelemetry.PREFIX).toIntOrNull()
        if (index == 0) {
            nextIndex = if (value == GamePieceTelemetry.VERSION) 1 else 0
            return null
        }
        if (index == null || index != nextIndex || nextIndex == 0 || !value.isFinite()) {
            nextIndex = 0
            return null
        }
        if (index == 1) {
            val count = GamePieceTelemetry.count(value)
            if (count == null) { nextIndex = 0; return null }
            val size = GamePieceTelemetry.requiredSize(count)
            if (values.size != size) values = DoubleArray(size)
            values[0] = GamePieceTelemetry.VERSION
            values[1] = value
            nextIndex = 2
            return null
        }
        values[index] = value
        if (index == values.lastIndex) {
            nextIndex = 0
            return GamePieceTelemetry.decodeScalars(values)?.pieces
        }
        nextIndex++
        return null
    }

    companion object {
        fun decodeSnapshot(frame: Map<String, Double>, strings: Map<String, String> = emptyMap()): Map<Int, GamePiece>? =
            GamePieceTelemetry.decodeSnapshot(frame, strings)
    }
}

internal fun isFieldPoseTopic(key: String): Boolean = when (key) {
    "ARES/TruePose/0", "ARES/TruePose/1", "ARES/TruePose/2",
    "ARES/EstimatedPose/0", "ARES/EstimatedPose/1", "ARES/EstimatedPose/2",
    "Drive/Pose_X", "Drive/Pose_Y", "Drive/Pose_Heading", "Drive/Drive_Heading",
    "Drive/Odom_X", "Drive/Odom_Y", "Drive/Odom_Heading" -> true
    else -> key.startsWith("ARES/SimulatorPoseFrame/")
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
    private val visionAccumulator = VisionPoseAccumulator()
    private val gamePieceAccumulator = GamePieceFrameAccumulator()
    private val legacyAccumulator = LegacyGamePieceAccumulator()

    private fun currentGamePieceFrame(): GamePieceFrameSnapshot? =
        nt4ClientService.gamePieceFrame.value?.takeIf {
            !nt4ClientService.isReplayActive.value && it.targetEpoch == nt4ClientService.telemetryStore.currentTargetEpoch()
        }

    private fun currentLegacyFrame(): LegacyGamePieceSnapshot? =
        nt4ClientService.legacyGamePieceFrame.value?.takeIf {
            !nt4ClientService.isReplayActive.value && it.targetEpoch == nt4ClientService.telemetryStore.currentTargetEpoch()
        }

    private fun currentVisionFrame(): VisionPoseArraySnapshot? =
        nt4ClientService.visionPoseArrayFrame.value?.takeIf {
            !nt4ClientService.isReplayActive.value && it.targetEpoch == nt4ClientService.telemetryStore.currentTargetEpoch()
        }

    init {
        scope.launch {
            nt4ClientService.isConnected.collect { connected ->
                if (!connected) {
                    poseAccumulator.reset()
                    visionAccumulator.reset()
                    gamePieceAccumulator.reset()
                    legacyAccumulator.reset()
                }
                livePoseFlow.update { currentState ->
                    val current = if (connected) currentState else visionAccumulator.snapshot(poseAccumulator.snapshot(currentState))
                    current.copy(
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

        // Replay rendering owns immutable ReplayFrame snapshots. Clear every live source at
        // mode changes; queued live publications cannot repopulate them while replay is active.
        scope.launch {
            nt4ClientService.isReplayActive.collect {
                poseAccumulator.reset()
                visionAccumulator.reset()
                gamePieceAccumulator.reset()
                legacyAccumulator.reset()
                livePoseFlow.update { state ->
                    visionAccumulator.snapshot(poseAccumulator.snapshot(state))
                        .copy(liveGamePieces = currentGamePieceFrame()?.pieces ?: legacyAccumulator.snapshot())
                }
            }
        }

        // The parent double[] is decoded before it is flattened onto the lossy global telemetry
        // bus. Consuming this StateFlow prevents startup bursts from dropping one array element
        // and committing a new sequence marker with an older staged coordinate.
        scope.launch(processingDispatcher) {
            nt4ClientService.simulatorPoseFrame.collect { frame ->
                if (frame == null) {
                    poseAccumulator.reset()
                    livePoseFlow.update(poseAccumulator::snapshot)
                } else if (frame === nt4ClientService.currentFieldPoseFrame() && poseAccumulator.accept(frame)) {
                    livePoseFlow.update(poseAccumulator::snapshot)
                }
            }
        }

        // Preserve the complete parent independently of scalar telemetry overflow and delivery order.
        scope.launch(processingDispatcher) {
            nt4ClientService.gamePieceFrame.collect { frame ->
                if (frame == null) {
                    gamePieceAccumulator.reset()
                    legacyAccumulator.reset()
                    if (!nt4ClientService.isReplayActive.value) {
                        currentLegacyFrame()?.let(legacyAccumulator::accept)
                        livePoseFlow.update { it.copy(liveGamePieces = legacyAccumulator.snapshot()) }
                    }
                } else if (frame === currentGamePieceFrame()) {
                    livePoseFlow.update { it.copy(liveGamePieces = frame.pieces) }
                }
            }
        }

        scope.launch(processingDispatcher) {
            nt4ClientService.legacyGamePieceFrame.collect { frame ->
                if (frame == null) {
                    legacyAccumulator.reset()
                } else if (frame === currentLegacyFrame()) {
                    legacyAccumulator.accept(frame)
                } else return@collect
                if (currentGamePieceFrame() == null && !gamePieceAccumulator.hasSeenFrame) {
                    livePoseFlow.update { it.copy(liveGamePieces = legacyAccumulator.snapshot()) }
                }
            }
        }

        scope.launch(processingDispatcher) {
            nt4ClientService.visionPoseArrayFrame.collect { frame ->
                if (frame == null) visionAccumulator.clearParent()
                else if (frame === currentVisionFrame()) visionAccumulator.accept(frame)
                else return@collect
                livePoseFlow.update(visionAccumulator::snapshot)
            }
        }

        val initialTargetEpoch = nt4ClientService.telemetryStore.currentTargetEpoch()
        scope.launch(processingDispatcher) {
            var observedEpoch = initialTargetEpoch
            nt4ClientService.telemetryStore.targetEpochs.collect { epoch ->
                if (epoch == observedEpoch) return@collect
                observedEpoch = epoch
                poseAccumulator.reset()
                visionAccumulator.reset()
                gamePieceAccumulator.reset()
                legacyAccumulator.reset()
                nt4ClientService.currentFieldPoseFrame()?.let(poseAccumulator::accept)
                currentLegacyFrame()?.let(legacyAccumulator::accept)
                currentVisionFrame()?.let(visionAccumulator::accept)
                livePoseFlow.update {
                    visionAccumulator.snapshot(poseAccumulator.snapshot(it))
                        .copy(liveGamePieces = currentGamePieceFrame()?.pieces ?: legacyAccumulator.snapshot())
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
                if (!isFieldViewerTopic(key) || !nt4ClientService.isCurrentFieldUpdate(frame)) return@collect
                val value = frame.value

                if (isFieldPoseTopic(key)) {
                    if (poseAccumulator.accept(key, if (frame.stringValue == null) value else Double.NaN)) {
                        livePoseFlow.update(poseAccumulator::snapshot)
                    }
                    return@collect
                }

                if (key.startsWith("Vision/") || key.startsWith("AdvantageScope/VisionPose/")) {
                    if (nt4ClientService.hasReceivedVisionPoseArray &&
                        (key.startsWith("Vision/PoseArray/") || key.startsWith("AdvantageScope/VisionPose/"))) return@collect
                    if (visionAccumulator.accept(frame)) livePoseFlow.update(visionAccumulator::snapshot)
                    return@collect
                }

                if (currentGamePieceFrame() != null) return@collect
                if (key.startsWith("ARES/GamePiecesFrame/")) {
                    if (frame.stringValue != null || !value.isFinite()) return@collect
                    gamePieceAccumulator.accept(key, value)?.let { pieces ->
                        livePoseFlow.update { current -> current.copy(liveGamePieces = pieces) }
                    }
                    return@collect
                }

                if (!gamePieceAccumulator.hasSeenFrame) {
                    if (key != "ARES/GamePieces/Count" && currentLegacyFrame()?.hasParent == true) return@collect
                    legacyAccumulator.accept(frame)?.let { pieces ->
                        livePoseFlow.update { it.copy(liveGamePieces = pieces) }
                    }
                }
            }
        }
    }
}
