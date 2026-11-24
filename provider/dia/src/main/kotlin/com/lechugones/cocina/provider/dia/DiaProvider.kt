package com.lechugones.cocina.provider.dia

import com.lechugones.cocina.provider.FoodSearches
import com.lechugones.cocina.provider.ProviderCategory
import com.lechugones.cocina.provider.ProviderError
import com.lechugones.cocina.provider.ProviderLocation
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.provider.ProviderResult
import com.lechugones.cocina.provider.RateLimiter
import com.lechugones.cocina.provider.SupermarketProvider
import com.lechugones.cocina.provider.providerCall
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant

/**
 * Día provider (docs/providers/dia.md). Día publishes no category endpoint we could verify, so
 * the catalogue sync runs a fixed list of food searches ([SEARCHES]), each read for at most
 * [MAX_PAGES] pages of 30. The postal code lives in a session cookie, kept in memory only.
 */
class DiaProvider(
    private val api: DiaApi,
    private val limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : SupermarketProvider {
    override val id: String = DiaMapper.PROVIDER_ID
    override val displayName: String = "Día"
    override val storeBrands: Set<String> = DiaMapper.STORE_BRANDS

    /** Postal code the current session cookie was set for. */
    @Volatile private var sessionPostalCode: String? = null

    override suspend fun resolveLocation(postalCode: String): ProviderResult<ProviderLocation> =
        when (val result = setPostalCode(postalCode)) {
            is ProviderResult.Failure -> result
            is ProviderResult.Success -> ProviderResult.Success(ProviderLocation(postalCode, postalCode))
        }

    override suspend fun categories(location: ProviderLocation): ProviderResult<List<ProviderCategory>> {
        // A sync starts here: make sure the session cookie is set for this postal code.
        (setPostalCode(location.postalCode) as? ProviderResult.Failure)?.let { return it }
        return ProviderResult.Success(SEARCHES.map { ProviderCategory(it, it) })
    }

    override suspend fun productsIn(
        category: ProviderCategory,
        location: ProviderLocation,
    ): ProviderResult<List<ProviderProduct>> {
        ensureSession(location)?.let { return it }
        val products = mutableListOf<ProviderProduct>()
        var page = 1
        while (page <= MAX_PAGES) {
            val response =
                when (val result = call { api.search(category.id, page) }) {
                    is ProviderResult.Failure -> return if (products.isEmpty()) result else ProviderResult.Success(products)
                    is ProviderResult.Success -> result.value
                }
            val now = Instant.now(clock)
            products += response.items.mapNotNull { DiaMapper.map(it, now) }
            if (page >= (response.pagination?.totalPages ?: 1)) break
            page++
        }
        return ProviderResult.Success(products.distinctBy { it.product.key })
    }

    /** Día has no product endpoint we could verify: the id is searched and matched exactly. */
    override suspend fun product(
        productId: String,
        location: ProviderLocation,
    ): ProviderResult<ProviderProduct?> {
        ensureSession(location)?.let { return it }
        return when (val result = call { api.search(productId, 1) }) {
            is ProviderResult.Failure -> {
                result
            }

            is ProviderResult.Success -> {
                val item = result.value.items.firstOrNull { it.id == productId }
                if (item == null) {
                    ProviderResult.Failure(ProviderError.Parse("Product $productId not found by id"))
                } else {
                    ProviderResult.Success(DiaMapper.map(item, Instant.now(clock)))
                }
            }
        }
    }

    private suspend fun ensureSession(location: ProviderLocation): ProviderResult.Failure? =
        if (sessionPostalCode == location.postalCode) null else setPostalCode(location.postalCode) as? ProviderResult.Failure

    private suspend fun setPostalCode(postalCode: String): ProviderResult<Unit> {
        val result =
            call {
                val response = api.savePostalCode(postalCode)
                if (!response.isSuccessful) throw HttpException(response)
                response.code()
            }
        return when (result) {
            is ProviderResult.Failure -> {
                result
            }

            is ProviderResult.Success -> {
                if (result.value == NOT_SERVED) {
                    ProviderResult.Failure(ProviderError.NotServed)
                } else {
                    sessionPostalCode = postalCode
                    ProviderResult.Success(Unit)
                }
            }
        }
    }

    private suspend fun <T> call(block: suspend () -> T): ProviderResult<T> = providerCall(limiter, sleep = sleep, block = block)

    companion object {
        const val BASE_URL = "https://www.dia.es/api/v1/"
        const val MAX_PAGES = 2
        private const val NOT_SERVED = 206

        /** The catalogue sync's searches (shared with other search-based providers). */
        val SEARCHES = FoodSearches.TERMS

        private val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                coerceInputValues = true
            }

        /** Builds the provider on the shared [client] (User-Agent, timeouts) with its own session cookies. */
        fun create(
            client: OkHttpClient,
            clock: Clock = Clock.systemUTC(),
            baseUrl: String = BASE_URL,
            limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
            sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        ): DiaProvider {
            val withSession = client.newBuilder().cookieJar(MemoryCookieJar()).build()
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(baseUrl)
                    .client(withSession)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
            return DiaProvider(retrofit.create(DiaApi::class.java), limiter, clock, sleep)
        }
    }
}

/** Session cookies for one provider, in memory only (never written to disk). */
private class MemoryCookieJar : CookieJar {
    private val cookies = mutableMapOf<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        cookies.forEach { this.cookies[it.name + "@" + it.domain + it.path] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        this.cookies.values.removeAll { it.expiresAt < now }
        return this.cookies.values.filter { it.matches(url) }
    }
}
