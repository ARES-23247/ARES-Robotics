package com.ares.analytics.mcp.engine

import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** Status of validating an explicit confirmation token prior to a guarded mutation. */
sealed interface ConfirmationCheckResult {
    data object Approved : ConfirmationCheckResult
    data class Rejected(val message: String) : ConfirmationCheckResult
}

/**
 * Enforces explicit preview-before-apply confirmation for `GENERATED STARTER` codegen writes,
 * descriptor deletions, canonical tuning promotions, and existing `USER-OWNED` overwrites.
 */
class ConfirmationTokenManager(
    private val clock: Clock = Clock.systemUTC(),
    private val ttlMillis: Long = 15 * 60 * 1000L,
) {
    private data class PendingToken(
        val key: String,
        val token: String,
        val digest: String,
        val expiresAtMillis: Long,
    )

    private val pending = ConcurrentHashMap<String, PendingToken>()

    fun recordPreviewToken(projectRoot: File, reconcilerToken: String): String {
        val canonicalRoot = projectRoot.canonicalPathSafe()
        val now = clock.millis()
        pruneExpired(now)
        val entryKey = "starter::$canonicalRoot"
        pending[entryKey] = PendingToken(
            key = entryKey,
            token = reconcilerToken,
            digest = reconcilerToken,
            expiresAtMillis = now + ttlMillis,
        )
        return reconcilerToken
    }

    fun consumeForReplacement(
        projectRoot: File,
        suppliedToken: String?,
        requiredReconcilerToken: String?,
    ): ConfirmationCheckResult {
        if (requiredReconcilerToken == null) return ConfirmationCheckResult.Approved
        if (suppliedToken.isNullOrBlank()) {
            return ConfirmationCheckResult.Rejected(
                "Applying changes to existing GENERATED STARTER files requires first running " +
                    "ares_codegen_reconcile with mode='PREVIEW' and passing back confirmationToken.",
            )
        }
        val canonicalRoot = projectRoot.canonicalPathSafe()
        val now = clock.millis()
        pruneExpired(now)
        val preview = pending["starter::$canonicalRoot"]
        if (preview == null) {
            return ConfirmationCheckResult.Rejected(
                "No active starter preview token exists for '$canonicalRoot' (or it expired). " +
                    "Run mode='PREVIEW' again before applying replacements.",
            )
        }
        if (suppliedToken != requiredReconcilerToken || preview.token != requiredReconcilerToken) {
            return ConfirmationCheckResult.Rejected(
                "Provided confirmationToken does not match the current starter diff on disk. " +
                    "Re-run mode='PREVIEW' to inspect updated diffs and obtain a fresh token.",
            )
        }
        pending.remove("starter::$canonicalRoot")
        return ConfirmationCheckResult.Approved
    }

    fun issueTargetToken(
        projectRoot: File,
        operationKey: String,
        targetDigest: String,
    ): String {
        val canonicalRoot = projectRoot.canonicalPathSafe()
        val now = clock.millis()
        pruneExpired(now)
        val entryKey = "$operationKey::$canonicalRoot"
        val token = sha256Hex("$canonicalRoot|$operationKey|$targetDigest")
        pending[entryKey] = PendingToken(
            key = entryKey,
            token = token,
            digest = targetDigest,
            expiresAtMillis = now + ttlMillis,
        )
        return token
    }

    fun verifyAndConsumeTargetToken(
        projectRoot: File,
        operationKey: String,
        currentDigest: String,
        suppliedToken: String?,
    ): ConfirmationCheckResult {
        if (suppliedToken.isNullOrBlank()) {
            return ConfirmationCheckResult.Rejected(
                "This operation ($operationKey) requires a two-phase preview confirmationToken. " +
                    "Preview first, review the diff/summary, and pass the returned confirmationToken.",
            )
        }
        val canonicalRoot = projectRoot.canonicalPathSafe()
        val now = clock.millis()
        pruneExpired(now)
        val entryKey = "$operationKey::$canonicalRoot"
        val expected = pending[entryKey]
            ?: return ConfirmationCheckResult.Rejected(
                "No pending preview confirmationToken for '$operationKey' (or it expired). Preview again.",
            )
        val recomputedToken = sha256Hex("$canonicalRoot|$operationKey|$currentDigest")
        if (expected.digest != currentDigest || recomputedToken != expected.token || suppliedToken != expected.token) {
            return ConfirmationCheckResult.Rejected(
                "Stale confirmationToken for '$operationKey': target files on disk changed since preview. Preview again.",
            )
        }
        pending.remove(entryKey)
        return ConfirmationCheckResult.Approved
    }

    private fun pruneExpired(nowMillis: Long) {
        pending.entries.removeIf { (_, entry) -> entry.expiresAtMillis <= nowMillis }
    }

    companion object {
        const val USER_OWNED_MARKER = "// ARES OWNERSHIP: USER-OWNED"
        const val GENERATED_DO_NOT_EDIT_MARKER = "// ARES OWNERSHIP: GENERATED DO NOT EDIT"
        const val GENERATED_STARTER_MARKER = "// ARES OWNERSHIP: GENERATED STARTER"

        fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun digestFileOrMissing(file: File): String =
            if (file.isFile) sha256Hex(file.readText().replace("\r\n", "\n")) else "missing"

        fun isProtectedUserOwnedFile(file: File): Boolean {
            if (!file.isFile) return false
            val header = runCatching {
                file.bufferedReader().use { reader ->
                    buildString {
                        repeat(5) {
                            val line = reader.readLine() ?: return@repeat
                            appendLine(line)
                        }
                    }
                }
            }.getOrDefault("")
            return header.contains(USER_OWNED_MARKER)
        }

        fun isGeneratedDoNotEditFile(file: File): Boolean {
            if (!file.isFile) return false
            val header = runCatching {
                file.bufferedReader().use { reader -> reader.readLine().orEmpty() }
            }.getOrDefault("")
            return header.contains(GENERATED_DO_NOT_EDIT_MARKER)
        }
    }
}

internal fun File.canonicalPathSafe(): String =
    runCatching { canonicalFile.path }.getOrElse { absoluteFile.normalize().path }
