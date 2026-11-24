package com.lechugones.cocina.provider.ahorramas

import com.lechugones.cocina.provider.FoodSearches
import com.lechugones.cocina.provider.ProviderCategory
import com.lechugones.cocina.provider.ProviderLocation
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.provider.ProviderResult
import com.lechugones.cocina.provider.RateLimiter
import com.lechugones.cocina.provider.SupermarketProvider
import com.lechugones.cocina.provider.providerCall
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.time.Clock
import java.time.Instant

/**
 * Ahorramás provider (docs/providers/ahorramas.md), ported from supermercapy
 * (third_party/supermercapy, MIT). A Madrid-area chain with one price list, so nothing binds to
 * the postal code. The sync runs the shared [FoodSearches] on the product grid, one page of
 * [TILES_PER_SEARCH] tiles each (about 11 kB of HTML per tile). Price refresh searches the grid
 * for the product id, because the product page is excluded by robots.txt.
 */
class AhorramasProvider(
    private val api: AhorramasApi,
    private val limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : SupermarketProvider {
    override val id: String = AhorramasMapper.PROVIDER_ID
    override val displayName: String = "Ahorramás"
    override val storeBrands: Set<String> = AhorramasMapper.STORE_BRANDS

    override suspend fun resolveLocation(postalCode: String): ProviderResult<ProviderLocation> =
        ProviderResult.Success(ProviderLocation(postalCode, SINGLE_PRICE_LIST))

    override suspend fun categories(location: ProviderLocation): ProviderResult<List<ProviderCategory>> =
        ProviderResult.Success(FoodSearches.TERMS.map { ProviderCategory(it, it) })

    override suspend fun productsIn(
        category: ProviderCategory,
        location: ProviderLocation,
    ): ProviderResult<List<ProviderProduct>> =
        when (val result = grid(category.id, TILES_PER_SEARCH)) {
            is ProviderResult.Failure -> {
                result
            }

            is ProviderResult.Success -> {
                val now = Instant.now(clock)
                ProviderResult.Success(result.value.mapNotNull { AhorramasMapper.map(it, now) }.distinctBy { it.product.key })
            }
        }

    /** The grid finds a product by its id; the exact id is then picked out. */
    override suspend fun product(
        productId: String,
        location: ProviderLocation,
    ): ProviderResult<ProviderProduct?> {
        if (!SAFE_ID.matches(productId)) return ProviderResult.Success(null)
        return when (val result = grid(productId, TILES_PER_LOOKUP)) {
            is ProviderResult.Failure -> {
                result
            }

            is ProviderResult.Success -> {
                val tile = result.value.firstOrNull { it.id == productId }
                ProviderResult.Success(tile?.let { AhorramasMapper.map(it, Instant.now(clock)) })
            }
        }
    }

    private suspend fun grid(
        query: String,
        size: Int,
    ): ProviderResult<List<AhorramasTile>> =
        providerCall(limiter, sleep = sleep) {
            AhorramasGrid.tiles(api.grid(query, size).use { it.string() })
        }

    companion object {
        const val BASE_URL = "https://www.ahorramas.com/"
        const val SINGLE_PRICE_LIST = "ahorramas"
        const val TILES_PER_SEARCH = 40
        const val TILES_PER_LOOKUP = 5
        private val SAFE_ID = Regex("^[0-9]{1,12}$")

        fun create(
            client: OkHttpClient,
            clock: Clock = Clock.systemUTC(),
            baseUrl: String = BASE_URL,
            limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
            sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        ): AhorramasProvider {
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .build()
            return AhorramasProvider(retrofit.create(AhorramasApi::class.java), limiter, clock, sleep)
        }
    }
}
