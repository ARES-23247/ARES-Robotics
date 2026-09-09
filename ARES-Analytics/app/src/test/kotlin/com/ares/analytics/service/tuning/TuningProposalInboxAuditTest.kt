package com.ares.analytics.service.tuning

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TuningProposalInboxAuditTest {
    private fun proposal(value: Double=1.0) = ExternalTuningProposal("test", "recorded", mapOf("key" to value))
    @Test fun `accepted proposals wait for a receiver and retain FIFO order`() {
        val inbox = TuningProposalInbox()
        repeat(TuningProposalInbox.CAPACITY) { assertTrue(inbox.submit(proposal(it.toDouble()))) }
        assertFalse(inbox.submit(proposal(99.0)))
        repeat(TuningProposalInbox.CAPACITY) { index -> assertTrue(inbox.deliverNext { assertEquals(index.toDouble(), it.values["key"]); true }) }
        assertEquals(0, inbox.pendingCount.value); assertFalse(inbox.deliverNext { fail("empty") })
    }
    @Test fun `submission owns an immutable map snapshot`() {
        val inbox = TuningProposalInbox()
        val source = linkedMapOf("key" to 1.0)
        assertTrue(inbox.submit(proposal().copy(values=source)))
        source["key"] = 2.0
        assertTrue(inbox.deliverNext {
            assertEquals(1.0, it.values["key"])
            assertFailsWith<UnsupportedOperationException> { (it.values as MutableMap)["key"] = 3.0 }
       ; true })
    }
    @Test fun `throwing receiver retains the proposal for retry`() {
        val inbox = TuningProposalInbox(); inbox.submit(proposal())
        assertFailsWith<IllegalStateException> { inbox.deliverNext { error("receiver failed") } }
        assertEquals(1, inbox.pendingCount.value)
        assertTrue(inbox.deliverNext { assertEquals(proposal(), it); true })
    }
    @Test fun `empty blank-key and nonfinite proposals do not occupy capacity`() {
        val inbox = TuningProposalInbox()
        for (values in listOf(emptyMap(), mapOf(" " to 1.0), mapOf("key" to Double.NaN), mapOf("key" to Double.POSITIVE_INFINITY))) {
            assertFalse(inbox.submit(proposal().copy(values=values)))
        }
        assertEquals(0, inbox.pendingCount.value)
    }
    @Test fun `receiver becoming unavailable defers acknowledgement without losing the proposal`() {
        val inbox = TuningProposalInbox(); assertTrue(inbox.submit(proposal()))
        assertFalse(inbox.deliverNext { false })
        assertEquals(1, inbox.pendingCount.value)
        assertTrue(inbox.deliverNext { assertEquals(proposal(), it); true })
        assertEquals(0, inbox.pendingCount.value)
    }
    @Test fun `concurrent receivers never duplicate a queued proposal`() {
        val inbox = TuningProposalInbox()
        repeat(TuningProposalInbox.CAPACITY) { inbox.submit(proposal(it.toDouble())) }
        val seen = java.util.Collections.synchronizedList(mutableListOf<Double>())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) { executor.submit { while (inbox.deliverNext { seen.add(it.values.getValue("key")); true }) {} } }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals((0 until TuningProposalInbox.CAPACITY).map(Int::toDouble), seen)
            assertEquals(0, inbox.pendingCount.value)
        } finally { executor.shutdownNow() }
    }
}
