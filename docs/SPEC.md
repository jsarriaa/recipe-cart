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
| Supermercado | `Provider` | A product source: Mercadona, Día, Carrefour, Aldi, bonÀrea, Ahorramás, Consum, Eroski, Caprabo, or Manual |
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
:provider:carrefour     Same for Carrefour (1.5.0; its public search index only).
:provider:aldi          Same for Aldi (1.5.0; the data its category pages embed).
:provider:bonarea       Same for bonÀrea (1.5.0; its shop's search).
:provider:ahorramas     Same for Ahorramás (1.5.0; HTML grid parsed with jsoup).
:provider:consum        Same for Consum (1.5.0; its shop API).
:provider:tapestry      Eroski and Caprabo (1.5.0; their shared Tapestry storefront, jsoup).
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
| Language | Kotlin 2.4 (K2), JVM target 17 | AGP 9 built-in Kotlin support |
| UI | Jetpack Compose, Material 3, Navigation Compose with type-safe routes | Own "cocina mediterránea" theme (tomato, olive, saffron on cream; warm dark mode), serif headings, rounded shapes. Dynamic colour is off so the app looks like itself everywhere |
| DI | Hilt (KSP) | `hilt-core` in JVM provider modules |
| DB | Room (KSP); product search with `LIKE` over a normalised key | Schemas exported to `core/data/schemas/` |
| Settings | DataStore Preferences | |
| Network | Retrofit + OkHttp + kotlinx.serialization converter | One shared `OkHttpClient` with per-provider interceptors |
| YAML | kaml (node API only, no serializers) | Only for recipe front matter. Pure Kotlin, so it runs on Android 8 |
| Markdown rendering | A small Compose renderer for the subset in RECIPE_FORMAT.md, or a maintained M3 Markdown library (decided in phase 2) | |
| Images | Coil 3 | Disk cache for product thumbnails |
| Tests | JUnit Jupiter 6 + kotlin.test (JVM modules), JUnit 4 + Robolectric (Room/Android), Turbine (Flow), MockWebServer (providers) | |
| Lint/format | Android Lint, ktlint via Spotless | |
| CI | GitHub Actions: `test` + `lint` on every PR | |

Exact versions are pinned in `gradle/libs.versions.toml`. Phase 1 (2026-11-18) started on the
latest stable releases: Gradle 9.9.1, AGP 9.5.1, Kotlin 2.4.21, KSP 2.3.14, Hilt 2.60.1, Compose
BOM 2026.09.00, Room 2.8.5, Navigation 2.10.2. Current AndroidX requires **compileSdk 37**;
targetSdk stays at 36, the test phone's API level.

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
  - **Día**: verified from the phone in phase 7 ([providers/dia.md](providers/dia.md)). Its
    catalogue sync is search-based (a fixed list of food searches), and prices ignore Club Dia.
  - **Carrefour** (1.5.0): its public search index only, national online prices,
    search-based sync ([providers/carrefour.md](providers/carrefour.md)). Ported from
    supermercapy (MIT). New supermarkets start switched off.
  - **Aldi** (1.5.0): shelf prices from its category pages (it has no online shop),
    península region ([providers/aldi.md](providers/aldi.md)). Ported from supermercapy.
  - **bonÀrea** (1.5.0): national catalogue and prices, search-based sync
    ([providers/bonarea.md](providers/bonarea.md)). Ported from supermercapy.
  - **Ahorramás** (1.5.0): its HTML product grid, one price list, search-based sync
    ([providers/ahorramas.md](providers/ahorramas.md)). Ported from supermercapy.
  - **Consum** (1.5.0): its shop API, national prices, full walk of the food categories
    ([providers/consum.md](providers/consum.md)). Ported from supermercapy.
  - **Eroski** and **Caprabo** (1.5.0): one shared module for their Tapestry storefront,
    search-based sync, prices of the shop an anonymous visitor is pinned to (Basque and Catalan)
    ([providers/eroski.md](providers/eroski.md), [providers/caprabo.md](providers/caprabo.md)).
    Ported from supermercapy.
  - **Manual**: built in, always available. The user types a product (name, brand, store-brand
    flag, package size, price, shop name). This covers shops without an API and providers that
    are down or refuse us. Manual products are stored in the catalogue table with provider id
    `manual` (the shop name as their category), are always searchable whatever supermarkets are
    active, and have no price refresh. Since phase 3b they are added from "Vincular producto"
    ("Añadir un producto a mano").
- **Catalogue sync** ("Actualizar catálogo") is on demand and per provider. It downloads the
  provider's food categories at the provider's rate limit, with a progress bar, and can be
  cancelled. Products are upserted into Room. Products missing from a full sync are marked
  `unavailable`, not deleted, so existing links do not break.
- **Search** runs locally over the cached catalogue: every word of the query must appear in
  the product's `search_key` (name, brand and last category, normalised like § 5.4, so it is
  accent-insensitive). Shorter names that start with the query rank first. It is instant and
  works offline. In Catálogo the search covers the supermarket chosen with its chip; elsewhere
  (linking, pantry and list pickers) it covers every active supermarket plus manual products,
  and each row says which supermarket it belongs to. FTS4 was dropped in phase 3a: its tokenizer cannot fold Spanish accents
  without a custom ICU build, and a few thousand rows scan fast enough with `LIKE`.
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
  or recently added (sorting by cost was removed at the owner's request). Each row shows its
  cost per serving when known ("2,40 €/ración", "≥" when some ingredient has no price).
- **Create / edit**: title, description, servings, prep and cook minutes, tags, photo (§ 4.9),
  source, steps (Markdown editor with preview), and ingredients.
- **Everything is editable**: no recipe is read-only. Imported recipes can be changed exactly
  like the ones created in the app: every field, ingredient, quantity, photo and step. Each
  recipe records its origin (`created` or `imported`, with the import date) and whether it was
  edited after import, so a later re-import of the same pack never silently overwrites local
  changes (§ 4.3, step 4).
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
- **Linking**: tapping an ingredient in the recipe detail opens "Vincular producto": the
  current link (with "Quitar vínculo"), suggestions from link memory and the cached catalogue
  (§ 5.4, in the supermarket the cost card is viewing), a search box, and "Añadir un producto a
  mano". With more than one active supermarket, chips at the top switch the sheet to another one
  (keeping the typed search), so every supermarket can be linked without leaving the recipe or
  changing the cost card's mode (owner's request, 1.6.0). Picking a product
  stores it as the preferred link of its provider, first in the list, so it is the product the
  recipe shows; links to other providers stay. It updates the **link memory** for the
  ingredient's normalised name (§ 5.4). A link change counts as an edit (re-import protection,
  § 4.3 step 4). The editor keeps links as they are.
