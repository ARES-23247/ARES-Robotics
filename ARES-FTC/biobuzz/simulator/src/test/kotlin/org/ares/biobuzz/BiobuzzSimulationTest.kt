package org.ares.biobuzz

import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldValidator
import kotlin.math.cos
import kotlin.test.*

class BiobuzzSimulationTest {
    private val worlds = java.util.IdentityHashMap<BiobuzzSimulation, org.dyn4j.world.World<org.dyn4j.dynamics.Body>>()
    private fun newSimulation(): BiobuzzSimulation {
        val physics = com.areslib.sim.physics.SimPhysicsWorld()
        return BiobuzzSimulation(physics.world, physics.robotBody, BiobuzzField.document()).also { worlds[it] = physics.world }
    }
    private fun stage(sim: BiobuzzSimulation, id: String, x: Double, y: Double, z: Double, vz: Double = 0.0) {
        val elements = sim.field.elements
        val kind = when { id.startsWith("pollen-") -> BallKind.POLLEN
            id.startsWith("red-nectar-") -> BallKind.RED_NECTAR
            id.startsWith("blue-nectar-") -> BallKind.BLUE_NECTAR
            else -> null }
        val actual = if (kind == null) id else elements.filter { it.elementTypeId == kind.typeId }[id.substringAfterLast('-').toInt()].id
        sim.stageBall(actual, x, y, z, vz)
    }

    private fun advance(sim: BiobuzzSimulation, count: Int, control: BiobuzzControl = BiobuzzControl()) {
        repeat(count) { sim.command(control); worlds.getValue(sim).step(1, 0.01); sim.step() }
    }

    @Test fun `bundled field is canonical and complete with physical piece specifications`() {
        val field = BiobuzzField.document()
        assertTrue(RobotFieldValidator.validate(field).isEmpty())
        assertEquals(field, RobotFieldDocument.decode(RobotFieldDocument.encode(field)))
        assertEquals(6, field.fieldWaypoints.size)
        assertEquals(40, field.elements.count { it.elementTypeId == BallKind.POLLEN.typeId })
        assertEquals(16, field.elements.count { it.elementTypeId != BallKind.POLLEN.typeId })
        for (kind in BallKind.entries) {
            val type = field.elementTypes.single { it.id == kind.typeId }
            assertEquals(kind.massKg, type.massKg, 1e-10)
            assertEquals(kind.diameter, type.diameter)
        }
    }

    @Test fun `reset conserves every ball and restores each independent hive and four robot preloads`() {
        newSimulation().use { sim ->
            val initial = sim.snapshot()
            assertEquals(56, initial.balls.size)
            assertEquals(56, initial.balls.map { it.id }.toSet().size)
            assertEquals(4, initial.inventory.size)
            assertEquals(listOf(4, 4, 4, 4), initial.flowers.map { it.contents.size })
            assertEquals(listOf(0, 1), initial.hives.map { it.upward })
            assertEquals(listOf(3, 3), initial.hives.map { it.contents.size })
            assertEquals(5, initial.redReserve)
            assertEquals(5, initial.blueReserve)
            sim.command(BiobuzzControl(enabled = true))
            assertTrue(sim.releaseNectar(true))
            advance(sim, 5, BiobuzzControl(enabled = true, shoot = true))
            sim.reset()
            assertEquals(initial, sim.snapshot())
        }
    }

    @Test fun `shoot requires enable and a rising edge and never duplicates inventory`() {
        newSimulation().use { sim ->
            advance(sim, 30, BiobuzzControl(shoot = true))
            assertEquals(4, sim.snapshot().inventory.size)
            advance(sim, 100, BiobuzzControl(enabled = true, shoot = true))
            assertEquals(3, sim.snapshot().inventory.size)
            advance(sim, 1, BiobuzzControl(enabled = true))
            advance(sim, 1, BiobuzzControl(enabled = true, shoot = true))
            assertEquals(2, sim.snapshot().inventory.size)
            assertEquals(56, sim.snapshot().balls.map { it.id }.toSet().size)
        }
    }

