(ns lg-hakken.nodes.okaimono-dropship
  "okaimono_dropship — Ph1: okaimono に AliExpress dropship 商品として登録。
  Faithful clj port of `lg/lg_hakken/nodes/okaimono_dropship.py` (ADR-2606280030).

  Injectable edge `*okaimono-create*` (payload) → {:item_id ..}|{:error ..}.
  NB (etzhayyim boundary): the regulated fulfillment tail (createCatalogItem)
  is the etzhayyim-FUNCTION side; this node calls it through the consent seam."
  (:require [lg-hakken.xrpc :as xrpc]))

(def ^:dynamic okaimono-xrpc "https://okaimono.etzhayyim.com")

(defn default-okaimono-create [payload]
  (let [resp (xrpc/post-json
              (str okaimono-xrpc "/xrpc/com.etzhayyim.apps.okaimono.createCatalogItem")
              payload)]
    (if (:ok resp)
      {:item_id (or (:item_id (:body resp)) "")}
      {:error (str "register failed: " (:status resp))})))

(def ^:dynamic *okaimono-create* default-okaimono-create)

(defn okaimono-dropship
  "承認済みSKUを okaimono にdropship商品として登録。"
  [state]
  (let [approved (:approved_skus state)
        category (:category state)]
    (loop [skus approved
           registered (vec (:registered_okaimono_ids state))
           errors (vec (:errors state))]
      (if (empty? skus)
        {:registered_okaimono_ids registered :errors errors}
        (let [sku (first skus)]
          (if (not= (:phase sku) "dropship")
            (recur (rest skus) registered errors)
            (let [candidate (:oem_candidate sku)
                  res (try
                        (*okaimono-create*
                         {:name (:name candidate) :category category
                          :price (:sell_price_jpy sku) :fulfillment "dropship"
                          :dropship_source {:platform "aliexpress"
                                            :item_id (:item_id candidate)
                                            :url (:url candidate)}
                          :lead_days (:lead_days candidate)
                          :review_score (:score (:review_score sku))
                          :review_grade (:grade (:review_score sku))})
                        (catch Exception exc {:error (str "okaimono_dropship: " (.getMessage exc))}))]
              (if (:error res)
                (recur (rest skus) registered
                       (conj errors (str (:error res) " " (:item_id candidate))))
                (recur (rest skus) (conj registered (:item_id res)) errors)))))))))
