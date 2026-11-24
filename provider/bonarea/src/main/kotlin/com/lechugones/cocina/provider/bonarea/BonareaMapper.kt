package com.lechugones.cocina.provider.bonarea

import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.product.BrandKind
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.Quantity
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderProduct
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Locale

/**
 * Maps bonÀrea articles to [ProviderProduct] (docs/providers/bonarea.md § Mapping). The unit-price
 * text rules (`€/ml.` is per litre) follow supermercapy (third_party/supermercapy, MIT).
 */
object BonareaMapper {
    const val PROVIDER_ID = "bonarea"
    const val BRAND = "bonÀrea"
    const val IMAGES = "https://images.bonarea.com/"
    val STORE_BRANDS = setOf(BRAND)

    /** Businesses whose articles are food: 13 the supermarket, 99 frozen food and ice cream. */
    private val FOOD_PREFIXES = setOf("13", "99")

    /** Tags that mark bonÀrea's own production. */
    private val OWN = setOf("BONAREA", "GARANTIA_BONAREA")

    private val pack = Regex("""(\d+)\s*u\.?\s*de\s*(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)
    private val packOf = Regex("""paq\.?\s*(?:de\s*)?(\d+)""", RegexOption.IGNORE_CASE)
    private val size = Regex("""(\d+(?:[.,]\d+)?)\s*(kg|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)
    private val pieces = Regex("""^\s*(?:pack\s*)?(\d+)\s*u\.?\s*$""", RegexOption.IGNORE_CASE)
    private val approx = Regex("""aprox""", RegexOption.IGNORE_CASE)
    private val reference = Regex("""(\d+(?:\.\d{3})*,\d+)\s*€\s*/\s*(kg|k|l|ml|u|d|m)\.*""", RegexOption.IGNORE_CASE)

    fun isFood(article: BonareaArticle): Boolean = article.identifier.substringBefore('*') in FOOD_PREFIXES

    /** Null for non-food articles, articles without a price, or without a usable size. */
    fun map(
        article: BonareaArticle,
        fetchedAt: Instant,
    ): ProviderProduct? {
        if (!isFood(article) || article.isValid == false) return null
        val euros = article.priceToPay?.takeIf { it > 0 } ?: return null
        val name = unescape(article.description).trim()
        val unitText = article.measurementUnit.orEmpty()
        val net = parseSize(unitText) ?: parseSize(name) ?: return null
        val own = article.caracteristiques.any { it.descripcio in OWN }
        val product =
            Product(
                key = ProductKey(PROVIDER_ID, article.identifier),
                name = name,
                net = net.first,
                price = Money.ofEuros(BigDecimal.valueOf(euros)),
                brand = if (own) BRAND else null,
                brandKind = if (own) BrandKind.STORE else BrandKind.NATIONAL,
                unitCount = net.second ?: countInName(name),
                approx = approx.containsMatchIn(unitText) || article.isAnApproximatePrice == true,
                fetchedAt = fetchedAt,
                available = article.itsOnStock ?: true,
            )
        val ref = referencePrice(article.unitPrice)
        return ProviderProduct(
            product = product,
            imageUrl = article.image.firstOrNull()?.let { IMAGES + it + IMAGE_SIZE },
            webUrl = article.urlFriendly,
            referencePrice = ref?.first,
            referenceFormat = ref?.second,
        )
    }

    /** `6 l`, `aprox. 187 g`, `16 u.`, `paq. 3 u. de 200 ml` → total and piece count. */
    private fun parseSize(text: String): Pair<Quantity, Int?>? {
        pack.find(text)?.let { m ->
            val count = m.groupValues[1].toInt()
            val each = base(m.groupValues[2], m.groupValues[3])
            if (each != null && count in 1..MAX_UNITS) {
                return checkNotNull(base(m.groupValues[2], m.groupValues[3], BigDecimal(count))) { "pack size" } to count.takeIf { it > 1 }
            }
        }
        size.find(text)?.let { m -> base(m.groupValues[1], m.groupValues[2])?.let { return it to null } }
        pieces.find(text)?.let { m ->
            val count = m.groupValues[1].toInt()
            if (count in 1..MAX_UNITS) return Quantity(Amount.of(count), MeasureUnit.UD) to count.takeIf { it > 1 }
        }
        return null
    }

    private fun countInName(name: String): Int? =
        (pack.find(name)?.groupValues?.get(1) ?: packOf.find(name)?.groupValues?.get(1))
            ?.toIntOrNull()
            ?.takeIf { it in 2..MAX_UNITS }

    /** [times] multiplies before rounding: 2 x 112,5 g is 225 g, not 2 x 113 g. */
    private fun base(
        value: String,
        unit: String,
        times: BigDecimal = BigDecimal.ONE,
    ): Quantity? {
        val (base, factor) =
            when (unit.lowercase(Locale.ROOT)) {
                "g" -> MeasureUnit.G to BigDecimal.ONE
                "kg" -> MeasureUnit.G to BigDecimal(1000)
                "ml" -> MeasureUnit.ML to BigDecimal.ONE
                "cl" -> MeasureUnit.ML to BigDecimal.TEN
                "l" -> MeasureUnit.ML to BigDecimal(1000)
                else -> return null
            }
        val amount = BigDecimal(value.replace(',', '.')).multiply(factor).multiply(times).setScale(0, RoundingMode.HALF_UP)
        return if (amount.signum() > 0) Quantity(Amount.of(amount), base) else null
    }

    /** `1,12 €/l` → 1,12 € per L; `€/ml.` is per litre too (supermercapy); `d.` (doses) and `m` are dropped. */
    private fun referencePrice(text: String?): Pair<Money, String>? {
        val m = reference.find(text ?: return null) ?: return null
        val format =
            when (m.groupValues[2].lowercase(Locale.ROOT)) {
                "kg", "k" -> "kg"
                "l", "ml" -> "L"
                "u" -> "ud"
                else -> return null
            }
        val euros = BigDecimal(m.groupValues[1].replace(".", "").replace(',', '.'))
        return Money.ofEuros(euros) to format
    }

    /** The few HTML entities bonÀrea leaves in names. */
    fun unescape(text: String): String =
        text
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    /** The CDN resizes on request; the originals are about 1 MB. */
    private const val IMAGE_SIZE = "?width=300&height=300"
    private const val MAX_UNITS = 100
}
