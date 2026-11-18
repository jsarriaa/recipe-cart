# CocinaParaLechugones

A private, non-commercial Android app for cooking at home with real supermarket products. It
links recipes to Mercadona and Día products, shows how much of each package a dish uses and what
it costs, keeps a pantry, and builds a shopping list grouped by supermarket.

It is a sideloaded APK for the owner and a few friends. It will never be published on the Play
Store. The app UI is in Spanish (es-ES); code and docs are in English.

> **Status: phase 2 (recipes, recipe packs, backup).** Recipes can be created, edited (imported
> ones too), photographed, scaled, duplicated, imported from recipe packs, exported and backed up.
> Supermarket products and prices come in phase 3. See the
> [development phases](docs/SPEC.md#9-development-phases).

## Features (planned)

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
  packages, leftovers, and a comparison across supermarkets.
- **Pantry**: what is at home, including opened packages. Food to use up soon is tagged
  "Usar pronto" (by hand, or automatically near its best-before date) and goes first in
  "¿Qué puedo cocinar?". "Lo he cocinado" deducts what a recipe used, after an editable
  confirmation.
- **Shopping list**: built from recipes minus the pantry, grouped by supermarket, with an
  estimated total. Bought items move into the pantry. Shared as text.
- **Backup and restore** to a single file.

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
| `build-logic/` | Gradle convention plugins |

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
| [Coil](https://github.com/coil-kt/coil) | Showing recipe photos | Apache-2.0 |
| [AndroidX ExifInterface](https://developer.android.com/jetpack/androidx/releases/exifinterface) | Rotating camera photos correctly | Apache-2.0 |
| [Okio](https://github.com/square/okio) (pulled in by kaml and DataStore) | I/O used by those libraries | Apache-2.0 |
| [actions/checkout](https://github.com/actions/checkout), [actions/setup-java](https://github.com/actions/setup-java), [actions/upload-artifact](https://github.com/actions/upload-artifact), [gradle/actions](https://github.com/gradle/actions) | GitHub Actions CI | MIT |

Planned for later phases (added here when they are introduced):
[Retrofit](https://github.com/square/retrofit) and [OkHttp](https://github.com/square/okhttp)
(Apache-2.0).
