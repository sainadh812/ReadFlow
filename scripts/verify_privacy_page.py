#!/usr/bin/env python3
"""Reject unfinished policy pages; optionally verify the exact published HTML."""
import argparse
import hashlib
from html.parser import HTMLParser
from pathlib import Path
import re
import sys
from urllib.parse import urlparse
from urllib.request import urlopen


class PolicyPage(HTMLParser):
    def __init__(self):
        super().__init__()
        self.attributes = []
        self.text = []

    def handle_starttag(self, tag, attrs):
        self.attributes.append((tag, dict(attrs)))

    def handle_data(self, data):
        self.text.append(data)


def verify(data):
    source = data.decode("utf-8")
    if re.search(r"\[[A-Z][A-Z_]+\]", source):
        raise ValueError("Owner details are incomplete: replace all bracketed placeholders.")
    page = PolicyPage()
    page.feed(source)
    if not any(tag == "html" and attrs.get("data-publication-status") == "published"
               for tag, attrs in page.attributes):
        raise ValueError("The policy is still a draft; finalize the text before publishing.")
    if any(attrs.get("class") == "draft" for _, attrs in page.attributes):
        raise ValueError("Remove the draft notice after completing owner details.")
    visible = " ".join(page.text)
    for required in ("Read Assist", "Privacy Policy", "app.readflow", "Google ML Kit", "GitHub Pages"):
        if required not in visible:
            raise ValueError(f"Missing policy information: {required}")
    emails = [attrs.get("href", "")[7:] for tag, attrs in page.attributes
              if tag == "a" and attrs.get("href", "").startswith("mailto:")]
    if not emails or any(not re.fullmatch(r"[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+", email)
                         or email.rsplit("@", 1)[-1].lower() in {"example.com", "example.org", "example.net"}
                         for email in emails):
        raise ValueError("Provide the owner's real public contact email.")
    for tag, attrs in page.attributes:
        if tag in {"script", "iframe", "form"} or tag == "link" and attrs.get("rel") == "stylesheet":
            raise ValueError("This static privacy page must not load scripts, forms or external styles.")
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, default=Path(__file__).resolve().parents[1] / "site/privacy.html")
    parser.add_argument("--url", help="Verify that this public HTTPS URL serves the exact finalized page.")
    args = parser.parse_args()
    data = args.file.read_bytes()
    digest = verify(data)
    if args.url:
        parsed = urlparse(args.url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("Verification requires a public HTTPS URL without credentials.")
        with urlopen(args.url, timeout=30) as response:
            if response.status != 200 or urlparse(response.url).scheme != "https":
                raise ValueError("Policy URL must return HTTP 200 over HTTPS.")
            if response.headers.get_content_type() != "text/html":
                raise ValueError("Policy URL must serve HTML.")
            live = response.read(2_000_001)
        if live != data:
            raise ValueError("Live policy differs from the finalized local HTML.")
        verify(live)
        print(f"Live policy verified: {args.url}")
    print(f"Policy HTML verified; SHA-256 {digest}. Owner facts and legal accuracy require separate review.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        sys.exit(str(error))
