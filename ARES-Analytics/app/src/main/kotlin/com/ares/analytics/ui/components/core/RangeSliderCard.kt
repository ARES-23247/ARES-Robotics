package com.ares.analytics.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*

/**
 * Standardized slider card with label, live formatted value, bounds display, and theme coloring.
 */
@Composable
fun RangeSliderCard(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    unit: String? = null,
    steps: Int = 0,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.sp, color = AresTextSecondary, fontWeight = FontWeight.Bold)
            Text(
                text = "${String.format("%.2f", value)}${unit ?: ""}",
                fontSize = 12.sp,
                color = AresCyan,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = AresCyan,
                activeTrackColor = AresCyan,
                inactiveTrackColor = AresSurfaceElevated
            )
        )
    }
}

/**
 * Standardized numeric text input field with Save button for tuning panels.
 */
@Composable
fun NumericValueField(
    value: Double?,
    onSave: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var textValue by remember(value) { mutableStateOf(value?.toString() ?: "") }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BasicTextField(
            value = textValue,
            onValueChange = { textValue = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = AresTextPrimary),
            cursorBrush = SolidColor(AresCyan),
            modifier = Modifier
                .width(90.dp)
                .height(32.dp)
                .background(AresSurface, RoundedCornerShape(6.dp))
                .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
        Button(
            onClick = {
                val parsed = textValue.toDoubleOrNull()
                if (parsed != null) onSave(parsed)
            },
            modifier = Modifier.width(60.dp).height(32.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("Save", color = AresBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
