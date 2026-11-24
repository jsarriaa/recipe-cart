package com.lechugones.cocina.provider.ahorramas

import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Ahorramás's product grid (docs/providers/ahorramas.md), a Salesforce Commerce Cloud controller
 * that answers HTML tiles. `Search-UpdateGrid` is allowed by robots.txt; the product page
 * (`Product-Variation`) is not, so it is never used. Ported from supermercapy
 * (third_party/supermercapy, MIT).
 */
interface AhorramasApi {
    @Headers("X-Requested-With: XMLHttpRequest")
    @GET("on/demandware.store/Sites-Ahorramas-Site/es/Search-UpdateGrid")
    suspend fun grid(
        @Query("q") query: String,
        @Query("sz") size: Int,
        @Query("start") start: Int = 0,
    ): ResponseBody
}

/** What one grid tile shows. */
data class AhorramasTile(
    val id: String,
    val name: String,
    /** In capitals; empty for loose fresh food. */
    val brand: String?,
    val price: String?,
    /** `0,83€/LITRO`, `3,05€/DOCENA`, `15,71€/KG.PESO ESC`, `3,48€/Kg`. */
    val unitPrice: String?,
    /** Priced per kilogram (fresh food sold by weight). */
    val pricePerKilo: Boolean,
    /** Weight of an average piece in kilograms, for food sold by weight. */
    val mediumWeight: String?,
    val available: Boolean,
    val imageUrl: String?,
    val path: String?,
    val category1: String?,
    val category2: String?,
)

/** Reads grid tiles with jsoup. */
object AhorramasGrid {
    fun tiles(
        html: String,
        baseUri: String = AhorramasMapper.SITE,
    ): List<AhorramasTile> =
        Jsoup
            .parse(html, baseUri)
            .select("div.product[data-pid]")
            .mapNotNull { tile(it) }

    private fun tile(product: Element): AhorramasTile? {
        val id = product.attr("data-pid").takeIf { it.isNotBlank() } ?: return null
        val body = product.selectFirst("div.product-tile") ?: product
        val name = body.selectFirst(".product-name-gtm")?.text()?.takeIf { it.isNotBlank() } ?: return null
        val cart = body.selectFirst("[data-price-kg]")
        val link = body.selectFirst("a.product-pdp-link")
        return AhorramasTile(
            id = id,
            name = name,
            brand = body.attr("data-brand").trim().takeIf { it.isNotEmpty() },
            price = body.selectFirst(".sales .value")?.attr("content")?.takeIf { it.isNotBlank() },
            unitPrice = body.selectFirst(".unit-price-per-unit.grey")?.text()?.takeIf { it.isNotBlank() },
            pricePerKilo = cart?.attr("data-price-kg") == "true",
            mediumWeight = cart?.attr("data-mediumweight")?.takeIf { it.isNotBlank() && it != "null" },
            available = cart?.attr("data-available") != "false",
            imageUrl = body.selectFirst("img.tile-image")?.absUrl("src")?.takeIf { it.isNotBlank() },
            path = link?.attr("href")?.takeIf { it.startsWith("/") },
            category1 = body.attr("data-category1").takeIf { it.isNotBlank() },
            category2 = body.attr("data-category2").takeIf { it.isNotBlank() },
        )
    }
}
