package com.ares.analytics.mcp.resources

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CapabilityContext
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.catalog.ResourceClaim
import com.areslib.controls.AnalogControlPolicyDocument
import com.areslib.controls.AxisTransformDocument
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSourceDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControllerAnchorDocument
import com.areslib.controls.ControllerAssignment
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputMappingDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileCodec
import com.areslib.controls.ControllerProfileDocument
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.drivetrain.ftcMecanumDrivetrain
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcHubCommandTransport
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresRuntimeOptionsDocument
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldWaypoint
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.supportsPlatform
import com.areslib.superstructure.StateNodeLayout
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDisabledPolicy
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructureStatePreset
import com.areslib.superstructure.SuperstructureSubsystemTarget
import com.areslib.superstructure.SuperstructureTargetMode
import com.areslib.superstructure.TransitionTriggerKind
import com.areslib.tuning.TuningAssignment
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningProfileDocumentCodec
import com.areslib.tuning.TuningValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Provides live codec-encoded JSON exemplars for all 11 canonical ARES document schemas,
 * an inventory catalog of all built-in templates/presets/vendor profiles, and zero-GC cookbooks.
 */
internal object SchemaExemplarCatalog {
    private val prettyJson = Json { prettyPrint = true }

    val supportedSchemaKinds: List<String> = listOf(
        "subsystem",
        "drivetrain",
        "superstructure",
        "routine",
        "controls",
        "controller",
        "tuning",
        "project",
        "action-catalog",
        "autonomous-catalog",
        "field",
    )

