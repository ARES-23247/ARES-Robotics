package com.ares.analytics.service

import com.ares.analytics.shared.models.DriveDestinationConfig
import com.ares.analytics.shared.models.DriveDestinationType
import com.ares.analytics.shared.models.WorkspaceCollaborationMode
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.toByteReadChannel

/**
 * Escapes a literal for use inside a single-quoted segment of a Google Drive API v3
 * query string. The Drive query language uses `'...'` string literals and escapes a
 * literal backslash as `\\` and a single quote as `''`. Failing to escape lets a `'` in
 * a name/substring break out of the literal and inject query clauses (AUDIT M9).
 */
private fun escapeDriveQuery(value: String): String = value.replace("\\", "\\\\").replace("'", "''")

private fun JsonElement.requiredDriveId(context: String): String =
    ((this as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
        ?: throw IllegalStateException("Google Drive returned $context without a file id")

internal data class DriveFileSnapshot(val bytes: ByteArray, val version: Long)

internal class DrivePreconditionFailedException(message: String) : IllegalStateException(message)

private fun createGoogleDriveHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

/**
 * Service managing Google Drive API v3 interactions for cloud backup of match telemetry logs and session archives.
 *
 * Utilizes OAuth 2.0 PKCE authentication via [OAuthService] to request OAuth access tokens, uploading Parquet and JSONL log files
 * directly to the user's Google Drive storage.
 *
 * ### REST Endpoint Targets:
 * - File Search: `GET https://www.googleapis.com/drive/v3/files`
 * - Resumable Upload: `POST https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable`
 *
 * ### Thread Safety & Performance Guarantees:
 * All file upload network calls run asynchronously on `Dispatchers.IO`. Uses Ktor CIO engine.
 *
 * @param oauthService OAuth authentication provider service.
 * @param environmentService Workspace settings service.
 * @param firebaseClientService Firebase auth service.
 *
 * @see OAuthService
 * @see SyncEngineService
 */
class GoogleDriveService(
    private val oauthService: OAuthService,
    private val environmentService: EnvironmentService,
    private val httpClient: HttpClient = createGoogleDriveHttpClient(),
    private val accessTokenOverride: (suspend () -> String)? = null,
    private val enforceWorkspaceScope: Boolean = accessTokenOverride == null,
) {
    private val folderMutationMutex = Mutex()

    private data class DriveScope(
        val token: String,
        val workspaceId: String,
        val destination: DriveDestinationConfig,
    )

    private data class DriveMetadata(
        val id: String,
        val name: String,
        val mimeType: String,
        val parents: List<String>,
        val driveId: String?,
        val ownedByMe: Boolean,
        val canRead: Boolean,
        val canWrite: Boolean,
        val webViewLink: String?,
        val ownerEmails: List<String>,
        val permissionLabels: List<String>,
    )

    private suspend fun getAccessToken(): String {
        accessTokenOverride?.let { return it() }
        return oauthService.refreshGoogleAccessToken()
            ?: throw IllegalStateException("Google sign-in expired or was revoked. Sign in with Google again; local ARES features remain available.")
    }

    private suspend fun activeScope(): DriveScope {
        val config = environmentService.loadConfig()
            ?: throw DriveDestinationAccessException("Choose an active ARES workspace before using Google Drive.")
        val destination = config.driveDestination
            ?: throw DriveDestinationAccessException(
                "Choose a Drive destination for this workspace before synchronizing. No files were scanned or changed.",
            )
        requireValidDriveDestination(destination)
        val identity = oauthService.authState.value as? AuthState.Authenticated
            ?: throw DriveDestinationAccessException("Sign in with Google before using this workspace's Drive destination.")
        if (identity.uid != destination.accountSubject || !identity.email.equals(destination.accountEmail, ignoreCase = true)) {
            throw DriveDestinationAccessException(
                "This workspace belongs to ${destination.accountEmail}, but ARES is signed in as ${identity.email}. Switch Google accounts or choose a new destination.",
            )
        }
        return DriveScope(getAccessToken(), config.id, destination)
    }

    private fun driveApiFailure(operation: String, status: HttpStatusCode): DriveDestinationAccessException {
        val failure = when (status) {
            HttpStatusCode.Unauthorized -> DriveDestinationAccessException(
                "$operation failed because Google sign-in expired or was revoked. Sign in again.",
            )
            HttpStatusCode.Forbidden -> DriveDestinationAccessException(
                "$operation was denied by Google Drive. Ask the folder or Shared Drive owner for access, or choose another destination.",
            )
            HttpStatusCode.NotFound -> DriveDestinationAccessException(
                "$operation could not find the selected Drive item. It may have been deleted or sharing may have been removed.",
            )
            else -> DriveDestinationAccessException("$operation failed with Google Drive status ${status.value}.")
        }
        if (status == HttpStatusCode.Unauthorized) {
            oauthService.clearGoogleSessionForRecovery(failure.message.orEmpty())
        }
        return failure
    }

    private suspend fun readMetadata(fileId: String, token: String): DriveMetadata {
        require(fileId.matches(Regex("[A-Za-z0-9_-]{10,256}"))) { "Google Drive file ID is invalid" }
        val response = httpClient.get("https://www.googleapis.com/drive/v3/files/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("supportsAllDrives", "true")
            parameter(
                "fields",
                "id,name,mimeType,parents,driveId,ownedByMe,webViewLink," +
                    "capabilities(canDownload,canListChildren,canEdit,canAddChildren)," +
                    "owners(emailAddress),permissions(type,role,emailAddress)",
            )
        }
        if (response.status != HttpStatusCode.OK) throw driveApiFailure("Reading the selected destination", response.status)
        val body = response.body<JsonObject>()
        val capabilities = body["capabilities"]?.jsonObject
        val owners = body["owners"]?.jsonArray.orEmpty().mapNotNull {
            it.jsonObject["emailAddress"]?.jsonPrimitive?.contentOrNull
        }
        val permissions = body["permissions"]?.jsonArray.orEmpty().mapNotNull { item ->
            val permission = item.jsonObject
            val role = permission["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val type = permission["type"]?.jsonPrimitive?.contentOrNull ?: "user"
            val email = permission["emailAddress"]?.jsonPrimitive?.contentOrNull
            listOfNotNull(role, type, email).joinToString(" · ")
        }
        return DriveMetadata(
            id = body.requiredDriveId("Drive metadata"),
            name = body["name"]?.jsonPrimitive?.contentOrNull ?: "Google Drive destination",
            mimeType = body["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            parents = body["parents"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            driveId = body["driveId"]?.jsonPrimitive?.contentOrNull,
            ownedByMe = body["ownedByMe"]?.jsonPrimitive?.booleanOrNull == true,
            canRead = capabilities?.get("canListChildren")?.jsonPrimitive?.booleanOrNull != false,
            canWrite = capabilities?.get("canEdit")?.jsonPrimitive?.booleanOrNull == true ||
                capabilities?.get("canAddChildren")?.jsonPrimitive?.booleanOrNull == true,
            webViewLink = body["webViewLink"]?.jsonPrimitive?.contentOrNull,
            ownerEmails = owners,
            permissionLabels = permissions,
        )
    }

    private suspend fun requireWithinDestination(fileId: String, scope: DriveScope) {
        if (!enforceWorkspaceScope) return
        val rootId = scope.destination.rootFolderId
        var currentId = fileId
        val visited = mutableSetOf<String>()
        repeat(MAX_DRIVE_ANCESTRY_DEPTH) {
            if (currentId == rootId) return
            require(visited.add(currentId)) { "Google Drive parent graph contains a cycle" }
            val metadata = readMetadata(currentId, scope.token)
            if (scope.destination.type == DriveDestinationType.SHARED_DRIVE &&
                metadata.driveId != scope.destination.sharedDriveId
            ) {
                throw DriveDestinationAccessException("The requested file is outside this workspace's Shared Drive.")
            }
            currentId = metadata.parents.singleOrNull()
                ?: throw DriveDestinationAccessException("The requested Drive item is outside this workspace's selected folder.")
        }
        throw DriveDestinationAccessException("The requested Drive item has an unexpectedly deep parent chain.")
    }

    /** Returns the only root under which synchronization may list or mutate files. */
    suspend fun workspaceRootId(): String {
        val scope = activeScope()
        val metadata = readMetadata(scope.destination.rootFolderId, scope.token)
        if (metadata.mimeType != GOOGLE_FOLDER_MIME_TYPE || !metadata.canWrite) {
            throw DriveDestinationAccessException(
                "The selected Drive destination is no longer writable. Ask its owner to restore access or choose another destination.",
            )
        }
        return scope.destination.rootFolderId
    }

    /** Creates or validates a destination without searching unrelated Drive files. */
    suspend fun configureDestination(
        type: DriveDestinationType,
        displayName: String,
        existingFolderReference: String? = null,
        sharedDriveId: String? = null,
    ): DriveDestinationConfig = withContext(Dispatchers.IO) {
        val identity = oauthService.authState.value as? AuthState.Authenticated
            ?: throw DriveDestinationAccessException("Sign in with Google before choosing a Drive destination.")
        val token = getAccessToken()
        val requestedName = displayName.trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Enter a name for the Drive destination")

        val rootId = when (type) {
            DriveDestinationType.PERSONAL_FOLDER,
            DriveDestinationType.TEAM_FOLDER -> createTopLevelFolder(requestedName, token)

            DriveDestinationType.SHARED_FOLDER,
            DriveDestinationType.SHARED_DRIVE -> extractGoogleDriveFolderId(
                existingFolderReference ?: sharedDriveId.orEmpty(),
            ) ?: throw IllegalArgumentException("Choose a folder with Google Drive Picker")
        }
        val metadata = readMetadata(rootId, token)
        require(metadata.mimeType == GOOGLE_FOLDER_MIME_TYPE) { "The selected Drive item is not a folder" }
        if (!metadata.canWrite) {
            throw DriveDestinationAccessException(
                "ARES can read the selected destination but cannot add files. Ask its owner for Contributor or Editor access.",
            )
        }
        if (type == DriveDestinationType.SHARED_DRIVE && metadata.driveId == null) {
            throw DriveDestinationAccessException("The selected folder is not inside a Google Shared Drive.")
        }
        val resolvedSharedDriveId = metadata.driveId
        DriveDestinationConfig(
            type = type,
            rootFolderId = rootId,
            displayName = metadata.name.ifBlank { requestedName },
            accountSubject = identity.uid,
            accountEmail = identity.email,
            sharedDriveId = resolvedSharedDriveId,
            collaborationMode = if (type == DriveDestinationType.PERSONAL_FOLDER) {
                WorkspaceCollaborationMode.PERSONAL
            } else {
                WorkspaceCollaborationMode.TEAM
            },
        )
    }

    private suspend fun createTopLevelFolder(name: String, token: String): String {
        val response = httpClient.post("https://www.googleapis.com/drive/v3/files") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("supportsAllDrives", "true")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", name)
                put("mimeType", GOOGLE_FOLDER_MIME_TYPE)
            })
        }
        if (response.status != HttpStatusCode.OK && response.status != HttpStatusCode.Created) {
            throw driveApiFailure("Creating the Drive folder", response.status)
        }
        return response.body<JsonObject>().requiredDriveId("a created destination folder")
    }

    suspend fun inspectDestination(destination: DriveDestinationConfig? = null): DriveDestinationStatus {
        val selected: DriveDestinationConfig
        val token: String
        if (destination == null) {
            val scope = activeScope()
            selected = scope.destination
            token = scope.token
        } else {
            val identity = oauthService.authState.value as? AuthState.Authenticated
                ?: throw DriveDestinationAccessException("Sign in with Google before inspecting a Drive destination.")
            if (identity.uid != destination.accountSubject || !identity.email.equals(destination.accountEmail, true)) {
                throw DriveDestinationAccessException("The selected destination belongs to ${destination.accountEmail}, not ${identity.email}.")
            }
            selected = destination
            token = getAccessToken()
        }
        val metadata = readMetadata(selected.rootFolderId, token)
        val owner = when {
            selected.type == DriveDestinationType.SHARED_DRIVE -> "Owned by the Google Workspace organization"
            metadata.ownedByMe -> "Owned by ${selected.accountEmail}"
            metadata.ownerEmails.isNotEmpty() -> "Owned by ${metadata.ownerEmails.joinToString()}"
            else -> "Ownership is managed by Google Drive"
        }
        val sharing = when {
            selected.type == DriveDestinationType.SHARED_DRIVE -> "Shared Drive membership controls access"
            metadata.permissionLabels.size > 1 -> "Shared with ${metadata.permissionLabels.size - 1} additional principal(s)"
            metadata.ownedByMe -> "Private until shared in Google Drive"
            else -> "Shared with this account"
        }
        return DriveDestinationStatus(
            type = selected.type,
            displayName = metadata.name,
            accountEmail = selected.accountEmail,
            ownerLabel = owner,
            sharingLabel = sharing,
            canRead = metadata.canRead,
            canWrite = metadata.canWrite,
            webViewLink = metadata.webViewLink,
            sharedDriveId = selected.sharedDriveId,
        )
    }

    private suspend fun findFolderIds(name: String, parentId: String?, token: String): List<String> {
        val escapedName = escapeDriveQuery(name)
        val query = if (parentId == null) {
            "name = '$escapedName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        } else {
            val escapedParent = escapeDriveQuery(parentId)
            "name = '$escapedName' and mimeType = 'application/vnd.google-apps.folder' and '$escapedParent' in parents and trashed = false"
        }
        val response = httpClient.get("https://www.googleapis.com/drive/v3/files") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("q", query)
            parameter("fields", "files(id)")
            parameter("pageSize", DRIVE_LIST_PAGE_SIZE)
            parameter("supportsAllDrives", "true")
            parameter("includeItemsFromAllDrives", "true")
        }
        if (response.status != HttpStatusCode.OK) {
            throw driveApiFailure("Searching the workspace folder", response.status)
        }
        return response.body<JsonObject>()["files"]?.jsonArray
            ?.map { it.requiredDriveId("a folder search result") }
            .orEmpty()
    }

    suspend fun findOrCreateFolder(name: String, parentId: String? = null): String =
        folderMutationMutex.withLock {
            withContext(Dispatchers.IO) {
                val scope = if (!enforceWorkspaceScope) null else activeScope()
                val token = scope?.token ?: getAccessToken()
                val effectiveParentId = parentId ?: scope?.destination?.rootFolderId
                    ?: throw DriveDestinationAccessException("A parent folder is required in this test context.")
                if (scope != null) requireWithinDestination(effectiveParentId, scope)
                val existingIds = findFolderIds(name, effectiveParentId, token).distinct().sorted()
                if (existingIds.isNotEmpty()) {
                    return@withContext existingIds.first()
                }

                val createBody = buildJsonObject {
                    put("name", name)
                    put("mimeType", "application/vnd.google-apps.folder")
                    put("parents", buildJsonArray { add(effectiveParentId) })
                }
                val createResponse = httpClient.post("https://www.googleapis.com/drive/v3/files") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("supportsAllDrives", "true")
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }

                if (createResponse.status != HttpStatusCode.OK) {
                    throw driveApiFailure("Creating a workspace subfolder", createResponse.status)
                }
                val createdId = createResponse.body<JsonObject>().requiredDriveId("a created folder")
                // A second dashboard process can win the same first-run race. Re-list and
                // discard only this process's newly-created empty loser. A pre-existing
                // folder may contain data and is never deleted here.
                val observedIds = (findFolderIds(name, effectiveParentId, token) + createdId).distinct().sorted()
                val canonicalId = observedIds.first()
                if (createdId != canonicalId) runCatching { deleteFile(createdId) }
                canonicalId
            }
        }

    suspend fun findFiles(name: String, parentId: String): List<String> = withContext(Dispatchers.IO) {
        val scope = if (!enforceWorkspaceScope) null else activeScope()
        val token = scope?.token ?: getAccessToken()
        if (scope != null) requireWithinDestination(parentId, scope)
        val escapedName = escapeDriveQuery(name)
        val escapedParent = escapeDriveQuery(parentId)
        val query = "name = '$escapedName' and '$escapedParent' in parents and trashed = false"
        val fileIds = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val response = httpClient.get("https://www.googleapis.com/drive/v3/files") {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("q", query)
                parameter("fields", "nextPageToken,files(id)")
                parameter("pageSize", DRIVE_LIST_PAGE_SIZE)
                pageToken?.let { parameter("pageToken", it) }
                parameter("supportsAllDrives", "true")
                parameter("includeItemsFromAllDrives", "true")
            }
            if (response.status != HttpStatusCode.OK) {
                throw driveApiFailure("Listing workspace files", response.status)
            }
            val searchResult = response.body<JsonObject>()
            searchResult["files"]?.jsonArray
                ?.mapTo(fileIds) { it.requiredDriveId("a file search result") }
            pageToken = (searchResult["nextPageToken"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf(String::isNotBlank)
        } while (pageToken != null)
        fileIds
    }

    private companion object {
        const val GOOGLE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val DRIVE_LIST_PAGE_SIZE = 1_000
        const val DRIVE_SNAPSHOT_ATTEMPTS = 3
        const val DRIVE_SNAPSHOT_RETRY_DELAY_MS = 50L
        const val DRIVE_STREAM_BUFFER_BYTES = 64 * 1024
        const val MAX_DRIVE_METADATA_BYTES = 8 * 1024 * 1024
        const val MAX_DRIVE_DOWNLOAD_BYTES = 2L * 1024L * 1024L * 1024L
        const val MAX_DRIVE_ANCESTRY_DEPTH = 64
    }

    suspend fun findFile(name: String, parentId: String): String? {
        val ids = findFiles(name, parentId).distinct()
        require(ids.size <= 1) { "Google Drive contains duplicate files named $name" }
        return ids.singleOrNull()
    }

    suspend fun readFile(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        val scope = if (!enforceWorkspaceScope) null else activeScope()
        val token = scope?.token ?: getAccessToken()
        if (scope != null) requireWithinDestination(fileId, scope)
        val response = httpClient.get("https://www.googleapis.com/drive/v3/files/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("alt", "media")
            parameter("supportsAllDrives", "true")
        }

        if (response.status != HttpStatusCode.OK) {
            throw driveApiFailure("Downloading a workspace file", response.status)
        }

        readBoundedBytes(response, MAX_DRIVE_METADATA_BYTES)
    }

    /**
     * Reads content together with Drive's monotonically increasing file version.
     *
     * Drive v3 does not expose a file-resource ETag for these responses. Read the documented
     * `version` field on both sides of the download instead. A changing version means the bytes
     * may not represent one stable revision, so retry rather than
     * returning a snapshot that could later overwrite another team's update.
     */
    internal suspend fun readFileSnapshot(fileId: String): DriveFileSnapshot = withContext(Dispatchers.IO) {
        val scope = if (!enforceWorkspaceScope) null else activeScope()
        val token = scope?.token ?: getAccessToken()
        if (scope != null) requireWithinDestination(fileId, scope)

        repeat(DRIVE_SNAPSHOT_ATTEMPTS) { attempt ->
            val beforeVersion = readMetadataVersion(fileId, token)
            val response = httpClient.get("https://www.googleapis.com/drive/v3/files/$fileId") {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("alt", "media")
                parameter("supportsAllDrives", "true")
            }
            if (response.status != HttpStatusCode.OK) {
                throw driveApiFailure("Reading workspace metadata content", response.status)
            }
            val bytes = readBoundedBytes(response, MAX_DRIVE_METADATA_BYTES)
            val afterVersion = readMetadataVersion(fileId, token)
            if (beforeVersion == afterVersion) return@withContext DriveFileSnapshot(bytes, beforeVersion)
            if (attempt < DRIVE_SNAPSHOT_ATTEMPTS - 1) {
                kotlinx.coroutines.delay(DRIVE_SNAPSHOT_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw DrivePreconditionFailedException("Google Drive file $fileId kept changing while it was read")
    }

    private suspend fun readMetadataVersion(fileId: String, token: String): Long {
        val response = httpClient.get("https://www.googleapis.com/drive/v3/files/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("fields", "id,version")
            parameter("supportsAllDrives", "true")
        }
        if (response.status != HttpStatusCode.OK) {
            throw driveApiFailure("Reading workspace metadata revision", response.status)
        }
        val metadata = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return metadata["version"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalStateException("Google Drive did not provide a numeric file version")
    }

    /**
     * Downloads a file from Google Drive by streaming directly to disk.
     * Use this for large files (Parquet) to avoid loading the entire file into memory.
     */
    suspend fun readFileStreaming(
        fileId: String,
        destination: File,
        expectedName: String,
        expectedBytes: Long,
        expectedSha256: String
    ): Unit = withContext(Dispatchers.IO) {
        require(expectedName.isNotBlank()) { "Cloud manifest filename is missing" }
        require(expectedBytes in 1L..MAX_DRIVE_DOWNLOAD_BYTES) { "Cloud manifest size is invalid" }
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Cloud manifest SHA-256 is invalid" }
        val scope = if (!enforceWorkspaceScope) null else activeScope()
        val token = scope?.token ?: getAccessToken()
        if (scope != null) requireWithinDestination(fileId, scope)
        try {
            val metadataResponse = httpClient.get("https://www.googleapis.com/drive/v3/files/$fileId") {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("fields", "id,name,size")
                parameter("supportsAllDrives", "true")
            }
            if (metadataResponse.status != HttpStatusCode.OK) {
                throw driveApiFailure("Reading workspace file metadata", metadataResponse.status)
            }
            val metadata = metadataResponse.body<JsonObject>()
            require(metadata["id"]?.jsonPrimitive?.contentOrNull == fileId) { "Drive file identity changed" }
            require(metadata["name"]?.jsonPrimitive?.contentOrNull == expectedName) { "Drive filename does not match manifest" }
            require(metadata["size"]?.jsonPrimitive?.longOrNull == expectedBytes) { "Drive size does not match manifest" }

            httpClient.prepareGet("https://www.googleapis.com/drive/v3/files/$fileId") {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("alt", "media")
                parameter("supportsAllDrives", "true")
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    throw driveApiFailure("Downloading a workspace file", response.status)
                }
                response.contentLength()?.let { length ->
                    require(length == expectedBytes) { "Drive response length does not match manifest" }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DRIVE_STREAM_BUFFER_BYTES)
                var totalBytes = 0L
                destination.parentFile?.mkdirs()
                java.io.FileOutputStream(destination).use { outputStream ->
                    val channel = response.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count == 0) continue
                        totalBytes = Math.addExact(totalBytes, count.toLong())
                        require(totalBytes <= expectedBytes) { "Drive download exceeds manifest size" }
                        digest.update(buffer, 0, count)
                        outputStream.write(buffer, 0, count)
                    }
                }
                require(totalBytes == expectedBytes) { "Drive download is truncated" }
                val actualSha256 = digest.digest().joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
                require(actualSha256 == expectedSha256) { "Drive download SHA-256 mismatch" }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private suspend fun readBoundedBytes(response: HttpResponse, maxBytes: Int): ByteArray {
        response.contentLength()?.let { length ->
            require(length in 0..maxBytes.toLong()) { "Drive response exceeds the metadata size limit" }
        }
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DRIVE_STREAM_BUFFER_BYTES)
        val channel = response.bodyAsChannel()
        var total = 0
        while (!channel.isClosedForRead) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            total = Math.addExact(total, count)
            require(total <= maxBytes) { "Drive response exceeds the metadata size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    suspend fun writeFile(
        name: String,
        bytes: ByteArray,
        parentId: String,
        mimeType: String,
        fileId: String? = null,
        expectedVersion: Long? = null
    ): String = withContext(Dispatchers.IO) {
        val scope = if (!enforceWorkspaceScope) null else activeScope()
        val token = scope?.token ?: getAccessToken()
        if (scope != null) {
            requireWithinDestination(parentId, scope)
            if (fileId != null) requireWithinDestination(fileId, scope)
        }

        if (fileId != null) {
            if (expectedVersion != null && readMetadataVersion(fileId, token) != expectedVersion) {
                throw DrivePreconditionFailedException("Google Drive file $fileId changed concurrently")
            }
            // Overwrite existing file media content
            return@withContext httpClient.preparePatch("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media&supportsAllDrives=true") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.parse(mimeType))
                setBody(bytes)
            }.execute { response ->
                if (response.status == HttpStatusCode.PreconditionFailed) {
                    throw DrivePreconditionFailedException("Google Drive file $fileId changed concurrently")
                }
                if (response.status != HttpStatusCode.OK) {
                    throw driveApiFailure("Updating a workspace file", response.status)
                }
                fileId
            }
        } else {
            // Create a new file with multipart metadata + media content
            val metadataPart = buildJsonObject {
                put("name", name)
                put("parents", buildJsonArray { add(parentId) })
            }.toString()
            val response = httpClient.post("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true") {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(
                    io.ktor.client.request.forms.MultiPartFormDataContent(
                        io.ktor.client.request.forms.formData {
                            append("metadata", metadataPart, Headers.build {
                                append(HttpHeaders.ContentType, "application/json; charset=UTF-8")
                            })
                            append("file", bytes, Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                            })
                        },
                        boundary = "Boundary_${System.currentTimeMillis()}"
                    )
                )
            }

            if (response.status != HttpStatusCode.OK) {
                throw driveApiFailure("Uploading a workspace file", response.status)
            }
            response.body<JsonObject>().requiredDriveId("an uploaded file")
        }
    }

    /**
     * Uploads a file to Google Drive by streaming directly from disk.
     * Use this for large files (Parquet) to avoid loading the entire file into memory.
     */
    suspend fun createFileStreaming(
        name: String,
        file: File,
        parentId: String,
        mimeType: String
    ): String = withContext(Dispatchers.IO) {
        val scope = if (!enforceWorkspaceScope) null else activeScope()
        val token = scope?.token ?: getAccessToken()
        if (scope != null) requireWithinDestination(parentId, scope)

        // Session Parquet objects are immutable. Create metadata first and stream the media through
        // a resumable upload session; this API intentionally has no existing-file ID/PATCH path.
        val metadata = buildJsonObject {
            put("name", name)
            put("parents", buildJsonArray { add(parentId) })
        }
        val sessionResponse = httpClient.post(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true"
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Upload-Content-Type", mimeType)
            header("X-Upload-Content-Length", file.length().toString())
            contentType(ContentType.Application.Json)
            setBody(metadata)
        }
        if (sessionResponse.status != HttpStatusCode.OK) {
            throw driveApiFailure("Starting a workspace upload", sessionResponse.status)
        }
        val uploadUrl = sessionResponse.headers[HttpHeaders.Location]
            ?: throw IllegalStateException("Google Drive resumable upload omitted its session URL")
        httpClient.preparePut(uploadUrl) {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType = ContentType.parse(mimeType)
                override val contentLength = file.length()

                override fun readFrom(): ByteReadChannel =
                    file.inputStream().toByteReadChannel()
            })
        }.execute { response ->
            if (response.status != HttpStatusCode.OK && response.status != HttpStatusCode.Created) {
                throw driveApiFailure("Uploading a workspace file", response.status)
            }
            response.body<JsonObject>().requiredDriveId("an uploaded file")
        }
    }

    suspend fun deleteFile(fileId: String): Unit = withContext(Dispatchers.IO) {
        val scope = if (!enforceWorkspaceScope) null else activeScope()
        val token = scope?.token ?: getAccessToken()
        if (scope != null) requireWithinDestination(fileId, scope)
        httpClient.prepareDelete("https://www.googleapis.com/drive/v3/files/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("supportsAllDrives", "true")
        }.execute { response ->
            if (response.status != HttpStatusCode.OK && response.status != HttpStatusCode.NoContent) {
                throw driveApiFailure("Deleting a workspace file", response.status)
            }
        }
    }

    /**
     * Final teardown — closes the underlying HttpClient. Call from [com.ares.analytics.di.ServiceRegistry].
     */
    fun dispose() {
        try {
            httpClient.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
