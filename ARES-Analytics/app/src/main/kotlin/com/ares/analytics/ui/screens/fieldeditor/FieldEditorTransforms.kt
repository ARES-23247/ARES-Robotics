package com.ares.analytics.ui.screens.fieldeditor

import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.shared.models.League

/**
 * Geometric transformations and mirror operations for field obstacles and coordinates.
 */
internal object FieldEditorTransforms {
    private fun isCenterOrigin(league: League): Boolean = league == League.FTC || league == League.XRP
    private fun mirrorCoord(value: Double, span: Double, league: League): Double {
        val mirrored = if (isCenterOrigin(league)) -value else span - value
        return if (mirrored == 0.0) 0.0 else mirrored
    }

    fun mirrorObstacleX(obs: Obstacle, fieldWidth: Double, league: League): Obstacle = when (obs) {
        is Obstacle.Circle -> obs.copy(centerX = mirrorCoord(obs.centerX, fieldWidth, league))
        is Obstacle.Rectangle -> obs.copy(
            centerX = mirrorCoord(obs.centerX, fieldWidth, league),
            rotation = if (obs.rotation == 0.0) 0.0 else -obs.rotation,
        )
        is Obstacle.Polygon -> obs.copy(vertices = obs.vertices.map { v ->
            PathPoint(mirrorCoord(v.x, fieldWidth, league), v.y)
        })
    }

    fun mirrorObstacleY(obs: Obstacle, fieldHeight: Double, league: League): Obstacle = when (obs) {
        is Obstacle.Circle -> obs.copy(centerY = mirrorCoord(obs.centerY, fieldHeight, league))
        is Obstacle.Rectangle -> obs.copy(
            centerY = mirrorCoord(obs.centerY, fieldHeight, league),
            rotation = if (obs.rotation == 0.0) 0.0 else -obs.rotation,
        )
        is Obstacle.Polygon -> obs.copy(vertices = obs.vertices.map { v ->
            PathPoint(v.x, mirrorCoord(v.y, fieldHeight, league))
        })
    }
}
