package com.ares.analytics.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in wall-clock soak of the real Analytics NT4 sender against an already-running FTC simulator.
 *
 * The dedicated Gradle `simulatorControlSoak` task makes the simulator mandatory. Ordinary unit
 * test runs return immediately so a developer without port 5810 does not receive a false failure.
 */
class SimulatorControlSoakTest {

    private data class ReceivedPose(val receivedAtNs: Long, val frame: SimulatorPoseFrameSnapshot)

    @Test
    fun `leased dashboard control remains responsive while raw telemetry is ingested`() = runBlocking {
        if (!booleanProperty("ares.simSoak.required", false)) return@runBlocking

        val durationSeconds = intProperty("ares.simSoak.seconds", 60).coerceAtLeast(10)
        val host = System.getProperty("ares.simSoak.host", "127.0.0.1")
        val port = intProperty("ares.simSoak.port", 5810)
        val opMode = System.getProperty(
            "ares.simSoak.opMode",
            "org.firstinspires.ftc.teamcode.opmodes.ARESStarterTeleOp"
        )
        val tempDirectory = kotlin.io.path.createTempDirectory("ares-simulator-control-soak").toFile()
        val database = DatabaseService(File(tempDirectory, "soak.duckdb").absolutePath)
        val client = Nt4ClientService(database)
        var collector: Job? = null
        try {
            client.start(host, "23247", "2026", "soak", port)
            withTimeout(15_000L) { client.isConnected.filter { it }.first() }

            // One collector owns appends; the test cancels and joins it before reading. A
            // CopyOnWriteArrayList here turns an hour at 50 Hz into O(n^2) array copying and can
            // manufacture the very scheduler stalls this test is intended to detect.
            val poses = ArrayList<ReceivedPose>(durationSeconds * 50 + 1_000)
            collector = launch {
                client.simulatorPoseFrame.filterNotNull().collect { frame ->
                    poses += ReceivedPose(System.nanoTime(), frame)
                }
            }

            // Connection state means the WebSocket is live; one-shot publication waits for the
            // clock/publisher handshake. Require accepted sends and observable lifecycle states so
            // INIT cannot race ahead of SelectedOpMode registration.
            withTimeout(15_000L) {
                while (!client.publishString("ARES/DriverStation/SelectedOpMode", opMode)) {
                    delay(100L)
                }
            }
            delay(100L)
            withTimeout(15_000L) {
                while (!client.publishString("ARES/DriverStation/Command", "STOP")) {
                    delay(100L)
                }
            }
            delay(100L)
            withTimeout(15_000L) {
                while (client.latestValues["ARES/DriverStation/ActiveOpModeState"]?.stringValue != "TELEOP_INIT") {
                    client.publishString("ARES/DriverStation/Command", "INIT")
                    delay(100L)
                }
            }
            withTimeout(15_000L) {
                while (client.latestValues["ARES/DriverStation/ActiveOpModeState"]?.stringValue != "TELEOP_RUNNING") {
                    client.publishString("ARES/DriverStation/Command", "START")
                    delay(100L)
                }
            }

            val frame = DoubleArray(8)
            val sessionNonce = client.nextDriveSessionNonce()
            var sequence = 0L
            var previousSendNs = Long.MIN_VALUE
            var maximumSendGapNs = 0L
            var failedPublishes = 0L

            suspend fun publish(vx: Double, vy: Double, omega: Double) {
                val nowNs = System.nanoTime()
                if (previousSendNs != Long.MIN_VALUE) {
                    maximumSendGapNs = maxOf(maximumSendGapNs, nowNs - previousSendNs)
                }
                previousSendNs = nowNs
                frame[0] = 2.0
                frame[1] = sessionNonce
                frame[2] = sequence++.toDouble()
                frame[3] = (nowNs / 1_000_000L).toDouble()
                frame[4] = vx
                frame[5] = vy
                frame[6] = omega
                frame[7] = 56.0 // TeleOp + field-centric + Red; no actuator or edge flags.
                if (!client.publishDriveFrame(frame)) failedPublishes++
            }

            repeat(6) {
                publish(0.0, 0.0, 0.0)
                delay(20L)
            }

            val motionStartedNs = System.nanoTime()
            val motionDeadlineNs = motionStartedNs + durationSeconds * 1_000_000_000L
            var nextPublishDeadlineNs = motionStartedNs
            var tick = 0
            while (System.nanoTime() < motionDeadlineNs) {
                when ((tick / 250) % 4) {
                    0 -> publish(1.2, 0.4, 0.8)
                    1 -> publish(-0.4, 1.2, -0.7)
                    2 -> publish(-1.2, -0.4, 0.9)
                    else -> publish(0.4, -1.2, -0.8)
                }
                tick++
                nextPublishDeadlineNs += CONTROL_PERIOD_NS
                val remainingNs = nextPublishDeadlineNs - System.nanoTime()
                if (remainingNs > 0L) {
                    // Round up so an early millisecond wake-up cannot create a >50 Hz burst.
                    delay((remainingNs + 999_999L) / 1_000_000L)
                } else {
                    // Do not emit catch-up bursts after a scheduler pause; restart the cadence from
                    // the current observation and let maximumSendGapNs preserve the evidence.
                    nextPublishDeadlineNs = System.nanoTime()
                }
            }
            val motionStoppedNs = System.nanoTime()

            repeat(6) {
                publish(0.0, 0.0, 0.0)
                delay(20L)
            }
            val receiverAcknowledgement = withTimeoutOrNull(2_000L) {
                while (true) {
                    val acknowledgement = client.driveInputAcknowledgement.value
                    if (
                        acknowledgement != null &&
                        acknowledgement.acceptedSession.toDouble() == sessionNonce &&
                        acknowledgement.acceptedSequence >= sequence - 2L
                    ) return@withTimeoutOrNull acknowledgement
                    delay(20L)
                }
                error("unreachable")
            }
            assertTrue(
                receiverAcknowledgement != null,
                "Simulator did not acknowledge the current drive session: " +
                    "senderSession=${sessionNonce.toLong()}, senderSequence=${sequence - 1L}, " +
                    "lastReceiver=${client.driveInputAcknowledgement.value}"
            )
            val verifiedReceiverAcknowledgement = requireNotNull(receiverAcknowledgement)
            delay(500L)
            client.publishString("ARES/DriverStation/Command", "STOP")
            collector?.cancelAndJoin()
            collector = null

            val activePoses = poses.filter { it.receivedAtNs in motionStartedNs..motionStoppedNs }
            val poseGapsNs = activePoses.zipWithNext { first, second -> second.receivedAtNs - first.receivedAtNs }
            val sourcePoseGapsUs = activePoses.zipWithNext { first, second ->
                second.frame.timestampUs - first.frame.timestampUs
            }
            val longPoseGaps = poseGapsNs.count { it > POSE_SMOOTH_GAP_NS }
            val allowedLongPoseGaps = durationSeconds / 3_600
            val sequencePairs = activePoses.zipWithNext { first, second -> first.frame.sequence to second.frame.sequence }
            val first = activePoses.firstOrNull()?.frame
            val last = activePoses.lastOrNull()?.frame
            val displacement = if (first == null || last == null) 0.0 else hypot(last.trueX - first.trueX, last.trueY - first.trueY)
            val ekfErrors = activePoses.map { sample ->
                hypot(sample.frame.ekfX - sample.frame.trueX, sample.frame.ekfY - sample.frame.trueY)
            }

            assertEquals(0L, failedPublishes, "The Analytics sender dropped a leased control heartbeat")
            assertTrue(
                verifiedReceiverAcknowledgement.statusCode in 2..3,
                "Simulator receiver did not acknowledge an armed neutral or active frame: $verifiedReceiverAcknowledgement"
            )
            assertTrue(maximumSendGapNs <= 100_000_000L, "Control scheduling gap exceeded 100 ms: ${maximumSendGapNs / 1_000_000.0} ms")
            assertTrue(activePoses.size >= durationSeconds * 10, "Packed pose telemetry fell below 10 Hz")
            assertTrue(
                poseGapsNs.maxOrNull() ?: Long.MAX_VALUE <= POSE_HARD_STALL_NS,
                "Packed pose stream stalled for more than one second: " +
                    "maxReceiptGapMs=${(poseGapsNs.maxOrNull() ?: 0L) / 1_000_000.0}, " +
                    "maxSourceGapMs=${(sourcePoseGapsUs.maxOrNull() ?: 0L) / 1_000.0}"
            )
            assertTrue(
                longPoseGaps <= allowedLongPoseGaps,
                "Packed pose stream exceeded the smoothness SLO: gapsOver250Ms=$longPoseGaps, " +
                    "allowed=$allowedLongPoseGaps, " +
                    "maxReceiptGapMs=${(poseGapsNs.maxOrNull() ?: 0L) / 1_000_000.0}, " +
                    "maxSourceGapMs=${(sourcePoseGapsUs.maxOrNull() ?: 0L) / 1_000.0}"
            )
            assertTrue(sequencePairs.all { (before, after) -> after > before }, "Packed pose sequence moved backwards or repeated")
            assertTrue(displacement >= 0.5, "The simulated robot did not move under dashboard control")
            assertTrue(ekfErrors.average() <= 0.25, "Mean EKF translation error exceeded 0.25 m")
            assertTrue((ekfErrors.maxOrNull() ?: Double.POSITIVE_INFINITY) <= 0.75, "Peak EKF translation error exceeded 0.75 m")

            // Exercise the live rewind source that the Dashboard timeline uses. This proves the
            // packed pose components reached the ephemeral DB and can be reconstructed at two
            // distinct historical playheads while fresh NT4 traffic remains connected.
            val replay = ReplayEngineService(database, client)
            var rewindEarlySequence = -1L
            var rewindLateSequence = -1L
            var rewindEarlyTimestampMs = -1L
            var rewindLateTimestampMs = -1L
            var persistedPoseSequences = emptyList<com.ares.analytics.shared.models.TelemetryFrame>()
            var persistedPoseSpanMs = -1L
            var rewindLoadMs = -1L
            val expectedReplaySeconds = minOf(
                durationSeconds,
                (Nt4ClientService.LIVE_RETENTION_MS / 1_000L).toInt()
            )
            client.isReplayActive.value = true
            try {
                val rewindLoadStartedNs = System.nanoTime()
                replay.loadSession(Nt4ClientService.LIVE_SESSION_ID)
                rewindLoadMs = (System.nanoTime() - rewindLoadStartedNs) / 1_000_000L
                assertTrue(
                    rewindLoadMs <= 10_000L,
                    "Live rewind took too long to commit and load: $rewindLoadMs ms"
                )
                persistedPoseSequences = database.getTelemetryForKey(
                    Nt4ClientService.LIVE_SESSION_ID,
                    "ARES/SimulatorPoseFrame/9"
                )
                assertTrue(
                    persistedPoseSequences.size >= expectedReplaySeconds * 10,
                    "Live rewind persisted fewer than 10 packed pose frames per second"
                )
                persistedPoseSpanMs = persistedPoseSequences.last().timestampMs -
                    persistedPoseSequences.first().timestampMs
                assertTrue(
                    persistedPoseSpanMs >= expectedReplaySeconds * 500L,
                    "Persisted packed poses cover too little wall time: $persistedPoseSpanMs ms"
                )
                assertTrue(
                    replay.sessionDurationMs.value >= expectedReplaySeconds * 500L,
                    "Live rewind retained too little of the soak session"
                )
                replay.scrubTo(0.25)
                withTimeout(5_000L) {
                    while (replay.progress.value < 0.24) delay(10L)
                }
                val earlyValues = replay.currentFrame.value?.values.orEmpty()
                assertTrue((0..9).all { "ARES/SimulatorPoseFrame/$it" in earlyValues })
                rewindEarlySequence = earlyValues.getValue("ARES/SimulatorPoseFrame/9").toLong()
                rewindEarlyTimestampMs = replay.currentFrame.value?.timestampMs ?: -1L

                replay.scrubTo(0.75)
                withTimeout(5_000L) {
                    while (replay.progress.value < 0.74) delay(10L)
                }
                val lateValues = replay.currentFrame.value?.values.orEmpty()
                assertTrue((0..9).all { "ARES/SimulatorPoseFrame/$it" in lateValues })
                rewindLateSequence = lateValues.getValue("ARES/SimulatorPoseFrame/9").toLong()
                rewindLateTimestampMs = replay.currentFrame.value?.timestampMs ?: -1L
                assertTrue(
                    rewindLateSequence - rewindEarlySequence >= expectedReplaySeconds * 5L,
                    "Live rewind advanced only ${rewindLateSequence - rewindEarlySequence} " +
                        "pose frames between quarter and three-quarter playheads; " +
                        "replayDurationMs=${replay.sessionDurationMs.value}, " +
                        "frameTimes=$rewindEarlyTimestampMs->$rewindLateTimestampMs, " +
                        "persistedTimes=${persistedPoseSequences.first().timestampMs}->" +
                        "${persistedPoseSequences.last().timestampMs}, " +
                        "persistedSequences=${persistedPoseSequences.first().value}->" +
                        "${persistedPoseSequences.last().value}"
                )
            } finally {
                replay.disposeAndJoin()
                client.isReplayActive.value = false
            }

            println(
                "[SIM-SOAK] seconds=$durationSeconds frames=${activePoses.size} " +
                    "maxControlGapMs=${maximumSendGapNs / 1_000_000.0} " +
                    "maxPoseGapMs=${(poseGapsNs.maxOrNull() ?: 0L) / 1_000_000.0} " +
                    "displacementM=$displacement meanEkfErrorM=${ekfErrors.average()} " +
                    "maxEkfErrorM=${ekfErrors.maxOrNull()} " +
                    "persistedPoseFrames=${persistedPoseSequences.size} " +
                    "persistedPoseSpanMs=$persistedPoseSpanMs " +
                    "rewindLoadMs=$rewindLoadMs " +
                    "rewindSequences=$rewindEarlySequence->$rewindLateSequence"
            )
        } finally {
            collector?.cancel()
            runCatching { client.publishString("ARES/DriverStation/Command", "STOP") }
            delay(100L)
            runCatching { client.stop() }
            database.closeAndJoin()
            tempDirectory.deleteRecursively()
        }
    }

    private fun booleanProperty(name: String, default: Boolean): Boolean =
        System.getProperty(name)?.toBooleanStrictOrNull() ?: default

    private fun intProperty(name: String, default: Int): Int =
        System.getProperty(name)?.toIntOrNull() ?: default

    companion object {
        private const val CONTROL_PERIOD_NS = 20_000_000L
        private const val POSE_SMOOTH_GAP_NS = 250_000_000L
        private const val POSE_HARD_STALL_NS = 1_000_000_000L
    }
}