    fun renderSchemaReference(kind: String): String = when (kind.trim().lowercase()) {
        "subsystem" -> markdownWithJson(
            title = ".ares/subsystems/<id>.aressubsystem",
            notes = """
                - `schemaVersion=11`, `documentId` (lowercase slug), `uid`, `displayName`, `kotlinTypeName` (PascalCase), `platform` (`FTC|FRC|XRP`).
                - `implementation.kind`: `DECLARATIVE_GENERATED | GENERATED_STARTER | HAND_AUTHORED`.
                - Zero-GC hot loops, single-read input caching, explicit `safeOutput`, and non-zero `feedbackTimeoutMs` on sensors/closed-loop controls.
                - Prefer `operation=TEMPLATE` (`templateName` from `ares://templates/catalog`) or `operation=PATCH` (`patchJson`) over hand-writing from scratch.
            """.trimIndent(),
            json = buildSubsystemExemplarJson(),
        )
        "drivetrain" -> markdownWithJson(
            title = ".ares/drivetrains/<id>.aresdrivetrain",
            notes = """
                - `schemaVersion=1`, `uid` (`[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*`), `drivebaseId`, `kind` (`FTC_MECANUM|FRC_CTRE_SWERVE|DIFFERENTIAL|...`), `platform` (`FTC|FRC|XRP`).
                - Declares wheel geometry, localization (`headingCcwPositive=true`, `cachedInputsRequired=true`), safety neutral policy, simulation class names, tuning parameters, and `canonicalProfileUid`.
                - Prefer `operation=TEMPLATE` with `FTC_MECANUM`, `FRC_CTRE_SWERVE`, `XRP_DIFFERENTIAL`, or `DIFFERENTIAL`.
            """.trimIndent(),
            json = buildDrivetrainExemplarJson(),
        )
        "superstructure" -> markdownWithJson(
            title = ".ares/superstructures/<id>.aressuperstructure",
            notes = """
                - `schemaVersion=3`, `superstructureId`, `initialStateId`, `faultStateId`, `disabledStateId`, `disabledPolicy=FORCE_SAFE_AND_REJECT_REQUESTS`.
                - Invariant: every entry in `states[]` MUST explicitly command the exact same `(subsystemUid, fieldUid)` target set so outputs cannot stay stale across transitions.
            """.trimIndent(),
            json = buildSuperstructureExemplarJson(),
        )
        "routine" -> markdownWithJson(
            title = ".ares/routines/<id>.aresroutine",
            notes = """
                - `schemaVersion=2`, `documentId`, `revision`, `name`, `steps[]` (`ACTION|DRIVE_TO|WAIT|WAIT_UNTIL|TOGETHER|FIRST_TO_FINISH|DEADLINE|CALL|REPEAT|BRANCH`).
                - Routines are starting-pose-neutral; register in `.ares/autonomous-catalog.json` to expose an entry on the match INIT selector.
            """.trimIndent(),
            json = buildRoutineExemplarJson(),
        )
        "controls" -> markdownWithJson(
            title = ".ares/controls/<id>.arescontrols",
            notes = """
                - `schemaVersion=2`, `documentId`, `controllers[]` (non-null `devicePort` 0..15 per slot, matching `.ares/controllers/<profileId>.arescontroller`), `bindings[]`.
                - `DRIVE` targets (`vx`, `vy`, `omega`) require `source.kind=AXIS_VALUE` and `event=VALUE`.
            """.trimIndent(),
            json = buildControlSchemeExemplarJson(),
        )
        "controller" -> markdownWithJson(
            title = ".ares/controllers/<profileId>.arescontroller",
            notes = """
                - `schemaVersion=1`, `documentId`, `displayName`, `controls[]` (`BUTTON|AXIS` with anchor and platform HID index mappings for `FTC|FRC|XRP|DESKTOP_GLFW`).
            """.trimIndent(),
            json = buildControllerProfileExemplarJson(),
        )
        "tuning" -> markdownWithJson(
            title = ".ares/tuning/<uid>.arestuning & .ares/local/tuning/<id>.arestuning",
            notes = """
                - `schemaVersion=2`, `uid`, `profileId`, `projectId`, `authority` (`CANONICAL_CHECKED_IN|LOCAL_EXPERIMENTAL`), `values[]`.
                - Canonical checked-in writes are confirmation-token gated; local overlays do not permit `promotion`. Values must respect declared `[minimum, maximum]` bounds and exclude `READ_ONLY_VENDOR`.
            """.trimIndent(),
            json = buildTuningExemplarJson(),
        )
        "project" -> markdownWithJson(
            title = ".ares/project.json",
            notes = """
                - `schemaVersion=5`, `projectId`, `identity`, `league` (`FTC|FRC|XRP`), `coordinateConvention` (`CENTER_ORIGIN_CCW` for FTC/XRP, `BLUE_CORNER_ORIGIN_CCW` for FRC).
                - `authoringModel`: `GUI_OWNED | HYBRID | CODE_FIRST`. Flip to `HYBRID` when registering user-owned vendor subsystems alongside Studio routines/drivetrain.
            """.trimIndent(),
            json = buildProjectMetadataExemplarJson(),
        )
        "action-catalog" -> markdownWithJson(
            title = ".ares/action-catalog.json",
            notes = """
                - `schemaVersion=1`, `projectId`, `revision`, `actions[]`, `conditions[]`.
                - Routines, control bindings, and hand-authored subsystems reference `actions[].key` here; removing an in-use action key fails project validation.
            """.trimIndent(),
            json = buildActionCatalogExemplarJson(),
        )
        "autonomous-catalog" -> markdownWithJson(
            title = ".ares/autonomous-catalog.json",
            notes = """
                - `schemaVersion=1`, `projectId`, `revision`, `defaultEntryId`, `entries[]` (`entryId`, `displayName`, `routineId`, `startingPose` in CCW+ radians, `authoredAlliance`, `mirrorForOppositeAlliance`).
            """.trimIndent(),
            json = buildAutonomousCatalogExemplarJson(),
        )
        "field" -> markdownWithJson(
            title = canonicalFieldPathHint(),
            notes = """
                - Canonical `RobotFieldConfig` (`schemaVersion=2`): `fieldType` (`FTC|FRC|XRP`), `widthMeters`/`heightMeters` (`0.0` selects league default), `apriltags[]`, `obstacles[]`, `fieldWaypoints[]`.
                - FTC AprilTags require `family` (e.g., `36h11`) and `sizeMeters` (e.g., `0.127`) for generated `VisionPortal` codegen.
                - Use `ares_field_mutate` (FULL) or `ares_descriptor_mutate(descriptorKind="FIELD")` (COMPACT) with `INSTALL_PRESET` or `IMPORT_APRILTAGS` (WPILib JSON / Limelight `.fmap`).
            """.trimIndent(),
            json = buildFieldExemplarJson(),
        )
        else -> buildString {
            appendLine("## Unknown schema '$kind'")
            appendLine("Supported kinds: ${supportedSchemaKinds.joinToString(", ") { "`$it`" }}.")
        }.trimEnd()
    }

