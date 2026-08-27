package com.areslib.sim

import org.dyn4j.dynamics.Body
import org.dyn4j.world.World

/**
 * Interface defining custom robot-environment physics interaction logic in the Dyn4j simulator.
 *
 * Implementations process field elements, intake/outtake mechanics, scoring zones, and inventory tracking per physics tick.
 */
interface SimInteractionModel {
    /**
     * Called every simulation tick to handle custom physical interactions.
     *
     * @param world Active Dyn4j physics world instance.
     * @param robotBody Physics body representing the robot chassis.
     * @param gamePieces Mutable list of active game piece physics bodies on the field.
     * @param intakeApplied Whether season IO actually applied intake output this frame.
     * @param flywheelApplied Whether season IO actually applied flywheel output this frame.
     * @param transferApplied Whether season IO actually applied transfer output this frame.
     * @param currentInventoryCount Current count of items/elements held by the robot.
     * @param robotHeading Robot heading orientation in radians (CCW positive).
     * @param robotX Robot field position X coordinate in meters.
     * @param robotY Robot field position Y coordinate in meters.
     * @return Updated item inventory count following interaction processing.
     */
    fun update(
        world: World<Body>,
        robotBody: Body,
        gamePieces: MutableList<Body>,
        intakeApplied: Boolean,
        flywheelApplied: Boolean,
        transferApplied: Boolean,
        currentInventoryCount: Int,
        robotHeading: Double,
        robotX: Double,
        robotY: Double
    ): Int

    /** Clears edge/cooldown state at an OpMode lifecycle boundary. */
    fun reset() = Unit
}

/**
 * Default pass-through implementation of [SimInteractionModel] performing no physical manipulation of field elements.
 */
class NoOpInteractionModel : SimInteractionModel {
    /**
     * Pass-through update loop preserving the existing inventory count without modifying field bodies.
     */
    override fun update(
        world: World<Body>,
        robotBody: Body,
        gamePieces: MutableList<Body>,
        intakeApplied: Boolean,
        flywheelApplied: Boolean,
        transferApplied: Boolean,
        currentInventoryCount: Int,
        robotHeading: Double,
        robotX: Double,
        robotY: Double
    ): Int {
        return currentInventoryCount
    }
}
