#!/usr/bin/env python3
"""Capture the real installed app on an isolated Android emulator, using only adb.

Seed first with the opt-in StoreDemoSeedTest. No screenshot pixels or playback
state are fabricated. screenrecord has no audio; an optional host PulseAudio
recording is kept separately with timing metadata for later synchronization.
"""
from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "app.readflow"
RUNNER = "app.readflow.test/androidx.test.runner.AndroidJUnitRunner"
BOOK = "A quiet space for thought"


class CaptureError(RuntimeError):
    pass


class Capture:
    def __init__(self, output: Path, serial: str | None = None, audio_source: str | None = None):
        self.output = output
        output.mkdir(parents=True, exist_ok=True)
        self.adb = ["adb"] + (["-s", serial] if serial else [])
        self.audio_source = audio_source
        self.report = {"startedAt": self.now(), "package": PACKAGE, "captureKind": "authentic emulator recording",
                       "sourceCommit": os.environ.get("CAPTURE_SOURCE_SHA"),
                       "workflowRun": os.environ.get("CAPTURE_RUN_URL"),
                       "screenshots": [], "clips": [], "steps": [], "errors": [],
                       "notes": ["Generated original CC0 demo documents; no user documents.",
                                 "Emulator-specific signing and ABI; not the Play-delivered ARM64 artifact.",
                                 "No synthetic UI, word highlights, playback state, or performance measurements."]}
        self.width, self.height = 1080, 1920

    @staticmethod
    def now():
        return dt.datetime.now(dt.timezone.utc).isoformat()

    def run(self, args, timeout=45, check=True, binary=False):
        result = subprocess.run(self.adb + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=timeout, check=False)
        if check and result.returncode:
            raise CaptureError(f"adb {args[0]} failed: {result.stderr.decode(errors='replace')[-1200:]}")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def shell(self, *args, **kwargs):
        return self.run(["shell", *map(str, args)], **kwargs)

    def check_emulator(self):
        if self.shell("getprop", "ro.kernel.qemu").strip() != "1":
            raise CaptureError("Capture setup is restricted to an isolated emulator.")
        dimensions = re.findall(r"(\d+)x(\d+)", self.shell("wm", "size"))
        if dimensions:
            self.width, self.height = map(int, dimensions[-1])
        self.report["displayPixels"] = [self.width, self.height]
        self.report["deviceFingerprint"] = self.shell("getprop", "ro.build.fingerprint").strip()
        self.report["deviceAbi"] = self.shell("getprop", "ro.product.cpu.abi").strip()
        self.report["installedPackage"] = self.shell("dumpsys", "package", PACKAGE)
        self.shell("input", "keyevent", "KEYCODE_WAKEUP")
        self.shell("wm", "dismiss-keyguard", check=False)
        self.shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
        self.shell("settings", "put", "system", "screen_off_timeout", "1800000")
        self.run(["pull", "/sdcard/Android/data/app.readflow/files/store-demo",
                  str(self.output / "seed")], check=False)

    def launch(self):
        self.shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        time.sleep(2)
        if self.dismiss_launcher_anr():
            self.shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
            time.sleep(2)

    def dismiss_launcher_anr(self):
        # A slow emulator cold boot can leave this unrelated system dialog
        # over the app. Preserve it and close only the named launcher; never
        # dismiss a Read Assist failure or edit a dialog out of recorded media.
        root = self.dump()
        title = self.find("Pixel Launcher isn't responding", root=root, text_only=True)
        if title is None or title.get("package") != "android":
            return False
        recoveries = self.report.setdefault("environmentRecoveries", [])
        evidence = f"diagnostic-launcher-anr-{len(recoveries) + 1:02d}"
        self.screenshot(evidence)
        recoveries.append({"time": self.now(), "dialog": "Pixel Launcher isn't responding",
                           "action": "Close app", "evidence": evidence + ".png",
                           "beforeRecording": True})
        self.tap("Close app")
        return True

    def dump(self):
        for _ in range(3):
            result = self.shell("uiautomator", "dump", "/sdcard/read-assist-ui.xml", timeout=20, check=False)
            if "dumped" in result.lower():
                raw = self.shell("cat", "/sdcard/read-assist-ui.xml")
                try:
                    root = ET.fromstring(raw)
                    self.last_xml = raw
                    return root
                except ET.ParseError:
                    pass
            time.sleep(.5)
        raise CaptureError("Android UI hierarchy could not be captured.")

    @staticmethod
    def bounds(node):
        values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        return values if len(values) == 4 else [0, 0, 0, 0]

    def find(self, label, contains=False, root=None, text_only=False):
        if root is None:
            root = self.dump()
        candidates = []
        for node in root.iter("node"):
            values = [node.get("text", "")] if text_only else [node.get("text", ""), node.get("content-desc", "")]
            matched = any(label in value if contains else label == value for value in values)
            x1, y1, x2, y2 = self.bounds(node)
            if matched and x2 > x1 and y2 > y1 and y1 < self.height:
                candidates.append(node)
        candidates.sort(key=lambda n: (n.get("clickable") != "true", n.get("content-desc") != label))
        return candidates[0] if candidates else None

    def wait(self, label, timeout=20, contains=False, text_only=False):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            node = self.find(label, contains, text_only=text_only)
            if node is not None:
                return node
            time.sleep(.7)
        raise CaptureError(f"Visible control not found: {label}")

    def tap(self, label, timeout=15, contains=False, text_only=False):
        node = self.wait(label, timeout, contains, text_only=text_only)
        x1, y1, x2, y2 = self.bounds(node)
        hierarchy = f"ui-before-tap-{len(self.report['steps']):03d}.xml"
        (self.output / hierarchy).write_text(self.last_xml)
        self.shell("input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)
        self.report["steps"].append({"time": self.now(), "action": "tap", "label": label,
                                     "textOnly": text_only, "hierarchy": hierarchy})
        time.sleep(.7)

    def swipe(self, x1=.5, y1=.75, x2=.5, y2=.3, duration=500):
        self.shell("input", "swipe", int(self.width*x1), int(self.height*y1),
                   int(self.width*x2), int(self.height*y2), duration)
        time.sleep(.8)

    def back(self):
        self.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(.8)

    def scroll_to(self, label, attempts=6, contains=False):
        for _ in range(attempts):
            if self.find(label, contains=contains) is not None:
                return
            self.swipe()
        raise CaptureError(f"Could not scroll to {label}")

    def screenshot(self, name):
        time.sleep(1)
        path = self.output / f"{name}.png"
        data = self.run(["exec-out", "screencap", "-p"], binary=True)
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise CaptureError("screencap did not return PNG data")
        path.write_bytes(data)
        self.dump()
        (self.output / f"{name}.xml").write_text(self.last_xml)
        self.report["screenshots"].append({"file": path.name, "sha256": hashlib.sha256(data).hexdigest(),
                                           "capturedAt": self.now()})
        self.save()

    @contextlib.contextmanager
    def recording(self, name):
        remote = f"/sdcard/{name}.mp4"
        self.shell("rm", "-f", remote)
        log = (self.output / f"{name}-screenrecord.log").open("wb")
        audio_log = None
        audio = None
        audio_started = None
        if self.audio_source and shutil.which("ffmpeg"):
            audio_log = (self.output / f"{name}-audio.log").open("wb")
            audio_started = time.monotonic()
            audio = subprocess.Popen(["ffmpeg", "-y", "-nostdin", "-f", "pulse", "-i", self.audio_source,
                                      "-c:a", "pcm_s16le", str(self.output / f"{name}-audio.wav")],
                                     stdout=audio_log, stderr=subprocess.STDOUT)
        started = time.monotonic()
        process = subprocess.Popen(self.adb + ["shell", "screenrecord", "--bit-rate", "8000000",
                                               "--time-limit", "180", remote], stdout=log, stderr=subprocess.STDOUT)
        time.sleep(1)
        entry = {"file": f"{name}.mp4", "startedAt": self.now(), "rawScreenrecordHasAudio": False}
        if audio_started is not None:
            entry.update({"hostAudioFile": f"{name}-audio.wav",
                          "hostAudioLeadSecondsApprox": started-audio_started,
                          "audioSynchronization": "Separate host capture; start offset approximate, inspect before publishing."})
        try:
            yield
        finally:
            finalization_errors = []
            try:
                self.shell("pkill", "-2", "screenrecord", timeout=10, check=False)
            except Exception as error:
                finalization_errors.append(f"Stop Android recording: {error}")
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            log.close()
            if audio is not None:
                audio.terminate()
                try:
                    audio.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    audio.kill()
                    audio.wait()
                audio_log.close()
            try:
                self.run(["pull", remote, str(self.output / f"{name}.mp4")], timeout=15, check=False)
            except Exception as error:
                finalization_errors.append(f"Pull Android recording: {error}")
            if finalization_errors:
                entry["finalizationErrors"] = finalization_errors
            entry["durationWallSeconds"] = round(time.monotonic()-started, 3)
            clip = self.output / f"{name}.mp4"
            entry["bytes"] = clip.stat().st_size if clip.exists() else 0
            entry["sha256"] = hashlib.sha256(clip.read_bytes()).hexdigest() if clip.exists() else None
            self.report["clips"].append(entry)
            self.save()

    def save(self):
        (self.output / "capture-report.json").write_text(json.dumps(self.report, indent=2) + "\n")

    def recover_reader(self):
        self.shell("am", "force-stop", PACKAGE)
        self.launch()
        self.wait("Library")
        self.open_book()
        self.wait("PDF view", timeout=30)

    def open_book(self):
        # The overflow icon is named "Options for <title>" and can remain
        # visible when the actual title is below the fold. Match title text
        # only, scrolling until it is exposed instead of opening that menu.
        for _ in range(4):
            if self.find(BOOK, contains=True, text_only=True) is not None:
                self.tap(BOOK, contains=True, text_only=True)
                return
            self.swipe()
        raise CaptureError(f"Document title is not visible: {BOOK}")

    def first_page(self):
        if self.find("Page 1 of 3", contains=True) is not None:
            return
        self.tap("Reader menu")
        self.tap("Contents and bookmarks")
        self.wait("Contents")
        self.tap("Page 1")
        self.wait("Page 1 of 3", contains=True)

    @staticmethod
    def media_state(dump):
        """Read this app's state, including Android's named form PLAYING(3)."""
        for match in re.finditer(r"(?m)^(?P<indent>[ \t]*)package=" + re.escape(PACKAGE) + r"[ \t]*$", dump):
            indent = len(match.group("indent"))
            for line in dump[match.end():].splitlines():
                if not line.strip():
                    continue
                if len(line) - len(line.lstrip()) < indent or line.strip().startswith("package="):
                    break
                state = re.match(r"\s*state=(?:PlaybackState\s*\{state=)?(?:[A-Z_]+\()?([0-9]+)\)?(?=[,\s}]|$)", line)
                if state:
                    return int(state.group(1))
        return None

    def seed(self, models, dictionary):
        args = ["shell", "am", "instrument", "-w", "-r", "-e", "class", "app.readflow.StoreDemoSeedTest",
                "-e", "demo", "true", "-e", "models", str(models).lower(),
                "-e", "dictionary", str(dictionary).lower(), RUNNER]
        # Stream progress to Actions so setup never looks like a silent hung job.
        result = subprocess.run(self.adb + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=36*60, text=True)
        (self.output / "seed-instrumentation.txt").write_text(result.stdout)
        print(result.stdout, flush=True)
        self.run(["pull", "/sdcard/Android/data/app.readflow/files/store-demo", str(self.output / "seed")], check=False)
        if result.returncode or "FAILURES!!!" in result.stdout or "OK (1 test)" not in result.stdout:
            raise CaptureError("Demo seed instrumentation did not pass; inspect seed-instrumentation.txt")

    def library(self):
        self.recover_reader()
        self.tap("Back")
        with self.recording("01-library"):
            self.screenshot("01-library-warm")
            time.sleep(3)
            self.tap("Add")
            self.screenshot("02-import-options")
            time.sleep(2)
            self.back()

    def reader(self):
        self.open_book()
        self.wait("PDF view", timeout=30)
        self.first_page()
        self.tap("PDF view")
        with self.recording("02-reading"):
            self.screenshot("03-reader-pdf")
            self.swipe(.84, .46, .16, .46, 800)
            self.wait("Page 2 of 3", contains=True)
            self.screenshot("04-page-turned")
            self.swipe(.16, .46, .84, .46, 800)
            self.wait("Page 1 of 3", contains=True)
            self.tap("Text view")
            time.sleep(3)

    def controls(self):
        with self.recording("03-reading-light"):
            self.screenshot("05-reader-text")
            self.tap("Open reader controls")
            self.wait("Reading light")
            self.screenshot("06-reader-controls")
            self.tap("Use phone brightness")
            slider = self.wait("Reader brightness")
            x1, y1, x2, y2 = self.bounds(slider)
            self.shell("input", "tap", int(x1+(x2-x1)*.65), (y1+y2)//2)
            self.tap("Warm light")
            self.screenshot("07-warm-light")
            time.sleep(3)
            self.tap("Close reader controls")
            self.screenshot("08-warm-reading")
            self.tap("Open reader controls")
            self.tap("Warm light")
            self.scroll_to("Reading speed")
            self.screenshot("09-voice-and-speed")
            self.tap("Close reader controls")

    def dictionary(self):
        with self.recording("04-dictionary"):
            self.tap("Reader menu")
            self.tap("Dictionary")
            self.tap("English word", contains=True)
            self.shell("input", "text", "curiosity")
            self.tap("Look up word")
            self.wait("curiosity", timeout=30)
            # Hide the search row to give the actual definition its normal space.
            if self.find("Hide dictionary search") is not None:
                self.tap("Hide dictionary search")
            # With the input hidden, this can only be the result headword.
            self.wait("curiosity", timeout=30)
            self.screenshot("10-dictionary")
            time.sleep(4)
            self.tap("Close dictionary")

    def themes(self):
        self.tap("Back")
        with self.recording("05-library-themes"):
            for label, filename in [("Modern Focus", "11-library-modern"), ("Night Reader", "12-library-night")]:
                self.tap("Settings")
                self.scroll_to(label, contains=True)
                self.tap(label, contains=True)
                self.tap("Library")
                self.screenshot(filename)
                time.sleep(3)
            self.tap("Settings")
            self.scroll_to("Warm Library", contains=True)
            self.tap("Warm Library", contains=True)
            self.screenshot("13-theme-chooser")
            self.tap("Library")

    def playback(self):
        self.recover_reader()
        self.first_page()
        self.tap("Text view")
        self.tap("Play")
        self.wait("Pause", timeout=150)
        # Wait for the media session to report actual PLAYING, not just preparation.
        deadline = time.monotonic() + 150
        playing = False
        while time.monotonic() < deadline:
            media = self.shell("dumpsys", "media_session")
            if self.media_state(media) == 3:
                playing = True
                break
            time.sleep(2)
        (self.output / "playback-session-before.txt").write_text(media)
        if not playing:
            raise CaptureError("Media session did not confirm actual PLAYING; foreground-service proof incomplete.")
        # Warm the real model/cache once, then show the explicit user-started
        # playback action in the proof clip without hiding setup latency as a metric.
        self.tap("Pause")
        self.wait("Play")
        self.report["playbackClipUsesPreviouslyPreparedAudio"] = True
        with self.recording("06-background-playback"):
            time.sleep(2)
            self.tap("Play")
            self.wait("Pause", timeout=30)
            self.screenshot("14-reading-aloud")
            time.sleep(4)
            self.shell("input", "keyevent", "KEYCODE_HOME")
            time.sleep(3)
            background_media = self.shell("dumpsys", "media_session")
            (self.output / "background-media-session.txt").write_text(background_media)
            if self.media_state(background_media) != 3:
                raise CaptureError("Media session did not remain PLAYING after Home.")
            (self.output / "background-service.txt").write_text(self.shell("dumpsys", "activity", "services", PACKAGE))
            self.shell("cmd", "statusbar", "expand-notifications", check=False)
            time.sleep(2)
            self.screenshot("15-background-notification")
            # Actual Android media notification transport, no synthesized button.
            self.tap("Pause", timeout=12, contains=True)
            time.sleep(2)
            self.screenshot("16-notification-paused")
            paused_media = self.shell("dumpsys", "media_session")
            (self.output / "playback-session-paused.txt").write_text(paused_media)
            if self.media_state(paused_media) != 2:
                raise CaptureError("Notification control did not result in a PAUSED media session.")
        self.report["backgroundPlaybackEvidenceRecorded"] = True
        self.back()
        self.launch()

    def capture(self, sections):
        self.report["sectionsRequested"] = sections
        actions = {"library": self.library, "reader": self.reader, "controls": self.controls,
                   "dictionary": self.dictionary, "themes": self.themes, "playback": self.playback}
        for name in sections:
            print(f"Capturing {name}…", flush=True)
            try:
                if name in ("controls", "dictionary", "themes"):
                    self.recover_reader()
                    self.tap("Text view")
                if name == "reader":
                    self.recover_reader()
                    self.tap("Back")
                actions[name]()
            except Exception as error:
                self.report["errors"].append({"section": name, "error": str(error), "time": self.now()})
                try:
                    self.screenshot(f"error-{name}")
                    (self.output / f"error-{name}-logcat.txt").write_text(self.run(["logcat", "-d", "-t", "350"]))
                except Exception:
                    pass
                print(f"Capture section {name} failed: {error}", flush=True)
            self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=["seed", "capture", "all"])
    parser.add_argument("--output", type=Path, default=Path("build/store-demo"))
    parser.add_argument("--serial")
    parser.add_argument("--models", action="store_true")
    parser.add_argument("--dictionary", action="store_true")
    parser.add_argument("--audio-source", default=os.environ.get("READFLOW_DEMO_AUDIO_SOURCE"))
    # Keep host-renderer stress from page curling last, preserving independent
    # recordings if a graphics-driver crash happens again.
    parser.add_argument("--sections", default="library,controls,dictionary,themes,playback,reader")
    args = parser.parse_args()
    capture = Capture(args.output, args.serial, args.audio_source)
    try:
        capture.check_emulator()
        if args.stage in ("seed", "all"):
            capture.seed(args.models, args.dictionary)
        if args.stage in ("capture", "all"):
            sections = args.sections.split(",")
            if not set(sections) <= {"library", "reader", "controls", "dictionary", "themes", "playback"}:
                raise CaptureError("Unknown capture section")
            capture.capture(sections)
        capture.report["status"] = "partial" if capture.report["errors"] else "complete"
    except Exception as error:
        capture.report["status"] = "failed"
        capture.report["errors"].append({"error": str(error), "time": capture.now()})
        print(str(error), flush=True)
    finally:
        capture.report["finishedAt"] = capture.now()
        capture.save()
    return 0 if capture.report.get("status") == "complete" else 1


if __name__ == "__main__":
    raise SystemExit(main())
