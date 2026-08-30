package com.ares.analytics.service

import com.ares.analytics.shared.models.DriveDestinationConfig
import com.ares.analytics.shared.models.DriveDestinationType
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceCollaborationMode
import com.ares.analytics.shared.models.WorkspaceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleDriveDestinationTest {
    private val account = AuthState.Authenticated(
        idToken = "id",
        uid = "google-subject-a",
        email = "student@team-a.example",
        displayName = "Student A",
    )

    @Test
    fun `team A cannot read a file rooted in team B through application state`() = runTest {
        val requestedMedia = mutableListOf<String>()
        val client = driveClient { request ->
            val id = request.url.encodedPath.substringAfterLast('/')
            if (request.url.parameters["alt"] == "media") requestedMedia += id
            when (id) {
                "team-b-file-01" -> metadata(id, "outside.parquet", parents = listOf("team-b-root-01"))
                "team-b-root-01" -> metadata(id, "Team B", parents = emptyList())
                else -> error("Unexpected Drive id $id")
            }
        }
        val fixture = fixture(client, destination(rootId = "team-a-root-01"))
        try {
            val failure = assertFailsWith<DriveDestinationAccessException> {
                fixture.service.readFile("team-b-file-01")
            }
            assertTrue(failure.message!!.contains("outside this workspace"))
            assertTrue(requestedMedia.isEmpty(), "Out-of-scope file bytes must never be requested")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `workspace destination rejects a mismatched signed in account before network access`() = runTest {
        var requests = 0
        val client = driveClient {
            requests++
            error("Network must not be reached")
        }
        val fixture = fixture(
            client,
            destination(rootId = "team-a-root-01").copy(accountSubject = "different-subject"),
        )
        try {
            val failure = assertFailsWith<DriveDestinationAccessException> { fixture.service.workspaceRootId() }
            assertTrue(failure.message!!.contains("workspace belongs to"))
            assertEquals(0, requests)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `removed folder permission fails visibly instead of appearing empty`() = runTest {
        val client = driveClient {
            respond("", HttpStatusCode.Forbidden)
        }
        val fixture = fixture(client, destination(rootId = "team-a-root-01"))
        try {
            val failure = assertFailsWith<DriveDestinationAccessException> { fixture.service.workspaceRootId() }
            assertTrue(failure.message!!.contains("denied by Google Drive"))
            assertTrue(failure.message!!.contains("owner"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `create team folder binds stable id account and team collaboration mode`() = runTest {
        val client = driveClient { request ->
            when (request.method) {
                HttpMethod.Post -> respond(
                    """{"id":"created-team-root"}""",
                    HttpStatusCode.OK,
                    jsonHeaders(),
                )
                HttpMethod.Get -> metadata(
                    id = "created-team-root",
                    name = "Team 23247 Drive",
                    parents = emptyList(),
                    ownedByMe = true,
                    canWrite = true,
                )
                else -> error("Unexpected method ${request.method}")
            }
        }
        val fixture = fixture(client, destination = null)
        try {
            val configured = fixture.service.configureDestination(
                type = DriveDestinationType.TEAM_FOLDER,
                displayName = "Team 23247 Drive",
            )

            assertEquals("created-team-root", configured.rootFolderId)
            assertEquals(account.uid, configured.accountSubject)
            assertEquals(account.email, configured.accountEmail)
            assertEquals(WorkspaceCollaborationMode.TEAM, configured.collaborationMode)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `shared folder requires write permission`() = runTest {
        val client = driveClient {
            metadata(
                id = "shared-folder-01",
                name = "Read only scouting",
                parents = emptyList(),
                ownedByMe = false,
                canWrite = false,
            )
        }
        val fixture = fixture(client, destination = null)
        try {
            val failure = assertFailsWith<DriveDestinationAccessException> {
                fixture.service.configureDestination(
                    type = DriveDestinationType.SHARED_FOLDER,
                    displayName = "Scouting",
                    existingFolderReference = "https://drive.google.com/drive/folders/shared-folder-01",
                )
            }
            assertTrue(failure.message!!.contains("cannot add files"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `picker selected Shared Drive folder stores both root and owning drive ids`() = runTest {
        val client = driveClient {
            metadata(
                id = "shared-drive-folder-01",
                name = "Team CAD and Logs",
                parents = listOf("shared-drive-root-01"),
                canWrite = true,
                driveId = "shared-drive-root-01",
            )
        }
        val fixture = fixture(client, destination = null)
        try {
            val configured = fixture.service.configureDestination(
                type = DriveDestinationType.SHARED_DRIVE,
                displayName = "Team CAD and Logs",
                existingFolderReference = "shared-drive-folder-01",
            )

            assertEquals("shared-drive-folder-01", configured.rootFolderId)
            assertEquals("shared-drive-root-01", configured.sharedDriveId)
            assertEquals(WorkspaceCollaborationMode.TEAM, configured.collaborationMode)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `concurrent folder creation remains serialized inside the selected workspace`() = kotlinx.coroutines.runBlocking {
        var createdId: String? = null
        var createCount = 0
        val client = driveClient { request ->
            when (request.method) {
                HttpMethod.Get -> respond(
                    if (createdId == null) """{"files":[]}""" else """{"files":[{"id":"$createdId"}]}""",
                    HttpStatusCode.OK,
                    jsonHeaders(),
                )
                HttpMethod.Post -> {
                    createCount++
                    createdId = "serialized-folder-01"
                    respond("""{"id":"serialized-folder-01"}""", HttpStatusCode.OK, jsonHeaders())
                }
                else -> error("Unexpected method ${request.method}")
            }
        }
        val fixture = fixture(client, destination(rootId = "team-a-root-01"))
        try {
            val results = List(2) {
                async { fixture.service.findOrCreateFolder("Sessions", "team-a-root-01") }
            }.awaitAll()

            assertEquals(setOf("serialized-folder-01"), results.toSet())
            assertEquals(1, createCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `folder references accept ids and reject unrelated urls`() {
        assertEquals("shared-folder-01", extractGoogleDriveFolderId("https://drive.google.com/drive/folders/shared-folder-01"))
        assertEquals("shared-folder-01", extractGoogleDriveFolderId("shared-folder-01"))
        assertEquals(null, extractGoogleDriveFolderId("https://example.com/not-drive"))
    }

    @Test
    fun `destination UI enables only complete safe choices`() {
        assertTrue(canConfigureDriveDestination(DriveDestinationType.PERSONAL_FOLDER, "My robot", "", null, false))
        assertFalse(canConfigureDriveDestination(DriveDestinationType.SHARED_FOLDER, "Team", "not a folder", null, false))
        assertFalse(
            canConfigureDriveDestination(
                DriveDestinationType.SHARED_FOLDER,
                "Team",
                "https://drive.google.com/drive/folders/shared-folder-01",
                null,
                false,
            ),
            "Existing folders must be granted through Google Picker rather than pasted IDs",
        )
        assertFalse(canConfigureDriveDestination(DriveDestinationType.SHARED_DRIVE, "Team", "", null, false))
        assertFalse(canConfigureDriveDestination(DriveDestinationType.PERSONAL_FOLDER, "My robot", "", null, true))
    }

    private data class Fixture(
        val service: GoogleDriveService,
        val directory: java.io.File,
    ) {
        fun close() {
            service.dispose()
            directory.deleteRecursively()
        }
    }

    private suspend fun fixture(
        client: HttpClient,
        destination: DriveDestinationConfig?,
    ): Fixture {
        val directory = Files.createTempDirectory("ares-drive-destination").toFile()
        val environment = EnvironmentService(
            workspacesPath = directory.resolve("workspaces.json").path,
        )
        environment.saveConfig(
            WorkspaceConfig(
                id = "team-a-workspace",
                teamId = "23247",
                seasonId = "2026",
                robotId = "ares",
                projectPath = directory.path,
                league = League.FTC,
                driveDestination = destination,
            ),
        )
        val oauth = mock(OAuthService::class.java)
        `when`(oauth.authState).thenReturn(MutableStateFlow(account))
        return Fixture(
            GoogleDriveService(
                oauthService = oauth,
                environmentService = environment,
                httpClient = client,
                accessTokenOverride = { "test-access-token" },
                enforceWorkspaceScope = true,
            ),
            directory,
        )
    }

    private fun destination(rootId: String) = DriveDestinationConfig(
        type = DriveDestinationType.TEAM_FOLDER,
        rootFolderId = rootId,
        displayName = "Team A",
        accountSubject = account.uid,
        accountEmail = account.email,
        collaborationMode = WorkspaceCollaborationMode.TEAM,
    )

    private fun driveClient(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): HttpClient =
        HttpClient(MockEngine) {
            engine { addHandler(handler) }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private fun MockRequestHandleScope.metadata(
        id: String,
        name: String,
        parents: List<String>,
        ownedByMe: Boolean = false,
        canWrite: Boolean = true,
        driveId: String? = null,
    ) = respond(
        """{
          "id":"$id",
          "name":"$name",
          "mimeType":"application/vnd.google-apps.folder",
          "parents":[${parents.joinToString { "\"$it\"" }}],
          ${driveId?.let { "\"driveId\":\"$it\"," } ?: ""}
          "ownedByMe":$ownedByMe,
          "capabilities":{"canListChildren":true,"canEdit":$canWrite,"canAddChildren":$canWrite},
          "owners":[{"emailAddress":"owner@example.com"}],
          "permissions":[{"type":"user","role":"owner","emailAddress":"owner@example.com"}],
          "webViewLink":"https://drive.google.com/drive/folders/$id"
        }""".trimIndent(),
        HttpStatusCode.OK,
        jsonHeaders(),
    )

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
}
