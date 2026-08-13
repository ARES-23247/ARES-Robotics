package com.areslib.tuning

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.security.MessageDigest

const val ARES_TUNING_PROFILE_SCHEMA_VERSION: Int = 1
const val ARES_TUNING_COMPONENT_SCHEMA_VERSION: Int = 1

enum class TuningParameterType { DOUBLE, INT, BOOLEAN, TEXT, ENUM }
enum class TuningApplyPolicy {
    LIVE_SAFE, DISABLED_ONLY, RESTART_REQUIRED, REBUILD_REQUIRED, CALIBRATION_ONLY, READ_ONLY_VENDOR,
}
enum class TuningProfileAuthority { CANONICAL_CHECKED_IN, LOCAL_EXPERIMENTAL }

/** Exactly one field is populated, according to the declaration's type. */
data class TuningValue(
    val doubleValue: Double? = null,
    val intValue: Int? = null,
    val booleanValue: Boolean? = null,
    val textValue: String? = null,
)

/** A parameter is declared by the component that consumes it, never inferred from arbitrary state. */
data class TuningParameterDeclaration(
    val uid: String,
    val key: String,
    val componentUid: String,
    val displayName: String,
    val description: String,
    val type: TuningParameterType,
    val unit: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val defaultValue: TuningValue,
    val enumOptions: List<String> = emptyList(),
    val applyPolicy: TuningApplyPolicy,
)

data class TuningAssignment(val parameterUid: String, val value: TuningValue)

/** Project/global component declarations stored as `.arestuningcomponent` files under `.ares/tuning-components`. */
data class TuningComponentDocument(
    val schemaVersion: Int = ARES_TUNING_COMPONENT_SCHEMA_VERSION,
    val uid: String,
    val projectUid: String,
    val displayName: String,
    val description: String,
    val parameters: List<TuningParameterDeclaration>,
)

/** Evidence that a local experiment was deliberately reviewed before becoming canonical data. */
data class TuningPromotionData(
    val sourceLocalProfileUid: String,
    val sourceContentSha256: String,
    val evidencePaths: List<String>,
    val evidenceSha256: List<String>,
    val reviewedBy: String,
    val reviewSummary: String,
)

/** Named robot-owned tuning profile stored as an `.arestuning` file under `.ares/tuning`. */
data class TuningProfileDocument(
    val schemaVersion: Int = ARES_TUNING_PROFILE_SCHEMA_VERSION,
    val uid: String,
    val profileId: String,
    val displayName: String,
    val description: String,
    val projectUid: String,
    val drivebaseUid: String? = null,
    val authority: TuningProfileAuthority,
    val baseProfileUid: String? = null,
    val values: List<TuningAssignment>,
    val promotion: TuningPromotionData? = null,
)

data class TuningValidationIssue(val path: String, val message: String)

fun validateTuningParameterDeclarations(declarations: List<TuningParameterDeclaration>): List<TuningValidationIssue> = buildList {
    fun issue(path: String, message: String) { add(TuningValidationIssue(path, message)) }
    duplicate(declarations.map { it.uid }).forEach { issue("uid", "Parameter UID '$it' is duplicated") }
    duplicate(declarations.map { it.key }).forEach { issue("key", "Parameter key '$it' is duplicated") }
    declarations.forEachIndexed { index, declaration ->
        val path = "[$index]"
        if (!declaration.uid.matches(UID)) issue("$path.uid", "Parameter UID is invalid")
        if (!declaration.key.matches(KEY)) issue("$path.key", "Parameter key is invalid")
        if (!declaration.componentUid.matches(UID)) issue("$path.componentUid", "Component UID is invalid")
        if (declaration.displayName.isBlank()) issue("$path.displayName", "Display name is required")
        if (declaration.description.isBlank()) issue("$path.description", "Description is required")
        if (declaration.unit?.isBlank() == true) issue("$path.unit", "Unit must be non-blank")
        declaration.minimum?.let { if (!it.isFinite()) issue("$path.minimum", "Minimum must be finite") }
        declaration.maximum?.let { if (!it.isFinite()) issue("$path.maximum", "Maximum must be finite") }
        if (declaration.minimum != null && declaration.maximum != null && declaration.minimum > declaration.maximum) issue(path, "Minimum cannot exceed maximum")
        if (declaration.type !in setOf(TuningParameterType.DOUBLE, TuningParameterType.INT) && (declaration.minimum != null || declaration.maximum != null)) issue(path, "Only numeric parameters accept bounds")
        if (declaration.type == TuningParameterType.ENUM) {
            if (declaration.enumOptions.isEmpty()) issue("$path.enumOptions", "Enum parameters require options")
            duplicate(declaration.enumOptions).forEach { issue("$path.enumOptions", "Enum option '$it' is duplicated") }
            if (declaration.enumOptions.any(String::isBlank)) issue("$path.enumOptions", "Enum options cannot be blank")
        } else if (declaration.enumOptions.isNotEmpty()) issue("$path.enumOptions", "Only enum parameters accept options")
        validateValue(declaration, declaration.defaultValue)?.let { issue("$path.defaultValue", it) }
    }
}

