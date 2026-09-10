package com.areslib.sim.physics

import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldManager
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldWallOriginAuditTest {
    @Test fun `walls follow field origin even when dimensions do not change`() {
        val previous = RobotFieldManager.activeConfig
        try {
            val physics = SimPhysicsWorld()
            for (type in listOf(FieldType.XRP, FieldType.FTC, FieldType.FRC, FieldType.FTC)) {
                physics.loadFieldElements(RobotFieldConfig(fieldType = type, widthMeters = 2.54, heightMeters = 1.4224))
                val cx = if (type == FieldType.FTC) 0.0 else 1.27
                val cy = if (type == FieldType.FTC) 0.0 else 0.7112
                val centers = physics.fieldWalls.map { it.transform.translation }
                assertEquals(cx, centers[0].x, 1e-12, "$type top X")
                assertEquals(cy + 0.7112 + 0.05, centers[0].y, 1e-12, "$type top Y")
                assertEquals(cy - 0.7112 - 0.05, centers[1].y, 1e-12, "$type bottom Y")
                assertEquals(cx - 1.27 - 0.05, centers[2].x, 1e-12, "$type left X")
                assertEquals(cx + 1.27 + 0.05, centers[3].x, 1e-12, "$type right X")
                assertEquals(cy, centers[2].y, 1e-12, "$type left Y")
                assertEquals(5, physics.world.bodyCount, "Replacing walls must not leak bodies")
            }
        } finally { RobotFieldManager.setActiveConfig(previous) }
    }
}
