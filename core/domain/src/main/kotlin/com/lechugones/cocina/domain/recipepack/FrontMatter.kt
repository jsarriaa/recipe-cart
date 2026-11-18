package com.lechugones.cocina.domain.recipepack

/** Splits a recipe document into YAML front matter and Markdown body (RECIPE_FORMAT § 3). */
object FrontMatter {
    /** The front matter starts on line 2 of the file. */
    const val YAML_FIRST_LINE = 2

    data class Parts(
        val yaml: String,
        val body: String,
    )

    /** Removes a BOM and normalises CRLF and CR line endings to LF. */
    fun normalize(text: String): String = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')

    /** Returns null when the first line is not `---` or the front matter is never closed (`E01`). */
    fun split(text: String): Parts? {
        val lines = normalize(text).split('\n')
        if (lines.first().trimEnd() != DELIMITER) return null
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == DELIMITER } ?: return null
        return Parts(
            yaml = lines.subList(1, end).joinToString("\n"),
            body = trimBlankLines(lines.subList(end + 1, lines.size)),
        )
    }

    /** Drops leading and trailing blank lines, keeping everything in between unchanged. */
    fun trimBlankLines(lines: List<String>): String {
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return ""
        val last = lines.indexOfLast { it.isNotBlank() }
        return lines.subList(first, last + 1).joinToString("\n")
    }

    private const val DELIMITER = "---"
}
