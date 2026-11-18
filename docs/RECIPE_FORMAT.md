# Recipe pack format — `cpl-recipe/1`

This is the exact, versioned format the app imports and exports. Recipes generated outside the
app (for example with [prompts/recipe-pack-generator.md](../prompts/recipe-pack-generator.md))
must follow it. The parser, validator and serializer live in `:core:domain` and are tested
against this document and the files in [examples/recipe-packs/](../examples/recipe-packs/).

Field names and unit codes are fixed identifiers. Recipe **content** (titles, names, notes,
steps) is Spanish.

## 1. At a glance

```markdown
---
format: cpl-recipe/1
title: Ñoquis con tomate
servings: 2
ingredients:
  - name: ñoquis de patata
    quantity: 250
    unit: g
    match:
      query: ñoquis patata
      package_size: 500 g
      store_brand_ok: true
  - name: tomate frito
    quantity: 200
    unit: g
    match:
      query: tomate frito
      package_size: 400 g
      store_brand_ok: true
  - name: sal
    unit: pizca
    pantry: true
---

## Preparación

1. Cuece los ñoquis en agua con sal hasta que floten.
2. Escúrrelos y mézclalos con el tomate caliente.
```

## 2. Containers and limits

A recipe pack is one of:

| Container | Content |
|---|---|
| **Single file** `*.md` | Exactly one recipe document (§ 3) |
| **Zip** `*.zip` | One or more `*.md` recipe documents **at the root**, plus an optional `images/` folder |
| **Shared text** (share sheet, `text/plain`) | Exactly one recipe document, as text |

Zip layout:

```
mi-pack.zip
├── tacos-de-pollo.md
├── noquis-con-tomate.md
└── images/
    └── tacos-de-pollo.jpg
```

Rules:

- Text is UTF-8. A leading BOM is removed. CRLF and CR line endings are normalised to LF.
- In a zip, only root-level `*.md` files and files under `images/` are read. `.md` files in other
  folders are ignored with a warning. `__MACOSX/`, `.DS_Store` and `Thumbs.db` are ignored
  silently. Any other file is ignored with a warning.
- File names carry no meaning. Recommended: `<slug>.md` with `[a-z0-9-]`.
- Images: `.jpg`, `.jpeg`, `.png` or `.webp`, referenced from a recipe's `image` field.

Limits (exceeding a pack limit rejects the whole input, `P02`):

| Limit | Value |
|---|---|
| Single `.md` file | ≤ 256 KiB |
| Recipe body | ≤ 20 000 characters |
| Zip file | ≤ 50 MiB compressed, ≤ 100 MiB uncompressed in total |
| Zip entries | ≤ 500 |
| Compression ratio per entry | ≤ 100 : 1 |
| Image | ≤ 5 MiB each |
| Recipes per pack | ≤ 100 |

Zip entries with an absolute path, a drive letter, a backslash or a `..` segment reject the whole
pack (`P03`).

## 3. Document structure

```
---
<YAML front matter>
---
<Markdown body>
```

- The first line must be exactly `---` (trailing spaces allowed). The front matter ends at the
  next line that is exactly `---`. Everything after it is the body.
- The front matter is **YAML 1.2, core schema**, block or flow style, comments allowed.
  **Not allowed** (`E03`): anchors (`&`), aliases (`*`), tags (`!`), merge keys (`<<`), more than one
  YAML document. Duplicate keys are a syntax error (`E02`).
- Booleans are only `true` / `false`. (YAML 1.1 words like `yes`, `no`, `on` are strings, and they
  fail type checks.)
- When in doubt, wrap a string in double quotes. A string must be quoted if it contains `: ` or
  ` #`, or starts with any of `[ ] { } , & * ! | > ' " % @` or a backtick.

## 4. Fields

### 4.1 Recipe (top level)

