# Día

| | |
|---|---|
| Status | **Verified from the phone on 2026-11-19** (see the verification log). The development PC is still blocked |
| Base URL (reported) | `https://www.dia.es/api/v1/` |
| Image base (reported) | `https://www.dia.es/product_images/…` |
| Location model (reported) | Postal code stored in a **server session** (cookie), not in each request |
| Bot protection | **Akamai Bot Manager.** It refuses the development PC (Croatian IP); the phone on its Spanish connection is answered with the app's honest User-Agent and no browser headers |

## What happened during research (2026-11-18)

- Every request to `www.dia.es` from the development PC returned **`403 Access Denied`** (an
  Akamai HTML error page, reference `errors.edgesuite.net`). That included the **homepage** and
  `/search?q=…`, with curl and also in a normal Chromium browser pane.
- The PC's public IP geolocated to **Croatia** (Cloudflare trace `loc=HR`, WARP off). Mercadona
  answered normally from the same network. So the block may be geographic, a reputation or bot
  score on this IP, or both.
- By project policy (see [README § Etiquette](README.md#etiquette)) we **did not try to get past
  it**: no browser impersonation, no spoofed client-hint headers, no other network. Everything
  below comes from a third-party open-source project and is **unverified**.

## Reported endpoints (unverified)

Source: the open-source project [`serrano1996/dia-scraper`](https://github.com/serrano1996/dia-scraper)
(files `app/scrapers/dia_search.py`, `dia_session.py`, `http_client.py`, `app/models/dia.py`,
read on 2026-11-18). That project says Día answers 403 to clients that do not send the full set
of headers a real Chrome sends. **We will not do that** (it is browser impersonation).

### Set postal code (session)

`PUT /api/v1/common-aggregator/save-shipping-address?new_postal_code={cp}`

- `204`: postal code set for the current session (cookie `session_id`).
- `206` with `{"code": 206, "message": {"no_service": "…"}, "type": "VALIDATION_ERROR"}`: the
  postal code is not served. Maps to `ProviderError.NotServed`.
- Reported: an anonymous session starts at postal code 28041, and the cookie lasts about 1 hour.
  How the first `session_id` is obtained is not documented there.

### Search

`GET /api/v1/search-back/search/reduced?q={query}&page={n}&page_size={size}`

Reported response fields:

| Field | Meaning |
|---|---|
| `cart.postal_code` | Postal code the session searched with (check it matches the setting) |
| `pagination.page_number`, `pagination.page_size`, `pagination.total_pages` | Paging |
| `total_items` | Result count |
| `search_items[].object_id` | Product id, e.g. `"504P6"` |
| `search_items[].display_name` | Product name |
| `search_items[].prices.price` | Package price (number, euros) |
| `search_items[].prices.price_per_unit` | Price per measure unit |
| `search_items[].prices.measure_unit` | Measure unit of `price_per_unit` (e.g. litre, kilo) |
| `search_items[].prices.strikethrough_price` | Regular price when an offer or Club price applies |
| `search_items[].prices.is_club_price` | `true` if `price` needs the Club Dia card |
| `search_items[].image` | Relative path, e.g. `/product_images/504P6/504P6_ISO_0_ES.jpg` |
| `search_items[].l2_category_description` | Second-level category, e.g. "Leche" |

Not in that model: brand, package size, EAN, URL slug.

### Product page (web, not JSON)

Product URLs look like `https://www.dia.es/{category-slug}/{product-slug}/p/{object_id}`. No
product-detail JSON endpoint is documented in the sources we read.

## Mapping plan (if the feasibility check passes)

| Día | `ProviderProduct` | Notes |
|---|---|---|
| `object_id` | `productId` | |
| `display_name` | `name` | |
| — | `brand`, `brandKind` | Inferred from the name: contains a store brand → `STORE`; fresh produce → `UNBRANDED` |
| `price / price_per_unit` in `measure_unit` | `netQuantity` | **Derived** (e.g. 0,83 € ÷ 0,83 €/l = 1 l). Cross-check with a size parsed from `display_name` ("1 L", "500 g", "6 x 125 g"); if they disagree by more than 5 %, trust the name and mark `approx` |
| `prices.price` / `strikethrough_price` / `is_club_price` | `price` | With the setting "Precios Club Dia" off, use `strikethrough_price` when `is_club_price` is true |
| `price_per_unit` + `measure_unit` | `referencePrice`, `referenceUnit` | |
| `image` | `imageUrl` | Prefix `https://www.dia.es` |
| `l2_category_description` | `categoryPath` | |

Store brands (to verify): `Dia` and its sub-lines (for example "Dia Nature", "Dia Al Punto",
"Dia Láctea"), and `Bonté` for personal care. Check the real list before relying on it.

Catalogue strategy: no category JSON is known, so Día would use **remote search**
(`Capability.REMOTE_SEARCH`) at import and link time. Results are cached in Room so that later
searches, costs and the shopping list work offline.

## Feasibility check (phase 7)

1. On the phone, on its usual connection, the debug app makes **one** request with the honest
   User-Agent: `PUT …/save-shipping-address` for the configured postal code, then one search.
2. `2xx` with JSON → implement `:provider:dia` from this page and record fixtures. Update this
   page with verified facts and a verification log.
3. `403` or an HTML block page → **stop**. Día stays unavailable, and Día prices go in through
   the Manual provider (shop name "Día"). Record the result here.

Either outcome is acceptable. There is no third option where we make the client look like a
browser.

## What the app does (phase 7)

- **Location**: `PUT save-shipping-address` with the postal code answers `204` and sets the
  session cookie (no earlier request is needed). The cookie lives in an in-memory cookie jar of
  the Día provider only; it is never written to disk. Every catalogue sync starts by setting it.
- **Search** (verified): `GET search-back/search/reduced?q=…&page=…`. The answer always has
  **30 items per page** (`page_size` is ignored). Fields seen, beyond the table above: `brand`,
  `brand_type` (`D` Día, `N` national), `dia_brand` (boolean), `l1_category_description`,
  `url` (relative product page), `units_in_stock`, `sku_id`, and `facets` (brand, `dia_brand`,
  milk type…) and `suggestions`. Bodies are UTF-8.
- **Catalogue sync**: Día publishes no category JSON we could verify, so the sync runs a fixed
  list of about 55 everyday food searches (`DiaProvider.SEARCHES`), at most 2 pages each: at most
  about 110 requests at the shared rate limit. On 2026-11-19 it took about 2 minutes and stored
  1980 available products.
- **Price refresh**: no product JSON is known, so the product id is searched (`q={object_id}`)
  and the exact `object_id` is taken from the answer. Verified on 2026-11-19 with `504`.
- **Mapping**: package size from the name (`6 x 1 L`, `3 x 200 ml`, `24 unidades`, `350 g`,
  `1.5 L`), cross-checked with price ÷ price per unit (more than 5 % apart, or no size in the name:
  `approx`); `dia_brand` → `STORE`; Club Dia prices are not used (the regular
  `strikethrough_price` is taken when `is_club_price`); images and product pages are prefixed
  with `https://www.dia.es`.

## Open questions

- Is there a category listing JSON (`plp`) and a product detail JSON (`pdp`) behind the web pages?
- Do prices depend on the postal code or the store?
- The "Precios Club Dia" setting is not offered yet: Club prices are always ignored.

## Verification log

| Date | Check | Result |
|---|---|---|
| 2026-11-18 | `GET https://www.dia.es/` (curl, mobile UA) | 403 Akamai "Access Denied" |
| 2026-11-18 | `https://www.dia.es/` and `/search?q=tortillas%20trigo` in a Chromium browser pane | 403 Akamai "Access Denied" |
| 2026-11-18 | Network egress check | Cloudflare trace `loc=HR`, WARP off |
| 2026-11-19 | Feasibility check from the phone (debug app, honest User-Agent): `PUT save-shipping-address?new_postal_code=08001` | `204`, session cookie set |
| 2026-11-19 | Same session: `GET search-back/search/reduced?q=leche` | `200 application/json`, 30 items, `cart.postal_code` 08001, UTF-8. Recorded as `provider/dia/src/test/resources/fixtures/dia/search-leche.json` (trimmed) |
| 2026-11-19 | Full catalogue sync from the app (55 searches, ≤ 2 pages) | About 2 minutes, 1980 available products, no error |
| 2026-11-19 | Price refresh of `504` (search by id) | Found, price unchanged (0,83 €) |
