#!/usr/bin/env python3
"""Add reviewed explanatory speech to the existing demo without re-encoding video.

Run with .venv/bin/python. Requires numpy, scipy, soundfile, and the repository's
imageio-ffmpeg environment. The original product and continuous evidence videos
are never modified. This creates a clearly separate post-production edition.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from fractions import Fraction
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import textwrap

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

ROOT = Path(__file__).resolve().parents[1]
SOURCE_SHA256 = "cda2837be33ece81a7e6ad205d59cf328d9695498c0323162511892b9057b2c2"
RATE = 48000
TARGET_LUFS = -18.0
PEAK_CEILING_DB = -3.0
EDGE_FADE_SECONDS = 0.05
PREFIX = "Read-Assist-0.3.2"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def relative(path: Path) -> str:
    return str(path.resolve().relative_to(ROOT))


def artifact(path: Path) -> dict:
    return {"file": relative(path), "bytes": path.stat().st_size, "sha256": sha256(path)}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


class FFmpeg:
    def __init__(self):
        self.binary = subprocess.run(
            [str(ROOT / "build/store-media-venv/bin/python"), "-c",
             "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())"],
            check=True, capture_output=True, text=True).stdout.strip()
        self.version = self.call(["-version"]).stdout.splitlines()[0]

    def call(self, args: list, *, binary: bool = False):
        result = subprocess.run([self.binary, "-nostdin", *map(str, args)],
                                capture_output=True, text=not binary)
        if result.returncode:
            error = result.stderr.decode(errors="replace") if binary else result.stderr
            raise RuntimeError("FFmpeg failed: " + error[-3000:])
        return result

    def decode_audio(self, path: Path) -> np.ndarray:
        result = self.call(["-v", "error", "-i", path, "-map", "0:a:0", "-vn",
                            "-ac", "1", "-af", f"aresample={RATE}:async=1:first_pts=0",
                            "-f", "f32le", "-"], binary=True)
        values = np.frombuffer(result.stdout, dtype="<f4").astype(np.float64)
        require(len(values) > 0 and np.isfinite(values).all(), "Empty or nonfinite decoded audio")
        return values

    def loudness(self, path: Path) -> dict:
        result = self.call(["-hide_banner", "-i", path, "-map", "0:a:0", "-vn",
                            "-af", f"loudnorm=I={TARGET_LUFS}:TP={PEAK_CEILING_DB}:LRA=11:print_format=json",
                            "-f", "null", "-"])
        blocks = re.findall(r'\{\s*"input_i".*?\}', result.stderr, flags=re.S)
        require(bool(blocks), "FFmpeg loudness measurement missing")
        data = json.loads(blocks[-1])
        measured = {"integratedLufs": float(data["input_i"]),
                    "truePeakDbtp": float(data["input_tp"]),
                    "loudnessRangeLu": float(data["input_lra"])}
        require(all(math.isfinite(v) for v in measured.values()), "Nonfinite loudness measurement")
        return measured

    def video_hash(self, path: Path) -> str:
        output = self.call(["-v", "error", "-i", path, "-map", "0:v:0", "-c", "copy",
                            "-f", "hash", "-hash", "sha256", "-"]).stdout
        match = re.search(r"SHA256=([a-f0-9]{64})", output)
        require(match is not None, "Encoded video hash missing")
        return match[1]

    def video_packets(self, path: Path) -> tuple[str, list[str], float]:
        output = self.call(["-v", "error", "-i", path, "-map", "0:v:0", "-c", "copy",
                            "-f", "framehash", "-hash", "sha256", "-"]).stdout
        match = re.search(r"#tb 0: ([0-9]+/[0-9]+)", output)
        require(match is not None, "Video time base missing")
        timebase = Fraction(match[1])
        records = [line for line in output.splitlines() if line and not line.startswith("#")]
        ends = [int(line.split(",")[2]) + int(line.split(",")[3]) for line in records]
        require(bool(ends), "Video packets missing")
        return match[1], records, float(max(ends) * timebase)

    def decode_check(self, path: Path, has_video: bool) -> None:
        args = ["-v", "error", "-i", path, "-map", "0:a:0"]
        if has_video:
            args += ["-map", "0:v:0", "-fps_mode", "passthrough", "-enc_time_base", "demux"]
        result = self.call([*args, "-f", "null", "-"])
        require(not result.stderr.strip(), "Decode errors: " + result.stderr[-2000:])


def sample_stats(values: np.ndarray) -> dict:
    require(np.isfinite(values).all(), "Nonfinite audio sample")
    peak = float(np.max(np.abs(values)))
    rms = float(np.sqrt(np.mean(np.square(values))))
    return {"sampleRate": RATE, "channels": 1, "sampleCount": len(values),
            "durationSeconds": len(values) / RATE,
            "peakLinear": peak, "peakDbfs": 20 * math.log10(peak) if peak else None,
            "rmsDbfs": 20 * math.log10(rms) if rms else None,
            "clippedSamples": int(np.count_nonzero(np.abs(values) >= 1.0))}


def fade_edges(values: np.ndarray) -> np.ndarray:
    values = values.copy()
    count = min(round(EDGE_FADE_SECONDS * RATE), len(values) // 2)
    values[:count] *= np.linspace(0, 1, count)
    values[-count:] *= np.linspace(1, 0, count)
    return values


def normalized_clip(ff: FFmpeg, values: np.ndarray, path: Path) -> tuple[np.ndarray, dict]:
    values = fade_edges(values)
    sf.write(path, values, RATE, subtype="FLOAT")
    measured = ff.loudness(path)
    peak_db = sample_stats(values)["peakDbfs"]
    gain_db = min(TARGET_LUFS - measured["integratedLufs"],
                  PEAK_CEILING_DB - max(measured["truePeakDbtp"], peak_db))
    values *= 10 ** (gain_db / 20)
    sf.write(path, values, RATE, subtype="FLOAT")
    return values, {"inputLoudness": measured, "constantGainDb": gain_db,
                    "outputLoudness": ff.loudness(path), "stats": sample_stats(values),
                    "edgeFadeSeconds": EDGE_FADE_SECONDS,
                    "dynamicCompression": False, "timeStretch": False}


def timestamp(seconds: float, decimal: str) -> str:
    value = round(seconds * 1000)
    hours, value = divmod(value, 3600000)
    minutes, value = divmod(value, 60000)
    sec, ms = divmod(value, 1000)
    return f"{hours:02}:{minutes:02}:{sec:02}{decimal}{ms:03}"


def captions(cues: list[dict], output: Path, duration: float) -> tuple[Path, Path]:
    srt, vtt = output / f"{PREFIX}-narrated-demo.en.srt", output / f"{PREFIX}-narrated-demo.en.vtt"
    previous_end = 0.0
    srt_parts, vtt_parts = [], ["WEBVTT\n"]
    for index, cue in enumerate(cues, 1):
        start, end = cue["start"], cue["end"]
        require(previous_end <= start < end <= duration, "Invalid or overlapping caption times")
        require(cue["text"].strip() and "-->" not in cue["text"], "Invalid caption text")
        lines = "\n".join(textwrap.wrap(cue["text"], width=48, break_long_words=False))
        require(len(lines.splitlines()) <= 3, "Caption needs more than three lines")
        srt_parts.append(f"{index}\n{timestamp(start, ',')} --> {timestamp(end, ',')}\n{lines}\n")
        vtt_parts.append(f"{index}\n{timestamp(start, '.')} --> {timestamp(end, '.')}\n{lines}\n")
        previous_end = end
    srt.write_text("\n".join(srt_parts), encoding="utf-8")
    vtt.write_text("\n".join(vtt_parts), encoding="utf-8")
    return srt, vtt


def build(args) -> None:
    source, script_path, synthesis_path, output = [path.resolve() for path in
        (args.source, args.script, args.synthesis, args.output)]
    require(source != output / source.name, "Output must not replace the original demo")
    require(sha256(source) == SOURCE_SHA256, "Original video hash mismatch")
    script = json.loads(script_path.read_text())
    synthesis = json.loads(synthesis_path.read_text())
    require(script["sourceVideoSha256"] == SOURCE_SHA256, "Script source hash mismatch")
    require(synthesis["status"] == "ALL_SEGMENTS_FIT", "Speech synthesis is not complete")
    require(synthesis["scriptSha256"] == sha256(script_path), "Speech script hash mismatch")
    require(synthesis["generatorSha256"] == sha256(ROOT / synthesis["generator"]), "Speech generator changed")
    expected = script["segments"]
    generated = synthesis["segments"]
    require(len(expected) == len(generated), "Segment counts differ")
    require(len({s["id"] for s in expected}) == len(expected), "Duplicate segment IDs")
    output.mkdir(parents=True, exist_ok=True)
    working = output / "work"
    working.mkdir(exist_ok=True)
    ff = FFmpeg()
    timebase, source_packets, timeline_end = ff.video_packets(source)
    require(74 <= timeline_end < 74.1, "Unexpected video timeline")
    master = np.zeros(round(timeline_end * RATE), dtype=np.float64)
    source_info = artifact(source)
    report = {"schemaVersion": 1, "status": "rendering", "generatedAt": datetime.now(timezone.utc).isoformat(),
              "sourceVideo": source_info, "sourceVideoSha256": SOURCE_SHA256,
              "script": artifact(script_path), "synthesisReport": artifact(synthesis_path),
              "renderer": artifact(Path(__file__)), "ffmpeg": ff.version,
              "timelineDurationSeconds": timeline_end,
              "description": "Separate post-production explanatory voiceover edition with an excerpt of genuine recorded app speech.",
              "humanListeningPerformed": False, "segments": [], "artifacts": {},
              "normalization": {"method": "Independent constant gain after 50ms edge fades; no compression, stretching, or speech truncation.",
                                "targetIntegratedLufs": TARGET_LUFS, "truePeakCeilingDbtp": PEAK_CEILING_DB}}
    cues, intervals = [], []
    for wanted, made in zip(expected, generated):
        require(all(wanted[key] == made[key] for key in ("id", "start", "end", "text")),
                f"Synthesis differs from reviewed script: {wanted['id']}")
        path = (ROOT / made["file"]).resolve()
        require(path.is_relative_to((output / "speech").resolve()), "Speech outside approved input directory")
        require(sha256(path) == made["wavSha256"] and made["fitsWindow"] is True, "Speech hash or fit check failed")
        values, rate = sf.read(path, dtype="float64", always_2d=True)
        require(values.shape[1] == 1, "Expected mono generated voice")
        values = values[:, 0]
        require(abs(len(values) / rate - made["durationSeconds"]) < 1 / rate, "Speech duration mismatch")
        divisor = math.gcd(RATE, rate)
        values = resample_poly(values, RATE // divisor, rate // divisor)
        values, leveling = normalized_clip(ff, values, working / (wanted["id"] + ".wav"))
        start_index = round(wanted["start"] * RATE)
        end_index = start_index + len(values)
        end = end_index / RATE
        require(end <= wanted["end"] + 1 / RATE, "Speech does not fit its reviewed window")
        master[start_index:end_index] += values
        cue = {"id": wanted["id"], "start": start_index / RATE, "end": end,
               "text": wanted["text"], "kind": "generated-explanatory-narration"}
        cues.append(cue)
        intervals.append((start_index, end_index, wanted["id"]))
        report["segments"].append({**cue, "input": artifact(path), "normalization": leveling,
                                   "windowEnd": wanted["end"], "durationSeconds": len(values) / RATE})
    app_window = script["originalAppAudioWindow"]
    original = ff.decode_audio(source)
    first, last = round(app_window["start"] * RATE), round(app_window["end"] * RATE)
    require(0 <= first < last <= len(original), "App audio excerpt outside source")
    excerpt, leveling = normalized_clip(ff, original[first:last], working / "original-app-excerpt.wav")
    master[first:last] += excerpt
    intervals.append((first, last, "original-app-audio"))
    for cue in app_window["captions"]:
        require(app_window["start"] <= cue["start"] < cue["end"] <= app_window["end"], "App caption outside excerpt")
        cues.append({**cue, "kind": "original-recorded-app-speech"})
    report["originalAppAudioExcerpt"] = {"start": app_window["start"], "end": app_window["end"],
                                          "sourceSha256": SOURCE_SHA256, "normalization": leveling,
                                          "otherOriginalAudioMuted": True,
                                          "continuousEvidenceVideoUnchanged": True}
    ordered = sorted(intervals)
    require(all(left[1] <= right[0] for left, right in zip(ordered, ordered[1:])), "Overlapping voices")
    cues.sort(key=lambda cue: cue["start"])
    srt, vtt = captions(cues, output, timeline_end)
    master_path = working / "mixed-soundtrack.wav"
    sf.write(master_path, master, RATE, subtype="PCM_24")
    report["masterAudio"] = {**sample_stats(master), "loudness": ff.loudness(master_path)}
    require(report["masterAudio"]["clippedSamples"] == 0, "Master has clipping")
    video = output / f"{PREFIX}-narrated-demo.mp4"
    audio = output / f"{PREFIX}-explanatory-audio.mp3"
    ff.call(["-v", "error", "-y", "-copyts", "-i", source, "-i", master_path, "-i", srt,
             "-map", "0:v:0", "-map", "1:a:0", "-map", "2:s:0", "-map_metadata", "-1",
             "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-c:s", "mov_text",
             "-metadata", "title=Read Assist 0.3.2 - narrated walkthrough",
             "-metadata", "comment=Explanatory speech added in post-production; original app speech excerpt at 57.5-64.15 seconds.",
             "-metadata:s:a:0", "language=eng", "-metadata:s:s:0", "language=eng",
             "-metadata:s:s:0", "title=English captions", "-disposition:s:0", "0",
             "-avoid_negative_ts", "disabled", "-movflags", "+faststart", video])
    ff.call(["-v", "error", "-y", "-i", master_path, "-c:a", "libmp3lame", "-b:a", "160k",
             "-metadata", "title=Read Assist explanatory audio",
             "-metadata", "comment=Full narrated demo soundtrack; synthetic explanation and recorded app speech excerpt.", audio])
    for path, has_video in ((video, True), (audio, False)):
        ff.decode_check(path, has_video)
        stats = sample_stats(ff.decode_audio(path))
        loudness = ff.loudness(path)
        require(stats["clippedSamples"] == 0 and stats["peakDbfs"] < -1.0, "Encoded audio lacks headroom")
        require(loudness["truePeakDbtp"] < -1.0, "Encoded audio true peak lacks headroom")
        report.setdefault("outputAudioAnalysis", {})[path.name] = {**stats, "loudness": loudness}
    original_video_hash, output_video_hash = ff.video_hash(source), ff.video_hash(video)
    output_timebase, output_packets, output_end = ff.video_packets(video)
    require(original_video_hash == output_video_hash, "Encoded video stream changed")
    require(timebase == output_timebase and source_packets == output_packets, "Video packet timestamps changed")
    require(sha256(source) == SOURCE_SHA256, "Original source changed during rendering")
    require(sha256(script_path) == report["script"]["sha256"] and
            sha256(synthesis_path) == report["synthesisReport"]["sha256"], "Narration inputs changed during rendering")
    report.update({"status": "complete", "decodeValidation": "passed", "videoStreamUnchanged": True,
                   "videoPacketTimestampsUnchanged": True, "sourceEncodedVideoSha256": original_video_hash,
                   "outputEncodedVideoSha256": output_video_hash, "videoPacketCount": len(source_packets),
                   "videoTimebase": timebase, "outputTimelineDurationSeconds": output_end,
                   "captionValidation": {"status": "passed", "cueCount": len(cues), "overlap": False,
                                         "narrationTiming": "Actual generated clip placement and duration; no word-level alignment claimed.",
                                         "originalAppTiming": "Two reviewed original-speech cues supplied in the narration script.",
                                         "cues": cues},
                   "voicesOverlap": False, "sourcePreserved": True,
                   "limitations": ["Human listening and pronunciation review were not performed.",
                                   "The narrated edition is edited promotional media, not continuous playback evidence."]})
    report["artifacts"] = {path.name: artifact(path) for path in (video, audio, srt, vtt)}
    (output / "narrated-build-report.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    print(json.dumps({"status": report["status"], "artifacts": report["artifacts"]}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / f"build/store-media/rendered/{PREFIX}-product-demo.mp4")
    parser.add_argument("--script", type=Path, default=ROOT / "store-assets/release-0.3.2/narration-script.json")
    parser.add_argument("--synthesis", type=Path, default=ROOT / "build/store-media/narrated/speech/synthesis-report.json")
    parser.add_argument("--output", type=Path, default=ROOT / "build/store-media/narrated")
    build(parser.parse_args())
