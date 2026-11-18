package com.lechugones.cocina.data.catalogue

import com.lechugones.cocina.data.db.PriceSnapshotEntity
import com.lechugones.cocina.data.db.ProductDao
import com.lechugones.cocina.data.db.ProductEntity
import com.lechugones.cocina.data.di.ApplicationScope
import com.lechugones.cocina.data.settings.SettingsRepository
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.recipe.NameKey
import com.lechugones.cocina.provider.ProviderError
import com.lechugones.cocina.provider.ProviderLocation
import com.lechugones.cocina.provider.ProviderProduct
import com.lechugones.cocina.provider.ProviderResult
import com.lechugones.cocina.provider.SupermarketProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of the on-demand catalogue download (SPEC § 4.1). */
sealed interface SyncState {
    data object Idle : SyncState

    data class Running(
        val providerId: String,
        val categoriesDone: Int,
        val categoriesTotal: Int,
        val products: Int,
    ) : SyncState

    data class Finished(
        val providerId: String,
        val products: Int,
        /** False when some categories failed: products not seen are kept as they were. */
        val complete: Boolean,
    ) : SyncState

    data class Failed(
        val providerId: String,
        val reason: SyncFailure,
        /** Products saved before the failure; they stay in the cache. */
        val products: Int,
    ) : SyncState

    data class Cancelled(
        val providerId: String,
        val products: Int,
    ) : SyncState
}

sealed interface SyncFailure {
    /** Settings has no postal code yet. */
    data object NoPostalCode : SyncFailure

    data object UnknownProvider : SyncFailure

    data class Provider(
        val error: ProviderError,
    ) : SyncFailure
}

data class CatalogueStatus(
    val products: Int,
    val lastFetch: Instant?,
)

/**
 * The cached supermarket catalogue. The network is touched only from [startSync] and
 * [refreshProduct], both started by the user (CLAUDE.md, hard rule 3); searches read Room only.
 */
