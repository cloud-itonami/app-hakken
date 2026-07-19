(ns lg-hakken.graph-test
  "hakken — graph topology + node behaviour tests under bb (ADR-2606280030).

  The Python nodes are async + network-bound (httpx to kakaku / aliexpress /
  kaimono-review / okaimono / kotoba). Here every network edge is an injectable
  dynamic var, so the pipeline topology + the pure decision logic (phase_router,
  target_price, quality estimate, phase_promotion thresholds) verify offline."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [lg-hakken.server :as server]
            [lg-hakken.graph :as graph]
            [lg-hakken.nodes.trend-scan :as trend-scan]
            [lg-hakken.nodes.gap-analysis :as gap-analysis]
            [lg-hakken.nodes.supplier-search :as supplier-search]
            [lg-hakken.nodes.quality-eval :as quality-eval]
            [lg-hakken.nodes.phase-router :as phase-router]
            [lg-hakken.nodes.okaimono-dropship :as okaimono-dropship]
            [lg-hakken.nodes.okaimono-register :as okaimono-register]
            [lg-hakken.nodes.social-announce :as social-announce]
            [lg-hakken.nodes.import-order :as import-order]
            [lg-hakken.nodes.tsukuru-order :as tsukuru-order]
            [lg-hakken.nodes.phase-promotion :as phase-promotion]
            [lg-hakken.kotoba-datomic :as kd]
            [lg-hakken.xrpc :as xrpc]))

