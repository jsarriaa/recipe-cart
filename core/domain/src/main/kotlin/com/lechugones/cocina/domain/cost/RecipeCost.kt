package com.lechugones.cocina.domain.cost

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.packaging.PackageFraction
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.units.MeasureUnit
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** Which product to use for each ingredient (SPEC § 4.4, supermarket mode). */
sealed interface ProductSelection {
    /** The preferred link at one provider, otherwise its first link there. */
    data class SingleProvider(
        val providerId: String,
    ) : ProductSelection

    /** The link with the lowest whole-package cost among [providerIds]. */
    data class Cheapest(
        val providerIds: Set<String>,
    ) : ProductSelection
}

data class CostOptions(
    val now: Instant,
    val includeStaples: Boolean = false,
    val includeOptionals: Boolean = false,
    val staleAfter: Duration = Duration.ofDays(14),
)

enum class LineStatus {
    PRICED,
    NO_LINK,
    NO_PRICE,
    INCOMPATIBLE_UNITS,

    /** `pizca`, `al_gusto`, or a missing quantity: nothing to cost. */
    NO_QUANTITY,
}

/** Cost information for one ingredient at the requested servings. */
data class IngredientCostLine(
    val ingredient: Ingredient,
    val scaledQuantity: BigDecimal?,
    val status: LineStatus,
    /** False for excluded staples/optionals and for [LineStatus.NO_QUANTITY]. */
    val includedInTotals: Boolean,
    val product: Product? = null,
    val usage: PackageUsage? = null,
    val fraction: PackageFraction? = null,
    /** Exact proportional cost in cents, before rounding. */
    val costCents: BigDecimal? = null,
    val approximate: Boolean = false,
    /** The product's price is older than [CostOptions.staleAfter]. */
    val stale: Boolean = false,
) {
    val cost: Money? get() = costCents?.let(Money::ofCents)
    val isMissingPrice: Boolean
        get() =
            includedInTotals &&
                (status == LineStatus.NO_LINK || status == LineStatus.NO_PRICE || status == LineStatus.INCOMPATIBLE_UNITS)
}

/** Whole packages to buy for one product, after consolidating every ingredient that uses it. */
data class ProductPurchase(
    val product: Product,
    val ratio: BigDecimal,
    val packages: Int,
    val totalCost: Money,
    /** Unused share of the bought packages, in packages. */
    val leftoverRatio: BigDecimal,
    /** Leftover amount in [leftoverUnit]. */
    val leftover: BigDecimal,
    val leftoverUnit: MeasureUnit,
    val approximate: Boolean,
)

data class RecipeCost(
    val servings: Int,
    val lines: List<IngredientCostLine>,
    val purchases: List<ProductPurchase>,
    /** Σ proportional costs ("Coste del plato"). */
    val dishCost: Money,
    val perServing: Money,
    /** Σ whole packages ("Comprando envases enteros"). */
    val wholePackagesCost: Money,
    /** Included ingredients without a usable price: the total is a lower bound ("≥"). */
    val missingPriceCount: Int,
    val approximate: Boolean,
    /** Ingredients counted in the totals, and how many of them have a price ("5 de 6 con precio"). */
    val includedCount: Int = 0,
    val pricedCount: Int = 0,
    /** Some included price is older than the staleness threshold. */
    val stale: Boolean = false,
    /** Fetch date of the oldest price used, for "Precios del …". */
    val oldestPriceAt: Instant? = null,
)

/** "Comparativa" (SPEC § 4.4): the same recipe priced at each provider, plus the best combination. */
data class CostComparison(
    val byProvider: Map<String, RecipeCost>,
    /** "Mejor combinación": each ingredient at the provider with the lowest whole-package cost. */
    val bestCombination: RecipeCost,
)
