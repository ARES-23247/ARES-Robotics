package com.ares.analytics.service.analysis

import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmpiricalStepSysIdAnalyzerTest {

    @Test
    fun `ols fit recovers kS kV kA and elevator constant kG accurately`() {
        // True plant: V = 0.35*sgn(v) + 2.40*v + 0.18*a + 0.65 (elevator gravity offset)
        val samples = mutableListOf<EmpiricalSysIdSample>()
        for (i in 0 until 240) {
            val t = i * 0.02
            val v = 1.6 * sin(2.0 * PI * 0.45 * t) + 0.4 * cos(2.0 * PI * 1.1 * t)
            if (kotlin.math.abs(v) < 0.08) continue
            val a = 1.6 * (2.0 * PI * 0.45) * cos(2.0 * PI * 0.45 * t) -
                0.4 * (2.0 * PI * 1.1) * sin(2.0 * PI * 1.1 * t)
            val sgn = kotlin.math.sign(v)
            val voltage = 0.35 * sgn + 2.40 * v + 0.18 * a + 0.65
            samples += EmpiricalSysIdSample(
                timestampMs = (i * 20L),
                voltage = voltage,
                velocity = v,
                acceleration = a,
                position = 0.5 + 0.2 * i * 0.02,
            )
        }

        val result = EmpiricalStepSysIdAnalyzer.fitFeedforward(
            samples = samples,
            gravityMode = GravityModelKind.ELEVATOR_CONSTANT,
        )
        assertTrue(result.valid)
        assertEquals(0.35, result.kS, 0.02)
        assertEquals(2.40, result.kV, 0.02)
        assertEquals(0.18, result.kA, 0.02)
        assertEquals(0.65, result.kG, 0.02)
        assertTrue(result.rSquared > 0.995)
    }

    @Test
    fun `ols fit recovers arm cosine gravity kG accurately`() {
        // True arm plant: V = 0.25*sgn(v) + 1.80*v + 0.12*a + 0.90*cos(thetaRad)
        val samples = mutableListOf<EmpiricalSysIdSample>()
        for (i in 0 until 260) {
            val t = i * 0.02
            val theta = -0.6 + 1.4 * (0.5 + 0.5 * sin(2.0 * PI * 0.25 * t))
            val v = 1.2 * cos(2.0 * PI * 0.6 * t)
            if (kotlin.math.abs(v) < 0.08) continue
            val a = -1.2 * (2.0 * PI * 0.6) * sin(2.0 * PI * 0.6 * t)
            val voltage = 0.25 * kotlin.math.sign(v) + 1.80 * v + 0.12 * a + 0.90 * cos(theta)
            samples += EmpiricalSysIdSample(
                timestampMs = i * 20L,
                voltage = voltage,
                velocity = v,
                acceleration = a,
                position = theta,
            )
        }

        val result = EmpiricalStepSysIdAnalyzer.fitFeedforward(
            samples = samples,
            gravityMode = GravityModelKind.ARM_COSINE,
        )
        assertTrue(result.valid)
        assertEquals(0.25, result.kS, 0.02)
        assertEquals(1.80, result.kV, 0.02)
        assertEquals(0.12, result.kA, 0.02)
        assertEquals(0.90, result.kG, 0.03)
        assertTrue(result.rSquared > 0.995)
    }

    @Test
    fun `step response analyzer measures rise time overshoot settling time and steady state error`() {
        // Underdamped second-order step from 0.0 -> 1.0 at t = 200 ms, sampled every 10 ms to 3000 ms
        // wn = 8 rad/s, zeta = 0.4 -> theoretical peak overshoot = exp(-pi*zeta/sqrt(1-zeta^2)) = 25.38%
        // Inject 0.02 steady-state deficit (final value = 0.98)
        val wn = 8.0
        val zeta = 0.4
        val wd = wn * kotlin.math.sqrt(1.0 - zeta * zeta)
        val phi = kotlin.math.acos(zeta)

        val setpoint = mutableListOf<TimedScalarSample>()
        val measured = mutableListOf<TimedScalarSample>()
        for (tMs in 0L..3000L step 10L) {
            val target = if (tMs < 200L) 0.0 else 1.0
            val actual = if (tMs < 200L) {
                0.0
            } else {
                val tau = (tMs - 200L) / 1000.0
                0.98 * (1.0 - (exp(-zeta * wn * tau) / kotlin.math.sqrt(1.0 - zeta * zeta)) * sin(wd * tau + phi))
            }
            setpoint += TimedScalarSample(tMs, target)
            measured += TimedScalarSample(tMs, actual)
        }

        val metrics = EmpiricalStepSysIdAnalyzer.analyzeStepResponse(setpoint, measured)
        assertNotNull(metrics)
        assertTrue(metrics.riseTimeMs in 140.0..240.0, "Expected riseTimeMs around 180ms, got ${metrics.riseTimeMs}")
        assertTrue(metrics.peakOvershootPct in 22.0..28.5, "Expected overshoot ~25.4%, got ${metrics.peakOvershootPct}")
        assertTrue(metrics.settlingTimeMs in 900.0..1500.0, "Expected settling ~1.2s, got ${metrics.settlingTimeMs}")
        assertEquals(0.02, kotlin.math.abs(metrics.steadyStateError), 0.01)
    }

    @Test
    fun `proposal builder matches subsystem tuning parameter declarations safely`() {
        val catalog = listOf(
            TuningParameterDeclaration(
                "arm-ks",
                "Arm/kS",
                "arm.main",
                "Arm kS",
                "Static friction",
                TuningParameterType.DOUBLE,
                minimum = 0.0,
                maximum = 12.0,
                defaultValue = com.areslib.tuning.TuningValue(doubleValue = 0.1),
                applyPolicy = TuningApplyPolicy.LIVE_SAFE,
            ),
            TuningParameterDeclaration(
                "arm-kv",
                "Arm/kV",
                "arm.main",
                "Arm kV",
                "Velocity feedforward",
                TuningParameterType.DOUBLE,
                minimum = 0.0,
                maximum = 12.0,
                defaultValue = com.areslib.tuning.TuningValue(doubleValue = 1.0),
                applyPolicy = TuningApplyPolicy.LIVE_SAFE,
            ),
            TuningParameterDeclaration(
                "arm-kg",
                "Arm/kG",
                "arm.main",
                "Arm kG",
                "Gravity feedforward",
                TuningParameterType.DOUBLE,
                minimum = -12.0,
                maximum = 12.0,
                defaultValue = com.areslib.tuning.TuningValue(doubleValue = 0.0),
                applyPolicy = TuningApplyPolicy.LIVE_SAFE,
            ),
        )
        val fit = EmpiricalFeedforwardFit(
            valid = true,
            kS = 0.28,
            kV = 1.92,
            kA = 0.14,
            kG = 0.84,
            rSquared = 0.994,
            sampleCount = 180,
            gravityMode = GravityModelKind.ARM_COSINE,
            suggestedKp = 3.6,
            suggestedKd = 0.22,
        )
        val proposal = EmpiricalStepSysIdAnalyzer.buildTuningProposal(
            mechanismHint = "Arm",
            fit = fit,
            declarations = catalog,
        )
        assertNotNull(proposal)
        assertEquals(0.28, proposal.values["Arm/kS"] ?: -1.0, 1e-6)
        assertEquals(1.92, proposal.values["Arm/kV"] ?: -1.0, 1e-6)
        assertEquals(0.84, proposal.values["Arm/kG"] ?: -1.0, 1e-6)
    }
}
