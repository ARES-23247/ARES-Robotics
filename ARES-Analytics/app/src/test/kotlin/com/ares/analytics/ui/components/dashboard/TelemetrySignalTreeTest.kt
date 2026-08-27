package com.ares.analytics.ui.components.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelemetrySignalTreeTest {

    @Test
    fun `buildSignalTree builds correct multi-level hierarchical tree`() {
        val topics = listOf(
            "Hardware/Motors/fl/CurrentAmps",
            "Hardware/Motors/fl/Velocity",
            "Hardware/Motors/fr/CurrentAmps",
            "Robot/BatteryVoltage",
            "ARES/Input/driveFrame"
        )

        val root = buildSignalTree(topics)

        assertEquals(3, root.children.size)
        assertTrue(root.children.containsKey("Hardware"))
        assertTrue(root.children.containsKey("Robot"))
        assertTrue(root.children.containsKey("ARES"))

        val hardware = assertNotNull(root.children["Hardware"])
        assertFalse(hardware.isLeaf)
        assertEquals("/Hardware", hardware.fullPath)

        val motors = assertNotNull(hardware.children["Motors"])
        assertFalse(motors.isLeaf)

        val fl = assertNotNull(motors.children["fl"])
        assertFalse(fl.isLeaf)

        val flCurrent = assertNotNull(fl.children["CurrentAmps"])
        assertTrue(flCurrent.isLeaf)
        assertEquals("/Hardware/Motors/fl/CurrentAmps", flCurrent.fullPath)

        val robot = assertNotNull(root.children["Robot"])
        val battery = assertNotNull(robot.children["BatteryVoltage"])
        assertTrue(battery.isLeaf)
        assertEquals("/Robot/BatteryVoltage", battery.fullPath)
    }

    @Test
    fun `buildSignalTree handles single level and empty inputs`() {
        val emptyRoot = buildSignalTree(emptyList())
        assertTrue(emptyRoot.children.isEmpty())
        assertTrue(emptyRoot.isLeaf)

        val singleLevel = buildSignalTree(listOf("LoopTimeMs", "/HeadingDeg"))
        assertEquals(2, singleLevel.children.size)
        assertTrue(singleLevel.children["LoopTimeMs"]!!.isLeaf)
        assertTrue(singleLevel.children["HeadingDeg"]!!.isLeaf)
    }
}
