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
    fun `legacy metadata is the only currently declared migration path`() {
        assertEquals(ARES_PROJECT_METADATA_SCHEMA_VERSION, ProjectSchemaVersions.policy(ProjectDocumentKind.PROJECT_METADATA)?.currentVersion)
        assertEquals(ProjectSchemaDisposition.MIGRATION_REQUIRED, ProjectSchemaVersions.disposition(ProjectDocumentKind.PROJECT_METADATA, 1))
        assertEquals(ProjectSchemaDisposition.MIGRATION_REQUIRED, ProjectSchemaVersions.disposition(ProjectDocumentKind.PROJECT_METADATA, 2))
        assertEquals(ProjectSchemaDisposition.UNSUPPORTED, ProjectSchemaVersions.disposition(ProjectDocumentKind.PROJECT_METADATA, 0))

        ProjectDocumentKind.entries
            .filterNot { it == ProjectDocumentKind.PROJECT_METADATA || it == ProjectDocumentKind.FIELD }
            .forEach { kind -> assertEquals(emptySet<Int>(), ProjectSchemaVersions.policy(kind)?.migratableFrom, kind.name) }
    }
}
