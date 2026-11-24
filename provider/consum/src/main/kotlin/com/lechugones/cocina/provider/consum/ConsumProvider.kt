package com.lechugones.cocina.provider.consum

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
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.text.Normalizer
import java.time.Clock
import java.time.Instant
import java.util.Locale

/**
 * Consum provider (docs/providers/consum.md), ported from supermercapy
 * (third_party/supermercapy, MIT).
 *
 * Prices are national; a zone only narrows the assortment, so no zone is bound and the postal
 * code is never sent. The sync reads the category menu once, then lists each second-level food
 * category a hundred rows per request.
 */
class ConsumProvider(
    private val api: ConsumApi,
    private val limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : SupermarketProvider {
    override val id: String = ConsumMapper.PROVIDER_ID
    override val displayName: String = "Consum"
    override val storeBrands: Set<String> = ConsumMapper.STORE_BRANDS

    override suspend fun resolveLocation(postalCode: String): ProviderResult<ProviderLocation> =
        ProviderResult.Success(ProviderLocation(postalCode, NATIONAL))

    override suspend fun categories(location: ProviderLocation): ProviderResult<List<ProviderCategory>> =
        when (val result = call { api.menu() }) {
            is ProviderResult.Failure -> {
                result
            }

            is ProviderResult.Success -> {
                val leaves =
                    result.value
                        .filter { it.type == 0 && plain(it.name) !in NOT_FOOD }
                        .flatMap { root ->
                            root.subcategories.ifEmpty { listOf(root) }.map {
                                ProviderCategory(it.id.toString(), it.name, parentName = root.name.takeIf { _ -> it !== root })
                            }
                        }.distinctBy { it.id }
                ProviderResult.Success(leaves)
            }
        }

    override suspend fun productsIn(
        category: ProviderCategory,
        location: ProviderLocation,
    ): ProviderResult<List<ProviderProduct>> {
        if (!SAFE_ID.matches(category.id)) return ProviderResult.Success(emptyList())
        val path = listOfNotNull(category.parentName, category.name)
        val found = mutableListOf<ProviderProduct>()
        var offset = 0
        repeat(MAX_PAGES) {
            val page =
                when (val result = call { api.category(category.id, PAGE_SIZE, offset) }) {
                    is ProviderResult.Failure -> return result
                    is ProviderResult.Success -> result.value
                }
            val now = Instant.now(clock)
            page.products.mapNotNullTo(found) { ConsumMapper.map(it, now, path) }
            // The offset counts every row sent, sponsored ones included.
            offset += page.products.size
            if (!page.hasMore || page.products.isEmpty()) return ProviderResult.Success(found.distinctBy { it.product.key })
        }
        return ProviderResult.Success(found.distinctBy { it.product.key })
    }

    override suspend fun product(
        productId: String,
        location: ProviderLocation,
    ): ProviderResult<ProviderProduct?> {
        if (!SAFE_ID.matches(productId)) return ProviderResult.Success(null)
        return call {
            try {
                ConsumMapper.map(api.product(productId), Instant.now(clock))
            } catch (e: HttpException) {
                // "Not in this assortment" and "no such product" are the same 404.
                if (e.code() == 404) null else throw e
            }
        }
    }

    private suspend fun <T> call(block: suspend () -> T): ProviderResult<T> = providerCall(limiter, sleep = sleep, block = block)

    companion object {
        const val BASE_URL = "https://tienda.consum.es/api/rest/V1.0/"
        const val NATIONAL = "national"

        /** The API caps a page at 100 rows. */
        const val PAGE_SIZE = 100
        private const val MAX_PAGES = 30
        private val SAFE_ID = Regex("^[0-9]{1,12}$")

        /** Top-level categories that hold no food, or only repeat others (accents removed, lowercase). */
        private val NOT_FOOD =
            setOf(
                "ecologico y saludable",
                "infantil",
                "drogueria y limpieza",
                "cuidado personal",
                "mascotas",
                "bazar",
            )

        private fun plain(name: String): String =
            Normalizer
                .normalize(name.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("""\p{Mn}+"""), "")

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
        ): ConsumProvider {
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
            return ConsumProvider(retrofit.create(ConsumApi::class.java), limiter, clock, sleep)
        }
    }
}
