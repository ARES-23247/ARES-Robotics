package com.ares.analytics.service

import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AdvancedAnalyticsMathAuditTest {
    @Test fun `tiny finite correlated signals do not underflow into missing correlation`() = runTest {
        val repo = Repo()
        repo.pair(CURRENT, VELOCITY, 20) { i -> i*1e-300 to i*2e-300 }
        assertEquals(1.0, AdvancedAnalyticsService(repo).analyze("current").correlations.single().coefficient, 1e-12)
    }

    @Test fun `huge finite correlated signals do not overflow into nan`() = runTest {
        val repo = Repo()
        repo.pair(CURRENT, VELOCITY, 20) { i -> i*1e200 to i*2e200 }
        assertEquals(1.0, AdvancedAnalyticsService(repo).analyze("current").correlations.single().coefficient, 1e-12)
    }

    @Test fun `text placeholders and nonfinite observations cannot count as paired evidence`() = runTest {
        val repo = Repo()
        repo.pair(CURRENT, VELOCITY, 10) { i -> i.toDouble() to i*2.0 }
        repo.data[CURRENT] = repo.data.getValue(CURRENT).mapIndexed { i,f -> if(i==9) f.copy(stringValue="invalid") else f }
        assertTrue(AdvancedAnalyticsService(repo).analyze("current").correlations.isEmpty())
        repo.data[CURRENT] = repo.data.getValue(CURRENT).mapIndexed { i,f -> if(i==9) f.copy(stringValue=null,value=Double.NaN) else f }
        assertTrue(AdvancedAnalyticsService(repo).analyze("current").correlations.isEmpty())
    }

    @Test fun `one right sample cannot be reused to inflate correlation sample count`() = runTest {
        val repo = Repo()
        repo.data[CURRENT] = List(30) { sample(CURRENT,it*10_000L,it.toDouble()) }
        repo.data[VELOCITY] = List(4) { sample(VELOCITY,it*100_000L,it*10.0) }
        assertTrue(AdvancedAnalyticsService(repo).analyze("current").correlations.isEmpty())
    }

    @Test fun `driver axes require the same source timestamp even within one millisecond`() = runTest {
        val repo = Repo()
        repo.data[X] = List(20) { sample(X,it*1000L+100,it/20.0) }
        repo.data[Y] = List(20) { sample(Y,it*1000L+900,0.0) }
        assertNull(AdvancedAnalyticsService(repo).analyze("current").driverScore)
    }

    @Test fun `heatmap speed averages actual intervals without inventing a stationary first sample`() = runTest {
        val repo = Repo()
        repo.pair(PX,PY,3,1_000_000L) { i -> i*0.1 to 0.0 }
        val cell=AdvancedAnalyticsService(repo).analyze("current").pathHeatmap.single()
        assertEquals(3,cell.visits)
        assertEquals(0.1,cell.averageSpeedMetersPerSecond)
    }

    @Test fun `heatmap velocity uses microseconds instead of rounding short intervals to zero`() = runTest {
        val repo = Repo()
        repo.pair(PX,PY,2,100L) { i -> i*0.0001 to 0.0 }
        assertEquals(1.0,AdvancedAnalyticsService(repo).analyze("current").pathHeatmap.single().averageSpeedMetersPerSecond)
    }

    @Test fun `pose components from incomplete different topic families are never mixed`() = runTest {
        val repo = Repo()
        repo.pair(PX,"ARES/EstimatedPose/1",20) { i -> i*0.1 to i*0.1 }
        assertTrue(AdvancedAnalyticsService(repo).analyze("current").pathHeatmap.isEmpty())
    }

    @Test fun `full speed direction reversals are not perfectly smooth commands`() = runTest {
        val repo = Repo()
        repo.pair(X,Y,20) { i -> (if(i%2==0) -1.0 else 1.0) to 0.0 }
        assertTrue(assertNotNull(AdvancedAnalyticsService(repo).analyze("current").driverScore).smoothness<10.0)
    }

    @Test fun `same command ramp has the same smoothness at different recording rates`() = runTest {
        suspend fun score(period:Long,count:Int):Double {
            val repo = Repo()
            repo.pair(X,Y,count,period) { i -> i*period/1_000_000.0 to 0.0 }
            return assertNotNull(AdvancedAnalyticsService(repo).analyze("current").driverScore).smoothness
        }
        assertEquals(score(20_000L,51),score(50_000L,21),1e-9)
    }

    @Test fun `summary recommendations cannot manufacture sample counts from error magnitude`() = runTest {
        val repo = Repo()
        repo.summaries["current"] = summary("current").copy(avgCrossTrackError=0.4,maxEkfDrift=0.6,avgBatteryResistance=0.08)
        val suggestions=AdvancedAnalyticsService(repo).analyze("current").tuningSuggestions
        assertEquals(3,suggestions.size)
        assertTrue(suggestions.all { it.evidenceSamples==1 })
    }

    @Test fun `invalid summary values cannot create confident warnings or tuning advice`() = runTest {
        val repo = Repo()
        repo.summaries["current"] = summary("current").copy(p95LoopTimeMs=Double.POSITIVE_INFINITY,
            avgCrossTrackError=Double.POSITIVE_INFINITY,maxEkfDrift=Double.POSITIVE_INFINITY,
            avgBatteryResistance=Double.POSITIVE_INFINITY,avgVisionLatencyMs=Double.POSITIVE_INFINITY)
        val report=AdvancedAnalyticsService(repo).analyze("current")
        assertTrue(report.diagnostics.isEmpty())
        assertTrue(report.tuningSuggestions.isEmpty())
    }

    @Test fun `cancellation propagates from safe analytics instead of becoming a failure result`() = runTest {
        val repo=Repo().apply { rangeFailure=CancellationException("cancelled") }
        assertFailsWith<CancellationException> { AdvancedAnalyticsService(repo).analyzeSafely("current") }
    }

    @Test fun `recent baseline lookup errors use the same typed failure boundary`() = runTest {
        val repo=Repo().apply { summaryFailure=IllegalStateException("unavailable database") }
        assertIs<OperationResult.Failure>(AdvancedAnalyticsService(repo).analyzeAgainstRecent("current"))
    }

    @Test fun `recent analysis reuses the loaded summary range and selected baseline objects`() = runTest {
        val repo=Repo()
        repo.summaries["current"]=summary("current")
        repo.summaries["baseline"]=summary("baseline")
        assertIs<OperationResult.Success<*>>(AdvancedAnalyticsService(repo).analyzeAgainstRecent("current"))
        assertEquals(1,repo.rangeCalls)
        assertEquals(mapOf("current" to 1),repo.summaryCalls)
        assertEquals(1,repo.allSummaryCalls)
    }

    @Test fun `a motor output voltage is not misidentified as battery sag evidence`() = runTest {
        val repo=Repo()
        repo.pair(CURRENT,"Hardware/Motors/a/Voltage",20) { i -> i.toDouble() to 12.0-i*0.1 }
        val report=AdvancedAnalyticsService(repo).analyze("current")
        assertTrue(report.correlations.isEmpty())
        assertTrue(report.tuningSuggestions.isEmpty())
    }

    @Test fun `large finite summary baselines do not overflow their average`() = runTest {
        val repo=Repo()
        repo.summaries["current"]=summary("current").copy(p95LoopTimeMs=1.1e308)
        repo.summaries["a"]=summary("a").copy(p95LoopTimeMs=1e308)
        repo.summaries["b"]=summary("b").copy(p95LoopTimeMs=1e308)
        val metric=assertNotNull(AdvancedAnalyticsService(repo).analyze("current",listOf("a","b")).comparison)
            .metrics.single { it.metric=="p95 loop time" }
        assertEquals(1e308,metric.baselineAverage)
        assertEquals(10.0,metric.percentChange,1e-10)
    }

    @Test fun `correlation handles signed extremes offsets and constant signals`() {
        fun correlation(values: List<Pair<Double, Double>>) = analyticsCorrelation(values.mapIndexed { i, (x, y) ->
            sample(CURRENT, i.toLong(), x) to sample(VELOCITY, i.toLong(), y)
        })
        assertEquals(-1.0, assertNotNull(correlation(listOf(
            -Double.MAX_VALUE to Double.MAX_VALUE, 0.0 to 0.0, Double.MAX_VALUE to -Double.MAX_VALUE))), 1e-12)
        assertEquals(1.0, assertNotNull(correlation(List(20) { i ->
            (1e100 + i * 1e86) to (3.0 + i * 0.25)
        })), 1e-5)
        assertNull(correlation(List(20) { 0.0 to it.toDouble() }))
        assertNull(correlation(listOf(1.0 to 2.0)))
    }

    @Test fun `numeric snapshot sorts and deduplicates source times without accepting other identities`() {
        val first = sample(CURRENT, 100, 1.0)
        val latest = first.copy(value = 2.0, sampleOrder = 2)
        val next = sample("/$CURRENT", 200, 3.0)
        val frames = listOf(next, latest, first, first.copy(sessionId = "other"),
            first.copy(key = VELOCITY), first.copy(stringValue = "2"), first.copy(value = Double.POSITIVE_INFINITY))
        assertEquals(listOf(latest, next), numericAnalyticsSeries(frames, "current", CURRENT))
        assertEquals(7, frames.size)
        for (invalid in listOf(latest.copy(stringValue = "invalid", sampleOrder = 3),
            latest.copy(value = Double.NaN, sampleOrder = 3))) {
            assertTrue(numericAnalyticsSeries(listOf(first, latest, invalid), "current", CURRENT).isEmpty())
        }
    }

    @Test fun `alignment respects nearest unused ties skew and linear scan bounds`() {
        val left = listOf(sample(X, 100_000, 1.0), sample(X, 200_000, 2.0))
        val right = listOf(sample(Y, 0, 1.0), sample(Y, 200_000, 2.0))
        assertEquals(listOf(0L, 200_000L), alignAnalyticsSeries(left, right, 100_000).map { it.second.timestampUs })
        assertTrue(alignAnalyticsSeries(listOf(sample(X, 100_001, 1.0)), right.take(1), 100_000).isEmpty())
        assertEquals(1, alignAnalyticsSeries(left, right).size)
        assertFailsWith<IllegalArgumentException> { alignAnalyticsSeries(left, right, -1) }
        val count = 5_000
        var accesses = 0
        val counted = object : AbstractList<TelemetryFrame>() {
            override val size = count
            override fun get(index: Int): TelemetryFrame {
                accesses++
                return sample(Y, index * 100_000L, index.toDouble())
            }
        }
        val pairs = alignAnalyticsSeries(List(count) { sample(X, it * 100_000L + 1, it.toDouble()) }, counted, 100_000)
        assertEquals(count, pairs.size)
        assertTrue(accesses <= 8 * count, "Alignment read $accesses right samples for $count pairs")
        println("Alignment right-input accesses: $accesses for $count pairs (limit ${8 * count})")
    }

    @Test fun `heatmap uses elapsed time weighting and does not infer speed for a singleton`() = runTest {
        val repo = Repo()
        val times = listOf(0L, 1_000_000L, 3_000_000L)
        repo.data[PX] = times.mapIndexed { i, time -> sample(PX, time, i * 0.1) }
        repo.data[PY] = times.map { sample(PY, it, 0.0) }
        assertEquals(0.2 / 3.0, AdvancedAnalyticsService(repo).analyze("current").pathHeatmap.single().averageSpeedMetersPerSecond)
        repo.pair(PX, PY, 1) { -0.1 to -0.1 }
        val cell = AdvancedAnalyticsService(repo).analyze("current").pathHeatmap.single()
        assertEquals(-1, cell.xIndex)
        assertEquals(-1, cell.yIndex)
        assertNull(cell.averageSpeedMetersPerSecond)
        repo.pair(PX, PY, 2) { Double.MAX_VALUE to 0.0 }
        assertTrue(AdvancedAnalyticsService(repo).analyze("current").pathHeatmap.isEmpty())
    }

    @Test fun `heatmap prefers complete packed estimator components without substituting simulator truth`() = runTest {
        val repo = Repo()
        repo.pair(PX, PY, 2) { 100.0 to 100.0 }
        repo.pair("ARES/SimulatorPoseFrame/0", "ARES/SimulatorPoseFrame/1", 2) { 50.0 to 50.0 }
        repo.pair("ARES/SimulatorPoseFrame/3", "ARES/SimulatorPoseFrame/4", 2) { 1.0 to 2.0 }
        val cell = AdvancedAnalyticsService(repo).analyze("current").pathHeatmap.single()
        assertEquals(2, cell.xIndex)
        assertEquals(4, cell.yIndex)
        assertEquals(setOf("ARES/SimulatorPoseFrame/3", "ARES/SimulatorPoseFrame/4"), repo.seriesCalls.keys)
    }

    @Test fun `invalid or isolated command samples cannot produce a driver score`() = runTest {
        val repo = Repo()
        for (invalid in listOf(1.01, Double.NaN, Double.NEGATIVE_INFINITY)) {
            repo.pair(X, Y, 20) { invalid to 0.0 }
            assertNull(AdvancedAnalyticsService(repo).analyze("current").driverScore)
        }
        repo.pair(X, Y, 20, 1_000_000) { 0.5 to 0.0 }
        assertNull(AdvancedAnalyticsService(repo).analyze("current").driverScore)
    }

    @Test fun `ambiguous zero or invalid fraction summaries cannot look like improvements`() = runTest {
        val repo = Repo()
        repo.summaries["baseline"] = summary("baseline").copy(p95LoopTimeMs = 10.0, visionAcceptanceRate = 0.9)
        repo.summaries["current"] = summary("current").copy(visionAcceptanceRate = 1.1)
        assertNull(AdvancedAnalyticsService(repo).analyze("current", listOf("baseline")).comparison)
        repo.summaries["current"] = summary("current").copy(p95LoopTimeMs = Double.MAX_VALUE)
        for (id in listOf("a", "b", "c")) repo.summaries[id] = summary(id).copy(p95LoopTimeMs = Double.MAX_VALUE)
        val metric = assertNotNull(AdvancedAnalyticsService(repo).analyze("current", listOf("a", "b", "c")).comparison).metrics.single()
        assertEquals(Double.MAX_VALUE, metric.baselineAverage)
        assertEquals(0.0, metric.percentChange)
    }

    @Test fun `tuning values are not motor currents and canonical aliases retain their meaning`() = runTest {
        val repo = Repo()
        repo.pair("Tuning/Parameters/path_kP/Current", "Robot/BatteryVoltage", 20) { i -> i.toDouble() to 12.0 - i * 0.1 }
        val empty = AdvancedAnalyticsService(repo).analyze("current")
        assertTrue(empty.correlations.isEmpty())
        assertTrue(empty.tuningSuggestions.isEmpty())
        assertTrue(empty.diagnostics.isEmpty())
        repo.pair("/Drive/MotorCurrent_fl", "/Drive/MotorVelocity_fl", 20) { i -> i.toDouble() to i * 2.0 }
        repo.pair("/Hardware/Motors/back/Current", "/Hardware/Motors/back/Velocity", 20) { i -> i.toDouble() to i * 2.0 }
        assertEquals(4, AdvancedAnalyticsService(repo).analyze("current").correlations.size)
    }

    @Test fun `motor analysis fetches each bounded series once including shared battery data`() = runTest {
        val repo = Repo()
        for (motor in 0..19) repo.pair("Hardware/Motors/${motor.toString().padStart(2, '0')}/CurrentAmps", "Robot/BatteryVoltage", 20) { i ->
            i.toDouble() to 12.0 - i * 0.1
        }
        val report = AdvancedAnalyticsService(repo).analyze("current")
        assertEquals(16, report.correlations.size)
        assertEquals(17, repo.seriesCalls.size)
        assertTrue(repo.seriesCalls.values.all { it == 1 })
        assertFalse(repo.seriesCalls.keys.any { it.contains("/16/") })
    }

    @Test fun `no telemetry and disabled baselines avoid unnecessary repository work`() = runTest {
        val repo = Repo().apply { range = null }
        val service = AdvancedAnalyticsService(repo)
        assertIs<OperationResult.Unavailable>(service.analyzeAgainstRecent("current"))
        assertTrue(repo.summaryCalls.isEmpty())
        assertEquals(0, repo.allSummaryCalls)
        assertTrue(service.analyze("current").diagnostics.any { it.category == "data" })
        repo.range = 0L to 100_000L
        repo.summaries["current"] = summary("current")
        assertIs<OperationResult.Success<*>>(service.analyzeAgainstRecent("current", 0))
        assertEquals(0, repo.allSummaryCalls)
    }

    @Test fun `later repository cancellation propagates while query failures remain typed`() = runTest {
        val repo = Repo()
        repo.summaries["current"] = summary("current")
        val service = AdvancedAnalyticsService(repo)
        repo.allSummaryFailure = CancellationException("cancel selection")
        assertFailsWith<CancellationException> { service.analyzeAgainstRecent("current") }
        repo.allSummaryFailure = null
        repo.pair(CURRENT, VELOCITY, 20) { i -> i.toDouble() to i * 2.0 }
        repo.seriesFailure = CancellationException("cancel series")
        assertFailsWith<CancellationException> { service.analyzeSafely("current") }
        repo.seriesFailure = IllegalStateException("query failed")
        assertIs<OperationResult.Failure>(service.analyzeSafely("current"))
    }

    @Test fun `unrelated baseline count cannot inflate evidence and rendered units describe actual support`() = runTest {
        val repo = Repo()
        repo.summaries["current"] = summary("current").copy(avgCrossTrackError = 0.4)
        repo.summaries["baseline"] = summary("baseline")
        repo.pair(X, Y, 20) { i -> (if (i % 2 == 0) -1.0 else 1.0) to 0.0 }
        val service = AdvancedAnalyticsService(repo)
        val report = service.analyze("current")
        assertEquals(report.tuningSuggestions, service.analyze("current", listOf("baseline")).tuningSuggestions)
        val markdown = service.renderDiagnosticMarkdown(report)
        assertTrue(markdown.contains("1 summary statistic"))
        assertTrue(markdown.contains("20 aligned samples"))
        assertTrue(markdown.contains("heuristic, not driver skill"))
        assertFalse(markdown.contains("% confidence"))
    }

    @Test fun `repository point limit violations fail before unbounded numerical work`() = runTest {
        val repo = Repo()
        repo.pair(CURRENT, VELOCITY, 5_001) { i -> i.toDouble() to i * 2.0 }
        assertIs<OperationResult.Failure>(AdvancedAnalyticsService(repo).analyzeSafely("current"))
        assertEquals(mapOf(CURRENT to 1), repo.seriesCalls)
    }

    private class Repo:TelemetryAnalyticsRepository {
        val data=linkedMapOf<String,List<TelemetryFrame>>()
        val summaries=linkedMapOf<String,SessionSummary>()
        var rangeCalls=0
        val summaryCalls=mutableMapOf<String,Int>()
        var allSummaryCalls=0
        val seriesCalls=mutableMapOf<String,Int>()
        var rangeFailure:Exception?=null
        var summaryFailure:Exception?=null
        var allSummaryFailure:Exception?=null
        var seriesFailure:Exception?=null
        var range:Pair<Long,Long>? = 0L to 100_000L
        override suspend fun getSessionTimestampRange(sessionId:String):Pair<Long,Long>? {
            rangeCalls++;rangeFailure?.let { throw it };return range
        }
        override suspend fun getSessionSummary(sessionId:String):SessionSummary? {
            summaryCalls[sessionId]=(summaryCalls[sessionId] ?: 0)+1
            summaryFailure?.let { throw it };return summaries[sessionId]
        }
        override suspend fun getAllSessionSummaries():List<SessionSummary> {
            allSummaryCalls++;allSummaryFailure?.let { throw it };return summaries.values.toList()
        }
        override suspend fun getDistinctTelemetryKeys(sessionId:String):List<String> = data.keys.toList()
        override suspend fun getTelemetrySeries(sessionId:String,key:String,startMs:Long,endMs:Long,maxPoints:Int):List<TelemetryFrame> {
            assertEquals(5000,maxPoints)
            seriesCalls[key]=(seriesCalls[key] ?: 0)+1
            seriesFailure?.let { throw it }
            return data[key].orEmpty()
        }
        fun pair(left:String,right:String,count:Int,periodUs:Long=20_000L,value:(Int)->Pair<Double,Double>) {
            data[left]=List(count) { sample(left,it*periodUs,value(it).first) }
            data[right]=List(count) { sample(right,it*periodUs,value(it).second) }
        }
    }

    private companion object {
        const val CURRENT="Hardware/Motors/a/CurrentAmps"
        const val VELOCITY="Hardware/Motors/a/Velocity"
        const val X="Gamepad1/LeftX"
        const val Y="Gamepad1/LeftY"
        const val PX="Drive/Pose_X"
        const val PY="Drive/Pose_Y"
        fun sample(key:String,timeUs:Long,value:Double)=TelemetryFrame(timeUs/1000,"current",key,value,timestampUs=timeUs)
        fun summary(id:String)=SessionSummary(id,"team","season","robot",0L)
    }
}
