package com.lechugones.cocina.provider.aldi

import com.lechugones.cocina.provider.ProviderCategory
import com.lechugones.cocina.provider.ProviderError
import com.lechugones.cocina.provider.ProviderLocation
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.provider.ProviderResult
import com.lechugones.cocina.provider.RateLimiter
import com.lechugones.cocina.provider.SupermarketProvider
import com.lechugones.cocina.provider.providerCall
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import java.time.Clock
import java.time.Instant

/**
 * Aldi provider (docs/providers/aldi.md), ported from supermercapy (third_party/supermercapy, MIT).
 *
 * Aldi has no online shop: www.aldi.es shows the shelf assortment with the península's shop
 * prices. The sync reads the category pages a visitor would open: the overview, the food
 * top-level pages (for their subcategories) and every food subcategory page, whose embedded
 * search results hold up to 1,000 products. About 94 requests. The Balearic and Canary price
 * regions live under paths robots.txt excludes, so only península prices are offered.
 */
class AldiProvider(
    private val api: AldiApi,
    private val limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : SupermarketProvider {
    override val id: String = AldiMapper.PROVIDER_ID
    override val displayName: String = "Aldi"
    override val storeBrands: Set<String> = AldiMapper.STORE_BRANDS

    /** Prices are the península's: the postal code changes nothing, and no request is made. */
    override suspend fun resolveLocation(postalCode: String): ProviderResult<ProviderLocation> =
        ProviderResult.Success(ProviderLocation(postalCode, PENINSULA))

    override suspend fun categories(location: ProviderLocation): ProviderResult<List<ProviderCategory>> {
        val tops =
            when (val result = page({ api.overview() }) { AldiPages.children(it) }) {
                is ProviderResult.Failure -> return result
                is ProviderResult.Success -> result.value.filter { isFood(it) }
            }
        val leaves = mutableListOf<ProviderCategory>()
        for (top in tops) {
            val children =
                when (val result = page({ api.category(top.path) }) { AldiPages.children(it) }) {
                    is ProviderResult.Failure -> return result
                    is ProviderResult.Success -> result.value.filterNot { it.marketing }
                }
            if (children.isEmpty()) {
                leaves += ProviderCategory(top.path, top.name)
            } else {
                children.forEach { leaves += ProviderCategory(it.path, it.name, parentName = top.name) }
            }
        }
        return ProviderResult.Success(leaves)
    }

    override suspend fun productsIn(
        category: ProviderCategory,
        location: ProviderLocation,
    ): ProviderResult<List<ProviderProduct>> =
        when (val result = page({ api.category(category.id) }) { AldiPages.records(it) }) {
            is ProviderResult.Failure -> {
                result
            }

            is ProviderResult.Success -> {
                val now = Instant.now(clock)
                val path = listOfNotNull(category.parentName, category.name)
                val products = result.value.mapNotNull { AldiMapper.map(it, now, path) }
                ProviderResult.Success(products.distinctBy { it.product.key })
            }
        }

    /** One product page; a 404 means Aldi no longer lists the product. */
    override suspend fun product(
        productId: String,
        location: ProviderLocation,
    ): ProviderResult<ProviderProduct?> {
        if (!SAFE_ID.matches(productId)) return ProviderResult.Success(null)
        return when (val result = page({ api.product(productId) }) { AldiPages.productRecord(it) }) {
            is ProviderResult.Failure -> {
                if ((result.error as? ProviderError.Http)?.code == NOT_FOUND) ProviderResult.Success(null) else result
            }

            is ProviderResult.Success -> {
                val record = result.value?.takeIf { it.objectID == productId }
                ProviderResult.Success(record?.let { AldiMapper.map(it, Instant.now(clock)) })
            }
        }
    }

    private fun isFood(link: AldiCategoryLink): Boolean = !link.marketing && link.path !in NOT_FOOD

    /** Fetches a page and reads it inside the guarded call, so an unexpected page is a parse error. */
    private suspend fun <T> page(
        fetch: suspend () -> ResponseBody,
        read: (String) -> T,
    ): ProviderResult<T> = providerCall(limiter, sleep = sleep) { read(fetch().use { it.string() }) }

    companion object {
        const val BASE_URL = "https://www.aldi.es/"
        const val PENINSULA = "pen"
        private const val NOT_FOUND = 404
        private val SAFE_ID = Regex("^[0-9]{1,12}$")

        /** Top-level categories that are not food. */
        val NOT_FOOD = setOf("limpieza-y-hogar", "cuidado-personal", "bebe-e-infantil", "mascotas", "marcas")

        fun create(
            client: OkHttpClient,
            clock: Clock = Clock.systemUTC(),
            baseUrl: String = BASE_URL,
            limiter: RateLimiter = RateLimiter(RateLimiter.DEFAULT_INTERVAL_MILLIS),
            sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        ): AldiProvider {
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .build()
            return AldiProvider(retrofit.create(AldiApi::class.java), limiter, clock, sleep)
        }
    }
}