- **Pantry staples**: shown in their own collapsed section ("Básicos de despensa"). By default
  they are left out of the dish cost (setting "Incluir básicos en el coste") and out of the
  shopping list unless the pantry knows they are missing (§ 4.6).

#### AC

- AC2.1 Scaling 2 → 3 servings multiplies by 1.5. Display rounds to friendly values
  (§ 5.3) while computations keep full precision.
- AC2.2 Duplicating and then editing the copy never changes the original.
- AC2.3 Every recipe field survives export → import unchanged (§ 4.3).
- AC2.4 An imported recipe can be edited and saved like any other, and is then marked as edited
  after import.
- AC2.5 A recipe photo can be added from the gallery or the camera, replaced and removed. Deleting
  a recipe deletes its photo file.

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
   "Reemplazar", "Guardar como copia" or "Omitir" (per recipe, with "aplicar a todas"). If the
   existing recipe was **edited after import**, the default is "Omitir" (shown as "Mantener la
   mía"), and "Reemplazar" asks for confirmation: "Perderás los cambios que hiciste en la app".
5. **Product matching** (§ 5.4) for every ingredient of the selected recipes, against the local
   catalogue of the primary supermarket (one provider until Día lands in phase 7). The screen
   ("Productos") lists ingredients with the suggested product, its picture and price. Each can be changed ("Ver más"), removed, or left unlinked.
   **"Aceptar todo"** confirms every pre-selected suggestion in one tap. Links included in the
   file (`links:`) and remembered links are pre-selected first.
6. **Save** in one Room transaction. Images are copied to app-private storage and downscaled to
   at most 1600 px on the long side. Link memory is updated with the confirmed links.
7. **Summary**: "3 recetas importadas, 1 omitida" with a link to each.

If no provider catalogue is synced yet, step 5 explains that there is nothing to suggest and
offers "Importar sin vincular"; products can be linked later from each recipe. "Importar sin
vincular" is also available when suggestions exist; links that came in the file are kept.

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
- AC3.6 Re-importing a pack never replaces a recipe that was edited after import unless the user
  explicitly confirms it.

### 4.4 Quantities and cost

For a recipe at the chosen servings and supermarket mode, each ingredient line shows:

- The selected product's photo (§ 4.9), or a neutral placeholder when the ingredient is not linked.
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
| **Comparativa** | The same figures per active supermarket, with coverage ("5 de 6 ingredientes con precio"), plus a **"Mejor combinación"** that picks, for each ingredient, the provider with the lowest whole-package cost |

Supermarket mode is a selector on the recipe: "Principal" (default), one specific provider, or
"Más barato". It is not saved. It lists every active supermarket (plus "A mano" when manual
products are linked) and is shown, with the comparison, when there is more than one. In a
specific supermarket's mode each ingredient shows that supermarket's product, or none (then
"Vincular producto" suggests and searches products of that supermarket); only "Principal" falls
back to a product of another supermarket. Leftovers are not shown (owner's request): the card is
about what the recipe uses.

The card also shows "Precios del ‹fecha del precio más antiguo›" (with a warning icon when a price
is older than the staleness setting) and **"Actualizar precios"**, which refreshes only the
recipe's linked products at the rate limit, skips manual ones, and stops at the first error that
would make the rest fail too (offline, blocked, rate limited). Cached prices always stay.
"Incluir básicos" and "Incluir opcionales" are chips on the card; they change the global
settings of the same name.

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
  remaining quantity, package size, `opened` flag, `addedAt`, optional location ("nevera",
  "congelador", "armario"). There are **no dates to enter** (owner decision after 1.0.0): no
  best-before, no shelf life, no "usar pronto" warnings.
- Staples can be tracked **presence-only** ("tengo sal" / "se ha acabado") with no quantity.
- Adding: from the catalogue (a full sealed package, measured like the cost engine measures it:
  pieces first, so "Maxi tortillas" is 6 ud), manually with a generic name, and from the shopping
  list ("Terminar compra", § 4.6). Lots are edited in a sheet: quantity left (g, ml or ud),
  "Abierto", and location. Each row also has an **Abierto** chip to toggle it in one tap.
- Each lot shows its product's photo (§ 4.9) when it is linked to a product.
- The list is grouped: Abiertos, Sin abrir, Básicos, with search and a location filter.
- **"Lo he cocinado"** on a recipe (at the current servings):
  1. The domain plans deductions: for each ingredient, matching lots (§ 5.5) are consumed
     **opened lots first, then FIFO by `addedAt`**. A sealed lot that is partly used becomes opened.
  2. A confirmation sheet lists every deduction, editable: amount, which lot, or skip. Missing
     amounts are shown ("No tenías suficiente: faltan 50 g").
  3. Confirm applies it in one transaction and records a `CookEvent`. A snackbar offers
     "Deshacer", which restores the lots exactly.
  4. Lots that reach zero are removed. Presence-only staples are not changed; the sheet offers
     "Se ha acabado" for each.
- **"¿Qué puedo cocinar?"**: every recipe, at its default servings, gets a coverage status
  ignoring staples and optional ingredients: **Lo tienes todo**, **Te falta 1–2**, **Te faltan
  más**. Within each status they are sorted by the share of the dish cost already in the
  pantry. Each card
  lists the missing items and has "Añadir lo que falta a la lista" (it adds the recipe; the list
  subtracts the pantry, so only what is missing appears). The screen opens from the Recetas top
  bar.
- **"Te falta comprar"** (cost card, § 4.4) is shown once the pantry has anything: the whole
  packages still to buy after "Lo he cocinado" planning subtracts the pantry.

#### AC

- AC5.1 Deduction planning is pure domain code, tested for: opened-first order, partial lots, insufficient stock, unit conversion (cda of oil from a 1 l bottle),
  count units, and undo restoring the exact previous state.
- AC5.2 Nothing is deducted without the confirmation sheet.
- AC5.3 The `best_before`, `use_soon_manual`, `use_soon_dismissed_at` columns and the
  `shelf_life_memory` table from schema v5 stay in the
  database (no migration) but the app no longer reads or writes them.

### 4.6 Shopping list ("Lista de la compra")

#### Behaviour

- One active list. Sources: "Añadir a la lista" from a recipe (with servings, can be repeated),
  "Añadir lo que falta" from "¿Qué puedo cocinar?", and manual items (free text, or a catalogue
  product).
- **Build**: for each recipe contribution, ingredient needs are converted to the selected
  product's unit, **consolidated per product** (or per normalised generic name when unlinked),
  then the pantry stock is subtracted, then the rest is rounded up to whole packages.
  Staples are added only when the setting "Añadir básicos que me faltan" is on, the pantry is in
  use (it has at least one lot), and no lot of that staple is left (presence-only staples are
  removed with "Se ha acabado"). Optional ingredients are left out.
- **Supermarket assignment**: the list's mode, "Principal" or "Más barato por producto" (menu of
  the Compra tab, saved), or a supermarket chosen by hand for an item ("Comprar en", saved per
  ingredient name).
- **Grouped by supermarket**, with a subtotal per group and an **estimated total**
  ("≈ 23,40 €" when any price is approximate or stale; items without a price are counted and
  shown as "sin precio").
- Each item shows packages to buy, the amount needed, and which recipes need it.
- Items can be checked off. **"Terminar compra"** moves checked items into the pantry as sealed
  lots (one per package bought; generic items as one lot with the amount needed, or as "tengo"
  when there is no amount; quantities are edited afterwards in the Despensa) and removes them
  from the list. Unchecked items stay. Recipes whose needs are all covered leave the list.
- **Always up to date** (replaces "Recalcular"): only the list's inputs are stored (recipes added,
  manual items, ticks, overrides); the list is rebuilt whenever recipes, manual items or the
  pantry change, so it cannot go stale. "Vaciar lista" removes recipes, manual items and ticks.
