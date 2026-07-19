(ns lg-hakken.nodes.tsukuru-order
  "tsukuru_order — Ph3: tsukuru.etzhayyim.com 経由でOEM製造発注 (stub)。
  Faithful clj port of `lg/lg_hakken/nodes/tsukuru_order.py` (ADR-2606280030).

  Pure: operator notification only until the tsukuru manufacturing XRPC exists.
  Injectable `*notify*` sink defaults to println (parity with Python `print`).")

(def ^:dynamic *notify* println)

(defn- pct [x] (str (Math/round (* (double x) 100)) "%"))

(defn tsukuru-order
  "Ph3: OEM製造発注。tsukuru XRPC 実装までオペレーター通知のみ。"
  [state]
  (doseq [sku (filter #(= (:phase %) "oem") (:approved_skus state))]
    (let [c (:oem_candidate sku)]
      (*notify* (str "[Ph3 OEM Order Required] " (:name c)
                     " supplier_item=" (:item_id c)
                     " margin=" (pct (:margin sku))))))
  {})
