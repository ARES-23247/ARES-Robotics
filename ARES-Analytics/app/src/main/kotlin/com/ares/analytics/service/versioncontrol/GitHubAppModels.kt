package com.ares.analytics.service.versioncontrol

/** The account that owns an approved GitHub App installation. */
enum class GitHubAccountKind { PERSONAL, ORGANIZATION }

/** One personal or organization installation visible to the signed-in user. */
data class GitHubBackupAccount(
    val installationId: Long,
    val login: String,
    val kind: GitHubAccountKind,
    val repositorySelection: String,
    val contentsPermission: String,
    val installationUrl: String,
) {
    val canWriteContents: Boolean get() = contentsPermission.equals("write", ignoreCase = true)
}

/** A repository explicitly granted to an ARES GitHub App installation. */
data class GitHubBackupRepository(
    val installationId: Long,
    val repositoryId: Long,
    val ownerLogin: String,
    val name: String,
    val fullName: String,
    val cloneUrl: String,
    val webUrl: String,
    val visibility: String,
    val isPrivate: Boolean,
    val canPush: Boolean,
    val archived: Boolean,
    val disabled: Boolean,
) {
    val canUseForBackup: Boolean get() = isPrivate && canPush && !archived && !disabled

    val unavailableReason: String?
        get() = when {
            !isPrivate -> "ARES project backups must use a private repository."
            archived -> "This repository is archived and cannot accept a backup."
            disabled -> "This repository is disabled."
            !canPush -> "Your account or the ARES GitHub App does not have write access."
            else -> null
        }
}

/** Fresh permission-scoped destinations returned by GitHub for the current account. */
data class GitHubBackupCatalog(
    val accounts: List<GitHubBackupAccount> = emptyList(),
    val repositories: List<GitHubBackupRepository> = emptyList(),
) {
    fun repositoriesFor(installationId: Long): List<GitHubBackupRepository> =
        repositories.filter { it.installationId == installationId }
}

/** Stable, non-secret identity recorded in local Git configuration for a chosen backup. */
data class GitHubBackupDestination(
    val installationId: Long,
    val repositoryId: Long,
    val ownerLogin: String,
    val repositoryName: String,
    val accountKind: GitHubAccountKind,
    val cloneUrl: String,
    val webUrl: String,
)
