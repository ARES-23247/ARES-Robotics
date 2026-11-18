package com.ares.analytics.ui.components.dashboard.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.flow.sample
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
    val latestSampleMs: Long? = null,
    val generation: Long = 0,
    val timestampRange: Pair<Long, Long>? = null,
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun rememberSessionTopicCatalog(
    databaseService: DatabaseService,
    sessionId: String?,
    nt4ClientService: Nt4ClientService?,
): SessionTopicCatalog {
    val catalog by produceState(SessionTopicCatalog(), databaseService, sessionId, nt4ClientService) {
        value = SessionTopicCatalog()
        if (!sessionId.isNullOrBlank() && sessionId != "live-telemetry") {
            value = withContext(Dispatchers.IO) {
                val topics = databaseService.getDistinctTelemetryKeys(sessionId).map { it.trimStart('/') }.distinct().sorted()
                SessionTopicCatalog(topics = topics, timestampRange = databaseService.getSessionTimestampRange(sessionId))
            }
        } else if (nt4ClientService != null) {
            fun topics() = nt4ClientService.getActiveTopics().map { it.trimStart('/') }.distinct().sorted()
            value = SessionTopicCatalog(topics = topics())
            nt4ClientService.uiTelemetryFlow.sample(500L).collect { frame ->
                value = SessionTopicCatalog(topics(), frame.timestampMs, value.generation + 1)
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
        if (!sessionId.isNullOrBlank() && sessionId != "live-telemetry") {
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
        if (expanded) {
            var search by remember { mutableStateOf("") }
            val matches = remember(search, availableTopics) {
                availableTopics.filter { it.contains(search.trim(), ignoreCase = true) }
            }
            AlertDialog(
                onDismissRequest = { expanded = false },
                title = { Text("Choose $label") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(search, { search = it }, label = { Text("Search topics") }, singleLine = true)
                        if (allowNone) TextButton(onClick = { onSelect(""); expanded = false }) { Text("None (Auto / Derive)") }
                        if (matches.isEmpty()) Text("No matching topics")
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                            items(matches, key = { it }) { topic ->
                                DropdownMenuItem(text = { Text(topic, fontFamily = FontFamily.Monospace) }, onClick = {
                                    onSelect(topic)
                                    expanded = false
                                })
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { expanded = false }) { Text("Close") } },
            )
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
