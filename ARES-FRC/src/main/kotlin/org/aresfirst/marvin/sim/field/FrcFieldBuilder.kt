package org.aresfirst.marvin.sim.field

import org.dyn4j.dynamics.Body
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.world.World

/** Builds static Dyn4j collision bodies in blue-origin field meters. */
object FrcFieldBuilder {

    /** Adds only an axis-aligned boundary of [width] by [height] meters. */
    fun buildWorldWalls(world: World<Body>, width: Double, height: Double) {
        addWall(world, width / 2.0, height, width, 0.1)   // Top
        addWall(world, width / 2.0, 0.0, width, 0.1)      // Bottom
        addWall(world, 0.0, height / 2.0, 0.1, height)     // Left
        addWall(world, width, height / 2.0, 0.1, height)   // Right
    }

    private fun addWall(world: World<Body>, x: Double, y: Double, w: Double, h: Double) {
        val wall = Body()
        wall.addFixture(Geometry.createRectangle(w, h))
        wall.setMass(MassType.INFINITE)
        wall.translate(x, y)
        world.addBody(wall)
    }

}
