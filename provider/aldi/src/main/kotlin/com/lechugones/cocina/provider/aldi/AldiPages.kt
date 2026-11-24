package com.lechugones.cocina.provider.aldi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * The pages of www.aldi.es (docs/providers/aldi.md). Aldi runs no online shop; its Next.js pages
 * embed their data in a `__NEXT_DATA__` script. Category pages embed the product records of their
 * search index, so no search key is needed (etiquette rule 7). Page structure and field meanings
 * ported from supermercapy (third_party/supermercapy, MIT).
 */
interface AldiApi {
    /** `productos.html`, a top-level `productos/despensa.html` or a leaf `productos/despensa/pasta.html`. */
    @GET("productos/{path}.html")
    suspend fun category(
        @Path("path", encoded = true) path: String,
    ): ResponseBody

    @GET("productos.html")
    suspend fun overview(): ResponseBody

    /** The slug is not checked: `p-{id}` works. An unknown id answers 404. */
    @GET("producto/p-{id}.html")
    suspend fun product(
        @Path("id") id: String,
    ): ResponseBody
}

/** One product record of Aldi's index, as embedded in its pages. */
@Serializable
data class AldiRecord(
    val objectID: String,
    val name: String,
    val brandName: String? = null,
    /** `1 l unidad`, `pack de 6 x 200 ml`, `114 g aprox. unidad`, `precio kg (a granel)`. */
    val salesUnit: String? = null,
    val currentPrice: AldiPrice? = null,
    val productSlug: String? = null,
    val isAvailable: Boolean? = null,
    val assets: List<AldiAsset> = emptyList(),
)

@Serializable
data class AldiPrice(
    val priceValue: Double? = null,
    val basePrice: List<AldiBasePrice> = emptyList(),
)

@Serializable
data class AldiBasePrice(
    val basePriceValue: Double? = null,
    /** `kg`, `l`, `100-ml`, `unidad`, and `g` / `100-g`, which Aldi uses for per-kilogram prices too. */
    val basePriceScale: String? = null,
)

@Serializable
data class AldiAsset(
    val type: String? = null,
    val url: String? = null,
)

/** A category link on a page: [path] below `/productos/`, without `.html`. */
data class AldiCategoryLink(
    val path: String,
    val name: String,
    val key: String?,
    val marketing: Boolean,
)

/** Reads the `__NEXT_DATA__` of Aldi pages. */
object AldiPages {
    private val script = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    private const val PRODUCTS = "/productos/"

    /** The visible child categories a page links to (hidden ones are skipped, as the site does). */
    fun children(html: String): List<AldiCategoryLink> =
        apiData(pageProps(html))["PRODUCT_MGNL_CATEGORY_CHILDREN_GET"]
            ?.asArray()
            .orEmpty()
            .mapNotNull { item ->
                val node = item as? JsonObject ?: return@mapNotNull null
                if (node.bool("hideInCategory") == true) return@mapNotNull null
                val path = pagePath(node.obj("reference")?.text("path")) ?: return@mapNotNull null
                val name = node.text("navigationTitle") ?: node.text("title") ?: return@mapNotNull null
                AldiCategoryLink(path, name, node.text("categoryKey"), node.bool("marketingCategory") == true)
            }

    /** The product records a leaf category page embeds (up to 1,000). */
    fun records(html: String): List<AldiRecord> {
        val state = pageProps(html).obj("algoliaState")?.obj("initialResults") ?: return emptyList()
        return state.values
            .flatMap { index -> (index as? JsonObject)?.get("results")?.asArray().orEmpty() }
            .flatMap { result -> (result as? JsonObject)?.get("hits")?.asArray().orEmpty() }
            .mapNotNull { decode(it) }
    }

    /** The record a product page embeds, or null. */
    fun productRecord(html: String): AldiRecord? =
        apiData(pageProps(html))["PRODUCT_DETAIL_GET"]
            ?.let { it as? JsonObject }
            ?.get("products")
            ?.asArray()
            ?.firstNotNullOfOrNull { decode(it) }

    private fun decode(element: JsonElement): AldiRecord? =
        try {
            json.decodeFromJsonElement(AldiRecord.serializer(), element)
        } catch (e: IllegalArgumentException) {
            null // Includes SerializationException: one odd record is skipped, not the page.
        }

    private fun pageProps(html: String): JsonObject {
        val match = requireNotNull(script.find(html)) { "Aldi page without __NEXT_DATA__" }
        val root = json.parseToJsonElement(match.groupValues[1]) as? JsonObject
        return root?.obj("props")?.obj("pageProps") ?: JsonObject(emptyMap())
    }

    /** `apiData` is a list of `[name, {"res": …}]` pairs, sometimes encoded as a JSON string. */
    private fun apiData(props: JsonObject): Map<String, JsonElement> {
        val raw = props["apiData"] ?: return emptyMap()
        val list =
            when {
                raw is JsonPrimitive && raw.isString -> json.parseToJsonElement(raw.content) as? JsonArray
                raw is JsonArray -> raw
                else -> null
            } ?: return emptyMap()
        val answers = mutableMapOf<String, JsonElement>()
        for (pair in list) {
            val items = (pair as? JsonArray)?.takeIf { it.size == 2 } ?: continue
            val name = (items[0] as? JsonPrimitive)?.contentOrNull ?: continue
            val res = (items[1] as? JsonObject)?.get("res") ?: continue
            answers.putIfAbsent(name, res)
        }
        return answers
    }

    /** `/productos/despensa/pasta` or `/spain/productos/despensa/pasta.html` → `despensa/pasta`. */
    private fun pagePath(path: String?): String? {
        if (path == null) return null
        val at = path.indexOf(PRODUCTS)
        if (at < 0) return null
        return path
            .substring(at + PRODUCTS.length)
            .removeSuffix(".html")
            .trim('/')
            .takeIf { it.isNotEmpty() }
    }

    private fun JsonElement.asArray(): JsonArray? = this as? JsonArray

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)
            ?.takeIf {
                it.isString
            }?.content
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
}
