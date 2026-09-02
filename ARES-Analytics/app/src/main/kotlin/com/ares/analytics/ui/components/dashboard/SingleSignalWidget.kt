package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MonitorHeart
import com.ares.analytics.ui.components.core.AresDialog
import com.ares.analytics.ui.components.forms.AresTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import kotlinx.coroutines.flow.collect

enum class DashboardSignalSource { LIVE, REPLAY }

data class DashboardSignalSample(
    val topic: String,
    val value: Double,
    val sourceTimestampMs: Long,
    val playheadTimestampMs: Long,
    val source: DashboardSignalSource,
)

data class SingleSignalConfiguration(
    val topic: String = "Robot/LoopTimeMs",
    val label: String = "Loop time",
    val unit: String = "ms",
    val displayMode: String = "value",
    val minimum: Double = 0.0,
    val maximum: Double = 40.0,
    val warningLow: Double? = null,
    val warningHigh: Double? = 25.0,
) {
    fun toProperties(): Map<String, String> = buildMap {
        put("topic", normalizeDashboardTopic(topic))
        put("label", label.trim())
        put("unit", unit.trim())
        put("displayMode", displayMode)
        put("minimum", minimum.toString())
        put("maximum", maximum.toString())
        warningLow?.let { put("warningLow", it.toString()) }
        warningHigh?.let { put("warningHigh", it.toString()) }
    }
}
fun singleSignalConfiguration(properties: Map<String, String>): SingleSignalConfiguration =
    SingleSignalConfiguration(
        topic = normalizeDashboardTopic(properties["topic"].orEmpty().ifBlank { "Robot/LoopTimeMs" }),
        label = properties["label"].orEmpty().ifBlank { "Loop time" },
        unit = properties["unit"].orEmpty(),
        displayMode = properties["displayMode"].takeIf { it == "bar" } ?: "value",
        minimum = properties["minimum"]?.toDoubleOrNull() ?: 0.0,
        maximum = properties["maximum"]?.toDoubleOrNull() ?: 40.0,
        warningLow = properties["warningLow"]?.toDoubleOrNull(),
        warningHigh = properties["warningHigh"]?.toDoubleOrNull(),
    )

fun resolveDashboardSignalSample(
    topic: String,
    liveFrame: TelemetryFrame?,
    replayFrame: ReplayFrame?,
): DashboardSignalSample? {
    val normalized = normalizeDashboardTopic(topic)
    if (replayFrame != null) {
        val entry = replayFrame.values.entries.firstOrNull { normalizeDashboardTopic(it.key) == normalized } ?: return null
        return DashboardSignalSample(
            topic = normalized,
            value = entry.value,
            sourceTimestampMs = replayFrame.timestampMs,
            playheadTimestampMs = replayFrame.playheadMs,
            source = DashboardSignalSource.REPLAY,
        )
    }
    val frame = liveFrame?.takeIf { normalizeDashboardTopic(it.key) == normalized } ?: return null
    return DashboardSignalSample(
        topic = normalized,
        value = frame.value,
        sourceTimestampMs = frame.timestampMs,
        playheadTimestampMs = frame.timestampMs,
        source = DashboardSignalSource.LIVE,
    )
}

fun normalizeDashboardTopic(topic: String): String = topic.trim().removePrefix("/")

