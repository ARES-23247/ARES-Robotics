package com.ares.analytics.service.versioncontrol

import com.ares.analytics.shared.AppJson
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets

@Serializable
internal data class StoredGitHubAppCredential(
    val accessToken: String,
    val accessTokenExpiresAtEpochSeconds: Long,
    val refreshToken: String,
    val refreshTokenExpiresAtEpochSeconds: Long,
    val login: String,
)

/** Owns the protected, current-format GitHub App user credential stored outside robot projects. */
internal class ProjectGitHubCredentialRepository(
    private val store: ProjectBackupCredentialStore,
) {
    fun read(): StoredGitHubAppCredential? = store.read()?.let { bytes ->
        AppJson.decodeFromString(
            StoredGitHubAppCredential.serializer(),
            bytes.toString(StandardCharsets.UTF_8),
        ).also(::validate)
    }

    fun write(credential: StoredGitHubAppCredential) {
        validate(credential)
        store.write(
            AppJson.encodeToString(StoredGitHubAppCredential.serializer(), credential)
                .toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun delete(): Boolean = store.delete()

    fun from(tokens: GitHubUserTokens, login: String, nowEpochSeconds: Long): StoredGitHubAppCredential {
        require(login.matches(Regex("[A-Za-z0-9-]{1,100}"))) {
            "GitHub returned an invalid account identity."
        }
        require(tokens.accessToken.length in 20..2_048 && tokens.refreshToken.length in 20..2_048) {
            "GitHub returned an invalid credential. Sign-in was not saved."
        }
        return StoredGitHubAppCredential(
            accessToken = tokens.accessToken,
            accessTokenExpiresAtEpochSeconds = Math.addExact(nowEpochSeconds, tokens.expiresInSeconds),
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAtEpochSeconds = Math.addExact(nowEpochSeconds, tokens.refreshTokenExpiresInSeconds),
            login = login,
        ).also(::validate)
    }

    private fun validate(credential: StoredGitHubAppCredential) {
        require(credential.accessToken.length in 20..2_048 && credential.refreshToken.length in 20..2_048)
        require(credential.login.matches(Regex("[A-Za-z0-9-]{1,100}")))
        require(credential.accessTokenExpiresAtEpochSeconds > 0 && credential.refreshTokenExpiresAtEpochSeconds > 0)
    }
}
