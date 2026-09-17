package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ares.analytics.shared.models.ConsoleMessage

/** Immutable snapshot keys keep filtering current when a bounded buffer replaces its oldest entry. */
@Composable
internal fun rememberConsoleDisplayMessages(
    messages: SnapshotStateList<ConsoleMessage>,
    playheadMs: Long?,
): List<ConsoleMessage> {
    val snapshot = messages.toList()
    return remember(snapshot, playheadMs) {
        if (playheadMs == null) snapshot else snapshot.filter { it.timestampMs <= playheadMs }
    }
}
