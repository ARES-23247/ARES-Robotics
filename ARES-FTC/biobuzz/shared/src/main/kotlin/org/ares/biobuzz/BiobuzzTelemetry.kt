package org.ares.biobuzz

import com.google.gson.Gson
import com.google.gson.JsonParser

/** One complete season snapshot; meters, kilograms and CCW radians. Published at 10 Hz. */
data class BiobuzzFrame(val version: Int, val sequence: Long, val fieldId: String,
    val fieldRevision: Int, val state: BiobuzzSnapshot)

internal object BiobuzzTelemetry {
    const val TOPIC = "ARES/BIOBUZZ/State"
    private val gson = Gson()
    fun encode(frame: BiobuzzFrame): String = gson.toJson(frame)

    /** Bound before parsing and fail closed on missing, invalid or unsupported wire data. */
    fun decode(json: String?): BiobuzzFrame? = runCatching {
        require(json != null && json.length <= 64_000)
        val tree = JsonParser.parseString(json).asJsonObject
        require(tree["version"].asDouble == 1.0 && tree.has("state") && tree.has("fieldId") && tree.has("sequence") && tree.has("fieldRevision"))
        val frame = gson.fromJson(tree, BiobuzzFrame::class.java)
        require(frame.sequence >= 0 && frame.fieldId.isNotBlank())
        val s = frame.state
        require(listOf(s.x,s.y,s.heading).all(Double::isFinite))
        require(s.inventory.size <= 4 && s.balls.size <= 256 && s.flowers.size == 4 && s.hives.size == 2)
        require(s.inventory.all { it in BallKind.entries })
        require(s.balls.map { it.id }.distinct().size == s.balls.size)
        for (b in s.balls) {
            require(b.id.isNotBlank() && b.kind in BallKind.entries && b.location in BallLocation.entries)
            require(listOf(b.x,b.y,b.z).all(Double::isFinite) && b.z >= 0)
        }
        for (f in s.flowers) require(f.x.isFinite() && f.y.isFinite() && f.contents.size <= 32 && f.contents.all { it in BallKind.entries })
        for (h in s.hives) require(h.x.isFinite() && h.y.isFinite() && h.angle.isFinite() &&
            h.upward in 0..1 && h.tips >= 0 && h.cells.size == 2 && h.cells.all { c -> c.size <= 32 && c.all { it in BallKind.entries } })
        require(s.redReserve in 0..256 && s.blueReserve in 0..256)
        frame
    }.getOrNull()
}
