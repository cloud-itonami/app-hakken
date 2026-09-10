(ns lg-hakken.kotoba-datomic
  "kotoba datomic XRPC client — PRIMARY write/read surface for hakken.

  Faithful port of `lg/lg_hakken/kotoba_datomic.py` (ADR-2606280030).

  The PURE substrate-correctness core ports exactly (and is unit-tested under
  bb, like the Python `tests/test_edn_and_cid.py`):
    - `kotoba-cid` / `graph-cid-for-label` — CIDv1 dag-cbor sha2-256 multibase-b
      content-addressing so the same graph label resolves identically across
      nodes/restarts.
    - `parse-edn-value` — decode a server `rows_edn` scalar to a clj value.

  The network surface (`dm-transact`, `dm-q`, …) is exposed as INJECTABLE
  dynamic edges (the actor swap pattern): the default implementations POST to
  the kotoba XRPC via babashka.http-client + cheshire (loaded lazily so the ns
  loads offline under bb); tests rebind them to stubs. This is the substrate
  boundary — RisingWave is forbidden; the only persisted target is the kotoba
  Datom log."
  (:require #?(:clj [cheshire.core :as json])
            [kotoba.lang.text :as str]
            [lg-hakken.edn :as edn]))

(def default-config {:url "https://kotoba.etzhayyim.com"
                     :bearer ""
                     :graph-label "kotobase-kg-v1"})

;; ── internal-trust header (ADR-2608124000) ────────────────────────────────────
;; kotoba-server's `require_internal_trust` gate compares this header against its
;; own KOTOBA_INTERNAL_SECRET. That variable is unset across the murakumo fleet,
;; so the gate returns success and the header is never read — sending it TODAY is
;; a complete no-op. That is precisely why it is safe to ship now: every caller
;; must demonstrably send it BEFORE the server side can be armed, and arming the
;; server first would break every caller at once.
;;
;; We read the SAME variable name the server and the Cloudflare gateway read, so
;; arming the fleet later is one variable rather than one per actor. The value is
;; only ever read from the environment — never minted, never defaulted.
;;
;; When it is unconfigured we OMIT the header, but never SILENTLY: a one-shot
;; stderr warning fires on the live path, and `internal-trust-status` exposes the
;; same fact as a value a fleet sweep can read, instead of as a log line nobody
;; reads.
;; Silent omission is the shape that let an unauthenticated
;; fleet look healthy in the first place.
;;
;; The value may also be supplied explicitly as `:internal-trust` in the config
;; map (this namespace already takes its url/bearer that way); the environment is
;; only the fallback. `resolve-internal-trust` is the one place that decides.
(def internal-trust-header "x-internal-trust")
(def internal-trust-env "KOTOBA_INTERNAL_SECRET")

#?(:clj
   (defn internal-trust
     "The configured internal-trust secret, or nil when unset/blank. Environment
     only — this function never mints or defaults a value."
     []
     (let [v (System/getenv internal-trust-env)]
       (when-not (str/blank? v) v))))

#?(:clj (def ^:private internal-trust-warned? (atom false)))

#?(:clj
   (defn internal-trust-status
     "`:configured` | `:unconfigured` — the machine-readable half of the warning."
     []
     (if (internal-trust) :configured :unconfigured)))

#?(:clj
   (defn warn-unconfigured-internal-trust!
     "Announce ONCE per process that this push carries no internal-trust header."
     []
     (when (compare-and-set! internal-trust-warned? false true)
       (binding [*out* *err*]
         (println (str "WARN lg-hakken.kotoba-datomic: " internal-trust-env " is unset — requests carry NO "
                       internal-trust-header " header. Harmless while kotoba-server's"
                       " require_internal_trust gate is disabled fleet-wide"
                       " (ADR-2608124000); it becomes a hard rejection the moment"
                       " that gate is armed."))))))

