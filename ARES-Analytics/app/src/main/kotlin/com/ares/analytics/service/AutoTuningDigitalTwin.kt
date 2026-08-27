package com.ares.analytics.service

import com.areslib.control.assist.SysIdMechanism
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

data class DigitalTwinPlant(
    val mechanism: SysIdMechanism,
    val kS: Double,
    val kV: Double,
    val kA: Double,
    val stepVoltage: Double,
    val samplePeriodMs: Long = 20L,
    val sampleCount: Int = 200
)

data class DigitalTwinDisturbance(
    val voltageNoiseStd: Double = 0.0,
    val velocityNoiseStd: Double = 0.0,
    val accelerationNoiseStd: Double = 0.0,
    val droppedSampleProbability: Double = 0.0,
    val outlierProbability: Double = 0.0,
    val sensorLatencySamples: Int = 0,
    val timestampJitterMs: Int = 0
)

data class DigitalTwinScenario(
    val name: String,
    val plant: DigitalTwinPlant,
    val disturbance: DigitalTwinDisturbance = DigitalTwinDisturbance(),
    val seed: Int = 0
)

data class ClosedLoopPrediction(
    val stable: Boolean,
    val percentOvershoot: Double,
    val settlingTimeMs: Double,
    val steadyStateError: Double,
    val peakVoltage: Double
)

data class DigitalTwinEvaluation(
    val scenario: DigitalTwinScenario,
    val recommendation: AutoTunerService.TuningRecommendation?,
    val kSRelativeError: Double,
    val kVRelativeError: Double,
    val kARelativeError: Double,
    val closedLoop: ClosedLoopPrediction?
) {
    val recoveredWithinTolerance: Boolean
        get() = recommendation != null && kSRelativeError <= 0.35 && kVRelativeError <= 0.20 && kARelativeError <= 0.40
}

data class MonteCarloEvaluationSummary(
    val cases: Int,
    val recommendationsProduced: Int,
    val readyOrReviewable: Int,
    val recoveredWithinTolerance: Int,
    val stableClosedLoops: Int,
    val unsafeRecommendations: Int,
    val evaluations: List<DigitalTwinEvaluation>
)

/** Deterministic robotless plant generator and closed-loop recommendation evaluator. */
class AutoTuningDigitalTwin {
    fun generateSamples(scenario: DigitalTwinScenario): List<AlignedDataRow> {
        val plant = scenario.plant
        require(plant.kS >= 0.0 && plant.kV > 0.0 && plant.kA > 0.0)
        require(plant.sampleCount >= 40 && plant.samplePeriodMs > 0L)
        val disturbance = scenario.disturbance
        val random = Random(scenario.seed)
        val dt = plant.samplePeriodMs / 1000.0
        val trueVelocity = DoubleArray(plant.sampleCount)
        val trueAcceleration = DoubleArray(plant.sampleCount)
        val trueVoltage = DoubleArray(plant.sampleCount)
        var velocity = 0.0

        for (i in 0 until plant.sampleCount) {
            val voltage = when (plant.mechanism) {
                SysIdMechanism.FLYWHEEL -> flywheelExcitationVoltage(i, plant.sampleCount, plant.stepVoltage)
                else -> if (i < plant.sampleCount / 2) plant.stepVoltage else -plant.stepVoltage
            }
            val direction = when {
                abs(velocity) > 1e-8 -> sign(velocity)
                abs(voltage) > plant.kS -> sign(voltage)
                else -> 0.0
            }
            val acceleration = if (direction == 0.0) 0.0
                else (voltage - plant.kS * direction - plant.kV * velocity) / plant.kA
            trueVoltage[i] = voltage
            trueVelocity[i] = velocity
            trueAcceleration[i] = acceleration
            velocity += acceleration * dt
        }

        val rows = ArrayList<AlignedDataRow>(plant.sampleCount)
        var timestamp = 0L
        for (i in 0 until plant.sampleCount) {
            val periodJitter = if (disturbance.timestampJitterMs <= 0) 0
                else random.nextInt(-disturbance.timestampJitterMs, disturbance.timestampJitterMs + 1)
            timestamp += max(1L, plant.samplePeriodMs + periodJitter)
            if (random.nextDouble() < disturbance.droppedSampleProbability) continue
            val delayedIndex = (i - disturbance.sensorLatencySamples).coerceAtLeast(0)
            val outlierScale = if (random.nextDouble() < disturbance.outlierProbability) 8.0 else 1.0
            rows += AlignedDataRow(
                timestampMs = timestamp,
                voltage = trueVoltage[i] + gaussian(random) * disturbance.voltageNoiseStd * outlierScale,
                velocity = trueVelocity[delayedIndex] + gaussian(random) * disturbance.velocityNoiseStd * outlierScale,
                accel = trueAcceleration[delayedIndex] + gaussian(random) * disturbance.accelerationNoiseStd * outlierScale
            )
        }
        return rows
    }

