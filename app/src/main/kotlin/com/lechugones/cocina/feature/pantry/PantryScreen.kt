package com.lechugones.cocina.feature.pantry

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.runtime.Composable
import com.lechugones.cocina.R
import com.lechugones.cocina.ui.components.EmptyState

/** Placeholder until phase 5 (SPEC § 4.5). */
@Composable
fun PantryScreen() {
    EmptyState(Icons.Outlined.Kitchen, R.string.pantry_empty_title, R.string.pantry_empty_body)
}
