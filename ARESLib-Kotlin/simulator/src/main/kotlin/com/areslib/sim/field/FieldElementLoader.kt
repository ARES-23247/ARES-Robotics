package com.areslib.sim.field

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.dyn4j.dynamics.Body
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
            val metadata = SimGamePieceBodyFactory.metadata(typeSpec, el)
            val body = if (typeSpec.movable) {
                SimGamePieceBodyFactory.createBody(metadata, el.x, el.y, Math.toRadians(el.rotation))
            } else {
                createStaticBody(el.x, el.y, el.rotation, metadata)
            }
            world.addBody(body)
            spawnedBodies.add(body)
        }
        return spawnedBodies
    }

    private fun createStaticBody(
        x: Double,
        y: Double,
        rotation: Double,
        metadata: SimGamePieceMetadata,
    ): Body {
        val body = SimGamePieceBodyFactory.createBody(metadata, x, y, Math.toRadians(rotation))
        body.setMass(MassType.INFINITE)
        return body
    }
}
