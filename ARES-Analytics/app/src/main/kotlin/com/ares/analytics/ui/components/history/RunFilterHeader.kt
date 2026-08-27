package com.ares.analytics.ui.components.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*

@Composable
fun RunFilterHeader(
    isAiAnalystOpen: Boolean,
    onToggleAiAnalyst: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Robot Run History Spreadsheet",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = AresTextPrimary
            )
            Text(
                "Interactive matrix of session telemetry & control constants. Click any row header to chart its values.",
                fontSize = 13.sp,
                color = AresTextSecondary
            )
        }

        Button(
            onClick = onToggleAiAnalyst,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isAiAnalystOpen) AresCyan.copy(alpha = 0.2f) else AresCyan,
                contentColor = if (isAiAnalystOpen) AresCyan else AresOnAccent,
            ),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresCyan),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = if (isAiAnalystOpen) AresCyan else AresBackground,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "AI Run Analyst",
                color = if (isAiAnalystOpen) AresCyan else AresBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
