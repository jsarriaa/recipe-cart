package com.lechugones.cocina.domain.units

import java.math.BigDecimal
import java.util.Locale

/** Physical dimension of a [MeasureUnit]. */
enum class Dimension {
    MASS,
    VOLUME,
    COUNT,

    /** No meaningful quantity (a pinch, "to taste"): never scaled, costed or deducted. */
    NEGLIGIBLE,
}

/**
 * Units allowed in recipes and packages. The table, aliases and factors are defined in
 * docs/RECIPE_FORMAT.md § 5 and must stay in sync with it.
 *
 * [factorToBase] converts one unit into the base unit of its dimension (g for mass, ml for
 * volume). It is null for count and negligible units, which convert only to themselves.
 */
enum class MeasureUnit(
    val code: String,
    val dimension: Dimension,
    val factorToBase: BigDecimal?,
    aliasList: List<String> = emptyList(),
) {
    G("g", Dimension.MASS, BigDecimal.ONE, listOf("gr", "grs", "gramo", "gramos")),
    KG("kg", Dimension.MASS, BigDecimal(1000), listOf("kilo", "kilos", "kilogramo", "kilogramos")),
    ML("ml", Dimension.VOLUME, BigDecimal.ONE, listOf("mililitro", "mililitros")),
    CL("cl", Dimension.VOLUME, BigDecimal(10), listOf("centilitro", "centilitros")),
    L("l", Dimension.VOLUME, BigDecimal(1000), listOf("lt", "litro", "litros")),
    CDTA("cdta", Dimension.VOLUME, BigDecimal(5), listOf("cucharadita", "cucharaditas", "cdita", "cdtas", "tsp")),
    CDA("cda", Dimension.VOLUME, BigDecimal(15), listOf("cucharada", "cucharadas", "cdas", "tbsp")),
    TAZA("taza", Dimension.VOLUME, BigDecimal(250), listOf("tazas", "cup")),
    UD("ud", Dimension.COUNT, null, listOf("u", "uds", "unidad", "unidades", "pieza", "piezas")),
    DIENTE("diente", Dimension.COUNT, null, listOf("dientes")),
    LONCHA("loncha", Dimension.COUNT, null, listOf("lonchas")),
    REBANADA("rebanada", Dimension.COUNT, null, listOf("rebanadas")),
    HOJA("hoja", Dimension.COUNT, null, listOf("hojas")),
    RAMITA("ramita", Dimension.COUNT, null, listOf("ramitas")),
    PIZCA("pizca", Dimension.NEGLIGIBLE, null, listOf("pizcas")),
    AL_GUSTO("al_gusto", Dimension.NEGLIGIBLE, null, listOf("al gusto", "c/s", "cs")),
    ;

    val aliases: Set<String> = aliasList.toSet()

    /** Spoons and cups: shown rounded to halves. */
    val isKitchenVolume: Boolean get() = this == CDTA || this == CDA || this == TAZA

    /** Whether a recipe ingredient in this unit must carry a quantity. */
    val requiresQuantity: Boolean get() = dimension != Dimension.NEGLIGIBLE

    /** Whether a quantity is allowed at all (`al_gusto` takes none). */
    val allowsQuantity: Boolean get() = this != AL_GUSTO

    val scalesWithServings: Boolean get() = dimension != Dimension.NEGLIGIBLE

    companion object {
        private val byCode: Map<String, MeasureUnit> = entries.associateBy { it.code }
        private val byAlias: Map<String, MeasureUnit> =
            entries.flatMap { unit -> unit.aliases.map { alias -> alias to unit } }.toMap()
        private val whitespace = Regex("\\s+")

        /** Exact canonical code lookup, as stored in the database. */
        fun fromCode(code: String): MeasureUnit? = byCode[code]

        /**
         * Lenient lookup for imported text: trims, ignores case and accepts aliases.
         * Returns null for unknown units.
         */
        fun parse(raw: String): UnitParse? {
            val trimmed = raw.trim()
            val key = trimmed.lowercase(Locale.ROOT).replace(whitespace, " ")
            val unit = byCode[key] ?: byAlias[key] ?: return null
            return UnitParse(unit = unit, normalized = trimmed != unit.code)
        }
    }
}

/** Result of [MeasureUnit.parse]. [normalized] is true when the input was not the exact code. */
data class UnitParse(
    val unit: MeasureUnit,
    val normalized: Boolean,
)
