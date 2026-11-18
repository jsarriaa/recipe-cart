package com.lechugones.cocina.domain.packaging

import java.util.Locale

/**
 * The packaging word of a product for package fractions (SPEC § 5.3, rule 4): the provider's
 * packaging lowercased ("Paquete" → "paquete"). Null when the provider gives none; the UI then
 * uses its own neutral word ("envase").
 */
object PackagingNoun {
    private val SPANISH: Locale = Locale.forLanguageTag("es-ES")
    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'á', 'é', 'í', 'ó', 'ú')

    fun of(
        packaging: String?,
        plural: Boolean,
    ): String? {
        val word =
            packaging
                ?.trim()
                ?.lowercase(SPANISH)
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
                ?: return null
        return if (plural) pluralize(word) else word
    }

    /** Vowel → +s, z → ces, otherwise +es. Only the last word changes ("bolsa grande" stays simple). */
    fun pluralize(word: String): String {
        val last = word.last()
        return when {
            last in VOWELS -> word + "s"
            last == 'z' -> word.dropLast(1) + "ces"
            last == 's' -> word
            else -> word + "es"
        }
    }

    private const val MAX_LENGTH = 24
}
