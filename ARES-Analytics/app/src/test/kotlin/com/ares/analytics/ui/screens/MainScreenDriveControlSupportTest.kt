package com.ares.analytics.ui.screens

import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.core.TargetSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainScreenDriveControlSupportTest {

    @Test
    fun `defaultLiveRobotHost uses AP gateway for XRP and standard robot IP for FTC and FRC`() {
        assertEquals("192.168.4.1", defaultLiveRobotHost(League.XRP, null))
        assertEquals("192.168.4.1", defaultLiveRobotHost(League.XRP, "   "))
        assertEquals("10.0.0.42", defaultLiveRobotHost(League.XRP, " 10.0.0.42 "))
        assertEquals("192.168.43.1", defaultLiveRobotHost(League.FTC, null))
        assertEquals("192.168.43.1", defaultLiveRobotHost(League.FRC, null))
    }

    @Test
    fun `FTC and FRC never allow drive publishers on LIVE_ROBOT targets`() {
        for (league in listOf(League.FTC, League.FRC)) {
            val liveAuth = computeDesktopDriveAuthorization(
                league = league,
                targetSelection = TargetSelection.LIVE_ROBOT,
                activeNav = NavigationTarget.DASHBOARD,
                dashboardWindowFocused = true,
                isAnyOverlayActive = false,
                isRobotLinkConnected = true,
                nt4ServerHost = "127.0.0.1",
                xrpServerHost = "127.0.0.1",
                xrpLiveControlAuthorized = true,
            )
            assertFalse(liveAuth.nt4PublisherConnected)
            assertFalse(liveAuth.nt4ControlSurfaceActive)
            assertFalse(liveAuth.xrpPublisherConnected)
            assertFalse(liveAuth.keyDispatcherActive)

            val simAuth = computeDesktopDriveAuthorization(
                league = league,
                targetSelection = TargetSelection.LOCAL_SIM,
                activeNav = NavigationTarget.DASHBOARD,
                dashboardWindowFocused = true,
                isAnyOverlayActive = false,
                isRobotLinkConnected = true,
                nt4ServerHost = "127.0.0.1",
                xrpServerHost = "127.0.0.1",
                xrpLiveControlAuthorized = false,
            )
            assertTrue(simAuth.nt4PublisherConnected)
            assertTrue(simAuth.nt4ControlSurfaceActive)
            assertTrue(simAuth.keyDispatcherActive)
        }
    }

    @Test
    fun `XRP allows LOCAL_SIM directly and gates LIVE_ROBOT behind explicit peer authorization and focus`() {
        val simAuth = computeDesktopDriveAuthorization(
            league = League.XRP,
            targetSelection = TargetSelection.LOCAL_SIM,
            activeNav = NavigationTarget.DASHBOARD,
            dashboardWindowFocused = true,
            isAnyOverlayActive = false,
            isRobotLinkConnected = true,
            nt4ServerHost = "127.0.0.1",
            xrpServerHost = "127.0.0.1",
            xrpLiveControlAuthorized = false,
        )
        assertTrue(simAuth.xrpPublisherConnected)
        assertTrue(simAuth.xrpControlSurfaceActive)
        assertFalse(simAuth.nt4PublisherConnected)

        val unauthLive = computeDesktopDriveAuthorization(
            league = League.XRP,
            targetSelection = TargetSelection.LIVE_ROBOT,
            activeNav = NavigationTarget.DASHBOARD,
            dashboardWindowFocused = true,
            isAnyOverlayActive = false,
            isRobotLinkConnected = true,
            nt4ServerHost = "127.0.0.1",
            xrpServerHost = "192.168.4.1",
            xrpLiveControlAuthorized = false,
        )
        assertFalse(unauthLive.xrpPublisherConnected)
        assertFalse(unauthLive.xrpControlSurfaceActive)
        assertFalse(unauthLive.keyDispatcherActive)

        val authLive = computeDesktopDriveAuthorization(
            league = League.XRP,
            targetSelection = TargetSelection.LIVE_ROBOT,
            activeNav = NavigationTarget.DASHBOARD,
            dashboardWindowFocused = true,
            isAnyOverlayActive = false,
            isRobotLinkConnected = true,
            nt4ServerHost = "127.0.0.1",
            xrpServerHost = "192.168.4.1",
            xrpLiveControlAuthorized = true,
        )
        assertTrue(authLive.xrpPublisherConnected)
        assertTrue(authLive.xrpControlSurfaceActive)
        assertTrue(authLive.keyDispatcherActive)

        val blurred = computeDesktopDriveAuthorization(
            league = League.XRP,
            targetSelection = TargetSelection.LIVE_ROBOT,
            activeNav = NavigationTarget.DASHBOARD,
            dashboardWindowFocused = false,
            isAnyOverlayActive = false,
            isRobotLinkConnected = true,
            nt4ServerHost = "127.0.0.1",
            xrpServerHost = "192.168.4.1",
            xrpLiveControlAuthorized = true,
        )
        assertFalse(blurred.xrpControlSurfaceActive)
        assertFalse(blurred.keyDispatcherActive)

        val overlayOpen = computeDesktopDriveAuthorization(
            league = League.XRP,
            targetSelection = TargetSelection.LIVE_ROBOT,
            activeNav = NavigationTarget.DASHBOARD,
            dashboardWindowFocused = true,
            isAnyOverlayActive = true,
            isRobotLinkConnected = true,
            nt4ServerHost = "127.0.0.1",
            xrpServerHost = "192.168.4.1",
            xrpLiveControlAuthorized = true,
        )
        assertFalse(overlayOpen.xrpControlSurfaceActive)
        assertFalse(overlayOpen.keyDispatcherActive)

        val otherScreen = computeDesktopDriveAuthorization(
            league = League.XRP,
            targetSelection = TargetSelection.LIVE_ROBOT,
            activeNav = NavigationTarget.ROBOT_STUDIO,
            dashboardWindowFocused = true,
            isAnyOverlayActive = false,
            isRobotLinkConnected = true,
            nt4ServerHost = "127.0.0.1",
            xrpServerHost = "192.168.4.1",
            xrpLiveControlAuthorized = true,
        )
        assertFalse(otherScreen.xrpControlSurfaceActive)
        assertFalse(otherScreen.keyDispatcherActive)
    }
}
