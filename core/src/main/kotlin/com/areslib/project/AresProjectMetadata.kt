package com.areslib.project

import com.google.gson.GsonBuilder
import java.security.MessageDigest

const val ARES_PROJECT_METADATA_SCHEMA_VERSION: Int = 1

enum class AresLeague { FTC, FRC }

/** Coordinate frame used by authored routine poses. Headings are CCW-positive in every frame. */
enum class AresCoordinateConvention {
    /** Origin at field center; FTC alliance transforms rotate 180 degrees around that origin. */
    CENTER_ORIGIN_CCW,

    /** Origin at the blue-alliance field corner; FRC alliance transforms reflect field X. */
    BLUE_CORNER_ORIGIN_CCW,
}

/**
 * Canonical project geometry shared by the editor, generator, simulator, and robot preflight.
 *
 * Keeping these values in `.ares/project.json` prevents two laptops from validating the same
 * routine with different machine-local workspace settings.
 */
data class AresProjectMetadataDocument(
    val schemaVersion: Int = ARES_PROJECT_METADATA_SCHEMA_VERSION,
    val projectId: String,
    val league: AresLeague,
    val coordinateConvention: AresCoordinateConvention,
    val robotLengthMeters: Double,
    val robotWidthMeters: Double,
    val fieldLengthMeters: Double,
    val fieldWidthMeters: Double,
)

fun validateAresProjectMetadata(document: AresProjectMetadataDocument): List<String> = buildList {
    if (document.schemaVersion != ARES_PROJECT_METADATA_SCHEMA_VERSION) {
        add("Unsupported project metadata schema ${document.schemaVersion}")
    }
    if (!document.projectId.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) {
        add("Project ID must be a stable key")
    }
    val dimensions = listOf(
        "robotLengthMeters" to document.robotLengthMeters,
        "robotWidthMeters" to document.robotWidthMeters,
        "fieldLengthMeters" to document.fieldLengthMeters,
        "fieldWidthMeters" to document.fieldWidthMeters,
    )
    dimensions.forEach { (name, value) ->
        if (!value.isFinite() || value <= 0.0) add("$name must be finite and positive")
    }
    if (document.robotLengthMeters > document.fieldLengthMeters ||
        document.robotWidthMeters > document.fieldWidthMeters
    ) {
        add("Robot footprint must fit inside the field")
    }
    when (document.league) {
        AresLeague.FTC -> if (document.coordinateConvention != AresCoordinateConvention.CENTER_ORIGIN_CCW) {
            add("FTC projects must use CENTER_ORIGIN_CCW")
        }
        AresLeague.FRC -> if (document.coordinateConvention != AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW) {
            add("FRC projects must use BLUE_CORNER_ORIGIN_CCW")
        }
    }
}

object AresProjectMetadataCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(document: AresProjectMetadataDocument): String {
        requireValid(document)
        return gson.toJson(document)
    }

    fun decode(json: String): AresProjectMetadataDocument {
        val document = try {
            gson.fromJson(json, AresProjectMetadataDocument::class.java)
        } catch (error: Exception) {
            throw IllegalArgumentException("Project metadata is not valid JSON: ${error.message}", error)
        } ?: throw IllegalArgumentException("Project metadata is empty")
        requireValid(document)
        return document
    }

    fun contentHash(document: AresProjectMetadataDocument): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(document).toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun requireValid(document: AresProjectMetadataDocument) {
        val issues = validateAresProjectMetadata(document)
        require(issues.isEmpty()) { issues.joinToString("; ") }
    }
}
