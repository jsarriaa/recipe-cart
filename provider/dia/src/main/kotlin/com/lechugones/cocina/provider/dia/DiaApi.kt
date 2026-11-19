package com.lechugones.cocina.provider.dia

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Query

/**
 * Día's online-shop JSON endpoints (docs/providers/dia.md, verified from the phone on
 * 2026-11-19). The postal code lives in a server session cookie set by [savePostalCode].
 */
interface DiaApi {
    /** 204: postal code set for the session; 206 with a validation error: not served. */
    @PUT("common-aggregator/save-shipping-address")
    suspend fun savePostalCode(
        @Query("new_postal_code") postalCode: String,
    ): Response<ResponseBody>

    /** 30 results per page whatever `page_size` says. */
    @GET("search-back/search/reduced")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int,
    ): SearchResponse
}

@Serializable
data class SearchResponse(
    val cart: Cart? = null,
    val pagination: Pagination? = null,
    @SerialName("search_items") val items: List<DiaItem> = emptyList(),
)

@Serializable
data class Cart(
    @SerialName("postal_code") val postalCode: String? = null,
)

@Serializable
data class Pagination(
    @SerialName("page_number") val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
data class DiaItem(
    @SerialName("object_id") val id: String,
    @SerialName("display_name") val name: String,
    val brand: String? = null,
    @SerialName("dia_brand") val diaBrand: Boolean? = null,
    val image: String? = null,
    val url: String? = null,
    @SerialName("l1_category_description") val category1: String? = null,
    @SerialName("l2_category_description") val category2: String? = null,
    @SerialName("units_in_stock") val unitsInStock: Int? = null,
    val prices: DiaPrices? = null,
)

@Serializable
data class DiaPrices(
    val price: Double? = null,
    @SerialName("price_per_unit") val pricePerUnit: Double? = null,
    @SerialName("measure_unit") val measureUnit: String? = null,
    @SerialName("strikethrough_price") val strikethroughPrice: Double? = null,
    @SerialName("is_club_price") val clubPrice: Boolean = false,
)
