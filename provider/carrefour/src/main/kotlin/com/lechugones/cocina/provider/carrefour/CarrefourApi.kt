package com.lechugones.cocina.provider.carrefour

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The search index Carrefour's own storefront search box calls, hosted by Empathy
 * (docs/providers/carrefour.md). Unauthenticated, no cookie. Endpoint and field meanings ported
 * from supermercapy (third_party/supermercapy, MIT).
 */
interface CarrefourApi {
    /** At most 500 rows per page; [query] is lowercased, as Carrefour's own client does. */
    @GET("search")
    suspend fun search(
        @Query("query") query: String,
        @Query("rows") rows: Int,
        @Query("start") start: Int = 0,
        @Query("lang") lang: String = "es",
        @Query("catalog") catalog: String = "food",
    ): CarrefourSearchResponse
}

@Serializable
data class CarrefourSearchResponse(
    val catalog: CarrefourCatalog? = null,
)

@Serializable
data class CarrefourCatalog(
    val content: List<CarrefourDocument> = emptyList(),
    val numFound: Int? = null,
)

/** One search document. Prices are numbers in euros; sizes come from [measureUnit] × [conversionFactor]. */
@Serializable
data class CarrefourDocument(
    @SerialName("product_id") val id: String,
    @SerialName("display_name") val name: String,
    val brand: String? = null,
    val ean13: String? = null,
    @SerialName("active_price") val price: Double? = null,
    @SerialName("list_price") val listPrice: Double? = null,
    /** `kg`, `l`, `ud`, `docena`, `lavado`, `m`. */
    @SerialName("measure_unit") val measureUnit: String? = null,
    /** How much of [measureUnit] one selling unit holds; a `0.001` placeholder when [variableWeight]. */
    @SerialName("unit_conversion_factor") val conversionFactor: Double? = null,
    /** Typical weight in grams, also for loose fresh food. */
    @SerialName("average_weight") val averageWeight: Double? = null,
    /** Pack text as printed: `1 l.`, `pack 6x100 g.`, `900 g aprox`. */
    val recipient: String? = null,
    @SerialName("variable_weight") val variableWeight: Boolean? = null,
    @SerialName("sale_point_available") val available: Boolean? = null,
    @SerialName("active_food") val activeFood: Boolean? = null,
    @SerialName("image_path") val images: Map<String, String> = emptyMap(),
    val urls: Map<String, String> = emptyMap(),
)
