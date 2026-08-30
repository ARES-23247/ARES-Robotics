package com.ares.analytics.ui.help

import com.ares.analytics.ui.components.NavigationTarget
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal const val ACADEMY_CATALOG_SCHEMA_VERSION: Int = 1

/** The single current Academy curriculum document bundled with Studio. */
internal data class AcademyCatalogDocument(
    val schemaVersion: Int,
    val labGuides: List<LearningLabGuide>,
    val lessons: List<LearningLesson>,
    val paths: List<LearningPath>,
    val contextualLessonIds: Map<String, String>,
)

internal object AcademyCatalogCodec {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val stableId = Regex("[a-z][a-z0-9-]{1,63}")
    private val rootFields = setOf(
        "schemaVersion",
        "labGuides",
        "lessons",
        "paths",
        "contextualLessonIds",
    )

    fun encode(document: AcademyCatalogDocument): String {
        requireValid(document)
        return gson.toJson(document) + "\n"
    }

    fun decode(payload: String): AcademyCatalogDocument {
        val root = JsonParser.parseString(payload).asJsonObject
        require(root.keySet() == rootFields) {
            "Academy catalog root fields must be exactly ${rootFields.sorted()}"
        }
        requireDocumentShape(root)
        val schemaVersion = root.get("schemaVersion")
        require(schemaVersion.isJsonPrimitive && schemaVersion.asJsonPrimitive.isNumber) {
            "Academy catalog schemaVersion must be an integer"
        }
        require(schemaVersion.asInt == ACADEMY_CATALOG_SCHEMA_VERSION) {
            "Unsupported Academy catalog schema ${schemaVersion.asInt}; expected $ACADEMY_CATALOG_SCHEMA_VERSION"
        }
        return gson.fromJson(root, AcademyCatalogDocument::class.java).also(::requireValid)
    }

    private fun requireDocumentShape(root: JsonObject) {
        root.getAsJsonArray("labGuides").forEachIndexed { index, element ->
            requireFields(
                element.asJsonObject,
                required = setOf(
                    "lab",
                    "title",
                    "outcome",
                    "beforeYouStart",
                    "tryThis",
                    "reflectionQuestions",
                    "successLooksLike",
                ),
                path = "labGuides[$index]",
            )
        }
        root.getAsJsonArray("paths").forEachIndexed { index, element ->
            requireFields(
                element.asJsonObject,
                required = setOf("id", "title", "summary", "level", "lessonIds"),
                path = "paths[$index]",
            )
        }
        root.getAsJsonArray("lessons").forEachIndexed { lessonIndex, element ->
            val lesson = element.asJsonObject
            requireFields(
                lesson,
                required = setOf(
                    "id",
                    "level",
                    "track",
                    "title",
                    "outcome",
                    "durationMinutes",
                    "destination",
                    "action",
                    "requiresRobot",
                    "beforeYouStart",
                    "steps",
                    "successLooksLike",
                    "keywords",
                    "prerequisiteLessonIds",
                    "checkpoints",
                ),
                optional = setOf("safetyNote", "lab"),
                path = "lessons[$lessonIndex]",
            )
            lesson.getAsJsonArray("checkpoints").forEachIndexed { checkpointIndex, checkpoint ->
                requireFields(
                    checkpoint.asJsonObject,
                    required = setOf("id", "title", "instruction", "successText", "evidence", "action"),
                    path = "lessons[$lessonIndex].checkpoints[$checkpointIndex]",
                )
            }
        }
        require(root.get("contextualLessonIds").isJsonObject) {
            "Academy catalog contextualLessonIds must be an object"
        }
    }

    private fun requireFields(
        value: JsonObject,
        required: Set<String>,
        optional: Set<String> = emptySet(),
        path: String,
    ) {
        val actual = value.keySet()
        require(actual.containsAll(required) && actual.all { it in required || it in optional }) {
            "Academy catalog $path fields must contain ${required.sorted()} and only allow ${optional.sorted()} as optional fields"
        }
    }

