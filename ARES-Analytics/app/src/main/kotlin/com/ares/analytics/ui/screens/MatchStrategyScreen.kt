
package com.ares.analytics.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*

/**
 * Driver coaching card containing telemetry analysis feedback on driver inputs and robot power usage.
 *
 * @property title Coaching rule headline.
 * @property detail Detailed diagnostic description (e.g. voltage sag $V_{\text{sag}} > 1.2\text{ V}$).
 * @property category Rule domain category (`"VOLTAGE"`, `"ACCELERATION"`, `"POSITION_HOLD"`, `"PATHING"`).
 * @property severity Severity rating (`"TIP"`, `"WARNING"`, `"EXCELLENT"`).
 */
data class DriverCoachingCard(
    val title: String,
    val detail: String,
    val category: String, // ACCELERATION, VOLTAGE, POSITION_HOLD, PATHING
    val severity: String // TIP, WARNING, EXCELLENT
)

/**
 * Match strategy and AI driver coaching screen.
 *
 * Analyzes historical match logs to highlight driver efficiency, battery voltage sag mitigation, pathing smoothness, and defensive positioning.
 *
 * @see DriverCoachingCard
 * @see com.ares.analytics.service.DriverAnalysisService
 */
@Composable
fun MatchStrategyScreen() {
    val coachingCards = remember {
        listOf(
            DriverCoachingCard(
                title = "Aggressive Acceleration Voltage Drop",
                detail = "Driver applied 100% stick input from standstill at t=42s, causing a 1.4V battery drop. Smooth joystick ramps preserve battery voltage.",
                category = "VOLTAGE",
                severity = "WARNING"
            ),
            DriverCoachingCard(
                title = "Mecanum Position Hold Precision",
                detail = "Position hold engaged 14 times during match with average positioning error of 0.012m. Static friction kS feedforward successfully broke foam tile friction.",
                category = "POSITION_HOLD",
                severity = "EXCELLENT"
            ),
            DriverCoachingCard(
                title = "AprilTag Alignment Efficiency",
                detail = "Limelight 3D pose alignment completed in 320ms per cycle. Mahalanobis outlier filter rejected 2 blurred frames during rapid rotation.",
                category = "PATHING",
                severity = "TIP"
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AresBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            color = AresGold.copy(alpha = .12f),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AresGold)
        ) {
            Text(
                "DEVELOPER PREVIEW — Every value on this page is sample data. Do not use it for match decisions.",
                color = AresGold,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }
        // Header Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Analytics, contentDescription = null, tint = AresPurple, modifier = Modifier.size(28.dp))
                        Text(
                            text = "Strategy UI Preview",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = AresTextPrimary
                        )
                    }
                    Text(
                        text = "Sample layout for a future session-backed analysis workflow",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AresTextSecondary
                    )
                }
            }
        }

        // Field Heatmap & Battery Drain Main Layout
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left: FTC Field Heatmap Canvas
            Card(
                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("FIELD TRAJECTORY & DRIVER SPEED HEATMAP", style = MaterialTheme.typography.labelMedium, color = AresCyan, fontWeight = FontWeight.Bold)

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                    ) {
                        val w = size.width
                        val h = size.height

                        // Draw Grid Lines
                        val cols = 6
                        val rows = 6
                        for (i in 1 until cols) {
                            drawLine(AresBorder.copy(alpha = 0.4f), Offset(i * w / cols, 0f), Offset(i * w / cols, h), 1f)
                        }
                        for (j in 1 until rows) {
                            drawLine(AresBorder.copy(alpha = 0.4f), Offset(0f, j * h / rows), Offset(w, j * h / rows), 1f)
                        }

                        // Simulated Heatmap Dots
                        val heatmapPoints = listOf(
                            Offset(w * 0.2f, h * 0.3f), Offset(w * 0.25f, h * 0.32f), Offset(w * 0.3f, h * 0.4f),
                            Offset(w * 0.5f, h * 0.5f), Offset(w * 0.7f, h * 0.6f), Offset(w * 0.8f, h * 0.8f)
                        )

                        heatmapPoints.forEach { pt ->
                            drawCircle(AresGold.copy(alpha = 0.6f), radius = 24f, center = pt)
                            drawCircle(AresCyan, radius = 8f, center = pt)
                        }
                    }
                }
            }

            // Right: Automated Driver Coaching Cards
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Lightbulb, contentDescription = null, tint = AresGold, modifier = Modifier.size(20.dp))
                        Text("AUTOMATED DRIVER COACHING INSIGHTS", style = MaterialTheme.typography.labelMedium, color = AresGold, fontWeight = FontWeight.Bold)
                    }

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(coachingCards) { card ->
                            val borderCol = when (card.severity) {
                                "EXCELLENT" -> AresGreen
                                "WARNING" -> AresGold
                                else -> AresCyan
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, borderCol)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                                        Surface(color = borderCol.copy(alpha = 0.2f), shape = RoundedCornerShape(10.dp)) {
                                            Text(card.severity, color = borderCol, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                        }
                                    }
                                    Text(card.detail, style = MaterialTheme.typography.bodySmall, color = AresTextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
