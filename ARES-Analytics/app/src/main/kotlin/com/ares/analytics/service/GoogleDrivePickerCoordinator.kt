package com.ares.analytics.service

import com.ares.analytics.shared.GoogleOAuthBrokerTokenResponse
import com.ares.analytics.shared.models.WorkspaceConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.net.URLEncoder

internal class GoogleDrivePickerCoordinator(
    private val googleClientResolver: GoogleOAuthClientResolver,
    private val httpClient: HttpClient,
    private val drivePickerState: MutableStateFlow<DrivePickerState>,
    private val getAuthState: () -> AuthState,
    private val getAuthGeneration: () -> Long,
    private val isGenerationCurrent: (Long) -> Boolean,
    private val hasPendingOAuthRequest: () -> Boolean,
    private val registerPendingRequest: (PendingOAuthRequest) -> Boolean,
    private val exchangeAuthorizationCode: suspend (GoogleOAuthClientCredentials, String, String, String) -> HttpResponse,
    private val bootCallbackServer: (Int, Long) -> Unit,
    private val launchBrowser: (String, Long, (String) -> Unit) -> Unit,
    private val testGoogleCredentials: (String) -> GoogleOAuthClientCredentials,
    private val clearPendingRequest: (Long) -> Unit,
    private val stopServer: (Long?) -> Unit,
) {
    fun startGoogleDriveFolderPicker(
        workspaceConfig: WorkspaceConfig? = null,
        onFolderPicked: suspend (String) -> Unit,
    ) {
        val identity = getAuthState() as? AuthState.Authenticated
        if (identity == null) {
            drivePickerState.value = DrivePickerState.Error("Sign in with Google before choosing a Drive folder.")
            return
        }
        val credentials = when (val resolution = googleClientResolver.resolve(workspaceConfig)) {
            is GoogleOAuthClientResolution.Available -> resolution.credentials
            is GoogleOAuthClientResolution.Unavailable -> {
                drivePickerState.value = DrivePickerState.Error(resolution.message)
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
        val generation = getAuthGeneration()
        if (!isGenerationCurrent(generation) || hasPendingOAuthRequest()) {
            drivePickerState.value = DrivePickerState.Error("Another Google authorization is already in progress.")
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
                    drivePickerState.value = DrivePickerState.Error(
                        "Google did not return one valid Drive folder. Try choosing the folder again.",
                    )
                    return@picker
                }
                try {
                    val response = exchangeAuthorizationCode(
                        credentials,
                        code,
                        redirectUri,
                        codeVerifier,
                    )
                    if (response.status != HttpStatusCode.OK) {
                        drivePickerState.value = DrivePickerState.Error(
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
                        drivePickerState.value = DrivePickerState.Error(
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
                        drivePickerState.value = DrivePickerState.Error(
                            "That folder was selected with ${pickerEmail ?: "another Google account"}, but ARES is signed in as ${identity.email}. Choose the same account.",
                        )
                        return@picker
                    }
                    onFolderPicked(pickedIds.single())
                    drivePickerState.value = DrivePickerState.Selected(pickedIds.single())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    drivePickerState.value = DrivePickerState.Error(
                        "The Drive folder selection could not be completed: ${failure.message ?: "unknown error"}",
                    )
                }
            },
            onError = { error ->
                drivePickerState.value = DrivePickerState.Error(
                    googleOAuthRecoveryMessage("{\"error\":\"$error\"}", credentials.source),
                )
            },
        )
        if (!registerPendingRequest(request)) return null
        drivePickerState.value = DrivePickerState.Picking
        if (interactive) {
            try {
                bootCallbackServer(callbackPort, generation)
                launchBrowser(pickerUrl, generation) { message ->
                    drivePickerState.value = DrivePickerState.Error(message)
                }
            } catch (t: Throwable) {
                clearPendingRequest(generation)
                stopServer(generation)
                drivePickerState.value = DrivePickerState.Error(
                    "Failed to start local authentication listener: ${t.message ?: "network port unavailable"}"
                )
                return null
            }
        }
        return state
    }

    internal fun beginGoogleDriveFolderPickerForTest(
        clientId: String,
        onFolderPicked: suspend (String) -> Unit,
    ): String {
        val identity = getAuthState() as? AuthState.Authenticated
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
}
