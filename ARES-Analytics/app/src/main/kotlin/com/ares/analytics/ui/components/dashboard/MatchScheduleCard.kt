package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MatchScheduleCard(
    matches: List<MatchInfo>,
    currentTeamId: String,
    onSelectMatch: (MatchInfo, String) -> Unit, // (match, allianceColor)
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val now = System.currentTimeMillis()

    // Find the next upcoming match (scheduled time is in the future, or closest to now)
    val nextMatch = matches.filter { (it.scheduledTime ?: 0L) >= now }
        .minByOrNull { it.scheduledTime ?: Long.MAX_VALUE }
        ?: matches.lastOrNull()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.AutoMirrored.Filled.EventNote, contentDescription = null, tint = AresCyan)
            Text(
                "Match Schedule",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AresTextPrimary
            )
        }

        HorizontalDivider(color = AresBorder, thickness = 1.dp)

        if (matches.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No matches scheduled or event key missing.", color = AresTextTertiary, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(matches) { match ->
                    val isUpcoming = match == nextMatch
                    val containsCurrentTeam = match.redAlliance.contains(currentTeamId) || match.blueAlliance.contains(currentTeamId)
                    val allianceColor = when {
                        match.redAlliance.contains(currentTeamId) -> "red"
                        match.blueAlliance.contains(currentTeamId) -> "blue"
                        else -> null
                    }
                    val cardBorder = when {
                        isUpcoming -> AresCyan
                        containsCurrentTeam -> AresGold
                        else -> AresBorder
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AresSurfaceElevated)
                            .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                            .clickable {
                                onSelectMatch(match, allianceColor ?: "red")
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "${match.compLevel.uppercase()} Match #${match.matchNumber}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isUpcoming) AresCyan else AresTextPrimary
                                )
                                if (isUpcoming) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(AresCyan.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("UPCOMING", color = AresCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            match.scheduledTime?.let {
                                Text(
                                    "Scheduled: ${timeFormat.format(Date(it))}",
                                    fontSize = 11.sp,
                                    color = AresTextSecondary
                                )
                            }
                        }

                        // Alliance representations
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Red Alliance teams
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                match.redAlliance.forEach { team ->
                                    val isCurrent = team == currentTeamId
                                    Text(
                                        team,
                                        fontSize = 11.sp,
                                        color = if (isCurrent) AresRed else AresTextSecondary,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                            Text("vs", fontSize = 11.sp, color = AresTextTertiary)
                            // Blue Alliance teams
                            Column(
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                match.blueAlliance.forEach { team ->
                                    val isCurrent = team == currentTeamId
                                    Text(
                                        team,
                                        fontSize = 11.sp,
                                        color = if (isCurrent) AresCyan else AresTextSecondary,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
