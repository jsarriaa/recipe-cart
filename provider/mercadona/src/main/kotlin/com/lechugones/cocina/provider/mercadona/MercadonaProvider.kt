package com.lechugones.cocina.provider.mercadona

import com.lechugones.cocina.provider.ProviderCategory
import com.lechugones.cocina.provider.ProviderError
import com.lechugones.cocina.provider.ProviderLocation
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.provider.ProviderResult
import com.lechugones.cocina.provider.RateLimiter
import com.lechugones.cocina.provider.SupermarketProvider
import com.lechugones.cocina.provider.providerCall
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant

/** Mercadona provider (docs/providers/mercadona.md). */
class MercadonaProvider(
    private val api: MercadonaApi,
    private val limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : SupermarketProvider {
    override val id: String = MercadonaMapper.PROVIDER_ID
    override val displayName: String = "Mercadona"
    override val storeBrands: Set<String> = MercadonaMapper.STORE_BRANDS

    override suspend fun resolveLocation(postalCode: String): ProviderResult<ProviderLocation> {
        val result =
            call {
                val response = api.changePostalCode(PostalCodeBody(postalCode))
                if (!response.isSuccessful) throw HttpException(response)
                response.headers()[WAREHOUSE_HEADER]
            }
        return when (result) {
            is ProviderResult.Failure -> {
                result
            }

            is ProviderResult.Success -> {
                result.value
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ProviderResult.Success(ProviderLocation(postalCode, it)) }
                    ?: ProviderResult.Failure(ProviderError.NotServed)
            }
        }
    }

    override suspend fun categories(location: ProviderLocation): ProviderResult<List<ProviderCategory>> =
        call {
            api.categories(LANG, location.token).results.filter { it.id !in EXCLUDED_TOP_CATEGORIES }.flatMap { top ->
                top.categories
                    .filter { it.published && it.id !in EXCLUDED_SUBCATEGORIES }
                    .map { ProviderCategory(it.id.toString(), it.name, top.name) }
            }
        }

    override suspend fun productsIn(
        category: ProviderCategory,
        location: ProviderLocation,
    ): ProviderResult<List<ProviderProduct>> {
        val id = category.id.toIntOrNull() ?: return ProviderResult.Failure(ProviderError.Parse("Bad category id ${category.id}"))
        return call {
            val now = Instant.now(clock)
            api.category(id, LANG, location.token).categories.flatMap { group ->
                val path = listOfNotNull(category.parentName, category.name, group.name)
                group.products.mapNotNull { MercadonaMapper.map(it, now, path) }
            }
        }
    }

    override suspend fun product(
        productId: String,
        location: ProviderLocation,
    ): ProviderResult<ProviderProduct?> {
        val result = call { MercadonaMapper.map(api.product(productId, LANG, location.token), Instant.now(clock)) }
        // A product that no longer exists answers 404: report "gone", not an error.
        return if (result is ProviderResult.Failure && result.error == ProviderError.Http(404)) ProviderResult.Success(null) else result
    }

    private suspend fun <T> call(block: suspend () -> T): ProviderResult<T> = providerCall(limiter, sleep = sleep, block = block)

    companion object {
        const val BASE_URL = "https://tienda.mercadona.es/api/"
        private const val LANG = "es"
        private const val WAREHOUSE_HEADER = "x-customer-wh"

        /**
         * Non-food top-level categories, skipped by the catalogue sync (mercadona.md § Catalogue
         * sync plan): personal care, hair, make-up, parapharmacy, pets, household.
         */
        val EXCLUDED_TOP_CATEGORIES = setOf(20, 21, 22, 23, 25, 26)

        /** Non-food parts of "Bebé": bottles, hygiene, wipes and nappies. */
        val EXCLUDED_SUBCATEGORIES = setOf(217, 218, 219)

        private val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                coerceInputValues = true
            }

        /** Builds the provider on a shared [client], which sets the User-Agent and timeouts. */
        fun create(
            client: OkHttpClient,
            clock: Clock = Clock.systemUTC(),
            baseUrl: String = BASE_URL,
            limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
            sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        ): MercadonaProvider {
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
            return MercadonaProvider(retrofit.create(MercadonaApi::class.java), limiter, clock, sleep)
        }
    }
}
