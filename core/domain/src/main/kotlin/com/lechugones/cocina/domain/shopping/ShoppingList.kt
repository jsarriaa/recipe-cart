package com.lechugones.cocina.domain.shopping

import com.lechugones.cocina.domain.cost.PackageUsageResolver
import com.lechugones.cocina.domain.cost.RecipeCostCalculator
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.pantry.PantryLot
import com.lechugones.cocina.domain.pantry.PantryMatcher
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.NameKey
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.recipe.ServingsScaler
import com.lechugones.cocina.domain.units.Dimension
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.domain.units.UnitConverter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/** A recipe added to the list with "Añadir a la lista" (SPEC § 4.6). */
data class ShoppingContribution(
    val id: String,
    val recipe: Recipe,
    val servings: Int,
)

/** A free-text item, or a catalogue product, added by hand. */
data class ManualShoppingItem(
    val id: String,
    val name: String,
    val product: ProductKey? = null,
    val packages: Int = 1,
)

/** How items pick their supermarket: the primary one, or the cheapest per product. */
enum class ShoppingMode { PRIMARY, CHEAPEST }

/** One recipe's share of an item ("para Tacos, 2 raciones"). */
data class ItemSource(
    val recipeTitle: String,
    val servings: Int,
    /** The [ShoppingContribution.id] this need comes from. */
    val contributionId: String = "",
)

/** One line of the list. [key] is stable across rebuilds: it carries the check state. */
data class ShoppingItem(
    val key: String,
    val name: String,
    /** Null for generic items (no linked product). */
    val product: Product?,
    /** Whole packages to buy; null for generic items. */
    val packages: Int?,
    /** What is still needed after the pantry, in [unit]; null when there is no amount ("al gusto"). */
    val needed: BigDecimal?,
    val unit: MeasureUnit?,
    val cost: Money?,
    val approximate: Boolean,
    val sources: List<ItemSource>,
    val manualId: String? = null,
    val checked: Boolean = false,
    /** Name key used to store a supermarket chosen by hand for this item; null for manual items. */
    val overrideKey: String? = null,
    /** Providers this item could be bought at (its ingredients' links). */
    val providerOptions: Set<String> = emptySet(),
) {
    val providerId: String? get() = product?.key?.providerId
}

/** Items of one supermarket; [providerId] null is "Sin supermercado". */
data class ShoppingGroup(
    val providerId: String?,
    val items: List<ShoppingItem>,
    val subtotal: Money,
    val approximate: Boolean,
    val missingPrices: Int,
)

data class ShoppingList(
    val groups: List<ShoppingGroup>,
    val total: Money,
    val approximate: Boolean,
    /** Items without a price, counted as "sin precio". */
    val missingPrices: Int,
) {
    val items: List<ShoppingItem> get() = groups.flatMap { it.items }
    val isEmpty: Boolean get() = groups.isEmpty()
}

data class ShoppingOptions(
    val now: Instant,
    val primaryProvider: String,
    val mode: ShoppingMode = ShoppingMode.PRIMARY,
    /** "Añadir básicos que me faltan". */
    val addMissingStaples: Boolean = true,
    val staleAfter: Duration = Duration.ofDays(14),
    /** Ingredient name key → provider chosen by hand for it ([ShoppingItem.overrideKey]). */
    val overrides: Map<String, String> = emptyMap(),
    val checked: Set<String> = emptySet(),
)

/**
 * Builds the shopping list (SPEC § 4.6, AC6.1). Pure: needs are consolidated per product (or per
 * name when unlinked), the pantry is subtracted, and the rest is rounded up to whole packages.
 */
object ShoppingListBuilder {
    private val EPSILON = BigDecimal("0.0005")

