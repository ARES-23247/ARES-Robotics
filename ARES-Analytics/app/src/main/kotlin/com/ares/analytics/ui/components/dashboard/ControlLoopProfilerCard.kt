package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun ControlLoopProfilerCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var selectedMotor by remember { mutableStateOf("Select Motor") }
    var availableMotors by remember { mutableStateOf<List<String>>(emptyList()) }
    var targetValue by remember { mutableStateOf<Double?>(null) }
    var actualValue by remember { mutableStateOf<Double?>(null) }

    // History buffers for plotting (TimeMs, Value)
    val targetHistory = remember { mutableStateListOf<Pair<Long, Double>>() }
    val actualHistory = remember { mutableStateListOf<Pair<Long, Double>>() }
    val historyWindowMs = 5000L

    LaunchedEffect(Unit) {
        scope.launch {
            while (true) {
                // Periodically update available motors based on active topics
                val topics = nt4ClientService.getActiveTopics()
                // Find topics that look like motor targets/actuals
                val motors = topics.filter { it.endsWith("/TargetPosition") || it.endsWith("/TargetVelocity") }
                    .map { it.substringBeforeLast("/") }
                    .distinct()
                    .sorted()
                if (motors != availableMotors) {
                    availableMotors = motors
                }
                if (selectedMotor == "Select Motor" && motors.isNotEmpty()) {
                    selectedMotor = motors.first()
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    LaunchedEffect(selectedMotor) {
        if (selectedMotor != "Select Motor") {
            // Clear history when switching motors
            targetHistory.clear()
            actualHistory.clear()

            scope.launch {
                nt4ClientService.uiTelemetryFlow.collect { frame ->
                    val now = System.currentTimeMillis()
                    when {
                        frame.key == "$selectedMotor/TargetPosition" || frame.key == "$selectedMotor/TargetVelocity" -> {
                            val v = frame.value
                            targetValue = v
                            targetHistory.add(now to v)
                            // Prune old values
                            while (targetHistory.isNotEmpty() && now - targetHistory.first().first > historyWindowMs) {
                                targetHistory.removeAt(0)
                            }
                        }
                        frame.key == "$selectedMotor/ActualPosition" || frame.key == "$selectedMotor/ActualVelocity" -> {
                            val v = frame.value
                            actualValue = v
                            actualHistory.add(now to v)
                            // Prune old values
                            while (actualHistory.isNotEmpty() && now - actualHistory.first().first > historyWindowMs) {
                                actualHistory.removeAt(0)
                            }
                        }
                    }
                }
            }

            // Background cleanup loop for periods of inactivity
            scope.launch {
                while (true) {
                    val now = System.currentTimeMillis()
                    if (targetHistory.isNotEmpty() && now - targetHistory.last().first > historyWindowMs) {
                        targetHistory.clear()
                    }
                    if (actualHistory.isNotEmpty() && now - actualHistory.last().first > historyWindowMs) {
                        actualHistory.clear()
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Profiler",
                        tint = AresCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Control Loop Profiler", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                }

                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AresBackground)
                            .clickable { expanded = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            selectedMotor.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: selectedMotor,
                            color = AresTextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = AresTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(AresSurface)
                    ) {
                        availableMotors.forEach { motor ->
                            DropdownMenuItem(
                                text = { Text(motor, color = AresTextPrimary) },
                                onClick = {
                                    selectedMotor = motor
                                    targetValue = null
                                    actualValue = null
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = AresBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TARGET", color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = targetValue?.let { String.format("%.2f", it) } ?: "--",
                        color = AresCyan,
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ACTUAL", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = actualValue?.let { String.format("%.2f", it) } ?: "--",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ERROR", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    val error = targetValue?.let { target ->
                        actualValue?.let { actual -> target - actual }
                    }
                    Text(
                        text = error?.let { String.format("%.2f", it) } ?: "--",
                        color = if (error != null && kotlin.math.abs(error) > 5.0) AresError else AresGreen,
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AresBackground)
                    .border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (targetHistory.isEmpty() && actualHistory.isEmpty()) {
                    Text(
                        "Waiting for telemetry...",
                        color = AresTextTertiary,
                        fontSize = 12.sp
                    )
                } else {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                    ) {
                        val width = size.width
                        val height = size.height

                        // Determine value range
                        var minValue = Double.MAX_VALUE
                        var maxValue = Double.MIN_VALUE

                        targetHistory.forEach { (_, v) ->
                            if (v < minValue) minValue = v
                            if (v > maxValue) maxValue = v
                        }
                        actualHistory.forEach { (_, v) ->
                            if (v < minValue) minValue = v
                            if (v > maxValue) maxValue = v
                        }

                        if (minValue == Double.MAX_VALUE) return@Canvas
                        if (maxValue == minValue) {
                            maxValue = minValue + 1.0
                            minValue = minValue - 1.0
                        }

                        // Add some padding to Y scale
                        val range = maxValue - minValue
                        minValue -= range * 0.1
                        maxValue += range * 0.1
                        val newRange = maxValue - minValue

                        // Draw grid lines
                        val steps = 4
                        for (i in 0..steps) {
                            val y = height - (i.toFloat() / steps) * height
                            drawLine(
                                color = AresBorder,
                                start = Offset(0f, y),
                                end = Offset(width, y),
                                strokeWidth = 1f
                            )
                        }
                        val now = System.currentTimeMillis()
                        val startTimeMs = now - historyWindowMs

                        fun mapToOffset(timeMs: Long, value: Double): Offset {
                            val x = ((timeMs - startTimeMs).toFloat() / historyWindowMs.toFloat()) * width
                            val y = height - ((value - minValue) / newRange).toFloat() * height
                            return Offset(x, y)
                        }

                        // Plot Actual (White)
                        if (actualHistory.size > 1) {
                            val path = Path()
                            var first = true
                            actualHistory.forEach { (t, v) ->
                                val pt = mapToOffset(t, v)
                                if (first) {
                                    path.moveTo(pt.x, pt.y)
                                    first = false
                                } else {
                                    path.lineTo(pt.x, pt.y)
                                }
                            }
                            drawPath(path, color = Color.White, style = Stroke(width = 2.dp.toPx()))
                        }

                        // Plot Target (Cyan)
                        if (targetHistory.size > 1) {
                            val path = Path()
                            var first = true
                            targetHistory.forEach { (t, v) ->
                                val pt = mapToOffset(t, v)
                                if (first) {
                                    path.moveTo(pt.x, pt.y)
                                    first = false
                                } else {
                                    path.lineTo(pt.x, pt.y)
                                }
                            }
                            drawPath(path, color = AresCyan, style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}

