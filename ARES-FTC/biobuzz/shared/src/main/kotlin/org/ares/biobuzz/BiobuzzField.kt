package org.ares.biobuzz

import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument

/** Season geometry in ARES meters, +X up the field, +Y left, CCW radians. */
internal object BiobuzzField {
    const val RESOURCE = "field-presets/ftc/2026-2027-biobuzz.json"
    const val SIZE = 3.6576
    const val FLOWER_MOUTH_HEIGHT = 0.5461
    const val FLOWER_MOUTH_RADIUS = 0.0508
    const val FLOWER_RETRIEVAL_HEIGHT = 0.09017
    const val HIVE_PIVOT_HEIGHT = 1.11633
    const val CELL_OFFSET = 0.39116
    const val CELL_DEPTH = 0.3058
    const val CELL_WIDTH = 0.508
    const val STABLE_ANGLE = Math.PI / 6.0

    fun document(): RobotFieldConfig = requireNotNull(javaClass.classLoader.getResourceAsStream(RESOURCE)) {
        "Bundled BIOBUZZ field is missing"
    }.bufferedReader().use { RobotFieldDocument.decode(it.readText()) }
}

enum class BallKind(val typeId: String, val diameter: Double, val color: Int, val massKg: Double) {
    POLLEN("biobuzz-pollen", 0.07112, 0xF4CF38, 0.055 * 0.45359237),
    RED_NECTAR("biobuzz-red-nectar", 0.09144, 0xEF5350, 0.091 * 0.45359237),
    BLUE_NECTAR("biobuzz-blue-nectar", 0.09144, 0x4285F4, 0.091 * 0.45359237),
}

enum class BallLocation { FLOOR, AIR, ROBOT, FLOWER, HIVE, RESERVE, OUT_OF_PLAY }

data class BallView(
    val id: String, val kind: BallKind, val x: Double, val y: Double, val z: Double,
    val location: BallLocation, val container: Int = -1,
)

data class FlowerView(val x: Double, val y: Double, val contents: List<BallKind>)
data class HiveView(
    val x: Double, val y: Double, val red: Boolean, val angle: Double, val upward: Int,
    val tipping: Boolean, val tips: Int, val cells: List<List<BallKind>>,
) {
    val contents: List<BallKind> get() = cells[upward]
}

/** Detached immutable presentation state; a 3-D renderer can consume the same positions/angles. */
data class BiobuzzSnapshot(
    val x: Double, val y: Double, val heading: Double, val enabled: Boolean,
    val inventory: List<BallKind>, val balls: List<BallView>,
    val flowers: List<FlowerView>, val hives: List<HiveView>,
    val redReserve: Int, val blueReserve: Int,
)

data class BiobuzzControl(
    val enabled: Boolean = false, val intake: Boolean = false, val shoot: Boolean = false,
    val speed: Double = 5.8, val elevation: Double = Math.PI / 3,
)