- Manual items: free text or a catalogue product ("Añadir" button).
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
  - `database/cocina.db`: a transactionally consistent snapshot of the Room database, made
    with `VACUUM INTO` (Android 11+), or `PRAGMA wal_checkpoint(TRUNCATE)` plus a file copy on
    older versions.
  - `datastore/settings.preferences_pb`.
  - `images/…`: the recipe photos that some recipe references (orphans are left out).
  The product catalogue is included, so cached prices survive a restore.
- **"Restaurar copia"** reads the file and validates it before touching anything: manifest,
  checksums, only the allowed entries (`database/`, `datastore/`, `images/<file>`), schema version
  not newer than the app's, `PRAGMA integrity_check`, and a `user_version` that matches the
  manifest. Then it shows a preview ("Copia del 18/11/2026 10:20 con 42 recetas y 30 fotos"). On
  confirmation it first writes an automatic backup of the current data to app-private storage
  (`files/auto-backups/`, the last 3 are kept), then swaps in the files and restarts the app
  process. Room migrations upgrade older backups on open.
- **"Deshacer la última restauración"** restores the newest automatic backup through the same
  validation and confirmation.
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
| Actualizaciones | "Buscar actualizaciones": asks `api.github.com` for the latest release of `jsarriaa/recipe-cart`. If its version is newer and it has an `.apk` asset, "Descargar e instalar" downloads it to the cache and opens Android's installer (the user confirms there; the first time Android asks to allow installs from this app). From 1.2.0 the app also checks once each time it opens; if a newer release has an APK, a pop-up "¡Hay una versión nueva!" offers "Actualizar" or "Ahora no" (it asks again on the next open). A failed check shows nothing. The APK is downloaded only on "Actualizar". The debug build only checks and links to the release page, because the release APK is a different app |
| Tu nombre en el recetario | Not set. The name shown as "por …" on published recipes; asked the first time you publish |
| GitHub | A card "Hecha con cariño por @jsarriaa" with buttons to the owner's GitHub profile and the app's repository (opened in the browser; the app fetches nothing) |
| Acerca de | Version, open-source licences, legal note (not affiliated with any supermarket) |

