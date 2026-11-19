package com.lechugones.cocina.domain.recipepack

import com.charleskorn.kaml.DuplicateKeyException
import com.charleskorn.kaml.EmptyYamlDocumentException
import com.charleskorn.kaml.ForbiddenAnchorOrAliasException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.PackageSize
import com.lechugones.cocina.domain.quantity.QuantityParseResult
import com.lechugones.cocina.domain.quantity.QuantityParser
import com.lechugones.cocina.domain.recipe.MatchHints
import com.lechugones.cocina.domain.units.MeasureUnit
import java.util.Locale

/** Result of parsing one recipe document. [draft] is null when any error was found. */
data class ParsedDocument(
    val draft: RecipeDraft?,
    val issues: List<PackIssue>,
) {
    val hasErrors: Boolean get() = issues.any { it.isError }
}

/**
 * Parses and validates one `cpl-recipe/1` document (RECIPE_FORMAT § 3–9). Never throws for bad
 * input: every problem becomes a [PackIssue].
 *
 * [availableImages] are the `images/…` paths present in the pack; a single file has none.
 */
object RecipeDocumentParser {
    // kaml forbids anchors and aliases by default, which is what the format requires (E03).
    private val yaml = Yaml.default

    private val TOP_LEVEL_KEYS =
        setOf(
            "format",
            "uid",
            "title",
            "description",
            "servings",
            "prep_minutes",
            "cook_minutes",
            "tags",
            "image",
            "source",
            "author",
            "ingredients",
        )
    private val INGREDIENT_KEYS =
        setOf("name", "quantity", "unit", "unit_weight_g", "note", "group", "optional", "pantry", "match", "links")
    private val MATCH_KEYS = setOf("query", "package_size", "store_brand_ok")
    private val LINK_KEYS = setOf("provider", "product_id", "name", "preferred")
    private val INTEGER = Regex("""^-?\d+$""")
    private val WHITESPACE = Regex("""\s+""")
    private val PLACEHOLDER = IngredientDraft(name = "", quantity = null, unit = MeasureUnit.UD)

    fun parse(
        text: String,
        availableImages: Set<String> = emptySet(),
    ): ParsedDocument {
        if (text.toByteArray(Charsets.UTF_8).size > RecipeRules.DOCUMENT_MAX_BYTES) {
            return failure(PackIssue(IssueCode.E12))
        }
        val parts = FrontMatter.split(text) ?: return failure(PackIssue(IssueCode.E01))

        val root =
            try {
                yaml.parseToYamlNode(parts.yaml)
            } catch (e: EmptyYamlDocumentException) {
                return failure(PackIssue(IssueCode.E04, "format"))
            } catch (e: ForbiddenAnchorOrAliasException) {
                return failure(syntaxIssue(IssueCode.E03, e))
            } catch (e: DuplicateKeyException) {
                return failure(syntaxIssue(IssueCode.E02, e))
            } catch (e: YamlException) {
                return failure(syntaxIssue(IssueCode.E02, e))
            }

        if (root is YamlNull) return failure(PackIssue(IssueCode.E04, "format"))
        val walker = Walker()
        walker.checkForbiddenFeatures(root, "")
        if (walker.hasErrors) return failure(walker.issues)
        val fields = walker.map(root, "") ?: return failure(walker.issues)
        return walker.document(fields, parts.body, availableImages)
    }

    private fun failure(issue: PackIssue) = ParsedDocument(null, listOf(issue))

    private fun failure(issues: List<PackIssue>) = ParsedDocument(null, issues)

    private fun syntaxIssue(
        code: IssueCode,
        e: YamlException,
    ) = PackIssue(
        code = code,
        // kaml lines are relative to the front matter; report file lines.
        line = e.line + FrontMatter.YAML_FIRST_LINE - 1,
        column = e.column,
    )

    private class Walker {
        val issues = mutableListOf<PackIssue>()

        /** Paths that already have an error, so the validator does not report them twice. */
        val errorPaths = mutableSetOf<String>()

        /** Ingredients with parse errors: the validator's findings about them are dropped. */
        val failedIngredients = mutableSetOf<Int>()
        val hasErrors: Boolean get() = issues.any { it.isError }

        fun add(
            code: IssueCode,
            path: String,
            value: String? = null,
        ) {
            issues += PackIssue(code, path, value)
            if (code.isError) errorPaths += path
        }

        fun checkForbiddenFeatures(
            node: YamlNode,
            path: String,
        ) {
            when (node) {
                is YamlTaggedNode -> {
                    add(IssueCode.E03, path, node.tag)
                }

                is YamlMap -> {
                    node.entries.forEach { (key, value) ->
                        val childPath = join(path, key.content)
                        if (key.content == "<<") add(IssueCode.E03, childPath) else checkForbiddenFeatures(value, childPath)
                    }
                }

                is YamlList -> {
                    node.items.forEachIndexed { i, item -> checkForbiddenFeatures(item, "$path[$i]") }
                }

                else -> {
                    Unit
                }
            }
        }

