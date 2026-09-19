"""Host-only real-audio smoke test. Android uses Kotlin/JNI, not this script."""
import argparse
import gc
import json
from pathlib import Path
import platform
import time
import numpy as np
import onnxruntime as ort
import sherpa_onnx
import soundfile as sf
from scipy.signal import resample_poly

ROOT = Path(__file__).resolve().parents[1]
DEPS = ROOT / ".deps"
TEXT = "Reading opens a quiet space for thought. Tap any word to hear the sentence from that point."

def run(name):
    begin = time.perf_counter()
    if name == "kokoro":
        folder = DEPS / "kokoro-en-v0_19"
        model = sherpa_onnx.OfflineTtsModelConfig(kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
            model=str(folder / "model.onnx"), voices=str(folder / "voices.bin"),
            tokens=str(folder / "tokens.txt"), data_dir=str(folder / "espeak-ng-data")), num_threads=2, debug=False)
    else:
        folder = DEPS / "sherpa-onnx-pocket-tts-int8-2026-01-26"
        model = sherpa_onnx.OfflineTtsModelConfig(pocket=sherpa_onnx.OfflineTtsPocketModelConfig(
            lm_flow=str(folder / "lm_flow.int8.onnx"), lm_main=str(folder / "lm_main.int8.onnx"),
            encoder=str(folder / "encoder.onnx"), decoder=str(folder / "decoder.int8.onnx"),
            text_conditioner=str(folder / "text_conditioner.onnx"), vocab_json=str(folder / "vocab.json"),
            token_scores_json=str(folder / "token_scores.json")), num_threads=2, debug=False)
    engine = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=model))
    loaded = time.perf_counter()
    if name == "pocket":
        voice, rate = sf.read(DEPS / "voices/alba.wav", dtype="float32")
        if voice.ndim > 1:
            voice = voice.mean(axis=1)
        config = sherpa_onnx.GenerationConfig()
        config.reference_audio = voice
        config.reference_sample_rate = rate
        config.num_steps = 5
        audio = engine.generate(TEXT, config)
    else:
        audio = engine.generate(TEXT, sid=0, speed=1.0)
    generated = time.perf_counter()
    out = ROOT / "docs/evidence" / name
    out.mkdir(parents=True, exist_ok=True)
    sf.write(out / "paragraph.wav", audio.samples, audio.sample_rate, subtype="PCM_16")
    # Use the quantized audio that is actually played, not a different artifact.
    samples, rate = sf.read(out / "paragraph.wav", dtype="float32")
    del engine
    gc.collect()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(DEPS / "alignment/model.onnx"), options, providers=["CPUExecutionProvider"])
    divisor = np.gcd(rate, 16000)
    x = resample_poly(samples, 16000 // divisor, rate // divisor).astype(np.float32)
    x = (x - x.mean()) / np.sqrt(x.var() + 1e-7)
    logits = session.run(None, {"input_values": x[None, :]})[0][0]
    logits.astype("<f4").tofile(out / "logits.f32")
    finished = time.perf_counter()
    report = dict(host=platform.platform(), processor=platform.processor(), model=name, sherpa="1.13.8",
        ort=ort.__version__, threads=2, text=TEXT, sampleRate=rate, sampleCount=len(samples), frames=len(logits),
        coldLoadSeconds=loaded-begin, synthesisSeconds=generated-loaded, alignmentLoadAndEmissionSeconds=finished-generated,
        durationSeconds=len(samples)/rate, combinedRtf=(finished-loaded)/(len(samples)/rate),
        boundaryValidation="Not manually audited. Host emissions only; Kotlin test produces boundaries.")
    (out / "run.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report), flush=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("model", choices=["kokoro", "pocket"])
    run(parser.parse_args().model)
