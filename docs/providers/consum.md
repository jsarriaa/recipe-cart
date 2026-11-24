# Consum

| | |
|---|---|
| Status | **Verified 2026-11-24 from the phone** (Spanish connection) with the app's User-Agent: JSON, 200. The development PC (Croatian IP) gets a 403 from Imperva, as with Día |
| Base URL | `https://tienda.consum.es/api/rest/V1.0/` |
| Images | `https://cdn-consum.aktiosdigitalservices.com/tol/consum/media/product/img/300x300/{code}_001.jpg?t=…` (the first of the product's `media`) |
| Location model | None bound. Prices are national; a zone only narrows the assortment, so the postal code is never sent |
| Source | Ported from [supermercapy](https://github.com/jtayped/supermercapy) (MIT, [third_party/supermercapy](../../third_party/supermercapy/README.md)): `docs/stores/consum.md`, `consum/client.py` (menu, listing, product by code), `consum/models.py` (offer rule, product types, unit vocabulary). Its `category_listing.json` and `error_404.json` are test fixtures |

## Endpoints

Every request sends `X-TOL-LOCALE: es` and `Accept: application/json`. No cookie, token or key.

| Use | Request | Notes |
|---|---|---|
| Category tree | `GET shopping/category/menu` | About 420 kB. Roots with `type` 1 are marketing groups ("Ahora más barato") |
| Category listing | `GET catalog/product?categories={id}&limit=100&offset={n}` | The subtree is included. At most 100 rows; `hasMore` says when to stop. The offset counts every row sent, sponsored ones too |
| Product | `GET catalog/product/code/{code}` | 404 (`errorType` 44) both for an unknown code and for one outside the zone: treated as gone |

## Catalogue sync plan

One menu request, then each second-level category of the food roots (Despensa, Bebidas,
Frescos, Horno, Platos preparados, Congelados y helados), 100 rows per request. Roots skipped:
Ecológico y saludable (it repeats the others), Infantil, Droguería y limpieza, Cuidado personal,
Mascotas, Bazar, and the marketing groups. supermercapy's full walk was 9,084 products in 91
requests; the food part is roughly 70 requests, about 85 s at one request per 1.2 s.

## Field mapping

| Consum | `ProviderProduct` | Notes |
|---|---|---|
| `code` | `key.productId` | Digits. The internal `id` is not used |
| `productData.name` | `name` | |
| `productData.description` | `net`, `unitCount` | The name followed by the size: `1 L`, `1,5 L`, `72,5 Gr`, `6 x 52 Gr`, `300 ml`, `3 U`, `1 Dc` (a dozen), `0,5 Dc` (6), `0,83 Dc` (10), `1,5 Dc` (18) |
| `productType` 2 | `net` = 1 kg, approximate | Sold by weight: the price is per kilogram (`priceUnitType` `kg`) |
| `productType` 3 | `net` = price ÷ price per kg, approximate; `packaging` = `format` | A piece or tray at an approximate price (`Bandeja 700 g aprox.`, `Pieza precio aprox.`) |
| `priceData.prices[]` | `price` | `OFFER_PRICE` (an immediate offer) replaces `PRICE`. `centAmount` holds **euros** despite its name, read from its decimal text |
| `centUnitAmount` + `unitPriceUnitType` | `referencePrice`, `referenceFormat` | `1 L`, `1 Kg`, `1 Dc`, `1 U` as given; `100 Gr` and `100 ml` restated per kg or L. Doses (`Do`), washes (`Lv`) and metres (`M`) are dropped |
| `productData.brand.name` | `brand` | Capitals, title-cased. Empty means unbranded |
| attribute `filter.id.brand.distributor` = `1`, or a brand starting with `CONSUM` | `brandKind` = store | Consum, Consum Eco, Consum Kids. The meat counter's trays carry the Consum brand without the flag |
| `availability` = `1` and not `temporaryOutOfStock` | `available` | |
| `media[]` (lowest `order`), else `imageURL` | `imageUrl` | `imageURL` (`…/{code}.jpg`) answered 404 on the CDN; the `media` photos (`…/{code}_001.jpg`) answer 200 |
| `url` | `webUrl` | |
| the synced category | `categoryPath` | Root and second-level name; a refreshed product keeps its leaf category |

## Etiquette notes

- Imperva fronts the shop. It refused the PC's foreign address and answered the phone; a 403
  stops the provider.
- supermercapy saw no rate limit in October 2026. We pace at our usual 1.2 s.

## Verification log

| Date | Check | Result |
|---|---|---|
| 2026-11-24 | `catalog/product?q=leche` from the PC, app User-Agent | 403 (Imperva) |
| 2026-11-24 | Same from the phone, then `q=platano|huevos|atun|pollo`, the menu, `catalog/product/code/7080604` and `categories=2075`, 2 s apart | 200 JSON for all; recorded as the test fixtures |
