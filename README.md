# CocinaParaLechugones

A private, non-commercial Android app for cooking at home with real supermarket products. It
links recipes to Mercadona and Día products, shows how much of each package a dish uses and what
it costs, keeps a pantry, and builds a shopping list grouped by supermarket.

It is a sideloaded APK for the owner and a few friends. It will never be published on the Play
Store. The app UI is in Spanish (es-ES); code and docs are in English.

> **Status: phase 0 (specification).** There is no app code yet. See the
> [development phases](docs/SPEC.md#9-development-phases).

## Features (planned)

- **Supermarket catalogue**: one pluggable module per supermarket, starting with Mercadona and
  Día, plus manual products. Cached locally, rate-limited, refreshed only on demand, and falling
  back to cached prices when a shop fails. Optional "store brand only" filter.
- **Recipes**: create, edit, duplicate, delete, scale servings. Each ingredient has a quantity,
  a unit, a generic name and links to products. Pantry staples are flagged.
- **Recipe packs**: import and export Markdown files with YAML front matter, or `.zip` packs with
  images. On import the app suggests products to link, and "Aceptar todo" confirms them in one
  tap. Export round-trips. Recipes can be generated in an external Claude chat with
  [the generator prompt](prompts/recipe-pack-generator.md).
- **Quantities and cost**: "1/2 paquete de ñoquis, 250 g de 500 g", dish cost, cost of whole
  packages, leftovers, and a comparison across supermarkets.
- **Pantry**: what is at home, including opened packages. "Lo he cocinado" deducts what a recipe
  used, after an editable confirmation. A "¿Qué puedo cocinar?" view.
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

- **JDK 21** for Gradle: Android Studio's bundled JBR (`C:\Program Files\Android\Android Studio\jbr`).
  Set `JAVA_HOME` to it, because the `java` on PATH is JDK 11.
- **Android SDK** in `%LOCALAPPDATA%\Android\Sdk`, with platform 36 installed.
- **adb** from the SDK's `platform-tools` (not the old `C:\Android\adb.exe`).
- **GitHub CLI** (`gh`) for pull requests.

Details and the phone setup: [docs/DEVICE_SETUP.md](docs/DEVICE_SETUP.md).

Build commands (available from phase 1):

```bash
./gradlew test                 # unit tests
./gradlew lint                 # Android lint
./gradlew :app:installDebug    # install the debug build on the connected phone
```

## Contributing workflow

One phase per branch and pull request, Conventional Commits, unit tests for all domain logic,
and docs updated in the same PR. See [CLAUDE.md](CLAUDE.md#workflow).

## Legal

Personal, non-commercial project. Not affiliated with, endorsed by or connected to Mercadona,
Día or any other supermarket. Brand names belong to their owners. Product data is read in small
amounts from the same public endpoints the supermarkets' websites use, only when the user asks,
and is cached on the phone. See [docs/providers/README.md § Etiquette](docs/providers/README.md#etiquette).