| Field | Type | Req. | Rules |
|---|---|---|---|
| `format` | string | **yes** | Must be `cpl-recipe/1`. Other values: `E04` |
| `uid` | string | no | 8–64 chars, `[A-Za-z0-9-]`. Stable identity, used to detect re-imports. Export writes the app's UUID. Generated on import if missing |
| `title` | string | **yes** | 1–120 chars after trimming |
| `description` | string | no | ≤ 500 chars. One or two sentences |
| `servings` | integer | **yes** | 1–50 |
| `prep_minutes` | integer | no | 0–1440 |
| `cook_minutes` | integer | no | 0–1440 |
| `tags` | list of strings | no | ≤ 15 tags, each 1–30 chars. Lowercased, trimmed and de-duplicated on import |
| `image` | string | no | `images/<file>` with `<file>` matching `[A-Za-z0-9._-]+\.(jpg\|jpeg\|png\|webp)`. No URLs, no absolute paths, no `..`. An invalid value is ignored (`W04`) |
| `source` | string | no | ≤ 300 chars. Free text or a URL. Informational, never fetched |
| `ingredients` | list of Ingredient | **yes** | 1–60 items. Order is preserved |

### 4.2 Ingredient

| Field | Type | Req. | Rules |
|---|---|---|---|
| `name` | string | **yes** | 1–80 chars. **Generic** Spanish name, lowercase except proper nouns, singular unless the food is naturally plural ("garbanzos cocidos"). No quantity, brand or preparation in the name. Used as the link-memory key after normalisation |
| `quantity` | number or fraction string | depends | See § 6. Required for every unit except `pizca` (optional, default 1) and `al_gusto` (must be absent) |
| `unit` | string | **yes** | A unit code from § 5. Aliases are accepted with a warning (`W02`) |
| `unit_weight_g` | number | no | > 0, ≤ 10 000. Grams in **one** `unit` of this ingredient (one avocado ≈ 200, one tablespoon of cumin ≈ 6). Enables count ↔ mass and spoon ↔ mass conversions |
| `note` | string | no | ≤ 120 chars. Preparation or state: "en tiras", "picada fina", "a temperatura ambiente" |
| `group` | string | no | ≤ 40 chars. Section label such as "Para el guacamole". Ingredients with the same group are shown together, in order of first appearance |
| `optional` | boolean | no | Default `false`. Optional ingredients are left out of totals unless the user includes them |
| `pantry` | boolean | no | Default `false`. `true` marks a **pantry staple** (salt, oil, spices, vinegar…) |
| `match` | Match | no | Product-matching hints. **Recommended for every ingredient**; missing on a non-staple gives `W05` |
| `links` | list of Link | no | Confirmed product links. Written by export; hand-written files normally omit it |

### 4.3 Match (hints for suggesting products on import)

| Field | Type | Req. | Rules |
|---|---|---|---|
| `query` | string | **yes** (if `match` present) | 2–80 chars. The words you would type in the supermarket's search box, in Spanish, without quantities. No brand unless the brand matters |
| `package_size` | string | no | Typical package in Spain, grammar in § 7: `500 g`, `1 l`, `6 ud`, `4 x 125 g`. Used to rank candidates; it is not a filter |
| `store_brand_ok` | boolean | no | Default `true`. `true` means a store brand (Hacendado, Dia…) or an unbranded fresh product is fine, and those get a ranking bonus. `false` means a specific brand matters (then put it in `query`) |

### 4.4 Link (confirmed product link)

| Field | Type | Req. | Rules |
|---|---|---|---|
| `provider` | string | **yes** | Provider id, `[a-z][a-z0-9_]{1,31}`: `mercadona`, `dia`, `manual` |
| `product_id` | string | **yes** | 1–64 chars, the provider's product id. Write it quoted (`"80870"`). A bare number is accepted and converted (`W02`) |
| `name` | string | no | ≤ 200 chars. Product name snapshot, for humans reading the file |
| `preferred` | boolean | no | At most one `true` per provider (extra ones: `W08`, the first wins). If none is set, the first link for each provider is preferred |

On import, a link is used only if its provider is installed and the product is in the local
catalogue. Otherwise it is skipped with `W07` and normal matching runs for that ingredient.
`manual` links resolve only on the phone that created them.

## 5. Units

