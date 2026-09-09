package com.ares.analytics.service

import com.ares.analytics.shared.models.TransientClassification
import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.control.assist.SysIdMechanism
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.*
import kotlin.test.*

class SysIdAnalysisMathAuditTest {
    private fun test(block: suspend (SysIdService) -> Unit) = runTest {
        val file=File.createTempFile("sysid-math-audit", ".duckdb")
        val db=DatabaseService(file.absolutePath)
        try { block(SysIdService(db)) } finally { db.close();file.delete() }
    }
    private fun rows(vScale: Double=1.0, aScale: Double=1.0, voltageScale: Double=1.0) = List(100) { i ->
        val v=if(i%2==0) 0.5+i*0.02 else -(0.5+i*0.02)
        val a=sin(i*0.7)
        AlignedDataRow(i*20L,voltageScale*(0.4*sign(v)+1.6*v+0.32*a),v*vScale,a*aScale)
    }
    @Test fun `unidentifiable gains are not fabricated by a pseudoinverse`() = test { service ->
        for (data in listOf(List(30) { AlignedDataRow(it*20L,6.0,2.0,0.0) },
            List(30) { val v=1.0+it;AlignedDataRow(it*20L,3*v,v,2*v) })) {
            val result=service.analyzeRawData(data)
            assertEquals(0.0,result.kS);assertEquals(0.0,result.kV);assertEquals(0.0,result.kA)
            assertEquals(0.0,result.rSquared)
        }
    }
    @Test fun `regression remains identifiable across widely different column units`() = test { service ->
        val r=service.analyzeRawData(rows(vScale=1e150,aScale=1e-150))
        assertEquals(0.4,r.kS,1e-8);assertEquals(1.6,r.kV*1e150,1e-8);assertEquals(0.32,r.kA*1e-150,1e-8)
        assertEquals(1.0,r.rSquared,1e-10)
    }
    @Test fun `large finite voltages keep finite gains and goodness of fit`() = test { service ->
        val r=service.analyzeRawData(rows(voltageScale=1e200))
        assertEquals(0.4,r.kS/1e200,1e-8);assertEquals(1.6,r.kV/1e200,1e-8);assertEquals(0.32,r.kA/1e200,1e-8)
        assertEquals(1.0,r.rSquared,1e-10)
    }
    @Test fun `physical gain conversion avoids overflowing an intermediate scale ratio`() = test { service ->
        val r = service.analyzeRawData(rows(aScale=1e-109, voltageScale=1e199))
        assertEquals(0.32, r.kA/1e308, 1e-8)
        assertEquals(1.0, r.rSquared, 1e-10)
    }
    @Test fun `ordinary fit and input rows are preserved`() = test { service ->
        val data=rows();val before=data.toList();val r=service.analyzeRawData(data)
        assertEquals(0.4,r.kS,1e-10);assertEquals(1.6,r.kV,1e-10);assertEquals(0.32,r.kA,1e-10)
        assertEquals(before,data)
    }
    private fun step(direction: Int) = listOf(AlignedDataRow(0,0.0,0.0,0.0)) + List(30) { i ->
        AlignedDataRow((i+1)*20L,12.0*direction,direction*(if(i==5) 1.2 else 1.0),0.0)
    }
    @Test fun `transient classification is independent of input list order`() = test { service ->
        assertEquals(TransientClassification.UNDERDAMPED,service.analyzeRawData(step(1).reversed()).transientClassification)
    }
    @Test fun `negative voltage steps use the same transient classification`() = test { service ->
        assertEquals(TransientClassification.UNDERDAMPED,service.analyzeRawData(step(-1)).transientClassification)
    }
    @Test fun `large finite transient velocities do not overflow tail averaging`() = test { service ->
        val data = step(-1).map { it.copy(velocity=it.velocity*1e308) }
        assertEquals(TransientClassification.UNDERDAMPED, service.analyzeRawData(data).transientClassification)
    }
    @Test fun `a large polarity reversal is not mistaken for a rest to motion step`() = test { service ->
        val data=step(1).toMutableList();data[0]=data[0].copy(voltage=-12.0,velocity=-1.0)
        assertEquals(TransientClassification.UNKNOWN,service.analyzeRawData(data).transientClassification)
    }
    @Test fun `nyquist frequency is included once with correct amplitude`() = test { service ->
        val r=service.performFftAnalysis(DoubleArray(128) { if(it%2==0) 3.0 else -3.0 },128.0)
        assertEquals(65,r.frequencies.size);assertEquals(64.0,r.frequencies.last())
        assertEquals(3.0,r.magnitudes.last(),1e-10);assertEquals(64.0,r.dominantFrequency)
    }
    @Test fun `fft frequencies do not overflow for large finite sample rates`() = test { service ->
        val r=service.performFftAnalysis(DoubleArray(128) { sin(2*PI*it/16) },Double.MAX_VALUE)
        assertTrue(r.frequencies.isNotEmpty());assertTrue(r.frequencies.all { it.isFinite() })
        assertEquals(Double.MAX_VALUE/2,r.frequencies.last())
    }
    @Test fun `large constant signals have zero finite spectral amplitude`() = test { service ->
        for (n in listOf(128, 100, 257)) {
            val r=service.performFftAnalysis(DoubleArray(n) { Double.MAX_VALUE },128.0)
            assertTrue(r.magnitudes.isNotEmpty());assertTrue(r.magnitudes.all { it==0.0 })
            assertEquals(0.0,r.dominantFrequency)
        }
    }
    @Test fun `large sinusoids preserve amplitude without transform overflow`() = test { service ->
        val r=service.performFftAnalysis(DoubleArray(128) { 1e307*sin(2*PI*it/16) },128.0)
        assertTrue(r.magnitudes.all { it.isFinite() });assertEquals(8.0,r.dominantFrequency)
        assertEquals(1.0,r.magnitudes[8]/1e307,0.001)
    }
    @Test fun `fft invalid inputs return no fabricated spectrum`() = test { service ->
        for(rate in listOf(0.0,Double.NaN,Double.POSITIVE_INFINITY)) assertTrue(service.performFftAnalysis(DoubleArray(128),rate).magnitudes.isEmpty())
        assertTrue(service.performFftAnalysis(doubleArrayOf(1.0,Double.NaN,2.0,3.0),100.0).magnitudes.isEmpty())
    }

