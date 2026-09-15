package com.ares.analytics.service

import com.ares.analytics.shared.GamePiece
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

internal data class VisionPoseArraySnapshot(
    val poses: Map<Int, Double>, val timestampUs: Long, val targetEpoch: Long,
)

/** Loss generation survives conflated false/true flags and bounded raw telemetry queues. */
internal data class VisionTargetSnapshot(
    val hasTarget: Boolean, val lossGeneration: Long, val timestampUs: Long, val targetEpoch: Long,
)

internal data class LegacyGamePieceSnapshot(
    val pieces: Map<Int, GamePiece>, val countLimit: Int?, val hasParent: Boolean,
    val timestampUs: Long, val targetEpoch: Long,
)

internal data class DecodedFieldArray<T>(val values: T, val length: Int)

/** Owned, finite active records. Legacy producers may retain capacity beyond their active count. */
private fun fieldArray(value: Any?, width: Int, count: Int? = null): DoubleArray? {
    val size = when (value) {
        is JsonArray -> value.size
        is List<*> -> value.size
        is DoubleArray -> value.size
        is FloatArray -> value.size
        is Array<*> -> value.size
        else -> return null
    }
    if (size > 4096 || (count == null && size % width != 0)) return null
    val length = count?.times(width) ?: size
    if (length > size) return null
    return DoubleArray(length) { index ->
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
        }?.takeIf(Double::isFinite) ?: return null
    }
}

private fun snapshotLength(values: Map<String, Double>, strings: Map<String, String>, key: String, width: Int): Int? =
    values[key]?.takeIf { key !in strings && it.isFinite() && it in 0.0..4096.0 &&
        it == it.toInt().toDouble() && it.toInt() % width == 0 }?.toInt()

internal object VisionPoseArrayTelemetry {
    const val TOPIC = "Vision/PoseArray"
    const val ALIAS = "AdvantageScope/VisionPose"
    fun isTopic(topic: String): Boolean = topic == TOPIC || topic == ALIAS

    fun decode(value: Any?): DecodedFieldArray<Map<Int, Double>>? {
        val numbers = fieldArray(value, 3) ?: return null
        return DecodedFieldArray(numbers.indices.associateWith { numbers[it] }, numbers.size)
    }

    fun decodeSnapshot(values: Map<String, Double>, strings: Map<String, String>): Map<Int, Double> {
        val topic = if (values.keys.any { it.startsWith("$TOPIC/") }) TOPIC else ALIAS
        val prefix = "$topic/"
        val lengthKey = prefix + "Length"
        fun number(index: Int) = values["$prefix$index"]?.takeIf { "$prefix$index" !in strings && it.isFinite() }
        if (lengthKey in values || lengthKey in strings) {
            val length = snapshotLength(values, strings, lengthKey, 3) ?: return emptyMap()
            val result = LinkedHashMap<Int, Double>(length)
            for (index in 0 until length) result[index] = number(index) ?: return emptyMap()
            return result
        }
        // Older recordings lack parent length metadata; preserve bounded complete triples.
        return buildMap {
            for (key in values.keys) {
                if (!key.startsWith(prefix)) continue
                val index = key.removePrefix(prefix).toIntOrNull()?.takeIf { it in 0..4092 && it % 3 == 0 } ?: continue
                val x = number(index) ?: continue
                val y = number(index + 1) ?: continue
                val heading = number(index + 2) ?: continue
                put(index, x); put(index + 1, y); put(index + 2, heading)
            }
        }
    }
}

internal object LegacyGamePieceTelemetry {
    const val TOPIC = "ARES/GamePieces"
    const val PREFIX = "$TOPIC/"
    const val COUNT = PREFIX + "Count"
    const val LENGTH = PREFIX + "Length"
    const val WIDTH = 7
    const val MAX_PIECES = 10_000

    fun count(value: Double?, text: String? = null): Int? = value?.takeIf {
        text == null && it.isFinite() && it in 0.0..MAX_PIECES.toDouble() && it == it.toInt().toDouble()
    }?.toInt()

    fun piece(index: Int, x: Double, y: Double): GamePiece =
        GamePiece(id = index.toString(), name = "Piece $index", x = x, y = y, type = "Game piece")

