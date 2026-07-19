(ns lg-hakken.nodes.gap-analysis
  "gap_analysis — kotoba datomic.transact でブランド品を KG に登録。
  Faithful clj port of `lg/lg_hakken/nodes/gap_analysis.py` (ADR-2606280030).

  Writes branded products into the kotobase-kg-v1 graph via the injectable
  `kotoba-datomic/*dm-transact*` edge (chunked <1 MiB EDN, chained lineage)."
  (:require [lg-hakken.kotoba-datomic :as kd]))

(defn branded->entity
  "BrandedProduct → datomic transact entity payload."
  [product]
  (let [eid (str "product:" (:brand product) ":" (:name product))]
    {:id eid
     :type "BrandedProduct"
     :labelJa (:name product)
     :claims [{:pred "brand"    :value (:brand product)}
              {:pred "category" :value (:category product)}
              {:pred "priceJpy" :value (str (:price_jpy product))}
              {:pred "url"      :value (:url product)}]}))

(defn gap-analysis
  "ブランド品を datomic.transact で kotobase-kg-v1 graph に書く (IPFS pin 永続化)。"
  [state]
  (let [cids    (vec (:kotoba_cids state))
        branded (:branded_products state)
        errors  (vec (:errors state))]
    (if (empty? branded)
      {:kotoba_cids cids}
      (try
        (let [entities (mapv branded->entity branded)
              results  (kd/dm-transact-entities entities)
              cids'    (reduce (fn [acc r] (if-let [t (:tx_cid r)] (conj acc t) acc))
                               cids results)]
          {:kotoba_cids cids'})
        (catch Exception exc
          {:kotoba_cids cids
           :errors (conj errors (str "gap_analysis: " (.getMessage exc)))})))))
