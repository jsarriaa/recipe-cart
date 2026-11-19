package com.lechugones.cocina.feature.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.data.catalogue.CatalogueRepository
import com.lechugones.cocina.data.catalogue.ProductLinker
import com.lechugones.cocina.data.pantry.PantryRepository
import com.lechugones.cocina.domain.pantry.PantryLocation
import com.lechugones.cocina.domain.pantry.PantryLot
import com.lechugones.cocina.domain.recipe.IngredientLink
import com.lechugones.cocina.domain.recipe.NameKey
import com.lechugones.cocina.provider.ProviderProduct
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** One lot as the list shows it. */
data class LotRow(
    val lot: PantryLot,
    val imageUrl: String?,
)

/** The list groups of SPEC § 4.5, in display order. */
enum class PantryGroup { OPENED, SEALED, STAPLES }

data class PantryUiState(
    val loading: Boolean = true,
    val groups: Map<PantryGroup, List<LotRow>> = emptyMap(),
    val total: Int = 0,
    val query: String = "",
    val location: PantryLocation? = null,
    /** The lot editor, open while not null. */
    val editing: LotForm? = null,
    val choosingProduct: Boolean = false,
    val productQuery: String = "",
    val productResults: List<ProviderProduct> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PantryViewModel
    @Inject
    constructor(
        private val pantry: PantryRepository,
        private val linker: ProductLinker,
        private val catalogue: CatalogueRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val location = MutableStateFlow<PantryLocation?>(null)
        private val local = MutableStateFlow(PantryUiState())

        private val rows: Flow<List<LotRow>> =
            pantry.observeLots().mapLatest { lots ->
                val images = linker.productsFor(lots.mapNotNull { it.product }.map { IngredientLink(it) })
                lots.map { LotRow(it, it.product?.let { key -> images[key]?.imageUrl }) }
            }

        private val productResults =
            local
                .map { it.productQuery to it.choosingProduct }
                .flatMapLatest { (q, open) -> if (open) catalogue.search(q, storeBrandOnly = false) else flowOf(emptyList()) }

        val uiState: StateFlow<PantryUiState> =
            combine(rows, query, location, local, productResults) { all, q, loc, l, found ->
                val words = NameKey.of(q).split(' ').filter { it.isNotBlank() }
                val visible =
                    all.filter { row ->
                        (loc == null || row.lot.location == loc) && words.all { it in row.lot.nameKey }
                    }
                l.copy(
                    loading = false,
                    groups = visible.groupBy(::groupOf).toSortedMap(compareBy { it.ordinal }),
                    total = all.size,
                    query = q,
                    location = loc,
                    productResults = found,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PantryUiState())

        private fun groupOf(row: LotRow): PantryGroup =
            when {
                row.lot.presenceOnly -> PantryGroup.STAPLES
                row.lot.opened -> PantryGroup.OPENED
                else -> PantryGroup.SEALED
            }

        fun setQuery(value: String) {
            query.value = value
        }

        fun setLocation(value: PantryLocation?) {
            location.value = if (location.value == value) null else value
        }

        // --- Adding ------------------------------------------------------------------------

        /** "Añadir a mano": a generic lot the user names. */
        fun addManual() {
            local.update { it.copy(editing = LotForm(pantry.newId(), isNew = true, addedAt = Instant.now(clock)), choosingProduct = false) }
        }

        fun openProductPicker(open: Boolean) {
            local.update { it.copy(choosingProduct = open, productQuery = "") }
        }

        fun setProductQuery(value: String) {
            local.update { it.copy(productQuery = value) }
        }

        /** A full sealed package of [product]. */
        fun addProduct(product: ProviderProduct) {
            val lot = pantry.newLotFor(product.product)
            local.update { it.copy(editing = LotForm.fromLot(lot, isNew = true), choosingProduct = false) }
        }

        // --- Editing -----------------------------------------------------------------------

        fun edit(lot: PantryLot) {
            local.update { it.copy(editing = LotForm.fromLot(lot)) }
        }

        fun updateForm(form: LotForm) {
            local.update { it.copy(editing = form) }
        }

        fun closeEditor() {
            local.update { it.copy(editing = null) }
        }

        fun saveForm() {
            val form = local.value.editing ?: return
            val lot = form.toLot(Instant.now(clock)) ?: return
            closeEditor()
            viewModelScope.launch { pantry.save(lot) }
        }

        fun deleteEditing() {
            val form = local.value.editing ?: return
            closeEditor()
            viewModelScope.launch { pantry.delete(form.id) }
        }

        /** "Se ha acabado" on a presence-only staple. */
        fun finished(lot: PantryLot) {
            viewModelScope.launch { pantry.delete(lot.id) }
        }

        /** Marks a lot as opened, or back to sealed. Opened lots are used first when cooking. */
        fun toggleOpened(lot: PantryLot) {
            viewModelScope.launch {
                pantry.save(
                    if (lot.opened) lot.copy(opened = false, openedAt = null) else lot.copy(opened = true, openedAt = Instant.now(clock)),
                )
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