(defn resolve-internal-trust
  "The internal-trust secret for a call: an explicit `:internal-trust` in the
   config map wins, otherwise the environment. nil when neither is set."
  [config]
  (let [explicit (:internal-trust config)]
    (if-not (str/blank? explicit) explicit (internal-trust))))

;; ── Graph CID derivation ────────────────────────────────────────────────────
;; kotoba's Datomic API requires `graph` to be a real CIDv1 multibase string,
;; not a human-readable label. Hash the label client-side so the same label
;; always resolves to the same content-addressed graph.

(def ^:private b32-alphabet "abcdefghijklmnopqrstuvwxyz234567")

(defn- base32-lower-nopad
  "RFC 4648 base32, lowercase, no padding (matches Python
  base64.b32encode(..).rstrip(b'=').lower())."
  [^bytes data]
  (let [sb (StringBuilder.)
        emit! (fn [idx] (.append sb (.charAt b32-alphabet idx)))]
    ;; Single loop over bytes. Each byte adds 8 bits to the accumulator; we
    ;; drain every complete 5-bit group as a base32 char before reading on.
    (loop [i 0, buffer 0, bits 0]
      (if (< i (alength data))
        (let [buffer (bit-or (bit-shift-left buffer 8) (bit-and (aget data i) 0xff))
              bits (+ bits 8)
              ;; drain whole groups; leave remainder (<5 bits) in buffer/bits
              rem (loop [bits bits]
                    (if (>= bits 5)
                      (do (emit! (bit-and (unsigned-bit-shift-right buffer (- bits 5)) 0x1f))
                          (recur (- bits 5)))
                      bits))]
          (recur (inc i) buffer rem))
        (when (pos? bits)
          (emit! (bit-and (bit-shift-left buffer (- 5 bits)) 0x1f)))))
    (.toString sb)))

(defn kotoba-cid
  "CIDv1 (dag-cbor 0x71, sha2-256 0x12 len 0x20) multibase-b string for bytes."
  [^bytes payload]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest md payload)
        prefix (byte-array [0x01 0x71 0x12 0x20])
        cid (byte-array (+ 4 (alength digest)))]
    (System/arraycopy prefix 0 cid 0 4)
    (System/arraycopy digest 0 cid 4 (alength digest))
    (str "b" (base32-lower-nopad cid))))

