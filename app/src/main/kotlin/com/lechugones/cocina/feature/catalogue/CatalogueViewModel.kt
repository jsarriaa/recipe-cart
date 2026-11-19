package com.lechugones.cocina.feature.catalogue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.data.catalogue.CatalogueRepository
import com.lechugones.cocina.data.catalogue.CatalogueStatus
import com.lechugones.cocina.data.catalogue.SyncFailure
import com.lechugones.cocina.data.catalogue.SyncState
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.settings.AppSettings
import com.lechugones.cocina.provider.ProviderError
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.provider.ProviderResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CatalogueUiState(
    /** The supermarket whose catalogue status and download are shown. */
    val providerId: String = AppSettings.DEFAULT_PROVIDER,
    val providerName: String = "",
    /** Active supermarkets with an installed provider: id to display name, for the chips. */
    val providers: List<Pair<String, String>> = emptyList(),
    val status: CatalogueStatus = CatalogueStatus(0, null),
    val sync: SyncState = SyncState.Idle,
    val query: String = "",
    val storeBrandOnly: Boolean = false,
    val results: List<ProviderProduct> = emptyList(),
    val postalCode: String? = null,
    /** The postal code dialog is open; a sync starts once a code is saved. */
    val askingPostalCode: Boolean = false,
    val selected: ProviderProduct? = null,
    val refreshing: Boolean = false,
    /** Outcome of the last price refresh, shown inside the product sheet (a snackbar would be hidden). */
    val refreshOutcome: CatalogueEvent? = null,
)

sealed interface CatalogueEvent {
    data class SyncEnded(
        val state: SyncState,
    ) : CatalogueEvent

    data object PriceUpdated : CatalogueEvent

    data object ProductGone : CatalogueEvent

    data class RefreshFailed(
        val error: ProviderError,
    ) : CatalogueEvent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class CatalogueViewModel
    @Inject
    constructor(
        private val catalogue: CatalogueRepository,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val selectedKey = MutableStateFlow<ProductKey?>(null)
        private val local = MutableStateFlow(LocalState())
        private val eventChannel = Channel<CatalogueEvent>(Channel.BUFFERED)
        val events: Flow<CatalogueEvent> = eventChannel.receiveAsFlow()

        private data class LocalState(
            val askingPostalCode: Boolean = false,
            val refreshing: Boolean = false,
            val refreshOutcome: CatalogueEvent? = null,
        )

        /** Chosen with the chips; null follows the primary supermarket. */
        private val chosen = MutableStateFlow<String?>(null)

        private val providerId =
            combine(settings.settings, chosen) { s, picked ->
                picked?.takeIf { it in s.activeProviders && catalogue.provider(it) != null } ?: s.primaryProvider
            }.distinctUntilChanged()

        /** Search follows the chosen supermarket chip (it used to search them all, Mercadona first). */
        private val results =
            combine(query.debounce(SEARCH_DEBOUNCE_MILLIS), settings.settings.map { it.storeBrandOnly }, providerId) { q, brand, id ->
                Triple(q, brand, id)
            }.distinctUntilChanged()
                .flatMapLatest { (q, brand, id) -> catalogue.search(q, brand, onlyProvider = id) }

        private val selected = selectedKey.flatMapLatest { key -> key?.let(catalogue::observeProduct) ?: flowOf(null) }

        private val core =
            combine(
                settings.settings,
                providerId,
                providerId.flatMapLatest(catalogue::status),
                catalogue.syncState,
            ) { s, id, status, sync ->
                CatalogueUiState(
                    providerId = id,
                    providerName = catalogue.provider(id)?.displayName.orEmpty(),
                    providers =
                        (listOf(s.primaryProvider) + s.activeProviders.sorted())
                            .distinct()
                            .mapNotNull { pid -> catalogue.provider(pid)?.let { pid to it.displayName } },
                    status = status,
                    sync = sync,
                    storeBrandOnly = s.storeBrandOnly,
                    postalCode = s.postalCode,
                )
            }

        val uiState: StateFlow<CatalogueUiState> =
            combine(core, query, results, selected, local) { base, q, found, product, l ->
                base.copy(
                    query = q,
                    results = found,
                    selected = product,
                    askingPostalCode = l.askingPostalCode,
                    refreshing = l.refreshing,
                    refreshOutcome = l.refreshOutcome,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CatalogueUiState())

        init {
            // Report the end of a sync once, then clear it so it is not shown again.
            viewModelScope.launch {
                catalogue.syncState.collect { state ->
                    if (state is SyncState.Finished || state is SyncState.Failed || state is SyncState.Cancelled) {
                        if (state is SyncState.Failed && state.reason == SyncFailure.NoPostalCode) {
                            local.update { it.copy(askingPostalCode = true) }
                        } else {
                            eventChannel.send(CatalogueEvent.SyncEnded(state))
                        }
                        catalogue.acknowledgeSync()
                    }
                }
            }
        }

        fun onQueryChange(value: String) {
            query.value = value
        }

        fun setStoreBrandOnly(enabled: Boolean) {
            viewModelScope.launch { settings.update { it.copy(storeBrandOnly = enabled) } }
        }

        fun startSync() {
            viewModelScope.launch {
                if (settings.settings.first().postalCode == null) {
                    local.update { it.copy(askingPostalCode = true) }
                } else {
                    catalogue.startSync(uiState.value.providerId)
                }
            }
        }

        fun cancelSync() = catalogue.cancelSync()

        fun chooseProvider(id: String) {
            chosen.value = id
        }

        fun dismissPostalCode() {
            local.update { it.copy(askingPostalCode = false) }
        }

        /** Saves a valid code chosen by the user and starts the download it was asked for. */
        fun savePostalCode(code: String) {
            if (!AppSettings.isValidPostalCode(code)) return
            viewModelScope.launch {
                settings.update { it.copy(postalCode = code) }
                local.update { it.copy(askingPostalCode = false) }
                catalogue.startSync(uiState.value.providerId)
            }
        }

        fun select(product: ProviderProduct?) {
            selectedKey.value = product?.product?.key
            local.update { it.copy(refreshOutcome = null) }
        }

        fun refreshSelected() {
            val key = selectedKey.value ?: return
            if (local.value.refreshing) return
            local.update { it.copy(refreshing = true, refreshOutcome = null) }
            viewModelScope.launch {
                var outcome: CatalogueEvent? = null
                try {
                    outcome =
                        when (val result = catalogue.refreshProduct(key)) {
                            is ProviderResult.Success -> {
                                if (result.value ==
                                    null
                                ) {
                                    CatalogueEvent.ProductGone
                                } else {
                                    CatalogueEvent.PriceUpdated
                                }
                            }

                            is ProviderResult.Failure -> {
                                CatalogueEvent.RefreshFailed(result.error)
                            }
                        }
                } finally {
                    local.update { it.copy(refreshing = false, refreshOutcome = outcome) }
                }
            }
        }

        private companion object {
            const val SEARCH_DEBOUNCE_MILLIS = 250L
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
