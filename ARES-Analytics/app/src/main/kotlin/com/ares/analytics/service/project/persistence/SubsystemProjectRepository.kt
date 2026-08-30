package com.ares.analytics.service.project.persistence

import com.areslib.project.schema.ProjectDocumentKind

import com.areslib.project.schema.ProjectDocumentId
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemDocumentCodec

/** Offline repository for versioned subsystem DSL documents under `.ares/subsystems`. */
class SubsystemProjectRepository : VersionedProjectDocumentStore<SubsystemDocument>(
    kind = ProjectDocumentKind.SUBSYSTEM,
    directoryName = "subsystems",
    historyName = "subsystems",
    extension = "aressubsystem",
) {
    fun file(projectPath: String, documentId: String) =
        resolveProjectPath(projectPath, ".ares/subsystems/${ProjectDocumentId(documentId).value}.aressubsystem")

    override fun encode(document: SubsystemDocument): String = SubsystemDocumentCodec.encode(document)
    override fun decode(json: String): SubsystemDocument = SubsystemDocumentCodec.decode(json)
    override fun contentHash(document: SubsystemDocument): String = SubsystemDocumentCodec.contentHash(document)
    override fun documentId(document: SubsystemDocument): String = document.documentId
    override fun revision(document: SubsystemDocument): Int = document.revision
    override fun displayName(document: SubsystemDocument): String = document.displayName
    override fun withRevision(document: SubsystemDocument, revision: Int, parentHash: String?): SubsystemDocument =
        document.copy(revision = revision, parentContentHash = parentHash)

    override fun sameContent(previous: SubsystemDocument, draft: SubsystemDocument): Boolean = previous == draft.copy(
        schemaVersion = previous.schemaVersion,
        documentId = previous.documentId,
        revision = previous.revision,
        parentContentHash = previous.parentContentHash,
    )
}
