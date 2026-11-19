# CLAUDE.md

Guidance for Claude Code (and any other contributor) working in this repository.
Read this file fully at the start of every session.

## Project in one paragraph

CocinaParaLechugones is a **private, non-commercial Android app** (sideloaded APK for the
owner and a few friends, never published on the Play Store). It links recipes to real
supermarket products (Mercadona, Día, more later), shows how much of each package a dish uses
and what it costs, keeps a pantry, and builds a shopping list. The full specification is in
[docs/SPEC.md](docs/SPEC.md). The recipe pack format is in [docs/RECIPE_FORMAT.md](docs/RECIPE_FORMAT.md).

## Language rules

- **English** for all code, identifiers, comments, KDoc, docs, commit messages, PR titles and
  descriptions, and test names.
- **Spanish (es-ES)** for everything the user sees in the app. Every UI string lives in
  `app/src/main/res/values/strings.xml` (Spanish is the default locale; the resources root
  declares `tools:locale="es"`). Use `<plurals>` for counts. No hard-coded UI text in Kotlin,
  not even in previews; use `stringResource`.
- Domain code (`:core:domain`) never produces user-facing text. It returns structured results
  (enums, numbers, fractions) and the UI layer formats them with string resources.
- Number, money and date formatting always uses `Locale.forLanguageTag("es-ES")`
  (`1,5 kg`, `3,45 €`, `18/11/2026`), whatever the device locale.
- Recipe text that comes in through recipe packs is Spanish. Format keys and unit codes are fixed
  identifiers defined in RECIPE_FORMAT.md.

## Hard rules

