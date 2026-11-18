package com.lechugones.cocina.provider.mercadona

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Mercadona's online-shop JSON endpoints, as documented in docs/providers/mercadona.md. */
interface MercadonaApi {
    /** Sets the postal code; the warehouse comes back in the `x-customer-wh` header. */
    @PUT("postal-codes/actions/change-pc/")
    suspend fun changePostalCode(
        @Body body: PostalCodeBody,
    ): Response<ResponseBody>

    @GET("categories/")
    suspend fun categories(
        @Query("lang") lang: String,
        @Query("wh") warehouse: String,
    ): CategoriesResponse

    /** A second-level category with its groups and products. */
    @GET("categories/{id}/")
    suspend fun category(
        @Path("id") id: Int,
        @Query("lang") lang: String,
        @Query("wh") warehouse: String,
    ): CategoryDetail

    @GET("products/{id}/")
    suspend fun product(
        @Path("id") id: String,
        @Query("lang") lang: String,
        @Query("wh") warehouse: String,
    ): ProductDto
}

@Serializable
data class PostalCodeBody(
    @SerialName("new_postal_code") val newPostalCode: String,
)

@Serializable
data class CategoriesResponse(
    val results: List<TopCategory> = emptyList(),
)

@Serializable
data class TopCategory(
    val id: Int,
    val name: String,
    val categories: List<SubCategory> = emptyList(),
)

@Serializable
data class SubCategory(
    val id: Int,
    val name: String,
    val published: Boolean = true,
)

@Serializable
data class CategoryDetail(
    val id: Int,
    val name: String,
    val categories: List<ProductGroup> = emptyList(),
)

@Serializable
data class ProductGroup(
    val id: Int,
    val name: String,
    val products: List<ProductDto> = emptyList(),
)

@Serializable
data class CategoryRef(
    val id: Int,
    val name: String,
)

@Serializable
data class ProductDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val packaging: String? = null,
    val published: Boolean = true,
    @SerialName("share_url") val shareUrl: String? = null,
    val thumbnail: String? = null,
    /** Only in the product detail response. */
    val brand: String? = null,
    @SerialName("unavailable_from") val unavailableFrom: String? = null,
    val categories: List<CategoryRef> = emptyList(),
    @SerialName("price_instructions") val price: PriceInstructions,
)

@Serializable
data class PriceInstructions(
    @SerialName("unit_price") val unitPrice: String,
    @SerialName("unit_size") val unitSize: Double? = null,
    @SerialName("size_format") val sizeFormat: String? = null,
    @SerialName("total_units") val totalUnits: Int? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("drained_weight") val drainedWeight: Double? = null,
    @SerialName("approx_size") val approxSize: Boolean = false,
    @SerialName("reference_price") val referencePrice: String? = null,
    @SerialName("reference_format") val referenceFormat: String? = null,
    @SerialName("selling_method") val sellingMethod: Int = 0,
)
