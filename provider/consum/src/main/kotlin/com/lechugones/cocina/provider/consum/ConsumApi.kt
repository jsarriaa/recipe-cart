package com.lechugones.cocina.provider.consum

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Consum's online shop API (docs/providers/consum.md): JSON, no cookie, token or key. Endpoints
 * and field meanings ported from supermercapy (third_party/supermercapy, MIT).
 */
interface ConsumApi {
    /** The category tree, about 420 kB. */
    @Headers("X-TOL-LOCALE: es", "Accept: application/json")
    @GET("shopping/category/menu")
    suspend fun menu(): List<ConsumCategory>

    /** One listing page of a category and its subtree; at most 100 rows. */
    @Headers("X-TOL-LOCALE: es", "Accept: application/json")
    @GET("catalog/product")
    suspend fun category(
        @Query("categories") categoryId: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): ConsumListing

    /** One product by its code; 404 when unknown. */
    @Headers("X-TOL-LOCALE: es", "Accept: application/json")
    @GET("catalog/product/code/{code}")
    suspend fun product(
        @Path("code") code: String,
    ): ConsumProduct
}

@Serializable
data class ConsumCategory(
    val id: Long,
    val name: String,
    /** 0 for the real tree, 1 for marketing groups such as "Ahora más barato". */
    val type: Int = 0,
    val subcategories: List<ConsumCategory> = emptyList(),
)

@Serializable
data class ConsumListing(
    val products: List<ConsumProduct> = emptyList(),
    val hasMore: Boolean = false,
    val totalCount: Int? = null,
)

@Serializable
data class ConsumProduct(
    /** The stable id every endpoint takes. */
    val code: String,
    /** 1 standard, 2 sold by weight (price per kg), 3 piece or tray at an approximate price. */
    val productType: Int = 1,
    val ean: String? = null,
    val productData: ConsumProductData,
    val priceData: ConsumPriceData = ConsumPriceData(),
    val categories: List<ConsumLeafCategory> = emptyList(),
    /** The photos. [ConsumProductData.imageURL] often names a file the CDN does not have. */
    val media: List<ConsumMedia> = emptyList(),
)

@Serializable
data class ConsumMedia(
    val url: String? = null,
    val order: Int? = null,
)

@Serializable
data class ConsumProductData(
    val name: String,
    /** The name followed by the size: `Leche Semidesnatada Brik 1 L`, `… 6 x 52 Gr`, `… 0,5 Dc`. */
    val description: String? = null,
    val brand: ConsumBrand? = null,
    val url: String? = null,
    val imageURL: String? = null,
    /** Trays and pieces: `Bandeja 700 g aprox.`. */
    val format: String? = null,
    val attributes: List<ConsumAttribute> = emptyList(),
    val sponsored: Boolean = false,
    /** `1` when it can be bought. */
    val availability: String? = null,
    val temporaryOutOfStock: Boolean = false,
)

@Serializable
data class ConsumBrand(
    val name: String? = null,
)

@Serializable
data class ConsumAttribute(
    val code: String,
    val languages: List<ConsumAttributeValues> = emptyList(),
)

@Serializable
data class ConsumAttributeValues(
    val values: List<String> = emptyList(),
)

@Serializable
data class ConsumPriceData(
    val prices: List<ConsumPrice> = emptyList(),
    /** `1 L`, `1 Kg`, `1 U`, `1 Dc`, `100 Gr`, `100 ml`, `1 Lv`, `1 Do`, `1 M`. */
    val unitPriceUnitType: String? = null,
    /** `kg` for food priced by the kilogram. */
    val priceUnitType: String? = null,
)

@Serializable
data class ConsumPrice(
    /** `PRICE` (shelf) or `OFFER_PRICE` (an immediate offer, which replaces it). */
    val id: String,
    val value: ConsumPriceValue,
)

/** Despite the name, `centAmount` holds euros (0.84 is 84 cents). Read as text, never as a double. */
@Serializable
data class ConsumPriceValue(
    val centAmount: JsonPrimitive? = null,
    @SerialName("centUnitAmount") val unitAmount: JsonPrimitive? = null,
)

@Serializable
data class ConsumLeafCategory(
    val id: Long? = null,
    val name: String? = null,
)
