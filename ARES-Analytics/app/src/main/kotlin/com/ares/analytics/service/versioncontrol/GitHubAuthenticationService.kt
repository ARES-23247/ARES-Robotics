package com.ares.analytics.service.versioncontrol

import com.ares.analytics.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.time.Instant

sealed class GitHubConnectionState {
    object Disconnected : GitHubConnectionState()
    data class Unavailable(val message: String) : GitHubConnectionState()
    data class AwaitingUser(
        val userCode: String,
        val verificationUri: String,
        val expiresAtEpochSeconds: Long,
    ) : GitHubConnectionState()
    data class Connected(val login: String) : GitHubConnectionState()
    data class Error(val message: String) : GitHubConnectionState()
}

/** Owns GitHub App authorization, credential refresh, and permission-scoped destination discovery. */
class GitHubAuthenticationService internal constructor(
    private val clientId: String = BuildConfig.GITHUB_APP_CLIENT_ID,
    private val appSlug: String = BuildConfig.GITHUB_APP_SLUG,
    private val credentialRepository: ProjectGitHubCredentialRepository =
        ProjectGitHubCredentialRepository(createProjectBackupCredentialStore()),
    private val api: GitHubProjectApi = DefaultGitHubProjectApi(),
    private val browserLauncher: (String) -> Unit = { uri -> Desktop.getDesktop().browse(URI(uri)) },
    private val pollDelay: suspend (Long) -> Unit = { milliseconds -> delay(milliseconds) },
    private val epochSeconds: () -> Long = { Instant.now().epochSecond },
) {
    constructor() : this(
        clientId = BuildConfig.GITHUB_APP_CLIENT_ID,
        appSlug = BuildConfig.GITHUB_APP_SLUG,
    )

    private val operationMutex = Mutex()
    private val _state = MutableStateFlow<GitHubConnectionState>(initialState())
    val state: StateFlow<GitHubConnectionState> = _state.asStateFlow()

    suspend fun signIn() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            requireValidConfiguration()
            val authorization = api.beginDeviceAuthorization(clientId)
            val expiresAt = epochSeconds() + authorization.expiresInSeconds
            _state.value = GitHubConnectionState.AwaitingUser(
                authorization.userCode,
                authorization.verificationUri,
                expiresAt,
            )
            browserLauncher(authorization.verificationUri)
            var interval = authorization.intervalSeconds.coerceAtLeast(MINIMUM_DEVICE_POLL_SECONDS)
            while (epochSeconds() < expiresAt) {
                pollDelay(interval * 1_000)
                when (val result = api.pollDeviceAuthorization(clientId, authorization.deviceCode)) {
                    GitHubDevicePollResult.Pending -> Unit
                    GitHubDevicePollResult.SlowDown -> interval += 5
                    is GitHubDevicePollResult.Authorized -> {
                        val login = api.currentLogin(result.tokens.accessToken)
                        val credential = credentialRepository.from(result.tokens, login, epochSeconds())
                        credentialRepository.write(credential)
                        _state.value = GitHubConnectionState.Connected(login)
                        return@withLock
                    }
                    is GitHubDevicePollResult.Failed -> failSignIn(result.code)
                }
            }
            val message = "The GitHub sign-in code expired. Start sign-in again to receive a new code."
            _state.value = GitHubConnectionState.Error(message)
            error(message)
        }
    }

    suspend fun discoverDestinations(): GitHubBackupCatalog = withCredential { credential ->
        loadCatalog(credential)
    }

    suspend fun openInstallationPage() = withContext(Dispatchers.IO) {
        requireValidConfiguration()
        browserLauncher("https://github.com/apps/$appSlug/installations/new")
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            check(credentialRepository.delete()) { "GitHub credentials could not be removed from this computer." }
            _state.value = if (validGitHubAppConfiguration(clientId, appSlug)) {
                GitHubConnectionState.Disconnected
            } else {
                GitHubConnectionState.Unavailable("Official GitHub App backup is not configured in this build.")
            }
        }
    }

    internal suspend fun <T> withCredential(
        operation: suspend (StoredGitHubAppCredential) -> T,
    ): T = withContext(Dispatchers.IO) {
        operationMutex.withLock { operation(requireUsableCredential()) }
    }

    internal fun loadCatalog(credential: StoredGitHubAppCredential): GitHubBackupCatalog = credentialAware {
        val accounts = api.listInstallations(credential.accessToken)
            .distinctBy(GitHubBackupAccount::installationId)
            .sortedWith(compareBy(GitHubBackupAccount::kind, GitHubBackupAccount::login))
        val repositories = accounts.flatMap { account ->
            api.listRepositories(credential.accessToken, account.installationId)
        }.distinctBy { it.installationId to it.repositoryId }
            .sortedWith(compareBy(GitHubBackupRepository::ownerLogin, GitHubBackupRepository::name))
        GitHubBackupCatalog(accounts, repositories)
    }

    internal fun verifyDestinationAccess(
        credential: StoredGitHubAppCredential,
        destination: GitHubBackupDestination,
    ): Pair<GitHubBackupAccount, GitHubBackupRepository> {
        val catalog = loadCatalog(credential)
        val account = catalog.accounts.singleOrNull { it.installationId == destination.installationId }
            ?: error(
                "The saved ${destination.ownerLogin} GitHub App installation is no longer accessible. " +
                    "Ask a team owner to restore it or change destination.",
            )
        require(account.canWriteContents) {
            "The saved GitHub App installation no longer has Contents: write permission. Nothing was synchronized."
        }
        val repository = catalog.repositories.singleOrNull {
            it.installationId == destination.installationId && it.repositoryId == destination.repositoryId
        } ?: error("The saved repository is no longer granted to the ARES GitHub App. Nothing was synchronized.")
        require(repository.canUseForBackup) {
            repository.unavailableReason ?: "The saved repository cannot accept a backup."
        }
        return account to repository
    }

    private fun <T> credentialAware(block: () -> T): T = try {
        block()
    } catch (failure: GitHubApiException) {
        if (failure.status == 401) {
            invalidateCredential("GitHub access was revoked or expired. Saved access was cleared; sign in again.")
        }
        throw failure
    }

    private fun requireUsableCredential(): StoredGitHubAppCredential {
        val credential = loadCredentialOrInvalidate()
        val now = epochSeconds()
        if (credential.refreshTokenExpiresAtEpochSeconds <= now + TOKEN_EXPIRY_SAFETY_SECONDS) {
            invalidateCredential("GitHub refresh access expired. Saved access was cleared; sign in again.")
        }
        if (credential.accessTokenExpiresAtEpochSeconds > now + TOKEN_EXPIRY_SAFETY_SECONDS) return credential
        val refreshed = try {
            api.refreshUserAccessToken(clientId, credential.refreshToken)
        } catch (failure: GitHubAuthorizationException) {
            invalidateCredential(refreshFailureMessage(failure.code))
        } catch (failure: GitHubApiException) {
            if (failure.status == 401) {
                invalidateCredential("GitHub refresh access was revoked. Saved access was cleared; sign in again.")
            }
            throw failure
        }
        return credentialRepository.from(refreshed, credential.login, epochSeconds()).also(credentialRepository::write)
    }

    private fun loadCredentialOrInvalidate(): StoredGitHubAppCredential {
        return try {
            credentialRepository.read()
        } catch (_: Exception) {
            invalidateCredential("Saved GitHub access was invalid or unreadable and has been cleared. Sign in again.")
        } ?: error("Sign in with GitHub before choosing or synchronizing a backup.")
    }

    private fun invalidateCredential(message: String): Nothing {
        credentialRepository.delete()
        _state.value = GitHubConnectionState.Error(message)
        error(message)
    }

    private fun initialState(): GitHubConnectionState {
        if (!validGitHubAppConfiguration(clientId, appSlug)) {
            return GitHubConnectionState.Unavailable(
                "Official GitHub App backup is not configured in this build. Local history is still available.",
            )
        }
        val credential = try {
            credentialRepository.read()
        } catch (_: Exception) {
            credentialRepository.delete()
            return GitHubConnectionState.Error(
                "Saved GitHub access was invalid or unreadable and has been cleared. Sign in again.",
            )
        } ?: return GitHubConnectionState.Disconnected
        return GitHubConnectionState.Connected(credential.login)
    }

    private fun requireValidConfiguration() {
        require(validGitHubAppConfiguration(clientId, appSlug)) {
            "This ARES build has no GitHub App identity. Local history still works; install an official build configured for GitHub backup."
        }
    }

    private fun failSignIn(code: String): Nothing {
        val message = deviceFailureMessage(code)
        _state.value = GitHubConnectionState.Error(message)
        error(message)
    }

    private companion object {
        const val MINIMUM_DEVICE_POLL_SECONDS = 5L
        const val TOKEN_EXPIRY_SAFETY_SECONDS = 60L

        fun deviceFailureMessage(code: String): String = when (code) {
            "access_denied" -> "GitHub sign-in was denied. No credential was saved."
            "expired_token" -> "The GitHub sign-in code expired. Start sign-in again."
            "incorrect_device_code" -> "GitHub rejected the device code. Start sign-in again."
            "incorrect_client_credentials" -> "This ARES build has an invalid GitHub App client ID."
            else -> "GitHub sign-in failed ($code). No credential was saved."
        }

        fun refreshFailureMessage(code: String): String = when (code) {
            "bad_refresh_token", "expired_refresh_token" ->
                "GitHub refresh access expired or was revoked. Saved access was cleared; sign in again."
            else -> "GitHub could not refresh saved access ($code). Saved access was cleared; sign in again."
        }
    }
}
