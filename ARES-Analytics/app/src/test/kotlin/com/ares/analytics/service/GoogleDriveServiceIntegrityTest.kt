package com.ares.analytics.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.mockito.Mockito.mock
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GoogleDriveServiceIntegrityTest {
    @Test
    fun `streaming session upload always creates a new Drive object`() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request.method.value
                    when (request.method) {
                        HttpMethod.Post -> {
                            assertEquals("resumable", request.url.parameters["uploadType"])
                            respond(
                                "",
                                headers = headersOf(
                                    HttpHeaders.Location,
                                    "https://upload.example/session"
                                )
                            )
                        }

                        HttpMethod.Put -> respond(
                            """{"id":"new-object"}""",
                            headers = headersOf(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json.toString()
                            )
                        )

                        else -> error("unexpected Drive method ${request.method.value}")
                    }
                }
            }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = GoogleDriveService(
            mock(OAuthService::class.java),
            mock(EnvironmentService::class.java),
            client,
            accessTokenOverride = { "token" },
        )
        val parquet = Files.createTempFile("ares-immutable-upload", ".parquet").toFile()
        parquet.writeBytes("immutable parquet".toByteArray())
        try {
            val objectId = service.createFileStreaming(
                name = "canonical.parquet",
                file = parquet,
                parentId = "sessions-folder",
                mimeType = "application/octet-stream"
            )

            assertEquals("new-object", objectId)
            assertEquals(listOf("POST", "PUT"), requests)
            assertFalse("PATCH" in requests)
        } finally {
            service.dispose()
            parquet.delete()
        }
    }

    @Test
    fun `download requires exact id name size and digest`() = runTest {
        val content = "verified parquet".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    if (request.url.parameters["alt"] == "media") {
                        respond(content, headers = headersOf(HttpHeaders.ContentLength, content.size.toString()))
                    } else {
                        respond(
                            """{"id":"file-id","name":"canonical.parquet","size":"${content.size}"}""",
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        )
                    }
                }
            }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = GoogleDriveService(
            mock(OAuthService::class.java),
            mock(EnvironmentService::class.java),
            client,
            accessTokenOverride = { "token" },
        )
        val tempDir = Files.createTempDirectory("ares-drive-integrity").toFile()
        try {
            val valid = tempDir.resolve("valid.parquet")
            service.readFileStreaming("file-id", valid, "canonical.parquet", content.size.toLong(), digest)
            assertContentEquals(content, valid.readBytes())

            val invalid = tempDir.resolve("invalid.parquet")
            assertFailsWith<IllegalArgumentException> {
                service.readFileStreaming("file-id", invalid, "canonical.parquet", content.size.toLong(), "0".repeat(64))
            }
            assertFalse(invalid.exists())
        } finally {
            service.dispose()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `duplicate exact Drive names fail closed`() = runTest {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        """{"files":[{"id":"first"},{"id":"second"}]}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = GoogleDriveService(
            mock(OAuthService::class.java),
            mock(EnvironmentService::class.java),
            client,
            accessTokenOverride = { "token" },
        )
        try {
            assertFailsWith<IllegalArgumentException> { service.findFile("canonical.parquet", "folder") }
        } finally {
            service.dispose()
        }
    }
}
