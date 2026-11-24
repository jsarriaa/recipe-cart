# Aldi

| | |
|---|---|
| Status | **Verified 2026-11-24** from the development PC with the app's User-Agent (HTML pages, 200). Not yet checked from the phone |
| Base URL | `https://www.aldi.es/` |
| Images | `https://s7g10.scene7.com/is/image/aldinord/…` (absolute URLs in the records) |
| Location model | None. Península shelf prices |
| Source | Ported from [supermercapy](https://github.com/jtayped/supermercapy) (MIT, [third_party/supermercapy](../../third_party/supermercapy/README.md)): `docs/stores/aldi.md`, `aldi/models.py` (`next_data`, `api_data`, `parse_category_page`, `parse_price`) |

Aldi runs **no online shop**. www.aldi.es shows the fixed assortment and the weekly offers
with shop prices, and nothing can be ordered. The prices are the ones on the shelf.

The site's Next.js pages embed their data in a `<script id="__NEXT_DATA__">` JSON document.
The site itself reads products from an Algolia index with a search key shipped in its
JavaScript. **We never use that key** (etiquette rule 7). Instead we read what the pages already
embed: every subcategory page arrives with that category's search results server-rendered,
up to 1,000 records.

Aldi prices by region: península, Balearic Islands and Canary Islands. The island pages live
under `/bal/` and `/can/`, which `robots.txt` excludes, so the app offers península prices only.

## Endpoints (pages)

| Page | Used for | Embedded data |
|---|---|---|
| `GET productos.html` | Top-level categories | `props.pageProps.apiData` → `PRODUCT_MGNL_CATEGORY_CHILDREN_GET.res[]` |
| `GET productos/{top}.html` | Its subcategories (top-level pages list no products) | Same children list |
| `GET productos/{top}/{leaf}.html` | Products of one subcategory | `props.pageProps.algoliaState.initialResults.<index>.results[0].hits[]` |
| `GET producto/p-{id}.html` | Price refresh of one product. The slug is not checked, so `p-{id}` works; an unknown id answers 404 | `apiData` → `PRODUCT_DETAIL_GET.res.products[0]` |

`apiData` is a list of `[name, {"res": …}]` pairs, on some pages encoded as a JSON string. A
child node has `reference.path` (`/productos/despensa/pasta`), `navigationTitle`,
`categoryKey`, `hideInCategory` (skipped, as the site does) and `marketingCategory`.

Top-level categories skipped:
- marketing ones: Juguetes, Productos gourmet, Dulces de Navidad and other seasonal ones;
- non-food ones: `limpieza-y-hogar`, `cuidado-personal`, `bebe-e-infantil`, `mascotas`, `marcas`.

## Field mapping

| Record field | `ProviderProduct` | Notes |
|---|---|---|
| `objectID` | `key.productId` | Digits only |
| `name` | `name` | |
| `brandName` | `brand`, `brandKind` | `MILSANI®`: the registered sign is removed and the name title-cased. Own brands are listed below; others are national; none means unbranded |
| `currentPrice.priceValue` | `price` | About 200 fresh items have no price at all: skipped. A record may already carry a price that starts the next day (supermercapy notes this); the app shows it as it is |
| `salesUnit` | `net`, `unitCount`, `approx` | `1 l unidad`, `340 g unidad`, `pack de 6 x 200 ml` (1200 ml, 6 pieces), `114 g aprox. unidad` (approximate). Falls back to the name (`Pera rocha 1 kg`) |
| `salesUnit` = `precio kg (a granel)` | `net` = 1 kg, approximate | Loose food priced per kilo |
| `currentPrice.basePrice[0]` | `referencePrice`, `referenceFormat` | Scales: `kg`, `g` and `100-g` all mean per kilo (as supermercapy found); `l`; `100-ml`; `unidad`. Packs of exactly 1 kg or 1 l carry none: the price itself is used |
| `assets[type=primary].url` | `imageUrl` | |
| `productSlug` | `webUrl` | `https://www.aldi.es/producto/{slug}.html` |
| `isAvailable` | `available` | |

## Store brands

Milsani, Myvay, Gutbio, Bio, Barissimo, Choceur, Cucina, Cucina Nobile, Special, Gourmet,
Olearia del Olivar, Olearia de Semillas, La Villa, Golden Seafood, Almare, Asia Green Garden,
Casa Morando, Lyttos, Snack Day, Sweet Valley, Belbake, Rio d'Oro, River, Moser Roth, Le Gusto,
Mamia, Tandil, Lacura. The list is incomplete: complete it when the catalogue shows other own
brands.

## Catalogue sync plan

- One overview request, 15 food top-level pages and 78 subcategory pages (counted 2026-11-24):
  about **94 requests**, about 2 minutes at 1 request per 1.2 s.
- Each page is 220 to 340 kB of HTML, so a full sync downloads about 25 to 30 MB. Only the
  embedded JSON is kept.

## Etiquette notes

- No bot protection was seen (supermercapy saw none either, nor a rate limit).
- `robots.txt` allows `/productos/` and `/producto/`. It disallows `/bal/`, `/can/`, `/mds/`,
  `/*?*filters` and `/*?*jobId=`, none of which are used.
- No search key, no Algolia request.

## Verification log

| Date | Check | Result |
|---|---|---|
| 2026-11-24 | `productos.html`, the 16 food top-level pages and three subcategory pages, from the PC, app User-Agent, 1.5 s apart | 200 HTML with `__NEXT_DATA__`; 30, 59 and 25 records |
| 2026-11-24 | `producto/p-80500.html` (placeholder slug) and `producto/p-1.html` | 200 with the record; 404 |
