package com.lechugones.cocina.domain.pantry

import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.ServingsScaler
import com.lechugones.cocina.domain.units.Dimension
import com.lechugones.cocina.domain.units.UnitConverter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Ingredient ↔ pantry matching (SPEC § 5.5). */
object PantryMatcher {
    /**
     * A lot matches when its product is one of the ingredient's links, its name key equals the
     * ingredient's, or its product was confirmed before for that name ([remembered]).
     */
    fun matches(
        lot: PantryLot,
        ingredient: Ingredient,
        remembered: Set<ProductKey> = emptySet(),
    ): Boolean {
        val product = lot.product
        return (product != null && (ingredient.links.any { it.product == product } || product in remembered)) ||
            lot.nameKey == ingredient.nameKey
    }
}

/** One planned take from one lot, in the lot's unit. [convertible] is false when units do not match. */
data class LotDeduction(
    val lotId: String,
    val amount: Amount,
    val convertible: Boolean = true,
)

/** What "Lo he cocinado" will take for one ingredient (SPEC § 4.5, step 1). */
data class IngredientDeduction(
    val ingredient: Ingredient,
    /** Scaled amount the recipe needs, in the ingredient's unit; null for pizca/al gusto. */
    val needed: BigDecimal?,
    val takes: List<LotDeduction>,
    /** Amount still missing after the takes, in the ingredient's unit ("faltan 50 g"). */
    val missing: BigDecimal?,
    /** Matching presence-only staples, offered "Se ha acabado". */
    val presenceLots: List<String> = emptyList(),
    /** Matching lots whose unit cannot be converted: listed, need a manual amount. */
    val manualLots: List<String> = emptyList(),
)

data class CookPlan(
    val servings: Int,
    val ingredients: List<IngredientDeduction>,
)

/** The result of applying a plan: the lots to save and the ones that reached zero. */
data class PantryChange(
    val updated: List<PantryLot>,
    val removed: List<String>,
)

/**
 * Plans and applies "Lo he cocinado" deductions (SPEC § 4.5, AC5.1). Pure.
 *
 * Opened lots are consumed first, then the oldest. Several ingredients drawing on the same lot
 * share what is left of it.
 */
object CookPlanner {
    private val EPSILON = BigDecimal("0.0005")

    fun plan(
        recipe: Recipe,
        servings: Int,
        lots: List<PantryLot>,
        remembered: (String) -> Set<ProductKey> = { emptySet() },
    ): CookPlan {
        val left = lots.filter { it.hasStock }.associate { it.id to checkNotNull(it.quantity).toBigDecimal() }.toMutableMap()
        val order = consumptionOrder(lots)
        val ingredients =
            recipe.ingredients.map { ingredient ->
                val matching = order.filter { PantryMatcher.matches(it, ingredient, remembered(ingredient.nameKey)) }
                deduct(ingredient, recipe.servings, servings, matching, left)
            }
        return CookPlan(servings, ingredients)
    }

    /** Opened lots first, then FIFO by added date. */
    fun consumptionOrder(lots: List<PantryLot>): List<PantryLot> =
        lots.sortedWith(compareBy<PantryLot> { !it.opened }.thenBy { it.addedAt })

    private fun deduct(
        ingredient: Ingredient,
        recipeServings: Int,
        servings: Int,
        matching: List<PantryLot>,
        left: MutableMap<String, BigDecimal>,
    ): IngredientDeduction {
        val presence = matching.filter { it.presenceOnly }.map { it.id }
        val needed = ServingsScaler.scale(ingredient.quantity, ingredient.unit, recipeServings, servings)
        if (needed == null || ingredient.unit.dimension == Dimension.NEGLIGIBLE) {
            return IngredientDeduction(ingredient, null, emptyList(), null, presence)
        }
        var remaining: BigDecimal = needed
        val takes = mutableListOf<LotDeduction>()
        val manual = mutableListOf<String>()
        for (lot in matching) {
            if (!lot.hasStock || remaining <= EPSILON) continue
            val lotUnit = checkNotNull(lot.unit)
            // How many lot units one ingredient unit is.
            val factor = UnitConverter.convert(BigDecimal.ONE, ingredient.unit, lotUnit, ingredient.unitWeight)
            val available = left[lot.id] ?: BigDecimal.ZERO
            if (factor == null || factor.signum() <= 0) {
                manual += lot.id
                continue
            }
            if (available <= EPSILON) continue
            val wanted = remaining.multiply(factor)
            val take = wanted.min(available)
            left[lot.id] = available.subtract(take)
            takes += LotDeduction(lot.id, Amount.of(take.setScale(3, RoundingMode.HALF_UP)))
            remaining = remaining.subtract(take.divide(factor, 9, RoundingMode.HALF_UP)).max(BigDecimal.ZERO)
        }
        val missing = remaining.takeIf { it > EPSILON }?.setScale(3, RoundingMode.HALF_UP)
        return IngredientDeduction(ingredient, needed, takes, missing, presence, manual)
    }

    /**
     * Applies the confirmed [takes] (after the user's edits). A sealed lot that is partly used
     * becomes opened now; lots that reach zero are removed. [finished] presence-only lots
     * ("Se ha acabado") are removed too.
     */
    fun apply(
        lots: List<PantryLot>,
        takes: List<LotDeduction>,
        finished: Set<String>,
        now: Instant,
    ): PantryChange {
        val byLot = takes.filter { it.amount.milli > 0 }.groupBy { it.lotId }.mapValues { (_, t) -> t.sumOf { it.amount.milli } }
        val updated = mutableListOf<PantryLot>()
        val removed = mutableListOf<String>()
        for (lot in lots) {
            if (lot.id in finished) {
                removed += lot.id
                continue
            }
            val taken = byLot[lot.id] ?: continue
            val quantity = lot.quantity ?: continue
            val rest = quantity.milli - taken
            if (rest <= 0) {
                removed += lot.id
            } else {
                updated +=
                    lot.copy(
                        quantity = Amount(rest),
                        opened = true,
                        openedAt = if (lot.opened) lot.openedAt else now,
                    )
            }
        }
        return PantryChange(updated, removed)
    }
}
