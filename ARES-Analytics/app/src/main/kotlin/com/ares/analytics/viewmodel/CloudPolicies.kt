package com.ares.analytics.viewmodel

import com.ares.analytics.shared.models.DriveDestinationConfig

internal fun shouldLoadRemoteCloudIndex(
    isAuthenticated: Boolean,
    driveDestination: DriveDestinationConfig?,
): Boolean = isAuthenticated && driveDestination != null

internal fun robotLogRefreshFailureMessage(robotIp: String, error: Throwable): String {
    val root = generateSequence(error) { it.cause }.last()
    val detail = root.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    return buildString {
        append("[CloudViewModel] Robot log refresh failed for ")
        append(robotIp)
        append(": ")
        append(root::class.simpleName ?: "Error")
        if (detail.isNotEmpty()) append(" — ").append(detail)
    }
}
