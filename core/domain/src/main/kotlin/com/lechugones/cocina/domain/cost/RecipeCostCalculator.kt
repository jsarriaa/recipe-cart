package com.lechugones.cocina.domain.cost

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.packaging.PackageFraction
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.ServingsScaler
import com.lechugones.cocina.domain.units.Dimension
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * Computes dish cost, whole-package cost and leftovers for a recipe (SPEC § 4.4).
 * Pure function of its inputs; money stays exact until the final rounding to cents.
 */
object RecipeCostCalculator {
    /** Ratios this close above a whole number do not trigger an extra package. */
    private val PACKAGE_EPSILON = BigDecimal("0.000001")

    fun compute(
        recipe: Recipe,
        servings: Int,
        products: Map<ProductKey, Product>,
        selection: ProductSelection,
        options: CostOptions,
    ): RecipeCost {
        require(servings > 0) { "servings must be positive: $servings" }
        val lines = recipe.ingredients.map { costLine(it, recipe.servings, servings, products, selection, options) }
        val included = lines.filter { it.includedInTotals && it.status == LineStatus.PRICED }
        val purchases = purchases(included)

        val dishCents = included.fold(BigDecimal.ZERO) { sum, line -> sum.add(line.costCents ?: BigDecimal.ZERO) }
        val perServingCents = dishCents.divide(BigDecimal(servings), 9, RoundingMode.HALF_UP)

        return RecipeCost(
            servings = servings,
            lines = lines,
            purchases = purchases,
            dishCost = Money.ofCents(dishCents),
            perServing = Money.ofCents(perServingCents),
            wholePackagesCost = purchases.fold(Money.ZERO) { sum, purchase -> sum + purchase.totalCost },
            missingPriceCount = lines.count { it.isMissingPrice },
            approximate = lines.any { it.includedInTotals && it.approximate },
        )
    }

    private fun costLine(
        ingredient: Ingredient,
        recipeServings: Int,
        servings: Int,
        products: Map<ProductKey, Product>,
        selection: ProductSelection,
        options: CostOptions,
    ): IngredientCostLine {
        val included =
            (!ingredient.pantryStaple || options.includeStaples) &&
                (!ingredient.optional || options.includeOptionals)
        val scaled = ServingsScaler.scale(ingredient.quantity, ingredient.unit, recipeServings, servings)
        if (ingredient.unit.dimension == Dimension.NEGLIGIBLE || scaled == null) {
            return IngredientCostLine(ingredient, scaled, LineStatus.NO_QUANTITY, includedInTotals = false)
        }

        val candidates = candidateLinks(ingredient.links, selection).mapNotNull { products[it.product] }
        if (candidates.isEmpty()) {
            return IngredientCostLine(ingredient, scaled, LineStatus.NO_LINK, included)
        }
        val evaluated =
            candidates.map { product ->
                Candidate(product, PackageUsageResolver.resolve(scaled, ingredient.unit, ingredient.unitWeight, product))
            }
        val chosen =
            when (selection) {
                is ProductSelection.SingleProvider -> evaluated.first()
                is ProductSelection.Cheapest -> evaluated.filter { it.isPriced }.minWithOrNull(cheapestFirst) ?: evaluated.first()
            }

        val product = chosen.product
        val usage = chosen.usage
        val price = product.price
        val approximate = product.approx || isStale(product, options)
        return when {
            usage == null -> {
                IngredientCostLine(ingredient, scaled, LineStatus.INCOMPATIBLE_UNITS, included, product, approximate = approximate)
            }

            price == null -> {
                IngredientCostLine(
                    ingredient,
                    scaled,
                    LineStatus.NO_PRICE,
                    included,
                    product,
                    usage,
                    PackageFraction.ofRatio(usage.ratio),
                    approximate = approximate,
                )
            }

            else -> {
                IngredientCostLine(
                    ingredient = ingredient,
                    scaledQuantity = scaled,
                    status = LineStatus.PRICED,
                    includedInTotals = included,
                    product = product,
                    usage = usage,
                    fraction = PackageFraction.ofRatio(usage.ratio),
                    costCents = usage.ratio.multiply(BigDecimal(price.cents)),
                    approximate = approximate,
                )
            }
        }
    }

    private fun candidateLinks(
        links: List<IngredientLink>,
        selection: ProductSelection,
    ): List<IngredientLink> =
        when (selection) {
            is ProductSelection.SingleProvider -> {
                links
                    .filter { it.product.providerId == selection.providerId }
                    .sortedByDescending { it.preferred }
            }

            is ProductSelection.Cheapest -> {
                links.filter { it.product.providerId in selection.providerIds }
            }
        }

    private fun purchases(lines: List<IngredientCostLine>): List<ProductPurchase> =
        lines
            .groupBy { checkNotNull(it.product).key }
            .map { (_, productLines) ->
                val product = checkNotNull(productLines.first().product)
                val price = checkNotNull(product.price)
                val ratio = productLines.fold(BigDecimal.ZERO) { sum, line -> sum.add(checkNotNull(line.usage).ratio) }
                val packages = packagesFor(ratio)
                val leftoverRatio = BigDecimal(packages).subtract(ratio).max(BigDecimal.ZERO)
                val measure = checkNotNull(productLines.first().usage).measure
                ProductPurchase(
                    product = product,
                    ratio = ratio,
                    packages = packages,
                    totalCost = price * packages,
                    leftoverRatio = leftoverRatio,
                    leftover = leftoverRatio.multiply(measure.usable).setScale(3, RoundingMode.HALF_UP),
                    leftoverUnit = measure.unit,
                    approximate = productLines.any { it.approximate },
                )
            }

    /** Whole packages needed for [ratio] packages: ⌈ratio⌉, at least 1. */
    fun packagesFor(ratio: BigDecimal): Int =
        ratio
            .subtract(PACKAGE_EPSILON)
            .setScale(0, RoundingMode.CEILING)
            .toInt()
            .coerceAtLeast(1)

    private fun isStale(
        product: Product,
        options: CostOptions,
    ): Boolean {
        val fetchedAt = product.fetchedAt ?: return false
        return Duration.between(fetchedAt, options.now) > options.staleAfter
    }

    private class Candidate(
        val product: Product,
        val usage: PackageUsage?,
    ) {
        val isPriced: Boolean get() = usage != null && product.price != null

        /** Cost of the whole packages this ingredient alone needs, in cents. */
        val wholeCents: Long
            get() = checkNotNull(product.price).cents * packagesFor(checkNotNull(usage).ratio)

        val proportionalCents: BigDecimal
            get() = checkNotNull(usage).ratio.multiply(BigDecimal(checkNotNull(product.price).cents))
    }

    private val cheapestFirst: Comparator<Candidate> =
        compareBy<Candidate> { it.wholeCents }.thenBy { it.proportionalCents }
}
