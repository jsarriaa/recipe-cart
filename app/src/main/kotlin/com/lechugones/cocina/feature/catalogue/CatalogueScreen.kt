package com.lechugones.cocina.feature.catalogue

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lechugones.cocina.R
import com.lechugones.cocina.ui.components.EmptyState
import com.lechugones.cocina.ui.components.ScreenScaffold

/** Placeholder until phase 3 (SPEC § 4.1). */
@Composable
fun CatalogueScreen(onOpenSettings: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.nav_catalogue), topLevel = true, onOpenSettings = onOpenSettings) { padding ->
        EmptyState(
            Icons.Outlined.Storefront,
            R.string.catalogue_empty_title,
            R.string.catalogue_empty_body,
            modifier = Modifier.padding(padding),
        )
    }
}