    fun decode(value: Any?, countLimit: Int? = null): DecodedFieldArray<Map<Int, GamePiece>>? {
        if (countLimit != null && countLimit !in 0..MAX_PIECES) return null
        val numbers = fieldArray(value, WIDTH, countLimit) ?: return null
        return DecodedFieldArray((0 until numbers.size / WIDTH).associateWith { index ->
            piece(index, numbers[index * WIDTH], numbers[index * WIDTH + 1])
        }, numbers.size)
    }

    fun decodeSnapshot(values: Map<String, Double>, strings: Map<String, String>): Map<Int, GamePiece> {
        val limit = if (COUNT in values || COUNT in strings) count(values[COUNT], strings[COUNT]) ?: return emptyMap() else null
        if (LENGTH in values || LENGTH in strings) {
            val length = snapshotLength(values, strings, LENGTH, WIDTH) ?: return emptyMap()
            val count = minOf(limit ?: MAX_PIECES, length / WIDTH)
            val result = LinkedHashMap<Int, GamePiece>(count)
            for (index in 0 until count) {
                val base = index * WIDTH
                // New length metadata is a validated complete-record contract, not a scalar guess.
                for (slot in base until base + WIDTH) {
                    val key = "$PREFIX$slot"
                    if (key in strings || values[key]?.isFinite() != true) return emptyMap()
                }
                result[index] = piece(index, values.getValue("$PREFIX$base"), values.getValue("$PREFIX${base + 1}"))
            }
            return result
        }
        return buildMap {
            for (key in values.keys) {
                if (!key.startsWith(PREFIX)) continue
                val slot = key.removePrefix(PREFIX).toIntOrNull()?.takeIf { it >= 0 && it < MAX_PIECES * WIDTH && it % WIDTH == 0 } ?: continue
                val index = slot / WIDTH
                if (limit != null && index >= limit) continue
                val yKey = "$PREFIX${slot + 1}"
                val x = values[key]?.takeIf { key !in strings && it.isFinite() } ?: continue
                val y = values[yKey]?.takeIf { yKey !in strings && it.isFinite() } ?: continue
                put(index, piece(index, x, y))
            }
        }
    }
}

internal data class FieldArrayLengthUpdate(val topic: String, val length: Int)

