package com.lechugones.cocina.feature.shopping

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.runtime.Composable
import com.lechugones.cocina.R
import com.lechugones.cocina.ui.components.EmptyState

/** Placeholder until phase 6 (SPEC § 4.6). */
@Composable
fun ShoppingScreen() {
    EmptyState(Icons.Outlined.ShoppingCart, R.string.shopping_empty_title, R.string.shopping_empty_body)
}