### 4.9 Pictures

#### Recipe photos (phase 2)

- One photo per recipe, added in the editor from the **gallery** (Android photo picker, no
  storage permission) or the **camera** (the system camera app through `TakePicture`, no camera
  permission). It can be replaced or removed.
- Shown as a thumbnail in the recipe list and as the header of the recipe detail. Recipes
  without a photo get a neutral placeholder.
- Stored in app-private storage (`files/images/<uuid>.jpg`), rotated per EXIF, downscaled to at
  most 1600 px on the long side, JPEG quality 85. Replaced and deleted photos are removed from
  disk. Photos travel in recipe pack exports (`images/`) and in backups.

#### Ingredient pictures (phase 3 onwards)

- Ingredient pictures come **only from the supermarket**: the image of the product an
  ingredient is linked to (the selected product for the current supermarket mode, § 4.4). There
  are no user photos for ingredients.
- Shown next to ingredients in the recipe detail and editor, in the import matching screen
  (product suggestions), in the catalogue, and from their phases on in the pantry and the
  shopping list. Unlinked ingredients show a neutral placeholder icon.
- Loaded with Coil from the provider's image CDN (Mercadona: `prod-mercadona.imgix.net` with
  small `w`/`h` parameters), only when a screen needs them, and cached on disk so they keep
  working offline. Manual products have no picture unless phase 7 adds one.

