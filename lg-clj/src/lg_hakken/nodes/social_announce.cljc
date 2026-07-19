(ns lg-hakken.nodes.social-announce
  "social_announce — kaimono-review DID 経由で Bluesky ATPost。
  Faithful clj port of `lg/lg_hakken/nodes/social_announce.py` (ADR-2606280030).

  Injectable edge `*social-post*` (payload) → ignored result. Posts an
  announcement per approved SKU under the kaimono-review home category DID."
  (:require [lg-hakken.xrpc :as xrpc]))

(def ^:dynamic kaimono-review-xrpc "https://kaimono-review.etzhayyim.com")

(def phase-label
  {"dropship" "お試し価格"
   "import"   "国内在庫あり"
   "oem"      "自社ブランド"})

(defn default-social-post [payload]
  (xrpc/post-json
   (str kaimono-review-xrpc "/xrpc/com.etzhayyim.apps.kaimono_review.postAnnouncement")
   payload))

(def ^:dynamic *social-post* default-social-post)

(defn- pct [x] (str (Math/round (* (double x) 100)) "%"))

(defn announce-text [sku]
  (let [c (:oem_candidate sku)
        label (get phase-label (:phase sku) "")
        rs (:review_score sku)]
    (str "【新着 " label "】" (:name c) "\n"
         "ブランド品比 " (pct (:margin sku)) "オフ · "
         "評価 " (:grade rs) "(" (:score rs) "点)\n"
         "okaimono.etzhayyim.com で販売中")))

(defn social-announce
  "承認SKUを kaimono-review の home カテゴリ DID で Bluesky 投稿。"
  [state]
  (let [errors
        (reduce
         (fn [errs sku]
           (try
             (*social-post* {:category "home" :text (announce-text sku)})
             errs
             (catch Exception exc
               (conj errs (str "social_announce: " (.getMessage exc))))))
         (vec (:errors state)) (:approved_skus state))]
    {:errors errors}))
