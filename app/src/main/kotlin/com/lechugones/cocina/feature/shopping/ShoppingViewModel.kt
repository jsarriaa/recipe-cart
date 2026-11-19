package com.lechugones.cocina.feature.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.data.catalogue.CatalogueRepository
import com.lechugones.cocina.data.shopping.ShoppingRepository
import com.lechugones.cocina.data.shopping.ShoppingState
import com.lechugones.cocina.domain.product.ManualProducts
import com.lechugones.cocina.domain.shopping.ShoppingItem
import com.lechugones.cocina.domain.shopping.ShoppingMode
import com.lechugones.cocina.provider.ProviderProduct
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShoppingUiState(
    val loading: Boolean = true,
    val state: ShoppingState? = null,
    /** The "Añadir" sheet: free text or a catalogue product. */
    val adding: Boolean = false,
    val addQuery: String = "",
    val addResults: List<ProviderProduct> = emptyList(),
    /** Item whose options sheet is open (supermarket, remove). */
    val selected: ShoppingItem? = null,
)

sealed interface ShoppingEvent {
    data class Finished(
        val lots: Int,
    ) : ShoppingEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShoppingViewModel
    @Inject
    constructor(
        private val shopping: ShoppingRepository,
        private val catalogue: CatalogueRepository,
    ) : ViewModel() {
        private val local = MutableStateFlow(ShoppingUiState())
        private val eventChannel = Channel<ShoppingEvent>(Channel.BUFFERED)
        val events: Flow<ShoppingEvent> = eventChannel.receiveAsFlow()

        private val results =
            local.flatMapLatest { if (it.adding) catalogue.search(it.addQuery, storeBrandOnly = false) else flowOf(emptyList()) }

        val uiState: StateFlow<ShoppingUiState> =
            combine(shopping.observe(), local, results) { state, l, found ->
                l.copy(loading = false, state = state, addResults = found)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ShoppingUiState())

        /** "Mercadona", or "A mano" for manual products (resolved by the screen). */
        fun providerDisplayName(id: String): String? = if (id == ManualProducts.PROVIDER_ID) null else catalogue.provider(id)?.displayName

        fun toggle(item: ShoppingItem) {
            viewModelScope.launch { shopping.setChecked(item.key, !item.checked) }
        }

        fun setCheapest(cheapest: Boolean) {
            viewModelScope.launch { shopping.setCheapest(cheapest) }
        }

        fun select(item: ShoppingItem?) {
            local.update { it.copy(selected = item) }
        }

        /** Buy the selected item at [providerId] from now on; null goes back to the list's mode. */
        fun override(providerId: String?) {
            val key = local.value.selected?.overrideKey ?: return
            select(null)
            viewModelScope.launch { shopping.setOverride(key, providerId) }
        }

        fun removeSelected() {
            val id = local.value.selected?.manualId ?: return
            select(null)
            viewModelScope.launch { shopping.removeManual(id) }
        }

        fun removeContribution(id: String) {
            viewModelScope.launch { shopping.removeContribution(id) }
        }

        fun clear() {
            viewModelScope.launch { shopping.clear() }
        }

        fun finish() {
            viewModelScope.launch { eventChannel.send(ShoppingEvent.Finished(shopping.finishShopping())) }
        }

        fun openAdd(open: Boolean) {
            local.update { it.copy(adding = open, addQuery = "") }
        }

        fun setAddQuery(value: String) {
            local.update { it.copy(addQuery = value) }
        }

        fun addText() {
            val text = local.value.addQuery
            openAdd(false)
            viewModelScope.launch { shopping.addManual(text) }
        }

        fun addProduct(product: ProviderProduct) {
            openAdd(false)
            viewModelScope.launch { shopping.addManual(product.product.name, product.product.key) }
        }

        val mode: ShoppingMode get() = uiState.value.state?.mode ?: ShoppingMode.PRIMARY

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
