package com.lechugones.cocina.feature.pantry

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lechugones.cocina.R
import com.lechugones.cocina.ui.components.EmptyState
import com.lechugones.cocina.ui.components.ScreenScaffold

/** Placeholder until phase 5 (SPEC § 4.5). */
@Composable
fun PantryScreen(onOpenSettings: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.nav_pantry), topLevel = true, onOpenSettings = onOpenSettings) { padding ->
        EmptyState(Icons.Outlined.Kitchen, R.string.pantry_empty_title, R.string.pantry_empty_body, modifier = Modifier.padding(padding))
    }
}
