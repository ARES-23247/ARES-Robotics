package com.areslib.pathing

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PathPlannerParserTest {

    @Test
    fun `parsePath extracts waypoints and calculates distance`() {
        val mockJson = """
            {
              "waypoints": [
                {"anchor": {"x": 0.0, "y": 0.0}},
                {"anchor": {"x": 3.0, "y": 4.0}}
              ],
              "eventMarkers": [
                {
                  "name": "IntakeOn",
                  "waypointRelativePos": 0.5,
                  "command": {
                    "type": "named",
                    "name": "IntakeOn"
                  }
                }
              ]
            }
        """.trimIndent()
        
        val path = PathPlannerParser.parsePath(mockJson)
        
        assertNotNull(path)
        assertEquals(101, path.points.size)
        
        val p1 = path.points.first()
        assertEquals(0.0, p1.pose.x)
        assertEquals(0.0, p1.pose.y)
        assertEquals(0.0, p1.distanceMeters)
        assertEquals(0.0, p1.velocityMps) // Should start at 0
        
        val pMid = path.points[50]
        // In the middle of the 5.0m S-curve, with max accel 1.5, it should easily reach max velocity 2.0
        assertEquals(2.0, pMid.velocityMps, 0.001)

        val pLast = path.points.last()
        assertEquals(3.0, pLast.pose.x, 0.001)
        assertEquals(4.0, pLast.pose.y, 0.001)
        assertEquals(5.0, pLast.distanceMeters, 0.05) // allow small numerical error from discretization
        assertEquals(0.0, pLast.velocityMps) // Should end at 0
        
        assertEquals(1, path.events.size)
        val event = path.events[0]
        assertEquals("IntakeOn", event.eventName)
        // distance at index 50 should be roughly 2.5
        assertEquals(2.5, event.triggerDistanceMeters, 0.5)
    }

    @Test
    fun `dynamicPathLoader successfully locates and parses example path`() {
        val path = DynamicPathLoader.loadPath("example_path")
        assertNotNull(path)
        assertEquals(41, path.points.size)
        assertEquals(0.0, path.points.first().pose.x)
        assertEquals(1.0, path.points.last().pose.x, 0.001)
        assertEquals(1.0, path.points.last().pose.y, 0.001)
    }
}
