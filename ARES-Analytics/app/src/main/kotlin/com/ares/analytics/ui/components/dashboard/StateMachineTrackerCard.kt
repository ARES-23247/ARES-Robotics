package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.ui.components.core.*
import com.ares.analytics.ui.theme.*

internal fun parseSubsystemStateTopicName(rawKey: String): String? {
    val clean = rawKey.trim().trimStart('/')
    val matchesStateSuffix = clean.endsWith("/State", ignoreCase = true) ||
        clean.endsWith("/CurrentState", ignoreCase = true) ||
        clean.endsWith("/CurrentStep", ignoreCase = true) ||
        clean.endsWith("/Phase", ignoreCase = true)
    if (!matchesStateSuffix) return null
    return clean.substringBeforeLast('/').substringAfterLast('/').takeIf { it.isNotBlank() }
}

internal fun extractReplaySubsystemStates(frame: ReplayFrame): Map<String, String> {
    val states = LinkedHashMap<String, String>()
    for ((key, strValue) in frame.stringValues) {
        val subsystem = parseSubsystemStateTopicName(key) ?: continue
        val text = strValue.trim()
        if (text.isNotEmpty()) states[subsystem] = text
    }
    for ((key, numValue) in frame.values) {
        if (key in frame.stringValues || !numValue.isFinite()) continue
        val subsystem = parseSubsystemStateTopicName(key) ?: continue
        if (subsystem !in states) states[subsystem] = numValue.toString()
    }
    return states.toSortedMap()
}

@Composable
fun StateMachineTrackerCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier,
    replayFrame: ReplayFrame? = null,
) {
    val liveStates = remember(nt4ClientService) { mutableStateMapOf<String, String>() }

    LaunchedEffect(nt4ClientService, replayFrame != null) {
        if (replayFrame != null) return@LaunchedEffect
        nt4ClientService.uiTelemetryFlow.collect { frame ->
            val subsystem = parseSubsystemStateTopicName(frame.key) ?: return@collect
            val stateValue = frame.stringValue?.takeIf { it.isNotBlank() } ?: frame.value.toString()
            liveStates[subsystem] = stateValue
        }
    }

    val displayedStates: Map<String, String> = remember(replayFrame?.sequence, liveStates.toMap()) {
        replayFrame?.let(::extractReplaySubsystemStates) ?: liveStates.toSortedMap()
    }

    AresCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = AresSurfaceElevated
    ) {
        CardHeader(
            title = if (replayFrame != null) "Subsystem States · Replay" else "Subsystem States",
            icon = Icons.Default.AccountTree,
            iconTint = AresCyan
        )

        if (displayedStates.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(100.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No state machines detected.", color = AresTextTertiary, fontSize = 12.sp)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                displayedStates.forEach { (subsystem, state) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AresBackground)
                            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(subsystem, color = AresTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = state,
                            color = AresCyan,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