(defn graph-cid-for-label
  "Stable kotoba graph CID derived from a human-readable label. If the caller
  already gave us a multibase CID, pass it through unchanged."
  [label]
  (if (and (str/starts-with? label "b")
           (re-matches #"b[a-z2-7]{58,80}" label))
    label
    (kotoba-cid (.getBytes ^String label "UTF-8"))))

(def DEFAULT-GRAPH (graph-cid-for-label (:graph-label default-config)))

(defn assert-kotoba-url [url]
  (let [[_ scheme authority] (or (re-find #"^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]+)" (str url))
                                 [nil nil nil])
        authority (some-> authority str/lower)
        host (some-> authority (str/split #":" 2) first)
        allowed? (or (and (= "https" (some-> scheme str/lower))
                          (= "kotoba.etzhayyim.com" host)
                          (not (str/includes? authority "@")))
                     (and (= "http" (some-> scheme str/lower))
                          (contains? #{"127.0.0.1" "localhost" "[::1]"} host)))]
    (when-not allowed?
      (throw (ex-info "off-fleet Kotoba endpoint refused"
                      {:endpoint url :capability :hakken/kotoba-endpoint})))
    nil))

;; ── EDN row value decode (server returns rows_edn as list[list[str]]) ────────

(def ^:private int-re #"^-?\d+$")
(def ^:private float-re #"^-?\d+\.\d+([eE][+-]?\d+)?$")

(defn parse-edn-value
  "Decode a single EDN-encoded scalar string (from a server `rows_edn` row) to
  a clj value. Keywords pass through as strings; unparseable tokens too."
  [s]
  (cond
    (and (str/starts-with? s "\"") (str/ends-with? s "\"") (>= (count s) 2))
    (-> (subs s 1 (dec (count s)))
        (str/replace "\\\"" "\"")
        (str/replace "\\\\" "\\"))
    (= s "true") true
    (= s "false") false
    (= s "nil") nil
    (re-matches int-re s) (Long/parseLong s)
    (re-matches float-re s) (Double/parseDouble s)
    :else s))

;; ── injectable network edges (defaults POST to kotoba XRPC) ──────────────────

(defn xrpc-post-with
  "POST through an explicit HTTP capability and host configuration."
  [http-post {:keys [url bearer] :as config} nsid body]
  (when-not (fn? http-post)
    (throw (ex-info "Hakken Kotoba requires an explicit HTTP POST capability"
                    {:capability :hakken/kotoba-http-post})))
  (assert-kotoba-url url)
  (let [trust (resolve-internal-trust config)
        _ (when-not trust (warn-unconfigured-internal-trust!))
        resp (http-post (str (str/replace (str url) #"/+$" "") "/xrpc/" nsid)
                   {:headers (cond-> {"Content-Type" "application/json"}
                               (seq bearer) (assoc "Authorization" (str "Bearer " bearer))
                               trust (assoc internal-trust-header trust))
                    :timeout 60000
                    :throw false
                    :body (json/generate-string body)})]
    (if (>= (:status resp) 400)
      (throw (ex-info (str nsid " " (:status resp)) {:status (:status resp)}))
      (json/parse-string (:body resp) true))))

(defn dm-transact-with
  "POST datomic.transact. Returns the response map (carries :tx_cid :commit_cid)."
  [http-post config tx-edn {:keys [graph expected-parent]}]
  (xrpc-post-with http-post config "com.etzhayyim.apps.kotoba.datomic.transact"
             (cond-> {:graph (or graph (graph-cid-for-label (:graph-label config))) :tx_edn tx-edn}
               expected-parent (assoc :expected_parent expected-parent))))

(defn dm-q-with
  "POST datomic.q (Datalog). Returns rows as decoded clj values."
  [http-post config query-edn {:keys [graph]}]
  (let [resp (xrpc-post-with http-post config "com.etzhayyim.apps.kotoba.datomic.q"
                        {:graph (or graph (graph-cid-for-label (:graph-label config))) :query_edn query-edn})]
    (mapv (fn [row] (mapv parse-edn-value row)) (or (:rows_edn resp) []))))

(def ^:dynamic *dm-transact* nil)
(def ^:dynamic *dm-q* nil)

(defn- require-capability [capability f]
  (when-not (fn? f)
    (throw (ex-info "Hakken Kotoba operation requires an explicit host capability"
                    {:capability capability})))
  f)

(defn dm-transact-entities
  "Batch ingest hakken-style entities, splitting into <1 MiB EDN chunks chained
  via expected_parent. Returns the list of per-chunk transact responses."
  ([entities] (dm-transact-entities entities {}))
  ([entities {:keys [graph]}]
   (let [all-ops (mapcat edn/entity->tx-ops entities)
         chunks (edn/chunk-tx-data (vec all-ops))]
     (loop [chunks chunks, parent nil, results []]
       (if (empty? chunks)
         results
         (let [r ((require-capability :hakken/dm-transact *dm-transact*)
                  (first chunks) {:graph graph :expected-parent parent})]
           (recur (rest chunks) (or (:commit_cid r) parent) (conj results r))))))))

(defn dm-q
  ([query-edn] (dm-q query-edn {}))
  ([query-edn opts] ((require-capability :hakken/dm-q *dm-q*) query-edn opts)))

(defn dm-transact
  ([tx-edn] (dm-transact tx-edn {}))
  ([tx-edn opts] ((require-capability :hakken/dm-transact *dm-transact*) tx-edn opts)))
