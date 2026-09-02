package com.ares.analytics.ui.components.tuning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.SysIdIntent
import com.ares.analytics.viewmodel.SysIdViewModel

@Composable
fun AbortCard(viewModel: SysIdViewModel, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().border(1.dp, AresError, RoundedCornerShape(8.dp)),
        color = AresError.copy(alpha = 0.05f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("CALIBRATION IN PROGRESS", color = AresError, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Robot is executing routine.", color = AresTextSecondary, fontSize = 11.sp)
            }
            Button(
                onClick = { viewModel.onIntent(SysIdIntent.StopCalibration) },
                colors = ButtonDefaults.buttonColors(containerColor = AresError, contentColor = AresOnAccent),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("ABORT TEST (STOP)", color = AresOnAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun RoutineButton(
    name: String,
    desc: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(220.dp)
            .height(80.dp)
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) AresSurfaceElevated else AresSurfaceElevated.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (enabled) AresCyan else AresTextTertiary)
            Text(desc, fontSize = 11.sp, color = AresTextTertiary, lineHeight = 14.sp)
        }
    }
}

@Composable
fun CalibrationTriggerCard(
    name: String,
    desc: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) AresSurfaceElevated else AresSurfaceElevated.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(if (enabled) AresCyan else AresBorder, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (enabled) AresBackground else AresTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (enabled) AresCyan else AresTextTertiary)
                Text(desc, fontSize = 11.sp, color = AresTextTertiary, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
fun ParamRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(AresSurfaceElevated).border(1.dp, AresBorder, RoundedCornerShape(6.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = AresTextSecondary)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AresTextPrimary)
    }
}

@Composable
fun ApplyButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
        modifier = modifier.fillMaxWidth().height(36.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text("Send result to proposal board", color = AresOnAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}
