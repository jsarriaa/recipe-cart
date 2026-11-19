package com.lechugones.cocina.data.catalogue

import com.lechugones.cocina.data.db.LinkMemoryDao
import com.lechugones.cocina.data.db.LinkMemoryEntity
import com.lechugones.cocina.data.db.ProductDao
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.product.ManualProducts
import com.lechugones.cocina.domain.product.MatchResult
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.product.ProductMatcher
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.provider.ProviderProduct
import kotlinx.coroutines.flow.first
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Suggestions for one ingredient at one provider, with the cached products they refer to. */
data class IngredientMatch(
    val providerId: String,
    val result: MatchResult,
    /** Display data (image, price, path) for every suggested product. */
    val products: Map<ProductKey, ProviderProduct>,
)

/**
 * Links ingredients to cached products (SPEC § 4.2 "Linking", § 5.4). Offline: it reads only
 * the local catalogue and link memory.
 */
@Singleton
class ProductLinker
    @Inject
    constructor(
        private val products: ProductDao,
        private val memory: LinkMemoryDao,
        private val settings: SettingsRepository,
        private val clock: Clock,
    ) {
        /** Active providers, primary first, then the built-in manual provider. */
        suspend fun activeProviders(): List<String> {
            val s = settings.settings.first()
            return (listOf(s.primaryProvider) + s.activeProviders.sorted() + ManualProducts.PROVIDER_ID).distinct()
        }

        /** Suggestions for [ingredient] at every active provider. [fileLinks] come from an imported pack. */
        suspend fun match(
            ingredient: Ingredient,
            fileLinks: List<ProductKey> = ingredient.links.map { it.product },
        ): List<IngredientMatch> {
            val storeBrandOnly = settings.settings.first().storeBrandOnly
            val remembered = memory.forName(ingredient.nameKey)
            val query = ProductMatcher.query(ingredient.name, ingredient.match)
            return activeProviders().map { providerId ->
                val file = load(providerId, fileLinks.filter { it.providerId == providerId }.map { it.productId })
                val known = load(providerId, remembered.filter { it.providerId == providerId }.map { it.productId })
                val search =
                    ProductMatcher
                        .searchTerms(query)
                        .take(MAX_TERMS)
                        .flatMap { products.candidates(providerId, it, CANDIDATES_PER_TERM) }
                        .distinctBy { it.productId }
                        .mapNotNull { it.toProviderProduct() }
                val all = (file + known + search).associateBy { it.product.key }
                val result =
                    ProductMatcher.match(
                        name = ingredient.name,
                        hints = ingredient.match,
                        fileLinks = file.map { it.product },
                        remembered = known.map { it.product },
                        searchResults = search.map { it.product },
                        storeBrandOnly = storeBrandOnly,
                    )
                IngredientMatch(providerId, result, all.filterKeys { key -> result.suggestions.any { it.product.key == key } })
            }
        }

        /** Cached display data for the links of some ingredients (missing products are left out). */
        suspend fun productsFor(links: Collection<IngredientLink>): Map<ProductKey, ProviderProduct> =
            links
                .map { it.product }
                .distinct()
                .groupBy { it.providerId }
                .flatMap { (providerId, keys) -> load(providerId, keys.map { it.productId }) }
                .associateBy { it.product.key }

        /** Products confirmed before for each of [nameKeys] (SPEC § 5.5: they also match pantry lots). */
        suspend fun rememberedProducts(nameKeys: Collection<String>): Map<String, Set<ProductKey>> =
            nameKeys.distinct().associateWith { key -> memory.forName(key).map { ProductKey(it.providerId, it.productId) }.toSet() }

        /** Records confirmed links so the same ingredient name suggests them first next time. */
        suspend fun remember(
            nameKey: String,
            links: List<IngredientLink>,
        ) {
            if (nameKey.isBlank()) return
            val now = clock.millis()
            for (link in links) {
                val key = link.product
                val previous = memory.get(nameKey, key.providerId, key.productId)
                memory.upsert(LinkMemoryEntity(nameKey, key.providerId, key.productId, now, (previous?.useCount ?: 0) + 1))
            }
        }

        private suspend fun load(
            providerId: String,
            ids: List<String>,
        ): List<ProviderProduct> {
            if (ids.isEmpty()) return emptyList()
            val rows = products.getMany(providerId, ids.distinct()).associateBy { it.productId }
            return ids.distinct().mapNotNull { rows[it]?.toProviderProduct() }
        }

        private companion object {
            const val MAX_TERMS = 4
            const val CANDIDATES_PER_TERM = 150
        }
    }