---

### 4.10 Community recipe book ("Recetas de la comunidad", 1.3.0)

Full design: [COMMUNITY.md](COMMUNITY.md).

- A public GitHub repository, [`jsarriaa/recetario`](https://github.com/jsarriaa/recetario),
  holds one `cpl-recipe/1` file per recipe under `recipes/`. A GitHub Action rebuilds
  `index.json` (title, author, servings, total time, tags, ingredient names) on every change, so
  the owner can edit or delete any recipe on GitHub and the index follows.
- **Recetas → ⋮ → Recetas de la comunidad** downloads `index.json` when the screen opens (no
  cache, no background work) and filters it on the phone: text over title, author, description,
  tags and ingredients (every word must match, ignoring case and accents), time chips (≤ 15,
  ≤ 30, ≤ 60 min) and tag chips (all chosen tags must be present).
- Each card shows the title, "por <author>", servings, time, tags and description.
  **Descargar** downloads the file and opens the normal import (§ 4.3): preview, duplicates and
  product matching.
- Only paths `recipes/….md` from the index are downloaded, at most 200 000 characters.
- **Publicar en la comunidad** (1.4.0), in a recipe's ⋮ menu:
  1. The first time, the app asks for the name shown as "por …" (1–60 characters, kept in
     settings, changeable in Ajustes → "Tu nombre en el recetario").
  2. A confirmation says the recipe becomes public (CC BY 4.0) and is published without its
     photo and without product links; "Cambiar nombre" is offered there too.
  3. The app POSTs the `cpl-recipe/1` text, with `author` set, to the owner's Cloudflare Worker
     (`community/worker/`), which commits it to `recipes/`. The recipe then keeps that author.
  4. A recipe signed by someone else (downloaded from the book) cannot be published: "Esta
     receta es de Marta".
  5. Results: published ("aparecerá en unos minutos"), rate limited (3 per minute per IP),
     rejected, failed, or not available (no Worker address in this build).
  The owner removes anything unwanted on GitHub.

#### AC

- AC10.1 Search and filters are pure domain code (`CommunitySearch`), tested.
- AC10.2 A failed index download shows "Reintentar"; a failed recipe download shows a snackbar.
- AC10.3 The published text is built by domain code (`CommunityPublication`), tested: author
  set, no links, no photo, parses back; someone else's recipe is refused; names are cleaned.

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
| g, ml (and cl, which is shown in ml) | < 10: one decimal if not whole, at least 0,1; 10–100: whole; > 100: nearest 5 |
| kg, l | Shown when the rounded value is ≥ 1000 g / ml, with up to 2 decimals ("1,25 kg"). Smaller kg/l amounts are shown in g/ml ("0,25 kg" → "250 g") |
| ud and other count units | Nearest 1/4 ("1 1/2 ud"), never below 1/4 |
| cdta, cda, taza | Nearest 1/2; 1/4 when it would round to 0 |
| pizca | Whole number, at least 1. `al_gusto` shows no number |

**Package fraction** for used amount `u` against usable package quantity `p` (same base unit),
`r = u / p`:

1. `whole = ⌊r + 0.02⌋`, `rest = r − whole` (negative rests are clamped to 0).
2. Snap `rest` to the nearest of {0, 1/4, 1/3, 1/2, 2/3, 3/4, 1} (0 is not a candidate when
   `whole` is 0, so less than one package never shows as "0"). If the distance is ≤ 0.02 the
   fraction is exact; otherwise it is prefixed with "≈".
3. If `r < 1/8`: show a percentage instead, always with the neutral noun "envase" to avoid
   gender agreement: "≈ 5 % del envase".
4. The noun is the product's packaging word lowercased (paquete, bandeja, bote, sobre, pieza,
   tarrina, botella, lata, malla), defaulting to "envase". It is pluralised when `r > 1`
   (vowel → +s, z → ces, otherwise +es).
