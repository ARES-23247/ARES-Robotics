package com.ares.analytics.service.versioncontrol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class GitHubDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

internal data class GitHubUserTokens(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String,
    val refreshTokenExpiresInSeconds: Long,
)

internal sealed class GitHubDevicePollResult {
    object Pending : GitHubDevicePollResult()
    object SlowDown : GitHubDevicePollResult()
    data class Authorized(val tokens: GitHubUserTokens) : GitHubDevicePollResult()
    data class Failed(val code: String) : GitHubDevicePollResult()
}

internal class GitHubAuthorizationException(val code: String) : IllegalStateException(code)

internal class GitHubApiException(val status: Int, message: String) : IllegalStateException(message)

internal interface GitHubProjectApi {
    fun beginDeviceAuthorization(clientId: String): GitHubDeviceAuthorization
    fun pollDeviceAuthorization(clientId: String, deviceCode: String): GitHubDevicePollResult
    fun refreshUserAccessToken(clientId: String, refreshToken: String): GitHubUserTokens
    fun currentLogin(token: String): String
    fun listInstallations(token: String): List<GitHubBackupAccount>
    fun listRepositories(token: String, installationId: Long): List<GitHubBackupRepository>
}

/** Narrow GitHub App user-to-server client. It never accepts a client secret or arbitrary URL. */
internal class DefaultGitHubProjectApi : GitHubProjectApi {
    override fun beginDeviceAuthorization(clientId: String): GitHubDeviceAuthorization {
        val json = postForm(
            "https://github.com/login/device/code",
            mapOf("client_id" to clientId),
        )
        return GitHubDeviceAuthorization(
            deviceCode = json.requiredString("device_code"),
            userCode = json.requiredString("user_code"),
            verificationUri = json.requiredString("verification_uri"),
            expiresInSeconds = json.get("expires_in")?.asLong ?: 900L,
            intervalSeconds = json.get("interval")?.asLong ?: 5L,
        )
    }

    override fun pollDeviceAuthorization(clientId: String, deviceCode: String): GitHubDevicePollResult {
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to clientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        json.get("access_token")?.asString?.takeIf(String::isNotBlank)?.let {
            return GitHubDevicePollResult.Authorized(parseExpiringTokens(json))
        }
        return when (val error = json.get("error")?.asString.orEmpty()) {
            "authorization_pending" -> GitHubDevicePollResult.Pending
            "slow_down" -> GitHubDevicePollResult.SlowDown
            else -> GitHubDevicePollResult.Failed(error.ifBlank { "unknown_error" })
        }
    }

