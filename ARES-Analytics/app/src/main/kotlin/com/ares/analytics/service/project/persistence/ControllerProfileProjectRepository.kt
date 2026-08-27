package com.ares.analytics.service.project.persistence

import com.areslib.controls.ControllerProfileCodec
import com.areslib.controls.ControllerProfileDocument

/** Offline repository for visual/raw-HID `.arescontroller` profiles under `.ares/controllers`. */
class ControllerProfileProjectRepository : VersionedProjectDocumentStore<ControllerProfileDocument>(
    kind = ProjectDocumentKind.CONTROLLER_PROFILE,
    directoryName = "controllers",
    historyName = "controllers",
    extension = "arescontroller"
) {
    override fun encode(document: ControllerProfileDocument): String = ControllerProfileCodec.encode(document)
    override fun decode(json: String): ControllerProfileDocument = ControllerProfileCodec.decode(json)
    override fun contentHash(document: ControllerProfileDocument): String = ControllerProfileCodec.contentHash(document)
    override fun documentId(document: ControllerProfileDocument): String = document.documentId
    override fun revision(document: ControllerProfileDocument): Int = document.revision
    override fun displayName(document: ControllerProfileDocument): String = document.displayName
    override fun withRevision(
        document: ControllerProfileDocument,
        revision: Int,
        parentHash: String?
    ): ControllerProfileDocument = document.copy(revision = revision, parentContentHash = parentHash)

    override fun sameContent(
        previous: ControllerProfileDocument,
        draft: ControllerProfileDocument
    ): Boolean = previous == draft.copy(
        schemaVersion = previous.schemaVersion,
        documentId = previous.documentId,
        revision = previous.revision,
        parentContentHash = previous.parentContentHash
    )
}
