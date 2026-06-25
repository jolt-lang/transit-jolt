# transit-jolt

Transit (JSON encoding) for the [Jolt](https://github.com/jolt-lang/jolt) Clojure
runtime (Chez Scheme host). Implements the Transit 0.8 wire format.

## Dependency

Add to your `deps.edn` (Jolt resolves the `:git/sha`; the `:git/tag` is for
reference):

```clojure
{:deps {jolt-lang/transit-jolt
        {:git/url "https://github.com/jolt-lang/transit-jolt"
         :git/tag "v0.1.0"
         :git/sha "909255b3bbdc04bbd1c7782b4056f8d2f082ec8d"}}}
```

It pulls `org.clojure/data.json` (the JSON reader/writer) transitively.

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

The suite compares against golden exemplars from
[transit-format](https://github.com/cognitect/transit-format), which is vendored
as a git submodule at `transit-format/`. Initialize it after cloning:

```sh
git clone --recurse-submodules https://github.com/jolt-lang/transit-jolt.git   # or, for an existing clone:
git submodule update --init
```

Then run the suite with the Jolt compiler (`joltc`) on your `PATH`:

```sh
joltc run -m transit.golden-test
```

Set `TRANSIT_FORMAT` to point at a different transit-format checkout if needed.
The suite decodes every golden `.json`, checks the decode against its paired
`.edn` where Jolt can read it, and asserts read/write/read idempotency over the
whole exemplar corpus.

## License

Apache License, Version 2.0. See `LICENSE`. Based on
[transit-cljs](https://github.com/cognitect/transit-cljs).
