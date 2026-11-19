package com.lechugones.cocina.domain.pantry

import com.lechugones.cocina.domain.cost.RecipeCost
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.units.Dimension
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/** "¿Qué puedo cocinar?" status (SPEC § 4.5). */
enum class CoverageStatus {
    /** "Lo tienes todo". */
    ALL,

    /** "Te falta 1–2". */
    FEW_MISSING,

    /** "Te faltan más". */
    MANY_MISSING,
}

data class RecipeCoverage(
    val recipe: Recipe,
    val status: CoverageStatus,
    /** Counted ingredients the pantry does not cover (fully). */
    val missing: List<Ingredient>,
    /** Names of the "usar pronto" lots this recipe would use ("Aprovecha: nata, espinacas"). */
    val usesSoon: List<String>,
    /** Share (0–1) of the dish cost already in the pantry; zero without prices. */
    val pantryShare: BigDecimal,
)

object CookCoverage {
    private const val FEW = 2

    /**
     * Coverage of every recipe at its default servings, ignoring staples and optional
     * ingredients, sorted by status, then recipes that use "usar pronto" food, then by
     * [pantryShare][RecipeCoverage.pantryShare]. [costs] are optional, by recipe id.
     */
    fun evaluate(
        recipes: List<Recipe>,
        lots: List<PantryLot>,
        today: LocalDate,
        zone: ZoneId,
        windowDays: Int = UseSoon.DEFAULT_WINDOW_DAYS,
        costs: Map<String, RecipeCost> = emptyMap(),
        remembered: (String) -> Set<ProductKey> = { emptySet() },
    ): List<RecipeCoverage> {
        val soon = lots.filter { UseSoon.evaluate(it, today, zone, windowDays).useSoon }.associateBy { it.id }
        return recipes
            .map { recipe ->
                val plan = CookPlanner.plan(recipe, recipe.servings, lots, today, zone, windowDays, remembered)
                val counted = plan.ingredients.filter { counts(it.ingredient) }
                val missing =
                    counted.filter { it.missing != null && it.presenceLots.isEmpty() }.map { it.ingredient }
                val usesSoon =
                    plan.ingredients
                        .flatMap { it.takes }
                        .mapNotNull { soon[it.lotId]?.name }
                        .distinct()
                RecipeCoverage(
                    recipe = recipe,
                    status =
                        when (missing.size) {
                            0 -> CoverageStatus.ALL
                            in 1..FEW -> CoverageStatus.FEW_MISSING
                            else -> CoverageStatus.MANY_MISSING
                        },
                    missing = missing,
                    usesSoon = usesSoon,
                    pantryShare = pantryShare(plan, costs[recipe.id]),
                )
            }.sortedWith(
                compareBy<RecipeCoverage> { it.status.ordinal }
                    .thenBy { it.usesSoon.isEmpty() }
                    .thenByDescending { it.pantryShare },
            )
    }

    /**
     * The part of [recipe] still to buy after [plan] ("Te falta comprar"): each counted
     * ingredient with only its missing amount, at the plan's servings. Fully covered
     * ingredients are left out, so the cost engine buys nothing for them.
     */
    fun remainingToBuy(
        recipe: Recipe,
        plan: CookPlan,
    ): Recipe {
        val byId = plan.ingredients.associateBy { it.ingredient.id }
        val remaining =
            recipe.ingredients.mapNotNull { ingredient ->
                val deduction = byId[ingredient.id] ?: return@mapNotNull ingredient
                when {
                    deduction.needed == null -> ingredient
                    deduction.presenceLots.isNotEmpty() -> null
                    deduction.missing == null -> null
                    else -> ingredient.copy(quantity = Amount.of(deduction.missing))
                }
            }
        return recipe.copy(servings = plan.servings, ingredients = remaining)
    }

    private fun counts(ingredient: Ingredient): Boolean =
        !ingredient.pantryStaple && !ingredient.optional && ingredient.quantity != null &&
            ingredient.unit.dimension != Dimension.NEGLIGIBLE

    private fun pantryShare(
        plan: CookPlan,
        cost: RecipeCost?,
    ): BigDecimal {
        if (cost == null) return BigDecimal.ZERO
        val byId = plan.ingredients.associateBy { it.ingredient.id }
        var total = BigDecimal.ZERO
        var covered = BigDecimal.ZERO
        for (line in cost.lines) {
            val cents = line.costCents ?: continue
            if (!line.includedInTotals) continue
            total = total.add(cents)
            val deduction = byId[line.ingredient.id] ?: continue
            val needed = deduction.needed ?: continue
            if (needed.signum() <= 0) continue
            val missing = deduction.missing ?: BigDecimal.ZERO
            val share = BigDecimal.ONE.subtract(missing.divide(needed, 9, RoundingMode.HALF_UP)).max(BigDecimal.ZERO)
            covered = covered.add(cents.multiply(share))
        }
        return if (total.signum() == 0) BigDecimal.ZERO else covered.divide(total, 9, RoundingMode.HALF_UP)
    }
}