5. Template: `{fraction} {noun} de {ingredient name}, {used} de {package}`. In the recipe
   detail the ingredient name is already on the line, so the app shows
   `{fraction} {noun}, {used} de {package} · {cost}` under it.
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
   3. Catalogue search with `match.query` (falling back to the name), top 30.
3. **Score** for search candidates (0–100):
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

Schema v1 (phase 1) contains `recipe`, `recipe_tag` and `ingredient` without the three
`recipe` columns marked *(v2)*. Schema v2 (phase 2) adds them with a tested migration. The other
tables arrive with later schema versions and tested migrations. Schema v3 (phase 3a) adds
`product` and `price_snapshot`; schema v4 (phase 3b) adds `ingredient_link` (cascades with its
ingredient, no foreign key to `product` so a link survives an uncached product) and
`link_memory`; schema v5 (phase 5) adds `pantry_lot`, `shelf_life_memory`, `cook_event`
(no foreign key to the recipe: history keeps a title snapshot) and `cook_deduction` (a JSON
snapshot of each touched lot, so "Deshacer" restores it exactly); schema v6 (phase 6) adds the
shopping list inputs; schema v7 (1.4.0) adds `recipe.author`. All ids are `TEXT`
UUIDs except provider product keys.

| Table | Key columns |
|---|---|
| `recipe` | `id`, `uid`, `title`, `description`, `servings`, `prep_minutes`, `cook_minutes`, `image_path`, `source`, `body_md`, `created_at`, `updated_at`, `origin` *(v2)*, `imported_at` *(v2)*, `edited_after_import` *(v2)*, `author` *(v7)* |
| `recipe_tag` | `recipe_id`, `tag` |
| `ingredient` | `id`, `recipe_id`, `position`, `group_label`, `name`, `name_key`, `quantity_milli`, `unit`, `note`, `optional`, `pantry_staple`, `unit_weight_g_milli`, `match_query`, `match_package`, `match_store_brand_ok` |
| `ingredient_link` *(v4)* | `ingredient_id`, `provider_id`, `product_id` (PK triple), `preferred`, `position` |
| `link_memory` *(v4)* | `name_key`, `provider_id`, `product_id` (PK triple), `last_used_at`, `use_count` |
| `product` *(v3)* | `provider_id`, `product_id` (PK pair), `name`, `search_key`, `brand`, `brand_kind`, `packaging`, `net_milli`, `base_unit`, `unit_count`, `unit_count_unit`, `drained_milli`, `approx`, `price_cents`, `reference_price_cents`, `reference_format`, `image_url`, `web_url`, `category_path`, `available`, `fetched_at` |
| `price_snapshot` *(v3)* | `provider_id`, `product_id`, `fetched_at` (PK triple), `price_cents`; one row per new or changed price |
| `pantry_lot` *(v5)* | `id`, `provider_id?`, `product_id?`, `name`, `name_key`, `quantity_milli?`, `unit?`, `package_quantity_milli?`, `opened`, `opened_at?`, `added_at`, `best_before?` (epoch day), `location?`, `presence_only`, `use_soon_manual`, `use_soon_dismissed_at?`, `opened_shelf_life_days?` |
| `shelf_life_memory` *(v5)* | `key` (provider product key or name key), `opened_shelf_life_days` |
| `shopping_recipe` *(v6)* | `id`, `recipe_id` (no foreign key: saving a recipe re-inserts its row; deleting a recipe removes these rows in the same transaction), `servings`, `added_at` |
| `shopping_manual` *(v6)* | `id`, `name`, `provider_id?`, `product_id?`, `packages`, `added_at` |
| `shopping_check` *(v6)* | `item_key` (stable key of a list item: product, name or manual id) |
| `shopping_override` *(v6)* | `name_key`, `provider_id` |
| `cook_event` *(v5)* | `id`, `recipe_id?`, `recipe_title`, `servings`, `cooked_at`, `undone` |
| `cook_deduction` *(v5)* | `event_id`, `lot_id` (PK pair), `lot_snapshot_json`, `deducted_milli` |

