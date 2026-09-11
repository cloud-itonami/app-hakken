# app-hakken

**A Clojure (`.cljc`) LangGraph pipeline that compares branded-product prices against
OEM/supplier listings and writes both into a kotoba knowledge graph — plus the
declaration files describing the service it is meant to become.** 39 tracked files:
33 preserved from the extraction, 2 added by it, and 4 of documentation (this file, the
quickstart, and two verifiers).
The pipeline has a passing test suite and runs offline against stubs; nothing in this
repository is deployed, and the host it claims to serve does not resolve.

The name says "発見 / discovery" and nothing else, so: this is the **ingest half** of a
product-discovery system. It finds price gaps and records them. It does not sell
anything.

Read this file before `CLAUDE.md`, `PROJECT.jsonld` or `kotodama.jsonld`. Those three
were written inside the `etzhayyim/root` monorepo before this directory was extracted,
and they describe a larger system than the one that arrived here. This file says which
parts of them you can act on today.

- Identity: `README.edn` (`:kind :app`, role `:product-discovery-application`,
  business layer `cloud-itonami/hakken`)
- Provenance: `migration.edn` — extracted from `etzhayyim/root@e6439bcb`, path
  `60-apps/etzhayyim-project-hakken`, 33 files / 105,537 bytes. The two files added by
  the extraction are `README.edn` and `migration.edn`.
- Running the tests: **`docs/operator-quickstart.md`**

## What is actually here

```
lg-clj/     the only runnable thing in the repo — 16 .cljc source, 2 .cljc test
lg/         3 Python files + .gitignore, a remnant of what lg-clj was ported FROM
kotoba/     2 TypeScript files, an unbuilt @etzhayyim/sdk ingest reference
CLAUDE.md PROJECT.jsonld   descriptions of the intended system
kotodama.jsonld            actor / deployment manifest
OWNERS README.edn migration.edn   identity and provenance
```

Measured at `4cf14f1`:

| Part | Files | Bytes | State |
|---|---:|---:|---|
| `lg-clj/src` (11 graph nodes + edn, graph, server, xrpc, kotoba-datomic) | 16 | 48,414 | tests pass |
| `lg-clj/test` (46 `deftest`) | 2 | 23,688 | 46 tests / 153 assertions, 0 failures |
| `lg/` (Python) | 3 | 12,403 | not runnable — see below |
| `kotoba/` (TypeScript) | 2 | 9,921 | never built here; no lockfile, no build script |

## The three implementations, and which one is real

Only `lg-clj/` is exercised. The other two are context, not code you can run.

`lg/` is the **remnant** of the Python pipeline. `lg-clj` describes itself as a
"faithful port" and names four Python originals — `lg/lg_hakken/graph.py`,
`lg/lg_hakken/edn.py`, `lg/lg_hakken/kotoba_datomic.py` and `tests/test_edn_and_cid.py`.
**None of those four are in this repository.** What survived extraction is
`lg/lg_hakken/kotoba.py`, `lg/lg_hakken/__init__.py` and `lg/wasm/agent.py`. So the
port's stated oracle is absent: you cannot check the port against the thing it says it
is a port of, here.

`kotoba/src/` is a TypeScript ingest reference (`types.ts`, `ingest.ts`) against
`@etzhayyim/sdk`. There is no lockfile and `kotoba/package.json` has no build script.

## What the declaration files claim that is not true in this repository

Each of these was measured, not inferred. They are left in place rather than edited —
they are the specification to build against, and rewriting them would destroy the
contract while appearing to fix the description.

- **`kotodama.jsonld` points at an entrypoint that does not exist.**
  `component.path` is `src/app.ts`. There is no top-level `src/` directory at all.
- **`PROJECT.jsonld` lists Phase 1 as delivering "4 `com.etzhayyim.apps.hakken.*`
  lexicons (this commit)".** No lexicon files are tracked in this repository.
- **The host it serves does not exist.** `hakken.etzhayyim.com` — the single entry in
  `kotodama.jsonld` `routes`, and the base of its `did:web:hakken.etzhayyim.com`
  identifier — has **no DNS A record (NXDOMAIN)**. The declared backend dependency
  `dispatcher.etzhayyim.com` is also NXDOMAIN. `kotoba.etzhayyim.com` and
  `atproto.etzhayyim.com` do resolve, and `kotoba.etzhayyim.com` is the default write
  target in `lg-clj/src/lg_hakken/kotoba_datomic.cljc`.
- **`CLAUDE.md` states a boundary the code does not keep.** It says the fulfillment
  tail "is NOT part of the etzhayyim ingest surface" and names `okaimono_register`,
  dropship, `import_order` and `tsukuru_order` as functions that stay elsewhere.
  `lg-clj/src/lg_hakken/graph.cljc` wires all five of those nodes
  (`okaimono_dropship`, `import_order`, `tsukuru_order`, `okaimono_register`,
  `social_announce`) into `build-discovery` as a compiled graph. The port is faithful
  to the vendor pipeline, which is wider than the boundary the document draws.
- **`CLAUDE.md` has had its organisation names collapsed.** "etzhayyim" appears 39
  times, including on both sides of a sentence that has to name two different parties
  to mean anything: the functions "move to the etzhayyim product front" while the
  regulated tail "stays a etzhayyim function". The same collapse makes its NSID note
  self-contradictory, where three supposedly distinct prefixes all render as
  `com.etzhayyim.*`. Read that section as unreliable.

## Custody: this repository has diverged from its extraction record

`migration.edn` records the source tree as `dd2842a9…`, 33 files / 105,537 bytes, and
that record is **correct** — `docs/verify-custody.cljs --origin` confirms that exact
tree still exists on GitHub at `etzhayyim/root@e6439bcb:60-apps/etzhayyim-project-hakken`.

The repository no longer matches it. Reconstructed today the preserved tree is
`71b308d6…` at 112,333 bytes. The divergence is attributable to a single commit:

| Commit | Preserved tree | Bytes | Custody |
|---|---|---:|---|
| `f33f90e` extract hakken app from root | `dd2842a9…` | 105,537 | passes |
| `84010a0` update repository identity | `dd2842a9…` | 105,537 | passes |
| `ae7298f` send x-internal-trust when configured | `71b308d6…` | 112,333 | **fails** |

`ae7298f` is a deliberate, documented improvement (ADR-2608124000) that edited two
preserved files, `kotoba_datomic.cljc` and `edn_and_cid_test.cljc`. Nothing was wrong
with the change; what is missing is that `migration.edn` was never updated to say the
repository had intentionally left its extraction. **`docs/verify-custody.cljs` exits 1
here today, and that is the verifier working, not a broken repository.** Resolving it
is an owner decision — either re-pin the record deliberately, or keep the divergence
visible — so this round documents it rather than picking one.

## Verifiers

Both are offline and deterministic, and both exit **3** when they cannot determine an
answer, so "could not measure" is never reported as "passed".

```bash
kbb --backend sci docs/verify-custody.cljk        # exit 1 today, by the divergence above
kbb --backend sci docs/verify-custody.cljk --origin   # also checks the source tree on GitHub
kbb --backend sci docs/verify-docs-claims.cljk    # exit 0 — the claims on this page still hold
```

`verify-docs-claims.cljs` pins the assertions of *absence* made above. Those are the
claims that rot silently once someone starts implementing: the day a `src/app.ts` or a
lexicon directory appears, this page becomes wrong and nothing else would notice. When
it goes red, fix this README — not the verifier.