        /** A mapping as an ordered field map, or null (E06) when [node] is something else. */
        fun map(
            node: YamlNode,
            path: String,
        ): Map<String, YamlNode>? {
            if (node !is YamlMap) {
                add(IssueCode.E06, path)
                return null
            }
            return node.entries.entries.associate { (key, value) -> key.content to value }
        }

        fun unknownKeys(
            fields: Map<String, YamlNode>,
            allowed: Set<String>,
            path: String,
        ) {
            fields.keys.filterNot { it in allowed }.forEach { add(IssueCode.W01, join(path, it)) }
        }

        /** Trimmed scalar text, null when absent or null; E06 for lists and maps. */
        fun string(
            fields: Map<String, YamlNode>,
            key: String,
            path: String,
        ): String? =
            when (val node = fields[key]) {
                null, is YamlNull -> {
                    null
                }

                is YamlScalar -> {
                    node.content.trim()
                }

                else -> {
                    add(IssueCode.E06, join(path, key))
                    null
                }
            }

        fun requiredString(
            fields: Map<String, YamlNode>,
            key: String,
            path: String,
        ): String? {
            if (fields[key] == null || fields[key] is YamlNull) {
                add(IssueCode.E05, join(path, key))
                return null
            }
            return string(fields, key, path)
        }

        fun int(
            fields: Map<String, YamlNode>,
            key: String,
            path: String,
        ): Int? {
            val text = string(fields, key, path) ?: return null
            val value = text.takeIf { INTEGER.matches(it) }?.toIntOrNull()
            if (value == null) add(IssueCode.E06, join(path, key), text)
            return value
        }

        fun bool(
            fields: Map<String, YamlNode>,
            key: String,
            path: String,
        ): Boolean? {
            val text = string(fields, key, path) ?: return null
            return when (text) {
                "true" -> {
                    true
                }

                "false" -> {
                    false
                }

                else -> {
                    add(IssueCode.E06, join(path, key), text)
                    null
                }
            }
        }

        fun list(
            fields: Map<String, YamlNode>,
            key: String,
            path: String,
        ): List<YamlNode>? =
            when (val node = fields[key]) {
                null, is YamlNull -> {
                    null
                }

                is YamlList -> {
                    node.items
                }

                else -> {
                    add(IssueCode.E06, join(path, key))
                    null
                }
            }

        fun document(
            fields: Map<String, YamlNode>,
            body: String,
            availableImages: Set<String>,
        ): ParsedDocument {
            unknownKeys(fields, TOP_LEVEL_KEYS, "")

            when (val format = string(fields, "format", "")) {
                null -> add(IssueCode.E04, "format")
                RecipeRules.FORMAT -> Unit
                else -> add(IssueCode.E04, "format", format)
            }
            val title = requiredString(fields, "title", "")
            val servings =
                if (fields["servings"] == null || fields["servings"] is YamlNull) {
                    add(IssueCode.E05, "servings")
                    null
                } else {
                    int(fields, "servings", "")
                }
            val ingredientNodes =
                if (fields["ingredients"] == null || fields["ingredients"] is YamlNull) {
                    add(IssueCode.E05, "ingredients")
                    null
                } else {
                    list(fields, "ingredients", "")
                }

            val tags = tags(fields)
            val image = image(string(fields, "image", ""), availableImages)
            // Failed ingredients keep their slot (as placeholders) so validator paths stay aligned.
            val ingredients = ingredientNodes?.mapIndexed { i, node -> ingredient(node, i) }.orEmpty()

            val draft =
                RecipeDraft(
                    title = title.orEmpty(),
                    servings = servings ?: RecipeRules.SERVINGS.first,
                    ingredients = ingredients,
                    uid = string(fields, "uid", ""),
                    description = string(fields, "description", "")?.ifEmpty { null },
                    prepMinutes = int(fields, "prep_minutes", ""),
                    cookMinutes = int(fields, "cook_minutes", ""),
                    tags = tags,
                    image = image,
                    source = string(fields, "source", "")?.ifEmpty { null },
                    author = string(fields, "author", "")?.ifEmpty { null },
                    body = body,
                )

            val validation = RecipeDraftValidator.validate(draft)
            issues +=
                validation.issues.filterNot { issue ->
                    issue.ingredientIndex in failedIngredients ||
                        errorPaths.any { issue.path == it || issue.path.startsWith("$it.") || issue.path.startsWith("$it[") }
                }
            return ParsedDocument(if (hasErrors) null else validation.draft, issues.toList())
        }

        private fun tags(fields: Map<String, YamlNode>): List<String> {
            val nodes = list(fields, "tags", "") ?: return emptyList()
            val raw =
                nodes.mapIndexedNotNull { i, node ->
                    if (node is YamlScalar) {
                        node.content
                    } else {
                        add(IssueCode.E06, "tags[$i]")
                        null
                    }
                }
            val normalized = raw.map { it.trim().lowercase(Locale.ROOT) }.distinct()
            if (normalized != raw) add(IssueCode.W02, "tags")
            return normalized
        }