Manual products are `product` rows with `provider_id = 'manual'`.

---

## 7. Non-functional requirements

- **Platform**: minSdk 26 (Android 8.0), targetSdk 36, compileSdk 37. Primary test device:
  OnePlus CPH2645, Android 16 ([DEVICE_SETUP.md](DEVICE_SETUP.md)).
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
| `:core:data` | Robolectric + Room in-memory, `MigrationTestHelper` | DAOs, catalogue search, transactions, migrations N−1 → N, backup/restore |
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
| **0 — Specification** (committed; push waits for the GitHub CLI) | Repository, CLAUDE.md, this spec, recipe format, generator prompt, sample pack, provider research, device setup | Committed and pushed |
| **1 — Foundations** (in review, branch `phase/1-foundations`) | Gradle multi-module skeleton with version catalog and convention plugins; Hilt; Room v1 (recipe tables) with schema export and migration test harness; DataStore settings; M3 theme; navigation shell (Recetas, Despensa, Compra, Catálogo, Ajustes) with empty states; debug/release build types; CI. Domain core: units, quantities, parsing and es-ES formatting, display rounding, package fractions, money, cost engine (on in-memory models), scaling, name normalisation | App installs on the phone and navigates; all domain tests green |
| **2 — Recipes, packs and backup** (in review, branch `phase/2-recipes`) | Recipe list, detail, editor, duplicate, delete, scaling, staples; **recipe photos** from gallery or camera; every recipe editable, with origin tracking (schema v2 + migration); recipe pack parser/validator/serializer; import via share sheet and picker, with validation, preview and edit-safe duplicate handling (no product matching yet); export `.md`/`.zip`; **backup/restore** (shipped as soon as real data exists) | AC2.*, AC3.1–3.4, AC3.6, AC7.* |
| **3 — Providers and catalogue** (two PRs: **3a** provider layer, catalogue and search; **3b** linking, import matching and product photos) | 3a: `:provider:api`, rate limiter, Mercadona module, user-chosen postal code → warehouse, catalogue sync (schema v3), search, product detail, price refresh with fallback, store-brand filter, supermarket settings. 3b (schema v4): Manual provider; ingredient linking from the recipe detail; **product photos** next to ingredients (§ 4.9); import matching, link memory, "Aceptar todo" | AC1.*, AC3.5 |
| **4 — Costs and comparison** | Package fraction lines, cost card, whole packages, leftovers, comparison across supermarkets, "Más barato", price staleness, "Actualizar precios" for a recipe, sort by cost per serving (price snapshots are stored since 3a; showing them stays optional). No schema change | AC4.* |
| **5 — Pantry and cooking** (schema v5) | Pantry lots, opened packages, presence-only staples, "Usar pronto" (removed in 1.1.0), "Lo he cocinado" with editable confirmation and undo, "¿Qué puedo cocinar?" prioritising food to use up, "Te falta comprar", sort by "Cocinadas hace poco" | AC5.* |
| **6 — Shopping list** (schema v6) | Build from recipes minus pantry, grouping, totals, overrides, check-off, "Terminar compra" → pantry, share as text, "Añadir a la lista" and "Añadir lo que falta" | AC6.* |
| **7 — Día and release** | Día feasibility check (passed from the phone) and `:provider:dia`; supermarket switches and "principal" in Ajustes; supermarket chips in Catálogo and a supermarket label on product rows; release signing; R8 with a `minified` build type for testing; version 1.0.0; install guide in README | Signed APK installed on a second phone (by the owner) |

**After 1.0.0** (owner feedback, no phase): 1.0.x removed "Sobras" and the cost sort, fixed the
supermarket switch and added the "cocina mediterránea" theme; 1.1.0 removed every pantry date
(only "Abierto" is left), and added "Buscar actualizaciones" and the GitHub card in Ajustes. The
repository is public from 1.1.0 and releases carry the signed APK. 1.2.0 checks for updates when
the app opens and offers them in a pop-up. 1.3.0 adds the community recipe book (§ 4.10);
1.4.0 adds publishing to it (schema v7).

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
