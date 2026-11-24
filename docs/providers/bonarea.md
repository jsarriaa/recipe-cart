# bonÀrea

| | |
|---|---|
| Status | **Verified 2026-11-24** from the development PC with the app's User-Agent (JSON, 200). Not yet checked from the phone |
| Base URL | `https://www.bonarea-online.com/` |
| Images | `https://images.bonarea.com/{file}?width=300&height=300` (the CDN resizes; originals are about 1 MB) |
| Location model | None. One national catalogue and national prices |
| Source | Ported from [supermercapy](https://github.com/jtayped/supermercapy) (MIT, [third_party/supermercapy](../../third_party/supermercapy/README.md)): `docs/stores/bonarea.md`, `bonarea/client.py` (`_prime`, `_search`, `get_product`), `bonarea/models.py` (`parse_product`, `_price`, `sized`) |

bonÀrea (Guissona) sells its own production plus some national brands, online and in its
shops, at the same national prices. Every read is a form post to `/es/shop/{action}` that
answers JSON.

## Endpoints

| Request | Used for | Notes |
|---|---|---|
| `GET es` | Session warm-up, once per app session | Search ignores the language in the path and reads it from the session; without this cookie it answers in Catalan. The cookie stays in memory only |
| `POST es/shop/search`, form `strQuery={term}` | Catalogue sync, one per `FoodSearches` term | Answers **every hit at once**: no page, size or sort parameter. "leche" gave 460 articles, 540 kB |
| `POST es/shop/Article`, form `identifier={id}` | Price refresh | Honours the path language, so no warm-up. Unknown id: 200 with `{"article": null}` |

## Field mapping

| Article field | `ProviderProduct` | Notes |
|---|---|---|
| `identifier` | `key.productId` | `13*0066`. The prefix is the business: **13** supermarket and **99** frozen food are kept; **03** (garden, drugstore) and **05** (farm) are skipped |
| `description` | `name` | May be HTML-escaped (`leche &amp; miel`): decoded |
| `caracteristiques` | `brand`, `brandKind` | `BONAREA` or `GARANTIA_BONAREA` means own production: brand "bonÀrea", store brand. Otherwise a national brand, whose name is part of the product name (there is no brand field) |
| `priceToPay` | `price` | Euros |
| `measurementUnit` | `net`, `approx` | `6 l`, `1,5 l`, `600 ml`, `aprox. 187 g` (approximate), `16 u.` (pieces); falls back to the name |
| name (`paq. de 6 brics`, `paq. 3 u. de 200 ml`) | `unitCount` | |
| `unitPrice` | `referencePrice`, `referenceFormat` | Text such as `1,12 €/l`. `€/ml.` is per litre (supermercapy found this on every row that carries it). Per-dose (`d.`) and per-metre prices are dropped |
| `isAnApproximatePrice` | `approx` | |
| `itsOnStock` | `available` | |
| `isValid: false` | skipped | |
| `image[0]` | `imageUrl` | Resized to 300 × 300 by the CDN |
| `urlFriendly` | `webUrl` | Absolute already |

## Store brands

bonÀrea.

## Catalogue sync plan

One warm-up plus about 55 searches (one per `FoodSearches` term), at 1 request per 1.2 s:
about 70 s. Because every search returns all its hits, a sync downloads roughly 20 to 25 MB
of JSON. Walking the category tree instead would cost 491 requests (supermercapy, October
2026).

## Etiquette notes

- No bot protection seen. supermercapy paces at 0.5 s; we use our 1.2 s default.
- `robots.txt` has no rules for these paths.
- Cart, session and stock endpoints are never called.

## Verification log

| Date | Check | Result |
|---|---|---|
| 2026-11-24 | `GET /es` then `POST /es/shop/search strQuery=leche`, from the PC, app User-Agent | 200 JSON, 460 articles, Spanish names |
| 2026-11-24 | `GET images.bonarea.com/13_9103_1.png` with and without `?width=300&height=300` | 1.1 MB and 55 kB PNG |
