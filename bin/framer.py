#!/usr/bin/env python3
"""Byte-copying bracket-depth framer.

transit-format's verify feeds a persistent process newline-less transit+json
values (each exemplar is one JSON array, no trailing \\n). jolt's stdin is
line-based (read-line blocks on \\n), so it can't read that stream directly.

This filter copies bytes verbatim and emits one value + "\\n" whenever the
bracket/brace depth returns to zero *outside* a string. It does NOT parse the
JSON — jolt still sees transit-clj's exact bytes, only with newlines injected.
"""
import sys

def main():
    depth = 0
    in_str = False
    esc = False
    acc = bytearray()
    data = sys.stdin.buffer
    out = sys.stdout.buffer
    while True:
        b = data.read(1)
        if not b:
            if acc:
                out.write(bytes(acc) + b"\n"); out.flush()
            return
        c = b[0]
        acc.append(c)
        if in_str:
            if esc:
                esc = False
            elif c == 0x5c:          # backslash
                esc = True
            elif c == 0x22:          # closing quote
                in_str = False
        else:
            if c == 0x22:            # opening quote
                in_str = True
            elif c in (0x5b, 0x7b):  # [ {
                depth += 1
            elif c in (0x5d, 0x7d):  # ] }
                depth -= 1
                if depth == 0:
                    out.write(bytes(acc) + b"\n"); out.flush()
                    acc = bytearray()

main()
