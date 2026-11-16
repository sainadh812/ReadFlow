#!/usr/bin/env python3
"""Insert an authentic audible reading sample into the approved product pitch.

The approved picture clips and explanatory narration are reused unchanged. The
narrator pauses at an existing quiet cut while a reviewed native recording plays
at its original A/V timing. Highlights are part of that recording, never drawn.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import math
from pathlib import Path
import re
import shutil
import textwrap

import numpy as np
from PIL import Image, ImageDraw
import soundfile as sf

from build_narrated_demo import FFmpeg, artifact, sample_stats, sha256, timestamp
from build_product_pitch import FPS, HEIGHT, INK, WIDTH, font, frame, render_scene

ROOT = Path(__file__).resolve().parents[1]
APPROVED = ROOT / "build/product-pitch/final"
OUTPUT = ROOT / "build/product-pitch/listening-final"
PREFIX = "Read-Assist-0.3.2-listening-pitch"
APPROVED_VIDEO_SHA256 = "a0a2d856dfeedc97739c8a6f00fe05e73654ac0a8ac16394067b0eecb6e606e3"
INSERT_TIME = 14.6
RATE = 48000


def require(ok: bool, reason: str):
    if not ok:
        raise RuntimeError(reason)


def pinned_path(entry: dict) -> Path:
    path = (ROOT / entry["file"]).resolve()
    require(path.is_relative_to(ROOT) and path.is_file(), "Missing local input")
    require(sha256(path) == entry["sha256"], "Input hash mismatch: " + str(path))
    return path


def decoded_frame_hashes(ff: FFmpeg, path: Path) -> list[str]:
    result = ff.call(["-v", "error", "-threads", "2", "-i", path, "-map", "0:v:0", "-an",
                      "-vf", "format=yuv420p", "-fps_mode", "passthrough", "-f", "framehash",
                      "-hash", "sha256", "-"])
    return [line.rsplit(",", 1)[1].strip() for line in result.stdout.splitlines()
            if line and not line.startswith("#")]


def write_captions(approved: dict, sample: dict, source_start: float, duration: float):
    cues = []
    for cue in approved["captions"]["cues"]:
        require(not cue["start"] < INSERT_TIME < cue["end"], "Insertion would split an existing caption")
        offset = duration if cue["start"] >= INSERT_TIME else 0
        cues.append({**cue, "start": cue["start"] + offset, "end": cue["end"] + offset,
                     "kind": "explanatory-narration"})
    for cue in sample["captions"]:
        start = INSERT_TIME + cue["sourceStart"] - source_start
        end = INSERT_TIME + cue["sourceEnd"] - source_start
        require(INSERT_TIME <= start < end <= INSERT_TIME + duration, "App caption outside reviewed sample")
        cues.append({"start": start, "end": end, "text": cue["text"], "kind": "recorded-app-speech"})
    cues.sort(key=lambda cue: cue["start"])
    srt, vtt = OUTPUT / f"{PREFIX}.en.srt", OUTPUT / f"{PREFIX}.en.vtt"
    srt_parts, vtt_parts, previous_end = [], ["WEBVTT\n"], 0.0
    for index, cue in enumerate(cues, 1):
        require(previous_end <= cue["start"] < cue["end"], "Overlapping captions")
        lines = "\n".join(textwrap.wrap(cue["text"], width=44))
        require(len(lines.splitlines()) <= 3, "App captions need shorter reviewed cues")
        srt_parts.append(f"{index}\n{timestamp(cue['start'], ',')} --> {timestamp(cue['end'], ',')}\n{lines}\n")
        # Keep browser captions above the enlarged paragraph so its final
        # highlighted words stay visible throughout the listening sample.
        settings = " line:10%" if cue["kind"] == "recorded-app-speech" else ""
        if settings:
            cue["vttSettings"] = settings.strip()
        vtt_parts.append(f"{index}\n{timestamp(cue['start'], '.')} --> {timestamp(cue['end'], '.')}{settings}\n{lines}\n")
        previous_end = cue["end"]
    srt.write_text("\n".join(srt_parts))
    vtt.write_text("\n".join(vtt_parts))
    return srt, vtt, cues


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sample-manifest", type=Path, required=True,
                        help="Reviewed native source/window/transcript/crop/captions manifest.")
    args = parser.parse_args()
    sample_path = args.sample_manifest.resolve()
    sample = json.loads(sample_path.read_text())
    require(sample["status"] == "reviewed", "Native sample must be reviewed before rendering")
    require(sample.get("captions") and sample.get("transcript"), "Reviewed speech transcript and cues are required")
    native = pinned_path({"file": sample["sourceFile"], "sha256": sample["sourceSha256"]})
    provenance = [artifact(pinned_path(entry)) for entry in sample.get("sourceProvenance", [])]
    require(bool(provenance), "Native recording provenance is required")
    approved_report_path = APPROVED / "product-pitch-build-report.json"
    approved = json.loads(approved_report_path.read_text())
    old_video = pinned_path(approved["artifacts"]["Read-Assist-0.3.2-product-pitch.mp4"])
    require(sha256(old_video) == APPROVED_VIDEO_SHA256, "Approved product pitch changed")
    old_master_path = pinned_path(approved["approvedAudioMaster"])
    for entry in approved["sourceCaptures"]:
        pinned_path(entry)
    source_start = float(sample["sourceStart"])
    source_end = float(sample["sourceEnd"])
    require(0 <= source_start < source_end, "Invalid source window")
    require(abs(source_start * FPS - round(source_start * FPS)) < 1e-5 and
            abs(source_end * FPS - round(source_end * FPS)) < 1e-5,
            "Reviewed source boundaries must align to 30fps while enclosing complete speech")
    count_frames = round((source_end - source_start) * FPS)
    duration = count_frames / FPS
    require(3 <= duration <= 18, "Expected a short complete listening sample")
    work = OUTPUT / "work"
    work.mkdir(parents=True, exist_ok=True)
    ff = FFmpeg()

    # Keep source AAC's native initial timestamp and any capture packet gaps.
    # The same source-time window is used for the picture and audio.
    native_full = ff.decode_audio(native)
    start_sample, end_sample = round(source_start * RATE), round(source_end * RATE)
    require(end_sample <= len(native_full), "Native audio ends before the requested window")
    excerpt = native_full[start_sample:end_sample].copy()
    require(len(excerpt) == round(duration * RATE), "Native window sample/frame duration mismatch")
    fade_samples = round(.03 * RATE)
    fade = np.ones(len(excerpt), dtype=np.float64)
    fade[:fade_samples] = np.linspace(0, 1, fade_samples)
    fade[-fade_samples:] = np.linspace(1, 0, fade_samples)
    raw_excerpt = work / "native-excerpt-before-gain.wav"
    sf.write(raw_excerpt, excerpt * fade, RATE, subtype="FLOAT")
    source_loudness = ff.loudness(raw_excerpt)
    gain_db = min(-18.0 - source_loudness["integratedLufs"], -1.5 - source_loudness["truePeakDbtp"])
    app_audio = excerpt * fade * (10 ** (gain_db / 20))
    gained_path = work / "native-excerpt-gained.wav"
    sf.write(gained_path, app_audio, RATE, subtype="PCM_24")
    gained_loudness = ff.loudness(gained_path)
    old_master, rate = sf.read(old_master_path, dtype="float64")
    require(rate == RATE and old_master.ndim == 1, "Expected approved mono 48kHz narrator master")
    split = round(INSERT_TIME * RATE)
    nearby = old_master[split - round(.04 * RATE):split + round(.04 * RATE)]
    split_peak_db = sample_stats(nearby)["peakDbfs"]
    require(split_peak_db < -55, "Narrator insertion does not fall in a quiet sentence break")
    mixed = np.concatenate([old_master[:split], app_audio, old_master[split:]])
    require(np.array_equal(mixed[:split], old_master[:split]) and
            np.array_equal(mixed[split + len(app_audio):], old_master[split:]),
            "Existing explanatory narrator samples changed")
    master = work / "listening-pitch-master.wav"
    sf.write(master, mixed, RATE, subtype="PCM_24")

    # Insert a new authentic clip at an existing cut. Every approved clip is
    # stream-copied, preserving its decoded frames rather than re-encoding it.
    crop = sample["crop"]
    require(len(crop) == 4 and all(isinstance(value, int) and value >= 0 for value in crop), "Invalid reviewed crop")
    x, y, crop_w, crop_h = crop
    require(crop_w > 0 and crop_h > 0 and x + crop_w <= 1080 and y + crop_h <= 1920, "Crop outside native recording")
    scale = min(1250 / crop_w, 762 / crop_h)
    width, height = round(crop_w * scale), round(crop_h * scale)
    demo_scene = {"id": "listening-demo", "layout": "detail", "title": "Listen.\nFollow\nalong.",
                  "titleXY": [88, 280], "titleSize": 86, "showDraftLabel": False,
                  "subline": "Actual app narration", "sublineXY": [92, 640], "sublineSize": 28,
                  "file": str(native), "sourceStart": source_start, "sourceEnd": source_end,
                  "crop": crop, "box": [590 + (1250 - width) // 2, 230 + (762 - height) // 2, width, height],
                  "start": INSERT_TIME, "end": INSERT_TIME + duration, "duration": duration,
                  "sourcePlaybackSpeedMultiplier": 1}
    print(f"Rendering authentic app audio/highlight sample {source_start:.3f}–{source_end:.3f}s", flush=True)
    demo_video = render_scene(ff, demo_scene, work)
    timeline, segments, inserted = [], [], False
    for original_scene in approved["timeline"]:
        if not inserted and original_scene["start"] >= INSERT_TIME:
            require(abs(original_scene["start"] - INSERT_TIME) < 1e-6, "Insertion must match an approved scene boundary")
            segments.append(demo_video)
            timeline.append({**demo_scene, "file": str(native.relative_to(ROOT))})
            inserted = True
        path = APPROVED / "work" / f"{original_scene['id']}.mp4"
        require(path.is_file(), "Approved encoded scene file missing")
        segments.append(path)
        offset = duration if original_scene["start"] >= INSERT_TIME else 0
        timeline.append({**original_scene, "start": original_scene["start"] + offset,
                         "end": original_scene["end"] + offset, "showDraftLabel": False})
    require(inserted, "Listening sample was not inserted")
    concat = work / "concat.txt"
    concat.write_text("".join(f"file '{path.resolve()}'\n" for path in segments))
    picture = work / "listening-pitch-picture.mp4"
    ff.call(["-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", concat,
             "-c:v", "copy", "-an", "-movflags", "+faststart", picture])
    srt, vtt, cues = write_captions(approved, sample, source_start, duration)
    video = OUTPUT / f"{PREFIX}.mp4"
    ff.call(["-v", "error", "-y", "-i", picture, "-i", master, "-i", srt,
             "-map", "0:v:0", "-map", "1:a:0", "-map", "2:s:0", "-c:v", "copy",
             "-c:a", "aac", "-b:a", "160k", "-c:s", "mov_text", "-disposition:s:0", "0",
             "-metadata", "title=Read Assist - listen and follow along",
             "-metadata", "comment=Synthetic product narration pauses for genuine recorded app speech and native word highlights.",
             "-metadata:s:a:0", "language=eng", "-metadata:s:s:0", "language=eng",
             "-movflags", "+faststart", video])
    audio = OUTPUT / f"{PREFIX}-audio.mp3"
    ff.call(["-v", "error", "-y", "-i", master, "-c:a", "libmp3lame", "-b:a", "160k",
             "-metadata", "title=Read Assist pitch with authentic listening demonstration", audio])
    poster = OUTPUT / "listening-pitch-poster.jpg"
    shutil.copyfile(APPROVED / "product-pitch-poster.jpg", poster)
    audio_checks = {}
    for path, has_video in ((video, True), (audio, False)):
        ff.decode_check(path, has_video)
        decoded = ff.decode_audio(path)
        stats = sample_stats(decoded)
        require(stats["clippedSamples"] == 0, "Encoded audio clips")
        decoded_sample = decoded[split:split + len(app_audio)]
        correlation = float(np.corrcoef(app_audio, decoded_sample)[0, 1])
        require(correlation > .995, "Native speech timing or waveform changed unexpectedly")
        audio_checks[path.name] = {**stats, "loudness": ff.loudness(path),
                                  "nativeExcerptZeroLagCorrelation": correlation}
    before_frames = decoded_frame_hashes(ff, old_video)
    after_frames = decoded_frame_hashes(ff, video)
    split_frame = round(INSERT_TIME * FPS)
    require(after_frames[:split_frame] == before_frames[:split_frame] and
            after_frames[split_frame + count_frames:] == before_frames[split_frame:],
            "An approved decoded picture frame changed outside the insertion")
    _, _, measured_duration = ff.video_packets(video)
    expected_duration = approved["durationSeconds"] + duration
    require(abs(measured_duration - expected_duration) < 1e-5, "Final duration differs from timeline")

    # Review frames sample the actual final output, including the enlarged
    # native highlights. Source/output sample comparisons retain original time.
    contact = Image.new("RGB", (1920, math.ceil(len(timeline) / 3) * 396), "#E9E5D9")
    draw = ImageDraw.Draw(contact)
    for index, scene in enumerate(timeline):
        at = (scene["start"] + scene["end"]) / 2
        still = frame(ff, str(video), at)
        left, top = index % 3 * 640, index // 3 * 396
        contact.paste(still.resize((640, 360), Image.Resampling.LANCZOS), (left, top))
        draw.text((left + 12, top + 368), f"{scene['id']} · {at:.2f}s", fill=INK, font=font(20))
    contact.save(OUTPUT / "listening-pitch-contact-sheet.jpg", quality=92)
    app_only_interval = {"timelineStart": INSERT_TIME, "timelineEnd": INSERT_TIME + duration,
                         "sourceStart": source_start, "sourceEnd": source_end,
                         "transcript": sample["transcript"], "gainDb": gain_db,
                         "sourceFile": str(native.relative_to(ROOT)), "sourceSha256": sha256(native),
                         "crop": crop, "box": demo_scene["box"],
                         "frameMapping": {"outputFirstFrame": split_frame,
                                          "sourceFirstFrameOn30fpsTimeline": round(source_start * FPS),
                                          "frameCount": count_frames, "fps": FPS,
                                          "sourceFilter": "fps=30:start_time=0",
                                          "formula": "sourceTime = sourceStart + outputTime - timelineStart"},
                         "editorialPictureFadeSeconds": .10,
                         "sourceAudioTimestampHandling": "Decode on native t=0 with aresample=48000:async=1:first_pts=0; preserve initial offset and packet gaps, then cut the same source window as the picture.",
                         "sourceLoudness": source_loudness, "gainedLoudness": gained_loudness,
                         "edgeFadeSeconds": .03, "constantGainOnly": False,
                         "constantGainAcrossSpokenInterval": True,
                         "audioProcessing": "Constant gain with 30 ms fades at the excerpt boundaries; no compression or retiming.",
                         "speechResynthesis": False,
                         "syntheticHighlightsAdded": False, "audioRetimed": False, "videoRetimed": False,
                         "narratorAudioPresent": False}
    report = {"schemaVersion": 1, "status": "complete", "publicationReady": True,
              "createdAt": datetime.now(timezone.utc).isoformat(), "decodeValidation": "passed",
              "narrationProvider": "kokoro", "authenticAppAudioIncluded": True,
              "narratorPausedDuringDemo": True, "syntheticUiAdded": False,
              "sourcePlaybackSpeedMultiplier": 1, "voiceTimeStretch": False,
              "description": "The approved product pitch now pauses its narrator for a genuine native app-reading sample with recorded word highlights.",
              "priorApprovedVideo": artifact(old_video), "priorApprovedReport": artifact(approved_report_path),
              "durationSeconds": measured_duration, "frameCount": len(after_frames),
              "approvedFramesOutsideInsertionUnchanged": True, "approvedNarratorSamplesUnchanged": True,
              "insertionNarratorPausePeakDbfs": split_peak_db, "demo": app_only_interval,
              "narrationSegments": [{"timelineStart": 0, "timelineEnd": INSERT_TIME, "originalStart": 0, "originalEnd": INSERT_TIME},
                                    {"timelineStart": INSERT_TIME + duration, "timelineEnd": expected_duration,
                                     "originalStart": INSERT_TIME, "originalEnd": approved["durationSeconds"]}],
              "sampleReview": artifact(sample_path), "sampleProvenance": provenance,
              "sourceCaptures": [*approved["sourceCaptures"], artifact(native)], "timeline": timeline,
              "captionCount": len(cues), "captions": {"cueCount": len(cues), "cues": cues},
              "audioAnalysis": audio_checks, "humanListeningPerformed": False,
              "renderer": artifact(Path(__file__)),
              "artifacts": {path.name: artifact(path) for path in (video, audio, srt, vtt, poster)}}
    require(sha256(old_video) == APPROVED_VIDEO_SHA256, "Prior approved video was modified")
    (OUTPUT / "listening-pitch-build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": "complete", "demo": app_only_interval, "artifacts": report["artifacts"]}, indent=2))


if __name__ == "__main__":
    main()
