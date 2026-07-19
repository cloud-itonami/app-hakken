(ns lg-hakken.nodes.phase-router
  "phase_router — weight / margin / rating からSKUのフェーズを決定。
  Faithful clj port of `lg/lg_hakken/nodes/phase_router.py` (ADR-2606280030).
  Pure logic (no I/O): the load-bearing decision core of the discovery graph.")

(def heavy-kg 5.0)            ; これ以上はPh1 dropship不可 → Ph2から
(def min-margin-drop 0.30)    ; Ph1 dropship 最低粗利
(def min-margin-import 0.60)
(def min-margin-oem 0.60)
(def min-rating-drop 4.0)
(def min-rating-import 4.5)
(def min-grade-drop "B")      ; kaimono-review 最低合格グレード

(def grade-order {"S" 5 "A" 4 "B" 3 "C" 2 "D" 1})

(defn margin [branded-price oem-price]
  (if (<= branded-price 0)
    0.0
    (- 1.0 (/ (double oem-price) branded-price))))

(defn grade-ok? [grade min-grade]
  (>= (get grade-order grade 0) (get grade-order min-grade 0)))

(defn target-price
  "販売価格を粗利目標から逆算。Ph1: 2.5x, Ph2: 2.8x, Ph3: 3.5x. 末尾を 800 に丸める。"
  [oem-price phase]
  (let [multiplier (get {"dropship" 2.5 "import" 2.8 "oem" 3.5} phase 2.5)
        raw (int (* oem-price multiplier))]
    (+ (* (quot raw 1000) 1000) 800)))

(defn- decide-phase
  "Return the phase string for a candidate, or nil to skip (条件未達)."
  [candidate m]
  (cond
    (> (:weight_kg candidate) heavy-kg)
    (when (and (>= m min-margin-import) (>= (:rating candidate) min-rating-import))
      "import")
    (and (>= m min-margin-oem) (>= (:rating candidate) min-rating-import))
    "oem"
    (and (>= m min-margin-drop) (>= (:rating candidate) min-rating-drop))
    "dropship"
    :else nil))

(defn phase-router
  "各OEM候補に対してフェーズを判定し approved_skus に積む。"
  [state]
  (let [candidates  (:oem_candidates state)
        branded-map (into {} (map (juxt :name identity) (:branded_products state)))
        scores      (:review_scores state)
        approved
        (vec (for [candidate candidates
                   :let [score (get scores (:item_id candidate))]
                   :when (and score (grade-ok? (:grade score) min-grade-drop))
                   :let [branded (get branded-map (or (:equivalent_of candidate) ""))]
                   :when branded
                   :let [m (margin (:price_jpy branded) (:price_jpy candidate))
                         phase (decide-phase candidate m)]
                   :when phase]
               {:oem_candidate candidate
                :branded_product branded
                :margin m
                :phase phase
                :review_score score
                :sell_price_jpy (target-price (:price_jpy candidate) phase)}))]
    {:approved_skus approved}))

(defn route-by-phase
  "conditional_edges 用のルーティング関数。先頭SKUのフェーズで分岐。"
  [state]
  (let [approved (:approved_skus state)]
    (if (empty? approved) "end" (:phase (first approved)))))