(deftest service-endpoints-are-explicit-host-capabilities
  (let [seen (atom nil)]
    (binding [trend-scan/kakaku-xrpc "https://custom.etzhayyim.com"
              xrpc/*http-get* (fn [url _]
                                (reset! seen url)
                                {:status 200 :body "{\"offers\":[]}"})]
      (is (= [] (trend-scan/default-list-offers "home" 1)))
      (is (= "https://custom.etzhayyim.com/xrpc/com.etzhayyim.apps.kakaku.listOffers"
             @seen)))))

;; ── server registry parity ──────────────────────────────────────────────────

(deftest graphs-match-expected-set
  (is (= #{"discovery" "phase_promotion"} (set (keys server/GRAPHS)))))

(deftest health-endpoint
  (let [r (server/health)]
    (is (= 200 (:status r)))
    (is (true? (get-in r [:body :ok])))
    (is (= #{"discovery" "phase_promotion"} (set (get-in r [:body :graphs]))))))

(deftest invoke-unknown-graph-404
  (is (= 404 (:status (server/invoke-graph "nope" {})))))

;; ── phase_router: the load-bearing decision core ────────────────────────────

(deftest margin-and-target-price
  (is (= 0.0 (phase-router/margin 0 100)))
  (is (< (Math/abs (- 0.5 (phase-router/margin 10000 5000))) 1e-9))
  ;; multipliers + round-to-800: Ph1 2.5x, Ph2 2.8x, Ph3 3.5x
  (is (= 6800 (phase-router/target-price 2500 "dropship")))   ; 6250 → 6800
  (is (= 12800 (phase-router/target-price 4600 "import")))    ; 12880 → 12800
  (is (= 8800 (phase-router/target-price 2500 "oem"))))       ; 8750 → 8800

(deftest grade-gate
  (is (true? (phase-router/grade-ok? "B" "B")))
  (is (true? (phase-router/grade-ok? "S" "B")))
  (is (false? (phase-router/grade-ok? "C" "B"))))

(defn- candidate [overrides]
  (merge {:name "X" :item_id "i1" :url "u" :price_jpy 2500 :weight_kg 0.5
          :rating 4.7 :review_count 100 :material "pe" :washable true
          :lead_days 18 :min_order 1 :supplier_country "CN"
          :equivalent_of "Branded"}
         overrides))

(defn- route-state [cand grade]
  {:oem_candidates [cand]
   :branded_products [{:name "Branded" :price_jpy 9000}]
   :review_scores {(:item_id cand) {:item_id (:item_id cand) :grade grade :score 80}}})

(deftest phase-router-light-high-margin->oem
  (let [out (phase-router/phase-router (route-state (candidate {}) "A"))]
    (is (= 1 (count (:approved_skus out))))
    (is (= "oem" (:phase (first (:approved_skus out)))))))

(deftest phase-router-heavy->import
  ;; weight > 5kg cannot dropship; needs import margin+rating
  (let [out (phase-router/phase-router
             (route-state (candidate {:weight_kg 8.5 :rating 4.6 :price_jpy 3000}) "A"))]
    (is (= "import" (:phase (first (:approved_skus out)))))))

(deftest phase-router-low-margin-low-rating->dropship
  ;; rating 4.1 (<4.5 import) but margin>=0.30 & rating>=4.0 → dropship
  (let [out (phase-router/phase-router
             (route-state (candidate {:rating 4.1 :price_jpy 6000}) "B"))]
    (is (= "dropship" (:phase (first (:approved_skus out)))))))

(deftest phase-router-below-grade-skipped
  (let [out (phase-router/phase-router (route-state (candidate {}) "C"))]
    (is (= [] (:approved_skus out)))))

(deftest phase-router-no-branded-match-skipped
  (let [out (phase-router/phase-router
             (route-state (candidate {:equivalent_of "Nonexistent"}) "A"))]
    (is (= [] (:approved_skus out)))))

(deftest route-by-phase-fn
  (is (= "end" (phase-router/route-by-phase {:approved_skus []})))
  (is (= "dropship" (phase-router/route-by-phase {:approved_skus [{:phase "dropship"}]}))))

;; ── quality_eval: XRPC score vs estimate fallback ───────────────────────────

(deftest quality-eval-estimate-fallback
  (binding [quality-eval/*score-product* (fn [_] nil)]   ; XRPC unreachable
    (let [out (quality-eval/quality-eval {:oem_candidates [(candidate {:rating 3.2})]})]
      (is (= "B" (:grade (get (:review_scores out) "i1"))))   ; 3.2*20=64 → 64≥60 = B
      (is (= 64 (:score (get (:review_scores out) "i1")))))))

(deftest quality-eval-xrpc-score
  (binding [quality-eval/*score-product* (fn [_] {:grade "S" :score 95 :quality 0.9})]
    (let [out (quality-eval/quality-eval {:oem_candidates [(candidate {})]})]
      (is (= "S" (:grade (get (:review_scores out) "i1"))))
      (is (= 95 (:score (get (:review_scores out) "i1")))))))

;; ── supplier_search: stub path when ALIEXPRESS_API unset ────────────────────

(deftest supplier-search-stub-pillow
  (let [out (supplier-search/supplier-search {:category "pillow"})]
    (is (= 1 (count (:oem_candidates out))))
    (is (= "Brain Sleep Pillow" (:equivalent_of (first (:oem_candidates out)))))))

;; ── gap_analysis: entities written via injected transact ────────────────────

(deftest gap-analysis-collects-tx-cids
  (with-redefs [kd/*dm-transact* (fn [_tx _opts] {:tx_cid "cid1" :commit_cid "c1"})]
    (let [out (gap-analysis/gap-analysis
               {:branded_products [{:name "P" :brand "B" :category "pillow"
                                    :price_jpy 9000 :url "u"}]})]
      (is (= ["cid1"] (:kotoba_cids out))))))

(deftest gap-analysis-empty-noop
  (is (= {:kotoba_cids []} (gap-analysis/gap-analysis {:branded_products []}))))

;; ── phase_promotion: Datalog thresholds via stubbed dm-q/dm-transact ────────

(deftest phase-promotion-promotes-on-threshold
  (let [txs (atom [])
        upds (atom [])]
    (with-redefs [kd/*dm-q* (fn [q _]
                              (if (clojure.string/includes? q "dropship")
                                [["sku:1" "ok:1" "42" "0.02"]]     ; orders 42≥30, rr<5%
                                [["sku:2" "ok:2" "350000" "0.01" "0.7"]]))
                  kd/*dm-transact* (fn [tx _] (swap! txs conj tx) {:tx_cid "t"})]
      (binding [phase-promotion/*okaimono-update* (fn [id ph] (swap! upds conj [id ph]) nil)]
        (let [out (phase-promotion/phase-promotion {})]
          (is (= [] (:errors out)))
          (is (= 2 (count @txs)))
          (is (clojure.string/includes? (first @txs) ":phase/import"))
          (is (clojure.string/includes? (second @txs) ":phase/oem"))
          (is (= [["ok:1" "import"] ["ok:2" "oem"]] @upds)))))))

(deftest phase-promotion-skips-below-threshold
  (let [txs (atom [])]
    (with-redefs [kd/*dm-q* (fn [q _]
                              (if (clojure.string/includes? q "dropship")
                                [["sku:1" "ok:1" "10" "0.02"]]      ; orders 10 < 30
                                []))
                  kd/*dm-transact* (fn [tx _] (swap! txs conj tx) {:tx_cid "t"})]
      (let [out (phase-promotion/phase-promotion {})]
        (is (= [] (:errors out)))
        (is (= 0 (count @txs)))))))

;; ── discovery graph end-to-end (all edges stubbed) ──────────────────────────

(deftest discovery-graph-full-pipeline
  (binding [trend-scan/*list-offers*
            (fn [_ _] [{:name "Brain Sleep Pillow" :brand "BrainSleep"
                        :price 9000 :url "u" :material "pe"}])
            okaimono-dropship/*okaimono-create* (fn [_] {:item_id "ok123"})
            okaimono-register/*okaimono-publish* (fn [_] true)
            social-announce/*social-post* (fn [_] nil)
            import-order/*notify* (fn [_] nil)
            tsukuru-order/*notify* (fn [_] nil)]
    (with-redefs [kd/*dm-transact* (fn [_ _] {:tx_cid "cidX" :commit_cid "cX"})]
      (let [out (g/invoke graph/discovery-graph {:category "pillow"})]
        ;; stub pillow (price 2500 vs branded 9000) → margin .72, light → oem
        (is (= ["cidX"] (:kotoba_cids out)))
        (is (= 1 (count (:approved_skus out))))
        (is (= "oem" (:phase (first (:approved_skus out)))))
        (is (= 8800 (:sell_price_jpy (first (:approved_skus out)))))
        (is (= [] (:errors out)))))))

(deftest discovery-graph-dropship-path-registers
  ;; force a dropship candidate (rating 4.1, light, margin .56) and assert the
  ;; dropship → okaimono_register → social_announce tail runs.
  (binding [trend-scan/*list-offers*
            (fn [_ _] [{:name "Brain Sleep Pillow" :brand "B" :price 5700 :url "u"}])
            okaimono-dropship/*okaimono-create* (fn [_] {:item_id "ok999"})
            okaimono-register/*okaimono-publish* (fn [_] true)
            social-announce/*social-post* (fn [_] nil)]
    (with-redefs [kd/*dm-transact* (fn [_ _] {:tx_cid "c"})
                  ;; pillow stub rating 4.7 → would be oem; lower it to force dropship
                  supplier-search/*aliexpress-search* (fn [_] nil)]
      (let [out (g/invoke graph/discovery-graph {:category "pillow"})]
        ;; stub pillow price 2500 vs branded 5700 → margin .56, rating 4.7 light
        ;; margin .56 < .60 oem, < import; ≥.30 drop & rating≥4.0 → dropship
        (is (= "dropship" (:phase (first (:approved_skus out)))))
        (is (= ["ok999"] (:registered_okaimono_ids out)))))))

;; ── phase_promotion graph end-to-end ────────────────────────────────────────

(deftest phase-promotion-graph-runs
  (with-redefs [kd/*dm-q* (fn [_ _] [])]
    (let [out (g/invoke graph/phase-promotion-graph {})]
      (is (= [] (:errors out))))))
