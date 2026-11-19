package com.lechugones.cocina.domain.community

import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipepack.RecipeDocumentWriter
import com.lechugones.cocina.domain.recipepack.RecipeDrafts
import com.lechugones.cocina.domain.recipepack.RecipeRules

/** Turning one of the user's recipes into the text published in the community recipe book. */
object CommunityPublication {
    sealed interface Prepared {
        data class Ready(
            val text: String,
        ) : Prepared

        /** The recipe came from someone else (downloaded from the book): it is theirs to publish. */
        data class OtherAuthor(
            val author: String,
        ) : Prepared
    }

    /** A publishing name: trimmed, 1 to 60 characters; null when it is not usable. */
    fun cleanName(name: String): String? =
        name
            .trim()
            .replace(Regex("""\s+"""), " ")
            .takeIf { it.isNotEmpty() && it.length <= RecipeRules.AUTHOR_MAX }

    /**
     * The recipe as a `cpl-recipe/1` file with [name] as its author. Product links and the photo
     * stay at home: links are personal choices, and the book holds text only.
     */
    fun prepare(
        recipe: Recipe,
        name: String,
    ): Prepared {
        val author = recipe.author
        if (author != null && !sameName(author, name)) return Prepared.OtherAuthor(author)
        val draft = RecipeDrafts.fromRecipe(recipe)
        val text =
            RecipeDocumentWriter.write(
                draft.copy(
                    author = name,
                    image = null,
                    ingredients = draft.ingredients.map { it.copy(links = emptyList()) },
                ),
            )
        return Prepared.Ready(text)
    }

    private fun sameName(
        a: String,
        b: String,
    ) = a.trim().equals(b.trim(), ignoreCase = true)
}
