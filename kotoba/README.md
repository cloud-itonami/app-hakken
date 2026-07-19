# hakken kotoba

Phase 1 ingest-core reference implementation of hakken on the etzhayyim substrate.

Per [ADR-2606011700](../../../90-docs/adr/2606011700-hakken-etzhayyim-migration-override.md),
hakken's product-discovery **ingest front** migrates from vendor's kotoba-datom + RisingWave
`vertex_hakken_*` writes to the etzhayyim kotoba + on-chain substrate
([ADR-2605172000](../../../90-docs/adr/2605172000-etzhayyim-kotoba-substrate.md)), overriding
the same-day vendor-keep verdict of
[ADR-2606011400](../../../90-docs/adr/2606011400-consensys-pattern-etzhayyim-product-etzhayyim-infra-vendor.md)
per explicit user direction 2026-06-01.

## Scope

This package implements the **ingest core** as reference — the part that is on-chain-clean
(no payment, no custody beyond public product/supplier facts):

| Module | Commands |
|---|---|
| ingest | `ingestProduct`, `ingestSupplierCandidate` |
| read | `listProducts`, `listSupplierCandidates` |

## Out of scope (etzhayyim function, Settlement axis)

Phase fulfillment is **deliberately NOT here** and remains a etzhayyim vendor function consumed via
consent capability (ADR-2606011400):

- `okaimono_register` (Ph1 dropship) + Stripe product creation
- `import_order` (Ph2 Alibaba small-lot import)
- `tsukuru_order` (Ph3 OEM) — tsukuru itself is already etzhayyim-migrated (ADR-2605202800)

## Pattern translation

| Vendor (`hakken.etzhayyim.com`) | etzhayyim (`hakken.etzhayyim.com`) |
|---|---|
| `createKyselyDb().insertInto("vertex_hakken_*").values({...})` | `e.write({ collection, record, rkey })` |
| kotoba `kg.ingest` datom (id = upsert key) | `e.write({ rkey: slug \| itemId })` (idempotent upsert) |
| `weight_kg` float | `weightG` integer (× 1000) — AT Lexicon has no float |
| `rating` float | `ratingMilli` integer (× 1000, 0-5000) |
| RisingWave MV read | `e.read({ collection, cursor, limit })` + app-layer filter |

## NSID namespace

`com.etzhayyim.apps.hakken.*` (operator-directed reverse-DNS of etzhayyim.com, 2026-06-01).
hakken had no legacy etzhayyim lexicon (vendor wrote kotoba datoms directly), so the namespace is
native here. See the app `CLAUDE.md` note on `com.etzhayyim.*` vs the `com.etzhayyim.*`
record-NSID convention used elsewhere in the repo.
