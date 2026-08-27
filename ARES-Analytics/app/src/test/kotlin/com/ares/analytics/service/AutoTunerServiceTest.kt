package com.ares.analytics.service

import com.areslib.control.assist.SysIdMechanism
import com.ares.analytics.service.tuning.TuningParameterKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.math.sign
import com.ares.analytics.service.tuning.TuningProposalInbox
import com.ares.analytics.service.tuning.ExternalTuningProposal
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertFalse

class AutoTunerServiceTest {
    private lateinit var autoTunerService: AutoTunerService
    private lateinit var mockNt4Service: Nt4ClientService

    @Before
    fun setUp() {
        val tempDb = File.createTempFile("mock_db_tuner", ".sqlite").apply { deleteOnExit() }
        val database = DatabaseService(tempDb.absolutePath)
        mockNt4Service = Nt4ClientService(database)
        autoTunerService = AutoTunerService(mockNt4Service, SysIdService(database))
    }

    @Test
    fun `measured plant produces feedforward and feedback recommendation`() {
        val recommendation = autoTunerService.analyzeSamples(SysIdMechanism.LINEAR, syntheticBidirectionalRun())

        assertNotNull(recommendation)
        assertEquals(0.45, recommendation!!.recommendedkS, 0.15)
        assertEquals(1.8, recommendation.recommendedkV, 0.2)
        assertEquals(0.25, recommendation.recommendedkA, 0.2)
        assertTrue(recommendation.rSquared > 0.9)
        assertTrue(recommendation.recommendedGains.kP > 0.0)
        assertTrue(recommendation.topicValues.containsKey(TuningParameterKeys.DRIVE_FEEDFORWARD_KV))
    }

    @Test
    fun `structured JSONL reads named values rather than timestamps`() {
        val file = File.createTempFile("sample_drive_log", ".jsonl")
        file.writeText(syntheticBidirectionalRun().joinToString("\n") {
            """{"timestampMs":${it.timestampMs},"voltage":${it.voltage},"velocity":${it.velocity},"accel":${it.accel}}"""
        })

        val recommendation = autoTunerService.analyzeLogFile(file)

        assertNotNull(recommendation)
        assertEquals(1.8, recommendation!!.recommendedkV, 0.2)
        file.delete()
    }

    @Test
    fun `approved gains become a review proposal without writing robot topics`() = runBlocking {
        val latestBefore = mockNt4Service.latestValues.toMap()
        val inbox = TuningProposalInbox()
        autoTunerService = AutoTunerService(mockNt4Service, SysIdService(DatabaseService(File.createTempFile("proposal_db", ".sqlite").absolutePath)), inbox)
        val recommendation = autoTunerService.analyzeSamples(SysIdMechanism.LINEAR, syntheticBidirectionalRun())!!
        val proposal = async<ExternalTuningProposal>(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { inbox.proposals.first() } }

        autoTunerService.approveAndApplyGains(recommendation)
        assertEquals(TuningApplyPhase.RECOMMENDED, autoTunerService.applyState.value.phase)
        assertEquals(recommendation.topicValues, proposal.await().values)
        assertEquals("AutoTuner approval must not publish any robot topic", latestBefore, mockNt4Service.latestValues.toMap())
    }

    @Test
    fun `hardware free teaching recommendation cannot enter proposal path`() = runBlocking {
        val inbox = TuningProposalInbox()
        autoTunerService = AutoTunerService(
            mockNt4Service,
            SysIdService(DatabaseService(File.createTempFile("teaching_db", ".sqlite").absolutePath)),
            inbox,
        )
        val teaching = AutoTuningDigitalTwin.teachingScenario(SysIdMechanism.LINEAR)
        val recommendation = autoTunerService.analyzeSamples(
            teaching.plant.mechanism,
            AutoTuningDigitalTwin().generateSamples(teaching),
            "digital-twin:${teaching.name}",
        )!!

        autoTunerService.approveAndApplyGains(recommendation)

        assertEquals(TuningApplyPhase.FAILED, autoTunerService.applyState.value.phase)
        assertTrue(autoTunerService.applyState.value.message.contains("did not measure", ignoreCase = true))
        assertFalse(inbox.proposals.replayCache.isNotEmpty())
        assertTrue(mockNt4Service.latestValues.isEmpty())
    }

    private fun syntheticBidirectionalRun(): List<AlignedDataRow> {
        val rows = ArrayList<AlignedDataRow>(120)
        var previousVelocity = 0.0
        for (i in 0 until 120) {
            val local = if (i < 60) i else i - 60
            val direction = if (i < 60) 1.0 else -1.0
            val velocity = direction * 3.0 * (1.0 - exp(-local / 9.0))
            val accel = if (i == 0 || i == 60) 0.0 else (velocity - previousVelocity) / 0.02
            val voltage = 0.45 * sign(velocity) + 1.8 * velocity + 0.25 * accel
            rows += AlignedDataRow(i * 20L, voltage, velocity, accel)
            previousVelocity = velocity
        }
        return rows
    }
}
