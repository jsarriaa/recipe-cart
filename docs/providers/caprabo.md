# Caprabo

| | |
|---|---|
| Status | **Verified 2026-11-24 from the phone** with the app's User-Agent: 200. The development PC gets a 403, as with Eroski |
| Base URL | `https://www.capraboacasa.com/` |
| Images | `https://www.capraboacasa.com//images/{id}.jpg` |
| Location model | None. An anonymous visitor is pinned to shop 8284 (Caprabo's online platform): **Catalan prices**. The postal code is never sent |
| Module | `:provider:tapestry`, shared with [Eroski](eroski.md) |
| Source | Ported from [supermercapy](https://github.com/jtayped/supermercapy) (MIT): `docs/stores/caprabo.md` and the shared Tapestry parser. Its `search.json` and `product.html` are test fixtures |

Caprabo belongs to the Eroski group and runs the same Apache Tapestry build: the same paths,
markup, sync plan and field mapping, all described in [eroski.md](eroski.md). What differs:

- **The catalogue is its own.** Regional products carry their own ids and prices (Caprabo sells
  `18581678`, a Catalan semi-skimmed milk at 0,99 €, where Eroski sells `18672311`, a Basque one
  at 1,06 €). Product ids of one shop mean nothing at the other, so they are separate providers
  (`caprabo`, `eroski`).
- **Minimum quantities.** Some products are sold in sixes (`minGranel` 6); the price is still
  per piece.

## Verification log

| Date | Check | Result |
|---|---|---|
| 2026-11-24 | Search fragment `q=leche` from the phone, app User-Agent | 200 |
| 2026-11-24 | `q=platano` and `q=18581678` (an id) from the phone | 200; the id finds nothing |
| 2026-11-24 | Full catalogue sync from the phone (debug app), search `leche`, photos | 1657 products, sizes, prices and photos correct. |
