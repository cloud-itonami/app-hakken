"""hakken phase_promotion — kotoba WASM Python LangGraph component.

Single-node LangGraph that promotes SKUs through the 3-tier lifecycle
(dropship → import → oem) based on Datalog queries against the kotobase-kg-v1
graph.  Runs entirely inside the kotoba pod via `kotoba_wasm_run`; replaces
the previous K8s LangServer pod plan for `phase_promotion_graph`.

Build:
    cd 60-apps/etzhayyim-project-kotoba
    ./scripts/build-pywasm.sh \
        ../etzhayyim-project-hakken/lg/wasm/agent.py \
        -o ../etzhayyim-project-hakken/lg/wasm/hakken-phase-promotion.wasm

Load + run via MCP:
    kotoba_wasm_run wasm=<base64 hakken-phase-promotion.wasm>

Reads (datomic.q / Datalog EDN) and writes (datomic.transact) flow through
`kotoba:kais/kqe` host imports — no HTTP, no `httpx`, fully sandboxed.
"""

from typing import TypedDict

import wit_world

from kotoba_langgraph import (
    END,
    START,
    KotobaCheckpointer,
    StateGraph,
    handle_invoke,
)
from kotoba_langgraph._cbor import dumps as cbor_dumps, loads as cbor_loads

# Quad helper imported lazily inside nodes (avoids hard ImportError in dev/test
# environments where wit_world.imports.kqe isn't available yet).


GRAPH_NAME = "kotobase-kg-v1"


# ── State ────────────────────────────────────────────────────────────────────


class PromotionState(TypedDict, total=False):
    """Run-scoped state.  No per-run inputs — entire workload comes from KQE.

    Fields:
      promoted: list of {sku, from, to} dicts written during this run
      errors:   list of error strings (one per failed SKU promotion)
    """

    promoted: list
    errors: list


# ── KQE helpers ──────────────────────────────────────────────────────────────


def _kqe():
    """Resolve the kqe import lazily so this module can be syntax-checked
    outside the WASM build environment."""
    from wit_world.imports import kqe  # noqa: WPS433  — lazy WASM import

    return kqe


def _cbor_text(s: str) -> bytes:
    return cbor_dumps({"Text": s})


def _cbor_decode(raw: bytes):
    """Decode a QuadObject CBOR variant into a primitive Python value."""
    obj = cbor_loads(bytes(raw))
    if isinstance(obj, dict):
        for key in ("Text", "Integer", "Float", "Cid"):
            if key in obj:
                return obj[key]
    return obj


def _assert_claim(sku: str, predicate: str, value: str) -> None:
    """Assert `(sku, kg/claim/<pred>, <value>)` via KQE.  Card-one attribute
    semantics are enforced server-side: prior values for `kg/claim/phase`
    are replaced atomically by datomic engine on commit."""
    kqe = _kqe()
    kqe.assert_quad(
        kqe.Quad(
            graph=GRAPH_NAME,
            subject=sku,
            predicate=f"kg/claim/{predicate}",
            object_cbor=_cbor_text(value),
        )
    )