    override fun refreshUserAccessToken(clientId: String, refreshToken: String): GitHubUserTokens {
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
            ),
        )
        json.get("error")?.asString?.takeIf(String::isNotBlank)?.let { throw GitHubAuthorizationException(it) }
        return parseExpiringTokens(json)
    }

    override fun currentLogin(token: String): String =
        authorizedObject("https://api.github.com/user", token).requiredString("login")

    override fun listInstallations(token: String): List<GitHubBackupAccount> =
        pagedObjects("https://api.github.com/user/installations", "installations", token).map { installation ->
            val account = installation.requiredObject("account")
            val targetType = installation.requiredString("target_type")
            GitHubBackupAccount(
                installationId = installation.requiredPositiveLong("id"),
                login = account.requiredString("login"),
                kind = when (targetType.lowercase()) {
                    "organization" -> GitHubAccountKind.ORGANIZATION
                    "user" -> GitHubAccountKind.PERSONAL
                    else -> error("GitHub returned an unsupported installation account type.")
                },
                repositorySelection = installation.get("repository_selection")?.asString.orEmpty().ifBlank { "selected" },
                contentsPermission = installation.getAsJsonObject("permissions")?.get("contents")?.asString.orEmpty(),
                installationUrl = installation.get("html_url")?.asString.orEmpty(),
            )
        }

    override fun listRepositories(token: String, installationId: Long): List<GitHubBackupRepository> {
        require(installationId > 0) { "GitHub installation identity is invalid." }
        return pagedObjects(
            "https://api.github.com/user/installations/$installationId/repositories",
            "repositories",
            token,
        ).map { repository ->
            val owner = repository.requiredObject("owner")
            GitHubBackupRepository(
                installationId = installationId,
                repositoryId = repository.requiredPositiveLong("id"),
                ownerLogin = owner.requiredString("login"),
                name = repository.requiredString("name"),
                fullName = repository.requiredString("full_name"),
                cloneUrl = repository.requiredString("clone_url"),
                webUrl = repository.requiredString("html_url"),
                visibility = repository.get("visibility")?.asString.orEmpty().ifBlank {
                    if (repository.get("private")?.asBoolean == true) "private" else "public"
                },
                isPrivate = repository.get("private")?.asBoolean == true,
                canPush = repository.getAsJsonObject("permissions")?.get("push")?.asBoolean == true,
                archived = repository.get("archived")?.asBoolean == true,
                disabled = repository.get("disabled")?.asBoolean == true,
            )
        }
    }

    private fun parseExpiringTokens(json: JsonObject): GitHubUserTokens {
        val accessToken = json.requiredString("access_token")
        val refreshToken = json.requiredString("refresh_token")
        val expiresIn = json.requiredPositiveLong("expires_in")
        val refreshExpiresIn = json.requiredPositiveLong("refresh_token_expires_in")
        require(expiresIn <= MAX_TOKEN_LIFETIME_SECONDS && refreshExpiresIn <= MAX_REFRESH_LIFETIME_SECONDS) {
            "GitHub returned an invalid credential lifetime. Sign-in was not saved."
        }
        return GitHubUserTokens(accessToken, expiresIn, refreshToken, refreshExpiresIn)
    }

    private fun pagedObjects(baseUrl: String, arrayName: String, token: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var page = 1
        var expected = Int.MAX_VALUE
        while (result.size < expected) {
            require(page <= MAX_PAGES) { "GitHub returned too many backup destinations for this ARES version." }
            val separator = if ('?' in baseUrl) '&' else '?'
            val json = authorizedObject("$baseUrl${separator}per_page=$PAGE_SIZE&page=$page", token)
            expected = json.get("total_count")?.asInt?.coerceAtLeast(0) ?: 0
            val values = json.getAsJsonArray(arrayName) ?: JsonArray()
            if (values.size() == 0) break
            values.forEach { element -> result += element.asJsonObject }
            page++
        }
        require(result.size >= expected) { "GitHub returned an incomplete backup destination list." }
        return result
    }

    private fun postForm(url: String, values: Map<String, String>): JsonObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        return requestJson(url, "POST", body, token = null, contentType = "application/x-www-form-urlencoded")
    }

    private fun authorizedObject(url: String, token: String): JsonObject =
        requestJson(url, "GET", body = null, token = token, contentType = "application/json")

    private fun requestJson(
        rawUrl: String,
        method: String,
        body: String?,
        token: String?,
        contentType: String,
    ): JsonObject {
        val uri = URI(rawUrl)
        require(uri.scheme == "https" && uri.host in setOf("github.com", "api.github.com")) {
            "Unexpected GitHub endpoint."
        }
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "ARES-Analytics Project Backup")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { readLimitedUtf8(it, MAX_RESPONSE_CHARS) }.orEmpty()
            if (status !in 200..299) {
                throw GitHubApiException(
                    status,
                    when (status) {
                        401 -> "GitHub access was revoked or expired. Sign in again."
                        403 -> "GitHub denied this operation. Ask a team owner to check the ARES GitHub App installation and repository access."
                        404 -> "The GitHub installation or repository is no longer available to this account."
                        else -> "GitHub returned HTTP $status. No local project files were changed."
                    },
                )
            }
            return JsonParser.parseString(response).asJsonObject
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 20
        const val MAX_RESPONSE_CHARS = 512 * 1024
        const val MAX_TOKEN_LIFETIME_SECONDS = 24L * 60L * 60L
        const val MAX_REFRESH_LIFETIME_SECONDS = 400L * 24L * 60L * 60L
    }
}

private fun readLimitedUtf8(input: InputStream, limit: Int): String {
    val reader = InputStreamReader(input, StandardCharsets.UTF_8)
    val output = StringBuilder()
    val buffer = CharArray(8 * 1024)
    while (true) {
        val read = reader.read(buffer)
        if (read < 0) break
        require(output.length + read <= limit) { "GitHub returned an unexpectedly large response." }
        output.append(buffer, 0, read)
    }
    return output.toString()
}

private fun JsonObject.requiredString(name: String): String =
    get(name)?.asString?.takeIf(String::isNotBlank) ?: error("GitHub response is missing '$name'.")

private fun JsonObject.requiredPositiveLong(name: String): Long =
    get(name)?.asLong?.takeIf { it > 0 } ?: error("GitHub response has an invalid '$name'.")

private fun JsonObject.requiredObject(name: String): JsonObject =
    getAsJsonObject(name) ?: error("GitHub response is missing '$name'.")
