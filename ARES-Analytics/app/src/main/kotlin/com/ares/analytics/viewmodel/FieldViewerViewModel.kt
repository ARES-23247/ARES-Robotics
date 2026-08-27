package com.ares.analytics.viewmodel

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.viewmodel.field.FieldPoseBufferManager
import com.ares.analytics.viewmodel.field.FieldTopicSubscriber
import com.ares.analytics.viewmodel.field.FieldCameraGestureController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.ares.analytics.shared.GamePiece

/** Latest live field measurements, expressed in meters and CCW-positive radians. */
data class LivePoseState(
    val trueX: Double = 0.0,
    val trueY: Double = 0.0,
    val trueHeading: Double = 0.0,
    val simHeading: Double? = null,
    val hasTruePoseData: Boolean = false,
    val ekfX: Double? = null,
    val ekfY: Double? = null,
    val ekfHeading: Double? = null,
    val odomX: Double? = null,
    val odomY: Double? = null,
    val odomHeading: Double? = null,
    val visionX: Double? = null,
    val visionY: Double? = null,
    val visionHeading: Double? = null,
    val visionPoses: Map<Int, Double> = emptyMap(),
    val visionHasTarget: Boolean = false,
    val liveGamePieces: Map<Int, GamePiece> = emptyMap(),
    val isConnected: Boolean = false,
    val indicatorLights: Map<String, Double> = emptyMap(),
    val prismLights: Map<String, Double> = emptyMap(),
)

/** User-controlled field-view state; pose samples are kept separately in [LivePoseState]. */
data class FieldViewerState(
    val poseHistory: List<Waypoint> = emptyList(),
    val isRedAlliance: Boolean = true
)

sealed class FieldViewerIntent {
    object ClearTrace : FieldViewerIntent()

    object ToggleAlliance : FieldViewerIntent()
}

/**
 * Coordinates live NT4 pose data and trace buffering. Canonical routine previews belong to the
 * Routine Builder; this live tracker intentionally has no loose PathPlanner compatibility loader.
 */
class FieldViewerViewModel(
    private val nt4ClientService: Nt4ClientService,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(
        FieldViewerState(isRedAlliance = nt4ClientService.selectedRedAlliance.value)
    )
    val state: StateFlow<FieldViewerState> = _state.asStateFlow()

    private val _livePose = MutableStateFlow(LivePoseState())
    val livePose: StateFlow<LivePoseState> = _livePose.asStateFlow()

    private val topicSubscriber = FieldTopicSubscriber(nt4ClientService, scope, _state, _livePose)
    private val poseBufferManager = FieldPoseBufferManager(scope, _state, _livePose)
    val cameraGestureController = FieldCameraGestureController()

    fun onIntent(intent: FieldViewerIntent) {
        scope.launch {
            when (intent) {
                is FieldViewerIntent.ToggleAlliance -> {
                    val isRedAlliance = !_state.value.isRedAlliance
                    nt4ClientService.selectRedAlliance(isRedAlliance)
                    _state.update { it.copy(isRedAlliance = isRedAlliance) }
                }
                is FieldViewerIntent.ClearTrace -> {
                    poseBufferManager.clearTrace()
                }
            }
        }
    }
}