| Code | Display (sing. / pl.) | Kind | Base | Accepted aliases (normalised with `W02`) |
|---|---|---|---|---|
| `g` | g | mass | 1 g | `gr`, `grs`, `gramo`, `gramos` |
| `kg` | kg | mass | 1000 g | `kilo`, `kilos`, `kilogramo`, `kilogramos` |
| `ml` | ml | volume | 1 ml | `mililitro`, `mililitros` |
| `cl` | cl | volume | 10 ml | `centilitro`, `centilitros` |
| `l` | l | volume | 1000 ml | `lt`, `litro`, `litros` |
| `cdta` | cucharadita / cucharaditas | volume (kitchen) | 5 ml | `cucharadita`, `cucharaditas`, `cdita`, `cdtas`, `tsp` |
| `cda` | cucharada / cucharadas | volume (kitchen) | 15 ml | `cucharada`, `cucharadas`, `cdas`, `tbsp` |
| `taza` | taza / tazas | volume (kitchen) | 250 ml | `tazas`, `cup` |
| `ud` | ud / ud | count | 1 ud | `u`, `uds`, `unidad`, `unidades`, `pieza`, `piezas` |
| `diente` | diente / dientes | count (own) | — | `dientes` |
| `loncha` | loncha / lonchas | count (own) | — | `lonchas` |
| `rebanada` | rebanada / rebanadas | count (own) | — | `rebanadas` |
| `hoja` | hoja / hojas | count (own) | — | `hojas` |
| `ramita` | ramita / ramitas | count (own) | — | `ramitas` |
| `pizca` | pizca / pizcas | negligible | — | `pizcas` |
| `al_gusto` | al gusto | negligible, no quantity | — | `al gusto`, `c/s`, `cs` |

Unit codes and aliases are matched case-insensitively, after trimming (`G`, `Kg` and `L` all
work). Export always writes the lowercase code. Any other value is `E08`.

Conversion rules:

- Within **mass** (`g`, `kg`) and within **volume** (`ml`, `cl`, `l`, `cdta`, `cda`, `taza`): by the
  factors above.
- **Count (own)** units (`diente`, `loncha`, `rebanada`, `hoja`, `ramita`) convert only to themselves.
  `ud` matches products sold as a number of units ("6 ud").
- **Count or volume ↔ mass**: only through `unit_weight_g`.
- **Negligible** (`pizca`, `al_gusto`): no conversion, cost 0, never scaled, never deducted.

Do not use package words (`lata`, `sobre`, `paquete`, `bote`) as units. Express the amount in
`g`, `ml` or `ud` and put the typical package in `match.package_size`. For example, "1 lata de
tomate triturado" becomes `quantity: 400`, `unit: g`, `package_size: 400 g`.

## 6. Quantities

`quantity` is one of:

- A YAML number: `300`, `0.5`, `1.25`. A dot is the decimal separator.
- A fraction string: `"1/2"`, `"3/4"`, `"1 1/2"` (whole number, one space, fraction). Denominator
  1–1000.
- A decimal-comma string: `"1,5"`, accepted and normalised (`W02`).

Rules:

- Must be > 0 and ≤ 100 000 (`E10` otherwise). Must be a finite number.
- More than 3 decimals are rounded half-up to 3 (`W12`). `"1/3"` becomes `0.333`.
- `pizca`: quantity optional, default 1. `al_gusto`: a quantity is ignored (`W06`).
- Every other unit requires a quantity (`E09`).

## 7. Package size grammar (`match.package_size`)

```
package_size := [ count ws? ("x" | "×") ws? ] amount ws? unit
count        := integer 1..100
amount       := number with "." or "," as decimal separator, > 0
unit         := g | kg | ml | cl | l | ud          (case-insensitive)
ws           := one or more spaces
```

Valid: `500 g`, `1 kg`, `1,5 l`, `250ml`, `6 ud`, `4 x 125 g`, `2×1 l`.
Invalid (`E11`): `medio kilo`, `1 paquete`, `500`, `g 500`.
Canonical form: `4 x 125 g`, `1.5 l` (dot decimal, single spaces, lowercase unit).

## 8. Body (Markdown)

- CommonMark subset: headings `##` and `###`, paragraphs, ordered and unordered lists, `**bold**`,
  `*italic*`, line breaks. Raw HTML is shown as text. Images are not rendered. Links are shown as
  text with their URL. Tables are shown as plain text.
- Recommended sections, in order:
  - `## Preparación`: an ordered list of steps, one action per step.
  - `## Notas` (optional): tips, substitutions, storage.
- An empty body, or one without `## Preparación`, gives `W09`.
- Ingredient quantities are not parsed from the body. The front matter is the source of truth.

## 9. Validation

Errors reject the recipe (other recipes in the same pack can still be imported). Warnings are
shown in the preview and do not block. Messages shown to the user are Spanish string resources;
the codes are stable and used in tests.

### 9.1 Pack level

