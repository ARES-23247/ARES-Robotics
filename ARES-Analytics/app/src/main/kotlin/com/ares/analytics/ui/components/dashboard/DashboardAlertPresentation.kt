package com.ares.analytics.ui.components.dashboard

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.AlertEngineService
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch

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

@Composable
fun DashboardCriticalAlertStack(
    alerts: List<AlertRecord>,
    onDismiss: (AlertRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.width(320.dp).heightIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(alerts, key = { it.alertId }) { alert ->
            Card(
                colors = CardDefaults.cardColors(containerColor = AresError.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Warning, null, tint = AresBackground, modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = criticalAlertTitle(alert.ruleKey),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AresBackground,
                        )
                        Text(
                            text = dashboardAlertDetail(alert),
                            fontSize = 10.sp,
                            color = AresBackground.copy(alpha = 0.8f),
                        )
                    }
                    IconButton(onClick = { onDismiss(alert) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, "Dismiss", tint = AresBackground, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun CriticalAlertOverlay(
    alertEngineService: AlertEngineService
) {
    val alerts by alertEngineService.alerts.collectAsState()
    val scope = rememberCoroutineScope()

    // Active untriaged critical alert (unresolved first, then resolved but untriaged)
    val criticalAlert = alerts.firstOrNull { !it.triaged && it.resolveTimestampMs == null }
        ?: alerts.firstOrNull { !it.triaged }

    AnimatedVisibility(
        visible = criticalAlert != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        if (criticalAlert != null) {
            val ruleName = alertEngineService.getRuleDisplayName(criticalAlert.ruleKey)
            val isStall = criticalAlert.ruleKey.contains("Stall")
            val isDisconnect = criticalAlert.ruleKey.contains("Disconnected")
            val isLowBattery = criticalAlert.ruleKey.contains("Voltage")
            val isCanError = criticalAlert.ruleKey.contains("CAN")
            val isI2cError = criticalAlert.ruleKey.contains("I2C")

            val bannerBg = when {
                isStall || isCanError -> AresRed.copy(alpha = 0.95f)
                isLowBattery || isI2cError -> AresGold.copy(alpha = 0.95f)
                else -> AresRedDark.copy(alpha = 0.95f)
            }
            // Select the readable semantic foreground for both normal and colorblind palettes.
            // This avoids the former low-contrast white-on-gold alert treatment.
            val bannerForeground = readableForeground(bannerBg, AresOnAccent, AresTextPrimary)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = bannerBg),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, AresRedGlow)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = bannerForeground,
                            modifier = Modifier.size(36.dp)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = ruleName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = bannerForeground,
                                fontSize = 16.sp
                            )

                            val detailText = when {
                                isStall -> "Motor power is ON but wheel velocity is ZERO! Check wheel for mechanical binding, bound screws, or gear lockup immediately!"
                                isDisconnect -> "Motor is commanded but drawing ZERO current. Check motor cable connections and REV hub fuse!"
                                isLowBattery -> "Battery voltage is below critical threshold. Swap battery to prevent robot reboot!"
                                isCanError -> "CAN bus utilization or transmit error detected. Check CAN bus wiring, 120-ohm termination, or packet rates!"
                                isI2cError -> "FTC I2C / Lynx bus timeout detected. Check REV Hub I2C sensor wiring!"
                                else -> "Peak Value: %.2f | Rule Key: %s".format(criticalAlert.peakValue, criticalAlert.ruleKey)
                            }

                            Text(
                                text = detailText,
                                style = MaterialTheme.typography.bodySmall,
                                color = bannerForeground.copy(alpha = 0.9f),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                alertEngineService.triageAlert(criticalAlert.alertId)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = AresBackground, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Acknowledge", color = AresBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

