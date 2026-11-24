# Lidl

| | |
|---|---|
| Status | **Not implemented (owner's decision, 2026-11-24).** Research kept so it is not repeated |
| Endpoint checked | `https://www.lidl.es/q/api/search?q={term}&assortment=ES&locale=es_ES&version=v2.0.0` |

## Findings (2026-11-24)

- The search endpoint answers JSON to the app's honest User-Agent.
- Its food results are only the **current weekly offers** (about 40 items in the "Food" and
  "F+V" categories). Lidl Spain sells its everyday range only in shops, with no online grocery
  catalogue: "garbanzos" found nothing, and "leche entera" found chocolates.
- So it cannot price ordinary recipe ingredients. The owner chose to skip it. Lidl products can
  still be added as manual products.

supermercapy ([third_party/supermercapy](../../third_party/supermercapy/README.md)) reaches more
of Lidl through its sitemap plus one request per product. That is too many requests for our
etiquette, and prices for grocery items are often missing there too.
