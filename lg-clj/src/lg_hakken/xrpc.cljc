(ns lg-hakken.xrpc
  "Tiny babashka.http-client + cheshire JSON helpers — the `httpx` replacement
  for the hakken node default edges (ADR-2606280030).

  Concrete HTTP implementations are supplied only by the host adapter."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]))

(def ^:dynamic *http-get* nil)
(def ^:dynamic *http-post* nil)

(defn assert-service-url [url]
  (let [[_ scheme authority] (or (re-find #"^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]+)" (str url))
                                 [nil nil nil])
        authority (some-> authority str/lower-case)
        host (some-> authority (str/split #":" 2) first)
        allowed? (or (and (= "https" (some-> scheme str/lower-case))
                          host
                          (or (= host "etzhayyim.com")
                              (str/ends-with? host ".etzhayyim.com"))
                          (not (str/includes? authority "@")))
                     (and (= "http" (some-> scheme str/lower-case))
                          (contains? #{"127.0.0.1" "localhost" "[::1]"} host)))]
    (when-not allowed?
      (throw (ex-info "off-fleet XRPC endpoint refused"
                      {:endpoint url :capability :hakken/xrpc-endpoint})))
    nil))

(defn get-json-with [http-get url query]
  (when-not (fn? http-get)
    (throw (ex-info "Hakken XRPC GET requires an explicit HTTP capability"
                    {:capability :hakken/xrpc-http-get})))
  (assert-service-url url)
  (let [resp (http-get url (cond-> {:timeout 30000 :throw false}
                             (seq query) (assoc :query-params query)))]
    (if (>= (:status resp) 400)
      (throw (ex-info (str "GET " url " " (:status resp)) {:status (:status resp)}))
      (json/parse-string (:body resp) true))))

(defn get-json
  "HTTP GET url with query params → parsed JSON (keyword keys). Throws on >=400."
  ([url] (get-json url nil))
  ([url query]
   (when-not (fn? *http-get*)
     (throw (ex-info "Hakken XRPC GET requires an explicit host capability"
                     {:capability :hakken/xrpc-get})))
   (get-json-with *http-get* url query)))

(defn post-json-with [http-post url body timeout-ms]
  (when-not (fn? http-post)
    (throw (ex-info "Hakken XRPC POST requires an explicit HTTP capability"
                    {:capability :hakken/xrpc-http-post})))
  (assert-service-url url)
  (let [resp (http-post url {:headers {"Content-Type" "application/json"}
                             :timeout timeout-ms :throw false
                             :body (json/generate-string body)})
        status (:status resp)
        parsed (try (json/parse-string (:body resp) true) (catch Exception _ nil))]
    {:status status :ok (and (>= status 200) (< status 300)) :body parsed}))

(defn post-json
  "HTTP POST url with a JSON body → {:status :body(parsed-or-raw) :ok bool}.
  Never throws on HTTP status; caller inspects :ok / :status (mirrors the
  Python `resp.is_success` checks)."
  ([url body] (post-json url body 30000))
  ([url body timeout-ms]
   (when-not (fn? *http-post*)
     (throw (ex-info "Hakken XRPC POST requires an explicit host capability"
                     {:capability :hakken/xrpc-post})))
   (post-json-with *http-post* url body timeout-ms)))

(defn clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))
