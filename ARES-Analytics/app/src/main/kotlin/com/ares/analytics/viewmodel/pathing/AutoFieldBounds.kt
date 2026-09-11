package com.ares.analytics.viewmodel.pathing

import com.ares.analytics.shared.models.League
import com.areslib.math.coordinate.CoordinateTransformers
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Physical bumper-to-bumper footprint used by the auto editor, in meters. */
data class RobotDimensions(
    val lengthMeters: Double,
    val widthMeters: Double
) {
    fun normalized(): RobotDimensions = RobotDimensions(
        lengthMeters = lengthMeters.takeIf(Double::isFinite)?.coerceIn(MIN_SIZE_METERS, MAX_SIZE_METERS)
            ?: DEFAULT_FTC_SIZE_METERS,
        widthMeters = widthMeters.takeIf(Double::isFinite)?.coerceIn(MIN_SIZE_METERS, MAX_SIZE_METERS)
            ?: DEFAULT_FTC_SIZE_METERS
    )

    companion object {
        const val MIN_SIZE_METERS = 0.10
        const val MAX_SIZE_METERS = 2.00
        const val DEFAULT_FTC_SIZE_METERS = 0.4572
        const val DEFAULT_FRC_SIZE_METERS = 0.80
        const val DEFAULT_XRP_SIZE_METERS = 0.16

        fun defaultFor(league: League): RobotDimensions {
            val size = when (league) {
                League.FTC -> DEFAULT_FTC_SIZE_METERS
                League.FRC -> DEFAULT_FRC_SIZE_METERS
                League.XRP -> DEFAULT_XRP_SIZE_METERS
            }
            return RobotDimensions(size, size)
        }
    }
}

data class AutoCenterBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    /** False when no center can keep this footprint inside the field. */
    val canFit: Boolean = true
)

/** Center bounds for a normalized robot; impossible axes use a centered editor fallback and [AutoCenterBounds.canFit] is false. */
fun legalCenterBounds(
    league: League,
    dimensions: RobotDimensions,
    headingRadians: Double
): AutoCenterBounds {
    val robot = dimensions.normalized()
    val heading = headingRadians.takeIf(Double::isFinite) ?: 0.0
    val halfLength = robot.lengthMeters / 2.0
    val halfWidth = robot.widthMeters / 2.0
    val c = abs(cos(heading))
    val s = abs(sin(heading))
    val projectedX = c * halfLength + s * halfWidth
    val projectedY = s * halfLength + c * halfWidth
    val field = when (league) {
        League.FTC -> {
            val halfField = CoordinateTransformers.FTC_FIELD_SIZE / 2.0
            AutoCenterBounds(-halfField, halfField, -halfField, halfField)
        }
        League.FRC -> AutoCenterBounds(0.0, CoordinateTransformers.FRC_FIELD_LENGTH,
            0.0, CoordinateTransformers.FRC_FIELD_WIDTH)
        League.XRP -> AutoCenterBounds(-XRP_FIELD_LENGTH_METERS / 2.0, XRP_FIELD_LENGTH_METERS / 2.0,
            -XRP_FIELD_WIDTH_METERS / 2.0, XRP_FIELD_WIDTH_METERS / 2.0)
    }
    val minX = field.minX + projectedX
    val maxX = field.maxX - projectedX
    val minY = field.minY + projectedY
    val maxY = field.maxY - projectedY
    val centerX = (field.minX + field.maxX) / 2.0
    val centerY = (field.minY + field.maxY) / 2.0
    return AutoCenterBounds(
        if (minX <= maxX) minX else centerX,
        if (minX <= maxX) maxX else centerX,
        if (minY <= maxY) minY else centerY,
        if (minY <= maxY) maxY else centerY,
        canFit = minX <= maxX && minY <= maxY,
    )
}

private const val XRP_FIELD_LENGTH_METERS = 2.54
private const val XRP_FIELD_WIDTH_METERS = 1.4224
