package com.ares.analytics.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import com.ares.analytics.di.ServiceRegistry
import com.ares.analytics.service.XrpLinkService
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.core.TargetSelection
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.requireXrpRuntimeOptions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns platform-specific discovery and live-link lifecycle outside the root navigation shell. */
@Composable
internal fun RobotConnectionCoordinator(
    services: ServiceRegistry,
    config: WorkspaceConfig,
    targetSelection: TargetSelection,
    liveRobotIp: String,
    simulatorRunning: Boolean,
    canonicalContentHash: String?,
    focusRequester: FocusRequester,
) {
    LaunchedEffect(liveRobotIp, config.league, config.projectPath, canonicalContentHash) {
        services.targetScannerService.startScanning(
            liveRobotHost = liveRobotIp,
            port = loadXrpMetadata(config)?.requireXrpRuntimeOptions()?.port ?: defaultPort(config.league),
        )
    }

    LaunchedEffect(config, targetSelection, liveRobotIp, simulatorRunning, canonicalContentHash) {
        focusRequester.requestFocus()
        val host = if (targetSelection == TargetSelection.LOCAL_SIM) "127.0.0.1" else liveRobotIp
        if (config.league == League.XRP) {
            services.nt4ClientService.stop()
            val metadata = loadXrpMetadata(config)
            if (metadata == null || canonicalContentHash == null) {
                services.xrpLinkService.stop()
                return@LaunchedEffect
            }
            val options = metadata.requireXrpRuntimeOptions()
            services.alertEngineService.configureRobotContext(League.XRP, options.brownoutThresholdVolts)
            services.xrpLinkService.start(host, options.port, metadata.projectId, canonicalContentHash)
        } else {
            services.alertEngineService.configureRobotContext(config.league)
            services.xrpLinkService.stop()
            services.nt4ClientService.start(host, config.teamId, config.seasonId, config.robotId)
            services.phoenixDiagnosticsService.start(host)
        }
    }
}

private suspend fun loadXrpMetadata(config: WorkspaceConfig) = if (config.league != League.XRP) null else {
    withContext(Dispatchers.IO) {
        runCatching {
            AresProjectMetadataCodec.decode(File(config.projectPath, ".ares/project.json").readText())
        }.getOrNull()
    }
}

private fun defaultPort(league: League): Int = if (league == League.XRP) XrpLinkService.DEFAULT_PORT else 5810
