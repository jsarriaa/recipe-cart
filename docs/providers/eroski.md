# Eroski

| | |
|---|---|
| Status | **Verified 2026-11-24 from the phone** (Spanish connection) with the app's User-Agent: 200 for every search fragment. The development PC (Croatian IP) gets a bare 403 |
| Base URL | `https://supermercado.eroski.es/` (Apache Tapestry 5, rendered on the server) |
| Images | `https://supermercado.eroski.es//images/{id}.jpg` (same host, as each tile gives it) |
| Location model | None. An anonymous visitor is pinned to shop 157 (Bilbondo), which sets the prices: **Basque prices**. Choosing another shop needs an account, so the postal code is never sent |
| Module | `:provider:tapestry`, shared with [Caprabo](caprabo.md) (same build, same markup) |
| Source | Ported from [supermercapy](https://github.com/jtayped/supermercapy) (MIT, [third_party/supermercapy](../../third_party/supermercapy/README.md)): `docs/stores/eroski.md`, `_platforms/tapestry.py` (fragments, tiles, visible price, weighed produce, unit text, product page, reCAPTCHA detection). Its `listing.json`, `listing_marketplace.json`, `product.html` and `recaptcha_challenge.html` are test fixtures |

## Endpoints

| Use | Request | Notes |
|---|---|---|
| Search | `GET es/search/results:loadpage?q={term}&t:zoneid=productListZone&pageNumber={n}` with `X-Requested-With: XMLHttpRequest` | The fragment the storefront's own infinite scroll loads: JSON `{"content": "<html>", "_tapestry": {"inits": […]}}`, about 20 tiles and 230 kB (about a tenth on the wire, compressed). The init `pages/supermarket:finishPagination` means no more pages |
| Product | `GET es/productdetail/{id}-producto/` | The slug is ignored. About 900 kB of HTML. An unknown id redirects to `/es/error/404/` (treated as gone) |

Searching for a product id finds nothing (checked 2026-11-24), so price refresh reads the
product page.

## Catalogue sync plan

Search-based, like Día: two fragments (about 40 products) for each `FoodSearches` term, about
110 requests at one per 1.2 s: a little over two minutes, roughly 25 MB of HTML before
decompression savings. supermercapy's category walk would be hundreds of pages and needs the
1 MB menu.

## Field mapping (tile)

| Tile | `ProviderProduct` | Notes |
|---|---|---|
| `a.product-title-link[data-metrics]` → `item_id` | `key.productId` | Digits. `MP…` ids and tiles flagged `isMarketplace` are third-party sellers and are dropped. Criteo ad slots carry no event and are skipped |
| link text | `name` | `Leche semidesnatada del País Vasco EROSKI, brik 1 litro`: the size ends the name |
| `item_brand` | `brand`, `brandKind` | Capitals, title-cased. Eroski, Eroski Basic (`E.BASIC`), Eroski Natur, Eroski Sannia, Seleqtia and Caprabo are store brands |
| `.price-offer-now` (tile) or `.offer-now` (product page) | `price` | The price printed now. A struck-through `…offer-before` is ignored. The analytics `price` is the fallback |
| `p.quantity-text` | `referencePrice`, `referenceFormat` | `1 KILO A 5,73 €`, `1 LITRO A …`, `1 DOCENA A …`, `100 ML. A 0,31 €` (restated per L). Most single items print none; then it is price ÷ size for g and ml |
| name | `net`, `unitCount` | `brik 1 litro`, `botella 1,5 litros`, `pack 4x100 g`, `pack 2x112,5 g`, `cartón 6 uds.`, `1 ud, 300 g`. Without a size, price ÷ unit price, approximate |
| weight icon, or `isWeightOptionsAvailable` | `net`, `approx` | Produce sold by weight (`al peso, compra mínima 1 kg`): the price is for the minimum purchase, so `net` is that minimum, approximate |
| `img.product-img` | `imageUrl` | |
| link `href` | `webUrl` | `:443` removed |

The analytics event names categories only by id, so `categoryPath` stays empty.

## Etiquette notes

- Google Cloud Armor answers a client it doubts with a reCAPTCHA page and HTTP 200. The provider
  recognises it and stops as blocked after one request. It is never solved.
- The edge refuses a few user agents (curl, python-requests, empty) with a bare 403. It accepted
  ours from the phone.
- `robots.txt` disallows `*/search/results/?q=`. The fragment path
  `/es/search/results:loadpage?q=` does not match it literally but is the same search; the
  owner accepted paced, user-initiated requests on such paths (etiquette rule 8).

## Verification log

| Date | Check | Result |
|---|---|---|
| 2026-11-24 | Search fragment `q=leche` from the PC, app User-Agent | 403 (134 bytes) |
| 2026-11-24 | Same from the phone, then `q=platano|huevos|atun`, `q=leche&pageNumber=2`, 2 s apart | 200 JSON fragments; recorded as the test fixtures |
| 2026-11-24 | `q=18672311` (an id) from the phone | No tiles, `finishPagination` |
| 2026-11-24 | Full catalogue sync from the phone (debug app), search `leche`, photos | 1911 products, sizes, prices and photos correct. |