@Singleton
class CatalogueRepository
    @Inject
    constructor(
        private val providers: Set<@JvmSuppressWildcards SupermarketProvider>,
        private val dao: ProductDao,
        private val settings: SettingsRepository,
        private val locations: ProviderLocationStore,
        private val clock: Clock,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
        val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

        private var syncJob: Job? = null
        private val lock = Mutex()

        /** After a 403 the provider is left alone for a while (docs/providers/README.md, rule 5). */
        private val blockedUntil = mutableMapOf<String, Instant>()

        fun provider(id: String): SupermarketProvider? = providers.firstOrNull { it.id == id }

        fun status(providerId: String): Flow<CatalogueStatus> =
            combine(dao.observeCount(providerId), dao.observeLastFetch(providerId)) { count, last ->
                CatalogueStatus(count, last?.let(Instant::ofEpochMilli))
            }

        /**
         * Products of the active providers matching every word of [query], accent-insensitive.
         * The first (longest) word goes to SQL; the others filter the candidates.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun search(
            query: String,
            storeBrandOnly: Boolean,
            limit: Int = SEARCH_LIMIT,
        ): Flow<List<ProviderProduct>> {
            val words =
                NameKey
                    .of(query)
                    .split(' ')
                    .filter { it.isNotBlank() }
                    .distinct()
            if (words.isEmpty()) return flowOf(emptyList())
            val first = words.maxBy { it.length }
            val activeIds = providers.map { it.id }.toSet()
            val key = NameKey.of(query)
            return settings.settings
                .map { it.activeProviders intersect activeIds }
                .distinctUntilChanged()
                .flatMapLatest { ids -> dao.search(ids, first, storeBrandOnly, CANDIDATES) }
                .map { rows -> rank(rows, words, key, limit) }
        }

        private fun rank(
            rows: List<ProductEntity>,
            words: List<String>,
            key: String,
            limit: Int,
        ): List<ProviderProduct> =
            rows
                .filter { row -> words.all { row.searchKey.contains(it) } }
                .sortedWith(
                    compareBy<ProductEntity>(
                        { !NameKey.of(it.name).startsWith(key) },
                        { !NameKey.of(it.name).contains(key) },
                        { it.name.length },
                    ),
                ).take(limit)
                .mapNotNull { it.toProviderProduct() }

        fun observeProduct(key: ProductKey): Flow<ProviderProduct?> =
            dao.observe(key.providerId, key.productId).map { it?.toProviderProduct() }

        /** Starts a full download of [providerId]'s food catalogue, unless one is running. */
        fun startSync(providerId: String) {
            if (syncJob?.isActive == true) return
            syncJob = scope.launch { lock.withLock { sync(providerId) } }
        }

        fun cancelSync() {
            syncJob?.cancel()
        }

        /** Clears a finished, failed or cancelled state once the UI has shown it. */
        fun acknowledgeSync() {
            if (syncJob?.isActive != true) _syncState.value = SyncState.Idle
        }

        private suspend fun sync(providerId: String) {
            val provider = provider(providerId)
            if (provider == null) {
                _syncState.value = SyncState.Failed(providerId, SyncFailure.UnknownProvider, 0)
                return
            }
            var saved = 0
            try {
                _syncState.value = SyncState.Running(providerId, 0, 0, 0)
                val location =
                    when (val resolved = location(provider)) {
                        is SyncResult.Ok -> resolved.value
                        is SyncResult.Stop -> return fail(providerId, resolved.reason, 0)
                    }
                val categories =
                    when (val result = guarded(provider) { provider.categories(location) }) {
                        is ProviderResult.Success -> result.value
                        is ProviderResult.Failure -> return fail(providerId, SyncFailure.Provider(result.error), 0)
                    }
                val started = Instant.now(clock)
                var complete = true
                categories.forEachIndexed { index, category ->
                    _syncState.value = SyncState.Running(providerId, index, categories.size, saved)
                    when (val result = guarded(provider) { provider.productsIn(category, location) }) {
                        is ProviderResult.Success -> {
                            saved += store(result.value)
                        }

                        is ProviderResult.Failure -> {
                            when (result.error) {
                                // One odd category does not stop the sync.
                                is ProviderError.Parse, is ProviderError.Http, ProviderError.NotServed -> {
                                    complete = false
                                }

                                else -> {
                                    return fail(providerId, SyncFailure.Provider(result.error), saved)
                                }
                            }
                        }
                    }
                }
                if (complete) dao.markUnseenUnavailable(providerId, started.toEpochMilli())
                _syncState.value = SyncState.Finished(providerId, saved, complete)
            } catch (e: CancellationException) {
                _syncState.value = SyncState.Cancelled(providerId, saved)
                throw e
            }
        }

        private fun fail(
            providerId: String,
            reason: SyncFailure,
            saved: Int,
        ) {
            _syncState.value = SyncState.Failed(providerId, reason, saved)
        }

        private suspend fun location(provider: SupermarketProvider): SyncResult<ProviderLocation> {
            val postalCode = settings.settings.first().postalCode ?: return SyncResult.Stop(SyncFailure.NoPostalCode)
            locations.get(provider.id, postalCode)?.let { return SyncResult.Ok(it) }
            return when (val result = guarded(provider) { provider.resolveLocation(postalCode) }) {
                is ProviderResult.Success -> {
                    locations.put(provider.id, result.value)
                    SyncResult.Ok(result.value)
                }

                is ProviderResult.Failure -> {
                    SyncResult.Stop(SyncFailure.Provider(result.error))
                }
            }
        }

        /**
         * Fetches one product again (the "Actualizar precio" action). A product the shop no longer
         * sells is kept but marked unavailable.
         */
        suspend fun refreshProduct(key: ProductKey): ProviderResult<ProviderProduct?> {
            val provider = provider(key.providerId) ?: return ProviderResult.Failure(ProviderError.NotServed)
            val location =
                when (val resolved = location(provider)) {
                    is SyncResult.Ok -> {
                        resolved.value
                    }

                    is SyncResult.Stop -> {
                        val error = (resolved.reason as? SyncFailure.Provider)?.error ?: ProviderError.NotServed
                        return ProviderResult.Failure(error)
                    }
                }
            return when (val result = guarded(provider) { provider.product(key.productId, location) }) {
                is ProviderResult.Failure -> {
                    result
                }

                is ProviderResult.Success -> {
                    val fresh = result.value
                    if (fresh == null) {
                        dao.markUnavailable(key.providerId, key.productId)
                        ProviderResult.Success(null)
                    } else {
                        // The detail endpoint lacks our category path: keep the one from the sync.
                        val previousPath = dao.get(key.providerId, key.productId)?.toProviderProduct()?.categoryPath
                        val merged =
                            if (fresh.categoryPath.isEmpty() &&
                                previousPath != null
                            ) {
                                fresh.copy(categoryPath = previousPath)
                            } else {
                                fresh
                            }
                        store(listOf(merged))
                        ProviderResult.Success(merged)
                    }
                }
            }
        }

        /** Upserts products and records a price snapshot for each new or changed price. */
        private suspend fun store(products: List<ProviderProduct>): Int {
            if (products.isEmpty()) return 0
            val now = Instant.now(clock)
            val providerId =
                products
                    .first()
                    .product.key.providerId
            val previous = dao.prices(providerId).associate { it.productId to it.priceCents }
            val rows = products.map { it.toEntity(now) }
            val snapshots =
                rows
                    .filter { it.priceCents != null && (it.productId !in previous || previous[it.productId] != it.priceCents) }
                    .map { PriceSnapshotEntity(it.providerId, it.productId, it.fetchedAt, checkNotNull(it.priceCents) { "filtered" }) }
            dao.upsert(rows)
            dao.insertSnapshots(snapshots)
            return rows.size
        }

        /** Short-circuits calls while a provider is in its post-403 pause. */
        private suspend fun <T> guarded(
            provider: SupermarketProvider,
            call: suspend () -> ProviderResult<T>,
        ): ProviderResult<T> {
            val now = Instant.now(clock)
            blockedUntil[provider.id]?.let { until ->
                if (now.isBefore(until)) return ProviderResult.Failure(ProviderError.Blocked)
            }
            val result = call()
            if (result is ProviderResult.Failure && result.error == ProviderError.Blocked) {
                blockedUntil[provider.id] = now.plus(BLOCK_PAUSE)
            }
            return result
        }

        private sealed interface SyncResult<out T> {
            data class Ok<T>(
                val value: T,
            ) : SyncResult<T>

            data class Stop(
                val reason: SyncFailure,
            ) : SyncResult<Nothing>
        }

        companion object {
            const val SEARCH_LIMIT = 60
            private const val CANDIDATES = 400
            val BLOCK_PAUSE: Duration = Duration.ofMinutes(30)
        }
    }
