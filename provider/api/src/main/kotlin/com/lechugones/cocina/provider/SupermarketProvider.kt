package com.lechugones.cocina.provider

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.Product

/**
 * One supermarket (docs/providers/README.md). Implementations only build requests, parse and
 * map; rate limiting, retries, caching and fallback are shared.
 */
interface SupermarketProvider {
    /** Stable id stored in the database and in recipe packs: `mercadona`, `dia`. */
    val id: String

    /** Brand name shown to the user; not translated. */
    val displayName: String

    /** Brand names classified as [com.lechugones.cocina.domain.product.BrandKind.STORE]. */
    val storeBrands: Set<String>

    /** Resolves a postal code into provider-specific location state (Mercadona: warehouse). */
    suspend fun resolveLocation(postalCode: String): ProviderResult<ProviderLocation>

    /** The categories a catalogue sync downloads (food only), in display order. */
    suspend fun categories(location: ProviderLocation): ProviderResult<List<ProviderCategory>>

    /** Every product of one category. */
    suspend fun productsIn(
        category: ProviderCategory,
        location: ProviderLocation,
    ): ProviderResult<List<ProviderProduct>>

    /** Fresh data for one product (price refresh). Null when the shop no longer has it. */
    suspend fun product(
        productId: String,
        location: ProviderLocation,
    ): ProviderResult<ProviderProduct?>
}

/** Where to shop: the postal code and the provider's token for it (Mercadona: `bcn1`). */
data class ProviderLocation(
    val postalCode: String,
    val token: String,
)

data class ProviderCategory(
    val id: String,
    val name: String,
    val parentName: String? = null,
)

/** A normalised product plus what only the catalogue needs (docs/providers/README.md). */
data class ProviderProduct(
    val product: Product,
    val imageUrl: String? = null,
    val webUrl: String? = null,
    val categoryPath: List<String> = emptyList(),
    /** Price per reference unit, as the shop shows it. */
    val referencePrice: Money? = null,
    /** The shop's reference unit text: `kg`, `l`, `ud`, `100 g`. */
    val referenceFormat: String? = null,
)

sealed interface ProviderError {
    data object Offline : ProviderError

    data class Http(
        val code: Int,
    ) : ProviderError

    /** 403 or a bot-protection page: stop, never retry (CLAUDE.md, hard rule 3). */
    data object Blocked : ProviderError

    data class RateLimited(
        val retryAfterSeconds: Long?,
    ) : ProviderError

    data class Parse(
        val detail: String,
    ) : ProviderError

    /** The postal code is outside the shop's delivery area. */
    data object NotServed : ProviderError
}

sealed interface ProviderResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ProviderResult<T>

    data class Failure(
        val error: ProviderError,
    ) : ProviderResult<Nothing>
}

inline fun <T, R> ProviderResult<T>.map(transform: (T) -> R): ProviderResult<R> =
    when (this) {
        is ProviderResult.Success -> ProviderResult.Success(transform(value))
        is ProviderResult.Failure -> this
    }