    @Test fun `computing a recommendation has no publication side effects`() = test { service ->
        val file = File.createTempFile("sysid-publish-audit", ".duckdb")
        val db = DatabaseService(file.absolutePath)
        val client = Nt4ClientService(db)
        try {
            val tuner = AutoTunerService(client, service)
            val analysis = tuner.computeSampleAnalysis(SysIdMechanism.LINEAR, rows())
            assertNull(tuner.currentRecommendation.value)
            assertEquals(TuningApplyPhase.IDLE, tuner.applyState.value.phase)
            val rec = assertNotNull(analysis.recommendation)
            assertEquals(analysis.summary.kV, rec.recommendedkV)
            tuner.publishRecommendation(rec)
            assertEquals(rec, tuner.currentRecommendation.value)
            assertNull(tuner.analyzeSamples(SysIdMechanism.LINEAR, emptyList()))
            assertNull(tuner.currentRecommendation.value)
            assertEquals(TuningApplyPhase.IDLE, tuner.applyState.value.phase)
            val short = tuner.computeSampleAnalysis(SysIdMechanism.LINEAR, rows().take(15))
            assertNull(short.recommendation)
            assertEquals(1.6, short.summary.kV, 1e-10)
        } finally { client.stop(); db.close(); file.delete() }
    }

    @Test fun `database alignment preserves independent samples within a millisecond`() = runTest {
        val file = File.createTempFile("sysid-alignment-audit", ".duckdb")
        val db = DatabaseService(file.absolutePath)
        try {
            val frames = buildList {
                repeat(60) { i ->
                    val t = (i / 2) * 20_000L + if (i % 2 == 0) 100L else 800L
                    val v = 1.0 + i * 0.03
                    val a = sin(i * 0.7)
                    add(TelemetryFrame(t / 1000, "run", "v", v, timestampUs=t))
                    add(TelemetryFrame((t + 10) / 1000, "run", "u", 0.4 + 1.6 * v + 0.32 * a, timestampUs=t + 10))
                    add(TelemetryFrame((t + 10) / 1000, "run", "a", a, timestampUs=t + 10))
                }
            }
            db.insertTelemetryFrames(frames)
            val r = SysIdService(db).analyzeMotorData("run", "u", "v", "a")
            assertEquals(0.4, r.kS, 1e-10); assertEquals(1.6, r.kV, 1e-10); assertEquals(0.32, r.kA, 1e-10)
            assertEquals(1.0, r.rSquared, 1e-10)
        } finally { db.close(); file.delete() }
    }

    @Test fun `alignment rejects samples just beyond fifty milliseconds`() = runTest {
        val file = File.createTempFile("sysid-alignment-limit", ".duckdb")
        val db = DatabaseService(file.absolutePath)
        try {
            for (delta in listOf(50_000L, 50_001L)) {
                val session = delta.toString()
                db.insertTelemetryFrames(buildList {
                    repeat(30) { i ->
                        val t = i * 200_000L + 100_000
                        val v = 1.0 + i * 0.03
                        val a = sin(i * 0.7)
                        add(TelemetryFrame(t / 1000, session, "v", v, timestampUs=t))
                        add(TelemetryFrame((t + delta) / 1000, session, "u", 0.4 + 1.6 * v + 0.32 * a, timestampUs=t + delta))
                        add(TelemetryFrame(t / 1000, session, "a", a, timestampUs=t))
                    }
                })
                val r = SysIdService(db).analyzeMotorData(session, "u", "v", "a")
                assertEquals(if (delta == 50_000L) 1.6 else 0.0, r.kV, 1e-10)
            }
        } finally { db.close(); file.delete() }
    }
}
