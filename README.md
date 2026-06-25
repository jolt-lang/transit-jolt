# transit-jolt

Transit (JSON encoding) for the [Jolt](https://github.com/jolt-lang/jolt) Clojure
runtime (Chez Scheme host). Implements the Transit 0.8 wire format.

## Usage

```clojure
(ns example
  (:require [jolt.transit :as t]))

(t/write {:name "transit" :tags #{:a :b}})
;; => "[\"^ \",\"name\",\"transit\",\"tags\",[\"~#set\",[\"~:a\",\"~:b\"]]]"

(t/read (t/write {:name "transit" :tags #{:a :b}}))
;; => {:name "transit" :tags #{:a :b}}
```

The facade is two functions: `(t/write value)` returns a transit-JSON string,
`(t/read string)` decodes one. There are no reader/writer objects.

## Type mapping

Jolt has no Java types, so `read` returns host-native values where possible and
small records where the host can't represent the Transit type directly:

- Transit `null`, `boolean`, `string`, `keyword`, `symbol` -> the obvious native value.
- `integer` -> native number (bounded by Jolt's 64-bit ints).
- `decimal` -> native number; integer/decimal distinction is preserved on the wire.
- `time` -> value for which `inst?` is true.
- `uuid` -> value for which `uuid?` is true.
- `uri` -> `java.net.URI`.
- `big integer` beyond 2^63 -> `jolt.transit.Bigint` (holds the decimal string).
- `char` -> `jolt.transit.CharV` (holds a 1-char string).
- `array`, `list`, `set`, `map` -> the matching Jolt collection.
- Unknown tags -> `jolt.transit.TaggedValue` (`tag` + `value`), re-emitted on write
  so non-conforming round-trips stay lossless.

## Tests

The test suite reads golden exemplars from a checkout of
[transit-format](https://github.com/cognitect/transit-format), pointed at by the
`TRANSIT_FORMAT` environment variable:

```sh
export TRANSIT_FORMAT=~/src/transit-format
~/src/jolt-lang/jolt/bin/joltc run -m transit.golden-test
```

It decodes every golden `.json`, checks the decode against its paired `.edn`
where Jolt can read it, and asserts read/write/read idempotency over the whole
exemplar corpus.

## License

Apache License, Version 2.0. See `LICENSE`. Based on
[transit-cljs](https://github.com/cognitect/transit-cljs).
