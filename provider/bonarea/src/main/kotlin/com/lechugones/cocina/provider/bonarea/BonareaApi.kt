package com.lechugones.cocina.provider.bonarea

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * bonÀrea's online shop (docs/providers/bonarea.md): form posts to `/es/shop/{action}` that
 * answer JSON. Endpoints and field meanings ported from supermercapy (third_party/supermercapy,
 * MIT).
 */
interface BonareaApi {
    /** Search reads the language from the session, so the Spanish home page is fetched once first. */
    @GET("es")
    suspend fun home(): ResponseBody

    /** Every hit at once: there is no page, size or sort parameter. */
    @FormUrlEncoded
    @POST("es/shop/search")
    suspend fun search(
        @Field("strQuery") query: String,
    ): BonareaSearchResponse

    /** An unknown id answers 200 with `{"article": null}`. */
    @FormUrlEncoded
    @POST("es/shop/Article")
    suspend fun article(
        @Field("identifier") id: String,
    ): BonareaArticleResponse
}

@Serializable
data class BonareaSearchResponse(
    val success: Boolean? = null,
    val articles: List<BonareaArticle> = emptyList(),
)

@Serializable
data class BonareaArticleResponse(
    val article: BonareaArticle? = null,
)

/** One article. bonÀrea publishes no brand and no barcode. */
@Serializable
data class BonareaArticle(
    /** `13*0066`: the prefix is the business (13 supermarket, 99 frozen, 03 garden and drugstore, 05 farm). */
    val identifier: String,
    /** May arrive HTML-escaped (`leche &amp; miel`). */
    val description: String,
    /** `6 l`, `600 ml`, `aprox. 187 g`, `16 u.`, or empty. */
    val measurementUnit: String? = null,
    val weightGrams: Int? = null,
    val priceToPay: Double? = null,
    /** Display text: `1,12 €/l`, `11,94 €/kg`, `0,22 €/u.`. */
    val unitPrice: String? = null,
    val isAnApproximatePrice: Boolean? = null,
    val itsOnStock: Boolean? = null,
    val isValid: Boolean? = null,
    val image: List<String> = emptyList(),
    val caracteristiques: List<BonareaTag> = emptyList(),
    val urlFriendly: String? = null,
)

@Serializable
data class BonareaTag(
    val descripcio: String? = null,
)
