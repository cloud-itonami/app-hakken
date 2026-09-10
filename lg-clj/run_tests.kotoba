;; lg-hakken clj-port test runner (repo rule: run_tests.clj, NOT .sh).
;;
;;   bb run_tests.clj      (from 60-apps/etzhayyim-project-hakken/lg-clj/)
;;   bb test               (bb.edn task alias)
;;
;; Exits non-zero if any test fails or errors.
(ns lg-hakken.host
  (:require [babashka.http-client :as http]
            [clojure.test :as t]
            [lg-hakken.kotoba-datomic :as kotoba]
            [lg-hakken.xrpc :as xrpc]
            [lg-hakken.nodes.okaimono-register :as okaimono-register]
            [lg-hakken.nodes.okaimono-dropship :as okaimono-dropship]
            [lg-hakken.nodes.phase-promotion :as phase-promotion]
            [lg-hakken.nodes.quality-eval :as quality-eval]
            [lg-hakken.nodes.social-announce :as social-announce]
            [lg-hakken.nodes.supplier-search :as supplier-search]
            [lg-hakken.nodes.trend-scan :as trend-scan]
            [lg-hakken.edn-and-cid-test]
            [lg-hakken.graph-test]))

(defn- env [name default] (or (System/getenv name) default))

(def kotoba-config
  {:url (env "KOTOBA_XRPC_URL" (:url kotoba/default-config))
   :bearer (env "KOTOBA_BEARER" "")
   :graph-label (env "KOTOBA_GRAPH" (:graph-label kotoba/default-config))})

(def endpoint-config
  {:okaimono (env "OKAIMONO_XRPC_URL" okaimono-register/okaimono-xrpc)
   :kaimono-review (env "KAIMONO_REVIEW_XRPC_URL" quality-eval/kaimono-review-xrpc)
   :kakaku (env "KAKAKU_XRPC_URL" trend-scan/kakaku-xrpc)
   :aliexpress (env "ALIEXPRESS_API_URL" supplier-search/aliexpress-api)})

(defn dm-transact [tx-edn opts]
  (kotoba/dm-transact-with http/post kotoba-config tx-edn opts))

(defn dm-q [query-edn opts]
  (kotoba/dm-q-with http/post kotoba-config query-edn opts))

(defn with-capabilities [f]
  (binding [xrpc/*http-get* http/get
            xrpc/*http-post* http/post
            kotoba/*dm-transact* dm-transact
            kotoba/*dm-q* dm-q
            okaimono-register/okaimono-xrpc (:okaimono endpoint-config)
            okaimono-dropship/okaimono-xrpc (:okaimono endpoint-config)
            phase-promotion/okaimono-xrpc (:okaimono endpoint-config)
            quality-eval/kaimono-review-xrpc (:kaimono-review endpoint-config)
            social-announce/kaimono-review-xrpc (:kaimono-review endpoint-config)
            trend-scan/kakaku-xrpc (:kakaku endpoint-config)
            supplier-search/aliexpress-api (:aliexpress endpoint-config)]
    (f)))

(let [{:keys [fail error]} (t/run-tests 'lg-hakken.edn-and-cid-test
                                        'lg-hakken.graph-test)]
  (when (pos? (+ (or fail 0) (or error 0)))
    (System/exit 1)))
