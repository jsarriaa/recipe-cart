package com.lechugones.cocina.domain.settings

/** User settings (SPEC § 4.8). Defaults here are the app defaults. */
data class AppSettings(
    val activeProviders: Set<String> = setOf(DEFAULT_PROVIDER),
    val primaryProvider: String = DEFAULT_PROVIDER,
    val postalCode: String? = null,
    val storeBrandOnly: Boolean = false,
    val clubDiaPrices: Boolean = false,
    val includeStaplesInCost: Boolean = false,
    val includeOptionalsInCost: Boolean = false,
    val staleAfterDays: Int = DEFAULT_STALE_AFTER_DAYS,
    val addMissingStaplesToList: Boolean = true,
) {
    /** Replaces impossible values (from an old or edited file) with safe ones. */
    fun sanitized(): AppSettings {
        val active = activeProviders.filter { it.isNotBlank() }.toSet().ifEmpty { setOf(DEFAULT_PROVIDER) }
        return copy(
            activeProviders = active,
            primaryProvider = primaryProvider.takeIf { it in active } ?: active.sorted().first(),
            postalCode = postalCode?.trim()?.takeIf { POSTAL_CODE.matches(it) },
            staleAfterDays = staleAfterDays.takeIf { it in STALE_AFTER_RANGE } ?: DEFAULT_STALE_AFTER_DAYS,
        )
    }

    companion object {
        const val DEFAULT_PROVIDER = "mercadona"
        const val DEFAULT_STALE_AFTER_DAYS = 14
        val STALE_AFTER_RANGE = 1..365
        val POSTAL_CODE = Regex("""^\d{5}$""")
    }
}