    fun loadBundled(): AcademyCatalogDocument {
        val payload = requireNotNull(
            AcademyCatalogCodec::class.java.getResourceAsStream("/academy/catalog.json")
        ) { "Bundled Academy catalog /academy/catalog.json is missing" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return decode(payload)
    }

    fun requireValid(document: AcademyCatalogDocument) {
        require(document.schemaVersion == ACADEMY_CATALOG_SCHEMA_VERSION) {
            "Unsupported Academy catalog schema ${document.schemaVersion}"
        }
        require(document.labGuides.isNotEmpty()) { "Academy catalog must contain lab guides" }
        require(document.lessons.isNotEmpty()) { "Academy catalog must contain lessons" }
        require(document.paths.isNotEmpty()) { "Academy catalog must contain paths" }

        val lessonIds = document.lessons.map(LearningLesson::id)
        require(lessonIds.all(stableId::matches)) { "Every Academy lesson ID must be stable kebab-case" }
        require(lessonIds.size == lessonIds.toSet().size) { "Academy lesson IDs must be unique" }
        val lessonIdSet = lessonIds.toSet()

        val pathIds = document.paths.map(LearningPath::id)
        require(pathIds.all(stableId::matches)) { "Every Academy path ID must be stable kebab-case" }
        require(pathIds.size == pathIds.toSet().size) { "Academy path IDs must be unique" }
        document.paths.forEach { path ->
            requireNotNull(path.level) { "Academy path ${path.id} is missing level" }
            require(path.title.isNotBlank() && path.summary.isNotBlank()) { "Academy path ${path.id} is incomplete" }
            require(path.lessonIds.isNotEmpty() && path.lessonIds.all(lessonIdSet::contains)) {
                "Academy path ${path.id} references an unknown lesson"
            }
        }

        val checkpointIds = mutableSetOf<String>()
        document.lessons.forEach { lesson ->
            requireNotNull(lesson.level) { "Academy lesson ${lesson.id} is missing level" }
            requireNotNull(lesson.track) { "Academy lesson ${lesson.id} is missing track" }
            requireNotNull(lesson.destination) { "Academy lesson ${lesson.id} is missing destination" }
            requireNotNull(lesson.action) { "Academy lesson ${lesson.id} is missing action" }
            require(lesson.title.isNotBlank() && lesson.outcome.isNotBlank()) { "Academy lesson ${lesson.id} is incomplete" }
            require(lesson.durationMinutes > 0) { "Academy lesson ${lesson.id} must have a positive duration" }
            require(lesson.beforeYouStart.isNotEmpty() && lesson.steps.isNotEmpty()) {
                "Academy lesson ${lesson.id} must include preparation and steps"
            }
            require(lesson.successLooksLike.isNotBlank()) { "Academy lesson ${lesson.id} is missing success guidance" }
            require(lesson.prerequisiteLessonIds.all(lessonIdSet::contains)) {
                "Academy lesson ${lesson.id} references an unknown prerequisite"
            }
            require(lesson.checkpoints.isNotEmpty()) { "Academy lesson ${lesson.id} must include checkpoints" }
            lesson.checkpoints.forEach { checkpoint ->
                require(
                    checkpoint.id.isNotBlank() &&
                        checkpoint.id.length <= 128 &&
                        checkpoint.id.none(Char::isWhitespace)
                ) {
                    "Academy checkpoint ${checkpoint.id} has an invalid ID"
                }
                require(checkpointIds.add(checkpoint.id)) { "Academy checkpoint ${checkpoint.id} is duplicated" }
                require(checkpoint.title.isNotBlank() && checkpoint.instruction.isNotBlank() && checkpoint.successText.isNotBlank()) {
                    "Academy checkpoint ${checkpoint.id} is incomplete"
                }
                requireNotNull(checkpoint.evidence) { "Academy checkpoint ${checkpoint.id} is missing evidence" }
                requireNotNull(checkpoint.action) { "Academy checkpoint ${checkpoint.id} is missing action" }
            }
        }

        val guideLabs = document.labGuides.map { guide ->
            requireNotNull(guide.lab) { "Academy lab guide is missing its lab ID" }
            require(guide.title.isNotBlank() && guide.outcome.isNotBlank()) { "Academy lab ${guide.lab} is incomplete" }
            require(guide.beforeYouStart.isNotEmpty() && guide.tryThis.isNotEmpty() && guide.reflectionQuestions.isNotEmpty()) {
                "Academy lab ${guide.lab} must include preparation, exercises, and reflection"
            }
            require(guide.successLooksLike.isNotBlank()) { "Academy lab ${guide.lab} is missing success guidance" }
            guide.lab
        }
        require(guideLabs.size == guideLabs.toSet().size) { "Academy lab guides must be unique" }
        require(guideLabs.toSet() == LearningLab.entries.toSet()) { "Every Academy lab requires exactly one guide" }
        require(LearningLab.entries.all { lab ->
            document.lessons.any { lesson -> lesson.lab == lab && lesson.action == LearningAction.OPEN_LAB }
        }) { "Every Academy lab requires an OPEN_LAB lesson" }

        document.contextualLessonIds.forEach { (targetName, lessonId) ->
            require(runCatching { NavigationTarget.valueOf(targetName) }.isSuccess) {
                "Academy context target $targetName is unknown"
            }
            require(lessonId in lessonIdSet) { "Academy context target $targetName references unknown lesson $lessonId" }
        }
    }
}
