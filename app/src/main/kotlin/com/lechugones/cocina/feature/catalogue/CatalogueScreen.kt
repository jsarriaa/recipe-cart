package com.lechugones.cocina.feature.catalogue

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.runtime.Composable
import com.lechugones.cocina.R
import com.lechugones.cocina.ui.components.EmptyState

/** Placeholder until phase 3 (SPEC § 4.1). */
@Composable
fun CatalogueScreen() {
    EmptyState(Icons.Outlined.Storefront, R.string.catalogue_empty_title, R.string.catalogue_empty_body)
}
