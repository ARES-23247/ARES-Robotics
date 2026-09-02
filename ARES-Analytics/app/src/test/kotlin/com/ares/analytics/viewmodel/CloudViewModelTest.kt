package com.ares.analytics.viewmodel

import com.ares.analytics.service.AuthState
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.OAuthService
import com.ares.analytics.service.RobotLogIngestionService
import com.ares.analytics.service.SyncEngineService
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.shared.models.DriveDestinationConfig
import com.ares.analytics.shared.models.DriveDestinationType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class CloudViewModelTest {
    @Test
    fun `entering Cloud reloads sessions created after workspace graph construction`() = runTest {
        val directory = Files.createTempDirectory("ares-cloud-entry-").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").path)
        val oauth = mock(OAuthService::class.java)
        `when`(oauth.authState).thenReturn(MutableStateFlow(AuthState.Unauthenticated))
        val workspace = WorkspaceConfig(
            teamId = "23247",
            seasonId = "2026",
            robotId = "lightbot",
            projectPath = directory.path,
            league = League.FTC,
        )
        val viewModel = CloudViewModel(
            databaseService = database,
            syncEngineService = mock(SyncEngineService::class.java),
            oauthService = oauth,
            nt4ClientService = mock(Nt4ClientService::class.java),
            robotLogIngestionService = mock(RobotLogIngestionService::class.java),
            workspaceConfig = workspace,
            scope = backgroundScope,
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) { viewModel.state.first { !it.isSyncing } }
            }
            database.insertSession(
                Session(
                    sessionId = "created-later",
                    teamId = workspace.teamId,
                    seasonId = workspace.seasonId,
                    robotId = workspace.robotId,
                    createdAt = 1_000L,
                ),
            )

            viewModel.onScreenEntered()

            val refreshed = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    viewModel.state.first { state -> state.sessions.any { it.summary.sessionId == "created-later" } }
                }
            }
            assertTrue(refreshed.sessions.single { it.summary.sessionId == "created-later" }.isLocal)
        } finally {
            viewModel.dispose()
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `remote cloud index requires authentication and an explicit destination`() {
        val destination = DriveDestinationConfig(
            type = DriveDestinationType.PERSONAL_FOLDER,
            rootFolderId = "folder-id",
            displayName = "ARES runs",
            accountSubject = "account-subject",
            accountEmail = "student@example.com",
        )

        assertFalse(shouldLoadRemoteCloudIndex(isAuthenticated = false, driveDestination = null))
        assertFalse(shouldLoadRemoteCloudIndex(isAuthenticated = true, driveDestination = null))
        assertFalse(shouldLoadRemoteCloudIndex(isAuthenticated = false, driveDestination = destination))
        assertTrue(shouldLoadRemoteCloudIndex(isAuthenticated = true, driveDestination = destination))
    }

    @Test
    fun `robot log refresh failures are concise and identify the root cause`() {
        val message = robotLogRefreshFailureMessage(
            "10.99.92.2",
            IllegalStateException("catalog failed", java.net.ConnectException("Connection refused\nignored detail")),
        )

        assertEquals(
            "[CloudViewModel] Robot log refresh failed for 10.99.92.2: ConnectException — Connection refused",
            message,
        )
        assertFalse(message.contains('\n'))
    }
}
