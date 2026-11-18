package com.lechugones.cocina.domain.recipepack

import com.lechugones.cocina.domain.quantity.Amount
import com.lechugones.cocina.domain.quantity.PackageSize
import com.lechugones.cocina.domain.units.MeasureUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeDocumentParserTest {
    // --- The documented examples must stay valid (SPEC AC3.4) ---------------------------------

    @Test
    fun `sample taco pack parses with zero issues`() {
        val parsed = parse(File(repoRoot, "examples/recipe-packs/tacos-de-pollo.md").readText())
        assertEquals(emptyList(), parsed.issues)
        val draft = assertNotNull(parsed.draft)
        assertEquals("Tacos de pollo con aguacate y mozzarella", draft.title)
        assertEquals("ejemplo-tacos-de-pollo", draft.uid)
        assertEquals(2, draft.servings)
        assertEquals(listOf("mexicana", "cena", "rápida"), draft.tags)
        assertEquals(8, draft.ingredients.size)

        val tortillas = draft.ingredients[1]
        assertEquals("tortilla de trigo maxi", tortillas.name)
        assertEquals(Amount.of(4), tortillas.quantity)
        assertEquals(MeasureUnit.UD, tortillas.unit)
        assertEquals(Amount.of(60), tortillas.unitWeightG)
        assertEquals(PackageSize.parse("6 ud"), tortillas.match?.packageSize)

        val salt = draft.ingredients.last()
        assertNull(salt.quantity)
        assertEquals(MeasureUnit.PIZCA, salt.unit)
        assertTrue(salt.pantry)
        assertTrue(draft.body.startsWith("## Preparación"))
    }

    @Test
    fun `examples in the format doc and the generator prompt are valid`() {
        val format = File(repoRoot, "docs/RECIPE_FORMAT.md").readText()
        val prompt = File(repoRoot, "prompts/recipe-pack-generator.md").readText()
        val block = Regex("```markdown\n(.*?)\n```", RegexOption.DOT_MATCHES_ALL)
        listOf(format, prompt).forEach { source ->
            val example = assertNotNull(block.find(source)).groupValues[1]
            assertEquals(emptyList(), parse(example + "\n").issues)
        }
    }

    // --- Structure ---------------------------------------------------------------------------

    @Test
    fun `missing or unclosed front matter is E01`() {
        assertEquals(listOf(IssueCode.E01), parse("title: x\n").codes())
        assertEquals(listOf(IssueCode.E01), parse("---\nformat: cpl-recipe/1\ntitle: x\n").codes())
    }

    @Test
    fun `CRLF line endings and a BOM are accepted`() {
        val text = "" + doc(minimal()).replace("\n", "\r\n")
        assertEquals(emptyList(), parse(text).issues)
    }

    @Test
    fun `YAML syntax errors are E02 with the file line`() {
        val parsed = parse(doc("title: [sin cerrar\nservings: 2"))
        val issue = parsed.assertIssue(IssueCode.E02)
        assertNotNull(issue.line)
        assertTrue(issue.line!! >= 3, "line ${issue.line} must be a file line")
    }

    @Test
    fun `duplicate keys are E02`() {
        parse(doc(minimal("title: Otra"))).assertIssue(IssueCode.E02)
    }

    @Test
    fun `anchors, aliases, tags and merge keys are E03`() {
        parse(doc(minimal("description: &a hola\nsource: *a"))).assertIssue(IssueCode.E03)
        parse(doc(minimal("description: !rara hola"))).assertIssue(IssueCode.E03)
        // kaml may resolve an inline merge itself; either way the document must not import.
        assertTrue(parse(doc(minimal("source:\n  <<: {a: 1}"))).hasErrors)
    }

    @Test
    fun `format must be cpl-recipe 1`() {
        parse(doc(minimal(), withFormat = false)).assertIssue(IssueCode.E04, "format")
        parse(doc("format: cpl-recipe/2\n" + minimal(), withFormat = false)).assertIssue(IssueCode.E04, "format")
        parse("---\n---\n").assertIssue(IssueCode.E04)
    }

    // --- Required fields and types -----------------------------------------------------------

    @Test
    fun `required fields are E05`() {
        val parsed = parse(doc("description: sin título"))
        parsed.assertIssue(IssueCode.E05, "title")
        parsed.assertIssue(IssueCode.E05, "servings")
        parsed.assertIssue(IssueCode.E05, "ingredients")
        assertNull(parsed.draft)
    }

    @Test
    fun `missing ingredient name or unit is E05 at its path`() {
        val parsed =
            parse(
                doc(
                    """
                    title: X
                    servings: 2
                    ingredients:
                      - quantity: 1
                        unit: g
                      - name: sal
                    """,
                ),
            )
        parsed.assertIssue(IssueCode.E05, "ingredients[0].name")
        parsed.assertIssue(IssueCode.E05, "ingredients[1].unit")
    }

    @Test
    fun `wrong types are E06`() {
        parse(doc(minimal().replace("servings: 2", "servings: dos"))).assertIssue(IssueCode.E06, "servings")
        parse(doc(minimal().replace("servings: 2", "servings: 2.5"))).assertIssue(IssueCode.E06, "servings")
        parse(doc(minimal("tags: mexicana"))).assertIssue(IssueCode.E06, "tags")
        parse(doc(minimal().replace("query: arroz", "query: arroz\n      store_brand_ok: yes")))
            .assertIssue(IssueCode.E06, "ingredients[0].match.store_brand_ok")
        parse(doc("title: X\nservings: 2\ningredients: arroz")).assertIssue(IssueCode.E06, "ingredients")
        parse(doc(minimal().replace("unit: g", "unit: g\n    pantry: si"))).assertIssue(IssueCode.E06, "ingredients[0].pantry")
    }

    @Test
    fun `values out of range are E07`() {
        parse(doc(minimal().replace("title: Prueba", "title: " + "x".repeat(121)))).assertIssue(IssueCode.E07, "title")
        parse(doc(minimal().replace("servings: 2", "servings: 0"))).assertIssue(IssueCode.E07, "servings")
        parse(doc(minimal().replace("servings: 2", "servings: 51"))).assertIssue(IssueCode.E07, "servings")
        parse(doc(minimal("uid: corto"))).assertIssue(IssueCode.E07, "uid")
        parse(doc(minimal("prep_minutes: 1441"))).assertIssue(IssueCode.E07, "prep_minutes")
        val tags = (1..16).joinToString(", ") { "t$it" }
        parse(doc(minimal("tags: [$tags]"))).assertIssue(IssueCode.E07, "tags")
        parse(doc(minimal().replace("query: arroz", "query: a"))).assertIssue(IssueCode.E07, "ingredients[0].match.query")
        parse(doc(minimal().replace("unit: g", "unit: ud\n    unit_weight_g: 20000")))
            .assertIssue(IssueCode.E07, "ingredients[0].unit_weight_g")
        parse(doc("title: X\nservings: 2\ningredients: []")).assertIssue(IssueCode.E07, "ingredients")
    }

    @Test
    fun `unknown units are E08 with the value`() {
        val issue = parse(doc(minimal().replace("unit: g", "unit: lata"))).assertIssue(IssueCode.E08, "ingredients[0].unit")
        assertEquals("lata", issue.value)
    }

    @Test
    fun `measured units need a quantity, E09`() {
        parse(doc(minimal().replace("    quantity: 100\n", ""))).assertIssue(IssueCode.E09, "ingredients[0].quantity")
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-3", "abc", "1/0", "100001"])
    fun `invalid quantities are E10`(quantity: String) {
        parse(doc(minimal().replace("quantity: 100", "quantity: \"$quantity\""))).assertIssue(IssueCode.E10, "ingredients[0].quantity")
    }

    @Test
    fun `invalid package sizes are E11`() {
        parse(doc(minimal().replace("query: arroz", "query: arroz\n      package_size: medio kilo")))
            .assertIssue(IssueCode.E11, "ingredients[0].match.package_size")
    }

    @Test
    fun `oversized documents and bodies are E12`() {
        parse(doc(minimal(), body = PREPARATION + "\n" + "a".repeat(20_001))).assertIssue(IssueCode.E12, "body")
        parse(doc(minimal(), body = "x".repeat(300 * 1024))).assertIssue(IssueCode.E12)
    }

    // --- Warnings ----------------------------------------------------------------------------

    @Test
    fun `unknown fields are W01 and ignored`() {
        val parsed =
            parse(
                doc(
                    minimal(
                        "color: rojo",
                    ).replace("unit: g", "unit: g\n    sabor: dulce").replace("query: arroz", "query: arroz\n      marca: x"),
                ),
            )
        parsed.assertIssue(IssueCode.W01, "color")
        parsed.assertIssue(IssueCode.W01, "ingredients[0].sabor")
        parsed.assertIssue(IssueCode.W01, "ingredients[0].match.marca")
        assertNotNull(parsed.draft)
    }

    @Test
    fun `normalisations are W02`() {
        val parsed =
            parse(
                doc(
                    minimal(
                        "tags: [Cena, cena, ' Rápida ']",
                    ).replace("quantity: 100", "quantity: \"1,5\"").replace("unit: g", "unit: kilos"),
                ),
            )
        assertEquals(3, parsed.issues.count { it.code == IssueCode.W02 }, parsed.issues.toString())
        val draft = assertNotNull(parsed.draft)
        assertEquals(listOf("cena", "rápida"), draft.tags)
        assertEquals(Amount.parse("1.5"), draft.ingredients[0].quantity)
        assertEquals(MeasureUnit.KG, draft.ingredients[0].unit)
    }

    @Test
    fun `images must exist in the pack, W03, and be valid paths, W04`() {
        val missing = parse(doc(minimal("image: images/tacos.jpg")))
        missing.assertIssue(IssueCode.W03, "image")
        assertNull(missing.draft?.image)
        assertEquals("images/tacos.jpg", parse(doc(minimal("image: images/tacos.jpg")), setOf("images/tacos.jpg")).draft?.image)
        parse(doc(minimal("image: https://example.com/a.jpg"))).assertIssue(IssueCode.W04, "image")
        parse(doc(minimal("image: images/../secreto.jpg"))).assertIssue(IssueCode.W04, "image")
    }

    @Test
    fun `non-staples without match are W05, staples are fine`() {
        val parsed =
            parse(
                doc(
                    """
                    title: X
                    servings: 1
                    ingredients:
                      - name: arroz
                        quantity: 100
                        unit: g
                      - name: sal
                        unit: pizca
                        pantry: true
                    """,
                ),
            )
        assertEquals(listOf(IssueCode.W05), parsed.codes())
        assertEquals("ingredients[0]", parsed.issues.single().path)
    }

    @Test
    fun `quantity for al_gusto is ignored, W06`() {
        val parsed = parse(doc(minimal().replace("quantity: 100\n    unit: g", "quantity: 2\n    unit: al gusto")))
        parsed.assertIssue(IssueCode.W06, "ingredients[0].quantity")
        assertNull(
            parsed.draft
                ?.ingredients
                ?.single()
                ?.quantity,
        )
    }

    @Test
    fun `only the first preferred link per provider stays, W08`() {
        val parsed =
            parse(
                doc(
                    minimal().replace(
                        "query: arroz",
                        """query: arroz
    links:
      - provider: mercadona
        product_id: "1"
        preferred: true
      - provider: mercadona
        product_id: 2
        preferred: true""",
                    ),
                ),
            )
        parsed.assertIssue(IssueCode.W08)
        val links = assertNotNull(parsed.draft).ingredients.single().links
        assertEquals(listOf(true, false), links.map { it.preferred })
        assertEquals("2", links[1].productId, "bare numeric ids are read as text")
    }

    @Test
    fun `a body without a Preparación section is W09`() {
        parse(doc(minimal(), body = "")).assertIssue(IssueCode.W09, "body")
        parse(doc(minimal(), body = "## Pasos\n\n1. x")).assertIssue(IssueCode.W09, "body")
        assertFalse(IssueCode.W09 in parse(doc(minimal(), body = "## Preparacion\n\n1. x")).codes())
    }

    @Test
    fun `repeated ingredients in the same group are W11`() {
        val parsed = parse(doc(minimal() + "\n" + minimal().substringAfter("ingredients:\n")))
        parsed.assertIssue(IssueCode.W11, "ingredients[1]")
    }

    @Test
    fun `quantities with more than three decimals are rounded, W12`() {
        val parsed = parse(doc(minimal().replace("quantity: 100", "quantity: 0.3335")))
        parsed.assertIssue(IssueCode.W12, "ingredients[0].quantity")
        assertEquals(
            Amount(334),
            parsed.draft
                ?.ingredients
                ?.single()
                ?.quantity,
        )
    }

    @Test
    fun `fractions and pinches without quantity are accepted silently`() {
        val parsed =
            parse(
                doc(
                    minimal().replace("quantity: 100", "quantity: \"1 1/2\"").replace("unit: g", "unit: cda") +
                        "\n  - name: pimienta\n    unit: pizca\n    pantry: true",
                ),
            )
        assertEquals(emptyList(), parsed.issues)
        assertEquals(
            Amount.parse("1.5"),
            parsed.draft
                ?.ingredients
                ?.first()
                ?.quantity,
        )
    }

    @Test
    fun `a broken ingredient does not shift the paths of the others`() {
        val parsed =
            parse(
                doc(
                    """
                    title: X
                    servings: 2
                    ingredients:
                      - name: arroz
                        quantity: 1
                        unit: lata
                      - name: sal fina
                        quantity: 5
                        unit: g
                    """,
                ),
            )
        parsed.assertIssue(IssueCode.E08, "ingredients[0].unit")
        parsed.assertIssue(IssueCode.W05, "ingredients[1]")
        assertTrue(parsed.issues.none { it.code == IssueCode.W05 && it.path == "ingredients[0]" })
    }
}
