package com.lechugones.cocina.feature.cookable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lechugones.cocina.data.catalogue.ProductLinker
import com.lechugones.cocina.data.pantry.PantryRepository
import com.lechugones.cocina.data.recipe.RecipeLibrary
import com.lechugones.cocina.data.recipe.RecipeRepository
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.cost.CostOptions
import com.lechugones.cocina.domain.cost.ProductSelection
import com.lechugones.cocina.domain.cost.RecipeCostCalculator
import com.lechugones.cocina.domain.pantry.CookCoverage
import com.lechugones.cocina.domain.pantry.RecipeCoverage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class CookableUiState(
    val loading: Boolean = true,
    val coverage: List<RecipeCoverage> = emptyList(),
    /** "Aprovechar lo que caduca": only recipes that use "usar pronto" food. */
    val onlyUseSoon: Boolean = false,
    val pantryEmpty: Boolean = false,
)

/** "¿Qué puedo cocinar?" (SPEC § 4.5): every recipe against the pantry. Offline. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CookableViewModel
    @Inject
    constructor(
        library: RecipeLibrary,
        private val recipes: RecipeRepository,
        pantry: PantryRepository,
        private val linker: ProductLinker,
        settings: SettingsRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val zone = ZoneId.systemDefault()
        private val onlyUseSoon = MutableStateFlow(false)

        private val coverage =
            combine(library.observeSummaries(), pantry.observeLots(), settings.settings) { _, lots, s -> lots to s }
                .mapLatest { (lots, s) ->
                    val all = recipes.getAll()
                    val links = all.flatMap { r -> r.ingredients.flatMap { it.links } }
                    val products = linker.productsFor(links).mapValues { it.value.product }
                    val options =
                        CostOptions(
                            Instant.now(clock),
                            s.includeStaplesInCost,
                            s.includeOptionalsInCost,
                            Duration.ofDays(s.staleAfterDays.toLong()),
                        )
                    val selection = ProductSelection.SingleProvider(s.primaryProvider)
                    val costs = all.associate { it.id to RecipeCostCalculator.compute(it, it.servings, products, selection, options) }
                    val remembered = linker.rememberedProducts(all.flatMap { r -> r.ingredients.map { it.nameKey } })
                    lots.isEmpty() to
                        CookCoverage.evaluate(
                            all,
                            lots,
                            LocalDate.now(clock.withZone(zone)),
                            zone,
                            s.useSoonWarningDays,
                            costs,
                        ) { remembered[it].orEmpty() }
                }

        val uiState: StateFlow<CookableUiState> =
            combine(coverage, onlyUseSoon) { (empty, list), only ->
                CookableUiState(
                    loading = false,
                    coverage = if (only) list.filter { it.usesSoon.isNotEmpty() } else list,
                    onlyUseSoon = only,
                    pantryEmpty = empty,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CookableUiState())

        fun toggleOnlyUseSoon() {
            onlyUseSoon.value = !onlyUseSoon.value
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