def _datalog_select_phase(phase: str) -> list[dict]:
    """Run `[:find ?sku ?okaimonoId ?orders ?rr (?gmv ?mp) :where ...]` for a
    given phase and return parsed binding rows.  Format mirrors the previous
    httpx-backed `dm_q` helper."""
    kqe = _kqe()
    if phase == ":phase/dropship":
        src = (
            "[:find ?sku ?okaimonoId ?orders ?rr "
            ":where "
            f'[?sku :kg/claim/phase "{phase}"] '
            "[?sku :kg/claim/dropshipOrders ?orders] "
            "[?sku :kg/claim/returnRate ?rr] "
            "[?sku :kg/claim/okaimonoId ?okaimonoId]]"
        )
        cols = ("sku", "okaimonoId", "orders", "rr")
    elif phase == ":phase/import":
        src = (
            "[:find ?sku ?okaimonoId ?gmv ?rr ?mp "
            ":where "
            f'[?sku :kg/claim/phase "{phase}"] '
            "[?sku :kg/claim/monthlyGmv ?gmv] "
            "[?sku :kg/claim/returnRate ?rr] "
            "[?sku :kg/claim/marginPotential ?mp] "
            "[?sku :kg/claim/okaimonoId ?okaimonoId]]"
        )
        cols = ("sku", "okaimonoId", "gmv", "rr", "mp")
    else:
        return []

    rows: list[dict] = []
    try:
        quads = kqe.query(src)
    except Exception as exc:  # noqa: BLE001 — pass through to caller-visible error
        raise RuntimeError(f"kqe.query failed for phase={phase}: {exc}") from exc

    # `kqe.query` returns quads matching the find pattern.  The decoded value
    # of each quad's object field maps positionally to the find variables.
    # We chunk by len(cols) to recover row tuples.
    chunk: list = []
    for q in quads:
        chunk.append(_cbor_decode(bytes(q.object_cbor)))
        if len(chunk) == len(cols):
            rows.append(dict(zip(cols, chunk)))
            chunk = []
    return rows


def _to_int(v) -> int:
    try:
        return int(str(v))
    except Exception:  # noqa: BLE001
        return 0


def _to_float(v) -> float:
    try:
        return float(str(v))
    except Exception:  # noqa: BLE001
        return 0.0


# ── Node ─────────────────────────────────────────────────────────────────────


def phase_promotion(state: PromotionState) -> dict:
    promoted: list = list(state.get("promoted", []))
    errors: list = list(state.get("errors", []))

    # Ph1 → Ph2: 累積注文 ≥ 30 AND 返品率 < 5%
    try:
        for row in _datalog_select_phase(":phase/dropship"):
            if _to_int(row["orders"]) >= 30 and _to_float(row["rr"]) < 0.05:
                try:
                    _assert_claim(row["sku"], "phase", ":phase/import")
                    promoted.append(
                        {"sku": row["sku"], "from": ":phase/dropship", "to": ":phase/import",
                         "okaimonoId": row.get("okaimonoId", "")}
                    )
                except Exception as exc:  # noqa: BLE001
                    errors.append(f"promote-import {row['sku']}: {exc}")
    except Exception as exc:  # noqa: BLE001
        errors.append(f"select :phase/dropship: {exc}")

    # Ph2 → Ph3: 月次GMV ≥ 300K AND 返品率 < 3% AND マージン見込み ≥ 60%
    try:
        for row in _datalog_select_phase(":phase/import"):
            if (
                _to_int(row["gmv"]) >= 300_000
                and _to_float(row["rr"]) < 0.03
                and _to_float(row["mp"]) >= 0.60
            ):
                try:
                    _assert_claim(row["sku"], "phase", ":phase/oem")
                    promoted.append(
                        {"sku": row["sku"], "from": ":phase/import", "to": ":phase/oem",
                         "okaimonoId": row.get("okaimonoId", "")}
                    )
                except Exception as exc:  # noqa: BLE001
                    errors.append(f"promote-oem {row['sku']}: {exc}")
    except Exception as exc:  # noqa: BLE001
        errors.append(f"select :phase/import: {exc}")

    return {"promoted": promoted, "errors": errors}


# ── Graph ────────────────────────────────────────────────────────────────────

_g = StateGraph(PromotionState)
_g.add_node("phase_promotion", phase_promotion)
_g.add_edge(START, "phase_promotion")
_g.add_edge("phase_promotion", END)

compiled = _g.compile(checkpointer=KotobaCheckpointer())


# ── WIT export (boilerplate) ─────────────────────────────────────────────────


class WitWorld(wit_world.WitWorld):
    def run(self, ctx_cbor: bytes) -> bytes:
        return handle_invoke(ctx_cbor, compiled)
