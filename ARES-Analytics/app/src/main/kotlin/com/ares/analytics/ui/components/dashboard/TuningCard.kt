package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.tuning.TuningTransport
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.ares.analytics.ui.components.core.AnalyticsCard
import com.ares.analytics.ui.components.core.CardHeader
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Read-only dashboard summary driven by the same component declaration metadata as the tuning
 * profile board. Editing and promotion intentionally live in the Tuning screen's review flow.
 */
@Composable
fun TuningCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier,
    declarations: List<TuningParameterDeclaration>
) {
    AnalyticsCard(modifier.fillMaxSize(), backgroundColor = AresSurface, contentPadding = 12.dp) {
        CardHeader(title = "Live Tuning Observations", showDivider = false)
        Text("Read-only telemetry. Open Tuning to propose, validate, live-test, or promote a robot profile.", color = AresTextSecondary, fontSize = 10.sp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (declarations.isEmpty()) {
                Text("Project tuning metadata is unavailable. Open the project profile or Tuning screen and reload; ARES will not infer constants.", color = AresGold, fontSize = 10.sp)
            }
            declarations.groupBy { it.componentUid }.forEach { (component, rows) ->
                Text(component, color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                rows.forEach { declaration -> LiveDeclaredValue(nt4ClientService, declaration) }
                HorizontalDivider(color = AresBorder.copy(alpha = .5f))
            }
        }
    }
}

@Composable
private fun LiveDeclaredValue(nt4: Nt4ClientService, declaration: TuningParameterDeclaration) {
    val currentTopic = TuningTransport.current(declaration)
    val frame by nt4.uiTelemetryFlow.filter { it.key == currentTopic }.map { it as com.ares.analytics.shared.models.TelemetryFrame? }
        .onStart { emit(nt4.latestValues[currentTopic]) }.collectAsState(initial = null)
    val display = when (declaration.type) {
        TuningParameterType.DOUBLE -> frame?.value?.takeIf { it.isFinite() }?.let(::format)
        TuningParameterType.INT -> frame?.value?.takeIf { it.isFinite() }?.toInt()?.toString()
        TuningParameterType.BOOLEAN -> frame?.let { if (it.value != 0.0) "true" else "false" }
        TuningParameterType.TEXT, TuningParameterType.ENUM -> frame?.stringValue
    }
    Row(
        Modifier.fillMaxWidth().semantics {
            contentDescription = "${declaration.displayName}. Live observed value ${display ?: "unavailable"} ${declaration.unit}. Read only."
        },
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(declaration.displayName, color = AresTextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text("${declaration.unit} · ${declaration.applyPolicy.name.lowercase().replace('_', ' ')}", color = AresTextSecondary, fontSize = 8.sp)
        }
        Text(display ?: "— unavailable", color = if (display != null) AresTextPrimary else AresTextSecondary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

private fun format(value: Double) = "%.5f".format(value).trimEnd('0').trimEnd('.')
