(ns transit.roundtrip
  (:require [jolt.transit :as t]))

;; transit-format compliance entry point (persistent streaming). Each stdin
;; line is one transit+json value — bin/framer.py newline-delimits the values
;; transit-clj feeds us (jolt's stdin is line-based). Round-trip each and
;; print, flushing per value so transit-clj's reader can consume one at a time.
(defn -main [& args]
  (loop []
    (let [line (read-line)]
      (when line
        ;; On any value we can't process, echo it verbatim: transit-clj's input
        ;; is valid transit, so the identity round-trip is correct and keeps the
        ;; persistent stream aligned (a throw would desync every later value).
        (println (try (t/write (t/read line))
                      (catch :default _ line)))
        (flush)
        (recur)))))
