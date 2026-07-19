(ns lg-hakken.edn-and-cid-test
  "hakken — EDN encoder + kotoba CID derivation tests.
  clojure.test port of `lg/tests/test_edn_and_cid.py` (ADR-2606280030).

  These are the pure substrate-correctness functions behind hakken's writes to
  the canonical kotoba Datom log: EDN tx-data encoding (what kotoba-server
  parses) and content-address derivation (what makes the same graph label
  resolve identically across nodes)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lg-hakken.edn :as e]
            [lg-hakken.kotoba-datomic :as kd]
            [lg-hakken.xrpc :as xrpc]))

;; ── edn/encode: every supported type + escaping ─────────────────────────────

(deftest encode-scalars
  (is (= "nil" (e/encode nil)))
  (is (= "true" (e/encode true)))
  (is (= "false" (e/encode false)))
  (is (= "42" (e/encode 42)))
  (is (= "-7" (e/encode -7)))
  (is (= "3.5" (e/encode 3.5)))
  (is (= ":phase" (e/encode (e/kw "phase"))))
  (is (= ":db/add" (e/encode (e/kw ":db/add")))))

(deftest encode-string-escaping
  (is (= "\"hi\"" (e/encode "hi")))
  ;; backslash, quote, newline, CR, tab → EDN escapes
  (is (= "\"a\\\\b\\\"c\\nd\\re\\tf\"" (e/encode "a\\b\"c\nd\re\tf"))))

(deftest encode-collections-and-map-keyword-keys
  (is (= "[1 2 3]" (e/encode [1 2 3])))
  (is (= "[1 \"x\"]" (e/encode [1 "x"])))
  (is (clojure.string/starts-with? (e/encode #{1 2}) "#{"))
  ;; string map keys are promoted to keywords; keyword keys pass through
  (is (= "{:phase 1}" (e/encode {"phase" 1})))
  (is (= "{:db/id 5}" (e/encode {:db/id 5}))))

(deftest encode-rejects-unsupported-type
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported EDN value"
                        (e/encode (Object.)))))

(deftest kw-normalizes-leading-colon
  (is (= :phase (e/kw "phase")))
  (is (= :phase (e/kw ":phase")))
  (is (= :db/add (e/kw "db/add"))))

;; ── tx-op builders ──────────────────────────────────────────────────────────

(deftest tx-add-and-retract-attr-keywordization
  (is (= "[:db/add \"e1\" :kg/type \"product\"]" (e/encode (e/tx-add "e1" "kg/type" "product"))))
  ;; already-keyword attr is preserved, not double-colon'd
  (is (= "[:db/add \"e1\" :kg/type \"x\"]" (e/encode (e/tx-add "e1" ":kg/type" "x"))))
  (is (= "[:db/retract \"e1\" :kg/type \"x\"]" (e/encode (e/tx-retract "e1" "kg/type" "x")))))

(deftest encode-tx-data-wraps-in-one-vector
  (let [ops [(e/tx-add "e1" "a" 1) (e/tx-retract "e1" "a" 2)]]
    (is (= "[[:db/add \"e1\" :a 1] [:db/retract \"e1\" :a 2]]" (e/encode-tx-data ops)))))

;; ── chunk-tx-data: the 1 MiB kotoba-server cap ──────────────────────────────

(deftest chunk-single-when-small
  (let [ops (mapv #(e/tx-add "e" "a" %) (range 5))
        chunks (e/chunk-tx-data ops)]
    (is (= 1 (count chunks)))
    (is (= (e/encode-tx-data ops) (first chunks)))))

(defn- count-occ [needle s]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote needle)) s)))

