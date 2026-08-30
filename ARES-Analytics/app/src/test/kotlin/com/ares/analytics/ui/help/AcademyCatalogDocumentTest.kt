package com.ares.analytics.ui.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcademyCatalogDocumentTest {
    @Test
    fun `bundled catalog is current complete and deterministic`() {
        val document = AcademyCatalogCodec.loadBundled()

        assertEquals(ACADEMY_CATALOG_SCHEMA_VERSION, document.schemaVersion)
        assertEquals(LearningLab.entries.size, document.labGuides.size)
        assertTrue(document.lessons.isNotEmpty())
        assertTrue(document.paths.isNotEmpty())
        assertEquals(
            AcademyCatalogCodec.encode(document),
            AcademyCatalogCodec.encode(AcademyCatalogCodec.decode(AcademyCatalogCodec.encode(document))),
        )
    }

    @Test
    fun `unsupported schema is rejected without a compatibility reader`() {
        val payload = AcademyCatalogCodec.encode(AcademyCatalogCodec.loadBundled())
            .replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2")

        assertFailsWith<IllegalArgumentException> { AcademyCatalogCodec.decode(payload) }
    }

    @Test
    fun `unknown root and nested fields are rejected`() {
        val payload = AcademyCatalogCodec.encode(AcademyCatalogCodec.loadBundled())
        val unknownRoot = payload.trimEnd().dropLast(1) + ",\n  \"legacyCatalog\": []\n}\n"
        val unknownLessonField = payload.replaceFirst(
            "\"durationMinutes\": 15,",
            "\"durationMinutes\": 15,\n      \"legacyScreen\": \"DASHBOARD\",",
        )

        assertFailsWith<IllegalArgumentException> { AcademyCatalogCodec.decode(unknownRoot) }
        assertFailsWith<IllegalArgumentException> { AcademyCatalogCodec.decode(unknownLessonField) }
    }
}
