package com.ares.analytics.service

import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.DEFAULT_GEMINI_MODEL
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Provider boundary for configured Gemini/Vertex text generation.
 *
 * Domain services own prompts, validation, and interpretation. This class owns only provider
 * configuration, authentication, transport, and response extraction.
 */
class GenerativeAiService(
    private val environmentService: EnvironmentService,
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) { json(AppJson) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30 * 60 * 1_000L
            connectTimeoutMillis = 60 * 1_000L
            socketTimeoutMillis = 30 * 60 * 1_000L
        }
    },
) {
    suspend fun requestStructuredJson(prompt: String): String = request(prompt, structuredJson = true)

    suspend fun requestText(prompt: String): String = request(prompt, structuredJson = false)

    private suspend fun request(prompt: String, structuredJson: Boolean): String = withContext(Dispatchers.IO) {
        val config = environmentService.loadConfig()
            ?: throw IllegalStateException("No active workspace configuration loaded")
        val model = validateModelId(configuredModel(config.geminiModel))
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                })
            })
            if (structuredJson) {
                put("generationConfig", buildJsonObject { put("responseMimeType", "application/json") })
            }
        }
        val response = if ((config.aiMode ?: "STUDIO") == "STUDIO") {
            val apiKey = config.geminiApiKey
                ?: throw IllegalStateException("Gemini API key is not configured in Profile → AI Diagnostics")
            httpClient.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } else {
            val serviceAccountPath = config.vertexServiceAccountPath
                ?: throw IllegalStateException("GCP Service Account path is not configured in Profile → AI Diagnostics")
            val projectId = validateVertexIdentifier(
                config.vertexProjectId
                    ?: throw IllegalStateException("GCP Project ID is not configured in Profile → AI Diagnostics"),
                "vertexProjectId",
            )
            val location = validateVertexIdentifier(config.vertexLocation ?: "us-central1", "vertexLocation")
            val accessToken = vertexAccessToken(serviceAccountPath)
            httpClient.post(
                "https://$location-aiplatform.googleapis.com/v1/projects/$projectId/locations/$location/publishers/google/models/$model:generateContent"
            ) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Generative AI request failed: ${response.bodyAsText().take(1_000)}")
        }
        response.body<JsonObject>()["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: throw IllegalStateException("Generative AI returned no response text")
    }

    private suspend fun vertexAccessToken(serviceAccountJsonPath: String): String {
        val file = File(serviceAccountJsonPath)
        require(file.exists()) { "Service Account file not found at: $serviceAccountJsonPath" }
        val json = AppJson.parseToJsonElement(file.readText()).jsonObject
        val clientEmail = json["client_email"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing client_email")
        val privateKeyPem = json["private_key"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing private_key")
        val tokenUri = validateGoogleTokenUri(
            json["token_uri"]?.jsonPrimitive?.content ?: "https://oauth2.googleapis.com/token"
        )
        val response = httpClient.post(tokenUri) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion" to createJwt(clientEmail, privateKeyPem, tokenUri),
                ).formUrlEncode()
            )
        }
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Service Account token exchange failed: ${response.bodyAsText().take(1_000)}")
        }
        return response.body<JsonObject>()["access_token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Service Account token response omitted access_token")
    }

    private fun createJwt(clientEmail: String, privateKeyPem: String, tokenUri: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val headerJson = buildJsonObject {
            put("alg", "RS256")
            put("typ", "JWT")
        }.toString()
        val claimsJson = buildJsonObject {
            put("iss", clientEmail)
            put("scope", "https://www.googleapis.com/auth/cloud-platform")
            put("aud", tokenUri)
            put("exp", nowSeconds + 3_600L)
            put("iat", nowSeconds)
        }.toString()
        val header = encoder.encodeToString(headerJson.toByteArray(Charsets.UTF_8))
        val claims = encoder.encodeToString(claimsJson.toByteArray(Charsets.UTF_8))
        val unsigned = "$header.$claims"
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey(privateKeyPem))
            update(unsigned.toByteArray(Charsets.UTF_8))
        }
        return "$unsigned.${encoder.encodeToString(signer.sign())}"
    }

    private fun privateKey(pem: String): PrivateKey {
        val decoded = Base64.getDecoder().decode(
            pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
        )
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decoded))
    }

    fun close() = httpClient.close()

    private fun configuredModel(configured: String?): String = configured
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.takeUnless { it == "gemini-1.5-flash" }
        ?: DEFAULT_GEMINI_MODEL

    companion object {
        private val VERTEX_ID_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
        private val MODEL_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,128}$")

        internal fun validateVertexIdentifier(raw: String, field: String): String {
            val trimmed = raw.trim()
            require(VERTEX_ID_REGEX.matches(trimmed)) {
                "Invalid $field '$raw': expected lowercase letters, digits, and hyphens only."
            }
            return trimmed
        }

        internal fun validateModelId(raw: String): String {
            val trimmed = raw.trim()
            require(MODEL_ID_REGEX.matches(trimmed)) {
                "Invalid Gemini model identifier '$raw'."
            }
            return trimmed
        }

        internal fun validateGoogleTokenUri(raw: String): String {
            val trimmed = raw.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: throw IllegalArgumentException("Invalid Vertex token_uri '$raw'")
            val host = uri.host?.lowercase().orEmpty()
            require(
                uri.scheme.equals("https", ignoreCase = true) &&
                    (host == "oauth2.googleapis.com" || host.endsWith(".googleapis.com")) &&
                    uri.userInfo == null &&
                    uri.port == -1 &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            ) {
                "Untrusted Vertex token_uri '$raw': must be an HTTPS Google APIs token endpoint."
            }
            return trimmed
        }
    }
}
