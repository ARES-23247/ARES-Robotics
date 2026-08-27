package com.areslib.sim.field

import com.areslib.state.RobotFieldElementInstance
import com.areslib.state.RobotFieldElementType
import org.dyn4j.dynamics.Body
import org.dyn4j.dynamics.BodyFixture
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType

/** Immutable canonical identity and physics carried by a simulated game-piece body. */
data class SimGamePieceMetadata(
    val instanceId: String,
    val instanceKey: Long,
    val typeId: String,
    val typeKey: Long,
    val name: String,
    val shapeCode: Int,
    val widthMeters: Double,
    val heightMeters: Double,
    /** Vertical extent used by 2.5-D projectile simulation. */
    val thicknessMeters: Double,
    val colorRgb: Int,
    val massKg: Double,
    val friction: Double,
    val restitution: Double,
)

/** Creates Dyn4j bodies without dropping canonical piece identity or material properties. */
object SimGamePieceBodyFactory {
    const val SHAPE_CIRCLE = 0
    const val SHAPE_BOX = 1

    fun metadata(type: RobotFieldElementType, instance: RobotFieldElementInstance): SimGamePieceMetadata {
        val circleDiameter = type.diameter ?: type.width
        val isBox = type.shape.equals("box", ignoreCase = true)
        return SimGamePieceMetadata(
            instanceId = instance.id,
            instanceKey = stableTelemetryKey(instance.id),
            typeId = type.id,
            typeKey = stableTelemetryKey(type.id),
            name = instance.name.ifBlank { type.name.ifBlank { instance.id } },
            shapeCode = if (isBox) SHAPE_BOX else SHAPE_CIRCLE,
            widthMeters = if (isBox) type.width else circleDiameter,
            heightMeters = if (isBox) type.height else circleDiameter,
            thicknessMeters = type.height,
            colorRgb = parseRgb(type.color),
            massKg = type.massKg,
            friction = type.friction,
            restitution = type.restitution,
        )
    }

    fun fallback(
        instanceId: String,
        typeId: String = "sim-default-piece",
        diameterMeters: Double = 0.15,
        massKg: Double = 0.24,
    ): SimGamePieceMetadata = SimGamePieceMetadata(
        instanceId = instanceId,
        instanceKey = stableTelemetryKey(instanceId),
        typeId = typeId,
        typeKey = stableTelemetryKey(typeId),
        name = instanceId,
        shapeCode = SHAPE_CIRCLE,
        widthMeters = diameterMeters,
        heightMeters = diameterMeters,
        thicknessMeters = diameterMeters,
        colorRgb = 0x9C27B0,
        massKg = massKg,
        friction = 0.6,
        restitution = 0.3,
    )

    fun createBody(
        metadata: SimGamePieceMetadata,
        x: Double,
        y: Double,
        rotationRadians: Double = 0.0,
    ): Body {
        val shape = when (metadata.shapeCode) {
            SHAPE_BOX -> Geometry.createRectangle(metadata.widthMeters, metadata.heightMeters)
            else -> Geometry.createCircle(metadata.widthMeters / 2.0)
        }
        val fixture = BodyFixture(shape).also {
            it.friction = metadata.friction
            it.restitution = metadata.restitution
            it.density = metadata.massKg / shape.getArea().coerceAtLeast(1e-9)
        }
        return Body().also { body ->
            body.addFixture(fixture)
            body.setMass(MassType.NORMAL)
            body.linearDamping = 2.0
            body.angularDamping = 2.0
            body.translate(x, y)
            if (rotationRadians != 0.0) body.rotate(rotationRadians, x, y)
            body.userData = metadata
        }
    }

    fun metadata(body: Body): SimGamePieceMetadata? = body.userData as? SimGamePieceMetadata

    /** Deterministic positive integer exactly representable by an IEEE-754 double. */
    fun stableTelemetryKey(value: String): Long {
        var hash = 1_125_899_906_842_597L
        for (index in value.indices) hash = 31L * hash + value[index].code
        return (hash and MAX_EXACT_DOUBLE_INTEGER).coerceAtLeast(1L)
    }

    private fun parseRgb(color: String): Int =
        color.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16) ?: 0xFFFFFF

    private const val MAX_EXACT_DOUBLE_INTEGER = 9_007_199_254_740_991L
}

/** Atomic v2 game-piece frame shared by FTC and FRC simulator producers. */
object SimGamePieceTelemetryFrame {
    const val VERSION = 2.0
    const val HEADER_WIDTH = 2
    const val RECORD_WIDTH = 9
    const val SEQUENCE_WIDTH = 1

    const val INSTANCE_KEY = 0
    const val TYPE_KEY = 1
    const val X_METERS = 2
    const val Y_METERS = 3
    const val ROTATION_RADIANS = 4
    const val WIDTH_METERS = 5
    const val HEIGHT_METERS = 6
    const val SHAPE_CODE = 7
    const val COLOR_RGB = 8

    fun requiredSize(count: Int): Int = HEADER_WIDTH + count.coerceAtLeast(0) * RECORD_WIDTH + SEQUENCE_WIDTH

    fun writeBody(buffer: DoubleArray, recordIndex: Int, body: Body) {
        val metadata = SimGamePieceBodyFactory.metadata(body) ?: DEFAULT_METADATA
        write(
            buffer = buffer,
            recordIndex = recordIndex,
            metadata = metadata,
            x = body.transform.translationX,
            y = body.transform.translationY,
            rotationRadians = body.transform.rotationAngle,
        )
    }

    fun write(
        buffer: DoubleArray,
        recordIndex: Int,
        metadata: SimGamePieceMetadata,
        x: Double,
        y: Double,
        rotationRadians: Double,
    ) {
        val base = HEADER_WIDTH + recordIndex * RECORD_WIDTH
        require(base >= HEADER_WIDTH && base + RECORD_WIDTH <= buffer.size) { "Game-piece record does not fit frame" }
        buffer[base + INSTANCE_KEY] = metadata.instanceKey.toDouble()
        buffer[base + TYPE_KEY] = metadata.typeKey.toDouble()
        buffer[base + X_METERS] = x
        buffer[base + Y_METERS] = y
        buffer[base + ROTATION_RADIANS] = rotationRadians
        buffer[base + WIDTH_METERS] = metadata.widthMeters
        buffer[base + HEIGHT_METERS] = metadata.heightMeters
        buffer[base + SHAPE_CODE] = metadata.shapeCode.toDouble()
        buffer[base + COLOR_RGB] = metadata.colorRgb.toDouble()
    }

    fun finish(buffer: DoubleArray, count: Int, sequence: Long) {
        require(buffer.size == requiredSize(count)) { "Game-piece frame has the wrong size for $count records" }
        buffer[0] = VERSION
        buffer[1] = count.toDouble()
        buffer[buffer.lastIndex] = sequence.toDouble()
    }

    private val DEFAULT_METADATA = SimGamePieceBodyFactory.fallback("unidentified-sim-piece")
}