    fun renderTemplatesCatalogJson(): String {
        val obj: JsonObject = buildJsonObject {
            put("schemaVersion", 1)
            put(
                "subsystemTemplates",
                buildJsonArray {
                    SubsystemTemplate.entries.forEach { template ->
                        add(
                            buildJsonObject {
                                put("name", template.name)
                                put(
                                    "supportedPlatforms",
                                    JsonArray(
                                        SubsystemPlatform.entries
                                            .filter { template.supportsPlatform(it) }
                                            .map { JsonPrimitive(it.name) },
                                    ),
                                )
                                put(
                                    "supportedImplementationKinds",
                                    JsonArray(
                                        listOf("DECLARATIVE_GENERATED", "GENERATED_STARTER")
                                            .map(::JsonPrimitive),
                                    ),
                                )
                            },
                        )
                    }
                },
            )
            put(
                "drivetrainPresets",
                buildJsonArray {
                    listOf(
                        Triple("FTC_MECANUM", "FTC", "Four-motor mecanum drivebase + Pinpoint/IMU odometry"),
                        Triple("FRC_CTRE_SWERVE", "FRC", "Four-module CTRE Phoenix 6 swerve template"),
                        Triple("XRP_DIFFERENTIAL", "XRP", "Two-wheel XRP differential drivetrain + IMU"),
                        Triple("DIFFERENTIAL", "FTC|FRC|XRP", "Generic tank/differential two-side drivetrain"),
                    ).forEach { (name, platform, desc) ->
                        add(
                            buildJsonObject {
                                put("name", name)
                                put("platform", platform)
                                put("description", desc)
                            },
                        )
                    }
                },
            )
            put(
                "fieldPresets",
                buildJsonArray {
                    listOf(
                        "LEAGUE_DEFAULT" to "Empty league-dimension field ready for custom tags/obstacles",
                        "VISION_CALIBRATION_TARGET_1M" to "AprilTag #1 placed 1.0 m directly ahead of start waypoint",
                        "THREE_CONE_SLALOM_COURSE" to "3 practice obstacle cones + 4 weave waypoints scaled per league",
                        "CLEAR_PRACTICE_FLOOR" to "Clear floor with clean practice start origin waypoint",
                    ).forEach { (name, desc) ->
                        add(
                            buildJsonObject {
                                put("name", name)
                                put("description", desc)
                            },
                        )
                    }
                },
            )
            put(
                "vendorSdkProfiles",
                buildJsonArray {
                    listOf(
                        "FRC_TALONFX_POSITION" to "FRC Phoenix 6 TalonFX closed-loop position + NeutralOut",
                        "FRC_TALONFX_VELOCITY" to "FRC Phoenix 6 TalonFX closed-loop velocity + NeutralOut",
                        "FRC_SPARKMAX_BRUSHLESS" to "FRC REVLib 2026 SparkMax brushless controller + mock IO",
                        "FRC_SPARKFLEX_VORTEX" to "FRC REVLib 2026 SparkFlex Vortex controller + mock IO",
                        "FRC_PHOTONVISION_APRILTAG" to "FRC PhotonVision PhotonPoseEstimator CCW+ vision fusion IO",
                        "FTC_DCMOTOREX_PIDF" to "FTC DcMotorEx PIDF + RobotClock single-read cached IO",
                        "FTC_SERVO_ACTUATOR" to "FTC Servo/CRServo bounded safe-neutral IO",
                        "FTC_LIMELIGHT3A_PINPOINT" to "FTC Limelight3A rotation.y + GoBildaPinpointDriver CCW+ IO",
                    ).forEach { (name, desc) ->
                        add(
                            buildJsonObject {
                                put("name", name)
                                put("description", desc)
                            },
                        )
                    }
                },
            )
        }
        return prettyJson.encodeToString(JsonObject.serializer(), obj)
    }

