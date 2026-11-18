# Supermarket providers

A **provider** turns one supermarket's data into the app's normalised product model. Each
provider is its own Gradle module (`:provider:<id>`), depends only on `:provider:api`, and is
registered through a Hilt multibinding. Feature code never knows which supermarkets exist.

| Provider | Module | Status | Doc |
|---|---|---|---|
| Mercadona | `:provider:mercadona` | **Verified 2026-11-18.** Plain requests with a descriptive User-Agent work | [mercadona.md](mercadona.md) |
| Día | `:provider:dia` | **Blocked / unverified.** Akamai answered 403 to every request from the development network. Endpoints are known only from a third-party project | [dia.md](dia.md) |
| Manual | built into `:core:data` | Always available. Products typed in by the user | this page, § Manual provider |

## Contract (`:provider:api`)

Draft. Finalised in phase 3.

```kotlin
interface SupermarketProvider {
    val id: ProviderId                  // "mercadona", "dia" – stable, stored in the DB and in recipe packs
    val displayName: String             // "Mercadona" – a brand name, not translated
    val storeBrands: Set<String>        // brand names classified as BrandKind.STORE
    val capabilities: Set<Capability>   // CATALOGUE_SYNC, REMOTE_SEARCH, PRODUCT_DETAIL, LOCATION
    val rateLimit: RateLimit            // e.g. 1 request / 1.2 s, burst 1

    /** Resolve a postal code into provider-specific location state (warehouse, session). */
    suspend fun setLocation(postalCode: String): ProviderResult<ProviderLocation>

    /** Top-level catalogue structure, used to plan a sync. */
    suspend fun categories(location: ProviderLocation): ProviderResult<List<ProviderCategory>>

    /** All products of one syncable category. */
    suspend fun productsIn(category: ProviderCategory, location: ProviderLocation): ProviderResult<List<ProviderProduct>>

    /** Fresh data for specific products (price refresh). Unknown ids come back in `missing`. */
    suspend fun products(ids: List<String>, location: ProviderLocation): ProviderResult<ProductBatch>

    /** Optional remote search, for providers without a crawlable catalogue. */
    suspend fun search(query: String, location: ProviderLocation, limit: Int): ProviderResult<List<ProviderProduct>>
}
```

The orchestration (sync progress, cancellation, rate limiting, retries, caching, fallback) lives
in `:core:data`, not in provider modules. Providers are thin: build the request, parse, map.

### Normalised product

```kotlin
data class ProviderProduct(
    val providerId: ProviderId,
    val productId: String,          // provider's own id, as a string
    val name: String,               // display name as the shop writes it
    val brand: String?,             // when known
    val brandKind: BrandKind,       // STORE | NATIONAL | UNBRANDED
    val packaging: String?,         // "Paquete", "Bandeja", "Bote", "Sobre", "Pieza"… (shop wording)
    val netQuantity: Quantity,      // in a base unit: g, ml or ud
    val unitCount: Int?,            // pieces inside (6 tortillas in a 360 g pack)
    val unitCountUnit: Unit?,       // what the pieces are: ud, loncha, rebanada (default ud)
    val drained: Quantity?,         // drained weight when reported (mozzarella, tinned food)
    val approx: Boolean,            // variable weight; price and size are estimates
    val price: Money,               // price of one package, in cents
    val referencePrice: Money?,     // price per reference unit, as the shop shows it
    val referenceUnit: ReferenceUnit?, // KG, L, UD, G100, ML100
    val imageUrl: String?,
    val webUrl: String?,
    val categoryPath: List<String>,
    val available: Boolean,
    val fetchedAt: Instant,
)
```

### Brand classification

- `STORE`: the brand is in `storeBrands` (case- and accent-insensitive), or the product name
  contains one of them as a whole word when the shop gives no brand field.
- `UNBRANDED`: no brand and none detectable (loose fruit, vegetables, fresh meat trays).
- `NATIONAL`: any other brand.

