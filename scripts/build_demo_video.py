#!/usr/bin/env python3
"""Build a real-footage Read Assist demo and uncut background-playback evidence.

Requires Pillow and imageio-ffmpeg. Input is capture_demo.py's capture directory.
No footage is generated, sped up, repainted, or replaced with a UI mockup.
An optional JSON recipe supplies exact cuts, captions, and audio-sync adjustments.
"""
from __future__ import annotations

import argparse
from array import array
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont
import imageio_ffmpeg

ROOT = Path(__file__).resolve().parents[1]
WIDTH, HEIGHT, FPS = 1920, 1080, 30
PORTRAIT = (1240, 60, 540, 960)
FGS_FILE = "06-background-playback.mp4"
DEFAULT_SEGMENTS = (
    ("01-library.mp4", "A place for every page", "Bring your PDFs, images and saved web articles into one library."),
    ("02-reading.mp4", "Turn the page.\nKeep your place.", "Read the original PDF or switch to text. Swipe through pages like a book."),
    ("03-reading-light.mp4", "Read in your\nown light", "Adjust brightness, add a warm glow and find your comfortable reading pace."),
    ("04-dictionary.mp4", "Stay with\nthe story", "Look up English words without leaving the reader. Definitions work offline after download."),
    ("05-library-themes.mp4", "Make it yours", "Choose Warm Library, Modern Focus or Night Reader. Your library, your style."),
    (FGS_FILE, "Keep listening", "Start narration, switch apps and pause from Android's media controls."),
)


class MediaError(RuntimeError):
    pass


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def number(value, label: str, minimum: float = 0.0) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < minimum:
        raise MediaError(f"{label} must be a finite number of at least {minimum}.")
    return float(value)


def input_file(directory: Path, name: str) -> Path:
    if not isinstance(name, str) or not name or Path(name).is_absolute():
        raise MediaError("Capture filenames must be relative paths.")
    candidate = (directory / name).resolve()
    if not candidate.is_relative_to(directory.resolve()) or not candidate.is_file():
        raise MediaError(f"Capture file is missing or outside the capture directory: {name}")
    return candidate


