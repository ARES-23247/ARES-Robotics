package com.ares.analytics.service.project.persistence

import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSchemeDocument

/** Offline repository for versioned `.arescontrols` mappings under `.ares/controls`. */
class ControlSchemeProjectRepository : VersionedProjectDocumentStore<ControlSchemeDocument>(
    kind = ProjectDocumentKind.CONTROL_SCHEME,
    directoryName = "controls",
    historyName = "controls",
    extension = "arescontrols"
) {
    override fun encode(document: ControlSchemeDocument): String = ControlSchemeCodec.encode(document)
    override fun decode(json: String): ControlSchemeDocument = ControlSchemeCodec.decode(json)
    override fun contentHash(document: ControlSchemeDocument): String = ControlSchemeCodec.contentHash(document)
    override fun documentId(document: ControlSchemeDocument): String = document.documentId
    override fun revision(document: ControlSchemeDocument): Int = document.revision
    override fun displayName(document: ControlSchemeDocument): String = document.name
    override fun withRevision(document: ControlSchemeDocument, revision: Int, parentHash: String?): ControlSchemeDocument =
        document.copy(revision = revision, parentContentHash = parentHash)

    override fun sameContent(previous: ControlSchemeDocument, draft: ControlSchemeDocument): Boolean = previous == draft.copy(
        schemaVersion = previous.schemaVersion,
        documentId = previous.documentId,
        revision = previous.revision,
        parentContentHash = previous.parentContentHash
    )
}
