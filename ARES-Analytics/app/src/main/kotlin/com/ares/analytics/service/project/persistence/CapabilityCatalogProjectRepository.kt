package com.ares.analytics.service.project.persistence

import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.catalog.CapabilityCatalogDocument

/** Authoritative offline action/condition catalog at `.ares/action-catalog.json`. */
class CapabilityCatalogProjectRepository {
    fun load(projectPath: String): Result<CapabilityCatalogDocument> = store.load(projectPath)

    fun save(
        projectPath: String,
        document: CapabilityCatalogDocument
    ): SavedProjectRevision<CapabilityCatalogDocument> = store.save(projectPath, document)

    fun listRevisions(projectPath: String): List<ProjectRevisionSummary> = store.listRevisions(projectPath)

    fun restore(
        projectPath: String,
        contentHash: String
    ): SavedProjectRevision<CapabilityCatalogDocument> = store.restore(projectPath, contentHash)

    fun diagnostic(projectPath: String): ProjectDocumentDiagnostic? = store.diagnostic(projectPath)

    fun file(projectPath: String) = resolveProjectPath(projectPath, ".ares/action-catalog.json")

    private object store : SingletonProjectDocumentStore<CapabilityCatalogDocument>(
        kind = ProjectDocumentKind.CAPABILITY_CATALOG,
        fileName = "action-catalog.json",
        historyName = "action-catalog"
    ) {
        override fun encode(document: CapabilityCatalogDocument): String = CapabilityCatalogCodec.encode(document)
        override fun decode(json: String): CapabilityCatalogDocument = CapabilityCatalogCodec.decode(json)
        override fun contentHash(document: CapabilityCatalogDocument): String = CapabilityCatalogCodec.contentHash(document)
        override fun revision(document: CapabilityCatalogDocument): Int = document.revision
        override fun withRevision(document: CapabilityCatalogDocument, revision: Int): CapabilityCatalogDocument =
            document.copy(revision = revision)

        override fun sameContent(
            previous: CapabilityCatalogDocument,
            draft: CapabilityCatalogDocument
        ): Boolean = previous == draft.copy(
            schemaVersion = previous.schemaVersion,
            revision = previous.revision
        )
    }
}
