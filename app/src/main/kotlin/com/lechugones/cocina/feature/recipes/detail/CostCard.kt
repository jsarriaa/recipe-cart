package com.lechugones.cocina.feature.recipes.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lechugones.cocina.R
import com.lechugones.cocina.domain.cost.RecipeCost
import com.lechugones.cocina.domain.product.ManualProducts
import com.lechugones.cocina.domain.recipe.Ingredient
import com.lechugones.cocina.ui.format.Dates
import com.lechugones.cocina.ui.format.costFigure
import com.lechugones.cocina.ui.format.leftoverText

/** Display name of a provider id; manual products are "A mano". */
@Composable
fun providerName(
    id: String,
    names: (String) -> String?,
): String = if (id == ManualProducts.PROVIDER_ID) stringResource(R.string.cost_provider_manual) else names(id) ?: id

/**
 * The cost card of a recipe (SPEC § 4.4): dish cost, per serving, whole packages, leftovers,
 * price date with "Actualizar precios", the include toggles, the supermarket mode and the
 * comparison.
 */
@Composable
fun CostCard(
    loaded: RecipeDetailUiState.Loaded,
    providerNames: (String) -> String?,
    onMode: (CostMode) -> Unit,
    onIncludes: (staples: Boolean, optionals: Boolean) -> Unit,
    onRefresh: () -> Unit,
    onFixMissing: (Ingredient) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cost = loaded.cost ?: return
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.cost_title), style = MaterialTheme.typography.titleMedium)
            if (loaded.providers.size > 1) ModeChips(loaded, providerNames, onMode)

            if (cost.includedCount == 0) {
                Text(stringResource(R.string.cost_nothing), style = MaterialTheme.typography.bodyMedium)
            } else {
                val missing = cost.missingPriceCount > 0
                Figure(stringResource(R.string.cost_dish), costFigure(cost.dishCost, missing, cost.approximate), strong = true)
                Figure(stringResource(R.string.cost_per_serving), costFigure(cost.perServing, missing, cost.approximate))
                Figure(stringResource(R.string.cost_whole_packages), costFigure(cost.wholePackagesCost, missing, cost.approximate))
                if (missing) {
                    val first = cost.lines.first { it.isMissingPrice }.ingredient
                    TextButton(onClick = { onFixMissing(first) }) {
                        Text(pluralStringResource(R.plurals.cost_missing, cost.missingPriceCount, cost.missingPriceCount))
                    }
                }
            }
            Leftovers(cost)

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            PriceDate(loaded, cost, onRefresh)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = loaded.includeStaples,
                    onClick = { onIncludes(!loaded.includeStaples, loaded.includeOptionals) },
                    label = { Text(stringResource(R.string.cost_include_staples)) },
                )
                FilterChip(
                    selected = loaded.includeOptionals,
                    onClick = { onIncludes(loaded.includeStaples, !loaded.includeOptionals) },
                    label = { Text(stringResource(R.string.cost_include_optionals)) },
                )
            }
            loaded.comparison?.let { comparison ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.cost_comparison), style = MaterialTheme.typography.titleSmall)
                comparison.byProvider.forEach { (id, providerCost) ->
                    ComparisonRow(providerName(id, providerNames), providerCost)
                }
                ComparisonRow(stringResource(R.string.cost_best_combination), comparison.bestCombination)
            }
        }
    }
}

@Composable
private fun ModeChips(
    loaded: RecipeDetailUiState.Loaded,
    providerNames: (String) -> String?,
    onMode: (CostMode) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = loaded.mode == CostMode.Primary,
            onClick = { onMode(CostMode.Primary) },
            label = { Text(stringResource(R.string.cost_mode_primary, providerName(loaded.primaryProvider, providerNames))) },
        )
        loaded.providers.filter { it != loaded.primaryProvider }.forEach { id ->
            FilterChip(
                selected = loaded.mode == CostMode.Provider(id),
                onClick = { onMode(CostMode.Provider(id)) },
                label = { Text(providerName(id, providerNames)) },
            )
        }
        FilterChip(
            selected = loaded.mode == CostMode.Cheapest,
            onClick = { onMode(CostMode.Cheapest) },
            label = { Text(stringResource(R.string.cost_mode_cheapest)) },
        )
    }
}

@Composable
private fun Figure(
    label: String,
    value: String,
    strong: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (strong) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = if (strong) FontWeight.SemiBold else null,
        )
    }
}

@Composable
private fun Leftovers(cost: RecipeCost) {
    val leftovers = cost.purchases.filter { it.leftoverRatio.signum() > 0 }
    if (leftovers.isEmpty()) return
    Text(stringResource(R.string.cost_leftovers), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    leftovers.forEach { purchase ->
        leftoverText(purchase.leftoverRatio, purchase.leftover, purchase.leftoverUnit, purchase.product.packaging)?.let { amount ->
            Text(
                stringResource(R.string.cost_leftover_line, purchase.product.name, amount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PriceDate(
    loaded: RecipeDetailUiState.Loaded,
    cost: RecipeCost,
    onRefresh: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (cost.stale) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = stringResource(R.string.cost_stale),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp).padding(end = 4.dp),
            )
        }
        Text(
            cost.oldestPriceAt?.let {
                stringResource(
                    R.string.cost_prices_from,
                    Dates.date(it),
                )
            } ?: stringResource(R.string.cost_no_prices),
            style = MaterialTheme.typography.bodySmall,
            color = if (cost.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (loaded.products.isNotEmpty()) {
            TextButton(onClick = onRefresh, enabled = !loaded.refreshingPrices) { Text(stringResource(R.string.cost_refresh)) }
        }
    }
    if (loaded.refreshingPrices) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun ComparisonRow(
    name: String,
    cost: RecipeCost,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.cost_coverage, cost.pricedCount, cost.includedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(costFigure(cost.wholePackagesCost, cost.missingPriceCount > 0, cost.approximate), style = MaterialTheme.typography.bodyLarge)
    }
}
