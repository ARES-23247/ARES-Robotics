package org.aresfirst.starter.frc

import com.areslib.state.RobotFieldManager
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.util.HexFormat

/** A successfully installed field and the digest of the exact received UTF-8 payload. */
internal data class FrcStudioFieldApplication(
    val contract: StarterFieldContract,
    val sha256: String,
    val changed: Boolean,
)

internal fun encodeFrcStudioFieldReceipt(
    application: FrcStudioFieldApplication,
    simulatorSession: String,
    sequence: Long,
): String {
    require(simulatorSession.isNotBlank()) { "simulator field receipt session must not be blank" }
    require(sequence > 0L) { "simulator field receipt sequence must be positive" }
    val config = application.contract.config
    return JsonObject().apply {
        addProperty("session", simulatorSession)
        addProperty("sequence", sequence)
        addProperty("configId", config.id)
        addProperty("revision", config.revision)
        addProperty("sha256", application.sha256)
        addProperty("obstacleCount", config.obstacles.size)
        addProperty("elementCount", config.elements.size)
        addProperty("aprilTagCount", config.apriltags.size)
    }.toString()
}

/**
 * Commits revision identity only after its application callback succeeds. Callbacks must leave
 * their previous state intact on failure. An exact active retry gets a fresh receipt without
 * decoding, hashing, or applying the same field again; a failed attempt never becomes active.
 * This gate is confined to the simulator update thread, as are its application callbacks.
 */
internal class FrcStudioFieldGate(
    private val loader: (ByteArray) -> StarterFieldContract? = ::loadStarterFieldContract,
) {
    private var active: FrcStudioFieldApplication? = null
    private var activePayload: String? = null
    var rejectionReason: String? = null
        private set

    fun accept(payload: String, apply: (StarterFieldContract) -> Unit = {}): FrcStudioFieldApplication? {
        rejectionReason = null
        if (payload.isBlank()) return reject("Canonical field payload is empty")
        val previous = active
        if (previous != null && payload == activePayload) return previous.copy(changed = false)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val contract = try {
            loader(bytes)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return reject("Canonical field could not be loaded: ${failure.message ?: failure.javaClass.simpleName}")
        } ?: return reject(StarterFieldContractLoader.error ?: "Canonical FRC field is invalid")
        val config = contract.config
        if (previous != null && config.id == previous.contract.config.id) {
            val revision = previous.contract.config.revision
            if (config.revision < revision) return reject("Ignored stale field revision ${config.revision}; active revision is $revision")
            // Only an exact payload retry may reuse the revision, even if the decoded values match.
            if (config.revision == revision) return reject("Field revision ${config.revision} was reused with different content")
        }
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val application = FrcStudioFieldApplication(contract, digest, changed = true)
        try {
            apply(contract)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return reject("Canonical field was not applied: ${failure.message ?: failure.javaClass.simpleName}")
        }
        active = application
        activePayload = payload
        return application
    }

    private fun reject(reason: String): FrcStudioFieldApplication? {
        rejectionReason = reason
        return null
    }
}

/** Publish the canonical field only after the simulator has accepted its geometry. */
internal fun applyStarterSimulationField(simulation: StarterDriveSimulation, field: StarterFieldContract) {
    simulation.configureField(field.config)
    RobotFieldManager.setActiveConfig(field.config)
}
