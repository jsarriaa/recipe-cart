package com.lechugones.cocina.provider.bonarea

import com.lechugones.cocina.provider.FoodSearches
import com.lechugones.cocina.provider.MemoryCookieJar
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

/**
 * bonÀrea provider (docs/providers/bonarea.md), ported from supermercapy
 * (third_party/supermercapy, MIT). One national catalogue and national prices, so nothing binds
 * to the postal code. The sync runs the shared [FoodSearches]; each search answers every hit at
 * once. Search reads the language from the session, so the Spanish home page is fetched once per
 * session first; the cookie stays in memory.
 */
class BonareaProvider(
    private val api: BonareaApi,
    private val limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : SupermarketProvider {
    override val id: String = BonareaMapper.PROVIDER_ID
    override val displayName: String = "bonÀrea"
    override val storeBrands: Set<String> = BonareaMapper.STORE_BRANDS

    @Volatile private var warm = false

    override suspend fun resolveLocation(postalCode: String): ProviderResult<ProviderLocation> =
        ProviderResult.Success(ProviderLocation(postalCode, NATIONAL))

    override suspend fun categories(location: ProviderLocation): ProviderResult<List<ProviderCategory>> =
        ProviderResult.Success(FoodSearches.TERMS.map { ProviderCategory(it, it) })

    override suspend fun productsIn(
        category: ProviderCategory,
        location: ProviderLocation,
    ): ProviderResult<List<ProviderProduct>> {
        if (!warm) {
            when (val result = call { api.home().close() }) {
                is ProviderResult.Failure -> return result
                is ProviderResult.Success -> warm = true
            }
        }
        return when (val result = call { api.search(category.id) }) {
            is ProviderResult.Failure -> {
                result
            }

            is ProviderResult.Success -> {
                val now = Instant.now(clock)
                val products = result.value.articles.mapNotNull { BonareaMapper.map(it, now) }
                ProviderResult.Success(products.distinctBy { it.product.key })
            }
        }
    }

    /** Product details honour the language in the path: no warm-up needed. */
    override suspend fun product(
        productId: String,
        location: ProviderLocation,
    ): ProviderResult<ProviderProduct?> =
        when (val result = call { api.article(productId.replace('_', '*')) }) {
            is ProviderResult.Failure -> result
            is ProviderResult.Success -> ProviderResult.Success(result.value.article?.let { BonareaMapper.map(it, Instant.now(clock)) })
        }

    private suspend fun <T> call(block: suspend () -> T): ProviderResult<T> = providerCall(limiter, sleep = sleep, block = block)

    companion object {
        const val BASE_URL = "https://www.bonarea-online.com/"
        const val NATIONAL = "national"

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
        ): BonareaProvider {
            val withSession = client.newBuilder().cookieJar(MemoryCookieJar()).build()
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(baseUrl)
                    .client(withSession)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
            return BonareaProvider(retrofit.create(BonareaApi::class.java), limiter, clock, sleep)
        }
    }
}
