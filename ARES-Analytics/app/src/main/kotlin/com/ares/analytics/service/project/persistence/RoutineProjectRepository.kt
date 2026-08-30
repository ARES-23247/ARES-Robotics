package com.ares.analytics.service.project.persistence

import com.areslib.project.schema.ProjectDocumentKind

import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.RoutineDocument

/** Offline repository for trigger-neutral `.aresroutine` documents under `.ares/routines`. */
class RoutineProjectRepository : VersionedProjectDocumentStore<RoutineDocument>(
    kind = ProjectDocumentKind.ROUTINE,
    directoryName = "routines",
    historyName = "routines",
    extension = "aresroutine"
) {
    override fun encode(document: RoutineDocument): String = AresRoutineCodec.encode(document)
    override fun decode(json: String): RoutineDocument = AresRoutineCodec.decode(json)
    override fun contentHash(document: RoutineDocument): String = AresRoutineCodec.contentHash(document)
    override fun documentId(document: RoutineDocument): String = document.documentId
    override fun revision(document: RoutineDocument): Int = document.revision
    override fun displayName(document: RoutineDocument): String = document.name
    override fun withRevision(document: RoutineDocument, revision: Int, parentHash: String?): RoutineDocument =
        document.copy(revision = revision, parentContentHash = parentHash)

    override fun sameContent(previous: RoutineDocument, draft: RoutineDocument): Boolean = previous == draft.copy(
        schemaVersion = previous.schemaVersion,
        documentId = previous.documentId,
        revision = previous.revision,
        parentContentHash = previous.parentContentHash
    )

}