The "Solo marca blanca" setting hides `NATIONAL` products and keeps `STORE` and `UNBRANDED` ones.

### Errors

```kotlin
sealed interface ProviderError {
    data object Offline
    data class Http(val code: Int)
    data object Blocked          // 403 with an HTML body, or a bot-protection page: stop, do not retry
    data class RateLimited(val retryAfter: Duration?)
    data class Parse(val detail: String)
    data object NotServed        // the postal code is outside the shop's delivery area
}
```

## Etiquette

These rules apply to every provider and are enforced in shared code, not left to each module.

1. **User-initiated only.** No background jobs, no periodic refresh, no prefetch.
2. **Rate limit per provider** (token bucket in `:provider:api`), shared by every caller. Default:
   1 request per 1.2 s, burst 1, at most one request in flight per provider. A catalogue sync is
   sequential.
3. **Retries**: only for timeouts and 5xx, at most 2, exponential backoff with jitter (2 s, 6 s).
   `429` honours `Retry-After` up to 60 s, otherwise it fails. `403` (or a bot-protection page)
   **is never retried**. The provider's circuit opens for 30 minutes and the UI says the shop
   refused the connection.
4. **Honest identification**: `User-Agent: CocinaParaLechugones/<version> (Android; personal, non-commercial)`.
   No browser impersonation, no spoofed `sec-ch-*` headers, no TLS or JS fingerprint emulation,
   no captcha solving, no proxies or VPN rotation. If a shop refuses this client, the provider is
   marked unavailable and the user is pointed to Manual products.
5. **Cache first.** Room is the source of truth. A failure never deletes cached data.
6. **Minimal footprint**: only the endpoints documented here. Images are loaded lazily (and
   cached by Coil) at the smallest useful size.
7. **No credentials.** We never log in to a shop account. We do not copy API keys found in a
   website's JavaScript into this repository or the app.

## Adding a provider

1. Investigate the shop's website JSON (browser dev tools, network tab). Write
   `docs/providers/<id>.md` with the template below. Record the date and what was verified.
2. Create `:provider:<id>` (JVM module, `cpl.jvm.library` convention plugin) with:
   - `<Id>Api` Retrofit interface, `@Serializable` DTOs (ignore unknown keys), and a mapper to
     `ProviderProduct`.
   - `<Id>Provider : SupermarketProvider`, plus a Hilt `@Module` with `@Binds @IntoSet`.
   - Trimmed JSON fixtures in `src/test/resources/fixtures/<id>/` and mapper tests, including
     tricky items (variable weight, packs, drained weight, missing fields).
3. Add the store brands, the rate limit and the base URL to the provider object.
4. Add the module to `settings.gradle.kts` and as a dependency of `:app`.
5. Add the provider's hosts to the network security config.
6. Update the status table on this page and the SPEC if anything new was needed.

No feature code, Room schema or settings screen should need changes. If one does, the contract
is missing something: fix the contract first.

### Provider doc template

```
# <Name>
Status, last verified date, base URL, location model (postal code / warehouse / session)
## Endpoints        (method, path, params, headers, example response trimmed to used fields)
## Field mapping    (shop field → ProviderProduct field, units, quirks)
## Store brands
## Catalogue sync plan (number of requests, categories excluded)
## Etiquette notes  (observed bot protection, limits, caching headers)
## Open questions
## Verification log (date, what was checked, result)
```

## Manual provider

For shops without an API, or when a provider is down or refuses us.

- The user creates a product: name, brand, "marca blanca" flag, shop name (free text, used as the
  group in the shopping list), packaging word, package size (`500 g`, `6 ud`…), price, and an
  optional photo.
- Stored as `product` rows with `provider_id = 'manual'`. "Actualizar precio" edits the price by
  hand and records a price snapshot.
- Behaves like any other provider in linking, costs, pantry and the shopping list. It has no
  catalogue sync and no remote search.
