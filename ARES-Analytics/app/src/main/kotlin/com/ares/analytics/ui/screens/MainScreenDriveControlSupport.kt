package com.ares.analytics.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ares.analytics.di.ServiceRegistry
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.service.isLoopbackDriveControlHost
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.core.TargetSelection
import com.ares.analytics.ui.input.DesktopDriveInputPublisher
import com.ares.analytics.ui.input.DesktopDriveKeyDispatcher
import com.ares.analytics.ui.input.XrpDriveInputPublisher

internal data class DesktopDriveAuthorizationState(
    val nt4PublisherConnected: Boolean,
    val nt4ControlSurfaceActive: Boolean,
    val xrpPublisherConnected: Boolean,
    val xrpControlSurfaceActive: Boolean,
    val keyDispatcherActive: Boolean,
)

/** Returns the default live-robot host when workspace settings do not specify an override. */
internal fun defaultLiveRobotHost(league: League, configuredHost: String?): String =
    configuredHost?.trim()?.takeIf(String::isNotEmpty)
        ?: if (league == League.XRP) "192.168.4.1" else "192.168.43.1"

/**
 * Computes fail-closed drive and keyboard-dispatch permissions for the active workspace shell.
 *
 * FTC and FRC remain strictly loopback-only (`LOCAL_SIM` on `127.0.0.1` / `localhost` / `::1`).
 * XRP allows live `LIVE_ROBOT` control only when `XrpLinkService` reports an operator-authorized,
 * handshake-verified peer.
 */
internal fun computeDesktopDriveAuthorization(
    league: League,
    targetSelection: TargetSelection,
    activeNav: NavigationTarget,
    dashboardWindowFocused: Boolean,
    isAnyOverlayActive: Boolean,
    isRobotLinkConnected: Boolean,
    nt4ServerHost: String,
    xrpServerHost: String,
    xrpLiveControlAuthorized: Boolean,
): DesktopDriveAuthorizationState {
    val surfaceEligible = !isAnyOverlayActive && dashboardWindowFocused && activeNav == NavigationTarget.DASHBOARD
    val nt4PublisherConnected = league != League.XRP &&
        targetSelection == TargetSelection.LOCAL_SIM &&
        isRobotLinkConnected &&
        isLoopbackDriveControlHost(nt4ServerHost)
    val nt4ControlSurfaceActive = surfaceEligible && nt4PublisherConnected
    val xrpSimConnected = league == League.XRP &&
        targetSelection == TargetSelection.LOCAL_SIM &&
        isRobotLinkConnected &&
        isLoopbackDriveControlHost(xrpServerHost)
    val xrpLiveConnected = league == League.XRP &&
        targetSelection == TargetSelection.LIVE_ROBOT &&
        isRobotLinkConnected &&
        xrpLiveControlAuthorized
    val xrpPublisherConnected = xrpSimConnected || xrpLiveConnected
    val xrpControlSurfaceActive = surfaceEligible && xrpPublisherConnected
    return DesktopDriveAuthorizationState(
        nt4PublisherConnected = nt4PublisherConnected,
        nt4ControlSurfaceActive = nt4ControlSurfaceActive,
        xrpPublisherConnected = xrpPublisherConnected,
        xrpControlSurfaceActive = xrpControlSurfaceActive,
        keyDispatcherActive = nt4ControlSurfaceActive || xrpControlSurfaceActive,
    )
}

/** Binds fail-closed drive publishers, focus disarming, and native key capture for the active workspace. */
@Composable
internal fun BindDesktopDriveControl(
    services: ServiceRegistry,
    league: League,
    targetSelection: TargetSelection,
    activeNav: NavigationTarget,
    dashboardWindowFocused: Boolean,
    isAnyOverlayActive: Boolean,
    isRobotLinkConnected: Boolean,
) {
    val xrpLiveControlAuthorized by services.xrpLinkService.liveControlAuthorized.collectAsState()

    LaunchedEffect(activeNav, targetSelection, isRobotLinkConnected) {
        if (activeNav != NavigationTarget.DASHBOARD || !isRobotLinkConnected) {
            services.keyboardDriveState.disarm(preserveInputSource = true)
        } else {
            services.keyboardDriveState.releaseAll()
        }
    }
    LaunchedEffect(dashboardWindowFocused) {
        if (!dashboardWindowFocused) services.keyboardDriveState.disarm(preserveInputSource = true)
    }
    LaunchedEffect(isAnyOverlayActive) {
        if (isAnyOverlayActive) services.keyboardDriveState.disarm(preserveInputSource = true)
    }
    LaunchedEffect(league, targetSelection, xrpLiveControlAuthorized) {
        if ((league != League.XRP || targetSelection != TargetSelection.LIVE_ROBOT) && xrpLiveControlAuthorized) {
            services.xrpLinkService.disarmLiveControl()
        }
    }
    LaunchedEffect(league, targetSelection, xrpLiveControlAuthorized, dashboardWindowFocused, isAnyOverlayActive, activeNav) {
        if (
            league == League.XRP &&
            targetSelection == TargetSelection.LIVE_ROBOT &&
            xrpLiveControlAuthorized &&
            (!dashboardWindowFocused || isAnyOverlayActive || activeNav != NavigationTarget.DASHBOARD)
        ) {
            services.xrpLinkService.requestStop()
        }
    }

    val auth = computeDesktopDriveAuthorization(
        league = league,
        targetSelection = targetSelection,
        activeNav = activeNav,
        dashboardWindowFocused = dashboardWindowFocused,
        isAnyOverlayActive = isAnyOverlayActive,
        isRobotLinkConnected = isRobotLinkConnected,
        nt4ServerHost = services.nt4ClientService.serverIp,
        xrpServerHost = services.xrpLinkService.serverHost,
        xrpLiveControlAuthorized = xrpLiveControlAuthorized,
    )

    DesktopDriveInputPublisher(
        nt4ClientService = services.nt4ClientService,
        keyboardState = services.keyboardDriveState,
        gamepadState = services.gamepadService.gamepad1State,
        connected = auth.nt4PublisherConnected,
        controlSurfaceActive = auth.nt4ControlSurfaceActive,
        league = league,
    )
    XrpDriveInputPublisher(
        xrpLinkService = services.xrpLinkService,
        keyboardState = services.keyboardDriveState,
        gamepadState = services.gamepadService.gamepad1State,
        connected = auth.xrpPublisherConnected,
        controlSurfaceActive = auth.xrpControlSurfaceActive,
        requireActiveSurfaceForAutonomous = targetSelection == TargetSelection.LIVE_ROBOT,
    )
    DesktopDriveKeyDispatcher(
        state = services.keyboardDriveState,
        controlSurfaceActive = auth.keyDispatcherActive,
    )
}
