package com.lechugones.cocina.domain.recipepack

import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.PackageSize
import com.lechugones.cocina.domain.recipe.MatchHints
import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipeDocumentWriterTest {
    @Test
    fun `canonical form of a small recipe`() {
        val draft =
            RecipeDraft(
                title = "Ñoquis: rápidos",
                servings = 2,
                uid = "abc-12345",
                tags = listOf("cena", "a, b"),
                ingredients =
                    listOf(
                        IngredientDraft(
                            name = "ñoquis de patata",
                            quantity = Amount.parse("0.5"),
                            unit = MeasureUnit.KG,
                            match = MatchHints("ñoquis", PackageSize.parse("2 x 250 g"), storeBrandOk = true),
                            links = listOf(LinkDraft("mercadona", "12345", "Ñoquis Hacendado", preferred = true)),
                        ),
                        IngredientDraft(name = "sal", quantity = null, unit = MeasureUnit.PIZCA, pantry = true),
                    ),
                body = "## Preparación\n\n1. Cuece.",
            )
        val expected =
            """
            ---
            format: cpl-recipe/1
            uid: abc-12345
            title: "Ñoquis: rápidos"
            servings: 2
            tags: [cena, "a, b"]
            ingredients:
              - name: ñoquis de patata
                quantity: 0.5
                unit: kg
                match:
                  query: ñoquis
                  package_size: 2 x 250 g
                  store_brand_ok: true
                links:
                  - provider: mercadona
                    product_id: "12345"
                    name: Ñoquis Hacendado
                    preferred: true
              - name: sal
                unit: pizca
                pantry: true
            ---

            ## Preparación

            1. Cuece.

            """.trimIndent()
        assertEquals(expected, RecipeDocumentWriter.write(draft))
    }

    @Test
    fun `the sample pack round-trips to the same draft and is idempotent`() {
        val original = File(repoRoot, "examples/recipe-packs/tacos-de-pollo.md").readText()
        val draft = assertNotNull(parse(original).draft)
        val written = RecipeDocumentWriter.write(draft)
        val reparsed = parse(written)
        assertEquals(emptyList(), reparsed.issues)
        assertEquals(draft, reparsed.draft)
        assertEquals(written, RecipeDocumentWriter.write(assertNotNull(reparsed.draft)))
    }

    @ParameterizedTest
    @CsvSource(
        "tacos, true",
        "contramuslo de pollo, true",
        "'yes', false",
        "'No', false",
        "'null', false",
        "'~', false",
        "'1.5', false",
        "'12', false",
        "'1/2', true",
        "'500 g', true",
        "'a: b', false",
        "'a #b', false",
        "'a#b', true",
        "'-tostado', false",
        "'@casa', false",
        "'fin:', false",
        "'[x]', false",
        "'0x1F', false",
        "'.5', false",
    )
    fun `plain scalars only when unambiguous`(
        value: String,
        plain: Boolean,
    ) {
        assertEquals(plain, YamlScalars.isPlainSafe(value), value)
    }

    @Test
    fun `flow context also quotes commas and brackets`() {
        assertEquals("\"a, b\"", YamlScalars.scalar("a, b", flow = true))
        assertEquals("cena", YamlScalars.scalar("cena", flow = true))
    }

    /** SPEC AC3.1 and AC3.2 on generated recipes full of awkward strings. */
    @Test
    fun `generated recipes round-trip and the canonical form is idempotent`() {
        val random = Random(20261118)
        repeat(300) { round ->
            val draft = randomDraft(random)
            val written = RecipeDocumentWriter.write(draft)
            val parsed = parse(written, images = setOfNotNull(draft.image))
            assertTrue(parsed.issues.none { it.isError }, "round $round: ${parsed.issues}\n$written")
            assertEquals(draft, parsed.draft, "round $round:\n$written")
            assertEquals(written, RecipeDocumentWriter.write(assertNotNull(parsed.draft)), "round $round")
        }
    }

    private val fragments =
        listOf(
            "tacos",
            "ñoquis",
            "pollo",
            "a: b",
            "x #y",
            "\"comillas\"",
            "'simples'",
            "\\barra",
            "yes",
            "null",
            "123",
            "1.5",
            "1/2",
            "-guion",
            "@arroba",
            "50 %",
            "€",
            "café",
            "[corchete]",
            "{llave}",
            "a,b",
            "tab\tinterno",
            "línea\nnueva",
            "émoji 🍅",
            "x:",
            "#hash",
            "&ancla",
            "*estrella",
            "!excl",
            "|tubo",
        )

    private fun text(
        random: Random,
        max: Int,
        allowNewlines: Boolean = true,
    ): String {
        val words = (1..random.nextInt(1, 4)).map { fragments[random.nextInt(fragments.size)] }
        var text = words.joinToString(" ")
        if (!allowNewlines) text = text.replace('\n', ' ').replace('\t', ' ').replace(Regex("\\s+"), " ")
        var cut = text.trim().take(max)
        if (cut.lastOrNull()?.isHighSurrogate() == true) cut = cut.dropLast(1)
        return cut.trim().ifEmpty { "x" }
    }

    private fun randomDraft(random: Random): RecipeDraft {
        val units = MeasureUnit.entries
        val ingredients =
            (1..random.nextInt(1, 6)).map { i ->
                val unit = units[random.nextInt(units.size)]
                val quantity =
                    when {
                        unit == MeasureUnit.AL_GUSTO -> null
                        unit == MeasureUnit.PIZCA && random.nextBoolean() -> null
                        else -> Amount(random.nextLong(1, 5_000_000))
                    }
                IngredientDraft(
                    name = "ing$i " + text(random, 60, allowNewlines = false),
                    quantity = quantity,
                    unit = unit,
                    unitWeightG = if (random.nextBoolean()) Amount(random.nextLong(1, 10_000_000)) else null,
                    note = if (random.nextBoolean()) text(random, 120) else null,
                    group = if (random.nextBoolean()) text(random, 40) else null,
                    optional = random.nextBoolean(),
                    pantry = random.nextBoolean(),
                    match =
                        if (random.nextBoolean()) {
                            MatchHints(
                                query = "q " + text(random, 70),
                                packageSize =
                                    if (random.nextBoolean()) {
                                        PackageSize.parse(
                                            "${random.nextInt(1, 5)} x ${random.nextInt(1, 999)} g",
                                        )
                                    } else {
                                        null
                                    },
                                storeBrandOk = random.nextBoolean(),
                            )
                        } else {
                            null
                        },
                    links =
                        if (random.nextInt(4) == 0) {
                            listOf(
                                LinkDraft(
                                    "mercadona",
                                    random.nextInt(1, 99999).toString(),
                                    text(random, 100),
                                    preferred = random.nextBoolean(),
                                ),
                            )
                        } else {
                            emptyList()
                        },
                )
            }
        return RecipeDraft(
            title = text(random, 120),
            servings = random.nextInt(1, 51),
            ingredients = ingredients,
            uid = if (random.nextBoolean()) "uid-" + random.nextLong(10_000, 99_999) else null,
            description = if (random.nextBoolean()) text(random, 500) else null,
            prepMinutes = if (random.nextBoolean()) random.nextInt(0, 1441) else null,
            cookMinutes = if (random.nextBoolean()) random.nextInt(0, 1441) else null,
            tags = (1..random.nextInt(0, 4)).map { "tag$it " + text(random, 20, allowNewlines = false).lowercase() }.distinct(),
            image = if (random.nextBoolean()) "images/foto-${random.nextInt(100)}.jpg" else null,
            source = if (random.nextBoolean()) text(random, 300) else null,
            body = "## Preparación\n\n1. " + text(random, 200) + "\n2. Servir **caliente** y *rápido*.",
        )
    }
}
