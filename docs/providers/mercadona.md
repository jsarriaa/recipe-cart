# Mercadona

| | |
|---|---|
| Status | **Verified 2026-11-18** with plain HTTPS requests (curl), no cookies, no login |
| Base URL | `https://tienda.mercadona.es/api/` |
| Image CDN | `https://prod-mercadona.imgix.net/images/` |
| Location model | Postal code → **warehouse** code (`wh`), passed as a query parameter |
| Auth | None for catalogue and products |
| Bot protection | Akamai cookies (`_abck`, `bm_sz`) are set, but plain requests with a descriptive User-Agent (`CocinaParaLechugones/0.0 (personal use; Android)`) got `200` |

All responses are JSON. Prices are decimal **strings** in euros (`"1.50"`). Sizes are numbers in
the unit named by `size_format` (`kg`, `l`, `ud`).

## Common query parameters

| Param | Example | Meaning |
|---|---|---|
| `lang` | `es` | Response language (`es`, `en`, `ca`…). Always send `es` |
| `wh` | `vlc1` | Warehouse. Determines assortment and price. Without it the server uses a default |

## Endpoints

### 1. Category tree

`GET /api/categories/?lang=es&wh={wh}`

```json
{
  "count": 26, "next": null, "previous": null,
  "results": [
    {
      "id": 12, "name": "Aceite, especias y salsas", "order": 7, "is_extended": false,
      "categories": [
        { "id": 112, "name": "Aceite, vinagre y sal", "order": 7, "layout": 1, "published": true, "is_extended": false },
        { "id": 115, "name": "Especias", "order": 7, "layout": 1, "published": true, "is_extended": false }
      ]
    }
  ]
}
```

On 2026-11-18: **26 top-level** categories and **152 second-level** categories.

### 2. Products of a second-level category

`GET /api/categories/{id}/?lang=es&wh={wh}`, where `{id}` is a **second-level** id (e.g. `115`).

The response holds third-level groups (`categories[]`), each with its full `products[]`, plus
`next_category` (the next second-level category, or `null`).

```json
{
  "id": 115, "name": "Especias", "layout": 1, "published": true, "is_extended": false,
  "next_category": { "id": 116, "name": "Mayonesa, ketchup y mostaza" },
  "categories": [
    {
      "id": 425, "name": "Hierbas",
      "products": [
        {
          "id": "5598",
          "slug": "oregano-hacendado-bote",
          "display_name": "Orégano Hacendado",
          "packaging": "Bote",
          "published": true,
          "share_url": "https://tienda.mercadona.es/product/5598/oregano-hacendado-bote",
          "thumbnail": "https://prod-mercadona.imgix.net/images/20a564cf97b2b9662bb7727d6008ed69.jpg?fit=crop&h=300&w=300",
          "categories": [{ "id": 12, "name": "Aceite, especias y salsas", "level": 0, "order": 7 }],
          "unavailable_from": null,
          "price_instructions": {
            "unit_price": "1.00",
            "unit_size": 0.025,
            "size_format": "kg",
            "bulk_price": "40.00",
            "reference_price": "4.000",
            "reference_format": "100 g",
            "total_units": null,
            "unit_name": null,
            "drained_weight": null,
            "approx_size": false,
            "is_pack": false,
            "pack_size": null,
            "selling_method": 0,
            "previous_unit_price": null,
            "price_decreased": false,
            "iva": 10,
            "tax_percentage": "10.000"
          }
        }
      ]
    }
  ]
}
```

Trimmed to the fields we use (groups also carry `order`, `layout`, `published`, `image` and
`subtitle`). The values are copied from the 2026-11-18 response (`wh=vlc1`).

### 3. Product detail

`GET /api/products/{id}/?lang=es&wh={wh}`

Everything in the listing item plus `ean`, `brand`, `origin`, `details` (with `brand`,
`legal_name`, `description`, `usage_instructions`, `storage_instructions`, `suppliers`),
`photos[]` (`zoom` 3600 px, `regular` 600 px, `thumbnail` 300 px), `nutrition_information`
(`ingredients`, `allergens`, which contain HTML `<strong>`), `is_bulk`, `is_variable_weight`.

