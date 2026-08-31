package com.ares.analytics.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope

/**
 * Returns a Compose-owned scope whose job is cancelled when the active workspace identity changes.
 * Workspace view models must never borrow the application shell scope.
 */
@Composable
internal fun rememberWorkspaceCoroutineScope(workspaceId: String): CoroutineScope = key(workspaceId) {
    rememberCoroutineScope()
}
