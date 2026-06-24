(ns transit.golden-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.data.json :as json]
            [jolt.transit :as t]))

(def ^:private dir "/Users/yogthos/src/transit-format/examples/0.8/simple/")

;; READ: decode golden transit-JSON -> Jolt value, compare to a hand-built literal.
;; (We can't read the .edn goldens directly — jolt's reader can't parse #inst / lists etc.
;;  These particular cases use only values jolt can express literally.)
(deftest read-scalars
  (testing "scalar + flat/nested vector goldens"
    (is (= nil
           (t/read (slurp (str dir "nil.json")))))
    (is (= "hello"
           (t/read (slurp (str dir "one_string.json")))))
    (is (= [-5 -4 -3 -2 -1 0 1 2 3 4 5]
           (t/read (slurp (str dir "small_ints.json")))))
    (is (= [:a :ab :abc :abcd :abcde :a1 :b2 :c3 :a_b]
           (t/read (slurp (str dir "keywords.json")))))
    (is (= (mapv symbol ["a" "ab" "abc" "abcd" "abcde" "a1" "b2" "c3" "a_b"])
           (t/read (slurp (str dir "symbols.json")))))
    (is (= [[1 2 3]
            [0 1 2.0 true false "five" :six (symbol "seven") "~eight" nil]]
           (t/read (slurp (str dir "vector_nested.json")))))))

;; WRITE: encode a Jolt value -> transit-JSON. Compare PARSED JSON (not raw string) so
;; insignificant whitespace/key-order don't cause false failures, while int-vs-float and
;; exact strings still must match.
(deftest write-scalars
  (testing "scalar + flat/nested vector goldens"
    (is (= (json/read-str (slurp (str dir "nil.json")))
           (json/read-str (t/write nil))))
    (is (= (json/read-str (slurp (str dir "one_string.json")))
           (json/read-str (t/write "hello"))))
    (is (= (json/read-str (slurp (str dir "small_ints.json")))
           (json/read-str (t/write [-5 -4 -3 -2 -1 0 1 2 3 4 5]))))
    (is (= (json/read-str (slurp (str dir "keywords.json")))
           (json/read-str (t/write [:a :ab :abc :abcd :abcde :a1 :b2 :c3 :a_b]))))
    (is (= (json/read-str (slurp (str dir "symbols.json")))
           (json/read-str (t/write (mapv symbol ["a" "ab" "abc" "abcd" "abcde" "a1" "b2" "c3" "a_b"])))))
    (is (= (json/read-str (slurp (str dir "vector_nested.json")))
           (json/read-str (t/write [[1 2 3]
                                    [0 1 2.0 true false "five" :six (symbol "seven") "~eight" nil]]))))))

;; Collection goldens. WRITE is checked semantically: decode my own output and the
;; golden, compare as Clojure values (map/set order-insensitive, list/vector ordered).
(deftest read-collections
  (testing "map / set / list goldens"
    (is (= {:a 1 :b 2 :c 3}
           (t/read (slurp (str dir "map_simple.json")))))
    (is (= {"first" 1 "second" 2 "third" 3}
           (t/read (slurp (str dir "map_string_keys.json")))))
    (is (= {:simple {:a 1 :b 2 :c 3} :mixed {:a 1 :b "a string" :c true}}
           (t/read (slurp (str dir "map_nested.json")))))
    (is (= #{1 2 3}
           (t/read (slurp (str dir "set_simple.json")))))
    (is (= #{nil 0 2.0 "~eight" 1 true "five" false (symbol "seven") :six}
           (t/read (slurp (str dir "set_mixed.json")))))
    (is (= (list 1 2 3)
           (t/read (slurp (str dir "list_simple.json")))))
    (is (= (list 0 1 2.0 true false "five" :six (symbol "seven") "~eight" nil)
           (t/read (slurp (str dir "list_mixed.json")))))))

