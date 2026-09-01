package com.ares.analytics.viewmodel

import com.ares.analytics.shared.models.DriveDestinationConfig
import com.ares.analytics.shared.models.DriveDestinationType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class CloudViewModelTest {
    @Test
    fun `remote cloud index requires authentication and an explicit destination`() {
        val destination = DriveDestinationConfig(
            type = DriveDestinationType.PERSONAL_FOLDER,
            rootFolderId = "folder-id",
            displayName = "ARES runs",
            accountSubject = "account-subject",
            accountEmail = "student@example.com",
        )

        assertFalse(shouldLoadRemoteCloudIndex(isAuthenticated = false, driveDestination = null))
        assertFalse(shouldLoadRemoteCloudIndex(isAuthenticated = true, driveDestination = null))
        assertFalse(shouldLoadRemoteCloudIndex(isAuthenticated = false, driveDestination = destination))
        assertTrue(shouldLoadRemoteCloudIndex(isAuthenticated = true, driveDestination = destination))
    }

    @Test
    fun `robot log refresh failures are concise and identify the root cause`() {
        val message = robotLogRefreshFailureMessage(
            "10.99.92.2",
            IllegalStateException("catalog failed", java.net.ConnectException("Connection refused\nignored detail")),
        )

        assertEquals(
            "[CloudViewModel] Robot log refresh failed for 10.99.92.2: ConnectException — Connection refused",
            message,
        )
        assertFalse(message.contains('\n'))
    }
}
