#!/usr/bin/env python3
"""Publish reviewed app videos alongside the existing Read Assist privacy site.

Only demo.html, MEDIA_CREDITS.md and the selected versioned MP4s may change. Hosting
configuration, privacy.html, index.html and all other tracked files are preserved.
A successful push still requires independent public HTTP verification.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REPO = "sainadh812/ReadFlow"
URL = "https://sainadh812.github.io/ReadFlow/demo.html"
VIDEOS = {
    "productDemo": "Read-Assist-0.3.2-product-demo.mp4",
    "foregroundService": "Read-Assist-0.3.2-foreground-service.mp4",
}
FEATURE_TOUR = "Read-Assist-0.3.2-feature-tour.mp4"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*args, cwd=ROOT):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def feature_tour_inputs(directory):
    """Validate the separately reviewed feature tour without claiming FGS proof."""
    evidence_bytes = (directory / "feature-tour-evidence.json").read_bytes()
    evidence = json.loads(evidence_bytes)
    if (evidence.get("artifact") != FEATURE_TOUR or evidence.get("appVersion") != "0.3.2"
            or type(evidence.get("versionCode")) is not int or evidence["versionCode"] != 28
            or evidence.get("decodeValidation") != "passed"):
        raise ValueError("Feature tour version, artifact or decode evidence is invalid.")
    for field in ("foregroundServiceProofIncluded", "narrationDemonstrationIncluded",
                  "syntheticFootageAdded", "syntheticUiAdded", "musicAdded"):
        if evidence.get(field) is not False:
            raise ValueError("Feature tour must explicitly exclude narration/FGS proof and synthetic additions.")
    if type(evidence.get("sourcePlaybackSpeedMultiplier")) not in (int, float) or evidence["sourcePlaybackSpeedMultiplier"] != 1:
        raise ValueError("Feature tour must preserve the reviewed source playback speed.")
    original_name = evidence.get("localOriginalFilename")
    if original_name not in (FEATURE_TOUR, VIDEOS["productDemo"]):
        raise ValueError("Unexpected feature tour input filename.")
    video = directory / original_name
    measured = {"sha256": sha(video), "bytes": video.stat().st_size}
    if (type(evidence.get("bytes")) is not int or not 0 < measured["bytes"] < 100 * 1024 * 1024
            or evidence.get("sha256") != measured["sha256"] or evidence["bytes"] != measured["bytes"]):
        raise ValueError("Feature tour bytes do not match the reviewed artifact.")
    timeline_ref = evidence.get("timelineVerification", {})
    if timeline_ref.get("file") != "timeline-verification/report.json" or timeline_ref.get("allPassed") is not True:
        raise ValueError("Feature tour requires its passing timeline evidence.")
    timeline_path = directory / "timeline-verification/report.json"
    if sha(timeline_path) != timeline_ref.get("sha256"):
        raise ValueError("Feature tour timeline evidence changed after review.")
    timeline = json.loads(timeline_path.read_bytes())
    frames = timeline.get("frames")
    if (timeline.get("productSha256") != measured["sha256"] or timeline.get("allFramesPassed") is not True
            or not isinstance(frames, list) or not frames
            or type(timeline_ref.get("comparisons")) is not int or timeline_ref["comparisons"] != len(frames)
            or any(not isinstance(frame, dict) or frame.get("passed") is not True for frame in frames)):
        raise ValueError("Not every feature tour timeline comparison passed for these video bytes.")
    files = {"demo.html": ROOT / "site/feature-tour.html",
             "MEDIA_CREDITS.md": ROOT / "store-assets/MEDIA_CREDITS.md",
             "media/" + FEATURE_TOUR: video}
    expected = {name: {"sha256": sha(path), "bytes": path.stat().st_size} for name, path in files.items()}
    credits = evidence.get("credits", {})
    if credits.get("file") != "MEDIA_CREDITS.md" or credits.get("sha256") != expected["MEDIA_CREDITS.md"]["sha256"]:
        raise ValueError("Source media credits do not match the reviewed feature tour.")
    return files, expected, hashlib.sha256(evidence_bytes).hexdigest()


def publish(render_dir, report_path, *, feature_tour_dir=None):
    report = {"startedAt": datetime.now(timezone.utc).isoformat(), "pageUrl": URL,
              "status": "started", "hostingConfigurationModified": False,
              "pushAttempted": False, "pushSucceeded": False}
    try:
        if (render_dir is None) == (feature_tour_dir is None):
            raise ValueError("Choose exactly one full-render or feature-tour input directory.")
        if feature_tour_dir is not None:
            files, expected, evidence_sha = feature_tour_inputs(feature_tour_dir)
            report.update({"publicationScope": "feature_tour_only", "featureTourEvidenceSha256": evidence_sha,
                           "foregroundServiceProofIncluded": False, "narrationDemonstrationIncluded": False,
                           "pageSource": "site/feature-tour.html", "creditsSource": "store-assets/MEDIA_CREDITS.md"})
        else:
            media_bytes = (render_dir / "video-build-report.json").read_bytes()
            media = json.loads(media_bytes)
            report["renderReportSha256"] = hashlib.sha256(media_bytes).hexdigest()
            if media.get("status") != "complete" or media.get("foregroundService", {}).get("foregroundServiceReady") is not True:
                raise ValueError("Both real videos and their playback review must be complete before publication.")
            files = {"demo.html": ROOT / "site/demo.html", "MEDIA_CREDITS.md": ROOT / "store-assets/MEDIA_CREDITS.md"}
            expected = {}
            for field, name in VIDEOS.items():
                evidence = media[field]
                path = render_dir / name
                measured = {"sha256": sha(path), "bytes": path.stat().st_size}
                if evidence.get("file") != name or evidence.get("sha256") != measured["sha256"] or evidence.get("bytes") != measured["bytes"] or evidence.get("decodeValidation") != "passed":
                    raise ValueError("Video does not match its verified render evidence.")
                if measured["bytes"] >= 100 * 1024 * 1024:
                    raise ValueError("Video exceeds GitHub's per-file publication limit.")
                files["media/" + name] = path
                expected["media/" + name] = measured
            for name in ("demo.html", "MEDIA_CREDITS.md"):
                expected[name] = {"sha256": sha(files[name]), "bytes": files[name].stat().st_size}
            if media.get("creditsSha256") != expected["MEDIA_CREDITS.md"]["sha256"]:
                raise ValueError("Media credits changed after the verified render.")
        report["files"] = expected
        settings = json.loads(run("gh", "api", f"repos/{REPO}/pages"))
        if settings.get("source") != {"branch": "gh-pages", "path": "/"} or settings.get("cname") or not settings.get("https_enforced"):
            raise ValueError("Existing Pages configuration does not match the established HTTPS site.")
        report["status"] = "prepared"
        with tempfile.TemporaryDirectory(prefix="read-assist-demo-") as directory:
            checkout = Path(directory)
            run("git", "clone", "--quiet", "--single-branch", "--branch", "gh-pages", f"https://github.com/{REPO}.git", directory)
            report["beforeCommit"] = run("git", "rev-parse", "HEAD", cwd=checkout)
            existing = run("git", "ls-files", cwd=checkout).splitlines()
            preserved = {name: sha(checkout / name) for name in existing if name not in files}
            if "privacy.html" not in preserved or "index.html" not in preserved:
                raise ValueError("Expected existing public site is missing.")
            for key in ("user.name", "user.email"):
                run("git", "config", key, run("git", "config", "--get", key), cwd=checkout)
            for name, source in files.items():
                destination = checkout / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, destination)
                if sha(destination) != expected[name]["sha256"] or destination.stat().st_size != expected[name]["bytes"]:
                    raise ValueError("A publication input changed after review; refusing to publish different bytes.")
            if any(sha(checkout / name) != expected for name, expected in preserved.items()):
                raise ValueError("An unrelated existing website file changed; refusing publication.")
            report["preservedFiles"] = preserved
            run("git", "add", "--", *files, cwd=checkout)
            changed = set(run("git", "diff", "--cached", "--name-only", cwd=checkout).splitlines())
            if not changed.issubset(files):
                raise ValueError("Unexpected staged website path.")
            for name, evidence in expected.items():
                staged = subprocess.check_output(["git", "show", f":{name}"], cwd=checkout)
                if hashlib.sha256(staged).hexdigest() != evidence["sha256"] or len(staged) != evidence["bytes"]:
                    raise ValueError("Staged website bytes differ from the verified publication inputs.")
            report["stagedFilesVerified"] = True
            if changed:
                run("git", "commit", "-m", "Publish Read Assist app demonstration videos", cwd=checkout)
                report["publicationCommit"] = run("git", "rev-parse", "HEAD", cwd=checkout)
                report["pushAttempted"] = True
                run("git", "push", "origin", "HEAD:gh-pages", cwd=checkout)
                report["pushSucceeded"] = True
            else:
                report["publicationCommit"] = report["beforeCommit"]
            report["status"] = "pushed_deployment_unverified"
    except Exception as error:
        ambiguous = report["pushAttempted"] and not report["pushSucceeded"]
        report["status"] = "push_outcome_unknown" if ambiguous else "failed"
        report["reconciliationRequired"] = ambiguous
        report["error"] = str(error)
        raise
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, indent=2) + "\n")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--render-dir", type=Path)
    inputs.add_argument("--feature-tour-dir", type=Path)
    parser.add_argument("--report", type=Path, default=Path("build/demo-site-publication.json"))
    args = parser.parse_args()
    result = publish(args.render_dir, args.report, feature_tour_dir=args.feature_tour_dir)
    print(json.dumps({"status": result["status"], "url": URL, "report": str(args.report)}))
