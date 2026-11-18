package com.lechugones.cocina.domain.recipepack

enum class IssueSeverity {
    /** The whole input is rejected. */
    PACK_ERROR,

    /** Something in the pack was ignored; the rest imports. */
    PACK_WARNING,

    /** The recipe cannot be imported. */
    ERROR,

    /** Shown in the preview; the recipe still imports. */
    WARNING,
}

/** Validation codes of docs/RECIPE_FORMAT.md § 9. The UI maps each one to a Spanish message. */
enum class IssueCode(
    val severity: IssueSeverity,
) {
    P01(IssueSeverity.PACK_ERROR),
    P02(IssueSeverity.PACK_ERROR),
    P03(IssueSeverity.PACK_ERROR),
    P04(IssueSeverity.PACK_ERROR),
    P05(IssueSeverity.PACK_ERROR),
    P06(IssueSeverity.PACK_WARNING),
    E01(IssueSeverity.ERROR),
    E02(IssueSeverity.ERROR),
    E03(IssueSeverity.ERROR),
    E04(IssueSeverity.ERROR),
    E05(IssueSeverity.ERROR),
    E06(IssueSeverity.ERROR),
    E07(IssueSeverity.ERROR),
    E08(IssueSeverity.ERROR),
    E09(IssueSeverity.ERROR),
    E10(IssueSeverity.ERROR),
    E11(IssueSeverity.ERROR),
    E12(IssueSeverity.ERROR),
    W01(IssueSeverity.WARNING),
    W02(IssueSeverity.WARNING),
    W03(IssueSeverity.WARNING),
    W04(IssueSeverity.WARNING),
    W05(IssueSeverity.WARNING),
    W06(IssueSeverity.WARNING),
    W07(IssueSeverity.WARNING),
    W08(IssueSeverity.WARNING),
    W09(IssueSeverity.WARNING),
    W10(IssueSeverity.WARNING),
    W11(IssueSeverity.WARNING),
    W12(IssueSeverity.WARNING),
    ;

    val isError: Boolean get() = severity == IssueSeverity.ERROR || severity == IssueSeverity.PACK_ERROR
}

/**
 * One validation finding. [path] uses YAML field names with 0-based indexes
 * (`ingredients[2].unit`); [value] is the offending input; [line]/[column] are 1-based file
 * positions for YAML syntax errors.
 */
data class PackIssue(
    val code: IssueCode,
    val path: String = "",
    val value: String? = null,
    val line: Int? = null,
    val column: Int? = null,
) {
    val isError: Boolean get() = code.isError

    /** 0-based ingredient index when [path] points inside `ingredients[n]`. */
    val ingredientIndex: Int?
        get() =
            INGREDIENT_INDEX
                .find(path)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

    /** Last field name of [path] (`unit` for `ingredients[2].unit`), or null for the root. */
    val field: String?
        get() =
            path
                .substringAfterLast('.')
                .substringBefore('[')
                .ifEmpty { null }

    private companion object {
        val INGREDIENT_INDEX = Regex("""^ingredients\[(\d+)]""")
    }
}
