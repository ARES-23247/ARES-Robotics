package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.LegacyGamePieceSnapshot
import com.ares.analytics.service.LegacyGamePieceTelemetry as Format
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.models.TelemetryFrame

/** Bounded compatibility for latched x/y topics; complete parents replace the whole layer. */
internal class LegacyGamePieceAccumulator {
    private class Coordinates(var x: Double? = null, var y: Double? = null)
    private val staged = HashMap<Int, Coordinates>()
    private var countLimit: Int? = null
    private var parentOwned = false
    private var pieces: Map<Int, GamePiece> = emptyMap()

    @Synchronized fun reset() {
        staged.clear(); countLimit = null; parentOwned = false; pieces = emptyMap()
    }

    @Synchronized fun snapshot(): Map<Int, GamePiece> = pieces

    @Synchronized fun accept(frame: LegacyGamePieceSnapshot) {
        frame.countLimit?.let(::setCount)
        if (frame.hasParent) {
            parentOwned = true
            staged.clear()
            val limit = countLimit ?: Format.MAX_PIECES
            pieces = if (frame.pieces.keys.all { it in 0 until limit }) frame.pieces
                else frame.pieces.filterKeys { it in 0 until limit }
        }
    }

    private fun setCount(count: Int) {
        countLimit = count
        staged.keys.removeAll { it >= count }
        if (pieces.keys.any { it >= count }) pieces = pieces.filterKeys { it < count }
    }

    @Synchronized fun accept(frame: TelemetryFrame): Map<Int, GamePiece>? {
        val previous = pieces
        if (frame.key == Format.COUNT) {
            setCount(Format.count(frame.value, frame.stringValue) ?: 0)
            return pieces.takeUnless { it === previous }
        }
        if (parentOwned || !frame.key.startsWith(Format.PREFIX)) return null
        val slot = frame.key.removePrefix(Format.PREFIX).toIntOrNull()
            ?.takeIf { it >= 0 && it < Format.MAX_PIECES * Format.WIDTH } ?: return null
        val attribute = slot % Format.WIDTH
        if (attribute > 1) return null // z/quaternion attributes do not change this planar display.
        val index = slot / Format.WIDTH
        if (index >= (countLimit ?: Format.MAX_PIECES)) return null
        val value = frame.value.takeIf { frame.stringValue == null && it.isFinite() }
        val coordinates = staged[index] ?: if (value != null) Coordinates().also { staged[index] = it } else return null
        if (attribute == 0) coordinates.x = value else coordinates.y = value
        val x = coordinates.x
        val y = coordinates.y
        if (x == null || y == null) {
            if (index in pieces) pieces = pieces - index
        } else {
            val old = pieces[index]
            if (old == null || old.x != x || old.y != y) pieces = pieces + (index to Format.piece(index, x, y))
        }
        return pieces.takeUnless { it === previous }
    }
}
