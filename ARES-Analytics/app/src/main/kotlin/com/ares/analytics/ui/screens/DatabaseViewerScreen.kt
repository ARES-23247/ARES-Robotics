
package com.ares.analytics.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.QueryResult
import com.ares.analytics.ui.components.core.AresEmptyState
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.SQLException

/**
 * Interactive SQL query viewer and table inspector screen for DuckDB match telemetry databases.
 *
 * Executes read-only SQL queries, displays query timing in milliseconds ($ms$), and renders bounded
 * result tables lazily. The repository, rather than the UI, enforces the result safety limits.
 *
 * @param databaseService Primary [DatabaseService] backing DuckDB query execution.
 *
 * @see DatabaseService
 * @see QueryResult
 */
@Composable
fun DatabaseViewerScreen(databaseService: DatabaseService) {
    val scope = rememberCoroutineScope()
    var queryText by remember { mutableStateOf("SELECT * FROM sessions LIMIT 10;") }
    var queryResult by remember { mutableStateOf<QueryResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var executionTimeMs by remember { mutableStateOf(0L) }
    var tablesList by remember { mutableStateOf<List<String>>(emptyList()) }
    var dbSizeMB by remember { mutableStateOf(0.0) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // Fetch dynamic tables list and DB size on startup and after runs
    LaunchedEffect(refreshTrigger) {
        withContext(Dispatchers.IO) {
            try {
                // Get tables using SHOW TABLES
                val tablesRes = databaseService.executeQueryRaw("SHOW TABLES;")
                tablesList = tablesRes.rows.flatten()

                // Get file size
                val file = File(databaseService.dbPath)
                if (file.exists()) {
                    dbSizeMB = file.length().toDouble() / (1024.0 * 1024.0)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    val runQuery = {
        scope.launch {
            if (queryText.trim().isEmpty()) return@launch
            isLoading = true
            errorMessage = null
            queryResult = null
            val startTime = System.currentTimeMillis()
            try {
                val result = withContext(Dispatchers.IO) {
                    databaseService.executeQueryRaw(queryText.trim())
                }
                executionTimeMs = System.currentTimeMillis() - startTime
                queryResult = result
                // Trigger statistics refresh if DDL/DML was run
                val lower = queryText.trim().lowercase()
                if (lower.contains("create") || lower.contains("drop") || lower.contains("insert") || lower.contains("delete") || lower.contains("update")) {
                    refreshTrigger++
                }
            } catch (e: SQLException) {
                errorMessage = e.message ?: "SQL Exception occurred"
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unknown Exception occurred"
            } finally {
                isLoading = false
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AresBackground),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── LEFT PANEL: Metadata & Presets ────────────────────────────────────
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(AresSurface, RoundedCornerShape(12.dp))
                .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = AresCyan,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "DuckDB Diagnostics",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            HorizontalDivider(color = AresBorder)

            // DB File Info
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DATABASE FILE", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(AresBackground)
                        .clickable {
                            try {
                                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                val selection = java.awt.datatransfer.StringSelection(databaseService.dbPath)
                                clipboard.setContents(selection, selection)
                            } catch (_: Exception) {
                                // Clipboard integration is optional in headless or restricted desktops.
                            }
                        }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dbName = File(databaseService.dbPath).name
                    Text(
                        text = dbName,
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy Path",
                        tint = AresTextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Size on Disk:", color = AresTextTertiary, fontSize = 11.sp)
                    Text("${"%.2f".format(dbSizeMB)} MB", color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = AresBorder)

            // Tables List
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("TABLES", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                if (tablesList.isEmpty()) {
                    Text("No tables found.", color = AresTextTertiary, fontSize = 11.sp)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        tablesList.forEach { table ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AresSurfaceElevated)
                                    .clickable {
                                        queryText = "SELECT * FROM $table LIMIT 50;"
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = table,
                                    color = AresTextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = AresTextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = AresBorder)

            // Preset Quick Queries
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PRESET QUERIES", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                val presets = listOf(
                    "Show Tables" to "SHOW TABLES;",
                    "Latest 10 Sessions" to "SELECT * FROM sessions ORDER BY created_at DESC LIMIT 10;",
                    "Match Summary" to "SELECT match_number, alliance_color, duration_ms FROM sessions WHERE match_number IS NOT NULL LIMIT 20;",
                    "Telemetry Frames Count" to "SELECT COUNT(*), session_id FROM telemetry_frames GROUP BY session_id LIMIT 10;"
                )

                presets.forEach { (label, sql) ->
                    OutlinedButton(
                        onClick = {
                            queryText = sql
                            runQuery()
                        },
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        border = BorderStroke(1.dp, AresBorder),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextSecondary)
                    ) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }
        }

        // ── RIGHT PANEL: Query Runner Console ─────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Query Box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AresSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SQL Query Console",
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    IconButton(
                        onClick = { queryText = "" },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Editor",
                            tint = AresTextTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = AresTextPrimary
                    ),
                    placeholder = {
                        Text(
                            "Enter SQL query here (e.g. SELECT * FROM sessions;)",
                            color = AresTextTertiary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AresCyan,
                        unfocusedBorderColor = AresBorder,
                        focusedContainerColor = AresBackground,
                        unfocusedContainerColor = AresBackground
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Read only • first ${QueryResult.DEFAULT_RAW_QUERY_ROW_LIMIT} rows maximum",
                        color = AresTextTertiary,
                        fontSize = 11.sp
                    )
                    Button(
                        onClick = { runQuery() },
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = AresBackground,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Execute Query", color = AresBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            // Output Results Grid
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(AresSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                val currentError = errorMessage
                val currentResult = queryResult
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            color = AresCyan,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    currentError != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, AresError, RoundedCornerShape(8.dp))
                                .background(AresError.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .padding(16.dp)
                                .align(Alignment.TopStart),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = AresError)
                                Text("SQL Query Failed", color = AresError, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Text(
                                text = currentError,
                                color = AresTextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                    currentResult != null -> {
                        val result = currentResult
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "QUERY RESULTS",
                                    color = AresTextTertiary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = if (result.isTruncated) {
                                            "Showing first ${result.rows.size} rows (safety limit) • ${executionTimeMs}ms"
                                        } else {
                                            "Returned ${result.rows.size} rows in ${executionTimeMs}ms"
                                        },
                                        color = if (result.isTruncated) AresAmber else AresCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (result.truncatedCellCount > 0) {
                                        Text(
                                            text = "${result.truncatedCellCount} oversized cell value(s) shortened",
                                            color = AresAmber,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }

                            if (result.rows.isEmpty()) {
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Query executed successfully, but returned no rows.", color = AresTextSecondary, fontSize = 13.sp)
                                }
                            } else {
                                // Horizontally scrollable, vertically lazy grid. The repository also caps
                                // row count, so neither JDBC collection nor Compose composition is unbounded.
                                val scrollStateHorizontal = rememberScrollState()
                                val tableWidth = (result.columns.size.coerceAtLeast(1) * 180).dp

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                                        .horizontalScroll(scrollStateHorizontal)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .width(tableWidth)
                                            .fillMaxHeight()
                                    ) {
                                        // Headers
                                        Row(
                                            modifier = Modifier
                                                .background(AresSurfaceElevated)
                                                .drawBehind {
                                                    drawLine(
                                                        color = AresBorder,
                                                        start = Offset(0f, size.height),
                                                        end = Offset(size.width, size.height),
                                                        strokeWidth = 1.dp.toPx()
                                                    )
                                                }
                                        ) {
                                            result.columns.forEach { colName ->
                                                Box(
                                                    modifier = Modifier
                                                        .width(180.dp)
                                                        .drawBehind {
                                                            drawLine(
                                                                color = AresBorder,
                                                                start = Offset(size.width, 0f),
                                                                end = Offset(size.width, size.height),
                                                                strokeWidth = 1.dp.toPx()
                                                            )
                                                        }
                                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                                    contentAlignment = Alignment.CenterStart
                                                ) {
                                                    Text(
                                                        text = colName,
                                                        color = AresTextPrimary,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }

                                        LazyColumn(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                        ) {
                                            itemsIndexed(
                                                items = result.rows,
                                                key = { rowIndex, _ -> rowIndex }
                                            ) { rowIndex, row ->
                                                val rowBg = if (rowIndex % 2 == 0) AresBackground else AresSurface
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(rowBg)
                                                        .drawBehind {
                                                            drawLine(
                                                                color = AresBorder,
                                                                start = Offset(0f, size.height),
                                                                end = Offset(size.width, size.height),
                                                                strokeWidth = 1.dp.toPx()
                                                            )
                                                        }
                                                ) {
                                                    row.forEach { cellValue ->
                                                        Box(
                                                            modifier = Modifier
                                                                .width(180.dp)
                                                                .drawBehind {
                                                                    drawLine(
                                                                        color = AresBorder.copy(alpha = 0.5f),
                                                                        start = Offset(size.width, 0f),
                                                                        end = Offset(size.width, size.height),
                                                                        strokeWidth = 1.dp.toPx()
                                                                    )
                                                                }
                                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                                            contentAlignment = Alignment.CenterStart
                                                        ) {
                                                            Text(
                                                                text = cellValue,
                                                                color = AresTextSecondary,
                                                                fontSize = 11.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                maxLines = 2,
                                                                overflow = TextOverflow.Ellipsis
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
                    }
                    else -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            AresEmptyState(
                                title = "Ready to receive SQL commands",
                                icon = Icons.Default.Terminal,
                                iconSize = 36.dp,
                                titleColor = AresTextTertiary,
                                titleFontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
