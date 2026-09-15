package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame

/** Loading a selected recording must not expose a retained frame from another recording. */
internal fun selectDashboardReplayFrame(
    frame: ReplayFrame?, primarySessionId: String?, isReplayActive: Boolean,
): ReplayFrame? {
    if (primarySessionId == null && !isReplayActive) return null
    val expectedSession = primarySessionId ?: Nt4ClientService.LIVE_SESSION_ID
    return frame?.takeIf { expectedSession.isNotBlank() && it.sessionId == expectedSession }
}
