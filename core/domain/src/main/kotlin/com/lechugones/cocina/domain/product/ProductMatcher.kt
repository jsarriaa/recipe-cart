package com.lechugones.cocina.domain.product

import com.lechugones.cocina.domain.recipe.MatchHints
import com.lechugones.cocina.domain.recipe.NameKey
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a suggestion came from, in order of trust (SPEC § 5.4, step 2). */
enum class SuggestionSource {
    /** A link written in the imported file. */
    FILE,

    /** A product the user confirmed before for the same ingredient name. */
    MEMORY,

    /** A catalogue search result, ranked by [ProductMatcher.score]. */
    SEARCH,
}

data class ProductSuggestion(
    val product: Product,
    val source: SuggestionSource,
    /** 0–100 for [SuggestionSource.SEARCH]; 100 for file and memory links. */
    val score: Int,
)

/** Suggestions for one ingredient at one provider, best first. */
data class MatchResult(
    val suggestions: List<ProductSuggestion>,
) {
    val best: ProductSuggestion? get() = suggestions.firstOrNull()

    /** The best suggestion is selected without asking when it is trusted or scores well. */
    val preselected: ProductSuggestion?
        get() = best?.takeIf { it.source != SuggestionSource.SEARCH || it.score >= ProductMatcher.PRESELECT_SCORE }
}

/**
 * Ingredient → product matching (SPEC § 5.4). Pure: the caller supplies the candidates (file
 * links and remembered links found in the cache, and catalogue search results).
 */
object ProductMatcher {
    const val PRESELECT_SCORE = 55
    const val MAX_SUGGESTIONS = 30

    private const val TEXT_POINTS = 50
    private const val ORDER_BONUS = 10
    private const val SIZE_POINTS = 25
    private const val SIZE_NO_HINT = 12
    private const val BRAND_GOOD = 15
    private const val BRAND_NATIONAL = 5
    private const val BRAND_SPECIFIC = 10

    /** The text searched in the catalogue: the hint's query, or the ingredient name. */
    fun query(
        name: String,
        hints: MatchHints?,
    ): String = hints?.query?.takeIf { it.isNotBlank() } ?: name

    fun match(
        name: String,
        hints: MatchHints?,
        fileLinks: List<Product> = emptyList(),
        remembered: List<Product> = emptyList(),
        searchResults: List<Product> = emptyList(),
        storeBrandOnly: Boolean = false,
    ): MatchResult {
        val query = query(name, hints)
        val trusted =
            fileLinks.filter { it.available }.map { ProductSuggestion(it, SuggestionSource.FILE, 100) } +
                remembered.filter { it.available }.map { ProductSuggestion(it, SuggestionSource.MEMORY, 100) }
        val searched =
            searchResults
                .filter { it.available && !(storeBrandOnly && it.brandKind == BrandKind.NATIONAL) }
                .map { ProductSuggestion(it, SuggestionSource.SEARCH, score(query, hints, it)) }
                .sortedWith(compareByDescending<ProductSuggestion> { it.score }.thenBy { it.product.name.length })
        return MatchResult((trusted + searched).distinctBy { it.product.key }.take(MAX_SUGGESTIONS))
    }

    /** Score 0–100: text (0–60), package size (0–25) and brand (0–15). */
    fun score(
        query: String,
        hints: MatchHints?,
        product: Product,
    ): Int = textScore(query, product.name) + sizeScore(hints, product) + brandScore(hints, product)

    internal fun textScore(
        query: String,
        productName: String,
    ): Int {
        val wanted = tokens(query)
        if (wanted.isEmpty()) return 0
        val have = tokens(productName)
        val positions = wanted.map { token -> have.indexOfFirst { matches(token, it) } }
        val found = positions.count { it >= 0 }
        val inOrder = found == wanted.size && positions.zipWithNext().all { (a, b) -> a < b }
        return (TEXT_POINTS * found / wanted.size.toDouble()).roundToInt() + if (inOrder) ORDER_BONUS else 0
    }

    internal fun sizeScore(
        hints: MatchHints?,
        product: Product,
    ): Int {
        val hint = hints?.packageSize?.total() ?: return SIZE_NO_HINT
        if (hint.unit != product.net.unit) return SIZE_NO_HINT
        val ratio = product.net.value.toDouble() / hint.value.toDouble()
        if (ratio <= 0) return 0
        return (SIZE_POINTS * (1 - min(1.0, abs(ln(ratio)) / ln(4.0)))).roundToInt()
    }

    internal fun brandScore(
        hints: MatchHints?,
        product: Product,
    ): Int =
        when {
            hints?.storeBrandOk == false -> BRAND_SPECIFIC
            product.brandKind == BrandKind.NATIONAL -> BRAND_NATIONAL
            else -> BRAND_GOOD
        }

    /** Folded words of [query] worth searching the catalogue for, longest first. */
    fun searchTerms(query: String): List<String> =
        tokens(query).filter { it.length >= MIN_TERM }.distinct().sortedByDescending { it.length }

    private const val MIN_TERM = 3

    /** Words that carry no meaning for matching ("de", "con"…). */
    private val stopWords = setOf("de", "del", "la", "el", "los", "las", "con", "y", "en", "a", "al", "para", "sin")

    private fun tokens(text: String): List<String> =
        NameKey
            .of(text)
            .split(' ')
            .filter { it.isNotBlank() && it !in stopWords }
            .map(::stem)

    /**
     * Simple Spanish plural folding: drop a final `s`, then a final `e`, so `tomate`/`tomates`
     * and `limón`/`limones` meet.
     */
    internal fun stem(word: String): String {
        var w = word
        if (w.length > 3 && w.endsWith('s')) w = w.dropLast(1)
        if (w.length > 3 && w.endsWith('e')) w = w.dropLast(1)
        return w
    }

    private fun matches(
        wanted: String,
        candidate: String,
    ): Boolean = candidate == wanted || (wanted.length >= MIN_PREFIX && candidate.startsWith(wanted))

    private const val MIN_PREFIX = 5
}
