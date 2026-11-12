package com.ares.analytics.ui.components.dashboard.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.analysis.SignalMathEngine
import com.ares.analytics.service.analysis.TimedScalarSample
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SessionTopicCatalog(
    val topics: List<String> = emptyList(),
    val timestampRange: Pair<Long, Long>? = null,
)

@Composable
internal fun rememberSessionTopicCatalog(
    databaseService: DatabaseService,
    sessionId: String?,
    nt4ClientService: Nt4ClientService?,
    liveRefreshTick: Int = 0,
): SessionTopicCatalog {
    val catalog by produceState(SessionTopicCatalog(), databaseService, sessionId, nt4ClientService, liveRefreshTick) {
        value = withContext(Dispatchers.IO) {
            if (!sessionId.isNullOrBlank()) {
                val keys = databaseService.getDistinctTelemetryKeys(sessionId)
                    .map { it.trimStart('/') }
                    .distinct()
                    .sorted()
                val range = databaseService.getSessionTimestampRange(sessionId)
                SessionTopicCatalog(topics = keys, timestampRange = range)
            } else if (nt4ClientService != null) {
                val keys = nt4ClientService.getActiveTopics()
                    .map { it.trimStart('/') }
                    .distinct()
                    .sorted()
                SessionTopicCatalog(topics = keys, timestampRange = null)
            } else {
                SessionTopicCatalog()
            }
        }
    }
    return catalog
}

internal suspend fun loadTopicScalarSamples(
    databaseService: DatabaseService,
    sessionId: String?,
    topic: String?,
    timestampRange: Pair<Long, Long>?,
    nt4ClientService: Nt4ClientService?,
    maxPoints: Int = 2_000,
): List<TimedScalarSample> {
    val cleanTopic = topic?.trim()?.trimStart('/')?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return withContext(Dispatchers.IO) {
        if (!sessionId.isNullOrBlank()) {
            val frames = if (timestampRange != null && timestampRange.second >= timestampRange.first) {
                databaseService.getTelemetrySeries(
                    sessionId = sessionId,
                    key = cleanTopic,
                    startMs = timestampRange.first,
                    endMs = timestampRange.second,
                    maxPoints = maxPoints,
                ).ifEmpty {
                    databaseService.getTelemetrySeries(
                        sessionId = sessionId,
                        key = "/$cleanTopic",
                        startMs = timestampRange.first,
                        endMs = timestampRange.second,
                        maxPoints = maxPoints,
                    )
                }
            } else {
                databaseService.getTelemetryForKey(sessionId, cleanTopic)
            }
            SignalMathEngine.fromFrames(frames)
        } else if (nt4ClientService != null) {
            val history = nt4ClientService.telemetryStore.history(cleanTopic)
                .ifEmpty { nt4ClientService.telemetryStore.history("/$cleanTopic") }
            SignalMathEngine.fromFrames(history)
        } else {
            emptyList()
        }
    }
}

internal fun pickBestTopic(
    topics: List<String>,
    vararg keywords: String,
    excludeKeywords: List<String> = emptyList(),
): String? = topics.firstOrNull { key ->
    val lower = key.lowercase()
    keywords.any { kw -> lower.contains(kw.lowercase()) } &&
        excludeKeywords.none { ex -> lower.contains(ex.lowercase()) }
}

@Composable
internal fun AnalysisTopicSelectorChip(
    label: String,
    selectedTopic: String?,
    availableTopics: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(AresBackground)
                .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                .clickable(enabled = availableTopics.isNotEmpty() || allowNone) { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "$label:",
                color = AresTextTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = selectedTopic?.substringAfterLast('/')?.ifBlank { selectedTopic } ?: "None",
                color = if (selectedTopic != null) AresCyan else AresTextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AresTextSecondary, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(AresSurface).heightIn(max = 280.dp),
        ) {
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text("None (Auto / Derive)", color = AresTextSecondary, fontSize = 11.sp) },
                    onClick = {
                        onSelect("")
                        expanded = false
                    },
                )
            }
            availableTopics.take(80).forEach { topic ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = topic,
                            color = if (topic == selectedTopic) AresCyan else AresTextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                    onClick = {
                        onSelect(topic)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun AnalysisModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color = AresCyan,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (selected) accent.copy(alpha = 0.18f) else AresBackground,
        border = BorderStroke(1.dp, if (selected) accent else AresBorder),
    ) {
        Text(
            text = label,
            color = if (selected) accent else AresTextSecondary,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun AnalysisMetricPill(
    label: String,
    value: String,
    valueColor: Color = AresTextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(AresBackground, RoundedCornerShape(6.dp))
            .border(1.dp, AresBorder.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(label, color = AresTextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}
