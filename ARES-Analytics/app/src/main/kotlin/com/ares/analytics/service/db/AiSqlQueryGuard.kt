package com.ares.analytics.service.db

/**
 * Fail-closed validator for SQL produced by the optional AI analyst.
 *
 * DuckDB's read-only transaction mode prevents writes, but it intentionally does not prevent
 * table functions from reading local files or remote URLs. AI SQL therefore uses a much smaller
 * language than the diagnostics console: one SELECT over the three documented summary tables,
 * direct joins only, and a deliberately small scalar/aggregate function allowlist.
 */
internal object AiSqlQueryGuard {
    private const val MAX_SQL_CHARACTERS = 8_192
    private const val MAX_TOKENS = 1_024
    private const val MAX_TABLE_REFERENCES = 2

    private val allowedTables = setOf("sessions", "session_summaries", "alerts")
    private val allowedFunctions = setOf(
        "abs", "avg", "cast", "ceil", "coalesce", "count", "date_trunc", "decimal",
        "epoch_ms", "filter", "floor", "in", "json_extract", "json_extract_string", "length",
        "lower", "max", "min", "nullif", "over", "round", "strftime", "sum", "upper",
    )
    private val forbiddenKeywords = setOf(
        "alter", "attach", "call", "copy", "create", "cross", "delete", "describe",
        "detach", "drop", "execute", "explain", "export", "import", "insert", "install",
        "intersect", "load", "pragma", "set", "show", "table", "tablesample", "truncate",
        "union", "update", "use", "vacuum", "values", "with",
    )
    private val fromClauseBoundaries = setOf(
        "where", "group", "having", "qualify", "window", "order", "limit", "offset", "fetch",
    )

    fun validate(sql: String): String {
        val query = sql.trim().trimEnd().also {
            require(it.isNotEmpty()) { "AI query is empty" }
            require(it.length <= MAX_SQL_CHARACTERS) {
                "AI query exceeds the $MAX_SQL_CHARACTERS-character limit"
            }
        }
        val tokens = tokenize(query)
        require(tokens.isNotEmpty() && tokens.first().identifier == "select") {
            "AI query must be one SELECT statement"
        }
        require(tokens.count { it.identifier == "select" } == 1) {
            "AI query cannot contain subqueries or multiple SELECT statements"
        }
        tokens.firstOrNull { it.identifier in forbiddenKeywords }?.let { token ->
            throw IllegalArgumentException("AI query keyword '${token.text}' is not allowed")
        }

        for (index in 0 until tokens.lastIndex) {
            val token = tokens[index]
            if (
                (token.kind == TokenKind.IDENTIFIER || token.kind == TokenKind.QUOTED_IDENTIFIER) &&
                tokens[index + 1].text == "("
            ) {
                require(token.identifier in allowedFunctions) {
                    "AI query function '${token.text}' is not allowed"
                }
            }
        }

        var tableReferences = 0
        val referencedTables = HashSet<String>(MAX_TABLE_REFERENCES)
        for (index in tokens.indices) {
            val keyword = tokens[index].identifier
            if (keyword != "from" && keyword != "join") continue
            val targetIndex = index + 1
            require(targetIndex < tokens.size) { "AI query has an incomplete $keyword clause" }
            val firstTarget = tokens[targetIndex]
            require(firstTarget.kind == TokenKind.IDENTIFIER || firstTarget.kind == TokenKind.QUOTED_IDENTIFIER) {
                "AI query $keyword target must be a documented database table"
            }
            var tableName = firstTarget.identifier
            if (targetIndex + 2 < tokens.size && tokens[targetIndex + 1].text == ".") {
                require(tableName == "main") { "AI query can only use the main schema" }
                val qualifiedTarget = tokens[targetIndex + 2]
                require(
                    qualifiedTarget.kind == TokenKind.IDENTIFIER ||
                        qualifiedTarget.kind == TokenKind.QUOTED_IDENTIFIER
                ) { "AI query has an invalid qualified table name" }
                tableName = qualifiedTarget.identifier
            }
            require(tableName in allowedTables) {
                "AI query table '$tableName' is not available to the AI analyst"
            }
            require(referencedTables.add(tableName)) {
                "AI query cannot scan the same table more than once"
            }
            tableReferences++
        }
        require(tableReferences in 1..MAX_TABLE_REFERENCES) {
            "AI query must reference one to $MAX_TABLE_REFERENCES documented tables"
        }
        rejectCommaJoins(tokens)
        return query
    }