(deftest chunk-splits-at-byte-budget-and-loses-no-ops
  (let [ops (mapv #(e/tx-add (str "e" %) "kg/note" (apply str (repeat 100 "x"))) (range 200))
        chunks (e/chunk-tx-data ops 2000)]
    (is (> (count chunks) 1))
    (doseq [c chunks]
      (is (<= (alength (.getBytes ^String c "UTF-8")) 2000)))
    ;; every op preserved exactly once across the chunks
    (is (= 200 (reduce + (map #(count-occ ":db/add" %) chunks))))))

(deftest chunk-oversized-single-op-still-emitted
  ;; an op larger than max-bytes on its own must not be silently dropped
  (let [big [(e/tx-add "e" "kg/blob" (apply str (repeat 5000 "z")))]
        chunks (e/chunk-tx-data big 1000)]
    (is (= 1 (count chunks)))
    (is (clojure.string/includes? (first chunks) ":db/add"))))

;; ── entity->tx-ops ──────────────────────────────────────────────────────────

(deftest entity->tx-ops-full-shape
  (let [ops (e/entity->tx-ops {:id "prod:1" :type "product"
                               :labelJa "製品" :labelEn "Product"
                               :claims [{:pred "price" :value 1980}]
                               :relations [{:pred "supplier" :dstId "sup:9"}]})
        encoded (mapv e/encode ops)]
    (is (= "[:db/add \"prod:1\" :kg/id \"prod:1\"]" (first encoded)))
    (is (some #{"[:db/add \"prod:1\" :kg/type \"product\"]"} encoded))
    (is (some #{"[:db/add \"prod:1\" :kg/labelJa \"製品\"]"} encoded))
    (is (some #{"[:db/add \"prod:1\" :kg/claim/price 1980]"} encoded))
    (is (some #{"[:db/add \"prod:1\" :kg/relation/supplier \"sup:9\"]"} encoded))))

(deftest entity->tx-ops-minimal-is-just-ident
  (let [ops (e/entity->tx-ops {:id "x"})]
    (is (= 1 (count ops)))
    (is (= "[:db/add \"x\" :kg/id \"x\"]" (e/encode (first ops))))))

;; ── kotoba CID derivation (content-addressing correctness) ──────────────────

(def cidv1-re #"^b[a-z2-7]+$")

(deftest kotoba-cid-is-cidv1-dagcbor-sha256-multibase-b
  (let [cid (kd/kotoba-cid (.getBytes "hello"))]
    (is (re-matches cidv1-re cid))
    ;; deterministic
    (is (= cid (kd/kotoba-cid (.getBytes "hello"))))
    (is (not= cid (kd/kotoba-cid (.getBytes "world"))))))

(deftest graph-cid-for-label-is-stable-and-distinct
  (let [a (kd/graph-cid-for-label "kotobase-kg-v1")]
    (is (= a (kd/graph-cid-for-label "kotobase-kg-v1")))
    (is (not= a (kd/graph-cid-for-label "other-label")))
    (is (re-matches cidv1-re a))))

(deftest graph-cid-passthrough-for-existing-cid
  ;; a string already shaped like a multibase CID is returned unchanged
  (let [existing (str "b" (apply str (repeat 59 "a")))]
    (is (re-matches #"b[a-z2-7]{58,80}" existing))
    (is (= existing (kd/graph-cid-for-label existing)))))

;; ── parse-edn-value: server row scalar decode ───────────────────────────────

(deftest parse-edn-value-cases
  (doseq [[raw expected]
          [["\"hello\""   "hello"]
           ["\"a\\\"b\""  "a\"b"]
           ["\"a\\\\b\""  "a\\b"]
           ["true"        true]
           ["false"       false]
           ["nil"         nil]
           ["42"          42]
           ["-7"          -7]
           ["3.5"         3.5]
           ["-2.0e3"      -2000.0]
           [":kw"         ":kw"]          ; keywords pass through as strings
           ["unparseable" "unparseable"]]]
    (is (= expected (kd/parse-edn-value raw)) (str "parse " (pr-str raw)))))

(deftest live-authority-requires-explicit-capabilities
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit host capability"
                        (xrpc/get-json "https://kakaku.etzhayyim.com/xrpc/test")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit host capability"
                        (xrpc/post-json "https://kakaku.etzhayyim.com/xrpc/test" {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit host capability"
                        (kd/dm-q "[:find ?e]")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit host capability"
                        (kd/dm-transact "[]"))))

(deftest endpoint-guards-reject-lookalikes
  (is (nil? (xrpc/assert-service-url "https://kakaku.etzhayyim.com/xrpc/test")))
  (is (nil? (kd/assert-kotoba-url "https://kotoba.etzhayyim.com")))
  (doseq [url ["not-a-url"
               "http://kakaku.etzhayyim.com/xrpc/test"
               "https://kakaku.etzhayyim.com.attacker.example/xrpc/test"
               "https://kakaku.etzhayyim.com@attacker.example/xrpc/test"]]
    (is (thrown? clojure.lang.ExceptionInfo (xrpc/assert-service-url url)) url))
  (doseq [url ["https://kotoba.etzhayyim.com.attacker.example"
               "https://other.etzhayyim.com"
               "https://kotoba.etzhayyim.com@attacker.example"]]
    (is (thrown? clojure.lang.ExceptionInfo (kd/assert-kotoba-url url)) url)))

(deftest injected-xrpc-wire-contract
  (let [seen (atom nil)
        result (xrpc/post-json-with
                (fn [url opts]
                  (reset! seen [url opts])
                  {:status 200 :body "{\"ok\":true}"})
                "https://kakaku.etzhayyim.com/xrpc/test" {:x 1} 1234)]
    (is (true? (:ok result)))
    (is (true? (get-in result [:body :ok])))
    (is (= 1234 (get-in @seen [1 :timeout])))
    (is (re-find #"\"x\"" (get-in @seen [1 :body])))))

(deftest injected-kotoba-wire-contract
  (let [seen (atom nil)
        config (assoc kd/default-config :bearer "secret")
        result (kd/dm-transact-with
                (fn [url opts]
                  (reset! seen [url opts])
                  {:status 200 :body "{\"tx_cid\":\"cid1\"}"})
                config "[]" {})]
    (is (= "cid1" (:tx_cid result)))
    (is (str/ends-with? (first @seen) "/xrpc/com.etzhayyim.apps.kotoba.datomic.transact"))
    (is (= "Bearer secret" (get-in @seen [1 :headers "Authorization"])))
    (is (re-find #"tx_edn" (get-in @seen [1 :body])))))
