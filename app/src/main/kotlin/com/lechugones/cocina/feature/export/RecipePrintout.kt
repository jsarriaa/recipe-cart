package com.lechugones.cocina.feature.export

import android.content.res.Resources
import com.lechugones.cocina.R
import com.lechugones.cocina.data.image.RecipeImageStore
import com.lechugones.cocina.domain.markdown.Markdown
import com.lechugones.cocina.domain.markdown.MdBlock
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.ServingsScaler
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.ui.format.quantityText
import java.io.File
import java.math.BigDecimal

/** One ingredient line of the shared PDF: no prices, only where to buy it. */
data class PrintIngredient(
    /** "700 g", "al gusto". */
    val quantity: String,
    /** "huevo (opcional)". */
    val name: String,
    val note: String?,
    /** "Huevos grandes L", or null when nothing is linked. */
    val product: String?,
    /** "Mercadona", "A mano"; null when unknown or nothing is linked. */
    val shop: String? = null,
    /** The product's page at the supermarket, linked from the PDF; only http(s). */
    val url: String? = null,
)

data class PrintGroup(
    val title: String?,
    val ingredients: List<PrintIngredient>,
)

/**
 * Everything the shared PDF shows (SPEC § 4.2, "Compartir en PDF"), with every word already
 * resolved from string resources, so [RecipePdfWriter] only lays it out.
 */
data class RecipePrintout(
    /** "Cocina para Lechugones". */
    val brand: String,
    val title: String,
    val description: String?,
    /** "4 raciones", "Preparación: 15 min", "Cocción: 35 min": shown as pills. */
    val details: List<String>,
    val photo: File?,
    val ingredientsTitle: String,
    val groups: List<PrintGroup>,
    /** "Básicos de despensa (2)", or null without staples. */
    val staplesTitle: String?,
    val staples: List<PrintIngredient>,
    val steps: List<MdBlock>,
    /** Shown instead of [steps] when the recipe has none. */
    val stepsEmpty: String,
    /** "Cocina para Lechugones · página %d" as a function of the page number. */
    val footer: (Int) -> String,
    /** File name without extension. */
    val fileName: String,
)

/**
 * The words of the PDF, resolved from string resources by [fromResources], so [RecipePrintouts]
 * can be tested without Android (as the shopping list's share text is).
 */
data class PrintWords(
    val brand: String,
    val optional: String,
    val ingredients: String,
    val stepsEmpty: String,
    /** "4 raciones". */
    val servings: (Int) -> String,
    val prep: (Int) -> String,
    val cook: (Int) -> String,
    /** "Básicos de despensa (2)". */
    val staples: (Int) -> String,
    val footer: (Int) -> String,
    /** "700 g", "al gusto". */
    val quantity: (BigDecimal?, MeasureUnit) -> String,
) {
    companion object {
        fun fromResources(resources: Resources): PrintWords {
            val brand = resources.getString(R.string.pdf_brand)
            return PrintWords(
                brand = brand,
                optional = resources.getString(R.string.recipe_optional),
                ingredients = resources.getString(R.string.recipe_ingredients),
                stepsEmpty = resources.getString(R.string.recipe_steps_empty),
                servings = { resources.getQuantityString(R.plurals.recipe_servings, it, it) },
                prep = { resources.getString(R.string.recipe_prep_minutes, it) },
                cook = { resources.getString(R.string.recipe_cook_minutes, it) },
                staples = { resources.getString(R.string.recipe_staples, it) },
                footer = { resources.getString(R.string.pdf_footer, brand, it) },
                quantity = { value, unit -> quantityText(resources, value, unit) },
            )
        }
    }
}

object RecipePrintouts {
    /**
     * The recipe at [servings], each ingredient with the product the recipe shows for it
     * ([productFor]) and the name of its supermarket ([shopName]). Never a price.
     */
    fun build(
        words: PrintWords,
        recipe: Recipe,
        servings: Int,
        photo: File?,
        productFor: (Ingredient) -> ProviderProduct?,
        shopName: (String) -> String?,
    ): RecipePrintout {
        fun line(ingredient: Ingredient): PrintIngredient {
            val scaled = ServingsScaler.scale(ingredient.quantity, ingredient.unit, recipe.servings, servings)
            val optional = if (ingredient.optional) " (${words.optional})" else ""
            val product = productFor(ingredient)
            return PrintIngredient(
                quantity = words.quantity(scaled, ingredient.unit),
                name = ingredient.name + optional,
                note = ingredient.note,
                product = product?.product?.name,
                shop = product?.let { shopName(it.product.key.providerId) },
                url = product?.webUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            )
        }

        val (staples, main) = recipe.ingredients.partition { it.pantryStaple }
        val details =
            listOfNotNull(
                words.servings(servings),
                recipe.prepMinutes?.let(words.prep),
                recipe.cookMinutes?.let(words.cook),
            )
        return RecipePrintout(
            brand = words.brand,
            title = recipe.title,
            description = recipe.description,
            details = details,
            photo = photo,
            ingredientsTitle = words.ingredients,
            groups = main.groupBy { it.group }.map { (group, items) -> PrintGroup(group, items.map(::line)) },
            staplesTitle = if (staples.isEmpty()) null else words.staples(staples.size),
            staples = staples.map(::line),
            steps = Markdown.parse(recipe.bodyMarkdown),
            stepsEmpty = words.stepsEmpty,
            footer = words.footer,
            fileName = recipe.title,
        )
    }

    /** The recipe's stored photo, if the file is there. */
    fun photo(
        filesDir: File,
        imagePath: String?,
    ): File? = imagePath?.let { File(File(filesDir, RecipeImageStore.DIRECTORY), it) }?.takeIf { it.isFile }
}