        private fun image(
            image: String?,
            availableImages: Set<String>,
        ): String? {
            if (image == null) return null
            if (!RecipeRules.IMAGE.matches(image)) {
                add(IssueCode.W04, "image", image)
                return null
            }
            if (image !in availableImages) {
                add(IssueCode.W03, "image", image)
                return null
            }
            return image
        }

        private fun ingredient(
            node: YamlNode,
            index: Int,
        ): IngredientDraft {
            val path = "ingredients[$index]"
            val errorsBefore = issues.count { it.isError }
            val fields =
                map(node, path) ?: run {
                    failedIngredients += index
                    return PLACEHOLDER
                }
            unknownKeys(fields, INGREDIENT_KEYS, path)

            val name = requiredString(fields, "name", path)?.replace(WHITESPACE, " ")
            val unitText = requiredString(fields, "unit", path)
            val unitParse = unitText?.let { MeasureUnit.parse(it) }
            if (unitText != null && unitParse == null) add(IssueCode.E08, "$path.unit", unitText)
            if (unitParse?.normalized == true) add(IssueCode.W02, "$path.unit", unitText)
            val unit = unitParse?.unit

            val quantity = quantity(fields, unit, path)
            val unitWeight = unitWeight(fields, path)
            val match = fields["match"]?.takeUnless { it is YamlNull }?.let { match(it, "$path.match") }
            val links = list(fields, "links", path)?.mapIndexedNotNull { i, link -> link(link, "$path.links[$i]") }.orEmpty()

            val draft =
                IngredientDraft(
                    name = name.orEmpty(),
                    quantity = quantity,
                    unit = unit ?: MeasureUnit.UD,
                    unitWeightG = unitWeight,
                    note = string(fields, "note", path)?.ifEmpty { null },
                    group = string(fields, "group", path)?.ifEmpty { null },
                    optional = bool(fields, "optional", path) ?: false,
                    pantry = bool(fields, "pantry", path) ?: false,
                    match = match,
                    links = links,
                )
            if (issues.count { it.isError } != errorsBefore) failedIngredients += index
            return draft
        }

        private fun quantity(
            fields: Map<String, YamlNode>,
            unit: MeasureUnit?,
            path: String,
        ): Amount? {
            val raw = string(fields, "quantity", path) ?: return null
            if (unit == MeasureUnit.AL_GUSTO) {
                add(IssueCode.W06, "$path.quantity", raw)
                return null
            }
            return when (val result = QuantityParser.parse(raw)) {
                is QuantityParseResult.Valid -> {
                    if (result.usedDecimalComma) add(IssueCode.W02, "$path.quantity", raw)
                    if (result.rounded) add(IssueCode.W12, "$path.quantity", raw)
                    result.amount
                }

                is QuantityParseResult.Invalid -> {
                    add(IssueCode.E10, "$path.quantity", raw)
                    null
                }
            }
        }

        private fun unitWeight(
            fields: Map<String, YamlNode>,
            path: String,
        ): Amount? {
            val raw = string(fields, "unit_weight_g", path) ?: return null
            return when (val result = QuantityParser.parse(raw)) {
                is QuantityParseResult.Valid -> {
                    result.amount
                }

                is QuantityParseResult.Invalid -> {
                    val code =
                        when (result.reason) {
                            QuantityParseResult.Reason.NOT_POSITIVE, QuantityParseResult.Reason.TOO_LARGE -> IssueCode.E07
                            else -> IssueCode.E06
                        }
                    add(code, "$path.unit_weight_g", raw)
                    null
                }
            }
        }

        private fun match(
            node: YamlNode,
            path: String,
        ): MatchHints? {
            val fields = map(node, path) ?: return null
            unknownKeys(fields, MATCH_KEYS, path)
            val query = requiredString(fields, "query", path)
            val packageText = string(fields, "package_size", path)
            val packageSize = packageText?.let { PackageSize.parse(it) }
            if (packageText != null && packageSize == null) add(IssueCode.E11, "$path.package_size", packageText)
            val storeBrandOk = bool(fields, "store_brand_ok", path) ?: true
            return query?.let { MatchHints(it, packageSize, storeBrandOk) }
        }

        private fun link(
            node: YamlNode,
            path: String,
        ): LinkDraft? {
            val fields = map(node, path) ?: return null
            unknownKeys(fields, LINK_KEYS, path)
            val provider = requiredString(fields, "provider", path)
            val productId = requiredString(fields, "product_id", path)
            val name = string(fields, "name", path)?.ifEmpty { null }
            val preferred = bool(fields, "preferred", path) ?: false
            return if (provider != null && productId != null) LinkDraft(provider, productId, name, preferred) else null
        }

        private fun join(
            path: String,
            key: String,
        ) = if (path.isEmpty()) key else "$path.$key"
    }
}
