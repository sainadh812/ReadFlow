#!/usr/bin/env python3
"""Publish only the finalized public policy to ReadFlow's dedicated gh-pages branch."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile

from verify_privacy_page import verify

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "sainadh812/ReadFlow"
REMOTE = f"https://github.com/{REPOSITORY}.git"
ENDPOINT = f"repos/{REPOSITORY}/pages"
URL = "https://sainadh812.github.io/ReadFlow/privacy.html"
INDEX = b'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Read Assist</title></head><body><main><h1>Read Assist</h1>
<p><a href="privacy.html">Privacy Policy</a></p></main></body></html>
'''


def run(*args, cwd=ROOT, **kwargs):
    return subprocess.run(args, cwd=cwd, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def main():
    policy = (ROOT / "site/privacy.html").read_bytes()
    verify(policy)  # No GitHub mutation is allowed until owner placeholders are resolved.
    current = subprocess.run(["gh", "api", ENDPOINT], cwd=ROOT, text=True, capture_output=True)
    if current.returncode:
        try:
            missing = str(json.loads(current.stdout).get("status")) == "404"
        except (ValueError, AttributeError):
            missing = False
        if not missing:
            raise ValueError("Could not read Pages settings; check GitHub access before publishing.")
        settings = None
    else:
        settings = json.loads(current.stdout)
        if settings.get("source") != {"branch": "gh-pages", "path": "/"} or settings.get("cname"):
            raise ValueError("Existing Pages configuration differs; review it before changing hosting.")

    branch_exists = bool(run("git", "ls-remote", "--heads", REMOTE, "refs/heads/gh-pages"))
    with tempfile.TemporaryDirectory(prefix="read-assist-policy-") as directory:
        checkout = Path(directory)
        if branch_exists:
            run("git", "clone", "--quiet", "--single-branch", "--branch", "gh-pages", REMOTE, directory)
        else:
            run("git", "init", "--quiet", "--initial-branch=gh-pages", directory)
            run("git", "remote", "add", "origin", REMOTE, cwd=checkout)
        for key in ("user.name", "user.email"):
            value = run("git", "config", "--get", key)
            if not value:
                raise ValueError(f"Configure {key} before committing the public page.")
            run("git", "config", key, value, cwd=checkout)
        index = checkout / "index.html"
        if index.exists() and index.read_bytes() != INDEX:
            raise ValueError("Existing site homepage differs; refusing to overwrite unrelated content.")
        index.write_bytes(INDEX)
        (checkout / "privacy.html").write_bytes(policy)
        (checkout / ".nojekyll").touch()
        run("git", "add", "--", "privacy.html", "index.html", ".nojekyll", cwd=checkout)
        if run("git", "status", "--porcelain", cwd=checkout):
            run("git", "commit", "-m", "Publish Read Assist privacy policy", cwd=checkout)
            run("git", "push", "origin", "HEAD:gh-pages", cwd=checkout)
    if settings is None:
        payload = {"build_type": "legacy", "source": {"branch": "gh-pages", "path": "/"}}
        settings = json.loads(run("gh", "api", "--method", "POST", ENDPOINT, "--input", "-", input=json.dumps(payload)))
    if not settings.get("https_enforced"):
        run("gh", "api", "--silent", "--method", "PUT", ENDPOINT, "--input", "-", input=json.dumps({"https_enforced": True}))
    print(f"Policy pushed and Pages configured. Deployment is not yet verified: {URL}")
    print(f"After deployment, run: python3 scripts/verify_privacy_page.py --url {URL}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        if isinstance(error, subprocess.CalledProcessError):
            sys.exit(error.stderr or str(error))
        sys.exit(str(error))