    fun cookbookSnippet(path: String): String? = when (path.trim().lowercase()) {
        "frc/photon-vision" -> """
            ### FRC PhotonVision AprilTag Fusion Cookbook (`ARES-COORD-012` / `ARES-SIM-008`)
            - Use PhotonLib 2026 `PhotonPoseEstimator(fieldLayout, robotToCamera)` with `estimateCoprocMultiTagPose(result)` in blue-corner origin CCW+ radians (`AprilTagFieldLayout` loaded from `src/main/deploy/paths/field.json`).
            - Read `camera.getAllUnreadResults()` ONCE inside `readInputs()` and record `lastVisionTimestampSeconds` using `com.areslib.util.RobotClock` / FPGA time.
            - Never read `"ARES/TruePose"` or simulator ground-truth topics in fusion or control loops.
            - Reject stale or non-finite poses before passing vision updates to the estimator.
        """.trimIndent()
        "ftc/limelight-pinpoint" -> """
            ### FTC Limelight 3A + goBILDA Pinpoint Cookbook (`ARES-COORD-006`)
            - Use `-robotPoseTargetSpace.orientation.getPitch(AngleUnit.RADIANS)` (`-robotPoseTargetSpace.rotation.y`) for horizontal target yaw alignment — **never** `rotation.z` / `getYaw(...)` (camera roll).
            - Keep Pinpoint heading CCW-positive in radians (`pinpoint.getHeading(AngleUnit.RADIANS)`) — **never** negate Pinpoint heading.
            - Cache all Pinpoint and Limelight reads once per loop inside `readInputs()`; neutralize actuators if feedback age exceeds `feedbackTimeoutMs`.
        """.trimIndent()
        "shared/redux-slice" -> """
            ### ARES Zero-GC Redux Slice Cookbook (`ARES-RDX-003` / `ARES-GC-001`)
            - Keep `State` immutable (`val` properties only) with zero hardware SDK imports inside state/reducer files.
            - Define sealed `Action` intents and pure `fun reduce(state: State, action: Action): State`.
            - Update outputs inside `controller` -> `io.writeOutputs()`, never allocating lists/maps/lambdas in `periodic()` or `reduce()`.
        """.trimIndent()
        "shared/mock-io-test" -> """
            ### Deterministic Fake-IO Test Cookbook
            - Bracket every test with `RobotClock.useMockTime(1_000L)` ... `try { ... } finally { RobotClock.useSystemTime() }`.
            - Verify all 4 lifecycle guarantees: `configure -> enable -> bounded setpoint`, `stale feedback (> feedbackTimeoutMs) -> 0.0 neutral`, `disable/stop/close -> 0.0 neutral`, and `non-finite input -> fault`.
        """.trimIndent()
        else -> null
    }

    private fun canonicalFieldPathHint(): String =
        "FTC: TeamCode/src/main/assets/paths/field.json | FRC: src/main/deploy/paths/field.json | XRP: deploy/paths/field.json"

    private fun markdownWithJson(title: String, notes: String, json: String): String = buildString {
        appendLine("## `$title`")
        appendLine()
        appendLine(notes)
        appendLine()
        appendLine("### Canonical JSON Exemplar")
        appendLine("```json")
        appendLine(json.trimEnd())
        appendLine("```")
    }.trimEnd()

    private fun buildSubsystemExemplarJson(): String {
        val doc = SubsystemTemplates.createWithOwnership(
            template = SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
            documentId = "arm",
            kotlinTypeName = "Arm",
            platform = SubsystemPlatform.FTC,
            displayName = "Scoring Arm",
            implementationKind = SubsystemImplementationKind.DECLARATIVE_GENERATED,
        )
        return SubsystemDocumentCodec.encode(doc)
    }

    private fun buildDrivetrainExemplarJson(): String {
        val doc = ftcMecanumDrivetrain(
            id = "main-drive",
            displayName = "Main Mecanum Drive",
            uid = "example.drive.main-drive",
            canonicalProfileUid = "example.profile.competition",
        ) {
            tuningDouble(
                key = "drive.heading.kp",
                defaultValue = 1.8,
                minimum = 0.0,
                maximum = 20.0,
                unit = "rad/s per rad",
            )
        }
        return DrivetrainDocumentCodec.encode(doc)
    }

