package com.acmerobotics.dashboard.canvas

/**
 * Mock representation of FTC Dashboard [Canvas].
 *
 * Provides a drawing canvas double for robot field visualization in FTC Dashboard.
 * In desktop simulation and test execution, operations on this canvas are safely
 * recorded or discarded without requiring WebSocket communication or active browser clients.
 *
 * Autonomous routines and odometry tracking algorithms can render robot poses,
 * target waypoints, and sensor field-of-view cones to verify drawing logic without crashes.
 */
open class Canvas {
    /** Constructs a default headless simulation [Canvas]. */
    constructor()

    /**
     * Draws a circular outline centered at ([x], [y]) with the specified [radius] in field inches.
     *
     * @param x The X coordinate of the circle center in field coordinate inches.
     * @param y The Y coordinate of the circle center in field coordinate inches.
     * @param radius The radius of the circle in field inches.
     */
    fun drawCircle(x: Double, y: Double, radius: Double) {}

    /**
     * Draws a line segment between points ([x1], [y1]) and ([x2], [y2]) in field inches.
     *
     * @param x1 Starting point X coordinate.
     * @param y1 Starting point Y coordinate.
     * @param x2 Ending point X coordinate.
     * @param y2 Ending point Y coordinate.
     */
    fun drawLine(x1: Double, y1: Double, x2: Double, y2: Double) {}

    /**
     * Sets the active stroke color used for subsequent vector drawing operations.
     *
     * @param color A CSS-style color string (e.g., "#FF0000", "red", "rgba(0,255,0,0.5)").
     */
    fun setStroke(color: String) {}
}
