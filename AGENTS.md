# etzhayyim-project-hakken — OEM Product Discovery Ingest (etzhayyim)

**hakken.etzhayyim.com** (発見) — AI-First OEM product-discovery ingest surface. Compares
branded-product prices against OEM/supplier candidate listings and ingests both into the
kotoba product knowledge graph as on-chain, content-addressed records.

SSoT: `90-docs/adr/2606011700-hakken-etzhayyim-migration-override.md`
(overrides same-day `2606011400` vendor-keep verdict per user direction 2026-06-01).
Origin design: `2605270000-hakken-oem-product-discovery-bmc-lean.md` (vendor).

## One-line definition

Hakken ≝ TrendScan × KotobaDatalog[gap] × SupplierScraper[aliexpress|alibaba|1688] × KaimonoReview[5-axis] × Ingest[on-chain kotoba KG]

## Architecture

```
nanoid:   h4kk3n0x
did:      did:web:hakken.etzhayyim.com
runtime:  k8s-langserver (LangGraph) — etzhayyim Murakumo fleet (planned Phase 2)
storage:  kotoba product KG (EAVT, IPFS-pinned) — NO RisingWave (etzhayyim kotoba substrate)
write:    @etzhayyim/sdk e.write() → PDS XRPC createRecord → MST + IPFS + Base L2 anchor
status:   Phase 1 scaffold + ingest-core kotoba (2026-06-01)
```

## etzhayyim boundary (ADR-2606011400 override)

| 3-axis | Vendor evidence (why hakken was vendor-confirmed) | etzhayyim resolution |
|---|---|---|
| Liability | resale/import operator duty | **ingest only** at etzhayyim — no order placement / fulfillment at this surface |
| Custody | supplier master, marketplace order data | public kotoba KG (product/supplier facts are public OSINT-grade) |
| Settlement | Stripe product creation, marketplace payment | **deferred to etzhayyim function** via consent capability (Phase 3) — etzhayyim takes no payment |

Per ADR-2606011400 (Consensys pattern) the **product-discovery / supplier-search functions
move to the etzhayyim product front**; the **regulated fulfillment + payment tail
(okaimono_register dropship, import_order, tsukuru_order, Stripe) stays a etzhayyim function**
consumed through consent capability. This app ports the ingest front only.

## NSID namespace (com.etzhayyim.*, user-directed 2026-06-01)

hakken is etzhayyim-native (no legacy etzhayyim lexicon existed — vendor wrote kotoba datoms
directly), so both lexicon ids and record write-path collections use `com.etzhayyim.apps.hakken.*`.

> Note: the established record-NSID authority elsewhere in etzhayyim/root is `com.etzhayyim.*`
> (consent/council/encrypted/esign). `com.etzhayyim.*` is otherwise used for launchd/system
> labels. `com.etzhayyim.*` was chosen here by explicit operator direction (reverse-DNS of
> etzhayyim.com); revisit if the org standardises on `com.etzhayyim.*` for record NSIDs.

## Record collections (write path)

| Collection | Record | Lexicon (procedure) |
|---|---|---|
| `com.etzhayyim.apps.hakken.brandedProduct` | branded reference product + observed price | `com.etzhayyim.apps.hakken.ingestProduct` |
| `com.etzhayyim.apps.hakken.supplierCandidate` | OEM/supplier candidate (aliexpress\|alibaba\|1688) | `com.etzhayyim.apps.hakken.ingestSupplierCandidate` |

Reads: `com.etzhayyim.apps.hakken.listProducts` / `listSupplierCandidates`.

## Pipeline (Phase 2 target)

```
trend_scan → gap_analysis (kotoba Datalog) → supplier_search (aliexpress/1688)
  → quality_eval (kaimono-review 5-axis) → ingest (kotoba e.write brandedProduct + supplierCandidate)
```

Phase fulfillment (`[dropship | import | oem]` + okaimono register + social announce) is NOT
part of the etzhayyim ingest surface — see Phase 3 (etzhayyim consent capability).

## kotoba reference

`kotoba/src/` — `@etzhayyim/sdk` ingest reference implementation:
- `types.ts` — BrandedProduct / SupplierCandidate record shapes + IO types
- `ingest.ts` — `ingestProduct` / `ingestSupplierCandidate` (idempotent upsert by rkey) +
  `listProducts` / `listSupplierCandidates`

Persistence pattern (mirrors tsukuru kotoba Phase 2):
`createKyselyDb().insertInto("vertex_hakken_*")` → `e.write({ collection, record })`.

## Cross-actor

| Actor | Use | Boundary |
|---|---|---|
| kotoba.etzhayyim.com | product KG storage (EAVT, IPFS) | etzhayyim |
| kaimono-review | 5-axis quality scoring (quality_eval) | etzhayyim/vendor TBD |
| okaimono | D2C sales channel (fulfillment) | **etzhayyim function** (Settlement) |
| tsukuru | Ph3 OEM manufacturing | etzhayyim (tsukuru already migrated) |

## ADR

- `2606011700` — hakken etzhayyim migration (override of 2606011400)
- `2606011400` — Consensys pattern (product front / infra back) — overridden for hakken move
- `2605172000` / `2605172400` — kotoba substrate + 3-axis split rule
