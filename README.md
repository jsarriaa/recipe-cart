# CocinaParaLechugones

A private, non-commercial Android app for cooking at home with real supermarket products. It
links recipes to Mercadona and Día products, shows how much of each package a dish uses and what
it costs, keeps a pantry, and builds a shopping list grouped by supermarket.

It is a sideloaded APK for the owner and a few friends. It will never be published on the Play
Store. The app UI is in Spanish (es-ES); code and docs are in English.

> **Status: 1.4.0.** Every feature below is in the app. Mercadona and Día prices are
> live (Día was verified from the phone; see [docs/providers/dia.md](docs/providers/dia.md)), and
> any other shop's products can be added by hand as manual products. See the
> [development phases](docs/SPEC.md#9-development-phases).

## Features

- **Supermarket catalogue**: one pluggable module per supermarket, starting with Mercadona and
  Día, plus manual products. Cached locally, rate-limited, refreshed only on demand, and falling
  back to cached prices when a shop fails. Optional "store brand only" filter.
- **Recipes**: create, edit, duplicate, delete, scale servings, with a photo from the gallery or
  the camera. Every recipe stays editable, imported ones included. Each ingredient has a
  quantity, a unit, a generic name and links to products, whose supermarket photos are shown
  next to it. Pantry staples are flagged.
- **Recipe packs**: import and export Markdown files with YAML front matter, or `.zip` packs with
  images. On import the app suggests products to link, and "Aceptar todo" confirms them in one
  tap. Export round-trips. Recipes can be generated in an external Claude chat with
  [the generator prompt](prompts/recipe-pack-generator.md).
- **Quantities and cost**: "1/2 paquete de ñoquis, 250 g de 500 g", dish cost, cost of whole
  packages, and a comparison across supermarkets.
- **Pantry**: what is at home. A package can be marked "Abierto", and opened packages are used
  first. No dates to keep. "¿Qué puedo cocinar?" checks recipes against it, and "Lo he cocinado"
  deducts what a recipe used, after an editable confirmation.
- **Shopping list**: built from recipes minus the pantry, grouped by supermarket, with an
  estimated total. Bought items move into the pantry. Shared as text.
- **Backup and restore** to a single file.
- **Recetas de la comunidad**: search the shared
  [recipe book](https://github.com/jsarriaa/recetario) by name, ingredient, author, time and tags,
  and download a recipe straight into the app. "Publicar en la comunidad" shares one of yours
  as "por <your name>" (through a small [Cloudflare Worker](community/worker/README.md)).
- **Updates**: when the app opens it checks for a newer
  [GitHub release](https://github.com/jsarriaa/recipe-cart/releases) and, if there is one, offers
  it in a pop-up. "Actualizar" downloads the APK and opens Android's installer. Ajustes has the
  same check under "Buscar actualizaciones".

No accounts, analytics, ads or AI features. All data stays on the phone.

## Documentation

| Document | Contents |
|---|---|
| [docs/SPEC.md](docs/SPEC.md) | Full specification: architecture, features with acceptance criteria, domain rules, data model, phases, risks |
| [docs/RECIPE_FORMAT.md](docs/RECIPE_FORMAT.md) | Exact recipe pack format `cpl-recipe/1`: fields, units, validation, canonical export |
| [docs/providers/](docs/providers/README.md) | Provider contract, network etiquette, and the research notes for [Mercadona](docs/providers/mercadona.md) and [Día](docs/providers/dia.md) |
| [docs/DEVICE_SETUP.md](docs/DEVICE_SETUP.md) | Phone and PC setup, adb over USB and Wi-Fi, logs, screenshots, data-safety rules |
| [prompts/recipe-pack-generator.md](prompts/recipe-pack-generator.md) | Prompt to paste into an external Claude chat to get importable recipes |
| [examples/recipe-packs/](examples/recipe-packs/) | Sample recipe pack (also used as a test fixture) |
| [docs/COMMUNITY.md](docs/COMMUNITY.md) | Community recipe book: storage, index, searching and publishing |
| [Editing the recipe book](#editing-the-community-recipe-book-on-github) | How to change, delete, add and restore recipes on GitHub |
| [community/worker/](community/worker/README.md) | The publishing Worker and its Cloudflare setup |
| [CLAUDE.md](CLAUDE.md) | Rules and workflow for development with Claude Code |

## Tech stack

Kotlin · Jetpack Compose + Material 3 · MVVM · Coroutines/Flow · Room · DataStore ·
Retrofit/OkHttp + kotlinx.serialization · Hilt (KSP) · Coil · kaml · Gradle Kotlin DSL with a
version catalog · minSdk 26, target SDK 36.

## Development setup (Windows)

- **JDK 21** for Gradle: the Adoptium JDK (`C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot`).
  Set `JAVA_HOME` to it, because the `java` on PATH is JDK 11.
- **Avast HTTPS scanning**: set `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` so
  Java trusts Avast's certificates when Gradle downloads dependencies.
- **Android SDK** in `%LOCALAPPDATA%\Android\Sdk`, referenced from a gitignored `local.properties`.
- **adb** from the SDK's `platform-tools` (not the old `C:\Android\adb.exe`).
- **GitHub CLI** (`gh`) for pull requests.

Details and the phone setup: [docs/DEVICE_SETUP.md](docs/DEVICE_SETUP.md).

```bash
./gradlew spotlessApply              # format Kotlin (ktlint)
./gradlew spotlessCheck test lint    # what CI runs on every PR
./gradlew :app:installDebug          # install the debug build on the connected phone
```

### Modules

| Module | Contents |
|---|---|
| `:app` | Compose UI, navigation, ViewModels, Hilt entry points |
| `:core:domain` | Pure Kotlin business logic: units, quantities, package fractions, money, cost engine, settings model |
| `:core:data` | Room database (schemas in `core/data/schemas/`), repositories, DataStore settings |
| `:provider:api` | Supermarket provider contract, shared rate limiter and request handling |
| `:provider:mercadona` | Mercadona provider (JSON endpoints, mapper, fixtures) |
| `:provider:dia` | Día provider (search-based sync, session cookie in memory, mapper, fixtures) |
| `build-logic/` | Gradle convention plugins |

## Installing the app (owner and friends)

The app is a sideloaded APK; it is never published on the Play Store.

1. **Build** the release APK: `./gradlew :app:assembleRelease`. The file is
   `app/build/outputs/apk/release/app-release.apk` (about 3 MB, shrunk with R8).
2. **Signing**: release builds are signed with the personal keystore in
   `%USERPROFILE%\.cocina-keys\` (`cocina-release.jks` and `keystore.properties`; copy the
   properties file to the repo root to sign). **Back up that folder** (password manager
   attachment or an encrypted drive): an app signed with a lost key can only be replaced by
   uninstalling it, which deletes its data. Without `keystore.properties` the release APK is
   produced unsigned.
3. **Install on a phone**: send the APK (cable, cloud drive, messaging app), open it on the phone
   and allow "Instalar aplicaciones desconocidas" for the app that opens it. Updates install over
   the previous version and keep the data, as long as they are signed with the same key.
4. **Before installing a version with a database change** (the release notes say so), make a
   backup in the app: Ajustes → Copia de seguridad → Crear copia.
5. **First use**: Catálogo → "Descargar catálogo" (choose a postal code; it is only sent to
   Mercadona), then import or write recipes.

For testing the shrunk build on the development phone without touching the installed release
app, `./gradlew :app:installMinified` installs the same R8 build as the debug app.

## Editing the community recipe book on GitHub

The recipes people see in "Recetas de la comunidad" are plain text files in
[github.com/jsarriaa/recetario](https://github.com/jsarriaa/recetario), in the `recipes/`
folder. Everything below works from a computer's browser, signed in as `jsarriaa`. On a phone,
use the browser (not the GitHub app) and turn on "desktop site" in its menu.

You never touch `index.json`: after every change, the **Build index** workflow rebuilds it by
itself in about a minute. Phones see the change on their next search, up to 5 minutes later
(GitHub's file cache).

### Change a recipe

1. Open [the `recipes/` folder](https://github.com/jsarriaa/recetario/tree/main/recipes) and
   click the recipe's file.
2. Click the **pencil icon** (✏️ "Edit this file") at the top right of the file.
3. Change the text. The parts you will usually touch:

   ```yaml
   ---
   format: cpl-recipe/1            # never change
   uid: recetario-tortilla         # never change (the app uses it to spot re-imports)
   title: Tortilla de patatas      # the name shown in the list
   description: Jugosa por dentro. # one or two sentences, optional
   author: Joan                    # shown as "por Joan"
   servings: 4
   prep_minutes: 15                # with cook_minutes, the "≤ 30 min" filters
   cook_minutes: 35
   tags: [española, cena, vegetariana]   # the filter chips: lowercase, comma separated
   ingredients:
     - name: patata
       quantity: 700
       unit: g
   ---

   ## Preparación

   1. Steps go here, below the second `---`, as normal text.
   ```

   Keep the spaces at the start of the ingredient lines exactly as they are: they are what
   tells an ingredient's fields apart. The full list of fields and units is in
   [docs/RECIPE_FORMAT.md](docs/RECIPE_FORMAT.md).
4. Click **Commit changes…** (green, top right). Leave "Commit directly to the `main` branch"
   selected, optionally write what you changed, and click **Commit changes**.

### Delete a recipe

1. Open the recipe's file in `recipes/`.
2. Click the **⋯** menu at the top right of the file and choose **Delete file**.
3. Click **Commit changes…** and then **Commit changes**.

It disappears from the app's search after the index rebuilds. People who already downloaded it
keep their copy: it lives in their own app.

### Add a recipe by hand

1. In [the `recipes/` folder](https://github.com/jsarriaa/recetario/tree/main/recipes), click
   **Add file → Create new file**.
2. Name it like `lentejas-con-chorizo.md` (lowercase, dashes, ending in `.md`).
3. Paste a recipe in the format above. The easiest source is a recipe exported from the app
   (recipe ⋮ → Exportar); delete its `links:` blocks and the `image:` line first. The
   [generator prompt](prompts/recipe-pack-generator.md) also produces valid recipes.
4. Click **Commit changes…** and then **Commit changes**.

### Check that it worked

- Open the repository's **Actions** tab. The latest **Build index** run should have a green tick.
- If a file has a mistake, the run still succeeds, but that recipe is **left out** of the app.
  Click the run, then the **index** job: a yellow warning says which file and why (for example
  "title, servings and ingredients are required"). Fix the file the same way and it comes back.
- [index.json](https://github.com/jsarriaa/recetario/blob/main/index.json) lists everything the
  app can see.

### Undo a change

1. Open the file and click **History** (top right).
2. Click the change you want to undo to see the old text in red, or click the **<>** icon
   ("View code at this point") on an older entry to see the whole file as it was then.
3. Copy the old text, edit the current file, paste it back, and commit.

A deleted file can be recovered the same way: open the repository's
[commit list](https://github.com/jsarriaa/recetario/commits/main), find the "Delete …" or
"chore: remove …" commit, open the file from the commit before it, copy its text, and add it
again with **Add file → Create new file**.

### Who can change what

Only you (`jsarriaa`) and the publishing Worker can write to the repository. The Worker only
adds new files, one per "Publicar"; editing and deleting are up to you. To stop all publishing,
delete the Worker's token on GitHub (Settings → Developer settings → Fine-grained tokens) or
disable the Worker in Cloudflare. Searching and downloading keep working.

## Contributing workflow

One phase per branch and pull request, Conventional Commits, unit tests for all domain logic,
and docs updated in the same PR. See [CLAUDE.md](CLAUDE.md#workflow).

## Legal

Personal, non-commercial project. Not affiliated with, endorsed by or connected to Mercadona,
Día or any other supermarket. Brand names belong to their owners. Product data is read in small
amounts from the same public endpoints the supermarkets' websites use, only when the user asks,
and is cached on the phone. See [docs/providers/README.md § Etiquette](docs/providers/README.md#etiquette).

## Credits and third-party code

No source code has been copied from other projects. Everything in this repository was written
for it, except the open-source libraries below, which are used as dependencies under their own
licences.

### Projects consulted during research

| Project | What it was used for |
|---|---|
| [serrano1996/dia-scraper](https://github.com/serrano1996/dia-scraper) | Source of the Día endpoint paths, the postal-code session behaviour and the response field names documented (unverified) in [docs/providers/dia.md](docs/providers/dia.md). Read only; no code taken |
| [vgvr0/dia-supermarket-scraper](https://github.com/vgvr0/dia-supermarket-scraper) | Read during the Día research. It scrapes HTML pages with a browser, so nothing from it is used |
| [Dia Spain Scraper on Apify](https://apify.com/studio-amba/dia-es-scraper) (studio-amba/dia-es-scraper) | Public description read during the Día research. Nothing used |

The Mercadona endpoints in [docs/providers/mercadona.md](docs/providers/mercadona.md) were
verified by calling the public JSON endpoints of Mercadona's online shop and reading its web
bundle (2026-11-18). No third-party code was involved.

### Libraries and tools

| Library / tool | Used for | Licence |
|---|---|---|
| [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Language, coroutines, serialisation | Apache-2.0 |
| [Android Jetpack / AndroidX](https://github.com/androidx/androidx): Compose, Material 3, Navigation, Lifecycle, Activity, Core, Room, DataStore, Hilt integration, Test | UI, database, settings, testing | Apache-2.0 |
| [Material Design icons](https://github.com/google/material-design-icons) (via `material-icons-extended`) | Icons | Apache-2.0 |
| [Dagger / Hilt](https://github.com/google/dagger) | Dependency injection | Apache-2.0 |
| [KSP](https://github.com/google/ksp) | Annotation processing for Room and Hilt | Apache-2.0 |
| [Gradle](https://github.com/gradle/gradle) and the Android Gradle Plugin | Build | Apache-2.0 |
| [Spotless](https://github.com/diffplug/spotless) | Formatting | Apache-2.0 |
| [ktlint](https://github.com/pinterest/ktlint) | Kotlin style rules | MIT |
| [JUnit](https://junit.org) (Jupiter and JUnit 4) | Unit tests | EPL-2.0 / EPL-1.0 |
| [Robolectric](https://github.com/robolectric/robolectric) | Android tests on the JVM | MIT |
| [Turbine](https://github.com/cashapp/turbine) | Testing Flows | Apache-2.0 |
| [kaml](https://github.com/charleskorn/kaml) (and its [snakeyaml-engine-kmp](https://github.com/krzema12/snakeyaml-engine-kmp)) | Reading the YAML front matter of recipe packs | Apache-2.0 |
| [Coil](https://github.com/coil-kt/coil) (with `coil-network-okhttp`) | Showing recipe photos and product images | Apache-2.0 |
| [Retrofit](https://github.com/square/retrofit) (with its kotlinx.serialization converter) | Supermarket JSON endpoints | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) (and MockWebServer in tests) | HTTP client with the app's User-Agent | Apache-2.0 |
| [AndroidX ExifInterface](https://developer.android.com/jetpack/androidx/releases/exifinterface) | Rotating camera photos correctly | Apache-2.0 |
| [Okio](https://github.com/square/okio) (pulled in by kaml and DataStore) | I/O used by those libraries | Apache-2.0 |
| [actions/checkout](https://github.com/actions/checkout), [actions/setup-java](https://github.com/actions/setup-java), [actions/upload-artifact](https://github.com/actions/upload-artifact), [gradle/actions](https://github.com/gradle/actions) | GitHub Actions CI | MIT |

