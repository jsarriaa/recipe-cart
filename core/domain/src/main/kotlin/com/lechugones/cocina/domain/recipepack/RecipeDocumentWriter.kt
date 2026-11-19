package com.lechugones.cocina.domain.recipepack

/**
 * Writes the canonical form of a `cpl-recipe/1` document (RECIPE_FORMAT § 11). The output is
 * deterministic, so `write(parse(write(d)))` equals `write(d)` byte for byte.
 */
object RecipeDocumentWriter {
    fun write(draft: RecipeDraft): String =
        buildString {
            line("---")
            line("format: ${RecipeRules.FORMAT}")
            draft.uid?.let { line("uid: ${YamlScalars.scalar(it)}") }
            line("title: ${YamlScalars.scalar(draft.title)}")
            draft.description?.let { line("description: ${YamlScalars.scalar(it)}") }
            line("servings: ${draft.servings}")
            draft.prepMinutes?.let { line("prep_minutes: $it") }
            draft.cookMinutes?.let { line("cook_minutes: $it") }
            if (draft.tags.isNotEmpty()) {
                line("tags: [" + draft.tags.joinToString(", ") { YamlScalars.scalar(it, flow = true) } + "]")
            }
            draft.image?.let { line("image: ${YamlScalars.scalar(it)}") }
            draft.source?.let { line("source: ${YamlScalars.scalar(it)}") }
            draft.author?.let { line("author: ${YamlScalars.scalar(it)}") }
            line("ingredients:")
            draft.ingredients.forEach { ingredient(it) }
            line("---")
            if (draft.body.isNotEmpty()) {
                line("")
                line(draft.body)
            }
        }

    private fun StringBuilder.ingredient(ingredient: IngredientDraft) {
        line("  - name: ${YamlScalars.scalar(ingredient.name)}")
        ingredient.quantity?.let { line("    quantity: $it") }
        line("    unit: ${ingredient.unit.code}")
        ingredient.unitWeightG?.let { line("    unit_weight_g: $it") }
        ingredient.note?.let { line("    note: ${YamlScalars.scalar(it)}") }
        ingredient.group?.let { line("    group: ${YamlScalars.scalar(it)}") }
        if (ingredient.optional) line("    optional: true")
        if (ingredient.pantry) line("    pantry: true")
        ingredient.match?.let { match ->
            line("    match:")
            line("      query: ${YamlScalars.scalar(match.query)}")
            match.packageSize?.let { line("      package_size: ${YamlScalars.scalar(it.canonical())}") }
            line("      store_brand_ok: ${match.storeBrandOk}")
        }
        if (ingredient.links.isNotEmpty()) {
            line("    links:")
            ingredient.links.forEach { link ->
                line("      - provider: ${YamlScalars.scalar(link.provider)}")
                line("        product_id: ${YamlScalars.quoted(link.productId)}")
                link.name?.let { line("        name: ${YamlScalars.scalar(it)}") }
                if (link.preferred) line("        preferred: true")
            }
        }
    }

    private fun StringBuilder.line(text: String) {
        append(text).append('\n')
    }
}

/** Plain-or-quoted YAML scalars, following the safety rules of RECIPE_FORMAT § 3. */
object YamlScalars {
    private const val INDICATORS = "-?:,[]{}#&*!|>'\"%@`"
    private const val FLOW_INDICATORS = ",[]{}"

    /** DEL, NEL, line/paragraph separators and BOM: never written raw (code points, not literals). */
    private val UNSAFE_CHARS = setOf(Char(0x7F), Char(0x85), Char(0x2028), Char(0x2029), Char(0xFEFF))
    private val RESERVED = setOf("null", "~", "true", "false", "yes", "no", "on", "off", "y", "n")
    private val NUMBER_LIKE =
        listOf(
            Regex("""^[-+]?(\.\d[\d_]*|\d[\d_]*(\.[\d_]*)?)([eE][-+]?\d+)?$"""),
            Regex("""^0x[0-9a-fA-F_]+$"""),
            Regex("""^0o[0-7_]+$"""),
            Regex("""^[-+]?\.(inf|Inf|INF)$"""),
            Regex("""^\.(nan|NaN|NAN)$"""),
        )

    fun scalar(
        value: String,
        flow: Boolean = false,
    ): String = if (isPlainSafe(value, flow)) value else quoted(value)

    fun isPlainSafe(
        value: String,
        flow: Boolean = false,
    ): Boolean =
        value.isNotEmpty() &&
            value == value.trim() &&
            value.none { it < ' ' || it in UNSAFE_CHARS } &&
            value[0] !in INDICATORS &&
            ": " !in value &&
            " #" !in value &&
            !value.endsWith(":") &&
            !(flow && value.any { it in FLOW_INDICATORS }) &&
            value.lowercase() !in RESERVED &&
            NUMBER_LIKE.none { it.matches(value) }

    fun quoted(value: String): String =
        buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '\\' -> {
                        append("\\\\")
                    }

                    '"' -> {
                        append("\\\"")
                    }

                    '\n' -> {
                        append("\\n")
                    }

                    '\t' -> {
                        append("\\t")
                    }

                    '\r' -> {
                        append("\\r")
                    }

                    else -> {
                        if (c < ' ' || c in UNSAFE_CHARS) {
                            append("\\u").append(c.code.toString(16).padStart(4, '0'))
                        } else {
                            append(c)
                        }
                    }
                }
            }
            append('"')
        }
}
