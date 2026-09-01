package com.areslib.catalog

/** One invalid serialized capability argument reported at the project-document boundary. */
data class CapabilityArgumentIssue(
    val key: String,
    val code: String,
    val message: String,
)

/**
 * Returns the value an editor should initially show for this parameter.
 *
 * Explicit descriptor defaults take precedence. Enum editors select their first declared option
 * when no explicit default exists so autonomous and controller editors begin from the same valid
 * choice. Other required values remain absent until the author supplies them.
 */
fun CapabilityParameterDescriptor.initialArgumentValue(): String? = when (type) {
    CapabilityParameterType.NUMBER -> defaultNumber?.toString()
    CapabilityParameterType.BOOLEAN -> defaultBoolean?.toString()
    CapabilityParameterType.TEXT -> defaultText
    CapabilityParameterType.ENUM -> defaultText ?: options.firstOrNull()
}

/** Builds deterministic initial serialized arguments for an action or condition editor. */
fun initialCapabilityArguments(
    parameters: List<CapabilityParameterDescriptor>,
): Map<String, String> = parameters.mapNotNull { parameter ->
    parameter.initialArgumentValue()?.let { parameter.key to it }
}.toMap()

/**
 * Validates portable string arguments against their shared capability descriptors.
 *
 * This contract is used by autonomous and controller authoring so both surfaces reject unknown,
 * missing, malformed, non-finite, out-of-range, and unsupported enum values consistently before
 * generated robot code decodes them.
 */
fun validateCapabilityArguments(
    parameters: List<CapabilityParameterDescriptor>,
    arguments: Map<String, String>,
): List<CapabilityArgumentIssue> = buildList {
    val declared = parameters.associateBy(CapabilityParameterDescriptor::key)
    arguments.keys.filterNot(declared::containsKey).sorted().forEach { key ->
        add(CapabilityArgumentIssue(key, "unknown_argument", "'$key' is not a declared parameter"))
    }
    parameters.forEach { parameter ->
        val raw = arguments[parameter.key]
        if (raw == null) {
            if (parameter.required && !parameter.hasExplicitDefault()) {
                add(
                    CapabilityArgumentIssue(
                        parameter.key,
                        "missing_argument",
                        "${parameter.displayName} is required",
                    ),
                )
            }
            return@forEach
        }
        if (raw.isBlank()) {
            if (parameter.required) {
                add(
                    CapabilityArgumentIssue(
                        parameter.key,
                        "missing_argument",
                        "${parameter.displayName} is required",
                    ),
                )
            }
            return@forEach
        }

        val issue = when (parameter.type) {
            CapabilityParameterType.NUMBER -> validateNumber(parameter, raw)
            CapabilityParameterType.BOOLEAN -> if (
                raw.equals("true", ignoreCase = true) || raw.equals("false", ignoreCase = true)
            ) {
                null
            } else {
                CapabilityArgumentIssue(
                    parameter.key,
                    "invalid_boolean",
                    "${parameter.displayName} must be true or false",
                )
            }
            CapabilityParameterType.TEXT -> null
            CapabilityParameterType.ENUM -> if (raw in parameter.options) {
                null
            } else {
                CapabilityArgumentIssue(
                    parameter.key,
                    "invalid_enum",
                    "${parameter.displayName} must be one of ${parameter.options.joinToString()}",
                )
            }
        }
        issue?.let(::add)
    }
}

private fun CapabilityParameterDescriptor.hasExplicitDefault(): Boolean = when (type) {
    CapabilityParameterType.NUMBER -> defaultNumber != null
    CapabilityParameterType.BOOLEAN -> defaultBoolean != null
    CapabilityParameterType.TEXT,
    CapabilityParameterType.ENUM,
    -> defaultText != null
}

private fun validateNumber(
    parameter: CapabilityParameterDescriptor,
    raw: String,
): CapabilityArgumentIssue? {
    val number = raw.toDoubleOrNull()
    return when {
        number == null || !number.isFinite() -> CapabilityArgumentIssue(
            parameter.key,
            "invalid_number",
            "${parameter.displayName} must be a finite number",
        )
        parameter.minimum != null && number < parameter.minimum -> CapabilityArgumentIssue(
            parameter.key,
            "below_minimum",
            "${parameter.displayName} must be at least ${parameter.minimum}",
        )
        parameter.maximum != null && number > parameter.maximum -> CapabilityArgumentIssue(
            parameter.key,
            "above_maximum",
            "${parameter.displayName} must be at most ${parameter.maximum}",
        )
        else -> null
    }
}
