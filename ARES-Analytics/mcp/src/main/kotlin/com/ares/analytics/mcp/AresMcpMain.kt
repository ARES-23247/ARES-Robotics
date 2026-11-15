package com.ares.analytics.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.FileAppender
import com.ares.analytics.mcp.bridge.StudioBridgeClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/** Toolset exposure mode for the MCP server. */
enum class McpToolsetMode(val cliValue: String) {
    FULL("full"),
    COMPACT("compact"),
    ;

    companion object {
        fun parse(raw: String?): McpToolsetMode = when (raw?.trim()?.lowercase()) {
            null, "", "full" -> FULL
            "compact" -> COMPACT
            else -> throw IllegalArgumentException("Unsupported --toolset value '$raw'; expected 'full' or 'compact'")
        }
    }
}

/** Parsed CLI configuration for `--mcp-stdio` and `--verify-mcp-smoke`. */
data class McpCliOptions(
    val mcpStdio: Boolean = false,
    val verifySmoke: Boolean = false,
    val toolset: McpToolsetMode = McpToolsetMode.FULL,
    val bridgeDir: File? = null,
    val templateDir: File? = null,
) {
    companion object {
        fun parse(args: Array<String>): McpCliOptions {
            var mcpStdio = false
            var verifySmoke = false
            var toolset = McpToolsetMode.FULL
            var bridgeDir: File? = null
            var templateDir: File? = null
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                when {
                    arg == "--mcp-stdio" -> mcpStdio = true
                    arg == "--verify-mcp-smoke" -> verifySmoke = true
                    arg == "--toolset" -> {
                        val next = args.getOrNull(++index)
                            ?: throw IllegalArgumentException("--toolset requires a value")
                        toolset = McpToolsetMode.parse(next)
                    }
                    arg.startsWith("--toolset=") -> {
                        toolset = McpToolsetMode.parse(arg.substringAfter('='))
                    }
                    arg == "--bridge-dir" || arg == "--mcp-bridge-dir" -> {
                        val next = args.getOrNull(++index)
                            ?: throw IllegalArgumentException("$arg requires a directory path")
                        bridgeDir = File(next)
                    }
                    arg.startsWith("--bridge-dir=") || arg.startsWith("--mcp-bridge-dir=") -> {
                        bridgeDir = File(arg.substringAfter('='))
                    }
                    arg == "--template-dir" -> {
                        val next = args.getOrNull(++index)
                            ?: throw IllegalArgumentException("--template-dir requires a directory path")
                        templateDir = File(next)
                    }
                    arg.startsWith("--template-dir=") -> {
                        templateDir = File(arg.substringAfter('='))
                    }
                }
                index++
            }
            return McpCliOptions(
                mcpStdio = mcpStdio,
                verifySmoke = verifySmoke,
                toolset = toolset,
                bridgeDir = bridgeDir,
                templateDir = templateDir,
            )
        }
    }
}

/**
 * Process entrypoint and packaged pre-lock CLI dispatcher for ARES Project MCP.
 *
 * Captures original [System.out] strictly for JSON-RPC framing and redirects [System.out] to
 * [System.err] before any logging or library initialization so stdout stays protocol-clean.
 */
object AresMcpMain {
    /** Dispatches `--verify-mcp-smoke` or `--mcp-stdio`, returning `null` for GUI launches. */
    @JvmStatic
    fun runIfMcpCommand(args: Array<String>): Int? {
        val options = try {
            McpCliOptions.parse(args)
        } catch (error: IllegalArgumentException) {
            System.err.println("[ares-mcp] ${error.message}")
            return 2
        }
        if (!options.mcpStdio && !options.verifySmoke) return null

        val rpcIn: InputStream = System.`in`
        val rpcOut: PrintStream = System.out
        System.setOut(System.err)

        val resolvedBridgeDir = StudioBridgeClient.resolveBridgeDir(options.bridgeDir)
        configureStderrLogging(resolvedBridgeDir)

        return if (options.verifySmoke) {
            runSmokeVerification(options, resolvedBridgeDir)
        } else {
            runStdioServer(options, resolvedBridgeDir, rpcIn, rpcOut)
        }
    }

    internal fun configureStderrLogging(bridgeDir: File) {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        context.reset()
        val patternText = "%d{ISO8601} %-5level [%thread] %logger{36} - %msg%n"
        val stderrAppender = ConsoleAppender<ILoggingEvent>().apply {
            this.context = context
            name = "ARES_MCP_STDERR"
            target = "System.err"
            encoder = PatternLayoutEncoder().apply {
                this.context = context
                pattern = patternText
                start()
            }
            start()
        }
        val rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME)
        rootLogger.level = Level.WARN
        rootLogger.addAppender(stderrAppender)

