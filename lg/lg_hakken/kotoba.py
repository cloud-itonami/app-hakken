"""kotoba — AUXILIARY high-level KG / SPARQL XRPC helpers.

NOTE 2026-05-30: primary surface is now `kotoba_datomic` (datomic.transact /
datomic.q / datomic.entity / datomic.pull).  This module is kept ONLY for
queries that the Datomic Datalog dialect does not express cleanly — full
SPARQL features (DESCRIBE, CONSTRUCT, property paths, ASK aggregates).  For
ordinary entity / claim / relation traversal, use `kotoba_datomic` instead.

kotoba-server exposes KG-native endpoints backed by TieredBlockStore /
sled WAL.  The `kotobase-kg-v1` graph is registered with `Authenticated`
visibility (kotoba server.rs), so reads accept Bearer JWT; CACAO is only
required for graphs whose visibility is `Private{owner_did}`.

See 60-apps/etzhayyim-project-kotoba/crates/kotoba-server/src/kg.rs.
"""

from __future__ import annotations

import os
from typing import Any

import httpx

KOTOBA_XRPC = os.environ.get("KOTOBA_XRPC_URL", "https://kotoba.etzhayyim.com")

# Bearer JWT issued to hakken DID — required for kg.ingest_batch / kg.ingest /
# kg.delete (write) AND for kg.entity / kg.catalog / graph.sparql (read on the
# Authenticated `kotobase-kg-v1` graph).
KOTOBA_BEARER = os.environ.get("KOTOBA_BEARER", "")

# Batch size cap (kotoba-server MAX_KG_BATCH_SIZE = 1000).
KG_INGEST_BATCH_MAX = 1000


def _auth_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {KOTOBA_BEARER}"} if KOTOBA_BEARER else {}


async def kg_ingest_batch(
    client: httpx.AsyncClient,
    entities: list[dict[str, Any]],
) -> dict[str, Any]:
    """POST kg.ingest_batch. Returns {ok, subjectCids, quadCount, entityCount}.

    Each entity: {id, type?, labelJa?, labelEn?, claims:[{pred,value}],
                  relations:[{pred,dstId}], labelVec:[f32]}.
    """
    resp = await client.post(
        f"{KOTOBA_XRPC}/xrpc/com.etzhayyim.apps.kotobase.kg.ingest_batch",
        headers=_auth_headers(),
        json={"entities": entities},
    )
    resp.raise_for_status()
    return resp.json()


async def kg_ingest(
    client: httpx.AsyncClient,
    entity: dict[str, Any],
) -> dict[str, Any]:
    """POST kg.ingest (single entity). Use kg_ingest_batch for >1 entity."""
    resp = await client.post(
        f"{KOTOBA_XRPC}/xrpc/com.etzhayyim.apps.kotobase.kg.ingest",
        headers=_auth_headers(),
        json=entity,
    )
    resp.raise_for_status()
    return resp.json()


async def kg_sparql(
    client: httpx.AsyncClient,
    query: str,
    limit: int = 10_000,
    max_hops: int = 0,
) -> dict[str, Any]:
    """POST kotoba.graph.sparql. Auto-detects SELECT/DESCRIBE/CONSTRUCT/ASK.

    Returns:
      SELECT    → {form:"select",    quads:[...]}
      DESCRIBE  → {form:"describe",  quads:[...], maxHops:int}
      CONSTRUCT → {form:"construct", quads:[...]}
      ASK       → {form:"ask",       result:bool}
    """
    resp = await client.post(
        f"{KOTOBA_XRPC}/xrpc/com.etzhayyim.apps.kotoba.graph.sparql",
        headers=_auth_headers(),
        json={"query": query, "limit": limit, "maxHops": max_hops},
    )
    resp.raise_for_status()
    return resp.json()


async def kg_entity(
    client: httpx.AsyncClient,
    entity_id: str | None = None,
    qid:       str | None = None,
) -> dict[str, Any] | None:
    """GET kg.entity. Returns the entity dict, or None if not found."""
    params: dict[str, str] = {}
    if entity_id is not None: params["id"] = entity_id
    if qid       is not None: params["qid"] = qid
    resp = await client.get(
        f"{KOTOBA_XRPC}/xrpc/com.etzhayyim.apps.kotobase.kg.entity",
        headers=_auth_headers(),
        params=params,
    )
    if resp.status_code == 404:
        return None
    resp.raise_for_status()
    return resp.json().get("entity")


async def kg_catalog(
    client: httpx.AsyncClient,
    limit: int = 100,
) -> dict[str, Any]:
    """GET kg.catalog. Returns aggregate stats + recent sources."""
    resp = await client.get(
        f"{KOTOBA_XRPC}/xrpc/com.etzhayyim.apps.kotobase.kg.catalog",
        headers=_auth_headers(),
        params={"limit": limit},
    )
    resp.raise_for_status()
    return resp.json()


async def kg_delete(client: httpx.AsyncClient, entity_id: str) -> bool:
    """POST kg.delete. Returns True on success."""
    resp = await client.post(
        f"{KOTOBA_XRPC}/xrpc/com.etzhayyim.apps.kotobase.kg.delete",
        headers=_auth_headers(),
        json={"id": entity_id},
    )
    return resp.is_success
