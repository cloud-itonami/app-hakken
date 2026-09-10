(ns lg-hakken.nodes.import-order
  "import_order — Ph2: Alibaba 小ロット輸入発注 (stub)。
  Faithful clj port of `lg/lg_hakken/nodes/import_order.py` (ADR-2606280030).

  Pure: emits operator notifications (manual approval required). The injectable
  `*notify*` sink defaults to println (parity with the Python `print`).")

(def ^:dynamic *notify* println)

(defn import-order
  "Ph2: 小ロット輸入発注。現在はオペレーターへの通知のみ (manual approval required)。
  TODO: tsukuru.etzhayyim.com に輸入調達 XRPC を実装後に自動化。"
  [state]
  (let [approved (filter #(= (:phase %) "import") (:approved_skus state))
        notifications
        (mapv (fn [sku]
                (let [c (:oem_candidate sku)]
                  (str "[Ph2 Import Required] " (:name c)
                       " item_id=" (:item_id c)
                       " price_jpy=" (:price_jpy c)
                       " weight_kg=" (:weight_kg c))))
              approved)]
    (doseq [note notifications] (*notify* note))
    {}))
