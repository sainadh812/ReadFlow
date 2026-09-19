#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs app/src/main/assets/vendor .deps gradle/wrapper
fetch() {
  local url="$1" dest="$2" checksum="$3"
  if ! test -f "$dest" || ! printf '%s  %s\n' "$checksum" "$dest" | sha256sum -c --status; then
    curl --fail --location --retry 3 "$url" -o "$dest.part"
    printf '%s  %s\n' "$checksum" "$dest.part" | sha256sum -c
    mv "$dest.part" "$dest"
  fi
}
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar app/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471
# Readability is fetched separately with its pinned digest from vendor-lock.json.
python3 scripts/vendor.py
