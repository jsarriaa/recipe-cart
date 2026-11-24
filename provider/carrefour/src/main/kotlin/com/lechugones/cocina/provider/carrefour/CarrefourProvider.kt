package com.lechugones.cocina.provider.carrefour

import com.lechugones.cocina.provider.FoodSearches
import com.lechugones.cocina.provider.ProviderCategory
import com.lechugones.cocina.provider.ProviderLocation
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.provider.ProviderResult
import com.lechugones.cocina.provider.RateLimiter
import com.lechugones.cocina.provider.SupermarketProvider
import com.lechugones.cocina.provider.providerCall
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.util.Locale

/**
 * Carrefour provider (docs/providers/carrefour.md), ported from supermercapy
 * (third_party/supermercapy, MIT).
 *
 * Only the public search index is used: carrefour.es itself lets only browsers through, and we
 * never pretend to be one (CLAUDE.md rule 3). So there is no sale point, and prices are
 * Carrefour's national online prices. The catalogue sync runs the shared [FoodSearches], one
 * request each.
 */
class CarrefourProvider(
    private val api: CarrefourApi,
    private val limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : SupermarketProvider {
    override val id: String = CarrefourMapper.PROVIDER_ID
    override val displayName: String = "Carrefour"
    override val storeBrands: Set<String> = CarrefourMapper.STORE_BRANDS

    /** National prices: the postal code changes nothing, and no request is made. */
    override suspend fun resolveLocation(postalCode: String): ProviderResult<ProviderLocation> =
        ProviderResult.Success(ProviderLocation(postalCode, NATIONAL))

    override suspend fun categories(location: ProviderLocation): ProviderResult<List<ProviderCategory>> =
        ProviderResult.Success(FoodSearches.TERMS.map { ProviderCategory(it, it) })

    override suspend fun productsIn(
        category: ProviderCategory,
        location: ProviderLocation,
    ): ProviderResult<List<ProviderProduct>> =
        when (val result = call { api.search(category.id.lowercase(Locale.ROOT), ROWS_PER_SEARCH) }) {
            is ProviderResult.Failure -> {
                result
            }

            is ProviderResult.Success -> {
                val now = Instant.now(clock)
                val products =
                    result.value.catalog
                        ?.content
                        .orEmpty()
                        .mapNotNull { CarrefourMapper.map(it, now) }
                ProviderResult.Success(products.distinctBy { it.product.key })
            }
        }

    /** The index finds a product by its id as free text; the exact id is then picked out. */
    override suspend fun product(
        productId: String,
        location: ProviderLocation,
    ): ProviderResult<ProviderProduct?> =
        when (val result = call { api.search(productId.lowercase(Locale.ROOT), ROWS_PER_LOOKUP) }) {
            is ProviderResult.Failure -> {
                result
            }

            is ProviderResult.Success -> {
                val doc =
                    result.value.catalog
                        ?.content
                        .orEmpty()
                        .firstOrNull { it.id == productId }
                ProviderResult.Success(doc?.let { CarrefourMapper.map(it, Instant.now(clock)) })
            }
        }

    private suspend fun <T> call(block: suspend () -> T): ProviderResult<T> = providerCall(limiter, sleep = sleep, block = block)

    companion object {
        const val BASE_URL = "https://api.empathy.co/search/v1/query/carrefour/"
        const val NATIONAL = "national"

        /** One page per search: the most relevant 120 products. */
        const val ROWS_PER_SEARCH = 120
        const val ROWS_PER_LOOKUP = 5

        private val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                coerceInputValues = true
            }

        fun create(
            client: OkHttpClient,
            clock: Clock = Clock.systemUTC(),
            baseUrl: String = BASE_URL,
            limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
            sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        ): CarrefourProvider {
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
            return CarrefourProvider(retrofit.create(CarrefourApi::class.java), limiter, clock, sleep)
        }
    }
}
