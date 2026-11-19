package com.lechugones.cocina.domain.recipe

import java.text.Collator
import java.util.Locale

enum class RecipeSort {
    /** Alphabetical, ignoring accents and case (es-ES collation). */
    NAME,

    /** Most recently added first. */
    NEWEST,

    /** Cheapest per serving first; recipes without a price go last, by name. */
    CHEAPEST,

    /** Most recently cooked first; never-cooked recipes go last, by name. */
    RECENTLY_COOKED,
}

/** Search, tag filter and sorting of the recipe list (SPEC § 4.2). */
object RecipeListFilter {
    /**
     * Keeps recipes where every word of [query] appears in the title, a tag or an ingredient
     * name (accent- and case-insensitive), and that carry [tag] when given.
     */
    fun apply(
        recipes: List<RecipeSummary>,
        query: String,
        tag: String?,
        sort: RecipeSort,
    ): List<RecipeSummary> {
        val words = NameKey.of(query).split(' ').filter { it.isNotEmpty() }
        val filtered =
            recipes.filter { recipe ->
                (tag == null || tag in recipe.tags) &&
                    (words.isEmpty() || haystack(recipe).let { text -> words.all { it in text } })
            }
        val collator = Collator.getInstance(SPANISH).apply { strength = Collator.PRIMARY }
        return when (sort) {
            RecipeSort.NAME -> {
                filtered.sortedWith(compareBy(collator) { it.title })
            }

            RecipeSort.RECENTLY_COOKED -> {
                filtered.sortedWith(
                    compareBy<RecipeSummary> { it.lastCookedAt == null }
                        .thenByDescending { it.lastCookedAt }
                        .thenBy(collator) { it.title },
                )
            }

            RecipeSort.CHEAPEST -> {
                filtered.sortedWith(
                    compareBy<RecipeSummary> { it.costPerServing == null }
                        .thenBy { it.costPerServing?.cents ?: 0 }
                        .thenBy(collator) { it.title },
                )
            }

            RecipeSort.NEWEST -> {
                filtered.sortedByDescending { it.createdAt }
            }
        }
    }

    /** Distinct tags of [recipes], alphabetically. */
    fun tags(recipes: List<RecipeSummary>): List<String> {
        val collator = Collator.getInstance(SPANISH).apply { strength = Collator.PRIMARY }
        return recipes.flatMap { it.tags }.distinct().sortedWith(collator)
    }

    private fun haystack(recipe: RecipeSummary): String =
        buildString {
            append(NameKey.of(recipe.title))
            recipe.tags.forEach { append(' ').append(NameKey.of(it)) }
            recipe.ingredientKeys.forEach { append(' ').append(it) }
        }

    private val SPANISH: Locale = Locale.forLanguageTag("es-ES")
}
