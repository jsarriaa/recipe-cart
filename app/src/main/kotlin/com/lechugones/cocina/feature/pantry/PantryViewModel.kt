package com.lechugones.cocina.feature.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.data.catalogue.CatalogueRepository
import com.lechugones.cocina.data.catalogue.ProductLinker
import com.lechugones.cocina.data.pantry.PantryRepository
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.pantry.PantryLocation
import com.lechugones.cocina.domain.pantry.PantryLot
import com.lechugones.cocina.domain.pantry.UseSoon
import com.lechugones.cocina.domain.pantry.UseSoonStatus
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
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One lot as the list shows it. */
data class LotRow(
    val lot: PantryLot,
    val status: UseSoonStatus,
    val imageUrl: String?,
)

/** The list groups of SPEC § 4.5, in display order. */
enum class PantryGroup { USE_SOON, OPENED, SEALED, STAPLES }

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
        private val settings: SettingsRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val zone: ZoneId = ZoneId.systemDefault()
        private val query = MutableStateFlow("")
        private val location = MutableStateFlow<PantryLocation?>(null)
        private val local = MutableStateFlow(PantryUiState())

        private val rows: Flow<List<LotRow>> =
            combine(pantry.observeLots(), settings.settings.map { it.useSoonWarningDays }) { lots, window -> lots to window }
                .mapLatest { (lots, window) ->
                    val today = LocalDate.now(clock.withZone(zone))
                    val images = linker.productsFor(lots.mapNotNull { it.product }.map { IngredientLink(it) })
                    lots.map { LotRow(it, UseSoon.evaluate(it, today, zone, window), it.product?.let { key -> images[key]?.imageUrl }) }
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
                row.status.useSoon -> PantryGroup.USE_SOON
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

        /** A full sealed package of [product], with its remembered shelf life once opened. */
        fun addProduct(product: ProviderProduct) {
            viewModelScope.launch {
                val lot = pantry.newLotFor(product.product)
                val days = pantry.rememberedShelfLife(product.product.key, product.product.name)
                local.update {
                    it.copy(
                        editing = LotForm.fromLot(lot.copy(openedShelfLifeDays = days), isNew = true),
                        choosingProduct = false,
                    )
                }
            }
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

        /** Saves a valid form. A new generic lot gets the shelf life remembered for its name. */
        fun saveForm() {
            val form = local.value.editing ?: return
            val lot = form.toLot(Instant.now(clock)) ?: return
            closeEditor()
            viewModelScope.launch {
                val days = lot.openedShelfLifeDays ?: if (form.isNew) pantry.rememberedShelfLife(lot.product, lot.name) else null
                pantry.save(lot.copy(openedShelfLifeDays = days))
            }
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

        /** The "Usar pronto" toggle: tag by hand, or clear (an automatic tag stays dismissed). */
        fun toggleUseSoon(row: LotRow) {
            viewModelScope.launch {
                val lot = row.lot
                pantry.save(
                    if (row.status.useSoon) {
                        UseSoon.clear(
                            lot,
                            Instant.now(clock),
                        )
                    } else {
                        lot.copy(useSoonManual = true, useSoonDismissedAt = null)
                    },
                )
            }
        }

        /** "Abrir": the lot is opened now, with the remembered shelf life if it has none. */
        fun markOpened(lot: PantryLot) {
            viewModelScope.launch {
                val days = lot.openedShelfLifeDays ?: pantry.rememberedShelfLife(lot.product, lot.name)
                pantry.save(lot.copy(opened = true, openedAt = Instant.now(clock), openedShelfLifeDays = days))
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
