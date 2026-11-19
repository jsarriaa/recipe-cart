package com.lechugones.cocina.data.pantry

import com.lechugones.cocina.data.db.CookDeductionEntity
import com.lechugones.cocina.data.db.CookEventEntity
import com.lechugones.cocina.data.db.PantryDao
import com.lechugones.cocina.data.db.PantryLotEntity
import com.lechugones.cocina.data.db.ShelfLifeMemoryEntity
import com.lechugones.cocina.domain.pantry.CookPlanner
import com.lechugones.cocina.domain.pantry.LotDeduction
import com.lechugones.cocina.domain.pantry.PantryLocation
import com.lechugones.cocina.domain.pantry.PantryLot
import com.lechugones.cocina.domain.product.Product
import com.lechugones.cocina.domain.product.ProductKey
import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.recipe.Recipe
import com.lechugones.cocina.domain.units.MeasureUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The pantry ("Despensa") and the cook history behind "Lo he cocinado" (SPEC § 4.5). */
@Singleton
class PantryRepository
    @Inject
    constructor(
        private val dao: PantryDao,
        private val clock: Clock,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        fun observeLots(): Flow<List<PantryLot>> = dao.observeLots().map { rows -> rows.mapNotNull { it.toDomain() } }

        suspend fun lots(): List<PantryLot> = dao.lots().mapNotNull { it.toDomain() }

        suspend fun lot(id: String): PantryLot? = dao.lot(id)?.toDomain()

        /** Recipe id → last time it was cooked (not undone), for "Cocinadas hace poco". */
        fun observeLastCooked(): Flow<Map<String, Instant>> =
            dao.observeLastCooked().map { rows -> rows.associate { it.recipeId to Instant.ofEpochMilli(it.cookedAt) } }

        /**
         * Saves a new or edited lot. A changed best-before date clears a dismissed "Usar pronto"
         * (SPEC § 4.5: the tag comes back when its trigger changes), and a shelf life after
         * opening is remembered for the product or name so it is asked only once.
         */
        suspend fun save(lot: PantryLot): PantryLot {
            val previous = dao.lot(lot.id)?.toDomain()
            val toSave = if (previous != null && previous.bestBefore != lot.bestBefore) lot.copy(useSoonDismissedAt = null) else lot
            dao.upsertLots(listOf(toSave.toEntity()))
            toSave.openedShelfLifeDays?.let { days ->
                if (days != previous?.openedShelfLifeDays) dao.rememberShelfLife(ShelfLifeMemoryEntity(shelfLifeKey(toSave), days))
            }
            return toSave
        }

        suspend fun delete(id: String) = dao.deleteLots(listOf(id))

        /** The remembered "se conserva N días" for a product, else for the name. */
        suspend fun rememberedShelfLife(
            product: ProductKey?,
            name: String,
        ): Int? =
            product?.let { dao.shelfLife(it.toString()) }
                ?: dao.shelfLife(
                    com.lechugones.cocina.domain.recipe.NameKey
                        .of(name),
                )

        /** A full, sealed package of [product], measured like the cost engine measures it (pieces first). */
        fun newLotFor(
            product: Product,
            bestBefore: LocalDate? = null,
            location: PantryLocation? = null,
        ): PantryLot {
            val measure = product.measures().first()
            return PantryLot(
                id = newId(),
                name = product.name,
                addedAt = Instant.now(clock),
                product = product.key,
                quantity = Amount.of(measure.usable),
                unit = measure.unit,
                packageQuantity = Amount.of(measure.usable),
                bestBefore = bestBefore,
                location = location,
            )
        }

        /**
         * Applies the confirmed deductions of "Lo he cocinado" and records the event, keeping a
         * snapshot of every lot it touches. Returns the event id for "Deshacer".
         */
        suspend fun cook(
            recipe: Recipe,
            servings: Int,
            takes: List<LotDeduction>,
            finished: Set<String>,
        ): String {
            val now = Instant.now(clock)
            val lots = lots()
            val change = CookPlanner.apply(lots, takes, finished, now)
            val touched = (change.updated.map { it.id } + change.removed).toSet()
            val deducted = takes.groupBy { it.lotId }.mapValues { (_, t) -> t.sumOf { it.amount.milli } }
            val eventId = newId()
            dao.cook(
                event = CookEventEntity(eventId, recipe.id, recipe.title, servings, now.toEpochMilli(), undone = false),
                deductions =
                    lots.filter { it.id in touched }.map {
                        CookDeductionEntity(
                            eventId,
                            it.id,
                            json.encodeToString(PantryLotEntity.serializer(), it.toEntity()),
                            deducted[it.id] ?: 0,
                        )
                    },
                updated = change.updated.map { it.toEntity() },
                removed = change.removed,
            )
            return eventId
        }

        /** Restores the pantry exactly as it was before [eventId]. Returns false if it was already undone. */
        suspend fun undo(eventId: String): Boolean {
            val event = dao.event(eventId) ?: return false
            if (event.undone) return false
            val snapshots = dao.deductions(eventId).map { json.decodeFromString(PantryLotEntity.serializer(), it.lotSnapshotJson) }
            dao.undo(eventId, snapshots)
            return true
        }

        fun newId(): String = UUID.randomUUID().toString()

        private fun shelfLifeKey(lot: PantryLot): String = lot.product?.toString() ?: lot.nameKey
    }

internal fun PantryLot.toEntity() =
    PantryLotEntity(
        id = id,
        providerId = product?.providerId,
        productId = product?.productId,
        name = name,
        nameKey = nameKey,
        quantityMilli = quantity?.milli,
        unit = unit?.code,
        packageQuantityMilli = packageQuantity?.milli,
        opened = opened,
        openedAt = openedAt?.toEpochMilli(),
        addedAt = addedAt.toEpochMilli(),
        bestBefore = bestBefore?.toEpochDay(),
        location = location?.code,
        presenceOnly = presenceOnly,
        useSoonManual = useSoonManual,
        useSoonDismissedAt = useSoonDismissedAt?.toEpochMilli(),
        openedShelfLifeDays = openedShelfLifeDays,
    )

/** Rows that no longer make a valid lot (unknown unit, blank name) map to null and are skipped. */
internal fun PantryLotEntity.toDomain(): PantryLot? {
    val measureUnit = unit?.let { MeasureUnit.fromCode(it) ?: return null }
    if (name.isBlank()) return null
    return runCatching {
        PantryLot(
            id = id,
            name = name,
            addedAt = Instant.ofEpochMilli(addedAt),
            product = if (providerId != null && productId != null) ProductKey(providerId, productId) else null,
            quantity = quantityMilli?.let(::Amount),
            unit = measureUnit,
            packageQuantity = packageQuantityMilli?.let(::Amount),
            opened = opened,
            openedAt = openedAt?.let(Instant::ofEpochMilli),
            bestBefore = bestBefore?.let(LocalDate::ofEpochDay),
            location = PantryLocation.fromCode(location),
            presenceOnly = presenceOnly,
            useSoonManual = useSoonManual,
            useSoonDismissedAt = useSoonDismissedAt?.let(Instant::ofEpochMilli),
            openedShelfLifeDays = openedShelfLifeDays?.takeIf { it > 0 },
        )
    }.getOrNull()
}