fun validateTuningProfileDocument(
    profile: TuningProfileDocument,
    declarations: Collection<TuningParameterDeclaration>,
): List<TuningValidationIssue> = buildList {
    fun issue(path: String, message: String) { add(TuningValidationIssue(path, message)) }
    if (profile.schemaVersion != ARES_TUNING_PROFILE_SCHEMA_VERSION) issue("schemaVersion", "Unsupported tuning profile schema ${profile.schemaVersion}")
    if (!profile.uid.matches(UID)) issue("uid", "Profile UID is invalid")
    if (!profile.profileId.matches(ID)) issue("profileId", "Profile ID is invalid")
    if (!profile.projectUid.matches(UID)) issue("projectUid", "Project UID is invalid")
    if (profile.drivebaseUid?.matches(UID) == false) issue("drivebaseUid", "Drivebase UID is invalid")
    if (profile.displayName.isBlank() || profile.description.isBlank()) issue("displayName", "Display name and description are required")
    if (profile.baseProfileUid == profile.uid) issue("baseProfileUid", "A profile cannot inherit itself")
    if (profile.baseProfileUid?.matches(UID) == false) issue("baseProfileUid", "Base profile UID is invalid")
    duplicate(profile.values.map { it.parameterUid }).forEach { issue("values", "Parameter '$it' is assigned twice") }
    val byUid = declarations.associateBy { it.uid }
    profile.values.forEachIndexed { index, assignment ->
        val declaration = byUid[assignment.parameterUid]
        if (declaration == null) issue("values[$index].parameterUid", "Unknown parameter '${assignment.parameterUid}'")
        else validateValue(declaration, assignment.value)?.let { issue("values[$index].value", it) }
    }
    when (profile.authority) {
        TuningProfileAuthority.CANONICAL_CHECKED_IN -> profile.promotion?.let { validatePromotion(it, ::issue) }
        TuningProfileAuthority.LOCAL_EXPERIMENTAL -> if (profile.promotion != null) issue("promotion", "Local experimental profiles cannot claim promotion")
    }
}

/** Resolves exactly one explicit parent. Deeper inheritance, cycles, and missing parents fail. */
fun resolveTuningProfiles(
    profiles: Collection<TuningProfileDocument>,
    declarations: Collection<TuningParameterDeclaration>,
): Map<String, Map<String, TuningValue>> {
    val duplicateProfiles = duplicate(profiles.map { it.uid })
    require(duplicateProfiles.isEmpty()) { "Duplicate profile UIDs: ${duplicateProfiles.sorted().joinToString()}" }
    val byUid = profiles.associateBy { it.uid }
    profiles.forEach { profile ->
        val issues = validateTuningProfileDocument(profile, declarations)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
        profile.baseProfileUid?.let { parentUid ->
            val parent = requireNotNull(byUid[parentUid]) { "Profile '${profile.uid}' has missing parent '$parentUid'" }
            require(parent.baseProfileUid == null) { "Profile '${profile.uid}' exceeds one-level shallow composition" }
            require(parent.projectUid == profile.projectUid) { "Profile '${profile.uid}' and parent target different projects" }
        }
    }
    return profiles.sortedBy { it.uid }.associate { profile ->
        val inherited = profile.baseProfileUid?.let { byUid.getValue(it).values } ?: emptyList()
        val resolved = (inherited + profile.values).associate { it.parameterUid to it.value }.toSortedMap()
        profile.uid to resolved
    }
}

object TuningProfileDocumentCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    fun encode(profile: TuningProfileDocument, declarations: Collection<TuningParameterDeclaration>): String {
        requireValid(profile, declarations)
        return gson.toJson(profile.copy(values = profile.values.sortedBy { it.parameterUid }))
    }
    fun decode(json: String, declarations: Collection<TuningParameterDeclaration>): TuningProfileDocument {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrElse { throw IllegalArgumentException("Tuning profile is not valid JSON", it) }
        require(root.get("schemaVersion")?.asInt == ARES_TUNING_PROFILE_SCHEMA_VERSION) { "Unsupported tuning profile schema ${root.get("schemaVersion")}" }
        REQUIRED.forEach { require(root.has(it)) { "Tuning profile field '$it' is required" } }
        val profile = runCatching { gson.fromJson(root, TuningProfileDocument::class.java) }.getOrElse { throw IllegalArgumentException("Invalid tuning profile: ${it.message}", it) }
        requireValid(profile, declarations)
        return profile
    }
    fun contentHash(profile: TuningProfileDocument, declarations: Collection<TuningParameterDeclaration>): String =
        MessageDigest.getInstance("SHA-256").digest(encode(profile, declarations).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun requireValid(profile: TuningProfileDocument, declarations: Collection<TuningParameterDeclaration>) {
        val issues = validateTuningProfileDocument(profile, declarations)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
    }
}

object TuningComponentDocumentCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    fun encode(document: TuningComponentDocument): String {
        requireValid(document)
        return gson.toJson(document.copy(parameters = document.parameters.sortedBy { it.uid }))
    }
    fun decode(json: String): TuningComponentDocument {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrElse { throw IllegalArgumentException("Tuning component is not valid JSON", it) }
        require(root.get("schemaVersion")?.asInt == ARES_TUNING_COMPONENT_SCHEMA_VERSION) { "Unsupported tuning component schema ${root.get("schemaVersion")}" }
        setOf("schemaVersion", "uid", "projectUid", "displayName", "description", "parameters").forEach {
            require(root.has(it)) { "Tuning component field '$it' is required" }
        }
        return gson.fromJson(root, TuningComponentDocument::class.java).also(::requireValid)
    }
    private fun requireValid(document: TuningComponentDocument) {
        require(document.schemaVersion == ARES_TUNING_COMPONENT_SCHEMA_VERSION)
        require(document.uid.matches(UID) && document.projectUid.matches(UID)) { "Tuning component/project UID is invalid" }
        require(document.displayName.isNotBlank() && document.description.isNotBlank()) { "Tuning component name and description are required" }
        val issues = validateTuningParameterDeclarations(document.parameters)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
        require(document.parameters.all { it.componentUid == document.uid }) {
            "Project/global declarations must be owned by tuning component '${document.uid}'"
        }
    }
}

private fun validateValue(declaration: TuningParameterDeclaration, value: TuningValue): String? {
    val populated = listOf(value.doubleValue, value.intValue, value.booleanValue, value.textValue).count { it != null }
    if (populated != 1) return "Exactly one typed value is required"
    val numeric = when (declaration.type) {
        TuningParameterType.DOUBLE -> value.doubleValue ?: return "Expected a double value"
        TuningParameterType.INT -> value.intValue?.toDouble() ?: return "Expected an integer value"
        TuningParameterType.BOOLEAN -> return if (value.booleanValue != null) null else "Expected a boolean value"
        TuningParameterType.TEXT -> return if (value.textValue != null) null else "Expected a text value"
        TuningParameterType.ENUM -> return if (value.textValue in declaration.enumOptions) null else "Expected one declared enum option"
    }
    if (!numeric.isFinite()) return "Numeric values must be finite"
    if (declaration.minimum != null && numeric < declaration.minimum) return "Value is below the minimum"
    if (declaration.maximum != null && numeric > declaration.maximum) return "Value is above the maximum"
    return null
}

private fun validatePromotion(promotion: TuningPromotionData, issue: (String, String) -> Unit) {
    if (!promotion.sourceLocalProfileUid.matches(UID)) issue("promotion.sourceLocalProfileUid", "Source profile UID is invalid")
    if (!promotion.sourceContentSha256.matches(SHA)) issue("promotion.sourceContentSha256", "Source profile hash must be SHA-256")
    if (promotion.evidencePaths.isEmpty() || promotion.evidencePaths.size != promotion.evidenceSha256.size) issue("promotion.evidencePaths", "Promotion requires matching evidence paths and hashes")
    promotion.evidencePaths.forEachIndexed { index, path -> if (!path.safeRelative()) issue("promotion.evidencePaths[$index]", "Evidence path must be project-relative") }
    promotion.evidenceSha256.forEachIndexed { index, hash -> if (!hash.matches(SHA)) issue("promotion.evidenceSha256[$index]", "Evidence hash must be SHA-256") }
    if (promotion.reviewedBy.isBlank() || promotion.reviewSummary.isBlank()) issue("promotion", "Reviewer and review summary are required")
}

private val REQUIRED = setOf("schemaVersion", "uid", "profileId", "displayName", "description", "projectUid", "authority", "values")
private val UID = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
private val ID = Regex("[a-z][a-z0-9-]{0,63}")
private val KEY = Regex("[a-z][A-Za-z0-9]*(?:[.][a-z][A-Za-z0-9]*)+")
private val SHA = Regex("[a-f0-9]{64}")
private fun duplicate(values: List<String>) = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
private fun String.safeRelative() = isNotBlank() && !startsWith('/') && '\\' !in this && split('/').none { it.isBlank() || it == "." || it == ".." }
