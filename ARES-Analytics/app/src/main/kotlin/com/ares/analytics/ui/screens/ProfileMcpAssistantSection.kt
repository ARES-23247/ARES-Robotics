package com.ares.analytics.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.mcp.McpToolsetMode
import com.ares.analytics.shared.mcp.McpBridgeDescriptor
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.util.copyTextToSystemClipboard
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import java.io.File

internal enum class McpAssistantClient(val slug: String, val label: String) {
    ANTIGRAVITY("antigravity", "Antigravity"),
    CODEX_CLI("codex", "Codex CLI"),
    CLAUDE_CODE("claude", "Claude Code"),
    GEMINI_CLI("gemini", "Gemini CLI"),
    VSCODE_CURSOR("vscode", "VS Code / Cursor"),
}

private val prettyMcpJson = Json { prettyPrint = true }

/**
 * Displays live `ares-project-mcp` bridge status, Full/Compact toolset selection,
 * and ready-to-copy client configuration snippets for AI coding tools.
 */
@Composable
fun ProfileMcpAssistantSection(
    bridgeDescriptor: McpBridgeDescriptor?,
    activeProjectPath: String? = null,
    detectedLauncherCommand: Pair<String, List<String>> = remember { resolveDefaultMcpLauncherCommand() },
) {
    var selectedToolset by remember { mutableStateOf(McpToolsetMode.FULL) }
    var selectedClient by remember { mutableStateOf(McpAssistantClient.ANTIGRAVITY) }
    var copiedNotice by remember { mutableStateOf<String?>(null) }

    val snippet = remember(selectedClient, selectedToolset, detectedLauncherCommand, activeProjectPath) {
        buildMcpClientSnippet(
            client = selectedClient,
            toolset = selectedToolset,
            launcherCommand = detectedLauncherCommand,
            activeProjectPath = activeProjectPath,
        )
    }

    AresCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                Text("AI Coding Assistant (MCP)", fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 15.sp)
                Spacer(modifier = Modifier.weight(1f))
                val isBridgeLive = bridgeDescriptor != null
                Box(
                    modifier = Modifier
                        .testTag("mcp-bridge-status")
                        .clip(RoundedCornerShape(999.dp))
                        .background((if (isBridgeLive) AresGreen else AresCyan).copy(alpha = 0.14f))
                        .border(1.dp, (if (isBridgeLive) AresGreen else AresCyan).copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (bridgeDescriptor != null) {
                            "Bridge Active · 127.0.0.1:${bridgeDescriptor.port}"
                        } else {
                            "Headless Ready (Offline)"
                        },
                        color = if (isBridgeLive) AresGreen else AresCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text(
                "Connect external coding agents to validate, mutate, and audit ARES robot projects safely over local stdio JSON-RPC.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                McpSelectChip(
                    label = "FULL (12 tools)",
                    selected = selectedToolset == McpToolsetMode.FULL,
                    modifier = Modifier.testTag("mcp-toolset-full"),
                    onClick = {
                        selectedToolset = McpToolsetMode.FULL
                        copiedNotice = null
                    },
                )
                McpSelectChip(
                    label = "COMPACT (7 tools)",
                    selected = selectedToolset == McpToolsetMode.COMPACT,
                    modifier = Modifier.testTag("mcp-toolset-compact"),
                    onClick = {
                        selectedToolset = McpToolsetMode.COMPACT
                        copiedNotice = null
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                McpAssistantClient.entries.forEach { client ->
                    McpSelectChip(
                        label = client.label,
                        selected = selectedClient == client,
                        modifier = Modifier.testTag("mcp-client-${client.slug}"),
                        onClick = {
                            selectedClient = client
                            copiedNotice = null
                        },
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
                color = AresSurface,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = snippet,
                    modifier = Modifier
                        .testTag("mcp-config-snippet")
                        .fillMaxWidth()
                        .padding(12.dp),
                    color = AresTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    modifier = Modifier.testTag("mcp-copy-config"),
                    onClick = {
                        val ok = copyTextToSystemClipboard(snippet)
                        copiedNotice = if (ok) "Copied ${selectedClient.label} configuration to clipboard." else "Clipboard unavailable."
                    },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Copy configuration", fontSize = 12.sp)
                }
                copiedNotice?.let { notice ->
                    Text(notice, color = AresGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun McpSelectChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AresCyan.copy(alpha = 0.16f) else AresSurfaceElevated)
            .border(1.dp, if (selected) AresCyan else AresBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) AresCyan else AresTextPrimary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

internal fun buildMcpClientSnippet(
    client: McpAssistantClient,
    toolset: McpToolsetMode,
    launcherCommand: Pair<String, List<String>>,
    activeProjectPath: String? = null,
): String {
    val (command, baseArgs) = launcherCommand
    val fullArgs = baseArgs + listOf("--toolset", toolset.cliValue)
    val projectNote = activeProjectPath?.takeIf { it.isNotBlank() }?.let { " (active workspace: $it)" }.orEmpty()

    return when (client) {
        McpAssistantClient.ANTIGRAVITY -> renderJsonMcpServers(
            topKey = "mcpServers",
            command = command,
            args = fullArgs,
        )
        McpAssistantClient.GEMINI_CLI -> buildString {
            appendLine("# Add to ~/.gemini/settings.json$projectNote")
            append(renderJsonMcpServers(topKey = "mcpServers", command = command, args = fullArgs))
        }
        McpAssistantClient.VSCODE_CURSOR -> buildString {
            appendLine("# .vscode/mcp.json uses \"servers\"; .cursor/mcp.json uses \"mcpServers\"")
            append(renderJsonMcpServers(topKey = "servers", command = command, args = fullArgs))
        }
        McpAssistantClient.CODEX_CLI -> buildString {
            appendLine("# ~/.codex/config.toml$projectNote")
            appendLine("[mcp_servers.ares_project]")
            appendLine("command = ${tomlQuote(command)}")
            appendLine("args = [${fullArgs.joinToString(", ") { tomlQuote(it) }}]")
        }.trimEnd()
        McpAssistantClient.CLAUDE_CODE -> buildString {
            appendLine("# CLI setup$projectNote:")
            appendLine("claude mcp add ares-project -- ${shellQuote(command)} ${fullArgs.joinToString(" ") { shellQuote(it) }}")
            appendLine()
            append(renderJsonMcpServers(topKey = "mcpServers", command = command, args = fullArgs))
        }.trimEnd()
    }
}

private fun renderJsonMcpServers(
    topKey: String,
    command: String,
    args: List<String>,
): String {
    val obj = buildJsonObject {
        putJsonObject(topKey) {
            putJsonObject("ares-project") {
                put("command", JsonPrimitive(command))
                put(
                    "args",
                    buildJsonArray {
                        args.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        }
    }
    return prettyMcpJson.encodeToString(obj)
}

private fun tomlQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun shellQuote(value: String): String =
    if (value.any { it.isWhitespace() || it == '"' || it == '\\' }) {
        "\"" + value.replace("\"", "\\\"") + "\""
    } else {
        value
    }

internal fun resolveDefaultMcpLauncherCommand(): Pair<String, List<String>> {
    val currentExe = ProcessHandle.current().info().command().orElse(null)?.let(::File)
    if (currentExe != null && currentExe.isFile && currentExe.name.contains("ARES Robotics Studio", ignoreCase = true)) {
        return currentExe.absolutePath to listOf("--mcp-stdio")
    }
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val relScript = if (isWindows) "scripts/ares-mcp.ps1" else "scripts/ares-mcp.sh"
    var cursor: File? = File(".").canonicalFile
    repeat(4) {
        val candidate = cursor?.let { File(it, relScript) }
        if (candidate != null && candidate.isFile) {
            return if (isWindows) {
                "powershell.exe" to listOf("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", candidate.absolutePath)
            } else {
                "bash" to listOf(candidate.absolutePath)
            }
        }
        cursor = cursor?.parentFile
    }
    return "ares-robotics-studio" to listOf("--mcp-stdio")
}
