(ns jolt.transit
  "Transit (JSON) serialization for the Jolt runtime.
  Wire format follows the transit-format compliance suite (~/src/transit-format);
  reference.js is the behavioral reference for the public API."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------- decoding (read)

(declare decode-value decode-vector decode-map decode-cached decode-string)

;; --- read cache: resolve ^n back-references produced by the writer's cache ---
;; Transit compresses repeated long strings into ^-prefixed base-44 indices (ASCII
;; 48-91). Only the READER needs this for compliance: uncached output is still valid
;; transit that any reader decodes, so the writer emits in full. Constants per the
;; transit-format README. Keys cache when >3 chars; values only when >3 and
;; ~:/~$/~# prefixed. The cache wraps (clears) once it holds 44*44 = 1936 entries.

(def ^:private ^:dynamic *rc*)
(def ^:private cache-code-digits 44)
(def ^:private max-cache-entries (* 44 44))

(defn- rc-make [] (atom {:entries [] :idx 0}))

(defn- rc-code->index [s]
  (let [hi (int (nth s 1))]
    (if (= (count s) 2)
      (- hi 48)
      (+ (* (- hi 48) cache-code-digits) (- (int (nth s 2)) 48)))))

(defn- rc-is-code? [s]
  (and (string? s) (= (first s) \^)
       (<= 2 (count s) 3)
       (every? #(<= 48 % 91) (map int (subs s 1)))))

(defn- rc-cacheable? [s as-key?]
  (and (> (count s) 3)
       (or as-key? (and (= (first s) \~) (#{\: \$ \#} (second s))))))

(defn- rc-resolve [s]
  (nth (:entries @*rc*) (rc-code->index s)))

(defn- rc-register [s]
  (swap! *rc*
         (fn [st]
           (let [st (if (= (:idx st) max-cache-entries) {:entries [] :idx 0} st)]
             (-> st (update :entries conj s) (update :idx inc))))))

;; BigInt beyond jolt's 64-bit range: jolt's reader silently overflows at 2^63,
;; so a decoded ~n is held as a record preserving the exact decimal string — it
;; round-trips through ~n, and transit-clj re-reads it as a BigInteger during
;; verify. Detected by class equality: jolt's instance? does not recognise
;; defrecord types (see JOLT_BUGS.md).
(defrecord Bigint [s])
(def ^:private bigint-class (class (->Bigint "")))
(def ^:private two-pow-53 9007199254740992)   ; 2^53 — ints at/above this become ~i

(defn- big-int? [v] (= (class v) bigint-class))

;; An unrecognised ~#tag value: transit preserves these as opaque tagged
;; values rather than erroring. Same defrecord/class-equality detection as
;; Bigint (jolt's instance? doesn't recognise records — see JOLT_BUGS.md).
(defrecord TaggedValue [tag value])
(def ^:private tv-class (class (->TaggedValue "" nil)))
(defn- tagged-value? [v] (= (class v) tv-class))

;; A decoded ~c character. jolt's `char` is numeric coercion (not string->char),
;; so hold the 1-char string and round-trip it through ~c.
(defrecord CharV [s])
(def ^:private charv-class (class (->CharV "")))
(defn- charv? [v] (= (class v) charv-class))

(defn- decode-string [s]
  (if (not= (first s) \~)
    s
    (case (second s)
      \~ (str/replace s "~~" "~")
      \: (keyword (subs s 2))
      \$ (symbol (subs s 2))
      \m (java.util.Date. (parse-long (subs s 2)))
      \u (java.util.UUID/fromString (subs s 2))
      \r (java.net.URI. (subs s 2))
      \i (parse-long (subs s 2))
      \d (Double/parseDouble (subs s 2))
      \n (->Bigint (subs s 2))
      \b (->Bigint (subs s 2))
      \f (bigdec (subs s 2))
      \^ (subs s 1)   ; ~^X  ->  "^X"  (transit string-prefix tag)
      \` (subs s 1)   ; ~`X  ->  "`X"  (transit string-prefix tag)
      \c (->CharV (subs s 2))   ; ~cX  ->  char X
      \t (java.util.Date/from (java.time.Instant/parse (subs s 2))) ; ~t RFC3339 (json-verbose inst)
      \z (case (subs s 2)
          "NaN"  Double/NaN
          "INF"  Double/POSITIVE_INFINITY
          "-INF" Double/NEGATIVE_INFINITY
          (throw (ex-info (str "transit: unknown special float " s) {:tag s})))
      (throw (ex-info (str "transit: unknown scalar tag " s) {:tag s})))))

(defn- decode-cached [s as-key?]
  (if (rc-is-code? s)
    (decode-string (rc-resolve s))
    (do (when (rc-cacheable? s as-key?) (rc-register s))
        (decode-string s))))

(defn- decode-verbose-map [m]
  ;; json-verbose object form. A single "~#"-prefixed key is a tagged value
  ;; (or ~#' scalar root, ~#set/~#list/~#cmap); any other map is a plain map
  ;; whose keys are transit-encoded strings.
  (let [ks (keys m)]
    (if (and (= (count ks) 1)
             (let [k (first ks)] (and (string? k) (.startsWith k "~#"))))
      (let [k (first ks) rep (get m k)]
        (cond
          (= k "~#'")    (decode-value rep)
          (= k "~#set")  (set (mapv decode-value rep))
          (= k "~#list") (apply list (mapv decode-value rep))
          (= k "~#cmap") (into {} (for [[kk vv] (partition 2 rep)]
                                   [(decode-value kk) (decode-value vv)]))
          :else          (->TaggedValue (subs k 2) (decode-value rep))))
      (into {} (for [[k v] m] [(decode-string k) (decode-value v)])))))

(defn- decode-value [node]
  (cond
    (string? node) (decode-cached node false)
    (vector? node) (decode-vector node)
    (map? node)    (decode-verbose-map node)
    :else node))                                                     ; nil, bool, number

(defn- decode-vector [node]
  (let [head (first node)
        raw (cond
              (not (string? head)) head
              (rc-is-code? head) (rc-resolve head)
              :else (do (when (rc-cacheable? head false) (rc-register head)) head))]
    (cond
      (= raw "~#'")    (decode-value (second node))                 ; root scalar
      (= raw "^ ")     (decode-map node)
      (= raw "~#set")  (set (mapv decode-value (second node)))
      (= raw "~#list") (apply list (mapv decode-value (second node)))
      (= raw "~#cmap") (into {} (for [[k v] (partition 2 (second node))]
                                  [(decode-value k) (decode-value v)]))
      (and (string? raw) (.startsWith raw "~#"))
        (->TaggedValue (subs raw 2) (decode-value (second node)))
      (string? raw)    (into [(decode-string raw)] (mapv decode-value (rest node)))
      :else            (mapv decode-value node))))

(defn- decode-map [node]
  (into {} (for [[k v] (partition 2 (rest node))]
             [(decode-cached k true) (decode-value v)])))

;; ---------------------------------------------------------- encoding (write)

(declare encode-value)

(defn- escape-string [s]
  ;; transit reserves strings whose first char is ~, ^ or ` — prefix with ~ so
  ;; they aren't mistaken for tags/markers. Interior chars are left alone; only
  ;; the leading char can be ambiguous. (Decoded back by the ~ / ^ / ` tag cases.)
  (if (and (pos? (count s)) (#{\~ \^ \`} (first s)))
    (str "~" s)
    s))

(defn- kw-name [k]
  ;; full name preserving namespace (transit emits "ns/name")
  (let [n (name k) ns (namespace k)]
    (if ns (str ns "/" n) n)))

(defn- encode-number [v]
  (cond
    (and (integer? v)
         (or (>= v two-pow-53) (<= v (- two-pow-53))))
      (str "~i" (str v))
    (and (double? v) (Double/isNaN v))      "~zNaN"
    (and (double? v) (Double/isInfinite v)) (if (pos? v) "~zINF" "~z-INF")
    :else v))   ; bare Long (<2^53) or plain Double

(defn- encode-value [v]
  (cond
    (nil? v)        nil
    (string? v)     (escape-string v)
    (keyword? v)    (str "~:" (kw-name v))
    (symbol? v)     (str "~$" (kw-name v))
    (big-int? v)    (str "~n" (:s v))
    (tagged-value? v) [(str "~#" (:tag v)) (encode-value (:value v))]
    (charv? v)      (str "~c" (:s v))
    (inst? v)       (str "~m" (str (inst-ms v)))
    (uuid? v)       (str "~u" (.toString v))
    (instance? java.net.URI v) (str "~r" (.toString v))
    (number? v)     (encode-number v)
    (true? v)       v
    (false? v)      v
    (vector? v)     (mapv encode-value v)
    (map? v)        (encode-map v)
    (set? v)        ["~#set" (mapv encode-value (seq v))]
    (seq? v)        ["~#list" (mapv encode-value v)]
    :else (throw (ex-info (str "transit: cannot encode " (pr-str v)) {:value v}))))

(defn- encode-key [k]
  ;; map keys are always JSON strings; scalars get their tag prefix.
  (cond
    (string? k)  (escape-string k)
    (keyword? k) (str "~:" (kw-name k))
    (symbol? k)  (str "~$" (kw-name k))
    (integer? k) (str "~i" (str k))
    (double? k)  (str "~d" (str k))
    (big-int? k) (str "~n" (:s k))
    :else (throw (ex-info (str "transit: non-stringable map key " (pr-str k)) {:key k}))))

(defn- stringable-key? [k]
  (or (string? k) (keyword? k) (symbol? k) (integer? k) (double? k) (big-int? k)))

(defn- encode-map [m]
  (if (every? stringable-key? (keys m))
    (into ["^ "] (mapcat (fn [[k v]] [(encode-key k) (encode-value v)]) m))
    ["~#cmap" (->> m (mapcat (fn [[k v]] [(encode-value k) (encode-value v)])) vec)]))

(defn- scalar? [v]
  (or (nil? v) (true? v) (false? v) (number? v)
      (string? v) (keyword? v) (symbol? v)
      (inst? v) (uuid? v) (instance? java.net.URI v)
      (big-int? v) (decimal? v) (charv? v)))

(defn- encode [v]
  ;; The top level is always a JSON array. A bare scalar is wrapped in ["~#'" X]
  ;; so a scalar root is distinguishable from a one-element array; collections
  ;; (vectors, later sets/lists/maps) emit their own array form.
  (if (scalar? v)
    ["~#'" (encode-value v)]
    (encode-value v)))

;; ------------------------------------------------------------ public facade

(defn read
  "Decode a transit-JSON string into a Jolt value."
  [s]
  (binding [*rc* (rc-make)]
    (decode-value (json/read-str s))))

(defn write
  "Encode a Jolt value as a transit-JSON string."
  [v]
  (json/write-str (encode v)))
