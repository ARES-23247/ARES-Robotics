package com.areslib.pathing

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FieldWaypointLoaderAuditTest {
    private val waypoint = """{"id":"dock","name":"Dock","x":1.25,"y":-0.5,"headingDegrees":90.0}"""

    @Test fun `missing and invalid sources are resolved only once until clear`() {
        for (initial in listOf(null, "not-json", "null", "{}", "[null]")) {
            var reads = 0
            var source = initial
            val cache = FieldWaypointCache { reads++; source }
            val first = cache.load()
            assertTrue(first.isEmpty())
            source = "[$waypoint]"
            repeat(100) { assertSame(first, cache.load()) }
            assertEquals(1, reads)
            cache.clear()
            assertEquals(1, cache.load().size)
            assertEquals(2, reads)
        }
    }

    @Test fun `valid exported units and literal names survive immutable caching`() {
        val cache = FieldWaypointCache { "[$waypoint]" }
        val snapshot = cache.load()
        val item = assertNotNull(snapshot["Dock"])
        assertEquals("dock", item.id)
        assertFalse(item.locked)
        val pose = item.toPose()
        assertEquals(1.25, pose.x)
        assertEquals(-0.5, pose.y)
        assertEquals(Math.PI / 2, pose.heading.radians)
        assertSame(snapshot, cache.load())
        assertFailsWith<UnsupportedOperationException> { (snapshot as MutableMap).clear() }
        val spaced = FieldWaypointCache { "[${waypoint.replace("Dock", " Dock ").dropLast(1)},\"locked\":true}]" }
        assertTrue(assertNotNull(spaced.load()[" Dock "]).locked)
    }

    @Test fun `unsafe and ambiguous records reject the complete file`() {
        val invalid = listOf(
            waypoint.replace("\"x\":1.25,", ""),
            waypoint.replace("1.25", "1e400"),
            waypoint.replace("1.25", "\"NaN\""),
            waypoint.replace("-0.5", "null"),
            waypoint.replace("90.0", "\"90\""),
            waypoint.replace("\"dock\"", "null"),
            waypoint.replace("\"Dock\"", "\"  \""),
            waypoint.dropLast(1) + ",\"locked\":1}",
        )
        for (record in invalid) {
            val json = "[${waypoint.replace("dock", "other").replace("Dock", "Other")},$record]"
            assertTrue(FieldWaypointCache { json }.load().isEmpty(), record)
        }
        assertTrue(FieldWaypointCache { "[$waypoint,$waypoint]" }.load().isEmpty())
        assertTrue(FieldWaypointCache { "[$waypoint,${waypoint.replace("dock", "other")}]" }.load().isEmpty())
        assertTrue(FieldWaypointCache { "[$waypoint,${waypoint.replace("Dock", "Other")}]" }.load().isEmpty())
    }

    @Test fun `read failure is cached and explicit reload can recover`() {
        var reads = 0
        val cache = FieldWaypointCache { if (++reads == 1) error("read failed") else "[$waypoint]" }
        assertTrue(cache.load().isEmpty())
        assertTrue(cache.load().isEmpty())
        assertEquals(1, reads)
        cache.clear()
        assertEquals(1, cache.load().size)
    }

    @Test fun `simultaneous first reads share a single loaded snapshot`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var reads = 0
        val cache = FieldWaypointCache {
            reads++
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            "[$waypoint]"
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<Map<String, FieldWaypoint>> { cache.load() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = workers.submit<Map<String, FieldWaypoint>> { cache.load() }
            release.countDown()
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, reads)
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `warm missing and present lookups allocate no loop bytes`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        for (json in listOf(null, "[$waypoint]")) {
            val cache = FieldWaypointCache { json }
            var observed: FieldWaypoint? = null
            // Warm the same compiled loop used by each measured window, including the counter API.
            repeat(20) {
                observed = lookups(cache, 10_000)
                bean.getThreadAllocatedBytes(thread)
            }
            repeat(2) {
                val before = bean.getThreadAllocatedBytes(thread)
                observed = lookups(cache, 10_000)
                assertEquals(0L, bean.getThreadAllocatedBytes(thread) - before)
            }
            assertEquals(json != null, observed != null)
        }
    }
    private fun lookups(cache: FieldWaypointCache, count: Int): FieldWaypoint? {
        var result: FieldWaypoint? = null
        var index = 0
        while (index++ < count) result = cache.load()["Dock"]
        return result
    }
}
