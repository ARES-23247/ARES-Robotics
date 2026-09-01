package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch
import com.ares.analytics.ui.components.core.*

@Composable
fun SessionSummaryCard(
    databaseService: DatabaseService,
    sessionId: String?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<SessionSummary?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            isLoading = true
            scope.launch {
                summary = databaseService.getSessionSummary(sessionId)
                isLoading = false
            }
        } else {
            summary = null
        }
    }

    AresCard(
        modifier = modifier
    ) {
        CardHeader(
            title = "Session Aggregate Stats",
            icon = Icons.Default.Assessment,
            iconTint = AresCyan
        )

        val currentSummary = summary
        when {
            isLoading -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Loading summary...", color = AresTextTertiary, fontSize = 12.sp)
                }
            }
            currentSummary == null -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Select a session to view aggregate stats.", color = AresTextTertiary, fontSize = 12.sp)
                }
            }
            else -> {
                val s = currentSummary
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    item { StatItem("Avg Loop Time", String.format("%.2f ms", s.avgLoopTimeMs)) }
                    item { StatItem("P95 Loop Time", String.format("%.2f ms", s.p95LoopTimeMs)) }
                    item { StatItem("Min Battery", String.format("%.1f V", s.minBatteryVoltage)) }
                    item { StatItem("Battery Resistance", String.format("%.3f Ω", s.avgBatteryResistance)) }
                    item { StatItem("Max EKF Drift", String.format("%.3f m", s.maxEkfDrift)) }
                    item { StatItem("Avg Cross-Track Err", String.format("%.3f m", s.avgCrossTrackError)) }
                    item { StatItem("Vision Acc. Rate", String.format("%.1f %%", s.visionAcceptanceRate * 100)) }
                    item { StatItem("Avg Vision Latency", String.format("%.1f ms", s.avgVisionLatencyMs)) }

                    s.motorCurrentAverages.forEach { (motor, avgAmp) ->
                        item {
                            StatItem("Avg $motor Curr.", String.format("%.2f A", avgAmp))
                        }
                    }

                    s.maxMotorTemps.forEach { (motor, maxTemp) ->
                        item {
                            StatItem("Max $motor Temp", String.format("%.1f °C", maxTemp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = AresTextTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = AresTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