@Composable
fun SingleSignalWidget(
    nt4ClientService: Nt4ClientService,
    replayFrame: ReplayFrame?,
    properties: Map<String, String>,
    onPropertiesChanged: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = remember(properties) { singleSignalConfiguration(properties) }
    var liveFrame by remember(configuration.topic) {
        mutableStateOf(
            nt4ClientService.latestValues.entries
                .firstOrNull { normalizeDashboardTopic(it.key) == configuration.topic }
                ?.value,
        )
    }
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(configuration.topic, replayFrame == null) {
        if (replayFrame == null) {
            nt4ClientService.uiTelemetryFlow.collect { frame ->
                if (normalizeDashboardTopic(frame.key) == configuration.topic) liveFrame = frame
            }
        }
    }

    val sample = resolveDashboardSignalSample(configuration.topic, liveFrame, replayFrame)
    val tone = signalTone(configuration, sample?.value)
    val sourceText = when (sample?.source) {
        DashboardSignalSource.LIVE -> "LIVE"
        DashboardSignalSource.REPLAY -> "REPLAY"
        null -> "NO DATA"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AresSurface, RoundedCornerShape(12.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.MonitorHeart, null, tint = tone, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(configuration.label, color = AresTextPrimary, fontWeight = FontWeight.Bold)
                Text(configuration.topic, color = AresTextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Text(sourceText, color = tone, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { editing = true }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Edit, "Configure signal widget", tint = AresTextSecondary, modifier = Modifier.size(16.dp))
            }
        }

        if (sample == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Waiting for this topic", color = AresTextSecondary)
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text(
                    text = buildString {
                        append(String.format("%.3f", sample.value))
                        if (configuration.unit.isNotBlank()) append(" ${configuration.unit}")
                    },
                    color = tone,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                if (configuration.displayMode == "bar" && configuration.maximum > configuration.minimum) {
                    val progress = ((sample.value - configuration.minimum) / (configuration.maximum - configuration.minimum))
                        .coerceIn(0.0, 1.0)
                    LinearProgressIndicator(
                        progress = { progress.toFloat() },
                        color = tone,
                        trackColor = AresSurfaceElevated,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                }
                val heldMs = (sample.playheadTimestampMs - sample.sourceTimestampMs).coerceAtLeast(0L)
                Text(
                    if (heldMs == 0L) "Exact source sample" else "Held $heldMs ms from the last source sample",
                    color = AresTextTertiary,
                    fontSize = 10.sp,
                )
            }
        }
    }

    if (editing) {
        SingleSignalConfigurationDialog(
            initial = configuration,
            topicSuggestions = if (replayFrame != null) replayFrame.values.keys else nt4ClientService.latestValues.keys,
            onDismiss = { editing = false },
            onSave = {
                onPropertiesChanged(it.toProperties())
                editing = false
            },
        )
    }
}

private fun signalTone(configuration: SingleSignalConfiguration, value: Double?): Color = when {
    value == null -> AresTextTertiary
    configuration.warningHigh != null && value >= configuration.warningHigh -> AresError
    configuration.warningLow != null && value <= configuration.warningLow -> AresAmber
    else -> AresGreen
}

@Composable
private fun SingleSignalConfigurationDialog(
    initial: SingleSignalConfiguration,
    topicSuggestions: Collection<String>,
    onDismiss: () -> Unit,
    onSave: (SingleSignalConfiguration) -> Unit,
) {
    var topic by remember { mutableStateOf(initial.topic) }
    var label by remember { mutableStateOf(initial.label) }
    var unit by remember { mutableStateOf(initial.unit) }
    var displayMode by remember { mutableStateOf(initial.displayMode) }
    var minimum by remember { mutableStateOf(initial.minimum.toString()) }
    var maximum by remember { mutableStateOf(initial.maximum.toString()) }
    var warningLow by remember { mutableStateOf(initial.warningLow?.toString().orEmpty()) }
    var warningHigh by remember { mutableStateOf(initial.warningHigh?.toString().orEmpty()) }
    val suggestions = remember(topicSuggestions) { topicSuggestions.map(::normalizeDashboardTopic).distinct().sorted() }
    val candidate = SingleSignalConfiguration(
        topic = topic,
        label = label,
        unit = unit,
        displayMode = displayMode,
        minimum = minimum.toDoubleOrNull() ?: Double.NaN,
        maximum = maximum.toDoubleOrNull() ?: Double.NaN,
        warningLow = warningLow.toDoubleOrNull(),
        warningHigh = warningHigh.toDoubleOrNull(),
    )
    val valid = topic.isNotBlank() && label.isNotBlank() && candidate.minimum.isFinite() &&
        candidate.maximum.isFinite() && candidate.maximum > candidate.minimum

    AresDialog(
        title = "Configure signal",
        onDismiss = onDismiss,
        confirmText = "Save",
        onConfirm = if (valid) { { onSave(candidate) } } else null,
        scrollable = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AresTextField(topic, { topic = it }, label = "Telemetry topic", singleLine = true, modifier = Modifier.fillMaxWidth())
            if (suggestions.isNotEmpty()) {
                Text("Available now", color = AresTextTertiary, fontSize = 10.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.take(3).forEach { suggestion ->
                        OutlinedButton(onClick = { topic = suggestion }, modifier = Modifier.weight(1f)) {
                            Text(suggestion.substringAfterLast('/'), maxLines = 1, fontSize = 10.sp)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AresTextField(label, { label = it }, label = "Label", singleLine = true, modifier = Modifier.weight(2f))
                AresTextField(unit, { unit = it }, label = "Display unit", singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { displayMode = "value" }, modifier = Modifier.weight(1f)) { Text("Value") }
                OutlinedButton(onClick = { displayMode = "bar" }, modifier = Modifier.weight(1f)) { Text("Value + bar") }
            }
            if (displayMode == "bar") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AresTextField(minimum, { minimum = it }, label = "Bar minimum", singleLine = true, modifier = Modifier.weight(1f))
                    AresTextField(maximum, { maximum = it }, label = "Bar maximum", singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AresTextField(warningLow, { warningLow = it }, label = "Warn below", singleLine = true, modifier = Modifier.weight(1f))
                AresTextField(warningHigh, { warningHigh = it }, label = "Warn above", singleLine = true, modifier = Modifier.weight(1f))
            }
            Text(
                "The unit is a display label; Studio does not silently convert the source value.",
                color = AresTextTertiary,
                fontSize = 10.sp,
            )
        }
    }
}
