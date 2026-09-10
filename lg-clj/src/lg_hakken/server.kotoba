(ns lg-hakken.server
  "lg-hakken dispatch surface — clj registry over the two compiled hakken graphs
  (ADR-2606280030).

  The Python hakken app has NO FastAPI server / langgraph.json: it ships two
  compiled LangGraph objects (`discovery_graph` run daily via a k8s CronJob per
  category; `phase_promotion_graph` run hourly / inside the kotoba WASM pod —
  see lg/wasm/agent.py). This namespace ports that surface as a graph REGISTRY
  plus plain `invoke-graph` / `health` dispatch fns (the same idiom as the
  wave-1 twins' server.cljc). Wrapping these in a concrete HTTP server
  (ring/http-kit) is left to the deployment layer; the graphs + dispatch are the
  load-bearing port. The Python runtime COEXISTS as the deployed surface."
  (:require [langgraph.graph :as g]
            [lg-hakken.graph :as graph]))

(def GRAPHS
  {"discovery"        graph/discovery-graph
   "phase_promotion"  graph/phase-promotion-graph})

(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

(defn health
  "Liveness/readiness → {:status 200 :body {:ok true :graphs [...]}}"
  []
  {:status 200 :body {:ok true :graphs (vec (keys GRAPHS))}})

(defn invoke-graph
  "Invoke a registered graph by name with an input map. Returns {:status :body}."
  ([gname] (invoke-graph gname {}))
  ([gname input]
   (let [graph (get GRAPHS gname)]
     (if (nil? graph)
       {:status 404 :body {:error (str "unknown graph: " gname)}}
       (try
         {:status 200 :body (g/invoke graph (or input {}))}
         (catch Exception e
           {:status 500 :body {:error (clip (.getMessage e) 300)}}))))))