    fun evaluate(
        scenario: DigitalTwinScenario,
        analyzer: (SysIdMechanism, List<AlignedDataRow>, String) -> AutoTunerService.TuningRecommendation?
    ): DigitalTwinEvaluation {
        val recommendation = analyzer(scenario.plant.mechanism, generateSamples(scenario), "digital-twin:${scenario.name}")
        val closedLoop = recommendation?.let { predictClosedLoop(scenario.plant, it) }
        return DigitalTwinEvaluation(
            scenario = scenario,
            recommendation = recommendation,
            kSRelativeError = relativeError(recommendation?.recommendedkS, scenario.plant.kS),
            kVRelativeError = relativeError(recommendation?.recommendedkV, scenario.plant.kV),
            kARelativeError = relativeError(recommendation?.recommendedkA, scenario.plant.kA),
            closedLoop = closedLoop
        )
    }

    fun runMonteCarlo(
        scenarios: List<DigitalTwinScenario>,
        analyzer: (SysIdMechanism, List<AlignedDataRow>, String) -> AutoTunerService.TuningRecommendation?
    ): MonteCarloEvaluationSummary {
        val evaluations = scenarios.map { evaluate(it, analyzer) }
        return MonteCarloEvaluationSummary(
            cases = evaluations.size,
            recommendationsProduced = evaluations.count { it.recommendation != null },
            readyOrReviewable = evaluations.count {
                it.recommendation?.quality == RecommendationQuality.READY ||
                    it.recommendation?.quality == RecommendationQuality.REVIEW_REQUIRED
            },
            recoveredWithinTolerance = evaluations.count { it.recoveredWithinTolerance },
            stableClosedLoops = evaluations.count { it.closedLoop?.stable == true },
            unsafeRecommendations = evaluations.count { evaluation ->
                val recommendation = evaluation.recommendation ?: return@count false
                recommendation.safetyEnvelope.violations(
                    recommendation.recommendedkS,
                    recommendation.recommendedkV,
                    recommendation.recommendedkA,
                    recommendation.recommendedGains
                ).isNotEmpty() && recommendation.quality != RecommendationQuality.REJECTED
            },
            evaluations = evaluations
        )
    }

