package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.*
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.flow.Flow

import com.ares.analytics.shared.GamePiece
import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.LivePoseState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val FIELD_POSE_SCALAR_TOPICS = setOf(
    "ARES/TruePose/0", "ARES/TruePose/1", "ARES/TruePose/2",
    "ARES/EstimatedPose/0", "ARES/EstimatedPose/1", "ARES/EstimatedPose/2",
    "Drive/Pose_X", "Drive/Pose_Y", "Drive/Pose_Heading", "Drive/Drive_Heading",
    "Drive/Odom_X", "Drive/Odom_Y", "Drive/Odom_Heading",
)
private val VISION_COORDINATE_TOPICS = listOf("Vision/Pose_X", "Vision/Pose_Y", "Vision/Pose_Heading")
private val FIELD_LATCHED_TOPICS = FIELD_POSE_SCALAR_TOPICS + "Vision/HasTarget" + VISION_COORDINATE_TOPICS

/** Shared topic selection for cached scalar subscriptions and raw array-fragment filtering. */
internal fun isFieldViewerTopic(key: String): Boolean =
    key in FIELD_LATCHED_TOPICS || key == "ARES/GamePieces/Count" ||
        key.startsWith("ARES/SimulatorPoseFrame/") || key.startsWith("ARES/GamePiecesFrame/") ||
        key.startsWith("Vision/PoseArray/") || key.startsWith("AdvantageScope/VisionPose/") ||
        key.startsWith("ARES/GamePieces/")

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

internal fun isFieldPoseTopic(key: String): Boolean =
    key in FIELD_POSE_SCALAR_TOPICS || key.startsWith("ARES/SimulatorPoseFrame/")

