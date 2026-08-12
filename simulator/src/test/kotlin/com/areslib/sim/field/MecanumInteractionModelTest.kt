package com.areslib.sim.field

import org.dyn4j.dynamics.Body
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.world.World
import kotlin.test.Test
import kotlin.test.assertEquals

class MecanumInteractionModelTest {
    @Test
    fun `intake changes inventory only for an applied season output`() {
        val world = World<Body>()
        val robot = dynamicCircle(0.20).also(world::addBody)
        val piece = dynamicCircle(0.075).apply { translate(0.35, 0.0) }
        world.addBody(piece)
        val pieces = mutableListOf(piece)
        val model = MecanumInteractionModel()

        val ignoredIntent = model.update(
            world, robot, pieces,
            intakeApplied = false,
            flywheelApplied = false,
            transferApplied = false,
            currentInventoryCount = 0,
            robotHeading = 0.0,
            robotX = 0.0,
            robotY = 0.0
        )
        assertEquals(0, ignoredIntent)
        assertEquals(1, pieces.size)

        val applied = model.update(
            world, robot, pieces,
            intakeApplied = true,
            flywheelApplied = false,
            transferApplied = false,
            currentInventoryCount = ignoredIntent,
            robotHeading = 0.0,
            robotX = 0.0,
            robotY = 0.0
        )
        assertEquals(1, applied)
        assertEquals(0, pieces.size)
    }

    @Test
    fun `transfer fires once per applied rising edge and reset clears the edge latch`() {
        val world = World<Body>()
        val robot = dynamicCircle(0.20).also(world::addBody)
        val pieces = mutableListOf<Body>()
        val model = MecanumInteractionModel()

        val first = updateTransfer(model, world, robot, pieces, transfer = true, inventory = 1)
        assertEquals(0, first)
        assertEquals(1, pieces.size)

        val held = updateTransfer(model, world, robot, pieces, transfer = true, inventory = 1)
        assertEquals(1, held, "a held transfer output must not repeatedly consume inventory")
        assertEquals(1, pieces.size)

        model.reset()
        val afterReset = updateTransfer(model, world, robot, pieces, transfer = true, inventory = 1)
        assertEquals(0, afterReset)
        assertEquals(2, pieces.size)
    }

    private fun updateTransfer(
        model: MecanumInteractionModel,
        world: World<Body>,
        robot: Body,
        pieces: MutableList<Body>,
        transfer: Boolean,
        inventory: Int
    ): Int = model.update(
        world, robot, pieces,
        intakeApplied = false,
        flywheelApplied = true,
        transferApplied = transfer,
        currentInventoryCount = inventory,
        robotHeading = 0.0,
        robotX = 0.0,
        robotY = 0.0
    )

    private fun dynamicCircle(radius: Double): Body = Body().apply {
        addFixture(Geometry.createCircle(radius))
        setMass(MassType.NORMAL)
    }
}