    fun predictClosedLoop(
        plant: DigitalTwinPlant,
        recommendation: AutoTunerService.TuningRecommendation,
        durationSeconds: Double = 5.0
    ): ClosedLoopPrediction {
        val dt = 0.01
        val steps = (durationSeconds / dt).toInt()
        val target = when (plant.mechanism) {
            SysIdMechanism.LINEAR, SysIdMechanism.ELEVATOR -> 1.5
            SysIdMechanism.ANGULAR, SysIdMechanism.ARM -> 3.0
            SysIdMechanism.FLYWHEEL, SysIdMechanism.CUSTOM -> 120.0
        }
        var velocity = 0.0
        var integral = 0.0
        var previousError = target
        var peakVelocity = 0.0
        var peakVoltage = 0.0
        var lastOutsideTolerance = 0
        var stable = true
        for (i in 0 until steps) {
            val error = target - velocity
            integral = (integral + error * dt).coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT)
            val derivative = (error - previousError) / dt
            previousError = error
            val gains = recommendation.recommendedGains
            val feedforward = recommendation.recommendedkS + recommendation.recommendedkV * target
            val voltage = (feedforward + gains.kP * error + gains.kI * integral + gains.kD * derivative)
                .coerceIn(-12.0, 12.0)
            val direction = when {
                abs(velocity) > 1e-8 -> sign(velocity)
                abs(voltage) > plant.kS -> sign(voltage)
                else -> 0.0
            }
            val acceleration = if (direction == 0.0) 0.0
                else (voltage - plant.kS * direction - plant.kV * velocity) / plant.kA
            velocity += acceleration * dt
            peakVelocity = max(peakVelocity, velocity)
            peakVoltage = max(peakVoltage, abs(voltage))
            if (abs(error) > target * 0.05) lastOutsideTolerance = i
            if (!velocity.isFinite() || abs(velocity) > target * 5.0) {
                stable = false
                break
            }
        }
        val steadyStateError = abs(target - velocity)
        val overshoot = max(0.0, (peakVelocity - target) / target * 100.0)
        val settled = lastOutsideTolerance < steps - (0.5 / dt).toInt()
        return ClosedLoopPrediction(
            stable = stable && settled && steadyStateError <= target * 0.05 && overshoot <= 35.0,
            percentOvershoot = overshoot,
            settlingTimeMs = lastOutsideTolerance * dt * 1000.0,
            steadyStateError = steadyStateError,
            peakVoltage = peakVoltage
        )
    }

    companion object {
        /**
         * Stable, noise-free mechanism used by the novice SysId walkthrough before any robot can
         * be armed. The constants are teaching fixtures, never estimates for the student's robot.
         */
        fun teachingScenario(mechanism: SysIdMechanism): DigitalTwinScenario {
            val plant = when (mechanism) {
                SysIdMechanism.LINEAR, SysIdMechanism.ELEVATOR ->
                    DigitalTwinPlant(mechanism, kS = 0.35, kV = 1.8, kA = 0.45, stepVoltage = 6.0)
                SysIdMechanism.ANGULAR, SysIdMechanism.ARM ->
                    DigitalTwinPlant(mechanism, kS = 0.25, kV = 1.2, kA = 0.35, stepVoltage = 5.0)
                SysIdMechanism.FLYWHEEL, SysIdMechanism.CUSTOM ->
                    DigitalTwinPlant(mechanism, kS = 0.25, kV = 0.05, kA = 0.015, stepVoltage = 7.0)
            }
            return DigitalTwinScenario(
                name = "teaching-${mechanism.name.lowercase()}",
                plant = plant,
                seed = 23_247,
            )
        }

        /**
         * A one-direction constant step is rank deficient for V = kS + kV*v + kA*a.
         * Combine a quasistatic ramp, coast-down, and dynamic step so all three terms
         * are independently observable without commanding a flywheel in reverse.
         */
        private fun flywheelExcitationVoltage(index: Int, sampleCount: Int, stepVoltage: Double): Double {
            val warmupEnd = sampleCount / 10
            val rampEnd = sampleCount * 4 / 10
            val holdEnd = sampleCount * 5 / 10
            val coastEnd = sampleCount * 6 / 10
            return when {
                index < warmupEnd -> 0.0
                index < rampEnd -> {
                    val progress = (index - warmupEnd).toDouble() / (rampEnd - warmupEnd).coerceAtLeast(1)
                    stepVoltage * 0.6 * progress
                }
                index < holdEnd -> stepVoltage * 0.6
                index < coastEnd -> 0.0
                else -> stepVoltage
            }
        }

        fun standardMonteCarloSuite(seed: Int, cases: Int): List<DigitalTwinScenario> {
            val random = Random(seed)
            return List(cases) { index ->
                val mechanism = SysIdMechanism.entries[index % SysIdMechanism.entries.size]
                val plant = when (mechanism) {
                    SysIdMechanism.LINEAR, SysIdMechanism.ELEVATOR -> DigitalTwinPlant(
                        mechanism, random.nextDouble(0.15, 0.8), random.nextDouble(1.0, 2.8),
                        random.nextDouble(0.25, 0.75), random.nextDouble(5.0, 8.0)
                    )
                    SysIdMechanism.ANGULAR, SysIdMechanism.ARM -> DigitalTwinPlant(
                        mechanism, random.nextDouble(0.1, 0.7), random.nextDouble(0.6, 2.2),
                        random.nextDouble(0.2, 0.65), random.nextDouble(4.0, 7.0)
                    )
                    SysIdMechanism.FLYWHEEL, SysIdMechanism.CUSTOM -> DigitalTwinPlant(
                        mechanism, random.nextDouble(0.1, 0.6), random.nextDouble(0.025, 0.075),
                        random.nextDouble(0.006, 0.025), random.nextDouble(6.0, 9.0)
                    )
                }
                DigitalTwinScenario(
                    name = "mc-$index-${mechanism.name.lowercase()}",
                    plant = plant,
                    disturbance = DigitalTwinDisturbance(
                        voltageNoiseStd = random.nextDouble(0.0, 0.025),
                        velocityNoiseStd = random.nextDouble(0.0, if (mechanism == SysIdMechanism.FLYWHEEL) 0.20 else 0.015),
                        accelerationNoiseStd = random.nextDouble(0.0, if (mechanism == SysIdMechanism.FLYWHEEL) 0.8 else 0.08),
                        droppedSampleProbability = random.nextDouble(0.0, 0.015),
                        outlierProbability = random.nextDouble(0.0, 0.003),
                        sensorLatencySamples = random.nextInt(0, 2),
                        timestampJitterMs = random.nextInt(0, 3)
                    ),
                    seed = seed * 10_000 + index
                )
            }
        }

        private fun gaussian(random: Random): Double {
            val u1 = random.nextDouble().coerceAtLeast(1e-12)
            val u2 = random.nextDouble()
            return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
        }

        private fun relativeError(actual: Double?, expected: Double): Double {
            if (actual == null || !actual.isFinite()) return Double.POSITIVE_INFINITY
            return abs(actual - expected) / max(abs(expected), 1e-9)
        }

        private const val INTEGRAL_LIMIT = 100.0
    }
}