    fun build(
        contributions: List<ShoppingContribution>,
        manual: List<ManualShoppingItem>,
        lots: List<PantryLot>,
        products: Map<ProductKey, Product>,
        options: ShoppingOptions,
        remembered: (String) -> Set<ProductKey> = { emptySet() },
    ): ShoppingList {
        val byProduct = LinkedHashMap<ProductKey, ProductNeed>()
        val generic = LinkedHashMap<String, GenericNeed>()

        for (contribution in contributions) {
            val recipe = contribution.recipe
            for (ingredient in recipe.ingredients) {
                if (ingredient.optional) continue
                val matchingLots = lots.filter { PantryMatcher.matches(it, ingredient, remembered(ingredient.nameKey)) }
                if (ingredient.pantryStaple && !stapleMissing(matchingLots, lots, options)) continue
                val source = ItemSource(recipe.title, contribution.servings, contribution.id)
                val scaled = ServingsScaler.scale(ingredient.quantity, ingredient.unit, recipe.servings, contribution.servings)
                val product = chooseProduct(ingredient, scaled, products, options)
                val usage =
                    if (product != null && scaled != null && ingredient.unit.dimension != Dimension.NEGLIGIBLE) {
                        PackageUsageResolver.resolve(scaled, ingredient.unit, ingredient.unitWeight, product)
                    } else {
                        null
                    }
                if (product != null && (usage != null || scaled == null || ingredient.unit.dimension == Dimension.NEGLIGIBLE)) {
                    val need = byProduct.getOrPut(product.key) { ProductNeed(product, ingredient.nameKey) }
                    need.sources += source
                    need.lots += matchingLots
                    need.providers += ingredient.links.map { it.product.providerId }
                    if (usage != null) need.used = need.used.add(usage.used)
                } else {
                    val key = ingredient.nameKey
                    val need = generic.getOrPut(key) { GenericNeed(ingredient.name) }
                    need.sources += source
                    need.lots += matchingLots
                    need.add(scaled, ingredient)
                }
            }
        }

        val items = mutableListOf<ShoppingItem>()
        for ((key, need) in byProduct) items += productItem(key, need, options) ?: continue
        for ((key, need) in generic) items += genericItem(key, need) ?: continue
        for (item in manual) items += manualItem(item, products, options)

        val withChecks = items.map { it.copy(checked = it.key in options.checked) }
        val groups =
            withChecks
                .groupBy { it.providerId }
                .entries
                .sortedWith(compareBy({ it.key == null }, { it.key != options.primaryProvider }, { it.key }))
                .map { (providerId, groupItems) ->
                    ShoppingGroup(
                        providerId = providerId,
                        items = groupItems,
                        subtotal = groupItems.mapNotNull { it.cost }.fold(Money.ZERO, Money::plus),
                        approximate = groupItems.any { it.approximate },
                        missingPrices = groupItems.count { it.product != null && it.cost == null },
                    )
                }
        return ShoppingList(
            groups = groups,
            total = groups.fold(Money.ZERO) { sum, group -> sum + group.subtotal },
            approximate = groups.any { it.approximate },
            missingPrices = withChecks.count { it.cost == null },
        )
    }

    /** Item key of a product, or of an unlinked name. */
    fun productKey(key: ProductKey): String = "p:${key.providerId}:${key.productId}"

    fun nameKey(name: String): String = "n:" + NameKey.of(name)

    /**
     * Staples go on the list only when the setting is on, the pantry is in use, and no lot of
     * that staple is left (presence-only staples are removed with "Se ha acabado").
     */
    private fun stapleMissing(
        matchingLots: List<PantryLot>,
        allLots: List<PantryLot>,
        options: ShoppingOptions,
    ): Boolean = options.addMissingStaples && allLots.isNotEmpty() && matchingLots.isEmpty()

    /** The product for an ingredient: an override, the primary provider's link, or the cheapest one. */
    private fun chooseProduct(
        ingredient: Ingredient,
        scaled: BigDecimal?,
        products: Map<ProductKey, Product>,
        options: ShoppingOptions,
    ): Product? {
        val linked = ingredient.links.sortedByDescending { it.preferred }.mapNotNull { products[it.product] }
        if (linked.isEmpty()) return null
        options.overrides[ingredient.nameKey]?.let { provider -> linked.firstOrNull { it.key.providerId == provider }?.let { return it } }
        return when (options.mode) {
            ShoppingMode.PRIMARY -> {
                linked.firstOrNull { it.key.providerId == options.primaryProvider } ?: linked.first()
            }

            ShoppingMode.CHEAPEST -> {
                linked
                    .filter { it.price != null }
                    .minByOrNull { product ->
                        val usage = scaled?.let { PackageUsageResolver.resolve(it, ingredient.unit, ingredient.unitWeight, product) }
                        val packages = usage?.let { RecipeCostCalculator.packagesFor(it.ratio) } ?: 1
                        checkNotNull(product.price).cents * packages
                    } ?: linked.first()
            }
        }
    }

