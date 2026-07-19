(ns lg-hakken.nodes.trend-scan
  "trend_scan — Bluesky/X/Insta トレンドから商品カテゴリの需要シグナルを収集。
  Faithful clj port of `lg/lg_hakken/nodes/trend_scan.py` (ADR-2606280030).

  Injectable edge `*list-offers*` (category limit) → seq of offer maps. Default
  GETs kakaku XRPC `com.etzhayyim.apps.kakaku.listOffers`."
  (:require [lg-hakken.xrpc :as xrpc]))

(def ^:dynamic kakaku-xrpc "https://kakaku.etzhayyim.com")

(defn default-list-offers [category limit]
  (-> (xrpc/get-json (str kakaku-xrpc "/xrpc/com.etzhayyim.apps.kakaku.listOffers")
                     {:category category :limit limit})
      (:offers)))

(def ^:dynamic *list-offers* default-list-offers)

(defn trend-scan
  "kakaku XRPC からカテゴリ内ブランド品一覧を取得して state に積む。"
  [state]
  (let [category (:category state)]
    (try
      (let [offers  (or (*list-offers* category 50) [])
            branded (mapv (fn [item]
                            {:name      (:name item)
                             :brand     (or (:brand item) "")
                             :category  category
                             :price_jpy (:price item)
                             :url       (or (:url item) "")
                             :material  (:material item)})
                          offers)]
        {:branded_products branded})
      (catch Exception exc
        {:branded_products []
         :errors (conj (vec (:errors state)) (str "trend_scan: " (.getMessage exc)))}))))
