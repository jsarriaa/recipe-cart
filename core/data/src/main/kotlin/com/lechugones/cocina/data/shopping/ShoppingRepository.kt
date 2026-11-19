package com.lechugones.cocina.data.shopping

import com.lechugones.cocina.data.catalogue.ProductLinker
import com.lechugones.cocina.data.db.ShoppingCheckEntity
import com.lechugones.cocina.data.db.ShoppingDao
import com.lechugones.cocina.data.db.ShoppingManualEntity
import com.lechugones.cocina.data.db.ShoppingOverrideEntity
import com.lechugones.cocina.data.db.ShoppingRecipeEntity
import com.lechugones.cocina.data.pantry.PantryRepository
import com.lechugones.cocina.data.recipe.RecipeRepository
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.pantry.PantryLot
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.settings.AppSettings
import com.lechugones.cocina.domain.shopping.ManualShoppingItem
import com.lechugones.cocina.domain.shopping.ShoppingContribution
import com.lechugones.cocina.domain.shopping.ShoppingItem
import com.lechugones.cocina.domain.shopping.ShoppingList
import com.lechugones.cocina.domain.shopping.ShoppingListBuilder
import com.lechugones.cocina.domain.shopping.ShoppingMode
import com.lechugones.cocina.domain.shopping.ShoppingOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The shopping list with what it was built from, for the Compra tab. */
data class ShoppingState(
    val list: ShoppingList,
    /** Recipes added to the list, for "Quitar de la lista". */
    val contributions: List<ShoppingContribution>,
    val mode: ShoppingMode,
)

/**
 * The shopping list (SPEC § 4.6). Only its inputs are stored; the list is rebuilt from recipes,
 * manual items and the pantry every time they change, so it never goes stale.
 */
@Singleton
class ShoppingRepository
    @Inject
    constructor(
        private val dao: ShoppingDao,
        private val recipes: RecipeRepository,
        private val pantry: PantryRepository,
        private val linker: ProductLinker,
        private val settings: SettingsRepository,
        private val clock: Clock,
    ) {
        private data class Inputs(
            val recipes: List<ShoppingRecipeEntity>,
            val manual: List<ShoppingManualEntity>,
            val checks: Set<String>,
            val overrides: Map<String, String>,
        )

        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<ShoppingState> =
            combine(
                combine(dao.observeRecipes(), dao.observeManual(), dao.observeChecks(), dao.observeOverrides()) { r, m, c, o ->
                    Inputs(r, m, c.toSet(), o.associate { it.nameKey to it.providerId })
                },
                pantry.observeLots(),
                settings.settings,
            ) { inputs, lots, s -> Triple(inputs, lots, s) }
                .mapLatest { (inputs, lots, s) -> build(inputs, lots, s) }

        private suspend fun build(
            inputs: Inputs,
            lots: List<PantryLot>,
            s: AppSettings,
        ): ShoppingState {
            val contributions =
                inputs.recipes.mapNotNull { row -> recipes.get(row.recipeId)?.let { ShoppingContribution(row.id, it, row.servings) } }
            val manual =
                inputs.manual.map { row ->
                    val product = if (row.providerId != null && row.productId != null) ProductKey(row.providerId, row.productId) else null
                    ManualShoppingItem(row.id, row.name, product, row.packages)
                }
            val links =
                contributions.flatMap { c -> c.recipe.ingredients.flatMap { it.links } } +
                    manual.mapNotNull { it.product }.map { IngredientLink(it) }
            val products = linker.productsFor(links).mapValues { it.value.product }
            val remembered = linker.rememberedProducts(contributions.flatMap { c -> c.recipe.ingredients.map { it.nameKey } })
            val mode = if (s.shoppingCheapest) ShoppingMode.CHEAPEST else ShoppingMode.PRIMARY
            val options =
                ShoppingOptions(
                    now = Instant.now(clock),
                    primaryProvider = s.primaryProvider,
                    mode = mode,
                    addMissingStaples = s.addMissingStaplesToList,
                    staleAfter = Duration.ofDays(s.staleAfterDays.toLong()),
                    overrides = inputs.overrides,
                    checked = inputs.checks,
                )
            val list = ShoppingListBuilder.build(contributions, manual, lots, products, options) { remembered[it].orEmpty() }
            return ShoppingState(list, contributions, mode)
        }

        /** "Añadir a la lista" from a recipe; adding it again adds its needs again. */
        suspend fun addRecipe(
            recipeId: String,
            servings: Int,
        ) = dao.upsertRecipe(ShoppingRecipeEntity(newId(), recipeId, servings, clock.millis()))

        suspend fun addManual(
            name: String,
            product: ProductKey? = null,
            packages: Int = 1,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty() && product == null) return
            dao.upsertManual(
                ShoppingManualEntity(newId(), trimmed, product?.providerId, product?.productId, packages.coerceAtLeast(1), clock.millis()),
            )
        }

        suspend fun removeContribution(id: String) = dao.deleteRecipe(id)

        suspend fun removeManual(id: String) = dao.deleteManual(listOf(id))

        suspend fun setChecked(
            itemKey: String,
            checked: Boolean,
        ) = if (checked) dao.check(ShoppingCheckEntity(itemKey)) else dao.uncheck(itemKey)

        /** Buy an item at another supermarket from now on; null goes back to the list's mode. */
        suspend fun setOverride(
            nameKey: String,
            providerId: String?,
        ) = if (providerId == null) dao.clearOverride(nameKey) else dao.setOverride(ShoppingOverrideEntity(nameKey, providerId))

        suspend fun setCheapest(cheapest: Boolean) = settings.update { it.copy(shoppingCheapest = cheapest) }

        suspend fun clear() = dao.clearAll()

        /**
         * "Terminar compra": every checked item becomes sealed pantry lots (one per package), checked
         * manual items are removed, and recipes whose needs are now all covered leave the list.
         * Returns how many lots were added.
         */
        suspend fun finishShopping(): Int {
            val state = observe().first()
            val checked = state.list.items.filter { it.checked }
            if (checked.isEmpty()) return 0
            var added = 0
            for (item in checked) {
                for (lot in lotsFor(item)) {
                    pantry.save(lot)
                    added++
                }
            }
            dao.deleteManual(checked.mapNotNull { it.manualId })
            dao.clearChecks()
            val after = observe().first()
            val stillNeeded =
                after.list.items
                    .flatMap { it.sources }
                    .map { it.contributionId }
                    .toSet()
            state.contributions.filter { it.id !in stillNeeded }.forEach { dao.deleteRecipe(it.id) }
            return added
        }

        private fun lotsFor(item: ShoppingItem): List<PantryLot> {
            val now = Instant.now(clock)
            val product = item.product
            if (product != null) return List(item.packages ?: 1) { pantry.newLotFor(product) }
            val needed = item.needed
            val unit = item.unit
            return listOf(
                if (needed != null && unit != null) {
                    PantryLot(newId(), item.name, now, quantity = Amount.of(needed), unit = unit)
                } else {
                    PantryLot(newId(), item.name, now, presenceOnly = true)
                },
            )
        }

        private fun newId(): String = UUID.randomUUID().toString()
    }
