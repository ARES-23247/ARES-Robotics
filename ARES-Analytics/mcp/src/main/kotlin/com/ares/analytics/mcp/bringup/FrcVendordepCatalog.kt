package com.ares.analytics.mcp.bringup

import com.google.gson.JsonParser
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class InstalledVendordepSummary(
    val fileName: String,
    val name: String,
    val version: String,
    val frcYear: String,
    val uuid: String,
    val conflictingUuids: List<String> = emptyList(),
)

internal data class VendordepPreset(
    val presetId: String,
    val fileName: String,
    val uuid: String,
    val jsonContent: String,
    val importedPackagePrefix: String,
)

/**
 * Offline pinned 2026 FRC `vendordeps/<name>.json` catalog + scanner for missing/conflicting vendordeps.
 */
internal object FrcVendordepCatalog {
    const val PHOENIX6_UUID = "e995de00-2c64-4df5-8831-c1441420ff19"
    const val REVLIB_UUID = "3f48eb8c-50fe-43a6-9cb7-44c86353c4cb"
    const val PHOTONLIB_UUID = "515fe07e-bfc6-11fa-b3de-0242ac130004"

    private val officialPhoenix6Json: String by lazy {
        val stream = FrcVendordepCatalog::class.java.getResourceAsStream("/vendordeps/Phoenix6-26.1.1.json")
            ?: error("Missing bundled official /vendordeps/Phoenix6-26.1.1.json resource")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    val presets: List<VendordepPreset> by lazy {
        listOf(
            VendordepPreset(
                presetId = "PHOENIX6",
                fileName = "Phoenix6-26.1.1.json",
                uuid = PHOENIX6_UUID,
                importedPackagePrefix = "com.ctre.phoenix6",
                jsonContent = officialPhoenix6Json,
            ),
            VendordepPreset(
                presetId = "REVLIB",
                fileName = "REVLib-2026.0.5.json",
                uuid = REVLIB_UUID,
                importedPackagePrefix = "com.revrobotics",
            jsonContent = """
                |{
                |    "fileName": "REVLib-2026.0.5.json",
                |    "name": "REVLib",
                |    "version": "2026.0.5",
                |    "frcYear": "2026",
                |    "uuid": "3f48eb8c-50fe-43a6-9cb7-44c86353c4cb",
                |    "mavenUrls": [
                |        "https://maven.revrobotics.com/"
                |    ],
                |    "jsonUrl": "https://software-metadata.revrobotics.com/REVLib-2026.json",
                |    "javaDependencies": [
                |        {
                |            "groupId": "com.revrobotics.frc",
                |            "artifactId": "REVLib-java",
                |            "version": "2026.0.5"
                |        }
                |    ],
                |    "jniDependencies": [
                |        {
                |            "groupId": "com.revrobotics.frc",
                |            "artifactId": "REVLib-driver",
                |            "version": "2026.0.5",
                |            "skipInvalidPlatforms": true,
                |            "isJar": false,
                |            "validPlatforms": [
                |                "windowsx86-64",
                |                "linuxarm64",
                |                "linuxx86-64",
                |                "linuxathena",
                |                "osxuniversal"
                |            ]
                |        }
                |    ],
                |    "cppDependencies": []
                |}
            """.trimMargin(),
        ),
        VendordepPreset(
            presetId = "PHOTONLIB",
            fileName = "PhotonLib-v2026.3.4.json",
            uuid = "515fe07e-bfc6-11fa-b3de-0242ac130004",
            importedPackagePrefix = "org.photonvision",
            jsonContent = """
                |{
                |    "fileName": "PhotonLib-v2026.3.4.json",
                |    "name": "photonlib",
                |    "version": "v2026.3.4",
                |    "frcYear": "2026",
                |    "uuid": "515fe07e-bfc6-11fa-b3de-0242ac130004",
                |    "mavenUrls": [
                |        "https://maven.photonvision.org/repository/internal",
                |        "https://maven.photonvision.org/repository/snapshots"
                |    ],
                |    "jsonUrl": "https://maven.photonvision.org/repository/internal/org/photonvision/photonlib-json/1.0/photonlib-json-1.0.json",
                |    "javaDependencies": [
                |        {
                |            "groupId": "org.photonvision",
                |            "artifactId": "photonlib-java",
                |            "version": "v2026.3.4"
                |        },
                |        {
                |            "groupId": "org.photonvision",
                |            "artifactId": "photontargeting-java",
                |            "version": "v2026.3.4"
                |        }
                |    ],
                |    "jniDependencies": [],
                |    "cppDependencies": []
                |}
            """.trimMargin(),
        ),
        VendordepPreset(
            presetId = "WPILIB_NEW_COMMANDS",
            fileName = "WPILibNewCommands.json",
            uuid = "111e20f7-815e-48f8-9dd6-e675ce75b266",
            importedPackagePrefix = "edu.wpi.first.wpilibj2.command",
            jsonContent = """
                |{
                |  "fileName": "WPILibNewCommands.json",
                |  "name": "WPILib-New-Commands",
                |  "version": "1.0.0",
                |  "uuid": "111e20f7-815e-48f8-9dd6-e675ce75b266",
                |  "frcYear": "2026",
                |  "mavenUrls": [],
                |  "jsonUrl": "",
                |  "javaDependencies": [
                |    {
                |      "groupId": "edu.wpi.first.wpilibNewCommands",
                |      "artifactId": "wpilibNewCommands-java",
                |      "version": "wpilib"
                |    }
                |  ],
                |  "jniDependencies": [],
                |  "cppDependencies": []
                |}
            """.trimMargin(),
        ),
    )
    }

    fun resolvePreset(raw: String): VendordepPreset {
        val norm = raw.trim().uppercase().replace('-', '_')
        return presets.firstOrNull { it.presetId == norm }
            ?: throw IllegalArgumentException(
                "Unknown vendordepPreset '$raw'; expected one of: ${presets.joinToString(", ") { it.presetId }}",
            )
    }

    fun listInstalled(projectRoot: File): List<InstalledVendordepSummary> {
        val dir = File(projectRoot, "vendordeps")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file -> parseSummary(file) }
            .orEmpty()
    }

    fun parseSummary(file: File): InstalledVendordepSummary? = parseSummaryText(file.name, file.readText())

    fun parseSummaryText(fallbackFileName: String, jsonText: String): InstalledVendordepSummary? = runCatching {
        val obj = JsonParser.parseString(jsonText).asJsonObject
        val conflicts = obj.getAsJsonArray("conflictsWith")
            ?.mapNotNull { el -> runCatching { el.asJsonObject.get("uuid")?.asString }.getOrNull() }
            .orEmpty()
        InstalledVendordepSummary(
            fileName = obj.get("fileName")?.asString ?: fallbackFileName,
            name = obj.get("name")?.asString ?: fallbackFileName.removeSuffix(".json"),
            version = obj.get("version")?.asString ?: "unknown",
            frcYear = obj.get("frcYear")?.asString ?: "unknown",
            uuid = obj.get("uuid")?.asString ?: "",
            conflictingUuids = conflicts,
        )
    }.getOrNull()
}
