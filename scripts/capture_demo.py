#!/usr/bin/env python3
"""Capture the real installed app on an isolated Android emulator.

Seed first with the opt-in StoreDemoSeedTest. No screenshot pixels or playback
state are fabricated. screenrecord has no audio; an optional host PulseAudio
recording is kept separately with timing metadata for later synchronization.
The playback proof can use scrcpy's synchronized live device audio/video instead.
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
import signal
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "app.readflow"
RUNNER = "app.readflow.test/androidx.test.runner.AndroidJUnitRunner"
BOOK = "A quiet space for thought"


class CaptureError(RuntimeError):
    pass


class Capture:
    def __init__(self, output: Path, serial: str | None = None, audio_source: str | None = None,
                 scrcpy: str | None = None):
        self.output = output
        output.mkdir(parents=True, exist_ok=True)
        self.adb = ["adb"] + (["-s", serial] if serial else [])
        self.audio_source = audio_source
        self.serial = serial
        self.scrcpy = scrcpy
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
        self.tap_observed(node, label, text_only=text_only)

    def tap_observed(self, node, label, text_only=False):
        """Tap a node from the most recent actual hierarchy without a second idle wait."""
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

    def screenshot(self, name, hierarchy=True):
        time.sleep(1)
        path = self.output / f"{name}.png"
        data = self.run(["exec-out", "screencap", "-p"], binary=True)
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise CaptureError("screencap did not return PNG data")
        path.write_bytes(data)
        if hierarchy:
            self.dump()
            (self.output / f"{name}.xml").write_text(self.last_xml)
        self.report["screenshots"].append({"file": path.name, "sha256": hashlib.sha256(data).hexdigest(),
                                           "capturedAt": self.now(), "hierarchyCaptured": hierarchy})
        self.save()

    def configure_recording_audio(self):
        """Use normal device/host volume, preserving before/after evidence."""
        for stage in ("before", "after"):
            volume = self.shell("cmd", "media_session", "volume", "--stream", "3", "--get")
            (self.output / f"android-music-volume-{stage}.txt").write_text(volume)
            match = re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]", volume)
            if not match:
                raise CaptureError("Android did not report a readable MUSIC volume range.")
            current, _, maximum = map(int, match.groups())
            if stage == "before":
                self.shell("cmd", "audio", "adj-unmute", "3")
                self.shell("cmd", "audio", "set-volume", "3", maximum)
            elif current != maximum:
                raise CaptureError("Android MUSIC volume did not reach the requested normal maximum.")
        self.report["audioCaptureConfiguration"] = {"androidMusicVolume": current,
            "androidMusicMaximum": maximum, "postCaptureAmplification": False}
        (self.output / "android-audio-before-proof.txt").write_text(self.shell("dumpsys", "audio"))
        if self.audio_source == "readassist_demo.monitor":
            def pulse(*args):
                return subprocess.check_output(["pactl", *args], text=True, timeout=15)
            for stage in ("before", "after"):
                values = {}
                for kind in ("sinks", "sink-inputs", "sources", "source-outputs"):
                    raw = pulse("--format=json", "list", kind)
                    (self.output / f"pulse-{kind}-{stage}-proof.json").write_text(raw)
                    values[kind] = json.loads(raw)
                if stage == "before":
                    sink = next(item for item in values["sinks"] if item["name"] == "readassist_demo")
                    pulse("set-sink-mute", "readassist_demo", "0")
                    pulse("set-sink-volume", "readassist_demo", "100%")
                    pulse("set-source-mute", self.audio_source, "0")
                    pulse("set-source-volume", self.audio_source, "100%")
                    for stream in values["sink-inputs"]:
                        if stream["sink"] == sink["index"]:
                            pulse("set-sink-input-mute", str(stream["index"]), "0")
                            pulse("set-sink-input-volume", str(stream["index"]), "100%")

    def inspect_cached_demo_audio(self):
        """Read only CC0 demo cache WAVs for diagnosis; never use them as a soundtrack."""
        evidence = {"purpose": "Diagnose generated sample speech independently of live audio routing",
                    "recordingSubstitutionAllowed": False, "files": []}
        try:
            prefix = ["su", "0"]
            if self.shell(*prefix, "id", "-u", check=False).strip() != "0":
                self.run(["root"], check=False)
                self.run(["wait-for-device"], timeout=30)
                if self.shell("id", "-u").strip() != "0":
                    raise CaptureError("This isolated emulator does not permit read-only cache diagnostics.")
                prefix = []
            folder = "/data/user/0/app.readflow/no_backup/audio"
            names = self.shell(*prefix, "ls", folder).splitlines()
            candidates = []
            for name in names:
                if re.fullmatch(r"[0-9a-f]{16,64}\.wav", name):
                    size = int(self.shell(*prefix, "stat", "-c", "%s", folder + "/" + name).strip())
                    if 44 < size <= 5 * 1024 * 1024:
                        candidates.append((size, name))
            destination = self.output / "diagnostic-generated-audio"
            destination.mkdir(exist_ok=True)
            for size, name in sorted(candidates, reverse=True)[:3]:
                data = self.run(["exec-out", *prefix, "cat", folder + "/" + name], binary=True, timeout=15)
                if len(data) != size or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
                    raise CaptureError("Generated audio cache read was incomplete or not WAV.")
                (destination / name).write_bytes(data)
                evidence["files"].append({"file": name, "bytes": size,
                                          "sha256": hashlib.sha256(data).hexdigest()})
        except Exception as error:
            evidence["error"] = str(error)
        (self.output / "audio-cache-diagnostics.json").write_text(json.dumps(evidence, indent=2) + "\n")

    @contextlib.contextmanager
    def recording(self, name):
        if name == "06-background-playback" and self.scrcpy:
            with self.device_recording(name):
                yield
            return
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

    @contextlib.contextmanager
    def device_recording(self, name):
        """Record original synchronized device output upstream of the host PCM path."""
        line_buffer = shutil.which("stdbuf")
        if line_buffer is None:
            raise CaptureError("Device recorder startup monitoring requires coreutils stdbuf.")
        clip = self.output / f"{name}.mp4"
        log_path = self.output / f"{name}-scrcpy.log"
        # scrcpy's INFO messages use buffered stdout when redirected to a file.
        # Observe the real startup message while the process is still running.
        command = [line_buffer, "-oL", "-eL", self.scrcpy, "--no-playback", "--no-control", "--no-window",
                   "--require-audio", "--audio-source=output", "--audio-codec=aac",
                   "--video-codec=h264", "--video-bit-rate=8000000", "--max-fps=30",
                   "--time-limit=180", f"--record={clip}"]
        if self.serial:
            command.extend(["--serial", self.serial])
        version = subprocess.check_output([self.scrcpy, "--version"], text=True, timeout=10)
        if not version.startswith("scrcpy 5.0.1 "):
            raise CaptureError("Playback recorder is not the reviewed scrcpy 5.0.1 release.")
        (self.output / "scrcpy-version.txt").write_text(version)
        entry = {"file": clip.name, "startedAt": self.now(), "embeddedDeviceAudio": True,
                 "recorder": "scrcpy 5.0.1",
                 "recorderArchiveSha256": "9f969d30cc574816077edecda65719c1c70b0aee1df1ba5fa00779ac07b8fd6e",
                 "audioCaptureMethod": "Live Android REMOTE_SUBMIX output via scrcpy --audio-source=output",
                 "audioSynchronization": "Original audio/video packet timestamps captured on device; multiplexed together by scrcpy.",
                 "deviceSpeakerOutputRedirectedDuringRecording": True,
                 "postCaptureAmplification": False}
        started = time.monotonic()
        with log_path.open("wb") as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            finalization_errors = []
            try:
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise CaptureError("Device recorder exited before capture: " + log_path.read_text()[-1500:])
                    if "Recording started to mp4 file:" in log_path.read_text():
                        break
                    time.sleep(.25)
                else:
                    raise CaptureError("Device recorder did not confirm recording startup within 30 seconds: "
                                       + log_path.read_text()[-1500:])
                entry["recordingReadyAt"] = self.now()
                yield
            finally:
                if process.poll() is None:
                    process.send_signal(signal.SIGINT)
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    finalization_errors.append("Device recorder did not finalize within 20 seconds.")
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                if process.returncode != 0:
                    finalization_errors.append(f"Device recorder exited with code {process.returncode}.")
                try:
                    probe = json.loads(subprocess.check_output(
                        ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(clip)],
                        text=True, timeout=15))
                    streams = probe.get("streams", [])
                    if not any(s.get("codec_type") == "video" and s.get("codec_name") == "h264" for s in streams):
                        raise CaptureError("Device recording lacks the expected H.264 video stream.")
                    if not any(s.get("codec_type") == "audio" and s.get("codec_name") == "aac" for s in streams):
                        raise CaptureError("Device recording lacks the required live AAC audio stream.")
                    if float(probe.get("format", {}).get("duration", 0)) < 1:
                        raise CaptureError("Device recording is unexpectedly short.")
                    (self.output / f"{name}-streams.json").write_text(json.dumps(probe, indent=2) + "\n")
                    entry["recordedDurationSeconds"] = float(probe["format"]["duration"])
                except Exception as error:
                    finalization_errors.append(f"Validate device audio/video streams: {error}")
                entry["durationWallSeconds"] = round(time.monotonic() - started, 3)
                entry["bytes"] = clip.stat().st_size if clip.exists() else 0
                entry["sha256"] = hashlib.sha256(clip.read_bytes()).hexdigest() if clip.exists() else None
                if finalization_errors:
                    entry["finalizationErrors"] = finalization_errors
                self.report["clips"].append(entry)
                self.save()
        if finalization_errors:
            raise CaptureError("; ".join(finalization_errors))

    def save(self):
        (self.output / "capture-report.json").write_text(json.dumps(self.report, indent=2) + "\n")

    def recover_reader(self):
        self.shell("am", "force-stop", PACKAGE)
        self.launch()
        self.wait("Library")
        self.open_book()
        self.wait_reader_view(timeout=30)

    def reader_view(self, root=None):
        """Read the current mode from the compact toggle's opposite-mode action."""
        if root is None:
            root = self.dump()
        for mode, action in (("original", "Show reader text"), ("text", "Show original page")):
            node = self.find(action, root=root)
            if node is not None:
                return mode, action, node
        return None

    def wait_reader_view(self, mode=None, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            current = self.reader_view()
            if current is not None and (mode is None or current[0] == mode):
                return current
            time.sleep(.7)
        raise CaptureError(f"Reader view toggle did not expose the requested mode: {mode or 'either'}")

    def select_reader_view(self, mode):
        """Select a mode idempotently; tapping the new single icon always toggles."""
        if mode not in ("text", "original"):
            raise ValueError(f"Unknown reader view: {mode}")
        current, action, node = self.wait_reader_view()
        if current != mode:
            self.tap_observed(node, action)
        return self.wait_reader_view(mode)

    def confirm_prepared_page(self, name):
        """Confirm complete cached speech in the normal details dialog, not a dock label."""
        self.tap("Reader menu")
        self.scroll_to("Reading details")
        self.tap("Reading details")
        self.wait("Reading details")
        root = self.dump()
        completion = "Pages 1–1 ready · press Play to read"
        complete = self.find(completion, contains=True, root=root) is not None
        page_count = self.find("1/1 pages", contains=True, root=root)
        self.screenshot(name)
        detail = {"method": "Normal Reading details dialog after the primary action returned to Play",
                  "completionMessage": completion if complete else None,
                  "pageCountText": page_count.get("text") if page_count is not None else None,
                  "evidence": name + ".png", "hierarchy": name + ".xml"}
        self.tap("Close")
        if not complete or page_count is None:
            raise CaptureError("Whole-page preparation was not confirmed in Reading details; inspect " + name)
        return detail

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
        self.wait_reader_view(timeout=30)
        self.first_page()
        self.select_reader_view("original")
        with self.recording("02-reading"):
            self.screenshot("03-reader-pdf")
            self.swipe(.84, .46, .16, .46, 800)
            self.wait("Page 2 of 3", contains=True)
            self.screenshot("04-page-turned")
            self.swipe(.16, .46, .84, .46, 800)
            self.wait("Page 1 of 3", contains=True)
            self.select_reader_view("text")
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

    def dock_snapshot(self, name):
        """Measure real accessibility geometry; retain the unmodified hierarchy."""
        root = self.dump()
        parents = {child: parent for parent in root.iter() for child in parent}

        def ancestors(node):
            result = [node]
            while result[-1] in parents:
                result.append(parents[result[-1]])
            return result

        def control(label, contains=False):
            node = self.find(label, contains=contains, root=root)
            if node is None:
                raise CaptureError(f"Floating dock control is missing: {label}")
            # Icons can expose smaller bounds than their actual touch target.
            return next((item for item in ancestors(node) if item.get("clickable") == "true"), node)

        primary_label = next((label for label in ("Cancel preparation", "Pause", "Play", "Read from selected word")
                              if self.find(label, root=root) is not None), None)
        if primary_label is None:
            raise CaptureError("Floating dock has no primary playback control.")
        view = self.reader_view(root)
        if view is None:
            raise CaptureError("Floating dock has no text/original view toggle.")
        mode, view_action, view_node = view
        controls = {"primary": control(primary_label), "viewToggle": control(view_action),
                    "expand": control("Open reader controls"),
                    "previous": control("Previous sentence"), "next": control("Next sentence"),
                    "speed": control("Playback speed,", contains=True)}
        if self.find("Reading status:", contains=True, root=root) is not None or self.find("PDF view", root=root) is not None:
            raise CaptureError("Removed dock status or PDF selector is still accessible.")
        metrics = self.find("Hide performance metrics", root=root)
        measured = list(controls.values()) + ([metrics] if metrics is not None else [])
        ancestor_sets = [set(ancestors(node)) for node in measured]
        container = next(node for node in ancestors(measured[0]) if all(node in items for items in ancestor_sets))
        container_bounds = self.bounds(container)
        exported_card = (container_bounds[3] - container_bounds[1] < self.height * .5
                         and container_bounds[2] - container_bounds[0] > self.width * .6)
        boxes = [self.bounds(node) for node in measured]
        envelope = [min(box[0] for box in boxes), min(box[1] for box in boxes),
                    max(box[2] for box in boxes), max(box[3] for box in boxes)]
        # Some Compose versions flatten layout-only ancestors. Do not label a
        # control envelope as the rendered card's exact outside border.
        geometry = {key: self.bounds(node) for key, node in controls.items()}
        geometry["dock"] = container_bounds if exported_card else envelope
        viewport_candidates = [self.bounds(node) for node in root.iter("node")
                               if node.get("scrollable") == "true"
                               and self.bounds(node)[3] <= envelope[1]
                               and self.bounds(node)[2] - self.bounds(node)[0] > self.width * .6]
        if viewport_candidates:
            geometry["readingViewport"] = max(viewport_candidates, key=lambda box: (box[2]-box[0])*(box[3]-box[1]))
        # Android's hierarchy does not always export Compose stateDescription.
        # The action changes with the real mode; record that observation explicitly.
        selected = {"text": mode == "text", "pdf": mode == "original"}
        centers = {key: (bounds[0] + bounds[2]) / 2 for key, bounds in geometry.items()}
        if not centers["next"] < centers["viewToggle"] < centers["expand"]:
            raise CaptureError("Compact text toggle is not between Next sentence and the up arrow.")
        hierarchy = name + ".xml"
        (self.output / hierarchy).write_text(self.last_xml)
        media_file = name + "-media-session.txt"
        media = self.shell("dumpsys", "media_session")
        (self.output / media_file).write_text(media)
        spinner = next((node for node in root.iter("node")
                        if node.get("resource-id", "").endswith("reader-preparation-spinner")), None)
        snapshot = {"name": name, "capturedAt": self.now(), "hierarchy": hierarchy,
                    "mediaSessionEvidence": media_file, "mediaState": self.media_state(media),
                    "primaryLabel": primary_label, "viewToggleAction": view_action,
                    "viewStateDescription": view_node.get("state-description"),
                    "viewModeEvidence": "Current mode inferred from the opposite-mode accessibility action",
                    "preparationSpinnerBoundsIfExported": self.bounds(spinner) if spinner is not None else None,
                    "metricsVisible": metrics is not None, "selected": selected, "bounds": geometry,
                    "dockMeasurement": "exported control container" if exported_card else "accessible control envelope",
                    "visibleMetricText": [node.get("text") for node in root.iter("node")
                                          if any(value in node.get("text", "") for value in
                                                 ("chars/min", "× listening", "Performance", "buffered", "No audio measured"))]}
        self.report["dockRegression"]["snapshots"].append(snapshot)
        self.save()
        return snapshot

    @staticmethod
    def assert_dock_stable(baseline, sample):
        if sample["metricsVisible"] != baseline["metricsVisible"]:
            raise CaptureError("Performance visibility changed without a user toggle.")
        if sample["dockMeasurement"] != baseline["dockMeasurement"]:
            raise CaptureError("Dock hierarchy geometry changed during playback; inspect the saved XML.")
        changed = {key: {"before": bounds, "after": sample["bounds"].get(key)}
                   for key, bounds in baseline["bounds"].items() if sample["bounds"].get(key) != bounds}
        if changed:
            raise CaptureError("Floating dock or reader moved during audio state changes: " + json.dumps(changed))

    def dock_performance(self, enabled, name):
        """Use the shipped Quick setting, and verify the real Switch state."""
        self.tap("Open reader controls")
        self.tap("Quick")
        switch = self.wait("Show performance info")
        if switch.get("checkable") != "true":
            raise CaptureError("Performance setting did not expose a checkable Switch.")
        if (switch.get("checked") == "true") != enabled:
            self.tap_observed(switch, "Show performance info")
        switch = self.wait("Show performance info")
        if (switch.get("checked") == "true") != enabled:
            raise CaptureError("Quick performance toggle did not retain the requested state.")
        self.screenshot(name + "-quick-setting")
        self.tap("Close reader controls")
        snapshot = self.dock_snapshot(name)
        if snapshot["metricsVisible"] != enabled:
            raise CaptureError("Floating card metrics disagree with the Quick setting.")
        self.screenshot(name, hierarchy=False)
        return snapshot

    def dock(self):
        evidence = {"status": "started", "snapshots": [], "persistence": {},
                    "scope": "Real Android emulator UI, normal Quick settings and actual on-device speech",
                    "limitations": ["Bounds checks do not establish absence of every rendering flicker.",
                                    "Explicitly changing performance visibility may resize the card.",
                                    "Emulator capture is not a physical-phone or Play-installed ARM64 test."]}
        self.report["dockRegression"] = evidence
        try:
            self.recover_reader()
            self.first_page()
            self.select_reader_view("text")
            self.wait("Play")
            initial = self.dock_snapshot("dock-initial-text")
            if initial["selected"] != {"text": True, "pdf": False}:
                raise CaptureError("Text view was not reflected by the toggle action.")
            self.select_reader_view("original")
            pdf = self.dock_snapshot("dock-pdf-selected")
            if pdf["selected"] != {"text": False, "pdf": True}:
                raise CaptureError("Original view was not reflected by the toggle action.")
            for key in ("primary", "viewToggle", "expand", "previous", "next", "speed"):
                if initial["bounds"][key] != pdf["bounds"][key]:
                    raise CaptureError("Text/PDF switching moved a fixed dock control: " + key)
            self.screenshot("dock-pdf-selected", hierarchy=False)
            self.select_reader_view("text")
            for enabled, label in ((True, "shown"), (False, "hidden")):
                self.dock_performance(enabled, "dock-metrics-" + label)
                # Force-stop tests persisted preferences, not rememberSaveable state.
                self.recover_reader()
                self.select_reader_view("text")
                persisted = self.dock_snapshot("dock-relaunched-" + label)
                if persisted["metricsVisible"] != enabled:
                    raise CaptureError("Performance visibility was not persisted after relaunch.")
                evidence["persistence"][label] = {"verified": True, "snapshot": persisted["name"]}
                self.screenshot(persisted["name"], hierarchy=False)
            baseline = self.dock_performance(True, "dock-before-preparation")
            self.first_page()
            # Preparing one whole page gives an authentic preparation transition
            # and enough speech to measure playback without racing a short heading.
            self.tap("Reader menu")
            self.tap("Prepare pages before listening")
            self.tap("Last page", contains=True)
            self.shell("input", "keyevent", "KEYCODE_MOVE_END")
            for _ in range(5):
                self.shell("input", "keyevent", "KEYCODE_DEL")
            self.shell("input", "text", "1")
            self.tap("Prepare")
            deadline = time.monotonic() + 900
            preparing = 0
            while time.monotonic() < deadline:
                sample = self.dock_snapshot(f"dock-preparation-{len(evidence['snapshots']):03d}")
                self.assert_dock_stable(baseline, sample)
                if sample["primaryLabel"] == "Cancel preparation":
                    preparing += 1
                    if preparing == 1:
                        self.screenshot("dock-preparing-audio", hierarchy=False)
                if sample["primaryLabel"] == "Play":
                    break
                time.sleep(2)
            else:
                raise CaptureError("Page preparation did not finish within 15 minutes.")
            evidence["preparingSamples"] = preparing
            evidence["preparationBoundsStable"] = preparing > 0
            if preparing == 0:
                evidence["limitations"].append("Preparation finished before a hierarchy sample; preparation geometry was not observed.")
            evidence["pagePreparation"] = self.confirm_prepared_page("dock-prepared-page-details")
            self.screenshot("dock-audio-ready", hierarchy=False)
            # Check fixed geometry both with changing metrics and with metrics hidden.
            for show_metrics, label in ((True, "shown"), (False, "hidden")):
                baseline = self.dock_performance(show_metrics, "dock-playback-before-" + label)
                self.tap("Play")
                deadline = time.monotonic() + 150
                playing = 0
                while time.monotonic() < deadline:
                    sample = self.dock_snapshot(f"dock-playback-{label}-{len(evidence['snapshots']):03d}")
                    self.assert_dock_stable(baseline, sample)
                    if sample["mediaState"] == 3 and sample["primaryLabel"] == "Pause":
                        playing += 1
                        if playing == 1:
                            self.screenshot("dock-listening-" + label, hierarchy=False)
                        if playing >= 3:
                            break
                    time.sleep(.5)
                else:
                    raise CaptureError("Actual PLAYING and three stable dock samples were not observed.")
                self.shell("input", "keyevent", "KEYCODE_MEDIA_PAUSE")
                self.wait("Play", timeout=30)
                paused = self.dock_snapshot("dock-paused-" + label)
                self.assert_dock_stable(baseline, paused)
                if paused["mediaState"] != 2:
                    raise CaptureError("Actual media session did not confirm PAUSED after the dock test.")
                evidence["playbackBoundsStable-" + label] = {"verified": True, "playingSamples": playing}
                self.screenshot("dock-paused-" + label, hierarchy=False)
            evidence["status"] = "passed"
        except Exception as error:
            evidence.update(status="failed", error=str(error))
            raise
        finally:
            evidence["finishedAt"] = self.now()
            (self.output / "dock-regression.json").write_text(json.dumps(evidence, indent=2) + "\n")
            self.save()

    def playback(self):
        self.configure_recording_audio()
        self.recover_reader()
        self.first_page()
        self.select_reader_view("text")
        # Prepare an actual page through the shipped user flow. Short heading
        # audio can end while UI automation obtains a hierarchy, so the proof
        # needs a real reserve of cached speech, not a race against that heading.
        self.tap("Reader menu")
        self.tap("Prepare pages before listening")
        self.tap("Last page", contains=True)
        self.shell("input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(5):
            self.shell("input", "keyevent", "KEYCODE_DEL")
        self.shell("input", "text", "1")
        self.tap("Prepare")
        self.wait("Play", timeout=900)
        preparation = self.confirm_prepared_page("playback-prepared-page-details")
        self.screenshot("playback-prepared-page")
        self.report["playbackPreparation"] = {
            "method": "Prepare pages before listening: page 1 through page 1",
            "completionVerification": preparation,
            "evidence": "playback-prepared-page.png",
            "outsideProofRecording": True,
            "usesOrdinaryVerifiedAudioCache": True,
        }
        self.inspect_cached_demo_audio()
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
        # A real media transport event pauses immediately, without another slow
        # hierarchy lookup racing the end of a short spoken heading. The proof
        # below still shows the actual visible Play and notification controls.
        self.shell("input", "keyevent", "KEYCODE_MEDIA_PAUSE")
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            warmed_paused = self.shell("dumpsys", "media_session")
            if self.media_state(warmed_paused) == 2:
                break
            time.sleep(.5)
        (self.output / "playback-session-warmed-paused.txt").write_text(warmed_paused)
        if self.media_state(warmed_paused) != 2:
            raise CaptureError("Android media Pause did not pause the actual warmed narration.")
        self.wait("Play")
        self.report["playbackClipUsesPreviouslyPreparedAudio"] = True
        self.report["foregroundReadingDwellSeconds"] = 2
        with self.recording("06-background-playback"):
            time.sleep(2)
            self.tap("Play")
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if self.media_state(self.shell("dumpsys", "media_session")) == 3:
                    break
                time.sleep(.5)
            else:
                raise CaptureError("Visible Play did not start actual narration inside the proof recording.")
            self.screenshot("14-reading-aloud", hierarchy=False)
            # Preserve time for the Home/Pause proof. Capture revision 038ae9e
            # used 14 seconds for a listening demo; its later Pause was too late.
            time.sleep(2)
            self.shell("input", "keyevent", "KEYCODE_HOME")
            time.sleep(2)
            background_media = self.shell("dumpsys", "media_session")
            (self.output / "background-media-session.txt").write_text(background_media)
            if self.media_state(background_media) != 3:
                raise CaptureError("Media session did not remain PLAYING after Home.")
            (self.output / "background-service.txt").write_text(self.shell("dumpsys", "activity", "services", PACKAGE))
            self.shell("cmd", "statusbar", "expand-notifications", check=False)
            time.sleep(2)
            pause = self.wait("Pause", timeout=12, contains=True)
            (self.output / "15-background-notification.xml").write_text(self.last_xml)
            self.screenshot("15-background-notification", hierarchy=False)
            # Actual Android media notification transport, no synthesized button.
            if self.media_state(self.shell("dumpsys", "media_session")) != 3:
                raise CaptureError("Narration ended before the observed notification Pause could be tapped.")
            self.tap_observed(pause, "Pause")
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
                   "dictionary": self.dictionary, "themes": self.themes, "playback": self.playback, "dock": self.dock}
        for name in sections:
            print(f"Capturing {name}…", flush=True)
            try:
                if name in ("controls", "dictionary", "themes"):
                    self.recover_reader()
                    self.select_reader_view("text")
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
    parser.add_argument("--scrcpy", help="Verified scrcpy 5.0.1 executable for synchronized playback proof")
    # Keep host-renderer stress from page curling last, preserving independent
    # recordings if a graphics-driver crash happens again.
    parser.add_argument("--sections", default="library,controls,dictionary,themes,playback,reader")
    args = parser.parse_args()
    capture = Capture(args.output, args.serial, args.audio_source, args.scrcpy)
    try:
        capture.check_emulator()
        if args.stage in ("seed", "all"):
            capture.seed(args.models, args.dictionary)
        if args.stage in ("capture", "all"):
            sections = args.sections.split(",")
            if not set(sections) <= {"library", "reader", "controls", "dictionary", "themes", "playback", "dock"}:
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