    private fun buildSuperstructureExemplarJson(): String {
        val arm = SubsystemTemplates.createWithOwnership(
            template = SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
            documentId = "arm",
            kotlinTypeName = "Arm",
            platform = SubsystemPlatform.FTC,
            displayName = "Scoring Arm",
            implementationKind = SubsystemImplementationKind.DECLARATIVE_GENERATED,
        )
        val targetField = arm.stateFields.first { it.role == SubsystemFieldRole.TARGET }
        val targetRef = SuperstructureFieldReference(subsystemUid = arm.uid, fieldUid = targetField.fieldId)
        val doc = SuperstructureDocument(
            superstructureId = "scoring-superstructure",
            displayName = "Scoring Superstructure",
            description = "Coordinates arm stow and score states safely.",
            initialStateId = "STOW",
            faultStateId = "SAFE_FAULT",
            disabledPolicy = SuperstructureDisabledPolicy.FORCE_SAFE_AND_REJECT_REQUESTS,
            disabledStateId = "SAFE_FAULT",
            states = listOf(
                SuperstructureStatePreset(
                    stateId = "STOW",
                    displayName = "Stow",
                    subsystemTargets = listOf(
                        SuperstructureSubsystemTarget(
                            target = targetRef,
                            targetMode = SuperstructureTargetMode.CONSTANT,
                            constantDoubleValue = 0.0,
                        ),
                    ),
                ),
                SuperstructureStatePreset(
                    stateId = "SCORE",
                    displayName = "Score",
                    subsystemTargets = listOf(
                        SuperstructureSubsystemTarget(
                            target = targetRef,
                            targetMode = SuperstructureTargetMode.CONSTANT,
                            constantDoubleValue = 1.2,
                        ),
                    ),
                ),
                SuperstructureStatePreset(
                    stateId = "SAFE_FAULT",
                    displayName = "Safe Fault",
                    subsystemTargets = listOf(
                        SuperstructureSubsystemTarget(
                            target = targetRef,
                            targetMode = SuperstructureTargetMode.CONSTANT,
                            constantDoubleValue = 0.0,
                        ),
                    ),
                ),
            ),
            transitions = listOf(
                StateTransitionEdge(
                    transitionId = "stow-to-score",
                    sourceStateId = "STOW",
                    targetStateId = "SCORE",
                    triggerKind = TransitionTriggerKind.ACTION_REQUEST,
                    actionKey = "superstructure.score",
                ),
            ),
            nodeLayouts = mapOf(
                "STOW" to StateNodeLayout(0.0, 0.0),
                "SCORE" to StateNodeLayout(240.0, 0.0),
                "SAFE_FAULT" to StateNodeLayout(120.0, 160.0),
            ),
        )
        return SuperstructureDocumentCodec.encode(doc)
    }

    private fun buildRoutineExemplarJson(): String = AresRoutineCodec.encode(
        RoutineDocument(
            documentId = "sample-auto",
            revision = 1,
            name = "Sample Auto",
            description = "Moves to target, pauses briefly, and neutralizes.",
            steps = listOf(
                RoutineStep(
                    kind = RoutineStepKind.WAIT,
                    stepId = "step-settle",
                    durationSeconds = 0.25,
                ),
            ),
        ),
    )

    private fun buildControlSchemeExemplarJson(): String = ControlSchemeCodec.encode(
        ControlSchemeDocument(
            documentId = "driver",
            revision = 1,
            name = "Driver Controls",
            description = "Field-centric translation + heading rotation",
            controllers = listOf(
                ControllerAssignment(
                    slot = "driver",
                    displayName = "Driver",
                    profileId = "ftc-driver",
                    devicePort = 0,
                ),
            ),
            bindings = listOf(
                ControlBindingDocument(
                    bindingId = "drive-forward",
                    displayName = "Drive Forward",
                    source = ControlSourceDocument(
                        kind = ControlSourceKind.AXIS_VALUE,
                        controllerSlot = "driver",
                        controlIds = listOf("left_stick_y"),
                        transform = AxisTransformDocument(deadband = 0.05, inverted = true),
                    ),
                    event = ControlEvent.VALUE,
                    target = ControlTargetDocument(kind = ControlTargetKind.DRIVE, key = "vx"),
                    analogPolicy = AnalogControlPolicyDocument(),
                ),
            ),
        ),
    )

    private fun buildControllerProfileExemplarJson(): String = ControllerProfileCodec.encode(
        ControllerProfileDocument(
            documentId = "ftc-driver",
            revision = 1,
            displayName = "FTC Driver Gamepad",
            controls = listOf(
                ControllerControlDocument(
                    controlId = "left_stick_y",
                    displayName = "Left Stick Y",
                    type = ControllerControlTypeDocument.AXIS,
                    anchor = ControllerAnchorDocument(0.25, 0.5),
                    mappings = listOf(
                        ControllerInputMappingDocument(platform = ControllerInputPlatform.FTC, axisIndex = 1),
                    ),
                ),
            ),
        ),
    )

