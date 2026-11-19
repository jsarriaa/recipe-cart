package com.lechugones.cocina.feature.shopping

import com.lechugones.cocina.domain.format.SpanishNumberFormat
import com.lechugones.cocina.domain.money.Money
import com.lechugones.cocina.domain.shopping.ShoppingItem
import com.lechugones.cocina.domain.shopping.ShoppingList

/**
 * The words of the shared list, resolved from string resources by the caller (AC6.2), so the
 * layout below can be tested without Android.
 */
data class ShareLabels(
    /** "Lista de la compra — 18/11/2026". */
    val title: String,
    /** "SIN SUPERMERCADO". */
    val noSupermarket: String,
    /** Upper-case supermarket name for a provider id. */
    val providerName: (String) -> String,
    /** "Total estimado: %1$s", "%1$s (%2$s)". */
    val total: (money: String, missing: Int) -> String,
    /** "6 ud", "≈ 550 g": package size of an item's product, or the amount needed for generic items. */
    val size: (ShoppingItem) -> String?,
)

/** "Compartir" (SPEC § 4.6): the list as plain text in the documented format. */
object ShoppingShareText {
    fun format(
        list: ShoppingList,
        labels: ShareLabels,
    ): String =
        buildString {
            appendLine(labels.title)
            for (group in list.groups) {
                appendLine()
                val name = group.providerId?.let(labels.providerName) ?: labels.noSupermarket
                if (group.providerId == null || group.items.none { it.cost != null }) {
                    appendLine(name)
                } else {
                    appendLine("$name (${money(group.subtotal, group.approximate)})")
                }
                group.items.forEach { appendLine(line(it, labels)) }
            }
            appendLine()
            append(labels.total(money(list.total, list.approximate), list.missingPrices))
        }

    private fun line(
        item: ShoppingItem,
        labels: ShareLabels,
    ): String {
        val box = if (item.checked) "[x]" else "[ ]"
        val count = item.packages?.let { "$it × " }.orEmpty()
        val size = labels.size(item)?.let { " ($it)" }.orEmpty()
        val price = item.cost?.let { " — " + money(it, item.approximate) }.orEmpty()
        return "$box $count${item.name}$size$price"
    }

    private fun money(
        value: Money,
        approximate: Boolean,
    ): String = (if (approximate) "≈ " else "") + SpanishNumberFormat.money(value)
}