(deftest write-collections
  (testing "map / set / list goldens (semantic round-trip vs golden)"
    (let [rt (fn [v golden]
               (= (t/read (t/write v)) (t/read (slurp (str dir golden)))))]
      (is (rt {:a 1 :b 2 :c 3} "map_simple.json"))
      (is (rt {"first" 1 "second" 2 "third" 3} "map_string_keys.json"))
      (is (rt {:simple {:a 1 :b 2 :c 3} :mixed {:a 1 :b "a string" :c true}} "map_nested.json"))
      (is (rt #{1 2 3} "set_simple.json"))
      (is (rt #{nil 0 2.0 "~eight" 1 true "five" false (symbol "seven") :six} "set_mixed.json"))
      (is (rt (list 1 2 3) "list_simple.json"))
      (is (rt (list 0 1 2.0 true false "five" :six (symbol "seven") "~eight" nil) "list_mixed.json")))))

(deftest read-cache
  (testing "read-cache resolves ^n refs in nested/cached goldens"
    ;; oracle: the .edn is plain Clojure, so jolt's own reader gives the expected value
    (let [edn-> (fn [n] (read-string (slurp (str dir n ".edn"))))
          js->  (fn [n] (t/read (slurp (str dir n ".json"))))]
      (is (= (edn-> "map_nested")     (js-> "map_nested")))
      (is (= (edn-> "map_10_nested")  (js-> "map_10_nested")))
      (is (= (edn-> "set_nested")     (js-> "set_nested")))
      (is (= (edn-> "list_nested")    (js-> "list_nested"))))))

(deftest read-tagged-scalars
  (testing "~m inst / ~u uuid / ~r uri / ~z special floats / numeric map keys"
    (is (= (java.util.Date. 946728000000)
           (t/read (slurp (str dir "one_date.json")))))
    (is (= [(java.util.Date. -6106017600000) (java.util.Date. 0)
            (java.util.Date. 946728000000) (java.util.Date. 1396909037000)]
           (t/read (slurp (str dir "dates_interesting.json")))))
    (is (= (java.util.UUID/fromString "5a2cbea3-e8c6-428b-b525-21239370dd55")
           (t/read (slurp (str dir "one_uuid.json")))))
    (is (= (mapv #(java.util.UUID/fromString %)
                 ["5a2cbea3-e8c6-428b-b525-21239370dd55"
                  "d1dc64fa-da79-444b-9fa4-d4412f427289"
                  "501a978e-3a3e-4060-b3be-1cf2bd4b1a38"
                  "b3ba141a-a776-48e4-9fae-a28ea8571f58"])
           (t/read (slurp (str dir "uuids.json")))))
    (is (= (mapv #(.toString (java.net.URI. %))
                 ["http://example.com" "ftp://example.com"
                  "file:///path/to/file.txt" "http://www.詹姆斯.com/"])
           (mapv #(.toString %) (t/read (slurp (str dir "uris.json"))))))
    (let [v (t/read (slurp (str dir "vector_special_numbers.json")))]
      (is (Double/isNaN (nth v 0)))
      (is (= Double/POSITIVE_INFINITY (nth v 1)))
      (is (= Double/NEGATIVE_INFINITY (nth v 2))))
    (is (= {1 "one" 2 "two"}
           (t/read (slurp (str dir "map_numeric_keys.json")))))))

(deftest write-tagged-scalars
  (testing "tagged scalars emit the exact golden wire form"
    ;; compare parsed JSON: ~zNaN etc. are ordinary strings there, so = works.
    (let [wj (fn [v g] (= (json/read-str (t/write v))
                          (json/read-str (slurp (str dir g)))))]
      (is (wj (java.util.Date. 946728000000) "one_date.json"))
      (is (wj [(java.util.Date. -6106017600000) (java.util.Date. 0)
               (java.util.Date. 946728000000) (java.util.Date. 1396909037000)]
              "dates_interesting.json"))
      (is (wj (java.util.UUID/fromString "5a2cbea3-e8c6-428b-b525-21239370dd55") "one_uuid.json"))
      (is (wj (mapv #(java.util.UUID/fromString %)
                    ["5a2cbea3-e8c6-428b-b525-21239370dd55"
                     "d1dc64fa-da79-444b-9fa4-d4412f427289"
                     "501a978e-3a3e-4060-b3be-1cf2bd4b1a38"
                     "b3ba141a-a776-48e4-9fae-a28ea8571f58"]) "uuids.json"))
      (is (wj (mapv #(java.net.URI. %)
                    ["http://example.com" "ftp://example.com"
                     "file:///path/to/file.txt" "http://www.詹姆斯.com/"]) "uris.json"))
      (is (wj [Double/NaN Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY]
              "vector_special_numbers.json"))
      (is (wj {1 "one" 2 "two"} "map_numeric_keys.json")))))

(deftest read-cmap
  (testing "~#cmap carries non-stringable keys"
    (is (= {nil "null as map key" [1 2] "Array as key to force cmap"}
           (t/read (slurp (str dir "cmap_null_key.json")))))
    (is (= {[1 1] "one" [2 2] "two"}
           (t/read (slurp (str dir "map_vector_keys.json")))))))

(deftest write-cmap
  (testing "non-stringable keys force ~#cmap; entries compare semantically
            (transit maps are unordered — jolt's array-map may reorder keys)"
    ;; jolt's array-map does not preserve insertion order, so we compare the
    ;; decoded maps (= is order-insensitive) rather than exact wire bytes.
    (let [rt (fn [v g] (= (t/read (t/write v))
                          (t/read (slurp (str dir g)))))]
      (is (rt (array-map nil "null as map key" [1 2] "Array as key to force cmap")
              "cmap_null_key.json"))
      (is (rt {[1 1] "one" [2 2] "two"} "map_vector_keys.json")))))

(deftest bigint-roundtrip
  (testing "~i/~n round-trip idempotently — jolt can't hold BigInts, so spec
            fidelity against transit-clj is deferred to bin/verify"
    (doseq [g ["ints_interesting.json" "ints_interesting_neg.json"]]
      (let [v (t/read (slurp (str dir g)))]
        (is (= v (t/read (t/write v))) g)))))

(defn -main [& _]
  (let [m (run-tests 'transit.golden-test)]
    (System/exit (if (zero? (+ (:fail m) (:error m))) 0 1))))
