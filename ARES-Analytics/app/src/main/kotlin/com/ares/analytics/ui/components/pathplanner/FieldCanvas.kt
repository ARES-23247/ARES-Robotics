package com.ares.analytics.ui.components.pathplanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.areslib.math.wrapAngle
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.areslib.math.coordinate.CoordinateTransformers
import com.ares.analytics.viewmodel.field.FieldDocumentMapper
import com.ares.analytics.viewmodel.field.FieldImageLoader
import com.areslib.state.RobotFieldDocument
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FieldCanvas(
    league: League,
    waypoints: List<Waypoint>,
    actualPath: List<Waypoint>,
    contextPath: List<Waypoint>? = null,
    contextWaypoints: List<Waypoint>? = null,
    onWaypointsChanged: (List<Waypoint>) -> Unit,
    projectPath: String? = null,
    showPathControls: Boolean = true,
    showObstacleControls: Boolean = true,
    fieldImage: ImageBitmap? = null,
    fieldImageConfig: FieldImageConfig? = null,
    obstacles: List<Obstacle>? = null,
    onObstaclesChanged: ((List<Obstacle>) -> Unit)? = null,
    gamePieces: List<GamePiece>? = null,
    onGamePiecesChanged: ((List<GamePiece>) -> Unit)? = null,
    gamePieceTypes: List<com.ares.analytics.shared.GamePieceType>? = null,
    onGamePieceTypesChanged: ((List<com.ares.analytics.shared.GamePieceType>) -> Unit)? = null,
    aprilTags: List<AprilTagPlacement>? = null,
    onAprilTagsChanged: ((List<AprilTagPlacement>) -> Unit)? = null,
    fieldWaypoints: List<FieldWaypoint>? = null,
    onFieldWaypointsChanged: ((List<FieldWaypoint>) -> Unit)? = null,
    estimatedPose: Waypoint? = null,
    playbackPose: Waypoint? = null,
    visionPoses: List<Waypoint> = emptyList(),
    odomPose: Waypoint? = null,
    showTruePose: Boolean = true,
    showEkfPose: Boolean = true,
    showOdomPose: Boolean = true,
    showVisionPoses: Boolean = true,
    onItemSelected: ((String?, String?) -> Unit)? = null,
    onItemDoubleTapped: ((String, String) -> Unit)? = null,
    initialViewRotation: Float = 0f,
    onViewRotationChanged: ((Float) -> Unit)? = null,
    showToolbar: Boolean = true,
    indicatorLights: List<IndicatorLightRenderState> = emptyList(),
    prismPulseWidthUs: Double? = null,
    autoGoalMode: Boolean = false,
    robotDimensions: RobotDimensions = RobotDimensions.defaultFor(league),
    modifier: Modifier = Modifier
) {
    var localFieldImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var localFieldImageError by remember { mutableStateOf<String?>(null) }
    var localFieldImageConfig by remember { mutableStateOf(FieldImageConfig()) }
    var localFieldConfigLoaded by remember { mutableStateOf(false) }
    var isDraggingHeading by remember { mutableStateOf(false) }
    var isDraggingPrevHeading by remember { mutableStateOf(false) }
    var isDraggingFieldWaypoint by remember { mutableStateOf(false) }
    var isDraggingFieldWaypointHeading by remember { mutableStateOf(false) }
    val textMeasurer = rememberTextMeasurer()
    val pathCache = remember { PathCacheHolder() }
    var contextMenuExpanded by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
    var contextTargetType by remember { mutableStateOf<String?>(null) }
    var contextTargetId by remember { mutableStateOf<String?>(null) }
    var contextTargetIndex by remember { mutableStateOf<Int>(-1) }
    val density = LocalDensity.current
    val splinePoints by remember(waypoints, autoGoalMode) {
        derivedStateOf {
            if (autoGoalMode || waypoints.size < 2) emptyList<Waypoint>() else {
                val list = ArrayList<Waypoint>((waypoints.size - 1) * 30 + 1)
                val density = 30
                for (i in 0 until waypoints.size - 1) {
                    val p0 = waypoints[i]
                    val p1 = waypoints[i + 1]
                    val h0 = resolveHeading(waypoints, i)
                    val h1 = resolveHeading(waypoints, i + 1)
                    val v0x = cos(h0) * p0.nextControlLength
                    val v0y = sin(h0) * p0.nextControlLength
                    val v1x = cos(h1) * p1.prevControlLength
                    val v1y = sin(h1) * p1.prevControlLength

                    for (j in 1..density) {
                        val t = j.toDouble() / density
                        list.add(Waypoint(cubicHermite(p0.x, v0x, p1.x, v1x, t), cubicHermite(p0.y, v0y, p1.y, v1y, t)))
                    }
                }
                list
            }
        }
    }
    val activeImage = fieldImage ?: localFieldImage
    val activeConfig = fieldImageConfig ?: localFieldImageConfig
    val useConfiguredFieldSize = fieldImageConfig != null || localFieldConfigLoaded
    val fieldWidthM = if (useConfiguredFieldSize && activeConfig.widthMeters > 0.0) {
        activeConfig.widthMeters
    } else if (league == League.FTC) {
        CoordinateTransformers.FTC_FIELD_SIZE
    } else {
        CoordinateTransformers.FRC_FIELD_LENGTH
    }
    val fieldHeightM = if (useConfiguredFieldSize && activeConfig.heightMeters > 0.0) {
        activeConfig.heightMeters
    } else if (league == League.FTC) {
        CoordinateTransformers.FTC_FIELD_SIZE
    } else {
        CoordinateTransformers.FRC_FIELD_WIDTH
    }
    var editorMode by remember { mutableStateOf(EditorMode.SELECT) }
    var selectedWaypointIndex by remember { mutableStateOf(-1) }
    var selectedObstacleId by remember { mutableStateOf<String?>(null) }
    var selectedAprilTagId by remember { mutableStateOf<String?>(null) }
    var selectedGamePieceId by remember { mutableStateOf<String?>(null) }
    var selectedFieldWaypointId by remember { mutableStateOf<String?>(null) }
    val availableGamePieceTypes = gamePieceTypes ?: FieldDocumentMapper.defaultGamePieceTypes(league)
    var activeGamePieceType by remember(league, availableGamePieceTypes) {
        mutableStateOf(availableGamePieceTypes.firstOrNull()?.id.orEmpty())
    }
    var zoomScale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var showHeatmap by remember { mutableStateOf(false) }
    var showCostmap by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    val isShiftPressed = windowInfo.keyboardModifiers.isShiftPressed
    var viewRotation by remember(initialViewRotation) { mutableStateOf(initialViewRotation) }
    val localObstacles = remember { mutableStateListOf<Obstacle>() }
    val localGamePieces = remember { mutableStateListOf<GamePiece>() }
    val localAprilTags = remember { mutableStateListOf<AprilTagPlacement>() }
    val localFieldWaypoints = remember { mutableStateListOf<FieldWaypoint>() }
    val activeObstacles = obstacles ?: localObstacles
    val activeGamePieces = gamePieces ?: localGamePieces
    val activeAprilTags = aprilTags ?: localAprilTags
    val activeFieldWaypoints = fieldWaypoints ?: localFieldWaypoints
    val updateObstacles: (List<Obstacle>) -> Unit = { newObstacles ->
        onObstaclesChanged?.invoke(newObstacles) ?: run {
            localObstacles.clear()
            localObstacles.addAll(newObstacles)
        }
    }
    val updateGamePieces: (List<GamePiece>) -> Unit = { newPieces ->
        onGamePiecesChanged?.invoke(newPieces) ?: run {
            localGamePieces.clear()
            localGamePieces.addAll(newPieces)
        }
    }
    val updateAprilTags: (List<AprilTagPlacement>) -> Unit = { newTags ->
        onAprilTagsChanged?.invoke(newTags) ?: run {
            localAprilTags.clear()
            localAprilTags.addAll(newTags)
        }
    }
    val updateFieldWaypoints: (List<FieldWaypoint>) -> Unit = { newWps ->
        onFieldWaypointsChanged?.invoke(newWps) ?: run {
            localFieldWaypoints.clear()
            localFieldWaypoints.addAll(newWps)
        }
    }
    val currentPolygonPoints = remember { mutableStateListOf<PathPoint>() }
    val currentWaypoints by rememberUpdatedState(waypoints)
    val currentActiveObstacles by rememberUpdatedState(activeObstacles)
    val currentActiveGamePieces by rememberUpdatedState(activeGamePieces)
    val currentActiveAprilTags by rememberUpdatedState(activeAprilTags)
    val currentActiveFieldWaypoints by rememberUpdatedState(activeFieldWaypoints)

    LaunchedEffect(projectPath) {
        try {
            if (!projectPath.isNullOrEmpty()) {
                var configuredImagePath = fieldImageConfig?.imagePath
                val documentFile = ProjectLayout.fieldDefinitionFile(projectPath, league)
                if (documentFile.isFile) {
                    val document = RobotFieldDocument.decode(documentFile.readText())
                    if (obstacles == null) updateObstacles(FieldDocumentMapper.obstacles(document))
                    if (gamePieces == null) updateGamePieces(FieldDocumentMapper.gamePieces(document))
                    if (aprilTags == null) updateAprilTags(FieldDocumentMapper.aprilTags(document))
                    if (fieldWaypoints == null) updateFieldWaypoints(FieldDocumentMapper.fieldWaypoints(document))
                    if (fieldImageConfig == null) {
                        localFieldImageConfig = FieldDocumentMapper.image(document)
                        localFieldConfigLoaded = true
                    }
                    configuredImagePath = fieldImageConfig?.imagePath ?: FieldDocumentMapper.image(document).imagePath
                }

                if (fieldImage == null) {
                    FieldImageLoader.load(projectPath, league, configuredImagePath)
                        .onSuccess {
                            localFieldImage = it
                            localFieldImageError = null
                        }
                        .onFailure { error ->
                            localFieldImage = null
                            localFieldImageError = error.message ?: "Field image could not be loaded."
                        }
                }
            }
        } catch (e: Exception) {
            localFieldImageError = e.message ?: "Field configuration could not be loaded."
            e.printStackTrace()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (showToolbar) {
            FieldCanvasToolbar(
                showPathControls = showPathControls,
                showObstacleControls = showObstacleControls,
                league = league,
                editorMode = editorMode,
                onEditorModeChanged = { editorMode = it },
                activeGamePieceType = activeGamePieceType,
                onActiveGamePieceTypeChanged = { activeGamePieceType = it },
                currentPolygonPoints = currentPolygonPoints,
                onCurrentPolygonPointsCleared = { currentPolygonPoints.clear() },
                activeObstacles = activeObstacles,
                updateObstacles = updateObstacles,
                zoomScale = zoomScale,
                onZoomScaleChanged = { zoomScale = it },
                onResetZoomPan = { zoomScale = 1f; panOffset = Offset.Zero },
                showHeatmap = showHeatmap,
                onShowHeatmapChanged = { showHeatmap = it },
                showCostmap = showCostmap,
                onShowCostmapChanged = { showCostmap = it },
                viewRotation = viewRotation,
                onViewRotationChanged = {
                    viewRotation = it
                    onViewRotationChanged?.invoke(it)
                },
                gamePieceTypes = availableGamePieceTypes,
                onGamePieceTypesChanged = onGamePieceTypesChanged,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.aspectRatio(fieldWidthM.toFloat() / fieldHeightM.toFloat())) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AresSurface)
                        .fieldCanvasGestures(
                            autoGoalMode = autoGoalMode,
                            editorMode = editorMode,
                            zoomScale = zoomScale,
                            panOffset = panOffset,
                            viewRotation = viewRotation,
                            fieldWidthM = fieldWidthM,
                            fieldHeightM = fieldHeightM,
                            league = league,
                            robotDimensions = robotDimensions,
                            currentWaypoints = currentWaypoints,
                            currentActiveObstacles = currentActiveObstacles,
                            currentActiveGamePieces = currentActiveGamePieces,
                            currentActiveAprilTags = currentActiveAprilTags,
                            currentActiveFieldWaypoints = currentActiveFieldWaypoints,
                            currentPolygonPoints = currentPolygonPoints,
                            availableGamePieceTypes = availableGamePieceTypes,
                            activeGamePieceType = activeGamePieceType,
                            showObstacleControls = showObstacleControls,
                            isShiftPressed = isShiftPressed,
                            selectedWaypointIndex = selectedWaypointIndex,
                            selectedObstacleId = selectedObstacleId,
                            selectedAprilTagId = selectedAprilTagId,
                            selectedGamePieceId = selectedGamePieceId,
                            selectedFieldWaypointId = selectedFieldWaypointId,
                            onWaypointsChanged = onWaypointsChanged,
                            updateObstacles = updateObstacles,
                            updateGamePieces = updateGamePieces,
                            updateAprilTags = updateAprilTags,
                            updateFieldWaypoints = updateFieldWaypoints,
                            onItemSelected = onItemSelected,
                            onItemDoubleTapped = onItemDoubleTapped,
                            onOpenContextMenu = { offset, targetType, targetIndex, targetId ->
                                contextMenuOffset = with(density) { DpOffset(offset.x.toDp(), offset.y.toDp()) }
                                contextTargetType = targetType
                                contextTargetIndex = targetIndex
                                contextTargetId = targetId
                                contextMenuExpanded = true
                            },
                            onSelectionChanged = { wpIdx, obsId, atId, gpId, fwpId, dHeading, dPrevHeading, dFwp, dFwpHeading ->
                                selectedWaypointIndex = wpIdx
                                selectedObstacleId = obsId
                                selectedAprilTagId = atId
                                selectedGamePieceId = gpId
                                selectedFieldWaypointId = fwpId
                                isDraggingHeading = dHeading
                                isDraggingPrevHeading = dPrevHeading
                                isDraggingFieldWaypoint = dFwp
                                isDraggingFieldWaypointHeading = dFwpHeading
                            },
                        )
                ) {
                    val w = size.width
                    val h = size.height

                    drawContext.canvas.save()
                    if (viewRotation != 0f) {
                        drawContext.transform.rotate(viewRotation, pivot = Offset(w / 2f, h / 2f))
                    }
                drawContext.transform.translate(panOffset.x, panOffset.y)
                drawContext.transform.scale(zoomScale, zoomScale, pivot = Offset.Zero)

                drawFieldBackground(activeImage, activeConfig, w, h)
                if (showHeatmap) HeatmapOverlay.drawHeatmap(this, actualPath, fieldWidthM, fieldHeightM, league)

                drawFieldGrid(w, h, fieldWidthM, fieldHeightM, league, showCostmap = showCostmap)
                drawFtcAllianceStations(w, h, fieldWidthM, fieldHeightM, league, activeConfig)
                if (league == League.FTC) drawCoordinateAxes(w, h, fieldWidthM, fieldHeightM, league, textMeasurer)

                drawCustomObstacles(currentActiveObstacles, w, h, fieldWidthM, fieldHeightM, league, showCostmap = showCostmap)
                drawGamePieces(currentActiveGamePieces, w, h, fieldWidthM, fieldHeightM, league)
                drawAprilTags(currentActiveAprilTags, w, h, fieldWidthM, fieldHeightM, league, textMeasurer)
                drawFieldWaypoints(currentActiveFieldWaypoints, selectedFieldWaypointId, w, h, fieldWidthM, fieldHeightM, league, textMeasurer)
                drawActivePolygonPoints(currentPolygonPoints, w, h, fieldWidthM, fieldHeightM, league)

                if (contextPath != null) {
                    drawContextPath(pathCache, contextPath, contextWaypoints, w, h, fieldWidthM, fieldHeightM, league)
                }

                if (!autoGoalMode) {
                    drawPlannedSpline(pathCache, splinePoints, waypoints, w, h, fieldWidthM, fieldHeightM, league)
                }

                drawActualPathAndDeviations(pathCache, actualPath, waypoints, w, h, fieldWidthM, fieldHeightM, league)
                if (autoGoalMode) {
                    drawAutoGoals(
                        pathCache = pathCache,
                        waypoints = waypoints,
                        selectedWaypointIndex = selectedWaypointIndex,
                        playbackPose = playbackPose,
                        robotDimensions = robotDimensions,
                        w = w,
                        h = h,
                        fieldWidthM = fieldWidthM,
                        fieldHeightM = fieldHeightM,
                        league = league
                    )
                } else {
                    drawRobotRepresentations(
                        pathCache = pathCache,
                        actualPath = actualPath,
                        estimatedPose = estimatedPose,
                        playbackPose = playbackPose,
                        visionPoses = visionPoses,
                        odomPose = odomPose,
                        showTruePose = showTruePose,
                        showEkfPose = showEkfPose,
                        showOdomPose = showOdomPose,
                        showVisionPoses = showVisionPoses,
                        w = w,
                        h = h,
                        fieldWidthM = fieldWidthM,
                        fieldHeightM = fieldHeightM,
                        league = league,
                        indicatorLights = indicatorLights,
                        prismPulseWidthUs = prismPulseWidthUs,
                    )
                    drawWaypoints(pathCache, waypoints, selectedWaypointIndex, isDraggingHeading, isDraggingPrevHeading, w, h, fieldWidthM, fieldHeightM, league)
                }

                drawContext.canvas.restore()
            }
                if (fieldImage == null && localFieldImageError != null) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        color = AresGold.copy(alpha = 0.16f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AresGold),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            localFieldImageError.orEmpty(),
                            color = AresGold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
                if (league == League.FTC) {
                    val coordinateLabel = when (activeConfig.ftcCoordinateSystem) {
                        FTCCoordinateSystem.SQUARE -> "Square field · alliance walls opposite"
                        FTCCoordinateSystem.DIAMOND -> "Diamond field · alliance walls adjacent"
                    }
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        color = AresBackground.copy(alpha = 0.88f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            coordinateLabel,
                            color = AresTextSecondary,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            FieldCanvasContextMenu(
                expanded = contextMenuExpanded && !autoGoalMode,
                onDismissRequest = { contextMenuExpanded = false },
                offset = contextMenuOffset,
                targetType = contextTargetType,
                targetIndex = contextTargetIndex,
                targetId = contextTargetId,
                waypoints = waypoints,
                obstacles = currentActiveObstacles,
                aprilTags = currentActiveAprilTags,
                gamePieces = currentActiveGamePieces,
                fieldWaypoints = currentActiveFieldWaypoints,
                onWaypointsChanged = onWaypointsChanged,
                updateObstacles = updateObstacles,
                updateAprilTags = updateAprilTags,
                updateGamePieces = updateGamePieces,
                updateFieldWaypoints = updateFieldWaypoints,
                onClearSelected = {
                    selectedWaypointIndex = -1
                    selectedObstacleId = null
                    selectedAprilTagId = null
                    selectedGamePieceId = null
                    selectedFieldWaypointId = null
                }
            )
        }
    }
}
