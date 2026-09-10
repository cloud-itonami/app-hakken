(ns lg-hakken.nodes.supplier-search
  "supplier_search — AliExpress / Alibaba / 1688 でOEM候補を検索。
  Faithful clj port of `lg/lg_hakken/nodes/supplier_search.py` (ADR-2606280030).

  Injectable edge `*aliexpress-search*` (keyword) → seq of raw item maps. When
  ALIEXPRESS_API is unset the node returns deterministic stub candidates (dev/
  test parity with the Python `_stub_candidates`)."
  (:require [lg-hakken.xrpc :as xrpc]))

(def ^:dynamic aliexpress-api "")

(def heavy-weight-kg 5.0)

(def search-keywords
  {"mattress" ["3D air fiber mattress 8cm" "polyethylene fiber mattress washable"]
   "pillow"   ["3D air fiber pillow washable" "polyethylene fiber pillow"]
   "topper"   ["3D fiber mattress topper 5cm washable"]})

(defn default-aliexpress-search [keyword]
  (-> (xrpc/get-json (str aliexpress-api "/search")
                     {:q keyword :limit 20 :sort "orders_desc"})
      (:items)))

(def ^:dynamic *aliexpress-search* default-aliexpress-search)

(defn stub-candidates
  "API未接続時のスタブ — 開発・テスト用。"
  [category]
  (case category
    "pillow"
    [{:name "3D Air Fiber Pillow Washable PE" :platform "aliexpress"
      :item_id "1005009071063808"
      :url "https://www.aliexpress.com/item/1005009071063808.html"
      :price_jpy 2500 :weight_kg 0.5 :rating 4.7 :review_count 312
      :material "polyethylene-fiber" :thickness_cm nil :washable true
      :lead_days 18 :min_order 1 :supplier_country "CN"
      :equivalent_of "Brain Sleep Pillow"}]
    "mattress"
    [{:name "3D Air Fiber Mattress 8cm Washable" :platform "aliexpress"
      :item_id "1005007792087113"
      :url "https://www.aliexpress.com/item/1005007792087113.html"
      :price_jpy 12500 :weight_kg 8.5 :rating 4.6 :review_count 189
      :material "polyethylene-fiber" :thickness_cm 8 :washable true
      :lead_days 21 :min_order 1 :supplier_country "CN"
      :equivalent_of "Brain Sleep Mattress"}]
    []))

(defn- ->candidate [item]
  {:name (:title item) :platform "aliexpress" :item_id (:item_id item)
   :url (:url item)
   :price_jpy (int (* (:price_usd item) 150))            ; USD→JPY概算
   :weight_kg (double (or (:weight_kg item) 1.0))
   :rating (double (:rating item))
   :review_count (int (:review_count item))
   :material (:material item) :thickness_cm (:thickness_cm item)
   :washable (boolean (:washable item))
   :lead_days (int (or (:shipping_days item) 21))
   :min_order (int (or (:min_order item) 1))
   :supplier_country (or (:country item) "CN")
   :equivalent_of nil})

(defn supplier-search
  "AliExpress API でカテゴリ別OEM候補を検索。重量・素材・レビュー数でフィルタ。"
  [state]
  (let [category (:category state)
        keywords (get search-keywords category [category])]
    (if (empty? aliexpress-api)
      {:oem_candidates (stub-candidates category)}
      (try
        (let [candidates
              (vec (for [kw keywords
                         item (or (*aliexpress-search* kw) [])
                         :when (and (>= (or (:rating item) 0) 4.0)
                                    (>= (or (:review_count item) 0) 50))]
                     (->candidate item)))]
          {:oem_candidates candidates})
        (catch Exception exc
          {:oem_candidates []
           :errors (conj (vec (:errors state)) (str "supplier_search: " (.getMessage exc)))})))))
