(ns lg-hakken.nodes.phase-promotion
  "phase_promotion — cron: SKU フェーズ昇格を datomic.q (Datalog) で検出し
  datomic.transact で更新する。
  Faithful clj port of `lg/lg_hakken/nodes/phase_promotion.py` (ADR-2606280030).

  Read = datomic.q (Datalog EDN) — primary. Write = datomic.transact
  ([:db/add E :kg/claim/phase V]; cardinality:one なので :db/add で原子的置換)。
  kg ingest 系は claim 値を文字列で書いているため、Datalog 側で数値 FILTER を
  かけず、結果を clj 側で型変換 + 閾値判定する。

  The datomic q/transact edges are the injectable `kotoba-datomic/*dm-q*` /
  `*dm-transact*` vars; the okaimono fulfillment update is `*okaimono-update*`."
  (:require [lg-hakken.edn :as edn]
            [lg-hakken.kotoba-datomic :as kd]
            [lg-hakken.xrpc :as xrpc]))

(def ^:dynamic okaimono-xrpc "https://okaimono.etzhayyim.com")

;; Ph1→Ph2: 累積注文≥30件 AND 返品率<5%
(def rule2-datalog
  (str "[:find ?sku ?okaimonoId ?orders ?rr\n"
       " :where\n"
       " [?sku :kg/claim/phase \":phase/dropship\"]\n"
       " [?sku :kg/claim/dropshipOrders ?orders]\n"
       " [?sku :kg/claim/returnRate ?rr]\n"
       " [?sku :kg/claim/okaimonoId ?okaimonoId]]"))

;; Ph2→Ph3: 月次GMV≥30万 AND 返品率<3% AND マージン見込み≥60%
(def rule3-datalog
  (str "[:find ?sku ?okaimonoId ?gmv ?rr ?mp\n"
       " :where\n"
       " [?sku :kg/claim/phase \":phase/import\"]\n"
       " [?sku :kg/claim/monthlyGmv ?gmv]\n"
       " [?sku :kg/claim/returnRate ?rr]\n"
       " [?sku :kg/claim/marginPotential ?mp]\n"
       " [?sku :kg/claim/okaimonoId ?okaimonoId]]"))

(defn default-okaimono-update [item-id phase]
  (xrpc/post-json
   (str okaimono-xrpc "/xrpc/com.etzhayyim.apps.okaimono.updateFulfillment")
   {:item_id item-id :phase phase}))

(def ^:dynamic *okaimono-update* default-okaimono-update)

(defn- ->int [v] (try (Long/parseLong (str v)) (catch Exception _ 0)))
(defn- ->float [v] (try (Double/parseDouble (str v)) (catch Exception _ 0.0)))

(defn- promote!
  "Re-assert SKU phase via datomic.transact + update okaimono fulfillment.
  `:kg/claim/phase` is cardinality:one — `[:db/add sku :kg/claim/phase V]`
  replaces the prior value in one tx (no explicit retract needed)."
  [sku-id okaimono-id new-phase label errors]
  (try
    (let [tx-edn (edn/encode-tx-data [(edn/tx-add sku-id "kg/claim/phase" new-phase)])]
      (kd/dm-transact tx-edn)
      (when (seq okaimono-id) (*okaimono-update* okaimono-id label))
      errors)
    (catch Exception exc
      (conj errors (str "_promote " sku-id " → " new-phase ": " (.getMessage exc))))))

(defn phase-promotion
  "datomic.q で昇格候補を検出し、datomic.transact で SKU + okaimono を更新。"
  [state]
  (let [errors
        (try
          (let [errs (vec (:errors state))
                ;; Ph1 → Ph2
                errs (reduce
                      (fn [errs [sku okaimono-id orders rr]]
                        (if (and (>= (->int orders) 30) (< (->float rr) 0.05))
                          (promote! sku okaimono-id ":phase/import" "import" errs)
                          errs))
                      errs (kd/dm-q rule2-datalog))
                ;; Ph2 → Ph3
                errs (reduce
                      (fn [errs [sku okaimono-id gmv rr mp]]
                        (if (and (>= (->int gmv) 300000)
                                 (< (->float rr) 0.03)
                                 (>= (->float mp) 0.60))
                          (promote! sku okaimono-id ":phase/oem" "oem" errs)
                          errs))
                      errs (kd/dm-q rule3-datalog))]
            errs)
          (catch Exception exc
            (conj (vec (:errors state)) (str "phase_promotion: " (.getMessage exc)))))]
    {:errors errors}))
