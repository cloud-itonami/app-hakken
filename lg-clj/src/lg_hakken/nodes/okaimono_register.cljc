(ns lg-hakken.nodes.okaimono-register
  "okaimono_register — LP自動生成 + Stripe 商品作成 (全Phase共通)。
  Faithful clj port of `lg/lg_hakken/nodes/okaimono_register.py` (ADR-2606280030).

  Injectable edge `*okaimono-publish*` (item-id) → boolean success. Publishes
  each already-registered SKU; accumulates a non-fatal error per failure."
  (:require [lg-hakken.xrpc :as xrpc]))

(def ^:dynamic okaimono-xrpc "https://okaimono.etzhayyim.com")

(defn default-okaimono-publish [item-id]
  (:ok (xrpc/post-json
        (str okaimono-xrpc "/xrpc/com.etzhayyim.apps.okaimono.publishCatalogItem")
        {:item_id item-id})))

(def ^:dynamic *okaimono-publish* default-okaimono-publish)

(defn okaimono-register
  "既に登録済みのSKUに対してStripe商品を紐付け (publish)。"
  [state]
  (let [ids (:registered_okaimono_ids state)]
    (if (empty? ids)
      {}
      (let [errors
            (reduce
             (fn [errs item-id]
               (if-not (seq item-id)
                 errs
                 (try
                   (if (true? (*okaimono-publish* item-id))
                     errs
                     (conj errs (str "okaimono_register publish failed: " item-id)))
                   (catch Exception exc
                     (conj errs (str "okaimono_register: " (.getMessage exc)))))))
             (vec (:errors state)) ids)]
        {:errors errors}))))
