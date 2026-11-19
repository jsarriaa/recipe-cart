package com.lechugones.cocina.ui

import androidx.lifecycle.ViewModel
import com.lechugones.cocina.provider.SupermarketProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** State of the app shell. */
@HiltViewModel
class ShellViewModel
    @Inject
    constructor(
        providers: Set<@JvmSuppressWildcards SupermarketProvider>,
    ) : ViewModel() {
        /** Supermarket display names by provider id, for labels on product rows. */
        val supermarketNames: Map<String, String> = providers.associate { it.id to it.displayName }
    }