1. **The phone holds the owner's real data.** Never uninstall the app, clear its data, or run a
   destructive adb command without asking first and getting an explicit yes. See
   [Device rules](#device-rules).
2. **Never lose user data in a schema change.** Room schemas are exported to `core/data/schemas/` and
   committed. Every schema change bumps the version and ships a tested `Migration` (or
   `AutoMigration` with a spec). `fallbackToDestructiveMigration*` is forbidden in every build type.
3. **Network etiquette with supermarkets** (details in [docs/providers/README.md](docs/providers/README.md)):
   requests run only when the user asks (no background polling or WorkManager jobs). They go
   through the shared per-provider rate limiter and are cached in Room. If a provider fails,
   fall back to cached data. The app sends an honest, descriptive `User-Agent`. **Never
   impersonate a browser or try to get around bot protection** (spoofed client hints,
   fingerprint emulation, captcha solving, rotating proxies). If a site refuses us, that provider
   degrades to cached or manual prices.
4. **Privacy.** No accounts, analytics, crash reporting, ads, remote config or AI features. No SDK
   that phones home. The only network hosts are the supermarket endpoints listed in
   `docs/providers/` plus their image CDNs, and GitHub (`api.github.com` and its release
   download hosts) for updates: one check each time the app opens (owner's request) and the
   "Buscar actualizaciones" button. The APK is downloaded only when the user taps "Actualizar".
   `raw.githubusercontent.com` serves the community recipe book (`jsarriaa/recetario`), read
   only when the user opens "Recetas de la comunidad" or taps "Descargar". The owner's
   Cloudflare Worker (`community/worker/`, address in `CommunityLinks.PUBLISH_URL`) receives a
   recipe only when the user confirms "Publicar".
   `android:allowBackup="false"`; the in-app backup file is the only backup path.
5. **No secrets in git.** Keystores, `keystore.properties`, `local.properties` and `.env` files are
   gitignored. Do not copy third-party API keys found in websites' JavaScript into this repo.
6. **Never change `applicationId`** (`com.lechugones.cocina`, debug: `com.lechugones.cocina.debug`)
   once real data exists. A new id is a new app, and the data stays behind in the old one.

## Stack

Kotlin, Jetpack Compose + Material 3, MVVM (ViewModel + `StateFlow` UI state), coroutines and
Flow, Room (with FTS for product search), DataStore (Preferences), Retrofit + OkHttp with
kotlinx.serialization, Hilt, KSP (not kapt), Coil for images, kaml for YAML front matter,
Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`) + convention plugins in
`build-logic/` (`cpl.android.application`, `cpl.android.library`, `cpl.jvm.library`).
AGP 9 with its built-in Kotlin support (no `kotlin-android` plugin). minSdk 26, targetSdk 36
(the test phone), compileSdk 37 (required by current AndroidX). Versions are pinned only in the
catalog; bump them at the start of a phase, not in the middle of one.

Module layout and responsibilities are defined in [docs/SPEC.md § Architecture](docs/SPEC.md#3-architecture).
In short: `:core:domain` is pure Kotlin (JVM) and holds all business logic so it can be unit
tested fast. Android-specific code stays out of it.

## Workflow

- **One phase per branch and PR.** Branch name `phase/<n>-<slug>` (e.g. `phase/1-foundations`).
  Small fixes outside a phase use `fix/<slug>` or `docs/<slug>`. Never commit directly to `main`
  after the initial commit; open a PR and wait for the owner's review.
- **Conventional Commits**: `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `build:`, `chore:`,
  `ci:`, with an optional scope (`feat(recipes): …`, `fix(provider-mercadona): …`). Imperative mood,
  subject ≤ 72 characters, body explains *why*.
- **Plan first.** At the start of each phase, propose a plan (scope, files, tests, risks) and wait
  for the owner's OK before writing code.
- **Tests.** Every piece of domain logic gets JVM unit tests: units and conversions, quantity
  parsing and formatting, package fractions, costs, scaling, pantry deduction, shopping list
  building, product matching, and recipe pack parsing, validation and round trip. Provider
  mappers are tested against recorded JSON fixtures in `src/test/resources/`. Room migrations get
  migration tests. A PR is not ready while `./gradlew test` or `lint` fails.
- **Docs stay current in the same PR.** If behaviour, a format, an endpoint or a phase changes,
  update `docs/SPEC.md`, `docs/RECIPE_FORMAT.md`, `docs/providers/*.md` and
  `prompts/recipe-pack-generator.md` together. A change to the recipe format must update the
  format doc, the generator prompt, the examples and the parser tests in one PR.
- **Credits stay current.** Adding a dependency, a GitHub Action, or anything learned from or
  taken from another project means updating "Credits and third-party code" at the end of
  README.md in the same PR. Never copy code without checking that its licence allows it, keeping
  its notice, and saying so in the credits.
- **Check on the device** before calling UI work done: install, open the screen, take a
  screencap, read logcat for errors. Say what was and was not verified.

## Commands

Two quirks of the development PC (details in [docs/DEVICE_SETUP.md](docs/DEVICE_SETUP.md#the-development-pc-windows-11)):

- The `java` on PATH is JDK 11, which the Android Gradle Plugin rejects. Use the **Adoptium
  JDK 21**. Android Studio's JBR is also JDK 21, but it lacks the `jdk.crypto.mscapi` module needed
  for the next point.
- **Avast Web Shield intercepts HTTPS** and re-signs certificates with its own root, which Java
  does not trust (`PKIX path building failed`). Point every JVM at the Windows trust store with
  `JAVA_TOOL_OPTIONS`. CI (Linux) needs neither setting.

```bash
# Git Bash, once per shell
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.1.12-hotspot"
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"

./gradlew spotlessApply             # format Kotlin with ktlint (run before committing)
./gradlew spotlessCheck test lint   # what CI runs
./gradlew :app:installDebug         # build + install the debug app, keeps its data (adb install -r)
./gradlew :core:domain:test --tests "*PackageFraction*"
```

```powershell
# PowerShell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot"
$env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
.\gradlew.bat test
```

`local.properties` (gitignored) holds `sdk.dir=C\:/Users/Joan/AppData/Local/Android/Sdk`.

Known test-environment limits on Windows, both handled in the tests: Room's
`MigrationTestHelper` needs an absolute database path under Robolectric, and DataStore's file
storage cannot rename over an existing file (repository tests use an in-memory `DataStore`).

## Device rules

Full setup and troubleshooting: [docs/DEVICE_SETUP.md](docs/DEVICE_SETUP.md).

- **adb binary**: use the SDK one, `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"` (the one
  Gradle uses). `C:\Android\adb.exe` is first on PATH but is v1.0.32 (2015). It cannot pair over
  Wi-Fi and it fights newer adb servers. Do not use it.
- **Start of every session**: run `adb devices -l`. Expect exactly one device in state `device`.
  If it shows `unauthorized`, ask the owner to accept the prompt on the phone. If it shows
  `offline` or nothing, see DEVICE_SETUP.md. Do not guess.
- Work on the **debug app** (`com.lechugones.cocina.debug`). It has its own data, separate from
  the release app the owner uses day to day.

Allowed without asking:

- `adb devices`, `adb connect`, `adb pair` (the owner reads you the code), `adb shell getprop`,
  `adb shell dumpsys window | grep mCurrentFocus`, `adb shell pidof`.
- `./gradlew :app:installDebug` or `adb install -r <debug apk>`. Both keep data.
- `adb shell am start -n com.lechugones.cocina.debug/com.lechugones.cocina.MainActivity`,
  `adb shell am force-stop com.lechugones.cocina.debug`.
- `adb logcat` filtered to the debug app's PID or tags (`-d`, `-T`, `--pid`). Do not clear the
  log buffer.
- `adb exec-out screencap -p > .device/<name>.png`, `adb exec-out uiautomator dump /dev/tty`.
  Captures go to `.device/` (gitignored), because they can show personal data.
- `adb shell input tap|swipe|text|keyevent`, only after confirming that the debug app has focus.
- `adb push` of test fixtures into `/sdcard/Download/CocinaParaLechugones-test/`, and deleting
  files **you** pushed there.

Ask first, every time (an earlier yes covers only that one action):

- `adb uninstall`, `pm uninstall`, `pm clear`, `cmd package` changes, `run-as … rm`, any `rm`
  outside the test folder above.
- Anything that touches the **release** package `com.lechugones.cocina` or its data.
- `adb reboot`, `settings put`, `pm grant/revoke`, `adb root`, `adb backup/restore`, `svc`.
- `install` without `-r`, or any install that fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  (signature mismatch). **Stop and report it. Never "fix" it by uninstalling.**
- Installing a build with a database migration before the owner has made a fresh in-app backup
  (from phase 2 on).

## Coding conventions

- Packages: `com.lechugones.cocina.<module>.<feature>`.
- UI state: one immutable `data class …UiState` per screen, exposed as `StateFlow`; one-off
  events via `Channel`/`SharedFlow`; no business logic in composables.
- Repositories expose `Flow` for observable data and `suspend` functions for actions. Dispatchers
  are injected, never hard-coded.
- Money is `Money` (`Long` euro cents). Quantities are `Amount` (fixed-point `Long` thousandths
  of their unit). Intermediate arithmetic uses `BigDecimal`. Round only when presenting.
- Units and conversions come only from the domain `MeasureUnit` and `UnitConverter`. No ad-hoc
  `* 1000` in UI code.
- es-ES number and money formatting (digits, comma, `€`) lives in the domain's
  `SpanishNumberFormat` so it is unit tested. Words (unit names, sentences) come from string
  resources.
- Errors cross layers as sealed results (`ProviderResult`, `ImportResult`). Do not let exceptions
  escape repositories.
- Keep dependencies in the version catalog. Adding a library needs a one-line justification in
  the PR description.
- No `!!` in production code (use `checkNotNull`/`requireNotNull` with a message for real
  invariants). No `GlobalScope`. No `runBlocking` outside tests.
- Never write invisible or special Unicode characters in source as literals or as unicode
  escapes (no-break space, BOM, line and paragraph separators): tooling has silently turned the
  escapes into raw characters and then dropped them. Use code points: `Char(0x00A0)`, `Char(0xFEFF)`.
- Domain tests use JUnit Jupiter (`@ParameterizedTest` + `@CsvSource` for rule tables) and
  kotlin.test assertions. Android module tests use JUnit 4 + Robolectric.
