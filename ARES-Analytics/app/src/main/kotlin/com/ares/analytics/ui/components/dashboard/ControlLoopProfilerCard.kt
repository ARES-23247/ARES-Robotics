package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Speed
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
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal data class ControlLoopInstantValues(
    val availableMotors: List<String>,
    val targetValue: Double?,
    val actualValue: Double?,
)

internal fun discoverControlLoopMotors(keys: Collection<String>): List<String> =
    keys.asSequence()
        .map { it.trim().trimStart('/') }
        .filter {
            it.endsWith("/TargetPosition", ignoreCase = true) ||
                it.endsWith("/TargetVelocity", ignoreCase = true) ||
                it.endsWith("/Setpoint", ignoreCase = true)
        }
        .map { it.substringBeforeLast('/') }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .toList()

internal fun extractReplayControlLoopValues(
    frame: ReplayFrame,
    selectedMotor: String,
): ControlLoopInstantValues {
    val motors = discoverControlLoopMotors(frame.values.keys)
    val active = selectedMotor.takeIf { it != "Select Motor" && it in motors } ?: motors.firstOrNull()
    if (active == null) return ControlLoopInstantValues(motors, null, null)
    val target = frame.values["$active/TargetPosition"]
        ?: frame.values["$active/TargetVelocity"]
        ?: frame.values["$active/Setpoint"]
    val actual = frame.values["$active/ActualPosition"]
        ?: frame.values["$active/ActualVelocity"]
        ?: frame.values["$active/Position"]
        ?: frame.values["$active/Velocity"]
    return ControlLoopInstantValues(
        availableMotors = motors,
        targetValue = target?.takeIf { it.isFinite() },
        actualValue = actual?.takeIf { it.isFinite() },
    )
}

