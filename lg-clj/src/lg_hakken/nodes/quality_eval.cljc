(ns lg-hakken.nodes.quality-eval
  "quality_eval — kaimono-review XRPC で5軸スコアリング。
  Faithful clj port of `lg/lg_hakken/nodes/quality_eval.py` (ADR-2606280030).

  Injectable edge `*score-product*` (candidate) → score map | nil. When the XRPC
  is unreachable / returns non-2xx (default returns nil), the node falls back to
  `estimate-score` (rating × 20)."
  (:require [lg-hakken.xrpc :as xrpc]))

(def ^:dynamic kaimono-review-xrpc "https://kaimono-review.etzhayyim.com")

(def min-grade "B")

(defn default-score-product
  "POST kaimono_review.scoreProduct → parsed body on 2xx, else nil."
  [candidate]
  (let [resp (xrpc/post-json
              (str kaimono-review-xrpc "/xrpc/com.etzhayyim.apps.kaimono_review.scoreProduct")
              {:name (:name candidate) :platform (:platform candidate)
               :item_id (:item_id candidate) :material (:material candidate)
               :washable (:washable candidate) :rating (:rating candidate)
               :review_count (:review_count candidate)}
              60000)]
    (when (:ok resp) (:body resp))))

(def ^:dynamic *score-product* default-score-product)

(defn estimate-score
  "XRPC 未接続時のスコア推定 (rating × 20 で0-100換算)。"
  [candidate]
  (let [raw   (:rating candidate)
        score (int (min (* raw 20) 100))
        grade (cond (>= score 90) "S" (>= score 75) "A" (>= score 60) "B" :else "C")]
    {:item_id (:item_id candidate) :grade grade :score score
     :quality (/ raw 5.0) :usability (/ raw 5.0)
     :cost_performance 0.8 :satisfaction (/ raw 5.0) :sustainability 0.7}))

(defn quality-eval
  "各OEM候補をスコアリングし review_scores に積む (XRPC失敗時は推定)。"
  [state]
  (let [candidates (:oem_candidates state)
        scores (reduce
                (fn [acc candidate]
                  (let [data (try (*score-product* candidate) (catch Exception _ nil))]
                    (assoc acc (:item_id candidate)
                           (if data
                             {:item_id (:item_id candidate)
                              :grade (or (:grade data) "C")
                              :score (or (:score data) 0)
                              :quality (or (:quality data) 0.0)
                              :usability (or (:usability data) 0.0)
                              :cost_performance (or (:cost_performance data) 0.0)
                              :satisfaction (or (:satisfaction data) 0.0)
                              :sustainability (or (:sustainability data) 0.0)}
                             (estimate-score candidate)))))
                {} candidates)]
    {:review_scores scores}))
