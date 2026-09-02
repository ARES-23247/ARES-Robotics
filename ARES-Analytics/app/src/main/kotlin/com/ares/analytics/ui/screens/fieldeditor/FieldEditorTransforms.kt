package com.ares.analytics.ui.screens.fieldeditor

import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.shared.models.League

/**
 * Geometric transformations and mirror operations for field obstacles and coordinates.
 */
internal object FieldEditorTransforms {

    fun mirrorObstacleX(obs: Obstacle, fieldWidth: Double, league: League): Obstacle = when (obs) {
        is Obstacle.Circle -> {
            val newX = if (league == League.FTC) -obs.centerX else fieldWidth - obs.centerX
            obs.copy(centerX = newX)
        }
        is Obstacle.Rectangle -> {
            val newX = if (league == League.FTC) -obs.centerX else fieldWidth - obs.centerX
            obs.copy(centerX = newX, rotation = -obs.rotation)
        }
        is Obstacle.Polygon -> {
            obs.copy(vertices = obs.vertices.map { v ->
                val newX = if (league == League.FTC) -v.x else fieldWidth - v.x
                PathPoint(newX, v.y)
            })
        }
    }

    fun mirrorObstacleY(obs: Obstacle, fieldHeight: Double, league: League): Obstacle = when (obs) {
        is Obstacle.Circle -> {
            val newY = if (league == League.FTC) -obs.centerY else fieldHeight - obs.centerY
            obs.copy(centerY = newY)
        }
        is Obstacle.Rectangle -> {
            val newY = if (league == League.FTC) -obs.centerY else fieldHeight - obs.centerY
            obs.copy(centerY = newY, rotation = -obs.rotation)
        }
        is Obstacle.Polygon -> {
            obs.copy(vertices = obs.vertices.map { v ->
                val newY = if (league == League.FTC) -v.y else fieldHeight - v.y
                PathPoint(v.x, newY)
            })
        }
    }
}
