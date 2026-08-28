package com.ares.analytics.service.integration

import com.ares.analytics.service.AppDataPaths
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.GoogleDriveService
import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.AppJsonPretty
import com.ares.analytics.shared.models.IntegrationEventType
import com.ares.analytics.shared.models.IntegrationSettings
import com.ares.analytics.shared.models.NotificationProviderConfig
import com.ares.analytics.shared.models.NotificationProviderKind
import com.ares.analytics.shared.models.NotebookPublisherKind
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.URI

class IntegrationSettingsService(
    private val settingsFile: File = AppDataPaths.file("integrations.json"),
    private val credentialStore: IntegrationCredentialStore = createIntegrationCredentialStore(),
) {
    private val mutex = Mutex()

    suspend fun load(): IntegrationSettings = mutex.withLock { loadSync() }

    suspend fun save(settings: IntegrationSettings) = mutex.withLock {
        validate(settings)
        writeFileAtomically(settingsFile) { temporary ->
            temporary.writeText(AppJsonPretty.encodeToString(settings))
        }
    }

    suspend fun saveCredential(providerId: String, credential: IntegrationCredential) = mutex.withLock {
        credentialStore.write(providerId, credential)
    }

    suspend fun deleteCredential(providerId: String): Boolean = mutex.withLock {
        credentialStore.delete(providerId)
    }

    suspend fun credential(providerId: String): IntegrationCredential? = mutex.withLock {
        credentialStore.read(providerId)
    }

    val credentialProtectionDescription: String
        get() = credentialStore.protectionDescription

    internal suspend fun configuredProviders(
        httpClient: HttpClient,
        store: IntegrationStore,
        googleDriveService: GoogleDriveService,
    ): ConfiguredNotificationProviders = mutex.withLock {
        val settings = loadSync()
        val providers = mutableListOf<IntegrationDeliveryProvider>()
        val errors = linkedMapOf<String, String>()
        settings.notificationProviders.filter(NotificationProviderConfig::enabled).forEach { config ->
            val credential = runCatching { credentialStore.read(config.providerId) }.getOrElse { failure ->
                errors[config.providerId] = "Saved credentials could not be read: ${failure.message ?: failure::class.java.simpleName}"
                null
            }
            if (credential == null) {
                errors.putIfAbsent(config.providerId, "Credentials are not configured")
                return@forEach
            }
            runCatching {
                when (config.kind) {
                    NotificationProviderKind.ZULIP -> ZulipNotificationProvider(
                        config = config,
                        credential = credential,
                        httpClient = httpClient,
                    )
                    NotificationProviderKind.WEBHOOK -> WebhookNotificationProvider(
                        config = config,
                        credential = credential,
                        httpClient = httpClient,
                    )
                }
            }.onSuccess(providers::add).onFailure { failure ->
                errors[config.providerId] = failure.message ?: "Provider configuration is invalid"
            }
        }
        settings.notebookPublishers.filter { it.enabled }.forEach { config ->
            runCatching {
                val publisher = when (config.kind) {
                    NotebookPublisherKind.LOCAL_MARKDOWN -> LocalMarkdownNotebookPublisher(
                        publisherId = config.publisherId,
                        directory = File(requireNotNull(config.localDirectory)),
                    )
                    NotebookPublisherKind.GOOGLE_DRIVE -> GoogleDriveNotebookPublisher(
                        publisherId = config.publisherId,
                        drive = GoogleDriveNotebookClient(googleDriveService),
                        folderName = config.driveFolderName,
                    )
                    NotebookPublisherKind.CMS -> {
                        val credential = credentialStore.read(config.publisherId)
                            ?: throw IllegalArgumentException("Credentials are not configured")
                        CmsNotebookPublisher(
                            publisherId = config.publisherId,
                            endpoint = requireNotNull(config.cmsEndpoint),
                            credential = credential,
                            httpClient = httpClient,
                        )
                    }
                }
                NotebookPublisherDeliveryAdapter(store, publisher)
            }.onSuccess(providers::add).onFailure { failure ->
                errors[config.publisherId] = failure.message ?: "Publisher configuration is invalid"
            }
        }
        ConfiguredNotificationProviders(settings, providers, errors)
    }

    private fun loadSync(): IntegrationSettings {
        if (!settingsFile.isFile) return IntegrationSettings()
        require(settingsFile.length() <= MAX_SETTINGS_BYTES) { "Integration settings file is too large" }
        return AppJson.decodeFromString<IntegrationSettings>(settingsFile.readText()).also(::validate)
    }

    private fun validate(settings: IntegrationSettings) {
        require(settings.schemaVersion == 1) { "Unsupported integration settings schema" }
        require(settings.notificationProviders.size <= 32) { "Too many notification providers" }
        require(settings.notebookPublishers.size <= 32) { "Too many notebook publishers" }
        val ids = mutableSetOf<String>()
        settings.notificationProviders.forEach { config ->
            require(config.providerId.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) {
                "Notification provider ID is invalid"
            }
            require(ids.add(config.providerId)) { "Notification provider IDs must be unique" }
            require(config.displayName.isNotBlank() && config.displayName.length <= 128) {
                "Notification provider display name is invalid"
            }
            require(config.eventTypes.isNotEmpty()) { "Notification provider must route at least one event type" }
            when (config.kind) {
                NotificationProviderKind.ZULIP -> {
                    require(config.webhook == null && config.zulip != null) { "Zulip provider target is invalid" }
                    validateHttpsUrl(requireNotNull(config.zulip).siteUrl)
                }
                NotificationProviderKind.WEBHOOK -> {
                    require(config.zulip == null && config.webhook != null) { "Webhook provider target is invalid" }
                    validateHttpsUrl(requireNotNull(config.webhook).url)
                }
            }
        }
        settings.notebookPublishers.forEach { config ->
            require(config.publisherId.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) {
                "Notebook publisher ID is invalid"
            }
            require(ids.add(config.publisherId)) { "Integration provider IDs must be unique" }
            require(config.displayName.isNotBlank() && config.displayName.length <= 128) {
                "Notebook publisher display name is invalid"
            }
            when (config.kind) {
                NotebookPublisherKind.LOCAL_MARKDOWN -> require(!config.localDirectory.isNullOrBlank()) {
                    "Local notebook export directory is required"
                }
                NotebookPublisherKind.GOOGLE_DRIVE -> require(
                    config.driveFolderName.isNotBlank() && config.driveFolderName.length <= 128
                ) { "Drive notebook folder name is invalid" }
                NotebookPublisherKind.CMS -> {
                    require(!config.cmsEndpoint.isNullOrBlank()) { "CMS endpoint is required" }
                    validateHttpsUrl(requireNotNull(config.cmsEndpoint))
                    require(config.requireApproval) { "CMS publishers must require human approval" }
                }
            }
        }
    }

    private fun validateHttpsUrl(value: String) {
        val uri = runCatching { URI(value.trim()) }.getOrElse { throw IllegalArgumentException("Integration URL is invalid") }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Integration URL must be an HTTPS URL without embedded credentials"
        }
    }

    private companion object {
        const val MAX_SETTINGS_BYTES = 256 * 1_024L
    }
}

