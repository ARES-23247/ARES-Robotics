package com.ares.analytics.ui.components.pathplanner

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.ares.analytics.shared.models.League

object HeatmapOverlay {
    // Stateful grid for accumulation
    private val globalCounts = Array(100) { IntArray(100) }
    private var globalMaxCount = 0
    private var lastPathSize = 0
    private var lastFirstPoint: Waypoint? = null
    private var lastLeague: League? = null
    private var lastFieldWidthM: Double = 0.0
    private var lastFieldHeightM: Double = 0.0

    fun resetHeatmap() {
        for (i in globalCounts.indices) {
            for (j in globalCounts[i].indices) {
                globalCounts[i][j] = 0
            }
        }
        globalMaxCount = 0
        lastPathSize = 0
        lastFirstPoint = null
        lastLeague = null
        lastFieldWidthM = 0.0
        lastFieldHeightM = 0.0
    }

    fun drawHeatmap(
        drawScope: DrawScope,
        actualPath: List<Waypoint>,
        fieldWidthM: Double,
        fieldHeightM: Double,
        league: League,
        gridSize: Int = 100,
        opacity: Float = 0.6f
    ) {
        // Check if path was reset or if we have new points
        if (actualPath.isEmpty()) {
            resetHeatmap()
            return
        }
        val width = drawScope.size.width
        val height = drawScope.size.height

        // Reset whenever the path, league, or field dimensions change.
        if (lastPathSize > actualPath.size ||
            (actualPath.isNotEmpty() && lastFirstPoint != actualPath.first()) ||
            lastLeague != league ||
            lastFieldWidthM != fieldWidthM ||
            lastFieldHeightM != fieldHeightM
        ) {
            resetHeatmap()
        }

        // Add only the NEW points to the heatmap
        val startIndex = if (lastPathSize <= actualPath.size && lastFirstPoint == actualPath.firstOrNull()) lastPathSize else 0

        for (i in startIndex until actualPath.size) {
            val wp = actualPath[i]
            val normalized = getCanvasOffsetBase(wp, 1f, 1f, fieldWidthM, fieldHeightM, league)
            val col = (normalized.x.coerceIn(0f, 1f) * (gridSize - 1)).toInt()
            val row = (normalized.y.coerceIn(0f, 1f) * (gridSize - 1)).toInt()

            globalCounts[row][col]++
            if (globalCounts[row][col] > globalMaxCount) {
                globalMaxCount = globalCounts[row][col]
            }
        }

        lastPathSize = actualPath.size
        lastFirstPoint = actualPath.firstOrNull()
        lastLeague = league
        lastFieldWidthM = fieldWidthM
        lastFieldHeightM = fieldHeightM

        if (globalMaxCount == 0) return

        // 2. Draw density rectangles
        val cellW = width / gridSize
        val cellH = height / gridSize

        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val count = globalCounts[row][col]
                if (count > 0) {
                    val density = count.toDouble() / globalMaxCount
                    val color = when {
                        density < 0.25 -> {
                            val t = (density / 0.25).toFloat()
                            Color(0f, t, 1f, opacity)
                        }
                        density < 0.5 -> {
                            val t = ((density - 0.25) / 0.25).toFloat()
                            Color(0f, 1f, 1f - t, opacity)
                        }
                        density < 0.75 -> {
                            val t = ((density - 0.5) / 0.25).toFloat()
                            Color(t, 1f, 0f, opacity)
                        }
                        else -> {
                            val t = ((density - 0.75) / 0.25).toFloat()
                            Color(1f, 1f - t, 0f, opacity)
                        }
                    }

                    drawScope.drawRect(
                        color = color,
                        topLeft = Offset(col * cellW, row * cellH),
                        size = Size(cellW, cellH)
                    )
                }
            }
        }
    }
}
