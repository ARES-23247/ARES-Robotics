package com.ares.analytics.shared.models

import kotlinx.serialization.Serializable

const val INTEGRATION_SETTINGS_SCHEMA_VERSION: Int = 1

@Serializable
enum class NotificationProviderKind {
    ZULIP,
    WEBHOOK,
}

@Serializable
enum class NotebookPublisherKind {
    LOCAL_MARKDOWN,
    GOOGLE_DRIVE,
    CMS,
}

@Serializable
data class NotebookPublisherConfig(
    val publisherId: String,
    val displayName: String,
    val kind: NotebookPublisherKind,
    val enabled: Boolean = true,
    val localDirectory: String? = null,
    val driveFolderName: String = "engineering-notebook",
    val cmsEndpoint: String? = null,
    val requireApproval: Boolean = kind == NotebookPublisherKind.CMS,
)

@Serializable
data class ZulipNotificationTarget(
    val siteUrl: String,
    val stream: String,
    val topic: String,
)

@Serializable
data class WebhookNotificationTarget(
    val url: String,
)

@Serializable
data class NotificationProviderConfig(
    val providerId: String,
    val displayName: String,
    val kind: NotificationProviderKind,
    val enabled: Boolean = true,
    val eventTypes: Set<IntegrationEventType>,
    val minimumIssueSeverity: IntegrationIssueSeverity = IntegrationIssueSeverity.WARNING,
    val zulip: ZulipNotificationTarget? = null,
    val webhook: WebhookNotificationTarget? = null,
)

@Serializable
data class IntegrationSettings(
    val notificationProviders: List<NotificationProviderConfig> = emptyList(),
    val notebookPublishers: List<NotebookPublisherConfig> = emptyList(),
    val schemaVersion: Int = INTEGRATION_SETTINGS_SCHEMA_VERSION,
)
