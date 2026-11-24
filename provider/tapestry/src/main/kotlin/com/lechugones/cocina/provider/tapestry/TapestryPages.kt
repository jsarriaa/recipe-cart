package com.lechugones.cocina.provider.tapestry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The Apache Tapestry storefront Eroski and Caprabo share (docs/providers/eroski.md). Endpoints
 * and markup ported from supermercapy's `_platforms/tapestry.py` (third_party/supermercapy, MIT).
 */
interface TapestryApi {
    /** One page of about 20 search tiles: the fragment the storefront's own infinite scroll loads. */
    @Headers("X-Requested-With: XMLHttpRequest")
    @GET("es/search/results:loadpage")
    suspend fun search(
        @Query("q") query: String,
        @Query("pageNumber") page: Int,
        @Query("t:zoneid") zone: String = "productListZone",
    ): ResponseBody

    /** The product page (the slug is ignored). An unknown id redirects to `/es/error/404/`. */
    @GET("es/productdetail/{id}-producto/")
    suspend fun product(
        @Path("id") id: String,
    ): Response<ResponseBody>
}

/** What one tile or product page shows. */
data class TapestryTile(
    val id: String,
    val name: String,
    /** In capitals, as the analytics event names it. */
    val brand: String?,
    /** The price printed on the tile (`2,29`), else the analytics figure (`2.29`). */
    val price: String?,
    /** `1 KILO A 5,73 €`, `100 ML. A 0,31 €`, `1 DOCENA A 4,28 €`; most single items print none. */
    val unitPrice: String?,
    /** Produce sold by weight (a weight icon); the price is for the minimum purchase. */
    val weighed: Boolean,
    val imageUrl: String?,
    val url: String?,
    /** Third-party sellers (`MP…` ids): never food. */
    val marketplace: Boolean,
)

/** One search fragment. */
data class TapestryPage(
    val tiles: List<TapestryTile>,
    /** The storefront's "no more pages" signal. */
    val finished: Boolean,
    /** Google's reCAPTCHA page instead of content: the shop doubts us, so stop. */
    val challenged: Boolean,
)

/** Reads fragments and product pages with jsoup. */
object TapestryPages {
    private val json = Json { ignoreUnknownKeys = true }
    private const val CHALLENGE = "google.com/recaptcha/challengepage"
    private const val ADD_COMPONENT = "common/button/productListItemAddComponent:init"
    private const val FINISHED = "pages/supermarket:finishPagination"
    private val viewItem = Regex(""""event\\?":\\?"view_item\\?".*?"items\\?":\[(\{.*?\})]""", RegexOption.DOT_MATCHES_ALL)

    /** A `…:loadpage` answer: `{"content": "<html>", "_tapestry": {"inits": […]}}`. */
    fun page(body: String): TapestryPage {
        if (CHALLENGE in body) return TapestryPage(emptyList(), finished = true, challenged = true)
        val root = json.parseToJsonElement(body).jsonObject
        val tapestry = root["_tapestry"] as? JsonObject
        require(tapestry?.get("redirectURL") == null) { "the storefront redirected the fragment" }
        val inits = (tapestry?.get("inits") as? JsonArray).orEmpty()
        // Each add-to-cart button is built from options: sold by weight, sold by a third party.
        val options =
            inits
                .mapNotNull { it as? JsonArray }
                .filter { (it.firstOrNull() as? JsonPrimitive)?.contentOrNull == ADD_COMPONENT }
                .mapNotNull { it.getOrNull(1) as? JsonObject }

        fun flagged(key: String) =
            options
                .filter { (it[key] as? JsonPrimitive)?.contentOrNull == "true" }
                .mapNotNull { (it["productRef"] as? JsonPrimitive)?.contentOrNull }
                .toSet()
        val weighable = flagged("isWeightOptionsAvailable")
        val marketplace = flagged("isMarketplace")
        val finished = inits.any { (it as? JsonPrimitive)?.contentOrNull == FINISHED }
        val content = (root["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val document = Jsoup.parseBodyFragment(content)
        val tiles =
            document
                .select("div.product-item-lineal")
                .mapNotNull { tile(it, weighable, marketplace) }
                .distinctBy { it.id }
        return TapestryPage(tiles, finished, challenged = false)
    }

    private fun tile(
        tile: Element,
        weighable: Set<String>,
        marketplace: Set<String>,
    ): TapestryTile? {
        // Criteo ad slots share the class but carry no analytics event.
        val link = tile.selectFirst("a.product-title-link[data-metrics]") ?: return null
        val item = metrics(link.attr("data-metrics")) ?: return null
        val id = item.text("item_id") ?: return null
        val name =
            link
                .text()
                .trim()
                .ifEmpty { item.text("item_name") }
                ?.takeIf { it.isNotBlank() } ?: return null
        return TapestryTile(
            id = id,
            name = name,
            brand = item.text("item_brand"),
            price = shownPrice(tile) ?: item.text("price"),
            unitPrice =
                tile
                    .selectFirst("p.quantity-text")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
            weighed = tile.selectFirst("img[src*=weight-icon]") != null || id in weighable,
            imageUrl = tile.selectFirst("img.product-img")?.absUrl("src")?.takeIf { it.isNotBlank() },
            url = link.absUrl("href").takeIf { it.isNotBlank() },
            marketplace = id.startsWith("MP") || id in marketplace,
        )
    }

    /**
     * A product page, for price refresh. The analytics event names the id, brand and price; the
     * markup adds the rest. Null when the page carries no product.
     */
    fun productPage(
        html: String,
        baseUri: String,
    ): TapestryTile? {
        if (CHALLENGE in html) return null
        val document: Document = Jsoup.parse(html, baseUri)
        val event =
            viewItem
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.replace("\\\"", "\"")
        val item = event?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val id = item?.text("item_id") ?: return null
        val name = document.selectFirst("h1.description-title")?.text()?.trim() ?: item.text("item_name") ?: return null
        return TapestryTile(
            id = id,
            name = name,
            brand = item.text("item_brand"),
            price = shownPrice(document) ?: item.text("price"),
            unitPrice =
                document
                    .selectFirst("p.quantity-text")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
            weighed = document.selectFirst("img[src*=weight-icon]") != null,
            imageUrl = document.selectFirst("#main-product-image")?.absUrl("src")?.takeIf { it.isNotBlank() },
            url = null,
            marketplace = id.startsWith("MP"),
        )
    }

    /** Whether a Tapestry JSON answer (or a page) is Google's reCAPTCHA page. */
    fun isChallenge(body: String): Boolean = CHALLENGE in body

    /** The price now; a struck-through price, if any, sits in `…offer-before` and is ignored. */
    private fun shownPrice(scope: Element): String? =
        scope
            .selectFirst(".price-offer-now, .offer-now")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun metrics(raw: String): JsonObject? =
        runCatching {
            json
                .parseToJsonElement(raw)
                .jsonObject["ecommerce"]
                ?.jsonObject
                ?.get("items")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
        }.getOrNull()

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.let { if (it.isString) it.content else it.contentOrNull }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
