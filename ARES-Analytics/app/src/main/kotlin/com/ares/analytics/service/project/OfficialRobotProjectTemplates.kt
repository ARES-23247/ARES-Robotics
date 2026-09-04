package com.ares.analytics.service.project

import com.ares.analytics.BuildConfig
import com.ares.analytics.shared.models.League

/** Immutable project archives bundled into this exact Studio release. */
internal fun officialRobotProjectTemplates(): List<RobotProjectTemplate> = listOf(
    RobotProjectTemplate(
        id = "ares-ftc-starter-${BuildConfig.FTC_STARTER_VERSION}",
        displayName = "ARES FTC Starter",
        league = League.FTC,
        aresVersion = BuildConfig.ARES_VERSION,
        revision = "schema4-standalone-v1",
        archiveUrl = "https://github.com/ARES-23247/ARES-Robotics/releases/download/v${BuildConfig.VERSION}/" +
            "ARES-FTC-Starter-${BuildConfig.FTC_STARTER_VERSION}.zip",
        archiveSha256 = BuildConfig.FTC_STARTER_SHA256,
        kind = RobotProjectTemplateKind.GENERIC_STARTER,
        bundledResourcePath = "/project-templates/ARES-FTC-Starter-${BuildConfig.FTC_STARTER_VERSION}.zip",
        deploymentPolicy = RobotProjectDeploymentPolicy.HARDWARE_REVIEW_REQUIRED,
    ),
    RobotProjectTemplate(
        id = "ares-frc-starter-${BuildConfig.FRC_STARTER_VERSION}",
        displayName = "ARES FRC Starter",
        league = League.FRC,
        aresVersion = BuildConfig.ARES_VERSION,
        revision = "schema4-standalone-v1",
        archiveUrl = "https://github.com/ARES-23247/ARES-Robotics/releases/download/v${BuildConfig.VERSION}/" +
            "ARES-FRC-Starter-${BuildConfig.FRC_STARTER_VERSION}.zip",
        archiveSha256 = BuildConfig.FRC_STARTER_SHA256,
        kind = RobotProjectTemplateKind.GENERIC_STARTER,
        bundledResourcePath = "/project-templates/ARES-FRC-Starter-${BuildConfig.FRC_STARTER_VERSION}.zip",
        deploymentPolicy = RobotProjectDeploymentPolicy.HARDWARE_REVIEW_REQUIRED,
    ),
    RobotProjectTemplate(
        id = "ares-xrp-starter-${BuildConfig.XRP_STARTER_VERSION}",
        displayName = "ARES XRP Starter",
        league = League.XRP,
        aresVersion = BuildConfig.ARES_VERSION,
        revision = "schema4-micropython-v1",
        archiveUrl = "https://github.com/ARES-23247/ARES-Robotics/releases/download/v${BuildConfig.VERSION}/" +
            "ARES-XRP-Starter-${BuildConfig.XRP_STARTER_VERSION}.zip",
        archiveSha256 = BuildConfig.XRP_STARTER_SHA256,
        kind = RobotProjectTemplateKind.GENERIC_STARTER,
        bundledResourcePath = "/project-templates/ARES-XRP-Starter-${BuildConfig.XRP_STARTER_VERSION}.zip",
        deploymentPolicy = RobotProjectDeploymentPolicy.HARDWARE_REVIEW_REQUIRED,
    ),
    RobotProjectTemplate(
        id = "ares-lightbot-example-${BuildConfig.LIGHTBOT_EXAMPLE_VERSION}",
        displayName = "Lightbot",
        league = League.FTC,
        aresVersion = BuildConfig.ARES_VERSION,
        revision = "schema4-lightbot-v1",
        archiveUrl = "https://github.com/ARES-23247/ARES-Robotics/releases/download/v${BuildConfig.VERSION}/" +
            "ARES-Lightbot-Example-${BuildConfig.LIGHTBOT_EXAMPLE_VERSION}.zip",
        archiveSha256 = BuildConfig.LIGHTBOT_EXAMPLE_SHA256,
        kind = RobotProjectTemplateKind.EXAMPLE,
        bundledResourcePath = "/project-templates/ARES-Lightbot-Example-${BuildConfig.LIGHTBOT_EXAMPLE_VERSION}.zip",
        deploymentPolicy = RobotProjectDeploymentPolicy.SIMULATION_ONLY_REFERENCE,
    ),
)
