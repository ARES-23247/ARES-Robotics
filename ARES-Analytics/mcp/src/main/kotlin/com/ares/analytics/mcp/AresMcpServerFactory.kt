package com.ares.analytics.mcp

import com.ares.analytics.mcp.bridge.StudioBridgeClient
import com.ares.analytics.mcp.engine.ConfirmationTokenManager
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.ares.analytics.mcp.prompts.AresMcpPrompts
import com.ares.analytics.mcp.resources.AresMcpResources
import com.ares.analytics.mcp.tools.AuditTools
import com.ares.analytics.mcp.tools.CodegenAndScaffoldTools
import com.ares.analytics.mcp.tools.DescriptorMutationTools
import com.ares.analytics.mcp.tools.ProjectLifecycleTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.io.File

/** Builds the configured `io.modelcontextprotocol` [Server] with tools, resources, and prompts. */
object AresMcpServerFactory {
    fun create(
        toolset: McpToolsetMode = McpToolsetMode.FULL,
        bridgeDir: File = StudioBridgeClient.resolveBridgeDir(),
        templateDir: File? = null,
    ): Server {
        val bridgeClient = StudioBridgeClient(bridgeDir = bridgeDir)
        val tokens = ConfirmationTokenManager()
        val engine = HeadlessProjectEngine(
            bridgeClient = bridgeClient,
            tokens = tokens,
            templateDir = templateDir,
        )
        val mutators = HeadlessProjectMutators(engine)

        val server = Server(
            serverInfo = Implementation(
                name = "ares-project-mcp",
                version = McpBuildConfig.STUDIO_VERSION,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                ),
            ),
        )

        ProjectLifecycleTools.register(server, engine)
        when (toolset) {
            McpToolsetMode.FULL -> DescriptorMutationTools.registerFull(server, engine, mutators)
            McpToolsetMode.COMPACT -> DescriptorMutationTools.registerCompact(server, engine, mutators)
        }
        CodegenAndScaffoldTools.register(server, engine, mutators)
        AuditTools.register(server, engine)

        AresMcpResources.register(server, engine)
        AresMcpPrompts.register(server)
        return server
    }
}
