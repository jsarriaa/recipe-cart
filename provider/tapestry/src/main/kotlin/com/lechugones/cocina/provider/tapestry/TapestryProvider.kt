package com.lechugones.cocina.provider.tapestry

import com.lechugones.cocina.provider.FoodSearches
import com.lechugones.cocina.provider.ProviderCategory
import com.lechugones.cocina.provider.ProviderError
import com.lechugones.cocina.provider.ProviderLocation
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.provider.ProviderResult
import com.lechugones.cocina.provider.RateLimiter
import com.lechugones.cocina.provider.SupermarketProvider
import com.lechugones.cocina.provider.providerCall
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import java.time.Clock
import java.time.Instant

/**
 * Eroski or Caprabo (docs/providers/eroski.md, docs/providers/caprabo.md), ported from
 * supermercapy (third_party/supermercapy, MIT).
 *
 * An anonymous visitor cannot choose a shop, so prices are those of the shop the storefront pins
 * (Eroski: Bilbondo, Basque prices; Caprabo: its online platform, Catalonia). The sync runs the
 * shared [FoodSearches], [PAGES_PER_SEARCH] fragments of about 20 tiles each. Searching an id
 * finds nothing, so price refresh reads the product page.
 */
class TapestryProvider(
    private val chain: TapestryChain,
    private val api: TapestryApi,
    private val limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : SupermarketProvider {
    override val id: String = chain.id
    override val displayName: String = chain.displayName
    override val storeBrands: Set<String> = TapestryMapper.STORE_BRANDS

    override suspend fun resolveLocation(postalCode: String): ProviderResult<ProviderLocation> =
        ProviderResult.Success(ProviderLocation(postalCode, PINNED_SHOP))

    override suspend fun categories(location: ProviderLocation): ProviderResult<List<ProviderCategory>> =
        ProviderResult.Success(FoodSearches.TERMS.map { ProviderCategory(it, it) })

    override suspend fun productsIn(
        category: ProviderCategory,
        location: ProviderLocation,
    ): ProviderResult<List<ProviderProduct>> {
        if (category.id.isBlank()) return ProviderResult.Success(emptyList())
        val found = mutableListOf<ProviderProduct>()
        for (page in 1..PAGES_PER_SEARCH) {
            val fragment =
                when (val result = call { TapestryPages.page(api.search(category.id, page).use { it.string() }) }) {
                    is ProviderResult.Failure -> return result
                    is ProviderResult.Success -> result.value
                }
            if (fragment.challenged) return ProviderResult.Failure(ProviderError.Blocked)
            val now = Instant.now(clock)
            fragment.tiles.mapNotNullTo(found) { TapestryMapper.map(chain, it, now) }
            if (fragment.finished || fragment.tiles.isEmpty()) break
        }
        return ProviderResult.Success(found.distinctBy { it.product.key })
    }

    override suspend fun product(
        productId: String,
        location: ProviderLocation,
    ): ProviderResult<ProviderProduct?> {
        if (!SAFE_ID.matches(productId)) return ProviderResult.Success(null)
        val page =
            call {
                val response = api.product(productId)
                if (!response.isSuccessful) throw HttpException(response)
                // An unknown id redirects to the error page.
                if ("/error/" in
                    response
                        .raw()
                        .request.url.encodedPath
                ) {
                    return@call null
                }
                response.body()?.use { it.string() }
            }
        return when (page) {
            is ProviderResult.Failure -> {
                page
            }

            is ProviderResult.Success -> {
                val html = page.value ?: return ProviderResult.Success(null)
                if (TapestryPages.isChallenge(html)) return ProviderResult.Failure(ProviderError.Blocked)
                val tile = TapestryPages.productPage(html, chain.baseUrl)?.takeIf { it.id == productId }
                val product = tile?.let { TapestryMapper.map(chain, it.copy(url = productUrl(productId)), Instant.now(clock)) }
                ProviderResult.Success(product)
            }
        }
    }

    private fun productUrl(productId: String) = "${chain.baseUrl}es/productdetail/$productId-producto/"

    private suspend fun <T> call(block: suspend () -> T): ProviderResult<T> = providerCall(limiter, sleep = sleep, block = block)

    companion object {
        /** The shop the storefront pins an anonymous visitor to. */
        const val PINNED_SHOP = "pinned"

        /** Two fragments per search: about 40 products. */
        const val PAGES_PER_SEARCH = 2

        /** Numeric; marketplace ids (`MP…`) are never stored. */
        private val SAFE_ID = Regex("^[0-9]{1,12}$")

        fun create(
            chain: TapestryChain,
            client: OkHttpClient,
            clock: Clock = Clock.systemUTC(),
            baseUrl: String = chain.baseUrl,
            limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
            sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        ): TapestryProvider {
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .build()
            return TapestryProvider(chain, retrofit.create(TapestryApi::class.java), limiter, clock, sleep)
        }
    }
}
