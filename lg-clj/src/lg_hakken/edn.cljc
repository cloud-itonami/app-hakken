(ns lg-hakken.edn
  "Minimal clj → EDN encoder for kotoba datomic transactions.

  Faithful port of `lg/lg_hakken/edn.py` (ADR-2606280030). Targets the subset
  kotoba-server `kotoba_edn::parse` understands for `datomic.transact` tx-data:
  a list of `[:db/add E A V]` / `[:db/retract E A V]` vectors and entity maps.

  Representation mapping (Python → clj):
    Python `EdnSymbol`/`kw(..)` (emit a bare keyword verbatim, no quoting)
      → a clj keyword. `encode` prints keywords as `:name` (no surrounding quotes).
    Python plain `str` (quoted EDN string)
      → a clj string. `encode` prints strings quoted + escaped.

  Strings are escaped per EDN spec (\\, \\\", \\n, \\r, \\t). All other chars
  pass through; hakken payloads are assumed valid UTF-8 text."
  (:require [clojure.string :as str]))

(defn kw
  "Keyword shortcut: (kw \"phase\") → :phase, (kw \"db/add\") → :db/add,
  (kw \":phase\") → :phase. Mirrors edn.kw — normalizes a leading colon."
  [name]
  (keyword (if (str/starts-with? name ":") (subs name 1) name)))

(defn- encode-str [s]
  (let [sb (StringBuilder.)]
    (.append sb \")
    (doseq [ch s]
      (case ch
        \\ (.append sb "\\\\")
        \" (.append sb "\\\"")
        \newline (.append sb "\\n")
        \return (.append sb "\\r")
        \tab (.append sb "\\t")
        (.append sb ch)))
    (.append sb \")
    (.toString sb)))

(declare encode)

(defn- encode-num [v]
  ;; Python distinguishes int (str) from float (repr). clj: integers → str,
  ;; doubles/floats → pr-str (matches Python repr for the values hakken emits).
  (cond
    (integer? v) (str v)
    :else        (pr-str v)))

(defn encode
  "Encode a clj value as an EDN string (faithful to edn.encode).
  Keywords print bare (`:db/add`); strings print quoted + escaped."
  [value]
  (cond
    (keyword? value) (str value)
    (symbol? value)  (str value)
    (nil? value)     "nil"
    (boolean? value) (if value "true" "false")
    (number? value)  (encode-num value)
    (string? value)  (encode-str value)
    (map? value)
    (str "{"
         (str/join " "
                   (mapcat (fn [[k v]]
                             [(encode (if (string? k) (keyword k) k)) (encode v)])
                           value))
         "}")
    (set? value)
    (str "#{" (str/join " " (map encode value)) "}")
    (sequential? value)
    (str "[" (str/join " " (map encode value)) "]")
    :else
    (throw (ex-info (str "unsupported EDN value: " (type value))
                    {:value value}))))

(defn tx-add
  "`[:db/add <e> <a> <v>]` tx op. `a` is keywordized (a leading colon is
  preserved, not double-colon'd)."
  [e a v]
  [(kw "db/add") e (kw a) v])

(defn tx-retract
  "`[:db/retract <e> <a> <v>]` tx op."
  [e a v]
  [(kw "db/retract") e (kw a) v])

(defn encode-tx-data
  "Encode a sequence of tx-ops as a single EDN vector string."
  [ops]
  (str "[" (str/join " " (map encode ops)) "]"))

(defn chunk-tx-data
  "Split tx-ops into EDN-encoded chunks each under `max-bytes`.
  kotoba-server caps `tx_edn` at 1 MiB; default 900000 leaves headroom.
  An op larger than max-bytes on its own is still emitted (never dropped)."
  ([ops] (chunk-tx-data ops 900000))
  ([ops max-bytes]
   (let [utf8 (fn [^String s] (alength (.getBytes s "UTF-8")))]
     (loop [ops ops, cur [], cur-size 2, chunks []]
       (if (empty? ops)
         (if (seq cur) (conj chunks (encode-tx-data cur)) chunks)
         (let [op (first ops)
               op-size (inc (utf8 (encode op)))]
           (if (and (seq cur) (> (+ cur-size op-size) max-bytes))
             (recur ops [] 2 (conj chunks (encode-tx-data cur)))
             (recur (rest ops) (conj cur op) (+ cur-size op-size) chunks))))))))

(defn entity->tx-ops
  "Convert a hakken-style entity map to a list of `:db/add` tx-ops.

  Schema: {:id .. :type? .. :labelJa? .. :labelEn? ..
           :claims [{:pred .. :value ..}] :relations [{:pred .. :dstId ..}]}

  Output uses the entity's :id as the EDN entity ref. Predicates are prefixed
  `kg/claim/` (claims) and `kg/relation/` (relations) per kotoba-server's KG
  predicate convention. Keys may be keywords or strings (JSON-decoded)."
  ([entity] (entity->tx-ops entity "kg/id"))
  ([entity ident-attr]
   (let [g (fn [m & ks] (some #(get m %) ks))
         eid (g entity :id "id")
         ops (transient [(tx-add eid ident-attr eid)])]
     (when-let [t (g entity :type "type")]    (conj! ops (tx-add eid "kg/type" t)))
     (when-let [v (g entity :labelJa "labelJa")] (conj! ops (tx-add eid "kg/labelJa" v)))
     (when-let [v (g entity :labelEn "labelEn")] (conj! ops (tx-add eid "kg/labelEn" v)))
     (doseq [c (g entity :claims "claims")]
       (conj! ops (tx-add eid (str "kg/claim/" (g c :pred "pred")) (g c :value "value"))))
     (doseq [r (g entity :relations "relations")]
       (conj! ops (tx-add eid (str "kg/relation/" (g r :pred "pred")) (g r :dstId "dstId"))))
     (persistent! ops))))
