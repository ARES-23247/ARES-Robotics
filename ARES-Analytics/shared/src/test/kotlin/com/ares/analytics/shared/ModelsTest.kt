package com.ares.analytics.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ModelsTest class.
 */
class ModelsTest {
    @Test
    fun `workspace diagnostics never render credentials`() {
        val secrets = listOf(
            "toa-secret",
            "tba-secret",
            "google-client-id",
            "legacy-google-secret",
            "gemini-secret",
            "C:/private/service-account.json",
        )
        val rendered = WorkspaceConfig(
            id = "workspace",
            teamId = "23247",
            seasonId = "2026",
            robotId = "robot",
            projectPath = "C:/robot",
            league = League.FTC,
            toaApiKey = secrets[0],
            tbaApiKey = secrets[1],
            googleClientId = secrets[2],
            googleClientSecret = secrets[3],
            geminiApiKey = secrets[4],
            vertexServiceAccountPath = secrets[5],
        ).toString()

        secrets.forEach { secret -> assertTrue(secret !in rendered) }
        assertTrue("hasGeminiApiKey=true" in rendered)
        assertTrue("hasGoogleClientSecret=true" in rendered)
    }


    @Test
    /**
     * testWorkspaceConfigSerialization fun.
     */
    fun testWorkspaceConfigSerialization() {
        val config = WorkspaceConfig(
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares-bot",
            projectPath = "/home/user/ares",
            league = League.FTC,
            nt4Host = "192.168.43.1",
            googleClientId = "my-gcp-client-id",
            googleOAuthUseCustomClient = true,
            googleOAuthBrokerUrl = "https://oauth.team.example",
            driveDestination = DriveDestinationConfig(
                type = DriveDestinationType.TEAM_FOLDER,
                rootFolderId = "stable-folder-id",
                displayName = "Team 23247",
                accountSubject = "google-subject",
                accountEmail = "student@example.com",
            ),
            largeTextMode = true
        )
        val json = Json.encodeToString(config)
        val decoded = Json.decodeFromString<WorkspaceConfig>(json)

        assertEquals(config.teamId, decoded.teamId)
        assertEquals(config.league, decoded.league)
        assertEquals(config.nt4Host, decoded.nt4Host)
        assertEquals("my-gcp-client-id", decoded.googleClientId)
        assertTrue(decoded.googleOAuthUseCustomClient)
        assertEquals("https://oauth.team.example", decoded.googleOAuthBrokerUrl)
        assertEquals("stable-folder-id", decoded.driveDestination?.rootFolderId)
        assertEquals(WorkspaceCollaborationMode.TEAM, decoded.driveDestination?.collaborationMode)
        assertTrue(decoded.largeTextMode)
        assertFalse(decoded.developerMode)
    }

    @Test
    fun `older workspace json defaults larger text off`() {
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<WorkspaceConfig>(
            """{"teamId":"23247","seasonId":"2026","robotId":"ares-bot","projectPath":"C:/ares","league":"FTC"}"""
        )

        assertFalse(decoded.largeTextMode)
        assertFalse(decoded.googleOAuthUseCustomClient)
        assertEquals(null, decoded.googleOAuthBrokerUrl)
        assertEquals(null, decoded.driveDestination)
    }

    @Test
    /**
     * testAppWorkspacesSerialization fun.
     */
    fun testAppWorkspacesSerialization() {
        val config1 = WorkspaceConfig(
            id = "ftc-1",
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares-ftc",
            projectPath = "/home/user/ares-ftc",
            league = League.FTC
        )
        val config2 = WorkspaceConfig(
            id = "frc-1",
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares-frc",
            projectPath = "/home/user/ares-frc",
            league = League.FRC
        )
        val app = AppWorkspaces(
            activeWorkspaceId = "ftc-1",
            workspaces = listOf(config1, config2)
        )
        val json = Json.encodeToString(app)
        val decoded = Json.decodeFromString<AppWorkspaces>(json)

        assertEquals("ftc-1", decoded.activeWorkspaceId)
        assertEquals(2, decoded.workspaces.size)
        assertEquals("ares-frc", decoded.workspaces[1].robotId)
        assertEquals(League.FRC, decoded.workspaces[1].league)
    }

    @Test
    /**
     * testObstacleSerialization fun.
     */
    fun testObstacleSerialization() {
        val circle: Obstacle = Obstacle.Circle(
            id = "c1",
            name = "Obstacle 1",
            centerX = 1.0,
            centerY = 2.0,
            radius = 0.5
        )
        val poly: Obstacle = Obstacle.Polygon(
            id = "p1",
            name = "Obstacle 2",
            vertices = listOf(PathPoint(0.0, 0.0), PathPoint(1.0, 0.0), PathPoint(0.0, 1.0))
        )
        val rect: Obstacle = Obstacle.Rectangle(
            id = "r1",
            name = "Obstacle 3",
            centerX = 2.0,
            centerY = 3.0,
            width = 0.8,
            height = 1.2,
            rotation = 45.0
        )
        val jsonCircle = Json.encodeToString(circle)
        val decodedCircle = Json.decodeFromString<Obstacle>(jsonCircle)
        assertTrue(decodedCircle is Obstacle.Circle)
        assertEquals(0.5, decodedCircle.radius)
        val jsonPoly = Json.encodeToString(poly)
        val decodedPoly = Json.decodeFromString<Obstacle>(jsonPoly)
        assertTrue(decodedPoly is Obstacle.Polygon)
        assertEquals(3, decodedPoly.vertices.size)
        val jsonRect = Json.encodeToString(rect)
        val decodedRect = Json.decodeFromString<Obstacle>(jsonRect)
        assertTrue(decodedRect is Obstacle.Rectangle)
        assertEquals(0.8, decodedRect.width)
        assertEquals(1.2, decodedRect.height)
        assertEquals(45.0, decodedRect.rotation)
    }

    @Test
    /**
     * testFieldImageConfigSerialization fun.
     */
    fun testFieldImageConfigSerialization() {
        val config = FieldImageConfig(
            imagePath = "/path/to/img.png",
            rotationDegrees = 90.0,
            cropLeft = 0.1,
            cropRight = 0.9,
            cropTop = 0.2,
            cropBottom = 0.8,
            widthMeters = 3.6,
            heightMeters = 3.6
        )
        val json = Json.encodeToString(config)
        val decoded = Json.decodeFromString<FieldImageConfig>(json)

        assertEquals(config.imagePath, decoded.imagePath)
        assertEquals(config.rotationDegrees, decoded.rotationDegrees)
        assertEquals(config.cropLeft, decoded.cropLeft)
        assertEquals(3.6, decoded.widthMeters)
    }

    @Test
    /**
     * testGamePieceSerialization fun.
     */
    fun testGamePieceSerialization() {
        val gp = GamePiece(
            id = "gp1",
            name = "Yellow Sample 1",
            x = 1.2,
            y = 2.3,
            type = "Sample (Yellow)"
        )
        val json = Json.encodeToString(gp)
        val decoded = Json.decodeFromString<GamePiece>(json)
        assertEquals(gp.id, decoded.id)
        assertEquals(gp.name, decoded.name)
        assertEquals(gp.type, decoded.type)
    }
}
