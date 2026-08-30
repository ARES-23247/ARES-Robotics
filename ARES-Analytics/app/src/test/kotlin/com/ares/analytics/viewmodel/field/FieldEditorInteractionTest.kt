package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.viewmodel.FieldEditorIntent
import com.ares.analytics.viewmodel.FieldEditorViewModel
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldEditorInteractionTest {
    @Test
    fun undoRedoAndDuplicateOperateOnWholeEditorTransactions() {
        val viewModel = FieldEditorViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FTC))
        val obstacle = Obstacle.Rectangle("barrier", "Barrier", 0.0, 0.0, 0.5, 0.25)

        viewModel.onIntent(FieldEditorIntent.AddObstacle(obstacle))
        viewModel.onIntent(FieldEditorIntent.SelectElement(obstacle.id))
        viewModel.onIntent(FieldEditorIntent.DuplicateSelection)

        assertEquals(2, viewModel.state.value.obstacles.size)
        assertEquals(1, viewModel.state.value.selectedElementIds.size)
        assertTrue(viewModel.state.value.canUndo)

        viewModel.onIntent(FieldEditorIntent.Undo)
        assertEquals(listOf(obstacle), viewModel.state.value.obstacles)
        assertTrue(viewModel.state.value.canRedo)

        viewModel.onIntent(FieldEditorIntent.Redo)
        assertEquals(2, viewModel.state.value.obstacles.size)
    }

    @Test
    fun lockedItemsAreNotDeletedOrNudged() {
        val viewModel = FieldEditorViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FTC))
        val locked = GamePiece("locked", "Locked", 0.2, 0.3, locked = true)
        viewModel.onIntent(FieldEditorIntent.AddGamePiece(locked))
        viewModel.onIntent(FieldEditorIntent.SelectElement(locked.id))

        viewModel.onIntent(FieldEditorIntent.NudgeSelection(0.1, 0.1))
        viewModel.onIntent(FieldEditorIntent.DeleteSelection)

        assertEquals(locked, viewModel.state.value.gamePieces.single())
    }

    @Test
    fun validationFindsDuplicateTagsInvalidGeometryAndOutOfBoundsItems() {
        val issues = FieldEditorValidator.validate(
            league = League.FRC,
            widthMeters = 16.541,
            heightMeters = 8.211,
            obstacles = listOf(Obstacle.Circle("bad", "Bad circle", 2.0, 2.0, -0.1)),
            gamePieces = listOf(GamePiece("outside", "Outside note", -1.0, 1.0)),
            aprilTags = listOf(
                AprilTagPlacement("tag-a", 4, 1.0, 1.0),
                AprilTagPlacement("tag-b", 4, 2.0, 1.0)
            ),
            waypoints = listOf(FieldWaypoint("waypoint", "Score", 1.0, 9.0, 0.0))
        )

        assertTrue(issues.any { it.message.contains("positive radius") })
        assertTrue(issues.any { it.message.contains("outside the field") })
        assertTrue(issues.any { it.message.contains("ID 4") && it.elementIds == setOf("tag-a", "tag-b") })
        assertFalse(issues.isEmpty())
    }

    @Test
    fun officialCrescendoWallMountedTagsAreAcceptedWithinPerimeterMargin() {
        val issues = FieldEditorValidator.validate(
            league = League.FRC,
            widthMeters = 16.541,
            heightMeters = 8.211,
            obstacles = emptyList(),
            gamePieces = emptyList(),
            aprilTags = listOf(
                AprilTagPlacement("red-speaker-3", 3, 16.579342, 4.982718),
                AprilTagPlacement("red-speaker-4", 4, 16.579342, 5.547868),
                AprilTagPlacement("blue-speaker-7", 7, -0.0381, 5.547868),
                AprilTagPlacement("blue-speaker-8", 8, -0.0381, 4.982718),
            ),
            waypoints = emptyList(),
        )

        assertFalse(issues.any { it.message.contains("outside the field perimeter") })
    }

    @Test
    fun aprilTagFarBeyondPerimeterStillWarns() {
        val issues = FieldEditorValidator.validate(
            league = League.FRC,
            widthMeters = 16.541,
            heightMeters = 8.211,
            obstacles = emptyList(),
            gamePieces = emptyList(),
            aprilTags = listOf(AprilTagPlacement("lost-tag", 99, -1.0, 2.0)),
            waypoints = emptyList(),
        )

        assertTrue(issues.any {
            it.elementIds == setOf("lost-tag") &&
                it.message.contains("more than 0.25 m outside the field perimeter")
        })
    }

    @Test
    fun FTCEditorSurfacesRuntimeRequirementForAnAprilTagLayout() {
        val viewModel = FieldEditorViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FTC))

        assertTrue(viewModel.state.value.validationIssues.any { it.message.contains("AprilTag layout") })
    }

    @Test
    fun clearingAFieldImageRemovesOnlyTheCanonicalReference() {
        val viewModel = FieldEditorViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FTC))
        viewModel.onIntent(
            FieldEditorIntent.UpdateFieldImageConfig(
                viewModel.state.value.fieldImageConfig.copy(imagePath = "field_image.png"),
                null,
                League.FTC,
            )
        )

        viewModel.onIntent(FieldEditorIntent.ClearFieldImage)

        assertEquals("", viewModel.state.value.fieldImageConfig.imagePath)
        assertEquals("", viewModel.state.value.document?.image?.imagePath)
        assertEquals(null, viewModel.state.value.fieldImage)
    }

    @Test
    fun WPILibImportRequiresPreviewAndPreservesFullOrientationWhenApplied() {
        val viewModel = FieldEditorViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FRC))
        val json = """
            {
              "field":{"length":17.0,"width":8.0},
              "tags":[{"ID":9,"pose":{"translation":{"x":1.0,"y":2.0,"z":0.4},"rotation":{"quaternion":{"W":0.7071067811865476,"X":0.0,"Y":0.0,"Z":0.7071067811865475}}}}]
            }
        """.trimIndent()

        viewModel.onIntent(FieldEditorIntent.PreviewAprilTagMap(json, "practice.json", null, League.FRC))

        assertTrue(viewModel.state.value.aprilTags.isEmpty())
        val preview = assertNotNull(viewModel.state.value.aprilTagImportPreview)
        assertEquals(1, preview.tags.size)
        assertTrue(preview.warnings.any { it.contains("tag size") })

        viewModel.onIntent(FieldEditorIntent.ApplyAprilTagImport(replaceExisting = true))

        val tag = viewModel.state.value.aprilTags.single()
        assertEquals(9, tag.tagId)
        assertEquals(90.0, tag.yawDegrees, 1e-9)
        assertEquals(17.0, viewModel.state.value.fieldImageConfig.widthMeters, 0.0)
        assertEquals(8.0, viewModel.state.value.fieldImageConfig.heightMeters, 0.0)
        assertEquals(null, viewModel.state.value.aprilTagImportPreview)
    }

    @Test
    fun LimelightImportCarriesFamilySizeAndMergeDoesNotOverwriteExistingID() {
        val viewModel = FieldEditorViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FTC))
        val existing = AprilTagPlacement(
            id = "existing",
            tagId = 3,
            name = "Mentor reviewed",
            family = "36h11",
            sizeMeters = 0.1651,
            x = 0.0,
            y = 0.0,
        )
        viewModel.onIntent(FieldEditorIntent.AddAprilTag(existing))
        val fmap = """
            {"fiducials":[{"id":3,"family":"36h11","size":165.1,"transform":[1,0,0,2,0,1,0,3,0,0,1,0.5,0,0,0,1],"unique":1}]}
        """.trimIndent()

        viewModel.onIntent(FieldEditorIntent.ImportFmap(fmap, null, League.FTC))
        val preview = assertNotNull(viewModel.state.value.aprilTagImportPreview)
        assertEquals(0.1651, preview.tags.single().sizeMeters!!, 1e-12)
        assertTrue(preview.warnings.any { it.contains("already exist") })

        viewModel.onIntent(FieldEditorIntent.ApplyAprilTagImport(replaceExisting = false))

        assertEquals(listOf(existing), viewModel.state.value.aprilTags)
    }

    @Test
    fun LimelightImportUsesTheCanonicalFrcBlueCornerOrigin() {
        val viewModel = FieldEditorViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FRC))
        val fmap = """
            {"fiducials":[{"id":4,"family":"apriltag3_36h11_classic","size":165.1,"transform":[1,0,0,7,0,1,0,-3,0,0,1,1.3,0,0,0,1],"unique":1}]}
        """.trimIndent()

        viewModel.onIntent(FieldEditorIntent.PreviewAprilTagMap(fmap, "field.fmap", null, League.FRC))
        viewModel.onIntent(FieldEditorIntent.ApplyAprilTagImport(replaceExisting = true))

        val tag = viewModel.state.value.aprilTags.single()
        assertEquals(7.0 + 16.541 / 2.0, tag.x, 1e-12)
        assertEquals(-3.0 + 8.211 / 2.0, tag.y, 1e-12)
    }

    @Test
    fun simulatorPublishReportsTransportFailureAndUsesOneCanonicalMessage() {
        val payloads = mutableListOf<String>()
        val viewModel = FieldEditorViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            fieldConfigPublisher = { payload ->
                payloads += payload
                false
            },
        )
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FRC))

        viewModel.onIntent(FieldEditorIntent.PushToSimulator)

        assertEquals(1, payloads.size)
        assertEquals(FieldType.FRC, RobotFieldDocument.decode(payloads.single()).fieldType)
        assertTrue(viewModel.state.value.simulatorStatus.contains("not accepted"))
    }

    @Test
    fun simulatorPublishReportsOnlyAnExactAppliedReceiptAsSuccess() {
        var confirmedExpected: ExpectedSimulatorField? = null
        val viewModel = FieldEditorViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            fieldConfigPublisher = { true },
            fieldApplyConfirmer = { expected, previous ->
                assertEquals(null, previous)
                confirmedExpected = expected
                SimulatorFieldApplyReceipt(
                    session = "sim-a",
                    sequence = 8,
                    configId = expected.configId,
                    revision = expected.revision,
                    sha256 = expected.sha256,
                    obstacleCount = 2,
                    elementCount = 3,
                    aprilTagCount = 16,
                )
            },
        )
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FRC))

        viewModel.onIntent(FieldEditorIntent.PushToSimulator)

        assertNotNull(confirmedExpected)
        assertTrue(viewModel.state.value.simulatorStatus.startsWith("Simulator applied field revision"))
        assertTrue(viewModel.state.value.simulatorStatus.contains("3 game piece(s)"))
        assertTrue(viewModel.state.value.simulatorStatus.contains("16 AprilTag(s)"))
    }

    @Test
    fun queuedFieldWithoutMatchingReceiptNeverClaimsItWasApplied() {
        val viewModel = FieldEditorViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            fieldConfigPublisher = { true },
            fieldApplyConfirmer = { expected, _ ->
                SimulatorFieldApplyReceipt(
                    session = "sim-a",
                    sequence = 9,
                    configId = expected.configId,
                    revision = expected.revision - 1,
                    sha256 = expected.sha256,
                    obstacleCount = 99,
                    elementCount = 99,
                    aprilTagCount = 99,
                )
            },
        )
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FRC))

        viewModel.onIntent(FieldEditorIntent.PushToSimulator)

        assertTrue(viewModel.state.value.simulatorStatus.contains("did not confirm the exact revision"))
        assertFalse(viewModel.state.value.simulatorStatus.contains("Simulator applied"))
    }

    @Test
    fun freshSimulatorRejectionIsShownInsteadOfAConfirmationTimeout() {
        var failure: SimulatorFieldApplyFailure? = null
        val viewModel = FieldEditorViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            fieldConfigPublisher = { true },
            fieldApplyFailureProvider = { failure },
            fieldApplyConfirmer = { _, _ ->
                failure = SimulatorFieldApplyFailure(
                    eventId = "sim-a:42",
                    message = "AprilTag layout could not be installed",
                )
                null
            },
        )
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FRC))

        viewModel.onIntent(FieldEditorIntent.PushToSimulator)

        assertTrue(viewModel.state.value.simulatorStatus.startsWith("Simulator rejected this field"))
        assertTrue(viewModel.state.value.simulatorStatus.contains("AprilTag layout could not be installed"))
        assertFalse(viewModel.state.value.simulatorStatus.contains("did not confirm"))
    }

    @Test
    fun suppressedDuplicateUpdateUsesTheCurrentConnectionsExactReceipt() {
        var previouslyApplied: SimulatorFieldApplyReceipt? = null
        val viewModel = FieldEditorViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            fieldConfigPublisher = { true },
            fieldApplyReceiptProvider = { previouslyApplied },
            fieldApplyConfirmer = { _, _ -> null },
        )
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FRC))
        val payload = RobotFieldDocument.encode(requireNotNull(viewModel.state.value.document))
        previouslyApplied = SimulatorFieldApplyReceipt(
            session = "current-sim-session",
            sequence = 12,
            configId = requireNotNull(viewModel.state.value.document).id,
            revision = requireNotNull(viewModel.state.value.document).revision,
            sha256 = sha256Hex(payload),
            obstacleCount = 1,
            elementCount = 0,
            aprilTagCount = 2,
        )

        viewModel.onIntent(FieldEditorIntent.PushToSimulator)

        assertTrue(viewModel.state.value.simulatorStatus.startsWith("Simulator already has exact field revision"))
        assertTrue(viewModel.state.value.simulatorStatus.contains("0 game piece(s)"))
    }

    @Test
    fun simulatorReceiptParserRejectsMalformedPayloadAndPreservesEventIdentity() {
        assertEquals(null, parseSimulatorFieldApplyReceipt("not-json"))
        val receipt = parseSimulatorFieldApplyReceipt(
            """{"session":"sim-b","sequence":4,"configId":"field","revision":2,"sha256":"abc","obstacleCount":1,"elementCount":0,"aprilTagCount":3}"""
        )
        assertNotNull(receipt)
        assertEquals("sim-b:4", receipt.eventId)
    }

    @Test
    fun validationUsesRotatedRectangleExtentsNotOnlyItsCenter() {
        val issues = FieldEditorValidator.validate(
            league = League.FRC,
            widthMeters = 16.541,
            heightMeters = 8.211,
            obstacles = listOf(
                Obstacle.Rectangle(
                    id = "edge",
                    name = "Edge barrier",
                    centerX = 16.4,
                    centerY = 4.0,
                    width = 1.0,
                    height = 0.5,
                    rotation = 45.0
                )
            ),
            gamePieces = emptyList(),
            aprilTags = emptyList(),
            waypoints = emptyList()
        )

        assertTrue(issues.any { it.elementIds == setOf("edge") && it.message.contains("extends outside") })
    }

    @Test
    fun prefabCatalogIsLeagueSpecific() {
        assertTrue(FieldPrefabCatalog.forLeague(League.FTC).any { it.id == "decode-ball" })
        assertFalse(FieldPrefabCatalog.forLeague(League.FRC).any { it.id == "decode-ball" })
        assertTrue(FieldPrefabCatalog.forLeague(League.FRC).any { it.id == "note" })
    }

    @Test
    fun gamePieceCatalogUsesStableIdsAndParticipatesInUndo() {
        val viewModel = FieldEditorViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FTC))
        val original = viewModel.state.value.gamePieceTypes
        val custom = GamePieceType(
            id = "practice-puck",
            name = "Practice Puck",
            shape = "cylinder",
            diameter = 0.18,
            width = 0.18,
            height = 0.04,
            colorHex = "#7E57C2",
            massKg = 0.25,
            friction = 0.55,
            restitution = 0.15,
        )

        viewModel.onIntent(FieldEditorIntent.SetGamePieceTypes(original + custom))

        assertEquals(custom, viewModel.state.value.gamePieceTypes.last())
        assertTrue(viewModel.state.value.canUndo)
        viewModel.onIntent(FieldEditorIntent.Undo)
        assertEquals(original, viewModel.state.value.gamePieceTypes)
        viewModel.onIntent(FieldEditorIntent.Redo)
        assertEquals(custom, viewModel.state.value.gamePieceTypes.last())
    }

    @Test
    fun catalogCannotDeleteATypeUsedByAPlacedPiece() {
        val viewModel = FieldEditorViewModel(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        viewModel.onIntent(FieldEditorIntent.LoadConfig(null, League.FTC))
        val catalog = viewModel.state.value.gamePieceTypes
        val usedType = catalog.first()
        viewModel.onIntent(
            FieldEditorIntent.AddGamePiece(
                GamePiece(
                    id = "placed-piece",
                    name = "Placed ${usedType.name}",
                    type = usedType.name,
                    x = 1.0,
                    y = 1.0,
                    typeId = usedType.id,
                )
            )
        )

        viewModel.onIntent(FieldEditorIntent.SetGamePieceTypes(catalog.drop(1)))

        assertEquals(catalog, viewModel.state.value.gamePieceTypes)
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("still uses"))
    }
}
