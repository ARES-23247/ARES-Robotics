@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.pit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.AlertRuleConfiguration
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.ThresholdRule
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

private data class EditableRuleRow(
    val id: Int,
    val key: String,
    val displayName: String,
    val minText: String,
    val maxText: String,
    val audibleAlert: Boolean,
)

@Composable
fun PitAlertThresholdsTab(
    activeRules: List<ThresholdRule>,
    configWarning: String?,
    activeLeague: League,
    onSaveHotReload: suspend (List<ThresholdRule>) -> Result<List<ThresholdRule>>,
) {
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<EditableRuleRow>() }
    var nextRowId by remember { mutableStateOf(1) }
    var statusMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun loadFromRules(source: List<ThresholdRule>) {
        rows.clear()
        source.forEach { rule ->
            rows += EditableRuleRow(
                id = nextRowId++,
                key = rule.key,
                displayName = rule.displayName,
                minText = rule.minValue?.toString().orEmpty(),
                maxText = rule.maxValue?.toString().orEmpty(),
                audibleAlert = rule.audibleAlert,
            )
        }
    }

    LaunchedEffect(activeRules) {
        if (rows.isEmpty() && activeRules.isNotEmpty()) {
            loadFromRules(activeRules)
        }
    }

    val compiled: Pair<List<ThresholdRule>, String?> = remember(rows.toList()) {
        val out = mutableListOf<ThresholdRule>()
        var parseProblem: String? = null
        for ((idx, row) in rows.withIndex()) {
            val minVal = row.minText.trim().takeIf(String::isNotEmpty)?.let { raw ->
                raw.toDoubleOrNull() ?: run {
                    parseProblem = "Row ${idx + 1}: Min bound '$raw' is not a finite number."
                    return@let null
                }
            }
            if (parseProblem != null) break
            val maxVal = row.maxText.trim().takeIf(String::isNotEmpty)?.let { raw ->
                raw.toDoubleOrNull() ?: run {
                    parseProblem = "Row ${idx + 1}: Max bound '$raw' is not a finite number."
                    return@let null
                }
            }
            if (parseProblem != null) break
            out += ThresholdRule(
                key = row.key.trim(),
                displayName = row.displayName.trim(),
                minValue = minVal,
                maxValue = maxVal,
                audibleAlert = row.audibleAlert,
            )
        }
        val validationError = parseProblem ?: AlertRuleConfiguration.validateRules(out)
        out to validationError
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            border = BorderStroke(1.dp, AresBorder),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Live Alert Threshold Rules (`thresholds.json`)",
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Text(
                            "Configure bounds for NT4 & live alert monitoring with atomic disk persistence and zero-restart hot-reload.",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            rows += EditableRuleRow(
                                id = nextRowId++,
                                key = "Subsystem/ExampleMetric",
                                displayName = "Custom Alert Rule",
                                minText = "",
                                maxText = "100.0",
                                audibleAlert = true,
                            )
                        }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp), tint = AresCyan)
                            Spacer(Modifier.width(4.dp))
                            Text("Add Rule", fontSize = 11.sp)
                        }
                        Button(
                            enabled = compiled.second == null && rows.isNotEmpty(),
                            onClick = {
                                val (candidateRules, validationError) = compiled
                                if (validationError != null) {
                                    statusMessage = false to validationError
                                } else {
                                    scope.launch {
                                        onSaveHotReload(candidateRules)
                                            .onSuccess { updated ->
                                                loadFromRules(updated)
                                                statusMessage = true to "Saved ${updated.size} alert rule(s) to thresholds.json and hot-reloaded live evaluator."
                                            }
                                            .onFailure { failure ->
                                                statusMessage = false to (failure.message ?: "Failed to save alert thresholds.")
                                            }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Save & Hot-Reload", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "League presets (${activeLeague.name} active):",
                        color = AresTextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    OutlinedButton(onClick = {
                        loadFromRules(AlertRuleConfiguration.defaultRulesForLeague(League.FTC))
                        statusMessage = true to "Loaded FTC 12V preset into editor (click Save & Hot-Reload to apply)."
                    }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("FTC 12V Preset", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = {
                        loadFromRules(AlertRuleConfiguration.defaultRulesForLeague(League.FRC))
                        statusMessage = true to "Loaded FRC 12V preset into editor (click Save & Hot-Reload to apply)."
                    }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("FRC 12V Preset", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = {
                        loadFromRules(AlertRuleConfiguration.defaultRulesForLeague(League.XRP))
                        statusMessage = true to "Loaded XRP 4-Cell preset into editor (click Save & Hot-Reload to apply)."
                    }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("XRP 4-Cell Preset", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = {
                        loadFromRules(activeRules)
                        statusMessage = null
                    }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("Revert Unsaved", fontSize = 11.sp)
                    }
                }

                configWarning?.let { warning ->
                    Surface(
                        color = AresAmber.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, AresAmber.copy(alpha = 0.55f)),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = AresAmber, modifier = Modifier.size(16.dp))
                            Text(warning, color = AresTextPrimary, fontSize = 11.sp)
                        }
                    }
                }

                compiled.second?.let { err ->
                    Text(err, color = AresError, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                } ?: statusMessage?.let { (ok, msg) ->
                    Text(msg, color = if (ok) AresGreen else AresError, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                val normKey = TelemetryMetricCatalog.normalizeTopic(row.key)
                val isLoopTemporal = normKey in TelemetryMetricCatalog.LOOP_TIME.keys
                val isDerivedMotor = normKey.startsWith("Hardware/Motors/") &&
                    (normKey.endsWith("/Stall") || normKey.endsWith("/Disconnected"))

                Card(
                    colors = CardDefaults.cardColors(containerColor = AresSurface),
                    border = BorderStroke(1.dp, AresBorder),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = row.key,
                                onValueChange = { updated -> rows[index] = row.copy(key = updated) },
                                label = { Text("Topic Key", fontSize = 10.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1.1f),
                            )
                            OutlinedTextField(
                                value = row.displayName,
                                onValueChange = { updated -> rows[index] = row.copy(displayName = updated) },
                                label = { Text("Alert Message / Label", fontSize = 10.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1.4f),
                            )
                            OutlinedTextField(
                                value = row.minText,
                                onValueChange = { updated -> rows[index] = row.copy(minText = updated) },
                                label = { Text("Min", fontSize = 10.sp) },
                                placeholder = { Text("—") },
                                singleLine = true,
                                modifier = Modifier.width(86.dp),
                            )
                            OutlinedTextField(
                                value = row.maxText,
                                onValueChange = { updated -> rows[index] = row.copy(maxText = updated) },
                                label = { Text("Max", fontSize = 10.sp) },
                                placeholder = { Text("—") },
                                singleLine = true,
                                modifier = Modifier.width(86.dp),
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Beep", color = AresTextTertiary, fontSize = 10.sp)
                                Switch(
                                    checked = row.audibleAlert,
                                    onCheckedChange = { checked -> rows[index] = row.copy(audibleAlert = checked) },
                                )
                            }
                            IconButton(onClick = { rows.removeAt(index) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete rule", tint = AresError, modifier = Modifier.size(16.dp))
                            }
                        }

                        if (isLoopTemporal || isDerivedMotor) {
                            val hint = if (isLoopTemporal) {
                                "Temporal detector: requires maxValue=25.0 (3 samples >25ms in 1s or >=100ms) or blank bounds to disable."
                            } else {
                                "Derived motor fault flag (0.0 ok / 1.0 active): maxValue=0.5 triggers when fault bit is asserted."
                            }
                            Surface(
                                color = AresSurfaceElevated,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    hint,
                                    color = AresTextSecondary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