| Code | Meaning |
|---|---|
| `P01` | Unreadable input: not UTF-8 text or not a valid zip |
| `P02` | A limit in § 2 was exceeded |
| `P03` | Unsafe zip entry path |
| `P04` | No recipe document found |
| `P05` | The file is an app backup (`backup-manifest.json` present). The app offers Restore instead |

### 9.2 Recipe errors

| Code | Meaning |
|---|---|
| `E01` | Front matter missing or not closed |
| `E02` | YAML syntax error (line and column reported), including duplicate keys |
| `E03` | Forbidden YAML feature (anchor, alias, tag, merge key, several documents) |
| `E04` | `format` missing or unsupported |
| `E05` | Required field missing (field path reported, e.g. `ingredients[2].unit`) |
| `E06` | Wrong type |
| `E07` | Value out of range, too long, or too many items |
| `E08` | Unknown unit |
| `E09` | Quantity missing for a unit that requires one |
| `E10` | Invalid quantity (≤ 0, > 100 000, malformed fraction) |
| `E11` | Invalid `package_size` |
| `E12` | File larger than 256 KiB, or body longer than 20 000 characters |

### 9.3 Recipe warnings

| Code | Meaning |
|---|---|
| `W01` | Unknown field, ignored (field path reported) |
| `W02` | Value normalised: unit alias, decimal comma, tag case, numeric `product_id` |
| `W03` | `image` not found in the pack |
| `W04` | `image` value invalid, ignored |
| `W05` | Non-staple ingredient without `match` |
| `W06` | Quantity ignored for `al_gusto` |
| `W07` | Link not resolved (provider not installed, or product not in the local catalogue) |
| `W08` | More than one preferred link for a provider |
| `W09` | Body empty or without `## Preparación` |
| `W10` | Possible duplicate: same `uid` or same normalised title as an existing recipe |
| `W11` | Same ingredient name and unit twice in the same group |
| `W12` | Quantity rounded to 3 decimals |

## 10. Import semantics

- `uid` present and already in the library → `W10`, and the user picks Replace, Save as copy, or
  Skip. "Save as copy" gives the recipe a new `uid`.
- `uid` missing → a new UUID is assigned.
- Unknown fields are dropped (`W01`), so a newer-minor-version file still imports.
- Ingredient names are trimmed. Inner whitespace is collapsed to one space. Case is kept.
- The `name` key for link memory and pantry matching is the normalised form described in
  [SPEC § 5.4](SPEC.md#54-ingredient--product-matching).

## 11. Canonical export form

The serializer always produces this exact form, so `export(import(export(r))) == export(r)`
byte for byte:

- UTF-8 without BOM, LF line endings, a final newline.
- Key order. Recipe: `format`, `uid`, `title`, `description`, `servings`, `prep_minutes`,
  `cook_minutes`, `tags`, `image`, `source`, `ingredients`. Ingredient: `name`, `quantity`, `unit`,
  `unit_weight_g`, `note`, `group`, `optional`, `pantry`, `match`, `links`. Match: `query`,
  `package_size`, `store_brand_ok`. Link: `provider`, `product_id`, `name`, `preferred`.
- Absent fields are omitted. `optional` and `pantry` are omitted when `false`.
  `match.store_brand_ok` is always written. `preferred` is written only when `true`.
- Numbers: integers without a decimal point. Decimals use `.`, with at most 3 decimals and no
  trailing zeros.
- Strings: plain style when that is unambiguous YAML, otherwise double-quoted with escapes.
  `product_id` is always double-quoted. `tags` use flow style: `tags: [mexicana, cena]`.
- Two-space indentation. Ingredient items are written as `  - name: …`.
- One blank line after the closing `---`, then the body with surrounding blank lines trimmed.
- In a zip: recipes at the root as `<slug>.md` (lowercase ASCII, hyphens, `-2`, `-3`… on
  clashes). Images go in `images/<slug>.<ext>`.

## 12. Versioning

- The current version is `cpl-recipe/1`.
- Adding optional fields keeps version 1. Older apps ignore them with `W01`.
- Any change that alters the meaning of existing fields or units bumps the version to
  `cpl-recipe/2`. The importer keeps reading every earlier version. The exporter writes the
  latest one.
- Any change to this document must also update the generator prompt, the examples and the
  parser tests in the same PR (see CLAUDE.md).

## 13. Full example

See [examples/recipe-packs/tacos-de-pollo.md](../examples/recipe-packs/tacos-de-pollo.md). It
must import with zero errors and zero warnings (SPEC AC3.4).