    @Test fun `ground intake respects four capacity and nectar cannot exit a flower bottom`() {
        newSimulation().use { sim ->
            repeat(4) { stage(sim,"pollen-${24 + it}", -1.4, -1.3 + it * 0.1, 0.04) }
            val flower = sim.snapshot().flowers[0]
            stage(sim,"red-nectar-3", flower.x, flower.y, 0.7)
            advance(sim, 80)
            assertEquals(BallKind.RED_NECTAR, sim.snapshot().flowers[0].contents.last())
            sim.robot.transform.setTranslation(flower.x - 0.27, flower.y)
            sim.robot.transform.setRotation(0.0)
            advance(sim, 120, BiobuzzControl(enabled = true, intake = true))
            assertEquals(4, sim.snapshot().inventory.size)
            assertEquals(listOf(BallKind.RED_NECTAR), sim.snapshot().flowers[0].contents)
            sim.command(BiobuzzControl())
            sim.snapshot().balls.filter { it.location == BallLocation.ROBOT }.forEachIndexed { i, ball ->
                stage(sim,ball.id, -1.2, -1.2 + i * 0.1, 0.04)
            }
            stage(sim,"pollen-28", flower.x, flower.y, 0.7)
            advance(sim, 80)
            advance(sim, 120, BiobuzzControl(enabled = true, intake = true))
            assertTrue(sim.snapshot().inventory.isEmpty())
            assertEquals(listOf(BallKind.RED_NECTAR, BallKind.POLLEN), sim.snapshot().flowers[0].contents)
        }
    }

    @Test fun `hive tips at all six minimum mixed loads and rejects the load one pollen short`() {
        val combinations = listOf(0 to 8, 1 to 7, 2 to 5, 3 to 4, 4 to 2, 5 to 0)
        for ((nectar, pollen) in combinations) {
            for (enough in listOf(false, true)) newSimulation().use { sim ->
                repeat(3) { stage(sim,"red-nectar-$it", -1.4, 1.2 + it * 0.1, 0.05) }
                val actualNectar = if (!enough && pollen == 0) nectar - 1 else nectar
                val actualPollen = if (!enough && pollen > 0) pollen - 1 else pollen
                val x = -BiobuzzField.CELL_OFFSET * cos(BiobuzzField.STABLE_ANGLE)
                repeat(actualNectar) { stage(sim,"red-nectar-$it", x, 0.32385, 1.7, -1.0) }
                repeat(actualPollen) { stage(sim,"pollen-$it", x, 0.32385, 1.7, -1.0) }
                advance(sim, 200)
                val state = sim.snapshot()
                assertEquals(if (enough) 1 else 0, state.hives[0].tips, "$nectar nectar + $pollen pollen; enough=$enough")
                assertEquals(0, state.hives[1].tips)
                assertEquals(if (enough) 1 else 0, state.hives[0].upward)
                if (enough) assertTrue(state.hives[0].cells.all { it.isEmpty() })
                assertEquals(56, state.balls.size)
            }
        }
    }

    @Test fun `tip is only counted on completion and the opposite cell can tip back`() {
        newSimulation().use { sim ->
            // Three preloaded nectar + four pollen cross the nominal threshold.
            val x = BiobuzzField.CELL_OFFSET * cos(BiobuzzField.STABLE_ANGLE)
            repeat(4) { stage(sim,"pollen-$it", -x, 0.32385, 1.6, -1.0) }
            advance(sim, 30)
            assertTrue(sim.snapshot().hives[0].tipping)
            assertEquals(0, sim.snapshot().hives[0].tips)
            advance(sim, 100)
            assertEquals(1, sim.snapshot().hives[0].tips)
            repeat(8) { stage(sim,"pollen-${it + 8}", x, 0.32385, 1.6, -1.0) }
            advance(sim, 150)
            assertEquals(2, sim.snapshot().hives[0].tips)
            assertEquals(0, sim.snapshot().hives[0].upward)
        }
    }

    @Test fun `high speed shots cross receiver height but shots below or outside it do not score`() {
        newSimulation().use { sim ->
            val x = -BiobuzzField.CELL_OFFSET * cos(BiobuzzField.STABLE_ANGLE)
            stage(sim,"pollen-0", x, 0.32385, 1.6, -30.0)
            stage(sim,"pollen-1", x, 0.32385, 0.5)
            stage(sim,"pollen-2", x, 1.0, 1.6, -30.0)
            advance(sim, 30)
            assertEquals(4, sim.snapshot().hives[0].contents.size)
        }
    }

    @Test fun `season layer never moves chassis or steps the shared world and expires its mechanism lease`() {
        val sim = newSimulation()
        sim.robot.transform.setTranslation(1.4, 0.0)
        sim.robot.setLinearVelocity(1.0, 0.0)
        sim.command(BiobuzzControl(enabled = true))
        repeat(20) { sim.step() }
        assertEquals(1.4, sim.robot.transform.translationX)
        assertEquals(1.0, sim.robot.linearVelocity.x)
        assertFalse(sim.snapshot().enabled)
        sim.command(BiobuzzControl(enabled = true, speed = Double.NaN))
        assertFalse(sim.snapshot().enabled)
        for (dt in listOf(0.0, -0.01, Double.NaN, Double.POSITIVE_INFINITY, 1.0))
            assertFailsWith<IllegalArgumentException> { sim.step(dt) }
        sim.close(); sim.close()
        assertFalse(sim.snapshot().enabled)
    }
}
