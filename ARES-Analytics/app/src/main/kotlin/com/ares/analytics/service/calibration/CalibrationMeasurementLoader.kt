package com.ares.analytics.service.calibration

import com.ares.analytics.service.CalibrationMeasurement
import com.ares.analytics.shared.models.TelemetryFrame

private val COMPONENT_TOPIC = Regex("Calibration/(CameraToTag|TagField)/(0|[1-9][0-9]*)(?:/(0|[1-9][0-9]*))?")
private val METADATA_TOPICS = setOf("Calibration/GyroHeading", "Calibration/CameraIndex", "Calibration/TagIndex", "Calibration/IsActive")

internal fun calibrationTopicKeys(cameraIndex: Int): List<String> = buildList {
    require(cameraIndex >= 0)
    addAll(METADATA_TOPICS)
    for (root in listOf("Calibration/CameraToTag", "Calibration/TagField")) for (component in 0..2) {
        add("$root/$component")
        add("$root/$cameraIndex/$component")
    }
}

/** One classification pass; exact topic grammar, camera identity and bounded sample skew. */
internal fun calibrationMeasurements(frames: List<TelemetryFrame>, cameraIndex: Int): List<CalibrationMeasurement> {
    require(cameraIndex >= 0) { "Camera index must be nonnegative" }
    val streams = mutableMapOf<String, MutableList<TelemetryFrame>>()
    for (frame in frames) {
        val key = frame.key.trimStart('/')
        val accepted = if (key in METADATA_TOPICS) true else {
            val match = COMPONENT_TOPIC.matchEntire(key)
            val first = match?.groupValues?.get(2)?.toIntOrNull()
            val second = match?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            if (match?.groupValues?.get(3).isNullOrEmpty()) first != null && first in 0..2
            else first == cameraIndex && second != null && second in 0..2
        }
        if (accepted) streams.getOrPut(key) { mutableListOf() }.add(frame)
    }
    streams.values.forEach { it.sortWith(compareBy<TelemetryFrame> { frame -> frame.timestampUs }.thenBy { frame -> frame.sampleOrder }) }
    fun components(root: String): Array<List<TelemetryFrame>> {
        // Select a complete namespace. Never fill missing scoped components from another camera's generic stream.
        val scoped = (0..2).any { streams.containsKey("$root/$cameraIndex/$it") }
        return Array(3) { streams[if (scoped) "$root/$cameraIndex/$it" else "$root/$it"] ?: emptyList() }
    }
    val target = components("Calibration/CameraToTag")
    val tag = components("Calibration/TagField")
    val gyro = streams["Calibration/GyroHeading"] ?: return emptyList()
    val cameras = streams["Calibration/CameraIndex"] ?: return emptyList()
    val tags = streams["Calibration/TagIndex"] ?: return emptyList()
    val active = streams["Calibration/IsActive"]
    if (target.any { it.isEmpty() } || tag.any { it.isEmpty() }) return emptyList()
    return buildList {
        for (frame in tags) {
            val t = frame.timestampUs
            if (exactIndex(nearest(cameras, t)?.numericValue()) != cameraIndex) continue
            if (active != null && !isActive(nearest(active, t))) continue
            val id = exactIndex(frame.numericValue()) ?: continue
            val heading = nearest(gyro, t)?.numericValue() ?: continue
            val p = DoubleArray(3) { nearest(target[it], t)?.numericValue() ?: Double.NaN }
            val f = DoubleArray(3) { nearest(tag[it], t)?.numericValue() ?: Double.NaN }
            if (!heading.isFinite() || p.any { !it.isFinite() } || f.any { !it.isFinite() }) continue
            add(CalibrationMeasurement(heading, id, f[0], f[1], f[2], p[0], p[1], p[2], 0.0, 0.0, 0.0))
        }
    }
}

private fun exactIndex(value: Double?): Int? {
    if (value == null || !value.isFinite() || value < 0.0 || value > Int.MAX_VALUE.toDouble()) return null
    return value.toInt().takeIf { it.toDouble() == value }
}

private fun TelemetryFrame.numericValue(): Double? = value.takeIf { stringValue == null }

private fun isActive(frame: TelemetryFrame?): Boolean = when (frame?.stringValue) {
    "true" -> true
    null -> frame?.value == 1.0
    else -> false
}

/** Ties prefer the earlier sample. TelemetryFrame validates source timestamps as nonnegative. */
private fun nearest(frames: List<TelemetryFrame>, time: Long): TelemetryFrame? {
    var low = 0
    var high = frames.size
    while (low < high) {
        val mid = low + (high - low) / 2
        if (frames[mid].timestampUs <= time) low = mid + 1 else high = mid
    }
    var best: TelemetryFrame? = null
    var distance = Long.MAX_VALUE
    for (index in low - 1..low) if (index in frames.indices) {
        val frame = frames[index]
        val delta = kotlin.math.abs(frame.timestampUs - time)
        if (best == null || delta < distance) { best = frame; distance = delta }
    }
    return if (distance <= 100_000L) best else null
}
