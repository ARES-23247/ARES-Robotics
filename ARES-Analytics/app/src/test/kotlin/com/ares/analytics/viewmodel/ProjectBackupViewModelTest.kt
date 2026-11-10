package com.ares.analytics.viewmodel

import com.ares.analytics.service.versioncontrol.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.mockito.Mockito.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectBackupViewModelTest {
    @Test fun `local load does not contact GitHub and failed explicit discovery preserves local history`() = runTest {
        val f = Fixture(this)
        f.authState.value = GitHubConnectionState.Connected("offline-test-account")
        runCurrent()
        `when`(f.service.inspect("first")).thenReturn(plan("first"))
        `when`(f.auth.discoverDestinations()).thenAnswer { throw java.io.IOException("offline") }
        f.vm.onIntent(ProjectBackupIntent.Load("first")); runCurrent()
        assertEquals("first", f.vm.state.value.plan?.projectPath)
        verify(f.auth, never()).discoverDestinations()
        f.vm.onIntent(ProjectBackupIntent.RefreshGitHubDestinations); runCurrent()
        assertEquals("first", f.vm.state.value.plan?.projectPath)
        assertContains(f.vm.state.value.error!!, "Local project history is available")
        assertFalse(f.vm.state.value.isBusy)
    }

    @Test fun `queued project switch preserves the admitted write target`() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        `when`(f.service.inspect("first")).thenReturn(plan("first"))
        `when`(f.service.inspect("second")).thenReturn(plan("second"))
        `when`(f.service.commit("first", "token", "message", "name", "email")).thenAnswer(suspendingAnswer {
            release.await(); plan("first")
        })
        f.vm.onIntent(ProjectBackupIntent.Load("first")); runCurrent()
        f.vm.onIntent(ProjectBackupIntent.SaveVersion("token", "message", "name", "email"))
        f.vm.onIntent(ProjectBackupIntent.Load("second")); runCurrent()
        assertNull(f.vm.state.value.plan)
        assertTrue(f.vm.state.value.isBusy)
        release.complete(Unit); runCurrent()
        verify(f.service, times(1)).commit("first", "token", "message", "name", "email")
        assertEquals("second", f.vm.state.value.plan?.projectPath)
        assertNull(f.vm.state.value.notice)
    }

    @Test fun `cancelled action releases busy state and permits another inspection`() = runTest {
        val f = Fixture(this)
        `when`(f.service.inspect("first")).thenThrow(kotlinx.coroutines.CancellationException("cancelled inspection"))
        f.vm.onIntent(ProjectBackupIntent.Load("first")); runCurrent()
        assertFalse(f.vm.state.value.isBusy)
        assertNull(f.vm.state.value.error)
        `when`(f.service.inspect("second")).thenReturn(plan("second"))
        f.vm.onIntent(ProjectBackupIntent.Load("second")); runCurrent()
        assertEquals("second", f.vm.state.value.plan?.projectPath)
    }

    @Test fun `new project selected during inspection replaces the old result`() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        `when`(f.service.inspect("first")).thenAnswer(suspendingAnswer { release.await(); plan("first") })
        `when`(f.service.inspect("second")).thenReturn(plan("second"))
        f.vm.onIntent(ProjectBackupIntent.Load("first")); runCurrent()
        f.vm.onIntent(ProjectBackupIntent.Load("second")); runCurrent()
        assertNull(f.vm.state.value.plan)
        release.complete(Unit); runCurrent()
        assertEquals("second", f.vm.state.value.projectPath)
        assertEquals("second", f.vm.state.value.plan?.projectPath)
        assertFalse(f.vm.state.value.isBusy)
    }

    @Test fun `repeated save intents admit one write before the UI dispatcher runs`() = runTest {
        val f = Fixture(this)
        `when`(f.service.inspect("first")).thenReturn(plan("first"))
        f.vm.onIntent(ProjectBackupIntent.Load("first")); runCurrent()
        `when`(f.service.commit("first", "token", "message", "name", "email")).thenReturn(plan("first"))
        repeat(3) { f.vm.onIntent(ProjectBackupIntent.SaveVersion("token", "message", "name", "email")) }
        runCurrent()
        verify(f.service, times(1)).commit("first", "token", "message", "name", "email")
        assertFalse(f.vm.state.value.isBusy)
    }

    @Test fun `latest queued project wins and old failure is not shown for it`() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        `when`(f.service.inspect("first")).thenAnswer(suspendingAnswer { release.await(); error("old project failed") })
        `when`(f.service.inspect("third")).thenReturn(plan("third"))
        f.vm.onIntent(ProjectBackupIntent.Load("first")); runCurrent()
        f.vm.onIntent(ProjectBackupIntent.Load("second"))
        f.vm.onIntent(ProjectBackupIntent.Load("third"))
        release.complete(Unit); runCurrent()
        assertEquals("third", f.vm.state.value.plan?.projectPath)
        assertNull(f.vm.state.value.error)
        verify(f.service, never()).inspect("second")
    }

    private class Fixture(test: TestScope) {
        val service = mock(ProjectVersionControlService::class.java)
        val auth = mock(GitHubAuthenticationService::class.java)
        val authState = MutableStateFlow<GitHubConnectionState>(GitHubConnectionState.Disconnected)
        val auto = mock(ProjectBackupAutoSyncService::class.java)
        val vm: ProjectBackupViewModel
        init {
            `when`(auth.state).thenReturn(authState)
            `when`(auto.state).thenReturn(MutableStateFlow(ProjectBackupAutoSyncState()))
            vm = ProjectBackupViewModel(service, mock(ProjectRemoteBackupService::class.java), mock(ProjectRecoveryService::class.java),
                auth, auto, mock(ProjectArchiveExporter::class.java), test.backgroundScope)
        }
    }
    companion object {
        private fun plan(path: String) = ProjectBackupPlan(path, true, "main", emptyList(), emptyList(), "commit", null, null, null)
    }
}
