(ns lg-hakken.graph
  "hakken langgraph-clj StateGraphs — faithful clj port of `lg/lg_hakken/graph.py`
  (ADR-2606280030).

  Two graph variants (same topology as the Python originals):

    discovery-graph        full SKU discovery + registration pipeline
      START → trend_scan → gap_analysis → supplier_search → quality_eval
            → phase_router → {dropship|import|oem|END}
            → (each dest) → okaimono_register → social_announce → END

    phase-promotion-graph  cron — kotoba Datalog Rule2/3 でフェーズ昇格
      START → phase_promotion → END

  HakkenState is a plain clj map (TypedDict in Python); langgraph-clj merges each
  node's partial-update map into it. Node fns accumulate :errors / list channels
  themselves (the default channel reducer is replace), exactly as the Python
  nodes return whole accumulated lists."
  (:require [langgraph.graph :as g]
            [lg-hakken.nodes.trend-scan :as trend-scan]
            [lg-hakken.nodes.gap-analysis :as gap-analysis]
            [lg-hakken.nodes.supplier-search :as supplier-search]
            [lg-hakken.nodes.quality-eval :as quality-eval]
            [lg-hakken.nodes.phase-router :as phase-router]
            [lg-hakken.nodes.okaimono-dropship :as okaimono-dropship]
            [lg-hakken.nodes.import-order :as import-order]
            [lg-hakken.nodes.tsukuru-order :as tsukuru-order]
            [lg-hakken.nodes.okaimono-register :as okaimono-register]
            [lg-hakken.nodes.social-announce :as social-announce]
            [lg-hakken.nodes.phase-promotion :as phase-promotion]))

;; ── discovery-graph — full SKU discovery + registration pipeline ─────────────

(defn build-discovery []
  (-> (g/state-graph)
      (g/add-node :trend_scan        trend-scan/trend-scan)
      (g/add-node :gap_analysis      gap-analysis/gap-analysis)
      (g/add-node :supplier_search   supplier-search/supplier-search)
      (g/add-node :quality_eval      quality-eval/quality-eval)
      (g/add-node :phase_router      phase-router/phase-router)
      (g/add-node :okaimono_dropship okaimono-dropship/okaimono-dropship)
      (g/add-node :import_order      import-order/import-order)
      (g/add-node :tsukuru_order     tsukuru-order/tsukuru-order)
      (g/add-node :okaimono_register okaimono-register/okaimono-register)
      (g/add-node :social_announce   social-announce/social-announce)
      (g/add-edge :trend_scan      :gap_analysis)
      (g/add-edge :gap_analysis    :supplier_search)
      (g/add-edge :supplier_search :quality_eval)
      (g/add-edge :quality_eval    :phase_router)
      (g/add-conditional-edges
       :phase_router phase-router/route-by-phase
       {"dropship" :okaimono_dropship
        "import"   :import_order
        "oem"      :tsukuru_order
        "end"      g/END})
      (g/add-edge :okaimono_dropship :okaimono_register)
      (g/add-edge :import_order      :okaimono_register)
      (g/add-edge :tsukuru_order     :okaimono_register)
      (g/add-edge :okaimono_register :social_announce)
      (g/set-entry-point :trend_scan)
      (g/set-finish-point :social_announce)
      (g/compile-graph)))

;; ── phase-promotion-graph — cron: Ph1→Ph2 / Ph2→Ph3 昇格 ─────────────────────

(defn build-phase-promotion []
  (-> (g/state-graph)
      (g/add-node :phase_promotion phase-promotion/phase-promotion)
      (g/set-entry-point :phase_promotion)
      (g/set-finish-point :phase_promotion)
      (g/compile-graph)))

(def discovery-graph
  "Compiled discovery pipeline. Run daily via k8s CronJob per category."
  (build-discovery))

(def phase-promotion-graph
  "Compiled phase-promotion cron. Run hourly to detect promotion-ready SKUs."
  (build-phase-promotion))
