package com.ares.analytics.viewmodel.subsystem

import com.ares.analytics.service.writeFileAtomically
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemImplementationKind
import java.io.File

internal fun ensureXrpExtensionScaffold(projectPath: String, document: SubsystemDocument) {
    if (document.platform != SubsystemPlatform.XRP ||
        document.implementation.kind != SubsystemImplementationKind.HAND_AUTHORED
    ) return
    val relative = document.implementation.sourceFiles.singleOrNull()
        ?: error("An XRP extension must declare exactly one USER-OWNED Python source file")
    val root = File(projectPath).canonicalFile
    val target = File(root, relative).canonicalFile
    require(target.toPath().startsWith(root.toPath()) && target.extension == "py") {
        "The XRP extension source must be a project-relative Python file"
    }
    if (target.exists()) return
    writeFileAtomically(target) { temporary ->
        temporary.writeText(
            """
            |'''USER-OWNED XRP subsystem extension. Studio will never overwrite this file.'''
            |
            |class UserSubsystem:
            |    def __init__(self, hardware_factory):
            |        self.hardware_factory = hardware_factory
            |        self.document_id = ${document.documentId.quotedPython()}
            |        self.capability_action_keys = ()
            |        self.faulted = False
            |
            |    def periodic(self, dt):
            |        pass
            |
            |    def stop(self):
            |        pass
            |
            |    def recover_neutral(self):
            |        self.stop()
            |        self.faulted = False
            |        return True
            |
            |    def handle_action(self, action_key, arguments):
            |        raise ValueError("Unsupported action: " + action_key)
            |
            |
            |def create_subsystem(hardware_factory):
            |    return UserSubsystem(hardware_factory)
            |
            |
            |def create_simulated_subsystem(hardware_factory):
            |    return UserSubsystem(hardware_factory)
            |""".trimMargin(),
        )
    }
}

private fun String.quotedPython(): String = "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"