/** Reduces current source snapshots under one monitor, independent of collector delivery order. */
class FieldTopicSubscriber(
    private val nt4ClientService: Nt4ClientService,
    scope: CoroutineScope,
    @Suppress("UNUSED_PARAMETER") stateFlow: MutableStateFlow<FieldViewerState>,
    private val livePoseFlow: MutableStateFlow<LivePoseState>,
    processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    // The store reuses these 17 observer objects across view recreation. Scalar coordinates
    // are latched NT4 values, so startup and overflow recovery cannot depend on the raw replay cache.
    private val latchedTopics = FIELD_LATCHED_TOPICS.associateWith(nt4ClientService.telemetryStore::observe)
    private val reductionLock = Any()
    private val poseAccumulator = FieldPoseFrameAccumulator()
    private val visionAccumulator = VisionPoseAccumulator()
    private val gamePieceAccumulator = GamePieceFrameAccumulator()
    private val legacyAccumulator = LegacyGamePieceAccumulator()
    private var epoch = Long.MIN_VALUE
    private var replay = false
    private var connected: Boolean? = null
    private var appliedPose: SimulatorPoseFrameSnapshot? = null
    private var appliedGame: GamePieceFrameSnapshot? = null
    private var appliedLegacy: LegacyGamePieceSnapshot? = null
    private var appliedVision: VisionPoseArraySnapshot? = null
    private var appliedTarget: VisionTargetSnapshot? = null
    private var typedPieces: Map<Int, GamePiece> = emptyMap()
    private var disconnectedParents: List<Any> = emptyList()
    private var lighting: RobotLightingTelemetryState? = null
    private var indicators: Map<String, Double> = emptyMap()
    private var prisms: Map<String, Double> = emptyMap()

    private fun reset() {
        poseAccumulator.reset(); visionAccumulator.reset()
        gamePieceAccumulator.reset(); legacyAccumulator.reset()
        appliedPose = null; appliedGame = null; appliedLegacy = null
        appliedVision = null; appliedTarget = null; typedPieces = emptyMap()
    }

    private fun allowed(parent: Any, targetEpoch: Long): Boolean =
        targetEpoch == epoch && disconnectedParents.none { it === parent }

    private fun restoreVisionCoordinates() {
        for (key in VISION_COORDINATE_TOPICS) {
            latchedTopics.getValue(key).value?.takeIf(nt4ClientService::isCurrentFieldUpdate)
                ?.let(visionAccumulator::accept)
        }
    }

    private fun synchronizeParents() {
        val target = nt4ClientService.visionTargetFrame.value?.takeIf { allowed(it, it.targetEpoch) }
        if (target !== appliedTarget) {
            appliedTarget = target
            if (target != null) {
                visionAccumulator.accept(target)
                if (target.hasTarget) restoreVisionCoordinates()
                // A target-loss generation can clear a parent accepted by an earlier callback.
                appliedVision = null
            }
        }
        val pose = nt4ClientService.simulatorPoseFrame.value?.takeIf { allowed(it, it.targetEpoch) }
        if (pose !== appliedPose) {
            if (pose == null) poseAccumulator.reset() else poseAccumulator.accept(pose)
            appliedPose = pose
        }
        val game = nt4ClientService.gamePieceFrame.value?.takeIf { allowed(it, it.targetEpoch) }
        if (game !== appliedGame) {
            appliedGame = game
            typedPieces = game?.pieces.orEmpty()
        }
        val legacy = nt4ClientService.legacyGamePieceFrame.value?.takeIf { allowed(it, it.targetEpoch) }
        if (legacy !== appliedLegacy) {
            if (legacy == null) legacyAccumulator.reset() else legacyAccumulator.accept(legacy)
            appliedLegacy = legacy
        }
        val vision = nt4ClientService.visionPoseArrayFrame.value?.takeIf { allowed(it, it.targetEpoch) }
        if (vision !== appliedVision) {
            if (vision == null) visionAccumulator.clearParent() else visionAccumulator.accept(vision)
            appliedVision = vision
        }
    }

    private fun reduceFrame(frame: TelemetryFrame) {
        if (!nt4ClientService.isCurrentFieldUpdate(frame) || disconnectedParents.any { it === frame }) return
        val key = frame.key
        val value = if (frame.stringValue == null) frame.value else Double.NaN
        when {
            isFieldPoseTopic(key) -> poseAccumulator.accept(key, value)
            key.startsWith("Vision/") || key.startsWith("AdvantageScope/VisionPose/") -> {
                if (key == "Vision/HasTarget" && appliedTarget != null) return
                if (nt4ClientService.hasReceivedVisionPoseArray &&
                    (key.startsWith("Vision/PoseArray/") || key.startsWith("AdvantageScope/VisionPose/"))) return
                visionAccumulator.accept(frame)
                if (key == "Vision/HasTarget" && frame.stringValue == null && frame.value == 1.0) restoreVisionCoordinates()
            }
            appliedGame != null -> return
            key.startsWith(GamePieceTelemetry.PREFIX) -> {
                // Even an invalid first header owns this source; legacy must not stand in for it.
                gamePieceAccumulator.accept(key, value)?.let { typedPieces = it }
            }
            !gamePieceAccumulator.hasSeenFrame -> {
                if (key != "ARES/GamePieces/Count" && appliedLegacy?.hasParent == true) return
                legacyAccumulator.accept(frame)
            }
        }
    }

    private fun refresh(frame: TelemetryFrame? = null) = synchronized(reductionLock) {
        val nextEpoch = nt4ClientService.telemetryStore.currentTargetEpoch()
        val nextReplay = nt4ClientService.isReplayActive.value
        val nextConnected = nt4ClientService.isConnected.value
        val disconnected = connected == true && !nextConnected
        val resetRequired = epoch != nextEpoch || replay != nextReplay || disconnected
        if (epoch != nextEpoch) {
            reset()
            disconnectedParents = emptyList()
        } else if (replay != nextReplay || disconnected) {
            reset()
            if (disconnected) disconnectedParents = listOfNotNull(
                nt4ClientService.simulatorPoseFrame.value, nt4ClientService.gamePieceFrame.value,
                nt4ClientService.legacyGamePieceFrame.value, nt4ClientService.visionPoseArrayFrame.value,
                nt4ClientService.visionTargetFrame.value,
            ) + latchedTopics.values.mapNotNull { it.value }
        }
        epoch = nextEpoch; replay = nextReplay; connected = nextConnected
        if (!replay) {
            synchronizeParents()
            if (resetRequired && !disconnected) {
                for (topic in latchedTopics.values) topic.value?.let(::reduceFrame)
            }
            if (frame != null) reduceFrame(frame)
        }
        val latestLighting = nt4ClientService.robotLighting.value
        if (lighting !== latestLighting) {
            lighting = latestLighting
            indicators = latestLighting.indicatorOutputs
            prisms = latestLighting.prismOutputs
        }
        val pieces = if (replay) emptyMap() else appliedGame?.pieces
            ?: if (gamePieceAccumulator.hasSeenFrame) typedPieces else legacyAccumulator.snapshot()
        livePoseFlow.update { previous ->
            val current = visionAccumulator.snapshot(poseAccumulator.snapshot(previous))
            val lights = if (replay) emptyMap() else indicators
            val prismOutputs = if (replay) emptyMap() else prisms
            if (current.isConnected == nextConnected && current.liveGamePieces == pieces &&
                current.indicatorLights == lights && current.prismLights == prismOutputs) current
            else current.copy(isConnected = nextConnected, liveGamePieces = pieces,
                indicatorLights = lights, prismLights = prismOutputs)
        }
    }

    init {
        // Signals always reconcile current values, not an old callback payload. No owner/UI
        // dispatcher callback can reset accumulators after a newer source was already applied.
        fun <T> observe(flow: Flow<T>) {
            scope.launch(processingDispatcher) { flow.collect { refresh() } }
        }
        observe(nt4ClientService.isConnected)
        observe(nt4ClientService.isReplayActive)
        observe(nt4ClientService.telemetryStore.targetEpochs)
        observe(nt4ClientService.simulatorPoseFrame)
        observe(nt4ClientService.gamePieceFrame)
        observe(nt4ClientService.legacyGamePieceFrame)
        observe(nt4ClientService.visionTargetFrame)
        observe(nt4ClientService.visionPoseArrayFrame)
        observe(nt4ClientService.robotLighting)
        for (topic in latchedTopics.values) {
            scope.launch(processingDispatcher) { topic.collect { refresh(it) } }
        }
        scope.launch(processingDispatcher) {
            nt4ClientService.telemetryFlow.collect { frame ->
                if (frame.key !in FIELD_LATCHED_TOPICS && isFieldViewerTopic(frame.key)) refresh(frame)
            }
        }
    }
}
