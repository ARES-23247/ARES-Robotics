package com.areslib.sim.field

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.dyn4j.dynamics.Body
import org.dyn4j.dynamics.BodyFixture
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.world.World
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldElementType
import com.areslib.state.RobotFieldElementInstance

/**
 * Object implementation for Field Element Loader.
 *
 * Robotics framework control component.
 */
object FieldElementLoader {
    private val gson = Gson()

    fun loadElements(world: World<Body>, jsonString: String): List<Body> {
        try {
            val config = gson.fromJson(jsonString, RobotFieldConfig::class.java)
            if (config != null) {
                return loadElements(world, config.elementTypes, config.elements)
            }
        } catch (e: Exception) {
            System.err.println("Failed to parse field elements JSON: ${e.message}")
            e.printStackTrace()
        }
        return emptyList()
    }

    fun loadElements(
        world: World<Body>,
        elementTypes: List<RobotFieldElementType>,
        elements: List<RobotFieldElementInstance>
    ): List<Body> {
        val spawnedBodies = mutableListOf<Body>()
        val typesMap = elementTypes.associateBy { it.id }

        for (el in elements) {
            val typeSpec = typesMap[el.elementTypeId] ?: continue
            val body = createBodyFromSpec(typeSpec, el.x, el.y, el.rotation, el.id)
            world.addBody(body)
            spawnedBodies.add(body)
        }
        return spawnedBodies
    }

    private data class SimGamePiece(
        val id: String = "",
        val name: String = "",
        val x: Double = 0.0,
        val y: Double = 0.0,
        val type: String = "Custom"
    )

    fun loadGamePiecesFromAnalyticsJson(world: World<Body>, jsonString: String): List<Body> {
        val spawnedBodies = mutableListOf<Body>()
        try {
            val listType = object : com.google.gson.reflect.TypeToken<List<SimGamePiece>>() {}.type
            val gamePieces: List<SimGamePiece> = gson.fromJson(jsonString, listType) ?: return emptyList()

            for (gp in gamePieces) {
                val isSample = gp.type.contains("Sample")
                val isNote = gp.type.contains("Note")
                
                val shape = if (isSample) "box" else "cylinder"
                val width = if (isSample) 0.15 else 0.1
                val height = if (isSample) 0.05 else 0.1
                val diameter = if (isNote) 0.35 else 0.15
                
                val typeSpec = RobotFieldElementType(
                    id = gp.type,
                    name = gp.type,
                    shape = shape,
                    width = width,
                    height = height,
                    diameter = diameter,
                    movable = true,
                    massKg = if (isSample) 0.2 else 0.24
                )
                
                val body = createBodyFromSpec(typeSpec, gp.x, gp.y, 0.0, gp.name)
                world.addBody(body)
                spawnedBodies.add(body)
            }
        } catch (e: Exception) {
            System.err.println("Failed to parse game_pieces.json: ${e.message}")
            e.printStackTrace()
        }
        return spawnedBodies
    }

    private fun createBodyFromSpec(
        type: RobotFieldElementType,
        x: Double,
        y: Double,
        rotation: Double,
        id: String
    ): Body {
        val body = Body()

        val shape = when (type.shape.lowercase()) {
            "box" -> Geometry.createRectangle(type.width, type.height)
            else -> {
                val radius = (type.diameter ?: 0.15) / 2.0
                Geometry.createCircle(radius)
            }
        }

        val fixture = BodyFixture(shape)
        fixture.friction = 0.6
        fixture.restitution = 0.3

        val area = shape.getArea()
        val density = if (area > 0) type.massKg / area else 1.0
        fixture.density = density

        body.addFixture(fixture)

        if (type.movable) {
            body.setMass(MassType.NORMAL)
            body.linearDamping = 2.0
            body.angularDamping = 2.0
        } else {
            body.setMass(MassType.INFINITE)
        }

        body.translate(x, y)

        if (rotation != 0.0) {
            body.rotate(Math.toRadians(rotation), x, y)
        }

        body.userData = id

        return body
    }
}
