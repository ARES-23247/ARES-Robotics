package com.ares.analytics.ui.components.robotstudio

import androidx.compose.ui.input.key.Key
import com.ares.analytics.service.DashboardHealthSnapshot
import com.ares.analytics.service.TelemetryStore
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.components.extractPitVitalsSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RobotStudioSplitSupportTest {

    @Test
    fun `clampSplitPaneWidthDp enforces min and max bounds and ignores NaN deltas`() {
        assertEquals(260f, clampSplitPaneWidthDp(220f, 40f, MIN_HIERARCHY_WIDTH_DP, MAX_HIERARCHY_WIDTH_DP))
        assertEquals(MIN_HIERARCHY_WIDTH_DP, clampSplitPaneWidthDp(220f, -90f, MIN_HIERARCHY_WIDTH_DP, MAX_HIERARCHY_WIDTH_DP))
        assertEquals(MAX_INSPECTOR_WIDTH_DP, clampSplitPaneWidthDp(300f, 500f, MIN_INSPECTOR_WIDTH_DP, MAX_INSPECTOR_WIDTH_DP))
        assertEquals(250f, clampSplitPaneWidthDp(250f, Float.NaN, MIN_INSPECTOR_WIDTH_DP, MAX_INSPECTOR_WIDTH_DP))
    }

    @Test
    fun `classifyRobotStudioShortcut maps Ctrl+B Ctrl+I Ctrl+S and rejects Shift+Ctrl+B`() {
        assertEquals(
            RobotStudioShortcutAction.TOGGLE_HIERARCHY,
            classifyRobotStudioShortcut(isCtrlOrMeta = true, isShift = false, isAlt = false, key = Key.B),
        )
        assertEquals(
            RobotStudioShortcutAction.TOGGLE_INSPECTOR,
            classifyRobotStudioShortcut(isCtrlOrMeta = true, isShift = false, isAlt = false, key = Key.I),
        )
        assertEquals(
            RobotStudioShortcutAction.SAVE_ALL,
            classifyRobotStudioShortcut(isCtrlOrMeta = true, isShift = false, isAlt = false, key = Key.S),
        )
        assertNull(classifyRobotStudioShortcut(isCtrlOrMeta = true, isShift = true, isAlt = false, key = Key.B))
        assertNull(classifyRobotStudioShortcut(isCtrlOrMeta = false, isShift = false, isAlt = false, key = Key.S))
    }

    @Test
    fun `collectDirtyRobotStudioStages preserves stage order`() {
        assertEquals(
            emptyList(),
            collectDirtyRobotStudioStages(
                identityDirty = false,
                drivebaseDirty = false,
                subsystemDirty = false,
                superstructureDirty = false,
                controlsDirty = false,
                routineDirty = false,
            ),
        )
        assertEquals(
            listOf("Identity", "Subsystems", "Autonomous"),
            collectDirtyRobotStudioStages(
                identityDirty = true,
                drivebaseDirty = false,
                subsystemDirty = true,
                superstructureDirty = false,
                controlsDirty = false,
                routineDirty = true,
            ),
        )
    }

    @Test
    fun `extractPitVitalsSnapshot reads battery voltage loop time and connection state`() = runTest {
        val store = TelemetryStore()
        val offline = extractPitVitalsSnapshot(
            store = store,
            health = DashboardHealthSnapshot(connected = false, ingestFramesPerSecond = 0.0),
            isRobotLinkConnected = false,
        )
        assertFalse(offline.isConnected)
        assertNull(offline.batteryVolts)
        assertNull(offline.loopTimeMs)

        store.accept(TelemetryFrame(sessionId = "live", key = "Robot/BatteryVoltage", value = 12.64, timestampMs = 1_000L))
        store.accept(TelemetryFrame(sessionId = "live", key = "Robot/LoopTimeMs", value = 4.2, timestampMs = 1_005L))

        val online = extractPitVitalsSnapshot(
            store = store,
            health = DashboardHealthSnapshot(connected = true, ingestFramesPerSecond = 50.0),
            isRobotLinkConnected = true,
        )
        assertTrue(online.isConnected)
        assertEquals(50.0, online.ingestHz, 1e-6)
        assertEquals(12.64, online.batteryVolts!!, 1e-6)
        assertEquals(4.2, online.loopTimeMs!!, 1e-6)
    }
}
