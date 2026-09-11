// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.AppJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulatorFieldApplyReceiptTest {
    private val receipt = SimulatorFieldApplyReceipt("sim", 7L, "field", 3L, "ab".repeat(32), 2, 4, 6)

    @Test
    fun `parser rejects absent malformed and structurally incomplete receipts`() {
        for (payload in listOf(null, "", " ", "not-json", "{}", "[]",
            """{"session":"sim","sequence":"bad"}""")) {
            assertNull(parseSimulatorFieldApplyReceipt(payload))
        }
        val encoded = AppJson.encodeToString(receipt)
        assertEquals(receipt, parseSimulatorFieldApplyReceipt(encoded))
        assertEquals(receipt, parseSimulatorFieldApplyReceipt(encoded.dropLast(1) + ",\"futureField\":true}"))
        assertEquals("sim:7", assertNotNull(parseSimulatorFieldApplyReceipt(encoded)).eventId)
    }

    @Test
    fun `exact content matching and event freshness are separate contracts`() {
        val expected = ExpectedSimulatorField("field", 3L, receipt.sha256.uppercase())
        assertTrue(receipt.matches(expected))
        assertFalse(receipt.matches(expected.copy(configId = "Field")))
        assertFalse(receipt.matches(expected.copy(revision = 4L)))
        assertFalse(receipt.matches(expected.copy(sha256 = "cd".repeat(32))))
        for (otherEvent in listOf(receipt.copy(sequence = 8L), receipt.copy(session = "new-sim"))) {
            assertTrue(otherEvent.matches(expected))
            assertNotEquals(receipt.eventId, otherEvent.eventId)
        }
    }

    @Test
    fun `payload hash preserves exact UTF8 bytes including line endings`() {
        val payload = "field\n温度"
        assertEquals("be9469ff84ee0af140474e7a5e1368472d94421ed2072484593f4e90b21c8015", simulatorFieldPayloadHash(payload))
        assertNotEquals(simulatorFieldPayloadHash(payload), simulatorFieldPayloadHash("field\r\n温度"))
        assertNotEquals(simulatorFieldPayloadHash(payload), simulatorFieldPayloadHash(payload + " "))
    }
}
