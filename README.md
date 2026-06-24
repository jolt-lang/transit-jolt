# transit-jolt

Transit (JSON encoding) for the [Jolt](https://github.com/jolt-lang/jolt) Clojure
runtime (Chez Scheme host, not the JVM). Implements the Transit 0.8 wire format
and passes the full [transit-format](https://github.com/cognitect/transit-format)
compliance suite.

The behavioral reference is `src/jolt/reference.js` (the JS public facade) and
the golden exemplars under transit-format. See `JOLT_BUGS.md` for known gaps in
the Jolt runtime that the port works around.

## Usage

```clojure
(ns example
  (:require [jolt.transit :as t]))

(t/write {:name "transit" :tags #{:a :b}})
;; => "[\"^ \",\"name\",\"transit\",\"tags\",[\"~#set\",[\"~:a\",\"~:b\"]]]"

(t/read (t/write {:name "transit" :tags #{:a :b}}))
;; => {:name "transit" :tags #{:a :b}}
```

The facade is two functions: `(t/write value)` -> transit-JSON string,
`(t/read string)` -> Jolt value. There are no reader/writer objects.

## Type mapping

Jolt has no Java types, so read returns host-native values where possible and
small records where the host can't represent the Transit type directly:

- Transit `null`, `boolean`, `string`, `keyword`, `symbol` -> the obvious native value.
- `integer` -> native number (bounded by Jolt's 64-bit ints).
- `decimal` -> native number; integer/decimal distinction is preserved on the wire.
- `time` -> value for which `inst?` is true.
- `uuid` -> value for which `uuid?` is true.
- `uri` -> `java.net.URI`.
- `big integer` beyond 2^63 -> `jolt.transit.Bigint` record holding the decimal
  string (Jolt overflows native ints; see GAP-3 in `JOLT_BUGS.md`).
- `char` -> `jolt.transit.CharV` record holding a 1-char string (Jolt's `char`
  coerces numerically rather than string->char).
- `array`, `list`, `set`, `map` -> the matching Jolt collection.
- Unknown tags -> `jolt.transit.TaggedValue` record (`tag` + `value`), re-emitted
  on write so non-compliant round-trips stay lossless.

## Running the tests

The golden suite reads the paired `.edn`/`.json` exemplars from a checkout of
transit-format and asserts decode + round-trip:

```sh
# set once per shell
export TRANSIT_FORMAT=~/src/transit-format
~/src/jolt-lang/jolt/bin/joltc run -m transit.golden-test
```

`bin/` holds the cross-implementation verification harness:

- `bin/roundtrip` — the persistent stdin->stdout round-tripper that
  transit-format's `transit.verify` invokes (pipes through `bin/framer.py`,
  which newline-delimits values to work around Jolt's line-based stdin).
- `bin/verify` — runs transit-format's `transit.verify` against this project's
  `bin/roundtrip`, using transit-clj as the cross-impl oracle (needs the Maven
  classpath from transit-format's `pom.xml`).
- `bin/roundtrip-corpus` — standalone disk-based self-check over every golden
  exemplar pair.

## Status

All three checks are green: 52 golden assertions, 189 cross-implementation
round-trips (134 exemplar + 48 EDN corner cases + 7 Transit-JSON corner cases),
and the full corpus self-check.

## License

Apache License, Version 2.0. See `LICENSE`. Based on
[transit-cljs](https://github.com/cognitect/transit-cljs).
