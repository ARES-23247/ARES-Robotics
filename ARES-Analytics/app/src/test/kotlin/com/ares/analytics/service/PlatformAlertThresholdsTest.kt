package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.ThresholdRule
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.*

class PlatformAlertThresholdsTest {
    private val key = TelemetryMetricCatalog.BATTERY_VOLTAGE.canonicalKey
    private val rule = ThresholdRule(key, "Configured battery", minValue = 10.5, maxValue = 6.0, audibleAlert = false)

    @Test fun `non XRP and unrelated signals preserve configured object identity`() {
        val policy = PlatformAlertThresholds()
        assertSame(rule, policy.effectiveRule(key, rule))
        policy.configure(League.FRC, 4.0); assertSame(rule, policy.effectiveRule(key, rule))
        policy.configure(League.XRP, 4.0); assertSame(rule, policy.effectiveRule("Drive/Voltage", rule))
    }
    @Test fun `project minimum accepts inclusive endpoints and rejects invalid input`() {
        val policy = PlatformAlertThresholds()
        for (minimum in listOf(3.0, 4.3, 6.0)) {
            policy.configure(League.XRP, minimum)
            assertEquals(minimum, policy.effectiveRule(key, rule).minValue)
        }
        for (invalid in listOf(null, 2.99, 6.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            policy.configure(League.XRP, invalid)
            assertEquals(4.3, policy.effectiveRule(key, rule).minValue)
        }
    }
    @Test fun `override preserves configured key upper bound audio and original rule`() {
        val policy = PlatformAlertThresholds(); policy.configure(League.XRP, 4.5)
        val effective = policy.effectiveRule(key, rule)
        assertEquals(rule.key, effective.key); assertEquals(6.0, effective.maxValue); assertFalse(effective.audibleAlert)
        assertEquals(4.5, effective.minValue); assertEquals(10.5, rule.minValue)
        assertEquals("XRP Battery Voltage (<4.50V or >6.00V)", effective.displayName)
    }
    @Test fun `each alias has a bounded independent cached rule`() {
        val policy = PlatformAlertThresholds(); policy.configure(League.XRP, 4.3)
        val sources = TelemetryMetricCatalog.BATTERY_VOLTAGE.keys.map { rule.copy(key = it) }
        val effective = sources.map { policy.effectiveRule(it.key, it) }
        repeat(100) {
            sources.forEachIndexed { index, source -> assertSame(effective[index], policy.effectiveRule(source.key, source)) }
        }
    }
    @Test fun `same context preserves cache while changed context and source replace it`() {
        val policy = PlatformAlertThresholds(); policy.configure(League.XRP, 4.3)
        val initial = policy.effectiveRule(key, rule)
        policy.configure(League.XRP, null); assertSame(initial, policy.effectiveRule(key, rule))
        val changedRule = rule.copy(maxValue = 7.0)
        val changed = policy.effectiveRule(key, changedRule)
        assertNotSame(initial, changed); assertEquals(7.0, changed.maxValue)
        policy.configure(League.XRP, 5.0)
        val next = policy.effectiveRule(key, changedRule)
        assertNotSame(changed, next); assertEquals(5.0, next.minValue)
        policy.configure(League.FTC, 6.0); assertSame(changedRule, policy.effectiveRule(key, changedRule))
    }
    @Test fun `low only description remains compatible and locale independent`() {
        val policy = PlatformAlertThresholds(); policy.configure(League.XRP, 4.3)
        assertEquals("Low XRP Battery Voltage (<4.30V)", policy.effectiveRule(key, rule.copy(maxValue = null)).displayName)
    }
    @Test fun `concurrent context changes cannot split threshold and description`() {
        val policy = PlatformAlertThresholds(); val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val configure = pool.submit {
                start.await()
                repeat(20_000) { policy.configure(League.XRP, if (it % 2 == 0) 3.0 else 6.0) }
            }
            val read = pool.submit {
                start.await()
                repeat(20_000) {
                    val effective = policy.effectiveRule(key, rule)
                    if (effective !== rule) {
                        val label = when (effective.minValue) { 3.0 -> "<3.00V"; 6.0 -> "<6.00V"; else -> error("mixed policy") }
                        assertTrue(effective.displayName.contains(label), effective.displayName)
                    }
                }
            }
            start.countDown(); configure.get(10, TimeUnit.SECONDS); read.get(10, TimeUnit.SECONDS)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun `steady XRP rule lookup allocates no heap after cache warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported); bean!!; bean.isThreadAllocatedMemoryEnabled = true
        val policy = PlatformAlertThresholds(); policy.configure(League.XRP, 4.3)
        val effective = policy.effectiveRule(key, rule)
        repeat(50_000) { check(policy.effectiveRule(key, rule) === effective) }
        val id = Thread.currentThread().id; var zero = 0; var bytes = -1L
        for (window in 0 until 10) {
            val before = bean.getThreadAllocatedBytes(id)
            repeat(10_000) { check(policy.effectiveRule(key, rule) === effective) }
            bytes = bean.getThreadAllocatedBytes(id) - before; zero = if (bytes == 0L) zero + 1 else 0
            if (zero == 2) break
        }
        assertEquals(2, zero, "last allocation window: $bytes bytes")
        println("PlatformAlertThresholds: two 10,000-lookup windows allocated 0 bytes")
    }
}