**`brand` is only in the detail response**, not in category listings.

### 4. Postal code → warehouse

`PUT /api/postal-codes/actions/change-pc/` with body `{"new_postal_code": "28001"}` and
`Content-Type: application/json`.

Response `200` with body `{"warehouse_changed": false}`, and the warehouse in the **response
headers**:

```
x-customer-wh: mad3
x-customer-pc: 28001
```

The app calls this once when the user sets or changes the postal code, stores `wh`, and then
passes it as `wh=` on every request. No cookie is needed afterwards.

### 5. Search (observed, not used)

The website searches with Algolia (application id and a public search-only key embedded in the
web bundle `/v<build>/index-<hash>.js`, index name prefix `products_prod`). The bundle also has a
kill-switch flag (`WEB_SEARCH_ALGOLIA_KILLSWITCH`).

**We do not use it**, and we do not copy that key into this repository: the full catalogue is
small enough to sync and search locally with Room FTS, which also works offline.

## Field mapping → `ProviderProduct`

| Mercadona field | `ProviderProduct` | Notes |
|---|---|---|
| `id` (string) | `productId` | |
| `display_name` | `name` | |
| `brand` (detail) | `brand` | In listings, infer the brand from the name: if it contains a store brand as a whole word, `brandKind = STORE` |
| — | `brandKind` | `STORE` if the brand is in the list below; `UNBRANDED` if no brand is detectable (fresh produce, meat trays); else `NATIONAL` |
| `packaging` | `packaging` | Observed: `Paquete`, `Bandeja`, `Bote`, `Sobre`, `Pieza`, `1/2 Pieza`, `Tarrina`, `Malla`, `Manojo`, `Caja`, `Vaso`, `Granel`, `Pack-2`, `Pack-3`, `Pack-4`, `null` |
| `price_instructions.unit_size` + `size_format` | `netQuantity` | `0.36` + `kg` → 360 g; `l` → ml; `ud` → ud |
| `total_units` + `unit_name` | `unitCount`, `unitCountUnit` | `6` + `ud.` → 6 ud. `unit_name` observed: `ud.`, `lonchas` (→ `loncha`), `rebanadas` (→ `rebanada`), `porciones`, `sobres`, `quesitos`, `tarrinas` (→ `ud`). `packaging = Pieza` with no `total_units` → 1 ud |
| `drained_weight` (kg) | `drained` | Mozzarella fresca 250 g → `0.125` |
| `approx_size` | `approx` | `true` for variable-weight items (meat trays, single fruit) |
| `unit_price` | `price` | Price of **one package**. Parse the decimal string exactly into cents |
| `reference_price` + `reference_format` | `referencePrice`, `referenceUnit` | `reference_format` is `kg`, `l`, `ud` or `100 g` (spices). **It may use the drained weight** (mozzarella: unit 0,90 €, `bulk_price` 3,60 €/kg on the net weight, `reference_price` 7,20 €/kg on the drained weight) |
| `bulk_price` | — | Price per `size_format` unit, on the net weight. Not stored; recomputed from price and size |
| `thumbnail` | `imageUrl` | imgix: change the `w`/`h` query params for other sizes |
| `share_url` | `webUrl` | |
| `categories[0].name` + group names | `categoryPath` | |
| `published`, `unavailable_from` | `available` | `published = false` or `unavailable_from` in the past → unavailable |
| `previous_unit_price`, `price_decreased` | — | Offer info. Could be shown later |
| `ean` (detail) | — | Not needed in v1 (possible barcode scanning later) |
| `is_pack`, `pack_size` | — | Meaning unclear (seen on bagged fruit and on mini tortillas). Ignored, see open questions |
| `selling_method` | — | Only `0` observed. Treat any other value as variable weight and log it |

Example mappings (vlc1, 2026-11-18):

