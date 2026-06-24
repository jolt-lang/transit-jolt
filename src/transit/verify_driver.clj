(ns transit.verify-driver
  "Drives transit-format's transit.verify against this project's bin/roundtrip.
  Bypasses transit.verify's supported-impls/sibling-path gate by calling
  verify-impl-encoding directly with an absolute command path."
  (:require [transit.verify :as v]))

(defn -main []
  (let [cmd     (str (System/getProperty "transit.jolt.rtdir") "/bin/roundtrip")
        results (v/verify-impl-encoding cmd :json {})]
    (v/report (assoc results :project "transit-jolt"))
    (let [result-tests (remove #(= (:test-name %) :time) (:tests results))
          every-result (mapcat :result result-tests)
          failed       (remove #(= (:status %) :success) every-result)]
      (flush)
      (System/exit (if (seq failed) 1 0)))))
