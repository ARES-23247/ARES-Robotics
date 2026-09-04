package com.ares.analytics.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.*

@Composable
internal fun LeagueSelector(league: League, onLeagueChange: (League) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Competition", style = MaterialTheme.typography.labelSmall, color = AresTextSecondary)
        Row(modifier = Modifier.fillMaxWidth().border(1.dp, AresBorder, RoundedCornerShape(8.dp))) {
            League.entries.forEach { option ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (league == option) AresCyanGlow else Color.Transparent)
                        .clickable { onLeagueChange(option) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.name,
                        color = if (league == option) AresCyan else AresTextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
