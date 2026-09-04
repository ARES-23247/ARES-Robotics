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
    val maxY: Double
)

/** Exact legal center bounds for a rectangular robot at [headingRadians]. */
fun legalCenterBounds(
    league: League,
    dimensions: RobotDimensions,
    headingRadians: Double
): AutoCenterBounds {
    val robot = dimensions.normalized()
    val heading = headingRadians.takeIf(Double::isFinite) ?: 0.0
    val halfLength = robot.lengthMeters / 2.0
    val halfWidth = robot.widthMeters / 2.0
    val projectedX = abs(cos(heading)) * halfLength + abs(sin(heading)) * halfWidth
    val projectedY = abs(sin(heading)) * halfLength + abs(cos(heading)) * halfWidth

    val (xBounds, yBounds) = when (league) {
        League.FTC -> {
            val halfField = CoordinateTransformers.FTC_FIELD_SIZE / 2.0
            boundedOrCentered(-halfField + projectedX, halfField - projectedX, 0.0) to
                boundedOrCentered(-halfField + projectedY, halfField - projectedY, 0.0)
        }
        League.FRC -> boundedOrCentered(
            projectedX,
            CoordinateTransformers.FRC_FIELD_LENGTH - projectedX,
            CoordinateTransformers.FRC_FIELD_LENGTH / 2.0,
        ) to boundedOrCentered(
            projectedY,
            CoordinateTransformers.FRC_FIELD_WIDTH - projectedY,
            CoordinateTransformers.FRC_FIELD_WIDTH / 2.0,
        )
        League.XRP -> {
            val halfLength = XRP_FIELD_LENGTH_METERS / 2.0
            val halfWidth = XRP_FIELD_WIDTH_METERS / 2.0
            boundedOrCentered(-halfLength + projectedX, halfLength - projectedX, 0.0) to
                boundedOrCentered(-halfWidth + projectedY, halfWidth - projectedY, 0.0)
        }
    }
    return AutoCenterBounds(xBounds.first, xBounds.second, yBounds.first, yBounds.second)
}

private fun boundedOrCentered(min: Double, max: Double, center: Double): Pair<Double, Double> =
    if (min <= max) min to max else center to center

private const val XRP_FIELD_LENGTH_METERS = 2.54
private const val XRP_FIELD_WIDTH_METERS = 1.4224
