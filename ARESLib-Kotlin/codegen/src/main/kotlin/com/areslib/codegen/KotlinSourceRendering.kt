package com.areslib.codegen

/**
 * Canonical Kotlin source literals shared by the mechanical code generators.
 *
 * Keeping these rules in one place prevents otherwise-identical generators from disagreeing about
 * string-template escaping, negative zero, control characters, or generated type-name casing.
 */
internal fun String.kotlinPascalCase(): String = split(Regex("[^A-Za-z0-9]+"))
    .filter(String::isNotEmpty)
    .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

internal fun String.kotlinStringLiteral(): String = buildString(length + 2) {
    append('"')
    this@kotlinStringLiteral.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            else -> if (
                character.code < 0x20 ||
                character.code in 0xD800..0xDFFF ||
                character == '\u2028' ||
                character == '\u2029'
            ) {
                append("\\u${character.code.toString(16).padStart(4, '0')}")
            } else {
                append(character)
            }
        }
    }
    append('"')
}

internal fun Double.kotlinDoubleLiteral(): String {
    require(isFinite()) { "Cannot render a non-finite Kotlin number" }
    if (this == -0.0) return "0.0"
    val rendered = toString()
    return if (rendered.contains('.') || rendered.contains('e', ignoreCase = true)) rendered else "$rendered.0"
}

// Transitional domain names used throughout the subsystem renderer. They deliberately delegate to
// the canonical implementation so the large renderer can be split without a noisy all-at-once
// textual rewrite.
internal fun String.pascalCase(): String = kotlinPascalCase()
internal fun String.quoted(): String = kotlinStringLiteral()
internal fun Double.kotlinDouble(): String = kotlinDoubleLiteral()