@Composable
fun ControlLoopProfilerCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier,
    replayFrame: ReplayFrame? = null,
    databaseService: DatabaseService? = null,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var selectedMotor by remember { mutableStateOf("Select Motor") }
    var availableMotors by remember { mutableStateOf<List<String>>(emptyList()) }
    var targetValue by remember { mutableStateOf<Double?>(null) }
    var actualValue by remember { mutableStateOf<Double?>(null) }

    val targetHistory = remember { mutableStateListOf<Pair<Long, Double>>() }
    val actualHistory = remember { mutableStateListOf<Pair<Long, Double>>() }
    val historyWindowMs = 5000L

    LaunchedEffect(nt4ClientService, replayFrame != null) {
        if (replayFrame != null) return@LaunchedEffect
        while (true) {
            val motors = discoverControlLoopMotors(nt4ClientService.getActiveTopics())
            if (motors != availableMotors) availableMotors = motors
            if (selectedMotor == "Select Motor" && motors.isNotEmpty()) {
                selectedMotor = motors.first()
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(selectedMotor, replayFrame != null) {
        if (replayFrame != null || selectedMotor == "Select Motor") return@LaunchedEffect
        targetHistory.clear()
        actualHistory.clear()

        launch {
            nt4ClientService.uiTelemetryFlow.collect { frame ->
                val cleanKey = frame.key.removePrefix("/")
                val now = System.currentTimeMillis()
                when (cleanKey) {
                    "$selectedMotor/TargetPosition", "$selectedMotor/TargetVelocity", "$selectedMotor/Setpoint" -> {
                        targetValue = frame.value
                        targetHistory.add(now to frame.value)
                        while (targetHistory.isNotEmpty() && now - targetHistory.first().first > historyWindowMs) {
                            targetHistory.removeAt(0)
                        }
                    }
                    "$selectedMotor/ActualPosition", "$selectedMotor/ActualVelocity",
                    "$selectedMotor/Position", "$selectedMotor/Velocity" -> {
                        actualValue = frame.value
                        actualHistory.add(now to frame.value)
                        while (actualHistory.isNotEmpty() && now - actualHistory.first().first > historyWindowMs) {
                            actualHistory.removeAt(0)
                        }
                    }
                }
            }
        }

        launch {
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

    // Replay mode updates instant target/actual and queries 5s trailing trace
    val replayBucket = replayFrame?.playheadMs?.div(200L)
    LaunchedEffect(replayFrame?.sessionId, replayFrame?.sequence) {
        val replay = replayFrame ?: return@LaunchedEffect
        val instant = extractReplayControlLoopValues(replay, selectedMotor)
        availableMotors = instant.availableMotors
        if ((selectedMotor == "Select Motor" || selectedMotor !in instant.availableMotors) && instant.availableMotors.isNotEmpty()) {
            selectedMotor = instant.availableMotors.first()
        }
        targetValue = instant.targetValue
        actualValue = instant.actualValue
        if (databaseService == null) {
            val tNow = replay.playheadMs
            instant.targetValue?.let { v ->
                if (targetHistory.lastOrNull()?.first?.let { tNow < it } == true) targetHistory.clear()
                targetHistory.add(tNow to v)
                while (targetHistory.isNotEmpty() && tNow - targetHistory.first().first > historyWindowMs) targetHistory.removeAt(0)
            }
            instant.actualValue?.let { v ->
                if (actualHistory.lastOrNull()?.first?.let { tNow < it } == true) actualHistory.clear()
                actualHistory.add(tNow to v)
                while (actualHistory.isNotEmpty() && tNow - actualHistory.first().first > historyWindowMs) actualHistory.removeAt(0)
            }
        }
    }

    LaunchedEffect(replayFrame?.sessionId, replayBucket, selectedMotor) {
        val replay = replayFrame ?: return@LaunchedEffect
        val database = databaseService ?: return@LaunchedEffect
        if (selectedMotor == "Select Motor") return@LaunchedEffect
        val startMs = (replay.playheadMs - historyWindowMs).coerceAtLeast(0L)
        val endMs = replay.playheadMs
        val targetKeys = listOf("$selectedMotor/TargetPosition", "$selectedMotor/TargetVelocity", "$selectedMotor/Setpoint")
        val actualKeys = listOf("$selectedMotor/ActualPosition", "$selectedMotor/ActualVelocity", "$selectedMotor/Position", "$selectedMotor/Velocity")
        val (loadedTargets, loadedActuals) = withContext(Dispatchers.IO) {
            val tSeries = targetKeys.firstNotNullOfOrNull { k ->
                database.getTelemetrySeries(replay.sessionId, k, startMs, endMs, 300).takeIf { it.isNotEmpty() }
            }.orEmpty()
            val aSeries = actualKeys.firstNotNullOfOrNull { k ->
                database.getTelemetrySeries(replay.sessionId, k, startMs, endMs, 300).takeIf { it.isNotEmpty() }
            }.orEmpty()
            tSeries to aSeries
        }
        targetHistory.clear()
        loadedTargets.forEach { targetHistory.add(it.timestampMs to it.value) }
        actualHistory.clear()
        loadedActuals.forEach { actualHistory.add(it.timestampMs to it.value) }
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
                    Icon(Icons.Default.Speed, contentDescription = "Profiler", tint = AresCyan, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (replayFrame != null) "Control Loop Profiler · Replay" else "Control Loop Profiler",
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
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
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AresTextSecondary, modifier = Modifier.size(16.dp))
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TARGET", color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(targetValue?.let { String.format("%.2f", it) } ?: "--", color = AresCyan, fontSize = 24.sp, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ACTUAL", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(actualValue?.let { String.format("%.2f", it) } ?: "--", color = Color.White, fontSize = 24.sp, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ERROR", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    val error = targetValue?.let { target -> actualValue?.let { actual -> target - actual } }
                    Text(
                        text = error?.let { String.format("%.2f", it) } ?: "--",
                        color = if (error != null && abs(error) > 5.0) AresError else AresGreen,
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
                    Text("Waiting for telemetry...", color = AresTextTertiary, fontSize = 12.sp)
                } else {
                    val plotEndMs = replayFrame?.playheadMs ?: System.currentTimeMillis()
                    Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
                        val width = size.width
                        val height = size.height
                        var minValue = Double.MAX_VALUE
                        var maxValue = -Double.MAX_VALUE
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
                            minValue -= 1.0
                        }
                        val range = maxValue - minValue
                        minValue -= range * 0.1
                        maxValue += range * 0.1
                        val newRange = maxValue - minValue

                        for (i in 0..4) {
                            val y = height - (i.toFloat() / 4f) * height
                            drawLine(color = AresBorder, start = Offset(0f, y), end = Offset(width, y), strokeWidth = 1f)
                        }
                        val startTimeMs = plotEndMs - historyWindowMs
                        fun mapToOffset(timeMs: Long, value: Double): Offset {
                            val x = ((timeMs - startTimeMs).toFloat() / historyWindowMs.toFloat()) * width
                            val y = height - ((value - minValue) / newRange).toFloat() * height
                            return Offset(x, y)
                        }
                        if (actualHistory.size > 1) {
                            val path = Path()
                            actualHistory.forEachIndexed { idx, (t, v) ->
                                val pt = mapToOffset(t, v)
                                if (idx == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                            }
                            drawPath(path, color = Color.White, style = Stroke(width = 2.dp.toPx()))
                        }
                        if (targetHistory.size > 1) {
                            val path = Path()
                            targetHistory.forEachIndexed { idx, (t, v) ->
                                val pt = mapToOffset(t, v)
                                if (idx == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                            }
                            drawPath(path, color = AresCyan, style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}
