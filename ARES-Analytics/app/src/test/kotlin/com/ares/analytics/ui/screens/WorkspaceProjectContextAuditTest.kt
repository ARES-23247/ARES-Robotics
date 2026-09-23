package com.ares.analytics.ui.screens

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceProjectContextAuditTest {

    @Test
    fun `rememberWorkspaceCoroutineScope cancels active job and child coroutines on project path change and preserves scope across preference mutation`() = runTest {
        var observedConfig by mutableStateOf(
            WorkspaceConfig(
                id = "workspace-alpha",
                teamId = "23247",
                seasonId = "2026",
                robotId = "robot-1",
                projectPath = "/home/user/robot-v1",
                league = League.FTC,
                colorblindMode = false,
            )
        )
        var observedScope: CoroutineScope? = null

        val scene = ImageComposeScene(10, 10, coroutineContext = StandardTestDispatcher(testScheduler))
        fun pump() {
            runCurrent()
            scene.render(testScheduler.currentTime * 1_000_000).close()
            runCurrent()
        }

        var secondScope: CoroutineScope? = null
        try {
            scene.setContent {
                val scope = rememberWorkspaceCoroutineScope(observedConfig.id, observedConfig.projectPath)
                SideEffect { observedScope = scope }
            }

            pump()
            val initialScope = checkNotNull(observedScope) { "Initial workspace coroutine scope should be composed" }
            assertTrue(initialScope.isActive, "Initial workspace coroutine scope should be active")

            // Launch a child coroutine on the first scope to track its lifecycle.
            var firstChildStarted = false
            var firstChildFinallyRan = false
            initialScope.launch {
                try {
                    firstChildStarted = true
                    awaitCancellation()
                } finally {
                    firstChildFinallyRan = true
                }
            }
            pump()
            assertTrue(firstChildStarted, "Child coroutine on initial scope must have started")
            assertFalse(firstChildFinallyRan, "Child coroutine on initial scope must still be active")

            // Mutate a preference in observed WorkspaceConfig to induce recomposition.
            observedConfig = observedConfig.copy(colorblindMode = true)
            pump()

            val scopeAfterPrefChange = checkNotNull(observedScope)
            assertSame(initialScope, scopeAfterPrefChange, "Scope must be preserved across in-workspace preference change")
            assertTrue(initialScope.isActive, "Initial scope must remain active across preference change")
            assertFalse(firstChildFinallyRan, "Child on initial scope must not be cancelled by preference change")

            // Mutate project path with the same workspace ID.
            observedConfig = observedConfig.copy(projectPath = "/home/user/robot-v2")
            pump()

            val newScope = checkNotNull(observedScope)
            secondScope = newScope
            assertNotSame(initialScope, newScope, "Scope must be re-keyed when project path changes")
            assertFalse(initialScope.isActive, "Initial workspace scope must be cancelled on project path change")
            assertTrue(firstChildFinallyRan, "Child coroutine finally block on initial scope must have executed upon path change")
            assertTrue(newScope.isActive, "New workspace scope must be active")

            // Prove new scope can run work.
            var secondChildRan = false
            newScope.launch {
                secondChildRan = true
            }
            pump()
            assertTrue(secondChildRan, "New workspace scope must be able to launch and execute work")
        } finally {
            // Dispose scene and assert new scope is cancelled upon disposal.
            scene.close()
            runCurrent()
            if (secondScope != null) {
                assertFalse(secondScope.isActive, "New workspace scope must be cancelled upon scene disposal")
            }
        }
    }
}
