package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.*
import com.ares.analytics.shared.models.AlertRecord

internal enum class DashboardCriticalKind(val title: String) {
    BROWNOUT("CRITICAL BROWNOUT"), COMMS("COMMS / PACKET LOSS"),
    CAN("CANBUS HARDWARE ERROR"), BATTERY("LOW BATTERY ALERT"),
}

private val CAN_SEGMENT = Regex("(?:^|[^a-z0-9])can(?:$|[^a-z0-9])", RegexOption.IGNORE_CASE)

/** Preserve known diagnostic categories without matching incidental text such as 'cancelled'. */
internal fun dashboardCriticalKind(ruleKey: String): DashboardCriticalKind? = when {
    ruleKey.contains("brownout", ignoreCase = true) -> DashboardCriticalKind.BROWNOUT
    ruleKey.contains("comms", ignoreCase = true) -> DashboardCriticalKind.COMMS
    ruleKey.contains("canbus", ignoreCase = true) || CAN_SEGMENT.containsMatchIn(ruleKey) -> DashboardCriticalKind.CAN
    ruleKey.contains("battery", ignoreCase = true) -> DashboardCriticalKind.BATTERY
    else -> null
}

internal fun criticalAlertTitle(ruleKey: String): String =
    dashboardCriticalKind(ruleKey)?.title ?: ruleKey.replace('_', ' ').uppercase()

internal fun currentDashboardAlerts(alerts: List<AlertRecord>, sessionId: String, enabled: Boolean): List<AlertRecord> =
    if (!enabled) emptyList() else alerts.filter { it.sessionId == sessionId && it.resolveTimestampMs == null }

private val ALERT_NEWEST_FIRST = Comparator<AlertRecord> { left, right ->
    val time = right.triggerTimestampMs.compareTo(left.triggerTimestampMs)
    if (time != 0) time else left.alertId.compareTo(right.alertId)
}

internal fun highestPriorityDashboardAlert(alerts: List<AlertRecord>): AlertRecord? {
    var best: AlertRecord? = null
    var bestCritical = false
    for (alert in alerts) {
        val critical = dashboardCriticalKind(alert.ruleKey) != null
        val previous = best
        if (previous == null || (critical && !bestCritical) ||
            (critical == bestCritical && ALERT_NEWEST_FIRST.compare(alert, previous) < 0)) {
            best = alert
            bestCritical = critical
        }
    }
    return best
}

internal data class DashboardAlertPopups(val alerts: List<AlertRecord>, val dismiss: (AlertRecord) -> Unit)

/** Dismissal survives peak updates while the alert remains active in the same live source. */
@Composable
internal fun rememberDashboardAlertPopups(
    alerts: List<AlertRecord>, sessionId: String, enabled: Boolean,
): DashboardAlertPopups {
    var dismissed by remember(sessionId, enabled) { mutableStateOf(emptySet<String>()) }
    val current = remember(alerts, sessionId, enabled) {
        currentDashboardAlerts(alerts, sessionId, enabled).filter { dashboardCriticalKind(it.ruleKey) != null }
    }
    LaunchedEffect(current) {
        val activeIds = current.mapTo(HashSet()) { it.alertId }
        dismissed = dismissed.intersect(activeIds)
    }
    val visible = remember(current, dismissed) { current.filter { it.alertId !in dismissed }.sortedWith(ALERT_NEWEST_FIRST) }
    return DashboardAlertPopups(visible) { dismissed = dismissed + it.alertId }
}

/** Alert timestamps are source-domain milliseconds, not necessarily Unix wall time. */
internal fun dashboardAlertDetail(alert: AlertRecord): String {
    val peak = alert.peakValue.takeIf(Double::isFinite)?.let { String.format("%.2f", it) } ?: "--"
    val time = alert.triggerTimestampMs.takeIf { it >= 0L }?.let { "$it ms" } ?: "unknown"
    return "Peak: $peak | Source time: $time"
}