        runCatching {
            val logDir = File(bridgeDir, "logs").apply { mkdirs() }
            val pid = ProcessHandle.current().pid()
            val fileAppender = FileAppender<ILoggingEvent>().apply {
                this.context = context
                name = "ARES_MCP_FILE"
                file = File(logDir, "mcp-$pid.log").absolutePath
                isAppend = true
                encoder = PatternLayoutEncoder().apply {
                    this.context = context
                    pattern = patternText
                    start()
                }
                start()
            }
            rootLogger.addAppender(fileAppender)
        }
    }

    private fun runStdioServer(
        options: McpCliOptions,
        resolvedBridgeDir: File,
        rpcIn: InputStream,
        rpcOut: PrintStream,
    ): Int {
        val server = AresMcpServerFactory.create(
            toolset = options.toolset,
            bridgeDir = resolvedBridgeDir,
            templateDir = options.templateDir,
        )
        return try {
            runBlocking {
                val closed = CompletableDeferred<Unit>()
                val transport = StdioServerTransport(
                    input = rpcIn.asSource().buffered(),
                    output = rpcOut.asSink().buffered(),
                )
                val session = server.createSession(transport)
                session.onClose {
                    closed.complete(Unit)
                }
                transport.onClose {
                    closed.complete(Unit)
                }
                closed.await()
            }
            0
        } catch (error: Throwable) {
            System.err.println("[ares-mcp] Fatal stdio server error: ${error.message}")
            error.printStackTrace(System.err)
            1
        }
    }

    internal fun runSmokeVerification(
        options: McpCliOptions,
        resolvedBridgeDir: File,
    ): Int = try {
        runBlocking {
            withTimeout(20_000L) {
                verifyRoundTrip(McpToolsetMode.FULL, resolvedBridgeDir, options.templateDir, expectedTools = 12)
                verifyRoundTrip(McpToolsetMode.COMPACT, resolvedBridgeDir, options.templateDir, expectedTools = 7)
            }
        }
        System.err.println(
            "[ares-mcp] MCP smoke verification passed " +
                "(studioVersion=${McpBuildConfig.STUDIO_VERSION}, fullTools=12, compactTools=7)",
        )
        0
    } catch (error: Throwable) {
        System.err.println("[ares-mcp] MCP smoke verification failed: ${error.message}")
        error.printStackTrace(System.err)
        1
    }

    private suspend fun verifyRoundTrip(
        toolset: McpToolsetMode,
        bridgeDir: File,
        templateDir: File?,
        expectedTools: Int,
    ) {
        val clientToServerIn = PipedInputStream(1024 * 1024)
        val clientToServerOut = PipedOutputStream(clientToServerIn)
        val serverToClientIn = PipedInputStream(1024 * 1024)
        val serverToClientOut = PipedOutputStream(serverToClientIn)

        val server = AresMcpServerFactory.create(
            toolset = toolset,
            bridgeDir = bridgeDir,
            templateDir = templateDir,
        )
        val serverTransport = StdioServerTransport(
            input = clientToServerIn.asSource().buffered(),
            output = serverToClientOut.asSink().buffered(),
        )
        val session = server.createSession(serverTransport)

        val client = Client(
            clientInfo = Implementation(name = "ares-mcp-smoke-client", version = McpBuildConfig.STUDIO_VERSION),
            options = ClientOptions(),
        )
        val clientTransport = StdioClientTransport(
            input = serverToClientIn.asSource().buffered(),
            output = clientToServerOut.asSink().buffered(),
        )
        client.connect(clientTransport)
        try {
            val tools = client.listTools().tools
            require(tools.size == expectedTools) {
                "Expected $expectedTools tools in $toolset mode but listed ${tools.size}: ${tools.map { it.name }}"
            }
            val resources = client.listResources().resources
            require(resources.isNotEmpty()) { "Expected static MCP resources in $toolset mode" }
            val templates = client.listResourceTemplates(ListResourceTemplatesRequest()).resourceTemplates
            require(templates.isNotEmpty()) { "Expected MCP resource templates in $toolset mode" }
            val prompts = client.listPrompts().prompts
            require(prompts.size >= 4) { "Expected at least 4 MCP prompts, found ${prompts.size}" }
            val safetyDoc = client.readResource(
                ReadResourceRequest(ReadResourceRequestParams(uri = "ares://conventions/coordinates-and-safety")),
            )
            val safetyText = safetyDoc.contents.filterIsInstance<TextResourceContents>().joinToString("\n") { it.text }
            require(safetyText.contains("RobotClock") && safetyText.contains("CCW")) {
                "Expected conventions resource to document RobotClock and CCW coordinates"
            }
        } finally {
            runCatching { client.close() }
            runCatching { session.close() }
            runCatching { clientToServerOut.close() }
            runCatching { serverToClientOut.close() }
            runCatching { clientToServerIn.close() }
            runCatching { serverToClientIn.close() }
        }
    }
}

fun main(args: Array<String>) {
    val effectiveArgs = if (args.any { it == "--mcp-stdio" || it == "--verify-mcp-smoke" }) {
        args
    } else {
        arrayOf("--mcp-stdio") + args
    }
    val exitCode = AresMcpMain.runIfMcpCommand(effectiveArgs) ?: 0
    if (exitCode != 0) exitProcess(exitCode)
}
