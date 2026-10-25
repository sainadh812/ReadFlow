#!/usr/bin/env python3
"""Exercise ReadFlow's exact C ABI frontend on a host with ONNX Runtime >=1.22.

No eSpeak is invoked. These checks validate lifecycle and phonemization behavior,
not subjective voice quality or Android device performance.
"""
import argparse
import ctypes
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ort", required=True, type=Path)
    args = parser.parse_args()
    ort = args.ort.resolve()
    if not ort.is_file():
        raise SystemExit(f"ONNX Runtime not found: {ort}")
    subprocess.run(["python3", str(ROOT / "scripts/prepare_misaki.py"), "--host"], check=True, stdout=sys.stderr)
    lib = ctypes.CDLL(str(ROOT / ".deps/libreadflow_misaki_host.so"))
    lib.ReadFlowMisakiCreate.argtypes = [ctypes.c_char_p, ctypes.c_int]
    lib.ReadFlowMisakiPhonemize.argtypes = [ctypes.c_uint64, ctypes.c_char_p]
    lib.ReadFlowMisakiClose.argtypes = [ctypes.c_uint64]
    lib.ReadFlowMisakiFree.argtypes = [ctypes.c_void_p]
    for name in ("Create", "Phonemize", "Close"):
        getattr(lib, "ReadFlowMisaki" + name).restype = ctypes.c_void_p

    def call(name, *arguments):
        pointer = getattr(lib, "ReadFlowMisaki" + name)(*arguments)
        assert pointer, f"Null response from {name}"
        try:
            return json.loads(ctypes.string_at(pointer).decode("utf-8"))
        finally:
            lib.ReadFlowMisakiFree(pointer)

    def create():
        result = call("Create", str(ort).encode(), 1)
        assert "error" not in result, result
        return result["handle"]

    def phonemize(handle, phrase):
        start = time.monotonic()
        result = call("Phonemize", handle, phrase.encode())
        assert "error" not in result and result["complete"] and result["phonemes"], result
        result.update(text=phrase, hostElapsedMs=round((time.monotonic() - start) * 1000, 2))
        return result

    assert "error" in call("Create", str(ort).encode(), 0)
    first, second = create(), create()
    try:
        rows = [phonemize(first, phrase) for phrase in [
            "Hello world.",
            "People aged twenty to twenty four can compare income and expenses.",
            "Milanković's example includes an emergency fund.",
            "I read a book yesterday.",
            "I will read a book tomorrow.",
            "The wind will wind the clock.",
            "I read books every day.",
        ]]
        assert rows[2]["fallbackCalls"] > 0, "Unknown-name neural fallback was not exercised"
        assert "ɹˈɛd" in rows[3]["phonemes"] and "ɹˈid" in rows[4]["phonemes"], rows[3:5]
        assert "wˈɪnd" in rows[5]["phonemes"] and "wˈInd" in rows[5]["phonemes"], rows[5]
        too_long = call("Phonemize", first, ("qxz" * 30).encode())
        assert "error" in too_long, "Oversized unresolved spans must fail instead of dropping words"
        assert call("Close", first)["closed"]
        assert call("Close", first)["closed"], "Close must be idempotent"
        assert "error" in call("Phonemize", first, b"Hello")
        assert "error" in call("Phonemize", 999999, b"Hello")
        rows.append(phonemize(second, "The second frontend remains usable."))
        third = create()
        try:
            rows.append(phonemize(third, "A reopened frontend remains usable."))
        finally:
            assert call("Close", third)["closed"]
        print(json.dumps({"status": "PASSED", "runtime": str(ort), "rows": rows,
                          "knownPronunciationLimitations": [
                              "The raw accented name Milanković's loses the final affricate in neural fallback; keep ReadFlow normalization/overrides.",
                              "Upstream dictionary maps present-tense read (VBP) to /rɛd/, so I read books every day is pronounced incorrectly."
                          ],
                          "note": "PASSED refers to lifecycle, explicit failures and checked context pairs; it is not a universal pronunciation score. Host validation only."}, ensure_ascii=False, indent=2))
    finally:
        call("Close", first)
        call("Close", second)


if __name__ == "__main__":
    main()
