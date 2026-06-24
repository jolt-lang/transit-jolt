(ns clojure.data.json
  "STUB for clojure/data.json — pure-Clojure JSON read/write for the transit-jolt
  port. Delete this file (and add the real git dep to deps.edn) once Jolt BUG-1 is
  fixed so the real clojure.data.json loads. See ../JOLT_BUGS.md.

  Implements the subset of the API transit needs:
    - read-str  (s & {:keys [key-fn]})  -> edn (nil/bool/long/double/string/vector/map)
    - write-str (x & {:keys [key-fn escape-unicode]})
  Number parsing returns Long for integers and Double for floats, matching the
  real library. read-str is permissive about trailing input.")
(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- writing

(def ^:private hex-digits "0123456789abcdef")

(defn- hex4
  "4-digit lowercase hex for a code point in the BMP, e.g. 0xABCD -> \"abcd\".
  (format \"%04x\" is uppercase under the current Jolt host.)"
  ^String [n]
  (let [d hex-digits]
    (str (get d (bit-and (bit-shift-right n 12) 15))
         (get d (bit-and (bit-shift-right n 8) 15))
         (get d (bit-and (bit-shift-right n 4) 15))
         (get d (bit-and n 15)))))

(defn- append-escaped [sb c escape-unicode?]
  (case c
    \" (.append sb "\\\"")
    \\ (.append sb "\\\\")
    \newline (.append sb "\\n")
    \return  (.append sb "\\r")
    \tab     (.append sb "\\t")
    \formfeed (.append sb "\\f")
    \backspace (.append sb "\\b")
    (let [cp (int c)]
      (cond
        ;; control char, or non-ASCII when escaping, or beyond the BMP -> escape
        (or (< cp 32)
            (and escape-unicode? (>= cp 0x7F))
            (> cp 0xFFFF))
        (if (> cp 0xFFFF)
          (let [off  (- cp 0x10000)
                hi   (+ 0xD800 (bit-shift-right off 10))
                lo   (+ 0xDC00 (bit-and off 0x3FF))]
            (doto sb
              (.append "\\u") (.append (hex4 hi))
              (.append "\\u") (.append (hex4 lo))))
          (doto sb (.append "\\u") (.append (hex4 cp))))
        :else (.append sb c)))))

(defn- write-string [sb s escape-unicode?]
  (.append sb \")
  (doseq [c s] (append-escaped sb c escape-unicode?))
  (.append sb \"))

(defn- write-key [sb k key-fn escape-unicode?]
  (let [k (if key-fn (key-fn k) k)]
    (write-string sb
                  (cond (string? k)  k
                        (keyword? k) (subs (str k) 1)
                        :else        (str k))
                  escape-unicode?)))

(declare write-value)

(defn- write-object [sb m opts]
  (let [ef (:escape-unicode opts true)
        kf (:key-fn opts)]
    (.append sb \{)
    (loop [entries (seq m) first? true]
      (when entries
        (let [[k v] (first entries)]
          (if-not first? (.append sb \,) )
          (write-key sb k kf ef)
          (.append sb \:)
          (write-value sb v opts)
          (recur (next entries) false))))
    (.append sb \})))

(defn- write-array [sb xs opts]
  (.append sb \[)
  (loop [xs (seq xs) first? true]
    (when xs
      (if-not first? (.append sb \,))
      (write-value sb (first xs) opts)
      (recur (next xs) false)))
  (.append sb \]))

(defn- write-value [sb x opts]
  (let [ef (:escape-unicode opts true)]
    (cond
      (nil? x)    (.append sb "null")
      (true? x)   (.append sb "true")
      (false? x)  (.append sb "false")
      (string? x) (write-string sb x ef)
      (number? x) (.append sb (str x))
      (keyword? x) (write-string sb (subs (str x) 1) ef)
      (map? x)    (write-object sb x opts)
      (or (vector? x) (set? x) (seq? x)) (write-array sb x opts)
      :else (throw (ex-info (str "JSON write: unsupported value " (pr-str x))
                            {:value x})))))

(defn write-str
  "Convert x to a JSON string. x may be nil, booleans, numbers, strings,
  keywords (written as their name), vectors/seqs/sets, or maps with string or
  keyword keys. Options (keyword-value pairs): :key-fn (applied to map keys),
  :escape-unicode (default true — escape non-ASCII as \\uXXXX)."
  [x & {:keys [key-fn escape-unicode] :as opts}]
  (let [sb (java.lang.StringBuilder.)]
    (write-value sb x opts)
    (.toString sb)))

;; ---------------------------------------------------------------- reading

(defn- skip-ws [s i len]
  (loop [i i]
    (if (and (< i len)
             (let [c (get s i)]
               (or (= c \space) (= c \tab) (= c \newline) (= c \return))))
      (recur (inc i))
      i)))

(defn- hex-val [c]
  (let [cp (int c)]
    (cond
      (<= 48 cp 57) (- cp 48)
      (<= 97 cp 102) (- cp 87)   ; a-f
      (<= 65 cp 70)  (- cp 55)   ; A-F
      :else (throw (ex-info (str "JSON: bad hex digit " c) {})))))

(defn- parse-hex4 [s i len]
  (if (< (+ i 3) len)
    [(+ (bit-shift-left (hex-val (get s i)) 12)
        (bit-shift-left (hex-val (get s (inc i))) 8)
        (bit-shift-left (hex-val (get s (+ 2 i))) 4)
        (hex-val (get s (+ 3 i))))
     (+ i 4)]
    (throw (ex-info "JSON: truncated \\u escape" {}))))

(defn- parse-string-literal
  ;; i = index just past the opening quote; returns [string next-i]
  [s i len]
  (let [sb (java.lang.StringBuilder.)]
    (loop [i i]
      (if (>= i len) (throw (ex-info "JSON: unterminated string" {})))
      (let [c (get s i)]
        (cond
          (= c \") [(.toString sb) (inc i)]
          (= c \\) (let [e (get s (inc i))]
                     (case e
                       \" (do (.append sb \") (recur (+ i 2)))
                       \\ (do (.append sb \\) (recur (+ i 2)))
                       \/ (do (.append sb \/) (recur (+ i 2)))
                       \b (do (.append sb \backspace) (recur (+ i 2)))
                       \f (do (.append sb \formfeed) (recur (+ i 2)))
                       \n (do (.append sb \newline) (recur (+ i 2)))
                       \r (do (.append sb \return) (recur (+ i 2)))
                       \t (do (.append sb \tab) (recur (+ i 2)))
                       \u (let [[cp ni] (parse-hex4 s (+ i 2) len)]
                            (.append sb (char cp))
                            (recur ni))
                       (throw (ex-info (str "JSON: bad escape \\" e) {}))))
          :else (do (.append sb c) (recur (inc i))))))))

(defn- parse-number [s i len]
  (loop [j i]
    (if (and (< j len)
             (let [c (get s j)]
               (or (<= 48 (int c) 57)
                   (#{\- \+ \. \e \E} c))))
      (recur (inc j))
      ;; JSON numbers are a subset of Clojure number literals, so read-string
      ;; yields Long for integers and Double for floats.
      [(clojure.core/read-string (subs s i j)) j])))

(defn- lit? [s i len ^String word]
  (let [n (count word)]
    (and (<= (+ i n) len) (= (subs s i (+ i n)) word))))

(declare parse-value)

(defn- parse-array [s i len key-fn]
  (loop [i (skip-ws s i len) acc (transient [])]
    (if (and (< i len) (= (get s i) \]))
      [(persistent! acc) (inc i)]
      (let [[v ni] (parse-value s i len key-fn)
            i2    (skip-ws s ni len)]
        (cond
          (and (< i2 len) (= (get s i2) \,))
          (recur (skip-ws s (inc i2) len) (conj! acc v))
          (and (< i2 len) (= (get s i2) \]))
          [(persistent! (conj! acc v)) (inc i2)]
          :else (throw (ex-info "JSON: malformed array" {})))))))

(defn- parse-object [s i len key-fn]
  (loop [i (skip-ws s i len) acc (transient {})]
    (if (and (< i len) (= (get s i) \}))
      [(persistent! acc) (inc i)]
      (if (and (< i len) (not= (get s i) \"))
        (throw (ex-info "JSON: object keys must be strings" {}))
        (let [[kstr ni] (parse-string-literal s (inc i) len)
              i1        (skip-ws s ni len)]
          (when (or (>= i1 len) (not= (get s i1) \:))
            (throw (ex-info "JSON: expected : in object" {})))
          (let [[v i2] (parse-value s (skip-ws s (inc i1) len) len key-fn)
                kk     (if key-fn (key-fn kstr) kstr)
                i3     (skip-ws s i2 len)]
            (cond
              (and (< i3 len) (= (get s i3) \,))
              (recur (skip-ws s (inc i3) len) (assoc! acc kk v))
              (and (< i3 len) (= (get s i3) \}))
              [(persistent! (assoc! acc kk v)) (inc i3)]
              :else (throw (ex-info "JSON: malformed object" {})))))))))

(defn- parse-value [s i len key-fn]
  (let [i (skip-ws s i len)]
    (when (>= i len) (throw (ex-info "JSON: unexpected end of input" {})))
    (let [c (get s i)]
      (cond
        (= c \{) (parse-object  s (inc i) len key-fn)
        (= c \[) (parse-array   s (inc i) len key-fn)
        (= c \") (parse-string-literal s (inc i) len)
        (= c \t) (if (lit? s i len "true")  [true  (+ i 4)]
                     (throw (ex-info "JSON: invalid literal" {})))
        (= c \f) (if (lit? s i len "false") [false (+ i 5)]
                     (throw (ex-info "JSON: invalid literal" {})))
        (= c \n) (if (lit? s i len "null")  [nil   (+ i 4)]
                     (throw (ex-info "JSON: invalid literal" {})))
        (or (= c \-) (<= 48 (int c) 57)) (parse-number s i len)
        :else (throw (ex-info (str "JSON: unexpected character " c) {}))))))

(defn read-str
  "Parse a JSON string into Clojure data: objects -> maps (string keys unless
  :key-fn is given), arrays -> vectors, numbers -> Long/Double. Options
  (keyword-value pairs): :key-fn (applied to each object key string)."
  [s & {:keys [key-fn] :as opts}]
  (let [[v] (parse-value s 0 (count s) key-fn)]
    v))
