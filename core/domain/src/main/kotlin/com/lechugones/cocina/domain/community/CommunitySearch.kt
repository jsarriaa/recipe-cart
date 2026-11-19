package com.lechugones.cocina.domain.community

import com.lechugones.cocina.domain.recipe.NameKey

/**
 * One entry of the community recipe book's `index.json` (docs/COMMUNITY.md). [path] is the
 * recipe file inside the book's repository.
 */
data class CommunityRecipe(
    val path: String,
    val title: String,
    val servings: Int,
    val uid: String? = null,
    val author: String? = null,
    val description: String? = null,
    val totalMinutes: Int? = null,
    val tags: List<String> = emptyList(),
    val ingredients: List<String> = emptyList(),
)

/** What the user filters by on "Recetas de la comunidad". */
data class CommunityFilter(
    val query: String = "",
    val tags: Set<String> = emptySet(),
    /** Prep plus cook time at most this; recipes without a time are left out while it is set. */
    val maxMinutes: Int? = null,
)

object CommunitySearch {
    /**
     * Recipes that match every word of the query (in the title, author, tags, ingredients or
     * description, ignoring case and accents), carry every chosen tag, and fit the time limit.
     * The index order is kept.
     */
    fun filter(
        recipes: List<CommunityRecipe>,
        filter: CommunityFilter,
    ): List<CommunityRecipe> {
        val words = NameKey.of(filter.query).split(' ').filter { it.isNotEmpty() }
        return recipes.filter { recipe ->
            filter.tags.all { it in recipe.tags } &&
                (filter.maxMinutes == null || (recipe.totalMinutes != null && recipe.totalMinutes <= filter.maxMinutes)) &&
                (words.isEmpty() || searchText(recipe).let { text -> words.all { it in text } })
        }
    }

    /** Every tag in the book, most used first, then alphabetically. */
    fun tags(recipes: List<CommunityRecipe>): List<String> =
        recipes
            .flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { NameKey.of(it.key) })
            .map { it.key }

    private fun searchText(recipe: CommunityRecipe): String =
        NameKey.of(
            buildString {
                append(recipe.title).append(' ')
                recipe.author?.let { append(it).append(' ') }
                recipe.description?.let { append(it).append(' ') }
                recipe.tags.forEach { append(it).append(' ') }
                recipe.ingredients.forEach { append(it).append(' ') }
            },
        )
}
