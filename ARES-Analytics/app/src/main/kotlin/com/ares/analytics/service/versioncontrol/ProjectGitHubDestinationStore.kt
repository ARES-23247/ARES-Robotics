package com.ares.analytics.service.versioncontrol

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import java.net.URI

/** Owns the approved GitHub destination metadata and matching origin remote for one project. */
internal class ProjectGitHubDestinationStore {
    fun write(git: Git, account: GitHubBackupAccount, repository: GitHubBackupRepository) {
        val config = git.repository.config
        config.setLong(CONFIG_SECTION, null, "installationId", account.installationId)
        config.setLong(CONFIG_SECTION, null, "repositoryId", repository.repositoryId)
        config.setString(CONFIG_SECTION, null, "owner", repository.ownerLogin)
        config.setString(CONFIG_SECTION, null, "repository", repository.name)
        config.setString(CONFIG_SECTION, null, "accountKind", account.kind.name)
        config.setString(CONFIG_SECTION, null, "cloneUrl", repository.cloneUrl)
        config.setString(CONFIG_SECTION, null, "webUrl", repository.webUrl)
        config.save()
    }

    fun read(git: Git): GitHubBackupDestination? {
        val config = git.repository.config
        val installationId = config.getLong(CONFIG_SECTION, null, "installationId", -1L)
        val repositoryId = config.getLong(CONFIG_SECTION, null, "repositoryId", -1L)
        val owner = config.getString(CONFIG_SECTION, null, "owner") ?: return null
        val repository = config.getString(CONFIG_SECTION, null, "repository") ?: return null
        val kind = config.getString(CONFIG_SECTION, null, "accountKind")
            ?.let { runCatching { GitHubAccountKind.valueOf(it) }.getOrNull() } ?: return null
        val cloneUrl = config.getString(CONFIG_SECTION, null, "cloneUrl") ?: return null
        val webUrl = config.getString(CONFIG_SECTION, null, "webUrl") ?: return null
        if (installationId <= 0 || repositoryId <= 0 || owner.isBlank() || repository.isBlank()) return null
        validateRepositoryUrl(cloneUrl)
        validateWebUrl(webUrl)
        val origin = originUrl(git) ?: return null
        if (!sameRepository(origin, cloneUrl)) return null
        return GitHubBackupDestination(installationId, repositoryId, owner, repository, kind, cloneUrl, webUrl)
    }

    fun clear(git: Git) {
        git.repository.config.apply {
            unsetSection(CONFIG_SECTION, null)
            save()
        }
    }

    fun originUrl(git: Git): String? = git.remoteList().call()
        .singleOrNull { it.name == ORIGIN_REMOTE }
        ?.urIs?.singleOrNull()?.toString()

    fun addOrigin(git: Git, cloneUrl: String) {
        validateRepositoryUrl(cloneUrl)
        git.remoteAdd().setName(ORIGIN_REMOTE).setUri(URIish(cloneUrl)).call()
    }

    fun updateOrigin(git: Git, cloneUrl: String) {
        validateRepositoryUrl(cloneUrl)
        git.remoteSetUrl().setRemoteName(ORIGIN_REMOTE).setRemoteUri(URIish(cloneUrl)).call()
    }

    fun removeOrigin(git: Git) {
        git.remoteRemove().setRemoteName(ORIGIN_REMOTE).call()
    }

    fun sameRepository(first: String, second: String): Boolean =
        repositoryIdentity(first)?.equals(repositoryIdentity(second), ignoreCase = true) == true

    private fun validateRepositoryUrl(raw: String) {
        val uri = runCatching { URI(raw) }.getOrElse { error("The GitHub repository URL is invalid.") }
        require(uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) && uri.userInfo == null) {
            "Project Backup only accepts credential-free HTTPS repositories on github.com."
        }
        val segments = uri.path.trim('/').removeSuffix(".git").split('/')
        require(segments.size == 2 && segments.all { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }) {
            "The GitHub repository URL must identify one owner and repository."
        }
    }

    private fun validateWebUrl(raw: String) {
        validateRepositoryUrl(raw.removeSuffix("/") + if (raw.endsWith(".git")) "" else ".git")
    }

    private fun repositoryIdentity(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "https" || !uri.host.equals("github.com", ignoreCase = true) || uri.userInfo != null) {
            return null
        }
        val segments = uri.path.trim('/').removeSuffix(".git").split('/')
        if (segments.size != 2 || segments.any(String::isBlank)) return null
        return segments.joinToString("/")
    }

    private companion object {
        const val ORIGIN_REMOTE = "origin"
        const val CONFIG_SECTION = "aresBackup"
    }
}
