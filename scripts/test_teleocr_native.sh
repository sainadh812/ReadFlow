#!/usr/bin/env bash
# Native semantic smoke: same JNI source used by the Android APK, hosted on Linux.
# Requires JDK17, cmake, ninja, g++, and the two checksum-verified GGUF files in
# .deps/teleocr-model. Does not download or upload any document.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/prepare_teleocr.py
cmake -S .deps/teleocr-llama -B .deps/teleocr-llama/build -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF \
  -DLLAMA_OPENSSL=OFF -DMTMD_VIDEO=OFF
cmake --build .deps/teleocr-llama/build --target llama mtmd -j2
mkdir -p .deps/teleocr-jni-host
readflow_java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
g++ -std=c++17 -shared -fPIC -O2 \
  -I"$readflow_java_home/include" -I"$readflow_java_home/include/linux" \
  -I.deps/teleocr-llama/include -I.deps/teleocr-llama/ggml/include \
  -I.deps/teleocr-llama/tools/mtmd app/src/main/cpp/teleocr_jni.cpp \
  .deps/teleocr-llama/build/bin/libllama.so .deps/teleocr-llama/build/bin/libmtmd.so \
  .deps/teleocr-llama/build/bin/libggml.so .deps/teleocr-llama/build/bin/libggml-base.so \
  -o .deps/teleocr-jni-host/libreadflow_teleocr.so
javac -d .deps/teleocr-jni-host scripts/teleocr-smoke/TeleOcrNative.java
export LD_LIBRARY_PATH="$PWD/.deps/teleocr-llama/build/bin${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
java -cp .deps/teleocr-jni-host app.readflow.ingest.TeleOcrNative \
  "${1:-docs/evidence/teleocr/text-fixture.png}" \
  "${2:-Please output the text content from the image.}" "${3:-}"
