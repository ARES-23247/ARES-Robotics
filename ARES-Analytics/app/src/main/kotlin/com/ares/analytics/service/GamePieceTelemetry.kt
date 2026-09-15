package com.ares.analytics.service

import com.ares.analytics.shared.GamePiece
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

internal data class DecodedGamePieceFrame(val sequence: Long, val pieces: Map<Int, GamePiece>)

/** One complete parent observation; null sequence represents a rejected frame, with no invented pieces. */
internal data class GamePieceFrameSnapshot(
    val pieces: Map<Int, GamePiece>,
    val sequence: Long?,
    val timestampUs: Long,
    val targetEpoch: Long,
)

/** Desktop decoder for the shared simulator v2 layout (meters, CCW radians, exact integer identities). */
internal object GamePieceTelemetry {
    const val TOPIC = "ARES/GamePiecesFrame"
    const val PREFIX = "$TOPIC/"
    const val VERSION = 2.0
    const val MAX_PIECES = 10_000
    const val MAX_EXACT_INTEGER = 9_007_199_254_740_991.0

    fun count(value: Double?): Int? = value?.takeIf {
        it.isFinite() && it >= 0.0 && it <= MAX_PIECES && it == it.toInt().toDouble()
    }?.toInt()

    fun requiredSize(count: Int): Int {
        require(count in 0..MAX_PIECES)
        return 3 + count * 9
    }

    fun decodePacked(value: Any?): DecodedGamePieceFrame? {
        val size = when (value) {
            is JsonArray -> value.size
            is List<*> -> value.size
            is DoubleArray -> value.size
            is FloatArray -> value.size
            is Array<*> -> value.size
            else -> return null
        }
        // Match the existing NT4 ingestion budget; replay maps retain their own bounded count.
        if (size > 4096) return null
        return decode(size) { index ->
            val element = when (value) {
                is JsonArray -> value[index]
                is List<*> -> value[index]
                is DoubleArray -> value[index]
                is FloatArray -> value[index]
                is Array<*> -> value[index]
                else -> null
            }
            when (element) {
                is JsonPrimitive -> if (element.isString) null else element.doubleOrNull
                is Number -> element.toDouble()
                else -> null
            }
        }
    }

    fun decodeScalars(values: DoubleArray): DecodedGamePieceFrame? = decode(values.size) { values[it] }

    fun decodeSnapshot(values: Map<String, Double>, strings: Map<String, String> = emptyMap()): Map<Int, GamePiece>? {
        val count = count(values["${PREFIX}1"]) ?: return null
        return decode(requiredSize(count)) { index ->
            val key = "$PREFIX$index"
            values[key].takeUnless { key in strings }
        }?.pieces
    }

    private inline fun decode(size: Int, numberAt: (Int) -> Double?): DecodedGamePieceFrame? {
        if (size < 3 || numberAt(0) != VERSION) return null
        val count = count(numberAt(1)) ?: return null
        if (size != requiredSize(count)) return null
        val sequence = exactInteger(numberAt(size - 1), minimum = 0.0) ?: return null
        val pieces = LinkedHashMap<Int, GamePiece>(count)
        for (index in 0 until count) {
            val base = 2 + index * 9
            val instance = exactInteger(numberAt(base), minimum = 1.0) ?: return null
            val type = exactInteger(numberAt(base + 1), minimum = 1.0) ?: return null
            val x = numberAt(base + 2)?.takeIf(Double::isFinite) ?: return null
            val y = numberAt(base + 3)?.takeIf(Double::isFinite) ?: return null
            val heading = numberAt(base + 4)?.takeIf(Double::isFinite) ?: return null
            val width = numberAt(base + 5)?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val height = numberAt(base + 6)?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val shape = when (numberAt(base + 7)) { 0.0 -> "circle"; 1.0 -> "box"; else -> return null }
            val color = numberAt(base + 8)?.takeIf {
                it.isFinite() && it >= 0.0 && it <= 0xFFFFFF && it == it.toInt().toDouble()
            }?.toInt() ?: return null
            val description = if (shape == "box") "Simulated Box" else "Simulated Circle"
            // Map slots follow wire order; GamePiece.id retains stable identity across reordering.
            // This avoids quadratic linear probing when different long IDs have the same Int hash.
            pieces[index] = GamePiece(
                id = "sim-$instance", name = description, x = x, y = y, type = description,
                typeId = "sim-type-$type", rotationRadians = heading, widthMeters = width,
                heightMeters = height, simulationShape = shape, colorRgb = color,
            )
        }
        return DecodedGamePieceFrame(sequence, pieces)
    }

    private fun exactInteger(value: Double?, minimum: Double): Long? = value?.takeIf {
        it.isFinite() && it >= minimum && it <= MAX_EXACT_INTEGER && it == it.toLong().toDouble()
    }?.toLong()
}
