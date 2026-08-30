package com.areslib.subsystem

internal val SUBSYSTEM_STABLE_ID = Regex("[a-z][a-z0-9-]{0,63}")
internal val SUBSYSTEM_PASCAL_CASE = Regex("[A-Z][A-Za-z0-9]{0,63}")
internal val SUBSYSTEM_SHA_256 = Regex("[a-f0-9]{64}")
internal val SUBSYSTEM_GRADLE_MODULE_PATH = Regex(":[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*")
internal val SUBSYSTEM_QUALIFIED_KOTLIN_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
internal val SUBSYSTEM_CAPABILITY_KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")

private val KOTLIN_IDENTIFIER = Regex("[a-z][A-Za-z0-9_]{0,63}")
private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
    "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor",
    "delegate", "dynamic", "field", "file", "finally", "get", "import", "init", "param", "property",
    "receiver", "set", "setparam", "where", "actual", "abstract", "annotation", "companion", "const",
    "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline", "inner", "internal",
    "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected", "public",
    "reified", "sealed", "suspend", "tailrec", "vararg",
)

internal fun String.isUsableSubsystemKotlinIdentifier(): Boolean =
    matches(KOTLIN_IDENTIFIER) && this !in KOTLIN_KEYWORDS

internal fun String.isSafeSubsystemProjectRelativePath(): Boolean =
    isNotBlank() && '/' in this && !startsWith('/') && '\\' !in this &&
        split('/').none { it.isBlank() || it == "." || it == ".." }

internal fun String.isSafeSubsystemProjectRelativeKotlinPath(): Boolean =
    isSafeSubsystemProjectRelativePath() && endsWith(".kt")
