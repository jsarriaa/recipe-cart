package com.lechugones.cocina.domain.recipe

import java.text.Normalizer
import java.util.Locale

/**
 * Normalised ingredient name used as the key for link memory and pantry matching
 * (SPEC § 5.4): lowercase, without diacritics, only `[a-z0-9 ]`, single spaces.
 *
 * `"Contramuslo de Pollo  deshuesado"` → `"contramuslo de pollo deshuesado"`;
 * `"Ñoquis"` → `"noquis"`.
 */
object NameKey {
    private val combiningMarks = Regex("\\p{M}+")
    private val nonAlphanumeric = Regex("[^a-z0-9]+")

    fun of(name: String): String =
        Normalizer
            .normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .replace(nonAlphanumeric, " ")
            .trim()
}
