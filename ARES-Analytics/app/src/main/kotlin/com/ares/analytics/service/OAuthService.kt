package com.ares.analytics.service

import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.GoogleAuthorizationCodeExchangeRequest
import com.ares.analytics.shared.GoogleOAuthBrokerTokenResponse
import com.ares.analytics.shared.GoogleRefreshTokenExchangeRequest
import com.ares.analytics.shared.models.WorkspaceConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Google-OAuth-first authentication service. Owns the local token store; no Firebase
 * Identity Toolkit round-trip. The Google ID token returned at login (and on refresh)
 * carries identity (sub/email/name) and is the credential used to call the gateway's
 * OIDC-authed endpoints. Google Drive access tokens are refreshed on demand for
 * [GoogleDriveService].
 */
class OAuthService(
    private val environmentService: EnvironmentService,
    private val googleClientResolver: GoogleOAuthClientResolver = GoogleOAuthClientResolver(),
    private val authFilePath: String = AppDataPaths.file("auth.json").path,
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    },
    private val loadPersistedAuthOnInit: Boolean = true,
    private val secretsWriter: (File, ByteArray) -> Unit = ::writeSecrets,
) {
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()
    private val authLifecycleLock = Any()
    private val authGeneration = AtomicLong(0L)
    private val pendingOAuthRequest = AtomicReference<PendingOAuthRequest?>(null)
    private val authWorkJobs = mutableSetOf<Job>()

    @Volatile
    private var disposed = false

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _drivePickerState = MutableStateFlow<DrivePickerState>(DrivePickerState.Idle)
    val drivePickerState: StateFlow<DrivePickerState> = _drivePickerState.asStateFlow()

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var serverGeneration: Long? = null

    private data class PendingOAuthRequest(
        val state: String,
        val generation: Long,
        val successTitle: String,
        val onCodeReceived: suspend (String, Parameters) -> Unit,
        val onError: (String) -> Unit,
    )

    private data class AuthAttempt(
        val generation: Long,
        val previousState: AuthState
    )

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val tokenStore: OAuthTokenStore = createOAuthTokenStore(authFilePath, secretsWriter)

    init {
        // On startup, re-establish Authenticated state from persisted Google tokens.
        if (loadPersistedAuthOnInit) {
            val generation = authGeneration.get()
            launchAuthWork(generation) { loadPersistedAuth(generation) }
        }
    }

    fun isDevMode(): Boolean = System.getenv("DEV_MODE") == "true"

    val managedGoogleClientAvailable: Boolean
        get() = googleClientResolver.managedClientAvailable

    internal suspend fun loadPersistedAuth() = loadPersistedAuth(authGeneration.get())

    private suspend fun loadPersistedAuth(generation: Long) {
        if (!isGenerationCurrent(generation)) return
        val saved = getSavedAuth() ?: return
        val config = environmentService.loadConfig()
        val credentials = when (val resolution = googleClientResolver.resolve(config)) {
            is GoogleOAuthClientResolution.Available -> resolution.credentials
            is GoogleOAuthClientResolution.Unavailable -> return
        }
        if (saved.googleClientId != credentials.clientId) {
            clearUnusableAuth(
                generation,
                "Your previous Google sign-in used an unavailable OAuth client and was cleared. Sign in again with Google.",
            )
            return
        }
        // Refresh yields a fresh access token and may omit the optional ID token. Only restore
        // Authenticated when the refresh actually round-tripped —
        // otherwise a revoked/disabled account would look logged-in while gateway calls 401.
        val refreshed = try {
            refreshGoogleAccessToken(credentials, generation) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network-down / Drive outage while refreshing: stay Unauthenticated so the UI
            // re-prompts rather than falsely advertising an authenticated session.
            false
        }
        if (!refreshed) return
        // Restore Authenticated state from the retained or freshly returned ID token.
        val restored = getSavedAuth() ?: return
        val idToken = restored.googleIdToken
        if (!idToken.isNullOrBlank()) {
            val payload = decodeIdToken(idToken)
            commitIfCurrent(generation) {
                _authState.value = AuthState.Authenticated(
                    idToken = idToken,
                    uid = payload.sub.ifBlank { restored.uid },
                    email = payload.email ?: restored.email,
                    displayName = payload.name ?: restored.displayName
                )
            }
        }
    }

    /** Starts the normal one-click flow or the workspace's explicit administrator override. */
    fun startGoogleLogin(workspaceConfig: WorkspaceConfig? = null) {
        when (val resolution = googleClientResolver.resolve(workspaceConfig)) {
            is GoogleOAuthClientResolution.Available -> beginGoogleLogin(resolution.credentials, interactive = true)
            is GoogleOAuthClientResolution.Unavailable -> {
                val generation = authGeneration.get()
                commitIfCurrent(generation) { _authState.value = AuthState.Error(resolution.message) }
            }
        }
    }

    private fun beginGoogleLogin(
        credentials: GoogleOAuthClientCredentials,
        interactive: Boolean
    ): String? {
        val attempt = beginAuthAttempt(
            permitted = { it !is AuthState.Authenticating },
            nextState = { AuthState.Authenticating }
        ) ?: return null
        val generation = attempt.generation

        if (interactive && isDevMode()) {
            launchAuthWork(generation) {
                applyGoogleTokens(
                    idToken = "dev-id-token",
                    accessToken = "dev-access-token",
                    refreshToken = null,
                    expiresIn = 3600L,
                    emailFallback = "dev-user@aresrobotics.org",
                    nameFallback = "ARES Dev User",
                    googleClientId = credentials.clientId,
                    generation = generation
                )
            }
            return null
        }
        val googleClientId = credentials.clientId
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val callbackPort = GOOGLE_CALLBACK_PORT
        val redirectUri = GOOGLE_DESKTOP_REDIRECT_URI
        // Per-request CSRF state parameter (AUDIT H1): unguessable, validated on callback.
        val state = generateCodeVerifier()
        val loginUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
                "client_id=$googleClientId" +
                "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
                "&response_type=code" +
                "&scope=${URLEncoder.encode("openid email profile https://www.googleapis.com/auth/drive.file", "UTF-8")}" +
                "&access_type=offline" +
                "&prompt=consent" +
                "&code_challenge=$codeChallenge" +
                "&code_challenge_method=S256" +
                "&state=$state"

        val pendingRequest = PendingOAuthRequest(
            state = state,
            generation = generation,
            successTitle = "Authorization Received",
            onCodeReceived = { code, _ -> try {
                val response = exchangeAuthorizationCode(
                    credentials = credentials,
                    code = code,
                    redirectUri = redirectUri,
                    codeVerifier = codeVerifier,
                )

                if (response.status == HttpStatusCode.OK) {
                    val tokenData = response.body<GoogleOAuthBrokerTokenResponse>()
                    val idToken = tokenData.idToken?.takeIf(String::isNotBlank)
                        ?: error("Google did not return an ID token during authorization")
                    applyGoogleTokens(
                        idToken = idToken,
                        accessToken = tokenData.accessToken,
                        refreshToken = tokenData.refreshToken,
                        expiresIn = tokenData.expiresIn,
                        emailFallback = "user@aresrobotics.org",
                        nameFallback = "Google User",
                        googleClientId = googleClientId,
                        generation = generation
                    )
                } else {
                    val errorText = response.bodyAsText()
                    updateStateIfCurrent(
                        generation,
                        AuthState.Error(googleOAuthRecoveryMessage(errorText, credentials.source))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateStateIfCurrent(
                    generation,
                    AuthState.Error(
                        "ARES could not finish Google sign-in. Check your connection and try again; if it continues, contact an administrator.",
                    ),
                )
            } },
            onError = { error ->
                updateStateIfCurrent(
                    generation,
                    AuthState.Error(googleOAuthRecoveryMessage("{\"error\":\"$error\"}", credentials.source)),
                )
            },
        )
        if (!registerPendingRequest(pendingRequest)) return null

        if (interactive) {
            bootCallbackServer(callbackPort, generation)
            launchBrowser(loginUrl, generation)
        }
        return state
    }

    /**
     * Opens Google's desktop folder picker using only `drive.file`.
     *
     * This resource-specific authorization grants ARES access to one existing folder without
     * granting visibility into unrelated Drive content. The picker account must match the account
     * already signed into ARES before the selected ID is released to workspace configuration.
     */
    fun startGoogleDriveFolderPicker(
        workspaceConfig: WorkspaceConfig? = null,
        onFolderPicked: suspend (String) -> Unit,
    ) {
        val identity = _authState.value as? AuthState.Authenticated
        if (identity == null) {
            _drivePickerState.value = DrivePickerState.Error("Sign in with Google before choosing a Drive folder.")
            return
        }
        val credentials = when (val resolution = googleClientResolver.resolve(workspaceConfig)) {
            is GoogleOAuthClientResolution.Available -> resolution.credentials
            is GoogleOAuthClientResolution.Unavailable -> {
                _drivePickerState.value = DrivePickerState.Error(resolution.message)
                return
            }
        }
        beginGoogleDriveFolderPicker(credentials, identity, interactive = true, onFolderPicked = onFolderPicked)
    }

    private fun beginGoogleDriveFolderPicker(
        credentials: GoogleOAuthClientCredentials,
        identity: AuthState.Authenticated,
        interactive: Boolean,
        onFolderPicked: suspend (String) -> Unit,
    ): String? {
        val generation = authGeneration.get()
        if (!isGenerationCurrent(generation) || pendingOAuthRequest.get() != null) {
            _drivePickerState.value = DrivePickerState.Error("Another Google authorization is already in progress.")
            return null
        }
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val callbackPort = GOOGLE_CALLBACK_PORT
        val redirectUri = GOOGLE_DESKTOP_REDIRECT_URI
        val state = generateCodeVerifier()
        val pickerUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
            "client_id=${credentials.clientId}" +
            "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
            "&response_type=code" +
            "&scope=${URLEncoder.encode("https://www.googleapis.com/auth/drive.file", "UTF-8")}" +
            "&access_type=offline" +
            "&prompt=consent" +
            "&include_granted_scopes=false" +
            "&trigger_onepick=true" +
            "&allow_folder_selection=true" +
            "&login_hint=${URLEncoder.encode(identity.email, "UTF-8")}" +
            "&code_challenge=$codeChallenge" +
            "&code_challenge_method=S256" +
            "&state=$state"

        val request = PendingOAuthRequest(
            state = state,
            generation = generation,
            successTitle = "Folder Authorization Received",
            onCodeReceived = picker@{ code, parameters ->
                val pickedIds = parameters["picked_file_ids"]
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    .orEmpty()
                if (pickedIds.size != 1 || !pickedIds.single().matches(Regex("[A-Za-z0-9_-]{10,256}"))) {
                    _drivePickerState.value = DrivePickerState.Error(
                        "Google did not return one valid Drive folder. Try choosing the folder again.",
                    )
                    return@picker
                }
                try {
                    val response = exchangeAuthorizationCode(
                        credentials = credentials,
                        code = code,
                        redirectUri = redirectUri,
                        codeVerifier = codeVerifier,
                    )
                    if (response.status != HttpStatusCode.OK) {
                        _drivePickerState.value = DrivePickerState.Error(
                            googleOAuthRecoveryMessage(response.bodyAsText(), credentials.source),
                        )
                        return@picker
                    }
                    val pickerToken = response.body<GoogleOAuthBrokerTokenResponse>().accessToken
                    val about = httpClient.get("https://www.googleapis.com/drive/v3/about") {
                        header(HttpHeaders.Authorization, "Bearer $pickerToken")
                        parameter("fields", "user(emailAddress)")
                    }
                    if (about.status != HttpStatusCode.OK) {
                        _drivePickerState.value = DrivePickerState.Error(
                            "Google could not verify the account that selected this folder. Try again.",
                        )
                        return@picker
                    }
                    val pickerEmail = about.body<JsonObject>()["user"]
                        ?.jsonObject
                        ?.get("emailAddress")
                        ?.jsonPrimitive
                        ?.contentOrNull
                    if (!pickerEmail.equals(identity.email, ignoreCase = true)) {
                        _drivePickerState.value = DrivePickerState.Error(
                            "That folder was selected with ${pickerEmail ?: "another Google account"}, but ARES is signed in as ${identity.email}. Choose the same account.",
                        )
                        return@picker
                    }
                    onFolderPicked(pickedIds.single())
                    _drivePickerState.value = DrivePickerState.Selected(pickedIds.single())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _drivePickerState.value = DrivePickerState.Error(
                        "The Drive folder selection could not be completed: ${failure.message ?: "unknown error"}",
                    )
                }
            },
            onError = { error ->
                _drivePickerState.value = DrivePickerState.Error(
                    googleOAuthRecoveryMessage("{\"error\":\"$error\"}", credentials.source),
                )
            },
        )
        if (!registerPendingRequest(request)) return null
        _drivePickerState.value = DrivePickerState.Picking
        if (interactive) {
            bootCallbackServer(callbackPort, generation)
            launchBrowser(pickerUrl, generation) { message ->
                _drivePickerState.value = DrivePickerState.Error(message)
            }
        }
        return state
    }

    internal fun beginGoogleDriveFolderPickerForTest(
        clientId: String,
        onFolderPicked: suspend (String) -> Unit,
    ): String {
        val identity = _authState.value as? AuthState.Authenticated
            ?: error("Test must establish an authenticated identity first")
        return requireNotNull(
            beginGoogleDriveFolderPicker(
                credentials = testGoogleCredentials(clientId),
                identity = identity,
                interactive = false,
                onFolderPicked = onFolderPicked,
            ),
        )
    }

    /** Deterministic non-interactive seam for callback lifecycle tests. */
    internal fun beginGoogleLoginForTest(googleClientId: String): String =
        requireNotNull(
            beginGoogleLogin(
                testGoogleCredentials(googleClientId),
                interactive = false,
            )
        ) {
            "Google authentication could not be started"
        }

    /**
     * Centralizes Google token handling: decode identity from the ID token, persist the
     * access/refresh tokens, and publish [AuthState.Authenticated].
     */
    private fun applyGoogleTokens(
        idToken: String,
        accessToken: String,
        refreshToken: String?,
        expiresIn: Long,
        emailFallback: String,
        nameFallback: String,
        googleClientId: String,
        generation: Long
    ): Boolean {
        val payload = decodeIdToken(idToken)
        val email = payload.email ?: emailFallback
        val name = payload.name ?: nameFallback
        val uid = payload.sub.ifEmpty { "google-$email" }
        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)
        val saved = OAuthSavedAuth(
            googleClientId = googleClientId,
            googleAccessToken = accessToken,
            googleRefreshToken = refreshToken,
            googleTokenExpiresAt = expiresAt,
            googleIdToken = idToken,
            uid = uid,
            email = email,
            displayName = name
        )
        var persisted = false
        val current = commitIfCurrent(generation) {
            try {
                saveAuth(saved)
                persisted = true
                _authState.value = AuthState.Authenticated(
                    idToken = idToken,
                    uid = uid,
                    email = email,
                    displayName = name
                )
            } catch (failure: Exception) {
                _authState.value = AuthState.Error(
                    "Authentication credentials could not be saved: ${failure.message ?: failure.javaClass.simpleName}",
                )
            }
        }
        return current && persisted
    }

    private suspend fun exchangeAuthorizationCode(
        credentials: GoogleOAuthClientCredentials,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): HttpResponse = httpClient.post("${credentials.tokenBrokerUrl}/api/oauth/google/token") {
        contentType(ContentType.Application.Json)
        setBody(
            GoogleAuthorizationCodeExchangeRequest(
                code = code,
                codeVerifier = codeVerifier,
                redirectUri = redirectUri,
            ),
        )
    }

    private suspend fun exchangeRefreshToken(
        credentials: GoogleOAuthClientCredentials,
        refreshToken: String,
    ): HttpResponse = httpClient.post("${credentials.tokenBrokerUrl}/api/oauth/google/refresh") {
        contentType(ContentType.Application.Json)
        setBody(GoogleRefreshTokenExchangeRequest(refreshToken))
    }

    private fun testGoogleCredentials(clientId: String) = GoogleOAuthClientCredentials(
        clientId = clientId,
        source = GoogleOAuthClientSource.CUSTOM,
        tokenBrokerUrl = "https://oauth-broker.test",
    )

    suspend fun refreshGoogleAccessToken(): String? {
        val credentials = when (val resolution = googleClientResolver.resolve(environmentService.loadConfig())) {
            is GoogleOAuthClientResolution.Available -> resolution.credentials
            is GoogleOAuthClientResolution.Unavailable -> return null
        }
        return refreshGoogleAccessToken(credentials, authGeneration.get())
    }

    internal suspend fun refreshGoogleAccessTokenForTest(clientId: String): String? =
        refreshGoogleAccessToken(
            testGoogleCredentials(clientId),
            authGeneration.get(),
        )

    private suspend fun refreshGoogleAccessToken(
        credentials: GoogleOAuthClientCredentials,
        generation: Long
    ): String? = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            if (!isGenerationCurrent(generation)) return@withLock null
            val saved = getSavedAuth() ?: return@withLock null
            if (saved.googleClientId != credentials.clientId) {
                clearUnusableAuth(
                    generation,
                    "The saved Google session belongs to a different OAuth client and was cleared. Sign in again.",
                )
                return@withLock null
            }
            val refreshToken = saved.googleRefreshToken ?: return@withLock valueIfCurrent(generation) {
                saved.googleAccessToken
            }

            // Reuse current access token if not within 2 minutes of expiry.
            val expiresAt = saved.googleTokenExpiresAt ?: 0
            if (System.currentTimeMillis() < expiresAt - 120_000 && saved.googleAccessToken.isNotBlank()) {
                return@withLock valueIfCurrent(generation) { saved.googleAccessToken }
            }

            try {
                val response = exchangeRefreshToken(credentials, refreshToken)

                if (response.status == HttpStatusCode.OK) {
                    val data = response.body<GoogleOAuthBrokerTokenResponse>()
                    val newExpiresAt = System.currentTimeMillis() + (data.expiresIn * 1000L)
                    val updatedAuth = saved.copy(
                        googleAccessToken = data.accessToken,
                        googleTokenExpiresAt = newExpiresAt,
                        googleRefreshToken = data.refreshToken ?: saved.googleRefreshToken,
                        googleIdToken = data.idToken?.takeIf(String::isNotBlank) ?: saved.googleIdToken
                    )
                    val committed = commitIfCurrent(generation) {
                        saveAuth(updatedAuth)
                        // Google commonly omits id_token on refresh. Refresh identity only when
                        // one is explicitly returned and otherwise retain the established identity.
                        val current = _authState.value
                        val refreshedIdToken = data.idToken?.takeIf(String::isNotBlank)
                        if (current is AuthState.Authenticated && refreshedIdToken != null) {
                            val payload = decodeIdToken(refreshedIdToken)
                            _authState.value = current.copy(
                                idToken = refreshedIdToken,
                                email = payload.email ?: current.email,
                                displayName = payload.name ?: current.displayName
                            )
                        }
                    }
                    return@withLock data.accessToken.takeIf { committed }
                } else {
                    val errorText = response.bodyAsText()
                    val parsedError = parseGoogleOAuthError(errorText)
                    if (parsedError == "deleted_client" || parsedError == "invalid_grant") {
                        clearUnusableAuth(
                            generation,
                            googleOAuthRecoveryMessage(errorText, credentials.source),
                        )
                    }
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An offline startup is expected and must not leak request details or revoke the
                // persisted refresh token. The UI remains signed out until a later retry.
                null
            }
        }
    }

    fun logout() {
        invalidateAuth(
            nextState = AuthState.Unauthenticated,
            deletePersistedAuth = true,
            markDisposed = false
        )
    }

    /** Used when Google APIs prove that the current access grant is no longer usable. */
    fun clearGoogleSessionForRecovery(message: String) {
        invalidateAuth(
            nextState = AuthState.Error(message),
            deletePersistedAuth = true,
            markDisposed = false,
        )
    }

    private fun clearUnusableAuth(generation: Long, message: String) {
        commitIfCurrent(generation) {
            val removed = tokenStore.delete()
            _authState.value = if (removed) {
                AuthState.Error(message)
            } else {
                AuthState.Error("$message ARES could not remove the old local token file; close the app and remove it from Settings before retrying.")
            }
        }
    }

    fun getSavedAuth(): OAuthSavedAuth? {
        return try {
            val bytes = tokenStore.read() ?: return null
            AppJson.decodeFromString<OAuthSavedAuth>(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun saveAuth(auth: OAuthSavedAuth) {
        // OAuthTokenStore owns platform protection and atomic replacement.
        tokenStore.write(Json.encodeToString(auth).toByteArray(Charsets.UTF_8))
    }

    private fun beginAuthAttempt(
        permitted: (AuthState) -> Boolean,
        nextState: (AuthState) -> AuthState
    ): AuthAttempt? {
        var jobsToCancel: List<Job> = emptyList()
        var serverToStop: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
        val attempt = synchronized(authLifecycleLock) {
            if (disposed) return@synchronized null
            val current = _authState.value
            if (!permitted(current)) return@synchronized null

            val generation = authGeneration.incrementAndGet()
            pendingOAuthRequest.set(null)
            jobsToCancel = authWorkJobs.toList()
            authWorkJobs.clear()
            serverToStop = server
            server = null
            serverGeneration = null
            _authState.value = nextState(current)
            AuthAttempt(generation, current)
        }
        if (attempt != null) {
            jobsToCancel.forEach { it.cancel() }
            stopEmbeddedServer(serverToStop)
        }
        return attempt
    }

    private fun registerPendingRequest(request: PendingOAuthRequest): Boolean =
        synchronized(authLifecycleLock) {
            if (!isGenerationCurrent(request.generation)) {
                false
            } else {
                pendingOAuthRequest.compareAndSet(null, request)
            }
        }

    /** Atomically consumes a matching state value; callback replays leave the request untouched. */
    private fun consumePendingRequest(returnedState: String?): PendingOAuthRequest? =
        synchronized(authLifecycleLock) {
            val pending = pendingOAuthRequest.get() ?: return@synchronized null
            if (
                returnedState == null ||
                returnedState != pending.state ||
                !isGenerationCurrent(pending.generation)
            ) {
                return@synchronized null
            }
            if (pendingOAuthRequest.compareAndSet(pending, null)) pending else null
        }

    private fun clearPendingRequest(generation: Long) {
        synchronized(authLifecycleLock) {
            val pending = pendingOAuthRequest.get()
            if (pending?.generation == generation) pendingOAuthRequest.compareAndSet(pending, null)
        }
    }

    private fun launchAuthWork(generation: Long, block: suspend () -> Unit): Job? {
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            if (isGenerationCurrent(generation)) block()
        }
        job.invokeOnCompletion {
            synchronized(authLifecycleLock) { authWorkJobs.remove(job) }
        }
        val registered = synchronized(authLifecycleLock) {
            if (isGenerationCurrent(generation)) {
                authWorkJobs.add(job)
                true
            } else {
                false
            }
        }
        if (registered) {
            job.start()
            return job
        }
        job.cancel()
        return null
    }

    private fun launchPendingCodeExchange(
        pending: PendingOAuthRequest,
        code: String,
        parameters: Parameters = Parameters.Empty,
    ): Job? =
        launchAuthWork(pending.generation) {
            try {
                pending.onCodeReceived(code, parameters)
            } finally {
                stopServer(pending.generation)
            }
        }

    /** Deterministic seam used to exercise callback replay and cancellation without a TCP server. */
    internal fun dispatchOAuthCallbackForTest(
        returnedState: String?,
        code: String,
        parameters: Parameters = Parameters.Empty,
    ): Job? {
        val pending = consumePendingRequest(returnedState) ?: return null
        return launchPendingCodeExchange(pending, code, parameters)
    }

    private fun commitIfCurrent(generation: Long, block: () -> Unit): Boolean =
        synchronized(authLifecycleLock) {
            if (!isGenerationCurrent(generation)) {
                false
            } else {
                block()
                true
            }
        }

    private fun <T> valueIfCurrent(generation: Long, block: () -> T): T? =
        synchronized(authLifecycleLock) {
            if (isGenerationCurrent(generation)) block() else null
        }

    private fun updateStateIfCurrent(generation: Long, state: AuthState): Boolean =
        commitIfCurrent(generation) { _authState.value = state }

    private fun isGenerationCurrent(generation: Long): Boolean =
        !disposed && authGeneration.get() == generation

    private fun invalidateAuth(
        nextState: AuthState,
        deletePersistedAuth: Boolean,
        markDisposed: Boolean
    ) {
        var jobsToCancel: List<Job> = emptyList()
        var serverToStop: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
        synchronized(authLifecycleLock) {
            authGeneration.incrementAndGet()
            if (markDisposed) disposed = true
            pendingOAuthRequest.set(null)
            jobsToCancel = authWorkJobs.toList()
            authWorkJobs.clear()
            serverToStop = server
            server = null
            serverGeneration = null
            _authState.value = nextState
            _drivePickerState.value = DrivePickerState.Idle
            if (deletePersistedAuth) tokenStore.delete()
        }
        jobsToCancel.forEach { it.cancel() }
        stopEmbeddedServer(serverToStop)
    }

    private fun bootCallbackServer(port: Int, generation: Long) {
        stopServer(generation)
        val candidate = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                get("/callback") {
                    val returnedState = call.request.queryParameters["state"]
                    val pending = consumePendingRequest(returnedState)
                    if (pending == null) {
                        call.respondText("Authentication failed: invalid state parameter (possible CSRF attack).")
                        return@get
                    }
                    val code = call.request.queryParameters["code"]
                    val error = call.request.queryParameters["error"]

                    if (code != null) {
                        call.respondText(
                            """
                            <html>
                            <head>
                                <title>ARES Mission Control Sign-In</title>
                                <style>
                                    body {
                                        background-color: #0D0F14;
                                        color: #E8ECF4;
                                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                                        display: flex;
                                        align-items: center;
                                        justify-content: center;
                                        height: 100vh;
                                        margin: 0;
                                    }
                                    .card {
                                        background-color: #161A22;
                                        border: 1px solid #2A2F3C;
                                        padding: 40px;
                                        border-radius: 16px;
                                        text-align: center;
                                        box-shadow: 0 4px 20px rgba(0,0,0,0.5);
                                    }
                                    h1 { color: #00E5FF; margin-bottom: 8px; }
                                    p { color: #9CA3B4; }
                                </style>
                            </head>
                            <body>
                                <div class="card">
                                    <h1>${pending.successTitle}</h1>
                                    <p>Google returned the authorization response. ARES is completing the secure exchange now. Return to the application to see the final result.</p>
                                </div>
                            </body>
                            </html>
                            """.trimIndent(),
                            io.ktor.http.ContentType.Text.Html
                        )
                        launchPendingCodeExchange(pending, code, call.request.queryParameters)
                    } else {
                        call.respondText("Authentication was not completed. Return to ARES Robotics Studio for recovery steps.")
                        pending.onError(error ?: "unknown")
                        serviceScope.launch { stopServer(pending.generation) }
                    }
                }
            }
        }
        val installed = synchronized(authLifecycleLock) {
            if (!isGenerationCurrent(generation)) {
                false
            } else {
                candidate.start(wait = false)
                server = candidate
                serverGeneration = generation
                true
            }
        }
        if (!installed) stopEmbeddedServer(candidate)
    }

    private fun launchBrowser(
        url: String,
        generation: Long,
        onFailure: (String) -> Unit = { message ->
            updateStateIfCurrent(generation, AuthState.Error(message))
        },
    ) {
        launchAuthWork(generation) {
            try {
                withContext(Dispatchers.IO) {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI(url))
                    } else {
                        onFailure("System browser not supported on this platform.")
                        clearPendingRequest(generation)
                        stopServer(generation)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure("Failed to launch system browser: ${e.message}")
                clearPendingRequest(generation)
                stopServer(generation)
            }
        }
    }

    private fun stopServer(expectedGeneration: Long? = null) {
        val serverToStop = synchronized(authLifecycleLock) {
            if (expectedGeneration != null && serverGeneration != expectedGeneration) {
                null
            } else {
                server.also {
                    server = null
                    serverGeneration = null
                }
            }
        }
        stopEmbeddedServer(serverToStop)
    }

    private fun stopEmbeddedServer(
        target: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>?
    ) {
        target?.let { runCatching { it.stop(1000, 2000) } }
    }

    fun dispose() {
        invalidateAuth(
            nextState = AuthState.Unauthenticated,
            deletePersistedAuth = false,
            markDisposed = true
        )
        serviceScope.cancel()
        try {
            httpClient.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
