#!/usr/bin/env python3
"""Build a narration-led product film from verified, unaltered-speed app captures."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import math
from pathlib import Path
import subprocess
import textwrap
import re

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont
import shutil
import soundfile as sf

from build_narrated_demo import FFmpeg, sha256, artifact, sample_stats, timestamp

ROOT = Path(__file__).resolve().parents[1]
CAPTURE = ROOT / "build/store-media/combined-capture"
OUTPUT = ROOT / "build/product-pitch/rendered"
STORY = ROOT / "store-assets/release-0.3.2/product-pitch/story-timeline.json"
PREFIX = "Read-Assist-0.3.2-product-pitch"
WIDTH, HEIGHT, FPS = 1920, 1080, 30
PAPER = "#F6F2E9"
FOREST = "#183C32"
INK = "#233A31"
APPROVED_DRAFT_SHA256 = "231821b46755c114ec1c532b0f64bf2c2011ed9cb48fb1d8aab56190dc383279"


def font(size: int, serif=False):
    name = "source_serif.ttf" if serif else "atkinson_next.ttf"
    return ImageFont.truetype(str(ROOT / "app/src/main/res/font" / name), size)


def draw_text(draw, xy, text, size, color, serif=False, spacing=1.12):
    x, y = xy
    face = font(size, serif)
    for line in text.split("\n"):
        draw.text((x, y), line, font=face, fill=color, anchor="lt")
        y += round(size * spacing)


def panel(background: Image.Image, box: list[int]):
    x, y, w, h = box
    shadow = Image.new("RGBA", background.size)
    draw = ImageDraw.Draw(shadow)
    draw.rectangle((x - 4, y + 12, x + w + 4, y + h + 18), fill=(10, 22, 16, 46))
    background.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(24)))
    ImageDraw.Draw(background).rectangle((x - 2, y - 2, x + w + 1, y + h + 1), fill="#C8C8B8")


def background(scene: dict) -> Image.Image:
    dark = scene.get("dark", False)
    color, text_color = (FOREST, PAPER) if dark else (PAPER, INK)
    image = Image.new("RGBA", (WIDTH, HEIGHT), color)
    draw = ImageDraw.Draw(image)
    # Editorial ink/paper shapes sit outside the authentic app footage.
    accent = "#2A4B3E" if dark else "#EBE6D9"
    if scene["layout"] in ("opening", "closing"):
        draw.ellipse((920, -600, 2320, 800), fill=accent)
        draw.arc((750, 260, 2260, 1770), 190, 290, fill="#B6AE96" if not dark else "#67806E", width=2)
    else:
        draw.line((85, 134, 215, 134), fill="#9BA48A" if dark else "#8B9A82", width=3)
    brand_xy = scene.get("brandXY", [88, 65])
    draw_text(draw, brand_xy, "Read Assist", scene.get("brandSize", 31), text_color)
    if scene.get("showDraftLabel", True):
        draw_text(draw, (brand_xy[0], brand_xy[1] + scene.get("brandSize", 31) + 18),
                  "Editing preview · final HeyGen narration pending", 20,
                  "#B7C8B8" if dark else "#626B5C")
    title = scene.get("title", "")
    if title:
        draw_text(draw, scene["titleXY"], title, scene.get("titleSize", 102), text_color, serif=True)
    if scene.get("subline"):
        draw_text(draw, scene["sublineXY"], scene["subline"], scene.get("sublineSize", 32),
                  "#B7C8B8" if dark else "#626B5C", spacing=1.32)
    if scene.get("box"):
        panel(image, scene["box"])
    if scene["layout"] == "closing":
        draw_text(draw, (130, 895), "Offline listening and definitions after pack downloads.", 31, "#B7C8B8")
        draw_text(draw, (130, 943), "No account. No ads.", 31, "#B7C8B8")
    return image.convert("RGB")


def frame(ff: FFmpeg, name: str, seconds: float) -> Image.Image:
    result = ff.call(["-v", "error", "-i", CAPTURE / name,
                      "-vf", f"fps={FPS}:start_time=0,trim=start_frame={round(seconds*FPS)},setpts=PTS-STARTPTS",
                      "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-"], binary=True)
    return Image.open(io.BytesIO(result.stdout)).convert("RGB")


def composite(ff: FFmpeg, scene: dict, at: float | None = None) -> Image.Image:
    image = background(scene)
    if scene.get("file"):
        source = frame(ff, scene["file"], at if at is not None else scene["sourceStart"])
        if scene.get("crop"):
            x, y, w, h = scene["crop"]
            source = source.crop((x, y, x + w, y + h))
        x, y, w, h = scene["box"]
        source = source.resize((w, h), Image.Resampling.LANCZOS)
        image.paste(source, (x, y))
    return image


def scene_templates() -> dict[str, dict]:
    return {
        "hook": {"layout": "opening", "dark": True, "title": "Too much to read.\nToo little time.",
                 "brandXY": [100, 95], "brandSize": 60,
                 "titleXY": [100, 295], "titleSize": 100, "subline": "Make room for the words.", "sublineXY": [104, 570],
                 "file": "01-library.mp4", "sourceStart": 0.5, "crop": [48, 684, 984, 648],
                 "box": [1030, 330, 816, 538]},
        "library": {"layout": "phone", "title": "Your reading.\nReady when\nyou are.",
                    "titleXY": [850, 290], "titleSize": 112, "brandXY": [850, 65],
                    "file": "01-library.mp4", "sourceStart": 4.5, "box": [124, 0, 608, 1080]},
        "play": {"layout": "phone", "title": "Let the page\ncome to you.",
                 "titleXY": [850, 345], "titleSize": 112, "brandXY": [850, 65],
                 "file": "06-background-playback.mp4", "sourceStart": 3.2, "box": [124, 0, 608, 1080]},
        "follow": {"layout": "detail", "title": "Stay with\nthe words.",
                   "titleXY": [88, 370], "titleSize": 86,
                   "file": "06-background-playback.mp4", "sourceStart": 7.1,
                   "crop": [48, 640, 984, 600], "box": [590, 230, 1250, 762]},
        "pdf": {"layout": "phone", "dark": True, "title": "The page\nyou know.",
                "titleXY": [110, 360], "titleSize": 112,
                "file": "02-reading.mp4", "sourceStart": 2.8, "box": [1168, 0, 608, 1080]},
        "away": {"layout": "phone", "title": "Take the words\nwith you.",
                 "titleXY": [850, 345], "titleSize": 108, "brandXY": [850, 65],
                 "file": "06-background-playback.mp4", "sourceStart": 9.7, "box": [124, 0, 608, 1080]},
        "media": {"layout": "detail", "dark": True, "title": "A little more freedom.",
                  "titleXY": [240, 160], "titleSize": 88,
                  "file": "06-background-playback.mp4", "sourceStart": 13.5,
                  "crop": [32, 584, 1016, 520], "box": [260, 320, 1400, 716]},
        "lookup": {"layout": "phone", "title": "A new word.\nNo detour.",
                   "titleXY": [850, 345], "titleSize": 112, "brandXY": [850, 65],
                   "file": "04-dictionary.mp4", "sourceStart": 23.4, "box": [124, 0, 608, 1080]},
        "definition": {"layout": "detail", "title": "Stay with\nthe meaning.",
                       "titleXY": [95, 355], "titleSize": 108,
                       "file": "04-dictionary.mp4", "sourceStart": 32.2,
                       "crop": [0, 800, 1080, 1080], "box": [970, 170, 820, 820]},
        "light": {"layout": "detail", "dark": True, "title": "Settle into\nyour page.",
                  "titleXY": [95, 355], "titleSize": 108,
                  "file": "03-reading-light.mp4", "sourceStart": 14.5,
                  "crop": [40, 610, 1000, 580], "box": [910, 300, 930, 540]},
        "warm": {"layout": "phone", "title": "A warmer\nplace to read.",
                 "titleXY": [850, 345], "titleSize": 108, "brandXY": [850, 65],
                 "file": "03-reading-light.mp4", "sourceStart": 28.8, "box": [124, 0, 608, 1080]},
        "closing": {"layout": "closing", "dark": True, "title": "Read when you can.\nListen when you like.",
                    "titleXY": [130, 400], "titleSize": 100, "brandXY": [130, 165], "brandSize": 112},
    }


def contact_sheet(ff: FFmpeg, scenes: list[dict], path: Path, previews=False) -> None:
    width = 640
    rows = math.ceil(len(scenes) / 3)
    sheet = Image.new("RGB", (width * 3, rows * 396), "#E9E5D9")
    draw = ImageDraw.Draw(sheet)
    for i, scene in enumerate(scenes):
        at = scene.get("previewSourceTime", scene.get("sourceStart"))
        still = composite(ff, scene, at)
        x, y = i % 3 * width, i // 3 * 396
        sheet.paste(still.resize((640, 360), Image.Resampling.LANCZOS), (x, y))
        draw.text((x + 12, y + 368), scene["id"], fill=INK, font=font(20))
    sheet.save(path, quality=92)


def round_frame(value: float) -> float:
    return round(value * FPS) / FPS


def build_timeline(narration: dict) -> tuple[list[dict], list[dict], float]:
    paragraphs, cursor = [], .7
    gaps = [.65, 1.2, 1.1, 2.0]
    for row, gap in zip(narration["paragraphs"], gaps):
        placed = {**row, "timelineStart": round_frame(cursor)}
        placed["timelineEnd"] = placed["timelineStart"] + row["durationSeconds"]
        paragraphs.append(placed)
        cursor = placed["timelineEnd"] + gap
    duration = math.ceil(cursor * FPS) / FPS
    p = {row["id"]: row for row in paragraphs}
    exp, understand, close = (p[key]["timelineStart"] for key in ("experience", "understand", "close"))

    def anchor(paragraph, word, fallback):
        matches = [w["start"] for w in p[paragraph].get("ctcWordAnchors", []) if w["word"] == word]
        return matches[0] if matches else fallback

    boundaries = [
        ("hook", 0.0, .5),
        ("library", p["hook"]["timelineStart"] + 6.1, .5),
        ("play", exp, 2.4),
        ("follow", exp + anchor("experience", "FOLLOW", 2.9) - .15, 7.7),
        ("pdf", exp + anchor("experience", "KEEP", 4.28) - .15, 3.5),
        ("away", exp + anchor("experience", "SWITCH", 6.86) - .2, 9.7),
        ("media", p["experience"]["timelineEnd"] - .8, 13.7),
        ("lookup", understand, 23.4),
        ("definition", understand + anchor("understand", "ITS", 1.78) - .05, 32.2),
        ("light", understand + 4.1, 15.9),
        ("warm", p["understand"]["timelineEnd"], 28.8),
        ("closing", close + 5.1, None),
    ]
    templates = scene_templates()
    scenes = []
    for i, (key, start, source_start) in enumerate(boundaries):
        start = round_frame(start)
        end = round_frame(boundaries[i + 1][1]) if i + 1 < len(boundaries) else duration
        if end <= start:
            raise RuntimeError("Narrator timings require a revised story edit")
        scene = {"id": key, **templates[key], "start": start, "end": end,
                 "duration": round_frame(end - start), "sourcePlaybackSpeedMultiplier": 1}
        if source_start is not None:
            scene["sourceStart"] = round_frame(source_start)
            scene["sourceEnd"] = scene["sourceStart"] + scene["duration"]
        scenes.append(scene)
    return scenes, paragraphs, duration


def write_captions(paragraphs: list[dict], duration: float) -> tuple[Path, Path, list[dict]]:
    cues = []
    for paragraph in paragraphs:
        for sentence in paragraph.get("sentenceAnchors", []):
            # Sentence bounds are recognizer guidance, not claimed manual alignment.
            words = sentence["text"].split()
            groups, current = [], []
            for word in words:
                if len(" ".join(current + [word])) > 78 and current:
                    groups.append(current)
                    current = []
                current.append(word)
            if current:
                groups.append(current)
            count, offset = sum(len(group) for group in groups), 0
            span = sentence["end"] - sentence["start"]
            for group in groups:
                start = paragraph["timelineStart"] + sentence["start"] + span * offset / count
                end = paragraph["timelineStart"] + sentence["start"] + span * (offset + len(group)) / count
                cues.append({"start": max(paragraph["timelineStart"], start - .07),
                             "end": min(paragraph["timelineEnd"], end + .04), "text": " ".join(group)})
                offset += len(group)
    for i in range(len(cues) - 1):
        cues[i]["end"] = min(cues[i]["end"], cues[i + 1]["start"])
    srt = OUTPUT / "product-pitch-editing-preview.en.srt"
    vtt = OUTPUT / "product-pitch-editing-preview.en.vtt"
    srt_parts, vtt_parts = [], ["WEBVTT\n"]
    for index, cue in enumerate(cues, 1):
        assert 0 <= cue["start"] < cue["end"] <= duration
        text = "\n".join(textwrap.wrap(cue["text"], width=44))
        srt_parts.append(f"{index}\n{timestamp(cue['start'], ',')} --> {timestamp(cue['end'], ',')}\n{text}\n")
        vtt_parts.append(f"{index}\n{timestamp(cue['start'], '.')} --> {timestamp(cue['end'], '.')}\n{text}\n")
    srt.write_text("\n".join(srt_parts))
    vtt.write_text("\n".join(vtt_parts))
    return srt, vtt, cues


def render_scene(ff: FFmpeg, scene: dict, work: Path) -> Path:
    bg = work / f"{scene['id']}.png"
    background(scene).save(bg)
    result = work / f"{scene['id']}.mp4"
    args = ["-v", "error", "-y", "-loop", "1", "-framerate", FPS, "-i", bg]
    if scene.get("file"):
        args += ["-i", CAPTURE / scene["file"]]
        source = (f"[1:v]fps={FPS}:start_time=0,trim=start={scene['sourceStart']}:duration={scene['duration']},"
                  "setpts=PTS-STARTPTS,")
        if scene.get("crop"):
            x, y, w, h = scene["crop"]
            source += f"crop={w}:{h}:{x}:{y},"
        x, y, w, h = scene["box"]
        source += f"scale={w}:{h}:flags=lanczos,setsar=1[app];[0:v][app]overlay={x}:{y}:eof_action=pass[composite];"
    else:
        source = "[0:v]null[composite];"
    color = FOREST if scene.get("dark") else PAPER
    vf = source + (f"[composite]fade=t=in:st=0:d=0.10:color={color},"
                   f"fade=t=out:st={scene['duration']-.10}:d=0.10:color={color},format=yuv420p[v]")
    ff.call([*args, "-filter_complex_threads", "1", "-filter_complex", vf, "-map", "[v]", "-an",
             "-t", scene["duration"], "-r", FPS, "-c:v", "libx264", "-preset", "fast", "-crf", "19",
             "-threads", "2", "-pix_fmt", "yuv420p", "-movflags", "+faststart", result])
    return result


def build_draft(ff: FFmpeg, narration_path: Path) -> None:
    narration = json.loads(narration_path.read_text())
    script_path = ROOT / "store-assets/release-0.3.2/product-pitch/script.json"
    script = json.loads(script_path.read_text())
    if narration["script"]["sha256"] != sha256(script_path):
        raise RuntimeError("Narration script changed")
    for expected, measured in zip(script["paragraphs"], narration["paragraphs"]):
        if expected["id"] != measured["id"] or expected["text"] != measured["text"]:
            raise RuntimeError("Narration differs from approved script")
    scenes, paragraphs, duration = build_timeline(narration)
    manifest = json.loads((CAPTURE / "assembly-manifest.json").read_text())
    pins = {entry["file"]: entry["sha256"] for entry in manifest["files"]}
    source_names = sorted({s["file"] for s in scenes if s.get("file")})
    source_captures = []
    for name in source_names:
        path = CAPTURE / name
        if sha256(path) != pins[name]:
            raise RuntimeError("Source capture hash mismatch: " + name)
        source_captures.append(artifact(path))
    story = {"status": "editing_draft", "publicationReady": False,
             "narrationProvider": "kokoro_local_temporary", "selectedFinalNarrationProvider": "heygen",
             "durationSeconds": duration, "syntheticUiAdded": False, "sourcePlaybackSpeedMultiplier": 1,
             "description": "Authentic full-height app recordings and enlarged authentic detail crops. Editorial typography and 100ms cut fades; no invented UI or taps.",
             "narrationManifest": artifact(narration_path), "sourceCaptures": source_captures,
             "paragraphPlacements": [{"id": row["id"], "start": row["timelineStart"], "end": row["timelineEnd"]} for row in paragraphs],
             "scenes": scenes,
             "reviewNote": "Temporary local narration guides this edit. HeyGen narration must replace it and action timing must be reviewed before publication."}
    STORY.write_text(json.dumps(story, indent=2) + "\n")
    work = OUTPUT / "work"
    work.mkdir(exist_ok=True)
    master = np.zeros(round(duration * 48000), dtype=np.float64)
    for paragraph in paragraphs:
        path = ROOT / paragraph["audio"]["file"]
        if sha256(path) != paragraph["audio"]["sha256"]:
            raise RuntimeError("Narration WAV hash mismatch")
        values, rate = sf.read(path, dtype="float64")
        if rate != 48000 or values.ndim != 1:
            raise RuntimeError("Expected 48kHz mono narration paragraph")
        start = round(paragraph["timelineStart"] * rate)
        master[start:start + len(values)] += values
    audio_wav = OUTPUT / "product-pitch-editing-audio.wav"
    sf.write(audio_wav, master, 48000, subtype="PCM_24")
    srt, vtt, cues = write_captions(paragraphs, duration)
    scene_paths = []
    for scene in scenes:
        print(f"Rendering {scene['id']} {scene['start']:.2f}–{scene['end']:.2f}s", flush=True)
        scene_paths.append(render_scene(ff, scene, work))
    concat = work / "concat.txt"
    concat.write_text("".join(f"file '{path.resolve()}'\n" for path in scene_paths))
    silent = OUTPUT / "product-pitch-silent-edit.mp4"
    ff.call(["-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", concat,
             "-c", "copy", "-movflags", "+faststart", silent])
    preview = OUTPUT / "product-pitch-editing-preview.mp4"
    ff.call(["-v", "error", "-y", "-i", silent, "-i", audio_wav, "-i", srt,
             "-map", "0:v:0", "-map", "1:a:0", "-map", "2:s:0", "-c:v", "copy",
             "-c:a", "aac", "-b:a", "160k", "-c:s", "mov_text", "-disposition:s:0", "0",
             "-metadata", "title=Read Assist product pitch - editing draft with temporary local voice",
             "-metadata:s:s:0", "language=eng", "-movflags", "+faststart", preview])
    ff.decode_check(preview, True)
    silent_check = ff.call(["-v", "error", "-i", silent, "-an", "-f", "null", "-"])
    if silent_check.stderr.strip():
        raise RuntimeError("Silent cut decode error")
    audio_stats = sample_stats(ff.decode_audio(preview))
    if audio_stats["clippedSamples"]:
        raise RuntimeError("Encoded preview audio clips")
    contact_sheet(ff, scenes, OUTPUT / "editing-contact-sheet.jpg")
    report = {**story, "status": "draft_complete", "createdAt": datetime.now(timezone.utc).isoformat(),
              "decodeValidation": "passed", "originalAppAudioMuted": True,
              "humanListeningPerformed": False, "voiceTimeStretch": False,
              "narrationSynthetic": True, "renderer": artifact(Path(__file__)),
              "audioStats": audio_stats, "audioLoudness": ff.loudness(preview),
              "captions": {"cueCount": len(cues), "alignment": "Approximate CTC sentence bounds with proportional splits for long captions; not manual word alignment.", "cues": cues},
              "artifacts": {path.name: artifact(path) for path in [preview, silent, audio_wav, srt, vtt, OUTPUT / "product-pitch-poster.jpg"]}}
    (OUTPUT / "product-pitch-draft-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "durationSeconds": duration, "preview": artifact(preview)}, indent=2))


def stream_hash(ff: FFmpeg, path: Path, selector: str) -> str:
    result = ff.call(["-v", "error", "-i", path, "-map", f"0:{selector}:0", "-c", "copy",
                      "-f", "hash", "-hash", "sha256", "-"])
    match = re.search(r"SHA256=([a-f0-9]{64})", result.stdout)
    if not match:
        raise RuntimeError("Encoded stream hash missing")
    return match[1]


def finalize_approved(ff: FFmpeg) -> None:
    """Remove only draft labels from the exact user-approved picture edit."""
    draft = OUTPUT / "product-pitch-editing-preview.mp4"
    draft_report_path = OUTPUT / "product-pitch-draft-report.json"
    draft_report = json.loads(draft_report_path.read_text())
    if sha256(draft) != APPROVED_DRAFT_SHA256:
        raise RuntimeError("Current draft is not the version approved by the user")
    for entry in draft_report["artifacts"].values():
        if sha256(ROOT / entry["file"]) != entry["sha256"]:
            raise RuntimeError("An approved draft artifact changed")
    for entry in draft_report["sourceCaptures"]:
        if sha256(ROOT / entry["file"]) != entry["sha256"]:
            raise RuntimeError("An approved source capture changed")
    final_dir = ROOT / "build/product-pitch/final"
    work = final_dir / "work"
    work.mkdir(parents=True, exist_ok=True)
    scenes = draft_report["scenes"]
    comparisons, paths = [], []
    for scene in scenes:
        original_bg = Image.open(OUTPUT / "work" / f"{scene['id']}.png").convert("RGB")
        rebuilt_bg = background(scene)
        if ImageChops.difference(original_bg, rebuilt_bg).getbbox() is not None:
            raise RuntimeError("Approved background does not reproduce exactly: " + scene["id"])
        clean_scene = {**scene, "showDraftLabel": False}
        final_bg = background(clean_scene)
        difference = np.any(np.asarray(original_bg) != np.asarray(final_bg), axis=2)
        ys, xs = np.nonzero(difference)
        brand_x, brand_y = scene.get("brandXY", [88, 65])
        label_y = brand_y + scene.get("brandSize", 31) + 18
        expected_bounds = ImageDraw.Draw(original_bg).textbbox(
            (brand_x, label_y), "Editing preview · final HeyGen narration pending", font=font(20), anchor="lt")
        changed_bounds = [int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1]
        if not (expected_bounds[0] <= changed_bounds[0] and expected_bounds[1] <= changed_bounds[1]
                and changed_bounds[2] <= expected_bounds[2] and changed_bounds[3] <= expected_bounds[3]):
            raise RuntimeError("Final background changes extend beyond the draft label")
        comparisons.append({"scene": scene["id"], "approvedBackgroundReproducesExactly": True,
                            "changedPixelCount": int(difference.sum()), "changedBounds": changed_bounds,
                            "labelBounds": list(expected_bounds), "onlyDraftLabelPixelsChanged": True})
        print(f"Finalizing approved {scene['id']} {scene['start']:.2f}–{scene['end']:.2f}s", flush=True)
        paths.append(render_scene(ff, clean_scene, work))
    concat = work / "concat.txt"
    concat.write_text("".join(f"file '{path.resolve()}'\n" for path in paths))
    clean_video = work / "clean-picture.mp4"
    ff.call(["-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", concat,
             "-c", "copy", "-movflags", "+faststart", clean_video])
    video = final_dir / f"{PREFIX}.mp4"
    ff.call(["-v", "error", "-y", "-i", clean_video, "-i", draft,
             "-map", "0:v:0", "-map", "1:a:0", "-map", "1:s:0", "-map_metadata", "-1", "-c", "copy",
             "-metadata", "title=Read Assist - Read when you can. Listen when you like.",
             "-metadata", "comment=Authentic app recordings with synthetic explanatory narration. App audio is muted.",
             "-metadata:s:a:0", "language=eng", "-metadata:s:s:0", "language=eng",
             "-disposition:s:0", "0", "-movflags", "+faststart", video])
    audio = final_dir / f"{PREFIX}-audio.mp3"
    master = OUTPUT / "product-pitch-editing-audio.wav"
    ff.call(["-v", "error", "-y", "-i", master, "-c:a", "libmp3lame", "-b:a", "160k",
             "-metadata", "title=Read Assist product pitch narration",
             "-metadata", "comment=Approved synthetic explanatory narration; original app audio is muted.", audio])
    caption_paths = []
    for extension in ("srt", "vtt"):
        src = OUTPUT / f"product-pitch-editing-preview.en.{extension}"
        dest = final_dir / f"{PREFIX}.en.{extension}"
        shutil.copyfile(src, dest)
        if sha256(src) != sha256(dest):
            raise RuntimeError("Approved captions changed")
        caption_paths.append(dest)
    poster = final_dir / "product-pitch-poster.jpg"
    composite(ff, {**scenes[0], "showDraftLabel": False}).save(poster, quality=95)
    ff.decode_check(video, True)
    ff.decode_check(audio, False)
    source_audio_hash, final_audio_hash = stream_hash(ff, draft, "a"), stream_hash(ff, video, "a")
    if source_audio_hash != final_audio_hash:
        raise RuntimeError("Approved AAC audio stream changed")
    if stream_hash(ff, draft, "s") != stream_hash(ff, video, "s"):
        raise RuntimeError("Approved embedded captions changed")
    approved_audio, final_audio = ff.decode_audio(draft), ff.decode_audio(video)
    if not np.array_equal(approved_audio, final_audio):
        raise RuntimeError("Decoded approved soundtrack changed")
    _, draft_packets, draft_duration = ff.video_packets(draft)
    _, final_packets, final_duration = ff.video_packets(video)
    if len(draft_packets) != len(final_packets) or draft_duration != final_duration:
        raise RuntimeError("Approved picture duration or frame count changed")
    audio_analysis = {}
    for path in (video, audio):
        measured = sample_stats(ff.decode_audio(path))
        if measured["clippedSamples"]:
            raise RuntimeError("Final soundtrack clips")
        audio_analysis[path.name] = {**measured, "loudness": ff.loudness(path)}

    # Compare decoded pictures as well as exact compositor backgrounds. Lossy
    # re-encoding may slightly change nearby pixels; scene inputs are unchanged.
    contact = Image.new("RGB", (1920, 4 * 396), "#E9E5D9")
    contact_draw = ImageDraw.Draw(contact)
    decoded_comparisons = []
    for i, (scene, comparison) in enumerate(zip(scenes, comparisons)):
        at = round_frame(scene["start"] + scene["duration"] / 2)
        before, after = frame(ff, str(draft), at), frame(ff, str(video), at)
        delta = np.abs(np.asarray(before, dtype=np.int16) - np.asarray(after, dtype=np.int16))
        mask = np.ones((HEIGHT, WIDTH), dtype=bool)
        x0, y0, x1, y1 = comparison["labelBounds"]
        mask[max(0, y0 - 32):min(HEIGHT, y1 + 32), max(0, x0 - 32):min(WIDTH, x1 + 32)] = False
        mean_difference = float(delta[mask].mean())
        if mean_difference > 1.5:
            raise RuntimeError("Unexpected decoded picture difference outside removed label")
        decoded_comparisons.append({"scene": scene["id"], "timeSeconds": at,
                                    "meanAbsoluteChannelDifferenceOutsideLabelAnd32pxMargin": mean_difference,
                                    "maximumDifference": int(delta[mask].max()),
                                    "interpretation": "Minor codec quantization differences are expected after re-encoding the picture without the label."})
        x, y = i % 3 * 640, i // 3 * 396
        contact.paste(after.resize((640, 360), Image.Resampling.LANCZOS), (x, y))
        contact_draw.text((x + 12, y + 368), f"{scene['id']} · {at:.2f}s", fill=INK, font=font(20))
    contact.save(final_dir / "final-contact-sheet.jpg", quality=92)
    comparison_report = {"status": "passed", "approvedDraftSha256": APPROVED_DRAFT_SHA256,
                         "backgroundComparisons": comparisons, "decodedFrameComparisons": decoded_comparisons,
                         "sceneCutCropTimingAndSourceInputsUnchanged": True,
                         "encodedAacStreamUnchanged": True, "decodedAudioSamplesIdentical": True,
                         "embeddedCaptionsUnchanged": True, "sidecarCaptionsByteIdentical": True,
                         "frameCount": len(final_packets), "durationSeconds": final_duration}
    (final_dir / "approved-draft-comparison.json").write_text(json.dumps(comparison_report, indent=2) + "\n")
    if sha256(draft) != APPROVED_DRAFT_SHA256:
        raise RuntimeError("Approved draft was modified")
    report = {"schemaVersion": 1, "status": "complete", "publicationReady": True,
              "createdAt": datetime.now(timezone.utc).isoformat(), "narrationProvider": "kokoro",
              "approvedCurrentVersion": True, "approvalBasis": "This video is good to upload.",
              "approvedDraftSha256": APPROVED_DRAFT_SHA256, "approvedDraftReport": artifact(draft_report_path),
              "description": "User-approved product pitch finalized by removing draft labels; the approved narration, scenes, crops, cuts and timing are preserved.",
              "syntheticUiAdded": False, "sourcePlaybackSpeedMultiplier": 1, "narrationSynthetic": True,
              "originalAppAudioMuted": True, "humanListeningPerformed": False, "voiceTimeStretch": False,
              "decodeValidation": "passed", "durationSeconds": final_duration, "frameCount": len(final_packets),
              "sourceCaptures": draft_report["sourceCaptures"], "timeline": scenes,
              "paragraphPlacements": draft_report["paragraphPlacements"], "captions": draft_report["captions"],
              "narrationManifest": draft_report["narrationManifest"], "approvedAudioMaster": artifact(master),
              "approvedEncodedAacSha256": source_audio_hash, "finalEncodedAacSha256": final_audio_hash,
              "encodedAacStreamUnchanged": True, "draftLabelsRemoved": True,
              "labelOnlyComparison": artifact(final_dir / "approved-draft-comparison.json"),
              "audioAnalysis": audio_analysis, "renderer": artifact(Path(__file__)),
              "artifacts": {path.name: artifact(path) for path in [video, audio, *caption_paths, poster]}}
    (final_dir / "product-pitch-build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": "complete", "publicationReady": True, "artifacts": report["artifacts"]}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preview", action="store_true")
    parser.add_argument("--finalize-approved-draft", action="store_true",
                        help="Remove labels from the exact user-approved draft, preserving its voice, captions and edit.")
    parser.add_argument("--narration-manifest", type=Path,
                        default=ROOT / "build/product-pitch/narration/synthesis-report.json")
    args = parser.parse_args()
    ff = FFmpeg()
    if args.finalize_approved_draft:
        finalize_approved(ff)
        return
    OUTPUT.mkdir(parents=True, exist_ok=True)
    templates = scene_templates()
    scenes = [{"id": key, **value} for key, value in templates.items()]
    contact_sheet(ff, scenes, OUTPUT / "design-contact-sheet.jpg", previews=True)
    composite(ff, {"id": "hook", **templates["hook"]}).save(OUTPUT / "product-pitch-poster.jpg", quality=95)
    print(str(OUTPUT / "design-contact-sheet.jpg"))
    if not args.preview:
        build_draft(ff, args.narration_manifest.resolve())


if __name__ == "__main__":
    main()
