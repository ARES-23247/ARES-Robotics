package com.ares.analytics.service.versioncontrol

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.util.Locale

internal enum class RemoteOperation { PUSH, FETCH }

internal fun <T> invokeRemoteOperation(operation: RemoteOperation, block: () -> T): T = try {
    block()
} catch (failure: Exception) {
    val safeMessage = friendlyRemoteFailure(operation, failure)
    if (safeMessage == failure.message) throw failure
    throw IllegalStateException(safeMessage, failure)
}

internal fun pushWithJGit(git: Git, accessToken: String) {
    val results = git.push()
        .setRemote("origin")
        .setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", accessToken))
        .setPushAll()
        .call()
    val failures = results.flatMap { it.remoteUpdates }
        .filter { update -> update.status.name !in setOf("OK", "UP_TO_DATE") }
    require(failures.isEmpty()) {
        "GitHub rejected the backup update (${failures.joinToString { it.status.name }}). Nothing remote was overwritten; refresh and resolve the history difference before retrying."
    }
}

internal fun fetchMainWithJGit(git: Git, accessToken: String): ObjectId {
    git.fetch()
        .setRemote("origin")
        .setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", accessToken))
        .setRefSpecs(RefSpec("+refs/heads/main:refs/remotes/origin/main"))
        .call()
    return git.repository.resolve("refs/remotes/origin/main")
        ?: error("The selected GitHub repository does not contain a main branch to restore.")
}

private fun friendlyRemoteFailure(operation: RemoteOperation, failure: Throwable): String {
    val messages = generateSequence(failure) { it.cause }
        .mapNotNull(Throwable::message)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    if (failure.message?.startsWith("GitHub ") == true || failure.message?.startsWith("The selected GitHub ") == true) {
        return failure.message.orEmpty()
    }
    val permissionDenied = listOf(
        "git-receive-pack not permitted",
        "git-upload-pack not permitted",
        "repository not found",
        "not authorized",
        "unauthorized",
        "forbidden",
        "status code: 403",
        "status code 403",
        "authentication is required",
    ).any(messages::contains)
    if (permissionDenied) {
        val access = if (operation == RemoteOperation.PUSH) "write to" else "read"
        return "ARES no longer has permission to $access this GitHub repository. " +
            "Ask a team owner to restore the ARES GitHub App's repository access, then choose Refresh destinations. " +
            "Local project history is unchanged."
    }
    val unreachable = listOf(
        "timed out",
        "timeout",
        "unknownhost",
        "connection reset",
        "connection refused",
        "network is unreachable",
        "could not resolve host",
    ).any(messages::contains)
    if (unreachable) {
        return "GitHub could not be reached. Check the internet connection and try again. Local project history is unchanged."
    }
    return if (operation == RemoteOperation.PUSH) {
        "GitHub could not update this backup. Refresh destinations and try again; local project history is unchanged."
    } else {
        "GitHub could not check this backup. Refresh destinations and try again; local project history is unchanged."
    }
}