internal data class ConfiguredNotificationProviders(
    val settings: IntegrationSettings,
    val providers: List<IntegrationDeliveryProvider>,
    val errors: Map<String, String>,
)

class NotificationIntegrationService(
    private val databaseService: DatabaseService,
    private val settingsService: IntegrationSettingsService,
    private val googleDriveService: GoogleDriveService,
    private val httpClient: HttpClient = notificationHttpClient(),
) {
    private val lifecycleMutex = Mutex()
    private var coordinator: IntegrationOutboxCoordinator? = null
    private var configurationErrors: Map<String, String> = emptyMap()

    suspend fun start() = lifecycleMutex.withLock {
        coordinator?.closeAndJoin()
        val configured = runCatching {
            settingsService.configuredProviders(httpClient, databaseService.integrations, googleDriveService)
        }.getOrElse { failure ->
            configurationErrors = mapOf("settings" to (failure.message ?: "Integration settings are invalid"))
            databaseService.integrationRouting.replace(emptyMap())
            return@withLock
        }
        configurationErrors = configured.errors
        val activeProviderIds = configured.providers.mapTo(hashSetOf(), IntegrationDeliveryProvider::providerId)
        val routes = IntegrationEventType.entries.associateWith { type ->
            val notificationRoutes = configured.settings.notificationProviders.asSequence()
                .filter { it.enabled && it.providerId in activeProviderIds && type in it.eventTypes }
                .map(NotificationProviderConfig::providerId)
                .toSet()
            val publisherRoutes = if (
                type == IntegrationEventType.NOTEBOOK_DRAFT_READY ||
                type == IntegrationEventType.SOFTWARE_DIGEST_READY
            ) {
                configured.settings.notebookPublishers.asSequence()
                    .filter { it.enabled && it.publisherId in activeProviderIds }
                    .map { it.publisherId }
                    .toSet()
            } else {
                emptySet()
            }
            notificationRoutes + publisherRoutes
        }
        databaseService.integrationRouting.replace(
            routes,
            configured.settings.notebookPublishers.asSequence()
                .filter { it.enabled && it.publisherId in activeProviderIds }
                .map { it.publisherId }
                .toSet(),
        )
        coordinator = IntegrationOutboxCoordinator(databaseService.integrations, configured.providers).also { it.start() }
    }

    suspend fun reload() = start()

    fun configurationErrors(): Map<String, String> = configurationErrors.toMap()

    suspend fun closeAndJoin() = lifecycleMutex.withLock {
        databaseService.integrationRouting.replace(emptyMap())
        coordinator?.closeAndJoin()
        coordinator = null
        httpClient.close()
    }
}