| Product | id | Package | Price | Mapped |
|---|---|---|---|---|
| Maxi tortillas de trigo Hacendado | 80870 | Paquete, 0.36 kg, 6 ud. | 1,50 € | 360 g, 6 ud, STORE |
| Contramuslos de pollo deshuesados y sin piel | 2789 | Bandeja, 0.55 kg, approx | 3,74 € | ≈550 g, UNBRANDED, approx |
| Aguacate | 3830 | Pieza, 0.2 kg, approx | 1,12 € | ≈200 g, 1 ud, UNBRANDED, approx |
| Tomates cherry | 69990 | Bandeja, 0.5 kg | 2,20 € | 500 g, UNBRANDED |
| Queso rallado mozzarella pizza-Roma de vaca Hacendado | 51110 | Paquete, 0.2 kg | 1,60 € | 200 g, STORE |
| Mozzarella fresca de vaca Hacendado | 51050 | Paquete, 0.25 kg, drained 0.125 | 0,90 € | 250 g, drained 125 g, STORE |
| Sazonador para fajitas Hacendado sabor tex-mex | 23353 | Sobre, 0.017 kg | 0,90 € | 17 g, STORE |

## Store brands

`Hacendado` (food), `Deliplus` (personal care), `Bosque Verde` (household), `Compy` (pets).
Fresh produce and meat trays usually carry no brand and map to `UNBRANDED`.

## Catalogue sync plan

1. `GET /api/categories/` (1 request).
2. For each second-level category in the selected top-level categories, `GET /api/categories/{id}/`
   (one request each, sequential, at the rate limit).
3. Default selection excludes non-food top-level categories: 20 Cuidado facial y corporal,
   21 Cuidado del cabello, 22 Maquillaje, 23 Fitoterapia y parafarmacia, 25 Mascotas,
   26 Limpieza y hogar, and the non-food parts of 24 Bebé. That leaves about 110 requests,
   ≈ 2–3 minutes at 1 request / 1.2 s.
4. Products seen in the sync are upserted. Products of synced categories that were not seen are
   marked `available = false`.

Price refresh for specific products uses `GET /api/products/{id}/` per product. If more than
~20 products in one category need refreshing, refresh the whole category instead (1 request).

## Etiquette notes

- `Cache-Control: no-cache` on API responses. HTTP caching is useless, so Room is the cache.
- No rate-limit headers were observed. We use the shared default (1 request / 1.2 s) anyway.
- The responses set Akamai cookies (`_abck`, `bm_sz`). We do not store or replay them. If
  Mercadona starts answering `403`, the provider stops (no retries, no evasion) and the app
  works from cache.

## Open questions

- Exact meaning of `is_pack` / `pack_size` (bagged fruit, mini tortillas `pack_size: 0.15`).
- How `Pack-2` / `Pack-3` packaging relates to `unit_size` (is the size per item or per pack?).
  Check with yoghurt multipacks in phase 3 and add fixtures.
- Whether prices really differ between warehouses. On 2026-11-18, product 80870 cost 1,50 € in
  both `vlc1` and `mad1`, and with no `wh` at all.
- Which warehouse the server uses when `wh` is missing.

## Verification log

| Date | Check | Result |
|---|---|---|
| 2026-11-18 | `GET /api/categories/?lang=es&wh=vlc1` | 200, 26 top-level, 152 second-level categories |
| 2026-11-18 | `GET /api/categories/{115,60,38,27,29,53,56}/` | 200, structure as above, 342 products in total |
| 2026-11-18 | `GET /api/products/23353/` and `/api/products/80870/` (`vlc1`, `mad1`, no `wh`) | 200, detail fields as above, same price everywhere |
| 2026-11-18 | `PUT /api/postal-codes/actions/change-pc/` with 28001 | 200, `x-customer-wh: mad3` |
| 2026-11-18 | Descriptive User-Agent `CocinaParaLechugones/0.0 (personal use; Android)` | 200 |
| 2026-11-18 | `PUT /api/postal-codes/actions/change-pc/` with 08001 (the app's suggested default) | 200, `x-customer-wh: bcn1` |
