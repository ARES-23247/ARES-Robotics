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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.ares.analytics.ui.components.core.*

@Composable
fun StateMachineTrackerCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // Map of Subsystem Name -> Current State String
    val activeStates = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(Unit) {
        scope.launch {
            nt4ClientService.uiTelemetryFlow.collect { frame ->
                val key = frame.key
                // Typical state paths: /robot/subsystems/Intake/State, /robot/auto/CurrentState
                if (key.endsWith("/State") || key.endsWith("/CurrentState")) {
                    val subsystem = key.substringBeforeLast("/").substringAfterLast("/")
                    val stateValue = frame.stringValue ?: frame.value.toString()
                    activeStates[subsystem] = stateValue
                }
            }
        }
    }

    AresCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = AresSurfaceElevated
    ) {
        CardHeader(
            title = "Subsystem States",
            icon = Icons.Default.AccountTree,
            iconTint = AresCyan
        )

            if (activeStates.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No state machines detected via NT4.", color = AresTextTertiary, fontSize = 12.sp)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeStates.forEach { (subsystem, state) ->
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

