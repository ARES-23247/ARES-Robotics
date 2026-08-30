package com.ares.analytics.service

import com.ares.analytics.shared.models.DriveDestinationType

data class DriveDestinationStatus(
    val type: DriveDestinationType,
    val displayName: String,
    val accountEmail: String,
    val ownerLabel: String,
    val sharingLabel: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val webViewLink: String?,
    val sharedDriveId: String?,
)

class DriveDestinationAccessException(message: String) : IllegalStateException(message)

internal fun extractGoogleDriveFolderId(reference: String): String? {
    val trimmed = reference.trim()
    if (trimmed.matches(Regex("[A-Za-z0-9_-]{10,256}"))) return trimmed
    return Regex("/folders/([A-Za-z0-9_-]{10,256})")
        .find(trimmed)
        ?.groupValues
        ?.get(1)
        ?: Regex("[?&]id=([A-Za-z0-9_-]{10,256})")
            .find(trimmed)
            ?.groupValues
            ?.get(1)
}

internal fun canConfigureDriveDestination(
    type: DriveDestinationType,
    displayName: String,
    existingFolderReference: String,
    sharedDriveId: String?,
    busy: Boolean,
): Boolean = !busy && displayName.isNotBlank() && when (type) {
    DriveDestinationType.PERSONAL_FOLDER,
    DriveDestinationType.TEAM_FOLDER -> true
    DriveDestinationType.SHARED_FOLDER,
    DriveDestinationType.SHARED_DRIVE -> false // Existing folders are granted only through Google Picker.
}

internal fun requireValidDriveDestination(destination: com.ares.analytics.shared.models.DriveDestinationConfig) {
    require(destination.rootFolderId.matches(Regex("[A-Za-z0-9_-]{10,256}"))) {
        "Workspace Drive destination has an invalid root folder ID"
    }
    require(destination.displayName.isNotBlank()) { "Workspace Drive destination has no display name" }
    require(destination.accountSubject.isNotBlank() && destination.accountEmail.contains('@')) {
        "Workspace Drive destination has an invalid Google account binding"
    }
    if (destination.type == DriveDestinationType.SHARED_DRIVE) {
        require(destination.sharedDriveId?.matches(Regex("[A-Za-z0-9_-]{10,256}")) == true) {
            "Workspace Shared Drive destination has no valid Shared Drive ID"
        }
    }
}
