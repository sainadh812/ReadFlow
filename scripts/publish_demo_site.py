#!/usr/bin/env python3
"""Publish reviewed app videos alongside the existing Read Assist privacy site.

Only demo.html, MEDIA_CREDITS.md and two versioned MP4s may change. Hosting
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
    "productDemo": "Read-Assist-0.3.1-product-demo.mp4",
    "foregroundService": "Read-Assist-0.3.1-foreground-service.mp4",
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*args, cwd=ROOT):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def publish(render_dir, report_path):
    report = {"startedAt": datetime.now(timezone.utc).isoformat(), "pageUrl": URL,
              "status": "started", "hostingConfigurationModified": False,
              "pushAttempted": False, "pushSucceeded": False}
    try:
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
    parser.add_argument("--render-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, default=Path("build/demo-site-publication.json"))
    args = parser.parse_args()
    result = publish(args.render_dir, args.report)
    print(json.dumps({"status": result["status"], "url": URL, "report": str(args.report)}))