    private fun buildTuningExemplarJson(): String {
        val drivetrain = ftcMecanumDrivetrain(
            id = "main-drive",
            uid = "example.drive.main-drive",
            canonicalProfileUid = "example.profile.competition",
        ) {
            tuningDouble(
                key = "drive.headingKp",
                defaultValue = 1.8,
                uid = "ftc.drive.heading.kp",
                minimum = 0.0,
                maximum = 20.0,
            )
        }
        val doc = TuningProfileDocument(
            uid = "example.profile.competition",
            profileId = "competition",
            displayName = "Competition",
            description = "Reviewed canonical competition gains.",
            projectId = "team-robot",
            drivebaseUid = drivetrain.uid,
            authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = listOf(
                TuningAssignment(
                    parameterUid = "ftc.drive.heading.kp",
                    value = TuningValue(doubleValue = 1.8),
                ),
            ),
        )
        return TuningProfileDocumentCodec.encode(doc, drivetrain.parameters)
    }

    private fun buildProjectMetadataExemplarJson(): String = AresProjectMetadataCodec.encode(
        AresProjectMetadataDocument(
            projectId = "team-robot",
            identity = AresProjectIdentityDocument(
                teamId = "23247",
                seasonId = "2026",
                robotId = "TeamRobot",
                displayName = "Team Robot",
            ),
            league = AresLeague.FTC,
            coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
            robotLengthMeters = 0.45,
            robotWidthMeters = 0.45,
            fieldLengthMeters = 3.6576,
            fieldWidthMeters = 3.6576,
            authoringModel = AresProjectAuthoringModel.GUI_OWNED,
            runtimeOptions = AresRuntimeOptionsDocument(
                ftc = AresFtcRuntimeOptionsDocument(
                    hubCommandTransport = AresFtcHubCommandTransport.STANDARD_SDK,
                    limelightProxyEnabled = false,
                ),
            ),
        ),
    )

    private fun buildActionCatalogExemplarJson(): String = CapabilityCatalogCodec.encode(
        CapabilityCatalogDocument(
            projectId = "team-robot",
            revision = 1,
            actions = listOf(
                ActionDescriptor(
                    key = "arm.setTargetAngle",
                    displayName = "Set Arm Target Angle",
                    description = "Moves arm mechanism to bounded CCW radians target.",
                    category = "Arm",
                    parameters = listOf(
                        CapabilityParameterDescriptor(
                            key = "angleRad",
                            displayName = "Angle (rad)",
                            description = "Target angle in radians",
                            type = CapabilityParameterType.NUMBER,
                            unit = "rad",
                            minimum = 0.0,
                            maximum = 2.0,
                            defaultNumber = 0.5,
                        ),
                    ),
                    resources = listOf(ResourceClaim("arm")),
                    allowedContexts = listOf(CapabilityContext.AUTONOMOUS, CapabilityContext.TELEOP),
                ),
            ),
        ),
    )

    private fun buildAutonomousCatalogExemplarJson(): String = AutonomousCatalogCodec.encode(
        AutonomousCatalogDocument(
            projectId = "team-robot",
            revision = 1,
            defaultEntryId = "sample-auto",
            entries = listOf(
                AutonomousCatalogEntry(
                    entryId = "sample-auto",
                    displayName = "Sample Auto",
                    description = "Scores preloaded game piece cleanly.",
                    routineId = "sample-auto",
                    startingPose = RoutinePose(xMeters = -0.9, yMeters = -0.9, headingRadians = 1.5707963267948966),
                    authoredAlliance = RoutineAlliance.RED,
                    mirrorForOppositeAlliance = true,
                ),
            ),
        ),
    )

    private fun buildFieldExemplarJson(): String = RobotFieldDocument.encode(
        RobotFieldConfig(
            id = "practice-field",
            name = "FTC Practice Field",
            gameYear = "2026",
            fieldType = FieldType.FTC,
            apriltags = listOf(
                RobotFieldAprilTag(
                    id = 1,
                    name = "Tag 1",
                    family = "36h11",
                    sizeMeters = 0.127,
                    x = 0.5,
                    y = 0.0,
                    z = 0.3,
                    yaw = 180.0,
                    editorId = "apriltag-1",
                ),
            ),
            fieldWaypoints = listOf(
                RobotFieldWaypoint(
                    id = "start-origin",
                    name = "Start",
                    x = -0.5,
                    y = 0.0,
                    headingDegrees = 0.0,
                ),
            ),
        ),
    )
}
