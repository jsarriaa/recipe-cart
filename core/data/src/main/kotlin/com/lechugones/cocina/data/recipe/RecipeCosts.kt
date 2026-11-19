package com.lechugones.cocina.data.recipe

import com.lechugones.cocina.data.catalogue.ProductLinker
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.cost.CostOptions
import com.lechugones.cocina.domain.cost.ProductSelection
import com.lechugones.cocina.domain.cost.RecipeCostCalculator
import com.lechugones.cocina.domain.money.Money
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** Cost per serving of every recipe, for "Ordenar por precio" in the list (SPEC § 4.2). Offline. */
class RecipeCosts
    @Inject
    constructor(
        private val repository: RecipeRepository,
        private val linker: ProductLinker,
        private val settings: SettingsRepository,
        private val clock: Clock,
    ) {
        /** Recipe id → cost per serving at its own servings and the primary supermarket; null without any price. */
        suspend fun perServing(): Map<String, Money?> {
            val s = settings.settings.first()
            val recipes = repository.getAll()
            val products = linker.productsFor(recipes.flatMap { r -> r.ingredients.flatMap { it.links } }).mapValues { it.value.product }
            val options =
                CostOptions(
                    Instant.now(clock),
                    s.includeStaplesInCost,
                    s.includeOptionalsInCost,
                    Duration.ofDays(s.staleAfterDays.toLong()),
                )
            val selection = ProductSelection.SingleProvider(s.primaryProvider)
            return recipes.associate { recipe ->
                val cost = RecipeCostCalculator.compute(recipe, recipe.servings, products, selection, options)
                recipe.id to cost.perServing.takeIf { cost.pricedCount > 0 }
            }
        }
    }
