package com.areslib.project.model

import com.areslib.project.schema.ProjectDocumentKind
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.resolveTuningProfiles
import com.areslib.tuning.validateTuningParameterDeclarations
import com.areslib.tuning.validateTuningProfileDocument

internal fun validateTuningDocuments(
    snapshot: RobotProjectSnapshot,
    issues: MutableList<ProjectModelIssue>,
) {
    val seenTuningComponentUids = linkedSetOf<String>()
    snapshot.tuningComponents.forEach { component ->
        if (!seenTuningComponentUids.add(component.uid)) {
            issues.add(
                ProjectModelIssue(
                    ProjectModelSeverity.ERROR,
                    ProjectDocumentKind.TUNING_COMPONENT,
                    component.uid,
                    "uid",
                    "duplicate_id",
                    "Duplicate tuning component UID '${component.uid}'",
                )
            )
        }
    }
    val seenTuningProfileUids = linkedSetOf<String>()
    snapshot.tuningProfiles.forEach { profile ->
        if (!seenTuningProfileUids.add(profile.uid)) {
            issues.add(
                ProjectModelIssue(
                    ProjectModelSeverity.ERROR,
                    ProjectDocumentKind.TUNING_PROFILE,
                    profile.uid,
                    "uid",
                    "duplicate_id",
                    "Duplicate tuning profile UID '${profile.uid}'",
                )
            )
        }
    }
    val tuningDeclarations = buildList<TuningParameterDeclaration> {
        snapshot.tuningComponents.forEach { addAll(it.parameters) }
        snapshot.drivetrains.forEach { addAll(it.parameters) }
        snapshot.subsystems.forEach { addAll(it.tuningParameters) }
    }
    validateTuningParameterDeclarations(tuningDeclarations).forEach {
        issues.add(
            ProjectModelIssue(
                ProjectModelSeverity.ERROR,
                ProjectDocumentKind.TUNING_COMPONENT,
                null,
                it.path,
                "tuning_declaration",
                it.message,
            )
        )
    }
    var profileDocErrors = 0
    snapshot.tuningProfiles.forEach { profile ->
        validateTuningProfileDocument(profile, tuningDeclarations).forEach {
            profileDocErrors++
            issues.add(
                ProjectModelIssue(
                    ProjectModelSeverity.ERROR,
                    ProjectDocumentKind.TUNING_PROFILE,
                    profile.uid,
                    it.path,
                    "tuning_profile",
                    it.message,
                )
            )
        }
    }
    if (snapshot.tuningProfiles.isNotEmpty() && profileDocErrors == 0 && seenTuningProfileUids.size == snapshot.tuningProfiles.size) {
        runCatching {
            resolveTuningProfiles(snapshot.tuningProfiles, tuningDeclarations)
        }.onFailure { failure ->
            issues.add(
                ProjectModelIssue(
                    ProjectModelSeverity.ERROR,
                    ProjectDocumentKind.TUNING_PROFILE,
                    null,
                    "baseProfileUid",
                    "tuning_profile_resolution",
                    failure.message ?: "Tuning profiles failed resolution",
                )
            )
        }
    }
}