class FFmpeg:
    def __init__(self):
        self.binary = imageio_ffmpeg.get_ffmpeg_exe()
        self.version = self.call(["-version"]).stdout.splitlines()[0]

    def call(self, arguments: list[str], *, check: bool = True, binary: bool = False):
        result = subprocess.run([self.binary, "-nostdin", *map(str, arguments)],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=not binary, check=False)
        if check and result.returncode:
            error = result.stderr.decode(errors="replace") if binary else result.stderr
            raise MediaError("FFmpeg failed: " + error[-2400:])
        return result

    def probe(self, path: Path) -> dict:
        result = self.call(["-hide_banner", "-i", str(path)], check=False)
        info = result.stderr
        duration = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", info)
        video_line = next((line for line in info.splitlines() if re.search(r"Stream .*Video:", line)), "")
        dimensions = re.search(r"(?:^|[, ])(\d{2,5})x(\d{2,5})(?:[, \[]|$)", video_line)
        fps = re.search(r"([\d.]+) fps", video_line)
        if not duration:
            raise MediaError(f"Could not determine media duration: {path.name}")
        seconds = int(duration[1]) * 3600 + int(duration[2]) * 60 + float(duration[3])
        if seconds <= 0:
            raise MediaError(f"Empty media duration: {path.name}")
        return {"durationSeconds": seconds,
                "width": int(dimensions[1]) if dimensions else None,
                "height": int(dimensions[2]) if dimensions else None,
                "fps": float(fps[1]) if fps else None,
                "hasVideo": bool(video_line),
                "hasAudio": bool(re.search(r"Stream .*Audio:", info))}

    def decode_check(self, path: Path) -> None:
        result = self.call(["-v", "error", "-i", str(path), "-map", "0:v:0", "-map", "0:a?", "-f", "null", "-"])
        if result.stderr.strip():
            raise MediaError(f"Decode reported errors for {path.name}: {result.stderr[-1800:]}")

    def video_stream_hash(self, path: Path) -> str:
        result = self.call(["-v", "error", "-i", str(path), "-map", "0:v:0", "-c", "copy", "-f", "hash", "-hash", "sha256", "-"])
        match = re.search(r"SHA256=([a-f0-9]{64})", result.stdout)
        if not match:
            raise MediaError(f"Could not hash encoded video stream: {path.name}")
        return match[1]

    def audio_stats(self, path: Path) -> dict:
        result = self.call(["-v", "error", "-i", str(path), "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "16000", "-f", "s16le", "-"], binary=True)
        samples = array("h", result.stdout)
        if sys.byteorder != "little":
            samples.byteswap()
        count = len(samples)
        if not count:
            return {"nonSilent": False, "sampleCount": 0}
        squares = sum(value * value for value in samples)
        peak = max(abs(value) for value in samples)
        rms = math.sqrt(squares / count)

        def db(value):
            return round(20 * math.log10(value / 32768), 2) if value else None

        active_windows = 0
        for position in range(0, count, 16000):
            window = samples[position:position + 16000]
            window_rms = math.sqrt(sum(value * value for value in window) / len(window))
            if window_rms / 32768 > 10 ** (-50 / 20):
                active_windows += 1
        return {"nonSilent": peak / 32768 > 10 ** (-50 / 20) and rms / 32768 > 10 ** (-65 / 20),
                "sampleCount": count, "sampleRate": 16000, "analysisChannels": 1,
                "rmsDbFS": db(rms), "peakDbFS": db(peak), "activeOneSecondWindows": active_windows,
                "meaning": "Signal-level check only; not speech recognition, listening review or a sync guarantee."}


def wrapped(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont, width: int) -> list[str]:
    lines = []
    for paragraph in text.split("\n"):
        words = paragraph.split()
        if not words:
            lines.append("")
            continue
        current = words.pop(0)
        if draw.textlength(current, font=font) > width:
            raise MediaError("Caption contains a word too wide for the layout.")
        for word in words:
            if draw.textlength(current + " " + word, font=font) <= width:
                current += " " + word
            else:
                lines.append(current)
                current = word
        lines.append(current)
    return lines


def background(path: Path, title: str, caption: str, index: int, count: int) -> None:
    if not isinstance(title, str) or not 1 <= len(title) <= 120 or not isinstance(caption, str) or len(caption) > 260:
        raise MediaError("Every scene needs a short title and a caption of at most 260 characters.")
    serif = ROOT / "app/src/main/res/font/source_serif.ttf"
    sans = ROOT / "app/src/main/res/font/atkinson_next.ttf"
    title_font = ImageFont.truetype(str(serif), 88)
    body_font = ImageFont.truetype(str(sans), 37)
    brand_font = ImageFont.truetype(str(sans), 44)
    small_font = ImageFont.truetype(str(sans), 25)
    image = Image.new("RGB", (WIDTH, HEIGHT), "#F7F3EA")
    # The editorial background is separate from the unaltered app frame.
    decoration = Image.new("RGBA", image.size)
    dd = ImageDraw.Draw(decoration)
    dd.ellipse((1030, -230, 2240, 1140), fill="#EEE8DC")
    image = Image.alpha_composite(image.convert("RGBA"), decoration)
    shadow = Image.new("RGBA", image.size)
    sx, sy, sw, sh = PORTRAIT
    ImageDraw.Draw(shadow).rounded_rectangle((sx - 8, sy - 4, sx + sw + 10, sy + sh + 18), radius=22, fill=(40, 44, 32, 65))
    image = Image.alpha_composite(image, shadow.filter(ImageFilter.GaussianBlur(23)))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((sx - 2, sy - 2, sx + sw + 2, sy + sh + 2), radius=4, fill="#D6D0C4")
    draw.rounded_rectangle((132, 112, 194, 174), radius=16, fill="#35483A")
    # A simple original open-book mark, not a fabricated control on the app.
    draw.line([(145, 132), (162, 137), (180, 132), (180, 154), (162, 159), (145, 154), (145, 132)], fill="#FFFDF7", width=3)
    draw.line([(162, 137), (162, 159)], fill="#FFFDF7", width=3)
    draw.text((218, 112), "Read Assist", font=brand_font, fill="#26372C")
    title_lines = wrapped(draw, title, title_font, 990)
    caption_lines = wrapped(draw, caption, body_font, 900)
    if len(title_lines) > 3 or len(caption_lines) > 4:
        raise MediaError("Title or caption exceeds the editorial layout; shorten the recipe copy.")
    y = 330
    for line in title_lines:
        draw.text((132, y), line, font=title_font, fill="#26372C")
        y += 106
    y += 34
    for line in caption_lines:
        draw.text((136, y), line, font=body_font, fill="#5A6258")
        y += 51
    draw.text((136, 929), f"{index + 1:02d} / {count:02d}", font=small_font, fill="#66705F")
    draw.line((265, 949, 865, 949), fill="#DDD8CC", width=3)
    draw.line((265, 949, 265 + round(600 * (index + 1) / count), 949), fill="#58704F", width=3)
    draw.text((136, 1005), "Recorded in Read Assist · Android emulator", font=small_font, fill="#74786D")
    image.convert("RGB").save(path)


def load_capture(directory: Path) -> tuple[dict, dict[str, dict]]:
    report_file = input_file(directory, "capture-report.json")
    report = json.loads(report_file.read_text())
    if report.get("package") != "app.readflow" or report.get("captureKind") != "authentic emulator recording":
        raise MediaError("Input report is not a Read Assist authentic-emulator capture report.")
    clips = {}
    for clip in report.get("clips", []):
        name = clip.get("file")
        if name in clips:
            raise MediaError(f"Capture report contains duplicate clips: {name}")
        source = input_file(directory, name)
        if clip.get("sha256") != digest(source):
            raise MediaError(f"Raw clip checksum does not match capture evidence: {name}")
        clips[name] = clip
    return report, clips


def choose_segments(recipe: dict, clips: dict[str, dict], probes: dict[str, dict]) -> list[dict]:
    segments = recipe.get("segments")
    if segments is None:
        segments = [{"file": name, "start": 0, "duration": min(10, math.floor(probes[name]["durationSeconds"] * FPS) / FPS),
                     "title": title, "caption": caption}
                    for name, title, caption in DEFAULT_SEGMENTS if name in clips]
        if len(segments) != len(DEFAULT_SEGMENTS):
            raise MediaError("Default demo requires all six capture clips; use a reviewed recipe to select fewer.")
    if not isinstance(segments, list) or not 1 <= len(segments) <= 20:
        raise MediaError("Recipe segments must contain between 1 and 20 scenes.")
    selected = []
    for segment in segments:
        if not isinstance(segment, dict) or segment.get("file") not in clips:
            raise MediaError("Recipe references a clip absent from the capture report.")
        item = dict(segment)
        item["start"] = number(item.get("start", 0), "Scene start")
        item["duration"] = number(item.get("duration"), "Scene duration", .5)
        available = probes[item["file"]]["durationSeconds"]
        if item["start"] + item["duration"] > available + .02:
            raise MediaError(f"Requested cut exceeds measured clip duration: {item['file']}")
        item["duration"] = math.floor(item["duration"] * FPS + .0001) / FPS
        selected.append(item)
    return selected


def audio_input(directory: Path, clip: dict, adjustment: float) -> tuple[Path | None, float]:
    name = clip.get("hostAudioFile")
    if not name:
        return None, 0.0
    lead = number(clip.get("hostAudioLeadSecondsApprox"), "Recorded host audio lead")
    return input_file(directory, name), lead + adjustment


def render_scene(ff: FFmpeg, source: Path, audio: Path | None, lead: float, cut: dict, bg: Path, destination: Path) -> None:
    duration, start = cut["duration"], cut["start"]
    args = ["-y", "-v", "error", "-loop", "1", "-framerate", str(FPS), "-i", str(bg),
            "-ss", str(start), "-t", str(duration), "-i", str(source)]
    audio_start = start + lead
    if audio is not None:
        args += ["-ss", str(max(0, audio_start)), "-i", str(audio)]
    else:
        args += ["-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"]
    x, y, width, height = PORTRAIT
    vf = (f"[0:v]setsar=1[bg];[1:v]setpts=PTS-STARTPTS,"
          f"scale={width}:{height}:force_original_aspect_ratio=decrease:flags=lanczos,"
          f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1[app];"
          f"[bg][app]overlay={x}:{y}:shortest=1,fps={FPS},format=yuv420p[v];"
          "[2:a]asetpts=PTS-STARTPTS")
    if audio is not None and audio_start < 0:
        vf += f",adelay={round(-audio_start * 1000)}:all=1"
    vf += f",apad,atrim=duration={duration},aresample=48000[a]"
    args += ["-filter_complex", vf, "-map", "[v]", "-map", "[a]", "-t", str(duration),
             "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p",
             "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2", "-movflags", "+faststart", str(destination)]
    ff.call(args)


def session_state(directory: Path, filename: str, state: int) -> dict:
    path = directory / filename
    if not path.is_file():
        return {"file": filename, "confirmed": False, "reason": "Capture evidence file missing."}
    text = path.read_text(errors="replace")
    confirmed = "app.readflow" in text and bool(re.search(r"state=(?:PlaybackState\s*\{state=)?" + str(state) + r"\b", text))
    return {"file": filename, "sha256": digest(path), "expectedState": state, "confirmed": confirmed}


def foreground_service(ff: FFmpeg, directory: Path, output: Path, capture: dict, clips: dict, probes: dict, recipe: dict) -> dict:
    result = {"rawVideoUnchanged": True, "foregroundServiceReady": False,
              "automaticChecksPassed": False, "playbackReviewConfirmed": False}
    if FGS_FILE not in clips:
        return dict(result, unavailable="Background-playback clip is missing.")
    clip = clips[FGS_FILE]
    settings = recipe.get("foregroundService", {})
    adjustment = number(settings.get("audioOffsetAdjustmentSeconds", 0), "FGS audio offset adjustment", -30)
    audio, lead = audio_input(directory, clip, adjustment)
    if audio is None:
        return dict(result, unavailable="Actual host audio capture is missing; no substitute audio was added.")
    source = input_file(directory, FGS_FILE)
    duration = probes[FGS_FILE]["durationSeconds"]
    audio_probe = ff.probe(audio)
    if audio_probe["durationSeconds"] - max(lead, 0) + max(-lead, 0) < duration - .5:
        raise MediaError("Captured FGS audio ends more than half a second before the video; investigate synchronization.")
    destination = output / "Read-Assist-0.3.2-foreground-service.mp4"
    af = f"atrim=start={max(0, lead)},asetpts=PTS-STARTPTS"
    if lead < 0:
        af += f",adelay={round(-lead * 1000)}:all=1"
    af += f",apad,atrim=duration={duration}"
    ff.call(["-y", "-v", "error", "-i", str(source), "-i", str(audio), "-map", "0:v:0", "-map", "1:a:0",
             "-c:v", "copy", "-af", af, "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
             "-t", str(duration), "-movflags", "+faststart", str(destination)])
    ff.decode_check(destination)
    raw_hash, output_hash = ff.video_stream_hash(source), ff.video_stream_hash(destination)
    if raw_hash != output_hash:
        raise MediaError("FGS output video stream differs from the original capture; evidence was not preserved.")
    stats = ff.audio_stats(destination)
    playing = session_state(directory, "background-media-session.txt", 3)
    paused = session_state(directory, "playback-session-paused.txt", 2)
    evidence_flag = capture.get("backgroundPlaybackEvidenceRecorded") is True
    automatic = evidence_flag and playing["confirmed"] and paused["confirmed"] and stats["nonSilent"]
    reviewed = settings.get("reviewedAudioSyncAndControls") is True and bool(str(settings.get("reviewNote", "")).strip())
    result.update({"file": destination.name, "sha256": digest(destination), "bytes": destination.stat().st_size,
                   "probe": ff.probe(destination), "decodeValidation": "passed", "uncutVideo": True,
                   "encodedVideoStreamSha256": output_hash, "sourceVideoStreamSha256": raw_hash,
                   "sourceVideo": FGS_FILE, "sourceVideoSha256": digest(source),
                   "sourceAudio": audio.relative_to(directory).as_posix(), "sourceAudioSha256": digest(audio),
                   "recordedHostAudioLeadSecondsApprox": clip["hostAudioLeadSecondsApprox"],
                   "operatorOffsetAdjustmentSeconds": adjustment, "appliedAudioLeadSeconds": lead,
                   "audioAlignmentRule": "Output t=0 uses source WAV t=appliedAudioLeadSeconds; negative lead adds initial silence.",
                   "audioTailPaddingLimitSeconds": .5, "audioStatistics": stats,
                   "capturePlaybackEvidenceFlag": evidence_flag, "backgroundPlaying": playing, "notificationPaused": paused,
                   "usesPreviouslyPreparedAudio": capture.get("playbackClipUsesPreviouslyPreparedAudio") is True,
                   "automaticChecksPassed": automatic, "playbackReviewConfirmed": reviewed,
                   "reviewNote": settings.get("reviewNote", ""), "foregroundServiceReady": automatic and reviewed})
    return result


def build(directory: Path, output: Path, recipe_path: Path | None) -> dict:
    directory, output = directory.resolve(), output.resolve()
    if directory == output or directory.is_relative_to(output) or output.is_relative_to(directory):
        raise MediaError("Output and raw capture directories must be separate, without nesting.")
    output.mkdir(parents=True, exist_ok=True)
    report = {"startedAt": now(), "status": "started", "createdBy": "scripts/build_demo_video.py",
              "layout": {"width": WIDTH, "height": HEIGHT, "fps": FPS, "fullPortraitFrame": list(PORTRAIT)},
              "speedMultiplier": 1, "musicAdded": False, "syntheticUiAdded": False,
              "sourceFootageModified": False,
              "notes": ["Editorial titles and warm background are outside the app footage.",
                        "Cuts omit time between scenes; retained footage plays at its original speed.",
                        "Models may have been prepared before capture; this is not a cold-start performance demonstration.",
                        "Audio signal checks do not establish audible speech quality or exact synchronization."]}
    try:
        recipe = json.loads(recipe_path.read_text()) if recipe_path else {}
        if not isinstance(recipe, dict):
            raise MediaError("Edit recipe must be a JSON object.")
        ff = FFmpeg()
        capture, clips = load_capture(directory)
        report.update({"ffmpeg": ff.version, "captureReportSha256": digest(directory / "capture-report.json"),
                       "captureProvenance": {key: capture.get(key) for key in ("sourceCommit", "workflowRun", "deviceFingerprint", "deviceAbi", "captureKind", "status", "errors", "notes")},
                       "recipeSha256": digest(recipe_path) if recipe_path else None})
        probes = {name: ff.probe(input_file(directory, name)) for name in clips}
        if any(not item["hasVideo"] or not item["width"] or not item["height"] for item in probes.values()):
            raise MediaError("Every capture clip must contain a decodable video stream.")
        segments = choose_segments(recipe, clips, probes)
        work = output / "render-parts"
        work.mkdir(exist_ok=True)
        report["segments"] = []
        for index, cut in enumerate(segments):
            print(f"Rendering scene {index + 1}/{len(segments)}: {cut['file']}", flush=True)
            clip = clips[cut["file"]]
            adjustment = number(cut.get("audioOffsetAdjustmentSeconds", 0), "Scene audio offset adjustment", -30)
            audio, lead = audio_input(directory, clip, adjustment)
            bg, part = work / f"background-{index:02d}.png", work / f"scene-{index:02d}.mp4"
            background(bg, cut.get("title", ""), cut.get("caption", ""), index, len(segments))
            source = input_file(directory, cut["file"])
            render_scene(ff, source, audio, lead, cut, bg, part)
            report["segments"].append({**cut, "sourceSha256": digest(source), "measuredSource": probes[cut["file"]],
                                       "sourceAudio": audio.relative_to(directory).as_posix() if audio else None,
                                       "sourceAudioSha256": digest(audio) if audio else None,
                                       "appliedAudioLeadSeconds": lead, "output": part.relative_to(output).as_posix()})
        concat = work / "concat.txt"
        concat.write_text("".join(f"file 'scene-{index:02d}.mp4'\n" for index in range(len(segments))))
        final = output / "Read-Assist-0.3.2-product-demo.mp4"
        ff.call(["-y", "-v", "error", "-f", "concat", "-safe", "1", "-i", str(concat), "-c", "copy", "-movflags", "+faststart", str(final)])
        ff.decode_check(final)
        measured = ff.probe(final)
        expected_duration = sum(item["duration"] for item in segments)
        if measured["width"] != WIDTH or measured["height"] != HEIGHT or abs(measured["durationSeconds"] - expected_duration) > .5:
            raise MediaError("Finished demo dimensions or duration do not match the edit recipe.")
        report["productDemo"] = {"file": final.name, "sha256": digest(final), "bytes": final.stat().st_size,
                                  "probe": measured, "decodeValidation": "passed", "expectedDurationSeconds": expected_duration}
        print("Preparing uncut background-playback evidence…", flush=True)
        report["foregroundService"] = foreground_service(ff, directory, output, capture, clips, probes, recipe)
        credits = ROOT / "store-assets/MEDIA_CREDITS.md"
        if credits.is_file():
            shutil.copyfile(credits, output / credits.name)
            report["creditsSha256"] = digest(credits)
        (output / "edit-recipe-used.json").write_text(json.dumps({**recipe, "segments": segments}, indent=2) + "\n")
        report["status"] = "complete" if report["foregroundService"].get("foregroundServiceReady") else "complete_with_fgs_review_pending"
    except Exception as error:
        report["status"] = "failed"
        report["error"] = str(error) if isinstance(error, MediaError) else f"{type(error).__name__}: rendering could not complete."
    finally:
        report["finishedAt"] = now()
        (output / "video-build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, epilog="Recipe: {\"segments\":[{\"file\":\"01-library.mp4\",\"start\":2,\"duration\":8,\"title\":\"Your library\",\"caption\":\"Read in your own time.\"}]}\nFGS optional keys: foregroundService.audioOffsetAdjustmentSeconds, reviewedAudioSyncAndControls, reviewNote.")
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--recipe", type=Path)
    args = parser.parse_args()
    try:
        report = build(args.capture, args.output, args.recipe)
    except MediaError as error:
        print(json.dumps({"status": "failed", "error": str(error)}))
        return 1
    print(json.dumps({"status": report["status"], "report": str(args.output / "video-build-report.json"), "error": report.get("error")}))
    return 1 if report["status"] == "failed" else 0


if __name__ == "__main__":
    sys.exit(main())
