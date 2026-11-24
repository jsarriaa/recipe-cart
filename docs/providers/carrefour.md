# Carrefour

| | |
|---|---|
| Status | **Verified 2026-11-24** from the development PC with the app's User-Agent (JSON, 200). Not yet checked from the phone |
| Base URL | `https://api.empathy.co/search/v1/query/carrefour/` (Carrefour's search index, hosted by Empathy) |
| Images | `https://static.carrefour.es/hd_350x_/img_pim_food/…` (absolute URLs in the documents) |
| Location model | None. National online prices (see below) |
| Source | Ported from [supermercapy](https://github.com/jtayped/supermercapy) (MIT, [third_party/supermercapy](../../third_party/supermercapy/README.md)): `docs/stores/carrefour.md`, `carrefour/models.py` (`parse_search_document`) |

Carrefour's online shop has two halves. The **search index** at `api.empathy.co` is the same one
the shop's own search box calls: unauthenticated, no cookie, JSON. The **storefront**
(`www.carrefour.es`: product pages, category tree, sale-point directory) sits behind Cloudflare,
which lets only browser User-Agents through. We never pretend to be a browser (etiquette rule
4), so the provider uses the search index only. That has two consequences:

- **No sale point.** Choosing a shop near the postal code needs the storefront's sale-point
  directory. Without one the index prices against Carrefour's national default, which are the
  online prices. The postal code is ignored and `resolveLocation` makes no request.
- **Search-based catalogue sync**, like Día: the shared `FoodSearches` terms, one request each.

## Endpoints

`GET search?query={term}&rows={n}&start={offset}&lang=es&catalog=food`

- `query` is lowercased, as Carrefour's client does (it shares the CDN cache key).
- `rows` at most 500, `start` at most 2,498. We ask for 120 rows, one page per search term.
- The answer is `{"catalog": {"content": [documents…], "numFound": n, …}, …}`. We read
  `catalog.content` only. The `tagging` URLs in every document are analytics beacons (writes):
  never followed.

Product refresh searches the product id as free text (`rows=5`) and picks the exact
`product_id`, as supermercapy's `_lookup` does. No match means the shop no longer has it.

## Field mapping

| Document field | `ProviderProduct` | Notes |
|---|---|---|
| `product_id` | `key.productId` | Opaque string; four spellings exist (`714713105`, `VC4AECOMM-481229`, `prod190222`, `fprod1280750`) |
| `display_name` | `name` | |
| `brand` | `brand`, `brandKind` | Written in capitals: title-cased for display. `MARCA NACIONAL SIN MARCA` and `PRIMER PRECIO SIN MARCA` mean no brand (unbranded) |
| `active_price` | `price` | Euros as a number; the current price (`list_price` is the pre-discount one) |
| `measure_unit` × `unit_conversion_factor` | `net` | `kg`/`l` → g/ml × 1000, `ud` → pieces, `docena` → × 12. `lavado` and `m` fall back to the text |
| `variable_weight`, `average_weight` | `net`, `approx` | Loose fresh food: the factor is a `0.001` placeholder, so the net is `average_weight` grams, marked approximate |
| `recipient` | `unitCount`, fallback `net` | `pack 6x100 g.` → 6 pieces; `1 l.` when there is no factor |
| `active_price ÷ unit_conversion_factor` | `referencePrice`, `referenceFormat` | Computed, as supermercapy does (the index publishes no unit price). None for variable weight |
| `image_path.food` | `imageUrl` | |
| `urls.food` | `webUrl` | Prefix `https://www.carrefour.es` |
| `sale_point_available` | `available` | Usually absent without a sale point: then available |
| `active_food: false` | skipped | Not in the food catalogue |

## Store brands

Carrefour, Carrefour Bio, Carrefour Eco, Carrefour Classic, Carrefour Extra, Carrefour
Sensation, Carrefour El Mercado, Carrefour Original, Carrefour Veggie, Carrefour No Gluten,
Simpl, De Nuestra Tierra. Any brand starting with "Carrefour" counts as a store brand.

## Catalogue sync plan

About 55 requests (one per `FoodSearches` term, 120 rows each), at the default 1 request per
1.2 s: about 70 s. Duplicates across terms are dropped.

## Etiquette notes

- The index answered our honest User-Agent from a Croatian IP on 2026-11-24. supermercapy notes
  that neither the index nor the images are behind the storefront's Cloudflare gate.
- The storefront (`www.carrefour.es`) is never called.
- The index has no `robots.txt` rule against search (it is an API host).

## Open questions

- Whether online prices match the owner's nearest Carrefour. If they don't, the app shows the
  online price; there is no compliant way to pick a sale point.

## Verification log

| Date | Check | Result |
|---|---|---|
| 2026-11-24 | `GET …/search?query=leche&lang=es&catalog=food&rows=5` from the PC, app User-Agent | 200 JSON, 2,024 hits, prices present |
| 2026-11-24 | Same for `platano` and `yogur natural` (fixtures) | 200 JSON; variable-weight and multipack documents recorded |
