package com.areslib.project.schema

import com.areslib.project.ARES_PROJECT_METADATA_SCHEMA_VERSION
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProjectSchemaVersionsTest {
    @Test
    fun `every schema-owned document has an explicit current version`() {
        val runtimeOwned = setOf(ProjectDocumentKind.FIELD)

        ProjectDocumentKind.entries.filterNot(runtimeOwned::contains).forEach { kind ->
            assertEquals(
                ProjectSchemaDisposition.CURRENT,
                ProjectSchemaVersions.disposition(kind, requireNotNull(ProjectSchemaVersions.policy(kind)).currentVersion),
                kind.name,
            )
        }
        assertNull(ProjectSchemaVersions.policy(ProjectDocumentKind.FIELD))
    }

    @Test
    fun `all non-current metadata versions are unsupported`() {
        assertEquals(ARES_PROJECT_METADATA_SCHEMA_VERSION, ProjectSchemaVersions.policy(ProjectDocumentKind.PROJECT_METADATA)?.currentVersion)
        assertEquals(ProjectSchemaDisposition.UNSUPPORTED, ProjectSchemaVersions.disposition(ProjectDocumentKind.PROJECT_METADATA, 1))
        assertEquals(ProjectSchemaDisposition.UNSUPPORTED, ProjectSchemaVersions.disposition(ProjectDocumentKind.PROJECT_METADATA, 2))
        assertEquals(ProjectSchemaDisposition.UNSUPPORTED, ProjectSchemaVersions.disposition(ProjectDocumentKind.PROJECT_METADATA, 3))
        assertEquals(ProjectSchemaDisposition.UNSUPPORTED, ProjectSchemaVersions.disposition(ProjectDocumentKind.PROJECT_METADATA, 0))
    }
}
