package com.ares.analytics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.GamepadState
import com.ares.analytics.service.hardware.SubsystemHealthSnapshot
import com.ares.analytics.service.hardware.SubsystemHealthStatus
import com.ares.analytics.shared.League
import com.ares.analytics.ui.components.controls.ControllerCanvas
import com.ares.analytics.ui.components.dashboard.SubsystemHealthContent
import com.ares.analytics.ui.components.core.AresInspectorDrawer
import com.ares.analytics.ui.components.core.AresSpecRow
import com.ares.analytics.ui.components.core.AresSpecSection
import com.ares.analytics.ui.components.core.AresSpecSummaryModal
import com.ares.analytics.ui.components.robotstudio.RobotStudioSelection
import com.ares.analytics.ui.components.robotstudio.SubsystemTreeItem
import com.ares.analytics.ui.components.routine.RoutineBuilderPane
import com.ares.analytics.ui.components.routine.RoutineBuilderResponsiveBody
import com.ares.analytics.ui.components.routine.routineBuilderLayoutPresentation
import com.ares.analytics.ui.screens.RobotStudioWorkspace
import com.ares.analytics.ui.screens.RoutineBuilderHeader
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTheme
import com.ares.analytics.viewmodel.robotstudio.RobotStudioAction
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStage
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageId
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageStatus
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState
import com.areslib.controls.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuilderVisualScreenshotTest {

    private val outputDir = File("build/diagnostics/builder-visual-tests").apply { mkdirs() }

    @Test
    fun renderSubsystemHealthWithExplicitNonColorStatus() {
        val scene = ImageComposeScene(900, 700)
        scene.setContent {
            AresTheme {
                Box(Modifier.fillMaxSize().background(AresBackground).padding(20.dp)) {
                    SubsystemHealthContent(
                        snapshots = listOf(
                            SubsystemHealthSnapshot(
                                subsystemId = "elevator",
                                status = SubsystemHealthStatus.NEEDS_HOMING,
                                issues = listOf("Home the mechanism before commanding motion."),
                                measurements = mapOf("positionMeters" to 0.12),
                                ageMs = 20L,
                            ),
                            SubsystemHealthSnapshot(
                                subsystemId = "flywheel",
                                status = SubsystemHealthStatus.OUTPUT_FAULT,
                                issues = listOf("An output write failed; motion remains latched off until neutral recovery."),
                                measurements = mapOf("velocityRadiansPerSecond" to 0.0),
                                ageMs = 20L,
                            ),
                            SubsystemHealthSnapshot(
                                subsystemId = "intake",
                                status = SubsystemHealthStatus.HEALTHY,
                                issues = emptyList(),
                                measurements = mapOf("piecePresent" to 1.0),
                                ageMs = 20L,
                            ),
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        val file = saveScene(scene, "subsystem_health_explicit_status.png")
        assertTrue(file.length() > 10_000, "Subsystem health screenshot should contain rendered status text")
    }

    @Test
    fun renderRobotStudioAtFullHdWithAllThreePanes() {
        renderRobotStudioWindow(width = 1920, height = 1080, name = "robot_studio_1920x1080.png")
    }

    @Test
    fun renderRobotStudioAtDefaultWindowWithInspectorCollapsed() {
        renderRobotStudioWindow(width = 1440, height = 900, name = "robot_studio_1440x900.png")
    }

    @Test
    fun renderRobotStudioAtCompactHdWithBothSidePanesCollapsed() {
        renderRobotStudioWindow(width = 1280, height = 720, name = "robot_studio_1280x720.png")
    }

    private fun renderRobotStudioWindow(width: Int, height: Int, name: String) {
        val scene = ImageComposeScene(width, height)
        val state = RobotStudioState(
            loading = false,
            projectName = "FTC-23247-GoBilda-2026",
            projectPath = "C:/fixture/robot",
            stages = listOf(
                studioStage(RobotStudioStageId.PROJECT_IDENTITY, "Project & robot identity", RobotStudioStageStatus.READY),
                studioStage(
                    RobotStudioStageId.HARDWARE,
                    "Robot hardware & mechanisms",
                    RobotStudioStageStatus.NEEDS_ACTION,
                    issues = listOf("Review the physical port map before hardware testing."),
                ),
                studioStage(RobotStudioStageId.COORDINATION, "Superstructure coordination", RobotStudioStageStatus.OPTIONAL),
                studioStage(RobotStudioStageId.AUTONOMOUS, "Autonomous catalog & routines", RobotStudioStageStatus.READY),
                studioStage(RobotStudioStageId.CONTROLS, "Driver & operator controls", RobotStudioStageStatus.READY),
                studioStage(RobotStudioStageId.GENERATE_VERIFY, "Verify & build", RobotStudioStageStatus.BLOCKED),
            ),
        )
        scene.setContent {
            AresTheme {
                Row(Modifier.fillMaxSize().background(AresBackground)) {
                    Surface(
                        modifier = Modifier.width(88.dp).fillMaxHeight(),
                        color = AresSurfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
                    ) {
                        Text(
                            "ARES",
                            color = AresCyan,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                    RobotStudioWorkspace(
                        state = state,
                        subsystems = listOf(
                            SubsystemTreeItem("intake", "Intake", status = RobotStudioStageStatus.READY),
                            SubsystemTreeItem("flywheel", "Flywheel", isDraft = true, status = RobotStudioStageStatus.NEEDS_ACTION),
                        ),
                        selection = RobotStudioSelection.Drivetrain,
                        onSelect = {},
                        onAddSubsystem = {},
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().background(AresBackground).padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("DRIVETRAIN · MECANUM", color = AresCyan, fontSize = 12.sp)
                            Text("Visual Robot Builder", color = AresTextPrimary, fontSize = 24.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                repeat(3) { index ->
                                    Surface(
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        color = AresSurfaceElevated,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                    ) {
                                        Text(
                                            listOf("Kinematics", "Hardware & IO", "Localization")[index],
                                            color = AresTextPrimary,
                                            fontSize = 14.sp,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val file = saveScene(scene, name)
        assertTrue(file.length() > 10_000, "Robot Studio screenshot should contain rendered UI content")
    }

    @Test
    fun renderRoutineBuilderAtMediumCenterWidth() {
        renderRoutineBuilderWorkspace(width = 1_100, height = 720, name = "routine_builder_medium_1100x720.png")
    }

    @Test
    fun renderRoutineBuilderAtCompactCenterWidth() {
        renderRoutineBuilderWorkspace(width = 900, height = 720, name = "routine_builder_compact_900x720.png")
    }

    private fun renderRoutineBuilderWorkspace(width: Int, height: Int, name: String) {
        val scene = ImageComposeScene(width, height)
        val presentation = routineBuilderLayoutPresentation(width.toFloat(), largeText = false)
        scene.setContent {
            AresTheme {
                Column(
                    modifier = Modifier.fillMaxSize().background(AresBackground).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RoutineBuilderHeader(
                        projectPath = "C:/fixture/robot",
                        league = League.FTC,
                        stackActions = presentation.stackHeaderActions,
                        onStartTour = {},
                        onChangeProject = {},
                    )
                    RoutineBuilderResponsiveBody(
                        presentation = presentation,
                        selectedPane = RoutineBuilderPane.ROUTINE,
                        onPaneSelected = {},
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        editor = { paneModifier ->
                            Surface(
                                modifier = paneModifier,
                                color = AresSurfaceElevated,
                                border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    "Routine steps and controls",
                                    color = AresTextPrimary,
                                    modifier = Modifier.padding(18.dp),
                                )
                            }
                        },
                        fieldPreview = { paneModifier ->
                            Surface(
                                modifier = paneModifier,
                                color = AresSurfaceElevated,
                                border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    "Field preview",
                                    color = AresTextPrimary,
                                    modifier = Modifier.padding(18.dp),
                                )
                            }
                        },
                    )
                }
            }
        }

        val file = saveScene(scene, name)
        assertTrue(file.length() > 10_000, "Routine Builder screenshot should contain rendered UI content")
    }

    @Test
    fun renderControllerCanvasWithActionPills() {
        val scene = ImageComposeScene(1000, 700)
        val profile = ControllerProfileDocument(
            documentId = "standard_gamepad",
            displayName = "Logitech F310 / Xbox Standard",
            controls = listOf(
                ControllerControlDocument(
                    controlId = "btn_a",
                    displayName = "A Button",
                    surface = ControllerSurfaceDocument.FRONT,
                    type = ControllerControlTypeDocument.BUTTON,
                    anchor = ControllerAnchorDocument(0.78, 0.65),
                ),
                ControllerControlDocument(
                    controlId = "btn_b",
                    displayName = "B Button",
                    surface = ControllerSurfaceDocument.FRONT,
                    type = ControllerControlTypeDocument.BUTTON,
                    anchor = ControllerAnchorDocument(0.85, 0.55),
                ),
                ControllerControlDocument(
                    controlId = "btn_x",
                    displayName = "X Button",
                    surface = ControllerSurfaceDocument.FRONT,
                    type = ControllerControlTypeDocument.BUTTON,
                    anchor = ControllerAnchorDocument(0.71, 0.55),
                ),
                ControllerControlDocument(
                    controlId = "btn_y",
                    displayName = "Y Button",
                    surface = ControllerSurfaceDocument.FRONT,
                    type = ControllerControlTypeDocument.BUTTON,
                    anchor = ControllerAnchorDocument(0.78, 0.45),
                ),
                ControllerControlDocument(
                    controlId = "bumper_r",
                    displayName = "Right Bumper",
                    surface = ControllerSurfaceDocument.FRONT,
                    type = ControllerControlTypeDocument.BUTTON,
                    anchor = ControllerAnchorDocument(0.75, 0.22),
                ),
            ),
        )

        val boundLabels = mapOf(
            "btn_a" to listOf("Intake Forward"),
            "btn_b" to listOf("Eject Gamepiece"),
            "bumper_r" to listOf("High Basket Score", "Auto Target Align"),
        )

        scene.setContent {
            AresTheme {
                Box(Modifier.fillMaxSize().background(AresBackground).padding(20.dp)) {
                    ControllerCanvas(
                        profile = profile,
                        surface = ControllerSurfaceDocument.FRONT,
                        selectedControlId = "bumper_r",
                        chordControlIds = emptySet(),
                        boundControlIds = setOf("btn_a", "btn_b", "bumper_r"),
                        targetPlatform = ControllerInputPlatform.FTC,
                        liveState = GamepadState(),
                        onControlSelected = {},
                        boundActionLabels = boundLabels,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        val image = scene.render()
        val data = image.encodeToData(EncodedImageFormat.PNG)
        if (data != null) {
            val file = File(outputDir, "controller_canvas_badges.png")
            file.writeBytes(data.bytes)
            println("Saved screenshot to: ${file.absolutePath}")
        }
    }

    @Test
    fun renderControllerCanvasWithSameRowStickAxes() {
        val scene = ImageComposeScene(1000, 700)
        fun axis(id: String, label: String, x: Double) = ControllerControlDocument(
            controlId = id,
            displayName = label,
            surface = ControllerSurfaceDocument.FRONT,
            type = ControllerControlTypeDocument.AXIS,
            anchor = ControllerAnchorDocument(x, 0.5),
        )
        val profile = ControllerProfileDocument(
            documentId = "frc_driver",
            displayName = "FRC Driver Station Xbox",
            controls = listOf(
                axis("left_stick_x", "Left stick horizontal", 0.32),
                axis("left_stick_y", "Left stick vertical", 0.40),
                axis("right_stick_y", "Right stick vertical", 0.60),
                axis("right_stick_x", "Right stick horizontal", 0.68),
            ),
        )
        scene.setContent {
            AresTheme {
                Box(Modifier.fillMaxSize().background(AresBackground).padding(20.dp)) {
                    ControllerCanvas(
                        profile = profile,
                        surface = ControllerSurfaceDocument.FRONT,
                        selectedControlId = null,
                        chordControlIds = emptySet(),
                        boundControlIds = profile.controls.mapTo(linkedSetOf()) { it.controlId },
                        targetPlatform = ControllerInputPlatform.FRC,
                        liveState = GamepadState(),
                        onControlSelected = {},
                        boundActionLabels = mapOf(
                            "left_stick_x" to listOf("Strafe left/right"),
                            "left_stick_y" to listOf("Drive forward/back"),
                            "right_stick_x" to listOf("Rotate"),
                            "right_stick_y" to listOf("Manual mechanism"),
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        val file = saveScene(scene, "controller_canvas_same_row_axes.png")
        assertTrue(file.length() > 10_000, "Controller axis screenshot should contain four separated callouts")
    }

    @Test
    fun renderVaderFrontAndRearControllerCanvases() {
        fun button(
            id: String,
            label: String,
            x: Double,
            y: Double,
            surface: ControllerSurfaceDocument = ControllerSurfaceDocument.FRONT,
        ) = ControllerControlDocument(
            controlId = id,
            displayName = label,
            surface = surface,
            type = ControllerControlTypeDocument.BUTTON,
            anchor = ControllerAnchorDocument(x, y),
            mappings = listOf(ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = 0)),
        )
        fun axis(id: String, label: String, x: Double, y: Double) = ControllerControlDocument(
            controlId = id,
            displayName = label,
            surface = ControllerSurfaceDocument.FRONT,
            type = ControllerControlTypeDocument.AXIS,
            anchor = ControllerAnchorDocument(x, y),
            mappings = listOf(ControllerInputMappingDocument(ControllerInputPlatform.FTC, axisIndex = 0)),
        )
        val standard = listOf(
            button("a", "A", .81, .44), button("b", "B", .88, .35),
            button("x", "X", .74, .35), button("y", "Y", .81, .26),
            button("left_bumper", "LB", .23, .10), button("right_bumper", "RB", .77, .10),
            button("back", "Back", .43, .40), button("start", "Start", .57, .40),
            button("left_stick_button", "L3", .35, .66), button("right_stick_button", "R3", .65, .66),
            button("dpad_up", "D-pad up", .23, .43), button("dpad_down", "D-pad down", .23, .57),
            button("dpad_left", "D-pad left", .16, .50), button("dpad_right", "D-pad right", .30, .50),
            axis("left_stick_x", "Left stick X", .35, .62), axis("left_stick_y", "Left stick Y", .35, .72),
            axis("right_stick_x", "Right stick X", .65, .62), axis("right_stick_y", "Right stick Y", .65, .72),
            axis("left_trigger", "LT", .17, .02), axis("right_trigger", "RT", .83, .02),
        )
        val extras = listOf(
            button("c", "C", .73, .54), button("z", "Z", .87, .54),
            button("lm", "LM", .23, .08), button("rm", "RM", .77, .08),
            button("m1", "M1", .32, .34, ControllerSurfaceDocument.REAR),
            button("m2", "M2", .68, .34, ControllerSurfaceDocument.REAR),
            button("m3", "M3", .36, .66, ControllerSurfaceDocument.REAR),
            button("m4", "M4", .64, .66, ControllerSurfaceDocument.REAR),
        )
        val profile = ControllerProfileDocument(
            documentId = "flydigi-vader-5-pro",
            displayName = "Flydigi Vader 5 Pro",
            controls = standard + extras,
        )
        val labels = profile.controls.associate { it.controlId to listOf("Action for ${it.displayName}") }
        val scene = ImageComposeScene(1100, 820)
        scene.setContent {
            AresTheme {
                Column(Modifier.fillMaxSize().background(AresBackground).padding(20.dp)) {
                    Text("Vader 5 Pro · Front", color = AresTextPrimary)
                    ControllerCanvas(
                        profile, ControllerSurfaceDocument.FRONT, "lm", emptySet(),
                        standard.mapTo(linkedSetOf()) { it.controlId }, ControllerInputPlatform.FTC,
                        GamepadState(), {}, boundActionLabels = labels,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Vader 5 Pro · Rear paddles", color = AresTextPrimary)
                    ControllerCanvas(
                        profile, ControllerSurfaceDocument.REAR, "m2", emptySet(),
                        extras.mapTo(linkedSetOf()) { it.controlId }, ControllerInputPlatform.FTC,
                        GamepadState(), {}, boundActionLabels = labels,
                    )
                }
            }
        }

        val file = saveScene(scene, "controller_canvas_vader_front_rear.png")
        assertTrue(file.length() > 10_000, "Vader front/rear screenshot should contain both mapped surfaces")
    }

    @Test
    fun renderInspectorDrawer() {
        val scene = ImageComposeScene(1100, 750)
        scene.setContent {
            AresTheme {
                Box(Modifier.fillMaxSize().background(AresBackground).padding(20.dp)) {
                    Text("Main Stage Background Area (Drivetrain / Subsystem / Controls Canvas)", color = AresBackground)
                    AresInspectorDrawer(
                        isOpen = true,
                        title = "Flywheel Motor (Left)",
                        categoryBadge = "ACTUATOR",
                        stableId = "left_flywheel",
                        onDismiss = {},
                        onDone = {},
                        onDelete = {},
                        width = 460.dp,
                    ) {
                        Text("Motor Velocity Control & Current Limits Inspector Body Content", color = AresBackground)
                    }
                }
            }
        }

        val image = scene.render()
        val data = image.encodeToData(EncodedImageFormat.PNG)
        if (data != null) {
            val file = File(outputDir, "inspector_drawer_preview.png")
            file.writeBytes(data.bytes)
            println("Saved screenshot to: ${file.absolutePath}")
        }
    }

    @Test
    fun renderSpecSummaryModal() {
        val scene = ImageComposeScene(1200, 800)
        val sections = listOf(
            AresSpecSection(
                title = "Hardware Map",
                rows = listOf(
                    AresSpecRow(
                        id = "fl_motor",
                        primaryLabel = "Front Left Motor",
                        secondaryLabel = "fl · Port 0",
                        badge = "MOTOR",
                        columns = listOf(
                            "Hardware Name" to "fl",
                            "Role" to "Front Left Drive",
                            "Direction" to "Normal",
                            "Current Limit" to "30A",
                        ),
                    ),
                    AresSpecRow(
                        id = "fr_motor",
                        primaryLabel = "Front Right Motor",
                        secondaryLabel = "fr · Port 1",
                        badge = "MOTOR",
                        columns = listOf(
                            "Hardware Name" to "fr",
                            "Role" to "Front Right Drive",
                            "Direction" to "Inverted",
                            "Current Limit" to "30A",
                        ),
                    ),
                    AresSpecRow(
                        id = "rl_motor",
                        primaryLabel = "Rear Left Motor",
                        secondaryLabel = "rl · Port 2",
                        badge = "MOTOR",
                        columns = listOf(
                            "Hardware Name" to "rl",
                            "Role" to "Rear Left Drive",
                            "Direction" to "Normal",
                            "Current Limit" to "30A",
                        ),
                    ),
                    AresSpecRow(
                        id = "rr_motor",
                        primaryLabel = "Rear Right Motor",
                        secondaryLabel = "rr · Port 3",
                        badge = "MOTOR",
                        columns = listOf(
                            "Hardware Name" to "rr",
                            "Role" to "Rear Right Drive",
                            "Direction" to "Inverted",
                            "Current Limit" to "30A",
                        ),
                    ),
                ),
            ),
            AresSpecSection(
                title = "Stateflow Fields",
                rows = listOf(
                    AresSpecRow(
                        id = "target_rpm",
                        primaryLabel = "Target Velocity",
                        secondaryLabel = "targetRpm · DOUBLE (RPM)",
                        badge = "TARGET",
                        columns = listOf(
                            "Type" to "DOUBLE",
                            "Unit" to "RPM",
                            "Default" to "0.0",
                            "Range" to "[0.0 .. 6000.0]",
                        ),
                    ),
                    AresSpecRow(
                        id = "measured_rpm",
                        primaryLabel = "Measured Velocity",
                        secondaryLabel = "measuredRpm · DOUBLE (RPM)",
                        badge = "ESTIMATE",
                        columns = listOf(
                            "Type" to "DOUBLE",
                            "Unit" to "RPM",
                            "Sensor Source" to "fl.encoderVelocity",
                        ),
                    ),
                ),
            ),
            AresSpecSection(
                title = "Control Laws",
                rows = listOf(
                    AresSpecRow(
                        id = "velocity_pid",
                        primaryLabel = "Flywheel Velocity PIDF",
                        secondaryLabel = "left_flywheel ← target targetRpm",
                        badge = "VELOCITY_PID",
                        columns = listOf(
                            "PID Gains" to "kP=0.0012, kI=0.0000, kD=0.0001",
                            "Feedforward" to "kS=0.05, kV=0.0018, kA=0.0002",
                            "Output Limits" to "[-1.0 .. 1.0]",
                            "Tolerance" to "50 RPM",
                        ),
                    ),
                ),
            ),
        )

        scene.setContent {
            AresTheme {
                Box(Modifier.fillMaxSize().background(AresBackground)) {
                    AresSpecSummaryModal(
                        isOpen = true,
                        title = "Mecanum Drivetrain Specification",
                        subtitle = "Autonomous & TeleOp Kinematics · .ares/drivetrains/mecanum.aresdrive",
                        sections = sections,
                        onDismiss = {},
                        rawMarkdownGenerator = { "# Drivetrain Spec\n- 4 Motors\n- Pinpoint Odometry" },
                    )
                }
            }
        }

        val image = scene.render()
        val data = image.encodeToData(EncodedImageFormat.PNG)
        if (data != null) {
            val file = File(outputDir, "spec_summary_modal_preview.png")
            file.writeBytes(data.bytes)
            println("Saved screenshot to: ${file.absolutePath}")
        }
    }

    @Test
    fun renderAiAssistantDrawer() {
        val scene = ImageComposeScene(1200, 800)
        scene.setContent {
            AresTheme {
                Box(Modifier.fillMaxSize().background(AresBackground).padding(20.dp)) {
                    Text("Drivebase Builder Workspace (Geometry & Odometry Stage)", color = AresBackground)
                    AresInspectorDrawer(
                        isOpen = true,
                        title = "AI Drivebase Assistant",
                        categoryBadge = "GEMINI",
                        icon = androidx.compose.material.icons.Icons.Default.AutoAwesome,
                        onDismiss = {},
                        onDone = {},
                        doneButtonText = "Close",
                        width = 520.dp,
                    ) {
                        androidx.compose.foundation.layout.Column(
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                        ) {
                            androidx.compose.material3.Surface(
                                color = com.ares.analytics.ui.theme.AresSurface,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, com.ares.analytics.ui.theme.AresBorder),
                            ) {
                                androidx.compose.foundation.layout.Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "Describe your robot's requirements in plain language.",
                                        color = com.ares.analytics.ui.theme.AresTextPrimary,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        "Gemini will generate a structured proposal matching your league rules (FTC). It suggests reviewed form edits only; it cannot save or edit Kotlin/Java source directly.",
                                        color = com.ares.analytics.ui.theme.AresTextSecondary,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                    )
                                }
                            }

                            androidx.compose.material3.OutlinedTextField(
                                value = "4-motor Mecanum drive with GoBilda 19.2:1 motors, 435 RPM, 96mm wheels, and Pinpoint odometry computer at (0.05, -0.02) m",
                                onValueChange = {},
                                label = { Text("What should this drivebase do?") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4,
                            )

                            androidx.compose.material3.Button(
                                onClick = {},
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = com.ares.analytics.ui.theme.AresCyan,
                                    contentColor = com.ares.analytics.ui.theme.AresOnAccent
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                androidx.compose.material3.Icon(
                                    androidx.compose.material.icons.Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                                Text("Ask Gemini for a form proposal")
                            }

                            androidx.compose.material3.Surface(
                                color = AresBackground.copy(alpha = 0.5f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    "Privacy: Only your prompt and current drivebase configuration are sent. Your source files, telemetry logs, and credentials are never transmitted.",
                                    color = com.ares.analytics.ui.theme.AresTextTertiary,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        val image = scene.render()
        val data = image.encodeToData(EncodedImageFormat.PNG)
        if (data != null) {
            val file = File(outputDir, "ai_assistant_drawer_preview.png")
            file.writeBytes(data.bytes)
            println("Saved screenshot to: ${file.absolutePath}")
        }
    }

    private fun saveScene(scene: ImageComposeScene, name: String): File {
        val data = assertNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
        val file = File(outputDir, name)
        file.writeBytes(data.bytes)
        println("Saved screenshot to: ${file.absolutePath}")
        return file
    }

    private fun studioStage(
        id: RobotStudioStageId,
        title: String,
        status: RobotStudioStageStatus,
        issues: List<String> = emptyList(),
    ) = RobotStudioStage(
        id = id,
        title = title,
        outcome = "Fixture outcome",
        status = status,
        explanation = when (status) {
            RobotStudioStageStatus.READY -> "Canonical documents for this section passed validation."
            RobotStudioStageStatus.NEEDS_ACTION -> "Complete the named review before verification."
            RobotStudioStageStatus.BLOCKED -> "Resolve the preceding authoring stage first."
            RobotStudioStageStatus.INVALID -> "Fix the invalid canonical document."
            RobotStudioStageStatus.OPTIONAL -> "Optional for this robot."
            RobotStudioStageStatus.CODE_REQUIRED -> "This configuration needs handwritten runtime code."
            RobotStudioStageStatus.RUNNING -> "Verification is running."
        },
        issues = issues,
        storage = ".ares/fixture.json",
        consumer = "Generated robot runtime",
        action = RobotStudioAction.OPEN_DRIVEBASE,
        actionLabel = "Open",
    )
}