    private fun productItem(
        key: ProductKey,
        need: ProductNeed,
        options: ShoppingOptions,
    ): ShoppingItem? {
        val product = need.product
        val measure = product.measures().first()
        val stock = stockIn(need.lots, measure.unit, product)
        val packages: Int
        val needed: BigDecimal?
        if (need.used.signum() > 0) {
            val rest = need.used.subtract(stock)
            if (rest <= EPSILON) return null
            packages = RecipeCostCalculator.packagesFor(rest.divide(measure.usable, 9, RoundingMode.HALF_UP))
            needed = rest.setScale(3, RoundingMode.HALF_UP)
        } else {
            // Only "al gusto"/pizca uses: one package, unless the pantry already has some.
            if (need.lots.isNotEmpty()) return null
            packages = 1
            needed = null
        }
        val stale = product.fetchedAt?.let { Duration.between(it, options.now) > options.staleAfter } == true
        return ShoppingItem(
            key = productKey(key),
            name = product.name,
            product = product,
            packages = packages,
            needed = needed,
            unit = measure.unit,
            cost = product.price?.times(packages),
            approximate = product.approx || stale,
            sources = need.sources.distinct(),
            overrideKey = need.overrideKey,
            providerOptions = need.providers,
        )
    }

    private fun genericItem(
        key: String,
        need: GenericNeed,
    ): ShoppingItem? {
        val unit = need.unit
        val amount = need.amount
        if (unit != null && amount != null) {
            val stock = stockIn(need.lots, unit, null)
            val rest = amount.subtract(stock)
            if (rest <= EPSILON) return null
            return ShoppingItem(
                "n:$key",
                need.name,
                null,
                null,
                rest.setScale(3, RoundingMode.HALF_UP),
                unit,
                null,
                false,
                need.sources.distinct(),
            )
        }
        if (need.lots.isNotEmpty()) return null
        return ShoppingItem("n:$key", need.name, null, null, null, null, null, false, need.sources.distinct())
    }

    private fun manualItem(
        item: ManualShoppingItem,
        products: Map<ProductKey, Product>,
        options: ShoppingOptions,
    ): ShoppingItem {
        val product = item.product?.let { products[it] }
        val stale = product?.fetchedAt?.let { Duration.between(it, options.now) > options.staleAfter } == true
        return ShoppingItem(
            key = "m:${item.id}",
            name = product?.name ?: item.name,
            product = product,
            packages = item.packages.takeIf { product != null },
            needed = null,
            unit = null,
            cost = product?.price?.times(item.packages),
            approximate = product != null && (product.approx || stale),
            sources = emptyList(),
            manualId = item.id,
        )
    }

    /** Pantry stock of matching lots in [unit] (opened lots included); lots in other units are ignored. */
    private fun stockIn(
        lots: List<PantryLot>,
        unit: MeasureUnit,
        product: Product?,
    ): BigDecimal =
        lots
            .distinctBy { it.id }
            .filter { it.hasStock }
            .fold(BigDecimal.ZERO) { sum, lot ->
                val lotUnit = checkNotNull(lot.unit)
                val amount = checkNotNull(lot.quantity).toBigDecimal()
                val converted =
                    UnitConverter.convert(amount, lotUnit, unit)
                        ?: product?.let {
                            PackageUsageResolver
                                .resolve(amount, lotUnit, null, it)
                                ?.takeIf { u ->
                                    u.measure.unit == unit
                                }?.used
                        }
                sum.add(converted ?: BigDecimal.ZERO)
            }

    private class ProductNeed(
        val product: Product,
        val overrideKey: String,
    ) {
        var used: BigDecimal = BigDecimal.ZERO
        val providers = mutableSetOf<String>()
        val sources = mutableListOf<ItemSource>()
        val lots = mutableListOf<PantryLot>()
    }

    private class GenericNeed(
        val name: String,
    ) {
        var unit: MeasureUnit? = null
        var amount: BigDecimal? = null
        var incompatible = false
        val sources = mutableListOf<ItemSource>()
        val lots = mutableListOf<PantryLot>()

        /** Adds an amount in the package base unit of its dimension; mixed dimensions drop the amount. */
        fun add(
            scaled: BigDecimal?,
            ingredient: Ingredient,
        ) {
            if (scaled == null || ingredient.unit.dimension == Dimension.NEGLIGIBLE || incompatible) return
            val base = UnitConverter.packageBaseUnit(ingredient.unit)
            val converted = UnitConverter.convert(scaled, ingredient.unit, base, ingredient.unitWeight)
            if (converted == null || (unit != null && unit != base)) {
                incompatible = true
                unit = null
                amount = null
                return
            }
            unit = base
            amount = (amount ?: BigDecimal.ZERO).add(converted)
        }
    }
}