    private fun rejectCommaJoins(tokens: List<Token>) {
        val fromIndex = tokens.indexOfFirst { it.identifier == "from" }
        if (fromIndex < 0) return
        var depth = 0
        for (index in (fromIndex + 1) until tokens.size) {
            val token = tokens[index]
            if (depth == 0 && token.identifier in fromClauseBoundaries) return
            when (token.text) {
                "(" -> depth++
                ")" -> {
                    depth--
                    require(depth >= 0) { "AI query has unbalanced parentheses" }
                }
                "," -> require(depth > 0) { "AI query cannot use comma/cross joins" }
            }
        }
        require(depth == 0) { "AI query has unbalanced parentheses" }
    }

    private fun tokenize(sql: String): List<Token> {
        val tokens = ArrayList<Token>(sql.length.coerceAtMost(128))
        var index = 0
        fun add(text: String, kind: TokenKind) {
            require(tokens.size < MAX_TOKENS) { "AI query exceeds the $MAX_TOKENS-token limit" }
            tokens += Token(text, kind)
        }
        while (index < sql.length) {
            val char = sql[index]
            when {
                char.isWhitespace() -> index++
                char == ';' -> throw IllegalArgumentException("AI query must contain exactly one statement")
                char == '-' && sql.getOrNull(index + 1) == '-' ->
                    throw IllegalArgumentException("AI query comments are not allowed")
                char == '/' && sql.getOrNull(index + 1) == '*' ->
                    throw IllegalArgumentException("AI query comments are not allowed")
                char == '\'' -> {
                    val start = index++
                    var closed = false
                    while (index < sql.length) {
                        if (sql[index] == '\'') {
                            if (sql.getOrNull(index + 1) == '\'') {
                                index += 2
                            } else {
                                index++
                                closed = true
                                break
                            }
                        } else {
                            index++
                        }
                    }
                    require(closed) { "AI query has an unterminated string literal" }
                    add(sql.substring(start, index), TokenKind.STRING)
                }
                char == '"' -> {
                    val start = ++index
                    val value = StringBuilder()
                    var closed = false
                    while (index < sql.length) {
                        if (sql[index] == '"') {
                            if (sql.getOrNull(index + 1) == '"') {
                                value.append('"')
                                index += 2
                            } else {
                                index++
                                closed = true
                                break
                            }
                        } else {
                            value.append(sql[index++])
                        }
                    }
                    require(closed && index > start) { "AI query has an invalid quoted identifier" }
                    add(value.toString(), TokenKind.QUOTED_IDENTIFIER)
                }
                char.isLetter() || char == '_' -> {
                    val start = index++
                    while (index < sql.length && (sql[index].isLetterOrDigit() || sql[index] == '_')) index++
                    add(sql.substring(start, index), TokenKind.IDENTIFIER)
                }
                char.isDigit() -> {
                    val start = index++
                    while (index < sql.length && (sql[index].isDigit() || sql[index] in ".eE+-")) index++
                    add(sql.substring(start, index), TokenKind.NUMBER)
                }
                char in "(),.*+-/%=<>!|:" -> {
                    add(char.toString(), TokenKind.SYMBOL)
                    index++
                }
                else -> throw IllegalArgumentException("AI query contains unsupported character '$char'")
            }
        }
        return tokens
    }

    private data class Token(val text: String, val kind: TokenKind) {
        val identifier: String = if (kind == TokenKind.IDENTIFIER || kind == TokenKind.QUOTED_IDENTIFIER) {
            text.lowercase()
        } else {
            ""
        }
    }

    private enum class TokenKind { IDENTIFIER, QUOTED_IDENTIFIER, STRING, NUMBER, SYMBOL }
}
