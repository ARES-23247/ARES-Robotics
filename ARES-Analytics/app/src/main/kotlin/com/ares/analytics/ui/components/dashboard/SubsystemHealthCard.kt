package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.ares.analytics.service.hardware.SubsystemHealthAccumulator
import com.ares.analytics.service.hardware.SubsystemHealthSnapshot
import com.ares.analytics.service.hardware.SubsystemHealthStatus
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import kotlinx.coroutines.delay

/** Generic health view for every GUI-generated or hand-authored subsystem using ARES telemetry. */
@Composable
fun SubsystemHealthCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier,
) {
    val accumulator = remember { SubsystemHealthAccumulator() }
    var nowNs by remember { mutableLongStateOf(System.nanoTime()) }

    LaunchedEffect(nt4ClientService) {
        nt4ClientService.uiTelemetryFlow.collect { frame ->
            accumulator.accept(frame, System.nanoTime())
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250L)
            nowNs = System.nanoTime()
        }
    }

    val snapshots = accumulator.snapshots(nowNs)
    SubsystemHealthContent(snapshots = snapshots, modifier = modifier)
}

/** Presentational boundary kept separate so deterministic Compose screenshots need no NT4 socket. */
@Composable
internal fun SubsystemHealthContent(
    snapshots: List<SubsystemHealthSnapshot>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AresSurface, RoundedCornerShape(12.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = AresCyan)
                Text("Subsystem Health", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "${snapshots.count { it.status == SubsystemHealthStatus.HEALTHY }}/${snapshots.size} ready",
                color = if (snapshots.isNotEmpty() && snapshots.all { it.status == SubsystemHealthStatus.HEALTHY }) AresGreen else AresTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        HorizontalDivider(color = AresBorder)
        if (snapshots.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("No subsystem health telemetry yet", color = AresTextSecondary, fontWeight = FontWeight.SemiBold)
                Text("Run a generated robot or simulator to discover mechanisms automatically.", color = AresTextTertiary, fontSize = 11.sp)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                snapshots.forEach { SubsystemHealthRow(it) }
            }
        }
    }
}

@Composable
private fun SubsystemHealthRow(snapshot: SubsystemHealthSnapshot) {
    val statusColor = snapshot.status.displayColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AresSurfaceElevated, RoundedCornerShape(8.dp))
            .border(1.dp, statusColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(snapshot.subsystemId, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(snapshot.status.label.uppercase(), color = statusColor, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
        snapshot.issues.firstOrNull()?.let { issue ->
            Text(issue, color = AresTextSecondary, fontSize = 11.sp)
        }
        if (snapshot.issues.isEmpty() && snapshot.measurements.isEmpty()) {
            Text("Configuration, feedback, and safety checks are healthy.", color = AresTextSecondary, fontSize = 11.sp)
        }
        if (snapshot.measurements.isNotEmpty()) {
            Text(
                snapshot.measurements.entries.take(4).joinToString("   ") { (name, value) -> "$name=${"%.3f".format(value)}" },
                color = AresTextTertiary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun SubsystemHealthStatus.displayColor(): Color = when (this) {
    SubsystemHealthStatus.HEALTHY -> AresGreen
    SubsystemHealthStatus.INCOMPLETE,
    SubsystemHealthStatus.STALE,
    SubsystemHealthStatus.NEEDS_HOMING,
    SubsystemHealthStatus.NEEDS_CALIBRATION,
    SubsystemHealthStatus.CURRENT_INVALID -> AresAmber
    SubsystemHealthStatus.OUTPUT_FAULT,
    SubsystemHealthStatus.HOMING_FAULT,
    SubsystemHealthStatus.CONFIGURATION_FAULT,
    SubsystemHealthStatus.FEEDBACK_INVALID -> AresRed
}
