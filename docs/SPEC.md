# CocinaParaLechugones — Specification

| | |
|---|---|
| Status | Draft v1, written in phase 0 (2026-11-18) |
| Audience | The owner, friends who use the APK, and Claude Code |
| Related | [RECIPE_FORMAT.md](RECIPE_FORMAT.md), [providers/](providers/README.md), [DEVICE_SETUP.md](DEVICE_SETUP.md), [../CLAUDE.md](../CLAUDE.md) |

---

## 1. Purpose and scope

CocinaParaLechugones ("cooking for lettuce-heads") is a personal Android app for cooking at home
with real supermarket products. It answers four questions:

1. **What do I need to buy for these recipes, and where?** (shopping list grouped by supermarket)
2. **How much of each package does a dish use, and what does it cost?** ("1/2 paquete de ñoquis,
   250 g de 500 g", dish cost, whole-package cost, leftovers)
3. **What do I already have?** (pantry, including opened packages)
4. **What can I cook with what I have?**

### 1.1 Goals

- Fast and fully usable offline once the catalogue is cached.
- Precise quantities: every amount is unit-aware and convertible where it makes physical sense.
- Recipes can be created in the app, or generated elsewhere (for example by an external Claude
  chat) as **recipe packs** and imported in a few taps.
- Adding a new supermarket means adding one Gradle module.

### 1.2 Non-goals

- No accounts, sync, analytics, crash reporting, ads, remote config or AI features in the app.
- No ordering or checkout at supermarkets. No logging into supermarket accounts.
- No background price polling. Prices refresh only when the user asks.
- No Play Store release, no multi-language UI (Spanish only), no tablet-specific layouts
  (they should not break, but are not optimised).

### 1.3 Users and distribution

The owner plus a few friends. Each person sideloads a release APK signed with the owner's
personal key. Each install is independent; there is no shared data. Recipe packs and backup
files are the only ways to move data between phones.

---

## 2. Glossary

| UI (es-ES) | Code (English) | Meaning |
|---|---|---|
| Supermercado | `Provider` | A product source: Mercadona, Día, or Manual |
| Producto | `Product` | A sellable item at one provider, with package size and price |
| Envase / paquete | `Package` | One sellable unit of a product (paquete, bandeja, bote, pieza…) |
| Marca blanca | `StoreBrand` | The provider's own brand (Hacendado, Dia…) |
| Receta | `Recipe` | Title, servings, ingredients, steps (Markdown) |
| Ingrediente | `Ingredient` | Quantity + unit + generic name, linked to products |
| Vínculo | `IngredientLink` | Link between an ingredient and one product |
| Memoria de vínculos | `LinkMemory` | Remembered links per normalised ingredient name |
| Básico de despensa | `pantry staple` | Ingredient flagged `pantry: true` (salt, oil, spices) |
| Despensa | `Pantry` / `PantryLot` | What is at home. One lot is one package or one opened remainder |
| Lista de la compra | `ShoppingList` | Items to buy, grouped by provider |
| Pack de recetas | `RecipePack` | `.md` or `.zip` in the [recipe format](RECIPE_FORMAT.md) |
| Copia de seguridad | `Backup` | Single file with all app data |
| "Lo he cocinado" | `CookEvent` | Records a cooked recipe and deducts the pantry |

---

## 3. Architecture

### 3.1 Modules

```
:app                    Android application. Compose UI, ViewModels, navigation, Hilt entry points,
                        Android I/O adapters (SAF file picker, share intents, image decoding).
:core:domain            Pure Kotlin (JVM). Models, units and quantities, cost engine, scaling,
                        pantry and shopping-list logic, product matching, recipe pack
                        parser/validator/serializer. No Android imports.
:core:data              Android library. Room database and DAOs, DataStore settings, repositories,
                        recipe image storage, backup/restore.
:provider:api           Pure Kotlin (JVM). SupermarketProvider contract, normalised product model,
                        rate limiter, shared OkHttp/Json configuration, ProviderResult errors.
:provider:mercadona     Pure Kotlin (JVM). Retrofit service, DTOs, mapper → provider:api model.
:provider:dia           Same for Día (phase 7, subject to the feasibility check in providers/dia.md).
build-logic/            Gradle convention plugins (android-app, android-library, jvm-library, hilt, room).
```

Dependency direction: `:app → :core:data → :core:domain`; `:core:data → :provider:api`;
`:provider:<x> → :provider:api → :core:domain` (only for shared value types such as `Unit`
and `Money`). Provider modules never depend on Room or Android.

Providers are contributed to a `Set<SupermarketProvider>` through Hilt multibindings
(`@IntoSet`). The rest of the app only sees the set, so adding a supermarket does not touch
feature code. See [providers/README.md § Adding a provider](providers/README.md#adding-a-provider).

### 3.2 Layers inside the app

- **UI**: composables are stateless and render an immutable `UiState`. Events go to the
  ViewModel.
- **ViewModel**: combines repository `Flow`s into `StateFlow<UiState>` and calls domain
  use cases.
- **Domain**: pure functions and small use-case classes (`ComputeRecipeCost`, `BuildShoppingList`,
  `PlanCookDeductions`, `MatchIngredients`, `ParseRecipePack`…).
- **Data**: repositories own Room, DataStore, files and providers. They return `Flow` and
  `suspend` results.

### 3.3 Technology choices

| Concern | Choice | Notes |
|---|---|---|
| Language | Kotlin 2.x (K2), JVM target 17 | |
| UI | Jetpack Compose, Material 3, Navigation Compose with type-safe routes | Dynamic colour on Android 12+, with a green fallback palette |
| DI | Hilt (KSP) | `hilt-core` in JVM provider modules |
| DB | Room (KSP) with FTS4 for product search | Schemas exported to `core/data/schemas/` |
| Settings | DataStore Preferences | |
| Network | Retrofit + OkHttp + kotlinx.serialization converter | One shared `OkHttpClient` with per-provider interceptors |
| YAML | kaml (kotlinx.serialization YAML) | Only for recipe front matter |
| Markdown rendering | A small Compose renderer for the subset in RECIPE_FORMAT.md, or a maintained M3 Markdown library (decided in phase 2) | |
| Images | Coil 3 | Disk cache for product thumbnails |
| Tests | JUnit 5 + kotlin.test (JVM modules), JUnit 4 + Robolectric (Room/Android), Turbine (Flow), MockWebServer (providers) | |
| Lint/format | Android Lint, ktlint via Spotless | |
| CI | GitHub Actions: `test` + `lint` on every PR | |

Exact library versions are pinned in `gradle/libs.versions.toml` in phase 1, using the latest
stable releases at that time.

### 3.4 Build types and identity

| | debug | release |
|---|---|---|
| applicationId | `com.lechugones.cocina.debug` | `com.lechugones.cocina` |
| Label | "Cocina (debug)" | "Cocina para Lechugones" |
| Signing | `~/.android/debug.keystore` (back it up, see DEVICE_SETUP.md) | Personal release keystore outside the repo, referenced from gitignored `keystore.properties` |
| Minify | off | R8 on, with rules for kotlinx.serialization and Retrofit |
| Data | Test data, separate from release | The owner's real data |

The two builds install side by side. Development and adb testing use the debug app. Real data
lives in the release app. Data moves between them only through the backup file (§ 4.7).

---

## 4. Features

Each feature lists behaviour and **acceptance criteria (AC)**. Phases are in § 9.

### 4.1 Supermarket catalogue (providers)

#### Behaviour

- A **provider** module turns a supermarket's internal website JSON into the normalised
  `ProviderProduct` model ([providers/README.md](providers/README.md)). Initial providers:
  - **Mercadona**: verified, see [providers/mercadona.md](providers/mercadona.md).
  - **Día**: endpoints reported by third parties, **blocked from our network during research**.
    Implemented only if the feasibility check in [providers/dia.md](providers/dia.md) passes.
  - **Manual**: built in, always available. The user types a product (name, brand, store-brand
    flag, package size, price, shop name). This covers shops without an API and providers that
    are down or refuse us.
- **Catalogue sync** ("Actualizar catálogo") is on demand and per provider. It downloads the
  provider's food categories at the provider's rate limit, with a progress bar, and can be
  cancelled. Products are upserted into Room. Products missing from a full sync are marked
  `unavailable`, not deleted, so existing links do not break.
- **Search** runs locally over the cached catalogue (Room FTS4, accent-insensitive,
  prefix matching). It is instant and works offline.
- **Price refresh** ("Actualizar precios") is on demand from a recipe, the shopping list, a
  product, or the catalogue. It refreshes only the products involved, at the rate limit.
- **Caching and fallback**: the last known price and its `fetchedAt` are always kept. If a
  refresh fails, the cached price stays and the UI shows "Precio del 12/11/2026" with a warning
  icon. Prices older than the staleness threshold (setting, default 14 days) are flagged.
- **Price history**: each refresh that changes a price appends a `PriceSnapshot` row
  (provider, product, price, date). Phase 4 only stores it; showing it is optional.
- **Store brands**: each product has `brandKind` = `STORE`, `NATIONAL` or `UNBRANDED` (fresh
  produce with no brand). The setting **"Solo marca blanca"** hides `NATIONAL` products in
  search and matching. `UNBRANDED` products stay visible.
- **Settings**: active supermarkets (multi-select, plus a "principal" one), postal code (used
  to pick the Mercadona warehouse and the Día session), "Solo marca blanca", "Precios Club Dia"
  (if Día is enabled), catalogue categories to sync, staleness threshold.

#### AC

- AC1.1 With no network, search, recipes, costs, pantry and the shopping list all work from cache.
- AC1.2 A provider error (timeout, 5xx, 403, parse failure) never crashes the app or wipes
  cached data. The UI shows a Spanish error and the cache date.
- AC1.3 No provider request is made unless the user triggered it. Verified by a test with a
  fake clock and a request-counting interceptor.
- AC1.4 The rate limiter never lets requests to one provider exceed its configured rate,
  including when several screens trigger refreshes at once.
- AC1.5 A full Mercadona food catalogue sync finishes in under 5 minutes on Wi-Fi and makes
  at most ~110 requests.

### 4.2 Recipes

#### Behaviour

- **List**: search by title, ingredient or tag; filter by tag; sort by name, recently cooked,
  recently added, or cost per serving.
- **Create / edit**: title, description, servings, prep and cook minutes, tags, image (from
  gallery or camera through the system picker), source, steps (Markdown editor with preview),
  and ingredients.
- **Ingredient**: quantity, unit ([units](RECIPE_FORMAT.md#5-units)), generic name, note,
  group (for example "Para el guacamole"), optional flag, **pantry staple** flag, optional
  `unitWeightG` (grams per unit, for count and spoon units), match hints, and **links to one or
  more products** (at most one *preferred* link per provider). Ingredients can be reordered.
- **Duplicate**: deep copy with title "‹título› (copia)", a new uid, and the same links.
- **Delete**: asks for confirmation. Removes the recipe's contributions from the shopping list.
  Cook history keeps a title snapshot.
- **Scale servings**: a servings stepper on the detail screen scales every quantity except
  `pizca` and `al_gusto`. The scaled view drives costs, "Lo he cocinado" and "Añadir a la
  lista". It is not saved unless the user picks "Guardar raciones" or "Guardar como receta
  nueva".
- **Linking**: from an ingredient, "Vincular producto" opens a catalogue search pre-filled
  with the match query (or the name). Picking a product stores the link and updates the
  **link memory** for the ingredient's normalised name (§ 5.4).
- **Pantry staples**: shown in their own collapsed section ("Básicos de despensa"). By default
  they are left out of the dish cost (setting "Incluir básicos en el coste") and out of the
  shopping list unless the pantry knows they are missing (§ 4.6).

#### AC

- AC2.1 Scaling 2 → 3 servings multiplies by 1.5. Display rounds to friendly values
  (§ 5.3) while computations keep full precision.
- AC2.2 Duplicating and then editing the copy never changes the original.
- AC2.3 Every recipe field survives export → import unchanged (§ 4.3).

### 4.3 Recipe packs: import and export

The exact file format is in [RECIPE_FORMAT.md](RECIPE_FORMAT.md).

#### Inputs

- **Share sheet**: `ACTION_SEND` / `ACTION_SEND_MULTIPLE` with `text/plain`, `text/markdown`,
  `text/x-markdown`, `application/zip`, `application/x-zip-compressed` and
  `application/octet-stream`. `ACTION_VIEW` for `text/markdown` and `application/zip`.
  Plain shared text must contain exactly one recipe document.
- **File picker**: "Importar recetas" opens the SAF picker (`.md`, `.zip`, several files allowed).
- Content is sniffed, not trusted by MIME type: zip magic `PK\x03\x04`, then the app backup
  manifest (which is redirected to Restore), then front matter.

#### Pipeline

1. **Read** with the size limits in RECIPE_FORMAT.md § 2 (zip-slip and zip-bomb safe).
2. **Validate** every recipe. Errors block that recipe; warnings do not. Other recipes in the
   pack can still be imported.
3. **Preview**: one card per recipe with title, servings, ingredient count and image, plus its
   errors and warnings in Spanish. The user can deselect recipes.
4. **Duplicates**: same `uid` as an existing recipe, or the same normalised title → choose
   "Reemplazar", "Guardar como copia" or "Omitir" (per recipe, with "aplicar a todas").
5. **Product matching** (§ 5.4) for every ingredient of the selected recipes, against the local
   catalogue of active providers. The screen lists ingredients with the suggested product per
   provider, package size and price. Each can be changed ("Ver más"), removed, or left unlinked.
   **"Aceptar todo"** confirms every pre-selected suggestion in one tap. Links included in the
   file (`links:`) and remembered links are pre-selected first.
6. **Save** in one Room transaction. Images are copied to app-private storage and downscaled to
   at most 1600 px on the long side. Link memory is updated with the confirmed links.
7. **Summary**: "3 recetas importadas, 1 omitida" with a link to each.

If no provider catalogue is synced yet, step 5 offers "Actualizar catálogo" or "Importar sin
vincular".

#### Export

- One recipe without an image → a `.md` file. With an image, or several recipes → a `.zip`
  pack. "Exportar todas" → one `.zip`.
- Delivered through the share sheet or saved with SAF `CreateDocument`.
- Exports include `uid` and the confirmed `links` (provider + product id + name snapshot), so a
  re-import on the same or another phone restores the links when those products are cached.

#### AC

- AC3.1 **Round trip**: for every recipe `r`, `import(export(r))` equals `r` in all fields
  (ids aside), including links, groups, flags and the Markdown body.
- AC3.2 **Canonical idempotence**: `export(import(export(r)))` is byte-identical to `export(r)`.
- AC3.3 Malformed input (bad YAML, unknown unit, path traversal in a zip, oversized files)
  produces a clear Spanish message and never a crash. Covered by parser tests with fixtures.
- AC3.4 The sample pack in `examples/recipe-packs/` imports with zero errors and zero warnings.
- AC3.5 "Aceptar todo" is a single tap and then shows the result. No extra confirmation dialog.

### 4.4 Quantities and cost

For a recipe at the chosen servings and supermarket mode, each ingredient line shows:

- The quantity in the recipe unit: "300 g de contramuslo de pollo deshuesado".
- When linked and convertible, the **package fraction**: "1/2 paquete de ñoquis, 250 g de 500 g"
  (§ 5.3).
- The proportional cost of that ingredient: "1,87 €".

The cost card shows:

| Figure | Definition |
|---|---|
| **Coste del plato** | Σ (quantity used × price per base unit of the selected product). Staples only if the setting is on |
| **Por ración** | Dish cost ÷ current servings |
| **Comprando envases enteros** | Σ over distinct products of ⌈total used ÷ usable package quantity⌉ × package price |
| **Te falta comprar** | Like the previous row, but after subtracting pantry stock (§ 4.5) |
| **Sobras** | For each product: packages bought × usable quantity − used, shown as a fraction plus amount ("sobra 1/3 paquete, 120 g") |
| **Comparativa** | The same figures per active supermarket, with coverage ("5 de 6 ingredientes con precio"), plus a **"Mejor combinación"** that picks, for each ingredient, the provider with the lowest whole-package cost |

Supermarket mode is a selector on the recipe: "Principal" (default), one specific provider, or
"Más barato".

Rules:

- **Selected product** for an ingredient in a given provider: its preferred link at that
  provider, otherwise its first link there. In "Más barato" mode: the cheapest by whole-package
  cost across its links.
- Several ingredients linked to the same product (for example olive oil in two groups) are
  consolidated before rounding up packages.
- **Usable quantity** of a package = drained weight if the provider reports one, otherwise net
  quantity (see Mercadona mozzarella: 250 g net, 125 g drained).
- **Unknown cost**: if a non-staple, non-optional ingredient has no link, no price, or
  incompatible units, the total is shown as "≥ 7,40 €" with "1 ingrediente sin precio" and a
  shortcut to fix it.
- **Approximate**: variable-weight products (`approx`) and stale prices put "≈" in front of the
  figure.
- Optional ingredients are excluded from totals, with a toggle "Incluir opcionales".

#### AC

- AC4.1 The cost engine is pure domain code with tests for: proportional cost, package rounding,
  consolidation, leftovers, drained weight, count packages (6 ud), unknown cost, staples on and
  off, optional ingredients, and "Más barato" selection.
- AC4.2 Money is integer cents end to end. Computation uses `BigDecimal` and rounds half-up to
  cents only for display. A test pins the rounding of 1/3 packages.

### 4.5 Pantry ("Despensa")

#### Behaviour

- A **pantry lot** is one package or one opened remainder: product (or generic name only),
  remaining quantity, package size, `opened` flag, `openedAt`, `addedAt`, optional best-before
  date, optional location ("nevera", "congelador", "armario").
- Staples can be tracked **presence-only** ("tengo sal" / "se ha acabado") with no quantity.
- Adding: from the catalogue, from a shopping list ("Comprado"), or manually with a generic name.
- The list is grouped: Abiertos, Sin abrir, Básicos, with search and a location filter.
- **"Lo he cocinado"** on a recipe (at the current servings):
  1. The domain plans deductions: for each ingredient, matching lots (§ 5.5) are consumed
     **opened lots first, then the earliest best-before, then FIFO by `addedAt`**. A sealed lot
     that is partly used becomes opened.
  2. A confirmation sheet lists every deduction, editable: amount, which lot, or skip. Missing
     amounts are shown ("No tenías suficiente: faltan 50 g").
  3. Confirm applies it in one transaction and records a `CookEvent`. A snackbar offers
     "Deshacer", which restores the lots exactly.
  4. Lots that reach zero are removed. Presence-only staples are not changed; the sheet offers
     "Se ha acabado" for each.
- **"¿Qué puedo cocinar?"**: every recipe, at its default servings, gets a coverage status
  ignoring staples and optional ingredients: **Lo tienes todo**, **Te falta 1–2**, **Te faltan
  más**. Sorted by status, then by the share of the dish cost already in the pantry. Each card
  lists the missing items and has "Añadir lo que falta a la lista".

#### AC

- AC5.1 Deduction planning is pure domain code, tested for: opened-first order, best-before
  order, partial lots, insufficient stock, unit conversion (cda of oil from a 1 l bottle),
  count units, and undo restoring the exact previous state.
- AC5.2 Nothing is deducted without the confirmation sheet.

### 4.6 Shopping list ("Lista de la compra")

#### Behaviour

- One active list. Sources: "Añadir a la lista" from a recipe (with servings, can be repeated),
  "Añadir lo que falta" from "¿Qué puedo cocinar?", and manual items (free text, or a catalogue
  product).
- **Build**: for each recipe contribution, ingredient needs are converted to the selected
  product's unit, **consolidated per product** (or per normalised generic name when unlinked),
  then the pantry stock is subtracted, then the rest is rounded up to whole packages.
  Staples are added only when the pantry tracks them and says they are missing (setting
  "Añadir básicos que me faltan").
- **Supermarket assignment** per item: "Principal", "Más barato por producto", or a manual
  override per item.
- **Grouped by supermarket**, with a subtotal per group and an **estimated total**
  ("≈ 23,40 €" when any price is approximate or stale; items without a price are counted and
  shown as "sin precio").
- Each item shows packages to buy, the amount needed, and which recipes need it.
- Items can be checked off. **"Terminar compra"** moves checked items into the pantry as sealed
  lots (one per package bought; the quantity can be edited first) and removes them from the
  list. Unchecked items stay.
- **"Recalcular"** rebuilds the list after recipes or the pantry changed. Manual items and manual
  overrides are kept.
- **"Compartir"** sends the list as plain text through the share sheet:

```
Lista de la compra — 18/11/2026

MERCADONA (≈ 8,56 €)
[ ] 1 × Contramuslos de pollo deshuesados y sin piel (≈ 550 g) — ≈ 3,74 €
[ ] 1 × Maxi tortillas de trigo Hacendado (6 ud) — 1,50 €
[ ] 1 × Aguacate (≈ 200 g) — ≈ 1,12 €
[ ] 1 × Tomates cherry (500 g) — 2,20 €

SIN SUPERMERCADO
[ ] Cilantro fresco

Total estimado: ≈ 8,56 € (1 artículo sin precio)
```

#### AC

- AC6.1 List building is pure domain code with tests for consolidation across recipes, pantry
  subtraction (opened lots included), package rounding, staple rules, and grouping and totals.
- AC6.2 The shared text matches the format above. Its strings come from string resources.

### 4.7 Backup and restore

#### Behaviour

- **"Crear copia de seguridad"** writes one file, `cocina-backup-YYYYMMDD-HHmm.zip`, through SAF
  `CreateDocument`. Contents:
  - `backup-manifest.json`: format version, app version, Room schema version, creation time,
    row counts, SHA-256 of every other entry.
  - `database/cocina.db`: a consistent snapshot of the Room database, taken after
    `PRAGMA wal_checkpoint(FULL)` with writes paused by the repository write lock.
  - `datastore/settings.preferences_pb`.
  - `images/…`: recipe images.
  The product catalogue is included, so cached prices survive a restore.
- **"Restaurar copia"** reads the file and validates it before touching anything: manifest,
  checksums, schema version not newer than the app's, `PRAGMA integrity_check`. Then it shows a
  preview ("42 recetas, 118 productos en despensa, copia del 18/11/2026"). On confirmation it
  first writes an automatic backup of the current data to app-private storage (the last 3 are
  kept), then swaps in the files and restarts the app process. Room migrations upgrade older
  backups on open.
- Because restore works through migrations, the backup format does not change when the schema
  changes. That is why the backup is a database snapshot, not a JSON dump.
- `android:allowBackup="false"` and empty data-extraction rules: there is no Google cloud
  backup. The backup file is the only path.

#### AC

- AC7.1 Backup → restore on the same build restores every table, setting and image (verified by
  an instrumented or Robolectric test comparing row counts and checksums).
- AC7.2 Restoring a backup from schema version N−1 runs the migrations and opens.
- AC7.3 Restoring a corrupt or newer-version file changes nothing and explains why.
- AC7.4 A failed restore can be undone from the automatic pre-restore backup.

### 4.8 Settings ("Ajustes")

| Setting | Default |
|---|---|
| Supermercados activos / principal | Mercadona / Mercadona |
| Código postal | empty (asked the first time a sync runs) |
| Solo marca blanca | off |
| Precios Club Dia | off (shown only if Día is active) |
| Categorías del catálogo | all food and drink categories |
| Incluir básicos en el coste | off |
| Incluir opcionales en el coste | off |
| Precio antiguo a partir de | 14 days |
| Añadir básicos que me faltan a la lista | on |
| Copia de seguridad | Create / Restore |
| Acerca de | Version, open-source licences, legal note (not affiliated with any supermarket) |

---

## 5. Domain rules

### 5.1 Units

The canonical unit table, aliases and conversion factors are defined in
[RECIPE_FORMAT.md § 5](RECIPE_FORMAT.md#5-units). Summary:

- **Mass** (`g`, `kg`) and **volume** (`ml`, `cl`, `l`, plus kitchen units `cdta` = 5 ml,
  `cda` = 15 ml, `taza` = 250 ml) convert within their dimension.
- **Count** units (`ud`, `diente`, `loncha`, `rebanada`, `hoja`, `ramita`) convert only to
  themselves, except through `unitWeightG`.
- **Negligible** units (`pizca`, `al_gusto`) have no meaningful quantity. Their cost is 0, they do
  not scale, and they deduct nothing from the pantry.
- **Cross-dimension** conversion (volume ↔ mass, count ↔ mass) is possible only when the
  ingredient has `unitWeightG` (grams per one unit). Example: `1 cda` of cumin with
  `unitWeightG: 6` → 6 g.
- A product package is expressed as `{netQuantity, baseUnit ∈ g | ml | ud, unitCount?, drained?}`.
  A product sold in grams with `unitCount` (tortillas: 360 g, 6 ud) accepts both mass and `ud`
  ingredients.

If no conversion path exists, the ingredient is "incompatible" for that product and the UI
asks for `unitWeightG` or a different product.

### 5.2 Numbers

- Quantities: fixed-point `Long` in thousandths of their own unit (`1,5 kg` → `1500` with unit
  `kg`). Converted to a base unit they keep thousandths (`1,5 kg` → `1 500 000` thousandths of a
  gram). Recipe files allow at most 3 decimals.
- Money: `Long` euro cents. Package prices come from providers as decimal strings ("1.50") and
  are parsed exactly.
- Division and proportion: `BigDecimal` with scale 6 and `RoundingMode.HALF_UP`. Cents are
  rounded only for display and for whole-package totals (which are exact anyway).

### 5.3 Display rules

**Friendly quantity rounding** (scaled values):

| Unit | Rule |
|---|---|
| g, ml | < 10: one decimal if not whole; 10–100: whole; > 100: nearest 5 |
| kg, l | Shown when ≥ 1000 g / ml, with up to 2 decimals ("1,25 kg") |
| ud and other count units | Nearest 1/4 ("1 1/2 ud") |
| cdta, cda, taza | Nearest 1/2 |

**Package fraction** for used amount `u` against usable package quantity `p` (same base unit),
`r = u / p`:

1. `whole = ⌊r + 0.02⌋`, `rest = r − whole` (negative rests are clamped to 0).
2. Snap `rest` to the nearest of {0, 1/4, 1/3, 1/2, 2/3, 3/4, 1}. If the distance is ≤ 0.02 the
   fraction is exact; otherwise it is prefixed with "≈".
3. If `r < 1/8`: show a percentage instead, always with the neutral noun "envase" to avoid
   gender agreement: "≈ 5 % del envase".
4. The noun is the product's packaging word lowercased (paquete, bandeja, bote, sobre, pieza,
   tarrina, botella, lata, malla), defaulting to "envase". It is pluralised when `r > 1`
   (vowel → +s, z → ces, otherwise +es).
5. Template: `{fraction} {noun} de {ingredient name}, {used} de {package}`.
   Several packages: `1 1/2 paquetes de ñoquis, 750 g de 2 × 500 g`.
   Count packages: `2/3 paquete de tortilla de trigo maxi, 4 ud de 6 ud`.

Examples (tested):

| Used | Package | Output |
|---|---|---|
| 250 g | 500 g | 1/2 paquete de ñoquis, 250 g de 500 g |
| 750 g | 500 g | 1 1/2 paquetes de ñoquis, 750 g de 2 × 500 g |
| 150 g | 500 g bandeja | ≈1/3 bandeja de tomate cherry, 150 g de 500 g |
| 4 ud | 6 ud (360 g) | 2/3 paquete de tortilla de trigo maxi, 4 ud de 6 ud |
| 10 g | 17 g sobre | ≈2/3 sobre de sazonador para fajitas, 10 g de 17 g |
| 15 ml (1 cda) | 1 l botella | ≈ 2 % del envase de aceite de oliva virgen extra, 15 ml de 1 l |

The domain returns a structured `PackageFraction(whole, rest, exact, percent, packages, used,
package)`. The UI builds the sentence from `<plurals>` and string resources.

### 5.4 Ingredient → product matching

Used by import and by "Vincular producto". It runs offline against the local catalogue.

1. **Normalised name key**: lowercase → Unicode NFD → strip diacritics → keep `[a-z0-9 ]` →
   collapse spaces → trim. "Contramuslo de Pollo  deshuesado" → `contramuslo de pollo deshuesado`.
2. **Candidates per active provider**, in this order:
   1. Links present in the imported file whose product exists in the cache.
   2. **Link memory** for the name key (most recently confirmed first).
   3. FTS search with `match.query` (falling back to the name), top 30.
3. **Score** for FTS candidates (0–100):
   - Text (0–60): share of query tokens found in the product name (accent-insensitive, simple
     Spanish plural folding `-es`/`-s`), with a bonus when the tokens appear in order.
   - Package size (0–25): `25 × (1 − min(1, |ln(candidate / hint)| / ln 4))` when
     `match.package_size` is given and comparable; 12 when no hint is given.
   - Brand (0–15): `STORE` or `UNBRANDED` with `store_brand_ok: true` → 15; `NATIONAL` → 5. When
     "Solo marca blanca" is on, `NATIONAL` candidates are dropped.
   - Unavailable products are dropped.
4. The best candidate per provider is **pre-selected** when it comes from (1) or (2), or when its
   score is ≥ 55. Below that it is suggested but not pre-selected.
5. Confirmed links update link memory (`nameKey, provider, productId, lastUsedAt, useCount`).

Matching quality is tuned with a fixture catalogue in tests (`MatchIngredientsTest`).

### 5.5 Ingredient ↔ pantry matching

A pantry lot matches an ingredient when the lot's product is one of the ingredient's links
(any provider), or the lot's normalised generic name equals the ingredient's name key, or the
lot's product appears in the link memory of that name key. Quantities are converted with § 5.1.
A lot whose unit cannot be converted is listed but needs a manual amount.

---

## 6. Data model (Room, first draft)

Final column names are fixed in phase 1 and 2. All ids are `TEXT` UUIDs except provider product
keys.

| Table | Key columns |
|---|---|
| `recipe` | `id`, `uid`, `title`, `description`, `servings`, `prep_minutes`, `cook_minutes`, `image_path`, `source`, `body_md`, `created_at`, `updated_at` |
| `recipe_tag` | `recipe_id`, `tag` |
| `ingredient` | `id`, `recipe_id`, `position`, `group_label`, `name`, `name_key`, `quantity_milli`, `unit`, `note`, `optional`, `pantry_staple`, `unit_weight_g_milli`, `match_query`, `match_package`, `match_store_brand_ok` |
| `ingredient_link` | `ingredient_id`, `provider_id`, `product_id`, `preferred`, `position` |
| `link_memory` | `name_key`, `provider_id`, `product_id`, `last_used_at`, `use_count` |
| `product` | `provider_id`, `product_id` (PK pair), `name`, `brand`, `brand_kind`, `packaging`, `net_quantity_milli`, `base_unit`, `unit_count`, `drained_milli`, `approx`, `price_cents`, `reference_price_cents`, `reference_unit`, `image_url`, `web_url`, `category_path`, `available`, `fetched_at`, `last_error` |
| `product_fts` | FTS4 over `name`, `brand`, `category_path` (content = `product`) |
| `price_snapshot` | `provider_id`, `product_id`, `price_cents`, `fetched_at` |
| `pantry_lot` | `id`, `provider_id?`, `product_id?`, `name`, `name_key`, `quantity_milli?`, `base_unit?`, `package_quantity_milli?`, `opened`, `opened_at`, `added_at`, `best_before`, `location`, `presence_only` |
| `shopping_item` | `id`, `provider_id?`, `product_id?`, `name`, `name_key`, `needed_milli`, `base_unit`, `packages`, `checked`, `manual`, `provider_override` |
| `shopping_contribution` | `item_id`, `recipe_id`, `servings`, `needed_milli` |
| `cook_event` | `id`, `recipe_id?`, `recipe_title`, `servings`, `cooked_at`, `undone` |
| `cook_deduction` | `event_id`, `lot_id`, `lot_snapshot_json`, `deducted_milli` |

Manual products are `product` rows with `provider_id = 'manual'`.

---

## 7. Non-functional requirements

- **Platform**: minSdk 26 (Android 8.0), target and compile SDK 36. Primary test device: OnePlus
  CPH2645, Android 16 ([DEVICE_SETUP.md](DEVICE_SETUP.md)).
- **Performance**: cold start < 1.5 s on the test device. Catalogue search < 100 ms for 10k
  products. Recipe cost recomputation < 50 ms.
- **Offline-first**: see AC1.1. Network is used only for provider endpoints and product images.
- **Security**: cleartext traffic disabled (network security config). Zip extraction is
  zip-slip and zip-bomb safe. No exported components except the import activity's intent
  filters. Imported Markdown is rendered without HTML or remote images.
- **Accessibility**: every icon has a Spanish content description. Touch targets are ≥ 48 dp.
  Layouts respect font scaling up to 200 %. Colour is never the only signal (stale prices also
  get an icon).
- **Localisation**: Spanish only, es-ES formats (§ CLAUDE.md language rules).
- **Data safety**: Room migrations are mandatory and tested. Destructive fallback is forbidden.
  Restore always makes an automatic backup first.
- **Etiquette and legal**: low-volume, user-initiated requests with an honest User-Agent and
  rate limits. No bot-protection evasion. Not affiliated with any supermarket. Brand names
  belong to their owners. Private use only.

---

## 8. Testing strategy

| Layer | Tooling | What |
|---|---|---|
| `:core:domain` | JUnit 5, kotlin.test | Units, quantities, display rounding, package fractions, cost engine, scaling, pantry planning and undo, shopping list building, matching scores, name normalisation, recipe parser/validator/serializer, round-trip and idempotence (property-style tests over generated recipes) |
| `:provider:*` | JUnit 5, MockWebServer, JSON fixtures | DTO parsing of recorded responses, mapping to `ProviderProduct`, rate limiter (virtual time), retries, 403/429/5xx handling |
| `:core:data` | Robolectric + Room in-memory, `MigrationTestHelper` | DAOs, FTS search, transactions, migrations N−1 → N, backup/restore |
| `:app` | Compose UI tests (a few critical flows) and manual device checks | Import "Aceptar todo", cook confirmation, shopping list check-off |

Fixtures recorded from real endpoints are trimmed to the fields we use and stored under
`src/test/resources/fixtures/<provider>/`.

---

## 9. Development phases

Each phase is one branch (`phase/<n>-<slug>`) and one PR, with a plan agreed before coding. Each
phase ends with: tests green, lint clean, docs updated, and a debug build installed and checked on
the phone.

| Phase | Scope | Exit criteria |
|---|---|---|
| **0 — Specification** | Repository, CLAUDE.md, this spec, recipe format, generator prompt, sample pack, provider research, device setup | Committed and pushed |
| **1 — Foundations** | Gradle multi-module skeleton with version catalog and convention plugins; Hilt; Room v1 (recipe tables) with schema export and migration test harness; DataStore settings; M3 theme; navigation shell (Recetas, Despensa, Compra, Catálogo, Ajustes) with empty states; debug/release build types; CI. Domain core: units, quantities, parsing and es-ES formatting, display rounding, package fractions, money, cost engine (on in-memory models), scaling, name normalisation | App installs on the phone and navigates; all domain tests green |
| **2 — Recipes, packs and backup** | Recipe list, detail, editor, duplicate, delete, scaling, staples; recipe pack parser/validator/serializer; import via share sheet and picker, with validation and preview (no product matching yet); export `.md`/`.zip`; **backup/restore** (shipped as soon as real data exists) | AC2.*, AC3.1–3.4, AC7.* |
| **3 — Providers and catalogue** | `:provider:api`, rate limiter, Mercadona module, postal code → warehouse, catalogue sync, FTS search, product detail, price refresh with fallback, Manual provider, store-brand filter, settings; ingredient linking in the editor; import matching, link memory, "Aceptar todo" | AC1.*, AC3.5 |
| **4 — Costs and comparison** | Package fraction lines, cost card, whole packages, leftovers, comparison across supermarkets, "Más barato", price staleness, price snapshots | AC4.* |
| **5 — Pantry and cooking** | Pantry lots, opened packages, presence-only staples, "Lo he cocinado" with editable confirmation and undo, "¿Qué puedo cocinar?" | AC5.* |
| **6 — Shopping list** | Build from recipes minus pantry, grouping, totals, overrides, check-off, "Terminar compra" → pantry, share as text | AC6.* |
| **7 — Día and release** | Día feasibility check and provider (or a documented manual-price fallback); release signing; R8; friends' APK; polish and performance pass | Signed APK installed on a second phone |

Phases 4–6 depend on 3 for real prices, but their domain logic is already exercised from
phase 1 with in-memory products.

---

## 10. Risks and open questions

| # | Item | Status / mitigation |
|---|---|---|
| R1 | **Día refused every request** from the development network (Akamai "Access Denied", 403), including its homepage in a normal browser. The network egress geolocated to Croatia, so this may be a geo-block, a bot-score block, or both | Phase 7 runs one plain request from the phone's own connection. If it is refused, Día works through Manual products. See [providers/dia.md](providers/dia.md) |
| R2 | Internal endpoints can change without notice | Fixtures plus mapper tests catch it quickly. Cache fallback keeps the app usable. Provider docs record the verification date |
| R3 | Supermarket terms of use may not allow automated access | Personal, non-commercial, low-volume, user-initiated, cached, honest User-Agent, no evasion, never published |
| R4 | Mercadona prices and assortment depend on the warehouse (postal code) | Postal code setting; the warehouse is resolved through the site's own endpoint |
| R5 | Matching quality for vague names ("queso") | Match hints in packs, link memory, store-brand bonus, and fast manual correction |
| R6 | Losing the debug or release keystore makes updates impossible without uninstalling (data loss) | Keystore backups documented in DEVICE_SETUP.md. Install failures with a signature mismatch must stop and be reported |
| Q1 | Which postal code should be the default for Mercadona? | Asked on first sync |
| Q2 | Should cook history and price history get their own screens? | Out of scope for v1; data is stored |
