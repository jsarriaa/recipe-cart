package com.lechugones.cocina.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.format.SpanishNumberFormat
import com.lechugones.cocina.domain.product.ManualProducts
import com.lechugones.cocina.domain.units.MeasureUnit
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.ui.format.quantityText
import java.math.BigDecimal

/**
 * A product picture from the supermarket's image CDN, or a neutral placeholder when there is
 * none (unlinked ingredients, manual products) (SPEC § 4.9).
 */
@Composable
fun ProductImage(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val shape =
        modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    if (url == null) {
        Box(shape, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.ShoppingBasket,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2),
            )
        }
    } else {
        AsyncImage(model = url, contentDescription = null, modifier = shape, contentScale = ContentScale.Fit)
    }
}

/** "360 g · 6 ud", "1 kg". */
@Composable
fun productSizeText(item: ProviderProduct): String {
    val product = item.product
    val net = quantityText(product.net.value, product.net.unit)
    val count = product.unitCount?.takeIf { it > 1 && product.net.unit != MeasureUnit.UD }
    return if (count == null) {
        net
    } else {
        net + " · " + quantityText(BigDecimal(count), product.unitCountUnit ?: MeasureUnit.UD)
    }
}

/** "4,17 €/kg", or null when the shop gives no reference price. */
@Composable
fun productReferenceText(item: ProviderProduct): String? {
    val price = item.referencePrice ?: return null
    val format = item.referenceFormat ?: return null
    return stringResource(R.string.catalogue_reference_price, SpanishNumberFormat.money(price), format)
}

@Composable
fun productPriceText(item: ProviderProduct): String =
    item.product.price?.let(SpanishNumberFormat::money) ?: stringResource(R.string.catalogue_no_price)

/** Supermarket names by provider id, provided by the app shell; empty in previews. */
val LocalSupermarketNames = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** "Día", "Mercadona", or "A mano"; null when only one supermarket is installed (no need to say). */
@Composable
fun supermarketLabel(item: ProviderProduct): String? {
    val names = LocalSupermarketNames.current
    if (names.size < 2) return null
    val id = item.product.key.providerId
    return if (id == ManualProducts.PROVIDER_ID) stringResource(R.string.cost_provider_manual) else names[id]
}

/** One product in a list: picture, name, supermarket, size, price and price per unit. */
@Composable
fun ProductListItem(
    item: ProviderProduct,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProductImage(item.imageUrl, 56.dp)
        Column(Modifier.weight(1f)) {
            Text(item.product.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(supermarketLabel(item), productSizeText(item)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            supporting?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(productPriceText(item), style = MaterialTheme.typography.titleMedium)
            productReferenceText(item)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
