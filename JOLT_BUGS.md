# JOLT_BUGS.md

Jolt runtime defects and gaps found while porting Transit. Each entry has a
**Problem** (repro against jolt at `~/src/jolt-lang/jolt`), **Status**, and notes.
Resolved entries are kept for the history; the verifying branch/commit is noted.

---

## BUG-1 — `extend` / `extends?` crashed on a `nil` target  — **RESOLVED**

### Problem (was)
`extend` and `extends?` called `(name atype)` with no nil-guard, so extending a
protocol to `nil` threw `name: expected string/symbol/keyword with irritant nil`,
even though `extend-type` already handled `nil` via `(if (nil? tsym) "nil" ...)`.
This blocked `clojure.data.json` and transit, both of which extend protocols to
`nil` — it was the single hard blocker for this port.

### Status — fixed in latest jolt
On branch `spike/datajson-final` (HEAD `9092b30`) both now work:

```clojure
(defprotocol P (p [this]))
(extend 'nil P {:p (fn [_] :nil-via-extend)}) (p nil)   ;; => :nil-via-extend
(extend-type nil P (p [_] :x)) (extends? P nil)         ;; => true
```

The nil-guard from `extend-type` is now applied in `extend`/`extends?`
(`jolt-core/clojure/core/30-macros.clj` ~line 401).

---

## BUG-2 — `format "%x"` was not case-sensitive — **RESOLVED**

### Problem (was)
`%x` (lowercase) emitted uppercase hex (`"00FF"`); `%X` also uppercase.

### Status — fixed in latest jolt

```clojure
(format "%04x" 255) ;; => "00ff"
(format "%04X" 255) ;; => "00FF"
```

---

## GAP-1 — JSON support — **largely closed**; in-tree stub remains

### Problem
Jolt (Chez host) has no native JSON and no host JSON object to interop with, the
way the JS reference uses `JSON.parse` / `JSON.stringify`. Transit needs a JSON
read/write of plain Clojure data at the wire boundary.

### Status — real `clojure.data.json` now loads
With BUG-1 fixed, the real library resolves and loads as a jolt git dep:

```clojure
;; deps.edn  — NOTE the key is :git/sha, NOT :sha (bare :sha is silently skipped)
{:deps {clojure/data.json {:git/url "https://github.com/clojure/data.json.git"
                           :git/sha "94463ffb54482427fd9b31f264b06bff6dcfd557"}}}
```

Verified under jolt: load, write, read, and deep round-trip all pass.

### In-tree stub (currently used)
`src/clojure/data/json.clj` is a pure-Clojure stub with the same `read-str` /
`write-str` API (keyword-value options). transit-jolt currently uses it — it sits
on the source path and shadows any git dep. It is API-compatible, so swapping to
the real dep later is just delete-the-stub + add-the-line-above.

### Known caveats (affect both layers; root cause is jolt, not the JSON lib)
- **Surrogate pairs don't round-trip** — a JSON `\ud83d\ude00` (😀) decodes to the
  wrong chars under jolt. Not exercised by the transit goldens (all BMP, mostly
  ASCII); revisit only if non-BMP strings are needed.
- **`(type n)` reports `:number` for every number** — but `integer?`, `float?`,
  and `=` all distinguish `1` (Long) from `1.0` (Double), so transit's int-vs-float
  dispatch is unaffected. See "Not a bug" below.

### Resolver quirk (note for future deps)
jolt's deps resolver accepts `:git/url` + `:git/sha`. A coordinate written with a
bare `:sha` is **silently skipped** (only a warning printed), so the dep appears
to be missing. Always use `:git/sha`.

---

## Not a bug — coarse `type` for numbers

`(type 1)` and `(type 1.0)` both report `:number`, so `type` can't tell an integer
from a float. This is NOT a problem for Transit: `class`, `integer?`, `float?`, and
`=` all distinguish `1` from `1.0` (`(= 1 1.0)` → false, `(== 1 1.0)` → true, like
Clojure). Recorded so future readers don't reach for `type` for numeric dispatch.

---

## GAP-2 — stdin is line-based (blocks the transit streaming protocol) — open

### Problem
jolt's stdin is acquired line-by-line (`__stdin-read-line` in
`jolt-core/clojure/core/50-io.clj`); `read-line` and `read` both block until a `\n`
(or EOF). There is no byte/char-level read: `(System/in)` fails ("No static
System/in"), `(.read *in*)` fails ("No method read"), and `instance?`/`.read`
interop on stdin is unavailable.

transit-format's compliance harness (`bin/verify`) feeds a **persistent** process
newline-less transit+json values (each exemplar is one JSON array, no trailing
`\n`), then reads one value back. jolt's line-based stdin deadlocks on this:
`read-line` blocks waiting for a `\n` that never comes, while transit-clj blocks
waiting for the response.

### Workaround in this repo
`bin/framer.py` is a byte-copying bracket-depth filter: it copies transit-clj's
bytes verbatim and emits one value + `\n` whenever bracket/brace depth returns to
zero (outside strings). `bin/roundtrip` pipes `framer.py | joltc ...`, so jolt
reads newline-delimited lines while still seeing transit-clj's exact bytes (no
re-parsing). With this, `bin/verify` passes (189/189 roundtrips). A proper fix is
upstream in jolt: a char/byte-level stdin host seam.

---

## GAP-3 — integers ≥ 2^63 don't round-trip as numbers — open

### Problem
jolt's integers are 64-bit and silently overflow past that:
- `(read-string "9223372036854775808")` (2^63) → a `java.lang.Long` (wraps).
- `(bigint "9223372036854775808")` → a `java.lang.String` (not a bigint).
- `(java.math.BigInteger. "9223372036854775808")` prns as the right digits but
  `(instance? java.math.BigInteger …)` → false, so it's unusable for dispatch.

### Workaround in this repo
Transit's `~n` (BigInt) values are decoded to a `Bigint` record that preserves the
exact decimal **string**; the writer re-emits `~n"<str>"`. transit-clj re-reads that
as a `BigInteger`, so `bin/verify` passes for the bigint exemplars without jolt ever
holding the value numerically. (The edn oracle in `bin/roundtrip-corpus` can't check
these — jolt mangles the bigints on read — but idempotency and the cross-impl
verify both hold.)

---

## GAP-4 — `instance?` inconsistent across host types — open

`(instance? java.util.Date x)`, `(instance? java.util.UUID x)`, and
`(instance? java.net.URI x)` work, but `(instance? java.math.BigInteger x)`,
`(instance? java.math.BigDecimal x)`, and `instance?` on **defrecord** types all
return false for genuine instances. So this port detects its own records
(`Bigint`, `TaggedValue`, `CharV`) by class equality (`(= (class v) <class-var>)`)
rather than `instance?`.

---

## GAP-5 — `array-map` does not preserve insertion order — open

`(keys (array-map nil 1 [2] 3))` → `([2] nil)`, not `(nil [2])`. Clojure array-maps
preserve insertion order. This only affected exact-wire-string comparisons of cmap
output; transit maps are unordered, so semantic round-trips are unaffected. The
cmap golden test compares decoded maps (`=`, order-insensitive).

---

## GAP-6 — `=` is identity-based for `java.net.URI` — open

`(= (java.net.URI. "http://x") (java.net.URI. "http://x"))` → **false** (jolt uses
identity, not `.equals`). UUIDs compare by value; URIs do not. The corpus
self-check (`teq`) compares URIs by `.toString`; the cross-impl verify is
unaffected (transit-clj does the comparison on its side).
