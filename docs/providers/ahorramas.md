# Ahorramás

| | |
|---|---|
| Status | **Verified 2026-11-24** from the development PC with the app's User-Agent (HTML, 200). Not yet checked from the phone |
| Base URL | `https://www.ahorramas.com/` (Salesforce Commerce Cloud) |
| Images | `https://www.ahorramas.com/dw/image/v2/…?sw=260&sh=260&sm=fit` (same host; the CDN resizes) |
| Location model | None. One price list (the chain sells in the Madrid area) |
| Source | Ported from [supermercapy](https://github.com/jtayped/supermercapy) (MIT, [third_party/supermercapy](../../third_party/supermercapy/README.md)): `docs/stores/ahorramas.md`, `ahorramas/client.py` (`_grid`), `ahorramas/models.py` (tile parsing, weighed products, unit vocabulary) |

The shop has no JSON API for listings. Its `Search-UpdateGrid` controller answers the HTML
tiles of the product grid. They are parsed with **jsoup**, the only HTML parser in the app.

## Endpoints

`GET on/demandware.store/Sites-Ahorramas-Site/es/Search-UpdateGrid?q={term}&sz={n}&start=0`
with `X-Requested-With: XMLHttpRequest`.

- The sync asks for 40 tiles per `FoodSearches` term. A tile is about 11 kB of HTML;
  supermercapy saw 300 tiles in one 3.9 MB answer, so the size is capped on our side.
- The grid publishes no total. One page per term is enough for recipe ingredients.
- **Price refresh** searches the grid for the product id (`q=70865` returns exactly that
  tile), because the product page `Product-Variation*` is disallowed by `robots.txt`.
- A blank query answers the whole catalogue, so it is never sent.

## Field mapping (tile)

| Tile | `ProviderProduct` | Notes |
|---|---|---|
| `div.product[data-pid]` | `key.productId` | Digits |
| `.product-name-gtm` text | `name` | Usually holds the size: `1L`, `1,5l`, `240g`, `4x100g`, `12u`, `6 unid` |
| `div.product-tile[data-brand]` | `brand`, `brandKind` | Capitals, title-cased. `ALIPENDE` (and `NUESTRO PESCADO CONGELADO`) are store brands; `SIN MARCA` or empty means unbranded |
| `.sales .value[content]` | `price` | The price the tile shows. On a price drop a struck-through price comes first and the analytics price is the old one (supermercapy), so neither is used |
| `.unit-price-per-unit.grey` | `referencePrice`, `referenceFormat` | `0,83€/LITRO`, `3,48€/Kg`, `3,05€/DOCENA`, `15,71€/KG.PESO ESC` (per kilo of **drained** weight, on tins and jars), `UNIDAD`. `LAVADO` (doses) is dropped |
| name, or price ÷ unit price | `net`, `approx` | When the name has no size (`Atún claro pack 6`), the content is derived from the unit price and marked approximate. For tins this is the drained weight |
| `[data-price-kg="true"]` | `net` = 1 kg, approximate | Fresh food sold by weight: the price is per kilogram. `data-mediumweight` is one piece in kg (not used yet) |
| `pack 6`, `P4`, `4x100g` in the name | `unitCount` | |
| `[data-available]` | `available` | |
| `img.tile-image` | `imageUrl` | Rewritten to `?sw=260&sh=260&sm=fit` |
| `a.product-pdp-link[href]` | `webUrl` | |
| `data-category1`, `data-category2` | `categoryPath` | |

## Store brands

Alipende, Nuestro Pescado Congelado (assumed own line; check when more tiles are seen).

## Catalogue sync plan

About 55 requests (one per `FoodSearches` term, 40 tiles each, about 0.45 MB each), at
1 request per 1.2 s: about 70 s and roughly 25 MB of HTML. supermercapy's full category walk is
about 76 pages and 88 MB.

## Etiquette notes

- Cloudflare is in front. It answered the honest User-Agent without a challenge
  (supermercapy, October 2026; us, 2026-11-24). A challenge page or 403 stops the provider.
- `robots.txt` disallows `Product-Variation*`, `Search-ShowAjax*`, `/buscador?*`, `*&prefn1*`,
  `*?pmax*`, `*ver-todo*`, the cart and login. None of them is used. `Search-UpdateGrid` is
  allowed.

## Verification log

| Date | Check | Result |
|---|---|---|
| 2026-11-24 | `Search-UpdateGrid?q=leche&sz=5`, then `q=leche|platano|huevos|atun&sz=12`, from the PC, app User-Agent, 1.5 s apart | 200 HTML tiles |
| 2026-11-24 | `Search-UpdateGrid?q=70865&sz=5` | Exactly the tile of product 70865 |