/** Owns bounded field parents and recorded lengths together across selected-target resets. */
internal class FieldArrayTelemetryState(
    private val telemetryStore: TelemetryStore,
    private val replayActive: StateFlow<Boolean>,
    private val coerceTelemetryValue: (Any?) -> Pair<Double, String?>,
) {
    // Nt4ClientService uses this same monitor while clearing the target's other telemetry.
    val lock = Any()
    private val _visionPoseArrayFrame = MutableStateFlow<VisionPoseArraySnapshot?>(null)
    internal val visionPoseArrayFrame: StateFlow<VisionPoseArraySnapshot?> = _visionPoseArrayFrame.asStateFlow()
    @Volatile internal var hasReceivedVisionPoseArray = false
        private set
    @Volatile private var hasCanonicalVisionPoseArray = false
    private val _legacyGamePieceFrame = MutableStateFlow<LegacyGamePieceSnapshot?>(null)
    internal val legacyGamePieceFrame: StateFlow<LegacyGamePieceSnapshot?> = _legacyGamePieceFrame.asStateFlow()
    private val _visionTargetFrame = MutableStateFlow<VisionTargetSnapshot?>(null)
    val visionTargetFrame: StateFlow<VisionTargetSnapshot?> = _visionTargetFrame.asStateFlow()
    private var visionLossGeneration = 0L
    private var legacyRecordedCount: Int? = null
    private var legacyRecordedLength: Int? = null

    fun reset() = synchronized(lock) {
        visionLossGeneration = 0L
        _visionTargetFrame.value = null
        hasReceivedVisionPoseArray = false
        hasCanonicalVisionPoseArray = false
        legacyRecordedCount = null
        legacyRecordedLength = null
        _visionPoseArrayFrame.value = null
        _legacyGamePieceFrame.value = null
    }

    fun accept(normalizedName: String, valueElement: Any?, timestampUs: Long): FieldArrayLengthUpdate? {
        if (normalizedName != LegacyGamePieceTelemetry.COUNT && normalizedName != LegacyGamePieceTelemetry.TOPIC &&
            normalizedName != "Vision/HasTarget" && !VisionPoseArrayTelemetry.isTopic(normalizedName)) return null
        var lengthTopic: String? = null
        var activeLength = -1
        val fieldArrayEpoch = telemetryStore.currentTargetEpoch()
        synchronized(lock) {
            if (fieldArrayEpoch == telemetryStore.currentTargetEpoch()) {
                if (normalizedName == LegacyGamePieceTelemetry.COUNT) {
                    val (value, text) = coerceTelemetryValue(valueElement)
                    val count = LegacyGamePieceTelemetry.count(value, text) ?: 0
                    legacyRecordedCount = count
                    legacyRecordedLength?.let { old ->
                        activeLength = if (count == 0) 0 else if (old < 0) -1 else minOf(old, count * 7)
                        legacyRecordedLength = activeLength
                        lengthTopic = LegacyGamePieceTelemetry.LENGTH
                    }
                    if (!replayActive.value && fieldArrayEpoch == telemetryStore.currentTargetEpoch()) {
                        val old = _legacyGamePieceFrame.value?.takeIf { it.targetEpoch == fieldArrayEpoch }
                        _legacyGamePieceFrame.value = LegacyGamePieceSnapshot(
                            old?.pieces.orEmpty().filterKeys { it < count }, count, old?.hasParent == true,
                            timestampUs, fieldArrayEpoch,
                        )
                    }
                } else if (normalizedName == LegacyGamePieceTelemetry.TOPIC) {
                    val decoded = LegacyGamePieceTelemetry.decode(valueElement, legacyRecordedCount)
                    activeLength = decoded?.length ?: -1
                    legacyRecordedLength = activeLength
                    lengthTopic = LegacyGamePieceTelemetry.LENGTH
                    if (!replayActive.value && fieldArrayEpoch == telemetryStore.currentTargetEpoch()) {
                        _legacyGamePieceFrame.value = LegacyGamePieceSnapshot(
                            decoded?.values.orEmpty(), legacyRecordedCount, true, timestampUs, fieldArrayEpoch,
                        )
                    }
                } else if (VisionPoseArrayTelemetry.isTopic(normalizedName)) {
                    val decoded = VisionPoseArrayTelemetry.decode(valueElement)
                    lengthTopic = "$normalizedName/Length"
                    activeLength = decoded?.length ?: -1
                    if (fieldArrayEpoch == telemetryStore.currentTargetEpoch()) {
                        val canonical = normalizedName == VisionPoseArrayTelemetry.TOPIC
                        val selected = canonical || !hasCanonicalVisionPoseArray
                        hasReceivedVisionPoseArray = true
                        if (canonical) hasCanonicalVisionPoseArray = true
                        if (!replayActive.value && selected) {
                            _visionPoseArrayFrame.value = VisionPoseArraySnapshot(
                                decoded?.values.orEmpty(), timestampUs, fieldArrayEpoch,
                            )
                        }
                    }
                } else if (normalizedName == "Vision/HasTarget") {
                    val (value, text) = coerceTelemetryValue(valueElement)
                    val hasTarget = text == null && value == 1.0
                    if (!hasTarget) visionLossGeneration++
                    if (!replayActive.value && fieldArrayEpoch == telemetryStore.currentTargetEpoch()) {
                        _visionTargetFrame.value = VisionTargetSnapshot(
                            hasTarget, visionLossGeneration, timestampUs, fieldArrayEpoch,
                        )
                    }
                    if (text != null || value != 1.0) {
                        if (hasReceivedVisionPoseArray) {
                            lengthTopic = (if (hasCanonicalVisionPoseArray) VisionPoseArrayTelemetry.TOPIC
                                else VisionPoseArrayTelemetry.ALIAS) + "/Length"
                            activeLength = 0
                        }
                        if (!replayActive.value && fieldArrayEpoch == telemetryStore.currentTargetEpoch()) {
                            _visionPoseArrayFrame.value = null
                        }
                    }
                }
            }
        }
        return lengthTopic?.let { FieldArrayLengthUpdate(it, activeLength) }
    }
}
